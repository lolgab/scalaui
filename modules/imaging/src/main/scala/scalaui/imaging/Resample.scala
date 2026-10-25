package scalaui.imaging

import scalaui.raster.Pixmap

enum ResizeFilter:
  case Nearest, Bilinear, Lanczos3

object Resample:
  private def kernel(f: ResizeFilter, x: Double): Double = f match
    case ResizeFilter.Nearest => if x >= -0.5 && x < 0.5 then 1.0 else 0.0
    case ResizeFilter.Bilinear => math.max(0.0, 1.0 - math.abs(x))
    case ResizeFilter.Lanczos3 =>
      val a = math.abs(x)
      if a < 1e-9 then 1.0 else if a >= 3.0 then 0.0 else { val px = math.Pi * a; 3.0 * math.sin(px) * math.sin(px / 3.0) / (px * px) }
  private def support(f: ResizeFilter) = f match { case ResizeFilter.Nearest => 0.5; case ResizeFilter.Bilinear => 1.0; case ResizeFilter.Lanczos3 => 3.0 }

  /** Separable resize on premultiplied data; the kernel widens when shrinking, so downscales are properly filtered. */
  def resize(src: Pixmap, w: Int, h: Int, filter: ResizeFilter = ResizeFilter.Lanczos3): Pixmap =
    require(w > 0 && h > 0, "target size must be positive")
    val tmp = new Pixmap(w, src.height)
    axis(src, tmp, horizontal = true, filter)
    val out = new Pixmap(w, h)
    axis(tmp, out, horizontal = false, filter)
    // lanczos can overshoot: restore valid premultiplied range
    val od = out.data
    var i = 0
    while i < od.length do
      val a = od(i + 3).max(0f).min(1f); od(i + 3) = a
      od(i) = od(i).max(0f).min(a); od(i + 1) = od(i + 1).max(0f).min(a); od(i + 2) = od(i + 2).max(0f).min(a)
      i += 4
    out

  /** Weights are computed once per output sample; the vertical pass accumulates whole rows (cache friendly) in the same
    * tap order as the per-pixel formulation, so results are bit-identical to it.
    */
  private def axis(src: Pixmap, dst: Pixmap, horizontal: Boolean, f: ResizeFilter): Unit =
    val inN = if horizontal then src.width else src.height
    val outN = if horizontal then dst.width else dst.height
    val scale = inN.toDouble / outN
    val fscale = math.max(1.0, scale)
    val sup = support(f) * fscale
    val s = src.data; val d = dst.data
    val sw = src.width; val dw = dst.width
    val lines = if horizontal then src.height else src.width
    val acc = if horizontal then null else new Array[Double](sw * 4)
    var o = 0
    while o < outN do
      val center = (o + 0.5) * scale
      val first = math.max(0, math.floor(center - sup).toInt); val last = math.min(inN - 1, math.ceil(center + sup).toInt)
      val nw = math.max(0, last - first + 1)
      val weights = new Array[Double](nw)
      var total = 0.0
      var k = 0
      while k < nw do { weights(k) = kernel(f, (first + k + 0.5 - center) / fscale); total += weights(k); k += 1 }
      val inv = if total == 0.0 then 0.0 else 1.0 / total
      if horizontal then
        var line = 0
        while line < lines do
          var a0 = 0.0; var a1 = 0.0; var a2 = 0.0; var a3 = 0.0
          var si = (line * sw + first) * 4
          k = 0
          while k < nw do
            val wt = weights(k)
            a0 += s(si) * wt; a1 += s(si + 1) * wt; a2 += s(si + 2) * wt; a3 += s(si + 3) * wt
            si += 4; k += 1
          val di = (line * dw + o) * 4
          d(di) = (a0 * inv).toFloat; d(di + 1) = (a1 * inv).toFloat; d(di + 2) = (a2 * inv).toFloat; d(di + 3) = (a3 * inv).toFloat
          line += 1
      else
        val n4 = sw * 4
        var z = 0
        while z < n4 do { acc(z) = 0.0; z += 1 }
        k = 0
        while k < nw do
          val wt = weights(k); var si = (first + k) * n4; var j = 0
          while j < n4 do { acc(j) += s(si) * wt; si += 1; j += 1 }
          k += 1
        var di = o * n4; var j = 0
        while j < n4 do { d(di) = (acc(j) * inv).toFloat; di += 1; j += 1 }
      o += 1

  def flipHorizontal(src: Pixmap): Pixmap =
    val out = new Pixmap(src.width, src.height)
    for y <- 0 until src.height; x <- 0 until src.width; c <- 0 until 4 do
      out.data((y * src.width + x) * 4 + c) = src.data((y * src.width + (src.width - 1 - x)) * 4 + c)
    out
  def flipVertical(src: Pixmap): Pixmap =
    val out = new Pixmap(src.width, src.height)
    for y <- 0 until src.height; x <- 0 until src.width; c <- 0 until 4 do
      out.data((y * src.width + x) * 4 + c) = src.data(((src.height - 1 - y) * src.width + x) * 4 + c)
    out
  /** Rotates by a multiple of 90 degrees clockwise. */
  def rotate90(src: Pixmap, quarterTurns: Int): Pixmap =
    ((quarterTurns % 4) + 4) % 4 match
      case 0 => Pixmaps.copy(src)
      case 2 => flipVertical(flipHorizontal(src))
      case q =>
        val out = new Pixmap(src.height, src.width)
        for y <- 0 until src.height; x <- 0 until src.width; c <- 0 until 4 do
          val (nx, ny) = if q == 1 then (src.height - 1 - y, x) else (y, src.width - 1 - x)
          out.data((ny * out.width + nx) * 4 + c) = src.data((y * src.width + x) * 4 + c)
        out
  def crop(src: Pixmap, x: Int, y: Int, w: Int, h: Int): Pixmap =
    val out = new Pixmap(w, h)
    for oy <- 0 until h; ox <- 0 until w if x + ox >= 0 && x + ox < src.width && y + oy >= 0 && y + oy < src.height; c <- 0 until 4 do
      out.data((oy * w + ox) * 4 + c) = src.data(((y + oy) * src.width + x + ox) * 4 + c)
    out
