package scalaui.gpu

import scalaui.color.Color
import scalaui.geom.*
import scalaui.raster.{Path, Stroke, Stroker}
import scala.collection.mutable.ArrayBuffer

/** Immediate-mode 2D drawing in logical points with y pointing down. Builds a [[Frame]] of
  * batched triangles; no GPU access happens until a backend `submit`s it.
  */
final class Canvas(white: Texture):
  private var verts = new Array[Float](4096 * Vertex.Floats)
  private var vertCount = 0
  private val calls = ArrayBuffer.empty[DrawCall]
  private var runTexture: Texture = white
  private var runScissor: Option[Rect] = None
  private var runStart = 0
  private val clips = ArrayBuffer.empty[Rect]
  private var clearColor = Color.Black
  private var w = 0.0
  private var h = 0.0
  private var scale = 1.0

  def begin(width: Double, height: Double, scaleFactor: Double, clear: Color): Unit =
    w = width; h = height; scale = scaleFactor; clearColor = clear
    vertCount = 0; calls.clear(); clips.clear()
    runTexture = white; runScissor = None; runStart = 0

  def scaleFactor: Double = scale
  def width: Double = w
  def height: Double = h

  def finish(): Frame =
    flushRun()
    Frame(clearColor, w, h, scale, java.util.Arrays.copyOf(verts, vertCount * Vertex.Floats), calls.toVector)

  // --- clipping ---

  def pushClip(r: Rect): Unit =
    val next = clips.lastOption.map(_.intersect(r)).getOrElse(r)
    clips += next
    setState(runTexture, Some(next))
  def popClip(): Unit =
    clips.remove(clips.size - 1)
    setState(runTexture, clips.lastOption)

  // --- shapes ---

  def fillRect(r: Rect, c: Color): Unit = fillRectGradient(r, c, c, c, c)

  /** Per-corner colors, interpolated across the rect. */
  def fillRectGradient(r: Rect, tl: Color, tr: Color, bl: Color, br: Color): Unit =
    useTexture(white)
    quad(r, 0, 0, 0, 0, tl, tr, bl, br)

  def fillRoundedRect(r: Rect, radius: Double, c: Color): Unit =
    if radius <= 0.5 then fillRect(r, c) else fillConvex(roundedRectPoints(r, radius), c)

  def fillCircle(center: Point, radius: Double, c: Color): Unit = fillEllipse(center, radius, radius, c)

  def fillEllipse(center: Point, rx: Double, ry: Double, c: Color): Unit =
    val n = arcSegments(math.max(rx, ry)) * 4
    fillConvex(Vector.tabulate(n)(i => { val t = 2 * math.Pi * i / n; Point(center.x + rx * math.cos(t), center.y + ry * math.sin(t)) }), c)

  /** Fills a convex polygon as a triangle fan. */
  def fillConvex(points: Seq[Point], c: Color): Unit =
    if points.size >= 3 then
      useTexture(white)
      val col = c.premultiplied
      val p0 = points.head
      var i = 1
      while i < points.size - 1 do
        vertex(p0.x, p0.y, 0, 0, col.r, col.g, col.b, col.a)
        vertex(points(i).x, points(i).y, 0, 0, col.r, col.g, col.b, col.a)
        vertex(points(i + 1).x, points(i + 1).y, 0, 0, col.r, col.g, col.b, col.a)
        i += 1

  def strokeLine(a: Point, b: Point, width: Double, c: Color): Unit =
    val d = (b - a).normalized
    val n = Vec2(-d.y, d.x) * (width / 2)
    fillConvex(Vector(a + n, b + n, b - n, a - n), c)

  def strokeRect(r: Rect, width: Double, c: Color): Unit =
    val hw = width / 2
    fillRect(Rect(r.x0 - hw, r.y0 - hw, r.x1 + hw, r.y0 + hw), c)
    fillRect(Rect(r.x0 - hw, r.y1 - hw, r.x1 + hw, r.y1 + hw), c)
    fillRect(Rect(r.x0 - hw, r.y0 + hw, r.x0 + hw, r.y1 - hw), c)
    fillRect(Rect(r.x1 - hw, r.y0 + hw, r.x1 + hw, r.y1 - hw), c)

  def strokeCircle(center: Point, radius: Double, width: Double, c: Color): Unit =
    val n = arcSegments(radius + width) * 4
    val ro = radius + width / 2; val ri = math.max(0, radius - width / 2)
    useTexture(white)
    val col = c.premultiplied
    var i = 0
    while i < n do
      val t0 = 2 * math.Pi * i / n; val t1 = 2 * math.Pi * (i + 1) / n
      val o0 = Point(center.x + ro * math.cos(t0), center.y + ro * math.sin(t0))
      val o1 = Point(center.x + ro * math.cos(t1), center.y + ro * math.sin(t1))
      val i0 = Point(center.x + ri * math.cos(t0), center.y + ri * math.sin(t0))
      val i1 = Point(center.x + ri * math.cos(t1), center.y + ri * math.sin(t1))
      for p <- Seq(o0, o1, i0, o1, i1, i0) do vertex(p.x, p.y, 0, 0, col.r, col.g, col.b, col.a)
      i += 1

  /** Strokes a path; each segment/join is its own convex piece, so translucent strokes can double-blend at overlaps. */
  def strokePath(path: Path, style: Stroke, c: Color): Unit =
    Stroker.stroke(path.flatten(), style).foreach(fillConvex(_, c))

  /** Draws `src` (uv rect, 0..1) of `texture` into `dst`, multiplied by `tint`. */
  def drawImage(texture: Texture, dst: Rect, src: Rect = Rect(0, 0, 1, 1), tint: Color = Color.White): Unit =
    useTexture(texture)
    quad(dst, src.x0, src.y0, src.x1, src.y1, tint, tint, tint, tint)

  // --- internals ---

  private def quad(r: Rect, u0: Double, v0: Double, u1: Double, v1: Double, tl: Color, tr: Color, bl: Color, br: Color): Unit =
    val a = tl.premultiplied; val b = tr.premultiplied; val c = bl.premultiplied; val d = br.premultiplied
    vertex(r.x0, r.y0, u0, v0, a.r, a.g, a.b, a.a)
    vertex(r.x1, r.y0, u1, v0, b.r, b.g, b.b, b.a)
    vertex(r.x0, r.y1, u0, v1, c.r, c.g, c.b, c.a)
    vertex(r.x1, r.y0, u1, v0, b.r, b.g, b.b, b.a)
    vertex(r.x1, r.y1, u1, v1, d.r, d.g, d.b, d.a)
    vertex(r.x0, r.y1, u0, v1, c.r, c.g, c.b, c.a)

  private def vertex(x: Double, y: Double, u: Double, v: Double, r: Float, g: Float, b: Float, a: Float): Unit =
    if (vertCount + 1) * Vertex.Floats > verts.length then verts = java.util.Arrays.copyOf(verts, verts.length * 2)
    val i = vertCount * Vertex.Floats
    verts(i) = x.toFloat; verts(i + 1) = y.toFloat; verts(i + 2) = u.toFloat; verts(i + 3) = v.toFloat
    verts(i + 4) = r; verts(i + 5) = g; verts(i + 6) = b; verts(i + 7) = a
    vertCount += 1

  private def useTexture(t: Texture): Unit = setState(t, runScissor)

  private def setState(t: Texture, s: Option[Rect]): Unit =
    if (t ne runTexture) || s != runScissor then
      flushRun()
      runTexture = t; runScissor = s

  private def flushRun(): Unit =
    if vertCount > runStart then calls += DrawCall(runTexture, runScissor, runStart, vertCount - runStart)
    runStart = vertCount

  private def arcSegments(radius: Double): Int = math.max(4, math.min(48, math.ceil(math.sqrt(radius * scale) * 2).toInt))

  private def roundedRectPoints(r: Rect, radius: Double): Vector[Point] =
    val rr = radius.min(r.width / 2).min(r.height / 2)
    val n = arcSegments(rr)
    val corners = Seq((r.x1 - rr, r.y0 + rr, -math.Pi / 2), (r.x1 - rr, r.y1 - rr, 0.0), (r.x0 + rr, r.y1 - rr, math.Pi / 2), (r.x0 + rr, r.y0 + rr, math.Pi))
    corners.flatMap { (cx, cy, start) =>
      (0 to n).map { i => val t = start + (math.Pi / 2) * i / n; Point(cx + rr * math.cos(t), cy + rr * math.sin(t)) }
    }.toVector
