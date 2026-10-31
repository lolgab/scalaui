package scalaui.window

import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*
import scala.collection.mutable.ArrayBuffer
import ObjC.*

/** macOS backend: NSApplication/NSWindow driven by polling `nextEventMatchingMask:`, no subclasses. Must be used from the main thread. */
final class CocoaWindow private (private val app: Id, private val win: Id, private val view: Id)
    extends Window:
  private var lastSize = (width, height)
  private var closed = false
  private val runLoopMode = nsString("kCFRunLoopDefaultMode")

  private def contentRect: (Double, Double, Double, Double) = rect(view, "frame")
  def width: Int = contentRect._3.toInt
  def height: Int = contentRect._4.toInt
  def scaleFactor: Double = double(win, "backingScaleFactor")

  def nativeHandle: Ptr[Byte] = view

  def setTitle(title: String): Unit = send(win, "setTitle:", nsString(title))
  def close(): Unit = if !closed then { closed = true; send(win, "close") }

  override def supportsTextInput: Boolean = true

  private def pooled[A](f: => A): A =
    val pool = send(send(cls("NSAutoreleasePool"), "alloc"), "init")
    try f finally send(pool, "drain")

  private def dispatch(ev: Id, out: ArrayBuffer[Event]): Unit =
    translate(ev, out)

  def pollEvents(): Seq[Event] =
    val out = ArrayBuffer.empty[Event]
    if closed then return out.toSeq
    drain(out)
    finishPoll(out)

  override def waitEvents(timeoutMs: Int): Seq[Event] =
    val out = ArrayBuffer.empty[Event]
    if closed then return out.toSeq
    pooled {
      val until = C.msgD(cls("NSDate"), sel("dateWithTimeIntervalSinceNow:"), timeoutMs / 1000.0)
      val ev = C.msgEvent(app, sel("nextEventMatchingMask:untilDate:inMode:dequeue:"), ULong.MaxValue, until, runLoopMode, 1)
      if ev != null then dispatch(ev, out)
    }
    drain(out)
    finishPoll(out)

  private def drain(out: ArrayBuffer[Event]): Unit =
    var continue = true
    while continue do
      pooled {
        val ev = C.msgEvent(app, sel("nextEventMatchingMask:untilDate:inMode:dequeue:"), ULong.MaxValue, null, runLoopMode, 1)
        if ev == null then continue = false else dispatch(ev, out)
      }

  private def finishPoll(out: ArrayBuffer[Event]): Seq[Event] =
    send(app, "updateWindows")
    drainViewEvents(out)
    val size = (width, height)
    if size != lastSize then { lastSize = size; out += Event.Resized(size._1, size._2) }
    val focused = long(win, "isKeyWindow") != 0
    if focused != lastFocused then { lastFocused = focused; out += Event.Focused(focused) }
    val theme = systemTheme
    if theme != lastTheme then { lastTheme = theme; out += Event.ThemeChanged(theme) }
    if !closed && long(win, "isVisible") == 0 && long(win, "isMiniaturized") == 0 && !hiddenByUser then
      closed = true
      out += Event.CloseRequested
    out.toSeq

  private def drainViewEvents(out: ArrayBuffer[Event]): Unit =
    val e = stackalloc[ViewShim.ViewEvent]()
    def paths(s: CString): Seq[String] = if s == null then Nil else fromCString(s).split('\n').toSeq.filter(_.nonEmpty)
    while ViewShim.scalaui_view_poll(view, e) != 0 do
      val text = e._5
      val (a, b, c) = (e._2, e._3, e._4)
      e._1 match
        case 1 => out += Event.TextInput(fromCString(text))
        case 2 => out += Event.Composition(fromCString(text), a.toInt, b.toInt)
        case 3 => out += Event.FilesDropped(paths(text), a, b)
        case 4 =>
          if c == 0 then hoverPaths = paths(text)
          out += Event.FilesHovered(hoverPaths, a, b)
        case 5 => hoverPaths = Nil; out += Event.FilesHoverCancelled
        case 6 => out += Event.ScaleChanged(a)
        case 7 => out += Event.AccessPress(a.toInt)
        case 10 => out += Event.AccessSetSelection(a.toInt, b.toInt, (b + c).toInt) // VoiceOver set AXSelectedTextRange: location b, length c
        case 8 => out += Event.MenuAction(a.toInt)
        case 9 => out += Event.CloseRequested
        case _ => ()
      if text != null then ViewShim.scalaui_free(text.asInstanceOf[Ptr[Byte]])

  private var lastFocused = true
  /** Gives the main menu a chance to handle a Cmd shortcut; true when an item consumed it (its action is queued as MenuAction). */
  private def menuClaims(ev: Id): Boolean =
    val menu = send(app, "mainMenu")
    menu != null && (C.msgP(menu, sel("performKeyEquivalent:"), ev).toLong & 0xff) != 0 // BOOL: low byte only
  private var lastTheme = Theme.Light
  private var hoverPaths: Seq[String] = Nil
  private var hiddenByUser = false
  private var cursorHidden = false
  private var cursor = CursorIcon.Default

  override def systemTheme: Theme =
    val name = fromNSString(send(send(app, "effectiveAppearance"), "name"))
    if name.contains("Dark") then Theme.Dark else Theme.Light

  override def setCursor(icon: CursorIcon): Unit =
    if icon == cursor then return
    cursor = icon
    val name = icon match
      case CursorIcon.Pointer => "pointingHandCursor"
      case CursorIcon.Text => "IBeamCursor"
      case CursorIcon.Crosshair => "crosshairCursor"
      case CursorIcon.Grab => "openHandCursor"
      case CursorIcon.Grabbing => "closedHandCursor"
      case CursorIcon.ResizeHorizontal => "resizeLeftRightCursor"
      case CursorIcon.ResizeVertical => "resizeUpDownCursor"
      case CursorIcon.NotAllowed => "operationNotAllowedCursor"
      case _ => "arrowCursor"
    if icon == CursorIcon.Hidden && !cursorHidden then { cursorHidden = true; send(cls("NSCursor"), "hide") }
    else if icon != CursorIcon.Hidden && cursorHidden then { cursorHidden = false; send(cls("NSCursor"), "unhide") }
    val c = send(cls("NSCursor"), name)
    ViewShim.scalaui_view_set_cursor(view, c)
    send(c, "set")

  override def setImeRect(x: Double, y: Double, w: Double, h: Double): Unit =
    ViewShim.scalaui_view_set_ime_rect(view, x, height - y - h, w, h)

  override def setAccessibility(nodes: Seq[AccessNode]): Unit = Zone {
    val n = nodes.length
    val roles = alloc[CString](n.max(1)); val labels = alloc[CString](n.max(1)); val values = alloc[CString](n.max(1))
    val bounds = alloc[CDouble](4 * n.max(1)); val flags = alloc[CInt](n.max(1))
    nodes.zipWithIndex.foreach { (a, i) =>
      roles(i) = toCString(CocoaWindow.axRole(a.role)); labels(i) = toCString(a.label); values(i) = toCString(a.value)
      bounds(4 * i) = a.bounds.x0; bounds(4 * i + 1) = a.bounds.y0; bounds(4 * i + 2) = a.bounds.width; bounds(4 * i + 3) = a.bounds.height
      flags(i) = (if a.focused then 1 else 0) | (if a.disabled then 2 else 0) | (if a.checked.isDefined then 4 else 0) | (if a.checked.contains(true) then 8 else 0)
    }
    ViewShim.scalaui_view_set_access(view, n, roles, labels, values, bounds, flags)
    // text fields: caret/selection for the NSAccessibility text attributes, and change notifications for VoiceOver
    val info = alloc[CInt](4 * n.max(1))
    nodes.zipWithIndex.foreach { (a, i) =>
      val t = a.text
      info(4 * i) = if t.isEmpty then 0 else if t.get.multiline then 2 else 1
      info(4 * i + 1) = t.fold(0)(_.caret); info(4 * i + 2) = t.fold(0)(_.selStart); info(4 * i + 3) = t.fold(0)(_.selEnd)
    }
    ViewShim.scalaui_view_set_access_text(view, n, info)
    nodes.zipWithIndex.foreach { (a, i) =>
      lastAccess.lift(i).filter(p => p.text.isDefined && a.text.isDefined && p.label == a.label).foreach { p =>
        if p.value != a.value then ViewShim.scalaui_view_post_text(view, i, 0)
        else if p.text.map(t => (t.selStart, t.selEnd)) != a.text.map(t => (t.selStart, t.selEnd)) then ViewShim.scalaui_view_post_text(view, i, 1)
      }
    }
    lastAccess = nodes.toVector
  }
  private var lastAccess = Vector.empty[AccessNode]

  override def setLiveResizeCallback(f: () => Unit): Unit =
    CocoaWindow.liveResize = f
    ViewShim.scalaui_set_resize_callback(CFuncPtr0.fromScalaFunction(CocoaWindow.liveResizeTrampoline))

  override def hasNativeMenus: Boolean = true

  private var tray: Id = null
  override def setTray(rgba: Array[Byte], pixelWidth: Int, pixelHeight: Int, tooltip: String, menu: Seq[MenuEntry]): Unit = pooled {
    require(rgba.length >= pixelWidth * pixelHeight * 4, "pixel buffer too small")
    removeTray()
    val bar = send(cls("NSStatusBar"), "systemStatusBar")
    tray = C.msgD(bar, sel("statusItemWithLength:"), -1.0) // NSVariableStatusItemLength
    send(tray, "retain")
    val cs = Frameworks.CGColorSpaceCreateDeviceRGB()
    val data = Frameworks.CFDataCreate(null, rgba.atUnsafe(0), (pixelWidth * pixelHeight * 4).toLong)
    val provider = Frameworks.CGDataProviderCreateWithCFData(data)
    val image = Frameworks.CGImageCreate(pixelWidth.toCSize, pixelHeight.toCSize, 8.toCSize, 32.toCSize, (pixelWidth * 4).toCSize, cs, 1.toUInt, provider, null, 0, 0)
    // menu-bar icons are about 18 points tall
    val h = 18.0; val w = h * pixelWidth / pixelHeight
    val ns = ViewShim.scalaui_nsimage(image, w, h)
    Frameworks.CFRelease(image); Frameworks.CFRelease(provider); Frameworks.CFRelease(data); Frameworks.CFRelease(cs)
    val button = send(tray, "button")
    send(button, "setImage:", ns)
    send(button, "setToolTip:", nsString(tooltip))
    send(tray, "setMenu:", CocoaMenus.buildMenu("", menu, view))
  }
  override def removeTray(): Unit =
    if tray != null then
      send(send(cls("NSStatusBar"), "systemStatusBar"), "removeStatusItem:", tray)
      send(tray, "release"); tray = null
  override def setMenuBar(menus: Seq[MenuEntry.Submenu]): Unit = pooled { send(app, "setMainMenu:", CocoaMenus.build(menus)) }

  override def setMinSize(w: Int, h: Int): Unit = ViewShim.scalaui_window_set_min_size(win, w.toDouble, h.toDouble)
  override def setResizable(resizable: Boolean): Unit =
    val mask = long(win, "styleMask")
    sendL(win, "setStyleMask:", if resizable then mask | 8L else mask & ~8L)
  override def isFullscreen: Boolean = (long(win, "styleMask") & (1L << 14)) != 0
  override def setFullscreen(fullscreen: Boolean): Unit = if fullscreen != isFullscreen then send(win, "toggleFullScreen:", null)
  override def setMinimized(minimized: Boolean): Unit =
    if minimized then send(win, "miniaturize:", null) else send(win, "deminiaturize:", null)
  override def setAlwaysOnTop(onTop: Boolean): Unit = sendL(win, "setLevel:", if onTop then 3L else 0L)
  override def setVisible(visible: Boolean): Unit =
    hiddenByUser = !visible
    if visible then send(win, "makeKeyAndOrderFront:", null) else send(win, "orderOut:", null)
  override def focus(): Unit = { bool(app, "activateIgnoringOtherApps:", true); send(win, "makeKeyAndOrderFront:", null) }
  override def requestAttention(): Unit = sendL(app, "requestUserAttention:", 10L)

  private def screenHeight: Double = rect(send(cls("NSScreen"), "mainScreen"), "frame")._4
  override def position: (Int, Int) =
    val b = stackalloc[CDouble](4)
    ViewShim.scalaui_window_origin(win, b)
    (b(0).toInt, (screenHeight - b(1) - b(3)).toInt)
  override def setPosition(x: Int, y: Int): Unit =
    val b = stackalloc[CDouble](4)
    ViewShim.scalaui_window_origin(win, b)
    ViewShim.scalaui_window_set_origin(win, x.toDouble, screenHeight - y - b(3))
  override def setIcon(rgba: Array[Byte], pixelWidth: Int, pixelHeight: Int): Unit =
    require(rgba.length >= pixelWidth * pixelHeight * 4, "pixel buffer too small")
    val cs = Frameworks.CGColorSpaceCreateDeviceRGB()
    val data = Frameworks.CFDataCreate(null, rgba.atUnsafe(0), (pixelWidth * pixelHeight * 4).toLong)
    val provider = Frameworks.CGDataProviderCreateWithCFData(data)
    val image = Frameworks.CGImageCreate(
      pixelWidth.toCSize, pixelHeight.toCSize, 8.toCSize, 32.toCSize, (pixelWidth * 4).toCSize,
      cs, 1.toUInt, provider, null, 0, 0
    )
    ViewShim.scalaui_set_app_icon(image, pixelWidth.toDouble, pixelHeight.toDouble)
    Frameworks.CFRelease(image); Frameworks.CFRelease(provider); Frameworks.CFRelease(data); Frameworks.CFRelease(cs)

  private def translate(ev: Id, out: ArrayBuffer[Event]): Unit =
    if send(ev, "window") != win then { send(app, "sendEvent:", ev); return }
    val h = contentRect._4
    def pos: (Double, Double) = { val (px, py) = point(ev, "locationInWindow"); (px, h - py) }
    val mods = modifiers(ev)
    long(ev, "type") match
      case 1 => val (x, y) = pos; out += Event.MouseDown(MouseButton.Left, x, y, mods)
      case 2 => val (x, y) = pos; out += Event.MouseUp(MouseButton.Left, x, y)
      case 3 => val (x, y) = pos; out += Event.MouseDown(MouseButton.Right, x, y, mods)
      case 4 => val (x, y) = pos; out += Event.MouseUp(MouseButton.Right, x, y)
      case 25 =>
        val (x, y) = pos
        out += Event.MouseDown(MouseButton.Other, x, y, mods)
      case 5 | 6 | 7 | 27 => val (x, y) = pos; out += Event.MouseMoved(x, y)
      case 10 =>
        val chars = fromNSString(send(ev, "charactersIgnoringModifiers"))
        val code = long(ev, "keyCode").toInt
        val composing = long(view, "hasMarkedText") != 0
        if mods.command && menuClaims(ev) then return
        if mods.command || mods.control then
          out += Event.KeyDown(keyOf(code), "", chars, mods, long(ev, "isARepeat") != 0)
          send(app, "sendEvent:", ev) // menu key equivalents, system shortcuts
          return
        // typed text, dead keys and input methods arrive through the view's NSTextInputClient methods
        if !composing then out += Event.KeyDown(keyOf(code), "", chars, mods, long(ev, "isARepeat") != 0)
        send(view, "interpretKeyEvents:", send(cls("NSArray"), "arrayWithObject:", ev))
        return
      case 11 => out += Event.KeyUp(keyOf(long(ev, "keyCode").toInt), mods)
      case 22 => out += Event.Scroll(double(ev, "scrollingDeltaX"), double(ev, "scrollingDeltaY"))
      case _  => ()
    send(app, "sendEvent:", ev)

  private def modifiers(ev: Id): Modifiers =
    val f = long(ev, "modifierFlags")
    Modifiers(shift = (f & (1L << 17)) != 0, control = (f & (1L << 18)) != 0, alt = (f & (1L << 19)) != 0, command = (f & (1L << 20)) != 0)

  private def keyOf(code: Int): Key = code match
    case 123 => Key.Left; case 124 => Key.Right; case 125 => Key.Down; case 126 => Key.Up
    case 115 => Key.Home; case 119 => Key.End; case 116 => Key.PageUp; case 121 => Key.PageDown
    case 51 => Key.Backspace; case 117 => Key.Delete; case 36 | 76 => Key.Enter
    case 48 => Key.Tab; case 53 => Key.Escape; case 49 => Key.Space
    case c => Key.Other(c)

  // Two native pixel buffers shown alternately without copying: a CGImage over a buffer is handed to the layer and Core Animation
  // may read it until the next commit, so the next frame is composed in the other buffer. Each buffer remembers what it lacks.
  private var nbufs = Array.empty[Ptr[Byte]]
  private var nbufW = 0; private var nbufH = 0; private var nbufNext = 0
  private val nbufDirty = new DirtyTracker(2)
  // buffers of a previous size, freed a few presents later when Core Animation no longer references them
  private var retiredBufs = List.empty[(Ptr[Byte], Int)]
  private var presents = 0

  def present(rgba: Array[Byte], pixelWidth: Int, pixelHeight: Int): Unit =
    present(rgba, pixelWidth, pixelHeight, 0, 0, pixelWidth, pixelHeight)

  override def present(rgba: Array[Byte], pixelWidth: Int, pixelHeight: Int, rx: Int, ry: Int, rw: Int, rh: Int): Unit =
    if pixelWidth <= 0 || pixelHeight <= 0 then return
    require(rgba.length >= pixelWidth * pixelHeight * 4, "pixel buffer too small")
    val size = pixelWidth.toLong * pixelHeight * 4
    presents += 1
    if pixelWidth != nbufW || pixelHeight != nbufH then
      nbufs.foreach(b => retiredBufs ::= ((b, presents)))
      nbufs = Array.fill(2)(scala.scalanative.libc.stdlib.malloc(size.toCSize))
      nbufW = pixelWidth; nbufH = pixelHeight; nbufDirty.reset(pixelWidth, pixelHeight)
    retiredBufs = retiredBufs.filter { case (b, at) => if presents - at > 4 then { scala.scalanative.libc.stdlib.free(b); false } else true }
    val (dx0, dy0, dx1, dy1) = nbufDirty.clamp(rx, ry, rw, rh)
    nbufDirty.add(dx0, dy0, dx1, dy1)
    val idx = nbufNext; nbufNext = 1 - nbufNext
    val (x0, y0, x1, y1) = nbufDirty.take(idx)
    val buf = nbufs(idx)
    var y = y0
    while y < y1 do // copy only the rows/columns this buffer is missing
      val o = (y.toLong * pixelWidth + x0) * 4
      scala.scalanative.libc.string.memcpy(buf + o, rgba.atUnsafe(o.toInt), ((x1 - x0) * 4).toCSize)
      y += 1
    val pool = send(send(cls("NSAutoreleasePool"), "alloc"), "init")
    val cs = Frameworks.CGColorSpaceCreateDeviceRGB()
    val provider = Frameworks.CGDataProviderCreateWithData(buf, size.toCSize)
    // kCGImageAlphaPremultipliedLast = 1
    val image = Frameworks.CGImageCreate(
      pixelWidth.toCSize, pixelHeight.toCSize, 8.toCSize, 32.toCSize, (pixelWidth * 4).toCSize,
      cs, 1.toUInt, provider, null, 0, 0
    )
    val layer = send(view, "layer")
    sendD(layer, "setContentsScale:", scaleFactor)
    send(layer, "setContents:", image)
    Frameworks.CFRelease(image); Frameworks.CFRelease(provider); Frameworks.CFRelease(cs)
    send(pool, "drain")

object CocoaWindow:
  private[window] var liveResize: () => Unit = () => ()
  // a plain function (no captures), as C callbacks require
  private[window] def liveResizeTrampoline(): Unit = liveResize()
  private[window] def menuKey(k: String): String = k match
    case "enter" => "\r"; case "tab" => "\t"; case "esc" => "\u001b"; case "space" => " "
    case "delete" => "\u007f"; case "backspace" => "\b"
    case "left" => "\uf702"; case "right" => "\uf703"; case "up" => "\uf700"; case "down" => "\uf701"
    case f if f.length >= 2 && f.startsWith("f") && f.drop(1).forall(_.isDigit) => new String(Character.toChars(0xF703 + f.drop(1).toInt))
    case other => other

  private[window] def axRole(r: AccessRole): String = r match
    case AccessRole.Label => "AXStaticText"; case AccessRole.Heading => "AXHeading"; case AccessRole.Button => "AXButton"
    case AccessRole.CheckBox => "AXCheckBox"; case AccessRole.RadioButton => "AXRadioButton"; case AccessRole.Switch => "AXSwitch"
    case AccessRole.Slider => "AXSlider"; case AccessRole.TextInput => "AXTextField"; case AccessRole.ComboBox => "AXPopUpButton"
    case AccessRole.Link => "AXLink"; case AccessRole.ProgressBar => "AXProgressIndicator"; case AccessRole.Tab => "AXRadioButton"
    case AccessRole.ListItem => "AXRow"

  private var appStarted = false

  def apply(title: String, width: Int, height: Int): CocoaWindow =
    val app = send(cls("NSApplication"), "sharedApplication")
    if !appStarted then
      appStarted = true
      sendL(app, "setActivationPolicy:", 0) // regular app: dock icon, menu bar, keyboard focus
      send(app, "finishLaunching")
      bool(app, "activateIgnoringOtherApps:", true)
    // titled | closable | miniaturizable | resizable
    val r = stackalloc[CDouble](4)
    r(0) = 200.0; r(1) = 200.0; r(2) = width.toDouble; r(3) = height.toDouble
    val win = Shim.scalaui_init_window(send(cls("NSWindow"), "alloc"), sel("initWithContentRect:styleMask:backing:defer:"), r, 15L.toULong, 2L, 0.toByte)
    send(win, "setTitle:", nsString(title))
    val view = ViewShim.scalaui_view_create(width.toDouble, height.toDouble)
    send(win, "setContentView:", view)
    bool(view, "setWantsLayer:", true)
    send(view, "registerForDraggedTypes:", send(cls("NSArray"), "arrayWithObject:", nsString("NSFilenamesPboardType")))
    send(win, "makeFirstResponder:", view)
    bool(win, "setReleasedWhenClosed:", false)
    send(win, "makeKeyAndOrderFront:", null)
    new CocoaWindow(app, win, view)

/** Builds the NSMenu tree for a window's menu bar. */
private[window] object CocoaMenus:
  def build(menus: Seq[MenuEntry.Submenu]): Id =
    val bar = newMenu("")
    addAppMenu(bar)
    menus.foreach { sub =>
      val top = item(sub.title, "", ""); send(top, "setSubmenu:", buildMenu(sub.title, sub.entries, null)); send(bar, "addItem:", top)
    }
    bar

  def newMenu(title: String): Id = C.msgP(send(cls("NSMenu"), "alloc"), sel("initWithTitle:"), nsString(title))
  def item(title: String, action: String, key: String): Id =
    C.msgPPP(send(cls("NSMenuItem"), "alloc"), sel("initWithTitle:action:keyEquivalent:"), nsString(title), if action.isEmpty then null else sel(action), nsString(key))

  private def addAppMenu(bar: Id): Unit =
    val appName = fromNSString(send(send(cls("NSProcessInfo"), "processInfo"), "processName"))
    val appItem = item("", "", ""); send(bar, "addItem:", appItem)
    val appMenu = newMenu(appName)
    send(appMenu, "addItem:", item(s"About $appName", "orderFrontStandardAboutPanel:", ""))
    send(appMenu, "addItem:", send(cls("NSMenuItem"), "separatorItem"))
    send(appMenu, "addItem:", item(s"Hide $appName", "hide:", "h"))
    val hideOthers = item("Hide Others", "hideOtherApplications:", "h"); sendL(hideOthers, "setKeyEquivalentModifierMask:", (1L << 19) | (1L << 20)); send(appMenu, "addItem:", hideOthers)
    send(appMenu, "addItem:", item("Show All", "unhideAllApplications:", ""))
    send(appMenu, "addItem:", send(cls("NSMenuItem"), "separatorItem"))
    send(appMenu, "addItem:", item(s"Quit $appName", "scaluiQuit:", "q"))
    send(appItem, "setSubmenu:", appMenu)

  /** An NSMenu for `entries`; with a `target` (needed outside the responder chain, e.g. status items) items are bound to it. */
  def buildMenu(title: String, entries: Seq[MenuEntry], target: Id): Id =
    val m = newMenu(title)
    bool(m, "setAutoenablesItems:", false)
    entries.foreach {
      case MenuEntry.Separator => send(m, "addItem:", send(cls("NSMenuItem"), "separatorItem"))
      case nested: MenuEntry.Submenu =>
        val it = item(nested.title, "", ""); send(it, "setSubmenu:", buildMenu(nested.title, nested.entries, target)); send(m, "addItem:", it)
      case MenuEntry.Item(t, id, shortcut, enabled, checked) =>
        val sc = Shortcut.parse(shortcut, isMac = true)
        val key = sc.map(k => CocoaWindow.menuKey(k.key)).getOrElse("")
        val it = item(t, "scaluiMenuAction:", key)
        sendL(it, "setTag:", id.toLong)
        if target != null then send(it, "setTarget:", target)
        sc.foreach { k =>
          val mask = (if k.shift then 1L << 17 else 0L) | (if k.control then 1L << 18 else 0L) | (if k.alt then 1L << 19 else 0L) | (if k.command then 1L << 20 else 0L)
          sendL(it, "setKeyEquivalentModifierMask:", mask)
        }
        bool(it, "setEnabled:", enabled)
        sendL(it, "setState:", if checked then 1L else 0L)
        send(m, "addItem:", it)
    }
    m
