package scalaui.text

import scalaui.font.FontFace
import scalaui.shaping.{FontFeature, Shaper}
import scalaui.unicode.*
import scala.collection.mutable.ArrayBuffer

/** A styled stretch `[start, end)` of the text: which shaper/size to use and extra spacing per glyph. */
private[text] final case class Run(start: Int, end: Int, shaper: Shaper, size: Double, letterSpacing: Double,
    features: Seq[FontFeature] = Nil, language: Option[String] = None)

/** A shaped glyph with its place in the source text: `[cluster, end)` is the UTF-16 range it represents. In horizontal flows
  * `xOff`/`yOff` are HarfBuzz offsets (y up); in vertical flows `adv` runs down the column and `xOff`/`yOff` are screen offsets
  * (y down) of the glyph origin from (column centre, pen); `sideways` glyphs are drawn rotated 90 degrees clockwise.
  */
private[text] final case class Piece(glyph: Int, font: FontFace, adv: Double, xOff: Double, yOff: Double, cluster: Int, end: Int, run: Int, level: Int,
    sideways: Boolean = false)

/** A line in visual order. `lead` is how far the line starts left of the alignment edge (hanging whitespace of an RTL paragraph). */
private[text] final case class FlowLine(start: Int, end: Int, pieces: Vector[Piece], width: Double, lead: Double)

/** The text pipeline: paragraphs -> bidi (UAX #9) -> script/style/level runs -> shaping -> UAX #14 line breaking
  * -> visual reordering per line.
  */
private[text] object Flow:
  def flow(text: String, runs: Vector[Run], limit: Double, direction: Int, vertical: Boolean = false): Vector[FlowLine] =
    val out = ArrayBuffer.empty[FlowLine]
    var paraStart = 0
    while paraStart <= text.length do
      val nl = text.indexOf('\n', paraStart)
      val paraEnd = if nl < 0 then text.length else nl
      out ++= paragraph(text, paraStart, paraEnd, runs, limit, direction, vertical)
      if nl >= 0 then out(out.size - 1) = out.last.copy(end = nl + 1) // the hard break belongs to the last line
      paraStart = if nl < 0 then text.length + 1 else nl + 1
    if out.isEmpty then out += FlowLine(0, 0, Vector.empty, 0.0, 0.0)
    out.toVector

  private def paragraph(text: String, ps: Int, pe: Int, runs: Vector[Run], limit: Double, direction: Int, vertical: Boolean): Vector[FlowLine] =
    if ps == pe then return Vector(FlowLine(ps, ps, Vector.empty, 0.0, 0.0))
    val ptext = text.substring(ps, pe)
    val cp = CodePoints(ptext)
    val n = cp.size
    val bidi = Bidi.resolve(cp.cps, if vertical then Bidi.Ltr else direction)
    val opp = LineBreaks.opportunities(cp)
    val gbound = Graphemes.boundaries(cp)
    val cpAt = new Array[Int](ptext.length + 1)       // UTF-16 offset in the paragraph -> code point index
    var k = 0
    while k < n do
      val o = cp.offset(k)
      cpAt(o) = k
      if k + 1 < n && cp.offset(k + 1) - o == 2 then cpAt(o + 1) = k
      k += 1
    cpAt(ptext.length) = n

    // --- shaping runs: split where style, bidi level or script changes
    val runOf = new Array[Int](n)
    var ri = 0
    k = 0
    while k < n do
      val off = ps + cp.offset(k)
      while ri + 1 < runs.size && off >= runs(ri).end do ri += 1
      runOf(k) = ri
      k += 1
    val scriptOf = new Array[Int](n)
    Scripts.itemize(cp.cps).zipWithIndex.foreach((r, id) => (r.start until r.end).foreach(scriptOf(_) = id))

    // vertical flows (UAX #50): each grapheme cluster is upright (shaped with vertical metrics) or sideways (shaped
    // horizontally, then rotated); the orientation of `Tr` characters depends on whether the font has a vertical form
    val upright = new Array[Boolean](n)
    if vertical then
      val gb = gbound
      val formCache = scala.collection.mutable.HashMap.empty[(Int, Int), Boolean]
      var cur = false
      k = 0
      while k < n do
        if gb(k) then cur = VerticalText.isUpright(cp.cps(k), c => formCache.getOrElseUpdate((runOf(k), c), hasVerticalForm(runs(runOf(k)), c)))
        upright(k) = cur
        k += 1

    val pieces = ArrayBuffer.empty[Piece]
    k = 0
    while k < n do
      var e = k + 1
      while e < n && bidi.levels(e) == bidi.levels(k) && runOf(e) == runOf(k) && scriptOf(e) == scriptOf(k) && (!vertical || upright(e) == upright(k)) do e += 1
      val startOff = cp.offset(k); val endOff = cp.offset(e)
      val r = runs(runOf(k)); val level = bidi.levels(k)
      val sub = ptext.substring(startOff, endOff)
      if !vertical then
        r.shaper.shape(sub, r.size, (level & 1) == 1, r.features, r.language).foreach { g =>
          pieces += Piece(g.glyph, g.font, g.xAdvance + r.letterSpacing, g.xOffset, g.yOffset, ps + startOff + g.cluster, pe, runOf(k), level)
        }
      else if upright(k) then
        r.shaper.shape(sub, r.size, false, r.features, r.language, vertical = true).foreach { g =>
          pieces += Piece(g.glyph, g.font, -g.yAdvance + r.letterSpacing, g.xOffset, -g.yOffset, ps + startOff + g.cluster, pe, runOf(k), level)
        }
      else
        // rotated 90 degrees clockwise: the em box (ascent above, descent below the baseline) is centred on the column axis
        val f = r.shaper.font; val sc = r.size / f.unitsPerEm
        val baselineShift = -(f.layoutAscender + f.layoutDescender) * sc / 2 // screen x of the baseline relative to the axis
        r.shaper.shape(sub, r.size, (level & 1) == 1, r.features, r.language).foreach { g =>
          pieces += Piece(g.glyph, g.font, g.xAdvance + r.letterSpacing, baselineShift + g.yOffset, g.xOffset, ps + startOff + g.cluster, pe, runOf(k), level, sideways = true)
        }
      k = e
    // each piece ends where the next one starts (invisible characters fold into the preceding piece)
    var i = pieces.size - 2
    while i >= 0 do // several glyphs of one cluster (marks, decompositions) share the range up to the next cluster
      val nxt = pieces(i + 1)
      pieces(i) = pieces(i).copy(end = if nxt.cluster > pieces(i).cluster then nxt.cluster else nxt.end)
      i -= 1
    val m = pieces.size
    if m == 0 then return Vector(FlowLine(ps, pe, Vector.empty, 0.0, 0.0))

    def isHang(p: Piece) = Character.isWhitespace(text.codePointAt(p.cluster))
    def cpIdx(p: Piece) = cpAt(p.cluster - ps)

    // --- line breaking over logical order
    val lines = Vector.newBuilder[FlowLine]
    var ls = 0
    while ls < m do
      var width = 0.0; var j = ls; var lastBreak = -1; var forced = -1; var done = false
      while j < m && !done do
        val p = pieces(j)
        if j > ls then
          val o = opp(cpIdx(p))
          if o == LineBreaks.Mandatory then { forced = j; done = true }
          else if o == LineBreaks.Allowed then lastBreak = j
        if !done then
          if !isHang(p) && width + p.adv > limit && j > ls then done = true
          else { width += p.adv; j += 1 }
      val breakAt =
        if forced >= 0 then forced
        else if j >= m then m
        else if lastBreak > ls then lastBreak
        else
          var q = j
          while q > ls && !gbound(cpIdx(pieces(q))) do q -= 1 // never split a grapheme cluster
          if q > ls then q else j
      lines += finishLine(ps, pe, pieces, ls, breakAt, bidi, n, cpAt, isHang)
      ls = breakAt
    lines.result()

  /** Whether the font substitutes a vertical glyph (`vert`) for `cp`, so a `Tr` character can stay upright. */
  private def hasVerticalForm(r: Run, cp: Int): Boolean =
    val gs = r.shaper.shape(new String(Character.toChars(cp)), r.size, false, r.features, r.language, vertical = true)
    gs.headOption.exists(g => g.glyph != 0 && g.glyph != g.font.glyphIndex(cp))

  private def finishLine(ps: Int, pe: Int, pieces: ArrayBuffer[Piece], from: Int, to: Int, bidi: Bidi.Result,
      n: Int, cpAt: Array[Int], isHang: Piece => Boolean): FlowLine =
    val start = if from == 0 then ps else pieces(from).cluster
    val end = if to < pieces.size then pieces(to).cluster else pe
    val cpStart = cpAt(pieces(from).cluster - ps)
    val cpEnd = if to < pieces.size then cpAt(pieces(to).cluster - ps) else n
    val ll = bidi.lineLevels(cpStart, cpEnd)
    val logical = Vector.tabulate(to - from)(i => pieces(from + i).copy(level = ll(cpAt(pieces(from + i).cluster - ps) - cpStart)))
    val order = Bidi.reorder(logical.map(_.level).toArray)
    val visual = order.iterator.map(logical(_)).toVector
    // width without the hanging whitespace at the logical end of the line
    var hangFrom = logical.size
    while hangFrom > 0 && isHang(logical(hangFrom - 1)) do hangFrom -= 1
    val visible = logical.iterator.take(hangFrom).map(_.adv).sum
    // in an RTL paragraph the hanging block sits visually first; start the line left of the edge by its width
    val hangW = logical.iterator.drop(hangFrom).map(_.adv).sum
    val lead = if (bidi.paragraphLevel & 1) == 1 then -hangW else 0.0
    FlowLine(start, end, visual, visible, lead)
