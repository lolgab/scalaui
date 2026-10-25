package scalaui.raster

import scalaui.geom.*

/** Anti-aliased scanline polygon filler: SubY sub-scanlines per pixel row, exact horizontal span coverage.
  *
  * Edges live in flat primitive arrays and only the edges crossing the current pixel row are visited (an active list kept in
  * original edge order, so ties between equal crossings resolve exactly as a stable sort of all edges would). Coverage is
  * accumulated per row and handed to a [[Rasterizer.RowSink]] as a dense `Array[Float]`, which keeps the per-pixel work free of
  * allocation and boxing.
  */
object Rasterizer:
  private val SubY = 8

  /** Receives the coverage of one pixel row: `cov(x)` for `x` in `[x0, x1]` is in 0..1 (zero entries are possible). */
  trait RowSink:
    def row(y: Int, cov: Array[Float], x0: Int, x1: Int): Unit

  /** Calls `emit(x, y, coverage)` for every pixel whose coverage exceeds a small threshold. */
  def fill(polys: Vector[Vector[Point]], rule: FillRule, width: Int, height: Int)(emit: (Int, Int, Float) => Unit): Unit =
    fillRows(polys, rule, width, height) { (y, cov, x0, x1) =>
      var x = x0
      while x <= x1 do
        val c = cov(x)
        if c > 0.0005f then emit(x, y, c.min(1f))
        x += 1
    }

  /** Like [[fill]] but delivers whole rows. */
  def fillRows(polys: Vector[Vector[Point]], rule: FillRule, width: Int, height: Int)(sink: RowSink): Unit =
    // edges: (x0, y0) -> (x1, y1) with y0 < y1 and winding direction
    var n = 0
    polys.foreach(p => if p.size >= 2 then n += p.size)
    val ex0 = new Array[Double](n); val ey0 = new Array[Double](n); val ex1 = new Array[Double](n); val ey1 = new Array[Double](n)
    val edir = new Array[Int](n)
    var m = 0
    var minY = Double.MaxValue; var maxY = Double.MinValue
    polys.foreach { pts =>
      val sz = pts.size
      if sz >= 2 then
        var i = 0
        while i < sz do
          val a = pts(i); val b = pts(if i + 1 == sz then 0 else i + 1)
          if a.y != b.y then
            if a.y < b.y then { ex0(m) = a.x; ey0(m) = a.y; ex1(m) = b.x; ey1(m) = b.y; edir(m) = 1 }
            else { ex0(m) = b.x; ey0(m) = b.y; ex1(m) = a.x; ey1(m) = a.y; edir(m) = -1 }
            m += 1
            val lo = math.min(a.y, b.y); val hi = math.max(a.y, b.y)
            if lo < minY then minY = lo
            if hi > maxY then maxY = hi
          i += 1
    }
    if m == 0 then return
    val rowStart = math.max(0, math.floor(minY).toInt)
    val rowEnd = math.min(height - 1, math.ceil(maxY).toInt)
    if rowStart > rowEnd then return
    // bucket edges by the first pixel row they touch (counting sort), preserving original order within a bucket
    val first = new Array[Int](m)
    val bucket = new Array[Int](rowEnd - rowStart + 2)
    var e = 0
    while e < m do
      val r = math.max(rowStart, math.floor(ey0(e)).toInt)
      first(e) = r
      if r <= rowEnd then bucket(r - rowStart + 1) += 1
      e += 1
    var b = 0
    while b < bucket.length - 1 do { bucket(b + 1) += bucket(b); b += 1 }
    val order = new Array[Int](bucket(bucket.length - 1))
    val fillPos = java.util.Arrays.copyOf(bucket, bucket.length)
    e = 0
    while e < m do
      if first(e) <= rowEnd then { val k = first(e) - rowStart; order(fillPos(k)) = e; fillPos(k) += 1 }
      e += 1

    val active = new Array[Int](m); var nActive = 0
    val incoming = new Array[Int](m)
    val acc = new Array[Float](width + 2)
    val xs = new Array[Double](m); val ds = new Array[Int](m)
    val evenOdd = rule == FillRule.EvenOdd
    var row = rowStart
    while row <= rowEnd do
      // drop finished edges (keeps order), then merge in edges that start in this row (both lists ascending by edge index)
      var w = 0; var i = 0
      while i < nActive do
        val a = active(i)
        if ey1(a) > row then { active(w) = a; w += 1 }
        i += 1
      nActive = w
      val bs = bucket(row - rowStart); val be = bucket(row - rowStart + 1)
      if be > bs then
        val cnt = be - bs
        System.arraycopy(order, bs, incoming, 0, cnt)
        // merge from the back so no temporary array is needed
        var ia = nActive - 1; var ib = cnt - 1; var o = nActive + cnt - 1
        while ib >= 0 do
          if ia >= 0 && active(ia) > incoming(ib) then { active(o) = active(ia); ia -= 1 } else { active(o) = incoming(ib); ib -= 1 }
          o -= 1
        nActive += cnt
      if nActive > 0 then
        var spanLo = Int.MaxValue; var spanHi = -1
        var s = 0
        while s < SubY do
          val y = row + (s + 0.5) / SubY
          var nx = 0
          var k = 0
          while k < nActive do
            val a = active(k)
            val y0 = ey0(a); val y1 = ey1(a)
            if y >= y0 && y < y1 then
              val x = ex0(a) + (y - y0) * (ex1(a) - ex0(a)) / (y1 - y0)
              // stable insertion sort by x
              var j = nx
              while j > 0 && xs(j - 1) > x do { xs(j) = xs(j - 1); ds(j) = ds(j - 1); j -= 1 }
              xs(j) = x; ds(j) = edir(a)
              nx += 1
            k += 1
          if nx > 0 then
            var wind = 0; var q = 0
            while q < nx - 1 do
              wind += ds(q)
              val inside = if evenOdd then (q % 2) == 0 else wind != 0
              if inside then
                val xa = math.max(0.0, xs(q)); val xb = math.min(width.toDouble, xs(q + 1))
                if xb > xa then
                  val ia = xa.toInt; val ib = math.min(width - 1, xb.toInt)
                  if ia == ib then acc(ia) += (xb - xa).toFloat
                  else
                    acc(ia) += (ia + 1 - xa).toFloat
                    var x = ia + 1
                    while x < ib do { acc(x) += 1f; x += 1 }
                    acc(ib) += (xb - ib).toFloat
                  if ia < spanLo then spanLo = ia
                  if ib > spanHi then spanHi = ib
              q += 1
          s += 1
        if spanHi >= 0 then
          var x = spanLo
          while x <= spanHi do { acc(x) = acc(x) / SubY; x += 1 }
          sink.row(row, acc, spanLo, spanHi)
          x = spanLo
          while x <= spanHi do { acc(x) = 0f; x += 1 }
      row += 1
