package scalaui.codecs

import scalaui.deflate.Zlib

/** Extraction of embedded ICC profiles (raw profile bytes) from encoded images. */
object IccProfile:
  private def u32(d: Array[Byte], o: Int) = ((d(o) & 0xff) << 24) | ((d(o + 1) & 0xff) << 16) | ((d(o + 2) & 0xff) << 8) | (d(o + 3) & 0xff)
  private def le32(d: Array[Byte], o: Int) = (d(o) & 0xff) | ((d(o + 1) & 0xff) << 8) | ((d(o + 2) & 0xff) << 16) | ((d(o + 3) & 0xff) << 24)
  private def tag(d: Array[Byte], o: Int) = new String(d, o, 4, "ISO-8859-1")

  def extract(d: Array[Byte]): Option[Array[Byte]] =
    try
      if Png.matches(d) then png(d)
      else if d.length > 2 && (d(0) & 0xff) == 0xff && (d(1) & 0xff) == 0xd8 then jpeg(d)
      else if Webp.matches(d) then webp(d)
      else None
    catch case _: RuntimeException => None

  private def png(d: Array[Byte]): Option[Array[Byte]] =
    var pos = 8
    while pos + 12 <= d.length do
      val len = u32(d, pos); val typ = tag(d, pos + 4); val body = pos + 8
      if len < 0 || body + len > d.length then return None
      if typ == "iCCP" then
        var n = 0
        while n < len && d(body + n) != 0 do n += 1
        if n + 2 > len then return None
        return Some(Zlib.decompress(java.util.Arrays.copyOfRange(d, body + n + 2, body + len)))
      if typ == "IDAT" then return None // iCCP must precede IDAT
      pos = body + len + 4
    None

  private def jpeg(d: Array[Byte]): Option[Array[Byte]] =
    val parts = scala.collection.mutable.TreeMap.empty[Int, Array[Byte]]
    var pos = 2
    val magic = "ICC_PROFILE\u0000".getBytes("ISO-8859-1")
    while pos + 4 <= d.length do
      if (d(pos) & 0xff) != 0xff then pos += 1
      else
        val m = d(pos + 1) & 0xff
        if m == 0xff then pos += 1
        else if m == 0xd8 || m == 0x01 || (m >= 0xd0 && m <= 0xd7) then pos += 2
        else if m == 0xda || m == 0xd9 then pos = d.length
        else
          val len = ((d(pos + 2) & 0xff) << 8) | (d(pos + 3) & 0xff)
          val body = pos + 4
          if len < 2 || pos + 2 + len > d.length then pos = d.length
          else
            if m == 0xe2 && len >= 2 + magic.length + 2 && java.util.Arrays.equals(java.util.Arrays.copyOfRange(d, body, body + magic.length), magic) then
              parts(d(body + magic.length) & 0xff) = java.util.Arrays.copyOfRange(d, body + magic.length + 2, pos + 2 + len)
            pos += 2 + len
    if parts.isEmpty then None else Some(parts.values.flatten.toArray)

  private def webp(d: Array[Byte]): Option[Array[Byte]] =
    var p = 12
    while p + 8 <= d.length do
      val size = le32(d, p + 4)
      if size < 0 || p + 8 + size > d.length then return None
      if tag(d, p) == "ICCP" then return Some(java.util.Arrays.copyOfRange(d, p + 8, p + 8 + size))
      p += 8 + size + (size & 1)
    None
