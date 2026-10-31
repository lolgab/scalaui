package scalaui.imaging

import scalaui.raster.Pixmap

enum BlendMode:
  case Normal, Multiply, Screen, Overlay, Darken, Lighten, ColorDodge, ColorBurn, HardLight, SoftLight, Difference, Exclusion
  case Hue, Saturation, Color, Luminosity

/** Porter-Duff source-over compositing with the W3C separable and non-separable blend functions. */
object Blend:
  /** Composites `src` onto `dst` with its top-left at `(ox, oy)`; `opacity` scales the source alpha. Works on premultiplied data. */
  def composite(dst: Pixmap, src: Pixmap, ox: Int = 0, oy: Int = 0, mode: BlendMode = BlendMode.Normal, opacity: Float = 1f): Unit =
    val x0 = math.max(0, ox); val y0 = math.max(0, oy)
    val x1 = math.min(dst.width, ox + src.width); val y1 = math.min(dst.height, oy + src.height)
    val cs = new Array[Float](3); val cb = new Array[Float](3); val bl = new Array[Float](3); val t1 = new Array[Float](3)
    var y = y0
    while y < y1 do
      var x = x0
      while x < x1 do
        val si = ((y - oy) * src.width + (x - ox)) * 4
        val di = (y * dst.width + x) * 4
        val as = src.data(si + 3) * opacity
        if as > 0f then
          val ab = dst.data(di + 3)
          if mode == BlendMode.Normal then
            val k = 1f - as
            dst.data(di) = src.data(si) * opacity + dst.data(di) * k
            dst.data(di + 1) = src.data(si + 1) * opacity + dst.data(di + 1) * k
            dst.data(di + 2) = src.data(si + 2) * opacity + dst.data(di + 2) * k
            dst.data(di + 3) = as + ab * k
          else
            // unpremultiply
            val ias = 1f / src.data(si + 3)
            cs(0) = src.data(si) * ias; cs(1) = src.data(si + 1) * ias; cs(2) = src.data(si + 2) * ias
            if ab > 0f then { val iab = 1f / ab; cb(0) = dst.data(di) * iab; cb(1) = dst.data(di + 1) * iab; cb(2) = dst.data(di + 2) * iab }
            else { cb(0) = 0; cb(1) = 0; cb(2) = 0 }
            blend(mode, cb, cs, bl, t1)
            var c = 0
            while c < 3 do
              // co = as*(1-ab)*Cs + as*ab*B + (1-as)*ab*Cb   (premultiplied result)
              dst.data(di + c) = as * (1f - ab) * cs(c) + as * ab * bl(c) + (1f - as) * dst.data(di + c)
              c += 1
            dst.data(di + 3) = as + ab * (1f - as)
        x += 1
      y += 1

  private def sep(mode: BlendMode, b: Float, s: Float): Float = mode match
    case BlendMode.Multiply => b * s
    case BlendMode.Screen => b + s - b * s
    case BlendMode.Overlay => sep(BlendMode.HardLight, s, b)
    case BlendMode.Darken => math.min(b, s)
    case BlendMode.Lighten => math.max(b, s)
    case BlendMode.ColorDodge => if b == 0f then 0f else if s >= 1f then 1f else math.min(1f, b / (1f - s))
    case BlendMode.ColorBurn => if b >= 1f then 1f else if s <= 0f then 0f else 1f - math.min(1f, (1f - b) / s)
    case BlendMode.HardLight => if s <= 0.5f then b * 2f * s else { val t = 2f * s - 1f; b + t - b * t }
    case BlendMode.SoftLight =>
      if s <= 0.5f then b - (1f - 2f * s) * b * (1f - b)
      else
        val d = if b <= 0.25f then ((16f * b - 12f) * b + 4f) * b else math.sqrt(b).toFloat
        b + (2f * s - 1f) * (d - b)
    case BlendMode.Difference => math.abs(b - s)
    case BlendMode.Exclusion => b + s - 2f * b * s
    case _ => s

  private def lum(c: Array[Float]): Float = 0.3f * c(0) + 0.59f * c(1) + 0.11f * c(2)
  private def min3(c: Array[Float]): Float = math.min(math.min(c(0), c(1)), c(2))
  private def max3(c: Array[Float]): Float = math.max(math.max(c(0), c(1)), c(2))
  private def clipColor(c: Array[Float]): Unit =
    val l = lum(c); val n = min3(c); val x = max3(c)
    if n < 0f then { var i = 0; while i < 3 do { c(i) = l + (c(i) - l) * l / (l - n); i += 1 } }
    if x > 1f then { var i = 0; while i < 3 do { c(i) = l + (c(i) - l) * (1f - l) / (x - l); i += 1 } }
  /** `out = clip(c + (l - lum(c)))`; `out` must not alias `c`'s later use. */
  private def setLum(c: Array[Float], l: Float, out: Array[Float]): Unit =
    val d = l - lum(c); out(0) = c(0) + d; out(1) = c(1) + d; out(2) = c(2) + d; clipColor(out)
  private def sat(c: Array[Float]): Float = max3(c) - min3(c)
  private def setSat(c: Array[Float], s: Float, o: Array[Float]): Unit =
    val mx = max3(c); val mn = min3(c)
    o(0) = 0f; o(1) = 0f; o(2) = 0f
    if mx > mn then { var i = 0; while i < 3 do { o(i) = (c(i) - mn) * s / (mx - mn); i += 1 } }

  // the non-separable modes use the scratch array `t1` instead of allocating per pixel
  private def blend(mode: BlendMode, cb: Array[Float], cs: Array[Float], out: Array[Float], t1: Array[Float]): Unit = mode match
    case BlendMode.Hue => setSat(cs, sat(cb), t1); setLum(t1, lum(cb), out)
    case BlendMode.Saturation => setSat(cb, sat(cs), t1); setLum(t1, lum(cb), out)
    case BlendMode.Color => setLum(cs, lum(cb), out)
    case BlendMode.Luminosity => setLum(cb, lum(cs), out)
    case m => var i = 0; while i < 3 do { out(i) = sep(m, cb(i), cs(i)); i += 1 }
