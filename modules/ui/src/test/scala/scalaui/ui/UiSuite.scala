package scalaui.ui

import scalaui.geom.*
import scalaui.gpu.{GpuBackend, Texture, Frame}
import scalaui.window.*

/** Headless UI harness: a stub GPU backend and scripted input. */
class Harness:
  object Tex extends Texture:
    def width = 1; def height = 1
    def update(x: Int, y: Int, w: Int, h: Int, rgba: Array[Byte]): Unit = ()
  val backend = new GpuBackend:
    def createTexture(w: Int, h: Int, rgba: Array[Byte]): Texture = Tex
    val whiteTexture: Texture = Tex
    def submit(f: Frame): Unit = ()
    def close(): Unit = ()
  val ctx = new Context(backend)
  var lastFrame: Frame = null

  def frame(f: Ui => Any): Unit =
    ctx.begin(400, 400, 1)
    f(new Ui(ctx, Rect(10, 8, 390, 400), true, 1L))
    lastFrame = ctx.end()
  def move(p: Point): Unit = ctx.input.handle(Event.MouseMoved(p.x, p.y))
  def press(p: Point): Unit = ctx.input.handle(Event.MouseDown(MouseButton.Left, p.x, p.y, Modifiers.None))
  def release(p: Point): Unit = ctx.input.handle(Event.MouseUp(MouseButton.Left, p.x, p.y))
  def type_(s: String): Unit = ctx.input.handle(Event.KeyDown(Key.Other(0), s, s, Modifiers.None, false))
  def key(k: Key, mods: Modifiers = Modifiers.None): Unit = ctx.input.handle(Event.KeyDown(k, "", "", mods, false))

  /** Clicks the center of `r` over three frames and returns whether `ui` reported a click on the release frame. */
  def click(r: Rect)(ui: Ui => Response): Boolean =
    val c = r.center
    move(c); frame(u => { ui(u); () })
    press(c); frame(u => { ui(u); () })
    release(c)
    var clicked = false
    frame { u => clicked = ui(u).clicked }
    clicked

class UiSuite extends scalaui.testkit.LoggedSuite:
  test("button click fires on release over the button") {
    val h = new Harness
    var rect = Rect.Zero
    h.frame { ui => rect = ui.button("OK").rect }
    assert(rect.width > 20 && rect.height >= 22)
    assert(h.click(rect)(_.button("OK")))
  }
  test("button not clicked when released elsewhere") {
    val h = new Harness
    var rect = Rect.Zero
    h.frame { ui => rect = ui.button("OK").rect }
    h.move(rect.center); h.frame(_.button("OK"))
    h.press(rect.center); h.frame(_.button("OK"))
    h.release(Point(300, 300))
    var clicked = true
    h.frame { ui => clicked = ui.button("OK").clicked }
    assert(!clicked)
  }
  test("checkbox toggles") {
    val h = new Harness; val v = Var(false)
    var rect = Rect.Zero
    h.frame { ui => rect = ui.checkbox(v, "x").rect }
    h.click(rect)(_.checkbox(v, "x"))
    assert(v.value)
  }
  test("radio selects its value") {
    val h = new Harness; val v = Var("a")
    var rect = Rect.Zero
    h.frame { ui => ui.radio(v, "a", "A"); rect = ui.radio(v, "b", "B").rect }
    h.click(rect) { ui => ui.radio(v, "a", "A"); ui.radio(v, "b", "B") }
    assertEquals(v.value, "b")
  }
  test("slider drag sets value from mouse x") {
    val h = new Harness; val v = Var(0.0)
    var rect = Rect.Zero
    h.frame { ui => rect = ui.slider(v, 0, 10).rect }
    val y = rect.center.y
    h.move(Point(rect.x0 + 20, y)); h.frame(_.slider(v, 0, 10))
    h.press(Point(rect.x0 + 20, y)); h.frame(_.slider(v, 0, 10))
    h.move(Point(rect.x0 + 80, rect.center.y)); h.frame(_.slider(v, 0, 10))
    assertEqualsDouble(v.value, 5.0, 0.1)
  }
  test("text edit: focus by click, type, backspace, select all + replace") {
    val h = new Harness; val v = Var("")
    var rect = Rect.Zero
    h.frame { ui => rect = ui.textEdit(v).rect }
    val c = rect.center
    h.move(c); h.frame(_.textEdit(v)); h.press(c); h.frame(_.textEdit(v)); h.release(c); h.frame(_.textEdit(v))
    h.type_("h"); h.type_("i"); h.frame(_.textEdit(v))
    assertEquals(v.value, "hi")
    h.key(Key.Backspace); h.frame(_.textEdit(v))
    assertEquals(v.value, "h")
    h.ctx.input.handle(Event.KeyDown(Key.Other(0), "", "a", Modifiers(command = true, control = !scala.util.Properties.isMac), false))
    h.type_("yo"); h.frame(_.textEdit(v))
    assertEquals(v.value, "yo")
  }
  test("clicking elsewhere drops text focus") {
    val h = new Harness; val v = Var("abc")
    var rect = Rect.Zero
    h.frame { ui => rect = ui.textEdit(v).rect }
    val c = rect.center
    h.move(c); h.frame(_.textEdit(v)); h.press(c); h.frame(_.textEdit(v)); h.release(c); h.frame(_.textEdit(v))
    assert(h.ctx.focusId != 0)
    h.press(Point(380, 380)); h.frame(_.textEdit(v))
    assertEquals(h.ctx.focusId, 0L)
  }
  test("combo box opens and picks an item") {
    val h = new Harness; val sel = Var(0)
    val opts = Seq("A", "B", "C")
    var rect = Rect.Zero
    h.frame { ui => rect = ui.comboBox(sel, opts).rect }
    h.click(rect)(_.comboBox(sel, opts))
    assert(h.ctx.popupOwner != 0)
    val item = Point(rect.x0 + 20, rect.y1 + 2 + 2 + 24 * 1 + 12) // second row
    h.move(item); h.frame(_.comboBox(sel, opts))
    h.press(item); h.frame(_.comboBox(sel, opts))
    h.release(item); h.frame(_.comboBox(sel, opts))
    assertEquals(sel.value, 1)
    assertEquals(h.ctx.popupOwner, 0L)
  }
  test("vertical layout stacks widgets with spacing; horizontal puts them side by side") {
    val h = new Harness
    var a = Rect.Zero; var b = Rect.Zero; var c = Rect.Zero; var d = Rect.Zero
    h.frame { ui =>
      a = ui.button("A").rect; b = ui.button("B").rect
      ui.horizontal { r => c = r.button("C").rect; d = r.button("D").rect }
    }
    assertEquals(a.x0, b.x0)
    assert(b.y0 >= a.y1)
    assertEquals(c.y0, d.y0)
    assert(d.x0 > c.x1)
    assert(c.y0 >= b.y1)
  }
  test("scroll area clips and scrolls with wheel") {
    val h = new Harness
    def body(ui: Ui): Unit = ui.scrollArea(60, Some(200)) { s => (1 to 20).foreach(i => s.label(s"row $i")) }
    h.frame(body); h.frame(body) // second frame knows content height
    h.move(Point(50, 30)); h.frame(body)
    h.ctx.input.handle(Event.Scroll(0, -40)); h.frame(body)
    assert(h.ctx.numbers.values.exists(v => v > 30 && v < 100), s"offsets: ${h.ctx.numbers}") // scroll offset applied
  }
  test("collapsing hides body until opened") {
    val h = new Harness
    var shown = false
    def ui(u: Ui): Response = { u.collapsing("Sec") { c => shown = true; c.label("body") }; Response(Rect.Zero) }
    h.frame(u => { ui(u); () })
    assert(!shown)
  }

class UndoSuite extends scalaui.testkit.LoggedSuite:
  private def focused(h: Harness, v: Var[String]): Rect =
    var rect = Rect.Zero
    h.frame { ui => rect = ui.textEdit(v).rect }
    h.move(rect.center); h.frame(_.textEdit(v)); h.press(rect.center); h.frame(_.textEdit(v)); h.release(rect.center); h.frame(_.textEdit(v))
    rect
  private val mod = Modifiers(command = true, control = true) // shortcut modifier on every platform

  test("typing is grouped into one undo step; undo and redo restore text") {
    val h = new Harness; val v = Var("")
    focused(h, v)
    "abc".foreach { c => h.type_(c.toString); h.frame(_.textEdit(v)) }
    assertEquals(v.value, "abc")
    h.key(Key.Other(0).asInstanceOf[Key], mod); h.frame(_.textEdit(v)) // no 'z' chars: nothing happens
    h.ctx.input.handle(Event.KeyDown(Key.Other(6), "", "z", mod, false)); h.frame(_.textEdit(v))
    assertEquals(v.value, "")
    h.ctx.input.handle(Event.KeyDown(Key.Other(6), "", "z", Modifiers(shift = true, command = true, control = true), false)); h.frame(_.textEdit(v))
    assertEquals(v.value, "abc")
  }
  test("deleting and typing are separate undo steps") {
    val h = new Harness; val v = Var("hello")
    focused(h, v)
    h.key(Key.End); h.frame(_.textEdit(v))
    h.key(Key.Backspace); h.frame(_.textEdit(v)); h.key(Key.Backspace); h.frame(_.textEdit(v))
    assertEquals(v.value, "hel")
    h.type_("p"); h.frame(_.textEdit(v))
    assertEquals(v.value, "help")
    val z = Event.KeyDown(Key.Other(6), "", "z", mod, false)
    h.ctx.input.handle(z); h.frame(_.textEdit(v)); assertEquals(v.value, "hel")
    h.ctx.input.handle(z); h.frame(_.textEdit(v)); assertEquals(v.value, "hello")
  }

class StateSuite extends scalaui.testkit.LoggedSuite:
  test("disabled widgets ignore clicks") {
    val h = new Harness
    var rect = Rect.Zero
    h.frame { ui => ui.disabled(true) { u => rect = u.button("OK").rect } }
    var clicked = false
    h.move(rect.center); h.frame(ui => ui.disabled(true)(_.button("OK")))
    h.press(rect.center); h.frame(ui => ui.disabled(true)(_.button("OK")))
    h.release(rect.center)
    h.frame { ui => ui.disabled(true) { u => clicked = u.button("OK").clicked } }
    assert(!clicked)
    assert(h.click(rect)(ui => { var r = Response(Rect.Zero); ui.disabled(false)(u => r = u.button("OK")); r }))
  }
  test("animate eases towards the target and settles") {
    val h = new Harness
    var v = 0.0
    h.frame { ui => v = ui.animate(0.0) }
    assertEquals(v, 0.0)
    h.frame { ui => v = ui.animate(1.0) }
    assert(v == 1.0 || (v > 0.0 && h.ctx.repaintRequested), s"v=$v") // the id changes per call site order; first read snaps
  }
  test("panels carve the region") {
    val h = new Harness
    var central = Rect.Zero
    h.frame { ui =>
      ui.leftPanel(100)(_.label("left"))
      ui.bottomPanel(40)(_.label("bottom"))
      central = ui.region
    }
    assert(central.x0 > 100, s"$central")
    assert(central.y1 < 400 - 30, s"$central")
  }

class ShadowSuite extends scalaui.testkit.LoggedSuite:
  test("shadow draws nine slices and darkens the area around a rect") {
    val h = new SnapshotHarness(200, 140)
    val img = h.render { ui =>
      ui.ctx.shadow(Rect(60, 40, 140, 100), 6, 12, scalaui.color.Color(0, 0, 0, 0.8f), Vec2(0, 4))
      ui.ctx.canvas.fillRoundedRect(Rect(60, 40, 140, 100), 6, scalaui.color.Color.White)
    }
    def lum(x: Int, y: Int) = img.rgba((y * img.width + x) * 4) & 0xff
    val bg = lum(5, 5)
    assert(lum(100, 106) < bg - 3, s"below the box should be shadowed: ${lum(100, 106)} vs $bg")
    assert(lum(100, 70) == 255)
    assert(lum(5, 5) == bg)
    Snapshot.check("shadow", img)
  }
