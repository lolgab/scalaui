package scalaui.shaping

import Bin.*

/** GPOS lookup types 1-8 (9, extension, is resolved when the table is loaded). Values are in font units. */
private[shaping] final class GposEngine(t: LayoutTable, gdef: Gdef) extends Engine(t, gdef):

  override protected def isGpos: Boolean = true
  /** Top-to-bottom direction: cursive attachment works along the y axis. */
  var vertical: Boolean = false

  override protected def applyOne(l: Lookup, i: Int): Int =
    var s = 0
    while s < l.subs.length do
      val r = sub(l.kind, l.subs(s), i)
      if r >= 0 then return r
      s += 1
    -1

  private def valueSize(fmt: Int): Int = Integer.bitCount(fmt & 0xFF) * 2

  /** Adds the value record at `o` (laid out per `fmt`) to glyph `g`. */
  private def applyValue(g: G, o: Int, fmt: Int): Unit =
    var p = o
    if (fmt & 1) != 0 then { g.xOff += i16(d, p); p += 2 }
    if (fmt & 2) != 0 then { g.yOff += i16(d, p); p += 2 }
    // like HarfBuzz, advances only apply along the text direction
    if (fmt & 4) != 0 then { if !vertical then g.xAdv += i16(d, p); p += 2 }
    // y advances grow downward in GPOS but upward in font space (HarfBuzz negates them)
    if (fmt & 8) != 0 then { if vertical then g.yAdv -= i16(d, p); p += 2 }

  private def anchorX(o: Int) = i16(d, o + 2)
  private def anchorY(o: Int) = i16(d, o + 4)

  private def sub(kind: Int, o: Int, i: Int): Int =
    val g = buf(i)
    kind match
      case 1 =>
        val ci = new Coverage(d, o + u16(d, o + 2)).index(g.id)
        if ci < 0 then return -1
        val fmt = u16(d, o + 4)
        if u16(d, o) == 1 then applyValue(g, o + 6, fmt)
        else applyValue(g, o + 8 + ci * valueSize(fmt), fmt)
        i + 1
      case 2 => pair(o, i)
      case 3 => cursive(o, i)
      case 4 => markBase(o, i)
      case 5 => markLigature(o, i)
      case 6 => markMark(o, i)
      case 7 => context(o, i)
      case 8 => chainContext(o, i)
      case _ => -1

  private def pair(o: Int, i: Int): Int =
    val g = buf(i)
    val ci = new Coverage(d, o + u16(d, o + 2)).index(g.id)
    if ci < 0 then return -1
    val j = next(i)
    if j < 0 || (buf(j).mask & mask) == 0 then return -1
    val second = buf(j)
    val vf1 = u16(d, o + 4); val vf2 = u16(d, o + 6)
    val s1 = valueSize(vf1); val s2 = valueSize(vf2)
    if u16(d, o) == 1 then
      val setO = o + u16(d, o + 10 + ci * 2)
      val n = u16(d, setO); val rec = 2 + s1 + s2
      var lo = 0; var hi = n - 1
      while lo <= hi do
        val mid = (lo + hi) >>> 1; val ro = setO + 2 + mid * rec
        val sg = u16(d, ro)
        if sg < second.id then lo = mid + 1
        else if sg > second.id then hi = mid - 1
        else
          applyValue(g, ro + 2, vf1); applyValue(second, ro + 2 + s1, vf2)
          return if vf2 != 0 then j + 1 else j
      -1
    else
      val cd1 = new ClassDef(d, o + u16(d, o + 8)); val cd2 = new ClassDef(d, o + u16(d, o + 10))
      val c1 = cd1.classOf(g.id); val c2 = cd2.classOf(second.id)
      val class1Count = u16(d, o + 12); val class2Count = u16(d, o + 14)
      if c1 >= class1Count || c2 >= class2Count then return -1
      val ro = o + 16 + (c1 * class2Count + c2) * (s1 + s2)
      applyValue(g, ro, vf1); applyValue(second, ro + s1, vf2)
      if vf2 != 0 then j + 1 else j

  private def cursive(o: Int, j: Int): Int =
    val cov = new Coverage(d, o + u16(d, o + 2))
    val cj = cov.index(buf(j).id)
    if cj < 0 then return -1
    val entryOff = u16(d, o + 4 + cj * 4)
    if entryOff == 0 then return -1
    val i = prev(j)
    if i < 0 then return -1
    val ci = cov.index(buf(i).id)
    if ci < 0 then return -1
    val exitOff = u16(d, o + 4 + ci * 4 + 2)
    if exitOff == 0 then return -1
    val entry = o + entryOff; val exit = o + exitOff
    val pi = buf(i); val pj = buf(j)
    if vertical then
      pi.yAdv = anchorY(exit) + pi.yOff
      val dd = anchorY(entry) + pj.yOff
      pj.yAdv -= dd; pj.yOff -= dd
    else if !rtl then
      pi.xAdv = anchorX(exit) + pi.xOff
      val dd = anchorX(entry) + pj.xOff
      pj.xAdv -= dd; pj.xOff -= dd
    else
      val dd = anchorX(exit) + pi.xOff
      pi.xAdv -= dd; pi.xOff -= dd
      pj.xAdv = anchorX(entry) + pj.xOff
    var child = i; var parent = j
    var yOffset = anchorY(entry) - anchorY(exit)
    var xOffset = anchorX(entry) - anchorX(exit)
    if (lk.flag & 1) == 0 then { child = j; parent = i; yOffset = -yOffset; xOffset = -xOffset }
    buf(child).attachType = Attach.Cursive
    buf(child).attachChain = parent - child
    if vertical then buf(child).xOff = xOffset else buf(child).yOff = yOffset
    j + 1

  /** The previous glyph that is neither a mark nor an ignorable (the lookup's own flags do not apply here).
    * A ZWJ is not skipped: a mark after a joiner has nothing to attach to (observed HarfBuzz behaviour). */
  private def prevNonMark(i: Int): Int =
    var j = i - 1
    while j >= 0 && (buf(j).cls == 3 || (buf(j).aux == 1 && !buf(j).substituted && !buf(j).isZwj)) do j -= 1
    j

  private def attachMark(mark: G, markIdx: Int, baseIdx: Int, markAnchor: Int, baseAnchor: Int): Unit =
    mark.xOff = anchorX(baseAnchor) - anchorX(markAnchor)
    mark.yOff = anchorY(baseAnchor) - anchorY(markAnchor)
    mark.attachType = Attach.Mark
    mark.attachChain = baseIdx - markIdx

  private def markBase(o: Int, i: Int): Int =
    val g = buf(i)
    val mi = new Coverage(d, o + u16(d, o + 2)).index(g.id)
    if mi < 0 then return -1
    val j = prevNonMark(i)
    if j < 0 then return -1
    val bi = new Coverage(d, o + u16(d, o + 4)).index(buf(j).id)
    if bi < 0 then return -1
    val classCount = u16(d, o + 6)
    val markArray = o + u16(d, o + 8); val baseArray = o + u16(d, o + 10)
    val cls = u16(d, markArray + 2 + mi * 4)
    if cls >= classCount then return -1
    val markAnchor = u16(d, markArray + 2 + mi * 4 + 2)
    val baseAnchor = u16(d, baseArray + 2 + (bi * classCount + cls) * 2)
    if markAnchor == 0 || baseAnchor == 0 then return -1
    attachMark(g, i, j, markArray + markAnchor, baseArray + baseAnchor)
    i + 1

  private def markLigature(o: Int, i: Int): Int =
    val g = buf(i)
    val mi = new Coverage(d, o + u16(d, o + 2)).index(g.id)
    if mi < 0 then return -1
    val j = prevNonMark(i)
    if j < 0 then return -1
    val li = new Coverage(d, o + u16(d, o + 4)).index(buf(j).id)
    if li < 0 then return -1
    val classCount = u16(d, o + 6)
    val markArray = o + u16(d, o + 8); val ligArray = o + u16(d, o + 10)
    val ligAttach = ligArray + u16(d, ligArray + 2 + li * 2)
    val compCount = u16(d, ligAttach)
    if compCount == 0 then return -1
    val lig = buf(j)
    val comp =
      if g.ligId != 0 && g.ligId == lig.ligId && g.ligComp > 0 then math.min(compCount, g.ligComp) - 1 else compCount - 1
    val cls = u16(d, markArray + 2 + mi * 4)
    if cls >= classCount then return -1
    val markAnchor = u16(d, markArray + 2 + mi * 4 + 2)
    val ligAnchor = u16(d, ligAttach + 2 + (comp * classCount + cls) * 2)
    if markAnchor == 0 || ligAnchor == 0 then return -1
    attachMark(g, i, j, markArray + markAnchor, ligAttach + ligAnchor)
    i + 1

  private def markMark(o: Int, i: Int): Int =
    val g = buf(i)
    val m1 = new Coverage(d, o + u16(d, o + 2)).index(g.id)
    if m1 < 0 then return -1
    val j = prev(i)
    if j < 0 then return -1
    val other = buf(j)
    if other.cls != 3 then return -1
    val m2 = new Coverage(d, o + u16(d, o + 4)).index(other.id)
    if m2 < 0 then return -1
    val ok =
      if g.ligId == other.ligId then g.ligId == 0 || g.ligComp == other.ligComp
      else (g.ligId > 0 && g.ligComp == 0) || (other.ligId > 0 && other.ligComp == 0)
    if !ok then return -1
    val classCount = u16(d, o + 6)
    val mark1Array = o + u16(d, o + 8); val mark2Array = o + u16(d, o + 10)
    val cls = u16(d, mark1Array + 2 + m1 * 4)
    if cls >= classCount then return -1
    val a1 = u16(d, mark1Array + 2 + m1 * 4 + 2)
    val a2 = u16(d, mark2Array + 2 + (m2 * classCount + cls) * 2)
    if a1 == 0 || a2 == 0 then return -1
    attachMark(g, i, j, mark1Array + a1, mark2Array + a2)
    i + 1

/** Post-GPOS fixups: marks keep zero advance, attached glyphs inherit their anchor glyph's offsets. */
private[shaping] object Positioning:
  def finish(buf: scala.collection.mutable.ArrayBuffer[G], rtl: Boolean, zeroMarks: Boolean, vertical: Boolean = false): Unit =
    if zeroMarks then buf.foreach(g => if g.cls == 3 then { g.xAdv = 0; g.yAdv = 0 })
    val done = new Array[Boolean](buf.size)
    def propagate(i: Int): Unit =
      if !done(i) then
        done(i) = true
        val g = buf(i)
        if g.attachChain != 0 then
          val j = i + g.attachChain
          g.attachChain = 0
          if j >= 0 && j < buf.size then
            propagate(j)
            val b = buf(j)
            if g.attachType == Attach.Cursive then { if vertical then g.xOff += b.xOff else g.yOff += b.yOff }
            else
              g.xOff += b.xOff; g.yOff += b.yOff
              if !rtl then
                var k = j
                while k < i do { g.xOff -= buf(k).xAdv; g.yOff -= buf(k).yAdv; k += 1 }
              else
                var k = j + 1
                while k <= i do { g.xOff += buf(k).xAdv; g.yOff += buf(k).yAdv; k += 1 }
    var i = 0
    while i < buf.size do { propagate(i); i += 1 }
