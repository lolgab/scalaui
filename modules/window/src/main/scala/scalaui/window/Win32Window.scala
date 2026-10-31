package scalaui.window

import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

/** Win32 API bindings (user32/gdi32/kernel32 ship with Windows; nothing third-party). Structs are poked by byte offset. */
@link("user32") @link("gdi32") @link("kernel32") @link("shell32") @link("advapi32") @link("imm32") @extern private[scalaui] object W:
  def GetModuleHandleW(name: Ptr[CShort]): Ptr[Byte] = extern
  def RegisterClassExW(wc: Ptr[Byte]): CUnsignedShort = extern
  def CreateWindowExW(exStyle: CUnsignedInt, cls: Ptr[CShort], title: Ptr[CShort], style: CUnsignedInt, x: CInt, y: CInt, w: CInt, h: CInt,
      parent: Ptr[Byte], menu: Ptr[Byte], inst: Ptr[Byte], param: Ptr[Byte]): Ptr[Byte] = extern
  def DefWindowProcW(hwnd: Ptr[Byte], msg: CUnsignedInt, wp: CUnsignedLongLong, lp: CLongLong): CLongLong = extern
  def ShowWindow(hwnd: Ptr[Byte], cmd: CInt): CInt = extern
  def UpdateWindow(hwnd: Ptr[Byte]): CInt = extern
  def DestroyWindow(hwnd: Ptr[Byte]): CInt = extern
  def PeekMessageW(msg: Ptr[Byte], hwnd: Ptr[Byte], min: CUnsignedInt, max: CUnsignedInt, remove: CUnsignedInt): CInt = extern
  def TranslateMessage(msg: Ptr[Byte]): CInt = extern
  def DispatchMessageW(msg: Ptr[Byte]): CLongLong = extern
  def GetClientRect(hwnd: Ptr[Byte], rect: Ptr[CInt]): CInt = extern
  def AdjustWindowRectEx(rect: Ptr[CInt], style: CUnsignedInt, menu: CInt, exStyle: CUnsignedInt): CInt = extern
  def SetWindowTextW(hwnd: Ptr[Byte], text: Ptr[CShort]): CInt = extern
  def GetDC(hwnd: Ptr[Byte]): Ptr[Byte] = extern
  def ReleaseDC(hwnd: Ptr[Byte], dc: Ptr[Byte]): CInt = extern
  def StretchDIBits(dc: Ptr[Byte], xd: CInt, yd: CInt, wd: CInt, hd: CInt, xs: CInt, ys: CInt, ws: CInt, hs: CInt,
      bits: Ptr[Byte], bmi: Ptr[Byte], usage: CUnsignedInt, rop: CUnsignedInt): CInt = extern
  def SetCapture(hwnd: Ptr[Byte]): Ptr[Byte] = extern
  def ReleaseCapture(): CInt = extern
  def GetKeyState(vk: CInt): CShort = extern
  def LoadCursorW(inst: Ptr[Byte], name: Ptr[Byte]): Ptr[Byte] = extern
  def SetProcessDPIAware(): CInt = extern
  def GetDpiForWindow(hwnd: Ptr[Byte]): CUnsignedInt = extern
  def ClientToScreen(hwnd: Ptr[Byte], pt: Ptr[CInt]): CInt = extern
  def ValidateRect(hwnd: Ptr[Byte], rect: Ptr[Byte]): CInt = extern
  def SetCursor(cursor: Ptr[Byte]): Ptr[Byte] = extern
  def MsgWaitForMultipleObjects(count: CUnsignedInt, handles: Ptr[Byte], waitAll: CInt, ms: CUnsignedInt, mask: CUnsignedInt): CUnsignedInt = extern
  def SetWindowPos(hwnd: Ptr[Byte], after: Ptr[Byte], x: CInt, y: CInt, w: CInt, h: CInt, flags: CUnsignedInt): CInt = extern
  def GetWindowRect(hwnd: Ptr[Byte], rect: Ptr[CInt]): CInt = extern
  def GetWindowLongPtrW(hwnd: Ptr[Byte], index: CInt): CLongLong = extern
  def SetWindowLongPtrW(hwnd: Ptr[Byte], index: CInt, v: CLongLong): CLongLong = extern
  def SetForegroundWindow(hwnd: Ptr[Byte]): CInt = extern
  def GetForegroundWindow(): Ptr[Byte] = extern
  def FlashWindowEx(info: Ptr[Byte]): CInt = extern
  def SendMessageW(hwnd: Ptr[Byte], msg: CUnsignedInt, wp: CUnsignedLongLong, lp: CLongLong): CLongLong = extern
  def CreateIcon(inst: Ptr[Byte], w: CInt, h: CInt, planes: CUnsignedChar, bits: CUnsignedChar, andBits: Ptr[Byte], xorBits: Ptr[Byte]): Ptr[Byte] = extern
  def MonitorFromWindow(hwnd: Ptr[Byte], flags: CUnsignedInt): Ptr[Byte] = extern
  def GetMonitorInfoW(monitor: Ptr[Byte], info: Ptr[Byte]): CInt = extern
  def DragAcceptFiles(hwnd: Ptr[Byte], accept: CInt): Unit = extern
  def DragQueryFileW(drop: Ptr[Byte], index: CUnsignedInt, buf: Ptr[CShort], cch: CUnsignedInt): CUnsignedInt = extern
  def DragQueryPoint(drop: Ptr[Byte], pt: Ptr[CInt]): CInt = extern
  def DragFinish(drop: Ptr[Byte]): Unit = extern
  def RegGetValueW(key: Ptr[Byte], sub: Ptr[CShort], value: Ptr[CShort], flags: CUnsignedInt, kind: Ptr[CUnsignedInt], data: Ptr[Byte], size: Ptr[CUnsignedInt]): CInt = extern
  def CreateMenu(): Ptr[Byte] = extern
  def Shell_NotifyIconW(msg: CUnsignedInt, data: Ptr[Byte]): CInt = extern
  def TrackPopupMenu(menu: Ptr[Byte], flags: CUnsignedInt, x: CInt, y: CInt, reserved: CInt, hwnd: Ptr[Byte], rect: Ptr[Byte]): CInt = extern
  def GetCursorPos(pt: Ptr[CInt]): CInt = extern
  def DestroyIcon(icon: Ptr[Byte]): CInt = extern
  def CreatePopupMenu(): Ptr[Byte] = extern
  def AppendMenuW(menu: Ptr[Byte], flags: CUnsignedInt, id: CUnsignedLongLong, text: Ptr[CShort]): CInt = extern
  def SetMenu(hwnd: Ptr[Byte], menu: Ptr[Byte]): CInt = extern
  def DestroyMenu(menu: Ptr[Byte]): CInt = extern
  def DrawMenuBar(hwnd: Ptr[Byte]): CInt = extern
  def ImmGetContext(hwnd: Ptr[Byte]): Ptr[Byte] = extern
  def ImmReleaseContext(hwnd: Ptr[Byte], himc: Ptr[Byte]): CInt = extern
  def ImmGetCompositionStringW(himc: Ptr[Byte], index: CUnsignedInt, buf: Ptr[Byte], len: CUnsignedInt): CInt = extern
  def ImmSetCandidateWindow(himc: Ptr[Byte], form: Ptr[Byte]): CInt = extern

/** Windows backend. Events are queued by the window procedure and drained by [[pollEvents]]; pixels are shown with StretchDIBits. */
final class Win32Window private (val hwnd: Ptr[Byte]) extends Window:
  private[window] val queue = ArrayBuffer.empty[Event]
  private[window] var closed = false
  private[window] var highSurrogate = 0

  private def clientSize: (Int, Int) =
    val r = stackalloc[CInt](4)
    W.GetClientRect(hwnd, r)
    (r(2) - r(0), r(3) - r(1))

  def scaleFactor: Double = { val d = W.GetDpiForWindow(hwnd).toInt; if d <= 0 then 1.0 else d / 96.0 }
  def width: Int = math.round(clientSize._1 / scaleFactor).toInt
  def height: Int = math.round(clientSize._2 / scaleFactor).toInt
  def nativeHandle: Ptr[Byte] = hwnd

  def setTitle(title: String): Unit = { windowTitle = title; setTitleNative(title) }
  private def setTitleNative(title: String): Unit = Zone { W.SetWindowTextW(hwnd, Win32Window.wide(title)) }
  def close(): Unit = if !closed then { closed = true; W.DestroyWindow(hwnd) }

  private[window] var cursorHandle: Ptr[Byte] = W.LoadCursorW(null, 32512L.toPtr[Byte])
  private[window] var minW = 0
  private[window] var minH = 0
  private var cursorIcon = CursorIcon.Default
  private var lastTheme: Theme = Theme.Light
  private var savedStyle = 0L
  private var savedRect = Array(0, 0, 0, 0)
  private var fullscreen = false

  override def supportsTextInput: Boolean = true

  private[window] var liveResize: () => Unit = () => ()

  // --- accessibility (UI Automation provider, see Win32Uia / scalaui_uia.c) ---
  private var windowTitle = ""
  private var lastUiaFocus = -1
  private var lastUiaNodes = Vector.empty[AccessNode]
  override def setAccessibility(nodes: Seq[AccessNode]): Unit =
    val previous = lastUiaNodes
    Win32Uia.publish(hwnd, windowTitle, nodes, scaleFactor)
    Win32Uia.raiseTextEvents(hwnd, previous, nodes)
    lastUiaNodes = nodes.toVector
    val focus = nodes.indexWhere(_.focused)
    if focus != lastUiaFocus then { lastUiaFocus = focus; Win32Uia.focusChanged(hwnd, focus) }
  /** DefWindowProc runs a modal loop while the user drags the frame; WM_SIZE arrives inside it, so the app repaints from there. */
  override def setLiveResizeCallback(f: () => Unit): Unit = liveResize = f

  private[window] var shortcuts = Map.empty[(Int, Boolean, Boolean, Boolean), Int]
  private var menuHandle: Ptr[Byte] = null
  override def hasNativeMenus: Boolean = true
  override def setMenuBar(menus: Seq[MenuEntry.Submenu]): Unit = Zone {
    val sc = Map.newBuilder[(Int, Boolean, Boolean, Boolean), Int]
    def label(title: String, shortcut: String): String =
      Shortcut.parse(shortcut, isMac = false).fold(title) { k =>
        val parts = Seq(if k.control then "Ctrl" else "", if k.alt then "Alt" else "", if k.shift then "Shift" else "", k.key.capitalize).filter(_.nonEmpty)
        title + "\t" + parts.mkString("+")
      }
    def build(entries: Seq[MenuEntry]): Ptr[Byte] =
      val m = W.CreatePopupMenu()
      entries.foreach {
        case MenuEntry.Separator => W.AppendMenuW(m, 0x800.toUInt, 0.toULong, null)
        case nested: MenuEntry.Submenu => W.AppendMenuW(m, 0x10.toUInt, build(nested.entries).toLong.toULong, Win32Window.wide(nested.title))
        case MenuEntry.Item(title, id, shortcut, enabled, checked) =>
          val flags = 0 | (if enabled then 0 else 3) | (if checked then 8 else 0) // MF_GRAYED, MF_CHECKED
          W.AppendMenuW(m, flags.toUInt, (id + 1).toULong, Win32Window.wide(label(title, shortcut)))
          if enabled then Shortcut.parse(shortcut, isMac = false).foreach { k =>
            Win32Window.virtualKey(k.key).foreach(vk => sc += ((vk, k.control, k.shift, k.alt) -> id))
          }
      }
      m
    val bar = W.CreateMenu()
    menus.foreach(sub => W.AppendMenuW(bar, 0x10.toUInt, build(sub.entries).toLong.toULong, Win32Window.wide(sub.title))) // MF_POPUP
    val old = menuHandle
    menuHandle = bar
    shortcuts = sc.result()
    W.SetMenu(hwnd, bar)
    if old != null then W.DestroyMenu(old)
    W.DrawMenuBar(hwnd)
    ()
  }

  override def waitEvents(timeoutMs: Int): Seq[Event] =
    if queue.isEmpty then
      // returns early when any message is queued; QS_ALLINPUT
      W.MsgWaitForMultipleObjects(0.toUInt, null, 0, timeoutMs.max(0).toUInt, 0x04FF.toUInt)
    pollEvents()

  override def setCursor(icon: CursorIcon): Unit =
    if icon == cursorIcon then return
    cursorIcon = icon
    val id = icon match
      case CursorIcon.Pointer | CursorIcon.Grab => 32649L
      case CursorIcon.Text => 32513L
      case CursorIcon.Crosshair => 32515L
      case CursorIcon.Grabbing => 32646L
      case CursorIcon.ResizeHorizontal => 32644L
      case CursorIcon.ResizeVertical => 32645L
      case CursorIcon.NotAllowed => 32648L
      case _ => 32512L
    cursorHandle = if icon == CursorIcon.Hidden then null else W.LoadCursorW(null, id.toPtr[Byte])
    W.SetCursor(cursorHandle)

  override def setImeRect(x: Double, y: Double, w: Double, h: Double): Unit =
    val himc = W.ImmGetContext(hwnd)
    if himc == null then return
    val s = scaleFactor
    val f = stackalloc[CInt](8) // CANDIDATEFORM: index, style, POINT, RECT
    f(0) = 0; f(1) = 0x80 // CFS_EXCLUDE: keep the candidate list off the caret rectangle
    f(2) = (x * s).toInt; f(3) = ((y + h) * s).toInt
    f(4) = (x * s).toInt; f(5) = (y * s).toInt; f(6) = ((x + w) * s).toInt; f(7) = ((y + h) * s).toInt
    W.ImmSetCandidateWindow(himc, f.asInstanceOf[Ptr[Byte]])
    W.ImmReleaseContext(hwnd, himc)

  /** Apps-use-light-theme registry flag (0 = dark). */
  override def systemTheme: Theme = Zone {
    val data = stackalloc[CUnsignedInt](1); val size = stackalloc[CUnsignedInt](1)
    size(0) = 4.toUInt; data(0) = 1.toUInt
    val rc = W.RegGetValueW(0x80000001L.toPtr[Byte], Win32Window.wide("Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize"),
      Win32Window.wide("AppsUseLightTheme"), 0x10.toUInt, null, data.asInstanceOf[Ptr[Byte]], size)
    if rc == 0 && data(0).toInt == 0 then Theme.Dark else Theme.Light
  }

  private def style: Long = W.GetWindowLongPtrW(hwnd, -16)
  private def adjusted(w: Int, h: Int): (Int, Int) =
    val r = stackalloc[CInt](4)
    r(0) = 0; r(1) = 0; r(2) = w; r(3) = h
    W.AdjustWindowRectEx(r, style.toInt.toUInt, 0, 0.toUInt)
    (r(2) - r(0), r(3) - r(1))
  override def setMinSize(w: Int, h: Int): Unit =
    val s = scaleFactor
    val (aw, ah) = adjusted((w * s).toInt, (h * s).toInt)
    minW = aw; minH = ah
  override def setResizable(resizable: Boolean): Unit =
    val bits = 0x00050000L // WS_THICKFRAME | WS_MAXIMIZEBOX
    W.SetWindowLongPtrW(hwnd, -16, if resizable then style | bits else style & ~bits)
    W.SetWindowPos(hwnd, null, 0, 0, 0, 0, 0x27.toUInt) // NOMOVE | NOSIZE | NOZORDER | FRAMECHANGED
  override def isFullscreen: Boolean = fullscreen
  override def setFullscreen(on: Boolean): Unit =
    if on == fullscreen then return
    if on then
      val r = stackalloc[CInt](4)
      W.GetWindowRect(hwnd, r)
      savedRect = Array(r(0), r(1), r(2), r(3)); savedStyle = style
      val mi = stackalloc[CInt](10); mi(0) = 40
      W.GetMonitorInfoW(W.MonitorFromWindow(hwnd, 2.toUInt), mi.asInstanceOf[Ptr[Byte]]) // MONITOR_DEFAULTTONEAREST
      W.SetWindowLongPtrW(hwnd, -16, 0x90000000L) // WS_POPUP | WS_VISIBLE
      W.SetWindowPos(hwnd, null, mi(1), mi(2), mi(3) - mi(1), mi(4) - mi(2), 0x24.toUInt) // NOZORDER | FRAMECHANGED
    else
      W.SetWindowLongPtrW(hwnd, -16, savedStyle)
      W.SetWindowPos(hwnd, null, savedRect(0), savedRect(1), savedRect(2) - savedRect(0), savedRect(3) - savedRect(1), 0x24.toUInt)
    fullscreen = on
  override def setMinimized(minimized: Boolean): Unit = W.ShowWindow(hwnd, if minimized then 6 else 9)
  override def setAlwaysOnTop(onTop: Boolean): Unit = W.SetWindowPos(hwnd, (if onTop then -1L else -2L).toPtr[Byte], 0, 0, 0, 0, 0x3.toUInt)
  override def setVisible(visible: Boolean): Unit = { W.ShowWindow(hwnd, if visible then 5 else 0); () }
  override def focus(): Unit = { W.SetForegroundWindow(hwnd); () }
  override def requestAttention(): Unit =
    val f = stackalloc[CInt](8); var i = 0; while i < 8 do { f(i) = 0; i += 1 }
    f(0) = 32; (f + 2).asInstanceOf[Ptr[Ptr[Byte]]](0) = hwnd; f(4) = 15 // FLASHW_ALL | FLASHW_TIMERNOFG
    W.FlashWindowEx(f.asInstanceOf[Ptr[Byte]])
    ()
  override def position: (Int, Int) =
    val r = stackalloc[CInt](4); W.GetWindowRect(hwnd, r)
    val s = scaleFactor
    ((r(0) / s).toInt, (r(1) / s).toInt)
  override def setPosition(x: Int, y: Int): Unit =
    val s = scaleFactor
    W.SetWindowPos(hwnd, null, (x * s).toInt, (y * s).toInt, 0, 0, 0x15.toUInt) // NOSIZE | NOZORDER | NOACTIVATE
    ()
  private def makeIcon(rgba: Array[Byte], pixelWidth: Int, pixelHeight: Int): Ptr[Byte] =
    val xor = new Array[Byte](pixelWidth * pixelHeight * 4)
    var i = 0
    while i < xor.length do { xor(i) = rgba(i + 2); xor(i + 1) = rgba(i + 1); xor(i + 2) = rgba(i); xor(i + 3) = rgba(i + 3); i += 4 }
    val and = new Array[Byte](((pixelWidth + 15) / 16) * 2 * pixelHeight)
    W.CreateIcon(null, pixelWidth, pixelHeight, 1.toUByte, 32.toUByte, and.atUnsafe(0), xor.atUnsafe(0))

  private[window] var trayMenu: Ptr[Byte] = null
  private var trayIcon: Ptr[Byte] = null
  private var trayAdded = false

  /** NOTIFYICONDATAW (x64 layout): cbSize@0 hWnd@8 uID@16 uFlags@20 callback@24 hIcon@32 szTip@40. */
  private def notify(op: Int, icon: Ptr[Byte], tooltip: String): Unit = Zone {
    val d = alloc[Byte](976); var i = 0; while i < 976 do { d(i) = 0.toByte; i += 1 }
    d.asInstanceOf[Ptr[CUnsignedInt]](0) = 976.toUInt
    (d + 8).asInstanceOf[Ptr[Ptr[Byte]]](0) = hwnd
    (d + 16).asInstanceOf[Ptr[CUnsignedInt]](0) = 1.toUInt
    (d + 20).asInstanceOf[Ptr[CUnsignedInt]](0) = 0x7.toUInt // NIF_MESSAGE | NIF_ICON | NIF_TIP
    (d + 24).asInstanceOf[Ptr[CUnsignedInt]](0) = 0x8001.toUInt // WM_APP + 1
    (d + 32).asInstanceOf[Ptr[Ptr[Byte]]](0) = icon
    val tip = (d + 40).asInstanceOf[Ptr[CShort]]
    tooltip.take(127).zipWithIndex.foreach((c, k) => tip(k) = c.toShort)
    W.Shell_NotifyIconW(op.toUInt, d)
    ()
  }
  override def setTray(rgba: Array[Byte], pixelWidth: Int, pixelHeight: Int, tooltip: String, menu: Seq[MenuEntry]): Unit =
    val icon = makeIcon(rgba, pixelWidth, pixelHeight)
    if trayMenu != null then W.DestroyMenu(trayMenu)
    trayMenu = Win32Window.popup(menu)
    notify(if trayAdded then 1 else 0, icon, tooltip) // NIM_MODIFY / NIM_ADD
    trayAdded = true
    if trayIcon != null then W.DestroyIcon(trayIcon)
    trayIcon = icon
  override def removeTray(): Unit =
    if trayAdded then { notify(2, trayIcon, ""); trayAdded = false } // NIM_DELETE
  /** Shows the tray menu at the cursor and queues the chosen item. */
  private[window] def showTrayMenu(): Unit =
    if trayMenu == null then return
    val pt = stackalloc[CInt](2); W.GetCursorPos(pt)
    W.SetForegroundWindow(hwnd) // required so the menu closes when clicking elsewhere
    val cmd = W.TrackPopupMenu(trayMenu, 0x100.toUInt, pt(0), pt(1), 0, hwnd, null) // TPM_RETURNCMD
    if cmd > 0 then queue += Event.MenuAction(cmd - 1)

  override def setIcon(rgba: Array[Byte], pixelWidth: Int, pixelHeight: Int): Unit =
    require(rgba.length >= pixelWidth * pixelHeight * 4, "pixel buffer too small")
    val icon = makeIcon(rgba, pixelWidth, pixelHeight)
    if icon != null then
      W.SendMessageW(hwnd, 0x0080.toUInt, 0.toULong, icon.toLong) // WM_SETICON ICON_SMALL
      W.SendMessageW(hwnd, 0x0080.toUInt, 1.toULong, icon.toLong) // ICON_BIG
      ()

  def pollEvents(): Seq[Event] =
    val msg = stackalloc[Byte](48)
    while W.PeekMessageW(msg, null, 0.toUInt, 0.toUInt, 1.toUInt) != 0 do // PM_REMOVE
      W.TranslateMessage(msg)
      W.DispatchMessageW(msg)
    val theme = systemTheme
    if theme != lastTheme then { lastTheme = theme; queue += Event.ThemeChanged(theme) }
    Win32Uia.presses(hwnd).foreach(i => queue += Event.AccessPress(i))
    Win32Uia.edits(hwnd).foreach(queue += _)
    val out = queue.toVector
    queue.clear()
    out

  // BGRX copy of the last presented frame, kept so a partial present converts only the dirty rectangle
  private var bgra: Array[Byte] = new Array[Byte](0)
  private var bgraW = 0; private var bgraH = 0
  private var lastBlit1to1 = false // the screen shows the image unscaled

  def present(rgba: Array[Byte], pixelWidth: Int, pixelHeight: Int): Unit =
    present(rgba, pixelWidth, pixelHeight, 0, 0, pixelWidth, pixelHeight)

  override def present(rgba: Array[Byte], pixelWidth: Int, pixelHeight: Int, rx: Int, ry: Int, rw: Int, rh: Int): Unit =
    if pixelWidth <= 0 || pixelHeight <= 0 || closed then return
    require(rgba.length >= pixelWidth * pixelHeight * 4, "pixel buffer too small")
    var x0 = math.max(0, rx); var y0 = math.max(0, ry)
    var x1 = math.min(pixelWidth.toLong, rx.toLong + rw).toInt; var y1 = math.min(pixelHeight.toLong, ry.toLong + rh).toInt
    if bgraW != pixelWidth || bgraH != pixelHeight then
      bgra = new Array[Byte](pixelWidth * pixelHeight * 4); bgraW = pixelWidth; bgraH = pixelHeight
      x0 = 0; y0 = 0; x1 = pixelWidth; y1 = pixelHeight // new size: convert and paint everything
    val (cw, ch) = clientSize
    val oneToOne = cw == pixelWidth && ch == pixelHeight
    if !(oneToOne && lastBlit1to1) then { x0 = 0; y0 = 0; x1 = pixelWidth; y1 = pixelHeight } // client resized or previous blit was scaled
    lastBlit1to1 = oneToOne
    if x1 <= x0 || y1 <= y0 then return
    // premultiplied RGBA -> BGRX, only the dirty rectangle
    var y = y0
    while y < y1 do
      var i = (y * pixelWidth + x0) * 4; val end = (y * pixelWidth + x1) * 4
      while i < end do { bgra(i) = rgba(i + 2); bgra(i + 1) = rgba(i + 1); bgra(i + 2) = rgba(i); bgra(i + 3) = -1; i += 4 }
      y += 1
    val bmi = stackalloc[Byte](40)
    var k = 0
    while k < 40 do { bmi(k) = 0.toByte; k += 1 }
    val h32 = bmi.asInstanceOf[Ptr[CInt]]
    h32(0) = 40; h32(1) = pixelWidth; h32(2) = -(y1 - y0) // top-down, only the dirty rows
    val h16 = (bmi + 12).asInstanceOf[Ptr[CShort]]
    h16(0) = 1.toShort; h16(1) = 32.toShort
    val dc = W.GetDC(hwnd)
    if oneToOne then
      // 1:1: blit just the dirty rectangle (the bits pointer starts at its first row)
      W.StretchDIBits(dc, x0, y0, x1 - x0, y1 - y0, x0, 0, x1 - x0, y1 - y0, bgra.atUnsafe(y0 * pixelWidth * 4), bmi, 0.toUInt, 0x00CC0020.toUInt)
    else
      // scaled: the whole image is stretched to the client area
      h32(2) = -pixelHeight
      W.StretchDIBits(dc, 0, 0, cw, ch, 0, 0, pixelWidth, pixelHeight, bgra.atUnsafe(0), bmi, 0.toUInt, 0x00CC0020.toUInt)
    W.ReleaseDC(hwnd, dc)

object Win32Window:
  private val ClassName = "ScalauiWindow"
  private val windows = mutable.HashMap.empty[Long, Win32Window]
  private var registered = false

  private[scalaui] def wideString(s: String)(using Zone): Ptr[CShort] = wide(s)

  private[window] def windowFor(hwnd: Ptr[Byte]): Option[Win32Window] = windows.get(hwnd.toLong)

  private[window] def wide(s: String)(using Zone): Ptr[CShort] =
    val p = alloc[CShort](s.length + 1)
    var i = 0
    while i < s.length do { p(i) = s.charAt(i).toShort; i += 1 }
    p(s.length) = 0.toShort
    p

  /** A popup menu for `entries` (items carry id + 1 so that 0 means "cancelled"). */
  private[window] def popup(entries: Seq[MenuEntry]): Ptr[Byte] = Zone {
    val m = W.CreatePopupMenu()
    entries.foreach {
      case MenuEntry.Separator => W.AppendMenuW(m, 0x800.toUInt, 0.toULong, null)
      case n: MenuEntry.Submenu => W.AppendMenuW(m, 0x10.toUInt, popup(n.entries).toLong.toULong, wide(n.title))
      case MenuEntry.Item(t, id, _, enabled, checked) =>
        W.AppendMenuW(m, ((if enabled then 0 else 3) | (if checked then 8 else 0)).toUInt, (id + 1).toULong, wide(t))
    }
    m
  }

  /** Windows virtual-key code for a shortcut key name. */
  private[window] def virtualKey(k: String): Option[Int] = k match
    case "enter" => Some(0x0D); case "tab" => Some(0x09); case "esc" => Some(0x1B); case "delete" => Some(0x2E)
    case "backspace" => Some(0x08); case "space" => Some(0x20); case "left" => Some(0x25); case "up" => Some(0x26)
    case "right" => Some(0x27); case "down" => Some(0x28)
    case f if f.length >= 2 && f.startsWith("f") && f.drop(1).forall(_.isDigit) => Some(0x6F + f.drop(1).toInt)
    case c if c.length == 1 && (c.head.isLetterOrDigit && c.head < 128) => Some(c.head.toUpper.toInt)
    case _ => None

  private def mods: Modifiers =
    def down(vk: Int) = (W.GetKeyState(vk) & 0x8000) != 0
    Modifiers(shift = down(0x10), control = down(0x11), alt = down(0x12), command = down(0x5B) || down(0x5C))

  private def keyOf(vk: Int): Key = vk match
    case 0x25 => Key.Left; case 0x27 => Key.Right; case 0x26 => Key.Up; case 0x28 => Key.Down
    case 0x24 => Key.Home; case 0x23 => Key.End; case 0x21 => Key.PageUp; case 0x22 => Key.PageDown
    case 0x08 => Key.Backspace; case 0x2E => Key.Delete; case 0x0D => Key.Enter
    case 0x09 => Key.Tab; case 0x1B => Key.Escape; case 0x20 => Key.Space
    case c => Key.Other(c)

  /** Window procedure: translates messages into [[Event]]s on the owning window's queue. */
  private def wndProc(hwnd: Ptr[Byte], msg: CUnsignedInt, wp: CUnsignedLongLong, lp: CLongLong): CLongLong =
    val win = windows.get(hwnd.toLong).orNull
    if win == null then return W.DefWindowProcW(hwnd, msg, wp, lp)
    val wpl = wp.toLong
    val lx = (lp & 0xffff).toShort.toDouble; val ly = ((lp >> 16) & 0xffff).toShort.toDouble
    val s = win.scaleFactor
    def mx = lx / s; def my = ly / s
    msg.toInt match
      case 0x003D if lp == -25L => Win32Uia.rootObject(hwnd, wp, lp) // WM_GETOBJECT with UiaRootObjectId
      case 0x0010 => win.queue += Event.CloseRequested; 0L                       // WM_CLOSE
      case 0x0002 => win.closed = true; windows.remove(hwnd.toLong); 0L          // WM_DESTROY
      case 0x0005 => win.queue += Event.Resized(win.width, win.height); win.liveResize(); 0L // WM_SIZE
      case 0x000F => W.ValidateRect(hwnd, null); 0L                              // WM_PAINT
      case 0x0200 => win.queue += Event.MouseMoved(mx, my); 0L                   // WM_MOUSEMOVE
      case 0x0201 => W.SetCapture(hwnd); win.queue += Event.MouseDown(MouseButton.Left, mx, my, mods); 0L
      case 0x0202 => W.ReleaseCapture(); win.queue += Event.MouseUp(MouseButton.Left, mx, my); 0L
      case 0x0204 => win.queue += Event.MouseDown(MouseButton.Right, mx, my, mods); 0L
      case 0x0205 => win.queue += Event.MouseUp(MouseButton.Right, mx, my); 0L
      case 0x0207 => win.queue += Event.MouseDown(MouseButton.Other, mx, my, mods); 0L
      case 0x020A => // WM_MOUSEWHEEL: delta in the high word of wParam, 120 per notch
        val delta = ((wpl >> 16) & 0xffff).toShort.toDouble / 120.0
        win.queue += Event.Scroll(0, delta * 10); 0L
      case 0x0100 | 0x0104 if win.shortcuts.nonEmpty && {
            val m = mods; win.shortcuts.contains(((wpl & 0xff).toInt, m.control, m.shift, m.alt)) } =>
        val m = mods
        win.queue += Event.MenuAction(win.shortcuts(((wpl & 0xff).toInt, m.control, m.shift, m.alt))); 0L // menu shortcuts replace the key event
      case 0x8001 if lp == 0x205L || lp == 0x202L => win.showTrayMenu(); 0L // tray icon right/left click
      case 0x0111 if lp == 0L => win.queue += Event.MenuAction((wpl & 0xffff).toInt - 1); 0L // WM_COMMAND from a menu item
      case 0x0100 | 0x0104 => // WM_KEYDOWN / WM_SYSKEYDOWN
        val vk = (wpl & 0xff).toInt
        val chars = if (vk >= 0x41 && vk <= 0x5A) || (vk >= 0x30 && vk <= 0x39) then vk.toChar.toLower.toString else ""
        win.queue += Event.KeyDown(keyOf(vk), "", chars, mods, ((lp >> 30) & 1) != 0)
        if msg.toInt == 0x0104 then W.DefWindowProcW(hwnd, msg, wp, lp) else 0L
      case 0x0101 | 0x0105 => win.queue += Event.KeyUp(keyOf((wpl & 0xff).toInt), mods); 0L
      case 0x0020 if (lp & 0xffff) == 1L => // WM_SETCURSOR over the client area
        W.SetCursor(win.cursorHandle); 1L
      case 0x0007 => win.queue += Event.Focused(true); 0L
      case 0x0008 => win.queue += Event.Focused(false); 0L
      case 0x0024 => // WM_GETMINMAXINFO: ptMinTrackSize
        if win.minW > 0 then { val p = lp.toPtr[CInt]; p(6) = win.minW; p(7) = win.minH }
        0L
      case 0x02E0 => // WM_DPICHANGED: lParam holds the suggested window rectangle
        val r = lp.toPtr[CInt]
        W.SetWindowPos(hwnd, null, r(0), r(1), r(2) - r(0), r(3) - r(1), 0x14.toUInt)
        win.queue += Event.ScaleChanged(win.scaleFactor); 0L
      case 0x0233 => // WM_DROPFILES
        val h = wp.toLong.toPtr[Byte]
        val n = W.DragQueryFileW(h, 0xFFFFFFFF.toUInt, null, 0.toUInt).toInt
        val paths = (0 until n).map { i =>
          val len = W.DragQueryFileW(h, i.toUInt, null, 0.toUInt).toInt
          Zone {
            val buf = alloc[CShort](len + 1)
            W.DragQueryFileW(h, i.toUInt, buf, (len + 1).toUInt)
            val sb = new StringBuilder
            var k = 0; while k < len do { sb += (buf(k).toInt & 0xffff).toChar; k += 1 }
            sb.result()
          }
        }
        val pt = stackalloc[CInt](2)
        W.DragQueryPoint(h, pt)
        W.DragFinish(h)
        win.queue += Event.FilesDropped(paths, pt(0) / s, pt(1) / s); 0L
      case 0x001A => // WM_SETTINGCHANGE (theme switches arrive as "ImmersiveColorSet")
        W.DefWindowProcW(hwnd, msg, wp, lp)
      case 0x010D => 0L // WM_IME_STARTCOMPOSITION: composition is drawn inline by the app
      case 0x010E => win.queue += Event.Composition("", 0, 0); W.DefWindowProcW(hwnd, msg, wp, lp) // WM_IME_ENDCOMPOSITION
      case 0x010F => // WM_IME_COMPOSITION
        val himc = W.ImmGetContext(hwnd)
        def str(kind: Int): String =
          val bytes = W.ImmGetCompositionStringW(himc, kind.toUInt, null, 0.toUInt)
          if bytes <= 0 then ""
          else
            val buf = new Array[Byte](bytes)
            W.ImmGetCompositionStringW(himc, kind.toUInt, buf.atUnsafe(0), bytes.toUInt)
            val sb = new StringBuilder
            var k = 0; while k + 1 < bytes do { sb += (((buf(k) & 0xff) | ((buf(k + 1) & 0xff) << 8)).toChar); k += 2 }
            sb.result()
        if himc != null then
          if (lp & 0x0800L) != 0 then { win.queue += Event.Composition("", 0, 0); win.queue += Event.TextInput(str(0x0800)) } // GCS_RESULTSTR
          if (lp & 0x0008L) != 0 then
            val text = str(0x0008) // GCS_COMPSTR
            val cur = W.ImmGetCompositionStringW(himc, 0x80.toUInt, null, 0.toUInt) // GCS_CURSORPOS (returned directly)
            win.queue += Event.Composition(text, cur.max(0), 0)
          W.ImmReleaseContext(hwnd, himc)
        0L
      case 0x0102 => // WM_CHAR: UTF-16 code unit
        val c = (wpl & 0xffff).toInt
        val m = mods
        if c >= 0xD800 && c <= 0xDBFF then win.highSurrogate = c
        else
          val text =
            if c >= 0xDC00 && c <= 0xDFFF && win.highSurrogate != 0 then { val t = new String(Array(win.highSurrogate.toChar, c.toChar)); win.highSurrogate = 0; t }
            else if c >= 0x20 && c != 0x7f then c.toChar.toString else ""
          if text.nonEmpty && !(m.control && !m.alt) then win.queue += Event.TextInput(text) // AltGr reports Ctrl+Alt
        0L
      case _ => W.DefWindowProcW(hwnd, msg, wp, lp)

  def apply(title: String, width: Int, height: Int): Win32Window = Zone {
    W.SetProcessDPIAware()
    val inst = W.GetModuleHandleW(null)
    if !registered then
      registered = true
      val wc = alloc[Byte](80)
      var i = 0; while i < 80 do { wc(i) = 0.toByte; i += 1 }
      (wc.asInstanceOf[Ptr[CUnsignedInt]])(0) = 80.toUInt
      (wc + 4).asInstanceOf[Ptr[CUnsignedInt]](0) = 0x0003.toUInt // CS_HREDRAW | CS_VREDRAW
      (wc + 8).asInstanceOf[Ptr[CFuncPtr4[Ptr[Byte], CUnsignedInt, CUnsignedLongLong, CLongLong, CLongLong]]](0) = CFuncPtr4.fromScalaFunction(wndProc)
      (wc + 24).asInstanceOf[Ptr[Ptr[Byte]]](0) = inst
      (wc + 40).asInstanceOf[Ptr[Ptr[Byte]]](0) = W.LoadCursorW(null, 32512L.toPtr[Byte]) // IDC_ARROW
      (wc + 64).asInstanceOf[Ptr[Ptr[CShort]]](0) = wide(ClassName)
      W.RegisterClassExW(wc)
    val style = 0x00CF0000.toUInt // WS_OVERLAPPEDWINDOW
    val rect = alloc[CInt](4)
    // client size in device pixels = logical * dpi scale; DPI is unknown before the window exists, so assume the system DPI
    rect(0) = 0; rect(1) = 0; rect(2) = width; rect(3) = height
    W.AdjustWindowRectEx(rect, style, 0, 0.toUInt)
    val hwnd = W.CreateWindowExW(0.toUInt, wide(ClassName), wide(title), style, 0x80000000, 0x80000000, rect(2) - rect(0), rect(3) - rect(1), null, null, inst, null)
    if hwnd == null then throw new RuntimeException("CreateWindowExW failed")
    val win = new Win32Window(hwnd)
    win.windowTitle = title
    windows(hwnd.toLong) = win
    W.DragAcceptFiles(hwnd, 1)
    W.ShowWindow(hwnd, 5) // SW_SHOW
    W.UpdateWindow(hwnd)
    win
  }
