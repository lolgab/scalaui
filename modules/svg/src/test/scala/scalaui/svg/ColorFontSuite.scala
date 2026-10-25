package scalaui.svg

import java.nio.file.{Files, Paths}
import scalaui.font.{FontFace, FontFamily}
import scalaui.raster.Pixmap

class ColorFontSuite extends scalaui.testkit.LoggedSuite:
  private def face(n: String) = FontFace(Files.readAllBytes(Paths.get("modules/font/src/test/resources/color").resolve(n)))
  private def render(svg: String, fonts: FontFamily*): Pixmap =
    SvgDocument.parse(svg, SvgOptions(fonts = fonts, systemFonts = false)).render(200, 100, Some(scalaui.color.Color.White))
  private def colorful(p: Pixmap): Int =
    (0 until p.width * p.height).count { i =>
      val r = p.data(i * 4); val g = p.data(i * 4 + 1); val b = p.data(i * 4 + 2)
      math.max(r, math.max(g, b)) - math.min(r, math.min(g, b)) > 0.25f
    }
  private def png(name: String, p: Pixmap): Unit =
    Files.createDirectories(Paths.get("target/svg-color-out"))
    Files.write(Paths.get(s"target/svg-color-out/$name.png"), scalaui.codecs.Png.encode(scalaui.codecs.Image(p.width, p.height, p.toRgba8), 6))

  private def doc(family: String, text: String, extra: String = "") =
    s"""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 200 100"><text x="10" y="70" font-family="$family" font-size="60" fill="black" $extra>$text</text></svg>"""

  test("COLR v1 emoji in svg text keeps its own colours") {
    val p = render(doc("E", "&#x1F600;&#x1F680;"), new FontFamily("E", face("NotoColorEmoji-colrv1-subset.ttf")))
    png("colrv1", p); assert(colorful(p) > 800)
  }
  test("CBDT bitmap emoji, rotation and textPath") {
    val f = new FontFamily("B", face("NotoColorEmoji-cbdt-subset.ttf"))
    val p = render(doc("B", "&#x1F600;&#x1F680;"), f); png("cbdt", p); assert(colorful(p) > 800)
    val r = render(doc("B", "&#x1F600;&#x1F680;", """transform="rotate(20 60 50)""""), f); png("cbdt-rot", r); assert(colorful(r) > 800)
    val tp = render("""<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 200 100"><path id="p" d="M10 80 Q100 -20 190 80" fill="none"/>
      <text font-family="B" font-size="40"><textPath xlink:href="#p">&#x1F600;&#x1F680;</textPath></text></svg>""", f)
    png("cbdt-path", tp); assert(colorful(tp) > 1000)
  }
  test("COLR v0: fill supplies the foreground colour, fill-opacity fades the glyph") {
    val f = new FontFamily("Z", face("colrv0-test.ttf"))
    val a = render(doc("Z", "&#xE000;").replace("fill=\"black\"", "fill=\"#ff0000\""), f)
    val b = render(doc("Z", "&#xE000;").replace("fill=\"black\"", "fill=\"#00ff00\""), f)
    assert(colorful(a) > 500); assert(a.data.toSeq != b.data.toSeq)
    val faint = render(doc("Z", "&#xE000;", "fill-opacity=\"0.3\""), f)
    assert(faint.data.sum > a.data.sum)
  }
  test("SVG-in-OpenType glyphs") {
    val f = face("NotoColorEmoji-svg-subset.ttf")
    assert(!f.hasLayeredColor(f.glyphIndex(0x1F680)) && f.bitmapGlyph(f.glyphIndex(0x1F680), 64).isEmpty) // only the SVG table can draw it
    val p = render(doc("S", "&#x1F680;"), new FontFamily("S", f))
    png("svgtable", p); assert(colorful(p) > 800)
  }
