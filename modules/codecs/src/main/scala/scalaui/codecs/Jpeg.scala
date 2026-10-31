package scalaui.codecs

/** JPEG decoder: baseline and progressive Huffman, 8-bit, 1/3/4 components, any sampling factors,
  * restart intervals, JFIF and Adobe (RGB, YCbCr, CMYK, YCCK). Arithmetic coding and 12-bit are not supported.
  */
object Jpeg:
  def matches(d: Array[Byte]): Boolean = d.length > 3 && (d(0) & 0xff) == 0xff && (d(1) & 0xff) == 0xd8

  private val Zigzag = Array(
    0, 1, 8, 16, 9, 2, 3, 10, 17, 24, 32, 25, 18, 11, 4, 5, 12, 19, 26, 33, 40, 48, 41, 34, 27, 20, 13, 6, 7, 14, 21,
    28, 35, 42, 49, 56, 57, 50, 43, 36, 29, 22, 15, 23, 30, 37, 44, 51, 58, 59, 52, 45, 38, 31, 39, 46, 53, 60, 61,
    54, 47, 55, 62, 63
  )

  private final class Huff(counts: Array[Int], symbols: Array[Int]):
    // fast 9-bit lookup: (length << 8) | symbol, 0 when the code is longer than 9 bits
    val lookup = new Array[Int](512)
    val maxCode = new Array[Int](18)
    val valPtr = new Array[Int](17)
    val minCode = new Array[Int](17)
    locally {
      var code = 0; var k = 0
      for len <- 1 to 16 do
        valPtr(len) = k; minCode(len) = code
        for _ <- 0 until counts(len - 1) do
          if len <= 9 then
            val base = code << (9 - len)
            for f <- 0 until (1 << (9 - len)) do lookup(base + f) = (len << 8) | symbols(k)
          code += 1; k += 1
        maxCode(len) = if counts(len - 1) > 0 then code - 1 else -1
        code <<= 1
      maxCode(17) = Int.MaxValue
    }
    def symbolAt(i: Int): Int = symbols(i)

  private final class Component(val id: Int, val h: Int, val v: Int, val tq: Int):
    var blocksW = 0; var blocksH = 0 // padded to whole MCUs
    var compW = 0; var compH = 0     // real size in samples
    var coeffs: Array[Short] = null
    var dcPred = 0
    var dcTable = 0; var acTable = 0

  private final class Reader(d: Array[Byte], var pos: Int):
    var bits = 0L; var count = 0
    var marker = 0 // pending marker byte hit while filling
    def reset(): Unit = { bits = 0; count = 0; marker = 0 }
    private def fill(): Unit =
      while count <= 48 do
        var b = 0
        if marker != 0 || pos >= d.length then b = 0
        else
          b = d(pos) & 0xff; pos += 1
          if b == 0xff then
            val n = if pos < d.length then d(pos) & 0xff else 0xd9
            if n == 0 then pos += 1
            else { marker = n; pos -= 1; b = 0 } // leave the marker in place
        bits = (bits << 8) | b; count += 8
    def peek(n: Int): Int = { if count < n then fill(); ((bits >>> (count - n)) & ((1L << n) - 1)).toInt }
    def skip(n: Int): Unit = count -= n
    def bit(): Int = { if count < 1 then fill(); count -= 1; ((bits >>> count) & 1).toInt }
    def receive(n: Int): Int = if n == 0 then 0 else { if count < n then fill(); count -= n; ((bits >>> count) & ((1L << n) - 1)).toInt }
    def decode(h: Huff): Int =
      val look = h.lookup(peek(9))
      if look != 0 then { skip(look >> 8); look & 0xff }
      else
        var code = peek(16); var len = 10; var result = -1
        while result < 0 && len <= 16 do
          val c = code >> (16 - len)
          if h.maxCode(len) >= 0 && c <= h.maxCode(len) && c >= h.minCode(len) then
            result = h.symbolAt(h.valPtr(len) + c - h.minCode(len)); skip(len)
          else len += 1
        if result < 0 then throw new ImageFormatException("bad Huffman code")
        result
    /** Aligns to a byte boundary and consumes an RSTn marker if present. */
    def restart(): Unit =
      reset()
      // find the marker
      while pos + 1 < d.length && !((d(pos) & 0xff) == 0xff && (d(pos + 1) & 0xff) >= 0xd0 && (d(pos + 1) & 0xff) <= 0xd7) do
        if (d(pos) & 0xff) == 0xff && (d(pos + 1) & 0xff) != 0 && (d(pos + 1) & 0xff) != 0xff then return // other marker: stop
        pos += 1
      if pos + 1 < d.length then pos += 2

  private def extend(v: Int, t: Int): Int = if t == 0 then 0 else if v < (1 << (t - 1)) then v - (1 << t) + 1 else v

  def decode(d: Array[Byte]): Image =
    if !matches(d) then throw new ImageFormatException("not a JPEG")
    var pos = 2
    val qt = Array.fill(4)(new Array[Int](64))
    val dcT = new Array[Huff](4); val acT = new Array[Huff](4)
    var comps = Array.empty[Component]
    var width = 0; var height = 0; var progressive = false
    var restartInterval = 0
    var adobe = -1
    var hmax = 1; var vmax = 1; var mcusX = 0; var mcusY = 0
    var eobrun = 0
    var done = false

    def u16(o: Int) = ((d(o) & 0xff) << 8) | (d(o + 1) & 0xff)

    while !done && pos + 4 <= d.length do
      if (d(pos) & 0xff) != 0xff then pos += 1
      else
        val m = d(pos + 1) & 0xff
        if m == 0xff then pos += 1
        else if m == 0 || (m >= 0xd0 && m <= 0xd7) || m == 0x01 then pos += 2
        else if m == 0xd9 then done = true
        else
          val len = u16(pos + 2); val body = pos + 4; val end = pos + 2 + len
          m match
            case 0xdb =>
              var p = body
              while p < end do
                val pq = (d(p) & 0xff) >> 4; val tq = d(p) & 15; p += 1
                for i <- 0 until 64 do
                  qt(tq)(Zigzag(i)) = if pq == 0 then d(p + i) & 0xff else u16(p + i * 2)
                p += (if pq == 0 then 64 else 128)
            case 0xc4 =>
              var p = body
              while p < end do
                val tc = (d(p) & 0xff) >> 4; val th = d(p) & 15; p += 1
                val counts = Array.tabulate(16)(i => d(p + i) & 0xff); p += 16
                val total = counts.sum
                val syms = Array.tabulate(total)(i => d(p + i) & 0xff); p += total
                (if tc == 0 then dcT else acT)(th) = new Huff(counts, syms)
            case 0xc0 | 0xc1 | 0xc2 =>
              progressive = m == 0xc2
              if (d(body) & 0xff) != 8 then throw new ImageFormatException("only 8-bit JPEG supported")
              height = u16(body + 1); width = u16(body + 3)
              val n = d(body + 5) & 0xff
              comps = Array.tabulate(n)(i => new Component(d(body + 6 + i * 3) & 0xff, (d(body + 7 + i * 3) & 0xff) >> 4, d(body + 7 + i * 3) & 15, d(body + 8 + i * 3) & 0xff))
              if width <= 0 || height <= 0 then throw new ImageFormatException("bad JPEG size")
              hmax = comps.map(_.h).max; vmax = comps.map(_.v).max
              mcusX = (width + 8 * hmax - 1) / (8 * hmax); mcusY = (height + 8 * vmax - 1) / (8 * vmax)
              comps.foreach { c =>
                c.blocksW = mcusX * c.h; c.blocksH = mcusY * c.v
                c.compW = (width * c.h + hmax - 1) / hmax; c.compH = (height * c.v + vmax - 1) / vmax
                c.coeffs = new Array[Short](c.blocksW * c.blocksH * 64)
              }
            case 0xc3 | 0xc5 | 0xc6 | 0xc7 | 0xc9 | 0xca | 0xcb | 0xcd | 0xce | 0xcf =>
              throw new ImageFormatException("unsupported JPEG coding (lossless / arithmetic / hierarchical)")
            case 0xdd => restartInterval = u16(body)
            case 0xee => if len >= 14 && new String(d, body, 5, "ISO-8859-1") == "Adobe" then adobe = d(body + 11) & 0xff
            case 0xda =>
              if comps.isEmpty then throw new ImageFormatException("SOS before SOF")
              val ns = d(body) & 0xff
              val scanComps = (0 until ns).map { i =>
                val c = comps.find(_.id == (d(body + 1 + i * 2) & 0xff)).getOrElse(throw new ImageFormatException("bad scan component"))
                c.dcTable = (d(body + 2 + i * 2) & 0xff) >> 4; c.acTable = d(body + 2 + i * 2) & 15; c
              }
              val ss = d(body + 1 + ns * 2) & 0xff; val se = d(body + 2 + ns * 2) & 0xff
              val ah = (d(body + 3 + ns * 2) & 0xff) >> 4; val al = d(body + 3 + ns * 2) & 15
              val r = new Reader(d, end)
              decodeScan(r, scanComps, ss, se, ah, al)
              pos = r.pos
              // advance to next marker
              while pos + 1 < d.length && !((d(pos) & 0xff) == 0xff && (d(pos + 1) & 0xff) != 0 && ((d(pos + 1) & 0xff) < 0xd0 || (d(pos + 1) & 0xff) > 0xd7) && (d(pos + 1) & 0xff) != 0xff) do pos += 1
              pos -= (len + 2) - (len + 2) // no-op; pos already points at marker
              // loop continues from marker (so skip the default pos = end below)
            case _ => ()
          if m != 0xda then pos = end
    if comps.isEmpty then throw new ImageFormatException("no frame in JPEG")

    def decodeScan(r: Reader, sc: Seq[Component], ss: Int, se: Int, ah: Int, al: Int): Unit =
      comps.foreach(_.dcPred = 0); eobrun = 0
      val single = sc.size == 1
      val c0 = sc.head
      val totalMcus = if single then ((c0.compW + 7) / 8) * ((c0.compH + 7) / 8) else mcusX * mcusY
      val singleBW = (c0.compW + 7) / 8
      var mcu = 0
      while mcu < totalMcus do
        if restartInterval > 0 && mcu > 0 && mcu % restartInterval == 0 then
          r.restart(); comps.foreach(_.dcPred = 0); eobrun = 0
        if single then
          val bx = mcu % singleBW; val by = mcu / singleBW
          block(r, c0, by * c0.blocksW + bx, ss, se, ah, al)
        else
          val mx = mcu % mcusX; val my = mcu / mcusX
          for c <- sc; v <- 0 until c.v; h <- 0 until c.h do
            block(r, c, (my * c.v + v) * c.blocksW + mx * c.h + h, ss, se, ah, al)
        mcu += 1

    def block(r: Reader, c: Component, index: Int, ss: Int, se: Int, ah: Int, al: Int): Unit =
      val base = index * 64
      val co = c.coeffs
      if !progressive then
        val t = r.decode(dcT(c.dcTable))
        c.dcPred += extend(r.receive(t), t)
        co(base) = c.dcPred.toShort
        val ac = acT(c.acTable)
        var k = 1
        while k < 64 do
          val rs = r.decode(ac); val s = rs & 15; val rr = rs >> 4
          if s == 0 then { if rr == 15 then k += 16 else k = 64 }
          else { k += rr; if k < 64 then co(base + Zigzag(k)) = extend(r.receive(s), s).toShort; k += 1 }
      else if ss == 0 then
        if ah == 0 then
          val t = r.decode(dcT(c.dcTable))
          c.dcPred += extend(r.receive(t), t)
          co(base) = (c.dcPred << al).toShort
        else if r.bit() == 1 then co(base) = (co(base) | (1 << al)).toShort
      else if ah == 0 then
        if eobrun > 0 then eobrun -= 1
        else
          val ac = acT(c.acTable)
          var k = ss
          while k <= se do
            val rs = r.decode(ac); val s = rs & 15; val rr = rs >> 4
            if s == 0 then
              if rr < 15 then { eobrun = (1 << rr) - 1; if rr > 0 then eobrun += r.receive(rr); k = 64 }
              else k += 16
            else { k += rr; if k < 64 then co(base + Zigzag(k)) = (extend(r.receive(s), s) * (1 << al)).toShort; k += 1 }
      else
        val p1 = 1 << al; val m1 = -1 << al
        var k = ss
        def refine(i: Int): Unit =
          val cv = co(i)
          if r.bit() == 1 && (cv & p1) == 0 then co(i) = (if cv >= 0 then cv + p1 else cv + m1).toShort
        if eobrun == 0 then
          val ac = acT(c.acTable)
          var stop = false
          while k <= se && !stop do
            val rs = r.decode(ac); val s = rs & 15; var rr = rs >> 4
            var value = 0
            if s != 0 then value = if r.bit() == 1 then p1 else m1
            else if rr != 15 then { eobrun = 1 << rr; if rr > 0 then eobrun += r.receive(rr); stop = true }
            if !stop then
              var brk = false
              while k <= se && !brk do
                val i = base + Zigzag(k)
                if co(i) != 0 then refine(i)
                else { rr -= 1; if rr < 0 then brk = true }
                if !brk then k += 1
              if value != 0 && k <= se then co(base + Zigzag(k)) = value.toShort
              k += 1
        if eobrun > 0 then
          while k <= se do { val i = base + Zigzag(k); if co(i) != 0 then refine(i); k += 1 }
          eobrun -= 1

    // --- IDCT + upsample + color convert ---
    // Float separable IDCT. Zero coefficients are skipped (adding 0 * cos never changes the sum), the order of the remaining
    // additions is the plain matrix-product order, so results are bit-identical to the unskipped formulation.
    val cosT = new Array[Float](64) // cosT(x * 8 + u)
    for x <- 0 until 8; u <- 0 until 8 do
      cosT(x * 8 + u) = (math.cos((2 * x + 1) * u * math.Pi / 16) * (if u == 0 then math.sqrt(0.125) else 0.5)).toFloat
    val planes = new Array[Array[Byte]](comps.length)
    val strides = new Array[Int](comps.length)
    var ci0 = 0
    while ci0 < comps.length do
      val c = comps(ci0)
      val pw = c.blocksW * 8; val ph = c.blocksH * 8
      val plane = new Array[Byte](pw * ph)
      val q = qt(c.tq)
      val tmp = new Array[Float](64); val blk = new Array[Float](64)
      val coeffs = c.coeffs
      var by = 0
      while by < c.blocksH do
        var bx = 0
        while bx < c.blocksW do
          val base = (by * c.blocksW + bx) * 64
          var i = 0
          while i < 64 do { blk(i) = (coeffs(base + i) * q(i)).toFloat; i += 1 }
          // rows
          var y = 0
          while y < 8 do
            val r8 = y * 8
            val rowZero = blk(r8) == 0f && blk(r8 + 1) == 0f && blk(r8 + 2) == 0f && blk(r8 + 3) == 0f &&
              blk(r8 + 4) == 0f && blk(r8 + 5) == 0f && blk(r8 + 6) == 0f && blk(r8 + 7) == 0f
            var x = 0
            while x < 8 do
              var sum = 0f
              if !rowZero then
                var u = 0
                while u < 8 do { val bv = blk(r8 + u); if bv != 0f then sum += cosT(x * 8 + u) * bv; u += 1 }
              tmp(r8 + x) = sum
              x += 1
            y += 1
          // columns
          var x = 0
          while x < 8 do
            var y2 = 0
            while y2 < 8 do
              var sum = 0f; var v = 0
              while v < 8 do { val tv = tmp(v * 8 + x); if tv != 0f then sum += cosT(y2 * 8 + v) * tv; v += 1 }
              val px = Math.round(sum + 128f).max(0).min(255)
              plane((by * 8 + y2) * pw + bx * 8 + x) = px.toByte
              y2 += 1
            x += 1
          bx += 1
        by += 1
      planes(ci0) = plane; strides(ci0) = pw
      ci0 += 1

    // bring every component to full resolution (bilinear, centered); full-resolution ones are used in place
    val full = new Array[Array[Byte]](comps.length)
    val fullStride = new Array[Int](comps.length)
    var cj = 0
    while cj < comps.length do
      val c = comps(cj); val p = planes(cj); val pw = strides(cj)
      if c.h == hmax && c.v == vmax then { full(cj) = p; fullStride(cj) = pw }
      else
        val up = new Array[Byte](width * height); fullStride(cj) = width
        val xa0 = new Array[Int](width); val xa1 = new Array[Int](width); val xt = new Array[Double](width)
        var x = 0
        while x < width do
          val fx = ((x + 0.5) * c.h / hmax - 0.5).max(0)
          val x0 = fx.toInt
          xa0(x) = x0; xa1(x) = math.min(x0 + 1, c.compW - 1); xt(x) = fx - x0
          x += 1
        var y = 0
        while y < height do
          val fy = ((y + 0.5) * c.v / vmax - 0.5).max(0)
          val y0 = fy.toInt; val y1 = math.min(y0 + 1, c.compH - 1); val ty = fy - y0
          val r0 = y0 * pw; val r1 = y1 * pw; val orow = y * width
          x = 0
          while x < width do
            val x0 = xa0(x); val x1 = xa1(x); val tx = xt(x)
            val a = p(r0 + x0) & 0xff; val b = p(r0 + x1) & 0xff
            val cc = p(r1 + x0) & 0xff; val dd = p(r1 + x1) & 0xff
            up(orow + x) = ((a * (1 - tx) + b * tx) * (1 - ty) + (cc * (1 - tx) + dd * tx) * ty + 0.5).toInt.toByte
            x += 1
          y += 1
        full(cj) = up
      cj += 1

    val out = new Array[Byte](width * height * 4)
    @inline def clamp(v: Int) = if v < 0 then 0 else if v > 255 then 255 else v
    val n = comps.length
    val rgbSource = n == 3 && (adobe == 0 || (adobe < 0 && comps.map(_.id) == Array('R'.toInt, 'G'.toInt, 'B'.toInt)))
    if n != 1 && n != 3 && n != 4 then throw new ImageFormatException(s"unsupported JPEG component count $n")
    val ycc = if n == 3 then !rgbSource else adobe == 2
    val p0 = full(0); val s0 = fullStride(0)
    val p1 = if n >= 3 then full(1) else null; val s1 = if n >= 3 then fullStride(1) else 0
    val p2 = if n >= 3 then full(2) else null; val s2 = if n >= 3 then fullStride(2) else 0
    val p3 = if n == 4 then full(3) else null; val s3 = if n == 4 then fullStride(3) else 0
    var y = 0
    while y < height do
      var x = 0
      var o = y * width * 4
      while x < width do
        if n == 1 then
          val g = p0(y * s0 + x)
          out(o) = g; out(o + 1) = g; out(o + 2) = g
        else
          val c0 = p0(y * s0 + x) & 0xff; val c1 = p1(y * s1 + x) & 0xff; val c2 = p2(y * s2 + x) & 0xff
          var r = c0; var g = c1; var b = c2
          if ycc then
            val cb = c1 - 128; val cr = c2 - 128
            r = clamp(Math.round(c0 + 1.402f * cr)); g = clamp(Math.round(c0 - 0.344136f * cb - 0.714136f * cr)); b = clamp(Math.round(c0 + 1.772f * cb))
          if n == 4 then
            // YCCK: libjpeg turns the converted RGB into CMY (255 - rgb); Adobe stores inverted inks, so the colour is the RGB as is times K,
            // while plain (inverted) CMYK samples already are 255 - ink
            if ycc then { r = 255 - r; g = 255 - g; b = 255 - b }
            val k = p3(y * s3 + x) & 0xff
            r = r * k / 255; g = g * k / 255; b = b * k / 255
          out(o) = r.toByte; out(o + 1) = g.toByte; out(o + 2) = b.toByte
        out(o + 3) = -1
        x += 1; o += 4
      y += 1
    Image(width, height, out)
