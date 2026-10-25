package scalaui.shaping

import scala.collection.mutable.ArrayBuffer
import Bin.*

/** A glyph in the shaping buffer. Metrics are in font units. */
private[shaping] final class G(var id: Int, var cluster: Int, val cp: Int, var mask: Int):
  var cls = 0                // GDEF class: 1 base, 2 ligature, 3 mark
  var ligId = 0; var ligComp = 0
  var ligNumComps = 1        // components a ligature glyph represents
  var xAdv = 0; var yAdv = 0; var xOff = 0; var yOff = 0
  var attachChain = 0; var attachType = 0 // relative index of the glyph this one attaches to; 1 mark, 2 cursive
  var aux = 0                // 1: default-ignorable character (ZWJ, ZWNJ, ...), hidden once shaping is done
  var syllable = 0           // syllable id (serial << 4 | type) set by syllabic shapers
  var feat = 0               // shaper-specific per-character tag (e.g. Hangul jamo role)
  var cat = 0                // shaper category (ShaperCat)
  var pos = 0                // shaper position (Pos)
  var substituted = false; var ligated = false; var multiplied = false
  def ligatedAndDidntMultiply: Boolean = ligated && !multiplied
  def isZwj: Boolean = cp == 0x200D
  def isZwnj: Boolean = cp == 0x200C

private[shaping] object Attach:
  inline val Mark = 1
  inline val Cursive = 2

/** Lookup-application machinery shared by GSUB and GPOS: glyph skipping by lookup flags, contextual matching and
  * nested lookup records.
  */
private[shaping] abstract class Engine(val t: LayoutTable, val gdef: Gdef):
  protected val d: Array[Byte] = t.d
  var buf: ArrayBuffer[G] = ArrayBuffer.empty
  var mask: Int = 0
  var rtl: Boolean = false
  protected var lk: Lookup = null
  private var depth = 0
  /** Per-lookup behaviour from the plan: `autoZwj`/`autoZwnj` let joiners be skipped while matching, `perSyllable`
    * confines input matching to the syllable of the first glyph. */
  var autoZwj = true; var autoZwnj = true; var perSyllable = false
  private var syl = 0

  /** Applies lookup `l` at index `i`; returns the index to continue from, or -1 when nothing applied. */
  protected def applyOne(l: Lookup, i: Int): Int

  protected def skip(g: G): Boolean =
    val f = lk.flag
    val c = g.cls
    if (f & 0x2) != 0 && c == 1 then true
    else if (f & 0x4) != 0 && c == 2 then true
    else if c == 3 then
      if (f & 0x8) != 0 then true
      else if (f & 0x10) != 0 then !gdef.inMarkSet(lk.markSet, g.id)
      else if (f >> 8) != 0 then gdef.markAttachClass(g.id) != (f >> 8)
      else false
    else false

  protected def isGpos: Boolean = false

  /** Default-ignorable glyphs that were never substituted are skipped when they do not match the pattern item.
    * ZWNJ/ZWJ are only ignorable under HarfBuzz's rules: in GPOS always, in GSUB input matching ZWJ only for lookups that
    * do not ask for manual joiners, and in GSUB context matching ZWJ always and ZWNJ unless manual.
    */
  private def maybeSkip(g: G, contextMatch: Boolean): Boolean =
    g.aux == 1 && !g.substituted && {
      val ignoreZwnj = isGpos || (contextMatch && autoZwnj)
      val ignoreZwj = isGpos || contextMatch || autoZwj
      (ignoreZwnj || !g.isZwnj) && (ignoreZwj || !g.isZwj)
    }

  /** Context (backtrack/lookahead) glyphs match regardless of feature masks; input glyphs need the lookup's mask. */
  private def maskOk(g: G, constrain: Boolean, contextMatch: Boolean): Boolean =
    (contextMatch || (g.mask & mask) != 0) && (!constrain || g.syllable == syl)

  /** Next glyph after `pos` accepted by `pred`, skipping glyphs excluded by the lookup flags and ignorables. */
  protected final def nextMatch(pos: Int, constrain: Boolean, contextMatch: Boolean, pred: G => Boolean): Int =
    var j = pos + 1
    while j < buf.size do
      val g = buf(j)
      if skip(g) then j += 1
      else if maskOk(g, constrain, contextMatch) && pred(g) then return j
      else if maybeSkip(g, contextMatch) then j += 1
      else return -1
    -1

  protected final def prevMatch(pos: Int, contextMatch: Boolean, pred: G => Boolean): Int =
    var j = pos - 1
    while j >= 0 do
      val g = buf(j)
      if skip(g) then j -= 1
      else if maskOk(g, false, contextMatch) && pred(g) then return j
      else if maybeSkip(g, contextMatch) then j -= 1
      else return -1
    -1

  /** Next glyph that is not skipped (no pattern to match: ignorables are skipped, a failing mask stops the search). */
  protected final def next(pos: Int): Int =
    var j = pos + 1
    while j < buf.size do
      val g = buf(j)
      if skip(g) || maybeSkip(g, false) then j += 1
      else if maskOk(g, false, false) then return j
      else return -1
    -1
  protected final def prev(pos: Int): Int =
    var j = pos - 1
    while j >= 0 do
      val g = buf(j)
      if skip(g) || maybeSkip(g, false) then j -= 1
      else if maskOk(g, false, false) then return j
      else return -1
    -1

  /** Matches `n` items going forward from the glyph at `start` (item 0 is `start` itself); returns glyph positions or null. */
  protected final def matchForward(start: Int, n: Int, pred: (Int, G) => Boolean): Array[Int] =
    val pos = new Array[Int](n)
    val first = buf(start)
    if (first.mask & mask) == 0 || !pred(0, first) then return null
    pos(0) = start
    syl = first.syllable
    var k = 1
    while k < n do
      val kk = k
      val j = nextMatch(pos(k - 1), perSyllable, false, g => pred(kk, g))
      if j < 0 then return null
      pos(k) = j
      k += 1
    pos

  /** Matches `n` context items going backward, the nearest preceding glyph first, starting before `start`. */
  protected final def matchBackward(start: Int, n: Int, pred: (Int, G) => Boolean): Boolean =
    var j = start
    var k = 0
    while k < n do
      val kk = k
      j = prevMatch(j, true, g => pred(kk, g))
      if j < 0 then return false
      k += 1
    true

  /** Applies a lookup at `pos` as a nested lookup record; returns whether it applied. */
  protected final def nested(index: Int, pos: Int): Boolean =
    if depth >= 6 || index >= t.lookups.length || pos < 0 || pos >= buf.size then false
    else
      val saved = lk
      val l = t.lookups(index)
      lk = l; depth += 1
      val r = applyOne(l, pos)
      depth -= 1; lk = saved
      r >= 0

  /** Runs SequenceLookupRecords over matched positions; returns the index after the (possibly resized) match. */
  protected final def applyRecords(matched: Array[Int], recOff: Int, count: Int): Int =
    val p = matched.clone()
    val size0 = buf.size
    val end = p(p.length - 1)
    var r = 0
    while r < count do
      val seq = u16(d, recOff + r * 4); val li = u16(d, recOff + r * 4 + 2)
      if seq < p.length then
        val q = p(seq)
        val before = buf.size
        nested(li, q)
        val delta = buf.size - before
        if delta != 0 then
          var k = 0
          while k < p.length do { if p(k) > q then p(k) = math.max(q + 1, p(k) + delta); k += 1 }
      r += 1
    math.max(0, end + 1 + (buf.size - size0))

  // ---- contextual lookups (GSUB 5/6 and GPOS 7/8 share formats) ----
  private def coverage(o: Int) = new Coverage(d, o)

  protected final def context(o: Int, i: Int): Int =
    val g = buf(i)
    u16(d, o) match
      case 1 =>
        val ci = coverage(o + u16(d, o + 2)).index(g.id)
        if ci < 0 then return -1
        val setOff = u16(d, o + 6 + ci * 2)
        if setOff == 0 then return -1
        val so = o + setOff
        var r = 0
        while r < u16(d, so) do
          val ro = so + u16(d, so + 2 + r * 2)
          val n = u16(d, ro); val subCount = u16(d, ro + 2)
          val m = matchForward(i, n, (k, gl) => k == 0 || gl.id == u16(d, ro + 4 + (k - 1) * 2))
          if m != null then return applyRecords(m, ro + 4 + (n - 1) * 2, subCount)
          r += 1
        -1
      case 2 =>
        if coverage(o + u16(d, o + 2)).index(g.id) < 0 then return -1
        val cd = new ClassDef(d, o + u16(d, o + 4))
        val cls = cd.classOf(g.id)
        if cls >= u16(d, o + 6) then return -1
        val setOff = u16(d, o + 8 + cls * 2)
        if setOff == 0 then return -1
        val so = o + setOff
        var r = 0
        while r < u16(d, so) do
          val ro = so + u16(d, so + 2 + r * 2)
          val n = u16(d, ro); val subCount = u16(d, ro + 2)
          val m = matchForward(i, n, (k, gl) => k == 0 || cd.classOf(gl.id) == u16(d, ro + 4 + (k - 1) * 2))
          if m != null then return applyRecords(m, ro + 4 + (n - 1) * 2, subCount)
          r += 1
        -1
      case 3 =>
        val n = u16(d, o + 2); val subCount = u16(d, o + 4)
        val covs = Array.tabulate(n)(k => coverage(o + u16(d, o + 6 + k * 2)))
        val m = matchForward(i, n, (k, gl) => covs(k).covers(gl.id))
        if m != null then applyRecords(m, o + 6 + n * 2, subCount) else -1
      case _ => -1

  protected final def chainContext(o: Int, i: Int): Int =
    val g = buf(i)
    u16(d, o) match
      case 1 =>
        val ci = coverage(o + u16(d, o + 2)).index(g.id)
        if ci < 0 then return -1
        val setOff = u16(d, o + 6 + ci * 2)
        if setOff == 0 then return -1
        val so = o + setOff
        var r = 0
        while r < u16(d, so) do
          val ro = so + u16(d, so + 2 + r * 2)
          val bt = u16(d, ro); val inO = ro + 2 + bt * 2
          val n = u16(d, inO); val laO = inO + 2 + (n - 1) * 2
          val la = u16(d, laO); val recO = laO + 2 + la * 2
          val m = matchForward(i, n, (k, gl) => k == 0 || gl.id == u16(d, inO + 2 + (k - 1) * 2))
          if m != null && matchBackward(i, bt, (k, gl) => gl.id == u16(d, ro + 2 + k * 2)) &&
             lookaheadOk(m, la, (k, gl) => gl.id == u16(d, laO + 2 + k * 2)) then
            return applyRecords(m, recO + 2, u16(d, recO))
          r += 1
        -1
      case 2 =>
        if coverage(o + u16(d, o + 2)).index(g.id) < 0 then return -1
        val btCd = new ClassDef(d, defOff(o, 4)); val inCd = new ClassDef(d, defOff(o, 6)); val laCd = new ClassDef(d, defOff(o, 8))
        val cls = inCd.classOf(g.id)
        if cls >= u16(d, o + 10) then return -1
        val setOff = u16(d, o + 12 + cls * 2)
        if setOff == 0 then return -1
        val so = o + setOff
        var r = 0
        while r < u16(d, so) do
          val ro = so + u16(d, so + 2 + r * 2)
          val bt = u16(d, ro); val inO = ro + 2 + bt * 2
          val n = u16(d, inO); val laO = inO + 2 + (n - 1) * 2
          val la = u16(d, laO); val recO = laO + 2 + la * 2
          val m = matchForward(i, n, (k, gl) => k == 0 || inCd.classOf(gl.id) == u16(d, inO + 2 + (k - 1) * 2))
          if m != null && matchBackward(i, bt, (k, gl) => btCd.classOf(gl.id) == u16(d, ro + 2 + k * 2)) &&
             lookaheadOk(m, la, (k, gl) => laCd.classOf(gl.id) == u16(d, laO + 2 + k * 2)) then
            return applyRecords(m, recO + 2, u16(d, recO))
          r += 1
        -1
      case 3 =>
        val bt = u16(d, o + 2)
        val btCov = Array.tabulate(bt)(k => coverage(o + u16(d, o + 4 + k * 2)))
        val inO = o + 4 + bt * 2
        val n = u16(d, inO)
        val inCov = Array.tabulate(n)(k => coverage(o + u16(d, inO + 2 + k * 2)))
        val laO = inO + 2 + n * 2
        val la = u16(d, laO)
        val laCov = Array.tabulate(la)(k => coverage(o + u16(d, laO + 2 + k * 2)))
        val recO = laO + 2 + la * 2
        val m = matchForward(i, n, (k, gl) => inCov(k).covers(gl.id))
        if m != null && matchBackward(i, bt, (k, gl) => btCov(k).covers(gl.id)) &&
           lookaheadOk(m, la, (k, gl) => laCov(k).covers(gl.id)) then applyRecords(m, recO + 2, u16(d, recO))
        else -1
      case _ => -1

  private def defOff(o: Int, field: Int): Int = { val off = u16(d, o + field); if off == 0 then -1 else o + off }

  private def lookaheadOk(m: Array[Int], n: Int, pred: (Int, G) => Boolean): Boolean =
    var j = m(m.length - 1)
    var k = 0
    while k < n do
      val kk = k
      j = nextMatch(j, false, true, g => pred(kk, g))
      if j < 0 then return false
      k += 1
    true

  /** Applies lookup `index` over the whole buffer with feature mask `m`. */
  def run(index: Int, m: Int, autoZwj: Boolean = true, autoZwnj: Boolean = true, perSyllable: Boolean = false): Unit =
    val l = t.lookups(index)
    lk = l; mask = m
    this.autoZwj = autoZwj; this.autoZwnj = autoZwnj; this.perSyllable = perSyllable
    if reverseLookup(l) then
      var i = buf.size - 1
      while i >= 0 do
        if i < buf.size then
          val g = buf(i)
          if (g.mask & m) != 0 && !skip(g) then applyOne(l, i)
        i -= 1
    else
      var i = 0
      while i < buf.size do
        val g = buf(i)
        if (g.mask & m) != 0 && !skip(g) then
          val size0 = buf.size
          val r = applyOne(l, i)
          if r >= 0 && Trace.enabled then Trace.log(s"lookup $index kind=${l.kind} applied at $i -> $r")
          i = if r < 0 then i + 1 else if r <= i && buf.size >= size0 then i + 1 else r
        else i += 1

  protected def reverseLookup(l: Lookup): Boolean = false
