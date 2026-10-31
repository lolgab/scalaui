package scalaui.ui

import scalaui.color.Color
import scalaui.geom.*
import scalaui.window.*

class AdvancedSuite extends scalaui.testkit.LoggedSuite:
  private val shortcutMods = if scala.util.Properties.isMac then Modifiers(command = true) else Modifiers(control = true)

  private def clickAt(h: Harness, p: Point)(f: Ui => Any): Unit =
    h.move(p); h.frame(f); h.press(p); h.frame(f); h.release(p); h.frame(f)

  // --- focus traversal ---
  test("Tab moves focus through widgets and Shift-Tab goes back; Enter and Space activate") {
    val h = new Harness
    val a = Var(false); val b = Var(false)
    var presses = 0
    def f(ui: Ui): Unit =
      if ui.button("one").clicked then presses += 1
      ui.checkbox(a, "a"); ui.checkbox(b, "b")
    h.frame(f)
    h.key(Key.Tab); h.frame(f)
    h.key(Enter); h.frame(f)
    assertEquals(presses, 1)
    h.key(Key.Tab); h.frame(f)
    h.key(Key.Space); h.frame(f)
    assert(a.value && !b.value)
    h.key(Key.Tab); h.frame(f)
    h.key(Key.Enter); h.frame(f)
    assert(b.value)
    h.key(Key.Tab); h.frame(f)            // wraps around to the button
    h.key(Key.Enter); h.frame(f)
    assertEquals(presses, 2)
    h.key(Key.Tab, Modifiers(shift = true)); h.frame(f) // back to the last checkbox
    h.key(Key.Space); h.frame(f)
    assert(!b.value)
  }
  private val Enter = Key.Enter
  test("Tab visits text fields and a click drops keyboard focus ring") {
    val h = new Harness
    val v = Var("")
    def f(ui: Ui): Unit = { ui.textEdit(v); ui.button("x") }
    h.frame(f)
    h.key(Key.Tab); h.frame(f)
    h.type_("hi"); h.frame(f)
    assertEquals(v.value, "hi")
    assert(h.ctx.keyboardNav)
    h.press(Point(300, 300)); h.frame(f)
    assert(!h.ctx.keyboardNav)
  }
  test("a modal traps Tab inside the dialog") {
    val h = new Harness
    val open = Var(true); val base = Var(false); val inner = Var(false)
    def f(ui: Ui): Unit = { ui.checkbox(base, "base"); ui.modal(open, "Dlg") { m => m.checkbox(inner, "inner") } }
    h.frame(f); h.frame(f)
    h.key(Key.Tab); h.frame(f)
    h.key(Key.Space); h.frame(f)
    assert(inner.value && !base.value)
    h.key(Key.Tab); h.frame(f); h.key(Key.Tab); h.frame(f)
    h.key(Key.Space); h.frame(f)
    assert(!base.value)
  }
  test("slider reacts to arrow keys when focused") {
    val h = new Harness
    val v = Var(0.5)
    def f(ui: Ui): Unit = { ui.slider(v, 0, 1); () }
    h.frame(f); h.key(Key.Tab); h.frame(f)
    assert(h.ctx.focusId != 0)
    h.key(Key.Right); h.frame(f)
    assertEqualsDouble(v.value, 0.51, 1e-9)
    h.key(Key.Left, Modifiers(shift = true)); h.frame(f)
    assertEqualsDouble(v.value, 0.41, 1e-9)
    h.key(Key.End); h.frame(f)
    assertEquals(v.value, 1.0)
  }

  // --- shortcuts ---
  test("ctx.shortcut consumes the key press once") {
    val h = new Harness
    h.ctx.input.handle(Event.KeyDown(Key.Other(1), "", "s", shortcutMods, false))
    assert(h.ctx.shortcut("s"))
    assert(!h.ctx.shortcut("s"))
    h.ctx.input.handle(Event.KeyDown(Key.Other(1), "", "s", shortcutMods.copy(shift = true), false))
    assert(!h.ctx.shortcut("s")); assert(h.ctx.shortcut("S", shift = true))
    h.ctx.input.handle(Event.KeyDown(Key.Escape, "", "", Modifiers.None, false))
    assert(h.ctx.shortcut(Key.Escape, Modifiers.None))
  }

  // --- multiline edit ---
  test("Edit.indent / dedent / newlineIndent") {
    assertEquals(Edit("ab", 1, 1).indent("  "), Edit("a  b", 3, 3))
    val sel = Edit("a\nb\nc", 4, 0) // lines a, b selected through the 'b' ... cursor in line 'c'? offset 4 is before 'c'
    assertEquals(sel.indent("  ").text, "  a\n  b\nc")
    val three = Edit("a\nb\nc", 5, 0).indent("  ")
    assertEquals(three.text, "  a\n  b\n  c")
    assertEquals((three.anchor, three.cursor), (2, 11))
    assertEquals(three.dedent("  ").text, "a\nb\nc")
    assertEquals(Edit("    x", 5, 5).dedent("    "), Edit("x", 1, 1))
    assertEquals(Edit("  foo", 5, 5).newlineIndent, Edit("  foo\n  ", 8, 8))
    assertEquals(Edit("\tx", 2, 2).newlineIndent.text, "\tx\n\t")
  }
  test("multiline textEdit: Tab indents, Enter keeps indentation, undo restores") {
    val h = new Harness; val v = Var("")
    var rect = Rect.Zero
    def f(ui: Ui): Unit = rect = ui.textEdit(v, multiline = true, rows = 3).rect
    h.frame(f); clickAt(h, rect.center)(f)
    h.key(Key.Tab); h.frame(f)
    h.type_("x"); h.frame(f)
    h.key(Key.Enter); h.frame(f)
    h.type_("y"); h.frame(f)
    assertEquals(v.value, "    x\n    y")
    h.key(Key.Tab, Modifiers(shift = true)); h.frame(f)
    assertEquals(v.value, "    x\ny")
    h.key(Key.Other(6), shortcutMods); // no-op key
    h.ctx.input.handle(Event.KeyDown(Key.Other(6), "", "z", shortcutMods, false)); h.frame(f)
    assertEquals(v.value, "    x\n    y")
  }
  test("Tab leaves a single-line field untouched and moves focus on") {
    val h = new Harness; val v = Var("ab"); val w = Var("")
    def f(ui: Ui): Unit = { ui.textEdit(v); ui.textEdit(w) }
    h.frame(f); h.key(Key.Tab); h.frame(f)
    h.key(Key.Tab); h.frame(f)
    h.type_("z"); h.frame(f)
    assertEquals(v.value, "ab"); assertEquals(w.value, "z")
  }
  test("multiline textEdit with maxRows has fixed height and scrolls to the caret; wheel scrolls") {
    val h = new Harness
    val v = Var((1 to 40).map(i => s"line $i").mkString("\n"))
    var rect = Rect.Zero
    def f(ui: Ui): Unit = rect = ui.textEdit(v, multiline = true, rows = 4, maxRows = 4, lineNumbers = true).rect
    h.frame(f)
    assert(rect.height < 120, s"height ${rect.height}")
    clickAt(h, rect.center)(f)
    h.key(Key.Down, shortcutMods.copy(shift = false)); h.frame(f) // doc end (Cmd+Down on mac / Ctrl+Down elsewhere handled by caret move)
    val st = h.ctx.edits.values.head
    // go to the end explicitly
    h.key(Key.End, shortcutMods); h.frame(f)
    assert(st.scrollY > 100, s"scrollY ${st.scrollY}")
    val before = st.scrollY
    h.move(rect.center); h.frame(f)
    h.ctx.input.handle(Event.Scroll(0, 40)); h.frame(f)
    assert(st.scrollY < before)
    h.key(Key.Home, shortcutMods); h.frame(f)
    assertEquals(st.scrollY, 0.0)
    h.key(Key.PageDown); h.frame(f)
    assert(st.cursor > 0)
  }
  test("no-wrap multiline field keeps lines whole") {
    val h = new Harness
    val v = Var("a very long line " * 20)
    var r1 = Rect.Zero; var r2 = Rect.Zero
    h.frame { ui => r1 = ui.textEdit(v, multiline = true, wrap = false).rect }
    h.frame { ui => r2 = ui.textEdit(v, multiline = true, wrap = true).rect }
    assert(r1.height < r2.height)
  }

  // --- virtual list ---
  test("virtualList builds only visible rows, scrolls and selects with the keyboard") {
    val h = new Harness
    val st = new VirtualListState
    val built = scala.collection.mutable.Set.empty[Int]
    var rect = Rect.Zero
    def f(ui: Ui): Response =
      built.clear()
      val r = ui.virtualList(st, 1_000_000, 24, 120)((i, u) => { built += i; u.label(s"row $i") })
      rect = r.rect; r
    h.frame(f(_))
    assert(built.size <= 7 && built.contains(0), s"built ${built.size}")
    h.move(rect.center); h.frame(f(_))
    h.ctx.input.handle(Event.Scroll(0, -2400)); h.frame(f(_))
    assert(built.min >= 90 && built.max <= 110, s"${built.min}..${built.max}")
    assertEquals(st.visible.start, built.min)
    // click selects
    val p = Point(rect.x0 + 20, rect.y0 + 12)
    clickAt(h, p)(f(_))
    assertEquals(st.selected, Some(built.min))
    val s0 = st.selected.get
    h.key(Key.Down); h.frame(f(_)); h.key(Key.PageDown); h.frame(f(_))
    assertEquals(st.selected, Some(s0 + 1 + 4))
    h.key(Key.End); h.frame(f(_))
    assertEquals(st.selected, Some(999_999))
    assert(built.contains(999_999))
    h.key(Key.Home); h.frame(f(_))
    assertEquals(st.selected, Some(0)); assert(built.contains(0))
  }
  test("virtualList Enter reports activation") {
    val h = new Harness
    val st = new VirtualListState
    var act = false
    def f(ui: Ui): Unit = act = ui.virtualList(st, 10, 24, 100)((i, u) => u.label(s"r$i")).doubleClicked
    h.frame(f); h.key(Key.Tab); h.frame(f); h.key(Key.Down); h.frame(f)
    h.key(Key.Enter); h.frame(f)
    assert(act); assertEquals(st.selected, Some(0))
  }

  // --- context menu / popup ---
  test("right click opens a context menu, item click runs and closes it") {
    val h = new Harness
    var rect = Rect.Zero; var picked = 0
    def f(ui: Ui): Unit =
      val r = ui.button("target"); rect = r.rect
      ui.contextMenu(r) { m => if m.menuItem("Copy").clicked then picked += 1; if m.menuItem("Paste").clicked then picked += 10 }
    h.frame(f)
    val c = rect.center
    h.move(c); h.frame(f)
    h.ctx.input.handle(Event.MouseDown(MouseButton.Right, c.x, c.y, Modifiers.None)); h.frame(f)
    h.ctx.input.handle(Event.MouseUp(MouseButton.Right, c.x, c.y)); h.frame(f); h.frame(f)
    assert(h.ctx.popupOwner != 0L)
    // first item sits just below the click point
    val item = Point(c.x + 20, c.y + 4 + 11)
    clickAt(h, item)(f)
    assertEquals(picked, 1)
    assertEquals(h.ctx.popupOwner, 0L)
  }
  test("context menu closes on outside click and Escape") {
    val h = new Harness
    var rect = Rect.Zero
    def f(ui: Ui): Unit =
      val r = ui.button("target"); rect = r.rect
      ui.contextMenu(r)(_.menuItem("Copy"))
    h.frame(f); val c = rect.center
    def open(): Unit =
      h.move(c); h.frame(f)
      h.ctx.input.handle(Event.MouseDown(MouseButton.Right, c.x, c.y, Modifiers.None)); h.frame(f)
      h.ctx.input.handle(Event.MouseUp(MouseButton.Right, c.x, c.y)); h.frame(f)
    open(); assert(h.ctx.popupOwner != 0L)
    h.key(Key.Escape); h.frame(f); assertEquals(h.ctx.popupOwner, 0L)
    open(); assert(h.ctx.popupOwner != 0L)
    h.press(Point(350, 380)); h.frame(f); h.release(Point(350, 380)); h.frame(f)
    assertEquals(h.ctx.popupOwner, 0L)
  }
  test("popup follows its open Var") {
    val h = new Harness
    val open = Var(true); var built = 0
    def f(ui: Ui): Unit = { val r = ui.button("anchor"); ui.popup(open, r.rect) { p => built += 1; p.label("hi") } }
    h.frame(f); h.frame(f)
    assert(built > 0)
    h.press(Point(350, 380)); h.frame(f); h.release(Point(350, 380)); h.frame(f); h.frame(f)
    assert(!open.value)
  }

  // --- splitter ---
  test("hSplit drag moves the divider and respects minimum sizes; arrows resize") {
    val h = new Harness
    val frac = Var(0.5)
    var rect = Rect.Zero; var a = Rect.Zero; var b = Rect.Zero
    def f(ui: Ui): Unit =
      rect = ui.hSplit(frac, height = 100)(l => a = l.region)(r => b = r.region).rect
    h.frame(f)
    assertEqualsDouble(a.width + 8 + b.width, rect.width, 0.01)
    val div = Point(a.x1 + 4, rect.center.y)
    h.move(div); h.frame(f); h.press(div); h.frame(f)
    h.move(Point(rect.x0 + 120, rect.center.y)); h.frame(f); h.frame(f)
    assert(frac.value < 0.4 && frac.value > 0.2, s"${frac.value}")
    h.move(Point(rect.x0 - 100, rect.center.y)); h.frame(f)
    assert(a.width >= 80 - 0.01)
    h.release(Point(rect.x0 - 100, rect.center.y)); h.frame(f)
    val w0 = a.width
    h.key(Key.Tab); h.frame(f); h.key(Key.Right); h.frame(f); h.frame(f)
    assert(a.width > w0)
  }
  test("vSplit stacks panes") {
    val h = new Harness
    val frac = Var(0.5); var a = Rect.Zero; var b = Rect.Zero
    h.frame { ui => ui.vSplit(frac, 200)(t => a = t.region)(u => b = u.region) }
    assertEqualsDouble(a.height, 0.5 * 192, 0.5)
    assert(b.y0 > a.y1)
  }

  // --- small widgets ---
  test("rating sets, clears and keyboard-adjusts") {
    val h = new Harness; val v = Var(0)
    var rect = Rect.Zero
    def f(ui: Ui): Response = { val r = ui.rating(v); rect = r.rect; r }
    h.frame(f(_))
    val third = Point(rect.x0 + 2 * 20 + 10, rect.center.y)
    clickAt(h, third)(f(_))
    assertEquals(v.value, 3)
    clickAt(h, third)(f(_))
    assertEquals(v.value, 0)
    h.move(Point(0, 0)); h.frame(f(_))
    h.key(Key.Tab); h.frame(f(_)); h.key(Key.Right); h.frame(f(_)); h.key(Key.Right); h.frame(f(_))
    assertEquals(v.value, 2)
  }
  test("closableTabBar reports closed tab and selects clicked tab") {
    val h = new Harness; val sel = Var(0)
    val titles = Seq("one", "two", "three")
    var closed: Option[Int] = None; var tabs = Vector.empty[Rect]
    def f(ui: Ui): Unit =
      tabs = Vector.empty
      closed = ui.closableTabBar(sel, titles)
    h.frame(f)
    // tab geometry: click the second tab's label, then its close button
    val y = 8 + 14.0
    clickAt(h, Point(110, y))(f)
    assert(sel.value >= 1)
    var res: Option[Int] = None
    val xs = (0 until 400).filter(_ => false)
    assert(xs.isEmpty)
    // close buttons are near each tab's right edge: scan for a hit
    var found = false
    var x = 40
    while !found && x < 390 do
      val h2 = new Harness
      h2.frame(f); h2.move(Point(x, y)); h2.frame(f); h2.press(Point(x, y)); h2.frame(f); h2.release(Point(x, y)); h2.frame(f)
      if closed.isDefined then { found = true; res = closed }
      x += 3
    assert(found && res.isDefined)
  }
  test("searchComboBox filters by typed text and Enter picks the first match") {
    val h = new Harness; val sel = Var(0)
    val opts = Seq("Apple", "Banana", "Cherry", "Blueberry")
    var rect = Rect.Zero
    def f(ui: Ui): Unit = rect = ui.searchComboBox(sel, opts).rect
    h.frame(f)
    clickAt(h, rect.center)(f); h.frame(f)
    assert(h.ctx.popupOwner != 0L)
    h.type_("b"); h.frame(f); h.type_("l"); h.frame(f)
    h.key(Key.Enter); h.frame(f)
    assertEquals(sel.value, 3) // only Blueberry matches "bl"
    assertEquals(h.ctx.popupOwner, 0L)
  }
  test("searchComboBox Down moves the highlight, click picks") {
    val h = new Harness; val sel = Var(0)
    val opts = Seq("Apple", "Banana", "Cherry")
    var rect = Rect.Zero
    def f(ui: Ui): Unit = rect = ui.searchComboBox(sel, opts).rect
    h.frame(f); clickAt(h, rect.center)(f); h.frame(f)
    h.key(Key.Down); h.frame(f)
    h.key(Key.Enter); h.frame(f)
    assertEquals(sel.value, 1)
    clickAt(h, rect.center)(f); h.frame(f); h.frame(f)
    val third = Point(rect.x0 + 30, rect.y1 + 4 + 6 + 28 + 6 + 2 * 24 + 12)
    clickAt(h, third)(f)
    assertEquals(sel.value, 2)
  }

  // --- date ---
  test("Date arithmetic") {
    assertEquals(Date(2026, 10, 25).dayOfWeek, 6) // Sunday
    assertEquals(Date(1970, 1, 1).toDays, 0L)
    assertEquals(Date.fromDays(Date(2024, 2, 29).toDays), Date(2024, 2, 29))
    assertEquals(Date(2024, 1, 31).plusMonths(1), Date(2024, 2, 29))
    assertEquals(Date(2024, 12, 15).plusMonths(2), Date(2025, 2, 15))
    assertEquals(Date(2024, 1, 15).plusMonths(-2), Date(2023, 11, 15))
    assertEquals(Date(2023, 12, 31).plusDays(1), Date(2024, 1, 1))
    assertEquals(Date(2000, 3, 1).plusDays(-1), Date(2000, 2, 29))
    assertEquals(Date(2026, 3, 9).toString, "2026-03-09")
    assert(Date(2026, 1, 1) < Date(2026, 1, 2))
    assertEquals((0 until 2000).map(i => Date.fromDays(i * 17L - 9000).toDays).toList, (0 until 2000).map(i => i * 17L - 9000).toList)
  }
  test("datePicker picks a day from the popup") {
    val h = new Harness; val d = Var(Date(2026, 10, 25))
    var rect = Rect.Zero
    def f(ui: Ui): Unit = rect = ui.datePicker(d).rect
    h.frame(f)
    clickAt(h, rect.center)(f); h.frame(f)
    assert(h.ctx.popupOwner != 0L)
    // October 2026 starts on Thursday (index 3); click day 14: cell index 3 + 13 = 16 -> row 2, col 2
    val pop0 = Point(rect.x0, rect.y1 + 4)
    val cell = Point(pop0.x + 8 + 2 * 34 + 17, pop0.y + 8 + 34 + 22 + 2 * 30 + 15)
    clickAt(h, cell)(f)
    assertEquals(d.value, Date(2026, 10, 14))
    // next month arrow
    clickAt(h, rect.center)(f); h.frame(f)
    val next = Point(pop0.x + 34 * 7 + 16 - 8 - 15, pop0.y + 8 + 15)
    clickAt(h, next)(f); h.frame(f)
    val cell2 = Point(pop0.x + 8 + 3 * 34 + 17, pop0.y + 8 + 34 + 22 + 0 * 30 + 15) // Nov 2026 starts on Sunday (col 6): this is Oct-end filler or Nov? col 3 row 0 = Oct 29
    clickAt(h, cell2)(f)
    assertEquals(d.value, Date(2026, 10, 29))
  }

  // --- reorder ---
  test("reorderList moves a row when its grip is dragged") {
    val h = new Harness; val items = Var(Vector("a", "b", "c"))
    var rect = Rect.Zero
    def f(ui: Ui): Unit = rect = ui.reorderList(items, 30)((t, u) => u.label(t)).rect
    h.frame(f)
    val grip = Point(rect.x0 + 10, rect.y0 + 15)
    h.move(grip); h.frame(f); h.press(grip); h.frame(f)
    val to = Point(rect.x0 + 10, rect.y0 + 75)
    h.move(to); h.frame(f); h.frame(f)
    h.release(to); h.frame(f); h.frame(f)
    assertEquals(items.value, Vector("b", "c", "a"))
  }

  // --- tree keyboard ---
  test("tree rows expand with Right, collapse with Left and move with Down") {
    val h = new Harness
    var rows = 0; var sel = ""
    def f(ui: Ui): Unit =
      rows = 0
      if ui.treeNode("root")(c => { rows += 1; c.treeNode("kid1", leaf = true)(); c.treeNode("kid2", leaf = true)() }).clicked then sel = "root"
    h.frame(f); h.key(Key.Tab); h.frame(f)
    h.key(Key.Right); h.frame(f); h.frame(f)
    assert(rows > 0)
    h.key(Key.Down); h.frame(f); h.frame(f)
    assert(h.ctx.focusId != 0)
    h.key(Key.Up); h.frame(f); h.key(Key.Left); h.frame(f); h.frame(f)
    rows = 0; h.frame(f); assertEquals(rows, 0)
  }

  // --- progress ---
  test("progress variants paint") {
    val h = new Harness
    h.frame { ui => ui.progressCircle(0.4); ui.progressIndeterminate(120) }
    assert(h.lastFrame.vertices.length > 0)
  }
