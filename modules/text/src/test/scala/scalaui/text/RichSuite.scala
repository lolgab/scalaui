package scalaui.text

import scalaui.color.Color
import scalaui.font.{FontFace, FontFamily}
import scalaui.shaping.Shaper

class RichSuite extends scalaui.testkit.LoggedSuite:
  private val reg = new Shaper(FontFamily.NotoSans(false, false)); private val bold = new Shaper(FontFamily.NotoSans(true, false))
  private def st(s: Shaper, size: Double = 16) = TextStyle(s, size)

  test("single line: glyph x positions accumulate across styles; bold is wider than regular") {
    val l = RichLayout.layout(Seq(Span("Hello ", st(reg)), Span("World", st(bold))))
    assertEquals(l.lines.size, 1)
    val g = l.lines.head.glyphs
    assertEquals(g.size, 11)
    assertEqualsDouble(g(1).x, g(0).advance, 1e-6)
    assert(g(6).style.shaper eq bold)
    val plain = RichLayout.layout(Seq(Span("Hello World", st(reg)))).width
    assert(l.width > plain, s"${l.width} vs $plain")
  }
  test("wraps across span boundaries and keeps indices contiguous") {
    val spans = Seq(Span("alpha beta ", st(reg)), Span("gamma delta ", st(bold)), Span("epsilon", st(reg)))
    val one = RichLayout.layout(spans)
    val l = RichLayout.layout(spans, Some(one.width / 2))
    assert(l.lines.size >= 2)
    l.lines.sliding(2).foreach { case Vector(a, b) => assertEquals(a.end, b.start); case _ => () }
    assertEquals(l.lines.last.end, l.text.length)
    l.lines.foreach(ln => assert(ln.width <= one.width / 2 + 1e-6))
  }
  test("line height follows the tallest run") {
    val small = RichLayout.layout(Seq(Span("ab", st(reg, 12)))).height
    val mixed = RichLayout.layout(Seq(Span("ab", st(reg, 12)), Span("CD", st(reg, 32)))).height
    assert(mixed > small * 2, s"$mixed vs $small")
  }
  test("newlines, empty paragraphs and caret/hit-test round trip") {
    val l = RichLayout.layout(Seq(Span("ab\n\ncd", st(reg)), Span("ef", st(bold))))
    assertEquals(l.lines.map(_.start), Vector(0, 3, 4))
    for i <- 0 to l.text.length do
      val (x, top, h) = l.caret(i)
      assertEquals(l.indexAt(x + 0.01, top + h / 2), i, s"index $i")
  }
  test("alignment") {
    val l = RichLayout.layout(Seq(Span("abc", st(reg))), Some(200), Align.Right)
    assertEqualsDouble(l.lines.head.x + l.lines.head.width, 200, 1e-6)
  }
  test("selection rects span styles on one line") {
    val l = RichLayout.layout(Seq(Span("ab", st(reg)), Span("cd", st(bold))))
    val r = l.selectionRects(1, 3)
    assertEquals(r.size, 1)
    assert(r.head.width > 0)
  }
