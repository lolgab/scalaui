package scalaui.pdf

import scalaui.codecs.Image
import scalaui.color.Color
import scalaui.geom.*
import scalaui.raster.{FillRule, LineCap, LineJoin, Path, PathEl, Pixmap, Stroke}
import scalaui.shaping.FontFeature
import scala.collection.mutable

/** How glyphs are painted. */
enum PdfTextMode(val op: Int):
  case Fill extends PdfTextMode(0)
  case Stroke extends PdfTextMode(1)
  case FillStroke extends PdfTextMode(2)
  /** Not painted (selectable text over scanned images / OCR layers). */
  case Invisible extends PdfTextMode(3)

/** A drawing surface recording a PDF content stream: a page, or the content of a transparency group, soft mask or tiling cell.
  * Coordinates are in points with the origin at the top-left and y pointing down (pages flip PDF's native axis themselves).
  */
class PdfCanvas private[pdf] (private[pdf] val doc: PdfDocument, val width: Double, val height: Double, private[pdf] val base: Affine, private[pdf] val bounds: Rect):
  import Fmt.n
  private[pdf] val content = new StringBuilder
  private var ctm = Affine.Identity
  private val ctmStack = mutable.Stack.empty[Affine]
  private[pdf] val fonts = mutable.LinkedHashSet.empty[PdfFont]
  private[pdf] val xobjects = mutable.LinkedHashMap.empty[Int, String]
  private[pdf] val gstates = mutable.LinkedHashMap.empty[String, String]
  private[pdf] val patterns = mutable.LinkedHashMap.empty[Int, String]
  private[pdf] val shadings = mutable.LinkedHashMap.empty[Int, String]

  private[pdf] def resourceDict: String =
    val sb = new StringBuilder("<< /ProcSet [/PDF /Text /ImageB /ImageC /ImageI]")
    if fonts.nonEmpty then sb ++= " /Font << " + fonts.map(f => s"/F${f.index} ${f.id} 0 R").mkString(" ") + " >>"
    if xobjects.nonEmpty then sb ++= " /XObject << " + xobjects.map((id, nm) => s"/$nm $id 0 R").mkString(" ") + " >>"
    if gstates.nonEmpty then sb ++= " /ExtGState << " + gstates.map((body, nm) => s"/$nm << $body >>").mkString(" ") + " >>"
    if patterns.nonEmpty then sb ++= " /Pattern << " + patterns.map((id, nm) => s"/$nm $id 0 R").mkString(" ") + " >>"
    if shadings.nonEmpty then sb ++= " /Shading << " + shadings.map((id, nm) => s"/$nm $id 0 R").mkString(" ") + " >>"
    sb ++= " >>"
    sb.toString

  /** Visible area in current user space (for gradient spread calculations and group bounding boxes). */
  private[pdf] def userBounds: Rect =
    val m = base * ctm
    if m.isInvertible then m.inverse.transformRectBbox(bounds) else bounds

  private def gs(body: String): String = gstates.getOrElseUpdate(body, "GS" + (gstates.size + 1))
  private def alphaGs(a: Double): String = gs(s"/ca ${n(a)} /CA ${n(a)}")

  private def emitPath(p: Path): Unit =
    var cur = Point.Zero
    p.elements.foreach {
      case PathEl.MoveTo(a) => content ++= s"${n(a.x)} ${n(a.y)} m\n"; cur = a
      case PathEl.LineTo(a) => content ++= s"${n(a.x)} ${n(a.y)} l\n"; cur = a
      case PathEl.QuadTo(c, a) =>
        val c1 = Point(cur.x + 2.0 / 3 * (c.x - cur.x), cur.y + 2.0 / 3 * (c.y - cur.y))
        val c2 = Point(a.x + 2.0 / 3 * (c.x - a.x), a.y + 2.0 / 3 * (c.y - a.y))
        content ++= s"${n(c1.x)} ${n(c1.y)} ${n(c2.x)} ${n(c2.y)} ${n(a.x)} ${n(a.y)} c\n"; cur = a
      case PathEl.CubicTo(c1, c2, a) =>
        content ++= s"${n(c1.x)} ${n(c1.y)} ${n(c2.x)} ${n(c2.y)} ${n(a.x)} ${n(a.y)} c\n"; cur = a
      case PathEl.Close => content ++= "h\n"
    }

  private def pathBounds(p: Path, inflate: Double = 0.0): Rect =
    val pts = p.flatten(0.5).flatMap(_._1)
    if pts.isEmpty then Rect(0, 0, 0, 0)
    else Rect(pts.map(_.x).min - inflate, pts.map(_.y).min - inflate, pts.map(_.x).max + inflate, pts.map(_.y).max + inflate)

  // ------------------------------------------------------------------ graphics state
  def save(): Unit = { content ++= "q\n"; ctmStack.push(ctm) }
  def restore(): Unit = { content ++= "Q\n"; if ctmStack.nonEmpty then ctm = ctmStack.pop() }
  def transform(m: Affine): Unit = { content ++= s"${n(m.a)} ${n(m.b)} ${n(m.c)} ${n(m.d)} ${n(m.e)} ${n(m.f)} cm\n"; ctm = ctm * m }
  def clip(p: Path, rule: FillRule = FillRule.NonZero): Unit =
    emitPath(p); content ++= (if rule == FillRule.EvenOdd then "W* n\n" else "W n\n")
  def clipRect(r: Rect): Unit = clip(Path.rect(r))

  // ------------------------------------------------------------------ paints
  /** Selects `paint` as the fill or stroke colour; `area` is the user-space region about to be painted (used by reflect/repeat gradients). */
  private def setPaint(paint: PdfPaint, stroke: Boolean, area: Rect): Unit =
    val csOp = if stroke then "CS" else "cs"; val scn = if stroke then "SCN" else "scn"
    paint match
      case PdfPaint.Solid(c) =>
        content ++= s"${n(c.r)} ${n(c.g)} ${n(c.b)} ${if stroke then "RG" else "rg"}\n"
        if c.a < 0.999f then content ++= s"/${alphaGs(c.a)} gs\n"
      case PdfPaint.Tiling(pat, t) =>
        val m = base * ctm * t
        val mx = s"[${n(m.a)} ${n(m.b)} ${n(m.c)} ${n(m.d)} ${n(m.e)} ${n(m.f)}]"
        val pid = doc.cached(s"tile ${System.identityHashCode(pat)} $mx") { (id, ctx) =>
          val dict = s"/Type /Pattern /PatternType 1 /PaintType 1 /TilingType 1 /BBox [0 0 ${n(pat.width)} ${n(pat.height)}] /XStep ${n(pat.xStep)} /YStep ${n(pat.yStep)} /Matrix $mx /Resources ${pat.canvas.resourceDict}"
          ctx.set(id, Fmt.stream(dict, Fmt.bytes(pat.canvas.content.toString), ctx.compress))
        }
        content ++= s"/Pattern $csOp /${patternName(pid)} $scn\n"
      case g @ (_: PdfPaint.LinearGradient | _: PdfPaint.RadialGradient) =>
        val (shadingOf, transform, stops, spread) = g match
          case l: PdfPaint.LinearGradient => (l, l.transform, l.stops, l.spread)
          case r: PdfPaint.RadialGradient => (r, r.transform, r.stops, r.spread)
          case _ => throw new IllegalStateException
        if stops.isEmpty then return
        val area2 = if transform.isInvertible then transform.inverse.transformRectBbox(area) else area
        val (coords, d0, d1, fnSpread) = Shadings.geometry(shadingOf, area2)
        def shadingId(alpha: Boolean): Int =
          val fn = Gradients.function(stops, fnSpread, d0, d1, alpha)
          val cs = if alpha then "/DeviceGray" else "/DeviceRGB"
          val isRadial = shadingOf.isInstanceOf[PdfPaint.RadialGradient]
          val body = s"<< /ShadingType ${if isRadial then 3 else 2} /ColorSpace $cs /Coords [${coords.map(n).mkString(" ")}] /Domain [$d0 $d1] /Function $fn /Extend [true true] >>"
          doc.cached("shading " + body)((id, ctx) => ctx.set(id, Fmt.bytes(body)))
        val m = base * ctm * transform
        val sid = shadingId(alpha = false)
        val pbody = s"<< /Type /Pattern /PatternType 2 /Shading $sid 0 R /Matrix [${n(m.a)} ${n(m.b)} ${n(m.c)} ${n(m.d)} ${n(m.e)} ${n(m.f)}] >>"
        val pid = doc.cached("pattern " + pbody)((id, ctx) => ctx.set(id, Fmt.bytes(pbody)))
        content ++= s"/Pattern $csOp /${patternName(pid)} $scn\n"
        if stops.exists(_._2.a < 0.999f) then
          // transparency in the stops: luminosity soft mask painting the same geometry as gray levels
          val maskCanvas = new PdfCanvas(doc, width, height, Affine.Identity, userBounds)
          maskCanvas.transform(transform)
          maskCanvas.paintShading(shadingId(alpha = true))
          val form = doc.addForm(maskCanvas, group = true, bbox = area.inflate(2, 2))
          content ++= s"/${gs(s"/SMask << /Type /Mask /S /Luminosity /G $form 0 R >>")} gs\n"

  private def patternName(id: Int): String = patterns.getOrElseUpdate(id, "P" + (patterns.size + 1))

  private[pdf] def paintShading(id: Int): Unit =
    val nm = shadings.getOrElseUpdate(id, "Sh" + (shadings.size + 1))
    content ++= s"/$nm sh\n"

  // ------------------------------------------------------------------ paths
  def fill(p: Path, paint: PdfPaint, rule: FillRule = FillRule.NonZero): Unit =
    save(); setPaint(paint, stroke = false, pathBounds(p)); emitPath(p); content ++= (if rule == FillRule.EvenOdd then "f*\n" else "f\n"); restore()

  def stroke(p: Path, paint: PdfPaint, s: Stroke, dash: Option[PdfDash] = None): Unit =
    save(); setPaint(paint, stroke = true, pathBounds(p, s.width * math.max(1.0, s.miterLimit)))
    content ++= s"${n(s.width)} w ${s.cap.ordinal} J ${s.join.ordinal match { case 0 => 0; case 1 => 2; case _ => 1 }} j ${n(s.miterLimit)} M\n"
    dash.foreach(d => content ++= s"[${d.array.map(n).mkString(" ")}] ${n(d.phase)} d\n")
    emitPath(p); content ++= "S\n"; restore()

  def fillPath(p: Path, c: Color, rule: FillRule = FillRule.NonZero): Unit = fill(p, PdfPaint.Solid(c), rule)
  def strokePath(p: Path, c: Color, s: Stroke): Unit = stroke(p, PdfPaint.Solid(c), s)
  def fillRect(r: Rect, c: Color): Unit = fillPath(Path.rect(r), c)
  def strokeRect(r: Rect, c: Color, width: Double): Unit = strokePath(Path.rect(r), c, Stroke(width))
  def strokeLine(a: Point, b: Point, c: Color, width: Double): Unit =
    strokePath(Path.builder.moveTo(a.x, a.y).lineTo(b.x, b.y).build, c, Stroke(width))

  // ------------------------------------------------------------------ text
  /** Draws `text` with its baseline starting at `(x, y)`; kerning and mark positioning come from the font's GPOS. Returns the advance width. */
  def drawText(text: String, font: PdfFont, size: Double, x: Double, y: Double, color: Color): Double =
    drawText(text, font, size, x, y, PdfPaint.Solid(color))

  def drawText(text: String, font: PdfFont, size: Double, x: Double, y: Double, paint: PdfPaint, rtl: Boolean = false, features: Seq[FontFeature] = Nil,
      language: Option[String] = None, mode: PdfTextMode = PdfTextMode.Fill, strokeWidth: Double = 1.0, letterSpacing: Double = 0.0): Double =
    val logical = font.shaper.shape(text, size, rtl, features, language)
    if logical.isEmpty then return 0.0
    // glyphs sharing a cluster form a group; its source text is the cluster's character range
    val groups = mutable.ArrayBuffer.empty[(Int, Int, String)] // (first glyph, last glyph + 1, text) in logical order
    var k0 = 0
    while k0 < logical.length do
      var k1 = k0 + 1
      while k1 < logical.length && logical(k1).cluster == logical(k0).cluster do k1 += 1
      val c = logical(k0).cluster
      val next = if k1 < logical.length then logical(k1).cluster else text.length
      groups += ((k0, k1, text.substring(c, math.min(next, text.length)).filter(_ >= ' ')))
      k0 = k1
    val width = logical.map(_.xAdvance + letterSpacing).sum
    save()
    val paintArea = Rect(x, y - size, x + width, y + size * 0.3)
    mode match
      case PdfTextMode.Fill | PdfTextMode.Invisible => setPaint(paint, stroke = false, paintArea)
      case PdfTextMode.Stroke => setPaint(paint, stroke = true, paintArea); content ++= s"${n(strokeWidth)} w\n"
      case PdfTextMode.FillStroke => setPaint(paint, stroke = false, paintArea); setPaint(paint, stroke = true, paintArea); content ++= s"${n(strokeWidth)} w\n"
    content ++= "BT\n"
    if mode != PdfTextMode.Fill then content ++= s"${mode.op} Tr\n"
    var penX = x
    var segFont: PdfFont = null; var seg = new StringBuilder
    def flush(): Unit =
      if segFont != null && seg.nonEmpty then content ++= s"[${seg.toString}] TJ\n"
      seg = new StringBuilder
    var needTm = true
    // visual order: reverse the groups of a right-to-left run (glyphs inside a group keep their logical order)
    val ordered = if rtl then groups.reverse else groups
    for case (g0, g1, gText) <- ordered do
      // map glyphs to text: one to one when counts match, the whole text for a single glyph, otherwise ActualText
      val cps = gText.codePointCount(0, gText.length)
      var consistent = true
      val glyphCount = g1 - g0
      val faceOf = (i: Int) => if logical(i).font != null then logical(i).font else font.face
      def pfOf(i: Int): PdfFont = { val face = faceOf(i); if face eq font.face then font else doc.fontFor(face) }
      // colour glyphs are painted as graphics and never embedded as font glyphs
      def isColor(i: Int): Boolean = PdfColorGlyphs.isColor(faceOf(i), logical(i).glyph)
      def useGlyph(i: Int, t: String): Boolean = isColor(i) || pfOf(i).use(logical(i).glyph, t)
      if glyphCount == 1 then consistent = useGlyph(g0, gText)
      else if glyphCount == cps then
        var off = 0
        for i <- g0 until g1 do
          val len = Character.charCount(gText.codePointAt(off))
          if !useGlyph(i, gText.substring(off, off + len)) then consistent = false
          off += len
      else
        consistent = false
        for i <- g0 until g1 do useGlyph(i, gText) // ActualText carries the real text; this keeps ToUnicode total (PDF/A-2u)
      val actual = !consistent && gText.nonEmpty && !(g0 until g1).exists(isColor) // marked content may not straddle the ET/BT around a colour glyph
      if actual then
        flush(); needTm = needTm
        content ++= s"/Span << /ActualText <FEFF${gText.map(ch => f"${ch.toInt}%04X").mkString}> >> BDC\n"
      for i <- (if rtl then (g1 - 1) to g0 by -1 else g0 until g1) do
        val g = logical(i)
        val pf = pfOf(i)
        val adv = g.xAdvance + letterSpacing
        // PDF/A-2 (6.2.11.8) forbids showing .notdef: leave a gap of the same width instead
        val skip = g.glyph == 0 && doc.pdfA.isDefined
        if skip then { flush(); needTm = true; penX += adv }
        else if isColor(i) then
          // leave the text object, paint the colour glyph as graphics, then continue the text object
          flush(); content ++= "ET\n"
          if mode != PdfTextMode.Invisible then
            val fgc = paint match { case PdfPaint.Solid(col) => col; case _ => Color.Black }
            PdfColorGlyphs.draw(this, faceOf(i), g.glyph, penX + g.xOffset, y - g.yOffset, size, fgc)
          content ++= "BT\n"; needTm = true; segFont = null; penX += adv
        else {
        fonts += pf
        val hasOffset = g.xOffset != 0.0 || g.yOffset != 0.0
        if hasOffset || needTm || (segFont ne pf) then
          flush()
          if segFont ne pf then { content ++= s"/F${pf.index} ${n(size)} Tf\n"; segFont = pf }
          content ++= s"1 0 0 -1 ${n(penX + g.xOffset)} ${n(y - g.yOffset)} Tm\n"
          needTm = false
        seg ++= f"<${pf.cidOf(g.glyph)}%04x>"
        val natural = pf.face.advance(g.glyph) * size / pf.face.unitsPerEm
        val adj = natural - adv
        if math.abs(adj) > 0.001 then seg ++= n(adj / size * 1000)
        if hasOffset then { flush(); needTm = true }
        penX += adv
        }
      if actual then { flush(); content ++= "EMC\n" }
    flush()
    content ++= "ET\n"
    restore()
    width

  def measure(text: String, font: PdfFont, size: Double): Double = font.shaper.shape(text, size).map(_.xAdvance).sum

  // ------------------------------------------------------------------ images
  /** Draws an image scaled into `dst`. */
  def drawImage(img: PdfImage, dst: Rect): Unit = drawImage(img, dst, interpolate = true)
  def drawImage(img: PdfImage, dst: Rect, interpolate: Boolean): Unit =
    val res = xobjects.getOrElseUpdate(img.id, "Im" + (xobjects.size + 1))
    save()
    // image space is a unit square with y up; flip because our page space is y-down
    content ++= s"${n(dst.width)} 0 0 ${n(-dst.height)} ${n(dst.x0)} ${n(dst.y1)} cm\n/$res Do\n"
    restore()
    val _ = interpolate
  def drawImage(img: Image, dst: Rect): Unit = drawImage(doc.addImage(img), dst)
  /** Draws a rasterized [[Pixmap]] (premultiplied floats) scaled into `dst`. */
  def drawPixmap(pm: Pixmap, dst: Rect): Unit = drawImage(Image(pm.width, pm.height, pm.toRgba8), dst)

  // ------------------------------------------------------------------ transparency
  /** Runs `draw` with an extra graphics state applied (no isolation: each object blends individually). */
  private def withGs(body: String)(draw: PdfCanvas => Unit): Unit = { save(); content ++= s"/${gs(body)} gs\n"; draw(this); restore() }

  /** Everything drawn inside uses constant alpha `fill`/`stroke` (multiplied with colour alpha by the viewer). */
  def withOpacity(fill: Double, stroke: Double)(draw: PdfCanvas => Unit): Unit = withGs(s"/ca ${n(fill)} /CA ${n(stroke)}")(draw)
  /** Everything drawn inside is blended with `mode` against the backdrop. */
  def withBlendMode(mode: PdfBlend)(draw: PdfCanvas => Unit): Unit = withGs(s"/BM /${mode.pdfName}")(draw)

  /** Renders `draw` into an isolated transparency group and composites it with `opacity`, `blend` and an optional soft mask `mask`
    * (drawn in the same user space). The group sees the current transformation, so coordinates stay the same inside and out.
    */
  def group(opacity: Double = 1.0, blend: PdfBlend = PdfBlend.Normal, mask: Option[(PdfMaskKind, PdfCanvas => Unit)] = None, knockout: Boolean = false)(draw: PdfCanvas => Unit): Unit =
    val child = new PdfCanvas(doc, width, height, Affine.Identity, userBounds)
    draw(child)
    val form = doc.addForm(child, group = true, bbox = userBounds, knockout = knockout)
    save()
    val parts = mutable.ArrayBuffer.empty[String]
    if opacity < 0.999 then parts += s"/ca ${n(opacity)} /CA ${n(opacity)}"
    if blend != PdfBlend.Normal then parts += s"/BM /${blend.pdfName}"
    mask.foreach { (kind, drawMask) =>
      val mc = new PdfCanvas(doc, width, height, Affine.Identity, userBounds)
      drawMask(mc)
      val mf = doc.addForm(mc, group = true, bbox = userBounds)
      parts += s"/SMask << /Type /Mask /S /${if kind == PdfMaskKind.Luminosity then "Luminosity" else "Alpha"} /G $mf 0 R >>"
    }
    if parts.nonEmpty then content ++= s"/${gs(parts.mkString(" "))} gs\n"
    val nm = xobjects.getOrElseUpdate(form, "Fm" + (xobjects.size + 1))
    content ++= s"/$nm Do\n"
    restore()

  /** Applies a soft mask drawn by `drawMask` to everything drawn by `draw` (no group isolation of the content itself). */
  def withMask(kind: PdfMaskKind)(drawMask: PdfCanvas => Unit)(draw: PdfCanvas => Unit): Unit =
    val mc = new PdfCanvas(doc, width, height, Affine.Identity, userBounds)
    drawMask(mc)
    val mf = doc.addForm(mc, group = true, bbox = userBounds)
    withGs(s"/SMask << /Type /Mask /S /${if kind == PdfMaskKind.Luminosity then "Luminosity" else "Alpha"} /G $mf 0 R >>")(draw)

/** Gradient geometry helpers. */
private[pdf] object Shadings:
  /** `(coords, domainStart, domainEnd, spread handled by the function)` covering `area` (gradient space) for the requested spread. */
  def geometry(g: PdfPaint, area: Rect): (Seq[Double], Int, Int, PdfSpread) = g match
    case l: PdfPaint.LinearGradient =>
      val v = l.to - l.from
      val l2 = v.lengthSquared
      if l.spread == PdfSpread.Pad || l2 == 0 then (Seq(l.from.x, l.from.y, l.to.x, l.to.y), 0, 1, PdfSpread.Pad)
      else
        val ts = Seq(Point(area.x0, area.y0), Point(area.x1, area.y0), Point(area.x1, area.y1), Point(area.x0, area.y1)).map(p => ((p - l.from).dot(v)) / l2)
        val d0 = math.max(-48, math.floor(ts.min).toInt); val d1 = math.max(d0 + 1, math.min(49, math.ceil(ts.max).toInt))
        (Seq(l.from.x + d0 * v.x, l.from.y + d0 * v.y, l.from.x + d1 * v.x, l.from.y + d1 * v.y), d0, d1, l.spread)
    case r: PdfPaint.RadialGradient =>
      val concentric = r.focal == r.center && r.focalRadius == 0 && r.radius > 0
      if r.spread == PdfSpread.Pad || !concentric then (Seq(r.focal.x, r.focal.y, r.focalRadius, r.center.x, r.center.y, r.radius), 0, 1, PdfSpread.Pad)
      else
        val far = Seq(Point(area.x0, area.y0), Point(area.x1, area.y0), Point(area.x1, area.y1), Point(area.x0, area.y1)).map(_.distance(r.center)).max
        val periods = math.max(1, math.min(96, math.ceil(far / r.radius).toInt))
        (Seq(r.center.x, r.center.y, 0, r.center.x, r.center.y, r.radius * periods), 0, periods, r.spread)
    case _ => throw new IllegalArgumentException("not a gradient")
