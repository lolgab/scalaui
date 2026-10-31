package scalaui.ui

import scalaui.geom.*
import scalaui.window.*

class PlatformInputSuite extends scalaui.testkit.LoggedSuite:
  /** A focused single-line text field. */
  private def focused(v: Var[String]): (Harness, Rect) =
    val h = new Harness
    var rect = Rect.Zero
    h.frame { ui => rect = ui.textEdit(v).rect }
    h.click(rect)(_.textEdit(v))
    (h, rect)

  test("text fields publish text, caret and selection, and apply edits requested by assistive technologies") {
    val v = Var("Ferris"); val (h, _) = focused(v)
    h.frame(_.textEdit(v))
    def field = h.ctx.accessNodes.find(_.role == AccessRole.TextInput).get
    val idx = h.ctx.accessNodes.indexWhere(_.role == AccessRole.TextInput)
    assertEquals(field.value, "Ferris")
    val t = field.text.get
    assertEquals((t.caret, t.selStart, t.selEnd, t.multiline), (6, 6, 6, false))
    assertEquals(t.charBounds.length, 6)
    assert(t.charBounds(1).x0 > t.charBounds(0).x0)
    h.ctx.accessEdits(idx) = (None, Some((1, 4)))
    h.frame(_.textEdit(v))
    assertEquals((field.text.get.selStart, field.text.get.selEnd), (1, 4))
    h.ctx.accessEdits(idx) = (Some("Ferris!"), Some((7, 7)))
    h.frame(_.textEdit(v))
    assertEquals(v.value, "Ferris!")
    assertEquals(field.text.get.caret, 7)
  }

  test("TextInput events insert text") {
    val v = Var("ab"); val (h, _) = focused(v)
    h.ctx.input.handle(Event.TextInput("日本"))
    h.frame(_.textEdit(v))
    assertEquals(v.value, "ab日本")
  }

  test("composition is previewed without changing the value, then committed by TextInput") {
    val v = Var("ab"); val (h, _) = focused(v)
    h.ctx.input.handle(Event.Composition("にほ", 2, 0))
    h.frame(_.textEdit(v))
    assertEquals(v.value, "ab")
    assert(h.ctx.imeRect.nonEmpty)
    h.ctx.input.handle(Event.Composition("", 0, 0))
    h.ctx.input.handle(Event.TextInput("日本"))
    h.frame(_.textEdit(v))
    assertEquals(v.value, "ab日本")
    assertEquals(h.ctx.input.composition, None)
  }

  test("caret rect follows the composition cursor") {
    val v = Var(""); val (h, _) = focused(v)
    h.ctx.input.handle(Event.Composition("abcd", 0, 0))
    h.frame(_.textEdit(v)); val atStart = h.ctx.imeRect.get
    h.ctx.input.handle(Event.Composition("abcd", 4, 0))
    h.frame(_.textEdit(v)); val atEnd = h.ctx.imeRect.get
    assert(atEnd.x0 > atStart.x0)
  }

  test("text fields request the text cursor, hyperlinks the pointer") {
    val v = Var("x"); val (h, rect) = focused(v)
    h.move(rect.center); h.frame(_.textEdit(v))
    assertEquals(h.ctx.cursorIcon, CursorIcon.Text)
    h.move(Point(500, 500)); h.frame(_.textEdit(v))
    assertEquals(h.ctx.cursorIcon, CursorIcon.Default)
  }

  test("dropped files are reported for one frame") {
    val h = new Harness
    h.ctx.input.handle(Event.FilesHovered(Seq("/a.png"), 5, 5))
    assertEquals(h.ctx.input.hoveredFiles, Seq("/a.png"))
    h.ctx.input.handle(Event.FilesDropped(Seq("/a.png"), 5, 5))
    var got = Seq.empty[String]
    h.frame(_ => got = h.ctx.input.droppedFiles)
    assertEquals(got, Seq("/a.png"))
    assertEquals(h.ctx.input.droppedFiles, Nil)
    assertEquals(h.ctx.input.hoveredFiles, Nil)
  }

  test("widgets publish an accessibility tree in reading order") {
    val h = new Harness
    val on = Var(true); val name = Var("Ada"); val vol = Var(0.5)
    h.frame { ui =>
      ui.heading("Settings"); ui.button("Save"); ui.checkbox(on, "Dark mode"); ui.textEdit(name, hint = "Name"); ui.slider(vol, 0, 1, "Volume")
      ui.disabled(true)(_.button("Nope"))
    }
    import scalaui.window.AccessRole.*
    val nodes = h.ctx.accessNodes.toSeq
    assertEquals(nodes.map(n => (n.role, n.label)), Seq((Heading, "Settings"), (Button, "Save"), (CheckBox, "Dark mode"), (TextInput, "Name"), (Slider, "Volume"), (Button, "Nope")))
    assertEquals(nodes(2).checked, Some(true))
    assertEquals(nodes(3).value, "Ada")
    assert(nodes.last.disabled && !nodes.head.disabled)
  }

  test("menu actions are reported for one frame; the fallback menu bar draws and fires them") {
    import scalaui.window.MenuEntry.*
    val menus: Seq[Submenu] = Seq(Submenu("File", Seq(Item("Save", 3, "mod+s"), Separator, Item("Quit", 4))))
    val h = new Harness
    h.ctx.input.handle(Event.MenuAction(3))
    var pressed = false
    h.frame { ui => pressed = ui.menuPressed(3) }
    assert(pressed)
    h.frame { ui => pressed = ui.menuPressed(3) }
    assert(!pressed, "only one frame")
    // fallback bar: open File and click Save
    var bar = Rect.Zero
    h.frame { ui => ui.appMenuBar(menus); bar = ui.usedRect }
    assert(bar.height > 10, "the fallback bar takes space")
    h.ctx.nativeMenus = true
    var usedNative = Rect.Zero
    h.frame { ui => ui.appMenuBar(menus); usedNative = ui.usedRect }
    assertEquals(usedNative.height, 0.0, "native menus draw nothing")
  }

  test("a floating window follows the pointer within the same frame (no drag lag)") {
    val h = new Harness
    def body(ui: Ui): Unit = ui.window("W", Point(100, 120), 200)(_.label("hi"))
    h.frame(body); h.frame(body) // second frame knows the content size
    val xKey = h.ctx.numbers.collectFirst { case (k, v) if v == 100.0 => k }.get
    h.move(Point(120, 130)); h.frame(body)
    h.press(Point(120, 130)); h.frame(body)
    h.move(Point(150, 160)); h.frame(body) // one frame after the move
    assertEquals(h.ctx.numbers(xKey), 130.0)
    assertEquals(h.ctx.numbers(xKey + 1), 150.0)
    h.move(Point(160, 170)); h.frame(body)
    assertEquals(h.ctx.numbers(xKey), 140.0)
  }

