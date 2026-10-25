package scalaui.shaping

import scala.collection.mutable.ArrayBuffer
import scalaui.font.FontFace
import scalaui.unicode.{Gc, Normalizer}

/** Normalization the way text shapers need it (a port of HarfBuzz's approach): decompose what the font cannot show,
  * order marks canonically (with shaper-specific class overrides), then recompose marks the font has composed glyphs for.
  * `shortCircuit` keeps characters the font has a glyph for instead of decomposing them first.
  */
private[shaping] final class NormalizeHooks(
    val shortCircuit: Boolean = true,
    /** Keep characters the font has even inside mark clusters (HarfBuzz normalization mode NONE). */
    val alwaysShortCircuit: Boolean = false,
    /** Recompose marks with their starters after ordering. */
    val recompose: Boolean = true,
    val decompose: Int => Option[(Int, Int)] = Normalizer.decomposeOnce,
    val compose: (Int, Int) => Int = Normalizer.composePair,
    /** Called with a canonically sorted mark run `[start, end)`; may reorder it (and clusters). */
    val reorderMarks: (ArrayBuffer[Int], ArrayBuffer[Int], Int, Int) => Unit = (_, _, _, _) => ())

private[shaping] object Normalize:
  private def isMark(cp: Int): Boolean =
    val g = Gc.table(cp)
    g == Gc.Mn || g == Gc.Mc || g == Gc.Me

  /** `tags` are caller data per character that decomposed characters inherit. Returns code points, clusters and tags. */
  def apply(font: FontFace, cps: Array[Int], clusters: Array[Int], tags: Array[Int], h: NormalizeHooks): (Array[Int], Array[Int], Array[Int]) =
    val c = ArrayBuffer.empty[Int]; val cl = ArrayBuffer.empty[Int]; val tg = ArrayBuffer.empty[Int]
    var curTag = 0
    def out(cp: Int, cluster: Int): Unit = { c += cp; cl += cluster; tg += curTag }

    // returns how many characters were output, 0 when nothing decomposed
    def decompose(ab: Int, shortest: Boolean, cluster: Int): Int =
      h.decompose(ab) match
        case None => 0
        case Some((a, b)) =>
          if b != 0 && !font.hasGlyph(b) then 0
          else
            val hasA = font.hasGlyph(a)
            if shortest && hasA then
              out(a, cluster); if b != 0 then out(b, cluster)
              if b != 0 then 2 else 1
            else
              val ret = decompose(a, shortest, cluster)
              if ret > 0 then
                if b != 0 then { out(b, cluster); ret + 1 } else ret
              else if hasA then
                out(a, cluster); if b != 0 then { out(b, cluster); 2 } else 1
              else 0

    def decomposeCurrent(u: Int, cluster: Int, shortest: Boolean): Unit =
      if shortest && font.hasGlyph(u) then out(u, cluster)
      else if decompose(u, shortest, cluster) > 0 then ()
      else if !shortest && font.hasGlyph(u) then out(u, cluster)
      else if u == 0x2011 && font.hasGlyph(0x2010) then out(0x2010, cluster)
      else out(u, cluster)

    // 1. decompose; a base with marks is a "multi-character cluster" and never short-circuits
    var i = 0
    val n = cps.length
    while i < n do
      var end = i + 1
      while end < n && !isMark(cps(end)) do end += 1
      val simpleEnd = if end < n then end - 1 else end // leave one base for the marks that follow
      while i < simpleEnd do { curTag = tags(i); decomposeCurrent(cps(i), clusters(i), h.shortCircuit || h.alwaysShortCircuit); i += 1 }
      if i < n then
        var e = i + 1
        while e < n && isMark(cps(e)) do e += 1
        while i < e do { curTag = tags(i); decomposeCurrent(cps(i), clusters(i), h.alwaysShortCircuit); i += 1 }

    // 2. canonical ordering of mark runs (modified classes), with the shaper's own adjustments
    i = 0
    while i < c.length do
      if Normalizer.modifiedCombiningClass(c(i)) == 0 then i += 1
      else
        var end = i + 1
        while end < c.length && Normalizer.modifiedCombiningClass(c(end)) != 0 do end += 1
        if end - i <= 32 then
          val idx = (i until end).sortBy(k => Normalizer.modifiedCombiningClass(c(k)))
          val vs = idx.map(c(_)); val cs = idx.map(cl(_)); val ts = idx.map(tg(_))
          vs.indices.foreach { k => c(i + k) = vs(k); cl(i + k) = cs(k); tg(i + k) = ts(k) }
          h.reorderMarks(c, cl, i, end)
        i = end

    // 3. recompose: only marks are composed with the preceding starter
    val oc = ArrayBuffer.empty[Int]; val ocl = ArrayBuffer.empty[Int]; val otg = ArrayBuffer.empty[Int]
    if !h.recompose then return (c.toArray, cl.toArray, tg.toArray)
    if c.nonEmpty then
      oc += c(0); ocl += cl(0); otg += tg(0)
      var starter = 0
      var j = 1
      while j < c.length do
        val x = c(j)
        var composed = false
        if isMark(x) && (starter == oc.length - 1 || Normalizer.modifiedCombiningClass(oc.last) < Normalizer.modifiedCombiningClass(x)) then
          val comp = h.compose(oc(starter), x)
          if comp >= 0 && font.hasGlyph(comp) then
            oc(starter) = comp
            val m = math.min(ocl.slice(starter, ocl.length).min, cl(j))
            (starter until ocl.length).foreach(ocl(_) = m)
            composed = true
        if !composed then
          oc += x; ocl += cl(j); otg += tg(j)
          if Normalizer.modifiedCombiningClass(x) == 0 then starter = oc.length - 1
        j += 1
    (oc.toArray, ocl.toArray, otg.toArray)
