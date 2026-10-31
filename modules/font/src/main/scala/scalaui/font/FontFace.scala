package scalaui.font

import scalaui.geom.Point
import scalaui.raster.{Path, PathEl}
import scala.collection.mutable

/** A parsed TrueType/OpenType (glyf-flavoured) font. Outlines are in font units, y up.
  * Variable fonts use their default instance; hinting and OpenType layout are ignored here
  * (see the `shaping` module for GSUB/GPOS).
  */
final class FontFace private (private val d: Array[Byte], base: Int):
  import FontFace.*

  private val tables: Map[String, (Int, Int)] =
    val n = u16(d, base + 4)
    (0 until n).map { i =>
      val o = base + 12 + i * 16
      new String(d, o, 4, "ISO-8859-1") -> (u32(d, o + 8), u32(d, o + 12))
    }.toMap

  private def table(tag: String): Int =
    tables.getOrElse(tag, throw new IllegalArgumentException(s"font has no '$tag' table"))._1
  def hasTable(tag: String): Boolean = tables.contains(tag)
  /** The raw font bytes and a table's offset into them, for layout engines that parse GSUB/GPOS themselves. */
  /** `(offset, length)` of a table within the font bytes. */
  def tableRange(tag: String): (Int, Int) = tables.getOrElse(tag, throw new IllegalArgumentException(s"font has no '$tag' table"))
  def rawTable(tag: String): Option[(Array[Byte], Int)] = tables.get(tag).map(t => (d, t._1))

  val unitsPerEm: Int = u16(d, table("head") + 18)
  private val longLoca: Boolean = i16(d, table("head") + 50) != 0
  val numGlyphs: Int = u16(d, table("maxp") + 4)
  /** Typographic ascent (positive), descent (negative) and line gap, in font units. */
  val ascent: Int = i16(d, table("hhea") + 4)
  val descent: Int = i16(d, table("hhea") + 6)
  val lineGap: Int = i16(d, table("hhea") + 8)
  private val numHMetrics: Int = u16(d, table("hhea") + 34)
  private val hmtx = table("hmtx")
  private val hasGlyf = tables.contains("glyf")
  private lazy val loca = table("loca")
  private lazy val glyf = table("glyf")
  private lazy val cff: Cff = { val (o, l) = tableRange("CFF "); new Cff(d, o, l) }
  /** True for TrueType-flavoured (glyf) fonts, false for CFF OpenType. */
  def isTrueType: Boolean = hasGlyf

  private val cmapLookup: Int => Int = buildCmap()

  // --- naming and style ---

  private def nameString(id: Int): Option[String] =
    tables.get("name").flatMap { (no, _) =>
      val count = u16(d, no + 2); val strOff = no + u16(d, no + 4)
      val recs = (0 until count).map(i => no + 6 + i * 12).filter(r => u16(d, r + 6) == id)
      def decode(r: Int): String =
        val len = u16(d, r + 8); val off = strOff + u16(d, r + 10)
        if u16(d, r) == 3 || u16(d, r) == 0 then new String(d, off, len, "UTF-16BE") else new String(d, off, len, "ISO-8859-1")
      recs.find(r => u16(d, r) == 3 && u16(d, r + 4) == 0x409).orElse(recs.find(r => u16(d, r) == 3)).orElse(recs.headOption).map(decode)
    }
  /** Family name, preferring the typographic family (nameID 16) over the legacy one. */
  lazy val familyName: String = nameString(16).orElse(nameString(1)).getOrElse("")
  lazy val styleName: String = nameString(17).orElse(nameString(2)).getOrElse("Regular")
  lazy val fullName: String = nameString(4).getOrElse(familyName)
  private lazy val macStyle: Int = u16(d, table("head") + 44)
  private lazy val os2: Option[Int] = tables.get("OS/2").map(_._1)
  /** CSS-style weight 100..900 (OS/2 usWeightClass; 400/700 inferred from head.macStyle otherwise). */
  lazy val weight: Int = os2.map(o => u16(d, o + 4)).getOrElse(if (macStyle & 1) != 0 then 700 else 400)
  lazy val isBold: Boolean = weight >= 600
  lazy val isItalic: Boolean = os2.map(o => (u16(d, o + 62) & 1) != 0).getOrElse((macStyle & 2) != 0)
  lazy val isMonospace: Boolean = tables.get("post").exists((o, _) => u32(d, o + 12) != 0)

  /** Glyph index for a Unicode code point, 0 (.notdef) when missing. */
  def glyphIndex(codePoint: Int): Int = cmapLookup(codePoint)
  def hasGlyph(codePoint: Int): Boolean = cmapLookup(codePoint) != 0

  def advance(glyph: Int): Int =
    val i = math.min(glyph, numHMetrics - 1)
    u16(d, hmtx + i * 4)
  def leftSideBearing(glyph: Int): Int =
    if glyph < numHMetrics then i16(d, hmtx + glyph * 4 + 2)
    else i16(d, hmtx + numHMetrics * 4 + (glyph - numHMetrics) * 2)

  // --- vertical metrics (vhea/vmtx/VORG), following HarfBuzz's fallbacks ---

  /** Ascender and descender used for horizontal layout like HarfBuzz: OS/2 typographic metrics when USE_TYPO_METRICS
    * is set, else hhea (typographic ones if hhea is zero). The descender is negative.
    */
  lazy val layoutAscender: Int = layoutMetrics._1
  lazy val layoutDescender: Int = layoutMetrics._2
  private lazy val layoutMetrics: (Int, Int) =
    val typo = os2.filter(o => u16(d, o) >= 4 && tableRange("OS/2")._2 >= 72).map(o => (i16(d, o + 68), i16(d, o + 70)))
    val useTypo = os2.exists(o => u16(d, o) >= 4 && (u16(d, o + 62) & 0x80) != 0)
    if useTypo && typo.isDefined then typo.get
    else if ascent != 0 || descent != 0 then (ascent, descent)
    else typo.getOrElse((0, 0))

  /** True if the font has vhea and vmtx tables. */
  def hasVerticalMetrics: Boolean = tables.contains("vhea") && tables.contains("vmtx")
  private lazy val numVMetrics: Int = u16(d, table("vhea") + 34)
  /** vhea ascent/descent/line gap in font units (the typographic ascender/descender split of the vertical line), or 0 without vhea. */
  lazy val verticalAscent: Int = if tables.contains("vhea") then i16(d, table("vhea") + 4) else layoutAscender
  lazy val verticalDescent: Int = if tables.contains("vhea") then i16(d, table("vhea") + 6) else layoutDescender
  lazy val verticalLineGap: Int = if tables.contains("vhea") then i16(d, table("vhea") + 8) else 0

  /** Advance height of a glyph in vertical text (positive, font units): vmtx, or ascender - descender when absent. */
  def verticalAdvance(glyph: Int): Int =
    if hasVerticalMetrics then
      val i = math.min(glyph, numVMetrics - 1)
      u16(d, table("vmtx") + i * 4)
    else layoutAscender - layoutDescender

  /** Top side bearing from vmtx; without vmtx the glyph is centred vertically in its advance (HarfBuzz). */
  def topSideBearing(glyph: Int): Int =
    if hasVerticalMetrics then
      val vm = table("vmtx")
      if glyph < numVMetrics then i16(d, vm + glyph * 4 + 2) else i16(d, vm + numVMetrics * 4 + (glyph - numVMetrics) * 2)
    else
      glyphBounds(glyph) match
        case Some((_, yMin, _, yMax)) => (verticalAdvance(glyph) - (yMax - yMin)) / 2
        case None => 0

  /** `(xMin, yMin, xMax, yMax)` of a glyph's control box in font units; blank glyphs have the empty box `(0, 0, 0, 0)` (CFF: `None`). */
  def glyphBounds(glyph: Int): Option[(Int, Int, Int, Int)] =
    if hasGlyf then
      val (s, e) = glyphRange(glyph)
      if e <= s then Some((0, 0, 0, 0)) else { val o = glyf + s; Some((i16(d, o + 2), i16(d, o + 4), i16(d, o + 6), i16(d, o + 8))) }
    else
      val pts = outline(glyph).elements.flatMap {
        case PathEl.MoveTo(p) => Seq(p)
        case PathEl.LineTo(p) => Seq(p)
        case PathEl.QuadTo(c, p) => Seq(c, p)
        case PathEl.CubicTo(a, b, p) => Seq(a, b, p)
        case PathEl.Close => Nil
      }
      if pts.isEmpty then None
      else Some((math.floor(pts.map(_.x).min).toInt, math.floor(pts.map(_.y).min).toInt, math.ceil(pts.map(_.x).max).toInt, math.ceil(pts.map(_.y).max).toInt))

  private lazy val vorg: Option[(Int, Array[Int], Array[Int])] = tables.get("VORG").map { (o, _) =>
    val n = u16(d, o + 6)
    (i16(d, o + 4), Array.tabulate(n)(i => u16(d, o + 8 + i * 4)), Array.tabulate(n)(i => i16(d, o + 8 + i * 4 + 2)))
  }

  /** Vertical origin of a glyph in font units, relative to its horizontal origin (y up): x is half the advance width,
    * y is VORG, else the top of the glyph plus its top side bearing, else the ascender.
    */
  def verticalOrigin(glyph: Int): (Int, Int) =
    val x = advance(glyph) / 2
    vorg match
      case Some((dflt, ids, ys)) =>
        val k = java.util.Arrays.binarySearch(ids, glyph)
        (x, if k >= 0 then ys(k) else dflt)
      case None =>
        glyphBounds(glyph) match
          case Some((_, _, _, yMax)) => (x, yMax + topSideBearing(glyph))
          case None => (x, layoutAscender)

  // --- colour glyphs (COLR/CPAL, sbix, CBDT/CBLC, SVG) ---

  private lazy val colorTables = new ColorTables(d, tables, numGlyphs)
  /** True if the font has any colour glyph source: COLR v0/v1 layers, bitmap strikes (sbix, CBDT) or an `SVG ` table. */
  lazy val hasColorGlyphs: Boolean = tables.contains("COLR") || tables.contains("sbix") || tables.contains("CBDT") || tables.contains("SVG ")
  /** Number of CPAL palettes (0 without CPAL). */
  def paletteCount: Int = if tables.contains("CPAL") then colorTables.paletteCount else 0
  /** Colours of CPAL palette `index`. */
  def palette(index: Int = 0): Vector[scalaui.color.Color] = if tables.contains("CPAL") then colorTables.palette(index) else Vector.empty
  /** True if `glyph` has a COLR entry. */
  def hasLayeredColor(glyph: Int): Boolean = tables.contains("COLR") && colorTables.hasColorGlyph(glyph)
  /** The COLR (v0 or v1) paint graph of `glyph` for CPAL palette `palette`; `foreground` replaces palette index 0xFFFF ("text colour"). */
  def colorGlyph(glyph: Int, palette: Int = 0, foreground: scalaui.color.Color = scalaui.color.Color.Black): Option[ColorPaint] =
    if tables.contains("COLR") then colorTables.colorGlyph(glyph, palette, foreground) else None
  /** Pixel sizes of the `sbix` / `CBDT` bitmap strikes, ascending. */
  def bitmapSizes: Vector[Int] = if tables.contains("sbix") || tables.contains("CBDT") then colorTables.bitmapSizes else Vector.empty
  /** The bitmap glyph (sbix or CBDT) from the strike closest to `ppem` pixels per em. */
  def bitmapGlyph(glyph: Int, ppem: Int): Option[BitmapGlyph] = if tables.contains("sbix") || tables.contains("CBDT") then colorTables.bitmapGlyph(glyph, ppem) else None
  /** The `SVG ` table document containing `glyph`. */
  def svgGlyph(glyph: Int): Option[SvgGlyphDoc] = if tables.contains("SVG ") then colorTables.svgGlyph(glyph) else None
  /** True if `glyph` has any colour representation. */
  def isColorGlyph(glyph: Int): Boolean = hasColorGlyphs && (hasLayeredColor(glyph) || bitmapGlyph(glyph, 128).isDefined || svgGlyph(glyph).isDefined)

  private val outlineCache = mutable.HashMap.empty[Int, Path]

  /** Glyph outline in font units, y up. Empty path for blank glyphs (space). */
  def outline(glyph: Int): Path = outlineCache.getOrElseUpdate(glyph, if hasGlyf then buildOutline(glyph, 0) else if tables.contains("CFF ") then cff.outline(glyph) else Path.builder.build)

  // --- glyf parsing ---

  private def glyphRange(g: Int): (Int, Int) =
    if g < 0 || g >= numGlyphs then (0, 0)
    else if longLoca then (u32(d, loca + g * 4), u32(d, loca + g * 4 + 4))
    else (u16(d, loca + g * 2) * 2, u16(d, loca + g * 2 + 2) * 2)

  private case class Pt(x: Int, y: Int, on: Boolean)

  private def buildOutline(g: Int, depth: Int): Path =
    val contours = glyphContours(g, depth)
    val pb = Path.builder
    contours.foreach(c => emitContour(pb, c))
    pb.build

  private def glyphContours(g: Int, depth: Int): Vector[Vector[Pt]] =
    val (start, end) = glyphRange(g)
    if end <= start || depth > 8 then return Vector.empty
    val o = glyf + start
    val nContours = i16(d, o)
    if nContours >= 0 then simpleGlyph(o, nContours) else compositeGlyph(o + 10, depth)

  private def simpleGlyph(o: Int, nContours: Int): Vector[Vector[Pt]] =
    if nContours == 0 then return Vector.empty
    val endPts = (0 until nContours).map(i => u16(d, o + 10 + i * 2))
    val nPts = endPts.last + 1
    val insLen = u16(d, o + 10 + nContours * 2)
    var p = o + 12 + nContours * 2 + insLen
    val flags = new Array[Int](nPts)
    var i = 0
    while i < nPts do
      val f = u8(d, p); p += 1
      flags(i) = f; i += 1
      if (f & 8) != 0 then
        var rep = u8(d, p); p += 1
        while rep > 0 do { flags(i) = f; i += 1; rep -= 1 }
    def coords(shortBit: Int, sameBit: Int): Array[Int] =
      val out = new Array[Int](nPts); var v = 0; var k = 0
      while k < nPts do
        val f = flags(k)
        if (f & shortBit) != 0 then
          val dx = u8(d, p); p += 1
          v += (if (f & sameBit) != 0 then dx else -dx)
        else if (f & sameBit) == 0 then { v += i16(d, p); p += 2 }
        out(k) = v; k += 1
      out
    val xs = coords(2, 16)
    val ys = coords(4, 32)
    var s = 0
    endPts.map { e =>
      val c = (s to e).map(k => Pt(xs(k), ys(k), (flags(k) & 1) != 0)).toVector
      s = e + 1; c
    }.toVector

  private def compositeGlyph(start: Int, depth: Int): Vector[Vector[Pt]] =
    var p = start
    val out = Vector.newBuilder[Vector[Pt]]
    var more = true
    while more do
      val flags = u16(d, p); val gi = u16(d, p + 2); p += 4
      var dx = 0; var dy = 0
      if (flags & 1) != 0 then { dx = i16(d, p); dy = i16(d, p + 2); p += 4 }
      else { dx = d(p).toInt; dy = d(p + 1).toInt; p += 2 } // signed bytes
      if (flags & 2) == 0 then { dx = 0; dy = 0 } // point-matching not supported
      var a = 1.0; var b = 0.0; var c = 0.0; var e = 1.0
      def f2(o: Int) = i16(d, o) / 16384.0
      if (flags & 8) != 0 then { a = f2(p); e = a; p += 2 }
      else if (flags & 0x40) != 0 then { a = f2(p); e = f2(p + 2); p += 4 }
      else if (flags & 0x80) != 0 then { a = f2(p); b = f2(p + 2); c = f2(p + 4); e = f2(p + 6); p += 8 }
      glyphContours(gi, depth + 1).foreach { contour =>
        out += contour.map(pt => Pt(math.round(a * pt.x + c * pt.y + dx).toInt, math.round(b * pt.x + e * pt.y + dy).toInt, pt.on))
      }
      more = (flags & 0x20) != 0
    out.result()

  /** Converts one TrueType contour (implied on-curve midpoints between off-curve points) to path elements. */
  private def emitContour(pb: Path.PathBuilder, c: Vector[Pt]): Unit =
    if c.size < 2 then return
    val n = c.size
    // find a starting on-curve point; otherwise synthesize one between two off-curve points
    val firstOn = c.indexWhere(_.on)
    val (startX, startY, from) =
      if firstOn >= 0 then (c(firstOn).x.toDouble, c(firstOn).y.toDouble, firstOn + 1)
      else ((c(0).x + c(n - 1).x) / 2.0, (c(0).y + c(n - 1).y) / 2.0, 0)
    pb.moveTo(startX, startY)
    var cx = 0.0; var cy = 0.0; var haveCtrl = false
    val count = if firstOn >= 0 then n - 1 else n
    var k = 0
    while k < count do
      val pt = c((from + k) % n)
      if pt.on then
        if haveCtrl then { pb.quadTo(cx, cy, pt.x, pt.y); haveCtrl = false } else pb.lineTo(pt.x, pt.y)
      else
        if haveCtrl then
          val mx = (cx + pt.x) / 2; val my = (cy + pt.y) / 2
          pb.quadTo(cx, cy, mx, my)
        cx = pt.x; cy = pt.y; haveCtrl = true
      k += 1
    if haveCtrl then pb.quadTo(cx, cy, startX, startY)
    pb.close()

  // --- cmap ---

  private def buildCmap(): Int => Int =
    val cm = table("cmap")
    val n = u16(d, cm + 2)
    val subs = (0 until n).map(i => (u16(d, cm + 4 + i * 8), u16(d, cm + 6 + i * 8), cm + u32(d, cm + 8 + i * 8)))
    val rank = Seq((3, 10), (0, 4), (0, 6), (3, 1), (0, 3), (0, 2), (0, 1), (0, 0))
    val chosen = rank.iterator.flatMap(r => subs.find(s => (s._1, s._2) == r)).nextOption()
      .orElse(subs.headOption)
    chosen.map(s => subtable(s._3)).getOrElse(_ => 0)

  private def subtable(o: Int): Int => Int =
    u16(d, o) match
      case 0 => cp => if cp >= 0 && cp < 256 then u8(d, o + 6 + cp) else 0
      case 4 =>
        val segX2 = u16(d, o + 6); val seg = segX2 / 2
        val endO = o + 14; val startO = endO + segX2 + 2; val deltaO = startO + segX2; val rangeO = deltaO + segX2
        cp =>
          if cp > 0xffff || cp < 0 then 0
          else
            var lo = 0; var hi = seg - 1; var res = 0
            while lo <= hi do
              val mid = (lo + hi) >>> 1
              if cp > u16(d, endO + mid * 2) then lo = mid + 1
              else if cp < u16(d, startO + mid * 2) then hi = mid - 1
              else
                val ro = u16(d, rangeO + mid * 2); val delta = i16(d, deltaO + mid * 2)
                res =
                  if ro == 0 then (cp + delta) & 0xffff
                  else
                    val gi = u16(d, rangeO + mid * 2 + ro + (cp - u16(d, startO + mid * 2)) * 2)
                    if gi == 0 then 0 else (gi + delta) & 0xffff
                lo = hi + 1
            res
      case 6 =>
        val first = u16(d, o + 6); val cnt = u16(d, o + 8)
        cp => if cp >= first && cp < first + cnt then u16(d, o + 10 + (cp - first) * 2) else 0
      case 12 =>
        val groups = u32(d, o + 12)
        cp =>
          var lo = 0; var hi = groups - 1; var res = 0
          while lo <= hi do
            val mid = (lo + hi) >>> 1; val g = o + 16 + mid * 12
            if cp > u32(d, g + 4) then lo = mid + 1
            else if cp < u32(d, g) then hi = mid - 1
            else { res = u32(d, g + 8) + (cp - u32(d, g)); lo = hi + 1 }
          res
      case _ => _ => 0

object FontFace:
  /** Parses a font file (TrueType/OpenType, WOFF 1.0 or WOFF2, detected by signature); for `.ttc` collections picks face `index`. */
  def apply(data: Array[Byte], index: Int = 0): FontFace =
    if data.length > 4 then
      new String(data, 0, 4, "ISO-8859-1") match
        case "wOF2" => return fromWoff2(data)
        case "wOFF" => return fromWoff(data)
        case _ =>
    val base = if new String(data, 0, 4, "ISO-8859-1") == "ttcf" then u32(data, 12 + index * 4) else 0
    new FontFace(data, base)

  /** Wraps WOFF 1.0 web font bytes (zlib-compressed tables): unpacks them into a plain sfnt and parses that. See [[fromWoff2]] for WOFF2. */
  def fromWoff(woff: Array[Byte]): FontFace =
    require(woff.length > 44 && new String(woff, 0, 4, "ISO-8859-1") == "wOFF", "not a WOFF 1.0 font")
    val flavor = u32(woff, 4); val numTables = u16(woff, 12)
    val entries = (0 until numTables).map { i =>
      val o = 44 + i * 20
      (new String(woff, o, 4, "ISO-8859-1"), u32(woff, o + 4), u32(woff, o + 8), u32(woff, o + 12), u32(woff, o + 16)) // tag, offset, compLen, origLen, checksum
    }
    val bodies = entries.map { (_, off, comp, orig, _) =>
      val raw = java.util.Arrays.copyOfRange(woff, off, off + comp)
      if comp < orig then scalaui.deflate.Zlib.decompress(raw) else raw
    }
    val out = new java.io.ByteArrayOutputStream()
    def w32(v: Int): Unit = { out.write(v >>> 24); out.write(v >>> 16); out.write(v >>> 8); out.write(v) }
    w32(flavor); out.write(numTables >> 8); out.write(numTables)
    var sr = 1; var es = 0; while sr * 2 <= numTables do { sr *= 2; es += 1 }
    out.write((sr * 16) >> 8); out.write(sr * 16); out.write(es >> 8); out.write(es)
    out.write((numTables * 16 - sr * 16) >> 8); out.write(numTables * 16 - sr * 16)
    var offset = 12 + numTables * 16
    entries.zip(bodies).foreach { case ((tag, _, _, orig, checksum), body) =>
      out.write(tag.getBytes("ISO-8859-1")); w32(checksum); w32(offset); w32(orig)
      offset += (body.length + 3) / 4 * 4
    }
    bodies.foreach { b => out.write(b); for _ <- 0 until ((4 - b.length % 4) % 4) do out.write(0) }
    FontFace(out.toByteArray)

  /** Wraps WOFF 2.0 web font bytes (Brotli-compressed, glyf/loca/hmtx transforms undone): unpacks them into a plain sfnt and parses that. Collections are not supported. */
  def fromWoff2(woff2: Array[Byte]): FontFace = FontFace(Woff2.toSfnt(woff2))

  /** Number of faces in a `.ttc` collection (1 for plain fonts). */
  def collectionSize(data: Array[Byte]): Int =
    if data.length > 12 && new String(data, 0, 4, "ISO-8859-1") == "ttcf" then u32(data, 8) else 1

  /** Loads a font bundled in this module's resources (`fonts/NotoSans-Regular.ttf`, `fonts/Hack-Regular.ttf`). */
  def bundled(name: String): FontFace =
    val in = getClass.getResourceAsStream(s"/fonts/$name")
    require(in != null, s"bundled font not found: $name")
    try FontFace(in.readAllBytes()) finally in.close()
  lazy val notoSans: FontFace = bundled("NotoSans-Regular.ttf")
  lazy val notoSansBold: FontFace = bundled("NotoSans-Bold.ttf")
  lazy val notoSansItalic: FontFace = bundled("NotoSans-Italic.ttf")
  lazy val notoSansBoldItalic: FontFace = bundled("NotoSans-BoldItalic.ttf")
  lazy val hack: FontFace = bundled("Hack-Regular.ttf")
  lazy val hackBold: FontFace = bundled("Hack-Bold.ttf")
  lazy val inter: FontFace = bundled("Inter-Regular.ttf")
  lazy val interMedium: FontFace = bundled("Inter-Medium.ttf")
  lazy val interSemiBold: FontFace = bundled("Inter-SemiBold.ttf")
  lazy val jetBrainsMono: FontFace = bundled("JetBrainsMono-Regular.ttf")

  private def u8(d: Array[Byte], o: Int): Int = d(o) & 0xff
  private def u16(d: Array[Byte], o: Int): Int = ((d(o) & 0xff) << 8) | (d(o + 1) & 0xff)
  private def i16(d: Array[Byte], o: Int): Int = (u16(d, o) << 16) >> 16
  private def u32(d: Array[Byte], o: Int): Int = (u16(d, o) << 16) | u16(d, o + 2)
