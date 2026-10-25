package scalaui.raster

import scalaui.geom.*
import scala.collection.mutable.ArrayBuffer

enum PathEl:
  case MoveTo(p: Point)
  case LineTo(p: Point)
  case QuadTo(c: Point, p: Point)
  case CubicTo(c1: Point, c2: Point, p: Point)
  case Close

enum FillRule:
  case NonZero, EvenOdd

final class Path private (val elements: Vector[PathEl]):
  def transform(m: Affine): Path = new Path(elements.map {
    case PathEl.MoveTo(p)         => PathEl.MoveTo(m(p))
    case PathEl.LineTo(p)         => PathEl.LineTo(m(p))
    case PathEl.QuadTo(c, p)      => PathEl.QuadTo(m(c), m(p))
    case PathEl.CubicTo(a, b, p)  => PathEl.CubicTo(m(a), m(b), m(p))
    case PathEl.Close             => PathEl.Close
  })

  /** Flattens curves to polylines. Each subpath is `(points, closed)`. */
  def flatten(tolerance: Double = 0.1): Vector[(Vector[Point], Boolean)] =
    val out = Vector.newBuilder[(Vector[Point], Boolean)]
    var cur = ArrayBuffer.empty[Point]
    def end(closed: Boolean): Unit =
      if cur.nonEmpty then out += ((cur.toVector, closed))
      cur = ArrayBuffer.empty
    def last = cur.last
    elements.foreach {
      case PathEl.MoveTo(p) => end(false); cur += p
      case PathEl.LineTo(p) => cur += p
      case PathEl.QuadTo(c, p) =>
        val p0 = last
        val n = segments(p0.distance(c) + c.distance(p), tolerance)
        for i <- 1 to n do
          val t = i.toDouble / n; val u = 1 - t
          cur += Point(u * u * p0.x + 2 * u * t * c.x + t * t * p.x, u * u * p0.y + 2 * u * t * c.y + t * t * p.y)
      case PathEl.CubicTo(c1, c2, p) =>
        val p0 = last
        val n = segments(p0.distance(c1) + c1.distance(c2) + c2.distance(p), tolerance)
        for i <- 1 to n do
          val t = i.toDouble / n; val u = 1 - t
          val a = u * u * u; val b = 3 * u * u * t; val c = 3 * u * t * t; val d = t * t * t
          cur += Point(a * p0.x + b * c1.x + c * c2.x + d * p.x, a * p0.y + b * c1.y + c * c2.y + d * p.y)
      case PathEl.Close =>
        end(true)
    }
    end(false)
    out.result()

  private def segments(len: Double, tol: Double): Int = math.max(2, math.min(200, math.ceil(math.sqrt(len / tol) * 1.5).toInt))

object Path:
  def builder: PathBuilder = new PathBuilder
  def rect(r: Rect): Path =
    builder.moveTo(r.x0, r.y0).lineTo(r.x1, r.y0).lineTo(r.x1, r.y1).lineTo(r.x0, r.y1).close().build
  def circle(c: Point, radius: Double): Path = ellipse(c, radius, radius)
  def ellipse(c: Point, rx: Double, ry: Double): Path =
    val k = 0.5522847498307936
    builder
      .moveTo(c.x + rx, c.y)
      .cubicTo(c.x + rx, c.y + k * ry, c.x + k * rx, c.y + ry, c.x, c.y + ry)
      .cubicTo(c.x - k * rx, c.y + ry, c.x - rx, c.y + k * ry, c.x - rx, c.y)
      .cubicTo(c.x - rx, c.y - k * ry, c.x - k * rx, c.y - ry, c.x, c.y - ry)
      .cubicTo(c.x + k * rx, c.y - ry, c.x + rx, c.y - k * ry, c.x + rx, c.y)
      .close().build
  def roundedRect(r: Rect, radius: Double): Path =
    val k = 0.5522847498307936; val rr = radius.min(r.width / 2).min(r.height / 2); val o = rr * (1 - k)
    builder
      .moveTo(r.x0 + rr, r.y0).lineTo(r.x1 - rr, r.y0)
      .cubicTo(r.x1 - o, r.y0, r.x1, r.y0 + o, r.x1, r.y0 + rr).lineTo(r.x1, r.y1 - rr)
      .cubicTo(r.x1, r.y1 - o, r.x1 - o, r.y1, r.x1 - rr, r.y1).lineTo(r.x0 + rr, r.y1)
      .cubicTo(r.x0 + o, r.y1, r.x0, r.y1 - o, r.x0, r.y1 - rr).lineTo(r.x0, r.y0 + rr)
      .cubicTo(r.x0, r.y0 + o, r.x0 + o, r.y0, r.x0 + rr, r.y0).close().build

  final class PathBuilder:
    private val els = Vector.newBuilder[PathEl]
    def moveTo(x: Double, y: Double): this.type = { els += PathEl.MoveTo(Point(x, y)); this }
    def lineTo(x: Double, y: Double): this.type = { els += PathEl.LineTo(Point(x, y)); this }
    def quadTo(cx: Double, cy: Double, x: Double, y: Double): this.type =
      els += PathEl.QuadTo(Point(cx, cy), Point(x, y)); this
    def cubicTo(c1x: Double, c1y: Double, c2x: Double, c2y: Double, x: Double, y: Double): this.type =
      els += PathEl.CubicTo(Point(c1x, c1y), Point(c2x, c2y), Point(x, y)); this
    def close(): this.type = { els += PathEl.Close; this }
    def build: Path = new Path(els.result())
