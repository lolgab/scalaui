package scalaui.cms

/** One processing element of a LUT pipeline; works in place on a normalised (0..1) channel buffer. */
sealed trait Stage:
  def inCh: Int
  def outCh: Int
  def apply(buf: Array[Double]): Unit

object Stage:
  final class Curves(val curves: Array[Curve]) extends Stage:
    def inCh: Int = curves.length
    def outCh: Int = curves.length
    def apply(buf: Array[Double]): Unit =
      var i = 0
      while i < curves.length do { buf(i) = curves(i).eval(buf(i)); i += 1 }

  /** `out = m * in + offset` on three channels. */
  final class Matrix(val m: Array[Double], val offset: Array[Double]) extends Stage:
    def inCh = 3
    def outCh = 3
    def apply(buf: Array[Double]): Unit =
      val x = buf(0); val y = buf(1); val z = buf(2)
      buf(0) = m(0) * x + m(1) * y + m(2) * z + offset(0)
      buf(1) = m(3) * x + m(4) * y + m(5) * z + offset(1)
      buf(2) = m(6) * x + m(7) * y + m(8) * z + offset(2)

  /** Multidimensional lookup table; `grid(0)` is the slowest varying input. Data normalised to 0..1. */
  final class Clut(val grid: Array[Int], val outCh: Int, val data: Array[Float]) extends Stage:
    def inCh: Int = grid.length
    private val strides: Array[Int] =
      val s = new Array[Int](grid.length); var acc = outCh; var i = grid.length - 1
      while i >= 0 do { s(i) = acc; acc *= grid(i); i -= 1 }
      s

    def apply(buf: Array[Double]): Unit =
      val n = grid.length
      val idx = new Array[Int](n); val fr = new Array[Double](n)
      var i = 0
      while i < n do
        val g = grid(i)
        val x = if buf(i) < 0 then 0.0 else if buf(i) > 1 then 1.0 else buf(i)
        if g == 1 then { idx(i) = 0; fr(i) = 0 }
        else
          val p = x * (g - 1); val k = math.min(p.toInt, g - 2)
          idx(i) = k; fr(i) = p - k
        i += 1
      var base = 0; i = 0
      while i < n do { base += idx(i) * strides(i); i += 1 }
      val res = new Array[Double](outCh)
      if n == 3 then tetra(base, fr(0), fr(1), fr(2), res)
      else if n == 4 then
        // linear in the first input between two tetrahedral evaluations (as littleCMS does for CMYK)
        val r1 = new Array[Double](outCh)
        tetra(base, fr(1), fr(2), fr(3), res)
        if fr(0) > 0 && grid(0) > 1 then
          tetra(base + strides(0), fr(1), fr(2), fr(3), r1)
          var c = 0
          while c < outCh do { res(c) = res(c) + (r1(c) - res(c)) * fr(0); c += 1 }
      else linear(base, fr, res)
      System.arraycopy(res, 0, buf, 0, outCh)

    // tetrahedral interpolation over the last three dimensions starting at `base`
    private def tetra(base: Int, fx: Double, fy: Double, fz: Double, out: Array[Double]): Unit =
      val n = grid.length
      val sx = strides(n - 3); val sy = strides(n - 2); val sz = strides(n - 1)
      val dx = if grid(n - 3) > 1 then sx else 0; val dy = if grid(n - 2) > 1 then sy else 0; val dz = if grid(n - 1) > 1 then sz else 0
      var c = 0
      while c < outCh do
        val b = base + c
        val c000 = data(b); val c111 = data(b + dx + dy + dz)
        out(c) =
          if fx >= fy && fy >= fz then
            val c100 = data(b + dx); val c110 = data(b + dx + dy)
            c000 + fx * (c100 - c000) + fy * (c110 - c100) + fz * (c111 - c110)
          else if fx >= fz && fz > fy then
            val c100 = data(b + dx); val c101 = data(b + dx + dz)
            c000 + fx * (c100 - c000) + fz * (c101 - c100) + fy * (c111 - c101)
          else if fz > fx && fx >= fy then
            val c001 = data(b + dz); val c101 = data(b + dx + dz)
            c000 + fz * (c001 - c000) + fx * (c101 - c001) + fy * (c111 - c101)
          else if fy > fx && fx >= fz then
            val c010 = data(b + dy); val c110 = data(b + dx + dy)
            c000 + fy * (c010 - c000) + fx * (c110 - c010) + fz * (c111 - c110)
          else if fy >= fz && fz > fx then
            val c010 = data(b + dy); val c011 = data(b + dy + dz)
            c000 + fy * (c010 - c000) + fz * (c011 - c010) + fx * (c111 - c011)
          else
            val c001 = data(b + dz); val c011 = data(b + dy + dz)
            c000 + fz * (c001 - c000) + fy * (c011 - c001) + fx * (c111 - c011)
        c += 1

    private def linear(base: Int, fr: Array[Double], out: Array[Double]): Unit =
      val n = grid.length
      var c = 0
      while c < outCh do
        var acc = 0.0; var corner = 0
        while corner < (1 << n) do
          var w = 1.0; var off = base + c; var d = 0
          while d < n do
            if ((corner >> d) & 1) == 1 then { w *= fr(d); if grid(d) > 1 then off += strides(d) } else w *= 1 - fr(d)
            d += 1
          if w != 0 then acc += w * data(off)
          corner += 1
        out(c) = acc
        c += 1

/** A device <-> PCS lookup pipeline (lut8, lut16, lutAtoB or lutBtoA) with the PCS value encoding it implies. */
final class Lut(val stages: Vector[Stage], val inCh: Int, val outCh: Int, val legacyLab: Boolean):
  def apply(in: Array[Double]): Array[Double] =
    val buf = new Array[Double](math.max(math.max(inCh, outCh), 4))
    System.arraycopy(in, 0, buf, 0, inCh)
    var i = 0
    while i < stages.length do { stages(i)(buf); i += 1 }
    java.util.Arrays.copyOf(buf, outCh)

object Lut:
  private def u8(d: Array[Byte], o: Int) = d(o) & 0xff
  private def u16(d: Array[Byte], o: Int) = ((d(o) & 0xff) << 8) | (d(o + 1) & 0xff)
  private def u32(d: Array[Byte], o: Int) = (u16(d, o) << 16) | u16(d, o + 2)
  private def s15(d: Array[Byte], o: Int) = u32(d, o) / 65536.0

  def parse(d: Array[Byte]): Lut =
    if d.length < 32 then throw new CmsException("truncated LUT tag")
    new String(d, 0, 4, "ISO-8859-1") match
      case "mft1" => parseMft(d, wide = false)
      case "mft2" => parseMft(d, wide = true)
      case "mAB " => parseMab(d, atob = true)
      case "mBA " => parseMab(d, atob = false)
      case other => throw new CmsException(s"unsupported LUT type '$other'")

  private def parseMft(d: Array[Byte], wide: Boolean): Lut =
    val inCh = u8(d, 8); val outCh = u8(d, 9); val g = u8(d, 10)
    if inCh == 0 || outCh == 0 || inCh > 8 || outCh > 8 || g == 0 then throw new CmsException("bad lut dimensions")
    val (nIn, nOut, start) = if wide then (u16(d, 48), u16(d, 50), 52) else (256, 256, 48)
    val sz = if wide then 2 else 1
    val maxv = if wide then 65535f else 255f
    def read(o: Int) = (if wide then u16(d, o) else u8(d, o)) / maxv
    val clutN = math.pow(g, inCh).toInt * outCh
    if start + (inCh * nIn + clutN + outCh * nOut) * sz > d.length then throw new CmsException("truncated LUT")
    var p = start
    val inCurves: Array[Curve] = Array.tabulate(inCh) { _ =>
      val t = Array.tabulate(nIn)(i => read(p + i * sz)); p += nIn * sz; Curve.Table(t)
    }
    val clut = new Array[Float](clutN)
    var i = 0
    while i < clutN do { clut(i) = read(p + i * sz); i += 1 }
    p += clutN * sz
    val outCurves: Array[Curve] = Array.tabulate(outCh) { _ =>
      val t = Array.tabulate(nOut)(i => read(p + i * sz)); p += nOut * sz; Curve.Table(t)
    }
    val stages = Vector(new Stage.Curves(inCurves), new Stage.Clut(Array.fill(inCh)(g), outCh, clut), new Stage.Curves(outCurves))
    new Lut(stages, inCh, outCh, legacyLab = wide)

  private def curveSet(d: Array[Byte], off: Int, n: Int): Stage =
    var p = off
    val cs = Array.tabulate[Curve](n) { _ =>
      val (c, len) = Curve.parse(d, p); p += (len + 3) & ~3; c
    }
    new Stage.Curves(cs)

  private def parseMab(d: Array[Byte], atob: Boolean): Lut =
    val inCh = u8(d, 8); val outCh = u8(d, 9)
    val oB = u32(d, 12); val oMat = u32(d, 16); val oM = u32(d, 20); val oClut = u32(d, 24); val oA = u32(d, 28)
    if inCh == 0 || outCh == 0 || inCh > 8 || outCh > 8 then throw new CmsException("bad lut dimensions")
    // A curves sit on the device side, B (and M) curves on the PCS side
    val nPcs = if atob then outCh else inCh
    val nDev = if atob then inCh else outCh
    val b = if oB != 0 then Some(curveSet(d, oB, nPcs)) else None
    val m = if oM != 0 then Some(curveSet(d, oM, nPcs)) else None
    val mat = if oMat != 0 then Some(new Stage.Matrix(Array.tabulate(9)(i => s15(d, oMat + i * 4)), Array.tabulate(3)(i => s15(d, oMat + 36 + i * 4)))) else None
    val clut =
      if oClut == 0 then None
      else
        val grid = Array.tabulate(inCh)(i => u8(d, oClut + i))
        val prec = u8(d, oClut + 16)
        if grid.exists(_ < 1) || (prec != 1 && prec != 2) then throw new CmsException("bad CLUT")
        val count = grid.map(_.toLong).product * outCh
        if count > Int.MaxValue || oClut + 20 + count * prec > d.length then throw new CmsException("truncated CLUT")
        val data = Array.tabulate(count.toInt)(i => if prec == 1 then u8(d, oClut + 20 + i) / 255f else u16(d, oClut + 20 + i * 2) / 65535f)
        Some(new Stage.Clut(grid, outCh, data))
    val a = if oA != 0 then Some(curveSet(d, oA, nDev)) else None
    val order: Seq[Option[Stage]] = if atob then Seq(a, clut, m, mat, b) else Seq(b, mat, m, clut, a)
    new Lut(order.flatten.toVector, inCh, outCh, legacyLab = false)
