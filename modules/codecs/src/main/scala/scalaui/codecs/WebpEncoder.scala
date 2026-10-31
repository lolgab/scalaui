package scalaui.codecs

import java.io.ByteArrayOutputStream

/** A small lossless WebP (VP8L) encoder: subtract-green transform and Huffman-coded literals (no LZ77, prediction or
  * colour cache), so files are valid and exact but larger than libwebp's.
  */
object WebpEncoder:
  private final class Bits:
    private val out = new ByteArrayOutputStream
    private var acc = 0L; private var cnt = 0
    def put(v: Int, n: Int): Unit =
      acc |= (v.toLong & ((1L << n) - 1)) << cnt; cnt += n
      while cnt >= 8 do { out.write((acc & 0xff).toInt); acc >>>= 8; cnt -= 8 }
    def bytes: Array[Byte] = { if cnt > 0 then { out.write((acc & 0xff).toInt); acc = 0; cnt = 0 }; out.toByteArray }

  /** Huffman code lengths limited to `maxLen` for the given histogram; zero counts get length 0. */
  private def lengths(counts0: Array[Int], maxLen: Int): Array[Int] =
    val n = counts0.length
    var counts = counts0.clone()
    var result = new Array[Int](n)
    var ok = false
    while !ok do
      val used = (0 until n).filter(counts(_) > 0)
      result = new Array[Int](n)
      if used.size == 1 then { result(used.head) = 1; ok = true }
      else if used.size > 1 then
        // classic Huffman by repeatedly merging the two lightest nodes
        final case class Node(weight: Long, symbols: List[Int])
        val pq = scala.collection.mutable.PriorityQueue.empty[Node](using Ordering.by((nd: Node) => -nd.weight))
        used.foreach(s => pq.enqueue(Node(counts(s), List(s))))
        while pq.size > 1 do
          val a = pq.dequeue(); val b = pq.dequeue()
          (a.symbols ++ b.symbols).foreach(s => result(s) += 1)
          pq.enqueue(Node(a.weight + b.weight, a.symbols ++ b.symbols))
        if result.max <= maxLen then ok = true
        else counts = counts.map(c => if c == 0 then 0 else math.max(1, c >> 1))
      else ok = true
    result

  private val CodeLengthOrder = Array(17, 18, 0, 1, 2, 3, 4, 5, 16, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15)

  /** Canonical codes (MSB-first values, to be bit-reversed when written LSB-first). */
  private def canonical(len: Array[Int]): Array[Int] =
    val code = new Array[Int](len.length)
    val count = new Array[Int](17)
    len.foreach(l => if l > 0 then count(l) += 1)
    var c = 0
    val first = new Array[Int](17)
    for l <- 1 to 16 do { c = (c + count(l - 1)) << 1; first(l) = c }
    val next = first.clone()
    for s <- len.indices if len(s) > 0 do { code(s) = next(len(s)); next(len(s)) += 1 }
    code

  private def writePrefixCode(w: Bits, len: Array[Int]): Unit =
    val used = len.indices.filter(len(_) > 0)
    if used.size <= 2 && used.forall(_ < 256) then
      w.put(1, 1); w.put(used.size - 1, 1)
      val first = used.head
      if first <= 1 then { w.put(0, 1); w.put(first, 1) } else { w.put(1, 1); w.put(first, 8) }
      if used.size == 2 then w.put(used(1), 8)
    else
      w.put(0, 1)
      val histo = new Array[Int](19)
      len.foreach(l => histo(l) += 1)
      val clen = lengths(histo, 7)
      val ccode = canonical(clen)
      var num = 19
      while num > 4 && clen(CodeLengthOrder(num - 1)) == 0 do num -= 1
      w.put(num - 4, 4)
      for i <- 0 until num do w.put(clen(CodeLengthOrder(i)), 3)
      w.put(0, 1) // all symbols are coded
      // a code-length code with a single used symbol is read without consuming bits (e.g. 256 literals that all get length 8)
      val singleLen = clen.count(_ > 0) == 1
      len.foreach { l =>
        val c = ccode(l); val n = clen(l)
        if n > 0 && !singleLen then for b <- n - 1 to 0 by -1 do w.put((c >> b) & 1, 1) // code-length codes are read MSB first, bit by bit
      }

  // `multi`: the code has more than one symbol (a single-symbol code is read without consuming bits)
  private def writeSymbol(w: Bits, code: Array[Int], len: Array[Int], multi: Boolean, s: Int): Unit =
    val n = len(s)
    if n > 0 && multi then
      var b = n - 1
      while b >= 0 do { w.put((code(s) >> b) & 1, 1); b -= 1 }

  def encode(img: Image): Array[Byte] =
    val w = img.width; val h = img.height
    require(w >= 1 && h >= 1 && w <= 16384 && h <= 16384, "WebP dimensions must be 1..16384")
    val n = w * h
    val argb = new Array[Int](n)
    var hasAlpha = false
    var i = 0
    while i < n do
      val a = img.rgba(i * 4 + 3) & 0xff
      if a != 255 then hasAlpha = true
      val r = img.rgba(i * 4) & 0xff; val g = img.rgba(i * 4 + 1) & 0xff; val b = img.rgba(i * 4 + 2) & 0xff
      // subtract green from red and blue
      argb(i) = (a << 24) | (((r - g) & 0xff) << 16) | (g << 8) | ((b - g) & 0xff)
      i += 1
    val histoG = new Array[Int](280); val histoR = new Array[Int](256); val histoB = new Array[Int](256); val histoA = new Array[Int](256)
    var hi = 0
    while hi < n do
      val p = argb(hi); histoG((p >>> 8) & 0xff) += 1; histoR((p >>> 16) & 0xff) += 1; histoB(p & 0xff) += 1; histoA(p >>> 24) += 1
      hi += 1
    val lG = lengths(histoG, 15); val lR = lengths(histoR, 15); val lB = lengths(histoB, 15); val lA = lengths(histoA, 15)
    val cG = canonical(lG); val cR = canonical(lR); val cB = canonical(lB); val cA = canonical(lA)
    val bw = new Bits
    bw.put(0x2f, 8); bw.put(w - 1, 14); bw.put(h - 1, 14); bw.put(if hasAlpha then 1 else 0, 1); bw.put(0, 3)
    bw.put(1, 1); bw.put(2, 2) // subtract-green transform
    bw.put(0, 1)               // no more transforms
    bw.put(0, 1)               // no colour cache
    bw.put(0, 1)               // no meta prefix codes
    writePrefixCode(bw, lG); writePrefixCode(bw, lR); writePrefixCode(bw, lB); writePrefixCode(bw, lA)
    writePrefixCode(bw, Array.tabulate(40)(k => if k == 0 then 1 else 0)) // distance code: unused
    val mG = lG.count(_ > 0) > 1; val mR = lR.count(_ > 0) > 1; val mB = lB.count(_ > 0) > 1; val mA = lA.count(_ > 0) > 1
    var pi = 0
    while pi < n do
      val p = argb(pi)
      writeSymbol(bw, cG, lG, mG, (p >>> 8) & 0xff); writeSymbol(bw, cR, lR, mR, (p >>> 16) & 0xff)
      writeSymbol(bw, cB, lB, mB, p & 0xff); writeSymbol(bw, cA, lA, mA, p >>> 24)
      pi += 1
    val payload = bw.bytes
    val out = new ByteArrayOutputStream
    def w32(v: Int): Unit = { out.write(v & 0xff); out.write((v >> 8) & 0xff); out.write((v >> 16) & 0xff); out.write((v >>> 24) & 0xff) }
    val chunk = payload.length + (payload.length & 1)
    out.write("RIFF".getBytes); w32(4 + 8 + chunk); out.write("WEBP".getBytes)
    out.write("VP8L".getBytes); w32(payload.length); out.write(payload)
    if (payload.length & 1) == 1 then out.write(0)
    out.toByteArray
