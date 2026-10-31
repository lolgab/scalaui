package scalaui.ui

import scalaui.color.Color
import scalaui.geom.*
import scalaui.clipboard.Clipboard
import scalaui.window.Key

/** A layout region plus the immediate-mode widgets that flow into it, top-to-bottom (`vertical`) or left-to-right. */
final class Ui(val ctx: Context, initialRegion: Rect, val vertical: Boolean, private val idBase: Long):
  private var _region = initialRegion
  /** The area this Ui lays widgets out in; panels shrink it. */
  def region: Rect = _region
  private var cursor = initialRegion.origin
  private var rowExtent = 0.0
  private var maxX = initialRegion.x0
  private var maxY = initialRegion.y0
  private var counter = 0L

  def style: Style = ctx.style
  private def spacing = style.itemSpacing
  def nextId(): Long = { counter += 1; idBase * 1000003L + counter * 7919L + 17L }

  /** Space left in the layout direction, from the cursor to the region edge. */
  def availableWidth: Double = if vertical then region.x1 - region.x0 else math.max(0, region.x1 - cursor.x)
  /** Where the next widget will be placed. */
  def cursorPoint: Point = cursor
  def availableHeight: Double = math.max(0, region.y1 - cursor.y)
  def usedRect: Rect = Rect(region.x0, region.y0, math.max(maxX, region.x0), math.max(maxY, region.y0))

  /** Reserves a `w x h` slot at the cursor and advances it. In a row, shorter widgets are centered on the row. */
  def allocate(w: Double, h: Double): Rect =
    val rect =
      if vertical then
        val r = Rect(cursor.x, cursor.y, cursor.x + w, cursor.y + h)
        cursor = Point(cursor.x, cursor.y + h + spacing.y)
        r
      else
        val rowH = math.max(h, style.minHeight)
        val r = Rect(cursor.x, cursor.y + (rowH - h) / 2, cursor.x + w, cursor.y + (rowH - h) / 2 + h)
        cursor = Point(cursor.x + w + spacing.x, cursor.y)
        rowExtent = math.max(rowExtent, rowH)
        r
    maxX = math.max(maxX, rect.x1); maxY = math.max(maxY, if vertical then rect.y1 else cursor.y + rowExtent)
    rect

  private[ui] def child(r: Rect, vert: Boolean): Ui = new Ui(ctx, r, vert, nextId())
  private[ui] def commit(c: Ui): Unit =
    val u = c.usedRect
    if vertical then { cursor = Point(region.x0, u.y1 + spacing.y); maxX = math.max(maxX, u.x1); maxY = math.max(maxY, u.y1) }
    else { cursor = Point(u.x1 + spacing.x, cursor.y); rowExtent = math.max(rowExtent, u.height); maxX = math.max(maxX, u.x1); maxY = math.max(maxY, cursor.y + rowExtent) }

  // --- layout containers ---

  def horizontal(f: Ui => Unit): Unit =
    val c = child(Rect(cursor.x, cursor.y, region.x1, region.y1), false)
    f(c)
    val u = c.usedRect
    allocate(math.max(0, u.width), math.max(c.rowExtent, u.height))

  def vertical(f: Ui => Unit): Unit =
    val c = child(Rect(cursor.x, cursor.y, if vertical then region.x1 else math.max(cursor.x + 1, region.x1), region.y1), true)
    f(c)
    val u = c.usedRect
    allocate(u.width, u.height)

  def indent(f: Ui => Unit): Unit =
    val c = child(Rect(cursor.x + style.indent, cursor.y, region.x1, region.y1), true)
    f(c)
    val u = c.usedRect
    if vertical then { cursor = Point(region.x0, u.y1 + spacing.y); maxX = math.max(maxX, u.x1); maxY = math.max(maxY, u.y1) }

  def space(amount: Double): Unit = if vertical then cursor = Point(cursor.x, cursor.y + amount) else cursor = Point(cursor.x + amount, cursor.y)

  /** Fills the remaining width/height region split: draws a top panel of `height` and continues below it. */
  def topPanel(height: Double)(f: Ui => Unit): Unit =
    val r = Rect(region.x0 - ctx.padX, cursor.y, region.x1 + ctx.padX, cursor.y + height)
    ctx.canvas.fillRect(r, style.panel)
    ctx.canvas.fillRect(Rect(r.x0, r.y1 - 1, r.x1, r.y1), style.border)
    val c = child(Rect(region.x0, r.y0 + 4, region.x1, r.y1 - 4), false); f(c)
    cursor = Point(region.x0, r.y1 + spacing.y); maxY = math.max(maxY, r.y1)

  /** Group with a frame drawn behind it (sized from the previous frame, so it lags by one frame on first appearance). */
  def group(f: Ui => Unit): Unit =
    val id = nextId()
    val pad = 6.0
    val last = ctx.lastSizes.getOrElse(id, Size(0, 0))
    val x0 = cursor.x; val y0 = cursor.y
    val w = if vertical then math.max(last.width, availableWidth - 2 * pad) + 2 * pad else last.width + 2 * pad
    ctx.box(Rect(x0, y0, x0 + w, y0 + last.height + 2 * pad), style.rounding + 1, style.faint, style.border)
    val c = child(Rect(x0 + pad, y0 + pad, region.x1 - pad, region.y1), true)
    f(c)
    val u = c.usedRect
    ctx.lastSizes(id) = Size(u.width, math.max(0, u.y1 - (y0 + pad)))
    val sz = ctx.lastSizes(id)
    allocate(sz.width + 2 * pad, sz.height + 2 * pad)

  // --- panels (call before the central content, like egui) ---

  /** Top of a panel: flush under whatever was placed above, or over the window margin when it is first. */
  private def panelTop: Double = if cursor.y <= _region.y0 + 0.5 then _region.y0 - ctx.padY else cursor.y - spacing.y
  private def firstInRegion: Boolean = cursor.y <= _region.y0 + 0.5

  private def carve(r: Rect, newRegion: Rect, f: Ui => Unit): Unit =
    ctx.canvas.fillRect(r, style.panel)
    val c = child(Rect(r.x0 + ctx.padX, r.y0 + (if firstInRegion then ctx.padY else 0.0) + 10, r.x1 - ctx.padX, r.y1), true)
    f(c)
    _region = newRegion
    cursor = _region.origin
    maxX = math.max(maxX, r.x1); maxY = math.max(maxY, r.y1)

  /** A panel of `width` along the left edge; the remaining region starts to its right. */
  def leftPanel(width: Double)(f: Ui => Unit): Unit =
    val top = panelTop; val first = firstInRegion
    val r = Rect(_region.x0 - ctx.padX, top, _region.x0 + width, _region.y1)
    carve(r, Rect(r.x1 + ctx.padX, (if first then _region.y0 else top) + 10, _region.x1, _region.y1), f)
    ctx.canvas.fillRect(Rect(r.x1 - 1, r.y0, r.x1, r.y1), style.border)

  def rightPanel(width: Double)(f: Ui => Unit): Unit =
    val top = panelTop; val first = firstInRegion
    val r = Rect(_region.x1 - width, top, _region.x1 + ctx.padX, _region.y1)
    carve(r, Rect(_region.x0, if first then _region.y0 else cursor.y, r.x0 - ctx.padX, _region.y1), f)
    ctx.canvas.fillRect(Rect(r.x0, r.y0, r.x0 + 1, r.y1), style.border)

  /** A panel of `height` along the bottom edge; the remaining region ends above it. */
  def bottomPanel(height: Double)(f: Ui => Unit): Unit =
    val r = Rect(_region.x0 - ctx.padX, _region.y1 - height, _region.x1 + ctx.padX, _region.y1 + ctx.padY)
    ctx.canvas.fillRect(r, style.panel)
    ctx.canvas.fillRect(Rect(r.x0, r.y0, r.x1, r.y0 + 1), style.border)
    val c = child(Rect(_region.x0, r.y0 + 8, _region.x1, r.y1), true)
    f(c)
    _region = Rect(_region.x0, _region.y0, _region.x1, r.y0 - 8)

  /** A full-width bar of `height` at the bottom of the region with `fill` background; the remaining region ends above it. */
  def bottomBar(height: Double, fill: Color)(f: Ui => Unit): Unit =
    val r = Rect(_region.x0 - ctx.padX, _region.y1 - height, _region.x1 + ctx.padX, _region.y1)
    ctx.canvas.fillRect(r, fill)
    ctx.canvas.fillRect(Rect(r.x0, r.y0, r.x1, r.y0 + 1), style.border)
    val c = child(Rect(_region.x0 + 2, r.y0 + (height - 18) / 2, _region.x1, r.y1), false)
    f(c)
    _region = Rect(_region.x0, _region.y0, _region.x1, r.y0)

  // --- state ---

  /** Runs `body`; when `cond` is true its widgets are inert and drawn dimmed. */
  def disabled(cond: Boolean)(body: Ui => Unit): Unit =
    if !cond then body(this)
    else
      val c = child(Rect(cursor.x, cursor.y, _region.x1, _region.y1), vertical)
      ctx.disabledDepth += 1
      try body(c) finally ctx.disabledDepth -= 1
      commit(c)
      val u = c.usedRect
      if u.width > 0 && u.height > 0 then ctx.canvas.fillRect(u.inflate(2, 2), style.background.withAlpha(0.55f))

  /** Eases a value towards `target` over about `seconds`; asks for repaints until it settles. */
  def animate(target: Double, seconds: Double = 0.12): Double = ctx.animate(nextId(), target, seconds)
  def animate(target: Boolean): Double = animate(if target then 1.0 else 0.0)

  // --- text widgets ---

  def label(text: String, color: Option[Color] = None, size: Option[Double] = None, mono: Boolean = false, weight: Weight = Weight.Regular, tracking: Double = 0.0): Response =
    val sz = size.getOrElse(if mono then style.monoSize else style.fontSize)
    var l = ctx.layout(text, sz, mono, None, weight, tracking)
    if vertical && l.width > availableWidth && availableWidth > 10 then l = ctx.layout(text, sz, mono, Some(availableWidth), weight, tracking)
    val r = allocate(l.width, l.height)
    ctx.paintText(l, r.origin, color.getOrElse(style.text))
    ctx.access(scalaui.window.AccessRole.Label, text, r)
    Response(r)

  def heading(text: String): Response =
    val rung = style.heading
    val r = label(text, Some(style.textStrong), Some(rung.size), weight = rung.weight, tracking = rung.trackingPx)
    ctx.accessNodes.lastOption.foreach(n => ctx.accessNodes(ctx.accessNodes.length - 1) = n.copy(role = scalaui.window.AccessRole.Heading))
    r
  def weak(text: String): Response = label(text, Some(style.textWeak))
  def monospace(text: String): Response = label(text, mono = true)
  /** Small, slightly open, medium-weight text in the weak color: group titles and section captions. */
  def caption(text: String): Response =
    val rung = style.caption
    label(text, Some(style.groupLabel), Some(rung.size), weight = rung.weight, tracking = rung.trackingPx)
  /** Medium-weight text in the strong color. */
  def strong(text: String): Response = label(text, Some(style.textStrong), weight = Weight.Medium)

  def separator(): Unit =
    if vertical then { val r = allocate(availableWidth, 1); ctx.canvas.fillRect(r, style.border) }
    else { val r = allocate(1, style.minHeight - 6); ctx.canvas.fillRect(r, style.border) }

  // --- images and custom painting ---

  def image(texture: scalaui.gpu.Texture, size: Size, tint: Color = Color.White): Response =
    val rect = allocate(size.width, size.height)
    ctx.canvas.drawImage(texture, rect, tint = tint)
    Response(rect)

  /** Draws an SVG at `size` logical points, rasterized crisply for the current display scale. */
  def svg(doc: scalaui.svg.SvgDocument, size: Size, tint: Color = Color.White): Response =
    val rect = allocate(size.width, size.height)
    val sc = ctx.canvas.scaleFactor
    val tex = ctx.svgTexture(doc, math.max(1, math.round(size.width * sc).toInt), math.max(1, math.round(size.height * sc).toInt))
    ctx.canvas.drawImage(tex, rect, tint = tint)
    Response(rect)

  /** A line icon `size` points square, tinted `color` (default: the icon color). */
  def icon(icon: Icon, size: Double = 16, color: Option[Color] = None): Response =
    val rect = allocate(size, size)
    ctx.paintIcon(icon, rect, color.getOrElse(style.icon))
    Response(rect)

  /** Reserves a `w x h` area (e.g. a document canvas) and returns its interaction state; paint into `ctx.canvas` within `response.rect`.
    * Drag/scroll deltas are on the response; content is clipped to the rect while `paint` runs.
    */
  def custom(w: Double, h: Double, drag: Boolean = true)(paint: (scalaui.gpu.Canvas, Rect, Response) => Unit): Response =
    val rect = allocate(w, h)
    val r = ctx.interact(rect, nextId(), drag)
    ctx.pushClip(rect)
    paint(ctx.canvas, rect, r)
    ctx.popClip()
    r

  // --- buttons and toggles ---

  def button(text: String): Response = button(text, ButtonKind.Default)
  def button(text: String, fill: Option[Color]): Response =
    val l = ctx.layout(text, style.fontSize, weight = Weight.Medium)
    val pad = style.buttonPadding
    val rect = allocate(l.width + 2 * pad.x + 8, math.max(l.height + 2 * pad.y, style.minHeight))
    val id = nextId()
    val r = ctx.interact(rect, id, focusable = true)
    val base = fill.getOrElse(style.widget)
    val fillNow = if r.pressed then Style.mix(base, style.text, 0.2) else Style.mix(base, style.text, 0.12 * ctx.hoverAmount(id, r.hovered))
    ctx.box(rect, style.rounding, fillNow, Style.mix(style.border, style.borderHover, ctx.hoverAmount(id, r.hovered)))
    ctx.paintText(l, Point(rect.x0 + (rect.width - l.width) / 2, rect.y0 + (rect.height - l.height) / 2), style.textStrong)
    ctx.access(scalaui.window.AccessRole.Button, text, rect)
    r

  /** A button of the given `kind`, optionally with a leading icon. */
  def button(text: String, kind: ButtonKind, icon: Option[Icon] = None): Response =
    val l = ctx.layout(text, style.fontSize, weight = Weight.Medium)
    val pad = style.buttonPadding
    val iconW = if icon.isDefined then (if text.isEmpty then 16.0 else 22.0) else 0.0
    val rect = allocate(l.width + iconW + 2 * pad.x + (if text.isEmpty then 0 else 8), math.max(l.height + 2 * pad.y, style.minHeight))
    val id = nextId()
    val r = ctx.interact(rect, id, focusable = true)
    val h = ctx.hoverAmount(id, r.hovered)
    val (fill, border, fg) = kind match
      case ButtonKind.Default =>
        (if r.pressed then style.widgetActive else Style.mix(style.widget, style.widgetHover, h), Style.mix(style.border, style.borderHover, h), style.textStrong)
      case ButtonKind.Primary =>
        val hov = if style.dark then Color.White else Color.Black
        (if r.pressed then Style.mix(style.accent, hov, 0.22) else Style.mix(style.accent, hov, 0.10 * h), Color.Transparent, style.onAccent)
      case ButtonKind.Danger =>
        val hov = if style.dark then Color.White else Color.Black
        (if r.pressed then Style.mix(style.danger, hov, 0.22) else Style.mix(style.danger, hov, 0.10 * h), Color.Transparent, if style.dark then Color.rgb8(36, 12, 12) else Color.White)
      case ButtonKind.Subtle =>
        (if r.pressed then style.widgetActive else if h > 0 then Style.mix(style.background, style.widgetHover, h) else Color.Transparent, Color.Transparent, style.textStrong)
    if fill.a > 0 then ctx.box(rect, style.rounding, fill, border)
    val total = l.width + iconW
    var x = rect.x0 + (rect.width - total) / 2
    icon.foreach { i => ctx.paintIcon(i, Rect(x, rect.center.y - 8, x + 16, rect.center.y + 8), fg); x += iconW }
    if text.nonEmpty then ctx.paintText(l, Point(x, rect.y0 + (rect.height - l.height) / 2), fg)
    ctx.access(scalaui.window.AccessRole.Button, text, rect)
    r

  /** The filled accent button for an area's main action. */
  def primaryButton(text: String, icon: Option[Icon] = None): Response = button(text, ButtonKind.Primary, icon)

  def checkbox(value: Var[Boolean], label: String): Response =
    val l = ctx.layout(label, style.fontSize)
    val box = 16.0
    val rect = allocate(box + 8 + l.width, math.max(l.height, box))
    val id = nextId()
    var r = ctx.interact(rect, id, focusable = true)
    if r.clicked then { value.value = !value.value; r = r.copy(changed = true) }
    val b = Rect(rect.x0, rect.y0 + (rect.height - box) / 2, rect.x0 + box, rect.y0 + (rect.height + box) / 2)
    val on = ctx.animate(id, if value.value then 1.0 else 0.0, 0.12)
    val h = ctx.hoverAmount(id, r.hovered)
    ctx.box(b, 4, Style.mix(Style.mix(style.inputBackground, style.widgetHover, h * 0.6), style.accent, on),
      Style.mix(Style.mix(style.inputBorder, style.accent, h), style.accent, on))
    if on > 0.05 then
      val c = style.onAccent.withAlpha(on.toFloat)
      ctx.canvas.strokeLine(Point(b.x0 + 3.6, b.y0 + 8.4), Point(b.x0 + 6.8, b.y0 + 11.6), 1.9, c)
      ctx.canvas.strokeLine(Point(b.x0 + 6.8, b.y0 + 11.6), Point(b.x0 + 12.4, b.y0 + 4.8), 1.9, c)
    ctx.paintText(l, Point(rect.x0 + box + 8, rect.y0 + (rect.height - l.height) / 2), style.text)
    ctx.access(scalaui.window.AccessRole.CheckBox, label, rect, checked = Some(value.value))
    r

  def radio[T](selected: Var[T], value: T, label: String): Response =
    val l = ctx.layout(label, style.fontSize)
    val box = 16.0
    val rect = allocate(box + 8 + l.width, math.max(l.height, box))
    val id = nextId()
    var r = ctx.interact(rect, id, focusable = true)
    if r.clicked && selected.value != value then { selected.value = value; r = r.copy(changed = true) }
    val c = Point(rect.x0 + box / 2, rect.y0 + rect.height / 2)
    val on = ctx.animate(id, if selected.value == value then 1.0 else 0.0, 0.12)
    val h = ctx.hoverAmount(id, r.hovered)
    ctx.canvas.fillCircle(c, box / 2, Style.mix(Style.mix(style.inputBorder, style.accent, h), style.accent, on))
    ctx.canvas.fillCircle(c, box / 2 - 1, Style.mix(style.inputBackground, style.widgetHover, h * 0.6))
    if on > 0.02 then
      ctx.canvas.fillCircle(c, box / 2 - 1, style.accent.withAlpha(on.toFloat))
      ctx.canvas.fillCircle(c, 3.0 + 0.0 * on, style.onAccent.withAlpha(on.toFloat))
    ctx.paintText(l, Point(rect.x0 + box + 8, rect.y0 + (rect.height - l.height) / 2), style.text)
    ctx.access(scalaui.window.AccessRole.RadioButton, label, rect, checked = Some(selected.value == value))
    r

  def slider(value: Var[Double], min: Double, max: Double, label: String = ""): Response =
    val l = ctx.layout(label, style.fontSize)
    val valueText = ctx.layout(f"${value.value}%.2f", style.fontSize)
    val sw = style.sliderWidth
    val rect = allocate(sw + 10 + valueText.width + (if label.isEmpty then 0 else 6 + l.width), style.minHeight)
    val track = Rect(rect.x0, rect.y0, rect.x0 + sw, rect.y1)
    val id = nextId()
    var r = ctx.interact(track, id, drag = true, focusable = true)
    if r.dragged || r.clicked || (r.pressed && r.hovered) then
      ctx.input.mouse.foreach { m =>
        val t = ((m.x - track.x0 - 8) / (sw - 16)).max(0).min(1)
        val v = min + t * (max - min)
        if v != value.value then { value.value = v; r = r.copy(changed = true) }
      }
    if r.focused && !r.pressed then
      // keyboard: arrows nudge by 1% of the range (10% with Shift), Home/End jump to the ends
      val step = (max - min) / 100 * (if ctx.input.mods.shift then 10 else 1)
      def set(v: Double): Unit = { val c = v.max(min).min(max); if c != value.value then { value.value = c; r = r.copy(changed = true) } }
      if ctx.takeKey(Key.Right) || ctx.takeKey(Key.Up) then set(value.value + step)
      else if ctx.takeKey(Key.Left) || ctx.takeKey(Key.Down) then set(value.value - step)
      else if ctx.takeKey(Key.Home) then set(min)
      else if ctx.takeKey(Key.End) then set(max)
    val t = ((value.value - min) / (max - min)).max(0).min(1)
    val cy = track.center.y
    val hx = track.x0 + 8 + t * (sw - 16)
    val grow = ctx.hoverAmount(id, r.hovered || r.pressed)
    ctx.canvas.fillRoundedRect(Rect(track.x0 + 2, cy - 2, track.x1 - 2, cy + 2), 2, style.borderStrong)
    ctx.canvas.fillRoundedRect(Rect(track.x0 + 2, cy - 2, hx, cy + 2), 2, style.accent)
    val rad = 7.0 + grow
    ctx.canvas.fillCircle(Point(hx, cy + 0.8), rad + 1.5, Color(0, 0, 0, 0.16f))
    ctx.canvas.fillCircle(Point(hx, cy), rad + 1, style.accent)
    ctx.canvas.fillCircle(Point(hx, cy), rad - 1.5, if style.dark then Color.rgb8(236, 236, 240) else Color.White)
    ctx.paintText(valueText, Point(track.x1 + 10, rect.y0 + (rect.height - valueText.height) / 2), style.textWeak)
    if label.nonEmpty then ctx.paintText(l, Point(track.x1 + 10 + valueText.width + 6, rect.y0 + (rect.height - l.height) / 2), style.text)
    ctx.access(scalaui.window.AccessRole.Slider, label, rect, value = f"${value.value}%.2f")
    r

  def progressBar(fraction: Double, width: Double = 200): Unit =
    val l = ctx.layout(f"${fraction * 100}%.0f%%", style.fontSize - 1, weight = Weight.Medium)
    val rect = allocate(width, math.max(l.height, 8))
    val bar = Rect(rect.x0, rect.center.y - 4, rect.x1 - l.width - 10, rect.center.y + 4)
    ctx.canvas.fillRoundedRect(bar, 4, style.borderStrong.withAlpha(if style.dark then 0.6f else 0.55f))
    val w = bar.width * fraction.max(0).min(1)
    if w > 1 then ctx.canvas.fillRoundedRect(Rect(bar.x0, bar.y0, bar.x0 + math.max(w, 8), bar.y1), 4, style.accent)
    ctx.paintText(l, Point(rect.x1 - l.width, rect.y0 + (rect.height - l.height) / 2), style.textWeak)
    ctx.access(scalaui.window.AccessRole.ProgressBar, "", rect, value = f"${fraction * 100}%.0f%%")

  // --- collapsing / combo ---

  def collapsing(title: String, defaultOpen: Boolean = false)(body: Ui => Unit): Unit =
    val id = nextId()
    val open = ctx.flags.getOrElse(id, defaultOpen)
    val l = ctx.layout(title, style.fontSize, weight = Weight.Medium)
    val rect = allocate(math.max(availableWidth, 40), math.max(l.height, style.minHeight))
    val r = ctx.interact(rect, id, focusable = true)
    if r.clicked then ctx.flags(id) = !open
    val col = Style.mix(style.text, style.textStrong, ctx.hoverAmount(id, r.hovered))
    ctx.paintIcon(if open then Icons.chevronDown else Icons.chevronRight, Rect(rect.x0, rect.center.y - 8, rect.x0 + 16, rect.center.y + 8), col)
    ctx.paintText(l, Point(rect.x0 + 20, rect.y0 + (rect.height - l.height) / 2), col)
    ctx.access(scalaui.window.AccessRole.Button, title, rect, checked = Some(open))
    if open then indent(body)

  def comboBox(selected: Var[Int], options: Seq[String], width: Double = 160): Response =
    val id = nextId()
    val rect = allocate(width, style.minHeight)
    val isOpen = ctx.popupOwner == id
    val r = ctx.interact(rect, id, focusable = true)
    if r.clicked then { ctx.popupOwner = if isOpen then 0L else id }
    val h = ctx.hoverAmount(id, r.hovered)
    ctx.box(rect, style.rounding, Style.mix(style.inputBackground, style.widgetHover, h * 0.5),
      if isOpen then style.accent else Style.mix(style.inputBorder, style.borderHover, h))
    if isOpen then ctx.focusRing(rect, style.rounding)
    val l = ctx.layout(options.lift(selected.value).getOrElse(""), style.fontSize)
    ctx.paintText(l, Point(rect.x0 + 8, rect.y0 + (rect.height - l.height) / 2), style.textStrong)
    ctx.paintIcon(Icons.chevronDown, Rect(rect.x1 - 22, rect.center.y - 7, rect.x1 - 8, rect.center.y + 7), style.textWeak)
    ctx.access(scalaui.window.AccessRole.ComboBox, "", rect, value = options.lift(selected.value).getOrElse(""))
    if ctx.popupOwner == id then
      ctx.defer { () =>
        val itemH = style.minHeight
        val pop = Rect(rect.x0, rect.y1 + 4, rect.x1, rect.y1 + 4 + itemH * options.size + 8)
        popupFrame(pop)
        val m = ctx.input.mouse
        options.zipWithIndex.foreach { (o, i) =>
          val ir = Rect(pop.x0 + 4, pop.y0 + 4 + i * itemH, pop.x1 - 4, pop.y0 + 4 + (i + 1) * itemH)
          val hov = m.exists(ir.contains)
          if hov then ctx.canvas.fillRoundedRect(ir, style.rounding - 1, style.widgetHover)
          else if i == selected.value then ctx.canvas.fillRoundedRect(ir, style.rounding - 1, style.checked)
          val ol = ctx.layout(o, style.fontSize, weight = if i == selected.value then Weight.Medium else Weight.Regular)
          ctx.paintText(ol, Point(ir.x0 + 8, ir.y0 + (ir.height - ol.height) / 2), if hov || i == selected.value then style.textStrong else style.text)
          if i == selected.value then ctx.paintIcon(Icons.check, Rect(ir.x1 - 22, ir.center.y - 7, ir.x1 - 8, ir.center.y + 7), style.accentBright)
          if hov && ctx.input.released && ctx.popupOwner == id then { selected.value = i; ctx.popupOwner = 0L }
        }
        if ctx.input.pressed && !m.exists(p => pop.contains(p) || rect.contains(p)) then ctx.popupOwner = 0L
      }
    r

  /** Draws the shadow, background and border of a popup (menu, combo list, tooltip) and marks it as an overlay area. */
  private[ui] def popupFrame(pop: Rect): Unit =
    ctx.shadow(pop, style.rounding + 1, 16, style.shadow, Vec2(0, 5))
    ctx.box(pop, style.rounding + 1, style.menu, style.borderStrong)
    ctx.cover(pop)

  // --- scrolling ---

  /** A fixed-height viewport onto taller content, scrolled by wheel/trackpad and a draggable bar. */
  def scrollArea(height: Double, width: Option[Double] = None)(body: Ui => Unit): Unit =
    val id = nextId()
    val w = width.getOrElse(if vertical then availableWidth else 300.0)
    val view = allocate(w, height)
    val contentH = ctx.numbers.getOrElse(id, 0.0)
    val maxScroll = math.max(0, contentH - height)
    var off = ctx.numbers.getOrElse(id + 1, 0.0).max(0).min(maxScroll)
    if ctx.hoverable(view, id) && !ctx.wheelClaimed(ctx.input.scroll.y) then off = (off - ctx.input.scroll.y).max(0).min(maxScroll)
    val barW = 6.0
    val showBar = maxScroll > 0
    val barRect = Rect(view.x1 - barW, view.y0, view.x1, view.y1)
    var barHot = false
    if showBar then
      val br = ctx.interact(barRect.inflate(4, 0), id + 2, drag = true)
      barHot = br.hovered || br.pressed
      if br.dragged then ctx.input.mouse.foreach(m => off = ((m.y - view.y0) / height * contentH - height / 2).max(0).min(maxScroll))
    ctx.numbers(id + 1) = off
    ctx.pushClip(view)
    val c = child(Rect(view.x0, view.y0 - off, view.x1 - (if showBar then barW + 4 else 0), view.y0 - off + 100000), true)
    body(c)
    if math.abs(ctx.numbers.getOrElse(id, 0.0) - c.usedRect.height) > 0.5 then ctx.requestRepaint()
    ctx.numbers(id) = c.usedRect.height
    ctx.popClip()
    if showBar then
      val thumbH = math.max(24, height * height / contentH)
      val ty = view.y0 + (if maxScroll == 0 then 0 else off / maxScroll * (height - thumbH))
      val hot = ctx.hoverAmount(id + 2, barHot || ctx.hoverable(view, id), 0.15)
      val tw = 4.0 + 2.0 * (if barHot then 1 else 0)
      ctx.canvas.fillRoundedRect(Rect(barRect.x1 - tw, ty, barRect.x1, ty + thumbH), tw / 2, style.text.withAlpha((0.22 + 0.28 * hot).toFloat))

  // --- text editing ---

  /** A text field. Multiline fields wrap (unless `wrap = false`), grow with their content from `rows` up to `maxRows` (0 = unbounded) and
    * then scroll vertically; `lineNumbers` adds a gutter, Tab inserts `tab` (Shift-Tab dedents; with several lines selected they are
    * indented) unless `acceptTab = false` lets Tab move focus, and Enter keeps the indentation of the current line. */
  def textEdit(value: Var[String], multiline: Boolean = false, width: Option[Double] = None, rows: Int = 1, mono: Boolean = false, hint: String = "",
      maxRows: Int = 0, lineNumbers: Boolean = false, wrap: Boolean = true, acceptTab: Boolean = true, tab: String = "    "): Response =
    val id = nextId()
    val w = width.getOrElse(if vertical && multiline then availableWidth else style.textEditWidth)
    val sz = if mono then style.monoSize else style.fontSize
    val padX = 6.0; val padY = 3.0
    val lineCount = if lineNumbers && multiline then value.value.count(_ == '\n') + 1 else 0
    val gutter = if lineCount > 0 then lineCount.toString.length.max(2) * ctx.layout("0", sz, mono).width + 16 else 0.0
    val inner = w - 2 * padX - gutter
    val doWrap = multiline && wrap
    val layout = ctx.layout(value.value + (if value.value.endsWith("\n") then " " else ""), sz, mono, if doWrap then Some(inner) else None)
    val lineH = ctx.layout("Ag", sz, mono).height
    val h =
      if multiline then
        val content = math.max(rows * lineH, layout.height)
        (if maxRows > 0 then math.min(content, math.max(maxRows, 1) * lineH) else content) + 2 * padY
      else math.max(style.minHeight, lineH + 2 * padY)
    val viewH = h - 2 * padY
    val rect = allocate(w, h)
    val st = ctx.edits.getOrElseUpdate(id, new EditState(value.value.length, value.value.length))
    val focused = ctx.focusId == id
    ctx.registerFocus(id)
    var r = ctx.interact(rect, id, drag = true)
    if multiline && layout.height > viewH then
      val maxY = layout.height - viewH
      // tell enclosing scroll areas (next frame) whether this field still consumes the wheel in either direction
      ctx.claimWheel(rect, st.scrollY > 0.01, st.scrollY < maxY - 0.01)
      if r.scroll.y != 0 && (if r.scroll.y > 0 then st.scrollY > 0.01 else st.scrollY < maxY - 0.01) then
        st.scrollY = (st.scrollY - r.scroll.y).max(0).min(maxY)
    if r.hovered then ctx.cursorIcon = scalaui.window.CursorIcon.Text
    var edit = Edit(value.value, st.cursor, st.anchor).clamp
    val before = edit.text
    // assistive technologies (AT-SPI EditableText / Text selection) edit the field through the published node index
    val accessIndex = ctx.accessNodes.length
    ctx.accessEdits.remove(accessIndex).foreach { (newText, sel) =>
      newText.foreach { t => val prev = edit; edit = Edit(t, t.length, t.length); st.record(prev, 1, true); value.value = t; r = r.copy(changed = true) }
      sel.foreach { (a, b) => edit = edit.copy(cursor = b, anchor = a).clamp }
    }

    val origin = Point(rect.x0 + padX + gutter - st.scrollX, rect.y0 + padY - st.scrollY)
    def pointIndex(m: Point): Int = layout.indexAt(m.x - origin.x, m.y - origin.y)
    if r.hovered && ctx.input.pressed then
      ctx.focusId = id; ctx.focusClaimed = true
      ctx.input.mouse.foreach { m =>
        val i = pointIndex(m)
        edit = if ctx.input.doubleClicked then edit.selectWordAt(i) else edit.moveTo(i, ctx.input.mods.shift)
      }
    else if r.dragged && focused then ctx.input.mouse.foreach { m =>
      if multiline then
        if m.y < rect.y0 then st.scrollY = (st.scrollY - 6).max(0)
        else if m.y > rect.y1 then st.scrollY = (st.scrollY + 6).min(math.max(0, layout.height - viewH))
      edit = edit.moveTo(pointIndex(m), true)
    }

    if ctx.focusId == id then
      ctx.requestRepaint()
      ctx.input.keys.foreach { k =>
        val shift = k.mods.shift
        val mac = scala.util.Properties.isMac
        val word = if mac then k.mods.alt else k.mods.control
        val line = if mac then k.mods.command else false
        val prevEdit = edit
        var kind = 0          // 1 typing, 2 deleting; forceStep: paste/cut/enter are always their own undo step
        var forceStep = false
        k.key match
          case Key.Left | Key.Right if !line && !word && (!edit.hasSelection || shift) && edit.text == value.value &&
              layout.moveVisual(edit.cursor, k.key == Key.Right) >= 0 =>
            // arrows move visually inside bidirectional lines
            edit = edit.moveTo(layout.moveVisual(edit.cursor, k.key == Key.Right), shift)
          case Key.Home if multiline && k.mods.shortcut => edit = edit.moveTo(0, shift)
          case Key.End if multiline && k.mods.shortcut => edit = edit.moveTo(edit.text.length, shift)
          case Key.Up if multiline && line => edit = edit.moveTo(0, shift)
          case Key.Down if multiline && line => edit = edit.moveTo(edit.text.length, shift)
          case Key.PageUp | Key.PageDown if multiline =>
            val (cx, top, lh) = layout.caret(edit.cursor)
            val d = math.max(lh, viewH - lh) * (if k.key == Key.PageUp then -1 else 1)
            edit = edit.moveTo(layout.indexAt(cx, top + lh / 2 + d), shift)
            st.scrollY = (st.scrollY + d).max(0).min(math.max(0, layout.height - viewH))
          case Key.Tab if multiline && acceptTab =>
            ctx.tabConsumed = true
            edit = if shift then edit.dedent(tab) else edit.indent(tab); kind = 1; forceStep = true
          case Key.Tab => ()
          case Key.Left => edit = if line then edit.lineStart(shift) else edit.left(shift, word)
          case Key.Right => edit = if line then edit.lineEnd(shift) else edit.right(shift, word)
          case Key.Home => edit = edit.lineStart(shift)
          case Key.End => edit = edit.lineEnd(shift)
          case Key.Up if multiline =>
            val (cx, top, lh) = layout.caret(edit.cursor); edit = edit.moveTo(layout.indexAt(cx, top - lh / 2), shift)
          case Key.Down if multiline =>
            val (cx, top, lh) = layout.caret(edit.cursor); edit = edit.moveTo(layout.indexAt(cx, top + lh * 1.5), shift)
          case Key.Backspace => edit = edit.backspace(word || line); kind = 2
          case Key.Delete => edit = edit.delete(word); kind = 2
          case Key.Enter => if multiline then { edit = edit.newlineIndent; kind = 1; forceStep = true } else ctx.focusId = 0
          case Key.Escape => ctx.focusId = 0
          case _ if k.mods.shortcut =>
            k.chars.toLowerCase match
              case "a" => edit = edit.selectAll
              case "c" => if edit.hasSelection then Clipboard.setText(edit.selected)
              case "x" => if edit.hasSelection then { Clipboard.setText(edit.selected); edit = edit.deleteSelection; kind = 2; forceStep = true }
              case "v" => Clipboard.getText().foreach { t => edit = edit.insert(if multiline then t else t.replace("\n", " ").replace("\r", "")); kind = 1; forceStep = true }
              case "z" if !shift => st.undo.lastOption.foreach { u => st.undo.remove(st.undo.length - 1); st.redo += edit; edit = u; st.lastKind = 0 }
              case "z" | "y" => st.redo.lastOption.foreach { u => st.redo.remove(st.redo.length - 1); st.undo += edit; edit = u; st.lastKind = 0 }
              case _ => ()
          case _ => if k.text.nonEmpty then { edit = edit.insert(k.text); kind = 1 }
        if edit.text != prevEdit.text && kind != 0 then st.record(prevEdit, kind, forceStep)
        else if edit.text == prevEdit.text && kind == 0 then st.lastKind = st.lastKind // navigation keeps the current group open
        // Key.Space arrives as printable text; handled by the fallthrough above only when not matched
      }
      if edit.text != before then { value.value = edit.text; r = r.copy(changed = true) }
    st.cursor = edit.cursor; st.anchor = edit.anchor

    // paint
    val comp = if ctx.focusId == id then ctx.input.composition else None
    val shown = comp match
      case Some((c, _, _)) => value.value.substring(0, edit.selStart) + c + value.value.substring(edit.selEnd)
      case None => value.value
    val showLayout = if shown == layout.text.stripSuffix(" ") || layout.text == shown then layout else ctx.layout(shown, sz, mono, if multiline then Some(inner) else None)
    // while composing, the caret sits inside the marked text
    val caretIndex = comp.fold(edit.cursor) { case (c, a, _) => edit.selStart + a.min(c.length) }
    val hot = ctx.hoverAmount(id, r.hovered)
    ctx.box(rect, style.rounding, style.inputBackground, if ctx.focusId == id then style.accent else Style.mix(style.inputBorder, style.borderHover, hot))
    if ctx.focusId == id then ctx.focusRing(rect, style.rounding)
    ctx.pushClip(rect.inflate(-1, -1))
    if multiline then
      val (_, ct, ch) = showLayout.caret(caretIndex)
      if edit.cursor != st.lastCaret || value.value.length != st.lastLen then
        if ct < st.scrollY then st.scrollY = ct
        else if ct + ch > st.scrollY + viewH then st.scrollY = ct + ch - viewH
      st.scrollY = st.scrollY.max(0).min(math.max(0, showLayout.height - viewH))
      st.lastCaret = edit.cursor; st.lastLen = value.value.length
    else st.scrollY = 0
    if !doWrap then
      val (cx, _, _) = showLayout.caret(caretIndex)
      if cx - st.scrollX > inner - 2 then st.scrollX = cx - inner + 2
      if cx - st.scrollX < 0 then st.scrollX = cx
      if showLayout.width <= inner then st.scrollX = 0
    val org = Point(rect.x0 + padX + gutter - st.scrollX, rect.y0 + padY - st.scrollY)
    if gutter > 0 then
      ctx.canvas.fillRect(Rect(rect.x0 + 1, rect.y0 + 1, rect.x0 + gutter, rect.y1 - 1), style.faint)
      ctx.canvas.fillRect(Rect(rect.x0 + gutter, rect.y0 + 1, rect.x0 + gutter + 1, rect.y1 - 1), style.border)
      var n = 0
      showLayout.lines.foreach { ln =>
        if ln.start == 0 || showLayout.text.charAt(ln.start - 1) == '\n' then
          n += 1
          val y = org.y + ln.top
          if y + ln.height >= rect.y0 && y <= rect.y1 then
            val nl = ctx.layout(n.toString, sz, mono)
            ctx.paintText(nl, Point(rect.x0 + gutter - 8 - nl.width, y + (ln.height - nl.height) / 2), style.textWeak)
      }
    ctx.pushClip(Rect(rect.x0 + 1 + (if gutter > 0 then gutter + 1 else 0), rect.y0 + 1, rect.x1 - 1, rect.y1 - 1))
    if ctx.focusId == id && edit.hasSelection && comp.isEmpty then
      showLayout.selectionRects(edit.selStart, edit.selEnd).foreach(sr => ctx.canvas.fillRect(sr.translate(org.toVec2), style.selection))
    if shown.isEmpty && hint.nonEmpty then ctx.paintText(ctx.layout(hint, sz, mono), org, style.textWeak)
    else ctx.paintText(showLayout, org, style.textStrong)
    comp.foreach { case (c, _, _) =>
      showLayout.selectionRects(edit.selStart, edit.selStart + c.length).foreach { sr =>
        val u = sr.translate(org.toVec2); ctx.canvas.fillRect(Rect(u.x0, u.y1 - 1.5, u.x1, u.y1), style.textStrong)
      }
    }
    if ctx.focusId == id then
      val (cx, ct, ch) = showLayout.caret(caretIndex)
      ctx.imeRect = Some(Rect(org.x + cx, org.y + ct, org.x + cx + 1.5, org.y + ct + ch))
    if ctx.focusId == id && ((ctx.time * 1.9).toLong % 2 == 0 || ctx.input.keys.nonEmpty || comp.nonEmpty) then
      val (cx, ct, ch) = showLayout.caret(caretIndex)
      ctx.canvas.fillRect(Rect(org.x + cx, org.y + ct, org.x + cx + 1.5, org.y + ct + ch), style.accentBright)
    ctx.popClip(); ctx.popClip()
    if multiline && showLayout.height > viewH then
      val thumbH = math.max(20, viewH * viewH / showLayout.height)
      val ty = rect.y0 + padY + st.scrollY / (showLayout.height - viewH) * (viewH - thumbH)
      ctx.canvas.fillRoundedRect(Rect(rect.x1 - 7, ty, rect.x1 - 3, ty + thumbH), 2, style.text.withAlpha(0.3f))
    // accessibility: text, caret and selection (character rectangles only for short focused fields, to keep frames cheap)
    val isFocused = ctx.focusId == id
    val charBounds = if isFocused && comp.isEmpty && value.value.length <= 256 then
      Vector.tabulate(value.value.length)(i => layout.selectionRects(i, i + 1).headOption.fold(rect)(_.translate(org.toVec2)))
    else Vector.empty
    ctx.access(scalaui.window.AccessRole.TextInput, hint, rect, value = value.value, focused = isFocused,
      text = Some(scalaui.window.AccessText(edit.cursor, edit.selStart, edit.selEnd, multiline, true, charBounds)))
    r.copy(focused = isFocused)
