package scalaui.codecs

import java.io.ByteArrayOutputStream

/** Baseline sequential JPEG encoder: JFIF, 8-bit, standard Huffman tables, quality-scaled quantization,
  * 4:2:0 (default) or 4:4:4 chroma, or grayscale. Alpha is composited onto `background`.
  */
object JpegEncoder:
  private val Zigzag = Array(0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5, 12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21, 28,
    35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51, 58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61, 54, 47, 55, 62, 63)
  private val LumaQ = Array(16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55, 14, 13, 16, 24, 40, 57, 69, 56, 14, 17, 22, 29, 51, 87, 80, 62,
    18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92, 49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99)
  private val ChromaQ = Array(17, 18, 24, 47, 99, 99, 99, 99, 18, 21, 26, 66, 99, 99, 99, 99, 24, 26, 56, 99, 99, 99, 99, 99, 47, 66, 99, 99, 99, 99, 99, 99,
    99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99, 99)

  // Annex K.3 standard Huffman tables: code length counts, then symbols
  private val DcLumaBits = Array(0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0); private val DcLumaVals = (0 to 11).toArray
  private val DcChromaBits = Array(0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0); private val DcChromaVals = (0 to 11).toArray
  private val AcLumaBits = Array(0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7d)
  private val AcLumaVals = Array(0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07, 0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xa1, 0x08,
    0x23, 0x42, 0xb1, 0xc1, 0x15, 0x52, 0xd1, 0xf0, 0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0a, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x25, 0x26, 0x27, 0x28, 0x29, 0x2a, 0x34, 0x35, 0x36,
    0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6a, 0x73,
    0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89, 0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6,
    0xa7, 0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3, 0xc4, 0xc5, 0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8,
    0xd9, 0xda, 0xe1, 0xe2, 0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf1, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8, 0xf9, 0xfa)
  private val AcChromaBits = Array(0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77)
  private val AcChromaVals = Array(0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21, 0x31, 0x06, 0x12, 0x41, 0x51, 0x07, 0x61, 0x71, 0x13, 0x22, 0x32, 0x81, 0x08, 0x14, 0x42, 0x91,
    0xa1, 0xb1, 0xc1, 0x09, 0x23, 0x33, 0x52, 0xf0, 0x15, 0x62, 0x72, 0xd1, 0x0a, 0x16, 0x24, 0x34, 0xe1, 0x25, 0xf1, 0x17, 0x18, 0x19, 0x1a, 0x26, 0x27, 0x28, 0x29, 0x2a, 0x35,
    0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49, 0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69, 0x6a,
    0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89, 0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4,
    0xa5, 0xa6, 0xa7, 0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3, 0xc4, 0xc5, 0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6,
    0xd7, 0xd8, 0xd9, 0xda, 0xe2, 0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8, 0xf9, 0xfa)

  /** Code and length per symbol from the standard (counts, symbols) description. */
  private def codes(bits: Array[Int], vals: Array[Int]): (Array[Int], Array[Int]) =
    val code = new Array[Int](256); val size = new Array[Int](256)
    var c = 0; var k = 0
    for len <- 1 to 16 do
      for _ <- 0 until bits(len - 1) do { code(vals(k)) = c; size(vals(k)) = len; c += 1; k += 1 }
      c <<= 1
    (code, size)

  private final class BitWriter(out: ByteArrayOutputStream):
    private var acc = 0; private var cnt = 0
    def put(code: Int, len: Int): Unit =
      acc = (acc << len) | (code & ((1 << len) - 1)); cnt += len
      while cnt >= 8 do
        val b = (acc >> (cnt - 8)) & 0xff
        out.write(b); if b == 0xff then out.write(0)
        cnt -= 8
      acc &= (1 << cnt) - 1
    def flush(): Unit = if cnt > 0 then put(0x7f, 8 - cnt)

  /** @param quality 1..100 (libjpeg's scaling), @param subsample 4:2:0 chroma when true, @param background used where alpha < 255 */
  def encode(img: Image, quality: Int = 85, subsample: Boolean = true, grayscale: Boolean = false, background: Int = 0xFFFFFF): Array[Byte] =
    val q = math.max(1, math.min(100, quality))
    val scale = if q < 50 then 5000 / q else 200 - 2 * q
    def table(base: Array[Int]) = base.map(v => math.max(1, math.min(255, (v * scale + 50) / 100)))
    val qL = table(LumaQ); val qC = table(ChromaQ)
    val w = img.width; val h = img.height
    // planes with alpha composited, converted to YCbCr (JFIF)
    val Y = new Array[Float](w * h); val Cb = new Array[Float](w * h); val Cr = new Array[Float](w * h)
    val br = (background >> 16) & 0xff; val bg = (background >> 8) & 0xff; val bb = background & 0xff
    var i = 0
    while i < w * h do
      val a = img.rgba(i * 4 + 3) & 0xff
      def mix(c: Int, b: Int) = ((c & 0xff) * a + b * (255 - a)) / 255.0f
      val r = mix(img.rgba(i * 4), br); val g = mix(img.rgba(i * 4 + 1), bg); val b = mix(img.rgba(i * 4 + 2), bb)
      Y(i) = 0.299f * r + 0.587f * g + 0.114f * b
      Cb(i) = -0.168736f * r - 0.331264f * g + 0.5f * b + 128f
      Cr(i) = 0.5f * r - 0.418688f * g - 0.081312f * b + 128f
      i += 1
    val out = new ByteArrayOutputStream
    def w16(v: Int): Unit = { out.write(v >> 8); out.write(v & 0xff) }
    out.write(0xFF); out.write(0xD8)
    out.write(0xFF); out.write(0xE0); w16(16); out.write("JFIF".getBytes); out.write(0); out.write(1); out.write(1); out.write(0); w16(1); w16(1); out.write(0); out.write(0)
    def dqt(id: Int, t: Array[Int]): Unit = { out.write(0xFF); out.write(0xDB); w16(67); out.write(id); Zigzag.foreach(z => out.write(t(z))) }
    dqt(0, qL); if !grayscale then dqt(1, qC)
    val comps = if grayscale then 1 else 3
    val hv = if subsample && !grayscale then 2 else 1
    out.write(0xFF); out.write(0xC0); w16(8 + 3 * comps); out.write(8); w16(h); w16(w); out.write(comps)
    out.write(1); out.write((hv << 4) | hv); out.write(0)
    if !grayscale then { out.write(2); out.write(0x11); out.write(1); out.write(3); out.write(0x11); out.write(1) }
    def dht(cls: Int, id: Int, bits: Array[Int], vals: Array[Int]): Unit =
      out.write(0xFF); out.write(0xC4); w16(19 + vals.length); out.write((cls << 4) | id); bits.foreach(out.write(_)); vals.foreach(out.write(_))
    dht(0, 0, DcLumaBits, DcLumaVals); dht(1, 0, AcLumaBits, AcLumaVals)
    if !grayscale then { dht(0, 1, DcChromaBits, DcChromaVals); dht(1, 1, AcChromaBits, AcChromaVals) }
    out.write(0xFF); out.write(0xDA); w16(6 + 2 * comps); out.write(comps)
    out.write(1); out.write(0x00)
    if !grayscale then { out.write(2); out.write(0x11); out.write(3); out.write(0x11) }
    out.write(0); out.write(63); out.write(0)

    val (dcLC, dcLS) = codes(DcLumaBits, DcLumaVals); val (acLC, acLS) = codes(AcLumaBits, AcLumaVals)
    val (dcCC, dcCS) = codes(DcChromaBits, DcChromaVals); val (acCC, acCS) = codes(AcChromaBits, AcChromaVals)
    val bw = new BitWriter(out)
    val cosT = new Array[Float](64) // cosT(x * 8 + u)
    for x <- 0 until 8; u <- 0 until 8 do cosT(x * 8 + u) = math.cos((2 * x + 1) * u * math.Pi / 16).toFloat
    // scratch buffers: each block is transformed and written before the next one is started
    val fTmp = new Array[Float](64); val fRes = new Array[Int](64); val fBlk = new Array[Float](64)
    def fdct(block: Array[Float], quant: Array[Int]): Array[Int] =
      val tmp = fTmp; val res = fRes
      var y = 0
      while y < 8 do
        var u = 0
        while u < 8 do
          var s = 0.0f; var x = 0
          while x < 8 do { s += block(y * 8 + x) * cosT(x * 8 + u); x += 1 }
          tmp(y * 8 + u) = s
          u += 1
        y += 1
      var u = 0
      while u < 8 do
        var v = 0
        while v < 8 do
          var s = 0.0f; var yy = 0
          while yy < 8 do { s += tmp(yy * 8 + u) * cosT(yy * 8 + v); yy += 1 }
          val cu = if u == 0 then 0.70710678f else 1f; val cv = if v == 0 then 0.70710678f else 1f
          val coef = 0.25f * cu * cv * s
          res(v * 8 + u) = math.round(coef / quant(v * 8 + u))
          v += 1
        u += 1
      res
    def nbits(v: Int): Int = if v == 0 then 0 else 32 - Integer.numberOfLeadingZeros(math.abs(v))
    def writeBlock(coef: Array[Int], prevDc: Int, dcC: Array[Int], dcS: Array[Int], acC: Array[Int], acS: Array[Int]): Int =
      val diff = coef(0) - prevDc
      val nb = nbits(diff)
      bw.put(dcC(nb), dcS(nb))
      if nb > 0 then bw.put(if diff < 0 then diff - 1 else diff, nb)
      var run = 0
      var k = 1
      while k < 64 do
        val v = coef(Zigzag(k))
        if v == 0 then run += 1
        else
          while run > 15 do { bw.put(acC(0xF0), acS(0xF0)); run -= 16 }
          val n = nbits(v)
          val sym = (run << 4) | n
          bw.put(acC(sym), acS(sym))
          bw.put(if v < 0 then v - 1 else v, n)
          run = 0
        k += 1
      if run > 0 then bw.put(acC(0), acS(0))
      coef(0)
    def sample(plane: Array[Float], px: Int, py: Int, size: Int): Array[Float] =
      // size 1: direct; size 2: average of 2x2 (chroma subsampling)
      val b = fBlk
      var y = 0
      while y < 8 do
        var x = 0
        while x < 8 do
          if size == 1 then
            val sx = math.min(w - 1, px + x); val sy = math.min(h - 1, py + y)
            b(y * 8 + x) = plane(sy * w + sx) - 128f
          else
            var s = 0f
            var dy = 0
            while dy < 2 do
              val row = math.min(h - 1, py + y * 2 + dy) * w
              s += plane(row + math.min(w - 1, px + x * 2))
              s += plane(row + math.min(w - 1, px + x * 2 + 1))
              dy += 1
            b(y * 8 + x) = s / 4 - 128f
          x += 1
        y += 1
      b
    val mcu = 8 * hv
    var prevY = 0; var prevCb = 0; var prevCr = 0
    var my = 0
    while my < h do
      var mx = 0
      while mx < w do
        var by = 0
        while by < hv do
          var bx = 0
          while bx < hv do
            prevY = writeBlock(fdct(sample(Y, mx + bx * 8, my + by * 8, 1), qL), prevY, dcLC, dcLS, acLC, acLS)
            bx += 1
          by += 1
        if !grayscale then
          val sz = if hv == 2 then 2 else 1
          prevCb = writeBlock(fdct(sample(Cb, mx, my, sz), qC), prevCb, dcCC, dcCS, acCC, acCS)
          prevCr = writeBlock(fdct(sample(Cr, mx, my, sz), qC), prevCr, dcCC, dcCS, acCC, acCS)
        mx += mcu
      my += mcu
    bw.flush()
    out.write(0xFF); out.write(0xD9)
    out.toByteArray
