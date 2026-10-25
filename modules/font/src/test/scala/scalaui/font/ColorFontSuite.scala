package scalaui.font

import java.nio.file.{Files, Paths}

class ColorFontSuite extends scalaui.testkit.LoggedSuite:
  private def load(name: String) = FontFace(Files.readAllBytes(Paths.get("modules/font/src/test/resources/color").resolve(name)))
  private def png(b: Array[Byte]) = b.length > 8 && (b(0) & 0xff) == 0x89 && b(1) == 'P'

  private def count(p: ColorPaint): Map[String, Int] = p match
    case _: ColorPaint.Solid => Map("solid" -> 1)
    case _: ColorPaint.LinearGradient => Map("linear" -> 1)
    case _: ColorPaint.RadialGradient => Map("radial" -> 1)
    case _: ColorPaint.SweepGradient => Map("sweep" -> 1)
    case ColorPaint.Glyph(_, q) => merge(Map("glyph" -> 1), count(q))
    case ColorPaint.Layers(ls) => ls.map(count).foldLeft(Map("layers" -> 1))(merge)
    case ColorPaint.Transform(_, q) => merge(Map("transform" -> 1), count(q))
    case ColorPaint.Clip(_, q) => merge(Map("clip" -> 1), count(q))
    case ColorPaint.Composite(_, a, b) => merge(merge(Map("composite" -> 1), count(a)), count(b))
  private def merge(a: Map[String, Int], b: Map[String, Int]) = (a.keySet ++ b.keySet).map(k => k -> (a.getOrElse(k, 0) + b.getOrElse(k, 0))).toMap

  test("COLR v0: layers, palettes and foreground colour") {
    val f = load("colrv0-test.ttf")
    assert(f.hasColorGlyphs); assertEquals(f.paletteCount, 2)
    val g = f.glyphIndex(0xE000)
    assert(f.hasLayeredColor(g)); assert(!f.hasLayeredColor(f.glyphIndex('A')))
    val ColorPaint.Layers(ls) = f.colorGlyph(g, 0, scalaui.color.Color(0f, 1f, 0f)).get: @unchecked
    assertEquals(ls.size, 3)
    val ColorPaint.Glyph(gid0, ColorPaint.Solid(c0)) = ls(0): @unchecked
    assertEquals(gid0, f.glyphIndex('O')); assert(c0.r > 0.89f && c0.g < 0.11f)
    val ColorPaint.Glyph(_, ColorPaint.Solid(c2)) = ls(2): @unchecked // 0xFFFF = foreground
    assert(c2.g == 1f && c2.r == 0f)
    val ColorPaint.Layers(p1) = f.colorGlyph(g, 1).get: @unchecked
    val ColorPaint.Glyph(_, ColorPaint.Solid(d0)) = p1(0): @unchecked
    assertEquals(d0.r, 0f)
  }

  test("COLR v1: every paint format of the googlefonts test font parses") {
    val f = load("test_glyphs-glyf_colr_1.ttf")
    val kinds = (0 until f.numGlyphs).flatMap(g => f.colorGlyph(g)).map(count).foldLeft(Map.empty[String, Int])(merge)
    for k <- Seq("solid", "linear", "radial", "sweep", "glyph", "layers", "transform", "composite") do assert(kinds.getOrElse(k, 0) > 0, s"no $k paints: $kinds")
  }

  test("COLR v1: Noto Color Emoji subset") {
    val f = load("NotoColorEmoji-colrv1-subset.ttf")
    val g = f.glyphIndex(0x1F600)
    assert(g > 0 && f.hasLayeredColor(g))
    assert(count(f.colorGlyph(g).get).getOrElse("glyph", 0) > 0)
  }

  test("sbix strikes") {
    val f = load("samples-sbix.ttf")
    assertEquals(f.bitmapSizes, Vector(109))
    val gs = (0 until f.numGlyphs).flatMap(g => f.bitmapGlyph(g, 20))
    assert(gs.nonEmpty && gs.forall(b => b.format == "png" && png(b.data) && b.bottom.isDefined))
  }

  test("CBDT/CBLC strikes") {
    val f = load("NotoColorEmoji-cbdt-subset.ttf")
    assertEquals(f.bitmapSizes, Vector(109))
    val b = f.bitmapGlyph(f.glyphIndex(0x1F600), 32).get
    assert(png(b.data)); assertEquals((b.width, b.height), (136, 128)); assert(b.top.isDefined)
    assertEquals(f.outline(f.glyphIndex(0x1F600)).elements.size, 0)
  }

  test("SVG table") {
    val f = load("NotoColorEmoji-svg-subset.ttf")
    val d = f.svgGlyph(f.glyphIndex(0x1F600)).get
    assert(d.svg.contains("<svg") && d.svg.contains("glyph" + f.glyphIndex(0x1F600)))
  }
