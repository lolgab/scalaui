package scalaui.cms

/** A one-dimensional tone curve mapping 0..1 to 0..1 (ICC `curv` / `para`). */
sealed trait Curve:
  def eval(x: Double): Double
  /** Inverse (output to input), clamped to 0..1. */
  def inverse(y: Double): Double

object Curve:
  private def clamp01(v: Double) = if v < 0 then 0.0 else if v > 1 then 1.0 else v
  private def pw(x: Double, g: Double) = if x <= 0 then 0.0 else math.pow(x, g)

  case object Identity extends Curve:
    def eval(x: Double): Double = x
    def inverse(y: Double): Double = y

  final case class Gamma(g: Double) extends Curve:
    def eval(x: Double): Double = pw(x, g)
    def inverse(y: Double): Double = clamp01(pw(y, 1 / g))

  /** Sampled curve with linear interpolation; `t` values in 0..1. */
  final case class Table(t: Array[Float]) extends Curve:
    def eval(x: Double): Double =
      val n = t.length
      if n == 1 then t(0).toDouble
      else
        val p = clamp01(x) * (n - 1); val i = math.min(p.toInt, n - 2); val f = p - i
        t(i) + (t(i + 1) - t(i)) * f
    def inverse(y: Double): Double =
      val n = t.length
      if n == 1 then 0.0
      else
        val asc = t(n - 1) >= t(0)
        var lo = 0; var hi = n // first index whose value reaches y
        while lo < hi do
          val mid = (lo + hi) >>> 1
          if (if asc then t(mid) >= y else t(mid) <= y) then hi = mid else lo = mid + 1
        if lo == 0 then 0.0
        else if lo >= n then 1.0
        else clamp01((lo - 1 + (y - t(lo - 1)) / (t(lo) - t(lo - 1))) / (n - 1))

  /** ICC parametric curve; `tpe` 0..4 and parameters in the order g, a, b, c, d, e, f. */
  final case class Parametric(tpe: Int, p: Array[Double]) extends Curve:
    private val g = p(0)
    private def at(i: Int) = if i < p.length then p(i) else 0.0
    private val a = at(1); private val b = at(2); private val c = at(3); private val d = at(4); private val e = at(5); private val f = at(6)
    def eval(x: Double): Double = clamp01(tpe match
      case 0 => pw(x, g)
      case 1 => if a != 0 && x >= -b / a then pw(a * x + b, g) else 0.0
      case 2 => if a != 0 && x >= -b / a then pw(a * x + b, g) + c else c
      case 3 => if x >= d then pw(a * x + b, g) else c * x
      case _ => if x >= d then pw(a * x + b, g) + e else c * x + f)
    def inverse(y: Double): Double = clamp01(tpe match
      case 0 => pw(y, 1 / g)
      case 1 => if a == 0 then 0.0 else (pw(y, 1 / g) - b) / a
      case 2 => if a == 0 then 0.0 else (pw(y - c, 1 / g) - b) / a
      case 3 =>
        val yd = pw(a * d + b, g)
        if y >= yd || c == 0 then (if a == 0 then 0.0 else (pw(y, 1 / g) - b) / a) else y / c
      case _ =>
        val yd = c * d + f
        if y >= yd || c == 0 then (if a == 0 then 0.0 else (pw(y - e, 1 / g) - b) / a) else (y - f) / c)

  private def u16(d: Array[Byte], o: Int) = ((d(o) & 0xff) << 8) | (d(o + 1) & 0xff)
  private def u32(d: Array[Byte], o: Int) = (u16(d, o).toLong << 16 | u16(d, o + 2)).toInt
  private def s15(d: Array[Byte], o: Int) = u32(d, o) / 65536.0

  private val ParaCount = Array(1, 3, 4, 5, 7)

  /** Parses a `curv` or `para` element at `off`; returns the curve and its byte length (unpadded). */
  def parse(d: Array[Byte], off: Int): (Curve, Int) =
    if off + 12 > d.length then throw new CmsException("truncated curve")
    new String(d, off, 4, "ISO-8859-1") match
      case "curv" =>
        val n = u32(d, off + 8)
        if n < 0 || off + 12 + n * 2 > d.length then throw new CmsException("truncated curve")
        val c: Curve =
          if n == 0 then Identity
          else if n == 1 then
            val g = u16(d, off + 12) / 256.0
            if g == 1.0 then Identity else Gamma(g)
          else Table(Array.tabulate(n)(i => u16(d, off + 12 + i * 2) / 65535f))
        (c, 12 + n * 2)
      case "para" =>
        val t = u16(d, off + 8)
        if t > 4 then throw new CmsException(s"unknown parametric curve type $t")
        val n = ParaCount(t)
        if off + 12 + n * 4 > d.length then throw new CmsException("truncated curve")
        (Parametric(t, Array.tabulate(n)(i => s15(d, off + 12 + i * 4))), 12 + n * 4)
      case other => throw new CmsException(s"unknown curve type '$other'")

class CmsException(msg: String) extends RuntimeException(msg)
