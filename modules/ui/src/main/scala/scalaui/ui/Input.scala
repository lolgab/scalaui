package scalaui.ui

import scalaui.geom.*
import scalaui.window.{Event, Key, Modifiers, MouseButton, Theme}
import scala.collection.mutable.ArrayBuffer

final case class KeyPress(key: Key, text: String, chars: String, mods: Modifiers, repeat: Boolean)

/** Per-frame input snapshot built from window events. */
final class Input:
  var mouse: Option[Point] = None
  /** Interface zoom: window-point coordinates of mouse events are divided by this to get UI coordinates. */
  var zoom = 1.0
  var down = false
  var pressed = false
  var released = false
  /** The right button went down this frame (one-frame flag) and is currently held. */
  var secondaryPressed = false
  var secondaryDown = false
  var doubleClicked = false
  var scroll: Vec2 = Vec2.Zero
  var mods: Modifiers = Modifiers.None
  val keys: ArrayBuffer[KeyPress] = ArrayBuffer.empty
  var anyEvent = false
  /** In-progress input-method composition: text and the cursor range inside it. */
  var composition: Option[(String, Int, Int)] = None
  /** Files dropped on the window this frame, with the drop position. */
  var droppedFiles: Seq[String] = Nil
  var dropPos: Option[Point] = None
  /** Files currently being dragged over the window. */
  var hoveredFiles: Seq[String] = Nil
  var windowFocused = true
  /** Native/fallback menu items chosen this frame (see [[Ui.appMenuBar]]). */
  val menuActions: ArrayBuffer[Int] = ArrayBuffer.empty
  /** Set when the OS theme changed; consumed by the app runner. */
  var themeChanged: Option[Theme] = None
  /** Mouse movement since the previous frame. */
  /** Mouse movement accumulated from the events of the current frame (so drags follow the pointer without a frame of lag). */
  var mouseDelta: Vec2 = Vec2.Zero
  private var lastClickNanos = 0L
  private var lastClickPos = Point.Zero

  /** Clears the one-frame flags; call after the frame has consumed them. */
  def endFrame(): Unit =
    mouseDelta = Vec2.Zero
    pressed = false; released = false; doubleClicked = false; secondaryPressed = false
    scroll = Vec2.Zero; keys.clear(); anyEvent = false; droppedFiles = Nil; dropPos = None; themeChanged = None; menuActions.clear()

  private def moveTo(p: Point): Unit =
    mouse.foreach(old => mouseDelta = mouseDelta + (p - old))
    mouse = Some(p)

  def handle(e0: Event): Unit =
    anyEvent = true
    val z = zoom
    val e = if z == 1.0 then e0 else e0 match
      case Event.MouseMoved(x, y) => Event.MouseMoved(x / z, y / z)
      case Event.MouseDown(b, x, y, m) => Event.MouseDown(b, x / z, y / z, m)
      case Event.MouseUp(b, x, y) => Event.MouseUp(b, x / z, y / z)
      case Event.FilesDropped(p, x, y) => Event.FilesDropped(p, x / z, y / z)
      case other => other
    e match
      case Event.MouseMoved(x, y) => moveTo(Point(x, y))
      case Event.MouseDown(MouseButton.Left, x, y, m) =>
        val p = Point(x, y); val now = System.nanoTime()
        doubleClicked = now - lastClickNanos < 400_000_000L && p.distance(lastClickPos) < 6
        lastClickNanos = if doubleClicked then 0L else now; lastClickPos = p
        moveTo(p); down = true; pressed = true; mods = m
      case Event.MouseUp(MouseButton.Left, x, y) => moveTo(Point(x, y)); down = false; released = true
      case Event.MouseDown(MouseButton.Right, x, y, m) => moveTo(Point(x, y)); secondaryDown = true; secondaryPressed = true; mods = m
      case Event.MouseUp(MouseButton.Right, x, y) => moveTo(Point(x, y)); secondaryDown = false
      case Event.Scroll(dx, dy) => scroll = scroll + Vec2(dx, dy)
      case Event.KeyDown(k, t, c, m, r) => mods = m; keys += KeyPress(k, t, c, m, r)
      case Event.KeyUp(_, m) => mods = m
      case Event.TextInput(t) => if t.nonEmpty then keys += KeyPress(Key.Other(-1), t, t, mods, false)
      case Event.Composition(t, a, n) => composition = if t.isEmpty then None else Some((t, a, n))
      case Event.FilesHovered(p, _, _) => hoveredFiles = p
      case Event.FilesHoverCancelled => hoveredFiles = Nil
      case Event.FilesDropped(p, x, y) => hoveredFiles = Nil; droppedFiles = p; dropPos = Some(Point(x, y)); mouse = Some(Point(x, y))
      case Event.ThemeChanged(t) => themeChanged = Some(t)
      case Event.MenuAction(id) => menuActions += id
      case Event.Focused(f) => windowFocused = f; if !f then { down = false; composition = None }
      case _ => ()
