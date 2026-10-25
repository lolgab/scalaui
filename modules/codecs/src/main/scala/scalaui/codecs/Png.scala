package scalaui.codecs

import java.io.ByteArrayOutputStream
import scalaui.deflate.{Crc32, Zlib}

/** PNG decoder (all color types, bit depths 1-16, Adam7 interlace, tRNS) and encoder (RGBA8, adaptive filtering). */
object Png:
  private val Signature = Array(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a).map(_.toByte)

  def matches(d: Array[Byte]): Boolean = d.length >= 8 && java.util.Arrays.equals(d.take(8), Signature)

  private def u32(d: Array[Byte], o: Int): Int =
    ((d(o) & 0xff) << 24) | ((d(o + 1) & 0xff) << 16) | ((d(o + 2) & 0xff) << 8) | (d(o + 3) & 0xff)

  def decode(d: Array[Byte]): Image =
    if !matches(d) then throw new ImageFormatException("not a PNG")
    var pos = 8
    var width = 0; var height = 0; var depth = 0; var colorType = 0; var interlace = 0
    var palette = Array.empty[Byte]
    var trns = Array.empty[Byte]
    val idat = new ByteArrayOutputStream()
    var done = false
    while !done && pos + 8 <= d.length do
      val len = u32(d, pos); val typ = new String(d, pos + 4, 4, "ISO-8859-1"); val body = pos + 8
      if len < 0 || body + len + 4 > d.length then throw new ImageFormatException("truncated PNG chunk")
      typ match
        case "IHDR" =>
          width = u32(d, body); height = u32(d, body + 4); depth = d(body + 8) & 0xff
          colorType = d(body + 9) & 0xff; interlace = d(body + 12) & 0xff
        case "PLTE" => palette = java.util.Arrays.copyOfRange(d, body, body + len)
        case "tRNS" => trns = java.util.Arrays.copyOfRange(d, body, body + len)
        case "IDAT" => idat.write(d, body, len)
        case "IEND" => done = true
        case _ => ()
      pos = body + len + 4
    if width <= 0 || height <= 0 then throw new ImageFormatException("missing IHDR")
    val channels = colorType match
      case 0 => 1; case 2 => 3; case 3 => 1; case 4 => 2; case 6 => 4
      case c => throw new ImageFormatException(s"bad PNG color type $c")
    val bitsPerPixel = channels * depth
    val bpp = math.max(1, bitsPerPixel / 8)

    val raw = inflate(idat.toByteArray)
    val out = new Array[Byte](width * height * 4)

    def rowBytes(w: Int) = (w * bitsPerPixel + 7) / 8

    // reads sample `i` (0-based within the row) of channel c from an unfiltered scanline, scaled to 16 bits
    def sample(row: Array[Byte], x: Int, c: Int): Int =
      depth match
        case 8 => (row(x * channels + c) & 0xff) * 257
        case 16 => ((row((x * channels + c) * 2) & 0xff) << 8) | (row((x * channels + c) * 2 + 1) & 0xff)
        case _ =>
          val bitIndex = (x * channels + c) * depth
          val v = ((row(bitIndex >> 3) & 0xff) >> (8 - depth - (bitIndex & 7))) & ((1 << depth) - 1)
          v * 65535 / ((1 << depth) - 1)
    def rawIndex(row: Array[Byte], x: Int): Int = // palette index (not scaled)
      val bitIndex = x * depth
      if depth == 8 then row(x) & 0xff else ((row(bitIndex >> 3) & 0xff) >> (8 - depth - (bitIndex & 7))) & ((1 << depth) - 1)

    val trnsGray = if colorType == 0 && trns.length >= 2 then ((trns(0) & 0xff) << 8) | (trns(1) & 0xff) else -1
    val trnsRgb = if colorType == 2 && trns.length >= 6 then Array.tabulate(3)(i => ((trns(i * 2) & 0xff) << 8) | (trns(i * 2 + 1) & 0xff)) else null

    def put(row: Array[Byte], x: Int, ox: Int, oy: Int): Unit =
      val o = (oy * width + ox) * 4
      def b8(v: Int) = (v >> 8).toByte
      colorType match
        case 0 =>
          val g = sample(row, x, 0)
          // for depth <= 8 compare against the raw sample value as stored in tRNS
          val rawG = if depth == 16 then g else g * ((1 << depth) - 1) / 65535
          out(o) = b8(g); out(o + 1) = b8(g); out(o + 2) = b8(g)
          out(o + 3) = if trnsGray >= 0 && rawG == trnsGray then 0 else -1
        case 2 =>
          val r = sample(row, x, 0); val g = sample(row, x, 1); val b = sample(row, x, 2)
          out(o) = b8(r); out(o + 1) = b8(g); out(o + 2) = b8(b)
          val scale = if depth == 16 then 1 else 257
          out(o + 3) = if trnsRgb != null && r / scale == trnsRgb(0) && g / scale == trnsRgb(1) && b / scale == trnsRgb(2) then 0 else -1
        case 3 =>
          val i = rawIndex(row, x)
          if i * 3 + 2 >= palette.length then throw new ImageFormatException("palette index out of range")
          out(o) = palette(i * 3); out(o + 1) = palette(i * 3 + 1); out(o + 2) = palette(i * 3 + 2)
          out(o + 3) = if i < trns.length then trns(i) else -1
        case 4 =>
          val g = sample(row, x, 0); out(o) = b8(g); out(o + 1) = b8(g); out(o + 2) = b8(g); out(o + 3) = b8(sample(row, x, 1))
        case 6 =>
          out(o) = b8(sample(row, x, 0)); out(o + 1) = b8(sample(row, x, 1)); out(o + 2) = b8(sample(row, x, 2)); out(o + 3) = b8(sample(row, x, 3))

    var offset = 0
    /** Unfilters a (sub)image of `w x h` stored at `offset`, calling `pixel(row, x, y)` per pixel. */
    def pass(w: Int, h: Int)(emit: (Array[Byte], Int, Int) => Unit): Unit =
      if w > 0 && h > 0 then
        val rb = rowBytes(w)
        var prev = new Array[Byte](rb)
        var y = 0
        while y < h do
          if offset + 1 + rb > raw.length then throw new ImageFormatException("truncated PNG image data")
          val filter = raw(offset) & 0xff
          val cur = java.util.Arrays.copyOfRange(raw, offset + 1, offset + 1 + rb)
          offset += 1 + rb
          unfilter(filter, cur, prev, bpp)
          var x = 0
          while x < w do { emit(cur, x, y); x += 1 }
          prev = cur; y += 1

    if interlace == 0 then pass(width, height)((row, x, y) => put(row, x, x, y))
    else
      val xs = Array(0, 4, 0, 2, 0, 1, 0); val ys = Array(0, 0, 4, 0, 2, 0, 1)
      val dx = Array(8, 8, 4, 4, 2, 2, 1); val dy = Array(8, 8, 8, 4, 4, 2, 2)
      for p <- 0 until 7 do
        val pw = (width - xs(p) + dx(p) - 1) / dx(p); val ph = (height - ys(p) + dy(p) - 1) / dy(p)
        pass(pw, ph)((row, x, y) => put(row, x, xs(p) + x * dx(p), ys(p) + y * dy(p)))
    Image(width, height, out)

  private def inflate(data: Array[Byte]): Array[Byte] =
    try Zlib.decompress(data)
    catch case e: scalaui.deflate.DataFormatException => throw new ImageFormatException(s"corrupt PNG zlib stream: ${e.getMessage}")

  private def unfilter(filter: Int, cur: Array[Byte], prev: Array[Byte], bpp: Int): Unit =
    var i = 0
    val n = cur.length
    filter match
      case 0 => ()
      case 1 => i = bpp; while i < n do { cur(i) = (cur(i) + cur(i - bpp)).toByte; i += 1 }
      case 2 => while i < n do { cur(i) = (cur(i) + prev(i)).toByte; i += 1 }
      case 3 =>
        while i < n do
          val left = if i >= bpp then cur(i - bpp) & 0xff else 0
          cur(i) = (cur(i) + ((left + (prev(i) & 0xff)) >> 1)).toByte; i += 1
      case 4 =>
        while i < n do
          val a = if i >= bpp then cur(i - bpp) & 0xff else 0
          val b = prev(i) & 0xff
          val c = if i >= bpp then prev(i - bpp) & 0xff else 0
          cur(i) = (cur(i) + paeth(a, b, c)).toByte; i += 1
      case f => throw new ImageFormatException(s"bad PNG filter $f")

  private def paeth(a: Int, b: Int, c: Int): Int =
    val p = a + b - c
    val pa = math.abs(p - a); val pb = math.abs(p - b); val pc = math.abs(p - c)
    if pa <= pb && pa <= pc then a else if pb <= pc then b else c

  // --- encoder ---

  /** Encodes as 8-bit RGBA (or RGB when fully opaque) with adaptive per-row filtering. */
  def encode(img: Image, level: Int = 6): Array[Byte] =
    val opaque = (0 until img.width * img.height).forall(i => img.rgba(i * 4 + 3) == -1)
    val ch = if opaque then 3 else 4
    val rb = img.width * ch
    val raw = new Array[Byte]((rb + 1) * img.height)
    var prev = new Array[Byte](rb)
    for y <- 0 until img.height do
      val cur = new Array[Byte](rb)
      for x <- 0 until img.width; c <- 0 until ch do cur(x * ch + c) = img.rgba((y * img.width + x) * 4 + c)
      // try the five filters, keep the one with the smallest sum of absolute (signed) values
      var best = Array.emptyByteArray; var bestF = 0; var bestScore = Long.MaxValue
      for f <- 0 to 4 do
        val out = new Array[Byte](rb)
        var i = 0; var score = 0L
        while i < rb do
          val a = if i >= ch then cur(i - ch) & 0xff else 0
          val b = prev(i) & 0xff
          val c = if i >= ch then prev(i - ch) & 0xff else 0
          val pred = f match { case 0 => 0; case 1 => a; case 2 => b; case 3 => (a + b) >> 1; case _ => paeth(a, b, c) }
          val v = ((cur(i) & 0xff) - pred).toByte
          out(i) = v; score += math.abs(v.toInt); i += 1
        if score < bestScore then { bestScore = score; best = out; bestF = f }
      raw(y * (rb + 1)) = bestF.toByte
      System.arraycopy(best, 0, raw, y * (rb + 1) + 1, rb)
      prev = cur

    val z = Zlib.compress(raw, level)

    val out = new ByteArrayOutputStream()
    out.write(Signature)
    def chunk(typ: String, body: Array[Byte]): Unit =
      val len = body.length
      out.write(Array((len >>> 24).toByte, (len >>> 16).toByte, (len >>> 8).toByte, len.toByte))
      val t = typ.getBytes("ISO-8859-1")
      out.write(t); out.write(body)
      val c = Crc32(t ++ body)
      out.write(Array((c >>> 24).toByte, (c >>> 16).toByte, (c >>> 8).toByte, c.toByte))
    val ihdr = new Array[Byte](13)
    def put32(o: Int, v: Int): Unit = { ihdr(o) = (v >>> 24).toByte; ihdr(o + 1) = (v >>> 16).toByte; ihdr(o + 2) = (v >>> 8).toByte; ihdr(o + 3) = v.toByte }
    put32(0, img.width); put32(4, img.height); ihdr(8) = 8; ihdr(9) = if opaque then 2 else 6
    chunk("IHDR", ihdr); chunk("IDAT", z); chunk("IEND", Array.emptyByteArray)
    out.toByteArray
