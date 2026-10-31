package scalaui.brotli

/** Pure-Scala Brotli (RFC 7932) decoder, including the 122,784-byte static dictionary (embedded resource `/brotli/dictionary.bin`).
  * The dictionary and the transform tables are those of the RFC; the decoder is whole-buffer (no streaming).
  */
object Brotli:
  /** Decompresses a complete Brotli stream. `sizeHint` is the expected output size, used only to pre-size the buffer. */
  def decompress(in: Array[Byte], offset: Int = 0, length: Int = -1, sizeHint: Int = 0): Array[Byte] =
    new Decoder(in, offset, if length < 0 then in.length else offset + length, sizeHint).run()

  private[brotli] lazy val dictionary: Array[Byte] =
    val s = getClass.getResourceAsStream("/brotli/dictionary.bin")
    require(s != null, "brotli dictionary resource missing (native builds need nativeConfig.withEmbedResources(true))")
    val d = try s.readAllBytes() finally s.close()
    require(d.length == 122784, s"brotli dictionary has wrong size ${d.length}")
    d

  private val NDBits = Array(0, 0, 0, 0, 10, 10, 11, 11, 10, 10, 10, 10, 10, 9, 9, 8, 7, 7, 8, 7, 7, 6, 6, 5, 5)
  private val DOffset: Array[Int] =
    val o = new Array[Int](26); var p = 0
    for i <- 0 until 25 do { o(i) = p; if NDBits(i) != 0 then p += i << NDBits(i) }
    o

  private val InsBase = Array(0, 1, 2, 3, 4, 5, 6, 8, 10, 14, 18, 26, 34, 50, 66, 98, 130, 194, 322, 578, 1090, 2114, 6210, 22594)
  private val InsExtra = Array(0, 0, 0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 7, 8, 9, 10, 12, 14, 24)
  private val CopyBase = Array(2, 3, 4, 5, 6, 7, 8, 9, 10, 12, 14, 18, 22, 30, 38, 54, 70, 102, 134, 198, 326, 582, 1094, 2118)
  private val CopyExtra = Array(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 7, 8, 9, 10, 24)
  private val BlockBase = Array(1, 5, 9, 13, 17, 25, 33, 41, 49, 65, 81, 97, 113, 145, 177, 209, 241, 305, 369, 497, 753, 1265, 2289, 4337, 8433, 16625)
  private val BlockExtra = Array(2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 6, 6, 7, 8, 9, 10, 11, 12, 13, 24)
  // per command-code cell (symbol >> 6): insert-code base, copy-code base, implicit distance 0
  private val CellIns = Array(0, 0, 0, 0, 8, 8, 0, 16, 8, 16, 16)
  private val CellCopy = Array(0, 8, 0, 8, 0, 8, 16, 0, 16, 8, 16)
  private val CodeLenOrder = Array(1, 2, 3, 4, 0, 5, 17, 6, 16, 7, 8, 9, 10, 11, 12, 13, 14, 15)

  // context lookup: 4 modes x (256 for the previous byte + 256 for the one before)
  private val Lookup: Array[Int] =
    val utfMap = "         !!  !                  \"#$##%#$&'##(#)#+++++++++" +
      "+((&*'##,---,---,-----,-----,-----&#'###.///.///./////./////./////&#'# "
    val utfRle = "A/*  ':  & : $  \u0081 @"
    val l = new Array[Int](2048)
    for i <- 0 until 256 do { l(i) = i & 0x3f; l(512 + i) = i >> 2; l(1792 + i) = 2 + (i >> 6) }
    for i <- 0 until 128 do l(1024 + i) = 4 * (utfMap.charAt(i) - 32)
    for i <- 0 until 64 do { l(1152 + i) = i & 1; l(1216 + i) = 2 + (i & 1) }
    var off = 1280
    for k <- 0 until 19 do
      val rep = utfRle.charAt(k) - 32
      for _ <- 0 until rep do { l(off) = k & 3; off += 1 }
    for i <- 0 until 16 do { l(1792 + i) = 1; l(2032 + i) = 6 }
    l(1792) = 0; l(2047) = 7
    for i <- 0 until 256 do l(1536 + i) = l(1792 + i) << 3
    l

  // dictionary word transforms (RFC 7932 appendix B)
  private val Transforms: Array[Int] =
    val src = "     !! ! ,  *!  &!  \" !  ) *   * -  ! # !  #!*!  " +
      "+  ,$ !  -  %  .  / #   0  1 .  \"   2  3!*   4%  ! # /   5  6  7  8 0  1 &   $   9 +   : " +
      " ;  < '  !=  >  ?! 4  @ 4  2  &   A *# (   B  C& ) %  ) !*# *-% A +! *.  D! %'  & E *6  F " +
      " G% ! *A *%  H! D  I!+!  J!+   K +- *4! A  L!*4  M  N +6  O!*% +.! K *G  P +%(  ! G *D +D " +
      " Q +# *K!*G!+D!+# +G +A +4!+% +K!+4!*D!+K!*K"
    Array.tabulate(121 * 3)(i => src.charAt(i) - 32)
  private val (affixes: Array[Byte], affixHeads: Array[Int]) =
    val src = "# #s #, #e #.# the #.com/#Â # of # and" +
      " # in # to #\"#\">#\n#]# for # a # that #. # with #'# from # by #. The # on # as # is #ing" +
      " #\n\t#:#ed #(# at #ly #=\"# of the #. This #,# not #er #al #='#ful #ive #less #est #ize #" +
      "ous #"
    val bytes = new Array[Byte](167); val heads = new Array[Int](51)
    var idx = 1; var j = 0
    for c <- src do
      if c == '#' then { heads(idx) = j; idx += 1 } else { bytes(j) = c.toByte; j += 1 }
    (bytes, heads)

  private final class Huff(val single: Int, val fast: Array[Int], val count: Array[Int], val sym: Array[Int])
  private val FastBits = 10

  private def buildHuff(lengths: Array[Int], n: Int): Huff =
    val count = new Array[Int](16); var nz = 0; var last = -1
    var i = 0
    while i < n do { if lengths(i) != 0 then { count(lengths(i)) += 1; nz += 1; last = i }; i += 1 }
    if nz == 0 then throw new IllegalArgumentException("brotli: empty prefix code")
    if nz == 1 then new Huff(last, null, count, null)
    else
      val offs = new Array[Int](17)
      for l <- 1 to 15 do offs(l + 1) = offs(l) + count(l)
      val sym = new Array[Int](nz)
      val pos = offs.clone()
      i = 0
      while i < n do { if lengths(i) != 0 then { sym(pos(lengths(i))) = i; pos(lengths(i)) += 1 }; i += 1 }
      val fast = new Array[Int](1 << FastBits)
      var code = 0; var k = 0
      for l <- 1 to 15 do
        var c = 0
        while c < count(l) do
          if l <= FastBits then
            var rev = 0; var b = 0
            while b < l do { rev |= ((code >> b) & 1) << (l - 1 - b); b += 1 }
            var e = rev
            while e < (1 << FastBits) do { fast(e) = (l << 16) | sym(k); e += 1 << l }
          code += 1; k += 1; c += 1
        code <<= 1
      new Huff(-1, fast, count, sym)

  private final class Decoder(in: Array[Byte], start: Int, end: Int, sizeHint: Int):
    private var pos = start
    private var acc = 0L
    private var nb = 0
    private var out = new Array[Byte](math.max(sizeHint, 1 << 12))
    private var op = 0
    private var wbits = 16
    private var d0 = 4; private var d1 = 11; private var d2 = 15; private var d3 = 16

    private def fail(msg: String): Nothing = throw new IllegalArgumentException("brotli: " + msg)

    private def fill(n: Int): Unit =
      while nb < n do
        if pos >= end + 16 then fail("truncated input")
        val b = if pos < end then in(pos) & 0xff else 0
        pos += 1
        acc |= b.toLong << nb
        nb += 8
    private def bits(n: Int): Int =
      if n == 0 then 0
      else
        fill(n)
        val v = (acc & ((1L << n) - 1)).toInt
        acc >>>= n; nb -= n
        v
    private def align(): Unit = { val r = nb & 7; acc >>>= r; nb -= r }

    private def read(h: Huff): Int =
      if h.single >= 0 then h.single
      else
        fill(15)
        val e = h.fast((acc & ((1 << FastBits) - 1)).toInt)
        if e != 0 then
          val l = e >>> 16
          acc >>>= l; nb -= l
          e & 0xffff
        else
          var code = 0; var first = 0; var index = 0; var len = 1
          while len <= 15 do
            code |= ((acc >>> (len - 1)) & 1).toInt
            val c = h.count(len)
            if code - c < first then
              acc >>>= len; nb -= len
              return h.sym(index + (code - first))
            index += c; first += c; first <<= 1; code <<= 1; len += 1
          fail("invalid prefix code")

    private def ensure(n: Int): Unit =
      if n > out.length then out = java.util.Arrays.copyOf(out, math.max(n, out.length * 2))

    private def varLen(): Int =
      if bits(1) == 0 then 1
      else
        val n = bits(3)
        (1 << n) + 1 + bits(n)

    private def readPrefix(alphabet: Int): Huff =
      val lengths = new Array[Int](alphabet)
      val hskip = bits(2)
      if hskip == 1 then
        val nsym = bits(2) + 1
        val abits = 32 - Integer.numberOfLeadingZeros(alphabet - 1)
        val syms = Array.fill(nsym)(bits(abits))
        if syms.exists(_ >= alphabet) then fail("symbol out of range")
        nsym match
          case 1 => lengths(syms(0)) = 1
          case 2 => lengths(syms(0)) = 1; lengths(syms(1)) = 1
          case 3 => lengths(syms(0)) = 1; lengths(syms(1)) = 2; lengths(syms(2)) = 2
          case _ =>
            if bits(1) == 0 then syms.foreach(lengths(_) = 2)
            else { lengths(syms(0)) = 1; lengths(syms(1)) = 2; lengths(syms(2)) = 3; lengths(syms(3)) = 3 }
        buildHuff(lengths, alphabet)
      else
        val cl = new Array[Int](18)
        var space = 32; var numCodes = 0; var i = hskip
        while i < 18 && space > 0 do
          fill(4)
          val idx = (acc & 15).toInt
          val (l, v) =
            if (idx & 3) != 3 then (2, Array(0, 4, 3)(idx & 3))
            else if (idx & 4) == 0 then (3, 2)
            else if (idx & 8) == 0 then (4, 1) else (4, 5)
          acc >>>= l; nb -= l
          cl(CodeLenOrder(i)) = v
          if v != 0 then { space -= 32 >> v; numCodes += 1 }
          i += 1
        if numCodes != 1 && space != 0 then fail("bad code length code")
        val clh = buildHuff(cl, 18)
        var symbol = 0; var prev = 8; var repeat = 0; var repeatLen = 0; var sp = 32768
        while symbol < alphabet && sp > 0 do
          val c = read(clh)
          if c < 16 then
            repeat = 0
            lengths(symbol) = c; symbol += 1
            if c != 0 then { prev = c; sp -= 32768 >> c }
          else
            val extra = c - 14
            val newLen = if c == 16 then prev else 0
            if repeatLen != newLen then { repeat = 0; repeatLen = newLen }
            val old = repeat
            if repeat > 0 then { repeat -= 2; repeat <<= extra }
            repeat += bits(extra) + 3
            val delta = repeat - old
            if symbol + delta > alphabet then fail("code length overflow")
            var k = 0
            while k < delta do { lengths(symbol) = repeatLen; symbol += 1; k += 1 }
            if repeatLen != 0 then sp -= delta << (15 - repeatLen)
        if sp != 0 then fail("incomplete prefix code")
        buildHuff(lengths, alphabet)

    private def readContextMap(ntrees: Int, size: Int): Array[Int] =
      val map = new Array[Int](size)
      if ntrees < 2 then return map
      val rleMax = if bits(1) == 1 then bits(4) + 1 else 0
      val h = readPrefix(ntrees + rleMax)
      var i = 0
      while i < size do
        val code = read(h)
        if code == 0 then i += 1
        else if code <= rleMax then
          val reps = (1 << code) + bits(code)
          if i + reps > size then fail("context map overflow")
          i += reps
        else { map(i) = code - rleMax; i += 1 }
      if bits(1) == 1 then
        val mtf = Array.tabulate(256)(identity)
        i = 0
        while i < size do
          val idx = map(i); val v = mtf(idx)
          map(i) = v
          System.arraycopy(mtf, 0, mtf, 1, idx); mtf(0) = v
          i += 1
      map

    def run(): Array[Byte] =
      if bits(1) == 0 then wbits = 16
      else
        val n = bits(3)
        if n != 0 then wbits = 17 + n
        else
          val m = bits(3)
          if m == 1 then fail("large window streams are not supported")
          wbits = if m == 0 then 17 else 8 + m
      var last = false
      while !last do
        last = bits(1) == 1
        if last && bits(1) == 1 then return java.util.Arrays.copyOf(out, op)
        val mn = bits(2)
        if mn == 3 then
          if bits(1) != 0 then fail("reserved bit set")
          val sb = bits(2)
          var skip = 0
          for i <- 0 until sb do skip |= bits(8) << (8 * i)
          if sb > 0 then skip += 1
          align()
          var i = 0
          while i < skip do { bits(8); i += 1 }
        else
          val mlen = bits((4 + mn) * 4) + 1
          if !last && bits(1) == 1 then
            align()
            ensure(op + mlen)
            var i = 0
            while i < mlen do { out(op) = bits(8).toByte; op += 1; i += 1 }
          else metaBlock(mlen)
      java.util.Arrays.copyOf(out, op)

    private def metaBlock(mlen: Int): Unit =
      val nbl = Array(1, 1, 1)
      val typeH = new Array[Huff](3); val cntH = new Array[Huff](3)
      val bType = Array(0, 0, 0); val bType2 = Array(1, 1, 1); val bCount = Array(1 << 24, 1 << 24, 1 << 24)
      def readCount(c: Int): Int = { val s = read(cntH(c)); BlockBase(s) + bits(BlockExtra(s)) }
      def switchBlock(c: Int): Unit =
        val code = read(typeH(c))
        var t = code match { case 0 => bType2(c); case 1 => bType(c) + 1; case _ => code - 2 }
        if t >= nbl(c) then t -= nbl(c)
        bType2(c) = bType(c); bType(c) = t
        bCount(c) = readCount(c)
      for c <- 0 until 3 do
        nbl(c) = varLen()
        if nbl(c) >= 2 then
          typeH(c) = readPrefix(nbl(c) + 2); cntH(c) = readPrefix(26)
          bCount(c) = readCount(c)
      val npostfix = bits(2); val ndirect = bits(4) << npostfix
      val modes = Array.fill(nbl(0))(bits(2))
      val ntreesL = varLen(); val cmapL = readContextMap(ntreesL, nbl(0) * 64)
      val ntreesD = varLen(); val cmapD = readContextMap(ntreesD, nbl(2) * 4)
      val litH = Array.fill(ntreesL)(readPrefix(256))
      val icH = Array.fill(nbl(1))(readPrefix(704))
      val distH = Array.fill(ntreesD)(readPrefix(16 + ndirect + (48 << npostfix)))
      var remaining = mlen
      while remaining > 0 do
        if bCount(1) == 0 then switchBlock(1)
        bCount(1) -= 1
        val sym = read(icH(bType(1)))
        val cell = sym >> 6
        val insCode = CellIns(cell) + ((sym >> 3) & 7); val copyCode = CellCopy(cell) + (sym & 7)
        val insLen = InsBase(insCode) + bits(InsExtra(insCode))
        val copyLen = CopyBase(copyCode) + bits(CopyExtra(copyCode))
        if insLen > remaining then fail("insert exceeds meta-block")
        ensure(op + insLen)
        var k = 0
        while k < insLen do
          if bCount(0) == 0 then switchBlock(0)
          bCount(0) -= 1
          val p1 = if op > 0 then out(op - 1) & 0xff else 0
          val p2 = if op > 1 then out(op - 2) & 0xff else 0
          val m = modes(bType(0)) << 9
          val ctx = Lookup(m + p1) | Lookup(m + 256 + p2)
          out(op) = read(litH(cmapL(bType(0) * 64 + ctx))).toByte
          op += 1; k += 1
        remaining -= insLen
        if remaining > 0 then
          var code = 0
          if cell >= 2 then
            if bCount(2) == 0 then switchBlock(2)
            bCount(2) -= 1
            code = read(distH(cmapD(bType(2) * 4 + (if copyLen > 4 then 3 else copyLen - 2))))
          val dist =
            if code < 16 then
              val r = code match
                case 0 => d0; case 1 => d1; case 2 => d2; case 3 => d3
                case 4 => d0 - 1; case 5 => d0 + 1; case 6 => d0 - 2; case 7 => d0 + 2
                case 8 => d0 - 3; case 9 => d0 + 3; case 10 => d1 - 1; case 11 => d1 + 1
                case 12 => d1 - 2; case 13 => d1 + 2; case 14 => d1 - 3; case _ => d1 + 3
              if r <= 0 then fail("invalid distance")
              r
            else if code < 16 + ndirect then code - 15
            else
              val x = code - ndirect - 16
              val nbits = 1 + (x >> (npostfix + 1))
              val hcode = x >> npostfix; val lcode = x & ((1 << npostfix) - 1)
              val offset = ((2 + (hcode & 1)) << nbits) - 4
              ((offset + bits(nbits)) << npostfix) + lcode + ndirect + 1
          val maxDist = math.min(op, (1 << wbits) - 16)
          if dist <= maxDist then
            if code != 0 then { d3 = d2; d2 = d1; d1 = d0; d0 = dist }
            if copyLen > remaining then fail("copy exceeds meta-block")
            ensure(op + copyLen)
            var i = 0
            while i < copyLen do { out(op) = out(op - dist); op += 1; i += 1 }
            remaining -= copyLen
          else
            if copyLen < 4 || copyLen > 24 then fail("invalid dictionary reference")
            val wordId = dist - maxDist - 1
            val nbd = NDBits(copyLen)
            val tid = wordId >>> nbd
            if tid >= 121 then fail("invalid transform id")
            val n = writeWord(DOffset(copyLen) + (wordId & ((1 << nbd) - 1)) * copyLen, copyLen, tid)
            remaining -= n
            if remaining < 0 then fail("dictionary word exceeds meta-block")

    private def writeWord(wordOff: Int, wordLen: Int, tid: Int): Int =
      val dict = dictionary
      val pre = Transforms(3 * tid); val typ = Transforms(3 * tid + 1); val suf = Transforms(3 * tid + 2)
      val omitFirst = if typ - 11 >= 1 && typ - 11 <= 9 then math.min(typ - 11, wordLen) else 0
      val omitLast = if typ >= 1 && typ <= 9 then typ else 0
      val len = math.max(wordLen - omitFirst - omitLast, 0)
      val start = op
      ensure(op + affixHeads(pre + 1) - affixHeads(pre) + len + affixHeads(suf + 1) - affixHeads(suf))
      var i = affixHeads(pre)
      while i < affixHeads(pre + 1) do { out(op) = affixes(i); op += 1; i += 1 }
      val bodyStart = op
      i = 0
      while i < len do { out(op) = dict(wordOff + omitFirst + i); op += 1; i += 1 }
      if typ == 10 || typ == 11 then
        var p = bodyStart; var left = if typ == 10 then math.min(1, len) else len
        while left > 0 do
          val c = out(p) & 0xff
          if c < 0xc0 then
            if c >= 97 && c <= 122 then out(p) = (c ^ 32).toByte
            p += 1; left -= 1
          else if c < 0xe0 then
            if left >= 2 then out(p + 1) = (out(p + 1) ^ 32).toByte
            p += 2; left -= 2
          else
            if left >= 3 then out(p + 2) = (out(p + 2) ^ 5).toByte
            p += 3; left -= 3
      i = affixHeads(suf)
      while i < affixHeads(suf + 1) do { out(op) = affixes(i); op += 1; i += 1 }
      op - start
