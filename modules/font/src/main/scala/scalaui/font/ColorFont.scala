package scalaui.font

import scalaui.color.Color
import scalaui.geom.*

/** How a gradient continues outside its first and last colour stop. */
enum ColorExtend:
  case Pad, Repeat, Reflect

/** A colour stop of a COLR gradient (palette colour and paint alpha already combined). */
final case class ColorStop(offset: Float, color: Color)

/** COLR `CompositeMode`: Porter-Duff operators followed by the separable and non-separable blend modes. */
enum CompositeMode:
  case Clear, Src, Dest, SrcOver, DestOver, SrcIn, DestIn, SrcOut, DestOut, SrcAtop, DestAtop, Xor, Plus
  case Screen, Overlay, Darken, Lighten, ColorDodge, ColorBurn, HardLight, SoftLight, Difference, Exclusion, Multiply
  case HslHue, HslSaturation, HslColor, HslLuminosity

/** A resolved COLR paint graph (v0 layer lists and v1 paint tables alike), in font units with y up. Palette indices are already
  * replaced by colours; the foreground colour (palette index 0xFFFF) was substituted when the graph was built. Variation deltas of
  * variable COLRv1 fonts are not applied (the default instance is used).
  */
sealed trait ColorPaint
object ColorPaint:
  final case class Solid(color: Color) extends ColorPaint
  /** Linear gradient along `p0 -> p1`; `p2` rotates the gradient normal (as in the COLRv1 spec). */
  final case class LinearGradient(p0: Point, p1: Point, p2: Point, stops: Vector[ColorStop], extend: ColorExtend) extends ColorPaint
  /** Two-circle radial gradient. */
  final case class RadialGradient(c0: Point, r0: Double, c1: Point, r1: Double, stops: Vector[ColorStop], extend: ColorExtend) extends ColorPaint
  /** Sweep gradient; angles are in degrees, counter-clockwise from the +x axis (y up). */
  final case class SweepGradient(center: Point, startAngle: Double, endAngle: Double, stops: Vector[ColorStop], extend: ColorExtend) extends ColorPaint
  /** Fills the outline of glyph `gid` with `paint` (the outline is the clip). */
  final case class Glyph(gid: Int, paint: ColorPaint) extends ColorPaint
  /** Paints bottom to top. */
  final case class Layers(layers: Vector[ColorPaint]) extends ColorPaint
  final case class Transform(matrix: Affine, paint: ColorPaint) extends ColorPaint
  /** Clips `paint` to a rectangle (the COLRv1 ClipBox of the glyph). */
  final case class Clip(rect: Rect, paint: ColorPaint) extends ColorPaint
  final case class Composite(mode: CompositeMode, source: ColorPaint, backdrop: ColorPaint) extends ColorPaint

/** A bitmap glyph from `sbix` or `CBDT`/`CBLC`: encoded image data (PNG in practice; sbix may also hold JPEG/TIFF) drawn at `ppem` pixels per em.
  * `left` is the offset in strike pixels from the glyph origin to the bitmap's left edge; `top` / `bottom` give the vertical offset
  * from the baseline to its top (CBDT) or bottom (sbix) edge, y up.
  */
final case class BitmapGlyph(data: Array[Byte], format: String, ppem: Int, left: Int, top: Option[Int], bottom: Option[Int], width: Int, height: Int)

/** An SVG document of the OpenType `SVG ` table covering glyphs `first..last` (already gunzipped); the glyph is the element with id `glyph<gid>`. */
final case class SvgGlyphDoc(svg: String, first: Int, last: Int)

/** The colour tables of a font: `CPAL`, `COLR` v0/v1, `sbix`, `CBDT`/`CBLC` and `SVG `. */
private[font] final class ColorTables(d: Array[Byte], tables: Map[String, (Int, Int)], numGlyphs: Int):
  import ColorTables.*

  private def tbl(tag: String): Option[Int] = tables.get(tag).map(_._1)

  // ---------------------------------------------------------------- CPAL
  private val cpal = tbl("CPAL")
  val paletteCount: Int = cpal.map(o => u16(d, o + 4)).getOrElse(0)
  private val paletteEntries: Int = cpal.map(o => u16(d, o + 2)).getOrElse(0)
  /** Colours of palette `index` (clamped to a valid palette). */
  def palette(index: Int): Vector[Color] = cpal match
    case Some(o) if paletteCount > 0 =>
      val p = if index < 0 || index >= paletteCount then 0 else index
      val first = u16(d, o + 12 + p * 2); val arr = o + u32(d, o + 8)
      Vector.tabulate(paletteEntries) { i =>
        val r = arr + (first + i) * 4
        Color((d(r + 2) & 0xff) / 255f, (d(r + 1) & 0xff) / 255f, (d(r) & 0xff) / 255f, (d(r + 3) & 0xff) / 255f)
      }
    case _ => Vector.empty

  // ---------------------------------------------------------------- COLR
  private val colr = tbl("COLR")
  private val colrVersion = colr.map(o => u16(d, o)).getOrElse(0)
  private val numBase = colr.map(o => u16(d, o + 2)).getOrElse(0)
  private val baseOff = colr.map(o => o + u32(d, o + 4)).getOrElse(0)
  private val layerOff = colr.map(o => o + u32(d, o + 8)).getOrElse(0)
  private val baseListOff = colr.filter(_ => colrVersion >= 1).map(o => u32(d, o + 14)).filter(_ != 0).map(u => colr.get + u)
  private val layerListOff = colr.filter(_ => colrVersion >= 1).map(o => u32(d, o + 18)).filter(_ != 0).map(u => colr.get + u)
  private val clipListOff = colr.filter(_ => colrVersion >= 1).map(o => u32(d, o + 22)).filter(_ != 0).map(u => colr.get + u)
  private val numV1Base = baseListOff.map(o => u32(d, o)).getOrElse(0)

  def hasColr: Boolean = colr.isDefined && paletteCount > 0

  private def findV0(gid: Int): Int =
    var lo = 0; var hi = numBase - 1
    while lo <= hi do
      val mid = (lo + hi) >>> 1; val g = u16(d, baseOff + mid * 6)
      if g < gid then lo = mid + 1 else if g > gid then hi = mid - 1 else return mid
    -1
  private def findV1(gid: Int): Int =
    val b = baseListOff.get
    var lo = 0; var hi = numV1Base - 1
    while lo <= hi do
      val mid = (lo + hi) >>> 1; val g = u16(d, b + 4 + mid * 6)
      if g < gid then lo = mid + 1 else if g > gid then hi = mid - 1 else return b + u32(d, b + 4 + mid * 6 + 2)
    -1

  def hasColorGlyph(gid: Int): Boolean =
    colr.isDefined && paletteCount > 0 && ((baseListOff.isDefined && findV1(gid) >= 0) || findV0(gid) >= 0)

  def colorGlyph(gid: Int, paletteIndex: Int, foreground: Color): Option[ColorPaint] =
    if !hasColorGlyph(gid) then return None
    val pal = palette(paletteIndex)
    def color(idx: Int, alpha: Float): Color =
      val c = if idx == 0xffff then foreground else if idx < pal.size then pal(idx) else Color(0f, 0f, 0f, 1f)
      Color(c.r, c.g, c.b, c.a * alpha)
    if baseListOff.isDefined then
      val p = findV1(gid)
      if p >= 0 then
        val root = new PaintReader(pal, color, foreground).paint(p, 0, Set(gid))
        return Some(clipBox(gid).map(r => ColorPaint.Clip(r, root)).getOrElse(root))
    val i = findV0(gid)
    val first = u16(d, baseOff + i * 6 + 2); val n = u16(d, baseOff + i * 6 + 4)
    Some(ColorPaint.Layers(Vector.tabulate(n) { k =>
      val r = layerOff + (first + k) * 4
      ColorPaint.Glyph(u16(d, r), ColorPaint.Solid(color(u16(d, r + 2), 1f)))
    }))

  private def clipBox(gid: Int): Option[Rect] = clipListOff.flatMap { cl =>
    val n = u32(d, cl + 1)
    var lo = 0; var hi = n - 1
    while lo <= hi do
      val mid = (lo + hi) >>> 1; val r = cl + 5 + mid * 7
      if gid < u16(d, r) then hi = mid - 1
      else if gid > u16(d, r + 2) then lo = mid + 1
      else
        val b = cl + ((u8(d, r + 4) << 16) | (u8(d, r + 5) << 8) | u8(d, r + 6))
        return Some(Rect(i16(d, b + 1), i16(d, b + 3), i16(d, b + 5), i16(d, b + 7)))
    None
  }

  private final class PaintReader(pal: Vector[Color], color: (Int, Float) => Color, foreground: Color):
    private def f2(o: Int): Float = i16(d, o) / 16384f
    private def fixed(o: Int): Double = ((d(o) << 24) | ((d(o + 1) & 0xff) << 16) | ((d(o + 2) & 0xff) << 8) | (d(o + 3) & 0xff)) / 65536.0
    private def off24(o: Int): Int = ((d(o) & 0xff) << 16) | ((d(o + 1) & 0xff) << 8) | (d(o + 2) & 0xff)

    private def colorLine(o: Int, variable: Boolean): (Vector[ColorStop], ColorExtend) =
      val ext = u8(d, o) match { case 1 => ColorExtend.Repeat; case 2 => ColorExtend.Reflect; case _ => ColorExtend.Pad }
      val n = u16(d, o + 1); val sz = if variable then 10 else 6
      val stops = Vector.tabulate(n) { i =>
        val s = o + 3 + i * sz
        ColorStop(f2(s), color(u16(d, s + 2), f2(s + 4)))
      }.sortBy(_.offset)
      (stops, ext)

    /** Reads the paint table at absolute offset `p`. `depth` guards against cyclic graphs. */
    def paint(p: Int, depth: Int, glyphs: Set[Int]): ColorPaint =
      if depth > 64 then return ColorPaint.Layers(Vector.empty)
      val fmt = u8(d, p)
      def sub(rel: Int): ColorPaint = paint(p + rel, depth + 1, glyphs)
      def around(cx: Double, cy: Double, m: Affine) = Affine.translate(Vec2(cx, cy)) * m * Affine.translate(Vec2(-cx, -cy))
      def xf(m: Affine, rel: Int): ColorPaint = ColorPaint.Transform(m, sub(rel))
      fmt match
        case 1 =>
          val n = u8(d, p + 1); val first = u32(d, p + 2)
          layerListOff match
            case Some(ll) =>
              val total = u32(d, ll)
              ColorPaint.Layers(Vector.tabulate(math.max(0, math.min(n, total - first))) { i =>
                paint(ll + u32(d, ll + 4 + (first + i) * 4), depth + 1, glyphs)
              })
            case None => ColorPaint.Layers(Vector.empty)
        case 2 | 3 => ColorPaint.Solid(color(u16(d, p + 1), f2(p + 3)))
        case 4 | 5 =>
          val (st, ext) = colorLine(p + off24(p + 1), fmt == 5)
          def fw(k: Int) = i16(d, p + 4 + k * 2).toDouble
          ColorPaint.LinearGradient(Point(fw(0), fw(1)), Point(fw(2), fw(3)), Point(fw(4), fw(5)), st, ext)
        case 6 | 7 =>
          val (st, ext) = colorLine(p + off24(p + 1), fmt == 7)
          def fw(k: Int) = if k == 2 || k == 5 then u16(d, p + 4 + k * 2).toDouble else i16(d, p + 4 + k * 2).toDouble
          ColorPaint.RadialGradient(Point(fw(0), fw(1)), fw(2), Point(fw(3), fw(4)), fw(5), st, ext)
        case 8 | 9 => // start/end angles are stored biased by 1.0 so that +360 degrees fits
          val (st, ext) = colorLine(p + off24(p + 1), fmt == 9)
          ColorPaint.SweepGradient(Point(i16(d, p + 4), i16(d, p + 6)), (f2(p + 8) + 1.0) * 180.0, (f2(p + 10) + 1.0) * 180.0, st, ext)
        case 10 => ColorPaint.Glyph(u16(d, p + 4), sub(off24(p + 1)))
        case 11 =>
          val g = u16(d, p + 1)
          if glyphs.contains(g) || baseListOff.isEmpty then ColorPaint.Layers(Vector.empty)
          else { val b = findV1(g); if b < 0 then ColorPaint.Layers(Vector.empty) else paint(b, depth + 1, glyphs + g) }
        case 12 | 13 =>
          val t = p + off24(p + 4)
          xf(Affine(fixed(t + 0), fixed(t + 4), fixed(t + 8), fixed(t + 12), fixed(t + 16), fixed(t + 20)), off24(p + 1))
        case 14 | 15 => xf(Affine.translate(Vec2(i16(d, p + 4), i16(d, p + 6))), off24(p + 1))
        case 16 | 17 => xf(Affine.scale(f2(p + 4), f2(p + 6)), off24(p + 1))
        case 18 | 19 => xf(around(i16(d, p + 8), i16(d, p + 10), Affine.scale(f2(p + 4), f2(p + 6))), off24(p + 1))
        case 20 | 21 => xf(Affine.scale(f2(p + 4)), off24(p + 1))
        case 22 | 23 => xf(around(i16(d, p + 6), i16(d, p + 8), Affine.scale(f2(p + 4))), off24(p + 1))
        case 24 | 25 => xf(Affine.rotate(f2(p + 4) * math.Pi), off24(p + 1))
        case 26 | 27 => xf(around(i16(d, p + 6), i16(d, p + 8), Affine.rotate(f2(p + 4) * math.Pi)), off24(p + 1))
        case 28 | 29 => xf(skew(f2(p + 4), f2(p + 6)), off24(p + 1))
        case 30 | 31 => xf(around(i16(d, p + 8), i16(d, p + 10), skew(f2(p + 4), f2(p + 6))), off24(p + 1))
        case 32 =>
          val mode = u8(d, p + 4)
          ColorPaint.Composite(CompositeMode.fromOrdinal(math.min(mode, CompositeMode.values.length - 1)), sub(off24(p + 1)), sub(off24(p + 5)))
        case _ => ColorPaint.Layers(Vector.empty)

    private def skew(x: Float, y: Float): Affine =
      // HarfBuzz: push_transform(1, tan(y*pi), -tan(x*pi), 1, 0, 0) (angles are counter-clockwise half-turns)
      Affine(1, if y == 0f then 0.0 else math.tan(y * math.Pi), if x == 0f then 0.0 else -math.tan(x * math.Pi), 1, 0, 0)

  // ---------------------------------------------------------------- sbix
  private val sbix = tbl("sbix")
  private lazy val sbixStrikes: Vector[(Int, Int)] = sbix match // (ppem, absolute offset)
    case Some(o) => Vector.tabulate(u32(d, o + 4))(i => { val so = o + u32(d, o + 8 + i * 4); (u16(d, so), so) }).sortBy(_._1)
    case None => Vector.empty

  private def sbixGlyph(strikeOff: Int, ppem: Int, gid: Int, depth: Int): Option[BitmapGlyph] =
    if gid < 0 || gid >= numGlyphs || depth > 4 then return None
    val a = u32(d, strikeOff + 4 + gid * 4); val b = u32(d, strikeOff + 4 + (gid + 1) * 4)
    if b - a < 8 then return None
    val g = strikeOff + a
    val tag = new String(d, g + 4, 4, "ISO-8859-1")
    val data = java.util.Arrays.copyOfRange(d, g + 8, strikeOff + b)
    tag match
      case "dupe" => if data.length >= 2 then sbixGlyph(strikeOff, ppem, u16(data, 0), depth + 1) else None
      case "png " | "jpg " | "tiff" =>
        Some(BitmapGlyph(data, tag.trim, ppem, i16(d, g), None, Some(i16(d, g + 2)), 0, 0))
      case _ => None

  // ---------------------------------------------------------------- CBDT / CBLC
  private val cblc = tbl("CBLC").orElse(tbl("EBLC"))
  private val cbdt = tbl("CBDT").orElse(tbl("EBDT"))
  private lazy val cbStrikes: Vector[(Int, Int)] = // (ppemY, absolute offset of BitmapSize)
    if cblc.isEmpty || cbdt.isEmpty then Vector.empty
    else
      val o = cblc.get
      Vector.tabulate(u32(d, o + 4))(i => (u8(d, o + 8 + i * 48 + 45), o + 8 + i * 48)).sortBy(_._1)

  private def cbdtGlyph(sizeRec: Int, ppem: Int, gid: Int): Option[BitmapGlyph] =
    val cblcO = cblc.get; val cbdtO = cbdt.get
    if gid < u16(d, sizeRec + 40) || gid > u16(d, sizeRec + 42) then return None
    val arr = cblcO + u32(d, sizeRec); val n = u32(d, sizeRec + 8)
    var k = 0
    while k < n do
      val e = arr + k * 8
      val first = u16(d, e); val last = u16(d, e + 2)
      if gid >= first && gid <= last then
        val st = arr + u32(d, e + 4)
        val indexFormat = u16(d, st); val imageFormat = u16(d, st + 2); val imageOff = u32(d, st + 4)
        val idx = gid - first
        var start = -1; var end = -1
        var big: Option[Int] = None // absolute offset of BigGlyphMetrics shared by the subtable
        indexFormat match
          case 1 => start = u32(d, st + 8 + idx * 4); end = u32(d, st + 8 + (idx + 1) * 4)
          case 3 => start = u16(d, st + 8 + idx * 2); end = u16(d, st + 8 + (idx + 1) * 2)
          case 2 => val sz = u32(d, st + 8); start = idx * sz; end = start + sz; big = Some(st + 12)
          case 5 =>
            val sz = u32(d, st + 8); big = Some(st + 12)
            val cnt = u32(d, st + 20)
            val j = (0 until cnt).indexWhere(c => u16(d, st + 24 + c * 2) == gid)
            if j >= 0 then { start = j * sz; end = start + sz }
          case 4 =>
            val cnt = u32(d, st + 8)
            val j = (0 until cnt).indexWhere(c => u16(d, st + 12 + c * 4) == gid)
            if j >= 0 then { start = u16(d, st + 12 + j * 4 + 2); end = u16(d, st + 12 + (j + 1) * 4 + 2) }
          case _ =>
        if start < 0 || end <= start then return None
        var p = cbdtO + imageOff + start
        imageFormat match
          case 17 =>
            val h = u8(d, p); val w = u8(d, p + 1); val bx = d(p + 2).toInt; val by = d(p + 3).toInt; p += 5
            val len = u32(d, p); p += 4
            return Some(BitmapGlyph(java.util.Arrays.copyOfRange(d, p, p + len), "png", ppem, bx, Some(by), None, w, h))
          case 18 =>
            val h = u8(d, p); val w = u8(d, p + 1); val bx = d(p + 2).toInt; val by = d(p + 3).toInt; p += 8
            val len = u32(d, p); p += 4
            return Some(BitmapGlyph(java.util.Arrays.copyOfRange(d, p, p + len), "png", ppem, bx, Some(by), None, w, h))
          case 19 =>
            val m = big.getOrElse(return None)
            val h = u8(d, m); val w = u8(d, m + 1); val bx = d(m + 2).toInt; val by = d(m + 3).toInt
            val len = u32(d, p); p += 4
            return Some(BitmapGlyph(java.util.Arrays.copyOfRange(d, p, p + len), "png", ppem, bx, Some(by), None, w, h))
          case _ => return None // monochrome / compressed formats are not colour glyphs
      k += 1
    None

  def hasBitmaps: Boolean = sbix.isDefined || (cblc.isDefined && cbdt.isDefined && tables.contains("CBDT"))

  /** Sizes (pixels per em) of the bitmap strikes, ascending. */
  def bitmapSizes: Vector[Int] = (if sbix.isDefined then sbixStrikes.map(_._1) else cbStrikes.map(_._1)).distinct

  def bitmapGlyph(gid: Int, ppem: Int): Option[BitmapGlyph] =
    // the strike closest to `ppem`, preferring larger ones (they scale down better); falls back to others when the glyph is missing there
    val strikes: Vector[(Int, Int)] = if sbix.isDefined then sbixStrikes else if tables.contains("CBDT") then cbStrikes else Vector.empty
    val ordered = strikes.sortBy { (p, _) => if p >= ppem then (0, p - ppem) else (1, ppem - p) }
    ordered.iterator.flatMap { (p, o) => if sbix.isDefined then sbixGlyph(o, p, gid, 0) else cbdtGlyph(o, p, gid) }.nextOption()

  // ---------------------------------------------------------------- SVG
  private val svgT = tbl("SVG ")
  def hasSvg: Boolean = svgT.isDefined
  def svgGlyph(gid: Int): Option[SvgGlyphDoc] = svgT.flatMap { o =>
    val list = o + u32(d, o + 2)
    val n = u16(d, list)
    var i = 0
    while i < n do
      val r = list + 2 + i * 12
      val first = u16(d, r); val last = u16(d, r + 2)
      if gid >= first && gid <= last then
        val bytes = java.util.Arrays.copyOfRange(d, list + u32(d, r + 4), list + u32(d, r + 4) + u32(d, r + 8))
        val raw = if bytes.length > 2 && (bytes(0) & 0xff) == 0x1f && (bytes(1) & 0xff) == 0x8b then gunzip(bytes) else bytes
        return Some(SvgGlyphDoc(new String(raw, "UTF-8"), first, last))
      i += 1
    None
  }

private[font] object ColorTables:
  def gunzip(d: Array[Byte]): Array[Byte] =
    val flags = d(3) & 0xff
    var p = 10
    if (flags & 4) != 0 then p += 2 + ((d(p) & 0xff) | ((d(p + 1) & 0xff) << 8))
    if (flags & 8) != 0 then { while d(p) != 0 do p += 1; p += 1 }
    if (flags & 16) != 0 then { while d(p) != 0 do p += 1; p += 1 }
    if (flags & 2) != 0 then p += 2
    scalaui.deflate.Zlib.inflateRaw(d, p)
  def u8(d: Array[Byte], o: Int): Int = d(o) & 0xff
  def u16(d: Array[Byte], o: Int): Int = ((d(o) & 0xff) << 8) | (d(o + 1) & 0xff)
  def i16(d: Array[Byte], o: Int): Int = (u16(d, o) << 16) >> 16
  def u32(d: Array[Byte], o: Int): Int = (u16(d, o) << 16) | u16(d, o + 2)
