package scalaui.cms

/** 3x3 matrices (row-major `Array[Double](9)`) and the white points / chromatic adaptation the ICC PCS needs. */
object Mat3:
  type M = Array[Double]
  val identity: M = Array(1.0, 0, 0, 0, 1, 0, 0, 0, 1)

  def mul(a: M, b: M): M =
    val r = new Array[Double](9)
    var i = 0
    while i < 3 do
      var j = 0
      while j < 3 do
        r(i * 3 + j) = a(i * 3) * b(j) + a(i * 3 + 1) * b(3 + j) + a(i * 3 + 2) * b(6 + j)
        j += 1
      i += 1
    r

  def apply(m: M, x: Double, y: Double, z: Double): (Double, Double, Double) =
    (m(0) * x + m(1) * y + m(2) * z, m(3) * x + m(4) * y + m(5) * z, m(6) * x + m(7) * y + m(8) * z)

  def det(m: M): Double =
    m(0) * (m(4) * m(8) - m(5) * m(7)) - m(1) * (m(3) * m(8) - m(5) * m(6)) + m(2) * (m(3) * m(7) - m(4) * m(6))

  /** Inverse, or `None` when singular. */
  def inverse(m: M): Option[M] =
    val d = det(m)
    if math.abs(d) < 1e-12 then None
    else
      Some(Array(
        (m(4) * m(8) - m(5) * m(7)) / d, (m(2) * m(7) - m(1) * m(8)) / d, (m(1) * m(5) - m(2) * m(4)) / d,
        (m(5) * m(6) - m(3) * m(8)) / d, (m(0) * m(8) - m(2) * m(6)) / d, (m(2) * m(3) - m(0) * m(5)) / d,
        (m(3) * m(7) - m(4) * m(6)) / d, (m(1) * m(6) - m(0) * m(7)) / d, (m(0) * m(4) - m(1) * m(3)) / d))

  def diag(x: Double, y: Double, z: Double): M = Array(x, 0, 0, 0, y, 0, 0, 0, z)

/** CIE XYZ white points, chromatic adaptation and CIELAB. */
object Adaptation:
  type XYZ = (Double, Double, Double)
  /** The ICC profile connection space white (D50). */
  val D50: XYZ = (0.9642, 1.0, 0.8249)
  val D65: XYZ = (0.95047, 1.0, 1.08883)

  def whiteFromXy(x: Double, y: Double): XYZ = (x / y, 1.0, (1 - x - y) / y)

  private val bradfordCone: Mat3.M = Array(0.8951, 0.2664, -0.1614, -0.7502, 1.7135, 0.0367, 0.0389, -0.0685, 1.0296)
  private val bradfordInv: Mat3.M = Mat3.inverse(bradfordCone).get

  /** Bradford chromatic adaptation matrix taking XYZ under white `src` to XYZ under white `dst`. */
  def bradford(src: XYZ, dst: XYZ): Mat3.M =
    val (rs, gs, bs) = Mat3(bradfordCone, src._1, src._2, src._3)
    val (rd, gd, bd) = Mat3(bradfordCone, dst._1, dst._2, dst._3)
    Mat3.mul(bradfordInv, Mat3.mul(Mat3.diag(rd / rs, gd / gs, bd / bs), bradfordCone))

  /** RGB -> XYZ matrix for primaries given as xy chromaticities and a white XYZ. */
  def rgbToXyz(r: (Double, Double), g: (Double, Double), b: (Double, Double), white: XYZ): Mat3.M =
    val p: Mat3.M = Array(r._1 / r._2, g._1 / g._2, b._1 / b._2, 1.0, 1.0, 1.0,
      (1 - r._1 - r._2) / r._2, (1 - g._1 - g._2) / g._2, (1 - b._1 - b._2) / b._2)
    val s = Mat3(Mat3.inverse(p).get, white._1, white._2, white._3)
    Mat3.mul(p, Mat3.diag(s._1, s._2, s._3))

  private def labF(t: Double): Double = if t > 216.0 / 24389 then math.cbrt(t) else (24389.0 / 27 * t + 16) / 116
  private def labFInv(f: Double): Double = if f > 6.0 / 29 then f * f * f else (116 * f - 16) * 27 / 24389

  /** XYZ (relative to D50) to CIELAB. */
  def xyzToLab(x: Double, y: Double, z: Double): XYZ =
    val fx = labF(x / D50._1); val fy = labF(y / D50._2); val fz = labF(z / D50._3)
    (116 * fy - 16, 500 * (fx - fy), 200 * (fy - fz))

  def labToXyz(l: Double, a: Double, b: Double): XYZ =
    val fy = (l + 16) / 116; val fx = fy + a / 500; val fz = fy - b / 200
    (labFInv(fx) * D50._1, labFInv(fy) * D50._2, labFInv(fz) * D50._3)
