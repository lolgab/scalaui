package scalaui.ui

import scalaui.color.Color
import scalaui.geom.*
import scalaui.text.{Align, RichLayout, Span, TextStyle}

/** Menus, tabs, grids, floating windows, tooltips, drag values and the color picker. */
extension (ui: Ui)
  private def ctx = ui.ctx
  private def style = ui.style

  // --- menus ---

  /** A horizontal strip of [[menu]]s. */
  def menuBar(f: Ui => Unit): Unit =
    val h = style.minHeight + 4
    val r = ui.allocate(ui.availableWidth, h)
    ctx.canvas.fillRect(r, style.tabStrip)
    ctx.canvas.fillRect(Rect(r.x0, r.y1 - 1, r.x1, r.y1), style.border)
    val c = new Ui(ctx, r.inflate(-4, -2), false, ui.nextId())
    f(c)

  /** A drop-down menu: `body` runs only while open, using [[menuItem]] and `separator`. */
  def menu(title: String)(body: Ui => Unit): Unit =
    val id = ui.nextId()
    val l = ctx.layout(title, style.fontSize, weight = Weight.Medium)
    val rect = ui.allocate(l.width + 18, style.minHeight)
    val open = ctx.popupOwner == id
    val r = ctx.interact(rect, id)
    val someMenuOpen = ctx.menuOwners.contains(ctx.popupOwner)
    ctx.menuOwners += id
    if r.clicked then ctx.popupOwner = if open then 0L else id
    else if someMenuOpen && !open && ctx.input.mouse.exists(rect.contains) then ctx.popupOwner = id
    val isOpen = ctx.popupOwner == id
    if isOpen || r.hovered then ctx.canvas.fillRoundedRect(rect, style.rounding, if isOpen then style.widgetActive else style.widgetHover)
    ctx.paintText(l, Point(rect.x0 + 9, rect.y0 + (rect.height - l.height) / 2), if isOpen || r.hovered then style.textStrong else style.text)
    if isOpen then
      ctx.defer { () =>
        ctx.focusTrap = true
        val last = ctx.lastSizes.getOrElse(id, Size(160, 10))
        val w = math.max(160, last.width)
        val pop = Rect(rect.x0, rect.y1 + 3, rect.x0 + w + 8, rect.y1 + 3 + last.height + 8)
        ui.popupFrame(pop)
        val c = new Ui(ctx, Rect(pop.x0 + 4, pop.y0 + 4, pop.x1 - 4, pop.y1 + 1000), true, id)
        body(c)
        val u = c.usedRect
        ctx.lastSizes(id) = Size(math.max(u.width, w), u.height)
        if ctx.input.pressed && !ctx.input.mouse.exists(p => pop.contains(p) || rect.contains(p)) then ctx.popupOwner = 0L
      }

  /** A row in a [[menu]]; clicking closes the menu. `shortcut` is shown right-aligned. */
  /** The application menu bar: native where the OS has one (installed by `App.run(menuBar = ...)`), otherwise drawn here.
    * Chosen items are reported by [[menuPressed]]. */
  def appMenuBar(menus: Seq[scalaui.window.MenuEntry.Submenu]): Unit =
    if !ctx.nativeMenus then
      def items(u: Ui, entries: Seq[scalaui.window.MenuEntry]): Unit = entries.foreach {
        case scalaui.window.MenuEntry.Separator => u.separator()
        case n: scalaui.window.MenuEntry.Submenu => u.menu(n.title)(items(_, n.entries))
        case scalaui.window.MenuEntry.Item(title, id, shortcut, enabled, checked) =>
          val label = if checked then "✓ " + title else title
          u.disabled(!enabled) { d => if d.menuItem(label, shortcut.split('+').map(_.capitalize).mkString("+")).clicked then ctx.input.menuActions += id }
      }
      ui.menuBar(bar => menus.foreach(m => bar.menu(m.title)(items(_, m.entries))))

  /** True during the frame in which native (or fallback) menu item `id` was chosen. */
  def menuPressed(id: Int): Boolean = ctx.input.menuActions.contains(id)

  def menuItem(text: String, shortcut: String = ""): Response =
    val l = ctx.layout(text, style.fontSize)
    val rect = ui.allocate(ui.availableWidth, style.minHeight)
    val r = ctx.interact(rect, ui.nextId(), focusable = true)
    if r.hovered || (ctx.keyboardNav && r.focused) then ctx.canvas.fillRoundedRect(rect, style.rounding - 1, style.widgetHover)
    ctx.paintText(l, Point(rect.x0 + 10, rect.y0 + (rect.height - l.height) / 2), if r.hovered then style.textStrong else style.text)
    if shortcut.nonEmpty then
      val sl = ctx.layout(shortcut, style.fontSize - 1)
      ctx.paintText(sl, Point(rect.x1 - sl.width - 10, rect.y0 + (rect.height - sl.height) / 2), style.textWeak)
    if r.clicked then ctx.popupOwner = 0L
    r

  // --- rich text ---

  /** Multi-style text, wrapped to the available width. Build spans with [[Context.shaper]] styles. */
  def richText(spans: Seq[Span], align: Align = Align.Left): Response =
    val w = if ui.vertical then Some(ui.availableWidth) else None
    val l = RichLayout.layout(spans, w, align)
    val r = ui.allocate(l.width, l.height)
    ctx.paintRich(l, r.origin)
    Response(r)

  /** Text run in the UI font: `bold`, `italic`, `monospace`, `color`, `size`. */
  def span(text: String, bold: Boolean = false, italic: Boolean = false, monospace: Boolean = false, color: Option[Color] = None,
      size: Option[Double] = None, underline: Boolean = false, strike: Boolean = false, background: Option[Color] = None): Span =
    Span(text, TextStyle(ctx.shaper(bold, italic, monospace), size.getOrElse(if monospace then style.monoSize else style.fontSize),
      color.getOrElse(style.text), underline, strike, background))

  // --- tabs ---

  def tabBar(selected: Var[Int], titles: Seq[String]): Response =
    var changed = false
    val start = ui.cursorPoint
    var total = Rect(start.x, start.y, start.x, start.y + style.minHeight + 6)
    ui.horizontal { h =>
      titles.zipWithIndex.foreach { (t, i) =>
        val on = selected.value == i
        val l = ctx.layout(t, style.fontSize, weight = if on then Weight.SemiBold else Weight.Medium)
        val rect = h.allocate(l.width + 28, style.minHeight + 6)
        val id = h.nextId()
        val r = ctx.interact(rect, id, focusable = true)
        if r.clicked && selected.value != i then { selected.value = i; changed = true }
        ctx.access(scalaui.window.AccessRole.Tab, t, rect, checked = Some(on))
        val hot = ctx.hoverAmount(id, r.hovered && !on)
        val tabR = rect.inflate(-3, -3).copy(y0 = rect.y0 + 2)
        if hot > 0.01 then ctx.canvas.fillRoundedRect(tabR, style.rounding, style.widgetHover.withAlpha((0.7 * hot).toFloat))
        ctx.paintText(l, Point(rect.x0 + 14, rect.y0 + (rect.height - l.height) / 2), if on then style.textStrong else Style.mix(style.textWeak, style.text, hot))
        val ind = ctx.animate(id + 77, if on then 1.0 else 0.0, 0.15)
        if ind > 0.01 then
          val cw = (rect.width - 20) * ind
          ctx.canvas.fillRoundedRect(Rect(rect.center.x - cw / 2, rect.y1 - 3, rect.center.x + cw / 2, rect.y1), 1.5, style.accent)
        total = total.union(rect)
      }
    }
    ctx.canvas.fillRect(Rect(start.x, total.y1, start.x + ui.availableWidth, total.y1 + 1), style.border)
    Response(total, changed = changed)

  // --- grid ---

  /** Aligned columns: call `cell` per cell; rows wrap every `cols` cells. Column widths settle after one frame. */
  def grid(cols: Int)(body: Grid => Unit): Unit =
    val id = ui.nextId()
    val g = new Grid(ui, id, cols)
    body(g)
    g.finish()

  // --- misc widgets ---

  /** Number box edited by dragging horizontally. */
  def dragValue(v: Var[Double], speed: Double = 0.1, min: Double = Double.NegativeInfinity, max: Double = Double.PositiveInfinity, suffix: String = ""): Response =
    val id = ui.nextId()
    val rect = ui.allocate(72, style.minHeight)
    var r = ctx.interact(rect, id, drag = true)
    if r.dragged && r.dragDelta.x != 0 then
      val nv = (v.value + r.dragDelta.x * speed * (if ctx.input.mods.shift then 0.1 else 1.0)).max(min).min(max)
      if nv != v.value then { v.value = nv; r = r.copy(changed = true) }
    ctx.box(rect, style.rounding, if r.pressed then style.widgetActive else if r.hovered then style.widgetHover else style.widget, if r.hovered then style.borderHover else style.border)
    if r.hovered || r.pressed then ctx.cursorIcon = scalaui.window.CursorIcon.ResizeHorizontal
    val l = ctx.layout(f"${v.value}%.2f$suffix", style.fontSize)
    ctx.paintText(l, Point(rect.x0 + (rect.width - l.width) / 2, rect.y0 + (rect.height - l.height) / 2), style.textStrong)
    r

  /** Shows `text` near the cursor once `r` has been hovered for `delay` seconds. */
  def tooltip(r: Response, text: String, delay: Double = 0.45): Unit =
    val key = 0x7001L + text.hashCode.toLong * 31 + (r.rect.x0 * 10).toLong * 7919 + (r.rect.y0 * 10).toLong * 104729
    if !r.hovered || r.pressed then ctx.numbers.remove(key)
    else if ctx.time - ctx.numbers.getOrElseUpdate(key, ctx.time) < delay then ctx.requestRepaint()
    else
      ctx.input.mouse.foreach { m =>
        ctx.defer { () =>
          val l = ctx.layout(text, style.fontSize - 1)
          val w = l.width + 16; val h = l.height + 10
          val x = math.min(m.x + 12, ctx.canvas.width - w - 2); val y = math.min(m.y + 18, ctx.canvas.height - h - 2)
          val rc = Rect(x, y, x + w, y + h)
          ctx.shadow(rc, style.rounding, 10, style.shadow, Vec2(0, 3))
          ctx.box(rc, style.rounding, if style.dark then Color.rgb8(58, 58, 64) else Color.rgb8(40, 40, 44), if style.dark then style.borderStrong else Color.rgb8(40, 40, 44))
          ctx.paintText(l, Point(x + 8, y + 5), Color.rgb8(240, 240, 244))
        }
      }

  /** A draggable floating window. Pass `open` to show a close button. Content sizes itself from the previous frame. */
  def window(title: String, initial: Point = Point(80, 80), width: Double = 280, open: Option[Var[Boolean]] = None)(body: Ui => Unit): Unit =
    if open.forall(_.value) then
      val id = ui.nextId()
      if !ctx.numbers.contains(id) then { ctx.numbers(id) = initial.x; ctx.numbers(id + 1) = initial.y }
      ctx.defer { () =>
        val th = 32.0
        val last = ctx.lastSizes.getOrElse(id, Size(width - 16, 20))
        var x = ctx.numbers(id).max(0).min(ctx.canvas.width - 60); var y = ctx.numbers(id + 1).max(0).min(ctx.canvas.height - th)
        // apply the drag before painting so the window follows the pointer within the same frame
        val tr = ctx.interact(Rect(x, y, x + width - 32, y + th), id + 2, drag = true)
        if tr.dragged then
          x = (x + tr.dragDelta.x).max(0).min(ctx.canvas.width - 60); y = (y + tr.dragDelta.y).max(0).min(ctx.canvas.height - th)
          ctx.numbers(id) = x; ctx.numbers(id + 1) = y
        val full = Rect(x, y, x + width, y + th + last.height + 16)
        ctx.shadow(full, style.windowRounding, 28, style.shadow, Vec2(0, 8))
        ctx.box(full, style.windowRounding, style.menu, style.borderStrong)
        val bar = Rect(x + 1, y + 1, x + width - 1, y + th)
        ctx.canvas.fillRoundedRect(bar, style.windowRounding - 1, style.tabStrip)
        ctx.canvas.fillRect(Rect(bar.x0, bar.y1 - style.windowRounding, bar.x1, bar.y1), style.tabStrip)
        ctx.canvas.fillRect(Rect(bar.x0, bar.y1 - 1, bar.x1, bar.y1), style.border)
        val tl = ctx.layout(title, style.fontSize, weight = Weight.SemiBold)
        ctx.paintText(tl, Point(x + 12, y + (th - tl.height) / 2), style.textStrong)
        open.foreach { o =>
          val cb = Rect(bar.x1 - 28, bar.y0 + 4, bar.x1 - 4, bar.y1 - 4)
          val cr = ctx.interact(cb, id + 3)
          if cr.hovered then ctx.canvas.fillRoundedRect(cb, style.rounding, style.widgetHover)
          ctx.paintIcon(Icons.close, cb.inflate(-5, -5), if cr.hovered then style.textStrong else style.textWeak)
          if cr.clicked then o.value = false
        }
        ctx.cover(full)
        val c = new Ui(ctx, Rect(x + 8, y + th + 8, x + width - 8, y + th + 4000), true, id)
        ctx.pushClip(full)
        body(c)
        ctx.popClip()
        ctx.lastSizes(id) = Size(width - 16, c.usedRect.height)
      }

  /** Color swatch that opens a picker (saturation/value square, hue bar, alpha bar). */
  def colorEdit(color: Var[Color], alpha: Boolean = true): Response =
    val id = ui.nextId()
    val rect = ui.allocate(44, style.minHeight)
    var r = ctx.interact(rect, id, focusable = true)
    if r.clicked then ctx.popupOwner = if ctx.popupOwner == id then 0L else id
    // checker behind translucent colors
    ctx.canvas.fillRoundedRect(rect, style.rounding, Color.rgb8(200, 200, 200))
    ctx.canvas.fillRoundedRect(rect, style.rounding, color.value)
    ctx.outline(rect, style.rounding, if r.hovered then style.borderHover else style.inputBorder)
    if ctx.popupOwner == id then
      ctx.defer { () =>
        val pw = 210.0; val sv = 160.0
        val pop = Rect(rect.x0, rect.y1 + 2, rect.x0 + pw, rect.y1 + 2 + sv + 12 + 18 + (if alpha then 22 else 0) + 18 + 12)
        ui.popupFrame(pop)
        var (h, s, v) = Color.toHsv(color.value)
        val keyHue = id + 1
        // keep hue stable when the color is gray/black (hue is undefined there)
        if s > 0 && v > 0 then ctx.numbers(keyHue) = h else h = ctx.numbers.getOrElse(keyHue, h.toDouble).toFloat
        var a = color.value.a
        var changed = false
        val svRect = Rect(pop.x0 + 8, pop.y0 + 8, pop.x1 - 8, pop.y0 + 8 + sv)
        val hueRect = Rect(svRect.x0, svRect.y1 + 8, svRect.x1, svRect.y1 + 8 + 14)
        val aRect = Rect(svRect.x0, hueRect.y1 + 8, svRect.x1, hueRect.y1 + 8 + 14)
        val hueColor = Color.fromHsv(h, 1, 1)
        ctx.canvas.fillRectGradient(svRect, Color.White, hueColor, Color.White, hueColor)
        ctx.canvas.fillRectGradient(svRect, Color(0, 0, 0, 0), Color(0, 0, 0, 0), Color.Black, Color.Black)
        val segW = hueRect.width / 6
        for i <- 0 until 6 do
          ctx.canvas.fillRectGradient(Rect(hueRect.x0 + i * segW, hueRect.y0, hueRect.x0 + (i + 1) * segW, hueRect.y1),
            Color.fromHsv(i * 60f, 1, 1), Color.fromHsv((i + 1) * 60f, 1, 1), Color.fromHsv(i * 60f, 1, 1), Color.fromHsv((i + 1) * 60f, 1, 1))
        def drag(area: Rect, sid: Long)(f: Point => Unit): Unit =
          val ar = ctx.interact(area, sid, drag = true)
          if (ar.dragged || ar.pressed) && ar.mouse.isDefined then { f(ar.mouse.get); changed = true }
        drag(svRect, id + 2) { m => s = ((m.x - svRect.x0) / svRect.width).toFloat.max(0).min(1); v = (1 - (m.y - svRect.y0) / svRect.height).toFloat.max(0).min(1) }
        drag(hueRect, id + 3) { m => h = (((m.x - hueRect.x0) / hueRect.width) * 360).toFloat.max(0).min(359.99f); ctx.numbers(keyHue) = h }
        if alpha then
          val base = Color.fromHsv(h, s, v)
          ctx.canvas.fillRect(aRect, Color.rgb8(200, 200, 200))
          ctx.canvas.fillRectGradient(aRect, base.withAlpha(0), base, base.withAlpha(0), base)
          drag(aRect, id + 4) { m => a = ((m.x - aRect.x0) / aRect.width).toFloat.max(0).min(1) }
        // markers
        ctx.canvas.strokeCircle(Point(svRect.x0 + s * svRect.width, svRect.y0 + (1 - v) * svRect.height), 5, 2, Color.White)
        ctx.canvas.strokeRect(Rect(hueRect.x0 + h / 360f * hueRect.width - 2, hueRect.y0 - 1, hueRect.x0 + h / 360f * hueRect.width + 2, hueRect.y1 + 1), 1.5, Color.White)
        if alpha then ctx.canvas.strokeRect(Rect(aRect.x0 + a * aRect.width - 2, aRect.y0 - 1, aRect.x0 + a * aRect.width + 2, aRect.y1 + 1), 1.5, Color.White)
        if changed then color.value = Color.fromHsv(h, s, v, if alpha then a else 1f)
        val argb = color.value.toArgb
        val hex = f"#${(argb >> 16) & 0xff}%02x${(argb >> 8) & 0xff}%02x${argb & 0xff}%02x" + (if alpha then f"${(argb >>> 24) & 0xff}%02x" else "")
        val hl = ctx.layout(hex, style.monoSize, monospace = true)
        ctx.paintText(hl, Point(svRect.x0, pop.y1 - 8 - hl.height - 2), style.text)
        if ctx.input.pressed && !ctx.input.mouse.exists(p => pop.contains(p) || rect.contains(p)) then ctx.popupOwner = 0L
      }
      if ctx.input.released then r = r.copy(changed = false)
    r

/** Cell-by-cell grid layout; see [[Ui.grid]]. */
final class Grid private[ui] (ui: Ui, id: Long, cols: Int):
  private val ctx = ui.ctx
  private val widths = Array.tabulate(cols)(i => ctx.numbers.getOrElse(id + i + 1, 0.0))
  private val newWidths = new Array[Double](cols)
  private val origin = ui.cursorPoint
  private var col = 0
  private var y = origin.y
  private var rowH = 0.0
  private var maxX = origin.x

  def cell(f: Ui => Unit): Unit =
    val x = origin.x + widths.take(col).sum + ui.style.itemSpacing.x * col
    val c = ui.child(Rect(x, y, math.max(x + 1, ui.region.x1), ui.region.y1), true)
    f(c)
    val u = c.usedRect
    newWidths(col) = math.max(newWidths(col), u.width); rowH = math.max(rowH, u.height)
    maxX = math.max(maxX, x + u.width)
    col += 1
    if col == cols then endRow()

  def endRow(): Unit =
    if col > 0 || rowH > 0 then { y += rowH + ui.style.itemSpacing.y; rowH = 0; col = 0 }

  private[ui] def finish(): Unit =
    if col > 0 then endRow()
    for i <- 0 until cols do { if math.abs(ctx.numbers.getOrElse(id + i + 1, 0.0) - newWidths(i)) > 0.5 then ctx.requestRepaint(); ctx.numbers(id + i + 1) = newWidths(i) }
    ui.allocate(math.max(0, maxX - origin.x), math.max(0, y - origin.y - ui.style.itemSpacing.y))
