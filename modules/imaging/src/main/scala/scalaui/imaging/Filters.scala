package scalaui.imaging

import scalaui.color.Color
import scalaui.raster.Pixmap

/** In-place and copying image operations on premultiplied float [[Pixmap]]s. */
object Filters:
  /** Gaussian blur with standard deviation `sigma` pixels (edge pixels are clamped). */
  def gaussianBlur(src: Pixmap, sigma: Double): Pixmap =
    if sigma <= 0.0 then return Pixmaps.copy(src)
    val r = math.max(1, math.ceil(sigma * 3).toInt)
    val k = Array.tabulate(2 * r + 1)(i => math.exp(-((i - r) * (i - r)) / (2 * sigma * sigma)).toFloat)
    val sum = k.sum; for i <- k.indices do k(i) /= sum
    val tmp = new Pixmap(src.width, src.height); val out = new Pixmap(src.width, src.height)
    pass(src, tmp, k, r, horizontal = true); pass(tmp, out, k, r, horizontal = false)
    out

  /** Fast approximation of [[gaussianBlur]]: three successive box blurs per axis (sliding window, O(1) per pixel regardless of
    * `sigma`), the classic W3C `feGaussianBlur` recipe. Differs from the exact kernel by a few percent of the local contrast
    * (see the test); use it for large radii (shadows, backgrounds) where the exact kernel's cost grows with `sigma`.
    * Edge pixels are clamped like in [[gaussianBlur]]. Falls back to the exact blur below sigma 2, where boxes are too coarse.
    */
  def gaussianBlurApprox(src: Pixmap, sigma: Double): Pixmap =
    if sigma < 2.0 then return gaussianBlur(src, sigma)
    val n = 3
    val wIdeal = math.sqrt(12.0 * sigma * sigma / n + 1)
    var wl = math.floor(wIdeal).toInt; if wl % 2 == 0 then wl -= 1
    val wu = wl + 2
    val m = math.round((12.0 * sigma * sigma - n * wl * wl - 4.0 * n * wl - 3.0 * n) / (-4.0 * wl - 4.0)).toInt
    var a = Pixmaps.copy(src); var b = new Pixmap(src.width, src.height)
    var i = 0
    while i < n do
      val r = ((if i < m then wl else wu) - 1) / 2
      boxPass(a, b, r, horizontal = true); boxPass(b, a, r, horizontal = false)
      i += 1
    a

  /** Box blur of radius `r` (window `2r + 1`, clamped edges) along one axis using a running sum. */
  private def boxPass(src: Pixmap, dst: Pixmap, r: Int, horizontal: Boolean): Unit =
    val w = src.width; val h = src.height; val s = src.data; val d = dst.data
    val inv = 1.0 / (2 * r + 1)
    if horizontal then
      var y = 0
      while y < h do
        val row = y * w * 4
        var c = 0
        while c < 4 do
          var sum = 0.0
          var k = -r
          while k <= r do { sum += s(row + math.min(w - 1, math.max(0, k)) * 4 + c); k += 1 }
          var x = 0
          while x < w do
            d(row + x * 4 + c) = (sum * inv).toFloat
            sum += s(row + math.min(w - 1, x + r + 1) * 4 + c) - s(row + math.max(0, x - r) * 4 + c)
            x += 1
          c += 1
        y += 1
    else
      val stride = w * 4
      val acc = new Array[Double](stride)
      var k = -r
      while k <= r do
        val o = math.min(h - 1, math.max(0, k)) * stride
        var j = 0
        while j < stride do { acc(j) += s(o + j); j += 1 }
        k += 1
      var y = 0
      while y < h do
        val o = y * stride; val add = math.min(h - 1, y + r + 1) * stride; val sub = math.max(0, y - r) * stride
        var j = 0
        while j < stride do
          d(o + j) = (acc(j) * inv).toFloat
          acc(j) += s(add + j) - s(sub + j)
          j += 1
        y += 1

  /** One 1-D pass. Accumulation order per output value is the tap order `-r..r` starting from 0, as in the textbook loop,
    * so results are bit-identical to it. Horizontal: four pixels per sweep (independent accumulation chains hide the float-add
    * latency) with clamp-free interior taps. Vertical: whole rows, four taps per sweep.
    */
  private def pass(src: Pixmap, dst: Pixmap, k: Array[Float], r: Int, horizontal: Boolean): Unit =
    val w = src.width; val h = src.height
    val s = src.data; val d = dst.data
    val taps = 2 * r + 1
    if horizontal then
      var y = 0
      while y < h do
        val rowBase = y * w * 4
        var x = 0
        while x < w do
          if x >= r && x + 3 + r < w then
            var a00 = 0f; var a01 = 0f; var a02 = 0f; var a03 = 0f
            var a10 = 0f; var a11 = 0f; var a12 = 0f; var a13 = 0f
            var a20 = 0f; var a21 = 0f; var a22 = 0f; var a23 = 0f
            var a30 = 0f; var a31 = 0f; var a32 = 0f; var a33 = 0f
            var i = rowBase + (x - r) * 4
            var t = 0
            while t < taps do
              val kv = k(t)
              a00 += s(i) * kv; a01 += s(i + 1) * kv; a02 += s(i + 2) * kv; a03 += s(i + 3) * kv
              a10 += s(i + 4) * kv; a11 += s(i + 5) * kv; a12 += s(i + 6) * kv; a13 += s(i + 7) * kv
              a20 += s(i + 8) * kv; a21 += s(i + 9) * kv; a22 += s(i + 10) * kv; a23 += s(i + 11) * kv
              a30 += s(i + 12) * kv; a31 += s(i + 13) * kv; a32 += s(i + 14) * kv; a33 += s(i + 15) * kv
              i += 4; t += 1
            val o = rowBase + x * 4
            d(o) = a00; d(o + 1) = a01; d(o + 2) = a02; d(o + 3) = a03
            d(o + 4) = a10; d(o + 5) = a11; d(o + 6) = a12; d(o + 7) = a13
            d(o + 8) = a20; d(o + 9) = a21; d(o + 10) = a22; d(o + 11) = a23
            d(o + 12) = a30; d(o + 13) = a31; d(o + 14) = a32; d(o + 15) = a33
            x += 4
          else
            // near the edges: clamped taps, one pixel at a time
            var a0 = 0f; var a1 = 0f; var a2 = 0f; var a3 = 0f
            var t = -r
            while t <= r do
              val sx = if x + t < 0 then 0 else if x + t > w - 1 then w - 1 else x + t
              val i = rowBase + sx * 4; val kv = k(t + r)
              a0 += s(i) * kv; a1 += s(i + 1) * kv; a2 += s(i + 2) * kv; a3 += s(i + 3) * kv
              t += 1
            val o = rowBase + x * 4
            d(o) = a0; d(o + 1) = a1; d(o + 2) = a2; d(o + 3) = a3
            x += 1
        y += 1
    else
      val stride = w * 4
      var y = 0
      while y < h do
        val o = y * stride; val end = o + stride
        // dst is freshly allocated (zero), so accumulating into it matches the "start from 0f" order; four taps per sweep keeps
        // the tap order ((d + s0*k0) + s1*k1) ... while quartering the loads and stores of d
        var t = -r
        while t + 3 <= r do
          val i0 = rowOf(y + t, h) * stride; val i1 = rowOf(y + t + 1, h) * stride; val i2 = rowOf(y + t + 2, h) * stride; val i3 = rowOf(y + t + 3, h) * stride
          val k0 = k(t + r); val k1 = k(t + r + 1); val k2 = k(t + r + 2); val k3 = k(t + r + 3)
          var j = o; var q = 0
          while j < end do { d(j) = d(j) + s(i0 + q) * k0 + s(i1 + q) * k1 + s(i2 + q) * k2 + s(i3 + q) * k3; j += 1; q += 1 }
          t += 4
        while t <= r do
          val i0 = rowOf(y + t, h) * stride; val k0 = k(t + r)
          var j = o; var q = 0
          while j < end do { d(j) += s(i0 + q) * k0; j += 1; q += 1 }
          t += 1
        y += 1

  @inline private def rowOf(y: Int, h: Int): Int = if y < 0 then 0 else if y > h - 1 then h - 1 else y

  /** Applies `f` to every pixel's non-premultiplied `(r, g, b)` in place, preserving alpha. */
  def mapRgb(pm: Pixmap)(f: (Float, Float, Float) => (Float, Float, Float)): Unit =
    var i = 0
    while i < pm.data.length do
      val a = pm.data(i + 3)
      if a > 0f then
        val inv = 1f / a
        val (r, g, b) = f(pm.data(i) * inv, pm.data(i + 1) * inv, pm.data(i + 2) * inv)
        pm.data(i) = r.max(0f).min(1f) * a; pm.data(i + 1) = g.max(0f).min(1f) * a; pm.data(i + 2) = b.max(0f).min(1f) * a
      i += 4

  def brightness(pm: Pixmap, amount: Float): Unit = mapRgb(pm)((r, g, b) => (r + amount, g + amount, b + amount))
  /** `factor` 1 = unchanged, >1 more contrast. */
  def contrast(pm: Pixmap, factor: Float): Unit = mapRgb(pm)((r, g, b) => ((r - 0.5f) * factor + 0.5f, (g - 0.5f) * factor + 0.5f, (b - 0.5f) * factor + 0.5f))
  def gamma(pm: Pixmap, g: Float): Unit = { val e = 1f / g; mapRgb(pm)((r, gg, b) => (math.pow(r, e).toFloat, math.pow(gg, e).toFloat, math.pow(b, e).toFloat)) }
  def invert(pm: Pixmap): Unit = mapRgb(pm)((r, g, b) => (1f - r, 1f - g, 1f - b))
  def grayscale(pm: Pixmap): Unit = mapRgb(pm) { (r, g, b) => val l = 0.2126f * r + 0.7152f * g + 0.0722f * b; (l, l, l) }
  /** `factor` 0 = gray, 1 = unchanged, >1 more vivid. */
  def saturation(pm: Pixmap, factor: Float): Unit = mapRgb(pm) { (r, g, b) =>
    val l = 0.2126f * r + 0.7152f * g + 0.0722f * b; (l + (r - l) * factor, l + (g - l) * factor, l + (b - l) * factor) }
  def hueRotate(pm: Pixmap, degrees: Float): Unit = mapRgb(pm) { (r, g, b) =>
    val (h, s, v) = Color.toHsv(Color(r, g, b)); val c = Color.fromHsv(h + degrees, s, v); (c.r, c.g, c.b) }
  /** Levels: maps `[black, white]` to `[0, 1]` with midtone `gamma`. */
  def levels(pm: Pixmap, black: Float, white: Float, gammaValue: Float = 1f): Unit =
    def f(v: Float) = math.pow(((v - black) / (white - black)).max(0f).min(1f), 1.0 / gammaValue).toFloat
    mapRgb(pm)((r, g, b) => (f(r), f(g), f(b)))
  /** Applies a 256-entry lookup table to each channel (a tone curve). */
  def curve(pm: Pixmap, lut: Array[Float]): Unit =
    require(lut.length == 256)
    def f(v: Float) = { val p = v.max(0f).min(1f) * 255f; val i = p.toInt.min(254); lut(i) + (lut(i + 1) - lut(i)) * (p - i) }
    mapRgb(pm)((r, g, b) => (f(r), f(g), f(b)))

  /** 3x3 convolution (e.g. sharpen) with edge clamping. */
  def convolve3(src: Pixmap, kernel: Array[Float]): Pixmap =
    require(kernel.length == 9)
    val w = src.width; val h = src.height
    val out = new Pixmap(w, h)
    val s = src.data; val o = out.data
    var y = 0
    while y < h do
      var x = 0
      while x < w do
        var c = 0
        while c < 4 do
          var acc = 0f
          var ky = -1
          while ky <= 1 do
            val sy = math.min(h - 1, math.max(0, y + ky)) * w
            var kx = -1
            while kx <= 1 do
              val sx = math.min(w - 1, math.max(0, x + kx))
              acc += s((sy + sx) * 4 + c) * kernel((ky + 1) * 3 + kx + 1)
              kx += 1
            ky += 1
          o((y * w + x) * 4 + c) = acc
          c += 1
        x += 1
      y += 1
    // keep premultiplied invariant
    var i = 0
    while i < o.length do
      val a = o(i + 3).max(0f).min(1f); o(i + 3) = a
      var c = 0
      while c < 3 do { o(i + c) = o(i + c).max(0f).min(a); c += 1 }
      i += 4
    out
  val Sharpen: Array[Float] = Array(0, -1, 0, -1, 5, -1, 0, -1, 0)

  def histogram(pm: Pixmap): Array[Array[Int]] =
    val h = Array.ofDim[Int](4, 256) // r, g, b, luminance
    var i = 0
    while i < pm.data.length do
      val a = pm.data(i + 3)
      if a > 0f then
        val inv = 1f / a
        val r = (pm.data(i) * inv).min(1f); val g = (pm.data(i + 1) * inv).min(1f); val b = (pm.data(i + 2) * inv).min(1f)
        h(0)((r * 255 + 0.5f).toInt) += 1; h(1)((g * 255 + 0.5f).toInt) += 1; h(2)((b * 255 + 0.5f).toInt) += 1
        h(3)(((0.2126f * r + 0.7152f * g + 0.0722f * b) * 255 + 0.5f).toInt) += 1
      i += 4
    h
