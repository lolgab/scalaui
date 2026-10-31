package scalaui.codecs

/** Windows/OS2 bitmaps: reads 1/4/8-bit palettes (incl. RLE4/RLE8), 16/24/32-bit RGB and bit-field formats with optional
  * alpha, bottom-up and top-down; writes 24-bit (opaque) or 32-bit (with alpha) bitmaps.
  */
object Bmp:
  def matches(d: Array[Byte]): Boolean = d.length >= 18 && d(0) == 'B' && d(1) == 'M'

  private def u16(d: Array[Byte], o: Int) = (d(o) & 0xff) | ((d(o + 1) & 0xff) << 8)
  private def u32(d: Array[Byte], o: Int) = u16(d, o) | (u16(d, o + 2) << 16)

  private def fail(msg: String): Nothing = throw new ImageFormatException("BMP: " + msg)

  /** Scales the field selected by `mask` to 0..255. */
  private def channel(v: Int, mask: Int): Int =
    if mask == 0 then 255
    else
      val shift = Integer.numberOfTrailingZeros(mask)
      val bits = Integer.bitCount(mask >>> shift)
      val x = (v & mask) >>> shift
      if bits >= 8 then x >> (bits - 8) else (x * 255 + ((1 << bits) - 1) / 2) / ((1 << bits) - 1)

  def decode(d: Array[Byte]): Image =
    if !matches(d) then fail("not a bitmap")
    val dataOffset = u32(d, 10)
    val headerSize = u32(d, 14)
    var width: Int = 0; var height: Int = 0; var bpp = 0; var compression = 0; var colorsUsed = 0
    var palEntrySize = 4
    if headerSize == 12 then
      width = u16(d, 18); height = (u16(d, 20) << 16) >> 16; bpp = u16(d, 24); palEntrySize = 3
    else if headerSize >= 40 then
      width = u32(d, 18); height = u32(d, 22); bpp = u16(d, 28); compression = u32(d, 30); colorsUsed = u32(d, 46)
    else fail(s"unsupported header size $headerSize")
    val topDown = height < 0
    val h = math.abs(height)
    if width <= 0 || h == 0 || width > 65536 || h > 65536 then fail("bad dimensions")
    if compression == 4 || compression == 5 then fail("embedded JPEG/PNG bitmaps are not supported")

    // masks (BITFIELDS) live in the header (V4+) or right after a 40-byte header
    var rMask = 0; var gMask = 0; var bMask = 0; var aMask = 0
    var paletteStart = 14 + headerSize
    if compression == 3 || compression == 6 then
      if headerSize == 40 then
        rMask = u32(d, 54); gMask = u32(d, 58); bMask = u32(d, 62)
        if compression == 6 then { aMask = u32(d, 66); paletteStart += 16 } else paletteStart += 12
      else { rMask = u32(d, 54); gMask = u32(d, 58); bMask = u32(d, 62); aMask = u32(d, 66) }
    else if headerSize >= 108 then { aMask = u32(d, 66) }
    val defaultMasks = compression == 0 || compression == 3 && rMask == 0
    if (compression == 0) then
      if bpp == 16 then { rMask = 0x7C00; gMask = 0x03E0; bMask = 0x001F }
      else if bpp == 32 then { rMask = 0xFF0000; gMask = 0xFF00; bMask = 0xFF }
    if defaultMasks && compression != 0 then fail("missing bit masks")

    val paletteSize = if bpp <= 8 then (if colorsUsed > 0 then colorsUsed else 1 << bpp) else 0
    val palette = new Array[Int](paletteSize)
    var i = 0
    while i < paletteSize do
      val o = paletteStart + i * palEntrySize
      if o + 2 < d.length then palette(i) = (0xFF << 24) | ((d(o + 2) & 0xff) << 16) | ((d(o + 1) & 0xff) << 8) | (d(o) & 0xff)
      i += 1

    val out = new Array[Byte](width * h * 4)
    def put(x: Int, y: Int, argb: Int): Unit =
      val row = if topDown then y else h - 1 - y
      val o = (row * width + x) * 4
      out(o) = (argb >> 16).toByte; out(o + 1) = (argb >> 8).toByte; out(o + 2) = argb.toByte; out(o + 3) = (argb >>> 24).toByte

    if compression == 1 || compression == 2 then decodeRle(d, dataOffset, width, h, compression == 1, palette, put)
    else
      val rowBytes = ((width * bpp + 31) / 32) * 4
      if dataOffset + rowBytes.toLong * h > d.length + 3 && dataOffset + rowBytes.toLong * (h - 1) >= d.length then fail("truncated pixel data")
      var anyAlpha = false
      var y = 0
      while y < h do
        val ro = dataOffset + y * rowBytes
        var x = 0
        while x < width do
          val argb: Int = bpp match
            case 1 => palette((d(ro + x / 8) >> (7 - x % 8)) & 1)
            case 4 => palette(((d(ro + x / 2) & 0xff) >> (if x % 2 == 0 then 4 else 0)) & 15)
            case 8 => palette(math.min(d(ro + x) & 0xff, math.max(0, paletteSize - 1)))
            case 16 =>
              val v = u16(d, ro + x * 2)
              val a = if aMask != 0 then channel(v, aMask) else 255
              (a << 24) | (channel(v, rMask) << 16) | (channel(v, gMask) << 8) | channel(v, bMask)
            case 24 => (0xFF << 24) | ((d(ro + x * 3 + 2) & 0xff) << 16) | ((d(ro + x * 3 + 1) & 0xff) << 8) | (d(ro + x * 3) & 0xff)
            case 32 =>
              val v = u32(d, ro + x * 4)
              val a = if aMask != 0 then channel(v, aMask) else (v >>> 24) & 0xff
              if a != 0 then anyAlpha = true
              (a << 24) | (channel(v, rMask) << 16) | (channel(v, gMask) << 8) | channel(v, bMask)
            case _ => fail(s"unsupported bit depth $bpp")
          put(x, y, argb)
          x += 1
        y += 1
      // a 32-bit bitmap whose alpha bytes are all zero is plain RGB with an unused byte
      if bpp == 32 && aMask == 0 && !anyAlpha then
        var k = 3
        while k < out.length do { out(k) = -1; k += 4 }
    Image(width, h, out)

  private def decodeRle(d: Array[Byte], start: Int, width: Int, h: Int, rle8: Boolean, palette: Array[Int], put: (Int, Int, Int) => Unit): Unit =
    // pixels the stream never writes stay transparent
    var x = 0; var y = 0; var p = start
    def color(i: Int) = if i < palette.length then palette(i) else 0xFF000000
    def plot(i: Int): Unit = { if x < width && y < h then put(x, y, color(i)); x += 1 }
    var done = false
    while !done && p + 1 < d.length do
      val n = d(p) & 0xff; val v = d(p + 1) & 0xff
      p += 2
      if n > 0 then
        // encoded run: n pixels of v (RLE8) or alternating nibbles of v (RLE4)
        var k = 0
        while k < n do
          if rle8 then plot(v) else plot(if k % 2 == 0 then v >> 4 else v & 15)
          k += 1
      else v match
        case 0 => x = 0; y += 1
        case 1 => done = true
        case 2 => if p + 1 < d.length then { x += d(p) & 0xff; y += d(p + 1) & 0xff; p += 2 }
        case count =>
          // absolute run of `count` literal pixels, padded to a 16-bit boundary
          if rle8 then
            var k = 0
            while k < count && p + k < d.length do { plot(d(p + k) & 0xff); k += 1 }
            p += count + (count & 1)
          else
            var k = 0
            while k < count && p + k / 2 < d.length do { val b = d(p + k / 2) & 0xff; plot(if k % 2 == 0 then b >> 4 else b & 15); k += 1 }
            val bytes = (count + 1) / 2
            p += bytes + (bytes & 1)
      if y >= h && done then ()

  /** Encodes as a 24-bit bitmap when the image is opaque, else as 32-bit with an alpha channel (BITMAPV4HEADER). */
  def encode(img: Image): Array[Byte] =
    val opaque = (3 until img.rgba.length by 4).forall(i => img.rgba(i) == -1)
    val bytesPer = if opaque then 3 else 4
    val rowBytes = ((img.width * bytesPer + 3) / 4) * 4
    val headerSize = if opaque then 40 else 108
    val dataOffset = 14 + headerSize
    val total = dataOffset + rowBytes * img.height
    val out = new Array[Byte](total)
    def w16(o: Int, v: Int): Unit = { out(o) = v.toByte; out(o + 1) = (v >> 8).toByte }
    def w32(o: Int, v: Int): Unit = { w16(o, v & 0xffff); w16(o + 2, v >>> 16) }
    out(0) = 'B'; out(1) = 'M'; w32(2, total); w32(10, dataOffset)
    w32(14, headerSize); w32(18, img.width); w32(22, img.height); w16(26, 1); w16(28, bytesPer * 8)
    w32(30, if opaque then 0 else 3); w32(34, rowBytes * img.height); w32(38, 2835); w32(42, 2835)
    if !opaque then
      w32(54, 0x00FF0000); w32(58, 0x0000FF00); w32(62, 0x000000FF); w32(66, 0xFF000000)
      w32(70, 0x73524742) // 'sRGB'
    var y = 0
    while y < img.height do
      val src = (img.height - 1 - y) * img.width * 4
      val dst = dataOffset + y * rowBytes
      var x = 0
      while x < img.width do
        val s = src + x * 4; val o = dst + x * bytesPer
        out(o) = img.rgba(s + 2); out(o + 1) = img.rgba(s + 1); out(o + 2) = img.rgba(s)
        if !opaque then out(o + 3) = img.rgba(s + 3)
        x += 1
      y += 1
    out
