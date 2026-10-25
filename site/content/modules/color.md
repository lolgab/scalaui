---
title: color
section: Modules
order: 2
description: sRGB colors with float channels, premultiplied compositing and HSV.
---
# color

`scalaui.color` provides the `Color` type used by every drawing API. Depends on [geom](geom.md). For ICC profile conversion see [cms](cms.md).

```scala
import scalaui.color.*
```

## Color

```scala
final case class Color(r: Float, g: Float, b: Float, a: Float = 1f)
```

Channels are straight (non-premultiplied) sRGB floats in `0..1`.

| Member | Description |
|---|---|
| `Color.rgb8(r, g, b, a = 255)` | from 0..255 integers |
| `Color.fromArgb(argb: Int)` | from `0xAARRGGBB` |
| `Color.hex(s): Option[Color]` | parses `#rgb`, `#rrggbb` or `#rrggbbaa` |
| `Color.Transparent`, `Black`, `White` | constants |
| `c.withAlpha(a)` | copy with alpha |
| `c.toArgb: Int` | packed, clamped and rounded |
| `c.premultiplied: PremulColor` | for compositing |
| `Color.toHsv(c)` / `Color.fromHsv(h, s, v, a)` | hue in degrees, `s` and `v` in 0..1 |
| `Color.srgbToLinear(x)` / `linearToSrgb(x)` | transfer functions for one channel |

## PremulColor

```scala
final case class PremulColor(r: Float, g: Float, b: Float, a: Float)
```

`over(dst)` is Porter-Duff source-over, `scaled(s)` multiplies all channels, `unpremultiplied` converts back. The CPU rasterizer and GPU textures work in premultiplied space.

## Example

```scala
import scalaui.color.*

val orange = Color.rgb8(255, 140, 0)
val translucent = orange.withAlpha(0.5f)
val over = translucent.premultiplied.over(Color.White.premultiplied).unpremultiplied

val (h, s, v) = Color.toHsv(orange)
val shifted = Color.fromHsv((h + 30) % 360, s, v)

val parsed = Color.hex("#7a5cff")      // Some(Color(...))
val lin = Color.srgbToLinear(0.5f)     // ~0.214
```
