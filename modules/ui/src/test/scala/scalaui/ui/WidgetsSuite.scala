package scalaui.ui

import scalaui.color.Color
import scalaui.geom.*
import scalaui.window.*

class WidgetsSuite extends scalaui.testkit.LoggedSuite:
  private val cols = Seq(Column("Name", 120), Column("Size", 80))

  test("table paints only visible rows and reports clicks as selection") {
    val h = new Harness
    val st = new TableState(cols)
    val asked = scala.collection.mutable.Set.empty[Int]
    var rect = Rect.Zero
    def frame(): Unit = h.frame { ui => rect = ui.table(st, cols, 1000, 120)((r, c, u) => { asked += r; u.label(s"r$r c$c") }).rect }
    frame()
    assert(asked.size < 20, s"asked for ${asked.size} rows")
    val rowCenter = Point(rect.x0 + 20, rect.y0 + 24 + 24 * 2 + 12) // third row below the header
    h.move(rowCenter); frame(); h.press(rowCenter); frame(); h.release(rowCenter); frame()
    assertEquals(st.selected, Some(2))
  }
  test("table sorts by clicking a header and keyboard moves the selection") {
    val h = new Harness
    val st = new TableState(cols)
    var rect = Rect.Zero
    def frame(): Unit = h.frame { ui => rect = ui.table(st, cols, 10, 200)((_, _, _) => ()).rect }
    frame()
    val head = Point(rect.x0 + 30, rect.y0 + 12)
    h.move(head); frame(); h.press(head); frame(); h.release(head); frame()
    assertEquals(st.sortColumn, Some(0)); assert(st.ascending)
    h.move(head); frame(); h.press(head); frame(); h.release(head); frame()
    assert(!st.ascending)
    // focus the body, then arrow down twice
    val body = Point(rect.x0 + 20, rect.y0 + 60)
    h.move(body); frame(); h.press(body); frame(); h.release(body); frame()
    val first = st.selected
    h.key(Key.Down); frame()
    assertEquals(st.selected.get, first.get + 1)
  }
  test("TableState.order sorts rows by key and direction") {
    val st = new TableState(cols)
    val data = Vector(3, 1, 2)
    assertEquals(st.order(3)((r, _) => data(r)), Vector(0, 1, 2))
    st.sortColumn = Some(0)
    assertEquals(st.order(3)((r, _) => data(r)), Vector(1, 2, 0))
    st.ascending = false
    assertEquals(st.order(3)((r, _) => data(r)), Vector(0, 2, 1))
  }
  test("tree nodes expand on arrow click and run children only while open") {
    val h = new Harness
    var rows = 0
    var rect = Rect.Zero
    def frame(): Unit = h.frame { ui =>
      rect = ui.treeNode("root")(c => { c.treeNode("child", leaf = true)(); rows += 1 }).rect
    }
    frame(); assertEquals(rows, 0)
    val arrow = Point(rect.x0 + 8, rect.center.y)
    h.move(arrow); frame(); h.press(arrow); frame(); h.release(arrow); frame(); frame()
    assert(rows > 0)
  }
  test("plot draws series and zooms with the wheel, double click resets") {
    val h = new Harness
    val st = new PlotState
    val pts = (0 to 20).map(i => (i.toDouble, math.sin(i / 3.0)))
    var rect = Rect.Zero
    def frame(): Unit = h.frame { ui => rect = ui.plot(st, Size(300, 160), Seq(PlotSeries("sin", pts, Color.rgb8(255, 120, 0))), "x", "y").rect }
    frame()
    assert(h.lastFrame.vertices.length > 0)
    h.move(rect.center); frame()
    h.ctx.input.handle(Event.Scroll(0, 8)); frame()
    assert(st.view.isDefined)
    val zoomed = st.view.get
    assert(zoomed.width < 21, s"$zoomed")
    h.press(rect.center); frame(); h.release(rect.center); frame(); h.press(rect.center); frame(); h.release(rect.center); frame()
    assertEquals(st.view, None)
  }
  test("drag payload reaches a drop target on release") {
    val h = new Harness
    val src = Rect(20, 20, 80, 50); val dst = Rect(200, 100, 300, 160)
    var dropped: Option[Any] = None
    def frame(): Unit = h.frame { ui => ui.draggable(src, "item-7", "item 7"); dropped = ui.dropTarget(dst)(_ => true) }
    h.move(src.center); frame()
    h.press(src.center); frame()
    h.move(Point(120, 80)); frame()
    h.move(dst.center); frame()
    assertEquals(h.ctx.dragPayload, Some("item-7"))
    h.release(dst.center); frame()
    assertEquals(dropped, Some("item-7"))
    frame()
    assertEquals(h.ctx.dragPayload, None)
  }
  test("toggle switch flips and small controls allocate space") {
    val h = new Harness; val v = Var(false)
    var rect = Rect.Zero
    h.frame { ui => rect = ui.toggleSwitch(v, "Wi-Fi").rect }
    assert(h.click(rect)(_.toggleSwitch(v, "Wi-Fi")) || v.value)
    assert(v.value)
    h.frame { ui => ui.spinner(); ui.separatorLabel("section"); ui.selectableLabel(true, "item"); ui.hyperlink("link", "https://example.com"); () }
  }
