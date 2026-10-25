package scalaui.deflate

import java.io.ByteArrayOutputStream

class DataFormatException(msg: String) extends RuntimeException(msg)

object Crc32:
  private val table = Array.tabulate(256) { n =>
    var c = n
    for _ <- 0 until 8 do c = if (c & 1) != 0 then 0xedb88320 ^ (c >>> 1) else c >>> 1
    c
  }
  def apply(data: Array[Byte], off: Int = 0, len: Int = -1): Int =
    var c = 0xffffffff; var i = off; val end = off + (if len < 0 then data.length - off else len)
    while i < end do { c = table((c ^ data(i)) & 0xff) ^ (c >>> 8); i += 1 }
    ~c

object Adler32:
  def apply(data: Array[Byte], off: Int = 0, len: Int = -1): Int =
    // Long accumulators: the usual NMAX=5552 bound assumes unsigned 32-bit sums, which overflow a signed Int
    var a = 1L; var b = 0L; var i = off; val end = off + (if len < 0 then data.length - off else len)
    while i < end do
      val stop = math.min(end, i + 5552)
      while i < stop do { a += data(i) & 0xff; b += a; i += 1 }
      a %= 65521; b %= 65521
    ((b << 16) | a).toInt

/** Pure-Scala zlib (RFC 1950) / DEFLATE (RFC 1951): no zlib C library, so it cross-compiles with any sysroot. */
object Zlib:
  /** Decompresses a zlib stream (also accepts raw DEFLATE via [[inflateRaw]]). Verifies the Adler-32 checksum. */
  def decompress(data: Array[Byte]): Array[Byte] =
    if data.length < 6 then throw new DataFormatException("zlib stream too short")
    val cmf = data(0) & 0xff; val flg = data(1) & 0xff
    if (cmf & 15) != 8 || ((cmf << 8) | flg) % 31 != 0 then throw new DataFormatException("bad zlib header")
    if (flg & 0x20) != 0 then throw new DataFormatException("preset dictionary not supported")
    val (out, end) = Inflate.run(data, 2)
    if end + 4 <= data.length then
      val expect = ((data(end) & 0xff) << 24) | ((data(end + 1) & 0xff) << 16) | ((data(end + 2) & 0xff) << 8) | (data(end + 3) & 0xff)
      if Adler32(out) != expect then throw new DataFormatException("zlib checksum mismatch")
    out

  def inflateRaw(data: Array[Byte], offset: Int = 0): Array[Byte] = Inflate.run(data, offset)._1

  /** Compresses to a zlib stream. `level` 0 stores, 1-9 trades speed for ratio (default 6). */
  def compress(data: Array[Byte], level: Int = 6): Array[Byte] =
    val out = new ByteArrayOutputStream(data.length / 2 + 64)
    out.write(0x78); out.write(if level <= 1 then 0x01 else if level < 6 then 0x5e else if level == 6 then 0x9c else 0xda)
    Deflate.run(data, level, out)
    val a = Adler32(data)
    out.write(a >>> 24); out.write(a >>> 16); out.write(a >>> 8); out.write(a)
    out.toByteArray

private object Inflate:
  private val LenBase = Array(3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258)
  private val LenExtra = Array(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
  private val DistBase = Array(1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577)
  private val DistExtra = Array(0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13)
  private val ClOrder = Array(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)

  private val FastBits = 10

  /** Canonical Huffman decoding table: counts per length and symbols sorted by code (for codes longer than `FastBits`, decoded
    * bit by bit), plus a direct lookup table indexed by the next `FastBits` input bits for the common short codes.
    * `fast(i)` is `(symbol << 4) | length`, or 0 when the code is longer or the lengths are over-subscribed.
    */
  private final class Table(lengths: Array[Int], n: Int):
    val count = new Array[Int](16); val symbol = new Array[Int](n)
    val fast = new Array[Int](1 << FastBits)
    locally {
      var i = 0
      while i < n do { count(lengths(i)) += 1; i += 1 }
      val offs = new Array[Int](16)
      var l = 1
      while l < 15 do { offs(l + 1) = offs(l) + count(l); l += 1 }
      i = 0
      while i < n do { if lengths(i) != 0 then { symbol(offs(lengths(i))) = i; offs(lengths(i)) += 1 }; i += 1 }
      // first canonical code per length; skip the fast table for over-subscribed (invalid) sets, they take the slow path
      var left = 1; var over = false
      l = 1
      while l <= 15 do { left = (left << 1) - count(l); if left < 0 then over = true; l += 1 }
      if !over then
        val next = new Array[Int](16)
        var code = 0; var prevCount = 0
        l = 1
        while l <= 15 do { code = (code + prevCount) << 1; next(l) = code; prevCount = count(l); l += 1 }
        i = 0
        while i < n do
          val len = lengths(i)
          if len != 0 then
            val c = next(len); next(len) += 1
            if len <= FastBits then
              var rev = 0; var k = 0
              while k < len do { rev = (rev << 1) | ((c >> k) & 1); k += 1 }
              val entry = (i << 4) | len
              var j = rev
              while j < (1 << FastBits) do { fast(j) = entry; j += 1 << len }
          i += 1
    }

  private lazy val fixedLit = { val l = new Array[Int](288); for i <- 0 until 288 do l(i) = if i < 144 then 8 else if i < 256 then 9 else if i < 280 then 7 else 8; new Table(l, 288) }
  private lazy val fixedDist = { val l = new Array[Int](30); java.util.Arrays.fill(l, 5); new Table(l, 30) }

  /** Bit reader + output window; fields rather than captured vars so the hot loops stay allocation free. */
  private final class State(d: Array[Byte], start: Int):
    var pos = start; var bitBuf = 0L; var bitCnt = 0
    var window = new Array[Byte](1 << 16); var wlen = 0 // growable output buffer doubling as the back-reference window

    def need(n: Int): Unit =
      if wlen + n > window.length then window = java.util.Arrays.copyOf(window, math.max(window.length * 2, wlen + n))

    /** Tops the bit buffer up to at least 57 bits (fewer at the end of the input). */
    def refill(): Unit =
      while bitCnt <= 56 && pos < d.length do
        bitBuf |= (d(pos) & 0xffL) << bitCnt; pos += 1; bitCnt += 8

    def bits(n: Int): Int =
      if bitCnt < n then
        refill()
        if bitCnt < n then throw new DataFormatException("unexpected end of deflate data")
      val v = (bitBuf & ((1L << n) - 1)).toInt; bitBuf >>>= n; bitCnt -= n; v

    def decode(t: Table): Int =
      if bitCnt < 15 then refill()
      val e = t.fast((bitBuf & ((1 << FastBits) - 1)).toInt)
      val len = e & 15
      if e != 0 && len <= bitCnt then { bitBuf >>>= len; bitCnt -= len; e >> 4 }
      else decodeSlow(t)

    /** Bit-at-a-time canonical decoding (long codes, invalid code sets, truncated input). */
    private def decodeSlow(t: Table): Int =
      var code = 0; var first = 0; var index = 0; var len = 1
      while len <= 15 do
        code |= bits(1)
        val c = t.count(len)
        if code - c < first then return t.symbol(index + (code - first))
        index += c; first += c; first <<= 1; code <<= 1; len += 1
      throw new DataFormatException("bad Huffman code")

    /** Gives back whole unread bytes and drops the partial byte, as a byte-granular reader would have left them. */
    def alignToByte(): Unit = { pos -= bitCnt >> 3; bitBuf = 0L; bitCnt = 0 }

  def run(d: Array[Byte], start: Int): (Array[Byte], Int) =
    val st = new State(d, start)
    var last = false
    while !last do
      last = st.bits(1) == 1
      st.bits(2) match
        case 0 =>
          st.alignToByte()
          var pos = st.pos
          if pos + 4 > d.length then throw new DataFormatException("truncated stored block")
          val len = (d(pos) & 0xff) | ((d(pos + 1) & 0xff) << 8)
          val nlen = (d(pos + 2) & 0xff) | ((d(pos + 3) & 0xff) << 8)
          if (len ^ 0xffff) != nlen then throw new DataFormatException("stored block length mismatch")
          pos += 4
          if pos + len > d.length then throw new DataFormatException("truncated stored block")
          st.need(len); System.arraycopy(d, pos, st.window, st.wlen, len); st.wlen += len; st.pos = pos + len
        case t @ (1 | 2) =>
          var lit = fixedLit; var dist = fixedDist
          if t == 2 then
            val nlen = st.bits(5) + 257; val ndist = st.bits(5) + 1; val ncode = st.bits(4) + 4
            val lens = new Array[Int](320)
            var q = 0
            while q < ncode do { lens(ClOrder(q)) = st.bits(3); q += 1 }
            val cl = new Table(lens.take(19), 19)
            java.util.Arrays.fill(lens, 0)
            var i = 0
            while i < nlen + ndist do
              val sym = st.decode(cl)
              if sym < 16 then { lens(i) = sym; i += 1 }
              else
                var prev = 0; var rep = 0
                if sym == 16 then { if i == 0 then throw new DataFormatException("bad repeat"); prev = lens(i - 1); rep = 3 + st.bits(2) }
                else if sym == 17 then rep = 3 + st.bits(3) else rep = 11 + st.bits(7)
                if i + rep > nlen + ndist then throw new DataFormatException("too many code lengths")
                while rep > 0 do { lens(i) = prev; i += 1; rep -= 1 }
            lit = new Table(lens.take(nlen), nlen); dist = new Table(java.util.Arrays.copyOfRange(lens, nlen, nlen + ndist), ndist)
          var done = false
          while !done do
            val sym = st.decode(lit)
            if sym < 256 then { st.need(1); st.window(st.wlen) = sym.toByte; st.wlen += 1 }
            else if sym == 256 then done = true
            else
              val s = sym - 257
              if s >= 29 then throw new DataFormatException("bad length symbol")
              val len = LenBase(s) + st.bits(LenExtra(s))
              val ds = st.decode(dist)
              if ds >= 30 then throw new DataFormatException("bad distance symbol")
              val dd = DistBase(ds) + st.bits(DistExtra(ds))
              if dd > st.wlen then throw new DataFormatException("distance too far back")
              st.need(len)
              val w = st.window; var wl = st.wlen
              if dd >= len then { System.arraycopy(w, wl - dd, w, wl, len); wl += len }
              else
                var k = 0
                while k < len do { w(wl) = w(wl - dd); wl += 1; k += 1 }
              st.wlen = wl
        case _ => throw new DataFormatException("bad block type")
    st.alignToByte() // the original returned the position after the byte holding the last bit
    (java.util.Arrays.copyOf(st.window, st.wlen), st.pos)

private object Deflate:
  private val LenBase = Array(3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258)
  private val LenExtra = Array(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
  private val DistBase = Array(1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537, 2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577)
  private val DistExtra = Array(0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13)
  private val ClOrder = Array(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)
  private val lenCode = { val t = new Array[Int](259); for s <- 0 until 29; l <- LenBase(s) until (if s == 28 then 259 else LenBase(s + 1)) do t(l) = s; t(258) = 28; t }
  private def distCode(d: Int): Int = { var s = 29; while DistBase(s) > d do s -= 1; s }

  private final class BitWriter(out: ByteArrayOutputStream):
    private var buf = 0L; private var cnt = 0
    def write(v: Int, n: Int): Unit = { buf |= (v.toLong & ((1L << n) - 1)) << cnt; cnt += n; while cnt >= 8 do { out.write((buf & 0xff).toInt); buf >>>= 8; cnt -= 8 } }
    def flush(): Unit = if cnt > 0 then { out.write((buf & 0xff).toInt); buf = 0; cnt = 0 }
    def align(): Unit = flush()

  /** Length-limited Huffman code lengths for `freq` (max 15 bits). Symbols with zero frequency get length 0. */
  private def codeLengths(freq: Array[Int], maxLen: Int): Array[Int] =
    var f = freq.clone()
    while true do
      val n = f.length; val lens = new Array[Int](n)
      val used = (0 until n).filter(f(_) > 0)
      if used.isEmpty then return lens
      if used.size == 1 then { lens(used.head) = 1; return lens }
      // simple two-queue Huffman construction
      case class Node(w: Long, sym: Int, l: Int, r: Int)
      val nodes = scala.collection.mutable.ArrayBuffer.empty[Node]
      val pq = scala.collection.mutable.PriorityQueue.empty[(Long, Int)](using Ordering.by[(Long, Int), Long](_._1).reverse)
      used.foreach { s => nodes += Node(f(s), s, -1, -1); pq.enqueue((f(s).toLong, nodes.size - 1)) }
      while pq.size > 1 do
        val (w1, a) = pq.dequeue(); val (w2, b) = pq.dequeue()
        nodes += Node(w1 + w2, -1, a, b); pq.enqueue((w1 + w2, nodes.size - 1))
      var tooLong = false
      def walk(i: Int, depth: Int): Unit =
        val nd = nodes(i)
        if nd.sym >= 0 then { lens(nd.sym) = depth; if depth > maxLen then tooLong = true }
        else { walk(nd.l, depth + 1); walk(nd.r, depth + 1) }
      walk(nodes.size - 1, 0)
      if !tooLong then return lens
      f = f.map(x => if x == 0 then 0 else math.max(1, x / 2)) // flatten and retry
    throw new IllegalStateException

  private def codes(lens: Array[Int]): Array[Int] =
    val count = new Array[Int](17); lens.foreach(l => if l > 0 then count(l) += 1)
    val next = new Array[Int](17); var code = 0
    for l <- 1 to 16 do { code = (code + count(l - 1)) << 1; next(l) = code }
    val out = new Array[Int](lens.length)
    for i <- lens.indices if lens(i) > 0 do
      val c = next(lens(i)); next(lens(i)) += 1
      var r = 0; var k = 0
      while k < lens(i) do { r = (r << 1) | ((c >> k) & 1); k += 1 } // bit-reversed: deflate writes codes MSB first into an LSB-first stream
      out(i) = r
    out

  def run(data: Array[Byte], level: Int, out: ByteArrayOutputStream): Unit =
    val bw = new BitWriter(out)
    if level <= 0 || data.isEmpty then
      var pos = 0
      if data.isEmpty then { bw.write(1, 1); bw.write(1, 2); bw.write(0, 7) } // empty final fixed block (just EOB)
      while pos < data.length do
        val n = math.min(65535, data.length - pos)
        bw.write(if pos + n >= data.length then 1 else 0, 1); bw.write(0, 2); bw.align()
        out.write(n & 0xff); out.write(n >> 8); out.write(~n & 0xff); out.write((~n >> 8) & 0xff)
        out.write(data, pos, n); pos += n
      bw.flush(); return
    val maxChain = level match { case 1 => 4; case 2 => 8; case 3 => 16; case 4 => 32; case 5 => 64; case 6 => 128; case 7 => 256; case 8 => 512; case _ => 1024 }
    val n = data.length
    val head = new Array[Int](1 << 15); java.util.Arrays.fill(head, -1)
    val prev = new Array[Int](n)
    def hash(i: Int): Int = (((data(i) & 0xff) << 10) ^ ((data(i + 1) & 0xff) << 5) ^ (data(i + 2) & 0xff)) & 0x7fff
    // tokens: literal = (byte), match = (len << 16 | dist) with high bit flag
    val tokens = new Array[Int](math.min(n, 1 << 16) + 1); var nt = 0
    val litFreq = new Array[Int](286); val distFreq = new Array[Int](30)
    def flushBlock(blockEnd: Int, last: Boolean): Unit =
      litFreq(256) += 1
      val ll = codeLengths(litFreq, 15); val dl = codeLengths(distFreq, 15)
      if dl.forall(_ == 0) then dl(0) = 1
      var nlit = 286; while nlit > 257 && ll(nlit - 1) == 0 do nlit -= 1
      var ndist = 30; while ndist > 1 && dl(ndist - 1) == 0 do ndist -= 1
      // run-length encode the code lengths
      val all = (ll.take(nlit) ++ dl.take(ndist)).toArray
      val rle = scala.collection.mutable.ArrayBuffer.empty[(Int, Int)] // (symbol, extra)
      var i = 0
      while i < all.length do
        val v = all(i); var run = 1
        while i + run < all.length && all(i + run) == v do run += 1
        var left = run
        if v == 0 then
          while left >= 11 do { val r = math.min(138, left); rle += ((18, r - 11)); left -= r }
          if left >= 3 then { rle += ((17, left - 3)); left = 0 }
        else
          rle += ((v, 0)); left -= 1
          while left >= 3 do { val r = math.min(6, left); rle += ((16, r - 3)); left -= r }
        while left > 0 do { rle += ((v, 0)); left -= 1 }
        i += run
      val clFreq = new Array[Int](19); rle.foreach((s, _) => clFreq(s) += 1)
      val cl = codeLengths(clFreq, 7)
      var ncl = 19; while ncl > 4 && cl(ClOrder(ncl - 1)) == 0 do ncl -= 1
      // compare stored vs dynamic size roughly: just always use dynamic unless the block is tiny/incompressible
      bw.write(if last then 1 else 0, 1); bw.write(2, 2)
      bw.write(nlit - 257, 5); bw.write(ndist - 1, 5); bw.write(ncl - 4, 4)
      for k <- 0 until ncl do bw.write(cl(ClOrder(k)), 3)
      val clc = codes(cl)
      rle.foreach { (s, extra) =>
        bw.write(clc(s), cl(s))
        if s == 16 then bw.write(extra, 2) else if s == 17 then bw.write(extra, 3) else if s == 18 then bw.write(extra, 7)
      }
      val lc = codes(ll); val dc = codes(dl)
      var t = 0
      while t < nt do
        val tk = tokens(t)
        if tk >= 0 then bw.write(lc(tk), ll(tk))
        else
          val len = (tk >>> 16) & 0x7fff; val dist = tk & 0xffff
          val ls = lenCode(len)
          bw.write(lc(257 + ls), ll(257 + ls)); if LenExtra(ls) > 0 then bw.write(len - LenBase(ls), LenExtra(ls))
          val ds = distCode(dist)
          bw.write(dc(ds), dl(ds)); if DistExtra(ds) > 0 then bw.write(dist - DistBase(ds), DistExtra(ds))
        t += 1
      bw.write(lc(256), ll(256))
      nt = 0; java.util.Arrays.fill(litFreq, 0); java.util.Arrays.fill(distFreq, 0)
    var i = 0
    while i < n do
      var bestLen = 0; var bestDist = 0
      if i + 2 < n then
        val h = hash(i)
        var cand = head(h); var chain = maxChain
        while cand >= 0 && i - cand <= 32768 && chain > 0 do
          var l = 0; val maxL = math.min(258, n - i)
          while l < maxL && data(cand + l) == data(i + l) do l += 1
          if l > bestLen then { bestLen = l; bestDist = i - cand; if l == maxL then chain = 0 }
          cand = prev(cand); chain -= 1
        prev(i) = head(h); head(h) = i
      if bestLen >= 3 then
        tokens(nt) = 0x80000000 | (bestLen << 16) | bestDist; nt += 1
        litFreq(257 + lenCode(bestLen)) += 1; distFreq(distCode(bestDist)) += 1
        // insert skipped positions into the hash chains
        var k = 1
        while k < bestLen do
          if i + k + 2 < n then { val h = hash(i + k); prev(i + k) = head(h); head(h) = i + k }
          k += 1
        i += bestLen
      else
        tokens(nt) = data(i) & 0xff; nt += 1; litFreq(data(i) & 0xff) += 1; i += 1
      if nt >= tokens.length - 1 then flushBlock(i, last = false)
    flushBlock(n, last = true)
    bw.flush()
