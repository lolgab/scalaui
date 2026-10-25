package scalaui.raster

import scalaui.color.*
import scalaui.geom.*

class RasterSuite extends scalaui.testkit.LoggedSuite:
  test("fill axis-aligned rect gives exact coverage") {
    val pm = new Pixmap(10, 10)
    pm.fill(Path.rect(Rect(2, 2, 6, 6)), Paint.solid(Color.White))
    assertEqualsDouble(pm.get(3, 3).a, 1.0, 1e-5)
    assertEqualsDouble(pm.get(0, 0).a, 0.0, 1e-5)
    assertEqualsDouble(pm.get(6, 3).a, 0.0, 1e-5)
  }
  test("half-pixel edge gives 50% coverage") {
    val pm = new Pixmap(4, 4)
    pm.fill(Path.rect(Rect(0.5, 0, 4, 4)), Paint.solid(Color.White))
    assertEqualsDouble(pm.get(0, 1).a, 0.5, 1e-4)
  }
  test("circle area is approx pi r^2") {
    val pm = new Pixmap(40, 40)
    pm.fill(Path.circle(Point(20, 20), 10), Paint.solid(Color.White))
    val area = (for y <- 0 until 40; x <- 0 until 40 yield pm.get(x, y).a).sum
    assertEqualsDouble(area, math.Pi * 100, 2.0)
  }
  test("even-odd leaves a hole") {
    val pm = new Pixmap(20, 20)
    val p = Path.builder.moveTo(0, 0).lineTo(20, 0).lineTo(20, 20).lineTo(0, 20).close()
      .moveTo(5, 5).lineTo(15, 5).lineTo(15, 15).lineTo(5, 15).close().build
    pm.fill(p, Paint.solid(Color.White), FillRule.EvenOdd)
    assertEqualsDouble(pm.get(10, 10).a, 0.0, 1e-5)
    assertEqualsDouble(pm.get(2, 2).a, 1.0, 1e-5)
  }
  test("stroke width") {
    val pm = new Pixmap(20, 20)
    pm.stroke(Path.builder.moveTo(0, 10).lineTo(20, 10).build, Paint.solid(Color.White), Stroke(4))
    assertEqualsDouble(pm.get(10, 8).a, 1.0, 1e-5)
    assertEqualsDouble(pm.get(10, 11).a, 1.0, 1e-5)
    assertEqualsDouble(pm.get(10, 12).a, 0.0, 1e-5)
  }
  test("round joins and caps do not cancel the segments they overlap") {
    val pm = new Pixmap(60, 60)
    val path = Path.builder.moveTo(5, 50).cubicTo(5, 5, 55, 5, 55, 50).build
    pm.stroke(path, Paint.solid(Color.White), Stroke(8, LineCap.Round, LineJoin.Round))
    // centerline of the cubic at t=0.5 is (30, 16.25): must be solid
    assertEqualsDouble(pm.get(30, 16).a, 1.0, 1e-3)
    // start cap center and a point along the curve
    assertEqualsDouble(pm.get(5, 50).a, 1.0, 1e-3)
    assertEqualsDouble(pm.get(10, 30).a, 1.0, 1e-3)
  }

  // The straightforward formulation the optimized Rasterizer replaced: every sub-scanline scans all edges and sorts the crossings.
  private def referenceCoverage(polys: Vector[Vector[Point]], rule: FillRule, width: Int, height: Int): Array[Float] =
    val SubY = 8
    val out = new Array[Float](width * height)
    val edges = scala.collection.mutable.ArrayBuffer.empty[(Double, Double, Double, Double, Int)]
    var minY = Double.MaxValue; var maxY = Double.MinValue
    polys.foreach { pts =>
      if pts.size >= 2 then
        for i <- pts.indices do
          val a = pts(i); val b = pts((i + 1) % pts.size)
          if a.y != b.y then
            if a.y < b.y then edges += ((a.x, a.y, b.x, b.y, 1)) else edges += ((b.x, b.y, a.x, a.y, -1))
            minY = minY.min(a.y.min(b.y)); maxY = maxY.max(a.y.max(b.y))
    }
    if edges.isEmpty then return out
    for row <- math.max(0, math.floor(minY).toInt) to math.min(height - 1, math.ceil(maxY).toInt) do
      val acc = new Array[Float](width + 2)
      for sub <- 0 until SubY do
        val y = row + (sub + 0.5) / SubY
        val xs = edges.collect { case (x0, y0, x1, y1, dir) if y >= y0 && y < y1 => (x0 + (y - y0) * (x1 - x0) / (y1 - y0), dir) }.sortBy(_._1)
        var wind = 0
        for i <- 0 until xs.size - 1 do
          wind += xs(i)._2
          val inside = rule match
            case FillRule.NonZero => wind != 0
            case FillRule.EvenOdd => (i % 2) == 0
          if inside then
            val a = math.max(0.0, xs(i)._1); val b = math.min(width.toDouble, xs(i + 1)._1)
            if b > a then
              val ia = a.toInt; val ib = math.min(width - 1, b.toInt)
              if ia == ib then acc(ia) += (b - a).toFloat
              else
                acc(ia) += (ia + 1 - a).toFloat
                for x <- ia + 1 until ib do acc(x) += 1f
                acc(ib) += (b - ib).toFloat
      for x <- 0 until width do
        val c = acc(x) / SubY
        if c > 0.0005f then out(row * width + x) = c.min(1f)
    out

  test("rasterizer coverage is bit-identical to the reference formulation (random polygons, both rules, clipped)") {
    val rnd = new scala.util.Random(42)
    val (w, h) = (64, 48)
    for round <- 0 until 60 do
      val polys = Vector.tabulate(1 + rnd.nextInt(4)) { _ =>
        Vector.tabulate(2 + rnd.nextInt(10))(_ => Point(rnd.nextDouble() * 90 - 15, rnd.nextDouble() * 70 - 10))
      }
      // include axis-aligned and shared-vertex cases
      val more = if round % 7 == 0 then polys :+ Vector(Point(10, 10), Point(30, 10), Point(30, 30), Point(10, 30)) else polys
      for rule <- Seq(FillRule.NonZero, FillRule.EvenOdd) do
        val expected = referenceCoverage(more, rule, w, h)
        val got = new Array[Float](w * h)
        Rasterizer.fill(more, rule, w, h)((x, y, c) => got(y * w + x) = c)
        assert(java.util.Arrays.equals(expected, got), s"round $round $rule differs")
  }
