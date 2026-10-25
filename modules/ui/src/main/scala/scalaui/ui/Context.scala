package scalaui.ui

import scalaui.color.Color
import scalaui.font.FontFamily
import scalaui.geom.*
import scalaui.gpu.{Canvas, GpuBackend}
import scalaui.shaping.Shaper
import scalaui.text.*
import scala.collection.mutable

/** A mutable cell the widgets read and write, e.g. `val name = Var("")`. */
final class Var[T](var value: T)
object Var:
  def apply[T](v: T): Var[T] = new Var(v)

/** What a widget reports back after being laid out and interacted with this frame. */
final case class Response(rect: Rect, hovered: Boolean = false, pressed: Boolean = false, clicked: Boolean = false,
    dragged: Boolean = false, changed: Boolean = false, focused: Boolean = false,
    dragDelta: Vec2 = Vec2.Zero, scroll: Vec2 = Vec2.Zero, mouse: Option[Point] = None, doubleClicked: Boolean = false,
    /** The right mouse button was pressed over the widget this frame (open a context menu). */
    secondaryClicked: Boolean = false,
    /** Target of the link that was activated this frame (see `Ui.richLinks`). */
    link: Option[String] = None)

/** Retained state of a text field: caret, selection anchor, horizontal scroll and the undo/redo history. */
final class EditState(var cursor: Int, var anchor: Int, var scrollX: Double = 0):
  var scrollY = 0.0
  var lastCaret = -1
  var lastLen = -1
  val undo = scala.collection.mutable.ArrayBuffer.empty[Edit]
  val redo = scala.collection.mutable.ArrayBuffer.empty[Edit]
  /** What the previous edit was (0 none, 1 typing, 2 deleting): consecutive edits of one kind form a single undo step. */
  var lastKind = 0

  def record(before: Edit, kind: Int, forceStep: Boolean): Unit =
    if forceStep || kind != lastKind then
      undo += before
      if undo.length > 200 then undo.remove(0)
    redo.clear()
    lastKind = kind

/** Per-app UI state: input, retained widget memory (focus, scroll, open sections), text caches and the canvas. */
final class Context(val backend: GpuBackend):
  var style: Style = Style.Dark
  /** Margin between the window edge and the root [[Ui]]'s content; panels extend over it. */
  var padX = 10.0
  var padY = 8.0
  /** Interface zoom (interface size setting): the UI is laid out in window points divided by this. */
  var zoom = 1.0
  /** Whether hover/press/open transitions animate (accessibility: reduced motion turns this off). */
  var animations = true
  val input = new Input
  val canvas = new Canvas(backend.whiteTexture)
  private def sansFamily(w: Weight): FontFamily = w match
    case Weight.Regular => FontFamily.Inter
    case Weight.Medium => FontFamily.InterMedium
    case Weight.SemiBold => FontFamily.InterSemiBold
  private val families = scala.collection.mutable.HashMap.empty[(Weight, Boolean, Boolean, Boolean), Shaper]
  /** Shaper for the bundled UI family (Inter, JetBrains Mono for code) in the requested style. Noto Sans and Hack fill in glyphs the
    * primary face lacks (more scripts, box drawing, symbols). */
  def shaper(bold: Boolean = false, italic: Boolean = false, monospace: Boolean = false, weight: Weight = Weight.Regular): Shaper =
    families.getOrElseUpdate((weight, bold, italic, monospace), new Shaper(
      if monospace then Seq(FontFamily.JetBrainsMono(bold, italic), FontFamily.Hack(bold, italic), FontFamily.NotoSans(bold, italic))
      else Seq(sansFamily(weight)(bold, italic), FontFamily.NotoSans(bold, italic), FontFamily.Hack(bold, false))))
  lazy val sans: Shaper = shaper()
  lazy val mono: Shaper = shaper(monospace = true)
  private val renderer = new TextRenderer(new GlyphAtlas(backend))

  // retained state keyed by widget id
  var activeId = 0L
  private var doubleId = 0L // widget whose press was the second click of a double click
  var focusId = 0L
  var popupOwner = 0L
  /** Ids of menu titles, so hovering another title while a menu is open switches menus. */
  val menuOwners = mutable.HashSet.empty[Long]
  val edits = mutable.HashMap.empty[Long, EditState]
  val flags = mutable.HashMap.empty[Long, Boolean]
  /** Retained text values keyed by widget id (search fields inside popups and the like). */
  val texts = mutable.HashMap.empty[Long, Var[String]]
  def textVar(id: Long): Var[String] = texts.getOrElseUpdate(id, Var(""))
  val numbers = mutable.HashMap.empty[Long, Double]
  private val sizes = mutable.HashMap.empty[Long, Size]
  /** Size a container had last frame (layout settles over two frames). Setting a different size schedules another frame. */
  object lastSizes:
    def getOrElse(id: Long, default: => Size): Size = sizes.getOrElse(id, default)
    def apply(id: Long): Size = sizes(id)
    def update(id: Long, s: Size): Unit =
      if sizes.get(id).forall(o => math.abs(o.width - s.width) > 0.5 || math.abs(o.height - s.height) > 0.5) then { sizes(id) = s; requestRepaint() }
  private var deferred = Vector.empty[() => Unit]
  private var clips = Vector.empty[Rect]
  var focusClaimed = false
  /** Focusable widgets of this frame in layout order (Tab / Shift-Tab traversal). */
  val focusOrder: mutable.ArrayBuffer[Long] = mutable.ArrayBuffer.empty
  private var overlayFocusStart = -1
  /** Set by modal dialogs: Tab then cycles only through widgets inside the overlay. */
  var focusTrap = false
  /** True after Tab navigation until the next mouse press: focused widgets draw a focus ring only then. */
  var keyboardNav = false
  /** A widget (multiline editor accepting Tab) handled this frame's Tab press itself. */
  var tabConsumed = false
  /** Adds `id` to this frame's Tab order. */
  def registerFocus(id: Long): Unit = focusOrder += id
  /** True if `id` has keyboard focus. */
  def hasFocus(id: Long): Boolean = focusId == id
  /** Moves keyboard focus to `id`. */
  def requestFocus(id: Long): Unit = { focusId = id; focusClaimed = true; requestRepaint() }

  /** Consumes the key press `chars` + the platform shortcut modifier (Cmd on macOS, Ctrl elsewhere), e.g. `ctx.shortcut("s")`. */
  def shortcut(chars: String, shift: Boolean = false, alt: Boolean = false): Boolean =
    val i = input.keys.indexWhere(k => k.mods.shortcut && k.mods.shift == shift && k.mods.alt == alt && k.chars.equalsIgnoreCase(chars))
    if i >= 0 then { input.keys.remove(i); true } else false
  /** Consumes a press of `key` with exactly the modifiers `mods`, e.g. `ctx.shortcut(Key.F5)`. */
  def shortcut(key: scalaui.window.Key, mods: scalaui.window.Modifiers): Boolean =
    val i = input.keys.indexWhere(k => k.key == key && k.mods == mods)
    if i >= 0 then { input.keys.remove(i); true } else false
  var repaintRequested = false
  /** Cursor shape requested during this frame (reset every frame); applied by the app runner. */
  var cursorIcon: scalaui.window.CursorIcon = scalaui.window.CursorIcon.Default
  /** Caret rectangle of the focused text field, for input-method candidate windows. */
  var imeRect: Option[Rect] = None
  /** True when the window shows a native menu bar, so [[Ui.appMenuBar]] draws nothing. */
  var nativeMenus = false
  /** Accessibility tree built by widgets during this frame, in reading order. */
  val accessNodes: mutable.ArrayBuffer[scalaui.window.AccessNode] = mutable.ArrayBuffer.empty
  /** Reports an element to the accessibility tree; elements fully clipped away are skipped. */
  def access(role: scalaui.window.AccessRole, label: String, rect: Rect, value: String = "", checked: Option[Boolean] = None, focused: Boolean = false,
      text: Option[scalaui.window.AccessText] = None): Unit =
    val visible = rect.intersect(clips.last)
    if visible.width > 0 && visible.height > 0 then
      accessNodes += scalaui.window.AccessNode(role, label, rect, value, checked, focused, disabledDepth > 0, text)
  /** Edits requested by assistive technologies, keyed by the node index of a text field: new text and/or a selection (anchor, caret).
    * Applied by the field on the next frame (see `Ui.textEdit`). */
  val accessEdits: mutable.HashMap[Int, (Option[String], Option[(Int, Int)])] = mutable.HashMap.empty
  val time0 = System.nanoTime()
  def time: Double = (System.nanoTime() - time0) / 1e9

  def requestRepaint(): Unit = repaintRequested = true

  /** Value being dragged between widgets (see `Ui.draggable` / `Ui.dropTarget`), cleared once the button is released. */
  var dragPayload: Option[Any] = None
  var dragSourceId = 0L

  /** Nesting depth of `Ui.disabled`: while positive, interaction is switched off. */
  var disabledDepth = 0
  private val animationValues = mutable.HashMap.empty[Long, Double]
  private var lastFrameTime = 0.0
  private var frameDt = 1.0 / 60

  /** Moves the animated value `id` towards `target` (exponential ease-out over about `seconds`) and returns it. */
  def animate(id: Long, target: Double, seconds: Double = 0.12): Double =
    if !this.animations then { animationValues(id) = target; return target }
    val cur = animationValues.getOrElse(id, target)
    val k = 1 - math.exp(-frameDt * 5.0 / math.max(seconds, 1e-3))
    val next = if math.abs(target - cur) < 0.002 then target else cur + (target - cur) * k
    animationValues(id) = next
    if next != target then requestRepaint()
    next

  def begin(width: Double, height: Double, scale: Double): Unit =
    val now = time; frameDt = (now - lastFrameTime).max(1.0 / 240).min(0.1); lastFrameTime = now
    canvas.begin(width, height, scale, style.background)
    clips = Vector(Rect(0, 0, width, height)); focusClaimed = false; repaintRequested = false
    focusOrder.clear(); overlayFocusStart = -1; focusTrap = false; tabConsumed = false
    cursorIcon = scalaui.window.CursorIcon.Default; imeRect = None; accessNodes.clear()

  // --- toasts ---
  private final case class Toast(text: String, kind: ToastKind, born: Double, life: Double)
  private val toasts = mutable.ArrayBuffer.empty[Toast]
  /** Shows a transient message at the bottom right for `seconds`. */
  def toast(text: String, kind: ToastKind = ToastKind.Info, seconds: Double = 3.5): Unit =
    toasts += Toast(text, kind, time, seconds); requestRepaint()

  private def paintToasts(): Unit =
    val now = time
    toasts.filterInPlace(t => now - t.born < t.life + 0.3)
    var y = canvas.height - 16
    toasts.reverseIterator.foreach { t =>
      val age = now - t.born
      val a = math.min(1.0, math.min(age / 0.15, (t.life + 0.3 - age) / 0.3)).max(0.0)
      val l = layout(t.text, style.fontSize, maxWidth = Some(320))
      val w = l.width + 62; val h = math.max(l.height + 20, 44.0)
      val rect = Rect(canvas.width - 16 - w, y - h + (1 - a) * 10, canvas.width - 16, y + (1 - a) * 10)
      val (icon, col) = t.kind match
        case ToastKind.Info => (Icons.info, style.info)
        case ToastKind.Success => (Icons.checkCircle, style.success)
        case ToastKind.Warning => (Icons.warning, style.warning)
        case ToastKind.Error => (Icons.error, style.danger)
      shadow(rect, style.rounding + 2, 18, style.shadow.withAlpha((style.shadow.a * a).toFloat), Vec2(0, 5))
      box(rect, style.rounding + 2, style.menu.withAlpha(a.toFloat), style.borderStrong.withAlpha(a.toFloat))
      paintIcon(icon, Rect(rect.x0 + 14, rect.center.y - 9, rect.x0 + 32, rect.center.y + 9), col.withAlpha(a.toFloat))
      paintText(l, Point(rect.x0 + 44, rect.y0 + (h - l.height) / 2), style.textStrong.withAlpha(a.toFloat))
      y -= h + 8
    }
    if toasts.nonEmpty then requestRepaint()

  /** Runs deferred (overlay) painting, finalises input state and returns the frame to submit. */
  def end(): scalaui.gpu.Frame =
    val d = deferred; deferred = Vector.empty
    overlayFocusStart = focusOrder.length
    overlay = true
    d.foreach(_())
    paintToasts()
    overlay = false
    covers = nextCovers; nextCovers = Vector.empty
    wheelClaims = nextWheelClaims; nextWheelClaims = Vector.empty
    if input.pressed then keyboardNav = false
    if input.pressed && !focusClaimed then focusId = 0
    traverseFocus()
    if input.released then { activeId = 0; dragPayload = None; dragSourceId = 0 }
    input.endFrame()
    canvas.finish()

  /** Asks for focus to move to the next (+1) or previous (-1) focusable widget at the end of this frame (arrow-key navigation in trees and menus). */
  var pendingFocusMove = 0
  /** Consumes a press of `key` (no modifiers) if present; used by focused widgets that own arrow keys. */
  def takeKey(key: scalaui.window.Key): Boolean =
    val i = input.keys.indexWhere(k => k.key == key && !k.mods.shortcut && !k.mods.alt)
    if i >= 0 then { input.keys.remove(i); true } else false

  private def traverseFocus(): Unit =
    val tab = input.keys.find(_.key == scalaui.window.Key.Tab)
    if pendingFocusMove != 0 then
      val list = if focusTrap && overlayFocusStart >= 0 then focusOrder.drop(overlayFocusStart) else focusOrder
      val i = list.indexOf(focusId)
      if list.nonEmpty then
        focusId = if i < 0 then (if pendingFocusMove > 0 then list.head else list.last) else list((i + pendingFocusMove).max(0).min(list.length - 1))
        keyboardNav = true; requestRepaint()
      pendingFocusMove = 0
    else if tab.isDefined && !tabConsumed then
      val list = if focusTrap && overlayFocusStart >= 0 then focusOrder.drop(overlayFocusStart) else focusOrder
      if list.nonEmpty then
        val i = list.indexOf(focusId); val last = list.length - 1
        val back = tab.get.mods.shift
        val n = if back then (if i <= 0 then last else i - 1) else (if i < 0 || i == last then 0 else i + 1)
        focusId = list(n); keyboardNav = true; requestRepaint()

  /** Uploads an image as a GPU texture (premultiplied RGBA8). */
  def texture(img: scalaui.codecs.Image): scalaui.gpu.Texture = backend.createTexture(img.width, img.height, img.premultiplied)

  private val svgCache = mutable.HashMap.empty[(scalaui.svg.SvgDocument, Int, Int), scalaui.gpu.Texture]
  /** Rasterizes `doc` at `w x h` device pixels into a cached texture. */
  def svgTexture(doc: scalaui.svg.SvgDocument, w: Int, h: Int): scalaui.gpu.Texture =
    svgCache.getOrElseUpdate((doc, w, h), {
      val pm = doc.render(w, h)
      backend.createTexture(w, h, pm.toPremulRgba8)
    })

  def defer(f: () => Unit): Unit = deferred :+= f

  // --- shadows ---
  private final case class ShadowTex(texture: scalaui.gpu.Texture, size: Int, corner: Int)
  private val shadowCache = mutable.HashMap.empty[(Int, Int, Int), ShadowTex]

  /** A pre-blurred rounded-rect alpha texture for the nine-slice shadow: `rr` is the corner radius and `pad` the blur extent, in device pixels. */
  private def shadowTexture(rr: Int, pad: Int, sigmaTenths: Int): ShadowTex =
    shadowCache.getOrElseUpdate((rr, pad, sigmaTenths), {
      val corner = pad + rr
      val n = 2 * corner + 2
      val pm = new scalaui.raster.Pixmap(n, n)
      pm.fill(scalaui.raster.Path.roundedRect(Rect(pad, pad, n - pad, n - pad), rr.toDouble), scalaui.raster.Paint.solid(Color.White))
      val blurred = scalaui.imaging.Filters.gaussianBlur(pm, sigmaTenths / 10.0)
      ShadowTex(backend.createTexture(n, n, blurred.toPremulRgba8), n, corner)
    })

  /** Draws a soft drop shadow for a `rect` with corner `rounding`: `blur` is the blur extent in points, `offset` shifts it. */
  def shadow(rect: Rect, rounding: Double, blur: Double, color: Color, offset: Vec2 = Vec2(0, 3)): Unit =
    val sc = canvas.scaleFactor
    val padPx = math.max(1, math.ceil(blur * sc).toInt)
    val rr = math.max(1, math.round(rounding * sc).toInt)
    val tex = shadowTexture(rr, padPx, math.max(1, (blur * sc * 10 / 2).toInt))
    val padPt = padPx / sc
    val d = Rect(rect.x0 + offset.x - padPt, rect.y0 + offset.y - padPt, rect.x1 + offset.x + padPt, rect.y1 + offset.y + padPt)
    val c = math.min(tex.corner / sc, math.min(d.width, d.height) / 2)
    val n = tex.size.toDouble
    val cu = tex.corner / n; val mu = (tex.corner + 2) / n
    val xs = Seq(d.x0, d.x0 + c, d.x1 - c, d.x1); val ys = Seq(d.y0, d.y0 + c, d.y1 - c, d.y1)
    val us = Seq(0.0, cu, mu, 1.0)
    for i <- 0 until 3; j <- 0 until 3 do
      val dst = Rect(xs(i), ys(j), xs(i + 1), ys(j + 1))
      if dst.width > 0 && dst.height > 0 then canvas.drawImage(tex.texture, dst, Rect(us(i), us(j), us(i + 1), us(j + 1)), color)

  // --- drawing helpers ---

  /** A rounded box with a `width`-px border drawn inside its bounds (opaque fills give the cleanest edge). */
  def box(rect: Rect, rounding: Double, fill: Color, border: Color, width: Double = 1.0): Unit =
    if border.a > 0 && width > 0 then
      canvas.fillRoundedRect(rect, rounding, border)
      canvas.fillRoundedRect(rect.inflate(-width, -width), math.max(0, rounding - width), fill)
    else canvas.fillRoundedRect(rect, rounding, fill)

  /** A rounded border only (nothing inside), `width` px, drawn inside `rect`. Use opaque colors. */
  def outline(rect: Rect, rounding: Double, color: Color, width: Double = 1.0): Unit =
    val half = width / 2
    canvas.strokePath(scalaui.raster.Path.roundedRect(rect.inflate(-half, -half), math.max(0, rounding - half)), scalaui.raster.Stroke(width, join = scalaui.raster.LineJoin.Round), color)

  /** Keyboard/text focus ring just outside `rect`. */
  def focusRing(rect: Rect, rounding: Double): Unit =
    canvas.fillRoundedRect(rect.inflate(2.5, 2.5), rounding + 2.5, style.accent.withAlpha(0.35f))

  /** Paints `icon` centered in `rect` (snapped to device pixels), tinted `color`. The icon scales to the smaller side of `rect`. */
  def paintIcon(icon: Icon, rect: Rect, color: Color): Unit =
    val sc = canvas.scaleFactor
    val side = math.max(1, math.round(math.min(rect.width, rect.height) * sc).toInt)
    val c = rect.center
    val x0 = math.round((c.x - side / sc / 2) * sc) / sc; val y0 = math.round((c.y - side / sc / 2) * sc) / sc
    canvas.drawImage(svgTexture(icon.doc, side, side), Rect(x0, y0, x0 + side / sc, y0 + side / sc), tint = color)

  /** Eased 0..1 hover amount for widget `id`, for color transitions. */
  def hoverAmount(id: Long, hovered: Boolean, seconds: Double = 0.1): Double = animate(id ^ 0x2545F4914F6CDD1DL, if hovered then 1.0 else 0.0, seconds)

  // --- clipping ---
  def clip: Rect = clips.last
  def pushClip(r: Rect): Unit = { val c = clips.last.intersect(r); clips :+= c; canvas.pushClip(c) }
  def popClip(): Unit = { clips = clips.init; canvas.popClip() }

  // --- interaction ---
  /** True while running deferred overlay content (popups, floating windows): widgets there ignore cover rects. */
  var overlay = false
  private var covers = Vector.empty[Rect]      // overlay areas from the previous frame
  private var nextCovers = Vector.empty[Rect]  // being collected this frame
  /** Marks `r` as an overlay area: base-layer widgets under it stop reacting next frame (and for the rest of this one, once painted). */
  def cover(r: Rect): Unit = nextCovers :+= r

  private var wheelClaims = Vector.empty[(Rect, Boolean, Boolean)]     // from the previous frame
  private var nextWheelClaims = Vector.empty[(Rect, Boolean, Boolean)]
  /** A widget under `rect` scrolls by the wheel itself (e.g. a multiline text field); `canUp`/`canDown` say whether it still can. */
  def claimWheel(rect: Rect, canUp: Boolean, canDown: Boolean): Unit = nextWheelClaims :+= ((rect.intersect(clip), canUp, canDown))
  /** True if the wheel movement `dy` over the mouse position is consumed by a nested scrollable widget, so outer scroll areas must ignore it. */
  def wheelClaimed(dy: Double): Boolean =
    dy != 0 && input.mouse.exists(m => wheelClaims.exists((r, up, down) => r.contains(m) && (if dy > 0 then up else down)))

  def hoverable(rect: Rect, id: Long): Boolean =
    disabledDepth == 0 && input.mouse.exists(m => rect.contains(m) && clip.contains(m)) &&
      (overlay || (popupOwner == 0 || popupOwner == id) && !input.mouse.exists(m => covers.exists(_.contains(m))))

  /** True if the right button went down this frame over `rect` and nothing in an overlay covers the point; unlike hover this still
    * works while a popup is open, so a context menu can move to a new place. */
  def secondaryHit(rect: Rect): Boolean =
    disabledDepth == 0 && input.secondaryPressed && input.mouse.exists(m => rect.contains(m) && clip.contains(m) && (overlay || !covers.exists(_.contains(m))))

  /** Standard press/drag/click interaction for a widget occupying `rect`. */
  def interact(rect: Rect, id: Long, drag: Boolean = false, focusable: Boolean = false): Response =
    if disabledDepth > 0 then return Response(rect)
    val hovered = hoverable(rect, id)
    // keyboard focus: Tab visits the widget, Enter/Space activate it
    var keyClick = false
    if focusable then
      registerFocus(id)
      if focusId == id then
        if keyboardNav then outline(rect.inflate(2.5, 2.5), style.rounding + 2.5, style.accent, 2.0)
        keyClick = input.keys.exists(k => (k.key == scalaui.window.Key.Enter || k.key == scalaui.window.Key.Space) && !k.mods.shortcut)
        if keyClick then keyboardNav = true
    if hovered && input.pressed && activeId == 0 then { activeId = id; doubleId = if input.doubleClicked then id else 0L }
    val mine = activeId == id
    val dragging = mine && input.down && drag
    Response(rect, hovered = hovered, pressed = mine && input.down, clicked = (mine && input.released && hovered) || keyClick,
      dragged = dragging, focused = focusId == id, doubleClicked = mine && input.released && hovered && doubleId == id, dragDelta = if dragging then input.mouseDelta else Vec2.Zero,
      scroll = if hovered then input.scroll else Vec2.Zero, mouse = input.mouse,
      secondaryClicked = hovered && input.secondaryPressed)

  // --- text ---
  private val layoutCache = mutable.HashMap.empty[(String, Double, Boolean, Option[Double], Weight, Double), TextLayout]
  def layout(text: String, size: Double, monospace: Boolean = false, maxWidth: Option[Double] = None, weight: Weight = Weight.Regular, tracking: Double = 0.0): TextLayout =
    if layoutCache.size > 4000 then layoutCache.clear()
    layoutCache.getOrElseUpdate((text, size, monospace, maxWidth, weight, tracking),
      TextLayout.layout(text, shaper(monospace = monospace, weight = weight), size, maxWidth, tracking = tracking))
  /** Lays out `text` on a type-scale rung. */
  def layout(text: String, rung: TypeRung): TextLayout = layout(text, rung.size, false, None, rung.weight, rung.trackingPx)
  def paintText(l: TextLayout, pos: Point, color: Color): Unit = renderer.draw(canvas, l, pos, color)
  def paintRich(l: RichLayout, pos: Point): Unit = renderer.drawRich(canvas, l, pos)
