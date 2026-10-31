package scalaui.shaping

import scalaui.font.FontFace
import scalaui.unicode.{CodePoints, ExtPict, Gc, Scripts}

/** One positioned glyph from `font`. Offsets are y up (HarfBuzz convention). `yAdvance` is non-zero only for vertical shaping
  * (negative: the pen moves down); there `xOffset`/`yOffset` already include the shift to the glyph's vertical origin.
  * `cluster` is the UTF-16 index in the source text of the code point it came from. */
final case class ShapedGlyph(glyph: Int, cluster: Int, xAdvance: Double, xOffset: Double = 0, yOffset: Double = 0, font: FontFace = null,
    yAdvance: Double = 0)

/** OpenType shaping: cmap, canonical normalization against the font, GSUB (ligatures, contextual forms, Arabic joining)
  * and GPOS (kerning, mark and cursive attachment), with per-character font fallback (the first face in `fonts`
  * that has a glyph wins). Indic/Khmer/Myanmar syllable reordering is not implemented.
  */
final class Shaper(val fonts: Seq[FontFace]):
  require(fonts.nonEmpty, "at least one font is required")
  def this(font: FontFace) = this(Seq(font))
  /** The primary face, used for line metrics. */
  val font: FontFace = fonts.head
  private val shapers = fonts.map(f => f -> new FaceShaper(f)).toMap

  private def faceFor(cp: Int): FontFace = fonts.find(_.glyphIndex(cp) != 0).getOrElse(font)

  /** Controls (except tab) and variation selectors never produce glyphs; other format characters stay in the
    * shaping buffer (joining, ligatures) and come out with zero advance.
    */
  private def dropped(cp: Int): Boolean =
    cp != '\t' && (Gc.table(cp) == Gc.Cc || (cp >= 0xFE00 && cp <= 0xFE0F) || (cp >= 0xE0100 && cp <= 0xE01EF))

  /** Shapes `text` at `size` (em height in pixels); advances are in pixels. Glyphs come out in logical order
    * (cluster indices ascend for left-to-right and right-to-left runs alike). With `rtl` (an odd bidi level)
    * mirrored characters such as brackets use their mirror image. Dropped characters get no glyph, so their index
    * falls inside the previous glyph's cluster range.
    *
    * @param features OpenType features to switch on (`value` > 0) or off (0) on top of the script defaults
    * @param language OpenType language system tag, e.g. `Some("TRK ")`
    * @param vertical top-to-bottom shaping (`vert` feature, vmtx advances, VORG origins, vertical GPOS); `rtl` is ignored
    */
  def shape(text: String, size: Double, rtl: Boolean = false, features: Seq[FontFeature] = Nil,
      language: Option[String] = None, vertical: Boolean = false): Vector[ShapedGlyph] =
    val cpsB = Array.newBuilder[Int]; val clB = Array.newBuilder[Int]
    val all = CodePoints(text)
    var cluster = 0; var prev = -1; var riRun = 0
    var k = 0
    while k < all.size do
      val cp = all.cps(k)
      // marks, ZWJ, emoji modifiers/ZWJ sequences and the second flag letter continue the previous cluster
      val g = Gc.table(cp)
      val ri = cp >= 0x1F1E6 && cp <= 0x1F1FF
      val continues = prev >= 0 && (g == Gc.Mn || g == Gc.Me || g == Gc.Mc || cp == 0x200D || (cp >= 0x1F3FB && cp <= 0x1F3FF) ||
        (prev == 0x200D && ExtPict.table(cp) == ExtPict.Extended_Pictographic) || (ri && riRun % 2 == 1))
      riRun = if ri then riRun + 1 else 0
      if !continues then cluster = all.offset(k)
      if !dropped(cp) then { cpsB += cp; clB += cluster }
      prev = cp
      k += 1
    val cps = cpsB.result(); val clusters = clB.result()
    val out = Vector.newBuilder[ShapedGlyph]
    var s = 0
    while s < cps.length do
      val face = faceFor(cps(s))
      var e = s + 1
      while e < cps.length && faceFor(cps(e)).eq(face) do e += 1
      val sub = cps.slice(s, e)
      val script = sub.iterator.map(Scripts.of).find(n => n != "Common" && n != "Inherited" && n != "Unknown").getOrElse("Common")
      val scale = size / face.unitsPerEm
      shapers(face).shape(sub, clusters.slice(s, e), rtl && !vertical, script, language, features, vertical).foreach { g =>
        out += ShapedGlyph(g.glyph, g.cluster, g.xAdvance * scale, g.xOffset * scale, g.yOffset * scale, face, g.yAdvance * scale)
      }
      s = e
    out.result()
