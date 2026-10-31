package scalaui.imaging

import scalaui.codecs.Image
import scalaui.color.{Color, PremulColor}
import scalaui.font.*
import scalaui.geom.*
import scalaui.raster.*

/** Rasterizes colour glyphs: COLR v0/v1 paint graphs (solid, linear/radial/sweep gradients, clipping glyphs, layers, transforms,
  * Porter-Duff and blend-mode composites) and `sbix` / `CBDT` bitmap strikes. `SVG ` table glyphs are rendered by the svg module.
  */
object ColorGlyphs:
  /** A rasterized glyph: `pixmap` is premultiplied; the glyph origin lies `top` pixels below the pixmap's top edge and `-left` pixels right of its left edge. */
  final case class Rendered(pixmap: Pixmap, left: Int, top: Int)

  /** True when `glyph` has a layered (COLR) or bitmap (sbix/CBDT) colour representation. `largestStrike` picks the biggest bitmap strike instead of the closest one in size. */
  def hasColor(face: FontFace, glyph: Int): Boolean =
    face.hasColorGlyphs && (face.hasLayeredColor(glyph) || face.bitmapGlyph(glyph, 64).isDefined)

  /** Draws `glyph` onto `dst` (source-over). `m` maps font units (y up, origin at the glyph origin) to `dst` pixels.
    * Returns false, drawing nothing, when the glyph has no colour representation.
    */
  def draw(dst: Pixmap, face: FontFace, glyph: Int, m: Affine, palette: Int = 0, foreground: Color = Color.Black, largestStrike: Boolean = false): Boolean =
    if !face.hasColorGlyphs then false
    else face.colorGlyph(glyph, palette, foreground) match
      case Some(p) => drawPaint(dst, face, p, m); true
      case None => drawBitmap(dst, face, glyph, m, largestStrike)

  /** Bounding box of the glyph's colour rendering in font units (y up) for a render at `pixelSize`. */
  def bounds(face: FontFace, glyph: Int, pixelSize: Double): Option[Rect] =
    if !face.hasColorGlyphs then None
    else face.colorGlyph(glyph) match
      case Some(p) => paintBounds(face, p, Affine.Identity)
      case None => bitmapBounds(face, glyph, pixelSize)

  /** Renders a colour glyph alone at `pixelSize` pixels per em (origin at integer pixel position), or None if it has no colour data.
    * With `rotated` the glyph is turned 90 degrees clockwise about its origin (sideways glyphs in vertical text).
    */
  def render(face: FontFace, glyph: Int, pixelSize: Double, palette: Int = 0, foreground: Color = Color.Black, rotated: Boolean = false, maxSize: Int = 2048): Option[Rendered] =
    val s = pixelSize / face.unitsPerEm
    bounds(face, glyph, pixelSize).flatMap { b =>
      if b.width <= 0 || b.height <= 0 then None
      else
        // pixel box (y down) of the upright or rotated glyph relative to its origin
        val (px0, px1, py0, py1) = if rotated then (b.y0 * s, b.y1 * s, b.x0 * s, b.x1 * s) else (b.x0 * s, b.x1 * s, -b.y1 * s, -b.y0 * s)
        val left = math.floor(px0).toInt - 1; val top = 1 - math.floor(py0).toInt
        val w = math.ceil(px1).toInt + 1 - left; val h = math.ceil(py1).toInt + 1 + top
        if w <= 0 || h <= 0 || w > maxSize || h > maxSize then None
        else
          val pm = new Pixmap(w, h)
          val m = if rotated then Affine(0, s, s, 0, -left.toDouble, top.toDouble) else Affine(s, 0, 0, -s, -left.toDouble, top.toDouble)
          if draw(pm, face, glyph, m, palette, foreground) then Some(Rendered(pm, left, top)) else None
    }

  // ---------------------------------------------------------------- bounds
  private def outlineBounds(face: FontFace, gid: Int, m: Affine): Option[Rect] =
    val pts = face.outline(gid).flatten(face.unitsPerEm / 200.0).flatMap(_._1).map(m.apply)
    if pts.isEmpty then None else Some(Rect(pts.map(_.x).min, pts.map(_.y).min, pts.map(_.x).max, pts.map(_.y).max))

  private def union(a: Option[Rect], b: Option[Rect]): Option[Rect] = (a, b) match
    case (Some(x), Some(y)) => Some(Rect(math.min(x.x0, y.x0), math.min(x.y0, y.y0), math.max(x.x1, y.x1), math.max(x.y1, y.y1)))
    case _ => a.orElse(b)

  private def paintBounds(face: FontFace, p: ColorPaint, m: Affine): Option[Rect] = p match
    case ColorPaint.Glyph(gid, _) => outlineBounds(face, gid, m)
    case ColorPaint.Layers(ls) => ls.foldLeft(Option.empty[Rect])((acc, l) => union(acc, paintBounds(face, l, m)))
    case ColorPaint.Transform(t, q) => paintBounds(face, q, m * t)
    case ColorPaint.Clip(r, q) =>
      val c = m.transformRectBbox(r)
      paintBounds(face, q, m).map(b => Rect(math.max(b.x0, c.x0), math.max(b.y0, c.y0), math.min(b.x1, c.x1), math.min(b.y1, c.y1))).filter(b => b.x1 > b.x0 && b.y1 > b.y0)
    case ColorPaint.Composite(_, s, b) => union(paintBounds(face, s, m), paintBounds(face, b, m))
    case _ => None

  private def bitmapBounds(face: FontFace, glyph: Int, pixelSize: Double): Option[Rect] =
    face.bitmapGlyph(glyph, math.max(1, math.round(pixelSize).toInt)).flatMap { b =>
      bitmapSize(b).map { (w, h) =>
        val k = face.unitsPerEm.toDouble / b.ppem
        val y0 = b.bottom.getOrElse(b.top.getOrElse(0) - h)
        Rect(b.left * k, y0 * k, (b.left + w) * k, (y0 + h) * k)
      }
    }

  private def bitmapSize(b: BitmapGlyph): Option[(Int, Int)] =
    if b.width > 0 && b.height > 0 then Some((b.width, b.height)) else decoded(b).map(i => (i.width, i.height))

  private def decoded(b: BitmapGlyph): Option[Image] =
    try Some(Image.decode(b.data)) catch case _: Exception => None

  // ---------------------------------------------------------------- bitmaps
  private def drawBitmap(dst: Pixmap, face: FontFace, glyph: Int, m: Affine, largestStrike: Boolean): Boolean =
    val ppem = if largestStrike then Int.MaxValue else math.max(1, math.round(face.unitsPerEm * math.sqrt(math.abs(m.determinant))).toInt)
    face.bitmapGlyph(glyph, ppem) match
      case None => false
      case Some(b) =>
        decoded(b) match
          case None => false
          case Some(img) =>
            val k = face.unitsPerEm.toDouble / b.ppem
            val y0 = b.bottom.getOrElse(b.top.getOrElse(0) - img.height) // bottom edge above the baseline, strike pixels
            // bitmap pixel (px, py) (y down from the top-left) -> font units
            val toFont = Affine(k, 0, 0, -k, b.left * k, (y0 + img.height) * k)
            drawImage(dst, Pixmaps.fromImage(img), m * toFont)
            true

  /** Source-over of `src` with `toDst` mapping its pixel coordinates to `dst`; box-filters first when it shrinks a lot, bilinear otherwise. */
  private def drawImage(dst: Pixmap, src0: Pixmap, toDst: Affine): Unit =
    if !toDst.isInvertible then return
    val scale = math.sqrt(math.abs(toDst.determinant))
    var src = src0; var m = toDst
    if scale < 0.75 then
      val w = math.max(1, math.round(src0.width * scale).toInt); val h = math.max(1, math.round(src0.height * scale).toInt)
      src = Resample.resize(src0, w, h, ResizeFilter.Bilinear)
      m = toDst * Affine.scale(src0.width.toDouble / w, src0.height.toDouble / h)
    val inv = m.inverse
    val corners = Seq(Point(0, 0), Point(src.width, 0), Point(src.width, src.height), Point(0, src.height)).map(m.apply)
    val x0 = math.max(0, math.floor(corners.map(_.x).min).toInt); val x1 = math.min(dst.width, math.ceil(corners.map(_.x).max).toInt)
    val y0 = math.max(0, math.floor(corners.map(_.y).min).toInt); val y1 = math.min(dst.height, math.ceil(corners.map(_.y).max).toInt)
    val sd = src.data; val sw = src.width; val sh = src.height
    def px(x: Int, y: Int, c: Int): Float = if x < 0 || y < 0 || x >= sw || y >= sh then 0f else sd((y * sw + x) * 4 + c)
    var y = y0
    while y < y1 do
      var x = x0
      while x < x1 do
        val p = inv(Point(x + 0.5, y + 0.5))
        if p.x > -1 && p.y > -1 && p.x < sw + 1 && p.y < sh + 1 then
          val fx = p.x - 0.5; val fy = p.y - 0.5
          val ix = math.floor(fx).toInt; val iy = math.floor(fy).toInt
          val tx = (fx - ix).toFloat; val ty = (fy - iy).toFloat
          val di = (y * dst.width + x) * 4
          val sa = bil(px, ix, iy, tx, ty, 3)
          if sa > 0f then
            val k = 1f - sa
            var c = 0
            while c < 3 do { dst.data(di + c) = bil(px, ix, iy, tx, ty, c) + dst.data(di + c) * k; c += 1 }
            dst.data(di + 3) = sa + dst.data(di + 3) * k
        x += 1
      y += 1

  private def bil(px: (Int, Int, Int) => Float, ix: Int, iy: Int, tx: Float, ty: Float, c: Int): Float =
    val a = px(ix, iy, c) * (1 - tx) + px(ix + 1, iy, c) * tx
    val b = px(ix, iy + 1, c) * (1 - tx) + px(ix + 1, iy + 1, c) * tx
    a * (1 - ty) + b * ty

  // ---------------------------------------------------------------- COLR rendering
  private def fullRect(dst: Pixmap): Path = Path.rect(Rect(0, 0, dst.width, dst.height))

  /** Composites `p` over `dst`; `m` maps font units to dst pixels. */
  private def drawPaint(dst: Pixmap, face: FontFace, p: ColorPaint, m: Affine): Unit = p match
    case ColorPaint.Layers(ls) => ls.foreach(drawPaint(dst, face, _, m))
    case ColorPaint.Transform(t, q) => drawPaint(dst, face, q, m * t)
    case ColorPaint.Glyph(gid, inner) =>
      val outline = face.outline(gid)
      leafPaint(inner, m) match
        case Some(paint) => dst.fill(outline, paint, FillRule.NonZero, m)
        case None =>
          val layer = new Pixmap(dst.width, dst.height)
          drawPaint(layer, face, inner, m)
          val mask = new Pixmap(dst.width, dst.height)
          mask.fill(outline, Paint.solid(Color.White), FillRule.NonZero, m)
          var i = 0
          while i < layer.data.length do
            val a = mask.data(i + 3)
            layer.data(i) *= a; layer.data(i + 1) *= a; layer.data(i + 2) *= a; layer.data(i + 3) *= a
            i += 4
          Blend.composite(dst, layer)
    case ColorPaint.Clip(r, inner) =>
      val layer = new Pixmap(dst.width, dst.height)
      drawPaint(layer, face, inner, m)
      val mask = new Pixmap(dst.width, dst.height)
      mask.fill(Path.rect(r), Paint.solid(Color.White), FillRule.NonZero, m)
      var i = 0
      while i < layer.data.length do
        val a = mask.data(i + 3)
        layer.data(i) *= a; layer.data(i + 1) *= a; layer.data(i + 2) *= a; layer.data(i + 3) *= a
        i += 4
      Blend.composite(dst, layer)
    case ColorPaint.Composite(mode, src, backdrop) =>
      val b = new Pixmap(dst.width, dst.height); drawPaint(b, face, backdrop, m)
      val s = new Pixmap(dst.width, dst.height); drawPaint(s, face, src, m)
      compositeOp(b, s, mode)
      Blend.composite(dst, b)
    case leaf =>
      // an unclipped paint covers the whole canvas
      leafPaint(leaf, m).foreach(paint => dst.fill(fullRect(dst), paint))

  private def leafPaint(p: ColorPaint, m: Affine): Option[Paint] = p match
    case ColorPaint.Solid(c) => Some(Paint.solid(c))
    case g: ColorPaint.LinearGradient => Some(new LinearPaint(g, m))
    case g: ColorPaint.RadialGradient => Some(new RadialPaint(g, m))
    case g: ColorPaint.SweepGradient => Some(new SweepPaint(g, m))
    case _ => None

  private def compositeOp(b: Pixmap, s: Pixmap, mode: CompositeMode): Unit =
    import CompositeMode.*
    def blendMode: Option[BlendMode] = mode match
      case Screen => Some(BlendMode.Screen); case Overlay => Some(BlendMode.Overlay); case Darken => Some(BlendMode.Darken)
      case Lighten => Some(BlendMode.Lighten); case ColorDodge => Some(BlendMode.ColorDodge); case ColorBurn => Some(BlendMode.ColorBurn)
      case HardLight => Some(BlendMode.HardLight); case SoftLight => Some(BlendMode.SoftLight); case Difference => Some(BlendMode.Difference)
      case Exclusion => Some(BlendMode.Exclusion); case Multiply => Some(BlendMode.Multiply); case HslHue => Some(BlendMode.Hue)
      case HslSaturation => Some(BlendMode.Saturation); case HslColor => Some(BlendMode.Color); case HslLuminosity => Some(BlendMode.Luminosity)
      case _ => None
    blendMode match
      case Some(bm) => Blend.composite(b, s, 0, 0, bm)
      case None =>
        val bd = b.data; val sd = s.data
        var i = 0
        while i < bd.length do
          val sa = sd(i + 3); val ba = bd(i + 3)
          var c = 0
          while c < 4 do
            val sv = sd(i + c); val bv = bd(i + c)
            bd(i + c) = mode match
              case Clear => 0f
              case Src => sv
              case Dest => bv
              case SrcOver => sv + bv * (1f - sa)
              case DestOver => bv + sv * (1f - ba)
              case SrcIn => sv * ba
              case DestIn => bv * sa
              case SrcOut => sv * (1f - ba)
              case DestOut => bv * (1f - sa)
              case SrcAtop => sv * ba + bv * (1f - sa)
              case DestAtop => bv * sa + sv * (1f - ba)
              case Xor => sv * (1f - ba) + bv * (1f - sa)
              case Plus => math.min(1f, sv + bv)
              case _ => sv + bv * (1f - sa)
            c += 1
          i += 4

  // ---------------------------------------------------------------- gradient paints (device pixel -> glyph space via the inverse of m)
  /** Colour line lookup. As in COLRv1, the first and last stop delimit the span that Repeat/Reflect repeat (stops need not cover 0..1). */
  private final class Stops(stops: Vector[ColorStop], extend: ColorExtend):
    private val n = stops.size
    private val lo = if n == 0 then 0f else stops.head.offset
    private val hi = if n == 0 then 0f else stops.last.offset
    private val pos = stops.map(_.offset).toArray
    private val col = stops.map(_.color).toArray
    private val degenerate = n > 0 && hi - lo < 1e-9f
    private def put(c: Color, out: Array[Float]): Unit = { out(0) = c.r * c.a; out(1) = c.g * c.a; out(2) = c.b * c.a; out(3) = c.a }
    def into(t0: Double, out: Array[Float]): Unit =
      if n == 0 then { out(0) = 0f; out(1) = 0f; out(2) = 0f; out(3) = 0f; return }
      if degenerate then
        if extend == ColorExtend.Pad then put(if t0 < lo then col(0) else col(n - 1), out)
        else { out(0) = 0f; out(1) = 0f; out(2) = 0f; out(3) = 0f }
        return
      val u0 = (t0 - lo) / (hi - lo) // 0..1 spans first..last stop
      val u = extend match
        case ColorExtend.Pad => math.min(1.0, math.max(0.0, u0))
        case ColorExtend.Repeat => u0 - math.floor(u0)
        case ColorExtend.Reflect => val v = u0 - 2 * math.floor(u0 / 2); if v > 1 then 2 - v else v
      val tf = (lo + u * (hi - lo)).toFloat
      var i = 0
      while i < n && !(pos(i) >= tf) do i += 1
      val c = if i == 0 then col(0) else if i == n then col(n - 1) else
        val k = if pos(i) == pos(i - 1) then 0f else (tf - pos(i - 1)) / (pos(i) - pos(i - 1))
        val a = col(i - 1); val b = col(i)
        Color(a.r + (b.r - a.r) * k, a.g + (b.g - a.g) * k, a.b + (b.b - a.b) * k, a.a + (b.a - a.a) * k)
      put(c, out)

  private abstract class GradPaint(m: Affine) extends Paint:
    protected val inv: Affine = if m.isInvertible then m.inverse else Affine.Identity
    protected val ok: Boolean = m.isInvertible
    def colorAt(x: Double, y: Double): PremulColor =
      val o = new Array[Float](4); colorAtInto(x, y, o); PremulColor(o(0), o(1), o(2), o(3))

  private final class LinearPaint(g: ColorPaint.LinearGradient, m: Affine) extends GradPaint(m):
    private val stops = new Stops(g.stops, g.extend)
    // t is the projection onto the normal of p0->p2, scaled so that p1 has t = 1
    private val d01x = g.p1.x - g.p0.x; private val d01y = g.p1.y - g.p0.y
    private val (ux, uy) =
      val vx = g.p2.x - g.p0.x; val vy = g.p2.y - g.p0.y
      if vx == 0 && vy == 0 then (d01x, d01y) else (-vy, vx)
    private val denom = d01x * ux + d01y * uy
    override def colorAtInto(x: Double, y: Double, out: Array[Float]): Unit =
      val p = inv(Point(x, y))
      val t = if denom == 0 then 0.0 else ((p.x - g.p0.x) * ux + (p.y - g.p0.y) * uy) / denom
      stops.into(t, out)

  private final class RadialPaint(g: ColorPaint.RadialGradient, m: Affine) extends GradPaint(m):
    private val stops = new Stops(g.stops, g.extend)
    private val cdx = g.c1.x - g.c0.x; private val cdy = g.c1.y - g.c0.y; private val dr = g.r1 - g.r0
    private val a = cdx * cdx + cdy * cdy - dr * dr
    override def colorAtInto(x: Double, y: Double, out: Array[Float]): Unit =
      val p = inv(Point(x, y))
      val px = p.x - g.c0.x; val py = p.y - g.c0.y
      // solve |P - c0 - s*cd| = r0 + s*dr for the largest s with r(s) >= 0
      val b = px * cdx + py * cdy + g.r0 * dr
      val c = px * px + py * py - g.r0 * g.r0
      var s = Double.NaN
      if math.abs(a) < 1e-12 then
        if b != 0 then { val s0 = c / (2 * b); if g.r0 + s0 * dr >= 0 then s = s0 }
      else
        val disc = b * b - a * c
        if disc >= 0 then
          val sq = math.sqrt(disc)
          val s1 = (b + sq) / a; val s2 = (b - sq) / a
          val hi = math.max(s1, s2); val lo = math.min(s1, s2)
          if g.r0 + hi * dr >= 0 then s = hi else if g.r0 + lo * dr >= 0 then s = lo
      if s.isNaN then { out(0) = 0f; out(1) = 0f; out(2) = 0f; out(3) = 0f } else stops.into(s, out)

  private final class SweepPaint(g: ColorPaint.SweepGradient, m: Affine) extends GradPaint(m):
    private val (start, end, stops) =
      if g.startAngle <= g.endAngle then (g.startAngle, g.endAngle, new Stops(g.stops, g.extend))
      else (g.endAngle, g.startAngle, new Stops(g.stops.reverse.map(s => ColorStop(1f - s.offset, s.color)), g.extend))
    override def colorAtInto(x: Double, y: Double, out: Array[Float]): Unit =
      val p = inv(Point(x, y))
      var a = math.toDegrees(math.atan2(p.y - g.center.y, p.x - g.center.x))
      if a < 0 then a += 360.0
      val span = end - start
      if span == 0 && g.extend != ColorExtend.Pad then { out(0) = 0f; out(1) = 0f; out(2) = 0f; out(3) = 0f }
      else stops.into(if span == 0 then (if a >= start then 1.0 else 0.0) else (a - start) / span, out)
