---
title: shaping
section: Modules
order: 13
description: OpenType shaping with GSUB, GPOS, complex scripts and font fallback, verified against HarfBuzz.
---
# shaping

`scalaui.shaping` turns text into positioned glyphs using the font's own OpenType tables (the role of harfrust). The engine was ported from HarfBuzz and its output is verified against `hb-shape` on about 2200 golden cases. Depends on [font](font.md) and [unicode](unicode.md).

```scala
import scalaui.shaping.*
```

## Shaper

```scala
final class Shaper(val fonts: Seq[FontFace]):
  def this(font: FontFace)
  val font: FontFace        // primary face, used for line metrics
  def shape(text: String, size: Double, rtl: Boolean = false, features: Seq[FontFeature] = Nil,
            language: Option[String] = None): Vector[ShapedGlyph]
```

`fonts` is a fallback chain: each character uses the first face that has a glyph for it. `size` is the em height in pixels and advances are in pixels. Glyphs are returned in **logical order** (cluster indices ascend for left-to-right and right-to-left runs alike); reorder visually with the [text](text.md) module. With `rtl = true`, mirrored characters such as brackets use their mirror image.

```scala
final case class ShapedGlyph(glyph: Int, cluster: Int, xAdvance: Double,
                             xOffset: Double = 0, yOffset: Double = 0, font: FontFace = null)
```

`cluster` is the UTF-16 index in the source text of the code point the glyph came from. Controls (except tab) and variation selectors produce no glyph.

## Features and languages

`FontFeature(tag, value = 1)` switches an OpenType feature on (`value > 0`) or off (`0`) on top of the script defaults, and `language` picks an OpenType language system tag:

```scala
val sans = new Shaper(FontFace.notoSans)
val glyphs = sans.shape("office fi", 16)
val noLigatures = sans.shape("office fi", 16, features = Seq(FontFeature("liga", 0), FontFeature("kern", 1)))
val turkish = sans.shape("iI", 16, language = Some("TRK "))
```

## What is implemented

- cmap lookup, canonical normalization against the font, default-ignorable handling.
- GSUB lookup types 1 to 8 and GPOS lookup types 1 to 8 (kerning, mark-to-base, mark-to-ligature, mark-to-mark, cursive attachment, contextual rules).
- Complex-script shapers: Arabic joining, Indic (9 scripts), the Universal Shaping Engine (Sinhala, Tibetan, Javanese and others), Khmer, Myanmar, Thai and Lao, Hangul.
- Per-character font fallback.

`shape(..., vertical = true)` shapes top to bottom (`vert` feature, vmtx advances, VORG origins, vertical GPOS). Known gaps: Lao stacks of three or more marks, Arabic reordering of marks after a space, Zawgyi. Hangul and Hebrew composition paths have no oracle font and are untested.

## Example

```scala
import scalaui.font.FontFace
import scalaui.shaping.*

val shaper = new Shaper(Seq(FontFace.notoSans, FontFace.hack))   // Hack fills in box-drawing glyphs
val run = shaper.shape("AV To Wa ─ │", 18)
val width = run.map(_.xAdvance).sum
run.foreach(g => println(s"glyph ${g.glyph} cluster ${g.cluster} adv ${g.xAdvance}"))
```
