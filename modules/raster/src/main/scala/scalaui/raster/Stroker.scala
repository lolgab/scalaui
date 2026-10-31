package scalaui.raster

import scalaui.geom.*

/** Converts polylines to fillable polygons (fill with NonZero). Each segment, join and cap becomes its own convex polygon. */
object Stroker:
  def stroke(subpaths: Vector[(Vector[Point], Boolean)], s: Stroke): Vector[Vector[Point]] =
    val hw = s.width / 2
    val out = Vector.newBuilder[Vector[Point]]
    subpaths.foreach { (raw, closed) =>
      val pts = raw.foldLeft(Vector.empty[Point]) { (acc, p) => if acc.nonEmpty && acc.last == p then acc else acc :+ p }
      val pts2 = if closed && pts.size > 1 && pts.head == pts.last then pts.init else pts
      if pts2.size == 1 then
        if s.cap == LineCap.Round then out += circle(pts2.head, hw)
        else if s.cap == LineCap.Square then
          val p = pts2.head
          out += Vector(Point(p.x - hw, p.y - hw), Point(p.x + hw, p.y - hw), Point(p.x + hw, p.y + hw), Point(p.x - hw, p.y + hw))
      else if pts2.size > 1 then
        val n = pts2.size
        val segCount = if closed then n else n - 1
        for i <- 0 until segCount do
          val a = pts2(i); val b = pts2((i + 1) % n)
          val d = (b - a).normalized; val nrm = Vec2(-d.y, d.x) * hw
          val ea = if !closed && i == 0 && s.cap == LineCap.Square then a - d * hw else a
          val eb = if !closed && i == segCount - 1 && s.cap == LineCap.Square then b + d * hw else b
          out += Vector(ea + nrm, eb + nrm, eb - nrm, ea - nrm)
        val joinFrom = if closed then 0 else 1
        val joinTo = if closed then n - 1 else n - 2
        for i <- joinFrom to joinTo do
          val prev = pts2((i - 1 + n) % n); val p = pts2(i); val next = pts2((i + 1) % n)
          out ++= join(prev, p, next, hw, s)
        if !closed && s.cap == LineCap.Round then
          out += circle(pts2.head, hw); out += circle(pts2.last, hw)
    }
    out.result().map(orient)

  /** NonZero fill needs every piece wound the same way, or overlaps of opposite pieces cancel (round joins and caps). */
  private def orient(poly: Vector[Point]): Vector[Point] =
    var area = 0.0
    var i = 0
    while i < poly.size do
      val a = poly(i); val b = poly((i + 1) % poly.size)
      area += a.x * b.y - b.x * a.y
      i += 1
    if area > 0 then poly.reverse else poly

  private def circle(c: Point, r: Double): Vector[Point] =
    val n = math.max(8, math.min(64, (r * 2).toInt + 8))
    Vector.tabulate(n)(i => { val t = 2 * math.Pi * i / n; Point(c.x + r * math.cos(t), c.y + r * math.sin(t)) })

  private def join(prev: Point, p: Point, next: Point, hw: Double, s: Stroke): Option[Vector[Point]] =
    val d0 = (p - prev).normalized; val d1 = (next - p).normalized
    val cross = d0.cross(d1)
    if math.abs(cross) < 1e-9 && d0.dot(d1) > 0 then return None
    s.join match
      case LineJoin.Round => Some(circle(p, hw))
      case j =>
        // outer side of the turn
        val side = if cross > 0 then -1.0 else 1.0
        val n0 = Vec2(-d0.y, d0.x) * (hw * side); val n1 = Vec2(-d1.y, d1.x) * (hw * side)
        val a = p + n0; val b = p + n1
        val bevel = Vector(p, a, b)
        if j == LineJoin.Bevel then Some(bevel)
        else
          val cosHalf = math.sqrt(math.max(0.0, (1 + d0.dot(d1) * -1) / 2)) // sin of half turn angle complement
          val miterRatio = if cosHalf < 1e-9 then Double.MaxValue else 1 / cosHalf
          if miterRatio > s.miterLimit then Some(bevel)
          else
            val m = (n0 + n1)
            val ml = m.lengthSquared
            if ml < 1e-12 then Some(bevel)
            else
              val tip = p + m * (2 * hw * hw / ml)
              Some(Vector(p, a, tip, b))
