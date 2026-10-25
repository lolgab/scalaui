package scalaui.pdf

import scalaui.codecs.Image
import scalaui.color.Color
import scalaui.font.*
import scalaui.geom.*
import scalaui.imaging.ColorGlyphs
import scalaui.raster.Path

/** Colour glyphs in PDF: COLR layers become clipped fills with PDF shadings (vector), bitmap strikes (sbix / CBDT) are embedded as images.
  * Paint graphs PDF cannot express (sweep gradients, non-SrcOver composites, repeating/reflecting non-concentric radial gradients) fall back to an
  * embedded rasterization of the glyph.
  */
private[pdf] object PdfColorGlyphs:
  def isColor(face: FontFace, gid: Int): Boolean = face.hasColorGlyphs && gid != 0 && ColorGlyphs.hasColor(face, gid)

  /** Draws the glyph with its origin at `(x, y)` (canvas space, y down) at `size` points per em; `fg` is the text colour for "foreground" palette entries. */
  def draw(c: PdfCanvas, face: FontFace, gid: Int, x: Double, y: Double, size: Double, fg: Color): Unit =
    val s = size / face.unitsPerEm
    face.colorGlyph(gid, 0, fg) match
      case Some(tree) if vectorOk(tree) =>
        c.save(); c.transform(Affine(s, 0, 0, -s, x, y))
        vector(c, face, tree)
        c.restore()
      case Some(_) => raster(c, face, gid, x, y, size, fg)
      case None => bitmap(c, face, gid, x, y, size)

  // ---------------------------------------------------------------- vector
  private def vectorOk(p: ColorPaint): Boolean = p match
    case _: ColorPaint.SweepGradient => false
    case ColorPaint.Composite(CompositeMode.SrcOver, a, b) => vectorOk(a) && vectorOk(b)
    case _: ColorPaint.Composite => false
    case g: ColorPaint.RadialGradient => g.extend == ColorExtend.Pad || (g.c0 == g.c1 && g.r0 == 0)
    case ColorPaint.Glyph(_, q) => vectorOk(q)
    case ColorPaint.Layers(ls) => ls.forall(vectorOk)
    case ColorPaint.Transform(_, q) => vectorOk(q)
    case ColorPaint.Clip(_, q) => vectorOk(q)
    case _ => true

  private def spread(e: ColorExtend): PdfSpread = e match
    case ColorExtend.Pad => PdfSpread.Pad
    case ColorExtend.Repeat => PdfSpread.Repeat
    case ColorExtend.Reflect => PdfSpread.Reflect

  /** The PDF paint for a leaf paint, or None when it paints nothing (e.g. degenerate stops). */
  private def leaf(p: ColorPaint): Option[PdfPaint] = p match
    case ColorPaint.Solid(c) => Some(PdfPaint.Solid(c))
    case g: ColorPaint.LinearGradient =>
      val lo = g.stops.head.offset; val hi = g.stops.last.offset
      if g.stops.isEmpty then None
      else if hi - lo < 1e-9f then (if g.extend == ColorExtend.Pad then Some(PdfPaint.Solid(g.stops.last.color)) else None)
      else
        // axis = projection of p0->p1 onto the normal of p0->p2
        val d01 = g.p1 - g.p0; val d02 = g.p2 - g.p0
        val n = if d02.x == 0 && d02.y == 0 then d01 else Vec2(-d02.y, d02.x)
        val nn = n.x * n.x + n.y * n.y
        if nn == 0 then None
        else
          val k = (d01.x * n.x + d01.y * n.y) / nn
          val axis = Vec2(n.x * k, n.y * k)
          val a = Point(g.p0.x + axis.x * lo, g.p0.y + axis.y * lo); val b = Point(g.p0.x + axis.x * hi, g.p0.y + axis.y * hi)
          Some(PdfPaint.LinearGradient(a, b, norm(g.stops, lo, hi), spread(g.extend)))
    case g: ColorPaint.RadialGradient =>
      if g.stops.isEmpty then None
      else
        val lo = g.stops.head.offset.toDouble; val hi = g.stops.last.offset.toDouble
        if hi - lo < 1e-9 then (if g.extend == ColorExtend.Pad then Some(PdfPaint.Solid(g.stops.last.color)) else None)
        else
          def lerp(a: Double, b: Double, t: Double) = a + (b - a) * t
          val f = Point(lerp(g.c0.x, g.c1.x, lo), lerp(g.c0.y, g.c1.y, lo)); val e = Point(lerp(g.c0.x, g.c1.x, hi), lerp(g.c0.y, g.c1.y, hi))
          Some(PdfPaint.RadialGradient(f, math.max(0, lerp(g.r0, g.r1, lo)), e, math.max(0, lerp(g.r0, g.r1, hi)), norm(g.stops, lo.toFloat, hi.toFloat), spread(g.extend)))
    case _ => None

  private def norm(stops: Vector[ColorStop], lo: Float, hi: Float): Seq[(Float, Color)] = stops.map(s => ((s.offset - lo) / (hi - lo), s.color))

  private def isLeaf(p: ColorPaint) = p match
    case _: ColorPaint.Solid | _: ColorPaint.LinearGradient | _: ColorPaint.RadialGradient => true
    case _ => false

  private def vector(c: PdfCanvas, face: FontFace, p: ColorPaint): Unit = p match
    case ColorPaint.Layers(ls) => ls.foreach(vector(c, face, _))
    case ColorPaint.Transform(t, q) => c.save(); c.transform(t); vector(c, face, q); c.restore()
    case ColorPaint.Clip(r, q) => c.save(); c.clipRect(r); vector(c, face, q); c.restore()
    case ColorPaint.Composite(_, src, backdrop) => vector(c, face, backdrop); vector(c, face, src) // SrcOver only (see vectorOk)
    case ColorPaint.Glyph(gid, inner) =>
      val outline = face.outline(gid)
      if isLeaf(inner) then leaf(inner).foreach(c.fill(outline, _))
      else { c.save(); c.clip(outline); vector(c, face, inner); c.restore() }
    case other => leaf(other).foreach(paint => c.fill(Path.rect(c.userBounds), paint)) // an unclipped paint covers the visible area

  // ---------------------------------------------------------------- raster fallbacks
  private final case class Cached(img: PdfImage, left: Double, top: Double, w: Double, h: Double) // in em-fractions

  private def raster(c: PdfCanvas, face: FontFace, gid: Int, x: Double, y: Double, size: Double, fg: Color): Unit =
    // resolution: 4 pixels per point, kept within sensible bounds; the result is an RGBA image drawn into the glyph box
    val px = math.max(32.0, math.min(1024.0, size * 4))
    val key = ("colr", face, gid, fg.hashCode, px.toInt)
    val cached = c.doc.colorCache.getOrElseUpdate(key, ColorGlyphs.render(face, gid, px, 0, fg).map { r =>
      Cached(c.doc.addImage(scalaui.imaging.Pixmaps.toImage(r.pixmap)), r.left / px, r.top / px, r.pixmap.width / px, r.pixmap.height / px)
    }).asInstanceOf[Option[Cached]]
    cached.foreach(k => c.drawImage(k.img, Rect(x + k.left * size, y - k.top * size, x + (k.left + k.w) * size, y + (k.h - k.top) * size)))

  private def bitmap(c: PdfCanvas, face: FontFace, gid: Int, x: Double, y: Double, size: Double): Unit =
    // the largest strike is embedded as is (PNG decoded once, re-encoded with Flate + SMask) and scaled by the viewer
    val ppem = face.bitmapSizes.lastOption.getOrElse(128)
    val key = ("bmp", face, gid)
    val cached = c.doc.colorCache.getOrElseUpdate(key, face.bitmapGlyph(gid, ppem).flatMap { b =>
      try
        val img = Image.decode(b.data)
        val k = 1.0 / b.ppem // strike pixel -> em
        val bottom = b.bottom.getOrElse(b.top.getOrElse(0) - img.height)
        Some(Cached(c.doc.addImage(img), b.left * k, (bottom + img.height) * k, img.width * k, img.height * k))
      catch case _: Exception => None
    }).asInstanceOf[Option[Cached]]
    cached.foreach(k => c.drawImage(k.img, Rect(x + k.left * size, y - k.top * size, x + (k.left + k.w) * size, y + (k.h - k.top) * size)))
