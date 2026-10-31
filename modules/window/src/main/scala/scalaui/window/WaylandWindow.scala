package scalaui.window

import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

/** Linux backend speaking the Wayland protocol directly (no libwayland, no libxkbcommon): xdg-shell toplevel, wl_shm
  * buffers, pointer/keyboard/seat, clipboard and drag-and-drop through wl_data_device, and, when the compositor offers
  * them, server-side decorations, fractional scaling (wp_viewporter), compositor-drawn cursors (cursor-shape-v1) and
  * input methods (text-input-v3). Keys are interpreted with the XKB keymap the compositor sends (see [[XkbKeymap]]).
  */
final class WaylandWindow private (c: WlConn, requestedW: Int, requestedH: Int, initialTitle: String) extends Window:
  private val queue = ArrayBuffer.empty[Event]
  private val external = new java.util.concurrent.ConcurrentLinkedQueue[Event]
  private var closed = false
  private var title = initialTitle

  // ---- globals
  private var registry = 0; private var compositor = 0; private var shm = 0; private var wmBase = 0; private var seat = 0
  private var dataMgr = 0; private var decoMgr = 0; private var viewporter = 0; private var fracMgr = 0; private var cursorMgr = 0; private var textMgr = 0
  // ---- window objects
  private var surface = 0; private var xdgSurface = 0; private var toplevel = 0; private var viewport = 0
  private var curW = requestedW; private var curH = requestedH
  private var pendingW = 0; private var pendingH = 0; private var pendingStates = Set.empty[Int]
  private var configured = false
  private var fullscreen = false
  private var activated = false
  private var intScale = 1; private var fracScale = 0.0
  private val outputScale = mutable.HashMap.empty[Int, Int]
  private val enteredOutputs = mutable.HashSet.empty[Int]
  private var preferredBufferScale = 0

  // ---- input
  private var pointer = 0; private var keyboard = 0; private var textInput = 0; private var cursorDevice = 0
  private var pointerSerial = 0; private var inputSerial = 0
  private var mouseX = 0.0; private var mouseY = 0.0
  private var keymap: XkbKeymap = null
  private var modMask = 0
  private var repeatRate = 25; private var repeatDelay = 600
  private var held: Option[(Int, Long)] = None // xkb keycode, next repeat time (ns)
  private var cursorShape = 1
  private var preeditText = ""; private var preeditBegin = 0; private var preeditEnd = 0; private var pendingCommit = ""

  // ---- clipboard / dnd
  private var dataDevice = 0
  private val offerMimes = mutable.HashMap.empty[Int, ArrayBuffer[String]]
  private var selectionOffer = 0
  private var dndOffer = 0; private var dndAccepted = false
  private var ownedItems: Map[String, Array[Byte]] = Map.empty
  private var ownedSource = 0

  private def dbg(m: String): Unit = if sys.env.contains("SCALAUI_DEBUG") then System.err.println("wl: " + m)

  private def proxy(f: (Int, WlReader) => Unit): Int = c.newObject(f)

  // ---------------------------------------------------------------- setup
  private def bind(name: Int, iface: String, version: Int, h: (Int, WlReader) => Unit): Int =
    val id = proxy(h)
    c.send(registry, 0, new WlArgs().uint(name).str(iface).uint(version).obj(id))
    id

  private def roundtrip(): Unit =
    var done = false
    val cb = proxy((_, _) => done = true)
    c.send(1, 0, new WlArgs().obj(cb)) // wl_display.sync
    val deadline = System.nanoTime() + 3_000_000_000L
    while !done && System.nanoTime() < deadline do { c.fill(50); c.dispatch() }
    if !done then throw new RuntimeException("Wayland compositor did not answer")

  private def init(): Unit =
    c.setHandler(1, (op, r) => op match // wl_display
      case 0 => val id = r.uint(); val code = r.uint(); throw new RuntimeException(s"Wayland protocol error $code on object $id: ${r.str()}")
      case _ => ())
    registry = proxy { (op, r) =>
      if op == 0 then
        val name = r.uint(); val iface = r.str(); val version = r.uint()
        iface match
          case "wl_compositor" => compositor = bind(name, iface, math.min(version, 6), surfaceEvents)
          case "wl_shm" => shm = bind(name, iface, 1, (_, _) => ())
          case "xdg_wm_base" => wmBase = bind(name, iface, math.min(version, 2), (o, rr) => if o == 0 then c.send(wmBase, 3, new WlArgs().uint(rr.uint()))) // ping -> pong
          case "wl_seat" => seat = bind(name, iface, math.min(version, 5), seatEvents)
          case "wl_data_device_manager" => dataMgr = bind(name, iface, math.min(version, 3), (_, _) => ())
          case "zxdg_decoration_manager_v1" => decoMgr = bind(name, iface, 1, (_, _) => ())
          case "wp_viewporter" => viewporter = bind(name, iface, 1, (_, _) => ())
          case "wp_fractional_scale_manager_v1" => fracMgr = bind(name, iface, 1, (_, _) => ())
          case "wp_cursor_shape_manager_v1" => cursorMgr = bind(name, iface, 1, (_, _) => ())
          case "zwp_text_input_manager_v3" => textMgr = bind(name, iface, 1, (_, _) => ())
          case "wl_output" =>
            val id = bind(name, iface, math.min(version, 2), null)
            c.setHandler(id, (o, rr) => if o == 3 then { outputScale(id) = rr.int(); updateScale() })
          case _ => ()
    }
    c.send(1, 1, new WlArgs().obj(registry)) // wl_display.get_registry
    roundtrip()
    if compositor == 0 || shm == 0 || wmBase == 0 then throw new RuntimeException("compositor lacks wl_compositor/wl_shm/xdg_wm_base")
    roundtrip() // seat capabilities, outputs

    surface = proxy(surfaceEvents)
    c.send(compositor, 0, new WlArgs().obj(surface))
    xdgSurface = proxy { (op, r) => if op == 0 then onConfigure(r.uint()) }
    c.send(wmBase, 2, new WlArgs().obj(xdgSurface).obj(surface))
    toplevel = proxy { (op, r) =>
      op match
        case 0 =>
          pendingW = r.int(); pendingH = r.int()
          val states = r.array()
          pendingStates = (0 until states.length / 4).map(i => (states(4 * i) & 0xff) | ((states(4 * i + 1) & 0xff) << 8)).toSet
        case 1 => queue += Event.CloseRequested
        case _ => ()
    }
    c.send(xdgSurface, 1, new WlArgs().obj(toplevel))
    c.send(toplevel, 2, new WlArgs().str(title))
    c.send(toplevel, 3, new WlArgs().str(title.toLowerCase.replace(' ', '-')))
    if decoMgr != 0 then
      val deco = proxy((_, _) => ())
      c.send(decoMgr, 1, new WlArgs().obj(deco).obj(toplevel))
      c.send(deco, 1, new WlArgs().uint(2)) // prefer server-side decorations
    if viewporter != 0 then
      viewport = proxy((_, _) => ())
      c.send(viewporter, 1, new WlArgs().obj(viewport).obj(surface))
    if fracMgr != 0 then
      val f = proxy { (op, r) => if op == 0 then { fracScale = r.uint() / 120.0; queue += Event.ScaleChanged(scaleFactor) } }
      c.send(fracMgr, 1, new WlArgs().obj(f).obj(surface))
    if dataMgr != 0 && seat != 0 then
      dataDevice = proxy(dataDeviceEvents)
      c.send(dataMgr, 1, new WlArgs().obj(dataDevice).obj(seat))
      PlatformClipboard.backend = Some(new PlatformClipboard.Backend:
        def get(mime: String): Option[Array[Byte]] = clipboardGet(mime)
        def set(items: Map[String, Array[Byte]]): Unit = clipboardSet(items))
    if textMgr != 0 && seat != 0 then
      textInput = proxy(textInputEvents)
      c.send(textMgr, 1, new WlArgs().obj(textInput).obj(seat))
    c.send(surface, 6) // initial commit without a buffer asks for the first configure
    val deadline = System.nanoTime() + 3_000_000_000L
    while !configured && System.nanoTime() < deadline do { c.fill(50); c.dispatch() }
    if !configured then throw new RuntimeException("Wayland compositor sent no configure")
    queue.clear()

  // ---------------------------------------------------------------- surface / scale
  private def surfaceEvents(op: Int, r: WlReader): Unit = op match
    case 0 => enteredOutputs += r.uint(); updateScale()
    case 1 => enteredOutputs -= r.uint(); updateScale()
    case 2 => preferredBufferScale = r.int(); updateScale()
    case _ => ()

  private def updateScale(): Unit =
    val before = scaleFactor
    intScale = if preferredBufferScale > 0 then preferredBufferScale else enteredOutputs.flatMap(outputScale.get).maxOption.getOrElse(outputScale.values.maxOption.getOrElse(1))
    if scaleFactor != before then queue += Event.ScaleChanged(scaleFactor)

  def scaleFactor: Double =
    sys.env.get("SCALAUI_SCALE").flatMap(_.toDoubleOption).getOrElse(if fracScale > 0 then fracScale else math.max(1, intScale).toDouble)

  private def onConfigure(serial: Int): Unit =
    c.send(xdgSurface, 4, new WlArgs().uint(serial)) // ack_configure
    val newW = if pendingW > 0 then pendingW else curW
    val newH = if pendingH > 0 then pendingH else curH
    val isActive = pendingStates.contains(4)
    fullscreen = pendingStates.contains(2)
    if configured && (newW != curW || newH != curH) then { curW = newW; curH = newH; queue += Event.Resized(curW, curH) }
    else { curW = newW; curH = newH }
    if isActive != activated || !configured then { activated = isActive; if configured then queue += Event.Focused(isActive) }
    configured = true

  def width: Int = curW
  def height: Int = curH
  def nativeHandle: Ptr[Byte] = surface.toLong.toPtr[Byte]

  // ---------------------------------------------------------------- seat
  private def seatEvents(op: Int, r: WlReader): Unit =
    if op == 0 then
      val caps = r.uint()
      dbg(s"seat capabilities=$caps")
      if (caps & 1) != 0 && pointer == 0 then
        pointer = proxy(pointerEvents)
        c.send(seat, 0, new WlArgs().obj(pointer))
        if cursorMgr != 0 then
          cursorDevice = proxy((_, _) => ())
          c.send(cursorMgr, 1, new WlArgs().obj(cursorDevice).obj(pointer))
      if (caps & 2) != 0 && keyboard == 0 then
        keyboard = proxy(keyboardEvents)
        c.send(seat, 1, new WlArgs().obj(keyboard))
      // devices can come and go (hot-plug, virtual keyboards): drop the dead objects so a returning device is picked up again
      if (caps & 2) == 0 && keyboard != 0 then { c.send(keyboard, 0); c.forget(keyboard); keyboard = 0; held = None }
      if (caps & 1) == 0 && pointer != 0 then
        c.send(pointer, 1); c.forget(pointer); pointer = 0
        if cursorDevice != 0 then { c.send(cursorDevice, 0); c.forget(cursorDevice); cursorDevice = 0 }

  private def pointerEvents(op: Int, r: WlReader): Unit = op match
    case 0 => // enter
      pointerSerial = r.uint(); inputSerial = pointerSerial; r.uint(); dbg("pointer enter"); mouseX = r.fixed(); mouseY = r.fixed()
      applyCursor(); queue += Event.MouseMoved(mouseX, mouseY)
    case 2 => r.uint(); mouseX = r.fixed(); mouseY = r.fixed(); queue += Event.MouseMoved(mouseX, mouseY)
    case 3 => // button
      inputSerial = r.uint(); r.uint(); val button = r.uint(); val state = r.uint()
      val b = button match { case 0x110 => MouseButton.Left; case 0x111 => MouseButton.Right; case _ => MouseButton.Other }
      if state == 1 then queue += Event.MouseDown(b, mouseX, mouseY, modifiers) else queue += Event.MouseUp(b, mouseX, mouseY)
    case 4 => // axis: vertical 0, horizontal 1; wheel notches are about 10 units
      r.uint(); val axis = r.uint(); val v = r.fixed()
      if axis == 0 then queue += Event.Scroll(0, -v) else queue += Event.Scroll(-v, 0)
    case _ => ()

  private def modifiers: Modifiers = Modifiers(shift = (modMask & 1) != 0, control = (modMask & 4) != 0, alt = (modMask & 8) != 0, command = (modMask & 64) != 0)

  private def keyboardEvents(op: Int, r: WlReader): Unit = op match
    case 0 => // keymap
      r.uint(); val kfd = c.takeFd(); val size = r.uint()
      if kfd >= 0 then
        val mem = WlLibc.mmap(null, size.toLong.toCSize, 1, 2, kfd, 0L) // PROT_READ, MAP_PRIVATE
        if mem.toLong != -1L then
          val bytes = new Array[Byte](math.max(0, size - 1))
          var i = 0; while i < bytes.length do { bytes(i) = mem(i); i += 1 }
          WlLibc.munmap(mem, size.toLong.toCSize)
          keymap = new XkbKeymap(new String(bytes, "UTF-8"))
        WlLibc.close(kfd)
    case 1 => inputSerial = r.uint(); dbg(s"keyboard enter serial=$inputSerial") // enter
    case 2 => r.uint(); held = None  // leave
    case 3 => // key
      inputSerial = r.uint(); r.uint(); val code = r.uint() + 8; val state = r.uint()
      onKey(code, state == 1)
    case 4 => // modifiers
      r.uint(); val dep = r.uint(); val lat = r.uint(); val lock = r.uint()
      modMask = dep | lat | lock
    case 5 => repeatRate = r.int(); repeatDelay = r.int()
    case _ => ()

  private def onKey(code: Int, down: Boolean): Unit =
    if keymap == null then return
    val base = keymap.base(code)
    val key = Keysyms.key(base)
    val m = modifiers
    if down then
      queue += Event.KeyDown(key, "", Keysyms.text(base).toLowerCase, m, false)
      emitText(code, m)
      val repeats = repeatRate > 0 && (Keysyms.text(base).nonEmpty || Set[Key](Key.Left, Key.Right, Key.Up, Key.Down, Key.Backspace, Key.Delete, Key.PageUp, Key.PageDown, Key.Enter, Key.Tab).contains(key))
      held = if repeats then Some((code, System.nanoTime() + repeatDelay * 1_000_000L)) else None
    else
      queue += Event.KeyUp(key, m)
      if held.exists(_._1 == code) then held = None

  private def emitText(code: Int, m: Modifiers): Unit =
    if !m.control && !m.command then
      val t = Keysyms.text(keymap.keysym(code, modMask))
      if t.nonEmpty then queue += Event.TextInput(t)

  private def repeatKeys(): Unit =
    held.foreach { (code, at) =>
      val now = System.nanoTime()
      if now >= at && keymap != null then
        val m = modifiers
        queue += Event.KeyDown(Keysyms.key(keymap.base(code)), "", Keysyms.text(keymap.base(code)).toLowerCase, m, true)
        emitText(code, m)
        held = Some((code, now + 1_000_000_000L / math.max(1, repeatRate)))
    }

  // ---------------------------------------------------------------- text input (IME)
  private def textInputEvents(op: Int, r: WlReader): Unit = op match
    case 0 => // enter: start receiving composition
      c.send(textInput, 1); c.send(textInput, 5, new WlArgs().uint(0).uint(0)); c.send(textInput, 7)
    case 1 => c.send(textInput, 2); c.send(textInput, 7)
    case 2 => preeditText = r.str(); preeditBegin = r.int(); preeditEnd = r.int()
    case 3 => pendingCommit = r.str()
    case 5 => // done
      if pendingCommit.nonEmpty then
        queue += Event.Composition("", 0, 0); queue += Event.TextInput(pendingCommit); pendingCommit = ""
      if preeditText.nonEmpty then
        def utf16(byteOffset: Int): Int = new String(preeditText.getBytes("UTF-8").take(math.max(0, byteOffset)), "UTF-8").length
        queue += Event.Composition(preeditText, utf16(preeditBegin), utf16(preeditEnd) - utf16(preeditBegin))
      else queue += Event.Composition("", 0, 0)
    case _ => ()

  override def supportsTextInput: Boolean = true
  override def setImeRect(x: Double, y: Double, w: Double, h: Double): Unit =
    if textInput != 0 then
      c.send(textInput, 6, new WlArgs().int(x.toInt).int(y.toInt).int(math.max(1, w.toInt)).int(math.max(1, h.toInt)))
      c.send(textInput, 7)

  // ---------------------------------------------------------------- cursor
  private def applyCursor(): Unit =
    if cursorShape == 0 then { if pointer != 0 && pointerSerial != 0 then c.send(pointer, 0, new WlArgs().uint(pointerSerial).obj(0).int(0).int(0)) } // set_cursor(null): hidden
    else if cursorDevice != 0 && pointerSerial != 0 then c.send(cursorDevice, 1, new WlArgs().uint(pointerSerial).uint(cursorShape))

  override def setCursor(icon: CursorIcon): Unit =
    val shape = icon match
      case CursorIcon.Pointer => 4; case CursorIcon.Text => 9; case CursorIcon.Crosshair => 8; case CursorIcon.Grab => 16
      case CursorIcon.Grabbing => 17; case CursorIcon.ResizeHorizontal => 26; case CursorIcon.ResizeVertical => 27
      case CursorIcon.NotAllowed => 15; case CursorIcon.Hidden => 0; case _ => 1
    if shape != cursorShape then { cursorShape = shape; applyCursor() }

  // ---------------------------------------------------------------- clipboard and drag-and-drop
  private def dataDeviceEvents(op: Int, r: WlReader): Unit = op match
    case 0 => // data_offer(new_id): collect its mime types
      val id = r.uint()
      val mimes = ArrayBuffer.empty[String]
      offerMimes(id) = mimes
      c.setHandler(id, (o, rr) => if o == 0 then mimes += rr.str())
    case 1 => // enter
      val serial = r.uint(); r.uint(); val x = r.fixed(); val y = r.fixed(); val offer = r.uint()
      dndOffer = offer
      if offer != 0 then
        // only file lists are understood: other payloads (text selections dragged from other apps) are declined so the source shows "not allowed"
        dndAccepted = offerMimes.get(offer).exists(_.contains("text/uri-list"))
        if dndAccepted then
          c.send(offer, 0, new WlArgs().uint(serial).str("text/uri-list")) // accept
          c.send(offer, 4, new WlArgs().uint(1).uint(1)) // set_actions(copy, preferred copy)
          queue += Event.FilesHovered(Nil, x, y)
        else c.send(offer, 0, new WlArgs().uint(serial).uint(0)) // accept(null)
        mouseX = x; mouseY = y
    case 2 => if dndOffer != 0 then { c.send(dndOffer, 2); c.forget(dndOffer); offerMimes.remove(dndOffer); dndOffer = 0 }; if dndAccepted then queue += Event.FilesHoverCancelled; dndAccepted = false
    case 3 => r.uint(); mouseX = r.fixed(); mouseY = r.fixed(); if dndAccepted then queue += Event.FilesHovered(Nil, mouseX, mouseY)
    case 4 => // drop
      if dndOffer != 0 && dndAccepted then
        val offer = dndOffer; dndOffer = 0; dndAccepted = false
        val data = receive(offer, "text/uri-list")
        c.send(offer, 3) // finish
        c.send(offer, 2) // destroy
        c.forget(offer)
        offerMimes.remove(offer)
        val paths = data.map(d => new String(d, "UTF-8").linesIterator.map(_.trim).filter(_.startsWith("file://"))
          .map(l => try new java.net.URI(l).getPath catch case _: Exception => l.drop(7)).toSeq).getOrElse(Nil)
        if paths.nonEmpty then queue += Event.FilesDropped(paths, mouseX, mouseY) else queue += Event.FilesHoverCancelled
    case 5 => // selection
      val offer = r.uint()
      dbg(s"selection offer=$offer mimes=${offerMimes.get(offer)}")
      if selectionOffer != 0 && selectionOffer != offer then { c.send(selectionOffer, 2); offerMimes.remove(selectionOffer) }
      selectionOffer = offer
    case _ => ()

  /** Asks the source behind `offer` for `mime` through a pipe and reads until EOF (up to a few seconds). */
  private def receive(offer: Int, mime: String): Option[Array[Byte]] = Zone {
    val fds = alloc[CInt](2)
    if WlLibc.pipe(fds) != 0 then None
    else
      val readFd = fds(0); val writeFd = fds(1)
      c.send(offer, 1, new WlArgs().str(mime), attachFd = writeFd)
      WlLibc.close(writeFd)
      val out = ArrayBuffer.empty[Byte]
      val buf = alloc[Byte](65536)
      val pfd = alloc[Byte](8)
      val deadline = System.nanoTime() + 3_000_000_000L
      var done = false
      while !done && System.nanoTime() < deadline do
        pfd.asInstanceOf[Ptr[CInt]](0) = readFd; (pfd + 4).asInstanceOf[Ptr[CShort]](0) = 1.toShort; (pfd + 6).asInstanceOf[Ptr[CShort]](0) = 0.toShort
        if WlLibc.poll(pfd, 1.toCSize, 100) > 0 then
          val n = WlLibc.read(readFd, buf, 65536.toCSize).toInt
          if n <= 0 then done = true else { var i = 0; while i < n do { out += buf(i); i += 1 } }
        // the compositor needs us to keep serving the connection while a client-owned selection is being read
        c.fill(0); c.dispatch()
      WlLibc.close(readFd)
      if done then Some(out.toArray) else None
  }

  private def mimeCandidates(mime: String): Seq[String] =
    if mime == "text/plain" then Seq("text/plain;charset=utf-8", "text/plain", "UTF8_STRING", "STRING") else Seq(mime)

  private def clipboardGet(mime: String): Option[Array[Byte]] =
    if ownedItems.nonEmpty then return ownedItems.get(mime)
    if selectionOffer == 0 then return None
    c.fill(0); c.dispatch()
    val offered = offerMimes.getOrElse(selectionOffer, ArrayBuffer.empty)
    mimeCandidates(mime).find(offered.contains).flatMap(receive(selectionOffer, _))

  private def clipboardSet(items: Map[String, Array[Byte]]): Unit =
    if dataMgr == 0 then return
    if ownedSource != 0 then { c.send(ownedSource, 1); c.forget(ownedSource); ownedSource = 0 }
    dbg(s"set_selection serial=$inputSerial mimes=${items.keys}")
    ownedItems = items
    val src = proxy(null)
    ownedSource = src
    c.setHandler(src, (op, r) => op match
      case 1 => // send(mime, fd)
        val mime = r.str(); val wfd = c.takeFd()
        val wanted = if mime.startsWith("text/plain") || mime == "UTF8_STRING" || mime == "STRING" then "text/plain" else mime
        val data = ownedItems.getOrElse(wanted, Array.emptyByteArray)
        if wfd >= 0 then
          val t = new Thread(() => { // pipes are small: write from a thread so the event loop never blocks on a slow reader
            var off = 0
            while off < data.length do
              val n = WlLibc.write(wfd, data.atUnsafe(off), (data.length - off).toCSize).toInt
              if n <= 0 then off = data.length else off += n
            WlLibc.close(wfd)
            ()
          })
          t.setDaemon(true); t.start()
      case 2 => if ownedSource == src then { ownedItems = Map.empty; ownedSource = 0 }; c.send(src, 1)
      case _ => ())
    c.send(dataMgr, 0, new WlArgs().obj(src)) // create_data_source
    items.keys.foreach { m =>
      if m == "text/plain" then Seq("text/plain;charset=utf-8", "text/plain", "UTF8_STRING").foreach(t => c.send(src, 0, new WlArgs().str(t)))
      else c.send(src, 0, new WlArgs().str(m))
    }
    c.send(dataDevice, 1, new WlArgs().obj(src).uint(inputSerial)) // set_selection

  // ---------------------------------------------------------------- shm presentation
  private final class Buf(val id: Int, val mem: Ptr[Byte], val size: Int):
    var busy = false
  private var bufs = Vector.empty[Buf]
  private var bufW = 0; private var bufH = 0
  private var pools = Vector.empty[(Ptr[Byte], Int)]
  private var retired = Vector.empty[Buf]

  private def releaseBuffers(): Unit =
    // buffers still held by the compositor are destroyed once released
    bufs.foreach(b => if b.busy then retired :+= b else { c.send(b.id, 0); c.forget(b.id) })
    bufs = Vector.empty

  private def allocateBuffers(w: Int, h: Int): Unit =
    releaseBuffers()
    val stride = w * 4; val size = stride * h
    // link-time condition: memfd_create is Linux-only, so the reference must vanish from macOS/Windows binaries
    val fd = if scala.scalanative.meta.LinktimeInfo.isLinux then Zone(WlLibc.memfd_create(toCString("scalaui"), 1.toUInt)) else -1 // MFD_CLOEXEC
    if fd < 0 then throw new RuntimeException("memfd_create failed")
    WlLibc.ftruncate(fd, (2L * size))
    val mem = WlLibc.mmap(null, (2L * size).toCSize, 3, 1, fd, 0L) // PROT_READ|WRITE, MAP_SHARED
    if mem.toLong == -1L then throw new RuntimeException("mmap failed")
    val pool = proxy((_, _) => ())
    c.send(shm, 0, new WlArgs().obj(pool).int(2 * size), attachFd = fd)
    WlLibc.close(fd)
    bufs = Vector.tabulate(2) { i =>
      var self: Buf = null
      val id = proxy((op, _) => if op == 0 && self != null then { self.busy = false; retire(self) })
      c.send(pool, 0, new WlArgs().obj(id).int(i * size).int(w).int(h).int(stride).uint(0)) // ARGB8888
      self = new Buf(id, mem + (i * size), size)
      self
    }
    c.send(pool, 1) // destroy: buffers keep the memory alive
    pools :+= ((mem, 2 * size))
    bufW = w; bufH = h

  private def retire(b: Buf): Unit =
    if retired.contains(b) then { retired = retired.filterNot(_ eq b); c.send(b.id, 0); c.forget(b.id) }

  // pixels each of the two shm buffers is missing compared with the newest frame (a buffer is reused every second frame)
  private val dirty = new DirtyTracker(2)
  private var droppedFrame = false // a frame was dropped (no free buffer): the next commit must damage everything

  def present(rgba: Array[Byte], pixelWidth: Int, pixelHeight: Int): Unit =
    present(rgba, pixelWidth, pixelHeight, 0, 0, pixelWidth, pixelHeight)

  override def present(rgba: Array[Byte], pixelWidth: Int, pixelHeight: Int, rx: Int, ry: Int, rw: Int, rh: Int): Unit =
    if closed || !configured || pixelWidth <= 0 || pixelHeight <= 0 then return
    require(rgba.length >= pixelWidth * pixelHeight * 4, "pixel buffer too small")
    if pixelWidth != bufW || pixelHeight != bufH || bufs.isEmpty then { allocateBuffers(pixelWidth, pixelHeight); dirty.reset(pixelWidth, pixelHeight) }
    // this frame's changes are missing from both buffers (even if no buffer is free and the frame is dropped); the chosen
    // buffer additionally lacks whatever the other one received since it was last used
    val (dx0, dy0, dx1, dy1) = dirty.clamp(rx, ry, rw, rh)
    dirty.add(dx0, dy0, dx1, dy1)
    var free = bufs.find(!_.busy)
    val deadline = System.nanoTime() + 100_000_000L
    while free.isEmpty && System.nanoTime() < deadline do { c.fill(10); c.dispatch(); free = bufs.find(!_.busy) }
    free.foreach { b =>
      val (x0, y0, x1, y1) = dirty.take(bufs.indexWhere(_ eq b))
      // premultiplied RGBA -> ARGB8888 (little endian: B, G, R, A), only the rows/columns that changed
      var y = y0
      while y < y1 do
        var i = (y * pixelWidth + x0) * 4; val end = (y * pixelWidth + x1) * 4
        while i < end do { b.mem(i) = rgba(i + 2); b.mem(i + 1) = rgba(i + 1); b.mem(i + 2) = rgba(i); b.mem(i + 3) = rgba(i + 3); i += 4 }
        y += 1
      b.busy = true
      if viewport != 0 then c.send(viewport, 2, new WlArgs().int(curW).int(curH)) // set_destination: logical size
      else c.send(surface, 8, new WlArgs().int(math.max(1, math.round(scaleFactor).toInt))) // set_buffer_scale
      c.send(surface, 1, new WlArgs().obj(b.id).int(0).int(0))                // attach
      if droppedFrame then c.send(surface, 9, new WlArgs().int(0).int(0).int(pixelWidth).int(pixelHeight))
      else if dx1 > dx0 && dy1 > dy0 then
        c.send(surface, 9, new WlArgs().int(dx0).int(dy0).int(dx1 - dx0).int(dy1 - dy0)) // damage_buffer: only what changed
      droppedFrame = false
      c.send(surface, 6)                                                       // commit
    }
    if free.isEmpty then droppedFrame = true

  // ---------------------------------------------------------------- event loop
  private def drainQueues(): Seq[Event] =
    repeatKeys()
    var ev = external.poll()
    while ev != null do { queue += ev; ev = external.poll() }
    val out = queue.toVector
    queue.clear()
    out

  def pollEvents(): Seq[Event] =
    if closed then return Nil
    if !c.fill(0) then { closed = true; return Vector(Event.CloseRequested) }
    c.dispatch()
    drainQueues()

  override def waitEvents(timeoutMs: Int): Seq[Event] =
    if closed then return Nil
    val repeatWait = held.map(h => math.max(1L, (h._2 - System.nanoTime()) / 1_000_000L).toInt).getOrElse(Int.MaxValue)
    if queue.isEmpty && external.isEmpty then
      if !c.fill(math.max(1, math.min(timeoutMs, repeatWait))) then { closed = true; return Vector(Event.CloseRequested) }
      c.dispatch()
    else { c.fill(0); c.dispatch() }
    drainQueues()

  // ---------------------------------------------------------------- window controls
  def setTitle(t: String): Unit = { title = t; if toplevel != 0 then c.send(toplevel, 2, new WlArgs().str(t)) }
  def close(): Unit = if !closed then { closed = true; c.close() }
  private var minSize = (0, 0)
  override def setMinSize(w: Int, h: Int): Unit = { minSize = (w, h); c.send(toplevel, 8, new WlArgs().int(w).int(h)); c.send(surface, 6) }
  override def setResizable(resizable: Boolean): Unit =
    if resizable then { c.send(toplevel, 7, new WlArgs().int(0).int(0)); c.send(toplevel, 8, new WlArgs().int(minSize._1).int(minSize._2)) }
    else { c.send(toplevel, 7, new WlArgs().int(curW).int(curH)); c.send(toplevel, 8, new WlArgs().int(curW).int(curH)) }
    c.send(surface, 6)
  override def isFullscreen: Boolean = fullscreen
  override def setFullscreen(on: Boolean): Unit = if on then c.send(toplevel, 11, new WlArgs().obj(0)) else c.send(toplevel, 12)
  override def setMinimized(minimized: Boolean): Unit = if minimized then c.send(toplevel, 13)
  override def systemTheme: Theme = LinuxTheme.current()

  override def setAccessibility(nodes: Seq[AccessNode]): Unit =
    if closed then return
    if atspi == null then
      if sys.env.get("SCALAUI_ATSPI").contains("0") then return
      atspi = new AtSpi(title.replace(' ', '-'), () => title, () => scaleFactor, () => (0, 0), e => external.add(e))
    atspi.update(nodes.toVector)
  private var atspi: AtSpi = null

object WaylandWindow:
  /** Connects to the compositor named by `WAYLAND_DISPLAY`; throws when there is none or it lacks xdg-shell. */
  def apply(title: String, width: Int, height: Int): WaylandWindow =
    val w = new WaylandWindow(WlConn.connect(), width, height, title)
    w.init()
    w

/** Light/dark preference of the Linux desktop as far as it can be detected without a toolkit. */
private[window] object LinuxTheme:
  def current(): Theme =
    def dark(s: String) = s.toLowerCase.contains("dark")
    sys.env.get("GTK_THEME").map(t => if dark(t) then Theme.Dark else Theme.Light).getOrElse {
      try
        val f = java.nio.file.Paths.get(sys.env.getOrElse("XDG_CONFIG_HOME", sys.env.getOrElse("HOME", "") + "/.config"), "gtk-3.0", "settings.ini")
        val lines = if java.nio.file.Files.exists(f) then new String(java.nio.file.Files.readAllBytes(f), "UTF-8").linesIterator.map(_.replace(" ", "")).toList else Nil
        if lines.contains("gtk-application-prefer-dark-theme=1") || lines.exists(l => l.startsWith("gtk-theme-name=") && dark(l)) then Theme.Dark else Theme.Light
      catch case _: Exception => Theme.Light
    }
