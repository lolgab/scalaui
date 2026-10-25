package scalaui.window

import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*
import scala.collection.mutable.ArrayBuffer
import java.nio.file.{Files, Paths}

/** libc socket calls (musl/glibc): X11 is spoken directly over the Unix socket, so no libX11/xcb is linked. */
@extern private[window] object Sock:
  def socket(domain: CInt, typ: CInt, proto: CInt): CInt = extern
  @blocking def connect(fd: CInt, addr: Ptr[Byte], len: CUnsignedInt): CInt = extern
  @blocking def read(fd: CInt, buf: Ptr[Byte], n: CSize): CSSize = extern
  @blocking def write(fd: CInt, buf: Ptr[Byte], n: CSize): CSSize = extern
  def recv(fd: CInt, buf: Ptr[Byte], n: CSize, flags: CInt): CSSize = extern
  def close(fd: CInt): CInt = extern
  @blocking def poll(fds: Ptr[Byte], n: CSize, timeoutMs: CInt): CInt = extern

/** Minimal X11 core-protocol client: connection setup, window, events, keyboard mapping and PutImage presentation. */
private[window] final class X11Connection(fd: Int):
  def hasBuffered: Boolean = rxBuf.nonEmpty
  /** Waits until the socket is readable (or the timeout passes). */
  def waitReadable(timeoutMs: Int): Unit =
    val p = stackalloc[Byte](8)
    p.asInstanceOf[Ptr[CInt]](0) = fd; (p + 4).asInstanceOf[Ptr[CShort]](0) = 1.toShort; (p + 6).asInstanceOf[Ptr[CShort]](0) = 0.toShort
    Sock.poll(p, 1.toCSize, timeoutMs)
    ()

  private val rxBuf = ArrayBuffer.empty[Byte]
  var rootWindow = 0; var rootVisual = 0; var rootDepth = 24; var idBase = 0; var idMask = 0
  var maxRequestBytes = 262140; var minKeycode = 8; var maxKeycode = 255
  private var nextId = 0

  def allocId(): Int = { nextId += 1; idBase | (nextId & idMask) }

  def writeAll(data: Array[Byte]): Unit =
    var off = 0
    while off < data.length do
      val n = Sock.write(fd, data.atUnsafe(off), (data.length - off).toCSize).toInt
      if n <= 0 then throw new RuntimeException("X11 connection closed")
      off += n

  /** Reads exactly `n` bytes (blocking). */
  def readExact(n: Int): Array[Byte] =
    val out = new Array[Byte](n); var off = 0
    // drain buffered bytes first
    while off < n && rxBuf.nonEmpty do { out(off) = rxBuf.remove(0); off += 1 }
    while off < n do
      val r = Sock.read(fd, out.atUnsafe(off), (n - off).toCSize).toInt
      if r <= 0 then throw new RuntimeException("X11 connection closed")
      off += r
    out

  /** Non-blocking: moves whatever the socket has into the buffer. */
  def fill(): Unit =
    val tmp = new Array[Byte](65536)
    var go = true
    while go do
      val r = Sock.recv(fd, tmp.atUnsafe(0), tmp.length.toCSize, 0x40).toInt // MSG_DONTWAIT
      if r > 0 then { var i = 0; while i < r do { rxBuf += tmp(i); i += 1 } } else go = false

  /** Pops one 32-byte event/error if buffered. */
  def pollPacket(): Option[Array[Byte]] =
    if rxBuf.length >= 32 then { val p = new Array[Byte](32); var i = 0; while i < 32 do { p(i) = rxBuf.remove(0); i += 1 }; Some(p) } else None

  def close(): Unit = Sock.close(fd)

  // --- request helpers ---
  def u16(b: Array[Byte], o: Int): Int = (b(o) & 0xff) | ((b(o + 1) & 0xff) << 8)
  def u32(b: Array[Byte], o: Int): Int = u16(b, o) | (u16(b, o + 2) << 16)
  def pad4(n: Int): Int = (4 - n % 4) % 4

/** Lets the `clipboard` module reach the X11 selection owner without depending on window internals. */
object PlatformClipboard:
  /** Selection access by MIME type: `text/plain` (UTF-8) and `image/png` are used by the clipboard module. */
  trait Backend:
    def get(mime: String): Option[Array[Byte]]
    def set(items: Map[String, Array[Byte]]): Unit
  @volatile private[scalaui] var backend: Option[Backend] = None
  def get(): Option[String] = getData("text/plain").map(new String(_, "UTF-8"))
  def set(text: String): Unit = setData(Map("text/plain" -> text.getBytes("UTF-8")))
  def getData(mime: String): Option[Array[Byte]] = backend.flatMap(_.get(mime))
  def setData(items: Map[String, Array[Byte]]): Unit = backend.foreach(_.set(items))

object X11Window:
  private def put16(b: ArrayBuffer[Byte], v: Int): Unit = { b += v.toByte; b += (v >> 8).toByte }
  private def put32(b: ArrayBuffer[Byte], v: Int): Unit = { put16(b, v); put16(b, v >> 16) }

  /** Looks up the MIT-MAGIC-COOKIE-1 for display `num` in ~/.Xauthority (or $XAUTHORITY). */
  private[window] def readCookie(num: String, hostname: String): Array[Byte] =
    try
      val path = sys.env.getOrElse("XAUTHORITY", sys.env.getOrElse("HOME", "") + "/.Xauthority")
      if !Files.exists(Paths.get(path)) then return Array.emptyByteArray
      val d = Files.readAllBytes(Paths.get(path)); var p = 0
      def be16 = { val v = ((d(p) & 0xff) << 8) | (d(p + 1) & 0xff); p += 2; v }
      def str(n: Int) = { val s = new String(d, p, n, "ISO-8859-1"); p += n; s }
      while p + 2 < d.length do
        val family = be16
        val addr = str(be16); val number = str(be16); val name = str(be16)
        val len = be16; val data = d.slice(p, p + len); p += len
        if name == "MIT-MAGIC-COOKIE-1" && number == num && (family == 0xffff || addr == hostname || family == 256) then return data
      Array.emptyByteArray
    catch case _: Exception => Array.emptyByteArray

  def apply(title: String, width: Int, height: Int): X11Window = Zone {
    val display = sys.env.getOrElse("DISPLAY", ":0")
    val colon = display.lastIndexOf(':')
    val num = display.substring(colon + 1).takeWhile(_.isDigit)
    val sockPath = s"/tmp/.X11-unix/X$num"
    val fd = Sock.socket(1, 1, 0) // AF_UNIX, SOCK_STREAM
    if fd < 0 then throw new RuntimeException("socket() failed")
    val addr = alloc[Byte](110)
    var i = 0; while i < 110 do { addr(i) = 0.toByte; i += 1 }
    addr(0) = 1.toByte // AF_UNIX (little endian u16)
    sockPath.zipWithIndex.foreach((c, j) => addr(2 + j) = c.toByte)
    if Sock.connect(fd, addr, 110.toUInt) != 0 then throw new RuntimeException(s"cannot connect to X server at $sockPath (DISPLAY=$display)")
    val c = new X11Connection(fd)

    // --- handshake ---
    val hostname = try new String(Files.readAllBytes(Paths.get("/etc/hostname"))).trim catch case _: Exception => ""
    val cookie = readCookie(num, hostname)
    val authName = if cookie.isEmpty then Array.emptyByteArray else "MIT-MAGIC-COOKIE-1".getBytes("ISO-8859-1")
    val hs = ArrayBuffer.empty[Byte]
    hs += 'l'.toByte; hs += 0; put16(hs, 11); put16(hs, 0); put16(hs, authName.length); put16(hs, cookie.length); put16(hs, 0)
    hs ++= authName; for _ <- 0 until c.pad4(authName.length) do hs += 0
    hs ++= cookie; for _ <- 0 until c.pad4(cookie.length) do hs += 0
    c.writeAll(hs.toArray)
    val head = c.readExact(8)
    val body = c.readExact(c.u16(head, 6) * 4)
    if head(0) != 1 then throw new RuntimeException("X11 handshake refused: " + new String(body, 0, math.min(body.length, head(1) & 0xff), "ISO-8859-1"))
    c.idBase = c.u32(body, 4); c.idMask = c.u32(body, 8)
    val vendorLen = c.u16(body, 16); c.maxRequestBytes = c.u16(body, 18) * 4
    val nScreens = body(20) & 0xff; val nFormats = body(21) & 0xff
    c.minKeycode = body(26) & 0xff; c.maxKeycode = body(27) & 0xff
    val screenOff = 32 + vendorLen + c.pad4(vendorLen) + nFormats * 8
    require(nScreens >= 1, "no X screens")
    c.rootWindow = c.u32(body, screenOff); c.rootVisual = c.u32(body, screenOff + 32); c.rootDepth = body(screenOff + 38) & 0xff

    val win = new X11Window(c, width, height)
    win.create(title)
    win
  }

/** Linux/BSD backend over the X11 wire protocol (works on Wayland through XWayland). Software-presented via PutImage. */
final class X11Window private[window] (c: X11Connection, w: Int, h: Int) extends Window:
  private var winId = 0
  private var gc = 0
  private var curW = w; private var curH = h
  private var wmProtocols = 0; private var wmDelete = 0
  private var keymap = Array.empty[Array[Int]]
  private var scale = sys.env.get("SCALAUI_SCALE").flatMap(_.toDoubleOption).getOrElse(1.0)
  private var lastPixels: Array[Byte] = null; private var lastW = 0; private var lastH = 0
  private var closed = false
  // clipboard (ICCCM selections)
  private var atomClipboard = 0; private var atomUtf8 = 0; private var atomTargets = 0; private var atomProp = 0; private var atomString = 31
  private var ownedItems: Map[String, Array[Byte]] = Map.empty
  private var selectionResult: Option[Option[Array[Byte]]] = None

  private def put16(b: ArrayBuffer[Byte], v: Int): Unit = { b += v.toByte; b += (v >> 8).toByte }
  private def put32(b: ArrayBuffer[Byte], v: Int): Unit = { put16(b, v); put16(b, v >> 16) }
  private def req(opcode: Int, data: Int, body: ArrayBuffer[Byte] => Unit): Unit =
    val b = ArrayBuffer.empty[Byte]
    body(b)
    while b.length % 4 != 0 do b += 0
    val out = ArrayBuffer.empty[Byte]
    out += opcode.toByte; out += data.toByte; put16(out, 1 + b.length / 4)
    out ++= b
    c.writeAll(out.toArray)

  private def reply(): Array[Byte] =
    // skip events/errors until the reply (first byte 1) arrives
    var result: Array[Byte] = null
    while result == null do
      val h = c.readExact(32)
      (h(0) & 0xff) match
        case 1 => val extra = c.u32(h, 4) * 4; result = if extra > 0 then h ++ c.readExact(extra) else h
        case 0 => throw new RuntimeException(s"X11 error code ${h(1) & 0xff} for request ${c.u16(h, 8)}/${h(10) & 0xff}")
        case _ => pending += h // an event arrived before the reply; keep it
    result

  private val pending = ArrayBuffer.empty[Array[Byte]]

  private def intern(name: String): Int =
    req(16, 0, b => { put16(b, name.length); put16(b, 0); b ++= name.getBytes("ISO-8859-1") })
    c.u32(reply(), 8)

  private[window] def create(title: String): Unit =
    winId = c.allocId()
    // CreateWindow: depth, parent, x, y, w, h, border, class InputOutput, visual, value-mask = EventMask
    req(1, c.rootDepth, b =>
      put32(b, winId); put32(b, c.rootWindow); put16(b, 0); put16(b, 0); put16(b, curW); put16(b, curH); put16(b, 0)
      put16(b, 1); put32(b, c.rootVisual); put32(b, 0x800)
      // KeyPress|KeyRelease|ButtonPress|ButtonRelease|PointerMotion|Exposure|StructureNotify
      put32(b, 0x1 | 0x2 | 0x4 | 0x8 | 0x40 | 0x8000 | 0x20000 | 0x200000 | 0x400000))
      // + FocusChange | PropertyChange (INCR transfers)
    setTitle(title)
    wmProtocols = intern("WM_PROTOCOLS"); wmDelete = intern("WM_DELETE_WINDOW")
    req(18, 0, b => { put32(b, winId); put32(b, wmProtocols); put32(b, 4); b += 32; b += 0; b += 0; b += 0; put32(b, 1); put32(b, wmDelete) }) // ChangeProperty: ATOM format 32
    gc = c.allocId()
    req(55, 0, b => { put32(b, gc); put32(b, winId); put32(b, 0) }) // CreateGC
    req(8, 0, b => put32(b, winId)) // MapWindow
    loadKeymap()
    atomClipboard = intern("CLIPBOARD"); atomUtf8 = intern("UTF8_STRING"); atomTargets = intern("TARGETS"); atomProp = intern("SCALAUI_SELECTION")
    PlatformClipboard.backend = Some(new PlatformClipboard.Backend:
      def get(mime: String): Option[Array[Byte]] = clipboardGet(mime)
      def set(items: Map[String, Array[Byte]]): Unit = clipboardSet(items))
    loadDpi()
    atomDropProp = intern("SCALAUI_DROP"); atomUriList = intern("text/uri-list"); atomXdndSelection = intern("XdndSelection")
    atomXdndEnter = intern("XdndEnter"); atomXdndPosition = intern("XdndPosition"); atomXdndLeave = intern("XdndLeave")
    atomXdndDrop = intern("XdndDrop"); atomXdndStatus = intern("XdndStatus"); atomXdndFinished = intern("XdndFinished")
    atomXdndCopy = intern("XdndActionCopy"); atomIncr = intern("INCR")
    val aware = intern("XdndAware")
    req(18, 0, b => { put32(b, winId); put32(b, aware); put32(b, 4); b += 32; b += 0; b += 0; b += 0; put32(b, 1); put32(b, 5) }) // XdndAware = version 5
    lastTheme = systemTheme
    initXim()

  private var atomDropProp = 0; private var atomUriList = 0; private var atomXdndSelection = 0; private var atomXdndEnter = 0
  private var atomXdndPosition = 0; private var atomXdndLeave = 0; private var atomXdndDrop = 0; private var atomXdndStatus = 0
  private val incrOut = scala.collection.mutable.HashMap.empty[(Int, Int), (Int, Array[Byte], Int)]
  private var incrIn: Option[scala.collection.mutable.ArrayBuffer[Byte]] = None
  private var atomXdndFinished = 0; private var atomXdndCopy = 0; private var atomIncr = 0
  private var dragSource = 0; private var dragHasUris = false; private var dragPos = (0.0, 0.0); private var dragPaths: Seq[String] = Nil
  private var lastTheme: Theme = Theme.Light
  private var lastThemeCheck = 0L
  private var cursorIcon = CursorIcon.Default
  private val cursors = scala.collection.mutable.HashMap.empty[CursorIcon, Int]
  private var fullscreen = false
  private var wmState = 0

  private def clientMessage(dest: Int, mask: Int, window: Int, typ: Int, d: Int*): Unit =
    req(25, 0, b => {
      put32(b, dest); put32(b, mask)
      b += 33; b += 32; put16(b, 0); put32(b, window); put32(b, typ)
      for i <- 0 until 5 do put32(b, d.lift(i).getOrElse(0)) })

  /** Reads `Xft.dpi` from the root window's RESOURCE_MANAGER property to derive the UI scale (unless SCALAUI_SCALE is set). */
  private def loadDpi(): Unit =
    if sys.env.contains("SCALAUI_SCALE") then return
    try
      req(20, 0, b => { put32(b, c.rootWindow); put32(b, 23); put32(b, 31); put32(b, 0); put32(b, 16384) }) // GetProperty RESOURCE_MANAGER (atom 23) type STRING
      val r = reply()
      val len = c.u32(r, 16)
      val text = new String(r, 32, math.min(len, r.length - 32), "ISO-8859-1")
      text.linesIterator.find(_.startsWith("Xft.dpi")).flatMap(l => l.dropWhile(_ != ':').drop(1).trim.toDoubleOption).foreach { dpi =>
        scale = math.max(1.0, math.round(dpi / 96.0 * 4) / 4.0)
        curW = math.round(w * scale).toInt; curH = math.round(h * scale).toInt
        req(12, 0, b => { put32(b, winId); put16(b, 0x0c); put16(b, 0); put32(b, curW); put32(b, curH) }) // ConfigureWindow width|height
      }
    catch case _: Exception => ()

  private def clipboardSet(items: Map[String, Array[Byte]]): Unit =
    ownedItems = items
    req(22, 0, b => { put32(b, winId); put32(b, atomClipboard); put32(b, 0) }) // SetSelectionOwner(owner, selection, CurrentTime)

  /** The atom that serves `mime` (text is exposed as UTF8_STRING). */
  private def targetAtom(mime: String): Int = if mime == "text/plain" then atomUtf8 else intern(mime)

  /** Own data if we hold the selection, otherwise asks the owner and waits (up to ~300 ms) for SelectionNotify.
    * Large transfers use the INCR protocol in both directions. */
  private def clipboardGet(mime: String): Option[Array[Byte]] =
    if ownedItems.nonEmpty then return ownedItems.get(mime)
    selectionResult = None
    req(24, 0, b => { put32(b, winId); put32(b, atomClipboard); put32(b, targetAtom(mime)); put32(b, atomProp); put32(b, 0) }) // ConvertSelection
    val deadline = System.nanoTime() + 300_000_000L
    while selectionResult.isEmpty && System.nanoTime() < deadline do
      c.fill()
      var p = c.pollPacket()
      while p.isDefined do { handlePacket(p.get, null); p = c.pollPacket() }
      if selectionResult.isEmpty then Thread.sleep(5)
    // INCR transfers can take many round trips: keep waiting while chunks arrive
    while incrIn.isDefined && selectionResult.isEmpty && System.nanoTime() < deadline + 5_000_000_000L do
      c.fill()
      var p = c.pollPacket()
      while p.isDefined do { handlePacket(p.get, null); p = c.pollPacket() }
      if selectionResult.isEmpty then Thread.sleep(2)
    incrIn = None
    selectionResult.flatten

  private def handleSelectionRequest(p: Array[Byte]): Unit =
    // SelectionRequest: time@4, owner@8, requestor@12, selection@16, target@20, property@24
    val requestor = c.u32(p, 12); val selection = c.u32(p, 16); val target = c.u32(p, 20); var property = c.u32(p, 24)
    if property == 0 then property = target
    var ok = false
    if selection == atomClipboard && ownedItems.nonEmpty then
      val byAtom = ownedItems.map((mime, data) => targetAtom(mime) -> data)
      if target == atomTargets then
        val atoms = (byAtom.keys.toSeq ++ (if byAtom.contains(atomUtf8) then Seq(atomString) else Nil) :+ atomTargets).distinct
        req(18, 0, b => { put32(b, requestor); put32(b, property); put32(b, 4); b += 32; b += 0; b += 0; b += 0; put32(b, atoms.length); atoms.foreach(put32(b, _)) })
        ok = true
      else
        val data = byAtom.get(if target == atomString then atomUtf8 else target)
        data.foreach { bytes =>
          if bytes.length < c.maxRequestBytes - 64 then
            req(18, 0, b => { put32(b, requestor); put32(b, property); put32(b, target); b += 8; b += 0; b += 0; b += 0; put32(b, bytes.length); b ++= bytes })
          else
            // INCR: announce the size, then hand over chunks as the requestor deletes the property
            req(2, 0, b => { put32(b, requestor); put32(b, 0x800); put32(b, 0x400000) }) // listen for its PropertyNotify
            req(18, 0, b => { put32(b, requestor); put32(b, property); put32(b, atomIncr); b += 32; b += 0; b += 0; b += 0; put32(b, 1); put32(b, bytes.length) })
            incrOut((requestor, property)) = (target, bytes, 0)
          ok = true
        }
    // SendEvent(propagate=0, destination=requestor, mask=0) with a SelectionNotify (code 31)
    req(25, 0, b => {
      put32(b, requestor); put32(b, 0)
      b += 31; b += 0; put16(b, 0); put32(b, c.u32(p, 4)); put32(b, requestor); put32(b, selection); put32(b, target); put32(b, if ok then property else 0)
      for _ <- 0 until 8 do b += 0 })

  private def handleDrop(out: ArrayBuffer[Event]): Unit =
    req(20, 1, b => { put32(b, winId); put32(b, atomDropProp); put32(b, 0); put32(b, 0); put32(b, 1 << 20) }) // GetProperty, delete
    val r = reply()
    val len = c.u32(r, 16)
    val text = new String(r, 32, math.min(len, r.length - 32), "UTF-8")
    val paths = text.linesIterator.map(_.trim).filter(_.startsWith("file://")).map(l => try new java.net.URI(l).getPath catch case _: Exception => l.drop(7)).toSeq
    dragHasUris = false; dragPaths = Nil
    clientMessage(dragSource, 0, winId, atomXdndFinished, winId, 1, atomXdndCopy)
    if paths.nonEmpty then out += Event.FilesDropped(paths, dragPos._1, dragPos._2)

  private def handleSelectionNotify(p: Array[Byte]): Unit =
    val property = c.u32(p, 20)
    if property == 0 then selectionResult = Some(None)
    else
      req(20, 1, b => { put32(b, winId); put32(b, property); put32(b, 0); put32(b, 0); put32(b, 1 << 20) }) // GetProperty, delete=true, any type
      val r = reply()
      val len = c.u32(r, 16) * math.max(1, (r(1) & 0xff) / 8)
      if c.u32(r, 8) == atomIncr then incrIn = Some(scala.collection.mutable.ArrayBuffer.empty[Byte]) // chunks follow as PropertyNotify events
      else selectionResult = Some(Some(java.util.Arrays.copyOfRange(r, 32, 32 + math.min(len, r.length - 32))))

  private def loadKeymap(): Unit =
    val count = c.maxKeycode - c.minKeycode + 1
    req(101, 0, b => { b += c.minKeycode.toByte; b += count.toByte; put16(b, 0) })
    val r = reply()
    val per = r(1) & 0xff
    keymap = Array.tabulate(count)(k => Array.tabulate(per)(j => c.u32(r, 32 + (k * per + j) * 4)))

  def width: Int = math.round(curW / scale).toInt
  def height: Int = math.round(curH / scale).toInt
  def scaleFactor: Double = scale
  def nativeHandle: Ptr[Byte] = winId.toLong.toPtr[Byte]

  def setTitle(title: String): Unit =
    this.title = title
    val bytes = title.getBytes("UTF-8")
    req(18, 0, b => { put32(b, winId); put32(b, 39); put32(b, 31); b += 8; b += 0; b += 0; b += 0; put32(b, bytes.length); b ++= bytes }) // WM_NAME, STRING

  def close(): Unit = if !closed then { if xim != null then xim.close(); closed = true; req(4, 0, b => put32(b, winId)); c.close() } // DestroyWindow

  override def supportsTextInput: Boolean = false

  // --- accessibility (AT-SPI over D-Bus; the bus thread hands actions back through `external`) ---
  private val external = new java.util.concurrent.ConcurrentLinkedQueue[Event]
  private var title = ""
  @volatile private var screenOrigin = (0, 0)
  private var atspi: AtSpi = null
  override def setAccessibility(nodes: Seq[AccessNode]): Unit =
    if closed then return
    if atspi == null then
      if sys.env.get("SCALAUI_ATSPI").contains("0") then return
      atspi = new AtSpi(title.takeWhile(_ != '\u0000').trim.replace(' ', '-'), () => title, () => scale, () => screenOrigin, e => external.add(e))
    screenOrigin = position match { case (x, y) => ((x * scale).toInt, (y * scale).toInt) }
    atspi.update(nodes.toVector)

  override def waitEvents(timeoutMs: Int): Seq[Event] =
    if closed then return Nil
    if pending.isEmpty && !c.hasBuffered && external.isEmpty then c.waitReadable(timeoutMs)
    pollEvents()

  /** Dark if GTK is configured that way (GTK_THEME, then gtk-3.0/settings.ini); X11 itself has no theme notion. */
  override def systemTheme: Theme =
    def dark(s: String) = s.toLowerCase.contains("dark")
    sys.env.get("GTK_THEME").map(t => if dark(t) then Theme.Dark else Theme.Light).getOrElse {
      try
        val f = Paths.get(sys.env.getOrElse("XDG_CONFIG_HOME", sys.env.getOrElse("HOME", "") + "/.config"), "gtk-3.0", "settings.ini")
        val lines = if Files.exists(f) then new String(Files.readAllBytes(f), "UTF-8").linesIterator.map(_.replace(" ", "")).toList else Nil
        if lines.contains("gtk-application-prefer-dark-theme=1") || lines.exists(l => l.startsWith("gtk-theme-name=") && dark(l)) then Theme.Dark else Theme.Light
      catch case _: Exception => Theme.Light
    }

  override def setCursor(icon: CursorIcon): Unit =
    if icon == cursorIcon || closed then return
    cursorIcon = icon
    val id = cursors.getOrElseUpdate(icon, makeCursor(icon))
    req(2, 0, b => { put32(b, winId); put32(b, 0x4000); put32(b, id) }) // ChangeWindowAttributes: cursor

  private def makeCursor(icon: CursorIcon): Int =
    val id = c.allocId()
    if icon == CursorIcon.Hidden then
      val pm = c.allocId()
      req(53, 1, b => { put32(b, pm); put32(b, winId); put16(b, 1); put16(b, 1) }) // CreatePixmap depth 1
      req(93, 0, b => { put32(b, id); put32(b, pm); put32(b, pm); put16(b, 0); put16(b, 0); put16(b, 0); put16(b, 0); put16(b, 0); put16(b, 0); put16(b, 0); put16(b, 0) })
    else
      val glyph = icon match
        case CursorIcon.Pointer => 60; case CursorIcon.Text => 152; case CursorIcon.Crosshair => 34; case CursorIcon.Grab => 58
        case CursorIcon.Grabbing => 52; case CursorIcon.ResizeHorizontal => 108; case CursorIcon.ResizeVertical => 116
        case CursorIcon.NotAllowed => 0; case _ => 68
      val font = c.allocId()
      req(45, 0, b => { put32(b, font); put16(b, 6); put16(b, 0); b ++= "cursor".getBytes("ISO-8859-1") }) // OpenFont "cursor"
      req(94, 0, b => { put32(b, id); put32(b, font); put32(b, font); put16(b, glyph); put16(b, glyph + 1)
        put16(b, 0); put16(b, 0); put16(b, 0); put16(b, 0xffff); put16(b, 0xffff); put16(b, 0xffff) }) // black on white
      req(46, 0, b => put32(b, font)) // CloseFont (the cursor keeps its glyphs)
    id

  private def netWmState(add: Boolean, name: String, second: String = ""): Unit =
    if wmState == 0 then wmState = intern("_NET_WM_STATE")
    clientMessage(c.rootWindow, 0x180000, winId, wmState, if add then 1 else 0, intern(name), if second.isEmpty then 0 else intern(second), 1)

  override def isFullscreen: Boolean = fullscreen
  override def setFullscreen(on: Boolean): Unit = { fullscreen = on; netWmState(on, "_NET_WM_STATE_FULLSCREEN") }
  override def setAlwaysOnTop(onTop: Boolean): Unit = netWmState(onTop, "_NET_WM_STATE_ABOVE")
  override def requestAttention(): Unit = netWmState(true, "_NET_WM_STATE_DEMANDS_ATTENTION")
  override def setMinimized(minimized: Boolean): Unit =
    if minimized then clientMessage(c.rootWindow, 0x180000, winId, intern("WM_CHANGE_STATE"), 3) // IconicState
    else req(8, 0, b => put32(b, winId))
  override def setVisible(visible: Boolean): Unit = if visible then req(8, 0, b => put32(b, winId)) else req(10, 0, b => put32(b, winId))
  override def focus(): Unit =
    clientMessage(c.rootWindow, 0x180000, winId, intern("_NET_ACTIVE_WINDOW"), 1)
    req(42, 1, b => { put32(b, winId); put32(b, 0) }) // SetInputFocus
  override def position: (Int, Int) =
    req(40, 0, b => { put32(b, winId); put32(b, c.rootWindow); put16(b, 0); put16(b, 0) }) // TranslateCoordinates
    val r = reply()
    ((c.u16(r, 12).toShort / scale).toInt, (c.u16(r, 14).toShort / scale).toInt)
  override def setPosition(x: Int, y: Int): Unit =
    req(12, 0, b => { put32(b, winId); put16(b, 0x3); put16(b, 0); put32(b, math.round(x * scale).toInt); put32(b, math.round(y * scale).toInt) })

  /** WM_NORMAL_HINTS size constraints (`max` of 0 leaves the maximum unset). */
  private def sizeHints(minW: Int, minH: Int, maxW: Int, maxH: Int): Unit =
    val flags = 16 | (if maxW > 0 then 32 else 0)
    req(18, 0, b => { put32(b, winId); put32(b, 40); put32(b, 41); b += 32; b += 0; b += 0; b += 0; put32(b, 18) // WM_NORMAL_HINTS, WM_SIZE_HINTS
      val v = Array.fill(18)(0); v(0) = flags; v(5) = minW; v(6) = minH; v(7) = maxW; v(8) = maxH
      v.foreach(put32(b, _)) })
  private var minSize = (0, 0)
  override def setMinSize(w: Int, h: Int): Unit = { minSize = (math.round(w * scale).toInt, math.round(h * scale).toInt); sizeHints(minSize._1, minSize._2, 0, 0) }
  override def setResizable(resizable: Boolean): Unit =
    if resizable then sizeHints(minSize._1, minSize._2, 0, 0) else sizeHints(curW, curH, curW, curH)

  override def setIcon(rgba: Array[Byte], pixelWidth: Int, pixelHeight: Int): Unit =
    require(rgba.length >= pixelWidth * pixelHeight * 4, "pixel buffer too small")
    req(18, 0, b => { put32(b, winId); put32(b, intern("_NET_WM_ICON")); put32(b, 6); b += 32; b += 0; b += 0; b += 0; put32(b, 2 + pixelWidth * pixelHeight)
      put32(b, pixelWidth); put32(b, pixelHeight)
      var i = 0
      while i < pixelWidth * pixelHeight * 4 do
        val a = rgba(i + 3) & 0xff
        def un(v: Byte) = if a == 0 then 0 else math.min(255, (v & 0xff) * 255 / a) // un-premultiply
        put32(b, (a << 24) | (un(rgba(i)) << 16) | (un(rgba(i + 1)) << 8) | un(rgba(i + 2)))
        i += 4 })

  def present(rgba: Array[Byte], pixelWidth: Int, pixelHeight: Int): Unit =
    if closed || pixelWidth <= 0 || pixelHeight <= 0 then return
    lastPixels = rgba; lastW = pixelWidth; lastH = pixelHeight
    blit(0, 0, pixelWidth, pixelHeight)

  override def present(rgba: Array[Byte], pixelWidth: Int, pixelHeight: Int, x: Int, y: Int, w: Int, h: Int): Unit =
    if closed || pixelWidth <= 0 || pixelHeight <= 0 then return
    val sameSize = pixelWidth == lastW && pixelHeight == lastH
    lastPixels = rgba; lastW = pixelWidth; lastH = pixelHeight
    if !sameSize then blit(0, 0, pixelWidth, pixelHeight)
    else
      val x0 = math.max(0, x); val y0 = math.max(0, y)
      val x1 = math.min(pixelWidth.toLong, x.toLong + w).toInt; val y1 = math.min(pixelHeight.toLong, y.toLong + h).toInt
      if x1 > x0 && y1 > y0 then blit(x0, y0, x1, y1) // PutImage of just the dirty sub-rectangle

  /** Sends the pixels of [x0, x1) x [y0, y1) of the cached frame with PutImage. */
  private def blit(x0: Int, y0: Int, x1: Int, y1: Int): Unit =
    val pw = lastW
    val bw = x1 - x0
    val rowBytes = bw * 4
    val rowsPerReq = math.max(1, (c.maxRequestBytes - 32) / rowBytes)
    var y = y0
    while y < y1 do
      val rows = math.min(rowsPerReq, y1 - y)
      val data = new Array[Byte](rows * rowBytes)
      var r = 0
      while r < rows do
        var i = 0
        val src = ((y + r) * pw + x0) * 4
        val dst = r * rowBytes
        while i < rowBytes do
          data(dst + i) = lastPixels(src + i + 2); data(dst + i + 1) = lastPixels(src + i + 1); data(dst + i + 2) = lastPixels(src + i); data(dst + i + 3) = 0 // BGRX
          i += 4
        r += 1
      req(72, 2, b => { // PutImage, ZPixmap
        put32(b, winId); put32(b, gc); put16(b, bw); put16(b, rows); put16(b, x0); put16(b, y)
        b += 0; b += 24; put16(b, 0); b ++= data })
      y += rows

  private def keysymToKey(ks: Int): Key = ks match
    case 0xff51 => Key.Left; case 0xff53 => Key.Right; case 0xff52 => Key.Up; case 0xff54 => Key.Down
    case 0xff50 => Key.Home; case 0xff57 => Key.End; case 0xff55 => Key.PageUp; case 0xff56 => Key.PageDown
    case 0xff08 => Key.Backspace; case 0xffff => Key.Delete; case 0xff0d | 0xff8d => Key.Enter
    case 0xff09 => Key.Tab; case 0xff1b => Key.Escape; case 0x20 => Key.Space
    case other => Key.Other(other)

  private def keysymText(ks: Int): String =
    if ks >= 0x20 && ks <= 0x7e || ks >= 0xa0 && ks <= 0xff then new String(Character.toChars(ks))
    else if ks >= 0x01000100 && ks <= 0x0110ffff then new String(Character.toChars(ks - 0x01000000))
    else ""

  private def mods(state: Int): Modifiers = Modifiers(shift = (state & 1) != 0, control = (state & 4) != 0, alt = (state & 8) != 0, command = (state & 0x40) != 0)

  def pollEvents(): Seq[Event] =
    if closed then return Nil
    c.fill()
    if xim != null then xim.tick()
    val out = ArrayBuffer.empty[Event]
    val packets = pending.toVector; pending.clear()
    (packets ++ Iterator.continually(c.pollPacket()).takeWhile(_.isDefined).map(_.get)).foreach(p => handlePacket(p, out))
    val now = System.nanoTime()
    if now - lastThemeCheck > 2_000_000_000L then // no change notification exists for GTK settings files, so poll gently
      lastThemeCheck = now
      val t = systemTheme
      if t != lastTheme then { lastTheme = t; out += Event.ThemeChanged(t) }
    var ev = external.poll()
    while ev != null do { out += ev; ev = external.poll() }
    out.toSeq

  /** The keysym of a key event with Shift/Lock applied (used to match XIM trigger keys). */
  private def eventKeysym(p: Array[Byte]): Int =
    val state = c.u16(p, 28)
    val syms = keymap.lift((p(1) & 0xff) - c.minKeycode).getOrElse(Array.empty[Int])
    val base = syms.headOption.getOrElse(0)
    if ((state & 1) != 0 ^ (state & 2) != 0) && syms.length > 1 && syms(1) != 0 then syms(1) else base

  /** Plain keyboard handling of a key event (no input method, or the input method handed the key back). */
  private def keyEvent(p: Array[Byte], out: ArrayBuffer[Event]): Unit =
    val code = p(0) & 0x7f
    val kc = p(1) & 0xff; val state = c.u16(p, 28); val m = mods(state)
    val syms = keymap.lift(kc - c.minKeycode).getOrElse(Array.empty[Int])
    val base = syms.headOption.getOrElse(0)
    val ks = eventKeysym(p)
    if code == 2 then
      val chars = keysymText(base).toLowerCase
      val text = if m.control || m.command then "" else keysymText(ks)
      out += Event.KeyDown(keysymToKey(base), text, chars, m, false)
    else out += Event.KeyUp(keysymToKey(base), m)

  // --- input method (XIM) ---
  private var xim: Xim = null

  /** Routes XIM transport traffic (client messages, LOCALES/TRANSPORT selection replies); false for every other packet. */
  private def ximRoute(p: Array[Byte]): Boolean =
    if xim == null then false
    else (p(0) & 0x7f) match
      case 33 if xim.isXimMessage(c.u32(p, 8)) => xim.clientMessage(c.u32(p, 8), p(1) & 0xff, java.util.Arrays.copyOfRange(p, 12, 32)); true
      case 31 => xim.selectionNotify(c.u32(p, 12))
      case _ => false

  private def ximPump(until: () => Boolean, timeoutMs: Int): Boolean =
    val deadline = System.nanoTime() + timeoutMs * 1_000_000L
    while !until() && System.nanoTime() < deadline do
      c.fill()
      val batch = pending.toVector; pending.clear()
      val fresh = Iterator.continually(c.pollPacket()).takeWhile(_.isDefined).map(_.get).toVector
      (batch ++ fresh).foreach(p => if !ximRoute(p) then pending += p)
      if !until() then c.waitReadable(10)
    until()

  private val ximIo = new XimIo:
    def intern(name: String): Int = X11Window.this.intern(name)
    def atomName(atom: Int): String =
      req(17, 0, b => put32(b, atom))
      val r = reply()
      new String(r, 32, math.min(c.u16(r, 8), r.length - 32), "ISO-8859-1")
    def selectionOwner(selection: Int): Int = { req(23, 0, b => put32(b, selection)); c.u32(reply(), 8) }
    def convertSelection(selection: Int, target: Int, property: Int): Unit =
      req(24, 0, b => { put32(b, winId); put32(b, selection); put32(b, target); put32(b, property); put32(b, 0) })
    def readProperty(window: Int, property: Int, delete: Boolean): Array[Byte] =
      req(20, if delete then 1 else 0, b => { put32(b, window); put32(b, property); put32(b, 0); put32(b, 0); put32(b, 1 << 20) })
      val r = reply()
      val len = c.u32(r, 16) * math.max(1, (r(1) & 0xff) / 8)
      java.util.Arrays.copyOfRange(r, 32, 32 + math.min(len, r.length - 32))
    def readRootAtoms(property: Int): Seq[Int] =
      req(20, 0, b => { put32(b, c.rootWindow); put32(b, property); put32(b, 0); put32(b, 0); put32(b, 4096) })
      val r = reply()
      if (r(1) & 0xff) != 32 then Nil else (0 until c.u32(r, 16)).map(i => c.u32(r, 32 + 4 * i))
    def clientMessage(dest: Int, typ: Int, format: Int, data: Array[Byte]): Unit =
      req(25, 0, b => {
        put32(b, dest); put32(b, 0)
        b += 33; b += format.toByte; put16(b, 0); put32(b, dest); put32(b, typ)
        b ++= java.util.Arrays.copyOf(data, 20) })
    def appendProperty(window: Int, property: Int, data: Array[Byte]): Unit =
      // large messages are chunked below the request size limit; Append keeps them contiguous
      var off = 0
      while off < data.length || off == 0 do
        val n = math.min(c.maxRequestBytes - 64, data.length - off)
        req(18, 2, b => { put32(b, window); put32(b, property); put32(b, 31); b += 8; b += 0; b += 0; b += 0; put32(b, n); b ++= java.util.Arrays.copyOfRange(data, off, off + n) })
        off += math.max(n, 1)
    def pump(until: () => Boolean, timeoutMs: Int): Boolean = ximPump(until, timeoutMs)

  private def initXim(): Unit =
    if sys.env.get("SCALAUI_XIM").contains("0") then return
    try
      val x = new Xim(ximIo, winId, e => external.add(e),
        ev => { val tmp = ArrayBuffer.empty[Event]; keyEvent(ev, tmp); tmp.foreach(external.add) }, eventKeysym)
      xim = x
      val ok = x.connect()
      if sys.env.contains("SCALAUI_DEBUG") then System.err.println("xim: connect() = " + ok + " active=" + x.active)
      if !ok then xim = null
    catch case e: RuntimeException => { if sys.env.contains("SCALAUI_DEBUG") then System.err.println("XIM unavailable: " + e); xim = null }

  /** True when an X input method server is connected and routing key events (see [[Event.Composition]]). */
  def inputMethodActive: Boolean = xim != null && xim.active

  override def setImeRect(x: Double, y: Double, w: Double, h: Double): Unit =
    if xim != null then xim.setSpot(math.round(x * scale).toInt, math.round((y + h) * scale).toInt)

  private def handlePacket(p: Array[Byte], outOrNull: ArrayBuffer[Event]): Unit =
    if ximRoute(p) then return
    val out = if outOrNull == null then ArrayBuffer.empty[Event] else outOrNull
    locally {
      val code = p(0) & 0x7f
      def ex = c.u16(p, 24).toShort / scale; def ey = c.u16(p, 26).toShort / scale
      code match
        case 2 | 3 => // KeyPress / KeyRelease
          if !(xim != null && xim.active && xim.filterKey(p)) then keyEvent(p, out)
        case 4 => // ButtonPress
          p(1) & 0xff match
            case 1 => out += Event.MouseDown(MouseButton.Left, ex, ey, mods(c.u16(p, 28)))
            case 3 => out += Event.MouseDown(MouseButton.Right, ex, ey, mods(c.u16(p, 28)))
            case 2 => out += Event.MouseDown(MouseButton.Other, ex, ey, mods(c.u16(p, 28)))
            case 4 => out += Event.Scroll(0, 10)
            case 5 => out += Event.Scroll(0, -10)
            case 6 => out += Event.Scroll(10, 0)
            case 7 => out += Event.Scroll(-10, 0)
            case _ => ()
        case 5 => // ButtonRelease
          p(1) & 0xff match
            case 1 => out += Event.MouseUp(MouseButton.Left, ex, ey)
            case 3 => out += Event.MouseUp(MouseButton.Right, ex, ey)
            case _ => ()
        case 6 => out += Event.MouseMoved(ex, ey)
        case 12 => if lastPixels != null && c.u16(p, 16) == 0 then blit(0, 0, lastW, lastH) // Expose (last of a series has count 0): repaint from the cached frame
        case 22 => // ConfigureNotify
          val nw = c.u16(p, 20); val nh = c.u16(p, 22)
          if nw != curW || nh != curH then { curW = nw; curH = nh; out += Event.Resized(width, height) }
        case 9 => if xim != null then xim.setFocus(true); out += Event.Focused(true)    // FocusIn
        case 10 => if xim != null then xim.setFocus(false); out += Event.Focused(false) // FocusOut
        case 33 => // ClientMessage
          val typ = c.u32(p, 8)
          if typ == wmProtocols && c.u32(p, 12) == wmDelete then out += Event.CloseRequested
          else if typ == atomXdndEnter then
            dragSource = c.u32(p, 12)
            val types = Seq(c.u32(p, 20), c.u32(p, 24), c.u32(p, 28))
            dragHasUris = types.contains(atomUriList) || (c.u32(p, 16) & 1) != 0 // bit 0: more than three types; assume URIs are among them
          else if typ == atomXdndPosition then
            val packed = c.u32(p, 20)
            val rx = (packed >>> 16) & 0xffff; val ry = packed & 0xffff
            req(40, 0, b => { put32(b, c.rootWindow); put32(b, winId); put16(b, rx); put16(b, ry) }) // TranslateCoordinates root -> window
            val r = reply()
            dragPos = (c.u16(r, 12).toShort / scale, c.u16(r, 14).toShort / scale)
            clientMessage(dragSource, 0, winId, atomXdndStatus, winId, if dragHasUris then 1 else 0, 0, 0, if dragHasUris then atomXdndCopy else 0)
            if dragHasUris then out += Event.FilesHovered(dragPaths, dragPos._1, dragPos._2)
          else if typ == atomXdndLeave then { dragHasUris = false; out += Event.FilesHoverCancelled }
          else if typ == atomXdndDrop then
            if dragHasUris then
              req(24, 0, b => { put32(b, winId); put32(b, atomXdndSelection); put32(b, atomUriList); put32(b, atomDropProp); put32(b, c.u32(p, 20)) }) // ConvertSelection
            else clientMessage(dragSource, 0, winId, atomXdndFinished, winId, 0, 0)
        case 17 => closed = true; out += Event.CloseRequested // DestroyNotify
        case 28 => // PropertyNotify: window@4 atom@8 state@16 (0 new value, 1 deleted)
          val atom = c.u32(p, 8); val state = p(16) & 0xff
          if state == 0 && atom == atomProp && incrIn.isDefined then
            req(20, 1, b => { put32(b, winId); put32(b, atomProp); put32(b, 0); put32(b, 0); put32(b, 1 << 20) })
            val r = reply()
            val len = c.u32(r, 16) * math.max(1, (r(1) & 0xff) / 8)
            if len == 0 then { selectionResult = Some(Some(incrIn.get.toArray)); incrIn = None }
            else incrIn.get ++= java.util.Arrays.copyOfRange(r, 32, 32 + math.min(len, r.length - 32))
          else if state == 1 then
            val w = c.u32(p, 4)
            incrOut.get((w, atom)).foreach { (target, bytes, off) =>
              val chunk = math.min(c.maxRequestBytes - 64, bytes.length - off)
              req(18, 0, b => { put32(b, w); put32(b, atom); put32(b, target); b += 8; b += 0; b += 0; b += 0; put32(b, chunk); b ++= java.util.Arrays.copyOfRange(bytes, off, off + chunk) })
              if chunk == 0 then incrOut.remove((w, atom)) else incrOut((w, atom)) = (target, bytes, off + chunk)
            }
        case 29 => ownedItems = Map.empty // SelectionClear: another client took the clipboard
        case 30 => handleSelectionRequest(p) // SelectionRequest
        case 31 => if atomDropProp != 0 && c.u32(p, 20) == atomDropProp then handleDrop(out) else handleSelectionNotify(p) // SelectionNotify
        case _ => ()
    }
    if outOrNull == null then pending ++= Nil // events seen while waiting for a selection are dropped except input handled above
