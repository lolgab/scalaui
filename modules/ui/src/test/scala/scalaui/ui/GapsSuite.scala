package scalaui.ui

import scalaui.geom.*
import scalaui.text.Span
import scalaui.window.*

/** Tree widget, rich-text links, variable-height lists and wheel consumption by nested scrollables. */
class GapsSuite extends scalaui.testkit.LoggedSuite:
  private val shortcutMods = if scala.util.Properties.isMac then Modifiers(command = true) else Modifiers(control = true)

  private def clickAt(h: Harness, p: Point, mods: Modifiers = Modifiers.None)(f: Ui => Any): Unit =
    h.move(p); h.frame(f)
    h.ctx.input.handle(Event.MouseDown(MouseButton.Left, p.x, p.y, mods)); h.frame(f)
    h.release(p); h.frame(f)

  // --- tree ---
  final case class N(name: String, kids: Seq[N] = Nil)
  private val data = Seq(
    N("src", Seq(N("alpha.scala"), N("beta.scala"), N("util", Seq(N("strings.scala"))))),
    N("docs", Seq(N("guide.md"))),
    N("README"))

  private class TreeRig(multi: Boolean = false, checks: Boolean = false, drag: Boolean = false):
    val h = new Harness
    val st = new TreeState
    var resp: Response = Response(Rect.Zero)
    def f(ui: Ui): Unit =
      resp = ui.tree[N](st, data, _.kids, _.name, 160, key = _.name, multiSelect = multi, checkboxes = checks,
        dragPayload = if drag then Some((n: N) => n.name) else None, canDrop = if drag then Some((n: N, p: Any) => n.kids.nonEmpty) else None)
    def rowH: Double = h.ctx.style.minHeight
    def row(i: Int): Point = Point(resp.rect.x0 + 60, resp.rect.y0 + (i + 0.5) * rowH)
    def arrow(depth: Int, i: Int): Point = Point(resp.rect.x0 + 4 + depth * 18 + 8, resp.rect.y0 + (i + 0.5) * rowH)
    def frame(): Unit = h.frame(f)
    def click(p: Point, mods: Modifiers = Modifiers.None): Unit = clickAt(h, p, mods)(f)
    def key(k: Key, mods: Modifiers = Modifiers.None): Unit = { h.key(k, mods); frame() }
    frame()

  test("tree: click selects, arrow click expands, expansion persists across frames") {
    val t = new TreeRig
    t.click(t.row(0))
    assertEquals(t.st.selected.toSeq, Seq[Any]("src"))
    assert(!t.st.isExpanded("src"))
    t.click(t.arrow(0, 0))
    assert(t.st.isExpanded("src"))
    assertEquals(t.st.selected.toSeq, Seq[Any]("src")) // arrow click leaves the selection alone
    for _ <- 0 until 5 do t.frame()
    assert(t.st.isExpanded("src"))
    t.click(t.row(2)) // beta.scala (src, alpha, beta, util, docs, README)
    assertEquals(t.st.selected.toSeq, Seq[Any]("beta.scala"))
    assert(t.resp.focused)
  }
  test("tree: initiallyExpanded applies once and can be collapsed afterwards") {
    val h = new Harness
    val st = new TreeState
    def f(ui: Ui): Unit = ui.tree[N](st, data, _.kids, _.name, 160, key = _.name, initiallyExpanded = _.name == "src")
    h.frame(f)
    assert(st.isExpanded("src") && !st.isExpanded("docs"))
    st.collapse("src"); h.frame(f); h.frame(f)
    assert(!st.isExpanded("src"))
  }
  test("tree: multi-select with ctrl and shift clicks") {
    val t = new TreeRig(multi = true)
    t.st.expand("src"); t.frame()
    t.click(t.row(1)) // alpha
    t.click(t.row(3), shortcutMods) // util
    assertEquals(t.st.selected.toSet, Set[Any]("alpha.scala", "util"))
    t.click(t.row(2), shortcutMods) // beta
    t.click(t.row(1), shortcutMods) // toggles alpha off
    assertEquals(t.st.selected.toSet, Set[Any]("beta.scala", "util"))
    t.click(t.row(5), Modifiers(shift = true)) // anchor (alpha) .. README replaces the selection
    assertEquals(t.st.selected.toSet, Set[Any]("alpha.scala", "beta.scala", "util", "docs", "README"))
    t.click(t.row(4)) // plain click collapses back to one
    assertEquals(t.st.selected.size, 1)
    assertEquals(t.st.selected.head, t.st.cursor.get)
  }
  test("tree: single select ignores modifiers") {
    val t = new TreeRig
    t.click(t.row(0)); t.click(t.row(1), shortcutMods)
    assertEquals(t.st.selected.toSeq, Seq[Any]("docs"))
  }
  test("tree: keyboard navigation") {
    val t = new TreeRig(multi = true)
    t.click(t.row(0)) // src, focused
    t.key(Key.Right) // expand
    assert(t.st.isExpanded("src"))
    t.key(Key.Right) // first child
    assertEquals(t.st.selected.toSeq, Seq[Any]("alpha.scala"))
    t.key(Key.Down); t.key(Key.Down)
    assertEquals(t.st.selected.toSeq, Seq[Any]("util"))
    t.key(Key.Right); t.key(Key.Right)
    assertEquals(t.st.selected.toSeq, Seq[Any]("strings.scala"))
    t.key(Key.Left) // to parent
    assertEquals(t.st.selected.toSeq, Seq[Any]("util"))
    t.key(Key.Left) // collapse util
    assert(!t.st.isExpanded("util"))
    t.key(Key.Left) // to src
    assertEquals(t.st.selected.toSeq, Seq[Any]("src"))
    t.key(Key.Left) // collapse src
    assert(!t.st.isExpanded("src"))
    t.key(Key.End); assertEquals(t.st.selected.toSeq, Seq[Any]("README"))
    t.key(Key.Home); assertEquals(t.st.selected.toSeq, Seq[Any]("src"))
    t.key(Key.Down, Modifiers(shift = true)); t.key(Key.Down, Modifiers(shift = true))
    assertEquals(t.st.selected.toSeq, Seq[Any]("src", "docs", "README"))
    t.key(Key.Up, Modifiers(shift = true))
    assertEquals(t.st.selected.toSeq, Seq[Any]("src", "docs"))
    t.key(Key.Down) // plain move collapses the range
    assertEquals(t.st.selected.toSeq, Seq[Any]("README"))
  }
  test("tree: type-ahead jumps to the next row starting with the typed text") {
    val t = new TreeRig
    t.click(t.row(0))
    t.h.type_("d"); t.frame()
    assertEquals(t.st.selected.toSeq, Seq[Any]("docs"))
    t.h.type_("r"); t.frame() // "dr" does not match, selection unchanged
    assertEquals(t.st.selected.toSeq, Seq[Any]("docs"))
    t.st.typedAt = -10
    t.h.type_("r"); t.frame()
    assertEquals(t.st.selected.toSeq, Seq[Any]("README"))
  }
  test("tree: Enter and double click activate") {
    val t = new TreeRig
    t.click(t.row(2)) // README
    t.key(Key.Enter)
    assertEquals(t.st.activated, Some[Any]("README"))
    assert(t.resp.doubleClicked)
    t.frame()
    assertEquals(t.st.activated, None)
    // double click on the first row activates and expands it
    val p = t.row(0)
    t.h.move(p); t.frame()
    t.h.press(p); t.frame(); t.h.release(p); t.frame()
    t.h.press(p); t.frame()
    assertEquals(t.st.activated, Some[Any]("src"))
    assert(t.st.isExpanded("src"))
  }
  test("tree: checkboxes toggle independently of selection, Space toggles the cursor row") {
    val t = new TreeRig(checks = true)
    val box = Point(t.resp.rect.x0 + 4 + 18 + 7, t.resp.rect.y0 + 0.5 * t.rowH)
    t.click(box)
    assertEquals(t.st.checked.toSet, Set[Any]("src"))
    assert(t.st.selected.isEmpty)
    t.click(t.row(1))
    t.key(Key.Space)
    assertEquals(t.st.checked.toSet, Set[Any]("src", "docs"))
    assertEquals(t.st.toggledCheck, Some[Any]("docs"))
    t.key(Key.Space)
    assertEquals(t.st.checked.toSet, Set[Any]("src"))
  }
  test("tree: scrolls and reveals the keyboard cursor") {
    val h = new Harness
    val st = new TreeState
    val many = (0 until 200).map(i => N(s"item$i"))
    var r = Response(Rect.Zero)
    def f(ui: Ui): Unit = r = ui.tree[N](st, many, _.kids, _.name, 120, key = _.name)
    h.frame(f)
    clickAt(h, Point(r.rect.x0 + 60, r.rect.y0 + 10))(f)
    h.key(Key.End); h.frame(f)
    assertEquals(st.selected.toSeq, Seq[Any]("item199"))
    assert(st.scroll > 4000, s"scroll ${st.scroll}")
    h.key(Key.Home); h.frame(f)
    assertEquals(st.scroll, 0.0)
  }
  test("tree: drag a row onto another reports the drop") {
    val t = new TreeRig(drag = true)
    val from = t.row(2) // README
    val to = t.row(1) // docs (has children, accepts)
    t.h.move(from); t.frame()
    t.h.press(from); t.frame()
    t.h.move(Point(from.x, from.y - 12)); t.frame()
    t.h.move(to); t.frame()
    t.h.release(to); t.frame()
    assertEquals(t.st.dropped, Some[(Any, Any)](("docs", "README")))
  }

  // --- links ---
  private val long = "Read the documentation about layouts and also the "
  private def parts(ui: Ui): Seq[Span | RichLink] =
    Seq(ui.span(long + long), ui.link("link that wraps over lines because it is long enough", "wrap"), ui.span(" and then "), ui.link("short", "short"), ui.span(" ends."))

  test("richLinks: links are laid out on several lines, clickable, pointer cursor, focusable, with link access role") {
    val h = new Harness
    var resp = Response(Rect.Zero)
    var got = List.empty[String]
    def f(ui: Ui): Unit = resp = ui.richLinks(parts(ui))(t => got ::= t)
    h.frame(f)
    val accessLinks = h.ctx.accessNodes.filter(_.role == AccessRole.Link)
    assertEquals(accessLinks.map(_.value).toList, List("wrap", "short"))
    // the long link wraps: its bounding box is taller than one text line
    val lineH = h.ctx.layout("Ag", h.ctx.style.fontSize).height
    assert(accessLinks.head.bounds.height > lineH * 1.5, s"${accessLinks.head.bounds}")
    // hover shows a pointer
    val short = accessLinks(1).bounds
    h.move(short.center); h.frame(f)
    assertEquals(h.ctx.cursorIcon, CursorIcon.Pointer)
    h.move(Point(resp.rect.x0 + 2, resp.rect.y0 + 2)); h.frame(f)
    assertEquals(h.ctx.cursorIcon, CursorIcon.Default)
    // clicking the first fragment and the continuation both report the link id
    val fragments = RichLinks.fragments(scalaui.text.RichLayout.layout(
      parts(new Ui(h.ctx, Rect(10, 8, 390, 400), true, 1L)).map { case s: Span => s; case l: RichLink => l.span }, Some(380.0)),
      (long + long).length, (long + long).length + "link that wraps over lines because it is long enough".length)
    assert(fragments.length >= 2, s"fragments ${fragments.length}")
    val origin = resp.rect.origin
    for fr <- fragments do
      got = Nil
      val p = Point(origin.x + fr.center.x, origin.y + fr.center.y)
      clickAt(h, p)(f)
      assertEquals(got, List("wrap"))
    got = Nil
    clickAt(h, short.center)(f)
    assertEquals(got, List("short"))
  }
  test("richLinks: Tab focuses links in order and Enter activates") {
    val h = new Harness
    var got = List.empty[String]
    def f(ui: Ui): Unit = ui.richLinks(parts(ui))(t => got ::= t)
    h.frame(f)
    h.key(Key.Tab); h.frame(f); h.frame(f)
    assert(h.ctx.accessNodes.filter(_.role == AccessRole.Link).head.focused)
    h.key(Key.Enter); h.frame(f)
    assertEquals(got, List("wrap"))
    h.key(Key.Tab); h.frame(f)
    h.key(Key.Enter); h.frame(f)
    assertEquals(got, List("short", "wrap"))
  }
  test("richLinks: click away from links does nothing and underline is part of link style") {
    val h = new Harness
    var got = 0
    var resp = Response(Rect.Zero)
    def f(ui: Ui): Unit = resp = ui.richLinks(parts(ui))(_ => got += 1)
    h.frame(f)
    clickAt(h, Point(resp.rect.x0 + 3, resp.rect.y0 + 3))(f)
    assertEquals(got, 0)
    assert(resp.link.isEmpty)
    // link spans are underlined
    assert(new Ui(h.ctx, Rect.Zero, true, 1L).link("x", "y").span.style.underline)
  }

  // --- variable-height lists ---
  private def hOf(i: Int): Double = if i % 3 == 0 then 60.0 else 20.0 + (i % 4) * 5

  test("variableList lays rows out with their own heights and builds only visible rows") {
    val h = new Harness
    val st = new VirtualListState
    val built = collection.mutable.Map.empty[Int, Double] // row -> y0
    var rect = Rect.Zero
    def f(ui: Ui): Response =
      built.clear()
      val r = ui.variableList(st, 100_000, hOf, 150)((i, u) => { built(i) = u.region.y0; u.label(s"row $i") })
      rect = r.rect; r
    h.frame(f(_)); h.frame(f(_))
    assert(built.size < 12 && built.contains(0), s"${built.keys.toSeq.sorted}")
    // row 1 starts after row 0's 60px
    assertEqualsDouble(built(1) - built(0), 60.0, 0.5)
    assertEqualsDouble(built(2) - built(1), hOf(1), 0.5)
    h.move(rect.center); h.frame(f(_))
    h.ctx.input.handle(Event.Scroll(0, -3000)); h.frame(f(_)); h.frame(f(_))
    assert(built.keys.min > 20 && built.size < 12, s"${built.keys.toSeq.sorted}")
    assertEquals(st.visible.start, built.keys.min)
  }
  test("variableList scrollTo puts any row in view, even far away, and alignTop works") {
    val h = new Harness
    val st = new VirtualListState
    var rect = Rect.Zero
    val built = collection.mutable.Map.empty[Int, Double]
    def f(ui: Ui): Unit =
      built.clear()
      rect = ui.variableList(st, 5000, hOf, 150)((i, u) => { built(i) = u.region.y0; u.label(s"row $i") }).rect
    h.frame(f); st.scrollTo(4321); for _ <- 0 until 4 do h.frame(f)
    assert(built.contains(4321), s"${built.keys.toSeq.sorted}")
    val y = built(4321)
    assert(y >= rect.y0 - 1 && y + hOf(4321) <= rect.y1 + 1, s"y=$y rect=$rect")
    st.scrollTo(1000, alignTop = true); for _ <- 0 until 4 do h.frame(f)
    assertEqualsDouble(built(1000), rect.y0 + 1, 1.0)
    st.scrollTo(0); for _ <- 0 until 4 do h.frame(f)
    assertEquals(st.scroll, 0.0)
  }
  test("variableList keyboard selection reveals rows and Enter activates") {
    val h = new Harness
    val st = new VirtualListState
    var r = Response(Rect.Zero)
    val built = collection.mutable.Set.empty[Int]
    def f(ui: Ui): Unit = { built.clear(); r = ui.variableList(st, 300, hOf, 120)((i, u) => { built += i; u.label(s"r$i") }) }
    h.frame(f); h.key(Key.Tab); h.frame(f)
    h.key(Key.Down); h.frame(f)
    assertEquals(st.selected, Some(0))
    for _ <- 0 until 10 do { h.key(Key.Down); h.frame(f); h.frame(f) }
    assertEquals(st.selected, Some(10))
    assert(built.contains(10), s"$built")
    h.key(Key.End); h.frame(f); h.frame(f); h.frame(f)
    assertEquals(st.selected, Some(299)); assert(built.contains(299))
    h.key(Key.Enter); h.frame(f)
    assert(r.doubleClicked)
    h.key(Key.Home); h.frame(f); h.frame(f)
    assertEquals(st.scroll, 0.0)
    h.key(Key.PageDown); h.frame(f)
    assert(st.selected.get >= 3)
  }
  test("measuredList measures rows, keeps the scroll anchored while rows above are measured, and tracks heights") {
    val h = new Harness
    val st = new VirtualListState
    var rect = Rect.Zero
    val ys = collection.mutable.Map.empty[Int, Double]
    def f(ui: Ui): Unit =
      ys.clear()
      rect = ui.measuredList(st, 2000, 150, estimate = 28)((i, u) => { ys(i) = u.region.y0; u.allocate(100, hOf(i)); () }).rect
    h.frame(f); h.frame(f); h.frame(f)
    // measured heights are applied: row 1 sits below the 60px row 0 (plus one px padding each side)
    assertEqualsDouble(ys(1) - ys(0), 60.0 + 2, 1.0)
    assert(st.knownHeights >= 3)
    st.scrollTo(900, alignTop = true); for _ <- 0 until 8 do h.frame(f)
    assertEqualsDouble(ys(900), rect.y0 + 2, 2.0)
    // scroll up in small steps: the top row of the previous frame never jumps by more than the wheel step plus rounding
    h.move(rect.center); h.frame(f)
    val anchor = 900
    var prev = ys(anchor)
    for _ <- 0 until 12 do
      h.ctx.input.handle(Event.Scroll(0, 7)); h.frame(f); h.frame(f)
      val y = ys(anchor)
      assertEqualsDouble(y - prev, 7.0, 1.5, s"anchor moved from $prev to $y")
      prev = y
  }
  test("variable lists: invalidateHeights re-measures") {
    val h = new Harness
    val st = new VirtualListState
    var tall = false
    val ys = collection.mutable.Map.empty[Int, Double]
    def f(ui: Ui): Unit = { ys.clear(); ui.variableList(st, 50, i => if tall then 40 else 20, 100)((i, u) => ys(i) = u.region.y0); () }
    h.frame(f); h.frame(f)
    assertEqualsDouble(ys(1) - ys(0), 20, 0.5)
    tall = true; st.invalidateHeights(); h.frame(f); h.frame(f)
    assertEqualsDouble(ys(1) - ys(0), 40, 0.5)
  }
  test("RowHeights offsets and lookups") {
    val rh = new RowHeights
    rh.reset(1000, 10)
    assertEquals(rh.total, 10000.0)
    rh.set(3, 50); rh.set(0, 5)
    assertEquals(rh.offset(1), 5.0); assertEquals(rh.offset(4), 5 + 10 + 10 + 50.0)
    assertEquals(rh.rowAt(74.9), 3); assertEquals(rh.rowAt(75.0), 4); assertEquals(rh.rowAt(1e9), 999)
    rh.reset(2, 10) // shrinking drops rows beyond the new count
    assertEquals(rh.total, 5.0 + 10)
  }

  // --- nested wheel handling ---
  test("wheel over a scrollable multiline text field scrolls the field, not the enclosing scroll area; at the end it passes through") {
    val h = new Harness
    val text = Var((0 until 40).map(i => s"line $i").mkString("\n"))
    var fieldRect = Rect.Zero
    var topY = 0.0
    def f(ui: Ui): Unit =
      ui.scrollArea(200) { s =>
        topY = s.cursorPoint.y
        s.label("top")
        fieldRect = s.textEdit(text, multiline = true, rows = 4, maxRows = 4).rect
        for i <- 0 until 30 do s.label(s"filler $i")
      }
    h.frame(f); h.frame(f)
    val top0 = topY
    def areaScroll: Double = top0 - topY
    val field = h.ctx.edits.values.head
    field.cursor = 0; field.anchor = 0; field.scrollY = 0 // the caret starts at the end, which scrolls the field to its bottom
    h.frame(f); h.frame(f)
    h.move(fieldRect.center); h.frame(f)
    assertEquals(areaScroll, 0.0)
    assert(h.ctx.wheelClaimed(-30), s"no claim at ${fieldRect.center}")
    h.ctx.input.handle(Event.Scroll(0, -30)); h.frame(f)
    assertEquals(areaScroll, 0.0)
    assert(field.scrollY > 0)
    val down = field.scrollY
    // scrolling up inside the field also stays in the field
    h.ctx.input.handle(Event.Scroll(0, 10)); h.frame(f)
    assertEquals(areaScroll, 0.0)
    assert(field.scrollY < down)
    // field at its end: wheel down now moves the outer area
    field.scrollY = 1e6; h.frame(f); h.frame(f)
    val max = field.scrollY
    h.ctx.input.handle(Event.Scroll(0, -30)); h.frame(f)
    assertEquals(field.scrollY, max)
    assert(areaScroll > 0, s"area scroll $areaScroll")
  }
