package scalaui.text

import scalaui.geom.*
import scalaui.gpu.{Canvas, GpuBackend, Texture}
import scalaui.color.Color
import scalaui.raster.{Paint, Pixmap}
import scalaui.font.FontFace
import scalaui.imaging.ColorGlyphs
import scala.collection.mutable

/** Where a rasterized glyph lives in the atlas. `left`/`top` are pixel offsets from the glyph origin (baseline, y up for `top`). */
final case class AtlasGlyph(u0: Double, v0: Double, u1: Double, v1: Double, left: Int, top: Int, w: Int, h: Int, color: Boolean = false):
  /** The tint to draw with: coverage glyphs take the text colour, colour glyphs (emoji) keep their own colours and only its opacity. */
  def tint(text: Color): Color = if color then Color(1f, 1f, 1f, text.a) else text

/** Shelf-packed glyph coverage atlas stored as premultiplied white RGBA (coverage in all channels),
  * so vertex color tints it. Glyphs are rasterized on demand with the CPU rasterizer at pixel size.
  */
final class GlyphAtlas(backend: GpuBackend, val size: Int = 2048):
  val texture: Texture = backend.createTexture(size, size, new Array[Byte](size * size * 4))
  private case class Key(font: FontFace, glyph: Int, px: Int, rotated: Boolean, fg: Int)
  private val cache = mutable.HashMap.empty[Key, Option[AtlasGlyph]]
  private var shelfX = 1; private var shelfY = 1; private var shelfH = 0

  /** With `rotated` the glyph is rasterized turned 90 degrees clockwise about its origin (sideways glyphs in vertical text);
    * `left`/`top` then refer to the rotated bitmap.
    */
  def get(font: FontFace, glyph: Int, pixelSize: Double, rotated: Boolean = false, foreground: Color = Color.Black): Option[AtlasGlyph] =
    // colour glyphs (COLR layers, sbix/CBDT bitmaps) are rasterized in full colour; their palette's "text colour" entry is the foreground
    val isColor = font.hasColorGlyphs && ColorGlyphs.hasColor(font, glyph)
    val fg = if isColor && font.hasLayeredColor(glyph) then Color(foreground.r, foreground.g, foreground.b, 1f).hashCode else 0
    cache.getOrElseUpdate(Key(font, glyph, math.round(pixelSize * 4).toInt, rotated, fg),
      if isColor then rasterizeColor(font, glyph, pixelSize, rotated, foreground).orElse(rasterize(font, glyph, pixelSize, rotated)) else rasterize(font, glyph, pixelSize, rotated))

  private def rasterizeColor(font: FontFace, glyph: Int, pixelSize: Double, rotated: Boolean, foreground: Color): Option[AtlasGlyph] =
    ColorGlyphs.render(font, glyph, pixelSize, 0, Color(foreground.r, foreground.g, foreground.b, 1f), rotated, size).flatMap { r =>
      val bytes = r.pixmap.toPremulRgba8
      place(bytes, r.pixmap.width, r.pixmap.height, r.left, r.top, color = true)
    }

  private def rasterize(font: FontFace, glyph: Int, pixelSize: Double, rotated: Boolean): Option[AtlasGlyph] =
    val s = pixelSize / font.unitsPerEm
    val path = font.outline(glyph)
    if path.elements.isEmpty then return None
    val pts0 = path.flatten(0.5 / s).flatMap(_._1)
    // pixel offsets (y down) from the glyph origin: upright (s*x, -s*y); rotated 90 degrees clockwise (s*y, s*x)
    val pts = if rotated then pts0.map(p => Point(p.y * s, p.x * s)) else pts0.map(p => Point(p.x * s, -p.y * s))
    val minX = pts.map(_.x).min; val maxX = pts.map(_.x).max
    val minY = pts.map(_.y).min; val maxY = pts.map(_.y).max
    val left = math.floor(minX).toInt - 1; val top = 1 - math.floor(minY).toInt // origin is `top` pixels below the bitmap's top edge
    val w = math.ceil(maxX).toInt + 1 - left; val h = math.ceil(maxY).toInt + 1 + top
    if w <= 0 || h <= 0 || w > size || h > size then return None
    val pm = new Pixmap(w, h)
    // font units (y up) -> pixmap pixels (y down), glyph origin at (-left, top)
    pm.fill(path, Paint.solid(Color.White), transform = if rotated then Affine(0, s, s, 0, -left.toDouble, top.toDouble) else Affine(s, 0, 0, -s, -left.toDouble, top.toDouble))
    val bytes = new Array[Byte](w * h * 4)
    var i = 0
    while i < w * h do
      val a = (pm.data(i * 4 + 3).max(0f).min(1f) * 255f + 0.5f).toInt.toByte
      bytes(i * 4) = a; bytes(i * 4 + 1) = a; bytes(i * 4 + 2) = a; bytes(i * 4 + 3) = a
      i += 1
    place(bytes, w, h, left, top, color = false)

  /** Packs premultiplied RGBA `bytes` into the shelf atlas. */
  private def place(bytes: Array[Byte], w: Int, h: Int, left: Int, top: Int, color: Boolean): Option[AtlasGlyph] =
    if shelfX + w + 1 > size then { shelfX = 1; shelfY += shelfH + 1; shelfH = 0 }
    if shelfY + h + 1 > size then
      // atlas full: start over (callers re-request glyphs every frame, so this self-heals)
      cache.clear(); shelfX = 1; shelfY = 1; shelfH = 0
      texture.update(0, 0, size, size, new Array[Byte](size * size * 4))
    val x = shelfX; val y = shelfY
    texture.update(x, y, w, h, bytes)
    shelfX += w + 1; shelfH = math.max(shelfH, h)
    Some(AtlasGlyph(x.toDouble / size, y.toDouble / size, (x + w).toDouble / size, (y + h).toDouble / size, left, top, w, h, color))

/** Draws [[TextLayout]]s onto a [[Canvas]] using a [[GlyphAtlas]]. */
final class TextRenderer(val atlas: GlyphAtlas):
  /** Draws `layout` with its top-left at `origin` (logical points); glyphs snap to the device pixel grid. */
  def draw(canvas: Canvas, layout: TextLayout, origin: Point, color: Color): Unit =
    val scale = canvas.scaleFactor
    val px = layout.size * scale
    layout.lines.foreach { line =>
      line.glyphs.foreach { g =>
        atlas.get(g.font, g.glyph, px, foreground = color).foreach { ag =>
          val gx = math.round((origin.x + g.x) * scale).toDouble
          val gy = math.round((origin.y + g.y) * scale).toDouble
          val dst = Rect((gx + ag.left) / scale, (gy - ag.top) / scale, (gx + ag.left + ag.w) / scale, (gy - ag.top + ag.h) / scale)
          canvas.drawImage(atlas.texture, dst, Rect(ag.u0, ag.v0, ag.u1, ag.v1), ag.tint(color))
        }
      }
    }

  /** Draws a [[VerticalLayout]] with its top-left at `origin`; sideways glyphs are drawn rotated 90 degrees clockwise. */
  def drawVertical(canvas: Canvas, layout: VerticalLayout, origin: Point, color: Color): Unit =
    val scale = canvas.scaleFactor
    val px = layout.size * scale
    layout.columns.foreach { col =>
      col.glyphs.foreach { g =>
        atlas.get(g.font, g.glyph, px, g.sideways, color).foreach { ag =>
          val gx = math.round((origin.x + g.originX) * scale).toDouble
          val gy = math.round((origin.y + g.originY) * scale).toDouble
          val dst = Rect((gx + ag.left) / scale, (gy - ag.top) / scale, (gx + ag.left + ag.w) / scale, (gy - ag.top + ag.h) / scale)
          canvas.drawImage(atlas.texture, dst, Rect(ag.u0, ag.v0, ag.u1, ag.v1), ag.tint(color))
        }
      }
    }

  /** Draws a [[RichLayout]] with per-run colors, backgrounds, underlines and strikethroughs. */
  def drawRich(canvas: Canvas, layout: RichLayout, origin: Point): Unit =
    val scale = canvas.scaleFactor
    layout.lines.foreach { line =>
      // backgrounds first, merged per adjacent run of the same style
      var gi = 0
      while gi < line.glyphs.size do
        val g0 = line.glyphs(gi); var gj = gi
        while gj + 1 < line.glyphs.size && line.glyphs(gj + 1).style == g0.style do gj += 1
        val gl = line.glyphs(gj); val x0 = g0.x; val x1 = gl.x + gl.advance
        g0.style.background.foreach(bg => canvas.fillRect(Rect(origin.x + x0, origin.y + line.top, origin.x + x1, origin.y + line.top + line.height), bg))
        gi = gj + 1
      line.glyphs.foreach { g =>
        atlas.get(g.font, g.glyph, g.style.size * scale, foreground = g.style.color).foreach { ag =>
          val gx = math.round((origin.x + g.x) * scale).toDouble
          val gy = math.round((origin.y + g.baseline) * scale).toDouble
          val dst = Rect((gx + ag.left) / scale, (gy - ag.top) / scale, (gx + ag.left + ag.w) / scale, (gy - ag.top + ag.h) / scale)
          canvas.drawImage(atlas.texture, dst, Rect(ag.u0, ag.v0, ag.u1, ag.v1), ag.tint(g.style.color))
        }
      }
      // decorations per run
      gi = 0
      while gi < line.glyphs.size do
        val g0 = line.glyphs(gi); var gj = gi
        while gj + 1 < line.glyphs.size && line.glyphs(gj + 1).style == g0.style do gj += 1
        val gl = line.glyphs(gj); val st = g0.style
        val thick = math.max(1.0, st.size / 14)
        if st.underline then canvas.fillRect(Rect(origin.x + g0.x, origin.y + g0.baseline + st.size * 0.12, origin.x + gl.x + gl.advance, origin.y + g0.baseline + st.size * 0.12 + thick), st.color)
        if st.strikethrough then canvas.fillRect(Rect(origin.x + g0.x, origin.y + g0.baseline - st.size * 0.3, origin.x + gl.x + gl.advance, origin.y + g0.baseline - st.size * 0.3 + thick), st.color)
        gi = gj + 1
    }
