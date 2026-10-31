package scalaui.text

import scalaui.font.FontFace
import scalaui.shaping.Shaper

class BidiLayoutSuite extends scalaui.testkit.LoggedSuite:
  private val shaper = new Shaper(FontFace.notoSans)
  private def lay(t: String, w: Option[Double] = None) = TextLayout.layout(t, shaper, 16, w)

  private val mixed = "abc אבג def" // LTR, Hebrew (RTL), LTR

  test("RTL run is reversed visually, LTR runs keep order") {
    val l = lay(mixed)
    val clusters = l.lines.head.glyphs.map(_.cluster)
    assertEquals(clusters, Vector(0, 1, 2, 3, 6, 5, 4, 7, 8, 9, 10))
    assertEquals(l.lines.head.glyphs.map(_.level), Vector(0, 0, 0, 0, 1, 1, 1, 0, 0, 0, 0))
  }
  test("glyph x positions increase in visual order") {
    val l = lay(mixed)
    l.lines.head.glyphs.sliding(2).foreach { case Vector(a, b) => assertEqualsDouble(b.x, a.x + a.advance, 1e-6); case _ => () }
  }
  test("pure RTL paragraph is laid out right-to-left with hanging space on the logical end") {
    val l = lay("אבג ")
    assertEquals(l.lines.head.glyphs.map(_.cluster), Vector(3, 2, 1, 0))
    assert(l.lines.head.glyphs.forall(_.level == 1))
  }
  test("caret round trip over mixed-direction text") {
    val l = lay(mixed)
    // positions 4 and 7 sit on a direction boundary: both carets map to the same x, so hit testing is ambiguous there
    for i <- 0 to mixed.length if i != 4 && i != 7 do
      val (x, top, h) = l.caret(i)
      assertEquals(l.indexAt(x, top + h / 2), i, s"index $i")
  }
  test("selection inside an RTL run is one rectangle; across directions it can split") {
    val l = lay(mixed)
    assertEquals(l.selectionRects(4, 7).size, 1)
    val r = l.selectionRects(4, 6).head
    val g = l.lines.head.glyphs
    assertEqualsDouble(r.x0, g.find(_.cluster == 5).get.x, 1e-6)
    assertEqualsDouble(r.x1, g.find(_.cluster == 4).get.x + g.find(_.cluster == 4).get.advance, 1e-6)
    assertEquals(l.selectionRects(2, 6).size, 2) // 'c', ' ' ... then Hebrew 'א','ב' are not visually adjacent
  }
  test("explicit direction overrides auto detection") {
    val rtl = TextLayout.layout("abc \u05D0\u05D1", shaper, 16, None, Align.Left, 1.0, scalaui.unicode.Bidi.Rtl)
    val g = rtl.lines.head.glyphs
    assert(g.head.cluster >= 4, "Hebrew is visually first in an RTL paragraph")
    assertEquals(g.last.cluster, 2)
    assertEquals(g.last.level, 2) // Latin inside an RTL paragraph is level 2
  }
  test("wrapping follows UAX #14: breaks after hyphens, never inside a grapheme cluster") {
    val one = lay("well-known fact")
    val l = lay("well-known fact", Some(lay("well-").width + 1))
    assert(l.lines.size >= 2)
    assertEquals(l.text.substring(l.lines.head.start, l.lines.head.end), "well-")
    assertEquals(one.lines.size, 1)
    val acc = "é" * 8 // e + combining acute, 8 clusters
    val w = lay("é").width * 2.5
    val wrapped = lay(acc, Some(w))
    assert(wrapped.lines.size > 1)
    wrapped.lines.foreach(ln => assertEquals(ln.start % 2, 0, "line must start on a cluster boundary"))
  }
  test("no break before closing punctuation or inside numbers") {
    val l = lay("price 1,234.50 (ok)", Some(lay("price 1,23").width))
    l.lines.foreach(ln => assert(!l.text.substring(ln.start).startsWith(","), "line starts with a comma"))
    assert(!l.lines.exists(ln => l.text.substring(ln.start, ln.end).trim == ""))
  }

  test("visual caret movement walks left-to-right across directions and terminates at the line edges") {
    val l = TextLayout.layout(mixed, shaper, 16)
    def walk(from: Int, right: Boolean): Vector[Int] =
      var path = Vector(from); var i = from; var steps = 0
      while steps < 30 && l.moveVisual(i, right) >= 0 do { i = l.moveVisual(i, right); path :+= i; steps += 1 }
      path
    val fwd = walk(0, right = true)
    val xs = fwd.map(i => l.caret(i)._1)
    assert(xs.zip(xs.tail).forall((a, b) => b > a - 1e-9), s"x must not decrease: $xs")
    assertEquals(fwd.last, mixed.length)
    // Hebrew letters are visited in reverse logical order while moving right
    val hebrew = fwd.filter(i => i >= 4 && i <= 7)
    assertEquals(hebrew, hebrew.sorted.reverse)
    assertEquals(walk(mixed.length, right = false).last, 0)
    // a left-to-right-only line defers to logical movement
    assertEquals(TextLayout.layout("abc", shaper, 16).moveVisual(1, toRight = true), -1)
  }

