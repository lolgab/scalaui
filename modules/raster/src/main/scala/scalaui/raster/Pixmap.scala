package scalaui.raster

import scalaui.color.*
import scalaui.geom.*

/** RGBA float premultiplied pixel buffer, row-major. */
final class Pixmap(val width: Int, val height: Int):
  val data: Array[Float] = new Array[Float](width * height * 4)

  def clear(c: Color): Unit =
    val p = c.premultiplied
    var i = 0
    while i < data.length do
      data(i) = p.r; data(i + 1) = p.g; data(i + 2) = p.b; data(i + 3) = p.a; i += 4

  def get(x: Int, y: Int): PremulColor =
    val i = (y * width + x) * 4
    PremulColor(data(i), data(i + 1), data(i + 2), data(i + 3))

  /** Blends `src` scaled by `coverage` over pixel (x, y). */
  def blend(x: Int, y: Int, src: PremulColor, coverage: Float): Unit =
    val i = (y * width + x) * 4
    val s = src.scaled(coverage); val k = 1f - s.a
    data(i) = s.r + data(i) * k; data(i + 1) = s.g + data(i + 1) * k
    data(i + 2) = s.b + data(i + 2) * k; data(i + 3) = s.a + data(i + 3) * k

  /** Packed 8-bit RGBA (non-premultiplied), for blitting to a window surface or PNG. */
  def toRgba8: Array[Byte] =
    val d = data
    val out = new Array[Byte](d.length)
    var i = 0
    while i < d.length do
      val a = d(i + 3)
      if a == 1f then
        // opaque: 1/a is exactly 1, only the clamp and the conversion remain
        out(i) = q8(d(i)); out(i + 1) = q8(d(i + 1)); out(i + 2) = q8(d(i + 2)); out(i + 3) = -1
      else if a > 0f then
        val inv = 1f / a
        out(i) = q8(d(i) * inv); out(i + 1) = q8(d(i + 1) * inv); out(i + 2) = q8(d(i + 2) * inv); out(i + 3) = q8(a)
      else
        out(i + 3) = q8(a)
        if a != a then { out(i) = q8(Float.NaN); out(i + 1) = out(i); out(i + 2) = out(i) } // NaN alpha: same as before
      i += 4
    out

  @inline private def q8(v: Float): Byte =
    val c = if v < 0f then 0f else if v > 1f then 1f else v
    (c * 255f + 0.5f).toInt.toByte

  /** Premultiplied 8-bit RGBA, the layout OS compositors expect. */
  def toPremulRgba8: Array[Byte] =
    val d = data
    val out = new Array[Byte](d.length)
    var i = 0
    while i < d.length do { out(i) = q8(d(i)); i += 1 }
    out

  def fill(path: Path, paint: Paint, rule: FillRule = FillRule.NonZero, transform: Affine = Affine.Identity): Unit =
    val polys = path.transform(transform).flatten().map(_._1)
    Rasterizer.fillRows(polys, rule, width, height)(sink(paint))

  def stroke(path: Path, paint: Paint, style: Stroke, transform: Affine = Affine.Identity): Unit =
    val polys = Stroker.stroke(path.transform(transform).flatten(), style)
    Rasterizer.fillRows(polys, FillRule.NonZero, width, height)(sink(paint))

  /** Row sink blending `paint` through the coverage; solid paints skip per-pixel color lookups, all paints avoid allocating. */
  private def sink(paint: Paint): Rasterizer.RowSink =
    val d = data; val w = width
    paint match
      case Paint.Solid(color) =>
        val p = color.premultiplied
        val pr = p.r; val pg = p.g; val pb = p.b; val pa = p.a
        (y: Int, cov: Array[Float], x0: Int, x1: Int) =>
          var x = x0
          var i = (y * w + x0) * 4
          while x <= x1 do
            val c0 = cov(x)
            if c0 >= 1f && pa == 1f then
              // opaque source over anything: the destination weight 1 - 1 * 1 is exactly 0
              d(i) = pr; d(i + 1) = pg; d(i + 2) = pb; d(i + 3) = 1f
            else if c0 > 0.0005f then
              val c = if c0 > 1f then 1f else c0
              val sa = pa * c; val k = 1f - sa
              d(i) = pr * c + d(i) * k; d(i + 1) = pg * c + d(i + 1) * k
              d(i + 2) = pb * c + d(i + 2) * k; d(i + 3) = sa + d(i + 3) * k
            x += 1; i += 4
      case _ =>
        val col = new Array[Float](4)
        (y: Int, cov: Array[Float], x0: Int, x1: Int) =>
          var x = x0
          var i = (y * w + x0) * 4
          while x <= x1 do
            val c0 = cov(x)
            if c0 > 0.0005f then
              val c = if c0 > 1f then 1f else c0
              paint.colorAtInto(x + 0.5, y + 0.5, col)
              val sa = col(3) * c; val k = 1f - sa
              d(i) = col(0) * c + d(i) * k; d(i + 1) = col(1) * c + d(i + 1) * k
              d(i + 2) = col(2) * c + d(i + 2) * k; d(i + 3) = sa + d(i + 3) * k
            x += 1; i += 4

/** Paint sources. */
trait Paint:
  def colorAt(x: Double, y: Double): PremulColor
  /** Allocation-free variant used by the rasterizer: writes premultiplied r, g, b, a to `out(0..3)`. */
  def colorAtInto(x: Double, y: Double, out: Array[Float]): Unit =
    val c = colorAt(x, y); out(0) = c.r; out(1) = c.g; out(2) = c.b; out(3) = c.a

object Paint:
  final case class Solid(color: Color) extends Paint:
    private val p = color.premultiplied
    def colorAt(x: Double, y: Double): PremulColor = p
    override def colorAtInto(x: Double, y: Double, out: Array[Float]): Unit = { out(0) = p.r; out(1) = p.g; out(2) = p.b; out(3) = p.a }

  /** Stop list unpacked into primitive arrays. */
  private final class StopTable(stops: Seq[(Float, Color)]):
    val n: Int = stops.size
    val pos: Array[Float] = stops.map(_._1).toArray
    val r: Array[Float] = stops.map(_._2.r).toArray; val g: Array[Float] = stops.map(_._2.g).toArray
    val b: Array[Float] = stops.map(_._2.b).toArray; val a: Array[Float] = stops.map(_._2.a).toArray
    /** Same arithmetic as [[stopColor]]. */
    def into(tf: Float, out: Array[Float]): Unit =
      var i = 0
      while i < n && !(pos(i) >= tf) do i += 1
      val idx = if i == n then -1 else i
      if idx <= 0 then
        val j = if idx < 0 then n - 1 else 0
        val al = a(j); out(0) = r(j) * al; out(1) = g(j) * al; out(2) = b(j) * al; out(3) = al
      else
        val t0 = pos(idx - 1); val t1 = pos(idx)
        val u = if t1 == t0 then 0f else (tf - t0) / (t1 - t0)
        val c0 = idx - 1; val c1 = idx
        val mr = r(c0) + (r(c1) - r(c0)) * u; val mg = g(c0) + (g(c1) - g(c0)) * u
        val mb = b(c0) + (b(c1) - b(c0)) * u; val ma = a(c0) + (a(c1) - a(c0)) * u
        out(0) = mr * ma; out(1) = mg * ma; out(2) = mb * ma; out(3) = ma

  final case class LinearGradient(from: Point, to: Point, stops: Seq[(Float, Color)]) extends Paint:
    private lazy val table = new StopTable(stops)
    private val dx = to.x - from.x; private val dy = to.y - from.y
    private val len2 = dx * dx + dy * dy
    def colorAt(x: Double, y: Double): PremulColor =
      val o = new Array[Float](4); colorAtInto(x, y, o); PremulColor(o(0), o(1), o(2), o(3))
    override def colorAtInto(x: Double, y: Double, out: Array[Float]): Unit =
      val t = if len2 == 0 then 0.0 else math.min(1.0, math.max(0.0, ((x - from.x) * dx + (y - from.y) * dy) / len2))
      table.into(t.toFloat, out)
  /** Radial gradient from `center` out to `radius` (focal point = center). */
  final case class RadialGradient(center: Point, radius: Double, stops: Seq[(Float, Color)]) extends Paint:
    private lazy val table = new StopTable(stops)
    def colorAt(x: Double, y: Double): PremulColor =
      val o = new Array[Float](4); colorAtInto(x, y, o); PremulColor(o(0), o(1), o(2), o(3))
    override def colorAtInto(x: Double, y: Double, out: Array[Float]): Unit =
      val ddx = x - center.x; val ddy = y - center.y
      table.into(math.min(1.0, math.max(0.0, math.sqrt(ddx * ddx + ddy * ddy) / radius)).toFloat, out)
  /** Color of a sorted stop list at `t` in 0..1, interpolated in non-premultiplied space then premultiplied. */
  private[raster] def stopColor(stops: Seq[(Float, Color)], tf: Float): PremulColor =
    val i = stops.indexWhere(_._1 >= tf)
    if i <= 0 then stops(if i < 0 then stops.size - 1 else 0)._2.premultiplied
    else
      val (t0, c0) = stops(i - 1); val (t1, c1) = stops(i)
      val u = if t1 == t0 then 0f else (tf - t0) / (t1 - t0)
      def mix(a: Float, b: Float) = a + (b - a) * u
      Color(mix(c0.r, c1.r), mix(c0.g, c1.g), mix(c0.b, c1.b), mix(c0.a, c1.a)).premultiplied
  def solid(c: Color): Paint = Solid(c)

enum LineCap:
  case Butt, Square, Round
enum LineJoin:
  case Miter, Bevel, Round

final case class Stroke(width: Double, cap: LineCap = LineCap.Butt, join: LineJoin = LineJoin.Miter, miterLimit: Double = 4.0)
