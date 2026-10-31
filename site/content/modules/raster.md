---
title: raster
section: Modules
order: 7
description: CPU path rasterizer: paths, fills, strokes, gradients and the Pixmap type.
---
# raster

`scalaui.raster` is a CPU vector rasterizer with analytic anti-aliasing (the role of vello_cpu and tiny-skia). It draws into a floating-point premultiplied `Pixmap`, and it is what the software GPU backend, the SVG renderer and the offscreen filters build on. Depends on [geom](geom.md) and [color](color.md).

```scala
import scalaui.raster.*
```

## Paths

```scala
val p = Path.builder.moveTo(10, 10).lineTo(100, 10).quadTo(120, 50, 100, 90).cubicTo(80, 120, 20, 120, 10, 90).close().build
```

`Path` is a sequence of `PathEl` (`MoveTo`, `LineTo`, `QuadTo`, `CubicTo`, `Close`). Shortcuts: `Path.rect(Rect)`, `Path.roundedRect(Rect, radius)`, `Path.circle(Point, radius)`, `Path.ellipse(Point, rx, ry)`. `path.transform(Affine)` returns a transformed copy and `path.flatten(tolerance = 0.1)` approximates curves with polylines. `FillRule` is `NonZero` or `EvenOdd`.

## Pixmap

```scala
final class Pixmap(val width: Int, val height: Int):
  val data: Array[Float]                 // premultiplied RGBA, 4 floats per pixel
  def clear(c: Color): Unit
  def fill(path: Path, paint: Paint, rule: FillRule = FillRule.NonZero, transform: Affine = Affine.Identity): Unit
  def stroke(path: Path, paint: Paint, style: Stroke, transform: Affine = Affine.Identity): Unit
  def get(x: Int, y: Int): PremulColor
  def blend(x: Int, y: Int, src: PremulColor, coverage: Float): Unit
  def toRgba8: Array[Byte]               // straight alpha
  def toPremulRgba8: Array[Byte]
```

## Paint and Stroke

| Type | Description |
|---|---|
| `Paint.Solid(color)` or `Paint.solid(c)` | flat color |
| `Paint.LinearGradient(from, to, stops)` | `stops` is `Seq[(Float, Color)]` |
| `Paint.RadialGradient(center, radius, stops)` | |
| `Stroke(width, cap = LineCap.Butt, join = LineJoin.Miter, miterLimit = 4.0)` | caps `Butt`, `Square`, `Round`; joins `Miter`, `Bevel`, `Round` |

`Rasterizer.fill` and `Stroker.stroke` are the low-level pieces (scanline coverage callback and stroke outline generation).

## Example

```scala
import scalaui.color.Color
import scalaui.geom.*
import scalaui.raster.*

val pm = new Pixmap(256, 256)
pm.clear(Color.White)

pm.fill(Path.roundedRect(Rect(20, 20, 236, 140), 18),
  Paint.LinearGradient(Point(20, 20), Point(236, 140), Seq((0f, Color.rgb8(255, 122, 89)), (1f, Color.rgb8(122, 92, 255)))))

pm.stroke(Path.circle(Point(128, 190), 40), Paint.Solid(Color.Black), Stroke(4, LineCap.Round, LineJoin.Round))

val png = scalaui.codecs.Png.encode(scalaui.imaging.Pixmaps.toImage(pm))   // needs the codecs and imaging modules
```
