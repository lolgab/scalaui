---
title: svg
section: Modules
order: 10
description: An SVG renderer (usvg/resvg role) on the CPU rasterizer.
---
# svg

`scalaui.svg` parses and renders SVG to a `raster.Pixmap`; the `ui` module uses it for icons and `Ui.svg`. XML is parsed with scala-xml's `ConstructingParser`. Depends on [raster](raster.md), [imaging](imaging.md) (layers, blending), [codecs](codecs.md), [font](font.md), [shaping](shaping.md) and [deflate](deflate.md) (svgz).

```scala
val doc = SvgDocument.parse(svgText, SvgOptions(resourceDir = Some("assets")))
val pm = doc.render(256, 256, Some(Color.White))   // fit into 256x256
val pm2 = doc.renderScaled(2.0)                    // natural size x 2, canvas rounded up like resvg
```

## Supported

- Structure: `g`, `a`, `switch` (`systemLanguage`), `use`, `symbol`, nested `svg` with `viewBox`/`preserveAspectRatio`, `transform-origin`, `display`, `overflow`.
- CSS: `<style>` with type/class/id/attribute selectors, combinators, `:first-child`, specificity, `!important`, `style=""`; only presentation properties are accepted.
- Paint: colors (all CSS forms), `currentColor`, gradients (`spreadMethod`, `gradientTransform`, focal point, `href` inheritance, stops with opacity), patterns, `context-fill`/`context-stroke`, fallbacks.
- Strokes: dashes, caps, joins, miter limit, `paint-order`; markers (`orient`, `markerUnits`, viewBox, clipping).
- `clipPath`, `mask` (luminance or alpha), opacity groups, `mix-blend-mode`, `isolation`; recursive references are broken like resvg.
- `<image>`: PNG, JPEG, GIF, WebP, BMP, TIFF and nested SVG/SVGZ from data URIs or files.
- Text: `tspan`, `tref`, `textPath`, `x/y/dx/dy/rotate`, `text-anchor`, decorations, baseline properties, `textLength`, letter/word spacing; fonts from `SvgOptions.fonts`, the bundled Noto Sans/Hack/Inter and (optionally) system fonts, shaped with the OpenType engine.
- Vertical writing: `writing-mode` `tb`, `tb-rl`, `vertical-rl` and `vertical-lr` (one column, as in resvg; read from the `text` element or an ancestor, ignored on `tspan`) with UAX #50 orientation: upright CJK is shaped with vertical metrics (`vert`, vmtx, VORG) and centred on the axis, Latin and other scripts lie sideways (rotated 90 degrees clockwise, em box centred on `x`). `text-orientation` (`mixed`, `upright`, `sideways`) and the legacy `glyph-orientation-vertical` (`0` = upright, `90` = sideways, `auto` = mixed) override it. `x`/`y`/`dx`/`dy`, `rotate`, `text-anchor`, `textLength`, `letter-spacing` and decorations follow the column (underline left, overline right of the glyphs); text on a path keeps horizontal layout with upright/sideways glyph orientation, as resvg does.
- Colour fonts: COLR v0/v1 (all paint formats), `sbix`, `CBDT` and the OpenType `SVG ` table are drawn in colour; `fill` supplies the "foreground" palette colour and `fill-opacity` fades the glyph. Bitmap emoji pick the largest strike. Fonts passed in `SvgOptions.fonts` also act as fallbacks for characters the named families lack.
- Text chunks: every character with an absolute `x`/`y` starts a new chunk that is shaped on its own (no ligature across it), lists address code points (a surrogate pair takes one entry) and `text-anchor` aligns the extent of the glyph cells, as resvg does.
- Filters: all `fe*` primitives including lighting, turbulence, convolve, morphology, displacement, tile, image, drop shadow, and the CSS filter functions.

## Not supported

`enable-background`, `FillPaint`/`StrokePaint` inputs, bidi reordering inside one text run beyond the shaper (also inside vertical text, where resvg reorders right-to-left runs), `font-variant: small-caps`, vertical text on a path with real vertical metrics (it uses the horizontal layout), per-glyph underline pieces for rotated vertical glyphs, resvg's outline-bbox shift of `sbix` bitmaps.

## Verification

`tools/run_svg_corpus.sh` renders a corpus and `tools/svg_compare.py` compares against resvg renders (ImageMagick). On resvg's own suite (1748 cases at 300 px, with its test fonts) 1648 match within 1% differing pixels. `ResvgSuite` checks 289 committed cases (`src/test/resources/resvg`) on every test run. `ResvgTextSuite` checks 38 more text cases that need extra fonts (`resvg-text`, fonts in `resvg-fonts`): all 24 `writing-mode` cases, the vertical `lengthAdjust`, `direction`, `alignment-baseline` and `textPath` cases and the `color-font` cases (COLR v0/v1, CBDT, `SVG `, coordinate lists, `writing-mode=tb`) by ink overlap (95% within 2 px each way; four cases that deviate on purpose have lower limits listed in the suite). With the full text corpus text/writing-mode went from 13/24 to 24/24 and text/color-font from 4/10 to 9/10 (the exception is `sbix`: resvg shifts the bitmap by a macOS heuristic); `text/glyph-orientation-vertical` (`glyph-orientation-vertical="0"`) renders upright where resvg and Chromium ignore the property; it stays inside the 1% limit only because the page is nearly empty. Own tests: `VerticalTextSuite`, `ColorFontSuite`.
