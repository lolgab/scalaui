---
title: imaging
section: Modules
order: 8
description: Blend modes, filters, resampling, offscreen layers and color-managed conversion on pixmaps.
---
# imaging

`scalaui.imaging` holds image operations on `raster.Pixmap` (the image-ops role). Depends on [raster](raster.md), [codecs](codecs.md), [color](color.md), [cms](cms.md) and [font](font.md).

```scala
import scalaui.imaging.*
```

## Converting between Image and Pixmap

`Pixmaps.fromImage(img: Image): Pixmap`, `Pixmaps.toImage(pm): Image`, `Pixmaps.copy(pm)`.

## Blend

`Blend.composite(dst, src, ox = 0, oy = 0, mode = BlendMode.Normal, opacity = 1f)` composites `src` onto `dst` with Porter-Duff source-over and the W3C blend functions. `BlendMode` has 16 values: `Normal`, `Multiply`, `Screen`, `Overlay`, `Darken`, `Lighten`, `ColorDodge`, `ColorBurn`, `HardLight`, `SoftLight`, `Difference`, `Exclusion`, `Hue`, `Saturation`, `Color`, `Luminosity`.

## Filters

All in-place filters take the pixmap first.

| Function | Description |
|---|---|
| `gaussianBlur(src, sigma): Pixmap` | returns a new pixmap |
| `brightness(pm, amount)`, `contrast(pm, factor)`, `gamma(pm, g)` | |
| `invert`, `grayscale`, `saturation(pm, factor)`, `hueRotate(pm, degrees)` | |
| `levels(pm, black, white, gamma = 1f)` | |
| `curve(pm, lut: Array[Float])` | per-channel lookup table |
| `convolve3(src, kernel: Array[Float])` | 3x3 convolution; `Filters.Sharpen` kernel provided |
| `mapRgb(pm)(f)` | custom per-pixel RGB function |
| `histogram(pm): Array[Array[Int]]` | |

## Resample

`Resample.resize(src, w, h, filter = ResizeFilter.Lanczos3)` (filters: `Nearest`, `Bilinear`, `Lanczos3`), `flipHorizontal`, `flipVertical`, `rotate90(src, quarterTurns)`, `crop(src, x, y, w, h)`.

## Layers

`Layers.layer(dst, opacity, mode, clip, rule, transform, mask)(draw)` runs `draw` on a transparent pixmap of `dst`'s size and composites it back with opacity, blend mode, an optional anti-aliased clip path and an optional alpha mask (the equivalent of vello's `push_layer` and `pop_layer`).

## Colour glyphs

`ColorGlyphs.draw(dst, face, glyph, m, palette = 0, foreground = Black, largestStrike = false): Boolean` draws a colour glyph (COLR v0/v1 paint graph or `sbix`/`CBDT` bitmap) onto a pixmap; `m` maps font units (y up) to pixels. It implements solid, linear, two-circle radial and sweep gradients with pad/repeat/reflect extend (the first and last stop delimit the repeated span, as in COLRv1), clipping glyphs, transforms, clip boxes and all 28 composite modes (Porter-Duff operators and the blend modes). Bitmaps are decoded, box-filtered when they shrink a lot and sampled bilinearly. `ColorGlyphs.render(face, glyph, pixelSize, palette, foreground, rotated)` rasterizes one glyph alone (`Rendered(pixmap, left, top)`), and `bounds` and `hasColor` support layout code. `SVG ` table glyphs are rendered by the [svg](svg.md) module.

Verified against BlackRenderer's Skia backend: 230 colour glyphs of the googlefonts `color-fonts` test font (every paint format, extend mode and composite mode), Noto Color Emoji subsets and a synthetic COLRv0 font are within a mean error of 4/255 of the references (typical: under 0.5; the clip-box glyphs differ at the edges), sbix and CBDT bitmaps against Pillow. `ColorGlyphSuite` checks 66 of the references committed under `src/test/resources/color-ref`; `tools/color_glyph_ref.py` and `tools/color_glyph_compare.py` regenerate and compare all of them.

## Color management

`ColorManagement.convert(pm | img, from, to, intent, blackPointCompensation)` converts between RGB ICC profiles, and `ColorManagement.toSrgb(encodedBytes)` decodes an image and converts it to sRGB using its embedded profile (PNG `iCCP`, JPEG `APP2`, WebP `ICCP`) when there is one. See [cms](cms.md).

## Example

```scala
import scalaui.codecs.Image
import scalaui.imaging.*

val src = Pixmaps.fromImage(Image.decode(bytes))
val thumb = Resample.resize(src, 128, 128 * src.height / src.width)
Filters.saturation(thumb, 1.3f)
val blurred = Filters.gaussianBlur(thumb, 2.0)

val canvas = new scalaui.raster.Pixmap(256, 256)
Blend.composite(canvas, blurred, 10, 10, BlendMode.Multiply, opacity = 0.8f)

val jpeg = Image.encode(Pixmaps.toImage(canvas), scalaui.codecs.ImageFormat.Jpeg, quality = 85)
```
