package scalaui.cms

import Adaptation.{D50, XYZ}

enum Intent:
  case Perceptual, RelativeColorimetric, Saturation, AbsoluteColorimetric

/** A parsed ICC profile (v2 or v4). Holds the raw bytes and decodes tags on demand. */
final class Profile private (val bytes: Array[Byte], tagTable: Map[String, (Int, Int)]):
  import Profile.*

  private def sig(o: Int) = new String(bytes, o, 4, "ISO-8859-1")
  val version: (Int, Int) = (bytes(8) & 0xff, (bytes(9) & 0xff) >> 4)
  /** Profile class: `mntr`, `scnr`, `prtr`, `spac`, ... */
  val profileClass: String = sig(12)
  /** Data colour space signature: `RGB `, `GRAY`, `CMYK`, ... */
  val colorSpace: String = sig(16)
  /** Profile connection space: `XYZ ` or `Lab `. */
  val pcs: String = sig(20)
  val headerIntent: Int = u32(bytes, 64)
  def channels: Int = colorSpace match
    case "GRAY" => 1
    case "RGB " | "Lab " | "XYZ " | "YCbr" | "Luv " | "Yxy " | "HSV " | "HLS " | "CMY " | "3CLR" => 3
    case "CMYK" | "4CLR" => 4
    case "5CLR" => 5
    case "6CLR" => 6
    case "7CLR" => 7
    case "8CLR" => 8
    case other => throw new CmsException(s"unsupported colour space '$other'")

  def hasTag(s: String): Boolean = tagTable.contains(s)
  def tags: Iterable[String] = tagTable.keys
  def tag(s: String): Option[Array[Byte]] = tagTable.get(s).map((o, n) => java.util.Arrays.copyOfRange(bytes, o, o + n))

  private def xyzTag(s: String): Option[XYZ] = tag(s).filter(t => t.length >= 20 && new String(t, 0, 4, "ISO-8859-1") == "XYZ ").map(t => (s15(t, 8), s15(t, 12), s15(t, 16)))
  /** Media white point (`wtpt`), D50 when absent. */
  lazy val whitePoint: XYZ = xyzTag("wtpt").getOrElse(D50)
  /** Black point (`bkpt`) if present. */
  lazy val blackPoint: Option[XYZ] = xyzTag("bkpt")
  /** Chromatic adaptation matrix (`chad`, v4) from the media white to D50, if present. */
  lazy val chad: Option[Mat3.M] =
    tag("chad").filter(t => t.length >= 44 && new String(t, 0, 4, "ISO-8859-1") == "sf32").map(t => Array.tabulate(9)(i => s15(t, 8 + i * 4)))

  /** Human readable description (`desc`). */
  lazy val description: Option[String] = tag("desc").flatMap(decodeText)

  private lazy val matrixRgb: Option[(Mat3.M, Array[Curve])] =
    if colorSpace != "RGB " then None
    else
      for
        r <- xyzTag("rXYZ"); g <- xyzTag("gXYZ"); b <- xyzTag("bXYZ")
        rc <- tag("rTRC"); gc <- tag("gTRC"); bc <- tag("bTRC")
      yield (Array(r._1, g._1, b._1, r._2, g._2, b._2, r._3, g._3, b._3), Array(Curve.parse(rc, 0)._1, Curve.parse(gc, 0)._1, Curve.parse(bc, 0)._1))
  private lazy val matrixInv: Option[Mat3.M] = matrixRgb.flatMap((m, _) => Mat3.inverse(m))
  private lazy val grayCurve: Option[Curve] = if colorSpace == "GRAY" then tag("kTRC").map(Curve.parse(_, 0)._1) else None

  private val lutCache = new scala.collection.mutable.HashMap[String, Option[Lut]]
  private def lut(s: String): Option[Lut] = lutCache.synchronized(lutCache.getOrElseUpdate(s, tag(s).map(Lut.parse)))
  private def intentIndex(i: Intent) = i match
    case Intent.Perceptual => 0
    case Intent.RelativeColorimetric | Intent.AbsoluteColorimetric => 1
    case Intent.Saturation => 2
  private def pick(prefix: String, i: Intent): Option[Lut] = lut(prefix + intentIndex(i)).orElse(lut(prefix + "0"))
  private[cms] def a2b(i: Intent): Option[Lut] = pick("A2B", i)
  private[cms] def b2a(i: Intent): Option[Lut] = pick("B2A", i)

  /** Whether the profile can convert device values to the PCS. */
  def canInput(i: Intent = Intent.Perceptual): Boolean = a2b(i).isDefined || matrixRgb.isDefined || grayCurve.isDefined
  /** Whether the profile can convert PCS values to device values. */
  def canOutput(i: Intent = Intent.Perceptual): Boolean = b2a(i).isDefined || matrixInv.isDefined || grayCurve.isDefined

  /** Device values (0..1 per channel) to PCS XYZ relative to D50. */
  def toXyz(dev: Array[Double], i: Intent = Intent.RelativeColorimetric): XYZ =
    a2b(i) match
      case Some(l) => decodePcs(l(dev), l.legacyLab)
      case None => matrixRgb match
        case Some((m, c)) => Mat3(m, c(0).eval(dev(0)), c(1).eval(dev(1)), c(2).eval(dev(2)))
        case None => grayCurve match
          case Some(c) => val y = c.eval(dev(0)); (D50._1 * y, y, D50._3 * y)
          case None => throw new CmsException("profile has no device-to-PCS transform")

  /** PCS XYZ relative to D50 to device values (clamped to 0..1). */
  def fromXyz(x: Double, y: Double, z: Double, i: Intent = Intent.RelativeColorimetric): Array[Double] =
    b2a(i) match
      case Some(l) => l(encodePcs(x, y, z, l.legacyLab)).map(clamp01)
      case None => matrixInv match
        case Some(inv) =>
          val c = matrixRgb.get._2
          val (r, g, b) = Mat3(inv, x, y, z)
          Array(c(0).inverse(clamp01(r)), c(1).inverse(clamp01(g)), c(2).inverse(clamp01(b)))
        case None => grayCurve match
          case Some(c) => Array(c.inverse(clamp01(y)))
          case None => throw new CmsException("profile has no PCS-to-device transform")

  private def decodePcs(v: Array[Double], legacy: Boolean): XYZ =
    if pcs == "Lab " then
      val k = if legacy then 65535.0 / 65280 else 1.0
      Adaptation.labToXyz(v(0) * k * 100, v(1) * k * 255 - 128, v(2) * k * 255 - 128)
    else (v(0) * XyzScale, v(1) * XyzScale, v(2) * XyzScale)

  private def encodePcs(x: Double, y: Double, z: Double, legacy: Boolean): Array[Double] =
    if pcs == "Lab " then
      val k = if legacy then 65535.0 / 65280 else 1.0
      val (l, a, b) = Adaptation.xyzToLab(x, y, z)
      Array(l / 100 / k, (a + 128) / 255 / k, (b + 128) / 255 / k)
    else Array(x / XyzScale, y / XyzScale, z / XyzScale)

object Profile:
  /** XYZ PCS encoding: 1.0 normalised corresponds to 1 + 32767/32768. */
  private[cms] val XyzScale = 65535.0 / 32768
  private[cms] def clamp01(v: Double) = if v < 0 then 0.0 else if v > 1 then 1.0 else v
  private[cms] def u32(d: Array[Byte], o: Int): Int =
    ((d(o) & 0xff) << 24) | ((d(o + 1) & 0xff) << 16) | ((d(o + 2) & 0xff) << 8) | (d(o + 3) & 0xff)
  private[cms] def s15(d: Array[Byte], o: Int): Double = u32(d, o) / 65536.0

  def parse(bytes: Array[Byte]): Profile =
    if bytes.length < 132 then throw new CmsException("ICC profile too short")
    if new String(bytes, 36, 4, "ISO-8859-1") != "acsp" then throw new CmsException("missing 'acsp' signature")
    val count = u32(bytes, 128)
    if count < 0 || count > 1000 || 132 + count * 12 > bytes.length then throw new CmsException("bad ICC tag table")
    val tags = (0 until count).flatMap { i =>
      val e = 132 + i * 12
      val off = u32(bytes, e + 4); val size = u32(bytes, e + 8)
      if off < 0 || size < 0 || off.toLong + size > bytes.length then throw new CmsException("ICC tag out of bounds")
      Some(new String(bytes, e, 4, "ISO-8859-1") -> (off, size))
    }.toMap
    new Profile(bytes, tags)

  private def decodeText(t: Array[Byte]): Option[String] =
    if t.length < 12 then None
    else new String(t, 0, 4, "ISO-8859-1") match
      case "desc" =>
        val n = u32(t, 8)
        if n <= 0 || 12 + n > t.length then None else Some(new String(t, 12, n - 1, "ISO-8859-1"))
      case "mluc" =>
        val count = u32(t, 8)
        if count < 1 || t.length < 28 then None
        else
          val len = u32(t, 20); val off = u32(t, 24)
          if off < 0 || len < 0 || off + len > t.length then None else Some(new String(t, off, len, "UTF-16BE"))
      case "text" => Some(new String(t, 8, math.max(0, t.length - 9), "ISO-8859-1"))
      case _ => None

  // built-in profiles (see [[Profiles]])
  def sRGB: Profile = Profiles.sRGB
  def displayP3: Profile = Profiles.displayP3
  def adobeRGB: Profile = Profiles.adobeRGB
  def linearSRGB: Profile = Profiles.linearSRGB
