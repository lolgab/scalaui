package scalaui.svg

import scalaui.raster.Pixmap

/** Vertical writing modes in svg text beyond the resvg fixtures (ResvgTextSuite): orientation properties, upright glyph metrics, decorations. */
class VerticalTextSuite extends scalaui.testkit.LoggedSuite:
  private def render(body: String, attrs: String = ""): Pixmap =
    SvgDocument.parse(s"""<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 200 200" font-family="Noto Sans" font-size="40">$body</svg>""")
      .render(200, 200, Some(scalaui.color.Color.White))
  private def dark(p: Pixmap, x: Int, y: Int) = p.data((y * p.width + x) * 4) < 0.5f
  /** `(x0, y0, x1, y1)` of the dark pixels. */
  private def ink(p: Pixmap): (Int, Int, Int, Int) =
    var x0 = Int.MaxValue; var y0 = Int.MaxValue; var x1 = -1; var y1 = -1
    for y <- 0 until p.height; x <- 0 until p.width if dark(p, x, y) do { x0 = x0.min(x); y0 = y0.min(y); x1 = x1.max(x); y1 = y1.max(y) }
    (x0, y0, x1, y1)
  private def text(attrs: String, content: String) = render(s"""<text x="100" y="20" $attrs>$content</text>""")

  test("tb: sideways Latin runs down the column, centred on x") {
    val (x0, y0, x1, y1) = ink(text("writing-mode=\"tb\"", "Text"))
    assert(y1 - y0 > 2 * (x1 - x0), s"tall: ${x1 - x0}x${y1 - y0}")
    assert(x0 < 100 && x1 > 100 && math.abs((x0 + x1) / 2 - 100) < 16, s"centred on the axis: $x0..$x1")
    assert(y0 >= 19, s"starts at y: $y0")
  }
  test("vertical-rl, vertical-lr, tb-rl are the same single column; tspan and inherited writing-mode") {
    val a = text("writing-mode=\"tb\"", "Text"); val names = Seq("vertical-rl", "vertical-lr", "tb-rl")
    names.foreach(n => assertEquals(text(s"""writing-mode="$n"""", "Text").data.toSeq, a.data.toSeq, n))
    val inherited = render("""<g writing-mode="tb"><text x="100" y="20">Text</text></g>""")
    assertEquals(inherited.data.toSeq, a.data.toSeq)
    val onTspan = render("""<text x="100" y="20"><tspan writing-mode="tb">Text</tspan></text>""") // only the text element counts
    val (_, y0, _, y1) = ink(onTspan); assert(y1 - y0 < 30)
  }
  test("text-orientation upright stands Latin letters up; glyph-orientation-vertical 0 and 90 map to upright and sideways") {
    val upright = text("writing-mode=\"tb\" text-orientation=\"upright\"", "L")
    val (x0, y0, x1, y1) = ink(upright)
    def d(x: Int, y: Int) = dark(upright, x, y)
    assert(d(x0 + 1, (y0 + y1) / 2) && d(x1 - 1, y1 - 1) && !d(x1 - 1, y0 + 2), "an upright L: stem on the left, foot at the bottom right")
    assertEquals(text("writing-mode=\"tb\" glyph-orientation-vertical=\"0\"", "L").data.toSeq, upright.data.toSeq)
    assertEquals(text("writing-mode=\"tb\" glyph-orientation-vertical=\"90\"", "L").data.toSeq, text("writing-mode=\"tb\" text-orientation=\"sideways\"", "L").data.toSeq)
    assertEquals(text("writing-mode=\"tb\" glyph-orientation-vertical=\"auto\"", "L").data.toSeq, text("writing-mode=\"tb\"", "L").data.toSeq)
    val sideways = text("writing-mode=\"tb\"", "L"); val (sx0, sy0, sx1, sy1) = ink(sideways)
    assert(sx1 - sx0 > sy1 - sy0, s"sideways L is wider than tall: ${sx1 - sx0}x${sy1 - sy0}")
  }
  test("upright text uses vertical advances: more letters make the column longer by about one em each") {
    val (_, a0, _, a1) = ink(text("writing-mode=\"tb\" text-orientation=\"upright\"", "II"))
    val (_, b0, _, b1) = ink(text("writing-mode=\"tb\" text-orientation=\"upright\"", "IIII"))
    assert(math.abs((b1 - b0) - (a1 - a0) - 80) < 10, s"${a1 - a0} -> ${b1 - b0}")
  }
  test("underline of vertical text lies left of the glyphs, overline right of them") {
    val plain = ink(text("writing-mode=\"tb\"", "Text"))
    val (u0, _, _, _) = ink(text("writing-mode=\"tb\" text-decoration=\"underline\"", "Text"))
    val (_, _, o1, _) = ink(text("writing-mode=\"tb\" text-decoration=\"overline\"", "Text"))
    assert(u0 < plain._1 - 3 && o1 > plain._3 + 3, s"plain ${plain._1}..${plain._3}, underline from $u0, overline to $o1")
  }
  test("text-anchor works along the column; per-character y resets the position") {
    val mid = ink(render("""<text x="100" y="100" writing-mode="tb" text-anchor="middle">Text</text>"""))
    assert(math.abs((mid._2 + mid._4) / 2 - 100) < 8, s"$mid")
    val two = ink(render("""<text x="100" y="20" writing-mode="tb">ab<tspan y="120">cd</tspan></text>"""))
    assert(two._4 > 140, s"second chunk starts at y=120: $two")
  }
  test("text on a path still works in vertical mode") {
    val p = render("""<path id="p" d="M20 100 Q100 20 180 100" fill="none"/><text writing-mode="tb"><textPath xlink:href="#p">Curve</textPath></text>""")
    val (x0, y0, x1, y1) = ink(p); assert(x1 - x0 > 60 && y1 > y0)
  }
  test("code point addressing: one list entry per code point, a non-BMP character takes one") {
    val a = render("""<text y="100 140" x="20" font-size="30">&#x1F600;AB</text>""") // the emoji (no glyph, notdef) is entry 0, A is entry 1
    val (_, ay0, _, ay1) = ink(a); assert(ay1 > 125 && ay0 < 100, s"$ay0..$ay1")
  }
