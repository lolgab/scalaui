---
title: pdf
section: Modules
order: 11
description: A PDF writer with vector graphics, alpha, images, subset-embedded shaped text, colour glyphs and veraPDF-validated PDF/A-2b/2u output.
---
# pdf

`scalaui.pdf` writes PDF 1.7 documents (the role of krilla). Text is shaped with the [shaping](shaping.md) module and embedded as subset TrueType, images are embedded with an alpha soft mask, and streams are Flate-compressed with [deflate](deflate.md). Depends on [raster](raster.md), [shaping](shaping.md), [codecs](codecs.md), [deflate](deflate.md), [cms](cms.md) and [imaging](imaging.md) (colour glyph rasterization).

```scala
import scalaui.pdf.*
```

## API

```scala
final class PdfDocument(var title: String = "", var author: String = ""):
  def font(face: FontFace, fallbacks: Seq[FontFace] = Nil): PdfFont
  def addPage(width: Double = 595, height: Double = 842)(draw: PdfPage => Unit): this.type
  def newPage(width: Double, height: Double): PdfPage
  def addImage(img: codecs.Image): PdfImage          // Flate + SMask, gray detected
  def addJpeg(bytes: Array[Byte]): PdfImage          // DCTDecode passthrough (gray/RGB/CMYK)
  def addEncodedImage(bytes: Array[Byte]): PdfImage
  def tilingPattern(w: Double, h: Double)(draw: PdfCanvas => Unit): PdfTilingPattern
  var subject, keywords, creator, producer: String; var language: Option[String]
  var creationDate, modificationDate: Option[PdfDate]
  var bookmarks: Seq[Bookmark]; var pageLabels: Seq[PageLabel]
  var pdfA: Option[PdfAConformance]; var xmp: Boolean; var compress: Boolean
  def toBytes: Array[Byte]
```

Coordinates are points, origin top left, y down. `PdfPage` and group/mask canvases share `PdfCanvas`:

| Method | Description |
|---|---|
| `fill(path, paint, rule)`, `stroke(path, paint, stroke, dash)` | `PdfPaint`: `Solid`, `LinearGradient`, `RadialGradient` (pad/reflect/repeat, alpha stops via soft mask), `Tiling` |
| `fillPath`, `strokePath`, `fillRect`, `strokeRect`, `strokeLine` | colour conveniences |
| `drawText(text, font, size, x, y, paint, rtl, features, language, mode, strokeWidth, letterSpacing)` | shaped text; GPOS marks, fallback faces, ligature-aware ToUnicode/ActualText, invisible mode for OCR layers |
| `drawImage(img, dst)`, `drawPixmap(pm, dst)` | images |
| `save`, `restore`, `transform`, `clip`, `clipRect` | graphics state |
| `group(opacity, blend, mask)(draw)` | isolated transparency group |
| `withBlendMode(mode)(draw)`, `withOpacity`, `withMask(kind)(mask)(draw)` | blend modes and luminosity/alpha soft masks |
| `PdfPage.link(rect, uri)`, `linkToPage(rect, PdfDestination)` | link annotations |

Fonts: TrueType is subset-embedded (CIDFontType2); CFF OpenType embeds the CFF with unused charstrings emptied (CID-keyed charsets are rewritten so CID = GID). Document features: outlines (`Bookmark`), page labels, Info + XMP metadata, PDF/A-2b and PDF/A-2u (see below).

## Colour fonts

`drawText` paints colour glyphs (COLR v0 and v1, `sbix`, `CBDT`) as graphics instead of font glyphs; the glyph is never embedded as a font glyph. COLR layers become clipped fills (the glyph outline is the clip) with PDF axial and radial shadings, transforms and clips; the text colour fills "foreground" palette entries. Paint graphs that PDF cannot express (sweep gradients, composite modes other than source-over, repeating or reflecting non-concentric radial gradients) fall back to an embedded RGBA rasterization of the glyph at 4 pixels per point (32 to 1024 px per em). Bitmap strikes embed the largest strike as an image, positioned from its bearings. Gaps: colour glyphs are not text in the PDF (no selection or extraction for them) and `SVG ` table glyphs are not drawn.

## PDF/A

Set `doc.pdfA = Some(PdfAConformance.A2b)` (or `A2u`). The writer then adds the XMP packet (`pdfaid` part and conformance, Dublin Core title, creator, description, `pdf:Producer`, creation and modification dates in step with the Info dictionary), an sRGB `OutputIntent` with an embedded ICC profile, a transparency group on every page, the trailer `/ID` and annotation flags, embeds every font used and writes a ToUnicode CMap for all of them. Two behaviours differ from the non-PDF/A output:

- glyphs that would be `.notdef` are not shown (6.2.11.8): the pen advances and a gap is left;
- CMYK JPEGs are converted to RGB instead of being embedded as `DeviceCMYK`, because no CMYK output intent is written (6.2.4.3).

**What is verified.** `tools/pdfa_check.sh` writes every `PdfSuite` scene (paths, gradients, transparency groups and soft masks, blend modes, images with soft masks, gray/RGB/CMYK JPEG, kerned/ligature/Arabic/CJK text with fallback fonts, invisible text, tiling patterns, links, outlines, page labels, colour glyphs, an empty page) once as PDF/A-2b and once as PDF/A-2u and validates each file with veraPDF 1.30 (`brew install verapdf`) against the matching profile. All 18 files pass (the 2b profile has 144 rules). The script exits non-zero on any violation. veraPDF found two real problems when this was first run, both fixed: `.notdef` references (with the missing glyph widths) and DeviceCMYK images.

**What is not claimed.** Only conformance levels B and U of part 2 are produced and only those two profiles were run: no PDF/A-2a (no tagged structure tree, no `/StructTreeRoot`, no `/Lang` on content), no PDF/A-1 or -3, no embedded files, no PDF/UA. Transparency is used freely, which part 2 allows but part 1 does not. The `compress = false` mode and the veraPDF profile for other element combinations than the ones listed were not tested, and veraPDF is one validator: a different checker (for example Acrobat's preflight) may still object. XMP is regenerated from the document properties; a user-supplied packet is not supported.

## Verification

`PdfSuite` writes documents to `target/pdf-out` (with `PDFA=2b` or `PDFA=2u` in the environment every scene is written as PDF/A); `tools/pdf_check.sh` runs `qpdf --check`, renders with poppler and mupdf (the two agree; the paths scene also matches the raster module within anti-aliasing) and extracts text with `pdftotext` (ligatures, combining marks, Arabic and CFF text extract correctly).

## Example

```scala
import scalaui.color.Color
import scalaui.font.FontFace
import scalaui.geom.*
import scalaui.pdf.*
import scalaui.raster.{Path, Stroke, LineCap, LineJoin}

val doc = new PdfDocument("Report", "scalaui")
val sans = doc.font(FontFace.notoSans)
val mono = doc.font(FontFace.hack)

doc.addPage() { p =>
  p.drawText("Hello, PDF from scalaui", sans, 24, 50, 80, Color.Black)
  p.drawText("AV To Wa kerning, café déjà vu", sans, 14, 50, 110, Color.rgb8(60, 60, 160))
  p.drawText("val x = 42", mono, 12, 50, 135, Color(0.8f, 0.1f, 0.1f))
  p.fillPath(Path.roundedRect(Rect(50, 160, 250, 260), 14), Color.rgb8(255, 160, 40))
  p.fillPath(Path.circle(Point(400, 210), 50), Color(0.1f, 0.5f, 0.9f, 0.5f))
  p.strokePath(Path.builder.moveTo(50, 300).cubicTo(150, 250, 250, 350, 500, 300).build, Color.Black, Stroke(3, LineCap.Round, LineJoin.Round))
}
doc.addPage(300, 200) { p => p.drawText("Page two", sans, 18, 20, 40, Color.Black) }

java.nio.file.Files.write(java.nio.file.Paths.get("report.pdf"), doc.toBytes)
```

::: note
Fonts must be TrueType (`glyf`) outlines to be subset-embedded: `FontFace.notoSans`, `hack`, `inter` and most system TrueType fonts work.
:::
