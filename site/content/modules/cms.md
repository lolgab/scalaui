---
title: cms
section: Modules
order: 3
description: ICC profile parsing and color transforms between profiles.
---
# cms

`scalaui.cms` is the color-management module (the `cms` half of the Rust `color / cms` pair): it parses ICC profiles (v2 and v4) and converts colors and pixel buffers between them. Depends on [color](color.md) and [geom](geom.md). Image conversion helpers live in [imaging](imaging.md) (`ColorManagement`), and `codecs` can extract the embedded profile from an image.

```scala
import scalaui.cms.*
```

## Profiles

```scala
val p = Profile.parse(bytes)              // throws CmsException for malformed profiles
p.colorSpace                              // "RGB ", "GRAY", "CMYK", ...
p.profileClass                            // "mntr", "scnr", "prtr", ...
p.pcs                                     // "XYZ " or "Lab "
p.version                                 // (major, minor)
p.description                             // Option[String] from the desc tag
p.whitePoint; p.blackPoint; p.chad
p.hasTag("rXYZ"); p.tags; p.tag("desc")   // raw tag access
p.canInput(); p.canOutput()               // can it convert device -> PCS / PCS -> device
p.toXyz(Array(r, g, b))                   // device values to PCS XYZ (D50)
p.fromXyz(x, y, z)                        // PCS XYZ to device values
```

Supported: ICC v2 and v4; matrix/TRC RGB profiles; gray TRC profiles; and A2B/B2A lookup-table profiles (`lut8`, `lut16` and `mAB` with curve, matrix and CLUT stages, tetrahedral CLUT interpolation) with XYZ or Lab connection space. All four rendering `Intent`s are available (`Perceptual`, `RelativeColorimetric`, `Saturation`, `AbsoluteColorimetric`), with optional black point compensation. The connection space is D50 with Bradford chromatic adaptation. The project reports the transforms were checked against littleCMS.

Built-in profiles are generated programmatically (no embedded binary data):

| Profile | Description |
|---|---|
| `Profile.sRGB` | sRGB IEC61966-2.1 |
| `Profile.displayP3` | Display P3 |
| `Profile.adobeRGB` | Adobe RGB (1998) |
| `Profile.linearSRGB` | sRGB primaries, linear |
| `Profiles.rec2020Linear` | Rec. 2020 primaries, linear |
| `Profiles.gray22` | gray, gamma 2.2 |

## Transform

```scala
final class Transform(src: Profile, dst: Profile, intent: Intent = Intent.Perceptual, blackPointCompensation: Boolean = false)
```

| Method | Purpose |
|---|---|
| `apply(r, g, b)` | one color, floats |
| `apply(in: Array[Double])` | one pixel with any channel count |
| `convert(src, dst, pixels)` | packed float pixels |
| `convert8(src, dst, pixels, srcStride, dstStride)` | 8-bit pixels; an alpha byte after the color channels is copied unchanged |
| `rgba8(src, dst, pixels)` | RGBA8 to RGBA8 |
| `inputChannels`, `outputChannels` | channel counts |

`Transform.convertColor(src, dst, (r, g, b))` is a one-off helper.

## Example

```scala
import scalaui.cms.*

val toP3 = new Transform(Profile.sRGB, Profile.displayP3, Intent.RelativeColorimetric, blackPointCompensation = true)
val (r, g, b) = toP3(0.9f, 0.2f, 0.1f)

// Convert an encoded image to sRGB using its embedded ICC profile
val img = scalaui.imaging.ColorManagement.toSrgb(fileBytes)
```

## Writing profiles

`ProfileWriter` creates profile bytes: `fromPrimaries(description, r, g, b, white, trc, ...)`, `matrixTrc(...)` and `gray(description, trc)`, with `Curve` types `Identity`, `Gamma(g)`, `Table(Array[Float])` and `Parametric(type, params)`.
