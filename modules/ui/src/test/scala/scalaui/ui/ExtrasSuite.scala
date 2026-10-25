package scalaui.ui

import scalaui.color.Color
import scalaui.geom.*

class ExtrasSuite extends scalaui.testkit.LoggedSuite:
  private def clickAt(h: Harness, p: Point)(f: Ui => Any): Unit =
    h.move(p); h.frame(f); h.press(p); h.frame(f); h.release(p); h.frame(f)

  test("menu opens on click, item click fires and closes, click outside closes") {
    val h = new Harness
    var opened = 0; var titleRect = Rect.Zero
    def ui(u: Ui): Unit =
      u.menuBar { b => b.menu("File") { m => if m.menuItem("Open", "Cmd+O").clicked then opened += 1; m.separator(); m.menuItem("Quit") } }
    // find the title rect via the bar: first widget sits at the region origin
    h.frame(ui); titleRect = Rect(14, 10, 50, 30)
    clickAt(h, titleRect.center)(ui)
    assert(h.ctx.popupOwner != 0, "menu should be open")
    h.frame(ui) // popup size settles
    val item = Point(titleRect.x0 + 30, titleRect.y1 + 2 + 4 + 11)
    clickAt(h, item)(ui)
    assertEquals(opened, 1)
    assertEquals(h.ctx.popupOwner, 0L)
    // reopen then click far away
    clickAt(h, titleRect.center)(ui)
    assert(h.ctx.popupOwner != 0)
    h.press(Point(380, 380)); h.frame(ui)
    assertEquals(h.ctx.popupOwner, 0L)
  }

  test("tabBar switches selection") {
    val h = new Harness; val sel = Var(0)
    var rects = Vector.empty[Rect]
    h.frame { u => u.tabBar(sel, Seq("One", "Two", "Three")) }
    // tabs are laid out left to right from x=10 (text width + 28 padding each); x=100 lands inside the second
    val p = Point(100, 8 + 13)
    clickAt(h, p)(_.tabBar(sel, Seq("One", "Two", "Three")))
    assert(sel.value >= 1, s"selected ${sel.value}")
  }

  test("grid aligns columns across rows after one frame") {
    val h = new Harness
    var xs = Vector.empty[Double]
    def ui(u: Ui): Unit =
      xs = Vector.empty
      u.grid(2) { g =>
        g.cell(_.label("short")); g.cell(c => xs :+= c.cursorPoint.x)
        g.cell(_.label("a much longer label")); g.cell(c => xs :+= c.cursorPoint.x)
      }
    h.frame(ui); h.frame(ui)
    assertEquals(xs.size, 2)
    assertEquals(xs(0), xs(1), "second column x must match across rows")
    assert(xs(0) > 100, s"column x ${xs(0)} should clear the long label")
  }

  test("dragValue changes by horizontal drag") {
    val h = new Harness; val v = Var(1.0)
    var rect = Rect.Zero
    h.frame { u => rect = u.dragValue(v).rect }
    val c = rect.center
    h.move(c); h.frame(_.dragValue(v)); h.press(c); h.frame(_.dragValue(v))
    h.move(Point(c.x + 20, c.y)); h.frame(_.dragValue(v))
    h.move(Point(c.x + 40, c.y)); h.frame(_.dragValue(v))
    assert(v.value > 2.0, s"value ${v.value}")
  }

  test("floating window drags by its title bar and covers widgets below") {
    val h = new Harness; var clicks = 0
    def ui(u: Ui): Unit =
      if u.button("below").clicked then clicks += 1
      u.window("W", Point(0, 0), 200)(_.label("hi"))
    h.frame(ui); h.frame(ui)
    val title = Point(60, 13)
    h.move(title); h.frame(ui); h.press(title); h.frame(ui)
    h.move(Point(110, 63)); h.frame(ui); h.frame(ui)
    h.release(Point(110, 63)); h.frame(ui)
    val x = h.ctx.numbers.values.toSeq
    assert(x.exists(_ >= 45) && x.exists(_ >= 45), s"window should have moved ~50px: $x")
    // a button under the (moved) window is covered: click it at the window body
    clickAt(h, Point(120, 80))(ui)
    assertEquals(clicks, 0)
  }

  test("color picker: dragging in the SV square changes the color; hue sticks for grays") {
    val h = new Harness; val c = Var(Color(1, 0, 0))
    var rect = Rect.Zero
    def ui(u: Ui): Unit = rect = u.colorEdit(c).rect
    h.frame(ui)
    clickAt(h, rect.center)(ui)
    assert(h.ctx.popupOwner != 0)
    h.frame(ui)
    // SV square spans x in [rect.x0+8, rect.x0+202], y in [rect.y1+2+8, +160]: pick bottom-left => black
    val bl = Point(rect.x0 + 9, rect.y1 + 2 + 8 + 159)
    h.move(bl); h.frame(ui); h.press(bl); h.frame(ui)
    assertEqualsDouble(c.value.r, 0, 0.05); assertEqualsDouble(c.value.g, 0, 0.05)
    // top-left => white
    val tl = Point(rect.x0 + 9, rect.y1 + 2 + 8 + 1)
    h.move(tl); h.frame(ui); h.frame(ui)
    assertEqualsDouble(c.value.r, 1, 0.05); assertEqualsDouble(c.value.g, 1, 0.05)
  }
