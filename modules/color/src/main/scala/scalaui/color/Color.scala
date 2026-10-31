package scalaui.color

import scala.math.pow

/** Non-premultiplied sRGB color, components in 0..1. */
final case class Color(r: Float, g: Float, b: Float, a: Float = 1f):
  def withAlpha(alpha: Float): Color = copy(a = alpha)
  def premultiplied: PremulColor = PremulColor(r * a, g * a, b * a, a)
  /** Packed 0xAARRGGBB, non-premultiplied. */
  def toArgb: Int =
    def c(v: Float) = (v.max(0f).min(1f) * 255f + 0.5f).toInt
    (c(a) << 24) | (c(r) << 16) | (c(g) << 8) | c(b)

object Color:
  val Transparent: Color = Color(0, 0, 0, 0)
  val Black: Color = Color(0, 0, 0)
  val White: Color = Color(1, 1, 1)
  def rgb8(r: Int, g: Int, b: Int, a: Int = 255): Color =
    Color(r / 255f, g / 255f, b / 255f, a / 255f)
  def fromArgb(argb: Int): Color =
    rgb8((argb >> 16) & 0xff, (argb >> 8) & 0xff, argb & 0xff, (argb >>> 24) & 0xff)
  /** Parses `#rgb`, `#rrggbb` or `#rrggbbaa`. */
  def hex(s: String): Option[Color] =
    val h = s.stripPrefix("#")
    val full = h.length match
      case 3 => Some(h.flatMap(c => s"$c$c") + "ff")
      case 6 => Some(h + "ff")
      case 8 => Some(h)
      case _ => None
    full.flatMap { f =>
      scala.util.Try(f.grouped(2).map(Integer.parseInt(_, 16)).toSeq).toOption.map { v =>
        rgb8(v(0), v(1), v(2), v(3))
      }
    }

  /** `(hue 0..360, saturation 0..1, value 0..1)`. */
  def toHsv(c: Color): (Float, Float, Float) =
    val mx = math.max(c.r, math.max(c.g, c.b)); val mn = math.min(c.r, math.min(c.g, c.b)); val d = mx - mn
    val h =
      if d == 0 then 0f
      else if mx == c.r then 60f * (((c.g - c.b) / d + 6f) % 6f)
      else if mx == c.g then 60f * ((c.b - c.r) / d + 2f)
      else 60f * ((c.r - c.g) / d + 4f)
    (h, if mx == 0 then 0f else d / mx, mx)

  def fromHsv(h: Float, s: Float, v: Float, a: Float = 1f): Color =
    val hh = ((h % 360f) + 360f) % 360f / 60f
    val i = hh.toInt; val f = hh - i
    val p = v * (1 - s); val q = v * (1 - s * f); val t = v * (1 - s * (1 - f))
    i match
      case 0 => Color(v, t, p, a); case 1 => Color(q, v, p, a); case 2 => Color(p, v, t, a)
      case 3 => Color(p, q, v, a); case 4 => Color(t, p, v, a); case _ => Color(v, p, q, a)

  def srgbToLinear(c: Float): Float =
    if c <= 0.04045f then c / 12.92f else pow((c + 0.055) / 1.055, 2.4).toFloat
  def linearToSrgb(c: Float): Float =
    if c <= 0.0031308f then c * 12.92f else (1.055 * pow(c, 1 / 2.4) - 0.055).toFloat

/** Premultiplied color, the form used for compositing. */
final case class PremulColor(r: Float, g: Float, b: Float, a: Float):
  /** Source-over: `this` drawn on `dst`. */
  def over(dst: PremulColor): PremulColor =
    val k = 1f - a
    PremulColor(r + dst.r * k, g + dst.g * k, b + dst.b * k, a + dst.a * k)
  def scaled(s: Float): PremulColor = PremulColor(r * s, g * s, b * s, a * s)
  def unpremultiplied: Color = if a <= 0f then Color.Transparent else Color(r / a, g / a, b / a, a)
