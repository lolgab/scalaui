---
title: text
section: Modules
order: 14
description: Paragraph layout with bidi, line breaking, rich text, carets, hit testing and a GPU glyph atlas.
---
# text

`scalaui.text` lays out paragraphs and draws them (the role of parley). Layout runs bidi analysis, script itemization, shaping, UAX #14 line breaking and visual reordering. Depends on [shaping](shaping.md), [raster](raster.md), [gpu](gpu.md) and [unicode](unicode.md).

```scala
import scalaui.text.*
```

## TextLayout

```scala
object TextLayout:
  def layout(text: String, shaper: Shaper, size: Double, maxWidth: Option[Double] = None, align: Align = Align.Left,
             lineHeightScale: Double = 1.0, direction: Int = Bidi.Auto, features: Seq[FontFeature] = Nil,
             language: Option[String] = None, tracking: Double = 0.0): TextLayout
```

`Align` is `Left`, `Center` or `Right`. `maxWidth = None` lays out on one line (plus explicit newlines); with a width, text wraps at line-break opportunities. `direction` is `Bidi.Ltr`, `Bidi.Rtl` or `Bidi.Auto` (first strong character).

A `TextLayout` holds `lines: Vector[Line]`, `width` and `height`. Each `Line` has `start`/`end` (UTF-16 range), `glyphs` in visual order (`GlyphPos(glyph, x, y, cluster, advance, font, end, level)`), and `x`, `top`, `baseline`, `height`, `width`.

| Method | Description |
|---|---|
| `caret(index): (x, top, height)` | caret rectangle for a UTF-16 index, bidi-aware |
| `indexAt(x, y): Int` | hit test; closest index to a point |
| `moveVisual(index, toRight): Int` | visual arrow-key movement in bidirectional text; `-1` means "use logical movement" |
| `selectionRects(from, to)` | highlight rectangles, one per contiguous visual stretch |

## Rich text

```scala
final case class TextStyle(shaper: Shaper, size: Double, color: Color = Color.Black, underline: Boolean = false,
    strikethrough: Boolean = false, background: Option[Color] = None, letterSpacing: Double = 0.0,
    baselineShift: Double = 0.0, features: Seq[FontFeature] = Nil, language: Option[String] = None)
final case class Span(text: String, style: TextStyle)

RichLayout.layout(spans: Seq[Span], maxWidth: Option[Double] = None, align: Align = Align.Left,
                  lineSpacing: Double = 1.0, direction: Int = Bidi.Auto): RichLayout
```

`RichLayout` offers the same `caret`, `indexAt`, `moveVisual` and `selectionRects` over the concatenated span text.

## Drawing

```scala
final class GlyphAtlas(backend: GpuBackend, val size: Int = 2048)    // glyph coverage texture, rasterized on demand
final class TextRenderer(val atlas: GlyphAtlas):
  def draw(canvas: Canvas, layout: TextLayout, origin: Point, color: Color): Unit
  def drawRich(canvas: Canvas, layout: RichLayout, origin: Point): Unit
```

Glyphs are rasterized by the CPU rasterizer at the requested pixel size, packed into a shelf atlas stored as white RGBA, and drawn as textured quads tinted by the text color.

Colour glyphs (COLR v0/v1 layers, `sbix` and `CBDT` bitmaps; see [font](font.md)) take a separate path: `GlyphAtlas.get` rasterizes them in full colour with `ColorGlyphs` and stores them premultiplied (`AtlasGlyph.color`), and the renderers draw them with the text color's opacity only (`AtlasGlyph.tint`). "Foreground" palette entries use the text color, which is part of the cache key for COLR glyphs. This works for `draw`, `drawVertical` (rotated like other sideways glyphs) and `drawRich`. `SVG `-table-only glyphs are not drawn.

## Vertical text

`TextLayout.layoutVertical(text, shaper, size, maxHeight, align, lineHeightScale, features, language, tracking): VerticalLayout` lays text out in columns that run right to left, glyphs top to bottom: UAX #50 decides per character whether it stands upright (shaped with `vert`/vmtx/VORG) or lies sideways (shaped horizontally and rotated 90 degrees clockwise). `TextRenderer.drawVertical` draws it. The [svg](svg.md) module uses the same orientation rules for `writing-mode`.

## Example

```scala
import scalaui.color.Color
import scalaui.font.FontFace
import scalaui.geom.Point
import scalaui.gpu.*
import scalaui.shaping.Shaper
import scalaui.text.*
import scalaui.window.*

val win = Window.create("text", 600, 300)
val gpu = GpuBackend.create(win)
val canvas = new Canvas(gpu.whiteTexture)
val renderer = new TextRenderer(new GlyphAtlas(gpu))
val sans = new Shaper(FontFace.notoSans)

val para = TextLayout.layout("The quick brown fox jumps over the lazy dog. שלום עולם", sans, 16, Some(260))
val (cx, cy, ch) = para.caret(5)

var running = true
while running do
  win.pollEvents().foreach { case Event.CloseRequested => running = false; case _ => () }
  canvas.begin(win.width, win.height, win.scaleFactor, Color.rgb8(30, 32, 40))
  renderer.draw(canvas, para, Point(20, 20), Color.White)
  canvas.fillRect(scalaui.geom.Rect(20 + cx, 20 + cy, 22 + cx, 20 + cy + ch), Color.rgb8(255, 140, 0))
  gpu.submit(canvas.finish())
  Thread.sleep(16)
win.close()
```
