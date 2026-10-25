package scalaui.ui

import scalaui.color.Color
import scalaui.geom.*
import scalaui.window.{AccessRole, CursorIcon, Key}

/** A calendar date (proleptic Gregorian). */
final case class Date(year: Int, month: Int, day: Int) extends Ordered[Date]:
  def compare(o: Date): Int = Ordering[(Int, Int, Int)].compare((year, month, day), (o.year, o.month, o.day))
  def daysInMonth: Int = Date.daysInMonth(year, month)
  /** 0 = Monday ... 6 = Sunday. */
  def dayOfWeek: Int = Math.floorMod(toDays + 3, 7L).toInt
  /** Days since 1970-01-01. */
  def toDays: Long =
    val y = if month <= 2 then year - 1L else year.toLong
    val era = Math.floorDiv(y, 400L)
    val yoe = y - era * 400
    val doy = (153 * (month + (if month > 2 then -3 else 9)) + 2) / 5 + day - 1
    val doe = yoe * 365 + yoe / 4 - yoe / 100 + doy
    era * 146097 + doe - 719468
  def plusDays(n: Long): Date = Date.fromDays(toDays + n)
  /** Moves by whole months, clamping the day to the target month's length. */
  def plusMonths(n: Int): Date =
    val idx = year * 12 + (month - 1) + n
    val y = Math.floorDiv(idx, 12); val m = Math.floorMod(idx, 12) + 1
    Date(y, m, day.min(Date.daysInMonth(y, m)))
  override def toString: String = f"$year%04d-$month%02d-$day%02d"

object Date:
  def isLeap(y: Int): Boolean = (y % 4 == 0 && y % 100 != 0) || y % 400 == 0
  def daysInMonth(y: Int, m: Int): Int = m match
    case 2 => if isLeap(y) then 29 else 28
    case 4 | 6 | 9 | 11 => 30
    case _ => 31
  def fromDays(days: Long): Date =
    val z = days + 719468
    val era = Math.floorDiv(z, 146097L)
    val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
    val mp = (5 * doy + 2) / 153
    val d = (doy - (153 * mp + 2) / 5 + 1).toInt
    val m = (if mp < 10 then mp + 3 else mp - 9).toInt
    Date((yoe + era * 400 + (if m <= 2 then 1 else 0)).toInt, m, d)
  /** Today's date in UTC. */
  def today: Date = fromDays(Math.floorDiv(System.currentTimeMillis(), 86400000L))
  val monthNames: Vector[String] = Vector("January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December")

/** State of a [[Ui.virtualList]]: scroll offset, selection and the rows built last frame. */
final class VirtualListState:
  var scroll = 0.0
  var selected: Option[Int] = None
  /** Row indices whose content was built in the latest frame. */
  var visible: Range = 0 until 0
  private[ui] var revealRow = -1
  private[ui] var revealTop = false
  /** Scrolls so that `row` is visible on the next frame; with `alignTop` the row is put at the top of the viewport. */
  def scrollTo(row: Int, alignTop: Boolean = false): Unit = { revealRow = row; revealTop = alignTop }
  // variable-height lists ([[Ui.variableList]], [[Ui.measuredList]])
  private[ui] val heights = new RowHeights
  private[ui] var anchorRow = -1
  private[ui] var anchorIntra = 0.0
  private[ui] var lastScroll = 0.0
  private[ui] var dirtyPrev = false
  /** Forgets all measured/computed row heights of a variable-height list (call when row contents change size). */
  def invalidateHeights(): Unit = { heights.clear(); anchorRow = -1 }
  /** How many rows of a variable-height list have a known height (the rest use the estimate). */
  def knownHeights: Int = heights.knownCount

object AdvancedImpl:
  def popupAt(ui: Ui, id: Long, pos: Point, minWidth: Double)(body: Ui => Unit): Unit =
    val ctx = ui.ctx
    if ctx.popupOwner == id then
      ctx.defer { () =>
        if ctx.popupOwner == id then
          ctx.focusTrap = true
          val last = ctx.lastSizes.getOrElse(id, Size(minWidth, 10))
          val w = math.max(minWidth, last.width) + 8; val h = last.height + 8
          val x = math.max(4, math.min(pos.x, ctx.canvas.width - w - 4))
          val y = if pos.y + h > ctx.canvas.height - 4 then math.max(4, pos.y - h) else pos.y
          val pop = Rect(x, y, x + w, y + h)
          ui.popupFrame(pop)
          val c = new Ui(ctx, Rect(pop.x0 + 4, pop.y0 + 4, pop.x1 - 4, pop.y1 + 1000), true, id)
          body(c)
          val u = c.usedRect
          ctx.lastSizes(id) = Size(math.max(u.width, minWidth), u.height)
          if ctx.popupOwner == id then
            if (ctx.input.pressed || ctx.input.secondaryPressed) && !ctx.input.mouse.exists(pop.contains) then ctx.popupOwner = 0L
            if ctx.input.keys.exists(_.key == Key.Escape) then ctx.popupOwner = 0L
            if ctx.takeKey(Key.Down) then ctx.pendingFocusMove = 1 else if ctx.takeKey(Key.Up) then ctx.pendingFocusMove = -1
      }

  def split(ui: Ui, fraction: Var[Double], side: Boolean, size: Double, minFirst: Double, minSecond: Double)(first: Ui => Unit)(second: Ui => Unit): Response =
    val ctx = ui.ctx; val st = ui.style
    val id = ui.nextId()
    val thick = 8.0
    val rect = ui.allocate(ui.availableWidth, if size > 0 then size else math.max(40, ui.availableHeight))
    val span = if side then rect.width else rect.height
    val avail = math.max(1.0, span - thick)
    def clampPos(p: Double) = if avail < minFirst + minSecond then avail / 2 else p.max(minFirst).min(avail - minSecond)
    var pos = clampPos(fraction.value * avail)
    def handleAt(p: Double) = if side then Rect(rect.x0 + p, rect.y0, rect.x0 + p + thick, rect.y1) else Rect(rect.x0, rect.y0 + p, rect.x1, rect.y0 + p + thick)
    val r = ctx.interact(handleAt(pos), id, drag = true, focusable = true)
    var changed = false
    if r.hovered || r.pressed then ctx.cursorIcon = if side then CursorIcon.ResizeHorizontal else CursorIcon.ResizeVertical
    if r.dragged then ctx.input.mouse.foreach { m =>
      val np = clampPos((if side then m.x - rect.x0 else m.y - rect.y0) - thick / 2)
      if np != pos then { pos = np; fraction.value = pos / avail; changed = true }
    }
    if r.focused then
      val (dec, inc) = if side then (Key.Left, Key.Right) else (Key.Up, Key.Down)
      val step = 0.02
      if ctx.takeKey(dec) then { fraction.value = ((pos / avail) - step).max(0); changed = true }
      else if ctx.takeKey(inc) then { fraction.value = ((pos / avail) + step).min(1); changed = true }
      if changed then pos = clampPos(fraction.value * avail)
    val h = handleAt(pos)
    val hot = ctx.hoverAmount(id, r.hovered || r.pressed)
    val line = Style.mix(st.border, st.accent, hot)
    val c = h.center
    if side then ctx.canvas.fillRect(Rect(c.x - 0.5, h.y0, c.x + 0.5, h.y1), line) else ctx.canvas.fillRect(Rect(h.x0, c.y - 0.5, h.x1, c.y + 0.5), line)
    for k <- -1 to 1 do
      val p = if side then Point(c.x, c.y + k * 6) else Point(c.x + k * 6, c.y)
      ctx.canvas.fillCircle(p, 1.3, Style.mix(st.textWeak, st.accent, hot).withAlpha((0.5 + 0.5 * hot).toFloat))
    val (a, b) =
      if side then (Rect(rect.x0, rect.y0, rect.x0 + pos, rect.y1), Rect(rect.x0 + pos + thick, rect.y0, rect.x1, rect.y1))
      else (Rect(rect.x0, rect.y0, rect.x1, rect.y0 + pos), Rect(rect.x0, rect.y0 + pos + thick, rect.x1, rect.y1))
    ctx.pushClip(a); first(new Ui(ctx, a, true, id + 1)); ctx.popClip()
    ctx.pushClip(b); second(new Ui(ctx, b, true, id + 2)); ctx.popClip()
    Response(rect, hovered = r.hovered, dragged = r.dragged, changed = changed, focused = r.focused)

  def starPoints(c: Point, outer: Double): Seq[Point] =
    (0 until 10).map { i =>
      val a = -math.Pi / 2 + i * math.Pi / 5
      val rr = if i % 2 == 0 then outer else outer * 0.46
      Point(c.x + rr * math.cos(a), c.y + rr * math.sin(a))
    }

extension (ui: Ui)

  // --- virtual list ---

  /** A scrollable list of `count` rows of equal `rowHeight` for large data: only the rows in view are built (`row(index, ui)` lays
    * out one row's content). Rows are selectable by click and by keyboard (Up/Down/PageUp/PageDown/Home/End) when focused;
    * Enter or a double click report `doubleClicked` on the response, a selection change reports `changed`.
    */
  def virtualList(state: VirtualListState, count: Int, rowHeight: Double, height: Double, selectable: Boolean = true)(row: (Int, Ui) => Unit): Response =
    val ctx = ui.ctx; val st = ui.style
    val id = ui.nextId()
    val outer = ui.allocate(math.max(ui.availableWidth, 60.0), height)
    ctx.canvas.fillRoundedRect(outer, st.rounding, st.inputBackground)
    val contentH = count * rowHeight
    val maxScroll = math.max(0.0, contentH - outer.height)
    var off = state.scroll.max(0).min(maxScroll)
    val barRect = Rect(outer.x1 - 10, outer.y0, outer.x1, outer.y1)
    var barHot = false
    if maxScroll > 0 then
      val br = ctx.interact(barRect, id + 2, drag = true)
      barHot = br.hovered || br.pressed
      if br.dragged then ctx.input.mouse.foreach(m => off = ((m.y - outer.y0) / outer.height * contentH - outer.height / 2).max(0).min(maxScroll))
    val r = ctx.interact(outer, id, focusable = selectable)
    if r.hovered then off = (off - r.scroll.y).max(0).min(maxScroll)
    var changed = false
    var activated = r.clicked && !ctx.input.released // Enter / Space
    def select(i: Int): Unit =
      val c = i.max(0).min(count - 1)
      if count > 0 && !state.selected.contains(c) then { state.selected = Some(c); changed = true }
      if count > 0 then state.revealRow = c
    if selectable && r.focused && count > 0 then
      val page = math.max(1, (outer.height / rowHeight).toInt - 1)
      val cur = state.selected.getOrElse(-1)
      if ctx.takeKey(Key.Down) then select(cur + 1)
      else if ctx.takeKey(Key.Up) then select(if cur < 0 then 0 else cur - 1)
      else if ctx.takeKey(Key.PageDown) then select(cur + page)
      else if ctx.takeKey(Key.PageUp) then select(cur - page)
      else if ctx.takeKey(Key.Home) then select(0)
      else if ctx.takeKey(Key.End) then select(count - 1)
    if state.revealRow >= 0 then
      val top = state.revealRow * rowHeight; val bot = top + rowHeight
      if top < off then off = top else if bot > off + outer.height then off = bot - outer.height
      off = off.max(0).min(maxScroll)
      state.revealRow = -1
    state.scroll = off
    val first = math.max(0, (off / rowHeight).toInt)
    val last = math.min(count - 1, ((off + outer.height) / rowHeight).toInt)
    state.visible = if last >= first then first to last else 0 until 0
    ctx.pushClip(outer)
    val rowRight = outer.x1 - (if maxScroll > 0 then 10 else 0)
    var row0 = first
    while row0 <= last do
      val ry = outer.y0 + row0 * rowHeight - off
      val rr = Rect(outer.x0, ry, rowRight, ry + rowHeight)
      val sel = state.selected.contains(row0)
      val hov = selectable && ctx.hoverable(rr.intersect(outer), id + 1000 + row0)
      if sel then ctx.canvas.fillRect(rr, st.checked)
      else if hov then ctx.canvas.fillRect(rr, st.widgetHover.withAlpha(0.5f))
      if sel && ctx.keyboardNav && r.focused then ctx.canvas.strokeRect(rr.inflate(-1, -1), 1.5, st.accent)
      if hov && ctx.input.pressed then
        select(row0); ctx.focusId = id; ctx.focusClaimed = true
        if ctx.input.doubleClicked then activated = true
      row(row0, new Ui(ctx, rr.inflate(-8, -1), false, id + 5000 + row0))
      row0 += 1
    ctx.popClip()
    if maxScroll > 0 then
      val thumbH = math.max(24, outer.height * outer.height / contentH)
      val ty = outer.y0 + (off / maxScroll) * (outer.height - thumbH)
      val tw = if barHot then 6.0 else 4.0
      ctx.canvas.fillRoundedRect(Rect(outer.x1 - 2 - tw, ty, outer.x1 - 2, ty + thumbH), tw / 2, st.text.withAlpha(if barHot then 0.5f else 0.28f))
    ctx.outline(outer, st.rounding, if r.focused then st.accent else st.border)
    ctx.access(AccessRole.ListItem, "", outer, value = state.selected.fold("")(_.toString), focused = r.focused)
    Response(outer, hovered = r.hovered, changed = changed, focused = r.focused, doubleClicked = activated)

  // --- context menu and popups ---

  /** A context menu opened by right-clicking the widget that produced `r`. `body` runs while it is open (menu items, separators). */
  def contextMenu(r: Response, minWidth: Double = 170)(body: Ui => Unit): Unit =
    val ctx = ui.ctx
    val id = ui.nextId()
    if ctx.secondaryHit(r.rect) then
      ctx.input.mouse.foreach { m => ctx.popupOwner = id; ctx.numbers(id) = m.x; ctx.numbers(id + 1) = m.y }
      ctx.focusId = 0; ctx.requestRepaint()
    AdvancedImpl.popupAt(ui, id, Point(ctx.numbers.getOrElse(id, 0.0), ctx.numbers.getOrElse(id + 1, 0.0)), minWidth)(body)

  /** A popup panel below `anchor` that is shown while `open` is true and closes (setting `open` to false) on an outside click or Escape. */
  def popup(open: Var[Boolean], anchor: Rect, minWidth: Double = 160)(body: Ui => Unit): Unit =
    val ctx = ui.ctx
    val id = ui.nextId()
    if open.value then
      val was = ctx.flags.getOrElse(id, false)
      if !was then { ctx.popupOwner = id; ctx.flags(id) = true }
      else if ctx.popupOwner != id then { open.value = false; ctx.flags(id) = false }
      if open.value then AdvancedImpl.popupAt(ui, id, Point(anchor.x0, anchor.y1 + 3), minWidth)(body)
    else
      if ctx.flags.getOrElse(id, false) then { ctx.flags(id) = false; if ctx.popupOwner == id then ctx.popupOwner = 0L }

  // --- splitters ---

  /** Two panes side by side separated by a draggable divider. `fraction` (0..1) is the first pane's share of the width; `height` 0 fills the
    * remaining height. Arrow keys resize while the divider has focus. */
  def hSplit(fraction: Var[Double], height: Double = 0, minFirst: Double = 80, minSecond: Double = 80)(first: Ui => Unit)(second: Ui => Unit): Response =
    AdvancedImpl.split(ui, fraction, true, height, minFirst, minSecond)(first)(second)

  /** Two panes stacked vertically (top, bottom) separated by a draggable divider; `fraction` is the top pane's share of the height. */
  def vSplit(fraction: Var[Double], height: Double, minFirst: Double = 60, minSecond: Double = 60)(first: Ui => Unit)(second: Ui => Unit): Response =
    AdvancedImpl.split(ui, fraction, false, height, minFirst, minSecond)(first)(second)

  // --- small controls ---

  /** A row of `max` stars; clicking star n sets the value to n (clicking the current value clears it). Left/Right adjust while focused. */
  def rating(value: Var[Int], max: Int = 5, size: Double = 18): Response =
    val ctx = ui.ctx; val st = ui.style
    val id = ui.nextId()
    val rect = ui.allocate(max * (size + 2), size + 4)
    var r = ctx.interact(rect, id, focusable = true)
    val hoverIdx = r.mouse.filter(m => r.hovered && rect.contains(m)).fold(0)(m => (((m.x - rect.x0) / (size + 2)).toInt + 1).min(max))
    if r.clicked && ctx.input.released && hoverIdx > 0 then
      value.value = if value.value == hoverIdx then 0 else hoverIdx; r = r.copy(changed = true)
    if r.focused then
      if ctx.takeKey(Key.Right) then { value.value = (value.value + 1).min(max); r = r.copy(changed = true) }
      else if ctx.takeKey(Key.Left) then { value.value = (value.value - 1).max(0); r = r.copy(changed = true) }
    val shown = if hoverIdx > 0 then hoverIdx else value.value
    for i <- 0 until max do
      val c = Point(rect.x0 + i * (size + 2) + size / 2 + 1, rect.center.y)
      val pts = AdvancedImpl.starPoints(c, size / 2)
      val on = i < shown
      val col = if on then (if hoverIdx > 0 then st.accentBright else Color.rgb8(244, 184, 40)) else st.borderStrong
      pts.indices.foreach(k => ctx.canvas.fillConvex(Seq(c, pts(k), pts((k + 1) % pts.length)), col))
    ctx.access(AccessRole.Slider, "rating", rect, value = s"${value.value} of $max")
    r

  /** A button showing a texture (e.g. a thumbnail or an app icon) with hover/press feedback. */
  def imageButton(texture: scalaui.gpu.Texture, size: Size, tip: String = "", selected: Boolean = false): Response =
    val ctx = ui.ctx; val st = ui.style
    val rect = ui.allocate(size.width + 8, size.height + 8)
    val id = ui.nextId()
    val r = ctx.interact(rect, id, focusable = true)
    val h = ctx.hoverAmount(id, r.hovered)
    val fill = if r.pressed then st.widgetActive else if selected then st.checked else st.widgetHover.withAlpha((h * st.widgetHover.a).toFloat)
    if fill.a > 0.01 then ctx.canvas.fillRoundedRect(rect, st.rounding, fill)
    if selected then ctx.outline(rect, st.rounding, st.accent)
    ctx.canvas.drawImage(texture, rect.inflate(-4, -4))
    if tip.nonEmpty then ui.tooltip(r, tip)
    ctx.access(AccessRole.Button, tip, rect)
    r

  /** A circular progress ring with the percentage in the middle. */
  def progressCircle(fraction: Double, diameter: Double = 40): Unit =
    val ctx = ui.ctx; val st = ui.style
    val rect = ui.allocate(diameter, diameter)
    val c = rect.center; val rad = diameter / 2 - 3
    ctx.canvas.strokeCircle(c, rad, 4, st.borderStrong.withAlpha(0.6f))
    val f = fraction.max(0).min(1)
    if f > 0 then
      val n = math.max(2, (f * 60).toInt)
      val b = scalaui.raster.Path.builder
      for i <- 0 to n do
        val a = -math.Pi / 2 + 2 * math.Pi * f * i / n
        val (x, y) = (c.x + rad * math.cos(a), c.y + rad * math.sin(a))
        if i == 0 then b.moveTo(x, y) else b.lineTo(x, y)
      ctx.canvas.strokePath(b.build, scalaui.raster.Stroke(4, cap = scalaui.raster.LineCap.Round, join = scalaui.raster.LineJoin.Round), st.accent)
    val l = ctx.layout(f"${f * 100}%.0f%%", math.max(9, diameter / 3.6), weight = Weight.Medium)
    ctx.paintText(l, Point(c.x - l.width / 2, c.y - l.height / 2), st.textWeak)
    ctx.access(AccessRole.ProgressBar, "", rect, value = f"${f * 100}%.0f%%")

  /** A progress bar of unknown duration: a segment sweeping back and forth. */
  def progressIndeterminate(width: Double = 200): Unit =
    val ctx = ui.ctx; val st = ui.style
    val rect = ui.allocate(width, 8)
    ctx.canvas.fillRoundedRect(rect, 4, st.borderStrong.withAlpha(0.6f))
    val t = (ctx.time * 0.9) % 1.0
    val seg = rect.width * 0.3
    val e = 0.5 - 0.5 * math.cos(t * 2 * math.Pi) // ease in/out sweep
    val x0 = rect.x0 + (rect.width - seg) * e
    ctx.canvas.fillRoundedRect(Rect(x0, rect.y0, x0 + seg, rect.y1), 4, st.accent)
    ctx.requestRepaint()
    ctx.access(AccessRole.ProgressBar, "", rect)

  // --- tabs ---

  /** Tabs with a close button each (also Delete while a tab has focus). Returns the index of the tab whose close button was pressed;
    * the caller removes it from `titles` and should clamp `selected`. */
  def closableTabBar(selected: Var[Int], titles: Seq[String]): Option[Int] =
    val ctx = ui.ctx; val st = ui.style
    var closed: Option[Int] = None
    val start = ui.cursorPoint
    var total = Rect(start.x, start.y, start.x, start.y + st.minHeight + 6)
    ui.horizontal { h =>
      titles.zipWithIndex.foreach { (t, i) =>
        val on = selected.value == i
        val l = ctx.layout(t, st.fontSize, weight = if on then Weight.SemiBold else Weight.Medium)
        val rect = h.allocate(l.width + 28 + 22, st.minHeight + 6)
        val id = h.nextId()
        val closeRect = Rect(rect.x1 - 26, rect.center.y - 9, rect.x1 - 8, rect.center.y + 9)
        val cr = ctx.interact(closeRect, id + 1)
        val r = ctx.interact(rect, id, focusable = true)
        if cr.clicked then closed = Some(i)
        else if r.clicked && selected.value != i then selected.value = i
        if r.focused && ctx.takeKey(Key.Delete) then closed = Some(i)
        ctx.access(AccessRole.Tab, t, rect, checked = Some(on))
        val hot = ctx.hoverAmount(id, r.hovered && !on)
        val tabR = rect.inflate(-3, -3).copy(y0 = rect.y0 + 2)
        if hot > 0.01 then ctx.canvas.fillRoundedRect(tabR, st.rounding, st.widgetHover.withAlpha((0.7 * hot).toFloat))
        ctx.paintText(l, Point(rect.x0 + 14, rect.y0 + (rect.height - l.height) / 2), if on then st.textStrong else Style.mix(st.textWeak, st.text, hot))
        if cr.hovered then ctx.canvas.fillRoundedRect(closeRect, 4, st.widgetActive)
        if on || r.hovered || cr.hovered then ctx.paintIcon(Icons.close, closeRect.inflate(-4, -4), if cr.hovered then st.textStrong else st.textWeak)
        val ind = ctx.animate(id + 77, if on then 1.0 else 0.0, 0.15)
        if ind > 0.01 then
          val cw = (rect.width - 20) * ind
          ctx.canvas.fillRoundedRect(Rect(rect.center.x - cw / 2, rect.y1 - 3, rect.center.x + cw / 2, rect.y1), 1.5, st.accent)
        total = total.union(rect)
      }
    }
    ctx.canvas.fillRect(Rect(start.x, total.y1, start.x + ui.availableWidth, total.y1 + 1), st.border)
    closed

  // --- combo with search ---

  /** A combo box whose popup has a search field that filters the options (case-insensitive substring); Up/Down/Enter pick with the keyboard. */
  def searchComboBox(selected: Var[Int], options: Seq[String], width: Double = 200, maxRows: Int = 8): Response =
    val ctx = ui.ctx; val st = ui.style
    val id = ui.nextId()
    val rect = ui.allocate(width, st.minHeight)
    val isOpen = ctx.popupOwner == id
    val r = ctx.interact(rect, id, focusable = true)
    val searchId = new Ui(ctx, rect, true, id + 11).nextId()
    if r.clicked then
      ctx.popupOwner = if isOpen then 0L else id
      if !isOpen then { ctx.textVar(id).value = ""; ctx.numbers(id + 3) = 0; ctx.numbers(id + 4) = 0; ctx.focusId = searchId; ctx.focusClaimed = true }
    val h = ctx.hoverAmount(id, r.hovered)
    ctx.box(rect, st.rounding, Style.mix(st.inputBackground, st.widgetHover, h * 0.5), if isOpen then st.accent else Style.mix(st.inputBorder, st.borderHover, h))
    val l = ctx.layout(options.lift(selected.value).getOrElse(""), st.fontSize)
    ctx.pushClip(rect.inflate(-4, 0))
    ctx.paintText(l, Point(rect.x0 + 8, rect.y0 + (rect.height - l.height) / 2), st.textStrong)
    ctx.popClip()
    ctx.paintIcon(Icons.chevronDown, Rect(rect.x1 - 22, rect.center.y - 7, rect.x1 - 8, rect.center.y + 7), st.textWeak)
    ctx.access(AccessRole.ComboBox, "", rect, value = options.lift(selected.value).getOrElse(""))
    if ctx.popupOwner == id then
      ctx.defer { () =>
        if ctx.popupOwner == id then
          ctx.focusTrap = true
          val q = ctx.textVar(id)
          val itemH = st.minHeight
          val query = q.value.toLowerCase
          val matches = options.zipWithIndex.filter((o, _) => query.isEmpty || o.toLowerCase.contains(query))
          val rows = math.min(maxRows, math.max(matches.size, 1))
          val pop = Rect(rect.x0, rect.y1 + 4, math.max(rect.x1, rect.x0 + 160), rect.y1 + 4 + 8 + st.minHeight + 6 + rows * itemH)
          ui.popupFrame(pop)
          val c = new Ui(ctx, Rect(pop.x0 + 6, pop.y0 + 6, pop.x1 - 6, pop.y0 + 6 + st.minHeight + 4), true, id + 11)
          ctx.focusId = searchId
          c.textEdit(q, width = Some(pop.width - 12), hint = "Search")
          val qHash = q.value.hashCode.toDouble
          if ctx.numbers.getOrElse(id + 4, 0.0) != qHash then { ctx.numbers(id + 4) = qHash; ctx.numbers(id + 3) = 0; ctx.numbers(id + 5) = 0 }
          var hi = ctx.numbers.getOrElse(id + 3, 0.0).toInt.max(0).min(math.max(0, matches.size - 1))
          val keys = ctx.input.keys
          if keys.exists(_.key == Key.Down) then hi = (hi + 1).min(matches.size - 1).max(0)
          if keys.exists(_.key == Key.Up) then hi = (hi - 1).max(0)
          ctx.numbers(id + 3) = hi
          val listTop = pop.y0 + 6 + st.minHeight + 6
          val view = Rect(pop.x0 + 4, listTop, pop.x1 - 4, listTop + rows * itemH)
          var off = ctx.numbers.getOrElse(id + 5, 0.0)
          val maxOff = math.max(0.0, matches.size * itemH - view.height)
          if ctx.input.mouse.exists(view.contains) then off -= ctx.input.scroll.y
          if hi * itemH < off then off = hi * itemH else if (hi + 1) * itemH > off + view.height then off = (hi + 1) * itemH - view.height
          off = off.max(0).min(maxOff); ctx.numbers(id + 5) = off
          ctx.pushClip(view)
          val m = ctx.input.mouse
          var pick = -1
          if keys.exists(_.key == Key.Enter) && matches.nonEmpty then pick = matches(hi)._2
          matches.zipWithIndex.foreach { case ((o, orig), k) =>
            val ir = Rect(view.x0, view.y0 + k * itemH - off, view.x1, view.y0 + (k + 1) * itemH - off)
            if ir.y1 >= view.y0 && ir.y0 <= view.y1 then
              val hov = m.exists(p => ir.contains(p) && view.contains(p))
              if hov && ctx.input.pressed then pick = orig
              if hov && (ctx.input.mouse.isDefined && ctx.input.mouseDelta != Vec2.Zero) then { hi = k; ctx.numbers(id + 3) = k }
              val on = k == hi
              if on then ctx.canvas.fillRoundedRect(ir.inflate(-2, 0), st.rounding - 1, st.widgetHover)
              else if orig == selected.value then ctx.canvas.fillRoundedRect(ir.inflate(-2, 0), st.rounding - 1, st.checked)
              val ol = ctx.layout(o, st.fontSize, weight = if orig == selected.value then Weight.Medium else Weight.Regular)
              ctx.paintText(ol, Point(ir.x0 + 8, ir.y0 + (ir.height - ol.height) / 2), if on || orig == selected.value then st.textStrong else st.text)
              if orig == selected.value then ctx.paintIcon(Icons.check, Rect(ir.x1 - 22, ir.center.y - 7, ir.x1 - 8, ir.center.y + 7), st.accentBright)
          }
          if matches.isEmpty then
            val nl = ctx.layout("No matches", st.fontSize)
            ctx.paintText(nl, Point(view.x0 + 8, view.y0 + (itemH - nl.height) / 2), st.textWeak)
          ctx.popClip()
          if pick >= 0 then { selected.value = pick; ctx.popupOwner = 0L; ctx.focusId = 0 }
          else if (ctx.input.pressed && !m.exists(p => pop.contains(p) || rect.contains(p))) || keys.exists(_.key == Key.Escape) then ctx.popupOwner = 0L
      }
    r

  // --- date picker ---

  /** A date field that opens a calendar popup (Monday-first). The picked day is written to `date` and closes the popup. */
  def datePicker(date: Var[Date], width: Double = 150): Response =
    val ctx = ui.ctx; val st = ui.style
    val id = ui.nextId()
    val rect = ui.allocate(width, st.minHeight)
    val isOpen = ctx.popupOwner == id
    val r = ctx.interact(rect, id, focusable = true)
    if r.clicked then
      ctx.popupOwner = if isOpen then 0L else id
      if !isOpen then ctx.numbers(id + 1) = date.value.year * 12.0 + date.value.month - 1
    val h = ctx.hoverAmount(id, r.hovered)
    ctx.box(rect, st.rounding, Style.mix(st.inputBackground, st.widgetHover, h * 0.5), if isOpen then st.accent else Style.mix(st.inputBorder, st.borderHover, h))
    val l = ctx.layout(date.value.toString, st.fontSize)
    ctx.paintText(l, Point(rect.x0 + 8, rect.y0 + (rect.height - l.height) / 2), st.textStrong)
    ctx.paintIcon(Icons.calendar, Rect(rect.x1 - 24, rect.center.y - 8, rect.x1 - 8, rect.center.y + 8), st.textWeak)
    ctx.access(AccessRole.ComboBox, "date", rect, value = date.value.toString)
    if ctx.popupOwner == id then
      ctx.defer { () =>
        if ctx.popupOwner == id then
          ctx.focusTrap = true
          val cell = 34.0; val rowH = 30.0
          val pw = cell * 7 + 16
          val x0 = math.max(4, math.min(rect.x0, ctx.canvas.width - pw - 4))
          val pop = Rect(x0, rect.y1 + 4, x0 + pw, rect.y1 + 4 + 8 + 34 + 22 + rowH * 6 + 8)
          ui.popupFrame(pop)
          val idx = ctx.numbers.getOrElse(id + 1, date.value.year * 12.0 + date.value.month - 1).toInt
          val vy = Math.floorDiv(idx, 12); val vm = Math.floorMod(idx, 12) + 1
          val prev = Rect(pop.x0 + 8, pop.y0 + 8, pop.x0 + 8 + 30, pop.y0 + 8 + 30)
          val next = Rect(pop.x1 - 8 - 30, pop.y0 + 8, pop.x1 - 8, pop.y0 + 8 + 30)
          for (b, ic, d, k) <- Seq((prev, Icons.chevronLeft, -1, 2L), (next, Icons.chevronRight, 1, 3L)) do
            val br = ctx.interact(b, id + k, focusable = true)
            if br.hovered then ctx.canvas.fillRoundedRect(b, st.rounding, st.widgetHover)
            ctx.paintIcon(ic, b.inflate(-7, -7), st.textStrong)
            if br.clicked then ctx.numbers(id + 1) = idx + d
          val title = ctx.layout(s"${Date.monthNames(vm - 1)} $vy", st.fontSize, weight = Weight.SemiBold)
          ctx.paintText(title, Point(pop.center.x - title.width / 2, pop.y0 + 8 + (30 - title.height) / 2), st.textStrong)
          val gridTop = pop.y0 + 8 + 34
          Seq("Mo", "Tu", "We", "Th", "Fr", "Sa", "Su").zipWithIndex.foreach { (d, i) =>
            val dl = ctx.layout(d, st.fontSize - 1)
            ctx.paintText(dl, Point(pop.x0 + 8 + i * cell + (cell - dl.width) / 2, gridTop + (22 - dl.height) / 2), st.textWeak)
          }
          val firstOfMonth = Date(vy, vm, 1)
          val lead = firstOfMonth.dayOfWeek
          val today = Date.today
          for k <- 0 until 42 do
            val d = firstOfMonth.plusDays(k - lead)
            val cr = Rect(pop.x0 + 8 + (k % 7) * cell + 1, gridTop + 22 + (k / 7) * rowH + 1, pop.x0 + 8 + (k % 7 + 1) * cell - 1, gridTop + 22 + (k / 7 + 1) * rowH - 1)
            val inMonth = d.month == vm
            val cb = ctx.interact(cr, id + 100 + k, focusable = true)
            val sel = d == date.value
            if sel then ctx.canvas.fillRoundedRect(cr, st.rounding, st.accent)
            else if cb.hovered then ctx.canvas.fillRoundedRect(cr, st.rounding, st.widgetHover)
            if d == today && !sel then ctx.outline(cr, st.rounding, st.accent)
            val tl = ctx.layout(d.day.toString, st.fontSize, weight = if sel then Weight.SemiBold else Weight.Regular)
            ctx.paintText(tl, Point(cr.center.x - tl.width / 2, cr.center.y - tl.height / 2),
              if sel then st.onAccent else if inMonth then st.text else st.textWeak.withAlpha(0.5f))
            if cb.clicked then { date.value = d; ctx.popupOwner = 0L }
          if (ctx.input.pressed && !ctx.input.mouse.exists(p => pop.contains(p) || rect.contains(p))) || ctx.input.keys.exists(_.key == Key.Escape) then ctx.popupOwner = 0L
      }
    r

  // --- reorderable list ---

  /** A list whose rows can be reordered by dragging the grip at the left. `row(item, ui)` lays out one row; the reordered vector is written
    * to `items` when the pointer is released (reported as `changed`). */
  def reorderList[T](items: Var[Vector[T]], rowHeight: Double = 30)(row: (T, Ui) => Unit): Response =
    val ctx = ui.ctx; val st = ui.style
    val id = ui.nextId()
    val n = items.value.length
    val area = ui.allocate(math.max(ui.availableWidth, 80.0), n * rowHeight)
    var drag = ctx.numbers.getOrElse(id, -1.0).toInt
    var changed = false
    def slotTop(s: Int) = area.y0 + s * rowHeight
    // grips: pressing one starts a drag
    for i <- 0 until n do
      val grip = Rect(area.x0, slotTop(i), area.x0 + 26, slotTop(i) + rowHeight)
      if drag < 0 || drag == i then
        val g = ctx.interact(grip, id + 10 + i, drag = true)
        if g.hovered then ctx.cursorIcon = CursorIcon.Grab
        if g.pressed && drag < 0 && ctx.input.mouse.exists(m => math.abs(m.y - (slotTop(i) + rowHeight / 2)) < rowHeight) then drag = i
    val mouse = ctx.input.mouse
    val target = if drag >= 0 then mouse.fold(drag)(m => ((m.y - area.y0) / rowHeight).toInt.max(0).min(n - 1)) else -1
    if drag >= 0 && !ctx.input.down then
      if target != drag && target >= 0 then
        val v = items.value; val item = v(drag)
        items.value = v.patch(drag, Nil, 1).patch(target, Seq(item), 0); changed = true
      drag = -1
    ctx.numbers(id) = drag
    if drag >= 0 then { ctx.cursorIcon = CursorIcon.Grabbing; ctx.requestRepaint() }
    // display order: dragged item removed and re-inserted at the target slot
    val order = if drag >= 0 then Vector.range(0, n).filter(_ != drag).patch(target, Seq(drag), 0) else Vector.range(0, n)
    def paintRow(orig: Int, top: Double, lifted: Boolean): Unit =
      val rr = Rect(area.x0, top, area.x1, top + rowHeight).inflate(0, -1)
      if lifted then ctx.shadow(rr, st.rounding, 10, st.shadow, Vec2(0, 3))
      ctx.box(rr, st.rounding, if lifted then st.widgetHover else st.faint, if lifted then st.accent else st.border)
      for dx <- Seq(-2.5, 2.5); dy <- Seq(-5.0, 0.0, 5.0) do ctx.canvas.fillCircle(Point(rr.x0 + 13 + dx, rr.center.y + dy), 1.2, st.textWeak)
      row(items.value(orig), new Ui(ctx, Rect(rr.x0 + 28, rr.y0, rr.x1 - 6, rr.y1), false, id + 5000 + orig))
    order.zipWithIndex.foreach { (orig, slot) => if orig != drag then paintRow(orig, slotTop(slot), false) }
    if drag >= 0 then
      val y = mouse.fold(slotTop(target))(m => (m.y - rowHeight / 2).max(area.y0).min(area.y1 - rowHeight))
      ctx.defer(() => paintRow(drag, y, true))
    Response(area, changed = changed, dragged = drag >= 0)
