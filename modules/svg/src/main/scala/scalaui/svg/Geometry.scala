package scalaui.svg

import scalaui.geom.*
import scalaui.raster.{Path, PathEl}

private[svg] object Geometry:
  def bbox(path: Path): Option[Rect] =
    var x0 = Double.MaxValue; var y0 = Double.MaxValue; var x1 = Double.MinValue; var y1 = Double.MinValue
    path.flatten(0.05).foreach { (pts, _) => pts.foreach { p => x0 = math.min(x0, p.x); y0 = math.min(y0, p.y); x1 = math.max(x1, p.x); y1 = math.max(y1, p.y) } }
    if x0 > x1 then None else Some(Rect(x0, y0, x1, y1))

  /** Elliptical-cornered rectangle. */
  def roundRect(x: Double, y: Double, w: Double, h: Double, rx: Double, ry: Double): Path =
    if rx <= 0 || ry <= 0 then Path.rect(Rect(x, y, x + w, y + h))
    else
      val k = 0.5522847498307936; val ox = rx * (1 - k); val oy = ry * (1 - k)
      val x1 = x + w; val y1 = y + h
      Path.builder
        .moveTo(x + rx, y).lineTo(x1 - rx, y)
        .cubicTo(x1 - ox, y, x1, y + oy, x1, y + ry).lineTo(x1, y1 - ry)
        .cubicTo(x1, y1 - oy, x1 - ox, y1, x1 - rx, y1).lineTo(x + rx, y1)
        .cubicTo(x + ox, y1, x, y1 - oy, x, y1 - ry).lineTo(x, y + ry)
        .cubicTo(x, y + oy, x + ox, y, x + rx, y).close().build

  /** `preserveAspectRatio` + `viewBox`: transform mapping the viewBox into a `w x h` viewport at the origin. */
  def viewBoxTransform(vb: Rect, par: String, w: Double, h: Double): Affine =
    val parts = par.trim.split("\\s+").filter(_.nonEmpty).toList.dropWhile(_ == "defer")
    val align = parts.headOption.getOrElse("xMidYMid")
    val slice = parts.lift(1).contains("slice")
    val sx = w / vb.width; val sy = h / vb.height
    if align == "none" then Affine.scale(sx, sy) * Affine.translate(Vec2(-vb.x0, -vb.y0))
    else
      val s = if slice then math.max(sx, sy) else math.min(sx, sy)
      val ax = if align.contains("xMin") then 0.0 else if align.contains("xMax") then 1.0 else 0.5
      val ay = if align.contains("YMin") then 0.0 else if align.contains("YMax") then 1.0 else 0.5
      Affine.translate(Vec2((w - vb.width * s) * ax, (h - vb.height * s) * ay)) * Affine.scale(s) * Affine.translate(Vec2(-vb.x0, -vb.y0))

  def parseViewBox(s: Option[String]): Option[Rect] =
    s.map(Values.numbers).filter(a => a.length == 4 && a(2) > 0 && a(3) > 0).map(a => Rect(a(0), a(1), a(0) + a(2), a(1) + a(3)))

  /** Splits a path into dashes (in path units). Closed subpaths that never get "off" at the join keep their join semantics only approximately. */
  def dash(subpaths: Vector[(Vector[Point], Boolean)], arr: Array[Double], offset: Double): Vector[(Vector[Point], Boolean)] =
    val total = arr.sum
    if total <= 0 then return subpaths
    val out = Vector.newBuilder[(Vector[Point], Boolean)]
    subpaths.foreach { (raw, closed) =>
      val pts = if closed && raw.nonEmpty then raw :+ raw.head else raw
      if pts.size >= 2 then
        // starting state from the offset
        var off = ((offset % total) + total) % total
        var idx = 0
        while off >= arr(idx) do { off -= arr(idx); idx = (idx + 1) % arr.length; if arr(idx) == 0 && off == 0 then () }
        var remain = arr(idx) - off
        var on = idx % 2 == 0
        var cur = Vector.newBuilder[Point]
        var curN = 0
        if on then { cur += pts.head; curN = 1 }
        var i = 0
        while i < pts.size - 1 do
          var a = pts(i); val b = pts(i + 1)
          var segLen = a.distance(b)
          while segLen > remain && segLen > 0 do
            val t = remain / segLen
            val p = a.lerp(b, t)
            if on then { cur += p; out += ((cur.result(), false)); cur = Vector.newBuilder; curN = 0 }
            else { cur = Vector.newBuilder; cur += p; curN = 1 }
            on = !on
            a = p; segLen -= remain
            idx = (idx + 1) % arr.length; remain = arr(idx)
            if remain == 0 && on then { // zero-length dash: emit a dot
              out += ((Vector(p, p), false)); on = false; idx = (idx + 1) % arr.length; remain = arr(idx); cur = Vector.newBuilder; curN = 0
            }
          remain -= segLen
          if on then { cur += b; curN += 1 }
          i += 1
        if on && curN >= 2 then out += ((cur.result(), false))
    }
    out.result()

  /** Vertices of a path with the direction of the path entering and leaving each: `(point, inAngle, outAngle)`; angles in radians, NaN if none. */
  def vertices(path: Path): Vector[(Point, Double, Double)] =
    final case class Seg(p0: Point, p1: Point, startDir: Vec2, endDir: Vec2)
    // collect per-subpath segment lists
    val subs = Vector.newBuilder[(Vector[Seg], Boolean, Point)]
    var segs = Vector.newBuilder[Seg]; var start = Point.Zero; var cur = Point.Zero; var open = false
    def finish(closed: Boolean): Unit =
      if open then subs += ((segs.result(), closed, start))
      segs = Vector.newBuilder; open = false
    def dirOr(v: Vec2, fallbacks: Vec2*): Vec2 = (v +: fallbacks).find(x => x.lengthSquared > 1e-18).getOrElse(Vec2(0, 0))
    path.elements.foreach {
      case PathEl.MoveTo(p) => finish(false); start = p; cur = p; open = true
      case PathEl.LineTo(p) => val d = p - cur; segs += Seg(cur, p, d, d); cur = p
      case PathEl.QuadTo(c, p) => segs += Seg(cur, p, dirOr(c - cur, p - cur), dirOr(p - c, p - cur)); cur = p
      case PathEl.CubicTo(c1, c2, p) => segs += Seg(cur, p, dirOr(c1 - cur, c2 - cur, p - cur), dirOr(p - c2, p - c1, p - cur)); cur = p
      case PathEl.Close =>
        if open then
          if cur != start then { val d = start - cur; segs += Seg(cur, start, d, d) }
          finish(true); cur = start; open = true; start = cur
    }
    finish(false)
    def ang(v: Vec2): Double = if v.lengthSquared < 1e-18 then Double.NaN else math.atan2(v.y, v.x)
    def bisect(a: Double, b: Double): Double =
      if a.isNaN then b else if b.isNaN then a
      else
        val x = math.cos(a) + math.cos(b); val y = math.sin(a) + math.sin(b)
        if math.abs(x) < 1e-12 && math.abs(y) < 1e-12 then a else math.atan2(y, x)
    val out = Vector.newBuilder[(Point, Double, Double)]
    subs.result().foreach { (ss, closed, st) =>
      if ss.isEmpty then out += ((st, Double.NaN, Double.NaN))
      else
        for i <- ss.indices do
          val s = ss(i)
          val prev = if i > 0 then Some(ss(i - 1)) else if closed then Some(ss.last) else None
          val a = ang(s.startDir)
          val pin = prev.map(p => ang(p.endDir)).getOrElse(Double.NaN)
          out += ((s.p0, if prev.isDefined then pin else a, a)) // start vertex: bisector applied by caller
        val last = ss.last
        val firstDir = ang(ss.head.startDir)
        out += ((last.p1, ang(last.endDir), if closed then firstDir else Double.NaN))
    }
    // convert to a single orientation angle per vertex
    out.result().map { (p, in, outA) => (p, in, outA) }

  def vertexAngle(in: Double, out: Double): Double =
    if in.isNaN then (if out.isNaN then 0.0 else out) else if out.isNaN then in
    else
      val x = math.cos(in) + math.cos(out); val y = math.sin(in) + math.sin(out)
      if math.abs(x) < 1e-12 && math.abs(y) < 1e-12 then in else math.atan2(y, x)
