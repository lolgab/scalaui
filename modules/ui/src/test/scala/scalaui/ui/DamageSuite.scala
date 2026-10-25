package scalaui.ui

import scalaui.geom.*
import scalaui.gpu.{HeadlessBackend, SoftwareRenderer}
import scalaui.window.*

/** Drives a real UI through hover, clicks, typing, caret blink and animation. After every frame the pixels produced with the
  * damage rectangle chosen by [[DamageTracker]] must be byte-identical to a full render of the same frame. */
class DamageSuite extends scalaui.testkit.LoggedSuite:
  private final class Rig(width: Int, height: Int, scale: Double):
    val head = new HeadlessBackend
    val tracker = new DamageTracker(new TrackedBackend(head))
    val ctx = new Context(tracker.backend)
    var w = width; var h = height
    val checked = Var(false); val slider = Var(0.3); val text = Var("hello")
    var progress = 0.0
    var button = Rect(0, 0, 0, 0); var edit = Rect(0, 0, 0, 0); var box = Rect(0, 0, 0, 0)
    val damages = scala.collection.mutable.ArrayBuffer.empty[Option[Rect]]
    var frames = 0

    def ui(root: Ui): Unit =
      root.heading("Damage")
      root.horizontal { r => button = r.button("Press").rect; box = r.checkbox(checked, "Check").rect }
      edit = root.textEdit(text).rect
      root.slider(slider, 0, 1, "value")
      root.progressBar(progress, 180)
      root.label(if checked.value then "checked" else "unchecked")

    /** One frame; returns the damage that was used. */
    def frame(force: Boolean = false): Option[Rect] =
      ctx.begin(w, h, scale)
      ui(new Ui(ctx, Rect(10, 8, w - 10.0, h.toDouble), true, 1L))
      val fr = ctx.end()
      val d = tracker.submit(fr, force)
      val (want, pw, ph) = SoftwareRenderer.render(tracker.backend.unwrap(fr))
      val (got, gw, gh) = head.last.get
      assertEquals((gw, gh), (pw, ph))
      val bad = got.indices.find(i => got(i) != want(i))
      assert(bad.isEmpty, s"frame $frames (damage $d): partial render differs from the full render at pixel ${bad.map(i => (i / 4 % pw, i / 4 / pw))}")
      frames += 1; damages += d
      d
    def move(p: Point): Unit = ctx.input.handle(Event.MouseMoved(p.x, p.y))
    def press(p: Point): Unit = ctx.input.handle(Event.MouseDown(MouseButton.Left, p.x, p.y, Modifiers.None))
    def release(p: Point): Unit = ctx.input.handle(Event.MouseUp(MouseButton.Left, p.x, p.y))
    def type_(s: String): Unit = ctx.input.handle(Event.KeyDown(Key.Other(0), s, s, Modifiers.None, false))
    def settle(n: Int = 4): Unit = (0 until n).foreach(_ => frame())

  private def area(r: Rect) = r.width * r.height

  for scale <- Seq(1.0, 1.5) do
    test(s"partial redraw equals full redraw across a scripted sequence (scale $scale)") {
      val rig = new Rig(360, 260, scale)
      val first = rig.frame()
      assertEquals(first, None) // nothing to compare with yet
      rig.settle()
      assertEquals(rig.frame().map(area), Some(0.0)) // an unchanged frame has no damage at all
      val full = 360.0 * 260.0
      // hover: the button animates, only its surroundings change
      rig.move(rig.button.center); rig.settle(8)
      assert(rig.damages.exists(d => d.exists(r => area(r) > 0 && area(r) < full * 0.3)), s"hover damage should be small: ${rig.damages}")
      rig.move(Point(300, 240)); rig.settle(8)
      // click the checkbox: label text changes too
      rig.move(rig.box.center); rig.frame(); rig.press(rig.box.center); rig.frame(); rig.release(rig.box.center); rig.settle(6)
      assertEquals(rig.checked.value, true)
      // focus the text field, type, and let the caret blink
      val c = rig.edit.center
      rig.move(c); rig.frame(); rig.press(c); rig.frame(); rig.release(c); rig.frame()
      rig.type_("x"); rig.frame(); rig.type_("yz"); rig.frame()
      val before = rig.damages.length
      var i = 0
      while i < 16 do { Thread.sleep(80); rig.frame(); i += 1 } // about 1.3 s: the caret toggles at least once
      val blink = rig.damages.drop(before).flatten.filter(r => area(r) > 0)
      assert(blink.nonEmpty, "the caret blink must produce damage")
      assert(blink.forall(r => area(r) < full * 0.2), s"caret blink damage should stay small: $blink")
      // animation: a progress bar that moves every frame, plus a slider drag
      (1 to 10).foreach { k => rig.progress = k / 10.0; rig.frame() }
      rig.move(Point(100, 200)); rig.frame()
      rig.press(Point(100, 200)); rig.frame(); rig.move(Point(160, 200)); rig.frame(); rig.release(Point(160, 200)); rig.settle()
      // a resize, a theme change and an explicit force always redraw everything
      rig.w = 300; rig.h = 220
      assertEquals(rig.frame(), None)
      rig.settle()
      rig.ctx.style = Style.Light
      assertEquals(rig.frame(force = true), None)
      rig.settle(2)
      assertEquals(rig.frame(force = true), None)
    }

  test("an upload into a texture region that is already drawn marks it dirty") {
    val head = new HeadlessBackend
    val tracker = new DamageTracker(new TrackedBackend(head))
    val tex = tracker.backend.createTexture(4, 4, new Array[Byte](64))
    val c = new scalaui.gpu.Canvas(tracker.backend.whiteTexture)
    def frame(): scalaui.gpu.Frame =
      c.begin(100, 100, 1.0, scalaui.color.Color.Black)
      c.fillRect(Rect(0, 0, 100, 100), scalaui.color.Color(0.2f, 0.2f, 0.2f, 1f))
      c.drawImage(tex, Rect(64, 64, 96, 96))
      c.drawImage(tex, Rect(2, 2, 12, 12), Rect(0.75, 0.75, 1, 1)) // samples only the last texel
      c.finish()
    assertEquals(tracker.damage(frame()), None)
    assertEquals(tracker.damage(frame()).map(area), Some(0.0))
    tex.update(0, 0, 1, 1, Array[Byte](-1, -1, -1, -1)) // texel (0,0): used by the big quad, not by the small one
    val d = tracker.damage(frame()).get
    assert(d.x0 >= 32 && d.y0 >= 32 && d.x0 <= 64, s"only the quad that samples the texel is damaged: $d")
    assertEquals(tracker.damage(frame()).map(area), Some(0.0))
  }
