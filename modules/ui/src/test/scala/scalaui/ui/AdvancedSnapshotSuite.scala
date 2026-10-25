package scalaui.ui

import scalaui.geom.*
import scalaui.window.*

class AdvancedSnapshotSuite extends scalaui.testkit.LoggedSuite:
  test("code editor with line numbers and scrolling") {
    val h = new SnapshotHarness(360, 190)
    val v = Var("def main(args: Array[String]): Unit =\n  println(\"hello\")\n  val xs = List(1, 2, 3)\n  xs.foreach(println)\n\n// a long comment line that wraps around because it does not fit in the editor width at all\nend main\nline 8\nline 9")
    var img = h.render(_.textEdit(v, multiline = true, mono = true, maxRows = 6, lineNumbers = true, rows = 6))
    h.ctx.edits.values.foreach(_.scrollY = 20)
    img = h.render(_.textEdit(v, multiline = true, mono = true, maxRows = 6, lineNumbers = true, rows = 6))
    Snapshot.check("code-editor", img)
  }
  test("splitter, rating, closable tabs, progress variants") {
    val h = new SnapshotHarness(420, 330)
    val f = Var(0.4); val r = Var(3); val sel = Var(1)
    val img = h.render { ui =>
      ui.closableTabBar(sel, Seq("main.scala", "README.md", "build.sbt"))
      ui.space(4)
      ui.hSplit(f, height = 110)(l => { l.label("Left pane"); l.button("A") })(rt => { rt.label("Right pane"); rt.checkbox(Var(true), "B") })
      ui.rating(r)
      ui.horizontal { row => row.progressCircle(0.65) } // progressIndeterminate animates off ctx.time, so it is covered by a behaviour test, not a golden
    }
    Snapshot.check("split-rating-tabs", img)
  }
  test("virtual list and reorder list") {
    val h = new SnapshotHarness(380, 330)
    val st = new VirtualListState; st.selected = Some(2)
    val items = Var(Vector("Alpha", "Beta", "Gamma"))
    val img = h.render { ui =>
      ui.virtualList(st, 100000, 24, 130)((i, u) => { u.label(s"Item number $i"); u.weak("row") })
      ui.space(6)
      ui.reorderList(items)((t, u) => u.label(t))
    }
    Snapshot.check("lists", img)
  }
  test("context menu and date picker popups") {
    val h = new SnapshotHarness(360, 330)
    val d = Var(Date(2026, 10, 25))
    var rect = Rect.Zero
    def f(ui: Ui): Unit =
      val b = ui.button("Right click me")
      ui.contextMenu(b) { m => m.menuItem("Cut", "Ctrl+X"); m.menuItem("Copy", "Ctrl+C"); m.separator(); m.menuItem("Paste", "Ctrl+V") }
      rect = ui.datePicker(d).rect
    h.render(f)
    val c = Point(60, 20)
    h.move(c); h.render(f)
    h.ctx.input.handle(Event.MouseDown(MouseButton.Right, c.x, c.y, Modifiers.None)); h.render(f)
    h.ctx.input.handle(Event.MouseUp(MouseButton.Right, c.x, c.y))
    h.render(f); val menuImg = h.render(f)
    Snapshot.check("context-menu", menuImg)
    h.ctx.popupOwner = 0L; h.render(f)
    h.move(rect.center); h.render(f); h.press(rect.center); h.render(f); h.release(rect.center); h.render(f)
    Snapshot.check("date-picker", h.render(f))
  }
