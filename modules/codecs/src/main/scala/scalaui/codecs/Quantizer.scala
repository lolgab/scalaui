package scalaui.codecs

import scala.collection.mutable

/** Median-cut colour quantization for palette formats (GIF). Alpha is not quantized: callers pass a transparency policy. */
object Quantizer:
  /** Returns `(palette as 0xRRGGBB ints, index per pixel)` using at most `maxColors` entries. Pixels for which `skip(i)` is true get index -1. */
  def quantize(rgba: Array[Byte], maxColors: Int, skip: Int => Boolean): (Array[Int], Array[Int]) =
    val n = rgba.length / 4
    val counts = mutable.HashMap.empty[Int, Int]
    var i = 0
    while i < n do
      if !skip(i) then
        val c = ((rgba(i * 4) & 0xff) << 16) | ((rgba(i * 4 + 1) & 0xff) << 8) | (rgba(i * 4 + 2) & 0xff)
        counts(c) = counts.getOrElse(c, 0) + 1
      i += 1
    val colors = counts.toVector
    val palette: Vector[Int] =
      if colors.size <= maxColors then colors.map(_._1).sorted
      else
        // repeatedly split the box with the largest population along its widest channel at the weighted median
        final case class Box(cs: Vector[(Int, Int)]):
          def range(shift: Int): Int = { val v = cs.map(c => (c._1 >> shift) & 0xff); v.max - v.min }
          def widest: Int = Seq(16, 8, 0).maxBy(range)
          def population: Long = cs.map(_._2.toLong).sum
        var boxes = Vector(Box(colors))
        while boxes.size < maxColors && boxes.exists(_.cs.size > 1) do
          val b = boxes.filter(_.cs.size > 1).maxBy(_.population)
          val sh = b.widest
          val sorted = b.cs.sortBy(c => (c._1 >> sh) & 0xff)
          var acc = 0L; val half = b.population / 2
          var cut = sorted.indexWhere { c => acc += c._2; acc >= half }
          cut = math.max(1, math.min(sorted.size - 1, cut + 1))
          boxes = boxes.filterNot(_ eq b) ++ Vector(Box(sorted.take(cut)), Box(sorted.drop(cut)))
        boxes.map { b =>
          val pop = b.population.toDouble
          def avg(shift: Int) = math.round(b.cs.map(c => ((c._1 >> shift) & 0xff) * c._2.toDouble).sum / pop).toInt
          (avg(16) << 16) | (avg(8) << 8) | avg(0)
        }.sorted
    val pal = palette.toArray
    val cache = mutable.HashMap.empty[Int, Int]
    def nearest(c: Int): Int = cache.getOrElseUpdate(c, {
      var best = 0; var bd = Int.MaxValue
      var k = 0
      while k < pal.length do
        val p = pal(k)
        val dr = ((c >> 16) & 0xff) - ((p >> 16) & 0xff); val dg = ((c >> 8) & 0xff) - ((p >> 8) & 0xff); val db = (c & 0xff) - (p & 0xff)
        val d = 2 * dr * dr + 4 * dg * dg + 3 * db * db
        if d < bd then { bd = d; best = k }
        k += 1
      best
    })
    val idx = Array.tabulate(n) { j =>
      if skip(j) then -1
      else nearest(((rgba(j * 4) & 0xff) << 16) | ((rgba(j * 4 + 1) & 0xff) << 8) | (rgba(j * 4 + 2) & 0xff))
    }
    (pal, idx)
