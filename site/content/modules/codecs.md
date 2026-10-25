---
title: codecs
section: Modules
order: 9
description: Read and write PNG, JPEG, BMP, GIF, TIFF and WebP images.
---
# codecs

`scalaui.codecs` decodes and encodes common image formats in pure Scala (the role of `image` and `zune-jpeg`). Decoders are checked against fixtures produced with ImageMagick, libwebp and libjpeg; the VP8 lossy decoder is a bit-exact port of libwebp's. Depends on [color](color.md) and [deflate](deflate.md).

```scala
import scalaui.codecs.*
```

## Image

```scala
final case class Image(width: Int, height: Int, rgba: Array[Byte])   // straight RGBA8, row-major
```

| Member | Description |
|---|---|
| `Image.decode(data)` | sniffs the header and decodes any supported format |
| `Image.decodeAnimation(data): Animation` | frames of GIF or animated WebP; other formats give one frame |
| `Image.encode(img, format, quality = 90): Array[Byte]` | `quality` (1..100) applies to JPEG only |
| `Image.iccProfile(data): Option[Array[Byte]]` | embedded ICC profile of a PNG, JPEG or WebP; parse with `cms.Profile.parse` |
| `Image.fromPremultiplied(w, h, bytes)` | build from premultiplied pixels (GPU layout) |
| `img.premultiplied` | premultiplied copy, the texture layout |
| `img.pixel(x, y)` | packed `0xRRGGBBAA` |

`ImageFormat` is `Png`, `Jpeg`, `Bmp`, `Gif`, `Tiff` or `Webp`. `Animation(width, height, frames: Vector[AnimationFrame], loopCount)` holds fully composited frames with `delayMs`. Decoders throw `ImageFormatException` on bad input.

## Per-format objects

| Object | Decode | Encode |
|---|---|---|
| `Png` | all color types, bit depths 1 to 16, Adam7 interlace, `tRNS` | `encode(img, level = 6)`: 8-bit RGBA (RGB when opaque), adaptive filtering |
| `Jpeg` | baseline and progressive Huffman, 8-bit, 1/3/4 components, any sampling factors, restart intervals, JFIF and Adobe (RGB, YCbCr, CMYK, YCCK); no arithmetic coding or 12-bit | `JpegEncoder.encode(img, quality = 85, subsample = true, grayscale = false, background = 0xFFFFFF)`: baseline; alpha is flattened onto `background` |
| `Bmp` | 1/4/8-bit palettes (RLE4, RLE8), 16/24/32-bit and bit-field formats, top-down and bottom-up | 24-bit when opaque, else 32-bit with alpha |
| `Gif` | GIF87a/89a, interlace, transparency, animation with disposal methods: `decode` (first frame), `decodeAnimation` | `encode`, `encodeAnimation(Animation)`: median-cut quantization to at most 255 colors plus transparency, no dithering (`Quantizer`) |
| `Tiff` | little and big endian, strips and tiles, chunky and planar, 1 to 16-bit gray, palette, RGB(A), CMYK; None, LZW, PackBits, Deflate; first page only | `encode(img, deflate = false)`: RGBA (RGB when opaque) |
| `Webp` | lossy (VP8), lossless (VP8L), alpha, animation | `WebpEncoder.encode(img)`: lossless only |

Each object also has `matches(bytes): Boolean` to check the signature.

## Example

```scala
import scalaui.codecs.*
import java.nio.file.{Files, Paths}

val img = Image.decode(Files.readAllBytes(Paths.get("photo.webp")))
println(s"${img.width}x${img.height}")

Files.write(Paths.get("photo.png"), Image.encode(img, ImageFormat.Png))
Files.write(Paths.get("photo.jpg"), Image.encode(img, ImageFormat.Jpeg, quality = 85))

val anim = Image.decodeAnimation(Files.readAllBytes(Paths.get("loop.gif")))
anim.frames.foreach(f => println(s"frame ${f.image.width}x${f.image.height} for ${f.delayMs} ms"))
```

::: note
Upload an image as a GPU texture with `ui.ctx.texture(img)` or `backend.createTexture(img.width, img.height, img.premultiplied)`: textures are premultiplied.
:::
