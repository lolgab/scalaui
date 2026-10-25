package scalaui.window

import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*
import scala.collection.mutable.ArrayBuffer
import java.nio.charset.StandardCharsets.UTF_8

@extern private object UnixIds:
  def getuid(): CInt = extern

/** D-Bus values as the reader produces them: y/n/q/i -> Int, u/x/t -> Long, b -> Boolean, d -> Double, s/o/g -> String,
  * arrays and structs -> Vector[Any], dict entries -> (key, value), variants -> [[DBus.Variant]].
  */
object DBus:
  final case class Variant(signature: String, value: Any)

  /** An unmarshalled message. `kind`: 1 method call, 2 return, 3 error, 4 signal. */
  final case class Message(kind: Int, serial: Long, replySerial: Long, path: String, iface: String, member: String, sender: String,
      errorName: String, signature: String, body: Vector[Any], noReply: Boolean)

  // ---------------------------------------------------------------- marshalling
  /** Writes values in D-Bus little-endian wire format; alignment is relative to the start of the buffer. */
  final class Writer:
    val buf = ArrayBuffer.empty[Byte]
    def align(n: Int): Unit = while buf.length % n != 0 do buf += 0
    def u8(v: Int): Unit = buf += v.toByte
    def u16(v: Int): Unit = { align(2); buf += v.toByte; buf += (v >> 8).toByte }
    def u32(v: Long): Unit = { align(4); var i = 0; while i < 4 do { buf += (v >> (8 * i)).toByte; i += 1 } }
    def i32(v: Int): Unit = u32(v.toLong & 0xffffffffL)
    def u64(v: Long): Unit = { align(8); var i = 0; while i < 8 do { buf += (v >> (8 * i)).toByte; i += 1 } }
    def bool(v: Boolean): Unit = u32(if v then 1 else 0)
    def str(s: String): Unit = { val b = s.getBytes(UTF_8); u32(b.length); buf ++= b; buf += 0 }
    def sig(s: String): Unit = { u8(s.length); buf ++= s.getBytes(UTF_8); buf += 0 }
    def double(d: Double): Unit = u64(java.lang.Double.doubleToRawLongBits(d))
    /** Patches a u32 written earlier at `at` (already aligned). */
    def patch32(at: Int, v: Long): Unit = { var i = 0; while i < 4 do { buf(at + i) = (v >> (8 * i)).toByte; i += 1 } }

    /** Array: `elemAlign` is the alignment of one element; `f` writes the elements. */
    def array(elemAlign: Int)(f: => Unit): Unit =
      align(4)
      val lenAt = buf.length
      u32(0)
      align(elemAlign)
      val start = buf.length
      f
      patch32(lenAt, (buf.length - start).toLong)

    def struct(f: => Unit): Unit = { align(8); f }

    /** Object reference `(so)` as used by AT-SPI. */
    def ref(bus: String, path: String): Unit = struct { str(bus); str(path) }

    def variantStr(s: String): Unit = { sig("s"); str(s) }
    def variantInt(i: Int): Unit = { sig("i"); i32(i) }
    def variantRef(bus: String, path: String): Unit = { sig("(so)"); ref(bus, path) }

  final class Reader(data: Array[Byte], var pos: Int, end: Int):
    private def align(n: Int): Unit = while pos % n != 0 do pos += 1
    def u8(): Int = { val v = data(pos) & 0xff; pos += 1; v }
    def u16(): Int = { align(2); val v = (data(pos) & 0xff) | ((data(pos + 1) & 0xff) << 8); pos += 2; v }
    def u32(): Long = { align(4); var v = 0L; var i = 0; while i < 4 do { v |= (data(pos + i) & 0xffL) << (8 * i); i += 1 }; pos += 4; v }
    def u64(): Long = { align(8); var v = 0L; var i = 0; while i < 8 do { v |= (data(pos + i) & 0xffL) << (8 * i); i += 1 }; pos += 8; v }
    def str(): String = { val n = u32().toInt; val s = new String(data, pos, n, UTF_8); pos += n + 1; s }
    def sig(): String = { val n = u8(); val s = new String(data, pos, n, UTF_8); pos += n + 1; s }

    /** Reads one complete type starting at `sig(i)`; returns the value and the index after the type. */
    def value(sig: String, i: Int): (Any, Int) = sig.charAt(i) match
      case 'y' => (u8(), i + 1)
      case 'b' => (u32() != 0, i + 1)
      case 'n' => (u16().toShort.toInt, i + 1)
      case 'q' => (u16(), i + 1)
      case 'i' => (u32().toInt, i + 1)
      case 'u' => (u32(), i + 1)
      case 'x' | 't' => (u64(), i + 1)
      case 'd' => (java.lang.Double.longBitsToDouble(u64()), i + 1)
      case 's' | 'o' => (str(), i + 1)
      case 'g' => (this.sig(), i + 1)
      case 'v' => val s = this.sig(); (Variant(s, value(s, 0)._1), i + 1)
      case 'a' =>
        val elemEnd = typeEnd(sig, i + 1)
        val n = u32().toInt
        align(alignOf(sig.charAt(i + 1)))
        val stop = pos + n
        val out = Vector.newBuilder[Any]
        while pos < stop do out += value(sig, i + 1)._1
        (out.result(), elemEnd)
      case '(' =>
        align(8)
        val fields = Vector.newBuilder[Any]
        var j = i + 1
        while sig.charAt(j) != ')' do { val (v, nj) = value(sig, j); fields += v; j = nj }
        (fields.result(), j + 1)
      case '{' =>
        align(8)
        val (k, j) = value(sig, i + 1)
        val (v, j2) = value(sig, j)
        ((k, v), j2 + 1)
      case c => throw new IllegalArgumentException(s"unsupported D-Bus type '$c'")

    def values(sig: String): Vector[Any] =
      val out = Vector.newBuilder[Any]
      var i = 0
      while i < sig.length do { val (v, ni) = value(sig, i); out += v; i = ni }
      out.result()

  def alignOf(c: Char): Int = c match
    case 'y' | 'g' | 'v' => 1
    case 'n' | 'q' => 2
    case 'b' | 'i' | 'u' | 's' | 'o' | 'a' => 4
    case _ => 8

  /** Index just past the single complete type starting at `i`. */
  def typeEnd(sig: String, i: Int): Int = sig.charAt(i) match
    case 'a' => typeEnd(sig, i + 1)
    case '(' => var j = i + 1; while sig.charAt(j) != ')' do j = typeEnd(sig, j); j + 1
    case '{' => var j = i + 1; while sig.charAt(j) != '}' do j = typeEnd(sig, j); j + 1
    case _ => i + 1

  // ---------------------------------------------------------------- messages
  private def header(kind: Int, flags: Int, serial: Long, fields: Seq[(Int, String, String)], body: Array[Byte], bodySig: String): Array[Byte] =
    val w = new Writer
    w.u8('l'); w.u8(kind); w.u8(flags); w.u8(1)
    w.u32(body.length); w.u32(serial)
    w.array(8) {
      fields.foreach { (code, sg, v) =>
        w.struct {
          w.u8(code); w.sig(sg)
          sg match
            case "o" | "s" => w.str(v)
            case "u" => w.u32(v.toLong)
            case "g" => w.sig(v)
        }
      }
    }
    w.align(8)
    w.buf ++= body
    w.buf.toArray

  def methodCall(serial: Long, dest: String, path: String, iface: String, member: String, bodySig: String, body: Array[Byte]): Array[Byte] =
    val f = Seq((1, "o", path), (6, "s", dest), (2, "s", iface), (3, "s", member)) ++ (if bodySig.nonEmpty then Seq((8, "g", bodySig)) else Nil)
    header(1, 0, serial, f, body, bodySig)

  def methodReturn(serial: Long, to: String, replySerial: Long, bodySig: String, body: Array[Byte]): Array[Byte] =
    val f = Seq((5, "u", replySerial.toString), (6, "s", to)) ++ (if bodySig.nonEmpty then Seq((8, "g", bodySig)) else Nil)
    header(2, 1, serial, f, body, bodySig)

  def error(serial: Long, to: String, replySerial: Long, name: String, text: String): Array[Byte] =
    val w = new Writer; w.str(text)
    header(3, 1, serial, Seq((4, "s", name), (5, "u", replySerial.toString), (6, "s", to), (8, "g", "s")), w.buf.toArray, "s")

  def signal(serial: Long, path: String, iface: String, member: String, bodySig: String, body: Array[Byte]): Array[Byte] =
    header(4, 1, serial, Seq((1, "o", path), (2, "s", iface), (3, "s", member)) ++ (if bodySig.nonEmpty then Seq((8, "g", bodySig)) else Nil), body, bodySig)

  /** Parses one complete message from `data` (as returned by [[Connection.readMessage]]). */
  def parse(data: Array[Byte]): Message =
    val r = new Reader(data, 0, data.length)
    require(data(0) == 'l'.toByte, "big-endian D-Bus messages are not supported")
    r.pos = 1
    val kind = r.u8(); val flags = r.u8(); r.u8()
    val bodyLen = r.u32().toInt; val serial = r.u32()
    var path = ""; var iface = ""; var member = ""; var sender = ""; var err = ""; var sig = ""; var replySerial = 0L
    val fields = r.value("a(yv)", 0)._1.asInstanceOf[Vector[Any]]
    fields.foreach {
      case Vector(code: Int, Variant(_, v)) =>
        code match
          case 1 => path = v.asInstanceOf[String]
          case 2 => iface = v.asInstanceOf[String]
          case 3 => member = v.asInstanceOf[String]
          case 4 => err = v.asInstanceOf[String]
          case 5 => replySerial = v.asInstanceOf[Long]
          case 7 => sender = v.asInstanceOf[String]
          case 8 => sig = v.asInstanceOf[String]
          case _ => ()
      case _ => ()
    }
    while r.pos % 8 != 0 do r.pos += 1
    val body = if bodyLen == 0 || sig.isEmpty then Vector.empty else r.values(sig)
    Message(kind, serial, replySerial, path, iface, member, sender, err, sig, body, (flags & 1) != 0)

  // ---------------------------------------------------------------- connection
  /** A blocking connection to a bus (session or accessibility) over a Unix socket. Reads and writes may come from different
    * threads; each side is serialised separately. */
  final class Connection private (fd: Int):
    private var serial = 0L
    private val writeLock = new Object
    var uniqueName = ""

    def nextSerial(): Long = writeLock.synchronized { serial += 1; serial }

    def send(bytes: Array[Byte]): Unit = writeLock.synchronized {
      var off = 0
      while off < bytes.length do
        val n = Sock.write(fd, bytes.atUnsafe(off), (bytes.length - off).toCSize).toInt
        if n <= 0 then throw new RuntimeException("D-Bus connection closed")
        off += n
    }

    private def readExact(n: Int): Array[Byte] =
      val out = new Array[Byte](n); var off = 0
      while off < n do
        val r = Sock.read(fd, out.atUnsafe(off), (n - off).toCSize).toInt
        if r <= 0 then throw new RuntimeException("D-Bus connection closed")
        off += r
      out

    /** Blocks for the next complete message and returns its raw bytes. */
    def readMessage(): Message =
      val head = readExact(16)
      val bodyLen = (head(4) & 0xff) | ((head(5) & 0xff) << 8) | ((head(6) & 0xff) << 16) | ((head(7) & 0xff) << 24)
      val fieldsLen = (head(12) & 0xff) | ((head(13) & 0xff) << 8) | ((head(14) & 0xff) << 16) | ((head(15) & 0xff) << 24)
      val fieldsPadded = (fieldsLen + 7) / 8 * 8
      val rest = readExact(fieldsPadded + bodyLen)
      parse(head ++ rest)

    /** Sends a method call and waits for its reply, queueing nothing else (only for the start-up handshake). */
    def call(dest: String, path: String, iface: String, member: String, bodySig: String = "", body: Array[Byte] = Array.emptyByteArray): Message =
      val s = nextSerial()
      send(methodCall(s, dest, path, iface, member, bodySig, body))
      var reply: Message = null
      while reply == null do
        val m = readMessage()
        if (m.kind == 2 || m.kind == 3) && m.replySerial == s then reply = m
      if reply.kind == 3 then throw new RuntimeException(s"D-Bus error ${reply.errorName}: ${reply.body.headOption.getOrElse("")}")
      reply

    private def authenticate(): Unit =
      val uid = UnixIds.getuid().toString.getBytes(UTF_8).map(b => f"${b & 0xff}%02x").mkString
      send(("\u0000AUTH EXTERNAL " + uid + "\r\n").getBytes(UTF_8))
      val line = new StringBuilder
      while !line.endsWith("\r\n") do line += readExact(1)(0).toChar
      if !line.startsWith("OK") then throw new RuntimeException("D-Bus authentication refused: " + line.toString.trim)
      send("BEGIN\r\n".getBytes(UTF_8))

    private def hello(): Unit =
      val r = call("org.freedesktop.DBus", "/org/freedesktop/DBus", "org.freedesktop.DBus", "Hello")
      uniqueName = r.body.head.asInstanceOf[String]

    def close(): Unit = Sock.close(fd)

  object Connection:
    /** Connects to a bus address such as `unix:path=/run/user/1000/bus` or `unix:abstract=/tmp/dbus-xyz`. */
    def open(address: String): Connection =
      val first = address.split(';').head
      require(first.startsWith("unix:"), s"unsupported D-Bus address $address")
      val kv = first.stripPrefix("unix:").split(',').map(_.split("=", 2)).collect { case Array(k, v) => k -> v }.toMap
      val (name, abstractSocket) = kv.get("path").map(_ -> false).orElse(kv.get("abstract").map(_ -> true)).getOrElse(throw new RuntimeException(s"no socket in $address"))
      val fd = Sock.socket(1, 1, 0)
      if fd < 0 then throw new RuntimeException("socket() failed")
      Zone {
        val addr = alloc[Byte](110)
        var i = 0; while i < 110 do { addr(i) = 0.toByte; i += 1 }
        addr(0) = 1.toByte // AF_UNIX
        val bytes = name.getBytes(UTF_8)
        val off = if abstractSocket then 3 else 2 // abstract names start with a NUL byte
        bytes.indices.foreach(j => addr(off + j) = bytes(j))
        val len = if abstractSocket then 3 + bytes.length else 110
        if Sock.connect(fd, addr, len.toUInt) != 0 then { Sock.close(fd); throw new RuntimeException(s"cannot connect to D-Bus at $address") }
      }
      val c = new Connection(fd)
      c.authenticate()
      c.hello()
      c
