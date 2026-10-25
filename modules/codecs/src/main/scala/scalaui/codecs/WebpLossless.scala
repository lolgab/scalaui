package scalaui.codecs

/** The WebP lossless (VP8L) decoder. Pixels are packed ARGB ints. */
private[codecs] object WebpLossless:
  private def fail(msg: String): Nothing = throw new ImageFormatException("WebP lossless: " + msg)

  /** LSB-first bit reader over `d[start, end)`. Reading past the end sets `eos` and yields zeros. */
  final class Bits(d: Array[Byte], start: Int, end: Int):
    private var pos = start
    private var buf = 0L
    private var cnt = 0
    var eos = false
    private def fill(): Unit =
      while cnt <= 32 && pos < end do { buf |= (d(pos) & 0xffL) << cnt; cnt += 8; pos += 1 }
    def peek(n: Int): Int = { fill(); (buf & ((1L << n) - 1)).toInt }
    def skip(n: Int): Unit =
      fill()
      if cnt < n then { eos = true; buf = 0; cnt = 0 } else { buf >>>= n; cnt -= n }
    def read(n: Int): Int =
      if n == 0 then 0 else { val v = peek(n); skip(n); v }

  private val FastBits = 10

  /** A canonical Huffman code over `lengths.length` symbols. A code with a single used symbol takes no bits. */
  final class PrefixCode(lengths: Array[Int]):
    private val maxLen = if lengths.isEmpty then 0 else lengths.max
    private val counts = new Array[Int](16)
    lengths.foreach(l => if l > 0 then counts(l) += 1)
    private val used = lengths.count(_ > 0)
    val single: Int = if used == 1 then lengths.indexWhere(_ > 0) else -1
    private val firstCode = new Array[Int](17); private val firstIndex = new Array[Int](17)
    private val sorted = new Array[Int](math.max(used, 1))
    private val fast = new Array[Int](1 << FastBits)
    if used == 0 then fail("empty Huffman code")
    if used > 1 then
      var code = 0; var idx = 0; var left = 1
      var l = 1
      while l <= 15 do
        left = (left << 1) - counts(l)
        if left < 0 then fail("over-subscribed Huffman code")
        firstCode(l) = code; firstIndex(l) = idx
        code = (code + counts(l)) << 1; idx += counts(l)
        l += 1
      if left != 0 then fail("incomplete Huffman code")
      // symbols sorted by (length, value); then the fast table for short codes
      val next = new Array[Int](17); l = 1
      while l <= 15 do { next(l) = firstIndex(l); l += 1 }
      var s = 0
      while s < lengths.length do
        val len = lengths(s)
        if len > 0 then
          sorted(next(len)) = s
          if len <= FastBits then
            val canon = firstCode(len) + (next(len) - firstIndex(len))
            val rev = Integer.reverse(canon) >>> (32 - len)
            var f = rev
            while f < (1 << FastBits) do { fast(f) = (len << 16) | s; f += 1 << len }
          next(len) += 1
        s += 1

    def decode(br: Bits): Int =
      if single >= 0 then single
      else
        val peeked = br.peek(FastBits)
        val e = fast(peeked)
        if e != 0 then { br.skip(e >>> 16); e & 0xffff }
        else
          // long code: canonical decode bit by bit
          var code = 0; var len = 0
          var result = -1
          while result < 0 do
            code = (code << 1) | br.read(1); len += 1
            if len > 15 then fail("bad Huffman code")
            val cnt = counts(len)
            if cnt > 0 && code - firstCode(len) >= 0 && code - firstCode(len) < cnt then result = sorted(firstIndex(len) + code - firstCode(len))
          result

  private val CodeLengthOrder = Array(17, 18, 0, 1, 2, 3, 4, 5, 16, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15)
  private val CodeToPlane = Array(
    0x18, 0x07, 0x17, 0x19, 0x28, 0x06, 0x27, 0x29, 0x16, 0x1a, 0x26, 0x2a, 0x38, 0x05, 0x37, 0x39, 0x15, 0x1b, 0x36, 0x3a,
    0x25, 0x2b, 0x48, 0x04, 0x47, 0x49, 0x14, 0x1c, 0x35, 0x3b, 0x46, 0x4a, 0x24, 0x2c, 0x58, 0x45, 0x4b, 0x34, 0x3c, 0x03,
    0x57, 0x59, 0x13, 0x1d, 0x56, 0x5a, 0x23, 0x2d, 0x44, 0x4c, 0x55, 0x5b, 0x33, 0x3d, 0x68, 0x02, 0x67, 0x69, 0x12, 0x1e,
    0x66, 0x6a, 0x22, 0x2e, 0x54, 0x5c, 0x43, 0x4d, 0x65, 0x6b, 0x32, 0x3e, 0x78, 0x01, 0x77, 0x79, 0x53, 0x5d, 0x11, 0x1f,
    0x64, 0x6c, 0x42, 0x4e, 0x76, 0x7a, 0x21, 0x2f, 0x75, 0x7b, 0x31, 0x3f, 0x63, 0x6d, 0x52, 0x5e, 0x00, 0x74, 0x7c, 0x41,
    0x4f, 0x10, 0x20, 0x62, 0x6e, 0x30, 0x73, 0x7d, 0x51, 0x5f, 0x40, 0x72, 0x7e, 0x61, 0x6f, 0x50, 0x71, 0x7f, 0x60, 0x70)

  private def subSample(size: Int, bits: Int) = (size + (1 << bits) - 1) >> bits

  private def readPrefixCode(br: Bits, alphabetSize: Int): PrefixCode =
    val lengths = new Array[Int](math.max(alphabetSize, 256))
    if br.read(1) == 1 then
      val numSymbols = br.read(1) + 1
      val firstIs8 = br.read(1)
      lengths(br.read(if firstIs8 == 0 then 1 else 8)) = 1
      if numSymbols == 2 then lengths(br.read(8)) = 1
    else
      val clc = new Array[Int](19)
      val n = br.read(4) + 4
      var i = 0
      while i < n do { clc(CodeLengthOrder(i)) = br.read(3); i += 1 }
      val codeLenCode = new PrefixCode(clc)
      var maxSymbol = alphabetSize
      if br.read(1) == 1 then
        val lengthBits = 2 + 2 * br.read(3)
        maxSymbol = 2 + br.read(lengthBits)
        if maxSymbol > alphabetSize then fail("bad code length count")
      var symbol = 0; var prev = 8
      while symbol < alphabetSize do
        if maxSymbol == 0 then symbol = alphabetSize
        else
          maxSymbol -= 1
          val len = codeLenCode.decode(br)
          if len < 16 then { lengths(symbol) = len; symbol += 1; if len != 0 then prev = len }
          else
            val (extra, offset) = len match { case 16 => (2, 3); case 17 => (3, 3); case _ => (7, 11) }
            val repeat = br.read(extra) + offset
            if symbol + repeat > alphabetSize then fail("code length repeat overflow")
            val v = if len == 16 then prev else 0
            var k = 0
            while k < repeat do { lengths(symbol) = v; symbol += 1; k += 1 }
    if br.eos then fail("truncated")
    new PrefixCode(java.util.Arrays.copyOf(lengths, alphabetSize))

  private final class Group(val green: PrefixCode, val red: PrefixCode, val blue: PrefixCode, val alpha: PrefixCode, val dist: PrefixCode)

  private final case class Transform(kind: Int, xsize: Int, ysize: Int, bits: Int, data: Array[Int])

  /** Decodes a full VP8L chunk payload into ARGB pixels (`width * height`); returns `(width, height, argb)`. */
  def decode(d: Array[Byte], start: Int, end: Int): (Int, Int, Array[Int]) =
    if end - start < 5 || (d(start) & 0xff) != 0x2f then fail("bad signature")
    val br = new Bits(d, start + 1, end)
    val width = br.read(14) + 1; val height = br.read(14) + 1
    br.read(1) // alpha_is_used: pixels keep their stored alpha either way
    if br.read(3) != 0 then fail("unsupported version")
    val argb = decodeImage(br, width, height)
    (width, height, argb)

  /** Decodes a headerless VP8L stream (used for the alpha plane of lossy images); the green channel is the plane. */
  def decodeHeaderless(d: Array[Byte], start: Int, end: Int, width: Int, height: Int): Array[Int] =
    decodeImage(new Bits(d, start, end), width, height)

  private def decodeImage(br: Bits, width: Int, height: Int): Array[Int] =
    val transforms = scala.collection.mutable.ArrayBuffer.empty[Transform]
    var xsize = width
    val seen = new Array[Boolean](4)
    while br.read(1) == 1 do
      val kind = br.read(2)
      if seen(kind) then fail("repeated transform")
      seen(kind) = true
      kind match
        case 0 | 1 =>
          val bits = 2 + br.read(3)
          val data = decodeStream(br, subSample(xsize, bits), subSample(height, bits), level0 = false)
          transforms += Transform(kind, xsize, height, bits, data)
        case 3 =>
          val n = br.read(8) + 1
          val bits = if n > 16 then 0 else if n > 4 then 1 else if n > 2 then 2 else 3
          val raw = decodeStream(br, n, 1, level0 = false)
          // expand: entries delta-coded per byte; unused slots are transparent black
          val size = 1 << (8 >> bits)
          val table = new Array[Int](size)
          table(0) = raw(0)
          var i = 1
          while i < n do
            val a = table(i - 1); val b = raw(i)
            table(i) = (((a >>> 24) + (b >>> 24)) & 0xff) << 24 | (((a >>> 16 & 0xff) + (b >>> 16 & 0xff)) & 0xff) << 16 |
              (((a >>> 8 & 0xff) + (b >>> 8 & 0xff)) & 0xff) << 8 | (((a & 0xff) + (b & 0xff)) & 0xff)
            i += 1
          transforms += Transform(3, xsize, height, bits, table)
          xsize = subSample(xsize, bits)
        case _ => transforms += Transform(2, xsize, height, 0, null)
    var pixels = decodeStream(br, xsize, height, level0 = true)
    var t = transforms.length - 1
    while t >= 0 do
      pixels = inverse(transforms(t), pixels)
      t -= 1
    pixels

  /** Decodes an entropy-coded ARGB image (the transform list was read by the caller for level 0). */
  private def decodeStream(br: Bits, w: Int, h: Int, level0: Boolean): Array[Int] =
    var cacheBits = 0
    if br.read(1) == 1 then
      cacheBits = br.read(4)
      if cacheBits < 1 || cacheBits > 11 then fail("bad color cache size")
    // prefix groups, optionally selected per block by an entropy image
    var metaBits = 0; var metaWidth = 0
    var entropy: Array[Int] = null
    var numGroups = 1
    if level0 && br.read(1) == 1 then
      metaBits = 2 + br.read(3)
      metaWidth = subSample(w, metaBits)
      val img = decodeStream(br, metaWidth, subSample(h, metaBits), level0 = false)
      entropy = img.map(p => (p >>> 8) & 0xffff)
      numGroups = entropy.max + 1
    val cacheSize = if cacheBits > 0 then 1 << cacheBits else 0
    val groups = Array.tabulate(numGroups) { _ =>
      new Group(readPrefixCode(br, 256 + 24 + cacheSize), readPrefixCode(br, 256), readPrefixCode(br, 256), readPrefixCode(br, 256), readPrefixCode(br, 40))
    }
    if br.eos then fail("truncated")
    val out = new Array[Int](w * h)
    val cache = if cacheBits > 0 then new Array[Int](cacheSize) else null
    val total = w * h
    var pos = 0
    def insert(p: Int): Unit = if cache != null then cache((0x1e35a7bd * p) >>> (32 - cacheBits)) = p
    def copyLength(sym: Int): Int =
      if sym < 4 then sym + 1
      else { val extra = (sym - 2) >> 1; val off = (2 + (sym & 1)) << extra; off + br.read(extra) + 1 }
    while pos < total && !br.eos do
      val g = if entropy == null then groups(0) else groups(entropy(((pos / w) >> metaBits) * metaWidth + ((pos % w) >> metaBits)))
      val sym = g.green.decode(br)
      if sym < 256 then
        val r = g.red.decode(br); val b = g.blue.decode(br); val a = g.alpha.decode(br)
        val p = (a << 24) | (r << 16) | (sym << 8) | b
        out(pos) = p; insert(p); pos += 1
      else if sym < 280 then
        val length = copyLength(sym - 256)
        val distCode = copyLength(g.dist.decode(br))
        val dist =
          if distCode > 120 then distCode - 120
          else
            val c = CodeToPlane(distCode - 1)
            math.max(1, (c >> 4) * w + (8 - (c & 0xf)))
        if pos < dist || pos + length > total then fail("bad backward reference")
        var k = 0
        while k < length do { val p = out(pos - dist); out(pos) = p; insert(p); pos += 1; k += 1 }
      else
        val idx = sym - 280
        if cache == null || idx >= cacheSize then fail("bad color cache index")
        val p = cache(idx)
        out(pos) = p; insert(p); pos += 1
    if pos < total then fail("truncated pixel data")
    out

  // ---- inverse transforms ----

  private def add(a: Int, b: Int): Int =
    ((((a >>> 24) + (b >>> 24)) & 0xff) << 24) | ((((a >>> 16) & 0xff) + ((b >>> 16) & 0xff)) & 0xff) << 16 |
      ((((a >>> 8) & 0xff) + ((b >>> 8) & 0xff)) & 0xff) << 8 | (((a & 0xff) + (b & 0xff)) & 0xff)
  private def avg2(a: Int, b: Int): Int = (((a ^ b) & 0xfefefefe) >>> 1) + (a & b)
  private def clip(v: Int): Int = if v < 0 then 0 else if v > 255 then 255 else v
  private def sub3(a: Int, b: Int, c: Int): Int = math.abs(b - c) - math.abs(a - c)
  private def select(a: Int, b: Int, c: Int): Int =
    val pa = sub3(a >>> 24, b >>> 24, c >>> 24) + sub3((a >>> 16) & 0xff, (b >>> 16) & 0xff, (c >>> 16) & 0xff) +
      sub3((a >>> 8) & 0xff, (b >>> 8) & 0xff, (c >>> 8) & 0xff) + sub3(a & 0xff, b & 0xff, c & 0xff)
    if pa <= 0 then a else b
  private def addSubFull(c0: Int, c1: Int, c2: Int): Int =
    def ch(s: Int) = clip(((c0 >>> s) & 0xff) + ((c1 >>> s) & 0xff) - ((c2 >>> s) & 0xff))
    (ch(24) << 24) | (ch(16) << 16) | (ch(8) << 8) | ch(0)
  private def addSubHalf(c0: Int, c1: Int, c2: Int): Int =
    val ave = avg2(c0, c1)
    def ch(s: Int) = { val a = (ave >>> s) & 0xff; val b = (c2 >>> s) & 0xff; clip(a + (a - b) / 2) }
    (ch(24) << 24) | (ch(16) << 16) | (ch(8) << 8) | ch(0)

  private def predict(mode: Int, left: Int, top: Int, tl: Int, tr: Int): Int = mode match
    case 0 => 0xff000000
    case 1 => left
    case 2 => top
    case 3 => tr
    case 4 => tl
    case 5 => avg2(avg2(left, tr), top)
    case 6 => avg2(left, tl)
    case 7 => avg2(left, top)
    case 8 => avg2(tl, top)
    case 9 => avg2(top, tr)
    case 10 => avg2(avg2(left, tl), avg2(top, tr))
    case 11 => select(top, left, tl)
    case 12 => addSubFull(left, top, tl)
    case 13 => addSubHalf(left, top, tl)
    case _ => 0xff000000 // 14 and 15 behave as black

  private def inverse(t: Transform, in: Array[Int]): Array[Int] = t.kind match
    case 2 => in.map { p => val g = (p >>> 8) & 0xff; (p & 0xff00ff00) | ((((p >>> 16) & 0xff) + g) & 0xff) << 16 | (((p & 0xff) + g) & 0xff) }
    case 0 =>
      val w = t.xsize; val h = t.ysize
      val out = new Array[Int](w * h)
      val bw = subSample(w, t.bits)
      var y = 0
      while y < h do
        var x = 0
        while x < w do
          val i = y * w + x
          val pred =
            if y == 0 then (if x == 0 then 0xff000000 else out(i - 1))
            else if x == 0 then out(i - w)
            else predict((t.data((y >> t.bits) * bw + (x >> t.bits)) >>> 8) & 0xf, out(i - 1), out(i - w), out(i - w - 1), out(i - w + 1))
          out(i) = add(in(i), pred)
          x += 1
        y += 1
      out
    case 1 =>
      val w = t.xsize; val h = t.ysize
      val bw = subSample(w, t.bits)
      def delta(pred: Int, c: Int): Int = (pred.toByte * c.toByte) >> 5
      val out = new Array[Int](w * h)
      var y = 0
      while y < h do
        var x = 0
        while x < w do
          val m = t.data((y >> t.bits) * bw + (x >> t.bits))
          val g2r = m & 0xff; val g2b = (m >>> 8) & 0xff; val r2b = (m >>> 16) & 0xff
          val p = in(y * w + x)
          val green = (p >>> 8) & 0xff
          val red = (((p >>> 16) & 0xff) + delta(g2r, green)) & 0xff
          val blue = ((p & 0xff) + delta(g2b, green) + delta(r2b, red)) & 0xff
          out(y * w + x) = (p & 0xff00ff00) | (red << 16) | blue
          x += 1
        y += 1
      out
    case _ =>
      val w = t.xsize; val h = t.ysize
      val packedW = subSample(w, t.bits)
      val out = new Array[Int](w * h)
      val bpp = 8 >> t.bits
      val mask = (1 << bpp) - 1
      var y = 0
      while y < h do
        var x = 0
        while x < w do
          val src = in(y * packedW + (x >> t.bits))
          val idx = if t.bits == 0 then (src >>> 8) & 0xff else ((src >>> 8) & 0xff) >> ((x & ((1 << t.bits) - 1)) * bpp) & mask
          out(y * w + x) = if idx < t.data.length then t.data(idx) else 0
          x += 1
        y += 1
      out
