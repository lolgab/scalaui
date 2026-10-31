package scalaui.geom

import scala.math.{abs, cos, sin, sqrt}

/** A point in 2D space (kurbo::Point). */
final case class Point(x: Double, y: Double):
  def +(v: Vec2): Point = Point(x + v.x, y + v.y)
  def -(v: Vec2): Point = Point(x - v.x, y - v.y)
  def -(p: Point): Vec2 = Vec2(x - p.x, y - p.y)
  def distance(p: Point): Double = (this - p).length
  def lerp(p: Point, t: Double): Point = Point(x + (p.x - x) * t, y + (p.y - y) * t)
  def toVec2: Vec2 = Vec2(x, y)

object Point:
  val Zero: Point = Point(0, 0)

/** A displacement in 2D space (kurbo::Vec2). */
final case class Vec2(x: Double, y: Double):
  def +(v: Vec2): Vec2 = Vec2(x + v.x, y + v.y)
  def -(v: Vec2): Vec2 = Vec2(x - v.x, y - v.y)
  def *(s: Double): Vec2 = Vec2(x * s, y * s)
  def unary_- : Vec2 = Vec2(-x, -y)
  def dot(v: Vec2): Double = x * v.x + y * v.y
  def cross(v: Vec2): Double = x * v.y - y * v.x
  def lengthSquared: Double = x * x + y * y
  def length: Double = sqrt(lengthSquared)
  def normalized: Vec2 = { val l = length; if l == 0 then this else Vec2(x / l, y / l) }
  def toPoint: Point = Point(x, y)

object Vec2:
  val Zero: Vec2 = Vec2(0, 0)

final case class Size(width: Double, height: Double):
  def area: Double = width * height
  def isEmpty: Boolean = width <= 0 || height <= 0

/** An axis-aligned rectangle with `x0 <= x1` and `y0 <= y1` once [[abs]]-normalized. */
final case class Rect(x0: Double, y0: Double, x1: Double, y1: Double):
  def width: Double = x1 - x0
  def height: Double = y1 - y0
  def size: Size = Size(width, height)
  def area: Double = width * height
  def center: Point = Point((x0 + x1) / 2, (y0 + y1) / 2)
  def origin: Point = Point(x0, y0)
  def normalized: Rect = Rect(x0.min(x1), y0.min(y1), x0.max(x1), y0.max(y1))
  def isEmpty: Boolean = width <= 0 || height <= 0
  def contains(p: Point): Boolean = p.x >= x0 && p.x < x1 && p.y >= y0 && p.y < y1
  def union(r: Rect): Rect = Rect(x0.min(r.x0), y0.min(r.y0), x1.max(r.x1), y1.max(r.y1))
  def intersect(r: Rect): Rect =
    val ix0 = x0.max(r.x0); val iy0 = y0.max(r.y0)
    val ix1 = x1.min(r.x1); val iy1 = y1.min(r.y1)
    if ix0 > ix1 || iy0 > iy1 then Rect(ix0, iy0, ix0, iy0) else Rect(ix0, iy0, ix1, iy1)
  def inflate(dx: Double, dy: Double): Rect = Rect(x0 - dx, y0 - dy, x1 + dx, y1 + dy)
  def translate(v: Vec2): Rect = Rect(x0 + v.x, y0 + v.y, x1 + v.x, y1 + v.y)

object Rect:
  val Zero: Rect = Rect(0, 0, 0, 0)
  def fromOriginSize(o: Point, s: Size): Rect = Rect(o.x, o.y, o.x + s.width, o.y + s.height)
  def fromPoints(a: Point, b: Point): Rect = Rect(a.x, a.y, b.x, b.y).normalized

/** 2D affine transform, coefficients `[a b c d e f]` as in kurbo/SVG:
  * `x' = a*x + c*y + e`, `y' = b*x + d*y + f`.
  */
final case class Affine(a: Double, b: Double, c: Double, d: Double, e: Double, f: Double):
  /** `this * o`: applies `o` first, then `this`. */
  def *(o: Affine): Affine = Affine(
    a * o.a + c * o.b,
    b * o.a + d * o.b,
    a * o.c + c * o.d,
    b * o.c + d * o.d,
    a * o.e + c * o.f + e,
    b * o.e + d * o.f + f
  )
  def apply(p: Point): Point = Point(a * p.x + c * p.y + e, b * p.x + d * p.y + f)
  def determinant: Double = a * d - b * c
  def isInvertible: Boolean = { val det = determinant; det != 0 && !det.isNaN && !det.isInfinite }
  def inverse: Affine =
    val inv = 1.0 / determinant
    val na = d * inv; val nb = -b * inv; val nc = -c * inv; val nd = a * inv
    Affine(na, nb, nc, nd, -(na * e + nc * f), -(nb * e + nd * f))
  def transformRectBbox(r: Rect): Rect =
    val ps = Seq(Point(r.x0, r.y0), Point(r.x1, r.y0), Point(r.x1, r.y1), Point(r.x0, r.y1)).map(apply)
    Rect(ps.map(_.x).min, ps.map(_.y).min, ps.map(_.x).max, ps.map(_.y).max)
  def isNearIdentity(eps: Double = 1e-12): Boolean =
    abs(a - 1) < eps && abs(b) < eps && abs(c) < eps && abs(d - 1) < eps && abs(e) < eps && abs(f) < eps

object Affine:
  val Identity: Affine = Affine(1, 0, 0, 1, 0, 0)
  def translate(v: Vec2): Affine = Affine(1, 0, 0, 1, v.x, v.y)
  def scale(s: Double): Affine = Affine(s, 0, 0, s, 0, 0)
  def scale(sx: Double, sy: Double): Affine = Affine(sx, 0, 0, sy, 0, 0)
  def rotate(radians: Double): Affine =
    val s = sin(radians); val c = cos(radians)
    Affine(c, s, -s, c, 0, 0)
