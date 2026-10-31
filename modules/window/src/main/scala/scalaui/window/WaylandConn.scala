package scalaui.window

import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import java.nio.charset.StandardCharsets.UTF_8

/** libc calls for the Wayland client: ancillary-data sockets, shared memory and pipes (Linux). */
@extern private[window] object WlLibc:
  @blocking def sendmsg(fd: CInt, msg: Ptr[Byte], flags: CInt): CSSize = extern
  def recvmsg(fd: CInt, msg: Ptr[Byte], flags: CInt): CSSize = extern
  def memfd_create(name: CString, flags: CUnsignedInt): CInt = extern
  def ftruncate(fd: CInt, length: CLongLong): CInt = extern
  def mmap(addr: Ptr[Byte], length: CSize, prot: CInt, flags: CInt, fd: CInt, offset: CLongLong): Ptr[Byte] = extern
  def munmap(addr: Ptr[Byte], length: CSize): CInt = extern
  def pipe(fds: Ptr[CInt]): CInt = extern
  @blocking def write(fd: CInt, buf: Ptr[Byte], n: CSize): CSSize = extern
  @blocking def read(fd: CInt, buf: Ptr[Byte], n: CSize): CSSize = extern
  def close(fd: CInt): CInt = extern
  @blocking def poll(fds: Ptr[Byte], n: CSize, timeoutMs: CInt): CInt = extern

/** Little-endian Wayland argument encoder. */
private[window] final class WlArgs:
  val buf = ArrayBuffer.empty[Byte]
  def uint(v: Int): WlArgs = { var i = 0; while i < 4 do { buf += (v >> (8 * i)).toByte; i += 1 }; this }
  def int(v: Int): WlArgs = uint(v)
  def obj(id: Int): WlArgs = uint(id)
  def fixed(d: Double): WlArgs = uint(math.round(d * 256).toInt)
  def str(s: String): WlArgs =
    val b = s.getBytes(UTF_8)
    uint(b.length + 1); buf ++= b; buf += 0
    while buf.length % 4 != 0 do buf += 0
    this
  def bytes: Array[Byte] = buf.toArray

/** Wayland event argument decoder. */
private[window] final class WlReader(data: Array[Byte]):
  var pos = 0
  def uint(): Int = { val v = (data(pos) & 0xff) | ((data(pos + 1) & 0xff) << 8) | ((data(pos + 2) & 0xff) << 16) | ((data(pos + 3) & 0xff) << 24); pos += 4; v }
  def int(): Int = uint()
  def fixed(): Double = uint() / 256.0
  def str(): String =
    val n = uint()
    if n == 0 then ""
    else
      val s = new String(data, pos, n - 1, UTF_8)
      pos += (n + 3) / 4 * 4
      s
  def array(): Array[Byte] =
    val n = uint(); val a = java.util.Arrays.copyOfRange(data, pos, pos + n); pos += (n + 3) / 4 * 4; a

/** The Wayland wire protocol over a Unix socket: framing, file-descriptor passing and object dispatch. */
private[window] final class WlConn(fd: Int):
  private val inBuf = ArrayBuffer.empty[Byte]
  private val fdQueue = mutable.Queue.empty[Int]
  private var nextId = 1
  private val handlers = mutable.HashMap.empty[Int, (Int, WlReader) => Unit]
  private val sendLock = new Object

  /** Allocates a client object id and registers its event handler. */
  def newObject(handler: (Int, WlReader) => Unit): Int =
    nextId += 1
    handlers(nextId) = handler
    nextId

  def setHandler(id: Int, h: (Int, WlReader) => Unit): Unit = handlers(id) = h
  def forget(id: Int): Unit = handlers.remove(id)

  /** Sends a request; `attachFd` (if >= 0) travels as SCM_RIGHTS ancillary data. */
  def send(obj: Int, opcode: Int, args: WlArgs = new WlArgs, attachFd: Int = -1): Unit = sendLock.synchronized {
    val body = args.bytes
    val total = 8 + body.length
    val msg = new Array[Byte](total)
    def put32(o: Int, v: Int): Unit = { var i = 0; while i < 4 do { msg(o + i) = (v >> (8 * i)).toByte; i += 1 } }
    put32(0, obj); put32(4, (total << 16) | opcode)
    System.arraycopy(body, 0, msg, 8, body.length)
    Zone {
      val iov = alloc[Byte](16)
      iov.asInstanceOf[Ptr[Ptr[Byte]]](0) = msg.atUnsafe(0)
      (iov + 8).asInstanceOf[Ptr[CSize]](0) = total.toCSize
      val hdr = alloc[Byte](56); var k = 0; while k < 56 do { hdr(k) = 0.toByte; k += 1 }
      (hdr + 16).asInstanceOf[Ptr[Ptr[Byte]]](0) = iov
      (hdr + 24).asInstanceOf[Ptr[CSize]](0) = 1.toCSize
      if attachFd >= 0 then
        val ctl = alloc[Byte](24); k = 0; while k < 24 do { ctl(k) = 0.toByte; k += 1 }
        ctl.asInstanceOf[Ptr[CLongLong]](0) = 20L // cmsg_len = CMSG_LEN(4)
        (ctl + 8).asInstanceOf[Ptr[CInt]](0) = 1   // SOL_SOCKET
        (ctl + 12).asInstanceOf[Ptr[CInt]](0) = 1  // SCM_RIGHTS
        (ctl + 16).asInstanceOf[Ptr[CInt]](0) = attachFd
        (hdr + 32).asInstanceOf[Ptr[Ptr[Byte]]](0) = ctl
        (hdr + 40).asInstanceOf[Ptr[CSize]](0) = 24.toCSize
      val n = WlLibc.sendmsg(fd, hdr, 0).toInt
      if n != total then throw new RuntimeException("Wayland connection closed")
    }
  }

  /** Reads whatever the socket has; `waitMs` > 0 waits that long for data first. Returns false when the peer closed. */
  def fill(waitMs: Int): Boolean =
    if waitMs > 0 then
      Zone {
        val p = alloc[Byte](8)
        p.asInstanceOf[Ptr[CInt]](0) = fd; (p + 4).asInstanceOf[Ptr[CShort]](0) = 1.toShort; (p + 6).asInstanceOf[Ptr[CShort]](0) = 0.toShort
        WlLibc.poll(p, 1.toCSize, waitMs)
      }
    var alive = true; var go = true
    while go do
      Zone {
        val buf = alloc[Byte](4096)
        val iov = alloc[Byte](16)
        iov.asInstanceOf[Ptr[Ptr[Byte]]](0) = buf
        (iov + 8).asInstanceOf[Ptr[CSize]](0) = 4096.toCSize
        val ctl = alloc[Byte](128)
        val hdr = alloc[Byte](56); var k = 0; while k < 56 do { hdr(k) = 0.toByte; k += 1 }
        (hdr + 16).asInstanceOf[Ptr[Ptr[Byte]]](0) = iov
        (hdr + 24).asInstanceOf[Ptr[CSize]](0) = 1.toCSize
        (hdr + 32).asInstanceOf[Ptr[Ptr[Byte]]](0) = ctl
        (hdr + 40).asInstanceOf[Ptr[CSize]](0) = 128.toCSize
        val n = WlLibc.recvmsg(fd, hdr, 0x40 | 0x40000000).toInt // MSG_DONTWAIT | MSG_CMSG_CLOEXEC
        if n > 0 then
          var i = 0; while i < n do { inBuf += buf(i); i += 1 }
          // collect passed file descriptors
          val ctlLen = (hdr + 40).asInstanceOf[Ptr[CSize]](0).toLong
          var off = 0L
          while off + 16 <= ctlLen do
            val len = (ctl + off).asInstanceOf[Ptr[CLongLong]](0)
            val level = (ctl + off + 8).asInstanceOf[Ptr[CInt]](0); val typ = (ctl + off + 12).asInstanceOf[Ptr[CInt]](0)
            if level == 1 && typ == 1 then
              var d = 16L
              while d + 4 <= len do { fdQueue.enqueue((ctl + off + d).asInstanceOf[Ptr[CInt]](0)); d += 4 }
            off += (len + 7) / 8 * 8
            if len <= 0 then off = ctlLen
        else
          go = false
          if n == 0 then alive = false
      }
    alive

  /** Dispatches every complete buffered message; returns how many were handled. */
  def dispatch(): Int =
    var count = 0; var go = true
    while go do
      if inBuf.length < 8 then go = false
      else
        def u32(o: Int) = (inBuf(o) & 0xff) | ((inBuf(o + 1) & 0xff) << 8) | ((inBuf(o + 2) & 0xff) << 16) | ((inBuf(o + 3) & 0xff) << 24)
        val id = u32(0); val word = u32(4)
        val size = word >>> 16; val opcode = word & 0xffff
        if inBuf.length < size then go = false
        else
          val payload = inBuf.slice(8, size).toArray
          inBuf.remove(0, size)
          count += 1
          handlers.get(id).foreach(h => h(opcode, new WlReader(payload)))
    count

  /** Takes the next file descriptor received with a message (e.g. the keymap). */
  def takeFd(): Int = if fdQueue.isEmpty then -1 else fdQueue.dequeue()

  def close(): Unit = WlLibc.close(fd)

private[window] object WlConn:
  def connect(): WlConn =
    val display = sys.env.getOrElse("WAYLAND_DISPLAY", "wayland-0")
    val path = if display.startsWith("/") then display else sys.env.getOrElse("XDG_RUNTIME_DIR", "/run/user/1000") + "/" + display
    val fd = Sock.socket(1, 1, 0)
    if fd < 0 then throw new RuntimeException("socket() failed")
    Zone {
      val addr = alloc[Byte](110); var i = 0; while i < 110 do { addr(i) = 0.toByte; i += 1 }
      addr(0) = 1.toByte
      path.getBytes(UTF_8).zipWithIndex.foreach((b, j) => addr(2 + j) = b)
      if Sock.connect(fd, addr, 110.toUInt) != 0 then { Sock.close(fd); throw new RuntimeException(s"cannot connect to Wayland at $path") }
    }
    new WlConn(fd)
