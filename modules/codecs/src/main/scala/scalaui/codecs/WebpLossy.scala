package scalaui.codecs

/** The WebP lossy (VP8 key frame) decoder: a Scala port of libwebp's decoding logic (BSD licence, Copyright Google Inc.).
  * Output matches libwebp bit for bit, including the in-loop filter and the fancy chroma upsampler.
  */
private[codecs] object WebpLossy:
  private def fail(msg: String): Nothing = throw new ImageFormatException("WebP lossy: " + msg)

  /** A decoded frame in planar YUV 4:2:0, padded to whole macroblocks. */
  final class Frame(val width: Int, val height: Int, val y: Array[Byte], val u: Array[Byte], val v: Array[Byte], val yStride: Int, val uvStride: Int)

  // ---- boolean entropy decoder (RFC 6386 section 7) ----
  private final class Bool(d: Array[Byte], start: Int, end: Int):
    private var pos = start
    private var value = 0
    private var range = 255
    private var bitCount = 0
    private var overrun = 0
    private def next(): Int = if pos < end then { val b = d(pos) & 0xff; pos += 1; b } else { overrun += 1; if overrun > 64 then fail("truncated data"); 0 }
    value = (next() << 8) | next()

    def bit(prob: Int): Int =
      val split = 1 + (((range - 1) * prob) >> 8)
      val big = split << 8
      var r = 0
      if value >= big then { r = 1; range -= split; value -= big } else range = split
      while range < 128 do
        value <<= 1; range <<= 1; bitCount += 1
        if bitCount == 8 then { bitCount = 0; value |= next() }
      r
    def flag(): Boolean = bit(128) == 1
    def literal(n: Int): Int = { var v = 0; var i = 0; while i < n do { v = (v << 1) | bit(128); i += 1 }; v }
    def signed(n: Int): Int = { val v = literal(n); if flag() then -v else v }

  // ---- constants ----
  private val Bps = 32
  private val YOff = Bps * 1 + 8
  private val UOff = YOff + Bps * 16 + Bps
  private val VOff = UOff + 16
  private val Zigzag = Array(0, 1, 4, 8, 5, 2, 3, 6, 9, 12, 13, 10, 7, 11, 14, 15)
  private val Bands = Array(0, 1, 2, 3, 6, 4, 5, 6, 6, 6, 6, 6, 6, 6, 6, 7, 0)
  private val Cat3 = Array(173, 148, 140); private val Cat4 = Array(176, 155, 140, 135)
  private val Cat5 = Array(180, 157, 141, 134, 130); private val Cat6 = Array(254, 254, 243, 230, 196, 177, 153, 140, 133, 130, 129)
  private val Cats = Array(Cat3, Cat4, Cat5, Cat6)
  // 4x4 prediction mode numbering (B_*) shared with the 16x16 / chroma modes
  private val BDc = 0; private val BTm = 1; private val BVe = 2; private val BHe = 3; private val BRd = 4; private val BVr = 5
  private val BLd = 6; private val BVl = 7; private val BHd = 8; private val BHu = 9
  private val DcNoTop = 4; private val DcNoLeft = 5; private val DcNoTopLeft = 6

  private def clip255(v: Int) = if v < 0 then 0 else if v > 255 then 255 else v
  private def sclip1(v: Int) = if v < -128 then -128 else if v > 127 then 127 else v
  private def sclip2(v: Int) = if v < -16 then -16 else if v > 15 then 15 else v

  private final class Quant(val y1: Array[Int], val y2: Array[Int], val uv: Array[Int])
  private final class FInfo(var limit: Int, var ilevel: Int, var inner: Boolean, var hevThresh: Int)

  def decode(d: Array[Byte], start: Int, end: Int): Frame =
    if end - start < 10 then fail("truncated header")
    val tag = (d(start) & 0xff) | ((d(start + 1) & 0xff) << 8) | ((d(start + 2) & 0xff) << 16)
    if (tag & 1) != 0 then fail("not a key frame")
    if ((tag >> 1) & 7) > 3 then fail("unknown profile")
    if ((tag >> 4) & 1) == 0 then fail("frame not displayable")
    val partLen = tag >> 5
    if (d(start + 3) & 0xff) != 0x9d || (d(start + 4) & 0xff) != 0x01 || (d(start + 5) & 0xff) != 0x2a then fail("bad start code")
    val width = ((d(start + 6) & 0xff) | ((d(start + 7) & 0xff) << 8)) & 0x3fff
    val height = ((d(start + 8) & 0xff) | ((d(start + 9) & 0xff) << 8)) & 0x3fff
    if width == 0 || height == 0 then fail("zero dimension")
    val mbW = (width + 15) >> 4; val mbH = (height + 15) >> 4
    val p0 = start + 10
    if p0 + partLen > end then fail("bad first partition size")
    val br = new Bool(d, p0, p0 + partLen)

    br.flag(); br.flag() // colour space, clamp type (both ignored)
    // segment header
    var useSegment = false; var updateMap = false; var absoluteDelta = true
    val segQuant = new Array[Int](4); val segFilter = new Array[Int](4); val segProb = Array(255, 255, 255)
    useSegment = br.flag()
    if useSegment then
      updateMap = br.flag()
      if br.flag() then
        absoluteDelta = br.flag()
        var s = 0
        while s < 4 do { segQuant(s) = if br.flag() then br.signed(7) else 0; s += 1 }
        s = 0
        while s < 4 do { segFilter(s) = if br.flag() then br.signed(6) else 0; s += 1 }
      if updateMap then
        var s = 0
        while s < 3 do { segProb(s) = if br.flag() then br.literal(8) else 255; s += 1 }
    // filter header
    val simple = br.flag(); val level = br.literal(6); val sharpness = br.literal(3)
    val useLfDelta = br.flag()
    val refLfDelta = new Array[Int](4); val modeLfDelta = new Array[Int](4)
    if useLfDelta && br.flag() then
      var i = 0
      while i < 4 do { if br.flag() then refLfDelta(i) = br.signed(6); i += 1 }
      i = 0
      while i < 4 do { if br.flag() then modeLfDelta(i) = br.signed(6); i += 1 }
    val filterType = if level == 0 then 0 else if simple then 1 else 2
    // token partitions
    val numParts = 1 << br.literal(2)
    val lastPart = numParts - 1
    var partStart = p0 + partLen + lastPart * 3
    if partStart > end then fail("truncated partition sizes")
    var sizeLeft = end - partStart
    val parts = new Array[Bool](numParts)
    var p = 0
    while p < lastPart do
      val o = p0 + partLen + p * 3
      var psize = (d(o) & 0xff) | ((d(o + 1) & 0xff) << 8) | ((d(o + 2) & 0xff) << 16)
      if psize > sizeLeft then psize = sizeLeft
      parts(p) = new Bool(d, partStart, partStart + psize)
      partStart += psize; sizeLeft -= psize
      p += 1
    parts(lastPart) = new Bool(d, partStart, partStart + sizeLeft)

    // quantizers
    val baseQ0 = br.literal(7)
    def dq(): Int = if br.flag() then br.signed(4) else 0
    val dqy1dc = dq(); val dqy2dc = dq(); val dqy2ac = dq(); val dquvdc = dq(); val dquvac = dq()
    def clipQ(v: Int, m: Int) = if v < 0 then 0 else if v > m then m else v
    val quants = new Array[Quant](4)
    var s = 0
    while s < 4 do
      var q = 0
      if useSegment then { q = segQuant(s); if !absoluteDelta then q += baseQ0 }
      else if s > 0 then q = -1 else q = baseQ0
      if q == -1 && !useSegment then quants(s) = quants(0)
      else
        val y2ac = (WebpTables.AcTable(clipQ(q + dqy2ac, 127)) * 101581) >> 16
        quants(s) = new Quant(
          Array(WebpTables.DcTable(clipQ(q + dqy1dc, 127)), WebpTables.AcTable(clipQ(q, 127))),
          Array(WebpTables.DcTable(clipQ(q + dqy2dc, 127)) * 2, math.max(8, y2ac)),
          Array(WebpTables.DcTable(clipQ(q + dquvdc, 117)), WebpTables.AcTable(clipQ(q + dquvac, 127))))
      s += 1

    br.flag() // update_proba (ignored for key frames)
    // token probabilities [type 4][band 8][ctx 3][proba 11]
    val probas = new Array[Int](4 * 8 * 3 * 11)
    var i = 0
    while i < probas.length do
      probas(i) = if br.bit(WebpTables.CoeffsUpdateProba(i)) == 1 then br.literal(8) else WebpTables.CoeffsProba0(i)
      i += 1
    val useSkipProba = br.flag()
    val skipP = if useSkipProba then br.literal(8) else 0

    // per-segment filter strengths
    val fstrength = Array.ofDim[FInfo](4, 2)
    if filterType > 0 then
      s = 0
      while s < 4 do
        var base = if useSegment then (if absoluteDelta then segFilter(s) else segFilter(s) + level) else level
        var i4 = 0
        while i4 <= 1 do
          var lvl = base
          if useLfDelta then { lvl += refLfDelta(0); if i4 == 1 then lvl += modeLfDelta(0) }
          lvl = if lvl < 0 then 0 else if lvl > 63 then 63 else lvl
          if lvl > 0 then
            var il = lvl
            if sharpness > 0 then
              il >>= (if sharpness > 4 then 2 else 1)
              if il > 9 - sharpness then il = 9 - sharpness
            if il < 1 then il = 1
            fstrength(s)(i4) = new FInfo(2 * lvl + il, il, i4 == 1, if lvl >= 40 then 2 else if lvl >= 15 then 1 else 0)
          else fstrength(s)(i4) = new FInfo(0, 0, i4 == 1, 0)
          i4 += 1
        s += 1

    // ---- frame buffers and per-column state ----
    val yStride = mbW * 16; val uvStride = mbW * 8
    val yPlane = new Array[Byte](yStride * mbH * 16)
    val uPlane = new Array[Byte](uvStride * mbH * 8); val vPlane = new Array[Byte](uvStride * mbH * 8)
    val topY = new Array[Int](mbW * 16); val topU = new Array[Int](mbW * 8); val topV = new Array[Int](mbW * 8)
    val intraT = new Array[Int](mbW * 4) // B_DC_PRED == 0
    val intraL = new Array[Int](4)
    val topNz = new Array[Int](mbW); val topNzDc = new Array[Int](mbW)
    var leftNz = 0; var leftNzDc = 0
    val yuv = new Array[Int](Bps * 17 + Bps * 9)
    val coeffs = new Array[Int](384)
    val finfos = new Array[FInfo](mbW * mbH)
    // per-row mode data
    val segment = new Array[Int](mbW); val skipFlag = new Array[Boolean](mbW); val isI4 = new Array[Boolean](mbW)
    val imodes = Array.ofDim[Int](mbW, 16); val uvmode = new Array[Int](mbW)

    def probaIdx(t: Int, n: Int, ctx: Int) = ((t * 8 + Bands(n)) * 3 + ctx) * 11

    def largeValue(tb: Bool, pi: Int): Int =
      if tb.bit(probas(pi + 3)) == 0 then
        if tb.bit(probas(pi + 4)) == 0 then 2 else 3 + tb.bit(probas(pi + 5))
      else if tb.bit(probas(pi + 6)) == 0 then
        if tb.bit(probas(pi + 7)) == 0 then 5 + tb.bit(159)
        else { val a = 2 * tb.bit(165); 7 + a + tb.bit(145) }
      else
        val bit1 = tb.bit(probas(pi + 8)); val bit0 = tb.bit(probas(pi + 9 + bit1))
        val cat = 2 * bit1 + bit0
        var v = 0
        val tab = Cats(cat)
        var k = 0
        while k < tab.length do { v += v + tb.bit(tab(k)); k += 1 }
        v + 3 + (8 << cat)

    /** Decodes one block of coefficients (libwebp's GetCoeffs); returns the position of the last non-zero coefficient + 1. */
    def getCoeffs(tb: Bool, t: Int, ctx: Int, dqm: Array[Int], first: Int, out: Array[Int], off: Int): Int =
      var n = first
      var pi = probaIdx(t, n, ctx)
      while n < 16 do
        if tb.bit(probas(pi)) == 0 then return n
        var zeroRun = true
        while zeroRun do
          if tb.bit(probas(pi + 1)) == 0 then
            n += 1
            pi = probaIdx(t, n, 0)
            if n == 16 then return 16
          else zeroRun = false
        val next = probaIdx(t, n + 1, 0)
        var v = 0
        if tb.bit(probas(pi + 2)) == 0 then { v = 1; pi = next + 11 } else { v = largeValue(tb, pi); pi = next + 22 }
        val sign = tb.bit(128)
        out(off + Zigzag(n)) = (if sign == 1 then -v else v) * dqm(if n > 0 then 1 else 0)
        n += 1
      16

    def nzCodeBits(nzCoeffs: Int, nz: Int, dcNz: Int): Int = (nzCoeffs << 2) | (if nz > 3 then 3 else if nz > 1 then 2 else dcNz)

    // ---- transforms and predictors on the Bps-stride work buffer ----
    def put(idx: Int, v: Int): Unit = yuv(idx) = clip255(v)
    def mul1(a: Int) = ((a * 20091) >> 16) + a
    def mul2(a: Int) = (a * 35468) >> 16
    val tmp = new Array[Int](16)
    def transformOne(inp: Array[Int], io: Int, dst: Int): Unit =
      var i = 0
      var ip = io
      var tp = 0
      while i < 4 do
        val a = inp(ip) + inp(ip + 8); val b = inp(ip) - inp(ip + 8)
        val c = mul2(inp(ip + 4)) - mul1(inp(ip + 12)); val dd = mul1(inp(ip + 4)) + mul2(inp(ip + 12))
        tmp(tp) = a + dd; tmp(tp + 1) = b + c; tmp(tp + 2) = b - c; tmp(tp + 3) = a - dd
        tp += 4; ip += 1; i += 1
      i = 0; tp = 0
      var dp = dst
      while i < 4 do
        val dc = tmp(tp) + 4
        val a = dc + tmp(tp + 8); val b = dc - tmp(tp + 8)
        val c = mul2(tmp(tp + 4)) - mul1(tmp(tp + 12)); val dd = mul1(tmp(tp + 4)) + mul2(tmp(tp + 12))
        put(dp, yuv(dp) + ((a + dd) >> 3)); put(dp + 1, yuv(dp + 1) + ((b + c) >> 3))
        put(dp + 2, yuv(dp + 2) + ((b - c) >> 3)); put(dp + 3, yuv(dp + 3) + ((a - dd) >> 3))
        tp += 1; dp += Bps; i += 1
    def transformAC3(inp: Array[Int], io: Int, dst: Int): Unit =
      val a = inp(io) + 4
      val c4 = mul2(inp(io + 4)); val d4 = mul1(inp(io + 4)); val c1 = mul2(inp(io + 1)); val d1 = mul1(inp(io + 1))
      def store2(y: Int, dc: Int, dd: Int, c: Int): Unit =
        val o = dst + y * Bps
        put(o, yuv(o) + ((dc + dd) >> 3)); put(o + 1, yuv(o + 1) + ((dc + c) >> 3)); put(o + 2, yuv(o + 2) + ((dc - c) >> 3)); put(o + 3, yuv(o + 3) + ((dc - dd) >> 3))
      store2(0, a + d4, d1, c1); store2(1, a + c4, d1, c1); store2(2, a - c4, d1, c1); store2(3, a - d4, d1, c1)
    def transformDC(inp: Array[Int], io: Int, dst: Int): Unit =
      val dc = inp(io) + 4
      var j = 0
      while j < 4 do { var i = 0; while i < 4 do { put(dst + i + j * Bps, yuv(dst + i + j * Bps) + (dc >> 3)); i += 1 }; j += 1 }
    def doTransform(bits: Int, inp: Array[Int], io: Int, dst: Int): Unit = (bits >>> 30) match
      case 3 => transformOne(inp, io, dst)
      case 2 => transformAC3(inp, io, dst)
      case 1 => transformDC(inp, io, dst)
      case _ => ()
    def doUVTransform(bits: Int, inp: Array[Int], io: Int, dst: Int): Unit =
      if (bits & 0xff) != 0 then
        if (bits & 0xaa) != 0 then { transformOne(inp, io, dst); transformOne(inp, io + 16, dst + 4); transformOne(inp, io + 32, dst + 4 * Bps); transformOne(inp, io + 48, dst + 4 * Bps + 4) }
        else
          if inp(io) != 0 then transformDC(inp, io, dst)
          if inp(io + 16) != 0 then transformDC(inp, io + 16, dst + 4)
          if inp(io + 32) != 0 then transformDC(inp, io + 32, dst + 4 * Bps)
          if inp(io + 48) != 0 then transformDC(inp, io + 48, dst + 4 * Bps + 4)

    def transformWHT(inp: Array[Int], out: Array[Int], oo: Int): Unit =
      val t = new Array[Int](16)
      var i = 0
      while i < 4 do
        val a0 = inp(i) + inp(12 + i); val a1 = inp(4 + i) + inp(8 + i); val a2 = inp(4 + i) - inp(8 + i); val a3 = inp(i) - inp(12 + i)
        t(i) = a0 + a1; t(8 + i) = a0 - a1; t(4 + i) = a3 + a2; t(12 + i) = a3 - a2
        i += 1
      i = 0
      var o = oo
      while i < 4 do
        val dc = t(i * 4) + 3
        val a0 = dc + t(3 + i * 4); val a1 = t(1 + i * 4) + t(2 + i * 4); val a2 = t(1 + i * 4) - t(2 + i * 4); val a3 = dc - t(3 + i * 4)
        out(o) = (a0 + a1) >> 3; out(o + 16) = (a3 + a2) >> 3; out(o + 32) = (a0 - a1) >> 3; out(o + 48) = (a3 - a2) >> 3
        o += 64; i += 1

    def avg3(a: Int, b: Int, c: Int) = (a + 2 * b + c + 2) >> 2
    def avg2(a: Int, b: Int) = (a + b + 1) >> 1
    def trueMotion(dst: Int, size: Int): Unit =
      val topLeft = yuv(dst - Bps - 1)
      var y = 0
      while y < size do
        val left = yuv(dst + y * Bps - 1)
        var x = 0
        while x < size do { yuv(dst + y * Bps + x) = clip255(yuv(dst - Bps + x) + left - topLeft); x += 1 }
        y += 1
    def fill(dst: Int, size: Int, v: Int): Unit = { var j = 0; while j < size do { var i = 0; while i < size do { yuv(dst + j * Bps + i) = v; i += 1 }; j += 1 } }
    def dcPred(dst: Int, size: Int, shift: Int, useTop: Boolean, useLeft: Boolean): Unit =
      var dc = if useTop && useLeft then size else size / 2
      var i = 0
      while i < size do { if useTop then dc += yuv(dst - Bps + i); if useLeft then dc += yuv(dst + i * Bps - 1); i += 1 }
      fill(dst, size, dc >> shift)
    def pred16(mode: Int, dst: Int): Unit = mode match
      case BDc => dcPred(dst, 16, 5, true, true)
      case BTm => trueMotion(dst, 16)
      case BVe => var j = 0; while j < 16 do { var i = 0; while i < 16 do { yuv(dst + j * Bps + i) = yuv(dst - Bps + i); i += 1 }; j += 1 }
      case BHe => var j = 0; while j < 16 do { var i = 0; while i < 16 do { yuv(dst + j * Bps + i) = yuv(dst + j * Bps - 1); i += 1 }; j += 1 }
      case DcNoTop => dcPred(dst, 16, 4, false, true)
      case DcNoLeft => dcPred(dst, 16, 4, true, false)
      case _ => fill(dst, 16, 0x80)
    def pred8(mode: Int, dst: Int): Unit = mode match
      case BDc => dcPred(dst, 8, 4, true, true)
      case BTm => trueMotion(dst, 8)
      case BVe => var j = 0; while j < 8 do { var i = 0; while i < 8 do { yuv(dst + j * Bps + i) = yuv(dst - Bps + i); i += 1 }; j += 1 }
      case BHe => var j = 0; while j < 8 do { var i = 0; while i < 8 do { yuv(dst + j * Bps + i) = yuv(dst + j * Bps - 1); i += 1 }; j += 1 }
      case DcNoTop => dcPred(dst, 8, 3, false, true)
      case DcNoLeft => dcPred(dst, 8, 3, true, false)
      case _ => fill(dst, 8, 0x80)
    def pred4(mode: Int, dst: Int): Unit =
      def T(i: Int) = yuv(dst - Bps + i)            // top row, -1 is the corner
      def L(j: Int) = yuv(dst - 1 + j * Bps)
      def set(x: Int, y: Int, v: Int): Unit = yuv(dst + x + y * Bps) = v
      mode match
        case BDc =>
          var dc = 4
          var i = 0
          while i < 4 do { dc += T(i) + L(i); i += 1 }
          fill(dst, 4, dc >> 3)
        case BTm => trueMotion(dst, 4)
        case BVe =>
          val vals = Array(avg3(T(-1), T(0), T(1)), avg3(T(0), T(1), T(2)), avg3(T(1), T(2), T(3)), avg3(T(2), T(3), T(4)))
          var j = 0; while j < 4 do { var i = 0; while i < 4 do { set(i, j, vals(i)); i += 1 }; j += 1 }
        case BHe =>
          val A = T(-1); val B = L(0); val C = L(1); val D = L(2); val E = L(3)
          val rows = Array(avg3(A, B, C), avg3(B, C, D), avg3(C, D, E), avg3(D, E, E))
          var j = 0; while j < 4 do { var i = 0; while i < 4 do { set(i, j, rows(j)); i += 1 }; j += 1 }
        case BRd =>
          val I = L(0); val J = L(1); val K = L(2); val Lq = L(3); val X = T(-1); val A = T(0); val B = T(1); val C = T(2); val D = T(3)
          set(0, 3, avg3(J, K, Lq))
          val v1 = avg3(I, J, K); set(1, 3, avg3(X, I, J)); set(0, 2, v1)
          val v2 = avg3(X, I, J); set(2, 3, avg3(A, X, I)); set(1, 2, v2); set(0, 1, v2)
          val v3 = avg3(A, X, I); set(3, 3, v3); set(2, 2, v3); set(1, 1, v3); set(0, 0, v3)
          val v4 = avg3(B, A, X); set(3, 2, v4); set(2, 1, v4); set(1, 0, v4)
          val v5 = avg3(C, B, A); set(3, 1, v5); set(2, 0, v5)
          set(3, 0, avg3(D, C, B))
          // (1,3) and (2,3) per the reference: DST(1,3)=DST(0,2)=AVG3(I,J,K); DST(2,3)=DST(1,2)=DST(0,1)=AVG3(X,I,J)
          set(1, 3, v1); set(2, 3, v2)
        case BLd =>
          val A = T(0); val B = T(1); val C = T(2); val D = T(3); val E = T(4); val F = T(5); val G = T(6); val H = T(7)
          set(0, 0, avg3(A, B, C))
          val a = avg3(B, C, D); set(1, 0, a); set(0, 1, a)
          val b = avg3(C, D, E); set(2, 0, b); set(1, 1, b); set(0, 2, b)
          val c = avg3(D, E, F); set(3, 0, c); set(2, 1, c); set(1, 2, c); set(0, 3, c)
          val e = avg3(E, F, G); set(3, 1, e); set(2, 2, e); set(1, 3, e)
          val f = avg3(F, G, H); set(3, 2, f); set(2, 3, f)
          set(3, 3, avg3(G, H, H))
        case BVr =>
          val I = L(0); val J = L(1); val K = L(2); val X = T(-1); val A = T(0); val B = T(1); val C = T(2); val D = T(3)
          val a = avg2(X, A); set(0, 0, a); set(1, 2, a)
          val b = avg2(A, B); set(1, 0, b); set(2, 2, b)
          val c = avg2(B, C); set(2, 0, c); set(3, 2, c)
          set(3, 0, avg2(C, D))
          set(0, 3, avg3(K, J, I)); set(0, 2, avg3(J, I, X))
          val e = avg3(I, X, A); set(0, 1, e); set(1, 3, e)
          val f = avg3(X, A, B); set(1, 1, f); set(2, 3, f)
          val g = avg3(A, B, C); set(2, 1, g); set(3, 3, g)
          set(3, 1, avg3(B, C, D))
        case BVl =>
          val A = T(0); val B = T(1); val C = T(2); val D = T(3); val E = T(4); val F = T(5); val G = T(6); val H = T(7)
          set(0, 0, avg2(A, B))
          val a = avg2(B, C); set(1, 0, a); set(0, 2, a)
          val b = avg2(C, D); set(2, 0, b); set(1, 2, b)
          val c = avg2(D, E); set(3, 0, c); set(2, 2, c)
          set(0, 1, avg3(A, B, C))
          val e = avg3(B, C, D); set(1, 1, e); set(0, 3, e)
          val f = avg3(C, D, E); set(2, 1, f); set(1, 3, f)
          val g = avg3(D, E, F); set(3, 1, g); set(2, 3, g)
          set(3, 2, avg3(E, F, G)); set(3, 3, avg3(F, G, H))
        case BHu =>
          val I = L(0); val J = L(1); val K = L(2); val Lq = L(3)
          set(0, 0, avg2(I, J))
          val a = avg2(J, K); set(2, 0, a); set(0, 1, a)
          val b = avg2(K, Lq); set(2, 1, b); set(0, 2, b)
          set(1, 0, avg3(I, J, K))
          val c = avg3(J, K, Lq); set(3, 0, c); set(1, 1, c)
          val e = avg3(K, Lq, Lq); set(3, 1, e); set(1, 2, e)
          set(3, 2, Lq); set(2, 2, Lq); set(0, 3, Lq); set(1, 3, Lq); set(2, 3, Lq); set(3, 3, Lq)
        case _ => // BHd
          val I = L(0); val J = L(1); val K = L(2); val Lq = L(3); val X = T(-1); val A = T(0); val B = T(1); val C = T(2)
          val a = avg2(I, X); set(0, 0, a); set(2, 1, a)
          val b = avg2(J, I); set(0, 1, b); set(2, 2, b)
          val c = avg2(K, J); set(0, 2, c); set(2, 3, c)
          set(0, 3, avg2(Lq, K))
          set(3, 0, avg3(A, B, C)); set(2, 0, avg3(X, A, B))
          val e = avg3(I, X, A); set(1, 0, e); set(3, 1, e)
          val f = avg3(J, I, X); set(1, 1, f); set(3, 2, f)
          val g = avg3(K, J, I); set(1, 2, g); set(3, 3, g)
          set(1, 3, avg3(Lq, K, J))

    def checkMode(mbx: Int, mby: Int, mode: Int): Int =
      if mode == BDc then (if mbx == 0 then (if mby == 0 then DcNoTopLeft else DcNoLeft) else (if mby == 0 then DcNoTop else BDc)) else mode

    val scan = Array.tabulate(16)(n => (n % 4) * 4 + (n / 4) * 4 * Bps)

    // ---- main loop ----
    var mbY = 0
    while mbY < mbH do
      val tokenBr = parts(mbY & lastPart)
      // 1. intra modes of this row (first partition)
      var mbx = 0
      while mbx < mbW do
        segment(mbx) = if updateMap then (if br.bit(segProb(0)) == 0 then br.bit(segProb(1)) else br.bit(segProb(2)) + 2) else 0
        skipFlag(mbx) = if useSkipProba then br.bit(skipP) == 1 else false
        isI4(mbx) = br.bit(145) == 0
        if !isI4(mbx) then
          val ymode = if br.bit(156) == 1 then (if br.bit(128) == 1 then BTm else BHe) else (if br.bit(163) == 1 then BVe else BDc)
          imodes(mbx)(0) = ymode
          var k = 0
          while k < 4 do { intraT(4 * mbx + k) = ymode; intraL(k) = ymode; k += 1 }
        else
          var y = 0
          while y < 4 do
            var ymode = intraL(y)
            var x = 0
            while x < 4 do
              val pb = (intraT(4 * mbx + x) * 10 + ymode) * 9
              def b(k: Int) = br.bit(WebpTables.BModesProba(pb + k))
              ymode =
                if b(0) == 0 then BDc
                else if b(1) == 0 then BTm
                else if b(2) == 0 then BVe
                else if b(3) == 0 then (if b(4) == 0 then BHe else if b(5) == 0 then BRd else BVr)
                else if b(6) == 0 then BLd
                else if b(7) == 0 then BVl
                else if b(8) == 0 then BHd
                else BHu
              intraT(4 * mbx + x) = ymode
              imodes(mbx)(y * 4 + x) = ymode
              x += 1
            intraL(y) = ymode
            y += 1
        uvmode(mbx) = if br.bit(142) == 0 then BDc else if br.bit(114) == 0 then BVe else if br.bit(183) == 1 then BTm else BHe
        mbx += 1
      // 2. residuals and reconstruction of each macroblock
      leftNz = 0; leftNzDc = 0
      java.util.Arrays.fill(intraL, BDc)
      // work-buffer borders at the left edge
      var j = 0
      while j < 16 do { yuv(YOff + j * Bps - 1) = 129; j += 1 }
      j = 0
      while j < 8 do { yuv(UOff + j * Bps - 1) = 129; yuv(VOff + j * Bps - 1) = 129; j += 1 }
      if mbY > 0 then { yuv(YOff - 1 - Bps) = 129; yuv(UOff - 1 - Bps) = 129; yuv(VOff - 1 - Bps) = 129 }
      else
        var k = 0
        while k < 16 + 4 + 1 do { yuv(YOff - Bps - 1 + k) = 127; k += 1 }
        k = 0
        while k < 8 + 1 do { yuv(UOff - Bps - 1 + k) = 127; yuv(VOff - Bps - 1 + k) = 127; k += 1 }
      mbx = 0
      while mbx < mbW do
        // --- residual parsing ---
        java.util.Arrays.fill(coeffs, 0)
        var skip = if useSkipProba then skipFlag(mbx) else false
        var nonZeroY = 0; var nonZeroUv = 0
        if !skip then
          val q = quants(segment(mbx))
          var dst = 0
          var first = 0
          var acType = 3
          if !isI4(mbx) then
            val dcBuf = new Array[Int](16)
            val ctx = topNzDc(mbx) + leftNzDc
            val nz = getCoeffs(tokenBr, 1, ctx, q.y2, 0, dcBuf, 0)
            val has = if nz > 0 then 1 else 0
            topNzDc(mbx) = has; leftNzDc = has
            if nz > 1 then transformWHT(dcBuf, coeffs, 0)
            else { val dc0 = (dcBuf(0) + 3) >> 3; var k = 0; while k < 256 do { coeffs(k) = dc0; k += 16 } }
            first = 1; acType = 0
          var tnz = topNz(mbx) & 0x0f
          var lnz = leftNz & 0x0f
          var y = 0
          while y < 4 do
            var l = lnz & 1
            var nzCoeffs = 0
            var x = 0
            while x < 4 do
              val ctx = l + (tnz & 1)
              val nz = getCoeffs(tokenBr, acType, ctx, q.y1, first, coeffs, dst)
              l = if nz > first then 1 else 0
              tnz = (tnz >> 1) | (l << 7)
              nzCoeffs = nzCodeBits(nzCoeffs, nz, if coeffs(dst) != 0 then 1 else 0)
              dst += 16
              x += 1
            tnz >>= 4
            lnz = (lnz >> 1) | (l << 7)
            nonZeroY = (nonZeroY << 8) | nzCoeffs
            y += 1
          var outT = tnz
          var outL = lnz >> 4
          var ch = 0
          while ch < 4 do
            var nzCoeffs = 0
            tnz = topNz(mbx) >> (4 + ch)
            lnz = leftNz >> (4 + ch)
            var yy = 0
            while yy < 2 do
              var l = lnz & 1
              var xx = 0
              while xx < 2 do
                val ctx = l + (tnz & 1)
                val nz = getCoeffs(tokenBr, 2, ctx, q.uv, 0, coeffs, dst)
                l = if nz > 0 then 1 else 0
                tnz = (tnz >> 1) | (l << 3)
                nzCoeffs = nzCodeBits(nzCoeffs, nz, if coeffs(dst) != 0 then 1 else 0)
                dst += 16
                xx += 1
              tnz >>= 2
              lnz = (lnz >> 1) | (l << 5)
              yy += 1
            nonZeroUv |= nzCoeffs << (4 * ch)
            outT |= (tnz << 4) << ch
            outL |= (lnz & 0xf0) << ch
            ch += 2
          topNz(mbx) = outT; leftNz = outL
          skip = (nonZeroY | nonZeroUv) == 0
        else
          topNz(mbx) = 0; leftNz = 0
          if !isI4(mbx) then { topNzDc(mbx) = 0; leftNzDc = 0 }
        if filterType > 0 then
          val fi = fstrength(segment(mbx))(if isI4(mbx) then 1 else 0)
          finfos(mbY * mbW + mbx) = new FInfo(fi.limit, fi.ilevel, fi.inner || !skip, fi.hevThresh)

        // --- reconstruction ---
        if mbx > 0 then
          var jj = -1
          while jj < 16 do { var k = 0; while k < 4 do { yuv(YOff + jj * Bps - 4 + k) = yuv(YOff + jj * Bps + 12 + k); k += 1 }; jj += 1 }
          jj = -1
          while jj < 8 do
            var k = 0
            while k < 4 do { yuv(UOff + jj * Bps - 4 + k) = yuv(UOff + jj * Bps + 4 + k); yuv(VOff + jj * Bps - 4 + k) = yuv(VOff + jj * Bps + 4 + k); k += 1 }
            jj += 1
        if mbY > 0 then
          var k = 0
          while k < 16 do { yuv(YOff - Bps + k) = topY(mbx * 16 + k); k += 1 }
          k = 0
          while k < 8 do { yuv(UOff - Bps + k) = topU(mbx * 8 + k); yuv(VOff - Bps + k) = topV(mbx * 8 + k); k += 1 }
        var bits = nonZeroY
        if isI4(mbx) then
          val tr = YOff - Bps + 16
          if mbY > 0 then
            var k = 0
            while k < 4 do { yuv(tr + k) = if mbx >= mbW - 1 then topY(mbx * 16 + 15) else topY((mbx + 1) * 16 + k); k += 1 }
          var k = 0
          while k < 4 do { yuv(tr + Bps * 4 + k) = yuv(tr + k); yuv(tr + Bps * 8 + k) = yuv(tr + k); yuv(tr + Bps * 12 + k) = yuv(tr + k); k += 1 }
          var n = 0
          while n < 16 do
            val dst = YOff + scan(n)
            pred4(imodes(mbx)(n), dst)
            doTransform(bits, coeffs, n * 16, dst)
            bits <<= 2
            n += 1
        else
          pred16(checkMode(mbx, mbY, imodes(mbx)(0)), YOff)
          if bits != 0 then
            var n = 0
            while n < 16 do { doTransform(bits, coeffs, n * 16, YOff + scan(n)); bits <<= 2; n += 1 }
        val uvPred = checkMode(mbx, mbY, uvmode(mbx))
        pred8(uvPred, UOff); pred8(uvPred, VOff)
        doUVTransform(nonZeroUv >>> 0, coeffs, 16 * 16, UOff)
        doUVTransform(nonZeroUv >>> 8, coeffs, 20 * 16, VOff)
        if mbY < mbH - 1 then
          var k = 0
          while k < 16 do { topY(mbx * 16 + k) = yuv(YOff + 15 * Bps + k); k += 1 }
          k = 0
          while k < 8 do { topU(mbx * 8 + k) = yuv(UOff + 7 * Bps + k); topV(mbx * 8 + k) = yuv(VOff + 7 * Bps + k); k += 1 }
        // store into the planes
        var row = 0
        while row < 16 do
          var k = 0
          while k < 16 do { yPlane((mbY * 16 + row) * yStride + mbx * 16 + k) = yuv(YOff + row * Bps + k).toByte; k += 1 }
          row += 1
        row = 0
        while row < 8 do
          var k = 0
          while k < 8 do
            uPlane((mbY * 8 + row) * uvStride + mbx * 8 + k) = yuv(UOff + row * Bps + k).toByte
            vPlane((mbY * 8 + row) * uvStride + mbx * 8 + k) = yuv(VOff + row * Bps + k).toByte
            k += 1
          row += 1
        mbx += 1
      mbY += 1

    if filterType > 0 then loopFilter(filterType, yPlane, uPlane, vPlane, yStride, uvStride, mbW, mbH, finfos)
    new Frame(width, height, yPlane, uPlane, vPlane, yStride, uvStride)

  // ---- in-loop filter ----
  private def loopFilter(kind: Int, y: Array[Byte], u: Array[Byte], v: Array[Byte], ys: Int, uvs: Int, mbW: Int, mbH: Int, f: Array[FInfo]): Unit =
    def g(p: Array[Byte], i: Int) = p(i) & 0xff
    def doFilter2(p: Array[Byte], o: Int, step: Int): Unit =
      val p1 = g(p, o - 2 * step); val p0 = g(p, o - step); val q0 = g(p, o); val q1 = g(p, o + step)
      val a = 3 * (q0 - p0) + sclip1(p1 - q1)
      val a1 = sclip2((a + 4) >> 3); val a2 = sclip2((a + 3) >> 3)
      p(o - step) = clip255(p0 + a2).toByte; p(o) = clip255(q0 - a1).toByte
    def doFilter4(p: Array[Byte], o: Int, step: Int): Unit =
      val p1 = g(p, o - 2 * step); val p0 = g(p, o - step); val q0 = g(p, o); val q1 = g(p, o + step)
      val a = 3 * (q0 - p0)
      val a1 = sclip2((a + 4) >> 3); val a2 = sclip2((a + 3) >> 3); val a3 = (a1 + 1) >> 1
      p(o - 2 * step) = clip255(p1 + a3).toByte; p(o - step) = clip255(p0 + a2).toByte
      p(o) = clip255(q0 - a1).toByte; p(o + step) = clip255(q1 - a3).toByte
    def doFilter6(p: Array[Byte], o: Int, step: Int): Unit =
      val p2 = g(p, o - 3 * step); val p1 = g(p, o - 2 * step); val p0 = g(p, o - step)
      val q0 = g(p, o); val q1 = g(p, o + step); val q2 = g(p, o + 2 * step)
      val a = sclip1(3 * (q0 - p0) + sclip1(p1 - q1))
      val a1 = (27 * a + 63) >> 7; val a2 = (18 * a + 63) >> 7; val a3 = (9 * a + 63) >> 7
      p(o - 3 * step) = clip255(p2 + a3).toByte; p(o - 2 * step) = clip255(p1 + a2).toByte; p(o - step) = clip255(p0 + a1).toByte
      p(o) = clip255(q0 - a1).toByte; p(o + step) = clip255(q1 - a2).toByte; p(o + 2 * step) = clip255(q2 - a3).toByte
    def hev(p: Array[Byte], o: Int, step: Int, thresh: Int): Boolean =
      math.abs(g(p, o - 2 * step) - g(p, o - step)) > thresh || math.abs(g(p, o + step) - g(p, o)) > thresh
    def needs(p: Array[Byte], o: Int, step: Int, t: Int): Boolean =
      4 * math.abs(g(p, o - step) - g(p, o)) + math.abs(g(p, o - 2 * step) - g(p, o + step)) <= t
    def needs2(p: Array[Byte], o: Int, step: Int, t: Int, it: Int): Boolean =
      val p3 = g(p, o - 4 * step); val p2 = g(p, o - 3 * step); val p1 = g(p, o - 2 * step); val p0 = g(p, o - step)
      val q0 = g(p, o); val q1 = g(p, o + step); val q2 = g(p, o + 2 * step); val q3 = g(p, o + 3 * step)
      if 4 * math.abs(p0 - q0) + math.abs(p1 - q1) > t then false
      else math.abs(p3 - p2) <= it && math.abs(p2 - p1) <= it && math.abs(p1 - p0) <= it && math.abs(q3 - q2) <= it && math.abs(q2 - q1) <= it && math.abs(q1 - q0) <= it
    def simple(p: Array[Byte], o: Int, hstride: Int, vstride: Int, thresh: Int): Unit =
      val t2 = 2 * thresh + 1
      var i = 0
      while i < 16 do { if needs(p, o + i * vstride, hstride, t2) then doFilter2(p, o + i * vstride, hstride); i += 1 }
    def loop26(p: Array[Byte], o: Int, hstride: Int, vstride: Int, size: Int, thresh: Int, ithresh: Int, hevT: Int): Unit =
      val t2 = 2 * thresh + 1
      var i = 0
      while i < size do
        val q = o + i * vstride
        if needs2(p, q, hstride, t2, ithresh) then (if hev(p, q, hstride, hevT) then doFilter2(p, q, hstride) else doFilter6(p, q, hstride))
        i += 1
    def loop24(p: Array[Byte], o: Int, hstride: Int, vstride: Int, size: Int, thresh: Int, ithresh: Int, hevT: Int): Unit =
      val t2 = 2 * thresh + 1
      var i = 0
      while i < size do
        val q = o + i * vstride
        if needs2(p, q, hstride, t2, ithresh) then (if hev(p, q, hstride, hevT) then doFilter2(p, q, hstride) else doFilter4(p, q, hstride))
        i += 1
    var mby = 0
    while mby < mbH do
      var mbx = 0
      while mbx < mbW do
        val fi = f(mby * mbW + mbx)
        if fi != null && fi.limit != 0 then
          val yo = mby * 16 * ys + mbx * 16
          val limit = fi.limit; val il = fi.ilevel; val ht = fi.hevThresh
          if kind == 1 then
            if mbx > 0 then simple(y, yo, 1, ys, limit + 4)
            if fi.inner then { var k = 1; while k <= 3 do { simple(y, yo + 4 * k, 1, ys, limit); k += 1 } }
            if mby > 0 then simple(y, yo, ys, 1, limit + 4)
            if fi.inner then { var k = 1; while k <= 3 do { simple(y, yo + 4 * k * ys, ys, 1, limit); k += 1 } }
          else
            val uo = mby * 8 * uvs + mbx * 8
            if mbx > 0 then
              loop26(y, yo, 1, ys, 16, limit + 4, il, ht)
              loop26(u, uo, 1, uvs, 8, limit + 4, il, ht); loop26(v, uo, 1, uvs, 8, limit + 4, il, ht)
            if fi.inner then
              var k = 1
              while k <= 3 do { loop24(y, yo + 4 * k, 1, ys, 16, limit, il, ht); k += 1 }
              loop24(u, uo + 4, 1, uvs, 8, limit, il, ht); loop24(v, uo + 4, 1, uvs, 8, limit, il, ht)
            if mby > 0 then
              loop26(y, yo, ys, 1, 16, limit + 4, il, ht)
              loop26(u, uo, uvs, 1, 8, limit + 4, il, ht); loop26(v, uo, uvs, 1, 8, limit + 4, il, ht)
            if fi.inner then
              var k = 1
              while k <= 3 do { loop24(y, yo + 4 * k * ys, ys, 1, 16, limit, il, ht); k += 1 }
              loop24(u, uo + 4 * uvs, uvs, 1, 8, limit, il, ht); loop24(v, uo + 4 * uvs, uvs, 1, 8, limit, il, ht)
        mbx += 1
      mby += 1

  // ---- YUV -> RGBA with libwebp's "fancy" chroma upsampling ----
  private def multHi(v: Int, coeff: Int) = (v * coeff) >> 8
  private def clip8(v: Int) = if (v & ~((256 << 6) - 1)) == 0 then v >> 6 else if v < 0 then 0 else 255
  private def toR(y: Int, v: Int) = clip8(multHi(y, 19077) + multHi(v, 26149) - 14234)
  private def toG(y: Int, u: Int, v: Int) = clip8(multHi(y, 19077) - multHi(u, 6419) - multHi(v, 13320) + 8708)
  private def toB(y: Int, u: Int) = clip8(multHi(y, 19077) + multHi(u, 33050) - 17685)

  def toImage(f: Frame, alpha: Option[Array[Byte]]): Image =
    val w = f.width; val h = f.height
    val out = new Array[Byte](w * h * 4)
    def emit(row: Int, x: Int, yv: Int, u: Int, v: Int): Unit =
      val o = (row * w + x) * 4
      out(o) = toR(yv, v).toByte; out(o + 1) = toG(yv, u, v).toByte; out(o + 2) = toB(yv, u).toByte
      out(o + 3) = alpha.map(a => a(row * w + x)).getOrElse(-1.toByte)
    def Y(row: Int, x: Int) = f.y(row * f.yStride + x) & 0xff
    def U(row: Int, x: Int) = f.u(row * f.uvStride + x) & 0xff
    def V(row: Int, x: Int) = f.v(row * f.uvStride + x) & 0xff
    def pack(u: Int, v: Int) = u | (v << 16)
    // libwebp UpsampleRgbaLinePair: topRow/bottomRow (-1 for none), chroma rows topC/curC
    def pair(topRow: Int, bottomRow: Int, topC: Int, curC: Int): Unit =
      val lastPair = (w - 1) >> 1
      var tl = pack(U(topC, 0), V(topC, 0)); var l = pack(U(curC, 0), V(curC, 0))
      def put(row: Int, x: Int, uv: Int): Unit = emit(row, x, Y(row, x), uv & 0xff, uv >>> 16)
      put(topRow, 0, (3 * tl + l + 0x00020002) >>> 2)
      if bottomRow >= 0 then put(bottomRow, 0, (3 * l + tl + 0x00020002) >>> 2)
      var x = 1
      while x <= lastPair do
        val t = pack(U(topC, x), V(topC, x)); val uv = pack(U(curC, x), V(curC, x))
        val avg = tl + t + l + uv + 0x00080008
        val diag12 = (avg + 2 * (t + l)) >>> 3
        val diag03 = (avg + 2 * (tl + uv)) >>> 3
        put(topRow, 2 * x - 1, (diag12 + tl) >>> 1); put(topRow, 2 * x, (diag03 + t) >>> 1)
        if bottomRow >= 0 then { put(bottomRow, 2 * x - 1, (diag03 + l) >>> 1); put(bottomRow, 2 * x, (diag12 + uv) >>> 1) }
        tl = t; l = uv
        x += 1
      if (w & 1) == 0 then
        put(topRow, w - 1, (3 * tl + l + 0x00020002) >>> 2)
        if bottomRow >= 0 then put(bottomRow, w - 1, (3 * l + tl + 0x00020002) >>> 2)
    pair(0, -1, 0, 0)                         // first row mirrors its chroma
    var row = 1
    var chroma = 1
    while row + 1 < h do { pair(row, row + 1, chroma - 1, chroma); row += 2; chroma += 1 }
    if h > 1 && (h & 1) == 0 then pair(h - 1, -1, (h - 1) >> 1, (h - 1) >> 1)
    Image(w, h, out)

/** The ALPH chunk: raw or lossless-compressed alpha plane with optional prediction filtering. */
private[codecs] object WebpAlpha:
  def decode(d: Array[Byte], start: Int, end: Int, w: Int, h: Int): Array[Byte] =
    if end - start < 1 then throw new ImageFormatException("WebP: empty alpha chunk")
    val hdr = d(start) & 0xff
    val method = hdr & 3; val filter = (hdr >> 2) & 3
    val plane = new Array[Byte](w * h)
    method match
      case 0 =>
        if end - start - 1 < w * h then throw new ImageFormatException("WebP: truncated alpha plane")
        System.arraycopy(d, start + 1, plane, 0, w * h)
      case 1 =>
        val argb = WebpLossless.decodeHeaderless(d, start + 1, end, w, h)
        var i = 0
        while i < w * h do { plane(i) = (argb(i) >> 8).toByte; i += 1 }
      case _ => throw new ImageFormatException("WebP: unknown alpha compression")
    if filter != 0 then
      def clip(v: Int) = if v < 0 then 0 else if v > 255 then 255 else v
      var y = 0
      while y < h do
        val row = y * w; val prev = (y - 1) * w
        if y == 0 then
          var pred = 0; var x = 0
          while x < w do { pred = (pred + plane(row + x)) & 0xff; plane(row + x) = pred.toByte; x += 1 }
        else filter match
          case 1 =>
            var pred = plane(prev) & 0xff; var x = 0
            while x < w do { pred = (pred + plane(row + x)) & 0xff; plane(row + x) = pred.toByte; x += 1 }
          case 2 =>
            var x = 0
            while x < w do { plane(row + x) = ((plane(prev + x) & 0xff) + (plane(row + x) & 0xff)).toByte; x += 1 }
          case _ =>
            var top = plane(prev) & 0xff; var topLeft = top; var left = top; var x = 0
            while x < w do
              top = plane(prev + x) & 0xff
              left = ((plane(row + x) & 0xff) + clip(left + top - topLeft)) & 0xff
              topLeft = top
              plane(row + x) = left.toByte
              x += 1
        y += 1
    plane
