package scalaui.svg

import scalaui.color.{Color, PremulColor}
import scalaui.geom.*
import scalaui.raster.Pixmap

private[svg] object Spread:
  inline val Pad = 0
  inline val Reflect = 1
  inline val Repeat = 2

/** Linear or radial gradient with spread methods, a focal circle and an arbitrary gradient-space transform.
  * `inv` maps device pixels to gradient space. For radial gradients `(x1,y1,x2,y2,r1,r2)` = focal point, centre, focal radius, radius.
  */
private[svg] final class GradientPaint(inv: Affine, radial: Boolean, x1: Double, y1: Double, x2: Double, y2: Double, fr: Double, r: Double,
    stops: Array[(Float, Color)], spread: Int, opacity: Float) extends scalaui.raster.Paint:
  private val n = stops.length
  private val offs = stops.map(_._1)
  private val cols = stops.map { (_, c) => c.copy(a = c.a * opacity) }
  // precomputed lookup table: gradients are sampled at 1024 positions
  private val lut: Array[PremulColor] =
    Array.tabulate(1025) { i =>
      val t = i / 1024f
      if t <= offs(0) then cols(0).premultiplied
      else if t >= offs(n - 1) then cols(n - 1).premultiplied
      else
        var k = 1; while offs(k) < t do k += 1
        val t0 = offs(k - 1); val t1 = offs(k)
        if t1 <= t0 then cols(k).premultiplied
        else
          val u = (t - t0) / (t1 - t0); val a = cols(k - 1); val b = cols(k)
          Color(a.r + (b.r - a.r) * u, a.g + (b.g - a.g) * u, a.b + (b.b - a.b) * u, a.a + (b.a - a.a) * u).premultiplied
    }
  private def sample(t0: Double): PremulColor =
    val t = spread match
      case Spread.Pad => if t0 < 0 then 0.0 else if t0 > 1 then 1.0 else t0
      case Spread.Reflect => val m = ((t0 % 2) + 2) % 2; if m > 1 then 2 - m else m
      case _ => val m = t0 - math.floor(t0); m
    lut((t * 1024 + 0.5).toInt)
  private val cdx = x2 - x1; private val cdy = y2 - y1; private val dr = r - fr
  private val qa = cdx * cdx + cdy * cdy - dr * dr
  def colorAt(x: Double, y: Double): PremulColor =
    val p = inv(Point(x, y))
    if !radial then
      val l2 = cdx * cdx + cdy * cdy
      if l2 == 0 then sample(1.0) else sample(((p.x - x1) * cdx + (p.y - y1) * cdy) / l2)
    else
      val px = p.x - x1; val py = p.y - y1
      val b = px * cdx + py * cdy + fr * dr
      val c = px * px + py * py - fr * fr
      var t = Double.NaN
      if math.abs(qa) < 1e-12 then
        if math.abs(b) > 1e-12 then { val tt = c / (2 * b); if fr + tt * dr >= 0 then t = tt }
      else
        val disc = b * b - qa * c
        if disc >= 0 then
          val sq = math.sqrt(disc)
          val t1 = (b + sq) / qa; val t2 = (b - sq) / qa
          val hi = math.max(t1, t2); val lo = math.min(t1, t2)
          if fr + hi * dr >= 0 then t = hi else if fr + lo * dr >= 0 then t = lo
      if t.isNaN then PremulColor(0, 0, 0, 0) else sample(t)

/** Bilinear sampler over a premultiplied pixmap; `wrap` tiles it, otherwise edges clamp. `inv` maps device pixels to texel coordinates. */
private[svg] final class PixmapPaint(inv: Affine, pm: Pixmap, wrap: Boolean, smooth: Boolean, opacity: Float) extends scalaui.raster.Paint:
  private def texel(ix: Int, iy: Int, out: Array[Float]): Unit =
    val x = if wrap then ((ix % pm.width) + pm.width) % pm.width else math.max(0, math.min(pm.width - 1, ix))
    val y = if wrap then ((iy % pm.height) + pm.height) % pm.height else math.max(0, math.min(pm.height - 1, iy))
    val i = (y * pm.width + x) * 4
    out(0) = pm.data(i); out(1) = pm.data(i + 1); out(2) = pm.data(i + 2); out(3) = pm.data(i + 3)
  def colorAt(x: Double, y: Double): PremulColor =
    val p = inv(Point(x, y))
    val a = new Array[Float](4)
    if !smooth then
      texel(math.floor(p.x).toInt, math.floor(p.y).toInt, a)
      PremulColor(a(0) * opacity, a(1) * opacity, a(2) * opacity, a(3) * opacity)
    else
      val fx = p.x - 0.5; val fy = p.y - 0.5
      val x0 = math.floor(fx).toInt; val y0 = math.floor(fy).toInt
      val u = (fx - x0).toFloat; val v = (fy - y0).toFloat
      val t = new Array[Float](4); val acc = new Array[Float](4)
      def add(ix: Int, iy: Int, w: Float): Unit = { texel(ix, iy, t); var k = 0; while k < 4 do { acc(k) += t(k) * w; k += 1 } }
      add(x0, y0, (1 - u) * (1 - v)); add(x0 + 1, y0, u * (1 - v)); add(x0, y0 + 1, (1 - u) * v); add(x0 + 1, y0 + 1, u * v)
      PremulColor(acc(0) * opacity, acc(1) * opacity, acc(2) * opacity, acc(3) * opacity)
