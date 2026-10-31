package scalaui.docx

import java.io.ByteArrayOutputStream
import scala.collection.mutable
import scala.io.Source
import scala.xml.Elem
import scala.xml.parsing.ConstructingParser
import scalaui.deflate.{Crc32, Zlib}

/** An OPC package (the zip container behind .docx): part name -> bytes, in archive order. */
final class Package(val parts: mutable.LinkedHashMap[String, Array[Byte]]):
  /** Parsed with scala-xml's pure-Scala `ConstructingParser`; the default SAX loader does not exist on Scala Native. */
  def xml(name: String): Option[Elem] =
    parts.get(name).map(b => ConstructingParser.fromSource(Source.fromBytes(b, "UTF-8"), preserveWS = true).document().docElem.asInstanceOf[Elem])

  def toBytes: Array[Byte] =
    import Package.*
    val out = new ByteArrayOutputStream
    val central = new ByteArrayOutputStream
    for (name, bytes) <- parts do
      val nameBytes = name.getBytes("UTF-8")
      val zlib = Zlib.compress(bytes)
      val packed = zlib.slice(2, zlib.length - 4) // strip the zlib header and Adler-32 for raw DEFLATE
      val (method, data) = if packed.length < bytes.length then (Deflated, packed) else (Stored, bytes)
      val crc = Crc32(bytes)
      val offset = out.size
      u32(out, LocalSig); u16(out, Version); u16(out, Utf8Names); u16(out, method); u16(out, 0); u16(out, DosDate)
      u32(out, crc); u32(out, data.length); u32(out, bytes.length); u16(out, nameBytes.length); u16(out, 0)
      out.write(nameBytes); out.write(data)
      u32(central, CentralSig); u16(central, Version); u16(central, Version); u16(central, Utf8Names); u16(central, method); u16(central, 0); u16(central, DosDate)
      u32(central, crc); u32(central, data.length); u32(central, bytes.length); u16(central, nameBytes.length)
      u16(central, 0); u16(central, 0); u16(central, 0); u16(central, 0); u32(central, 0); u32(central, offset)
      central.write(nameBytes)
    val centralOffset = out.size
    out.write(central.toByteArray)
    u32(out, EndSig); u16(out, 0); u16(out, 0); u16(out, parts.size); u16(out, parts.size)
    u32(out, central.size); u32(out, centralOffset); u16(out, 0)
    out.toByteArray

object Package:
  private val LocalSig = 0x04034b50
  private val CentralSig = 0x02014b50
  private val EndSig = 0x06054b50
  private val Stored = 0
  private val Deflated = 8
  private val Version = 20
  private val Utf8Names = 0x0800
  private val DosDate = 0x21 // 1980-01-01, the zip epoch

  private def u16(o: ByteArrayOutputStream, v: Int): Unit = { o.write(v); o.write(v >>> 8) }
  private def u32(o: ByteArrayOutputStream, v: Int): Unit = { u16(o, v); u16(o, v >>> 16) }
  private def r16(b: Array[Byte], i: Int): Int = (b(i) & 0xff) | ((b(i + 1) & 0xff) << 8)
  private def r32(b: Array[Byte], i: Int): Int = r16(b, i) | (r16(b, i + 2) << 16)

  /** Reads through the central directory, so entries written with data descriptors work too. Zip64 is not supported. */
  def read(bytes: Array[Byte]): Package =
    val end = (bytes.length - 22 to math.max(0, bytes.length - 22 - 0xffff) by -1).find(i => r32(bytes, i) == EndSig)
      .getOrElse(throw new IllegalArgumentException("not a zip archive: end of central directory not found"))
    val count = r16(bytes, end + 10)
    var at = r32(bytes, end + 16)
    val parts = mutable.LinkedHashMap.empty[String, Array[Byte]]
    for _ <- 0 until count do
      require(r32(bytes, at) == CentralSig, "corrupt zip central directory")
      val method = r16(bytes, at + 10)
      val crc = r32(bytes, at + 16)
      val packedSize = r32(bytes, at + 20)
      val size = r32(bytes, at + 24)
      val nameLen = r16(bytes, at + 28)
      val skip = nameLen + r16(bytes, at + 30) + r16(bytes, at + 32)
      val local = r32(bytes, at + 42)
      val name = new String(bytes, at + 46, nameLen, "UTF-8")
      at += 46 + skip
      require(packedSize != -1 && size != -1, "zip64 archives are not supported")
      if !name.endsWith("/") then
        val start = local + 30 + r16(bytes, local + 26) + r16(bytes, local + 28)
        val data = method match
          case Stored => bytes.slice(start, start + size)
          case Deflated => Zlib.inflateRaw(bytes, start)
          case m => throw new IllegalArgumentException(s"unsupported zip compression method $m for $name")
        require(Crc32(data) == crc, s"zip entry $name is corrupt (CRC mismatch)")
        parts(name) = data
    new Package(parts)
