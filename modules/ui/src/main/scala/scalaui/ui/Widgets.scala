package scalaui.ui

import scalaui.color.Color
import scalaui.geom.*
import scalaui.window.{Key, Platform}

/** A table column; `width` is the initial width (users resize by dragging the header border). */
final case class Column(title: String, width: Double = 120, minWidth: Double = 40, sortable: Boolean = true)

/** Table state the caller keeps: selected row, sort order and column widths. */
final class TableState(columns: Seq[Column]):
  var selected: Option[Int] = None
  var sortColumn: Option[Int] = None
  var ascending = true
  val widths: Array[Double] = columns.map(_.width).toArray

  /** Row indices ordered by the current sort column; `key(row, column)` extracts the sort key. */
  def order[K](rows: Int)(key: (Int, Int) => K)(using ord: Ordering[K]): Vector[Int] =
    val base = Vector.range(0, rows)
    sortColumn match
      case None => base
      case Some(c) =>
        val sorted = base.sortBy(r => key(r, c))(using ord)
        if ascending then sorted else sorted.reverse

/** One data series of a [[Ui.plot]]. */
final case class PlotSeries(name: String, points: Seq[(Double, Double)], color: Color, kind: PlotKind = PlotKind.Line)
enum PlotKind:
  case Line, Points, Bars

/** Pan/zoom state of a plot; `None` means auto-fit to the data. */
final class PlotState:
  var view: Option[Rect] = None

extension (ui: Ui)

  // --- table ---

  /** A scrollable table with a header, resizable and sortable columns, striped rows and keyboard selection. Only visible
    * rows are asked for cells. The caller supplies row order (see [[TableState.order]]) by mapping row indices itself.
    */
  def table(state: TableState, columns: Seq[Column], rows: Int, height: Double, rowHeight: Double = 24)(cell: (Int, Int, Ui) => Unit): Response =
    val id = ui.nextId()
    val headerH = rowHeight
    val w = math.max(ui.availableWidth, 100.0)
    val outer = ui.allocate(w, height)
    val header = Rect(outer.x0, outer.y0, outer.x1, outer.y0 + headerH)
    val body = Rect(outer.x0, header.y1, outer.x1, outer.y1)
    ui.ctx.canvas.fillRoundedRect(outer, ui.style.rounding, ui.style.inputBackground)
    ui.ctx.canvas.strokeRect(outer, 1, ui.style.border)

    // header: titles, sort arrows, resize handles
    ui.ctx.canvas.fillRect(header, ui.style.faint)
    var x = outer.x0
    columns.indices.foreach { c =>
      val col = columns(c)
      val cr = Rect(x, header.y0, x + state.widths(c), header.y1)
      val hid = id + 100 + c
      val r = ui.ctx.interact(cr.inflate(-3, 0).copy(x1 = cr.x1 - 6), hid)
      if r.clicked && col.sortable then
        if state.sortColumn.contains(c) then state.ascending = !state.ascending else { state.sortColumn = Some(c); state.ascending = true }
      if r.hovered then ui.ctx.canvas.fillRect(cr, ui.style.widgetHover)
      val l = ui.ctx.layout(col.title, ui.style.fontSize)
      ui.ctx.pushClip(cr.inflate(-4, 0))
      ui.ctx.paintText(l, Point(cr.x0 + 6, cr.y0 + (headerH - l.height) / 2), ui.style.textStrong)
      ui.ctx.popClip()
      if state.sortColumn.contains(c) then
        val ax = cr.x1 - 14; val ay = header.center.y
        val up = state.ascending
        ui.ctx.canvas.fillConvex(if up then Seq(Point(ax - 4, ay + 2), Point(ax + 4, ay + 2), Point(ax, ay - 3)) else Seq(Point(ax - 4, ay - 2), Point(ax + 4, ay - 2), Point(ax, ay + 3)), ui.style.text)
      // resize handle on the right border
      val handle = Rect(cr.x1 - 4, cr.y0, cr.x1 + 4, cr.y1)
      val hr = ui.ctx.interact(handle, id + 200 + c, drag = true)
      if hr.dragged then state.widths(c) = math.max(col.minWidth, state.widths(c) + hr.dragDelta.x)
      ui.ctx.canvas.fillRect(Rect(cr.x1 - 1, cr.y0 + 3, cr.x1, cr.y1 - 3), if hr.hovered || hr.pressed then ui.style.borderHover else ui.style.border)
      x += state.widths(c)
    }
    ui.ctx.canvas.fillRect(Rect(outer.x0, header.y1 - 1, outer.x1, header.y1), ui.style.border)

    // body: virtualised rows
    val contentH = rows * rowHeight
    val maxScroll = math.max(0.0, contentH - body.height)
    var off = ui.ctx.numbers.getOrElse(id + 1, 0.0).max(0).min(maxScroll)
    val br = ui.ctx.interact(body, id)
    ui.ctx.registerFocus(id)
    if br.hovered then off = (off - ui.ctx.input.scroll.y).max(0).min(maxScroll)
    // keyboard navigation while focused
    if ui.ctx.focusId == id then
      ui.ctx.input.keys.foreach { k =>
        val cur = state.selected.getOrElse(-1)
        k.key match
          case Key.Down => state.selected = Some((cur + 1).min(rows - 1).max(0))
          case Key.Up => state.selected = Some((cur - 1).max(0).min(rows - 1))
          case Key.Home => state.selected = Some(0)
          case Key.End => state.selected = Some(rows - 1)
          case _ => ()
        state.selected.foreach { s =>
          val top = s * rowHeight; val bot = top + rowHeight
          if top < off then off = top else if bot > off + body.height then off = bot - body.height
        }
      }
    ui.ctx.numbers(id + 1) = off
    ui.ctx.pushClip(body)
    val first = math.max(0, (off / rowHeight).toInt)
    val last = math.min(rows - 1, ((off + body.height) / rowHeight).toInt)
    var clickedRow = -1
    var row = first
    while row <= last do
      val ry = body.y0 + row * rowHeight - off
      val rr = Rect(body.x0, ry, body.x1 - (if maxScroll > 0 then 8 else 0), ry + rowHeight)
      val sel = state.selected.contains(row)
      val hov = ui.ctx.hoverable(rr.intersect(body), id + 1000 + row)
      if sel then ui.ctx.canvas.fillRect(rr, ui.style.checked)
      else if hov then ui.ctx.canvas.fillRect(rr, ui.style.widgetHover.withAlpha(0.5f))
      else if row % 2 == 1 then ui.ctx.canvas.fillRect(rr, ui.style.faint)
      if hov && ui.ctx.input.pressed then clickedRow = row
      var cx = body.x0
      columns.indices.foreach { c =>
        val cr = Rect(cx, ry, cx + state.widths(c), ry + rowHeight)
        ui.ctx.pushClip(cr)
        val cu = ui.child(cr.inflate(-6, -1), false)
        cell(row, c, cu)
        ui.ctx.popClip()
        cx += state.widths(c)
      }
      row += 1
    if clickedRow >= 0 then { state.selected = Some(clickedRow); ui.ctx.focusId = id; ui.ctx.focusClaimed = true }
    ui.ctx.popClip()
    if maxScroll > 0 then
      val thumbH = math.max(20, body.height * body.height / contentH)
      val ty = body.y0 + (off / maxScroll) * (body.height - thumbH)
      ui.ctx.canvas.fillRoundedRect(Rect(body.x1 - 7, ty, body.x1 - 2, ty + thumbH), 2.5, ui.style.widgetHover)
    if ui.ctx.focusId == id then ui.ctx.canvas.strokeRect(outer, 1, ui.style.accentBright)
    br.copy(rect = outer, changed = clickedRow >= 0)

  // --- tree ---

  /** A tree node: a disclosure triangle (unless `leaf`), a selectable label and, when open, `children` indented below. */
  def treeNode(label: String, leaf: Boolean = false, selected: Boolean = false, defaultOpen: Boolean = false)(children: Ui => Unit = _ => ()): Response =
    val id = ui.nextId()
    val open = !leaf && ui.ctx.flags.getOrElse(id, defaultOpen)
    val l = ui.ctx.layout(label, ui.style.fontSize)
    val indentW = 16.0
    val rect = ui.allocate(math.max(ui.availableWidth, l.width + indentW + 8), math.max(l.height + 2, ui.style.minHeight))
    val r = ui.ctx.interact(rect, id, focusable = true)
    val arrow = Rect(rect.x0, rect.y0, rect.x0 + indentW, rect.y1)
    if r.clicked then
      val onArrow = ui.ctx.input.mouse.exists(arrow.contains)
      val byKey = !ui.ctx.input.released
      if !leaf && (onArrow || r.doubleClicked || byKey) then ui.ctx.flags(id) = !open
    if r.focused && !r.clicked then
      // keyboard: Right expands, Left collapses, Up/Down move to the neighbouring row
      if !leaf && !open && ui.ctx.takeKey(Key.Right) then ui.ctx.flags(id) = true
      else if !leaf && open && ui.ctx.takeKey(Key.Left) then ui.ctx.flags(id) = false
      else if ui.ctx.takeKey(Key.Down) then ui.ctx.pendingFocusMove = 1
      else if ui.ctx.takeKey(Key.Up) then ui.ctx.pendingFocusMove = -1
    if selected then ui.ctx.canvas.fillRoundedRect(rect, ui.style.rounding - 1, ui.style.checked)
    else if r.hovered then ui.ctx.canvas.fillRoundedRect(rect, ui.style.rounding - 1, ui.style.widgetHover.withAlpha(0.6f))
    val cy = rect.center.y; val cx = rect.x0 + indentW / 2
    if !leaf then
      val col = if r.hovered then ui.style.textStrong else ui.style.textWeak
      ui.ctx.paintIcon(if open then Icons.chevronDown else Icons.chevronRight, Rect(cx - 7, cy - 7, cx + 7, cy + 7), col)
    ui.ctx.paintText(l, Point(rect.x0 + indentW + 2, rect.y0 + (rect.height - l.height) / 2), if selected || r.hovered then ui.style.textStrong else ui.style.text)
    if open then
      val top = ui.cursorPoint.y
      ui.indent(children)
      val guideX = rect.x0 + indentW / 2 + 0.5
      ui.ctx.canvas.fillRect(Rect(guideX, top - 2, guideX + 1, ui.cursorPoint.y - 4), ui.style.border)
    r

  // --- plot ---

  /** A 2-D plot of line, point or bar series with axes, grid, legend, hover read-out, wheel zoom and drag pan
    * (double-click resets). `state` keeps the view between frames.
    */
  def plot(state: PlotState, size: Size, series: Seq[PlotSeries], xLabel: String = "", yLabel: String = ""): Response =
    val id = ui.nextId()
    val rect = ui.allocate(size.width, size.height)
    val r = ui.ctx.interact(rect, id, drag = true)
    ui.ctx.canvas.fillRoundedRect(rect, ui.style.rounding, ui.style.inputBackground)
    ui.ctx.canvas.strokeRect(rect, 1, ui.style.border)
    val pts = series.flatMap(_.points).filter((x, y) => x.isFinite && y.isFinite)
    if pts.isEmpty then return r
    val auto =
      val xs = pts.map(_._1); val ys = pts.map(_._2)
      var x0 = xs.min; var x1 = xs.max; var y0 = ys.min; var y1 = ys.max
      if series.exists(_.kind == PlotKind.Bars) then y0 = math.min(y0, 0.0)
      if x1 - x0 < 1e-12 then { x0 -= 1; x1 += 1 }
      if y1 - y0 < 1e-12 then { y0 -= 1; y1 += 1 }
      val px = (x1 - x0) * 0.04; val py = (y1 - y0) * 0.06
      Rect(x0 - px, y0 - py, x1 + px, y1 + py)
    var view = state.view.getOrElse(auto)
    val marginL = 46.0; val marginB = if xLabel.nonEmpty then 38.0 else 24.0; val marginT = 8.0; val marginR = 10.0
    val area = Rect(rect.x0 + marginL, rect.y0 + marginT, rect.x1 - marginR, rect.y1 - marginB)
    def sx(x: Double) = area.x0 + (x - view.x0) / (view.x1 - view.x0) * area.width
    def sy(y: Double) = area.y1 - (y - view.y0) / (view.y1 - view.y0) * area.height
    // interaction
    if r.doubleClicked then { state.view = None; view = auto }
    else
      if r.dragged && area.width > 0 then
        val dx = -r.dragDelta.x / area.width * (view.x1 - view.x0); val dy = r.dragDelta.y / area.height * (view.y1 - view.y0)
        view = Rect(view.x0 + dx, view.y0 + dy, view.x1 + dx, view.y1 + dy); state.view = Some(view)
      if r.hovered && r.scroll.y != 0 && area.contains(ui.ctx.input.mouse.getOrElse(Point.Zero)) then
        val m = ui.ctx.input.mouse.get
        val fx = view.x0 + (m.x - area.x0) / area.width * (view.x1 - view.x0)
        val fy = view.y0 + (area.y1 - m.y) / area.height * (view.y1 - view.y0)
        val k = math.pow(0.9, r.scroll.y / 4.0)
        view = Rect(fx - (fx - view.x0) * k, fy - (fy - view.y0) * k, fx + (view.x1 - fx) * k, fy + (view.y1 - fy) * k); state.view = Some(view)
    // grid and ticks
    def niceTicks(lo: Double, hi: Double, target: Int): Seq[Double] =
      val span = hi - lo
      val raw = span / target
      val mag = math.pow(10, math.floor(math.log10(raw)))
      val step = Seq(1.0, 2.0, 5.0, 10.0).map(_ * mag).find(_ >= raw).getOrElse(10 * mag)
      val first = math.ceil(lo / step) * step
      Iterator.iterate(first)(_ + step).takeWhile(_ <= hi + step * 1e-9).toVector
    def fmt(v: Double, step: Double): String =
      if math.abs(v) < step * 1e-6 then "0" else if step >= 1 then f"$v%.0f" else if step >= 0.1 then f"$v%.1f" else if step >= 0.01 then f"$v%.2f" else f"$v%.3g"
    val xt = niceTicks(view.x0, view.x1, math.max(2, (area.width / 70).toInt))
    val yt = niceTicks(view.y0, view.y1, math.max(2, (area.height / 40).toInt))
    val xStep = if xt.size > 1 then xt(1) - xt(0) else 1.0; val yStep = if yt.size > 1 then yt(1) - yt(0) else 1.0
    ui.ctx.pushClip(rect)
    xt.foreach { v =>
      val x = sx(v)
      ui.ctx.canvas.fillRect(Rect(x, area.y0, x + 1, area.y1), ui.style.border.withAlpha(0.5f))
      val l = ui.ctx.layout(fmt(v, xStep), ui.style.fontSize - 3)
      ui.ctx.paintText(l, Point(x - l.width / 2, area.y1 + 4), ui.style.textWeak)
    }
    yt.foreach { v =>
      val y = sy(v)
      ui.ctx.canvas.fillRect(Rect(area.x0, y, area.x1, y + 1), ui.style.border.withAlpha(0.5f))
      val l = ui.ctx.layout(fmt(v, yStep), ui.style.fontSize - 3)
      ui.ctx.paintText(l, Point(area.x0 - l.width - 5, y - l.height / 2), ui.style.textWeak)
    }
    ui.ctx.canvas.strokeRect(area, 1, ui.style.border)
    ui.ctx.popClip()
    // series, clipped to the plot area
    ui.ctx.pushClip(area)
    series.foreach { s =>
      val sp = s.points.filter((x, y) => x.isFinite && y.isFinite)
      s.kind match
        case PlotKind.Line => sp.sliding(2).foreach { case Seq((ax, ay), (bx, by)) => ui.ctx.canvas.strokeLine(Point(sx(ax), sy(ay)), Point(sx(bx), sy(by)), 1.8, s.color); case _ => () }
        case PlotKind.Points => sp.foreach((x, y) => ui.ctx.canvas.fillCircle(Point(sx(x), sy(y)), 3, s.color))
        case PlotKind.Bars =>
          val step = if sp.size > 1 then sp.map(_._1).sorted.sliding(2).map(p => p(1) - p(0)).filter(_ > 0).minOption.getOrElse(1.0) else 1.0
          val bw = math.max(2.0, step / (view.x1 - view.x0) * area.width * 0.7)
          sp.foreach { (x, y) => val x0 = sx(x) - bw / 2; ui.ctx.canvas.fillRect(Rect(x0, math.min(sy(y), sy(0)), x0 + bw, math.max(sy(y), sy(0))), s.color) }
    }
    // hover read-out: nearest point of any series
    ui.ctx.input.mouse.filter(area.contains).foreach { m =>
      val near = series.flatMap(s => s.points.map(p => (s, p))).filter((_, p) => p._1.isFinite && p._2.isFinite)
        .minByOption((_, p) => (sx(p._1) - m.x) * (sx(p._1) - m.x) + (sy(p._2) - m.y) * (sy(p._2) - m.y))
      near.foreach { (s, p) =>
        if math.hypot(sx(p._1) - m.x, sy(p._2) - m.y) < 14 && r.hovered then
          ui.ctx.canvas.strokeCircle(Point(sx(p._1), sy(p._2)), 5, 1.5, ui.style.textStrong)
          ui.tooltip(r, f"${s.name}: ${p._1}%.4g, ${p._2}%.4g")
      }
    }
    ui.ctx.popClip()
    // axis labels and legend
    if xLabel.nonEmpty then { val l = ui.ctx.layout(xLabel, ui.style.fontSize - 2); ui.ctx.paintText(l, Point(area.center.x - l.width / 2, rect.y1 - l.height - 1), ui.style.textWeak) }
    if yLabel.nonEmpty then { val l = ui.ctx.layout(yLabel, ui.style.fontSize - 2); ui.ctx.paintText(l, Point(rect.x0 + 4, rect.y0 + 1), ui.style.textWeak) }
    var lx = area.x1 - 6
    series.filter(_.name.nonEmpty).reverse.foreach { s =>
      val l = ui.ctx.layout(s.name, ui.style.fontSize - 2)
      lx -= l.width
      ui.ctx.paintText(l, Point(lx, area.y0 + 4), ui.style.text)
      lx -= 14
      ui.ctx.canvas.fillRect(Rect(lx, area.y0 + 4 + l.height / 2 - 2, lx + 10, area.y0 + 4 + l.height / 2 + 2), s.color)
      lx -= 10
    }
    r

  // --- drag and drop between widgets ---

  /** Makes `rect` a drag source. While dragged (past a small threshold) `payload` is available to [[dropTarget]]s and a
    * ghost of `ghost` text follows the pointer. Returns the interaction state of the rect.
    */
  def draggable(rect: Rect, payload: Any, ghost: String = ""): Response =
    val id = ui.nextId()
    val r = ui.ctx.interact(rect, id, drag = true)
    if r.pressed then
      if ui.ctx.input.pressed then ui.ctx.input.mouse.foreach { m => ui.ctx.numbers(id) = m.x; ui.ctx.numbers(id + 1) = m.y }
      val start = Point(ui.ctx.numbers.getOrElse(id, 0.0), ui.ctx.numbers.getOrElse(id + 1, 0.0))
      val travelled = ui.ctx.input.mouse.exists(_.distance(start) > 4)
      if travelled || ui.ctx.dragSourceId == id then
        ui.ctx.dragPayload = Some(payload); ui.ctx.dragSourceId = id
        if ghost.nonEmpty then ui.ctx.input.mouse.foreach { m =>
          ui.ctx.defer { () =>
            val l = ui.ctx.layout(ghost, ui.style.fontSize)
            val rc = Rect(m.x + 10, m.y + 10, m.x + 22 + l.width, m.y + 18 + l.height)
            ui.ctx.canvas.fillRoundedRect(rc, 3, ui.style.panel.withAlpha(0.92f)); ui.ctx.canvas.strokeRect(rc, 1, ui.style.accentBright)
            ui.ctx.paintText(l, Point(rc.x0 + 6, rc.y0 + 4), ui.style.textStrong)
          }
        }
    r

  /** A drop zone: highlights while an acceptable payload hovers it and returns the payload on the frame it is released over it. */
  def dropTarget(rect: Rect)(accept: Any => Boolean = _ => true): Option[Any] =
    ui.ctx.dragPayload.filter(accept).flatMap { p =>
      val over = ui.ctx.input.mouse.exists(rect.contains)
      if over then ui.ctx.canvas.strokeRect(rect.inflate(1, 1), 2, ui.style.accentBright)
      if over && ui.ctx.input.released then Some(p) else None
    }

  // --- small controls ---

  def toggleSwitch(value: Var[Boolean], label: String = ""): Response =
    val id = ui.nextId()
    val l = ui.ctx.layout(label, ui.style.fontSize)
    val sw = Size(34, 18)
    val rect = ui.allocate(sw.width + (if label.isEmpty then 0 else 8 + l.width), math.max(sw.height, l.height))
    var r = ui.ctx.interact(rect, id, focusable = true)
    if r.clicked then { value.value = !value.value; r = r.copy(changed = true) }
    ui.ctx.access(scalaui.window.AccessRole.Switch, label, rect, checked = Some(value.value))
    val t = ui.ctx.animate(id, if value.value then 1.0 else 0.0, 0.1)
    val track = Rect(rect.x0, rect.center.y - sw.height / 2, rect.x0 + sw.width, rect.center.y + sw.height / 2)
    val h = ui.ctx.hoverAmount(id, r.hovered)
    val off = Style.mix(ui.style.borderStrong, ui.style.borderHover, h)
    ui.ctx.canvas.fillRoundedRect(track, sw.height / 2, Style.mix(off, ui.style.accent, t))
    val knob = if t > 0.5 then ui.style.onAccent else (if ui.style.dark then Color.rgb8(232, 232, 236) else Color.White)
    ui.ctx.canvas.fillCircle(Point(track.x0 + 9 + t * (sw.width - 18), track.center.y), 6.5, knob)
    if label.nonEmpty then ui.ctx.paintText(l, Point(track.x1 + 8, rect.y0 + (rect.height - l.height) / 2), ui.style.text)
    r

  /** An indeterminate activity spinner. */
  def spinner(diameter: Double = 18): Unit =
    val rect = ui.allocate(diameter, diameter)
    val c = rect.center; val rad = diameter / 2 - 2
    val t = ui.ctx.time * 1.6
    (0 until 8).foreach { i =>
      val a = 2 * math.Pi * i / 8
      val fade = ((i / 8.0 - t % 1.0) % 1.0 + 1.0) % 1.0
      ui.ctx.canvas.fillCircle(Point(c.x + rad * math.cos(a), c.y + rad * math.sin(a)), 1.7, ui.style.text.withAlpha((0.2 + 0.8 * (1 - fade)).toFloat))
    }
    ui.ctx.requestRepaint()

  /** A text link that opens `url` in the default browser when clicked. */
  def hyperlink(text: String, url: String): Response =
    val l = ui.ctx.layout(text, ui.style.fontSize)
    val rect = ui.allocate(l.width, l.height)
    val r = ui.ctx.interact(rect, ui.nextId(), focusable = true)
    if r.hovered then ui.ctx.cursorIcon = scalaui.window.CursorIcon.Pointer
    val col = if r.hovered then Style.mix(ui.style.accentBright, ui.style.textStrong, 0.3) else ui.style.accentBright
    ui.ctx.paintText(l, rect.origin, col)
    ui.ctx.canvas.fillRect(Rect(rect.x0, rect.y1 - 1, rect.x1, rect.y1), col)
    ui.ctx.access(scalaui.window.AccessRole.Link, text, rect, value = url)
    if r.clicked then Platform.openUrl(url)
    r

  /** A label that highlights when selected or hovered and reports clicks. */
  def selectableLabel(selected: Boolean, text: String): Response =
    val l = ui.ctx.layout(text, ui.style.fontSize)
    val rect = ui.allocate(math.max(ui.availableWidth, l.width + 12), math.max(l.height + 2, ui.style.minHeight))
    val r = ui.ctx.interact(rect, ui.nextId(), focusable = true)
    ui.ctx.access(scalaui.window.AccessRole.ListItem, text, rect, checked = Some(selected))
    if selected then ui.ctx.canvas.fillRoundedRect(rect, ui.style.rounding - 1, ui.style.checked)
    else if r.hovered then ui.ctx.canvas.fillRoundedRect(rect, ui.style.rounding - 1, ui.style.widgetHover)
    ui.ctx.paintText(l, Point(rect.x0 + 8, rect.y0 + (rect.height - l.height) / 2), if selected || r.hovered then ui.style.textStrong else ui.style.text)
    r

  /** A horizontal rule with a centered caption. */
  def separatorLabel(text: String): Unit =
    val l = ui.ctx.layout(text, ui.style.fontSize - 1)
    val rect = ui.allocate(ui.availableWidth, l.height + 4)
    val y = rect.center.y
    val gap = 8.0
    val x0 = rect.x0 + (rect.width - l.width) / 2
    ui.ctx.canvas.fillRect(Rect(rect.x0, y, x0 - gap, y + 1), ui.style.border)
    ui.ctx.canvas.fillRect(Rect(x0 + l.width + gap, y, rect.x1, y + 1), ui.style.border)
    ui.ctx.paintText(l, Point(x0, rect.y0 + 2), ui.style.textWeak)
