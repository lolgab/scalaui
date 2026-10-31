package scalaui.codecs

import java.io.ByteArrayOutputStream
import scalaui.deflate.Zlib

/** Baseline TIFF: little/big endian, strips and tiles, chunky and planar, 1/2/4/8/16-bit gray, palette and RGB(A)/CMYK,
  * compression None/LZW/PackBits/Deflate with the horizontal predictor. Reads the first page; writes RGBA, optionally
  * deflate-compressed.
  */
object Tiff:
  def matches(d: Array[Byte]): Boolean =
    d.length >= 8 && ((d(0) == 'I' && d(1) == 'I' && d(2) == 42 && d(3) == 0) || (d(0) == 'M' && d(1) == 'M' && d(2) == 0 && d(3) == 42))

  private def fail(msg: String): Nothing = throw new ImageFormatException("TIFF: " + msg)

  private final class Reader(val d: Array[Byte], val le: Boolean):
    def u16(o: Int): Int = if le then (d(o) & 0xff) | ((d(o + 1) & 0xff) << 8) else ((d(o) & 0xff) << 8) | (d(o + 1) & 0xff)
    def u32(o: Int): Long = if le then (u16(o) | (u16(o + 2) << 16)).toLong & 0xffffffffL else ((u16(o).toLong << 16) | u16(o + 2))

  private final case class Entry(tag: Int, tpe: Int, count: Int, valueOffset: Int)

  def decode(d: Array[Byte]): Image =
    if !matches(d) then fail("not a TIFF")
    val r = new Reader(d, d(0) == 'I')
    val ifd = r.u32(4).toInt
    if ifd < 8 || ifd + 2 > d.length then fail("bad IFD offset")
    val n = r.u16(ifd)
    val entries = (0 until n).map { i => val o = ifd + 2 + i * 12; Entry(r.u16(o), r.u16(o + 2), r.u32(o + 4).toInt, o + 8) }.map(e => e.tag -> e).toMap

    def typeSize(t: Int) = t match { case 1 | 2 | 6 | 7 => 1; case 3 | 8 => 2; case 4 | 9 | 11 => 4; case 5 | 10 | 12 => 8; case _ => 1 }
    def values(tag: Int): Array[Long] = entries.get(tag) match
      case None => Array.empty
      case Some(e) =>
        val sz = typeSize(e.tpe)
        val base = if sz * e.count <= 4 then e.valueOffset else r.u32(e.valueOffset).toInt
        Array.tabulate(e.count)(i => if sz == 1 then (d(base + i) & 0xff).toLong else if sz == 2 then r.u16(base + i * 2).toLong else r.u32(base + i * 4))
    def one(tag: Int, default: Int): Int = values(tag).headOption.map(_.toInt).getOrElse(default)

    val width = one(256, 0); val height = one(257, 0)
    if width <= 0 || height <= 0 || width > 65536 || height > 65536 then fail("bad dimensions")
    val spp = one(277, 1)
    val bits = values(258).map(_.toInt) match { case a if a.isEmpty => Array.fill(spp)(1); case a => a }
    val bps = bits(0)
    val compression = one(259, 1)
    val photometric = one(262, 1)
    val planar = one(284, 1)
    val predictor = one(317, 1)
    val extra = values(338).map(_.toInt)
    val colorMap = values(320)
    val sampleFormat = one(339, 1)
    if sampleFormat != 1 && !(sampleFormat == 2 && bps == 8) then fail("only unsigned integer samples are supported")
    if !Set(1, 2, 4, 8, 16).contains(bps) then fail(s"unsupported bits per sample $bps")
    if photometric == 6 || compression == 7 || compression == 6 then fail("JPEG-compressed or YCbCr TIFFs are not supported")

    val tiled = entries.contains(322)
    val (tileW, tileH) = if tiled then (one(322, width), one(323, height)) else (width, math.min(height, one(278, height)))
    val offsets = (if tiled then values(324) else values(273)).map(_.toInt)
    val counts = (if tiled then values(325) else values(279)).map(_.toInt)
    val planes = if planar == 2 then spp else 1
    val samplesPerPlaneUnit = if planar == 2 then 1 else spp
    val tilesAcross = (width + tileW - 1) / tileW
    val tilesDown = (height + tileH - 1) / tileH
    val unitsPerPlane = tilesAcross * tilesDown
    // decoded samples, row-major, `spp` interleaved, expanded to 16 bits: (width * height * spp)
    val samples = new Array[Int](width * height * spp)

    def decompress(data: Array[Byte], expected: Int): Array[Byte] = compression match
      case 1 => data
      case 5 => lzw(data, expected)
      case 8 | 32946 => Zlib.decompress(data)
      case 32773 => packBits(data, expected)
      case c => fail(s"unsupported compression $c")

    var unit = 0
    while unit < planes * unitsPerPlane do
      val plane = unit / unitsPerPlane; val within = unit % unitsPerPlane
      val tx = (within % tilesAcross) * tileW; val ty = (within / tilesAcross) * tileH
      val uw = if tiled then tileW else width
      val uh = if tiled then tileH else math.min(tileH, height - ty)
      val rowBytes = (uw * samplesPerPlaneUnit * bps + 7) / 8
      if unit < offsets.length && unit < counts.length then
        val raw = java.util.Arrays.copyOfRange(d, offsets(unit), math.min(d.length, offsets(unit) + counts(unit)))
        val bytes = decompress(raw, rowBytes * uh)
        // horizontal predictor: undo differencing per row
        if predictor == 2 then
          var row = 0
          while row < uh do
            val ro = row * rowBytes
            if bps == 8 then
              var i = samplesPerPlaneUnit
              while i < uw * samplesPerPlaneUnit && ro + i < bytes.length do { bytes(ro + i) = (bytes(ro + i) + bytes(ro + i - samplesPerPlaneUnit)).toByte; i += 1 }
            else if bps == 16 then
              var i = samplesPerPlaneUnit
              while i < uw * samplesPerPlaneUnit && ro + i * 2 + 1 < bytes.length do
                val cur = if r.le then (bytes(ro + i * 2) & 0xff) | ((bytes(ro + i * 2 + 1) & 0xff) << 8) else ((bytes(ro + i * 2) & 0xff) << 8) | (bytes(ro + i * 2 + 1) & 0xff)
                val pr = ro + (i - samplesPerPlaneUnit) * 2
                val prev = if r.le then (bytes(pr) & 0xff) | ((bytes(pr + 1) & 0xff) << 8) else ((bytes(pr) & 0xff) << 8) | (bytes(pr + 1) & 0xff)
                val v = (cur + prev) & 0xffff
                if r.le then { bytes(ro + i * 2) = v.toByte; bytes(ro + i * 2 + 1) = (v >> 8).toByte } else { bytes(ro + i * 2) = (v >> 8).toByte; bytes(ro + i * 2 + 1) = v.toByte }
                i += 1
            row += 1
        var row = 0
        while row < uh && ty + row < height do
          var col = 0
          while col < uw && tx + col < width do
            var s = 0
            while s < samplesPerPlaneUnit do
              val sampleIndex = col * samplesPerPlaneUnit + s
              val v: Int = bps match
                case 8 => val o = row * rowBytes + sampleIndex; if o < bytes.length then bytes(o) & 0xff else 0
                case 16 =>
                  val o = row * rowBytes + sampleIndex * 2
                  if o + 1 < bytes.length then (if r.le then (bytes(o) & 0xff) | ((bytes(o + 1) & 0xff) << 8) else ((bytes(o) & 0xff) << 8) | (bytes(o + 1) & 0xff)) else 0
                case b =>
                  val bitPos = sampleIndex * b
                  val o = row * rowBytes + bitPos / 8
                  if o < bytes.length then ((bytes(o) & 0xff) >> (8 - b - bitPos % 8)) & ((1 << b) - 1) else 0
              val sIdx = if planar == 2 then plane else s
              samples(((ty + row) * width + tx + col) * spp + sIdx) = v
              s += 1
            col += 1
          row += 1
      unit += 1

    // convert to 8-bit RGBA
    val maxV = (1 << bps) - 1
    def to8(v: Int): Int = if bps == 8 then v else if bps == 16 then (v * 255 + 32767) / 65535 else (v * 255 + maxV / 2) / maxV
    val out = new Array[Byte](width * height * 4)
    val alphaIdx = photometric match
      case 2 | 5 if spp > (if photometric == 5 then 4 else 3) => spp - 1
      case 0 | 1 | 3 if spp > 1 => 1
      case _ => -1
    val alphaPremul = extra.headOption.contains(1)
    var px = 0
    while px < width * height do
      val b = px * spp
      var red = 0; var green = 0; var blue = 0
      photometric match
        case 0 | 1 =>
          val g = to8(samples(b)); val gg = if photometric == 0 then 255 - g else g
          red = gg; green = gg; blue = gg
        case 3 =>
          val idx = samples(b)
          val entries = colorMap.length / 3
          red = if idx < entries then (colorMap(idx) >> 8).toInt & 0xff else 0
          green = if idx < entries then (colorMap(entries + idx) >> 8).toInt & 0xff else 0
          blue = if idx < entries then (colorMap(2 * entries + idx) >> 8).toInt & 0xff else 0
        case 2 => red = to8(samples(b)); green = to8(samples(b + 1)); blue = to8(samples(b + 2))
        case 5 =>
          val c = to8(samples(b)); val m = to8(samples(b + 1)); val y = to8(samples(b + 2)); val k = to8(samples(b + 3))
          red = (255 - c) * (255 - k) / 255; green = (255 - m) * (255 - k) / 255; blue = (255 - y) * (255 - k) / 255
        case p => fail(s"unsupported photometric interpretation $p")
      var a = if alphaIdx >= 0 then to8(samples(b + alphaIdx)) else 255
      if alphaPremul && a > 0 && a < 255 then
        red = math.min(255, (red * 255 + a / 2) / a); green = math.min(255, (green * 255 + a / 2) / a); blue = math.min(255, (blue * 255 + a / 2) / a)
      out(px * 4) = red.toByte; out(px * 4 + 1) = green.toByte; out(px * 4 + 2) = blue.toByte; out(px * 4 + 3) = a.toByte
      px += 1
    Image(width, height, out)

  private def packBits(data: Array[Byte], expected: Int): Array[Byte] =
    val out = new ByteArrayOutputStream(expected)
    var p = 0
    while p < data.length && out.size < expected do
      val n = data(p); p += 1
      if n >= 0 then
        val len = n + 1
        out.write(data, p, math.min(len, data.length - p)); p += len
      else if n != -128 then
        val len = 1 - n
        if p < data.length then { val b = data(p); p += 1; var i = 0; while i < len do { out.write(b); i += 1 } }
    out.toByteArray

  /** TIFF LZW: MSB-first codes of 9-12 bits, clear 256, end 257, "early change". */
  private def lzw(data: Array[Byte], expected: Int): Array[Byte] =
    val out = new ByteArrayOutputStream(expected)
    val prefix = new Array[Int](4096); val suffix = new Array[Byte](4096)
    var next = 258; var size = 9; var prev = -1
    var bitBuf = 0L; var bitCnt = 0; var p = 0
    val stack = new Array[Byte](4097)
    var i = 0
    while i < 256 do { suffix(i) = i.toByte; prefix(i) = -1; i += 1 }
    def expand(code: Int): Int =
      var sp = 0; var c = code
      while c >= 0 && sp < 4096 do { stack(sp) = suffix(c); sp += 1; c = prefix(c) }
      sp
    var done = false
    while !done && out.size < expected do
      while bitCnt < size && p < data.length do { bitBuf = (bitBuf << 8) | (data(p) & 0xff); bitCnt += 8; p += 1 }
      if bitCnt < size then done = true
      else
        val code = ((bitBuf >> (bitCnt - size)) & ((1 << size) - 1)).toInt
        bitCnt -= size
        if code == 256 then { next = 258; size = 9; prev = -1 }
        else if code == 257 then done = true
        else if code < next || (code == next && prev >= 0) then
          var len = 0; var first: Byte = 0
          if code < next then { len = expand(code); first = stack(len - 1) }
          else
            val pl = expand(prev); first = stack(pl - 1)
            var k = pl
            while k > 0 do { stack(k) = stack(k - 1); k -= 1 }
            stack(0) = first; len = pl + 1
          var k = len - 1
          while k >= 0 do { out.write(stack(k)); k -= 1 }
          if prev >= 0 && next < 4096 then
            prefix(next) = prev; suffix(next) = first; next += 1
            if next + 1 == (1 << size) && size < 12 then size += 1 // early change
          prev = code
        else done = true
    out.toByteArray

  // ---- encoding ----

  /** Writes a little-endian TIFF with 8-bit RGBA (or RGB if opaque) in strips; `deflate` adds zlib compression with the predictor. */
  def encode(img: Image, deflate: Boolean = false): Array[Byte] =
    val opaque = (3 until img.rgba.length by 4).forall(i => img.rgba(i) == -1)
    val spp = if opaque then 3 else 4
    val rowBytes = img.width * spp
    val rowsPerStrip = math.max(1, math.min(img.height, 16384 / math.max(1, rowBytes)))
    val strips = (0 until img.height by rowsPerStrip).map { y0 =>
      val rows = math.min(rowsPerStrip, img.height - y0)
      val raw = new Array[Byte](rows * rowBytes)
      var y = 0
      while y < rows do
        var x = 0
        while x < img.width do
          var c = 0
          while c < spp do
            val v = img.rgba(((y0 + y) * img.width + x) * 4 + c)
            raw(y * rowBytes + x * spp + c) = if deflate && x > 0 then (v - img.rgba(((y0 + y) * img.width + x - 1) * 4 + c)).toByte else v
            c += 1
          x += 1
        y += 1
      if deflate then Zlib.compress(raw) else raw
    }
    val out = new ByteArrayOutputStream
    def w16(v: Int): Unit = { out.write(v & 0xff); out.write((v >> 8) & 0xff) }
    def w32(v: Int): Unit = { w16(v & 0xffff); w16(v >>> 16) }
    out.write('I'); out.write('I'); w16(42); w32(8)
    val entryCount = if opaque then 11 else 12 + 0
    // layout: header(8) | IFD | bits array | strip offsets | strip counts | strip data (single strips are stored inline)
    val multi = strips.size > 1
    val ifdSize = 2 + entryCount * 12 + 4
    val bitsOffset = 8 + ifdSize
    val offsetsOffset = bitsOffset + spp * 2
    val countsOffset = offsetsOffset + (if multi then strips.size * 4 else 0)
    var dataOffset = countsOffset + (if multi then strips.size * 4 else 0)
    val stripOffsets = strips.map { s => val o = dataOffset; dataOffset += s.length; o }
    def entry(tag: Int, tpe: Int, count: Int, value: Int): Unit = { w16(tag); w16(tpe); w32(count); w32(value) }
    w16(entryCount)
    entry(256, 4, 1, img.width); entry(257, 4, 1, img.height)
    entry(258, 3, spp, bitsOffset)
    entry(259, 3, 1, if deflate then 8 else 1)
    entry(262, 3, 1, 2)
    entry(273, 4, strips.size, if multi then offsetsOffset else stripOffsets.head)
    entry(277, 3, 1, spp)
    entry(278, 4, 1, rowsPerStrip)
    entry(279, 4, strips.size, if multi then countsOffset else strips.head.length)
    entry(284, 3, 1, 1)
    if deflate then entry(317, 3, 1, 2) else entry(296, 3, 1, 2)
    if !opaque then entry(338, 3, 1, 2) // unassociated alpha
    w32(0)
    (0 until spp).foreach(_ => w16(8))
    if multi then { stripOffsets.foreach(w32); strips.foreach(s => w32(s.length)) }
    strips.foreach(s => out.write(s))
    out.toByteArray
