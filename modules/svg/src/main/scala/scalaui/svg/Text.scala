package scalaui.svg

import scalaui.font.{FontFace, FontFamily, SystemFonts}
import scalaui.geom.*
import scalaui.raster.{Path, PathEl}
import scalaui.shaping.Shaper
import scala.collection.mutable

/** A piece of laid-out text as a user-space outline painted with `style`. */
private[svg] final case class TextItem(path: Path, style: Style, colors: Vector[ColorGlyphDraw] = Vector.empty)

/** A colour glyph (COLR, sbix/CBDT or `SVG ` table) to paint instead of an outline: `matrix` maps font units (y up) to user space. */
private[svg] final case class ColorGlyphDraw(face: FontFace, gid: Int, matrix: Affine, style: Style)

/** SVG text layout: whitespace handling, per-character `x/y/dx/dy/rotate`, text chunks with `text-anchor`, `tspan`, `textPath`,
  * decorations and baseline selection. Glyphs are shaped with the OpenType engine and emitted as vector outlines.
  */
private[svg] object TextEngine:
  private type Decos = List[(Set[String], Style)]

  private final class Chars:
    val cp = mutable.ArrayBuffer.empty[Int]               // UTF-16 code units
    val style = mutable.ArrayBuffer.empty[Style]
    val elem = mutable.ArrayBuffer.empty[XmlElement]       // element that directly contains the character
    val path = mutable.ArrayBuffer.empty[Option[(XmlElement, XmlElement)]] // (textPath, referenced path element)
    val decos = mutable.ArrayBuffer.empty[Decos]           // text-decoration declared by this element and its ancestors, each with its own paint
    var x: Array[Option[Double]] = null; var y: Array[Option[Double]] = null
    var dx: Array[Option[Double]] = null; var dy: Array[Option[Double]] = null
    var rot: Array[Option[Double]] = null
    val ranges = mutable.ArrayBuffer.empty[(XmlElement, Style, Int, Array[Int])] // element, style, start, end (single-slot array filled on exit)
    def size: Int = cp.length
    def dropLast(): Unit = { val k = size - 1; cp.remove(k); style.remove(k); elem.remove(k); path.remove(k); decos.remove(k) }

  /** `x`/`y` are the glyph origin in the layout frame: `x` runs along the text (down the column in vertical text), `y` is the baseline offset
    * (vertical text is laid out in a frame turned 90 degrees clockwise). `orient` is 0 for horizontal text, 1 for sideways glyphs of vertical text
    * (drawn rotated 90 degrees clockwise) and 2 for upright ones, whose origin lies `(ox, oy)` (visual units, y down) from the pivot `(x, y)` rotation
    * and `rotate` act around; `axisV` is the frame position of the column axis.
    */
  private final class Glyph(val face: FontFace, val gid: Int, var x: Double, var y: Double, val rot: Double, val size: Double, val adv: Double, val style: Style, val decos: Decos, val xs: Double,
      val orient: Int = 0, val ox: Double = 0.0, val oy: Double = 0.0, val axisV: Double = 0.0)
  private final class Chunk(val anchor: String, var startX: Double, val onPath: Option[(Vector[Point], Vector[Double], Double)], var width: Double = 0.0, val glyphs: mutable.ArrayBuffer[Glyph] = mutable.ArrayBuffer.empty,
      var lo: Double = Double.PositiveInfinity, var hi: Double = Double.NegativeInfinity, var firstX: Double = 0.0)

  def layout(r: Renderer, text: XmlElement, ctx: Ctx, colorGlyphs: Boolean = false): Vector[TextItem] =
    val chars = new Chars
    val rootStyle = ctx.style // `ctx.style` already includes the text element's own properties
    // writing-mode is read from the text element (or an ancestor); on a tspan it has no effect. rl/lr/tb-rl variants other than the vertical ones are horizontal.
    val vertical = Set("tb", "tb-rl", "vertical-rl", "vertical-lr").contains(rootStyle.writingMode.trim)
    val rootDecos: Decos = decoOf(text, rootStyle, Nil)
    flatten(r, text, rootStyle, ctx, chars, preserve = text.attrs.get("xml:space").contains("preserve"), None, rootDecos)
    // trailing collapsed space
    val preserveRoot = text.attrs.get("xml:space").contains("preserve")
    if !preserveRoot && chars.size > 0 && chars.cp.last == ' ' then chars.dropLast()
    val n = chars.size
    if n == 0 then return Vector.empty
    chars.x = Array.fill(n)(None); chars.y = Array.fill(n)(None); chars.dx = Array.fill(n)(None); chars.dy = Array.fill(n)(None); chars.rot = Array.fill(n)(None)
    applyLists(r, text, ctx, chars)

    /** One layout pass. `extra(i)` is added to the advance after character i, `gscale(i)` stretches its glyphs horizontally; `nat(i)` receives the natural advance. */
    def run(extra: Array[Double], gscale: Array[Double], nat: Array[Double]): mutable.ArrayBuffer[Chunk] =
      val chunks = mutable.ArrayBuffer.empty[Chunk]
      var curX = 0.0; var curY = 0.0
      var chunk: Chunk = null
      var curPath: Option[(XmlElement, XmlElement)] = None
      def startChunk(style: Style, onPath: Option[(Vector[Point], Vector[Double], Double)], at: Double): Unit =
        if chunk != null then chunk.width = curX - chunk.startX
        chunk = new Chunk(style.textAnchor, at, onPath); chunks += chunk
      // runs of identical (style, path); every character with an absolute x/y starts a new text chunk and is shaped on its own (SVG text chunks)
      var i = 0
      while i < n do
        var j = i + 1
        while j < n && (chars.style(j) eq chars.style(i)) && chars.path(j) == chars.path(i) && (chars.path(i).isDefined || !(chars.x(j).isDefined || chars.y(j).isDefined)) do j += 1
        val st = chars.style(i)
        val str = new String(chars.cp.slice(i, j).map(_.toChar).toArray)
        val faces = fontsFor(r, st)
        val shaper = shaperFor(r, faces)
        val pathInfo = chars.path(i).flatMap(p => pathPolyline(r, p._2, ctx))
        val shaped = shapeRun(str, st, shaper, faces, vertical, linear = pathInfo.isEmpty)
        val pkey = chars.path(i)
        if pathInfo.isDefined && (chunk == null || curPath != pkey) then
          val tp = pkey.get._1
          val total = pathInfo.get._3
          val off = tp.attrs.get("startOffset").flatMap(Values.parseLen).map(l => if l.unit == "%" then l.v / 100 * total else Style.length(l, st.fontSize, ctx.vw, ctx.vh, 'x')).getOrElse(0.0)
          curX = off; curY = 0
          startChunk(st, pathInfo, off)
        else if pathInfo.isEmpty && (chunk == null || curPath.isDefined) then startChunk(st, None, curX)
        curPath = pkey
        var gi = 0
        var lastCluster = -1
        while gi < shaped.length do
          val g = shaped(gi)
          val idx = i + g.cluster
          val first = g.cluster != lastCluster && idx < n
          if first then
            lastCluster = g.cluster
            if pathInfo.isEmpty then
              // vertical text is laid out in a frame turned 90 degrees clockwise: `y` runs along the text, `x` (negated) is the baseline offset
              val nx = if vertical then chars.y(idx) else chars.x(idx)
              val ny = if vertical then chars.x(idx).map(-_) else chars.y(idx)
              if nx.isDefined || ny.isDefined then
                if chunk.glyphs.nonEmpty || chunk.width != 0 || curX != chunk.startX then startChunk(st, None, nx.getOrElse(curX))
                curX = nx.getOrElse(curX); curY = ny.getOrElse(curY)
                chunk.startX = curX
            if vertical then { chars.dy(idx).foreach(d => curX += d); chars.dx(idx).foreach(d => curY -= d) }
            else { chars.dx(idx).foreach(d => curX += d); chars.dy(idx).foreach(d => curY += d) }
          val face = if g.font != null then g.font else faces.head
          val chR = if idx < n then chars.rot(idx).getOrElse(0.0) else 0.0
          val shift = baselineOffset(r, face, st, text, chars.elem(math.min(idx, n - 1)), vertical)
          val xs = if idx < n then gscale(idx) else 1.0
          val isSpace = idx < n && chars.cp(idx) == ' '
          val adv = (g.xAdvance + st.letterSpacing + (if isSpace then st.wordSpacing else 0.0)) * xs
          val dec = chars.decos(math.min(idx, n - 1))
          val gl = g.orient match
            case 0 => new Glyph(face, g.glyph, curX + g.xOffset * xs, curY - g.yOffset - shift, chR, st.fontSize, g.xAdvance * xs, st, dec, xs)
            case 1 => // sideways glyph of vertical text: the em box is centred on the axis, i.e. the baseline sits (ascent + descent) / 2 below it
              val k = centreShift(face, st.fontSize)
              new Glyph(face, g.glyph, curX + g.xOffset * xs, curY + k - g.yOffset - shift, chR, st.fontSize, g.xAdvance * xs, st, dec, xs, 1, 0, 0, curY - shift)
            case 3 => new Glyph(face, g.glyph, curX + g.xOffset * xs, curY + centreShift(face, st.fontSize) - g.yOffset - shift, chR, st.fontSize, g.xAdvance * xs, st, dec, xs, 3, 0, 0, curY - shift)
            case 4 => new Glyph(face, g.glyph, curX + g.xOffset * xs, curY - g.yOffset - shift, chR, st.fontSize, g.xAdvance * xs, st, dec, xs, 4, 0, 0, curY - shift)
            case _ => new Glyph(face, g.glyph, curX, curY - shift, chR, st.fontSize, g.xAdvance * xs, st, dec, xs, 2, g.ox, g.oy, curY - shift)
          if chunk.glyphs.isEmpty then chunk.firstX = curX
          chunk.glyphs += gl
          // extent of the glyph cells (relative shifts included): text-anchor aligns this range, not the plain advance sum
          chunk.lo = math.min(chunk.lo, math.min(curX, curX + adv)); chunk.hi = math.max(chunk.hi, math.max(curX, curX + adv))
          if idx < n then nat(idx) += g.xAdvance + st.letterSpacing + (if isSpace then st.wordSpacing else 0.0)
          curX += adv + (if first && idx < n then extra(idx) else 0.0)
          gi += 1
        i = j
      if chunk != null then chunk.width = curX - chunk.startX
      chunks

    // textLength / lengthAdjust: measure naturally, then redistribute (children before parents)
    val extra = new Array[Double](n); val gscale = Array.fill(n)(1.0)
    val adjust = chars.ranges.filter((e, _, s, end) => e.attrs.get("textLength").flatMap(Values.parseNumber).exists(_ > 0) && math.min(end(0), n) > s)
    var chunks = run(extra, gscale, new Array[Double](n))
    if adjust.nonEmpty then
      val nat = new Array[Double](n)
      run(extra, gscale, nat)
      adjust.reverse.foreach { (e, st, s, endSlot) =>
        val end = math.min(endSlot(0), n)
        val target = Values.parseNumber(e.attrs("textLength")).get
        val spacingAndGlyphs = e.attrs.get("lengthAdjust").contains("spacingAndGlyphs")
        def measured: Double = (s until end).map(k => nat(k) * gscale(k) + extra(k)).sum
        val m = measured
        if spacingAndGlyphs then
          if m > 0 then { val f = target / m; for k <- s until end do { gscale(k) *= f; extra(k) *= f } }
        else
          val clusters = (s until end).count(k => nat(k) > 0)
          if clusters > 1 then
            val per = (target - m) / (clusters - 1)
            var seen = 0
            for k <- s until end if nat(k) > 0 do { seen += 1; if seen < clusters then extra(k) += per }
        val _ = st
      }
      chunks = run(extra, gscale, new Array[Double](n))

    // ---- emit paths
    val items = Vector.newBuilder[TextItem]
    chunks.foreach { ck =>
      val factor = ck.anchor match { case "middle" => 0.5; case "end" => 1.0; case _ => 0.0 }
      val shiftX = if ck.onPath.isEmpty && ck.lo <= ck.hi then ck.firstX - ck.lo - (ck.hi - ck.lo) * factor else -ck.width * factor
      // group consecutive glyphs by style and decoration set
      var gi = 0
      while gi < ck.glyphs.size do
        var gj = gi + 1
        while gj < ck.glyphs.size && (ck.glyphs(gj).style eq ck.glyphs(gi).style) && (ck.glyphs(gj).decos eq ck.glyphs(gi).decos) do gj += 1
        val st = ck.glyphs(gi).style
        val pb = Path.builder
        val colors = Vector.newBuilder[ColorGlyphDraw]
        var minX = Double.MaxValue; var maxX = Double.MinValue; var decoY = 0.0
        var minU = Double.MaxValue; var maxU = Double.MinValue; var axisV = 0.0; var verticalRun = false
        for k <- gi until gj do
          val g = ck.glyphs(k)
          val scale = g.size / g.face.unitsPerEm
          val place: Option[Affine] = ck.onPath match
            case None if g.orient == 1 => // vertical text: frame (u, v) -> visual (-v, u); the glyph lies on its side
              Some(Affine.translate(Vec2(-g.y, g.x + shiftX)) * Affine.rotate(math.toRadians(g.rot)) * Affine.rotate(math.Pi / 2))
            case None if g.orient == 2 => // upright: rotation (the `rotate` attribute) acts around the pivot on the column axis
              Some(Affine.translate(Vec2(-g.y, g.x + shiftX)) * Affine.rotate(math.toRadians(g.rot)) * Affine.translate(Vec2(g.ox, g.oy)))
            case None => Some(Affine.translate(Vec2(g.x + shiftX, g.y)) * Affine.rotate(math.toRadians(g.rot)))
            case Some((pts, cum, total)) =>
              val mid = g.x + shiftX + g.adv / 2
              if mid < 0 || mid > total then None
              else
                val (p, ang) = pointAt(pts, cum, mid)
                Some(Affine.translate(Vec2(p.x, p.y)) * Affine.rotate(ang + math.toRadians(g.rot)) * Affine.translate(Vec2(-g.adv / 2, g.y)))
          place.foreach { pl =>
            // upright glyphs on a path are turned a quarter anticlockwise about the middle of their em box (as resvg does)
            val up = if g.orient == 4 then
              val kc = centreShift(g.face, g.size); val c = Vec2(g.adv / 2, -kc)
              Affine.translate(Vec2(0, kc)) * Affine.translate(c) * Affine.rotate(-math.Pi / 2) * Affine.translate(Vec2(-c.x, -c.y))
            else Affine.Identity
            val m = pl * up * Affine.scale(scale * g.xs, -scale)
            if colorGlyphs && g.face.isColorGlyph(g.gid) then colors += ColorGlyphDraw(g.face, g.gid, m, st)
            else appendTransformed(pb, g.face.outline(g.gid), m)
            if (g.orient == 1 || g.orient == 2) && ck.onPath.isEmpty then
              verticalRun = true; minU = math.min(minU, g.x + shiftX); maxU = math.max(maxU, g.x + shiftX + g.adv); axisV = g.axisV
            else
              val sx = pl(Point(0, 0)).x; minX = math.min(minX, sx); maxX = math.max(maxX, pl(Point(g.adv, 0)).x); decoY = pl(Point(0, 0)).y
          }
        val glyphPath = pb.build
        val colorList = colors.result()
        if glyphPath.elements.nonEmpty || colorList.nonEmpty then items += TextItem(glyphPath, st, colorList)
        // decorations: one line per declaring element, painted with that element's fill and stroke
        if verticalRun && minU <= maxU then
          // vertical text: lines run along the column, underline to the left of the glyphs, overline to the right (offsets as in resvg)
          val face = ck.glyphs(gi).face; val u = st.fontSize / face.unitsPerEm
          val half = (face.ascent - face.descent) * u / 2
          def vRect(vc: Double, th: Double) = Path.rect(Rect(minU, axisV + vc - th / 2, maxU, axisV + vc + th / 2)).transform(Affine.rotate(math.Pi / 2))
          val (_, uth) = underline(face)
          ck.glyphs(gi).decos.foreach { (kinds, dst) =>
            if kinds.contains("underline") then items += TextItem(vRect(half, uth * u), dst)
            if kinds.contains("overline") then items += TextItem(vRect(-half, uth * u), dst)
            if kinds.contains("line-through") then { val (_, sth) = strike(face); items += TextItem(vRect(0, sth * u), dst) }
          }
        else if minX <= maxX then
          val face = ck.glyphs(gi).face; val size = st.fontSize; val u = size / face.unitsPerEm
          def rectPath(yTop: Double, th: Double) = Path.rect(Rect(minX, decoY + yTop, maxX, decoY + yTop + th))
          val (upos, uth) = underline(face)
          ck.glyphs(gi).decos.foreach { (kinds, dst) =>
            if kinds.contains("underline") then items += TextItem(rectPath(-upos * u, uth * u), dst)
            if kinds.contains("overline") then items += TextItem(rectPath(-face.ascent * u, uth * u), dst)
            if kinds.contains("line-through") then { val (spos, sth) = strike(face); items += TextItem(rectPath(-(spos + sth / 2) * u, sth * u), dst) }
          }
        gi = gj
      end while
    }
    items.result()

  /** A shaped glyph in layout terms: `orient` 0 horizontal, 1 sideways and 2 upright in vertical text, 3 and 4 the same on a text path (see [[Glyph]]).
    * For upright glyphs `xAdvance` is the vertical advance and `(ox, oy)` the offset of the glyph origin from its pivot (screen units, y down).
    */
  private final case class SG(cluster: Int, font: FontFace, glyph: Int, xAdvance: Double, xOffset: Double, yOffset: Double, orient: Int = 0, ox: Double = 0.0, oy: Double = 0.0)

  /** Baseline shift (user units, positive = down in the layout frame) that centres the em box on the axis of vertical text. */
  private def centreShift(face: FontFace, size: Double): Double = (face.layoutAscender + face.layoutDescender) * size / face.unitsPerEm / 2.0

  /** Vertical writing: UAX #50 decides per character whether it stands upright (shaped with vertical metrics) or lies sideways (shaped horizontally,
    * drawn rotated 90 degrees clockwise). `text-orientation` overrides it (`upright`, `sideways`); the legacy `glyph-orientation-vertical` maps 0 to upright and 90 to sideways.
    */
  private def orientationMode(st: Style): String =
    st.textOrientation match
      case "upright" => "upright"
      case "sideways" | "sideways-right" => "sideways"
      case _ => st.glyphOrientationVertical match { case "0" => "upright"; case "90" => "sideways"; case _ => "mixed" }

  private def shapeRun(str: String, st: Style, shaper: Shaper, faces: Vector[FontFace], vertical: Boolean, linear: Boolean): Vector[SG] =
    def horizontal(sub: String, off: Int, orient: Int): Vector[SG] =
      shaper.shape(sub, st.fontSize, rtl = st.direction == "rtl").map(g => SG(off + g.cluster, g.font, g.glyph, g.xAdvance, g.xOffset, g.yOffset, orient))
    if !vertical then return horizontal(str, 0, 0)
    val mode = orientationMode(st)
    val formCache = mutable.HashMap.empty[Int, Boolean]
    def hasVerticalForm(cp: Int): Boolean = formCache.getOrElseUpdate(cp,
      shaper.shape(new String(Character.toChars(cp)), st.fontSize, false, Nil, None, vertical = true).headOption.exists { g =>
        g.glyph != 0 && g.glyph != (if g.font != null then g.font else faces.head).glyphIndex(cp) })
    def upright(cp: Int): Boolean = mode match
      case "upright" => true
      case "sideways" => false
      case _ => scalaui.unicode.VerticalText.isUpright(cp, hasVerticalForm)
    def continuesCluster(cp: Int): Boolean =
      val t = Character.getType(cp)
      t == Character.NON_SPACING_MARK || t == Character.ENCLOSING_MARK || t == Character.COMBINING_SPACING_MARK || cp == 0x200D || (cp >= 0xFE00 && cp <= 0xFE0F)
    if !linear then
      // text on a path keeps the horizontal layout (as resvg does): each cluster is only marked upright or sideways
      return shaper.shape(str, st.fontSize, rtl = st.direction == "rtl").map { g =>
        val cp = if g.cluster < str.length then str.codePointAt(g.cluster) else ' '.toInt
        SG(g.cluster, g.font, g.glyph, g.xAdvance, g.xOffset, g.yOffset, if upright(cp) then 4 else 3)
      }
    // orientation runs over code points
    val out = Vector.newBuilder[SG]
    var k = 0; var cur = false; var first = true
    var segStart = 0; var segUp = false
    def flush(end: Int): Unit =
      if end > segStart then
        val sub = str.substring(segStart, end)
        if segUp then
          out ++= shaper.shape(sub, st.fontSize, false, Nil, None, vertical = true).map(g => SG(segStart + g.cluster, g.font, g.glyph, -g.yAdvance, 0.0, 0.0, 2, g.xOffset, -g.yOffset))
        else out ++= horizontal(sub, segStart, 1)
    while k < str.length do
      val cp = str.codePointAt(k)
      if first || !continuesCluster(cp) then cur = upright(cp)
      if first then { segUp = cur; first = false }
      else if cur != segUp then { flush(k); segStart = k; segUp = cur }
      k += Character.charCount(cp)
    flush(str.length)
    out.result()

  private def decoOf(e: XmlElement, st: Style, parent: Decos): Decos =
    e.attrs.get("text-decoration").map(_.trim) match
      case Some(v) if v.nonEmpty && v != "none" && v != "inherit" =>
        val kinds = v.split("\\s+").filter(Set("underline", "overline", "line-through").contains).toSet
        if kinds.isEmpty then parent else parent :+ ((kinds, st))
      case _ => parent

  private def appendTransformed(pb: Path.PathBuilder, p: Path, m: Affine): Unit =
    p.elements.foreach {
      case PathEl.MoveTo(a) => val q = m(a); pb.moveTo(q.x, q.y)
      case PathEl.LineTo(a) => val q = m(a); pb.lineTo(q.x, q.y)
      case PathEl.QuadTo(c, a) => val qc = m(c); val q = m(a); pb.quadTo(qc.x, qc.y, q.x, q.y)
      case PathEl.CubicTo(c1, c2, a) => val q1 = m(c1); val q2 = m(c2); val q = m(a); pb.cubicTo(q1.x, q1.y, q2.x, q2.y, q.x, q.y)
      case PathEl.Close => pb.close()
    }

  private def i16(d: Array[Byte], o: Int): Int = (((d(o) & 0xff) << 8) | (d(o + 1) & 0xff)).toShort
  private def underline(f: FontFace): (Double, Double) =
    f.rawTable("post").map { (d, o) => (i16(d, o + 8).toDouble, i16(d, o + 10).toDouble) }.filter(_._2 > 0).getOrElse((-f.unitsPerEm * 0.1, f.unitsPerEm * 0.05))
  private def strike(f: FontFace): (Double, Double) =
    f.rawTable("OS/2").map { (d, o) => (i16(d, o + 28).toDouble, i16(d, o + 26).toDouble) }.filter(_._2 > 0).getOrElse((f.unitsPerEm * 0.3, f.unitsPerEm * 0.05))
  private def xHeight(f: FontFace): Double =
    f.rawTable("OS/2").filter((d, o) => d.length > o + 88 && ((d(o) & 0xff) << 8 | (d(o + 1) & 0xff)) >= 2).map((d, o) => i16(d, o + 86).toDouble).filter(_ > 0).getOrElse(f.unitsPerEm * 0.5)

  /** Baseline raise (user units, positive = up) from `dominant-baseline` and `baseline-shift`. */
  private def baselineOffset(r: Renderer, face: FontFace, st: Style, text: XmlElement, owner: XmlElement, vertical: Boolean = false): Double =
    val u = st.fontSize / face.unitsPerEm
    val dom = if vertical then 0.0 else st.dominantBaseline match
      case "central" => -(face.ascent + face.descent) / 2.0 * u
      case "middle" => -xHeight(face) / 2 * u
      case "hanging" => -face.ascent * 0.8 * u
      case "text-before-edge" | "text-top" => -face.ascent * u
      case "text-after-edge" | "text-bottom" | "ideographic" => -face.descent * u
      case "mathematical" => -face.ascent * 0.5 * u
      case _ => 0.0
    // baseline-shift is not inherited, but applies to the element and its descendants' glyphs in practice; read the owning chain up to the text element
    val bs = owner.attrs.get("baseline-shift").map(_.trim).getOrElse("baseline") match
      case "super" => st.fontSize * 0.4
      case "sub" => -st.fontSize * 0.2
      case "baseline" => 0.0
      case v => Values.parseLen(v).map(l => if l.unit == "%" then l.v / 100 * st.fontSize else Style.length(l, st.fontSize, 100, 100, 'd')).getOrElse(0.0)
    dom + bs

  // ---------------------------------------------------------------- flatten the element tree into characters
  private def textContent(e: XmlElement): String = e.nodes.map { case XmlText(t) => t; case k: XmlElement => textContent(k) }.mkString

  private def flatten(r: Renderer, e: XmlElement, st: Style, ctx: Ctx, out: Chars, preserve: Boolean, path: Option[(XmlElement, XmlElement)], decos: Decos): Unit =
    val endSlot = Array(0)
    out.ranges += ((e, st, out.size, endSlot))
    e.nodes.foreach {
      case XmlText(t) =>
        val s = t.map(c => if c == '\n' || c == '\r' || c == '\t' then ' ' else c)
        s.foreach { c =>
          val skip = !preserve && c == ' ' && (out.size == 0 || out.cp.last == ' ')
          if !skip then { out.cp += c.toInt; out.style += st; out.elem += e; out.path += path; out.decos += decos }
        }
      case k: XmlElement =>
        k.name match
          case "tspan" | "a" | "textPath" | "tref" =>
            if !k.attrs.get("display").exists(_.trim == "none") && conditions(k) then
              val ks = Style.derive(k.attrs, st, ctx.vw, ctx.vh)
              val pr = k.attrs.get("xml:space").map(_ == "preserve").getOrElse(preserve)
              val kp = if k.name == "textPath" then r.hrefTarget(k).filter(t => Set("path", "rect", "circle", "ellipse", "line", "polyline", "polygon").contains(t.name)).map(t => (k, t)) else path
              val kd = decoOf(k, ks, decos)
              if k.name == "tref" then
                r.hrefTarget(k).foreach { t =>
                  val fake = new XmlElement("tspan", k.attrs, Vector(XmlText(textContent(t))))
                  flatten(r, fake, ks, ctx, out, pr, kp, kd)
                }
              else if !(k.name == "textPath" && kp.isEmpty) then flatten(r, k, ks, ctx, out, pr, kp, kd)
          case _ => ()
    }
    endSlot(0) = out.size

  private def conditions(k: XmlElement): Boolean = k.attrs.get("systemLanguage").forall(_.split(',').map(_.trim).exists(l => l == "en" || l.startsWith("en-")))

  /** Assigns per-character x/y/dx/dy/rotate; ranges are in pre-order so children override their parents. */
  private def applyLists(r: Renderer, text: XmlElement, ctx: Ctx, c: Chars): Unit =
    def lens(e: XmlElement, k: String, st: Style, axis: Char): Array[Double] =
      e.attrs.get(k).map(_.trim.split("[\\s,]+").filter(_.nonEmpty).flatMap(Values.parseLen).map(l => Style.length(l, st.fontSize, ctx.vw, ctx.vh, axis))).getOrElse(Array.empty[Double])
    c.ranges.foreach { (e, st, start, endSlot) =>
      val end = math.min(endSlot(0), c.size)
      // addressable characters are code points: a surrogate pair takes one list entry and is addressed through its first code unit
      val addr = (start until end).filter(u => !Character.isLowSurrogate(c.cp(u).toChar))
      def put(arr: Array[Option[Double]], vals: Array[Double]): Unit = for j <- vals.indices if j < addr.size do arr(addr(j)) = Some(vals(j))
      put(c.x, lens(e, "x", st, 'x')); put(c.y, lens(e, "y", st, 'y')); put(c.dx, lens(e, "dx", st, 'x')); put(c.dy, lens(e, "dy", st, 'y'))
      val rots = e.attrs.get("rotate").map(Values.numbers).getOrElse(Array.empty[Double])
      for j <- rots.indices if j < addr.size do c.rot(addr(j)) = Some(rots(j))
      if rots.nonEmpty then for k <- rots.length until addr.size do c.rot(addr(k)) = Some(rots.last)
    }

  // ---------------------------------------------------------------- fonts
  private def fontsFor(r: Renderer, st: Style): Vector[FontFace] =
    val key = st.fontFamily.mkString("|") + "#" + (st.fontWeight >= 600) + "#" + (st.fontStyle != "normal")
    r.doc.fontCache.getOrElseUpdate("faces:" + key, {
      val bold = st.fontWeight >= 600; val italic = st.fontStyle != "normal"
      val opts = r.doc.options
      def named(n: String): Option[FontFamily] =
        opts.fonts.find(_.name.equalsIgnoreCase(n))
          .orElse(Seq(FontFamily.NotoSans, FontFamily.Hack, FontFamily.Inter, FontFamily.JetBrainsMono).find(_.name.equalsIgnoreCase(n)))
          .orElse(if opts.systemFonts then SystemFonts.family(n) else None)
      val default = named(opts.defaultFont).getOrElse(FontFamily.NotoSans)
      val out = mutable.ArrayBuffer.empty[FontFace]
      st.fontFamily.foreach { n =>
        val fam = n.toLowerCase match
          case "sans-serif" | "system-ui" | "ui-sans-serif" | "-apple-system" | "default" => Some(default)
          case "monospace" | "ui-monospace" => Some(FontFamily.Hack)
          case "serif" | "ui-serif" | "cursive" | "fantasy" =>
            if opts.systemFonts then Seq("Times New Roman", "Times", "Georgia", "DejaVu Serif", "Liberation Serif").view.flatMap(SystemFonts.family).headOption.orElse(Some(default)) else Some(default)
          case _ => named(n)
        fam.foreach(f => { val face = f(bold, italic); if !out.contains(face) then out += face })
      }
      val d = default(bold, italic)
      if !out.contains(d) then out += d
      if !out.contains(FontFamily.NotoSans(bold, italic)) then out += FontFamily.NotoSans(bold, italic)
      // fonts supplied through SvgOptions also serve as fallbacks for characters the named families lack (CJK, emoji, ...)
      opts.fonts.foreach { f => val face = f(bold, italic); if !out.contains(face) then out += face }
      out.toVector
    }).asInstanceOf[Vector[FontFace]]

  private def shaperFor(r: Renderer, faces: Vector[FontFace]): Shaper =
    r.doc.fontCache.getOrElseUpdate("shaper:" + faces.map(System.identityHashCode).mkString(","), new Shaper(faces)).asInstanceOf[Shaper]

  // ---------------------------------------------------------------- textPath geometry
  private def pathPolyline(r: Renderer, target: XmlElement, ctx: Ctx): Option[(Vector[Point], Vector[Double], Double)] =
    val key = target
    r.doc.fontCache.getOrElseUpdate("poly:" + System.identityHashCode(key), {
      val c = Ctx(ctx.pm, Affine.Identity, ctx.style, ctx.vw, ctx.vh)
      r.shapePath(target, c).flatMap { p =>
        val tt = target.attrs.get("transform").flatMap(Transforms.parseOpt).getOrElse(Affine.Identity)
        p.transform(tt).flatten(0.05).headOption.map { (pts, closed) =>
          val all = if closed then pts :+ pts.head else pts
          val lens = Array.ofDim[Double](all.size)
          for k <- 1 until all.size do lens(k) = lens(k - 1) + all(k - 1).distance(all(k))
          (all, lens.toVector, lens.last)
        }.filter(_._3 > 0)
      }
    }).asInstanceOf[Option[(Vector[Point], Vector[Double], Double)]]

  private def pointAt(pts: Vector[Point], cum: Vector[Double], d: Double): (Point, Double) =
    var lo = 0; var hi = cum.size - 1
    while hi - lo > 1 do { val mid = (lo + hi) / 2; if cum(mid) <= d then lo = mid else hi = mid }
    val a = pts(lo); val b = pts(math.min(lo + 1, pts.size - 1))
    val seg = cum(math.min(lo + 1, cum.size - 1)) - cum(lo)
    val t = if seg <= 0 then 0.0 else (d - cum(lo)) / seg
    (a.lerp(b, t), math.atan2(b.y - a.y, b.x - a.x))
