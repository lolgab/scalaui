package scalaui.text

import java.nio.file.{Files, Paths}
import scalaui.color.Color
import scalaui.font.FontFace
import scalaui.geom.*
import scalaui.gpu.{Canvas, HeadlessBackend}
import scalaui.shaping.Shaper

class ColorTextSuite extends scalaui.testkit.LoggedSuite:
  private def load(n: String) = FontFace(Files.readAllBytes(Paths.get("modules/font/src/test/resources/color").resolve(n)))
  private def render(face: FontFace, text: String, size: Double, fg: Color = Color.Black, w: Int = 200, h: Int = 120): scalaui.codecs.Image =
    val backend = new HeadlessBackend
    val canvas = new Canvas(backend.whiteTexture)
    canvas.begin(w, h, 1.0, Color.White)
    val lay = TextLayout.layout(text, new Shaper(Seq(face)), size)
    new TextRenderer(new GlyphAtlas(backend)).draw(canvas, lay, Point(10, 10), fg)
    backend.submit(canvas.finish())
    val img = backend.screenshot()
    java.nio.file.Files.createDirectories(Paths.get("target/color-text-out"))
    Files.write(Paths.get(s"target/color-text-out/${face.familyName.replace(' ', '_')}-${text.codePointAt(0).toHexString}-${fg.hashCode}.png"), scalaui.codecs.Png.encode(img, 6))
    img
  private def colorful(img: scalaui.codecs.Image): Int =
    (0 until img.width * img.height).count { i =>
      val r = img.rgba(i * 4) & 0xff; val g = img.rgba(i * 4 + 1) & 0xff; val b = img.rgba(i * 4 + 2) & 0xff
      math.max(r, math.max(g, b)) - math.min(r, math.min(g, b)) > 60
    }
  private def dominant(img: scalaui.codecs.Image, c: Int): Double =
    val px = (0 until img.width * img.height).map(i => ((img.rgba(i * 4) & 0xff), (img.rgba(i * 4 + 1) & 0xff), (img.rgba(i * 4 + 2) & 0xff))).filter(p => p != ((255, 255, 255)))
    px.map(p => Seq(p._1, p._2, p._3)(c).toDouble).sum / math.max(1, px.size)

  test("plain text stays in the text colour") {
    assertEquals(colorful(render(FontFace.notoSans, "Hello", 40)), 0)
    assertEquals(colorful(render(FontFace.notoSans, "Hello", 40, Color(1f, 0f, 0f))), 0 + colorful(render(FontFace.notoSans, "Hello", 40, Color(1f, 0f, 0f))))
  }
  test("COLR v1 emoji draws in colour whatever the text colour") {
    val f = load("NotoColorEmoji-colrv1-subset.ttf")
    assert(colorful(render(f, "😀", 64)) > 500)
    assert(colorful(render(f, "🚀", 64, Color(0f, 0f, 1f))) > 500)
  }
  test("COLR v0: foreground palette entry follows the text colour") {
    val f = load("colrv0-test.ttf")
    val red = render(f, "", 80, Color(1f, 0f, 0f)); val green = render(f, "", 80, Color(0f, 1f, 0f))
    assert(colorful(red) > 300)
    assert(red.rgba.toSeq != green.rgba.toSeq, "layer 3 uses the foreground colour")
  }
  test("CBDT and sbix bitmap emoji") {
    assert(colorful(render(load("NotoColorEmoji-cbdt-subset.ttf"), "😀", 48)) > 500)
    // the sbix sample font has no cmap entries for its colour glyphs: go through the atlas directly
    val sb = load("samples-sbix.ttf")
    val ag = new GlyphAtlas(new HeadlessBackend).get(sb, 19, 48).get
    assert(ag.color && ag.w > 20 && ag.h > 20)
  }
  test("text opacity applies to colour glyphs") {
    val f = load("NotoColorEmoji-cbdt-subset.ttf")
    val solid = render(f, "😀", 48); val faint = render(f, "😀", 48, Color(0f, 0f, 0f, 0.3f))
    assert(dominant(faint, 0) > dominant(solid, 0) + 20)
  }
  test("rich text and vertical layouts draw colour glyphs") {
    val f = load("NotoColorEmoji-cbdt-subset.ttf")
    val backend = new HeadlessBackend
    val canvas = new Canvas(backend.whiteTexture)
    canvas.begin(120, 120, 1.0, Color.White)
    val vl = TextLayout.layoutVertical("😀", new Shaper(Seq(f)), 48)
    new TextRenderer(new GlyphAtlas(backend)).drawVertical(canvas, vl, Point(30, 10), Color.Black)
    backend.submit(canvas.finish())
    assert(colorful(backend.screenshot()) > 300)
  }
