package scalaui.text

import scalaui.font.FontFace
import scalaui.shaping.Shaper

class LayoutSuite extends scalaui.testkit.LoggedSuite:
  private val shaper = new Shaper(FontFace.notoSans)
  private def lay(t: String, w: Option[Double] = None, a: Align = Align.Left) = TextLayout.layout(t, shaper, 16, w, a)

  test("single line has one line, width = sum of advances") {
    val l = lay("Hello")
    assertEquals(l.lines.size, 1)
    assertEqualsDouble(l.width, l.lines.head.glyphs.map(_.advance).sum, 1e-6)
  }
  test("hard newlines split lines and preserve indices") {
    val l = lay("ab\ncd\n\nx")
    assertEquals(l.lines.map(_.start), Vector(0, 3, 6, 7))
    assertEquals(l.lines.map(_.end), Vector(3, 6, 7, 8))
    assertEquals(l.lines.map(_.glyphs.size), Vector(2, 2, 0, 1))
  }
  test("wraps at word boundaries within max width") {
    val one = lay("hello world foo bar baz")
    val l = lay("hello world foo bar baz", Some(one.width / 3))
    assert(l.lines.size >= 3)
    l.lines.foreach(ln => assert(ln.width <= one.width / 3 + 1e-6, s"line too wide: ${ln.width}"))
    // text is covered contiguously
    assertEquals(l.lines.head.start, 0)
    l.lines.sliding(2).foreach { case Vector(a, b) => assertEquals(a.end, b.start); case _ => () }
    assertEquals(l.lines.last.end, l.text.length)
  }
  test("long word breaks by character") {
    val l = lay("Supercalifragilistic", Some(40))
    assert(l.lines.size > 1)
    assertEquals(l.lines.map(_.glyphs.size).sum, 20)
  }
  test("center and right alignment") {
    val c = lay("ab\nabcdefgh", Some(200), Align.Center)
    assertEqualsDouble(c.lines.head.x, (200 - c.lines.head.width) / 2, 1e-6)
    val r = lay("abc", Some(200), Align.Right)
    assertEqualsDouble(r.lines.head.x + r.lines.head.width, 200, 1e-6)
  }
  test("caret and hit testing round trip") {
    val l = lay("Hello world")
    for i <- 0 to l.text.length do
      val (x, top, h) = l.caret(i)
      assertEquals(l.indexAt(x + 0.01, top + h / 2), i, s"index $i")
  }
  test("selection rects: one per touched line") {
    val l = lay("ab\ncd")
    assertEquals(l.selectionRects(1, 4).size, 2)
    assertEquals(l.selectionRects(2, 2).size, 0)
  }

  test("OpenType features switch shaping behaviour per layout and per span") {
    import scalaui.shaping.FontFeature
    val on = TextLayout.layout("AVAVAV", shaper, 32)
    val off = TextLayout.layout("AVAVAV", shaper, 32, features = Seq(FontFeature("kern", 0)))
    assert(off.width > on.width, s"kerning off should widen: ${off.width} vs ${on.width}")
    // per span: only the second half has kerning disabled
    val st = TextStyle(shaper, 32)
    val mixed = RichLayout.layout(Seq(Span("AVAVAV", st), Span("AVAVAV", st.copy(features = Seq(FontFeature("kern", 0))))))
    assertEqualsDouble(mixed.width, on.width + off.width, 1e-6)
  }
