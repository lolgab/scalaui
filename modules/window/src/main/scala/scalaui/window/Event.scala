package scalaui.window

import scala.scalanative.unsafe.Ptr

enum MouseButton:
  case Left, Right, Other

/** Held modifier keys. `command` is Cmd on macOS and Win/Super elsewhere. */
final case class Modifiers(shift: Boolean = false, control: Boolean = false, alt: Boolean = false, command: Boolean = false):
  /** The platform's shortcut modifier: Cmd on macOS, Ctrl elsewhere. */
  def shortcut: Boolean = if scala.util.Properties.isMac then command else control

object Modifiers:
  val None: Modifiers = Modifiers()

enum Key:
  case Left, Right, Up, Down, Home, End, PageUp, PageDown
  case Backspace, Delete, Enter, Tab, Escape, Space
  /** Any other key by its platform key code. Printable input arrives as text on [[Event.KeyDown]]. */
  case Other(code: Int)

enum Event:
  case CloseRequested
  case Resized(width: Int, height: Int)
  case MouseMoved(x: Double, y: Double)
  case MouseDown(button: MouseButton, x: Double, y: Double, mods: Modifiers)
  case MouseUp(button: MouseButton, x: Double, y: Double)
  case Scroll(dx: Double, dy: Double)
  /** `text` is the typed printable text (empty for navigation keys or shortcuts); `chars` is the unmodified
    * character(s) of the key, e.g. "a" for Cmd+A, for shortcut matching. */
  case KeyDown(key: Key, text: String, chars: String, mods: Modifiers, repeat: Boolean)
  case KeyUp(key: Key, mods: Modifiers)
  /** Text committed by the keyboard or an input method (dead keys, IME, emoji picker, ...). Replaces `text` on [[KeyDown]]
    * for backends that support it (see [[Window.supportsTextInput]]). */
  case TextInput(text: String)
  /** In-progress input-method composition (marked text). `cursorStart`/`cursorLength` select inside `text` in UTF-16 units;
    * an empty `text` ends the composition. */
  case Composition(text: String, cursorStart: Int, cursorLength: Int)
  /** Files are being dragged over the window. */
  case FilesHovered(paths: Seq[String], x: Double, y: Double)
  case FilesHoverCancelled
  case FilesDropped(paths: Seq[String], x: Double, y: Double)
  case ThemeChanged(theme: Theme)
  case Focused(focused: Boolean)
  case ScaleChanged(scaleFactor: Double)
  /** An assistive technology activated element `index` of the last [[Window.setAccessibility]] tree (e.g. VoiceOver press). */
  case AccessPress(index: Int)
  /** An assistive technology replaced the whole text of text field `index` (AT-SPI EditableText). */
  case AccessSetText(index: Int, text: String)
  /** An assistive technology moved the caret / selection of text field `index` (UTF-16 offsets; `anchor == caret` for none). */
  case AccessSetSelection(index: Int, anchor: Int, caret: Int)
  /** A native menu item (see [[Window.setMenuBar]]) was chosen, by mouse or by its shortcut. */
  case MenuAction(id: Int)

enum Theme:
  case Light, Dark

/** Mouse cursor shapes. */
enum CursorIcon:
  case Default, Pointer, Text, Crosshair, Grab, Grabbing, ResizeHorizontal, ResizeVertical, NotAllowed, Hidden

/** A native window. Sizes are in logical points; `scaleFactor` converts to device pixels. */
trait Window:
  def width: Int
  def height: Int
  def scaleFactor: Double
  /** Drains pending OS events without blocking. */
  def pollEvents(): Seq[Event]
  /** Shows a premultiplied RGBA8 image (`pixelWidth * pixelHeight * 4` bytes) as the window contents. */
  def present(rgba: Array[Byte], pixelWidth: Int, pixelHeight: Int): Unit
  /** Like [[present]] but promises that only the pixel rectangle `(x, y, w, h)` differs from the image shown by the previous
    * `present` call (same pixel size). `rgba` must still hold the complete image: backends that cannot exploit the hint,
    * and every backend after a size change, show all of it. The rectangle is clamped to the image. */
  def present(rgba: Array[Byte], pixelWidth: Int, pixelHeight: Int, x: Int, y: Int, w: Int, h: Int): Unit =
    present(rgba, pixelWidth, pixelHeight)
  def setTitle(title: String): Unit
  def close(): Unit
  /** True when the backend delivers typed text as [[Event.TextInput]] rather than in [[Event.KeyDown]]`.text`. */
  def supportsTextInput: Boolean = false
  /** Blocks up to `timeoutMs` for an OS event, then drains like [[pollEvents]]. Default: sleeps in short slices. */
  def waitEvents(timeoutMs: Int): Seq[Event] =
    val deadline = System.nanoTime() + timeoutMs * 1_000_000L
    var evs = pollEvents()
    while evs.isEmpty && System.nanoTime() < deadline do { Thread.sleep(4); evs = pollEvents() }
    evs
  /** Registers a function the backend calls while the OS is inside its modal window-resize loop (when `pollEvents` is blocked),
    * so the app can repaint at the new size instead of stretching stale content. */
  def setLiveResizeCallback(f: () => Unit): Unit = ()
  def setCursor(icon: CursorIcon): Unit = ()
  /** Caret rectangle in logical points (top-left origin), where the input-method candidate window anchors. */
  def setImeRect(x: Double, y: Double, w: Double, h: Double): Unit = ()
  def systemTheme: Theme = Theme.Light
  def setMinSize(w: Int, h: Int): Unit = ()
  def setResizable(resizable: Boolean): Unit = ()
  def setFullscreen(fullscreen: Boolean): Unit = ()
  def isFullscreen: Boolean = false
  def setMinimized(minimized: Boolean): Unit = ()
  def setAlwaysOnTop(onTop: Boolean): Unit = ()
  def setVisible(visible: Boolean): Unit = ()
  /** Brings the window to front and focuses it. */
  def focus(): Unit = ()
  /** Bounces the dock icon / flashes the taskbar entry. */
  def requestAttention(): Unit = ()
  /** Outer top-left position in screen points. */
  def position: (Int, Int) = (0, 0)
  def setPosition(x: Int, y: Int): Unit = ()
  /** Sets the application/window icon from premultiplied RGBA8 pixels. */
  /** Publishes the current accessibility tree (reading order) to the OS screen-reader API. Called after a frame changed it. */
  /** Installs a native menu bar (macOS: the global menu bar with a standard application menu; Windows: the window menu).
    * Backends without native menus ignore it; apps there can draw `Ui.menuBar` instead (see [[Window.hasNativeMenus]]). */
  def setMenuBar(menus: Seq[MenuEntry.Submenu]): Unit = ()
  def hasNativeMenus: Boolean = false
  /** Adds a system tray / menu-bar icon (premultiplied RGBA8) whose menu items arrive as [[Event.MenuAction]]. Replaces a previous one. */
  def setTray(rgba: Array[Byte], pixelWidth: Int, pixelHeight: Int, tooltip: String, menu: Seq[MenuEntry]): Unit = ()
  def removeTray(): Unit = ()
  def setAccessibility(nodes: Seq[AccessNode]): Unit = ()
  def setIcon(rgba: Array[Byte], pixelWidth: Int, pixelHeight: Int): Unit = ()
  /** The OS view/surface handle for GPU backends: an `NSView*` on macOS. */
  def nativeHandle: Ptr[Byte]

object Window:
  /** Creates a window using the backend for the current OS. */
  def create(title: String, width: Int, height: Int): Window =
    // link-time checks keep other platforms' bindings out of the binary
    import scala.scalanative.meta.LinktimeInfo
    if LinktimeInfo.isMac then CocoaWindow(title, width, height)
    else if LinktimeInfo.isWindows then Win32Window(title, width, height)
    else if LinktimeInfo.isLinux || LinktimeInfo.isFreeBSD then
      // Wayland when a compositor is running (SCALAUI_X11=1 forces X11), otherwise X11
      val wayland = sys.env.contains("WAYLAND_DISPLAY") && !sys.env.get("SCALAUI_X11").exists(v => v.nonEmpty && v != "0")
      if wayland then
        try WaylandWindow(title, width, height)
        catch case e: RuntimeException =>
          if sys.env.contains("SCALAUI_DEBUG") then System.err.println(s"Wayland unavailable (${e.getMessage}), using X11")
          X11Window(title, width, height)
      else X11Window(title, width, height)
    else throw new UnsupportedOperationException("window backend not implemented for this OS yet")


/** Window-internal helper for `present` implementations that keep several image copies (double-buffered surfaces): remembers,
  * for each copy, the bounding box of everything drawn since it was last refreshed, so a partial present only copies that box. */
private[window] final class DirtyTracker(n: Int):
  // per copy: x0, y0, x1, y1 (exclusive); x1 <= x0 means clean
  private val pend = Array.fill(n)(Array(0, 0, 0, 0))
  private var w = 0; private var h = 0
  /** A new image size: every copy needs the whole image. */
  def reset(width: Int, height: Int): Unit =
    w = width; h = height
    pend.foreach { p => p(0) = 0; p(1) = 0; p(2) = width; p(3) = height }
  def size: (Int, Int) = (w, h)
  /** Clamps a requested rectangle to the image; returns (x0, y0, x1, y1) with exclusive ends. */
  def clamp(x: Int, y: Int, rw: Int, rh: Int): (Int, Int, Int, Int) =
    (math.max(0, x), math.max(0, y), math.min(w.toLong, x.toLong + rw).toInt, math.min(h.toLong, y.toLong + rh).toInt)
  /** Marks the rectangle as changed in every copy. */
  def add(x0: Int, y0: Int, x1: Int, y1: Int): Unit =
    if x1 > x0 && y1 > y0 then
      pend.foreach { p =>
        if p(2) <= p(0) || p(3) <= p(1) then { p(0) = x0; p(1) = y0; p(2) = x1; p(3) = y1 }
        else { p(0) = math.min(p(0), x0); p(1) = math.min(p(1), y0); p(2) = math.max(p(2), x1); p(3) = math.max(p(3), y1) }
      }
  /** The box copy `i` must refresh now; marks it clean. */
  def take(i: Int): (Int, Int, Int, Int) =
    val p = pend(i); val r = (p(0), p(1), p(2), p(3))
    p(0) = 0; p(1) = 0; p(2) = 0; p(3) = 0
    r
