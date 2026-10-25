---
title: font
section: Modules
order: 12
description: TrueType, CFF, WOFF and WOFF2 font parsing, colour glyph tables (COLR/CPAL, sbix, CBDT, SVG), font families, system font discovery and bundled fonts.
---
# font

`scalaui.font` parses font files and exposes metrics, character mapping and glyph outlines (the role of skrifa and wuff). It bundles Noto Sans, Hack, Inter and JetBrains Mono. Depends on [geom](geom.md), [raster](raster.md), [deflate](deflate.md) and [brotli](brotli.md).

```scala
import scalaui.font.*
```

## FontFace

```scala
val face = FontFace(Files.readAllBytes(Paths.get("MyFont.ttf")))   // sniffs sfnt, ttc, WOFF and WOFF2
```

| Member | Description |
|---|---|
| `FontFace(data, index = 0)` | parse a font; for `.ttc` collections `index` picks the face (`FontFace.collectionSize(data)` counts them) |
| `FontFace.fromWoff(bytes)` | WOFF 1.0 (zlib-compressed tables) |
| `FontFace.fromWoff2(bytes)` | WOFF 2.0 (Brotli, with the `glyf`, `loca` and `hmtx` transforms undone); collections are not supported |
| `unitsPerEm`, `numGlyphs`, `ascent`, `descent`, `lineGap` | metrics in font units |
| `glyphIndex(cp)`, `hasGlyph(cp)` | `cmap` lookup (glyph 0 means missing) |
| `advance(glyph)`, `leftSideBearing(glyph)` | horizontal metrics in font units |
| `outline(glyph): Path` | glyph outline as a `raster.Path` (quadratic for TrueType, cubic for CFF), cached |
| `familyName`, `styleName`, `fullName`, `weight`, `isBold`, `isItalic`, `isMonospace` | naming and style |
| `isTrueType` | has `glyf` outlines (required by the [pdf](pdf.md) subsetter) |
| `hasTable(tag)`, `rawTable(tag)`, `tableRange(tag)` | raw table access, used by [shaping](shaping.md) |

Outlines come from TrueType `glyf` (simple and composite glyphs) or CFF charstrings (OpenType `.otf`).

## Colour glyphs

`FontFace` parses `CPAL`, `COLR` (v0 layers and v1 paint graphs, including `ClipList`), `sbix`, `CBDT`/`CBLC` and `SVG `. Fonts without outlines (CBDT-only fonts) are fine: `outline` returns an empty path.

| Member | Description |
|---|---|
| `hasColorGlyphs`, `isColorGlyph(glyph)` | any colour source is present / this glyph has one |
| `paletteCount`, `palette(index): Vector[Color]` | CPAL palettes |
| `hasLayeredColor(glyph)` | a COLR entry exists |
| `colorGlyph(glyph, palette = 0, foreground = Black): Option[ColorPaint]` | the resolved COLR paint graph (palette indices replaced by colours, `0xFFFF` = foreground) |
| `bitmapSizes`, `bitmapGlyph(glyph, ppem): Option[BitmapGlyph]` | `sbix` / `CBDT` strikes: PNG (or JPEG/TIFF in sbix) bytes with bearings, from the strike closest to `ppem` (larger preferred) |
| `svgGlyph(glyph): Option[SvgGlyphDoc]` | the (gunzipped) `SVG ` document that holds the glyph; the glyph is the element `id="glyph<gid>"` |

`ColorPaint` is `Solid`, `LinearGradient`, `RadialGradient`, `SweepGradient`, `Glyph(gid, paint)` (fill the outline with a paint), `Layers`, `Transform` (translate, scale, rotate, skew and `PaintTransform` all become an `Affine`), `Clip` and `Composite(mode, source, backdrop)`. COLRv0 lists become `Layers` of `Glyph(Solid)`. `PaintColrGlyph` references are inlined (cycles are cut). Variable COLRv1 fonts use their default instance (variation deltas are not applied). Rasterizing is in [imaging](imaging.md) (`ColorGlyphs`); [text](text.md), [svg](svg.md) and [pdf](pdf.md) draw colour glyphs with it.

## WOFF and WOFF2

`FontFace(bytes)` detects the signature, so web fonts need no special handling. Both formats are unpacked into a plain sfnt in memory. WOFF2 needs the `brotli` module's static dictionary resource, so native builds need `withEmbedResources(true)`.

```scala
val web = FontFace(Files.readAllBytes(Paths.get("font.woff2")))
println(web.fullName)
```

## Bundled fonts

Bundled fonts are classpath resources, so set `nativeConfig.withEmbedResources(true)`.

| `FontFace` | Description |
|---|---|
| `notoSans`, `notoSansBold`, `notoSansItalic`, `notoSansBoldItalic` | Noto Sans, broad Latin, Greek and Cyrillic coverage |
| `hack`, `hackBold` | Hack monospace |
| `inter`, `interMedium`, `interSemiBold` | Inter, the default UI font |
| `jetBrainsMono` | JetBrains Mono |
| `FontFace.bundled(name)` | any resource under `fonts/` |

Licenses (SIL OFL) are bundled next to the fonts.

## FontFamily

```scala
final class FontFamily(val name: String, val regular: FontFace, bold: Option[FontFace] = None,
                       italic: Option[FontFace] = None, boldItalic: Option[FontFace] = None):
  def apply(isBold: Boolean, isItalic: Boolean): FontFace   // falls back through the available styles
```

Predefined: `FontFamily.NotoSans`, `Hack`, `Inter`, `InterMedium`, `InterSemiBold`, `JetBrainsMono`.

## SystemFonts

Finds installed fonts in the standard directories of the OS (scanned lazily, once).

```scala
SystemFonts.directories            // existing font directories
SystemFonts.families               // sorted family names
SystemFonts.family("Helvetica")    // Option[FontFamily] with regular/bold/italic/bold italic
```

## Example

```scala
import scalaui.font.*

val face = FontFace.notoSans
val g = face.glyphIndex('A')
val advancePx = face.advance(g) * 16.0 / face.unitsPerEm      // advance at 16 px
val outline = face.outline(g)                                  // raster.Path, y-up font units

val fam = SystemFonts.family("Arial").getOrElse(FontFamily.NotoSans)
val boldItalic = fam(isBold = true, isItalic = true)
```
