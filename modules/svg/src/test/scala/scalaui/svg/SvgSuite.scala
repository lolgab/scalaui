package scalaui.svg

import scalaui.color.Color
import scalaui.geom.*
import scalaui.raster.Pixmap

class SvgSuite extends scalaui.testkit.LoggedSuite:
  private def px(p: Pixmap, x: Int, y: Int) = { val i = (y * p.width + x) * 4; Vector(p.data(i), p.data(i + 1), p.data(i + 2), p.data(i + 3)) }
  private def near(a: Vector[Float], b: Seq[Double], eps: Double = 0.02) = a.zip(b).foreach((x, y) => assertEqualsDouble(x, y, eps, s"$a vs $b"))
  private def render(svg: String, w: Int = 100, h: Int = 100) = SvgDocument.parse(svg).render(w, h)

  test("rect and viewBox scaling") {
    val p = render("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 10 10"><rect x="0" y="0" width="5" height="10" fill="#ff0000"/></svg>""")
    near(px(p, 20, 50), Seq(1, 0, 0, 1)); near(px(p, 80, 50), Seq(0, 0, 0, 0))
  }
  test("viewBox is letterboxed (xMidYMid meet)") {
    val p = render("""<svg viewBox="0 0 10 5"><rect width="10" height="5" fill="blue"/></svg>""", 100, 100)
    near(px(p, 50, 50), Seq(0, 0, 1, 1)); near(px(p, 50, 5), Seq(0, 0, 0, 0)) // bars above/below
  }
  test("circle, ellipse, polygon, line with stroke") {
    val p = render("""<svg viewBox="0 0 100 100"><circle cx="25" cy="25" r="10" fill="lime"/><polygon points="60,10 90,10 75,40" fill="red"/>
      <line x1="10" y1="60" x2="90" y2="60" stroke="black" stroke-width="8"/><ellipse cx="50" cy="85" rx="30" ry="8" fill="orange"/></svg>""")
    near(px(p, 25, 25), Seq(0, 0.5, 0.0, 1), 0.6) // lime = #00ff00 (green channel 1)
    assertEqualsDouble(px(p, 25, 25)(1), 1.0, 0.02)
    near(px(p, 75, 20), Seq(1, 0, 0, 1)); near(px(p, 50, 60), Seq(0, 0, 0, 1)); assertEqualsDouble(px(p, 50, 85)(3), 1.0, 0.02)
  }
  test("path data: relative commands, arcs, close, even-odd hole") {
    val p = render("""<svg viewBox="0 0 100 100"><path fill-rule="evenodd" fill="black" d="M10 10h80v80h-80z M30 30v40h40v-40z"/></svg>""")
    near(px(p, 15, 15), Seq(0, 0, 0, 1)); near(px(p, 50, 50), Seq(0, 0, 0, 0))
    val a = render("""<svg viewBox="0 0 100 100"><path d="M10 50 A 40 40 0 0 1 90 50 L 50 50 Z" fill="red"/></svg>""")
    near(px(a, 50, 30), Seq(1, 0, 0, 1)) // inside the half disc above the chord
    near(px(a, 50, 70), Seq(0, 0, 0, 0))
  }
  test("transforms on groups: translate + scale + rotate") {
    val p = render("""<svg viewBox="0 0 100 100"><g transform="translate(50 0) scale(2)"><rect width="10" height="10" fill="blue"/></g>
      <g transform="rotate(90 50 50)"><rect x="40" y="0" width="20" height="10" fill="red"/></g></svg>""")
    near(px(p, 60, 10), Seq(0, 0, 1, 1)); near(px(p, 90, 50), Seq(1, 0, 0, 1)) // rect rotated to the right edge
  }
  test("opacity, style attribute precedence and currentColor") {
    val p = render("""<svg viewBox="0 0 100 100" color="green"><rect width="100" height="100" fill="currentColor" style="fill-opacity:0.5"/></svg>""")
    near(px(p, 50, 50), Seq(0, 0.25, 0, 0.5))
  }
  test("linear gradient across the bounding box, stops with opacity") {
    val p = render("""<svg viewBox="0 0 100 100"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="0"><stop offset="0" stop-color="#ff0000"/><stop offset="1" stop-color="#0000ff"/></linearGradient></defs>
      <rect width="100" height="100" fill="url(#g)"/></svg>""")
    near(px(p, 2, 50), Seq(1, 0, 0, 1), 0.06); near(px(p, 97, 50), Seq(0, 0, 1, 1), 0.06); near(px(p, 50, 50), Seq(0.5, 0, 0.5, 1), 0.06)
  }
  test("use references defs; radial gradient") {
    val p = render("""<svg viewBox="0 0 100 100"><defs><rect id="r" width="20" height="20" fill="red"/><radialGradient id="rg"><stop offset="0" stop-color="white"/><stop offset="1" stop-color="black"/></radialGradient></defs>
      <use href="#r" x="10" y="10"/><circle cx="70" cy="70" r="25" fill="url(#rg)"/></svg>""")
    near(px(p, 20, 20), Seq(1, 0, 0, 1)); assert(px(p, 70, 70)(0) > 0.9f); assert(px(p, 70, 90)(0) < 0.4f)
  }
  test("parser tolerates comments, doctype, entities and malformed path tails") {
    val p = render("""<?xml version="1.0"?><!DOCTYPE svg><!-- hi --><svg viewBox="0 0 10 10"><path d="M0 0 L10 0 L10 10 L0 10 Z garbage" fill="red"/><title>a &amp; b</title></svg>""")
    near(px(p, 50, 50), Seq(1, 0, 0, 1))
  }
