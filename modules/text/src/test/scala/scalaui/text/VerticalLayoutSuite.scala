package scalaui.text

import java.nio.file.{Files, Paths}
import scalaui.color.Color
import scalaui.font.FontFace
import scalaui.geom.*
import scalaui.gpu.{Canvas, HeadlessBackend}
import scalaui.shaping.Shaper

class VerticalLayoutSuite extends scalaui.testkit.LoggedSuite:
  private val jp = FontFace(Files.readAllBytes(Paths.get("modules/shaping/src/test/resources/fonts/NotoSansJP-Subset.otf")))
  private val shaper = new Shaper(Seq(jp, FontFace.notoSans))
  private def lay(t: String, h: Option[Double] = None, size: Double = 100, a: Align = Align.Left) = TextLayout.layoutVertical(t, shaper, size, h, a)

  test("upright CJK stacks top to bottom, centred on the column axis (VORG origin)") {
    val l = lay("日本語")
    assertEquals(l.columns.size, 1)
    val c = l.columns.head
    assertEquals(c.glyphs.map(_.sideways), Vector(false, false, false))
    assertEquals(c.glyphs.map(_.pen), Vector(0.0, 100.0, 200.0))
    c.glyphs.foreach { g =>
      assertEqualsDouble(g.advance, 100, 1e-6)
      assertEqualsDouble(g.originX, c.centerX - 50, 1e-6) // half the 1000-unit advance to the left of the axis
      assertEqualsDouble(g.originY, g.pen + 88, 1e-6)     // VORG y = 880 units below the pen
    }
    assertEqualsDouble(l.height, 300, 1e-6)
    assertEqualsDouble(l.width, c.breadth, 1e-6)
  }
  test("wraps at maxHeight into columns running right to left") {
    val l = lay("日本語漢字", Some(250))
    assertEquals(l.columns.map(_.glyphs.size), Vector(2, 2, 1))
    assert(l.columns(0).left > l.columns(1).left && l.columns(1).left > l.columns(2).left)
    assertEquals(l.columns.map(_.start), Vector(0, 2, 4))
    assertEqualsDouble(l.width, 3 * l.columns.head.breadth, 1e-6)
    assertEqualsDouble(l.columns.head.left, l.width - l.columns.head.breadth, 1e-6)
  }
  test("Latin runs are sideways, CJK upright, punctuation with a vertical form upright") {
    val l = lay("日abc本「x」、")
    val g = l.columns.head.glyphs
    assertEquals(g.map(_.sideways), Vector(false, true, true, true, false, false, true, false, false))
    // sideways glyphs advance by their horizontal advance; the em box is centred on the axis
    val a = g(1)
    val s = 100.0 / jp.unitsPerEm
    assertEqualsDouble(a.advance, shaper.shape("a", 100).head.xAdvance, 1e-6)
    assertEqualsDouble(a.originX, l.columns.head.centerX - (jp.layoutAscender + jp.layoutDescender) * s / 2, 1e-6)
    // 「 and 、 are replaced by their vertical forms
    assertNotEquals(g(5).glyph, jp.glyphIndex(0x300C))
    assertNotEquals(g(8).glyph, jp.glyphIndex(0x3001))
  }
  test("caret and hit testing round trip; selection rects follow the column") {
    val l = lay("日本語abc漢字", Some(450))
    for i <- 0 to l.text.length do
      val (x, y, w) = l.caret(i)
      assertEquals(l.indexAt(x + w / 2, y + 0.01), i, s"index $i")
    val r = l.selectionRects(1, 3)
    assertEquals(r.size, 1)
    assertEqualsDouble(r.head.y0, 100, 1e-6); assertEqualsDouble(r.head.y1, 300, 1e-6)
    assertEquals(l.selectionRects(0, l.text.length).size, l.columns.size)
  }
  test("alignment along the vertical axis and hard newlines") {
    val l = lay("日本\n語", Some(400), a = Align.Center)
    assertEquals(l.columns.size, 2)
    assertEqualsDouble(l.columns(0).top, 100, 1e-6)
    assertEqualsDouble(l.columns(1).top, 150, 1e-6)
    val b = lay("日本", Some(400), a = Align.Right)
    assertEqualsDouble(b.columns.head.top, 200, 1e-6)
  }
  test("tracking adds space after each glyph; empty text has one empty column") {
    val l = TextLayout.layoutVertical("日本", shaper, 100, tracking = 10)
    assertEqualsDouble(l.columns.head.length, 220, 1e-6)
    assertEquals(lay("").columns.size, 1)
  }

  private def ink(img: scalaui.codecs.Image): (Int, Int, Int, Int) =
    var x0 = Int.MaxValue; var y0 = Int.MaxValue; var x1 = -1; var y1 = -1
    for y <- 0 until img.height; x <- 0 until img.width do
      if (img.rgba((y * img.width + x) * 4) & 0xff) < 128 then
        x0 = x0.min(x); y0 = y0.min(y); x1 = x1.max(x); y1 = y1.max(y)
    (x0, y0, x1, y1)

  private def render(l: VerticalLayout, w: Int, h: Int): scalaui.codecs.Image =
    val backend = new HeadlessBackend
    val canvas = new Canvas(backend.whiteTexture)
    canvas.begin(w, h, 1.0, Color.White)
    new TextRenderer(new GlyphAtlas(backend)).drawVertical(canvas, l, Point(0, 0), Color.Black)
    backend.submit(canvas.finish())
    backend.screenshot()

  test("rendering rotates sideways glyphs 90 degrees clockwise") {
    val upright = TextLayout.layout("l", shaper, 80)
    val backend = new HeadlessBackend
    val canvas = new Canvas(backend.whiteTexture)
    canvas.begin(120, 120, 1.0, Color.White)
    new TextRenderer(new GlyphAtlas(backend)).draw(canvas, upright, Point(20, 0), Color.Black)
    backend.submit(canvas.finish())
    val (ux0, uy0, ux1, uy1) = ink(backend.screenshot())
    assert(uy1 - uy0 > 3 * (ux1 - ux0), "an upright 'l' is tall and thin")
    val (x0, y0, x1, y1) = ink(render(lay("l", size = 80), 160, 120))
    assert(x1 - x0 > 3 * (y1 - y0), s"a sideways 'l' is wide and flat: ${x1 - x0}x${y1 - y0}")
  }
  test("sideways 'L' is turned clockwise: stem on top, foot hanging down on the left") {
    val img = render(lay("L", size = 80), 160, 120)
    val (x0, y0, x1, y1) = ink(img)
    def dark(x: Int, y: Int) = (img.rgba((y * img.width + x) * 4) & 0xff) < 128
    val top = (x0 to x1).count(dark(_, y0 + 1)); val bottom = (x0 to x1).filter(dark(_, y1 - 1))
    assert(top > 0.8 * (x1 - x0), s"top row $top of ${x1 - x0}")
    assert(bottom.nonEmpty && bottom.max < x0 + 0.5 * (x1 - x0), s"bottom row ink ${bottom.headOption}..${bottom.lastOption} in $x0..$x1")
  }
  test("rendering draws an upright kanji inside its column") {
    val l = lay("日", size = 80)
    val c = l.columns.head
    val (x0, y0, x1, y1) = ink(render(l, c.breadth.ceil.toInt + 4, 100))
    assert(x0 >= c.left - 1 && x1 <= c.left + c.breadth + 1, s"x range $x0..$x1 vs column ${c.left}..${c.left + c.breadth}")
    assert(y0 >= 0 && y1 <= 85, s"y range $y0..$y1")
    val cx = (x0 + x1) / 2.0
    assertEqualsDouble(cx, c.centerX, 2.0)
  }
