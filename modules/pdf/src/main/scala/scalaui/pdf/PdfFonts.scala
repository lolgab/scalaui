package scalaui.pdf

import scalaui.font.FontFace
import scalaui.shaping.Shaper
import scala.collection.mutable

/** A font registered with a [[PdfDocument]]; only glyphs actually drawn are embedded (TrueType: outline subset; CFF OpenType: the CFF table with
  * unused charstrings emptied). `fallbacks` are consulted per character when `face` lacks a glyph.
  */
final class PdfFont private[pdf] (val face: FontFace, private[pdf] val index: Int, val fallbacks: Seq[FontFace], private[pdf] val id: Int):
  val shaper = new Shaper(face +: fallbacks)
  private[pdf] var pdfA = false
  /** The character id written to the content stream for a glyph (always the glyph id: embedded CFF charsets are rewritten to match). */
  private[pdf] def cidOf(gid: Int): Int = gid
  /** cid -> the text (UTF-16) it was drawn for, for ToUnicode. */
  private[pdf] val used = mutable.TreeMap.empty[Int, String]
  private[pdf] val gidOfCid = mutable.HashMap.empty[Int, Int]
  /** Records that glyph `gid` stands for `text`; false when it already stands for different text (callers then use ActualText). */
  private[pdf] def use(gid: Int, text: String): Boolean =
    val cid = cidOf(gid)
    if gid == 0 then { gidOfCid(cid) = 0; if !used.contains(cid) then used(cid) = ""; true }
    else
      gidOfCid(cid) = gid
      used.get(cid) match
        case Some(t) => t == text || text.isEmpty
        case None => if text.nonEmpty then used(cid) = text else if !used.contains(cid) then used(cid) = ""; true

private[pdf] object PdfFonts:
  private def i16(d: Array[Byte], o: Int): Int = (((d(o) & 0xff) << 8) | (d(o + 1) & 0xff)).toShort
  private def u16(d: Array[Byte], o: Int): Int = ((d(o) & 0xff) << 8) | (d(o + 1) & 0xff)

  private def sanitize(s: String): String = s.filter(c => c.isLetterOrDigit || c == '-').take(40)

  /** Six uppercase letters derived from the glyph set (the conventional subset tag). */
  private def tag(glyphs: Iterable[Int], salt: String): String =
    var h = 2166136261L
    salt.foreach(c => h = ((h ^ c) * 16777619L) & 0xffffffffL)
    glyphs.foreach(g => h = ((h ^ g) * 16777619L) & 0xffffffffL)
    val sb = new StringBuilder
    for _ <- 0 until 6 do { sb += ('A' + (h % 26).toInt).toChar; h /= 26; h = (h * 31 + 7) & 0xffffffffL }
    sb.toString

  def emit(f: PdfFont, ctx: EmitCtx): Unit =
    val face = f.face
    val scale = 1000.0 / face.unitsPerEm
    def sc(v: Double): Int = math.round(v * scale).toInt
    val glyphs = f.used.keySet.toSet.map(f.gidOfCid)
    val name = tag(glyphs, face.fullName) + "+" + sanitize(face.fullName)
    val (headD, headO) = face.rawTable("head").get
    val bbox = Seq(sc(i16(headD, headO + 36)), sc(i16(headD, headO + 38)), sc(i16(headD, headO + 40)), sc(i16(headD, headO + 42)))
    val italicAngle = face.rawTable("post").map((d, o) => ((d(o + 4) << 24 | (d(o + 5) & 0xff) << 16 | (d(o + 6) & 0xff) << 8 | (d(o + 7) & 0xff)) / 65536.0)).getOrElse(0.0)
    val capHeight = face.rawTable("OS/2").filter((d, o) => u16(d, o) >= 2 && face.tableRange("OS/2")._2 >= 90).map((d, o) => i16(d, o + 88)).filter(_ > 0).map(sc(_)).getOrElse(sc(face.ascent * 0.7))
    val flags = 4 | (if face.isMonospace then 1 else 0) | (if face.isItalic then 64 else 0)
    val stemV = 50 + (face.weight - 100) / 4 * 1
    val fileEntry =
      if face.isTrueType then
        val sub = FontSubset.build(face, glyphs)
        val id = ctx.stream(s"/Length1 ${sub.length}", sub)
        s"/FontFile2 $id 0 R"
      else
        val otf = CffFont.build(face, glyphs)
        val id = ctx.stream("/Subtype /OpenType", otf)
        s"/FontFile3 $id 0 R"
    val desc = ctx.add(Fmt.bytes(s"<< /Type /FontDescriptor /FontName ${Fmt.name(name)} /Flags $flags /FontBBox [${bbox.mkString(" ")}] /ItalicAngle ${Fmt.n(italicAngle)} " +
      s"/Ascent ${sc(face.ascent)} /Descent ${sc(face.descent)} /CapHeight $capHeight /StemV $stemV $fileEntry >>"))
    // widths: consecutive glyph ids share one `c [w w ...]` entry
    val w = new StringBuilder
    val ids = f.used.keys.toVector
    var i = 0
    while i < ids.length do
      var j = i
      while j + 1 < ids.length && ids(j + 1) == ids(j) + 1 do j += 1
      w ++= s"${ids(i)} [${(i to j).map(k => sc(face.advance(f.gidOfCid(ids(k))))).mkString(" ")}] "
      i = j + 1
    val cidType = if face.isTrueType then "CIDFontType2" else "CIDFontType0"
    val map = if face.isTrueType then " /CIDToGIDMap /Identity" else ""
    val cid = ctx.add(Fmt.bytes(s"<< /Type /Font /Subtype /$cidType /BaseFont ${Fmt.name(name)} /CIDSystemInfo << /Registry (Adobe) /Ordering (Identity) /Supplement 0 >> " +
      s"/FontDescriptor $desc 0 R$map /DW 1000 /W [$w] >>"))
    val cmap = new StringBuilder("/CIDInit /ProcSet findresource begin\n12 dict begin\nbegincmap\n/CIDSystemInfo << /Registry (Adobe) /Ordering (UCS) /Supplement 0 >> def\n/CMapName /Adobe-Identity-UCS def\n/CMapType 2 def\n1 begincodespacerange\n<0000> <FFFF>\nendcodespacerange\n")
    // PDF/A-2u needs every used code mapped: glyphs without source text (e.g. .notdef) map to U+FFFD
    val mapped = if f.pdfA then f.used.toSeq.map((g, t) => (g, if t.isEmpty then "\uFFFD" else t)) else f.used.toSeq.filter(_._2.nonEmpty)
    mapped.grouped(100).foreach { chunk =>
      cmap ++= s"${chunk.size} beginbfchar\n"
      chunk.foreach { (g, t) => if t.nonEmpty then cmap ++= f"<$g%04X> <${t.map(c => f"${c.toInt}%04X").mkString}>\n" }
      cmap ++= "endbfchar\n"
    }
    cmap ++= "endcmap\nCMapName currentdict /CMap defineresource pop\nend\nend"
    val tu = ctx.stream("", Fmt.bytes(cmap.toString))
    ctx.set(f.id, Fmt.bytes(s"<< /Type /Font /Subtype /Type0 /BaseFont ${Fmt.name(name)} /Encoding /Identity-H /DescendantFonts [$cid 0 R] /ToUnicode $tu 0 R >>"))
