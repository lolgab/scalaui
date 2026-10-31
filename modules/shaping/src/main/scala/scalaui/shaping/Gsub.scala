package scalaui.shaping

import Bin.*

/** GSUB lookup types 1-6 and 8 (7, extension, is resolved when the table is loaded). */
private[shaping] final class GsubEngine(t: LayoutTable, gdef: Gdef) extends Engine(t, gdef):
  private var ligCounter = 0

  /** Whether lookup `index` would substitute the glyph sequence `gl` (HarfBuzz's would_substitute); with `zeroContext`
    * contextual rules that need backtrack or lookahead glyphs do not count. */
  def wouldApply(index: Int, gl: Array[Int], zeroContext: Boolean): Boolean =
    val l = t.lookups(index)
    l.subs.exists(o => wouldSub(l.kind, o, gl, zeroContext))

  private def cov(o: Int) = new Coverage(d, o)

  private def wouldSub(kind: Int, o: Int, gl: Array[Int], zero: Boolean): Boolean =
    kind match
      case 1 | 2 | 3 => gl.length == 1 && cov(o + u16(d, o + 2)).covers(gl(0))
      case 4 =>
        val ci = cov(o + u16(d, o + 2)).index(gl(0))
        if ci < 0 then false
        else
          val setO = o + u16(d, o + 6 + ci * 2)
          (0 until u16(d, setO)).exists { r =>
            val lo = setO + u16(d, setO + 2 + r * 2)
            u16(d, lo + 2) == gl.length && (1 until gl.length).forall(k => u16(d, lo + 2 + k * 2) == gl(k))
          }
      case 5 | 6 =>
        val chain = kind == 6
        u16(d, o) match
          case 1 =>
            val ci = cov(o + u16(d, o + 2)).index(gl(0))
            if ci < 0 then false
            else
              val setOff = u16(d, o + 6 + ci * 2)
              setOff != 0 && {
                val so = o + setOff
                (0 until u16(d, so)).exists { r =>
                  val ro = so + u16(d, so + 2 + r * 2)
                  if !chain then u16(d, ro) == gl.length && (1 until gl.length).forall(k => u16(d, ro + 4 + (k - 1) * 2) == gl(k))
                  else
                    val bt = u16(d, ro); val inO = ro + 2 + bt * 2
                    val n = u16(d, inO); val la = u16(d, inO + 2 + (n - 1) * 2)
                    !(zero && (bt > 0 || la > 0)) && n == gl.length && (1 until n).forall(k => u16(d, inO + 2 + (k - 1) * 2) == gl(k))
                }
              }
          case 2 =>
            if !cov(o + u16(d, o + 2)).covers(gl(0)) then false
            else if !chain then
              val cd = new ClassDef(d, o + u16(d, o + 4))
              val setOff = u16(d, o + 8 + cd.classOf(gl(0)) * 2)
              setOff != 0 && {
                val so = o + setOff
                (0 until u16(d, so)).exists { r =>
                  val ro = so + u16(d, so + 2 + r * 2)
                  u16(d, ro) == gl.length && (1 until gl.length).forall(k => cd.classOf(gl(k)) == u16(d, ro + 4 + (k - 1) * 2))
                }
              }
            else
              def cdAt(f: Int) = { val off = u16(d, o + f); new ClassDef(d, if off == 0 then -1 else o + off) }
              val inCd = cdAt(6)
              val setOff = u16(d, o + 12 + inCd.classOf(gl(0)) * 2)
              setOff != 0 && {
                val so = o + setOff
                (0 until u16(d, so)).exists { r =>
                  val ro = so + u16(d, so + 2 + r * 2)
                  val bt = u16(d, ro); val inO = ro + 2 + bt * 2
                  val n = u16(d, inO); val la = u16(d, inO + 2 + (n - 1) * 2)
                  !(zero && (bt > 0 || la > 0)) && n == gl.length && (1 until n).forall(k => inCd.classOf(gl(k)) == u16(d, inO + 2 + (k - 1) * 2))
                }
              }
          case 3 =>
            if !chain then
              val n = u16(d, o + 2)
              n == gl.length && (0 until n).forall(k => cov(o + u16(d, o + 6 + k * 2)).covers(gl(k)))
            else
              val bt = u16(d, o + 2); val inO = o + 4 + bt * 2
              val n = u16(d, inO); val laO = inO + 2 + n * 2; val la = u16(d, laO)
              !(zero && (bt > 0 || la > 0)) && n == gl.length && (0 until n).forall(k => cov(o + u16(d, inO + 2 + k * 2)).covers(gl(k)))
          case _ => false
      case _ => false

  override protected def reverseLookup(l: Lookup): Boolean = l.kind == 8

  override protected def applyOne(l: Lookup, i: Int): Int =
    var s = 0
    while s < l.subs.length do
      val r = sub(l.kind, l.subs(s), i)
      if r >= 0 then return r
      s += 1
    -1

  private def setGlyph(g: G, id: Int): Unit =
    g.id = id
    g.substituted = true
    if gdef.hasGlyphClasses then g.cls = gdef.glyphClass(id)

  private def sub(kind: Int, o: Int, i: Int): Int =
    val g = buf(i)
    kind match
      case 1 =>
        val ci = new Coverage(d, o + u16(d, o + 2)).index(g.id)
        if ci < 0 then return -1
        if u16(d, o) == 1 then setGlyph(g, (g.id + i16(d, o + 4)) & 0xFFFF)
        else setGlyph(g, u16(d, o + 6 + ci * 2))
        i + 1
      case 2 =>
        val ci = new Coverage(d, o + u16(d, o + 2)).index(g.id)
        if ci < 0 then return -1
        val so = o + u16(d, o + 6 + ci * 2)
        val n = u16(d, so)
        if n == 0 then { buf.remove(i); return i }
        setGlyph(g, u16(d, so + 2))
        g.multiplied = true
        g.ligComp = 0
        var k = 1
        while k < n do
          val ng = new G(u16(d, so + 2 + k * 2), g.cluster, g.cp, g.mask)
          ng.cls = if gdef.hasGlyphClasses then gdef.glyphClass(ng.id) else g.cls
          ng.ligId = g.ligId; ng.ligComp = k
          ng.syllable = g.syllable; ng.cat = g.cat; ng.pos = g.pos; ng.aux = g.aux
          ng.substituted = true; ng.multiplied = true
          buf.insert(i + k, ng)
          k += 1
        i + n
      case 3 =>
        val ci = new Coverage(d, o + u16(d, o + 2)).index(g.id)
        if ci < 0 then return -1
        val so = o + u16(d, o + 6 + ci * 2)
        if u16(d, so) == 0 then return -1
        setGlyph(g, u16(d, so + 2)) // first alternate (default feature value 1)
        i + 1
      case 4 => ligature(o, i)
      case 5 => context(o, i)
      case 6 => chainContext(o, i)
      case 8 => reverse(o, i)
      case _ => -1

  private def ligature(o: Int, i: Int): Int =
    val g = buf(i)
    val ci = new Coverage(d, o + u16(d, o + 2)).index(g.id)
    if ci < 0 then return -1
    val setO = o + u16(d, o + 6 + ci * 2)
    var r = 0
    while r < u16(d, setO) do
      val lo = setO + u16(d, setO + 2 + r * 2)
      val lig = u16(d, lo); val n = u16(d, lo + 2)
      val m = matchForward(i, n, (k, gl) => k == 0 || gl.id == u16(d, lo + 2 + k * 2))
      if m != null then
        if n > 1 then
          Syllabic.mergeClusters(buf, i, m(n - 1) + 1)
          // a base followed only by marks, or a sequence of marks, is not a "real" ligature for component tracking
          val restMarks = (1 until n).forall(k => buf(m(k)).cls == 3)
          val isLigature = !(restMarks && (g.cls == 1 || g.cls == 3))
          if isLigature then
            ligCounter += 1
            val id = ligCounter
            // HarfBuzz's component bookkeeping: glyphs skipped between components (marks) join the ligature at the
            // component they followed
            var lastNum = g.ligNumComps
            var soFar = lastNum
            var lastLigId = g.ligId
            var idx = i + 1
            for k <- 1 until n do
              while idx < m(k) do
                val mk = buf(idx)
                var thisComp = mk.ligComp
                if thisComp == 0 then thisComp = lastNum
                mk.ligId = id; mk.ligComp = soFar - lastNum + math.min(thisComp, lastNum)
                idx += 1
              val comp = buf(m(k))
              lastLigId = comp.ligId; lastNum = comp.ligNumComps
              soFar += lastNum
              idx = m(k) + 1
            // marks following the ligature that carry component info of the last component are re-attached to it
            var q = m(n - 1) + 1
            var go = true
            while q < buf.size && go do
              val mk = buf(q)
              if mk.ligId != lastLigId || mk.ligComp == 0 then go = false
              else { mk.ligId = id; mk.ligComp = soFar - lastNum + math.min(mk.ligComp, lastNum); q += 1 }
            g.ligId = id; g.ligComp = 0; g.ligNumComps = soFar
          var k = n - 1
          while k >= 1 do { buf.remove(m(k)); k -= 1 }
        setGlyph(g, lig)
        if n > 1 then g.ligated = true
        if !gdef.hasGlyphClasses && n > 1 then g.cls = 2
        return i + 1
      r += 1
    -1

  private def reverse(o: Int, i: Int): Int =
    val g = buf(i)
    val ci = new Coverage(d, o + u16(d, o + 2)).index(g.id)
    if ci < 0 then return -1
    val bt = u16(d, o + 4)
    val laO = o + 6 + bt * 2
    val la = u16(d, laO)
    val subO = laO + 2 + la * 2
    val okBack = matchBackward(i, bt, (k, gl) => new Coverage(d, o + u16(d, o + 6 + k * 2)).covers(gl.id))
    if !okBack then return -1
    var j = i; var k = 0
    while k < la do
      j = next(j)
      if j < 0 || !new Coverage(d, o + u16(d, laO + 2 + k * 2)).covers(buf(j).id) then return -1
      k += 1
    setGlyph(g, u16(d, subO + 2 + ci * 2))
    i
