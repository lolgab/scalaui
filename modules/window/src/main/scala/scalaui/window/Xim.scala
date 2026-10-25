package scalaui.window

import scala.collection.mutable.ArrayBuffer

/** What the XIM client needs from the X connection it rides on (implemented by [[X11Window]]). */
private[window] trait XimIo:
  def intern(name: String): Int
  def atomName(atom: Int): String
  def selectionOwner(selection: Int): Int
  def convertSelection(selection: Int, target: Int, property: Int): Unit
  /** The raw bytes of a property of our own window (deleted afterwards when `delete`), or empty. */
  def readProperty(window: Int, property: Int, delete: Boolean): Array[Byte]
  def readRootAtoms(property: Int): Seq[Int]
  /** SendEvent of a ClientMessage with `data` (20 bytes) in `format` (8 or 32) to the owner of `dest`. */
  def clientMessage(dest: Int, typ: Int, format: Int, data: Array[Byte]): Unit
  def appendProperty(window: Int, property: Int, data: Array[Byte]): Unit
  /** Services the connection (routing XIM traffic to the client) until `until` holds or the timeout passes. */
  def pump(until: () => Boolean, timeoutMs: Int): Boolean

/** Client of the X Input Method protocol (XIM 1.0 over the X transport: client messages and properties), written against
  * the specification so that no libX11 is needed. Preferred input style is on-the-spot (preedit callbacks); servers
  * that only offer over-the-spot / root-window styles are driven through those instead.
  *
  * Committed text and preedit updates are handed to `emit` as [[Event.TextInput]] / [[Event.Composition]]; key events the
  * server did not consume come back through `unfiltered`. Everything runs on the thread that polls X events.
  */
private[window] final class Xim(io: XimIo, clientWin: Int, emit: Event => Unit, unfiltered: Array[Byte] => Unit, keysymOf: Array[Byte] => Int):
  import Xim.*

  private def dbg(m: String): Unit = if sys.env.contains("SCALAUI_DEBUG") then System.err.println("xim: " + m)

  private var typProtocol, typMoreData, typXConnect, selProp = 0
  private var serverWin = 0
  private var imId = 0; private var icId = 0
  private var utf8 = false
  private val imAttrs = scala.collection.mutable.HashMap.empty[String, Int]
  private val icAttrs = scala.collection.mutable.HashMap.empty[String, Int]
  private var style = 0
  private var forwardMask = 0; private var syncMask = 0
  private var onKeys = Vector.empty[(Int, Int, Int)]; private var offKeys = Vector.empty[(Int, Int, Int)]
  private var triggered = false
  private var focused = false
  private var propCounter = 0
  private val inbox = scala.collection.mutable.HashMap.empty[Int, Array[Byte]]
  private var selectionDone = false
  private var waitingSelection = 0
  private var failed: Option[String] = None
  private val partial = ArrayBuffer.empty[Byte]
  @volatile private var up = false
  // synchronous event flow: after a synchronous forward we hold further keys until XIM_SYNC_REPLY
  private var awaitingSync = false; private var syncDeadline = 0L
  private val held = ArrayBuffer.empty[Array[Byte]]
  // preedit buffer in code points, and the last caret/spot
  private var preedit = Array.emptyIntArray; private var preeditCaret = 0
  private var spot: Option[(Int, Int)] = None

  def active: Boolean = up
  def inputStyle: Int = style
  def usesUtf8: Boolean = utf8

  // ------------------------------------------------------------------ discovery + handshake

  /** Finds a server through `@im=` in XMODIFIERS, else the first live entry of the root `XIM_SERVERS` property. */
  private def discover(): Option[(String, Int)] =
    val fromEnv = sys.env.get("XMODIFIERS").toSeq.flatMap(_.split('@').toSeq).map(_.trim).find(_.startsWith("im=")).map(_.drop(3))
    if fromEnv.contains("none") then None
    else
      val names = fromEnv match
        case Some(n) => Seq("@server=" + n)
        case None => io.readRootAtoms(io.intern("XIM_SERVERS")).map(io.atomName).filter(_.startsWith("@server="))
      names.iterator.map(n => (n, io.intern(n))).map((n, a) => (n, a, io.selectionOwner(a))).collectFirst { case (n, a, o) if o != 0 => (n, a) }

  private def selectionText(selection: Int, target: String): Option[String] =
    selectionDone = false; waitingSelection = selection
    io.convertSelection(selection, io.intern(target), selProp)
    if io.pump(() => selectionDone, 1000) then
      val data = io.readProperty(clientWin, selProp, delete = true)
      if data.isEmpty then None else Some(new String(data, "ISO-8859-1"))
    else None

  /** Called by the window for every SelectionNotify; true when it answers one of our LOCALES/TRANSPORT requests. */
  def selectionNotify(selection: Int): Boolean =
    if waitingSelection != 0 && selection == waitingSelection && !selectionDone then { selectionDone = true; true } else false

  private def chooseLocale(offered: Seq[String]): String =
    val env = Seq("LC_ALL", "LC_CTYPE", "LANG").flatMap(sys.env.get).find(_.nonEmpty).getOrElse("en_US.UTF-8")
    val norm = (s: String) => s.toLowerCase.replace("-", "").replace("utf8", "utf8")
    offered.find(o => norm(o) == norm(env))
      .orElse(offered.find(_.toLowerCase.take(2) == env.toLowerCase.take(2)))
      .orElse(offered.headOption).getOrElse(if env == "C" || env == "POSIX" then "en_US.UTF-8" else env)

  /** Connects, opens the method, negotiates encoding and input style and creates the input context. Returns false (and leaves
    * the window to its plain keyboard handling) when no server is found or anything fails. */
  def connect(): Boolean =
    try doConnect()
    catch
      case _: Abort => up = false; false
      case e: RuntimeException => dbg("failed: " + e); up = false; false

  private def abort(): Nothing = throw new Abort

  private def doConnect(): Boolean =
      val (name, selAtom) = discover().getOrElse { dbg("no server"); abort() }
      dbg("server " + name)
      typProtocol = io.intern("_XIM_PROTOCOL"); typMoreData = io.intern("_XIM_MOREDATA"); typXConnect = io.intern("_XIM_XCONNECT")
      selProp = io.intern("_SCALAUI_XIM")
      val owner = io.selectionOwner(selAtom)
      val locales = selectionText(selAtom, "LOCALES").map(_.stripPrefix("@locale=").split(',').toSeq.map(_.trim).filter(_.nonEmpty)).getOrElse(Nil)
      selectionText(selAtom, "TRANSPORT").foreach { t =>
        if !t.stripPrefix("@transport=").split(',').exists(_.trim.toLowerCase.startsWith("x/")) then fail("no X transport: " + t)
      }
      if failed.isDefined then abort()
      // _XIM_XCONNECT: client window, protocol 0.0, dividing size
      io.clientMessage(owner, typXConnect, 32, le32(clientWin, 0, 0, 20, 0))
      if !io.pump(() => serverWin != 0 || failed.isDefined, 1500) || failed.isDefined then { dbg("no XCONNECT reply"); abort() }

      send(1, 0, Array[Byte](0x6c, 0) ++ le16(0, 0, 0))              // XIM_CONNECT: little endian, v0.0, no auth
      expect(2, "CONNECT_REPLY").getOrElse(abort())
      val locale = chooseLocale(locales)
      val open = ArrayBuffer(locale.length.toByte); open ++= locale.getBytes("ISO-8859-1"); pad(open, 1 + locale.length)
      send(30, 0, open.toArray)
      val oreply = expect(31, "OPEN_REPLY").getOrElse(abort())
      imId = u16(oreply, 0)
      parseAttrs(oreply, imAttrs, icAttrs)

      send(40, 0, le16(imId, 0))                                         // QUERY_EXTENSION: we use none
      // optional: some servers (IMdkit) stay silent for an empty extension list, so a missing reply is not an error
      io.pump(() => inbox.contains(41) || failed.isDefined, 300); inbox.remove(41)

      val enc = ArrayBuffer.empty[Byte]
      val names = Seq("UTF-8", "COMPOUND_TEXT")
      names.foreach { n => enc += n.length.toByte; enc ++= n.getBytes("ISO-8859-1") }
      val body = ArrayBuffer.empty[Byte]
      body ++= le16(imId, enc.length); body ++= enc; pad(body, enc.length); body ++= le16(0, 0)
      send(38, 0, body.toArray)                                          // ENCODING_NEGOTIATION
      val ereply = expect(39, "ENCODING_NEGOTIATION_REPLY").getOrElse(abort())
      val idx = u16(ereply, 4).toShort
      utf8 = idx == 0
      dbg(s"encoding index $idx -> ${if utf8 then "UTF-8" else "COMPOUND_TEXT"}")

      imAttrs.get("queryInputStyle").foreach { id =>
        send(44, 0, le16(imId, 2, id))               // GET_IM_VALUES queryInputStyle
        val r = expect(45, "GET_IM_VALUES_REPLY").getOrElse(abort())
        var p = 4
        while p + 4 <= r.length do
          val aid = u16(r, p); val vlen = u16(r, p + 2)
          if aid == id then
            val n = u16(r, p + 4)
            style = chooseStyle((0 until n).map(i => u32(r, p + 8 + 4 * i)))
          p += 4 + vlen + padLen(vlen)
      }
      if style == 0 then { dbg("no usable input style"); abort() }
      dbg(f"style 0x$style%x")
      createIc()
      up = failed.isEmpty && icId != 0
      if up then
        dbg("input context ready")
        // the server's SET_EVENT_MASK usually follows immediately
        io.pump(() => forwardMask != 0 || onKeys.nonEmpty || failed.isDefined, 300)
        if focused then setFocus(true)
      up

  private def fail(msg: String): Unit = { dbg(msg); failed = Some(msg) }

  private def expect(op: Int, what: String): Option[Array[Byte]] =
    if io.pump(() => inbox.contains(op) || failed.isDefined, 2000) && inbox.contains(op) then inbox.remove(op)
    else { fail("timeout waiting for " + what); None }

  private def chooseStyle(offered: Seq[Int]): Int =
    val prefs = Seq(PreeditCallbacks | StatusNothing, PreeditCallbacks | StatusNone, PreeditCallbacks | StatusCallbacks,
      PreeditPosition | StatusNothing, PreeditPosition | StatusNone, PreeditNothing | StatusNothing, PreeditNothing | StatusNone,
      PreeditNone | StatusNone, PreeditNone | StatusNothing)
    prefs.find(offered.contains).getOrElse(0)

  private def attrEntry(id: Int, value: Array[Byte]): Array[Byte] =
    val b = ArrayBuffer.empty[Byte]; b ++= le16(id, value.length); b ++= value; pad(b, value.length); b.toArray

  private def icValue(name: String, value: Array[Byte]): Option[Array[Byte]] = icAttrs.get(name).map(attrEntry(_, value))

  private def spotValue(x: Int, y: Int): Option[Array[Byte]] =
    icValue("spotLocation", le16(x, y)).map(e => attrEntry(icAttrs("preeditAttributes"), e)).filter(_ => icAttrs.contains("preeditAttributes"))

  private def createIc(): Unit =
    val attrs = ArrayBuffer.empty[Byte]
    icValue("inputStyle", le32(style)).foreach(attrs ++= _)
    icValue("clientWindow", le32(clientWin)).foreach(attrs ++= _)
    icValue("focusWindow", le32(clientWin)).foreach(attrs ++= _)
    if (style & PreeditPosition) != 0 then spotValue(spot.fold(0)(_._1), spot.fold(0)(_._2)).foreach(attrs ++= _)
    send(50, 0, le16(imId, attrs.length) ++ attrs)
    expect(51, "CREATE_IC_REPLY").foreach(r => icId = u16(r, 2))

  // ------------------------------------------------------------------ outgoing

  private def send(op: Int, minor: Int, body: Array[Byte]): Unit =
    val padded = body.length + padLen(body.length)
    val pkt = new Array[Byte](4 + padded)
    pkt(0) = op.toByte; pkt(1) = minor.toByte; pkt(2) = ((padded / 4) & 0xff).toByte; pkt(3) = ((padded / 4) >> 8).toByte
    System.arraycopy(body, 0, pkt, 4, body.length)
    if pkt.length <= 20 then io.clientMessage(serverWin, typProtocol, 8, java.util.Arrays.copyOf(pkt, 20))
    else
      propCounter = (propCounter + 1) % 256
      val atom = io.intern("_client" + propCounter)
      io.appendProperty(serverWin, atom, pkt)
      io.clientMessage(serverWin, typProtocol, 32, le32(pkt.length, atom, 0, 0, 0))

  def setFocus(on: Boolean): Unit =
    focused = on
    if up then send(if on then 58 else 59, 0, le16(imId, icId))

  /** Reports the caret position (window pixels) so the server can place its candidate window. */
  def setSpot(x: Int, y: Int): Unit =
    if spot.contains((x, y)) then return
    spot = Some((x, y))
    if up then
      spotValue(x, y).foreach { v => send(54, 0, le16(imId, icId, v.length, 0) ++ v) }

  def reset(): Unit = if up then { preedit = Array.emptyIntArray; send(64, 0, le16(imId, icId)) }

  def close(): Unit =
    if up then
      up = false
      try
        send(52, 0, le16(imId, icId)); send(32, 0, le16(imId, 0)); send(3, 0, Array.emptyByteArray)
      catch case _: RuntimeException => ()

  // ------------------------------------------------------------------ key events

  private def eventBit(code: Int): Int = if (code & 0x7f) == 2 then 1 else 2 // KeyPressMask / KeyReleaseMask

  /** Offers a key press/release to the input method. True when it was taken (forwarded to the server, which will either
    * commit text or hand the key back through `unfiltered`). */
  def filterKey(p: Array[Byte]): Boolean =
    if !up then return false
    val code = p(0) & 0x7f
    if awaitingSync then { held += p; return true }
    if !triggered && forwardMask == 0 && onKeys.nonEmpty then
      if code == 2 then
        val ks = keysymOf(p); val state = (p(28) & 0xff) | ((p(29) & 0xff) << 8)
        val i = onKeys.indexWhere((k, m, mask) => k == ks && (state & mask) == m)
        if i >= 0 then { triggered = true; send(35, 0, le16(imId, icId) ++ le32(0, i, 3)); return true }
      return false
    if triggered && code == 2 && offKeys.nonEmpty then
      val ks = keysymOf(p); val state = (p(28) & 0xff) | ((p(29) & 0xff) << 8)
      val i = offKeys.indexWhere((k, m, mask) => k == ks && (state & mask) == m)
      if i >= 0 then { triggered = false; send(35, 0, le16(imId, icId) ++ le32(1, i, 3)); return true }
    if (forwardMask & eventBit(code)) == 0 then return false
    val sync = (syncMask & eventBit(code)) != 0
    val serial = (p(2) & 0xff) | ((p(3) & 0xff) << 8)
    send(60, 0, le16(imId, icId, if sync then 1 else 0, serial) ++ p)
    if sync then { awaitingSync = true; syncDeadline = System.nanoTime() + 500_000_000L }
    true

  /** Releases held keys if the server never acknowledged a synchronous forward. */
  def tick(): Unit =
    if awaitingSync && System.nanoTime() > syncDeadline then { dbg("sync timeout"); endSync() }

  private def endSync(): Unit =
    awaitingSync = false
    val keys = held.toVector; held.clear()
    keys.foreach(k => if !filterKey(k) then unfiltered(k))

  // ------------------------------------------------------------------ incoming

  def isXimMessage(typ: Int): Boolean = typ != 0 && (typ == typProtocol || typ == typMoreData || typ == typXConnect)

  /** ClientMessage addressed to our window: `format` 8 carries 20 bytes inline; 32 names a property holding the message. */
  def clientMessage(typ: Int, format: Int, data: Array[Byte]): Unit =
    if typ == typXConnect then
      serverWin = u32(data, 0)
      dbg(f"XCONNECT reply, server window 0x$serverWin%x")
    else if format == 8 then
      partial ++= data
      if typ == typProtocol then
        val total = 4 + 4 * u16(partial.toArray, 2)
        val msg = partial.toArray.take(total); partial.clear()
        handle(msg)
    else
      val len = u32(data, 0); val atom = u32(data, 4)
      val prop = io.readProperty(clientWin, atom, delete = true)
      if prop.length >= 4 then handle(prop.take(math.min(prop.length, math.max(len, 4))))

  private def handle(msg: Array[Byte]): Unit =
    val op = msg(0) & 0xff
    val body = msg.drop(4)
    op match
      case 2 | 31 | 39 | 41 | 45 | 51 => inbox(op) = body
      case 20 => // XIM_ERROR: imid icid flag code len type detail
        dbg(s"error code ${u16(body, 6)} ${if body.length > 12 then new String(body, 12, math.min(u16(body, 8), body.length - 12), "ISO-8859-1") else ""}")
        if !up then fail("XIM_ERROR during setup")
      case 3 => up = false; preedit = Array.emptyIntArray; emit(Event.Composition("", 0, 0)); send(4, 0, Array.emptyByteArray)
      case 4 | 33 | 36 | 43 | 53 | 55 | 65 => ()
      case 34 => // REGISTER_TRIGGERKEYS: imid, 2 unused, on-keys byte length, keys, off-keys length, keys
        val onLen = u32(body, 4)
        def keys(from: Int, len: Int) = (0 until len / 12).map(i => (u32(body, from + 12 * i), u32(body, from + 12 * i + 4), u32(body, from + 12 * i + 8))).toVector
        onKeys = keys(8, onLen)
        val offLen = u32(body, 8 + onLen)
        offKeys = keys(12 + onLen, offLen)
        dbg(s"trigger keys on=${onKeys.length} off=${offKeys.length}")
      case 37 => forwardMask = u32(body, 4); syncMask = u32(body, 8); dbg(f"event masks forward=0x$forwardMask%x sync=0x$syncMask%x")
      case 60 => // returned (unconsumed) key event; flag bit 0 asks for a sync reply
        val flag = u16(body, 4)
        val ev = body.slice(8, 40)
        if ev.length == 32 then unfiltered(ev)
        if (flag & 1) != 0 then send(62, 0, le16(imId, icId))
      case 63 => commit(body)
      case 61 => send(62, 0, le16(imId, icId))
      case 62 => if awaitingSync then endSync()
      case 73 => send(74, 0, le16(imId, icId) ++ le32(-1)); dbg("preedit start")
      case 75 => preeditDraw(body)
      case 76 => // PREEDIT_CARET: position, direction, style -> reply with the caret position
        val pos = u32(body, 4)
        val newPos = if u32(body, 8) == 0 then preeditCaret + pos else if u32(body, 8) == 1 then preeditCaret - pos else pos
        preeditCaret = newPos.max(0).min(preedit.length)
        send(77, 0, le16(imId, icId) ++ le32(preeditCaret))
        emitComposition()
      case 78 => preedit = Array.emptyIntArray; preeditCaret = 0; emit(Event.Composition("", 0, 0))
      case 79 | 80 | 81 | 70 => () // status area callbacks / geometry: we draw no status
      case 71 => send(72, 0, le16(imId, icId) ++ le32(0, 0, 0))
      case _ => dbg(s"unhandled opcode $op")

  private def decode(b: Array[Byte]): String = if utf8 then new String(b, "UTF-8") else CompoundText.decode(b)

  private def commit(body: Array[Byte]): Unit =
    val flag = u16(body, 4)
    var text = ""
    if (flag & 6) == 6 then { val n = u16(body, 10); text = decode(body.slice(12, 12 + n)) }
    else if (flag & 2) != 0 then { val n = u16(body, 6); text = decode(body.slice(8, 8 + n)) }
    else if (flag & 4) != 0 then
      val ks = u32(body, 8)
      text = if ks >= 0x20 && ks <= 0x7e || ks >= 0xa0 && ks <= 0xff then new String(Character.toChars(ks)) else if ks >= 0x01000100 && ks <= 0x0110ffff then new String(Character.toChars(ks - 0x01000000)) else ""
    if text.nonEmpty then emit(Event.TextInput(text))
    if (flag & 1) != 0 then send(62, 0, le16(imId, icId))

  private def preeditDraw(body: Array[Byte]): Unit =
    val caret = u32(body, 4); val first = u32(body, 8); val len = u32(body, 12); val status = u32(body, 16)
    val n = u16(body, 20)
    val text = if (status & 1) != 0 || n == 0 then "" else decode(body.slice(22, 22 + n))
    val ins = text.codePoints().toArray
    val f = first.max(0).min(preedit.length); val l = len.max(0).min(preedit.length - f)
    preedit = preedit.take(f) ++ ins ++ preedit.drop(f + l)
    preeditCaret = caret.max(0).min(preedit.length)
    emitComposition()

  private def emitComposition(): Unit =
    val s = new String(preedit, 0, preedit.length)
    val caretUtf16 = new String(preedit, 0, preeditCaret.min(preedit.length)).length
    emit(Event.Composition(s, caretUtf16, 0))

object Xim:
  private final class Abort extends RuntimeException(null, null, false, false)
  val PreeditArea = 0x1; val PreeditCallbacks = 0x2; val PreeditPosition = 0x4; val PreeditNothing = 0x8; val PreeditNone = 0x10
  val StatusArea = 0x100; val StatusCallbacks = 0x200; val StatusNothing = 0x400; val StatusNone = 0x800

  def padLen(n: Int): Int = (4 - n % 4) % 4
  private def pad(b: ArrayBuffer[Byte], n: Int): Unit = { var i = padLen(n); while i > 0 do { b += 0; i -= 1 } }
  def u16(b: Array[Byte], o: Int): Int = if o + 2 > b.length then 0 else (b(o) & 0xff) | ((b(o + 1) & 0xff) << 8)
  def u32(b: Array[Byte], o: Int): Int = if o + 4 > b.length then 0 else u16(b, o) | (u16(b, o + 2) << 16)
  def le16(v: Int*): Array[Byte] = v.flatMap(x => Seq(x.toByte, (x >> 8).toByte)).toArray
  def le32(v: Int*): Array[Byte] = v.flatMap(x => Seq(x.toByte, (x >> 8).toByte, (x >> 16).toByte, (x >> 24).toByte)).toArray

  /** Reads the XIMATTR lists of an OPEN_REPLY into name -> id maps. */
  private[window] def parseAttrs(r: Array[Byte], im: scala.collection.mutable.HashMap[String, Int], ic: scala.collection.mutable.HashMap[String, Int]): Unit =
    def list(from: Int, bytes: Int, into: scala.collection.mutable.HashMap[String, Int]): Unit =
      var p = from; val end = from + bytes
      while p + 6 <= end && p + 6 <= r.length do
        val id = u16(r, p); val nlen = u16(r, p + 4)
        into(new String(r, p + 6, math.min(nlen, r.length - p - 6), "ISO-8859-1")) = id
        p += 6 + nlen + padLen(2 + nlen)
    val imLen = u16(r, 2)
    list(4, imLen, im)
    val icOff = 4 + imLen
    list(icOff + 4, u16(r, icOff), ic)

/** X Compound Text decoding for the subset input methods emit: ASCII, ISO 8859-1 (the default right half), UTF-8 escape
  * sequences (`ESC % G ... ESC % @`) and the `iso10646-1` extended segment. Other character sets decode to U+FFFD. */
private[window] object CompoundText:
  def decode(b: Array[Byte]): String =
    val sb = new StringBuilder
    var i = 0; var grLatin1 = true; var wide = 0
    def at(k: Int) = if k < b.length then b(k) & 0xff else -1
    while i < b.length do
      val c = b(i) & 0xff
      if c == 0x1b then
        if at(i + 1) == '%' && at(i + 2) == 'G' then
          var j = i + 3
          while j < b.length && !(at(j) == 0x1b && at(j + 1) == '%' && at(j + 2) == '@') do j += 1
          sb ++= new String(b, i + 3, j - i - 3, "UTF-8"); i = math.min(b.length, j + 3)
        else if at(i + 1) == '%' && at(i + 2) == '/' && at(i + 3) >= '1' && at(i + 3) <= '4' then
          val len = (at(i + 4) & 0x7f) * 128 + (at(i + 5) & 0x7f)
          val seg = b.slice(i + 6, i + 6 + len)
          val nul = seg.indexOf(2.toByte)
          val name = if nul >= 0 then new String(seg, 0, nul, "ISO-8859-1").toLowerCase else ""
          if name.startsWith("iso10646-1") then sb ++= new String(seg, nul + 1, seg.length - nul - 1, "UTF-8") else sb += '�'
          i += 6 + len
        else
          // ESC [intermediates] final: a character set designation; remember only whether the right half is Latin-1
          var j = i + 1
          while j < b.length && at(j) >= 0x20 && at(j) <= 0x2f do j += 1
          val inter = b.slice(i + 1, j).map(_.toChar).mkString
          val fin = at(j)
          if inter == "-" then grLatin1 = fin == 'A'
          else if inter.startsWith("$") then wide = 2
          else if inter == "(" && fin == 'B' then wide = 0
          i = j + 1
      else if wide == 2 && c >= 0x21 then { sb += '�'; i += 2 }
      else if c < 0x80 then { sb += c.toChar; i += 1 }
      else if c >= 0xa0 && grLatin1 then { sb += c.toChar; i += 1 }
      else { sb += '�'; i += 1 }
    sb.toString
