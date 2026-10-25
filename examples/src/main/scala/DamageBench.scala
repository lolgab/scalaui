package scalaui.examples

import scala.scalanative.meta.LinktimeInfo
import scala.scalanative.unsafe.*
import scalaui.geom.*
import scalaui.gpu.*
import scalaui.ui.*
import scalaui.window.*

/** Measures UI + submit cost per frame of an idle caret blink and of a button hover, with and without damage rectangles:
  * `scalaui-examples damageBench` (the `demo` entry point). Uses no OS windows or input (Metal draws into a detached layer). */
def damageBench(): Unit =
  val w = 900; val h = 600; val scale = 2.0
  val name = Var("Ferris"); val notes = Var("Multi-line\ntext editing:\nselect, copy, paste."); val checked = Var(true); val vol = Var(0.35)
  // focus the first text field (state lives in the Context; no OS event is involved)
  final class Rig(make: () => GpuBackend, damage: Boolean):
    val tracker = new DamageTracker(new TrackedBackend(make()))
    val ctx = new Context(tracker.backend)
    ctx.animations = true
    var buttonRect = Rect(0, 0, 1, 1); var fieldRect = Rect(0, 0, 1, 1)
    def frame(): (Double, Double, Option[Rect]) =
      val t0 = System.nanoTime()
      ctx.begin(w, h, scale)
      val root = new Ui(ctx, Rect(10, 8, w - 10.0, h.toDouble), true, 1L)
      root.heading("Damage benchmark")
      root.horizontal { r => r.label("Name:"); fieldRect = r.textEdit(name, hint = "type here").rect; r.button("Clear") }
      root.horizontal { r => buttonRect = r.button("Hover me").rect; r.checkbox(checked, "Enabled") }
      root.slider(vol, 0, 1, "volume")
      root.textEdit(notes, multiline = true, rows = 5)
      (0 until 18).foreach(i => root.label(s"row $i: the quick brown fox jumps over the lazy dog"))
      val fr = ctx.end()
      val t1 = System.nanoTime()
      val d = if damage then tracker.submit(fr) else { tracker.backend.submit(fr); None }
      val t2 = System.nanoTime()
      ((t1 - t0) / 1e6, (t2 - t1) / 1e6, d)
  def run(backendName: String, make: () => GpuBackend): Unit =
    for damage <- Seq(false, true) do
      val rig = new Rig(make, damage)
      def click(p: Point): Unit =
        rig.ctx.input.handle(Event.MouseMoved(p.x, p.y)); rig.frame()
        rig.ctx.input.handle(Event.MouseDown(MouseButton.Left, p.x, p.y, Modifiers.None)); rig.frame()
        rig.ctx.input.handle(Event.MouseUp(MouseButton.Left, p.x, p.y)); rig.frame()
      (0 until 6).foreach(_ => rig.frame())
      click(rig.fieldRect.center) // focus the field
      (0 until 6).foreach(_ => rig.frame())
      // idle blink: the caret toggles every ~0.53 s; run frames at 30 per second for 4 s and keep the cost of the frames that changed pixels
      def measure(label: String, frames: Int, step: Int => Unit): Unit =
        var uiMs = 0.0; var subMs = 0.0; var n = 0; var area = 0.0
        val c0 = Clk.clock()
        (0 until frames).foreach { i =>
          step(i)
          val (u, s, d) = rig.frame()
          uiMs += u; subMs += s; n += 1
          area += d.map(r => r.width * r.height).getOrElse(w.toDouble * h)
        }
        val cpu = (Clk.clock() - c0).toDouble / 1e6 * 1000 / frames
        println(f"  $backendName%-9s damage=${if damage then "on " else "off"} $label%-6s ui ${uiMs / n}%6.2f ms  submit ${subMs / n}%6.2f ms  cpu/frame ${cpu}%6.2f ms  mean damaged area ${area / n / (w.toDouble * h) * 100}%5.1f%%")
      measure("blink", 130, _ => Thread.sleep(33))
      val c = rig.buttonRect.center; val away = Point(w - 5.0, h - 5.0)
      // hover: the pointer enters and leaves a button every 20 frames; the transition animates
      measure("hover", 120, i => { val p = if (i / 20) % 2 == 0 then c else away; rig.ctx.input.handle(Event.MouseMoved(p.x + (i % 2) * 0.01, p.y)) })
  println(s"frame ${w}x$h at ${scale}x (${(w * scale).toInt}x${(h * scale).toInt} px); caret blink and button hover")
  run("software", () => new SoftwareBackend(new NullWindow(w, h, scale)))
  VulkanBackend.create(None).foreach(_ => run("vulkan", () => VulkanBackend.createOrThrow(None)))
  if LinktimeInfo.isMac then
    Frameworks.load()
    run("metal", () =>
      scalaui.window.ObjC.send(scalaui.window.ObjC.cls("NSApplication"), "sharedApplication")
      new MetalBackend(new NullWindow(w, h, scale) { override def nativeHandle: Ptr[Byte] = scalaui.window.ViewShim.scalaui_view_create(w.toDouble, h.toDouble) }))

@extern private object Clk:
  def clock(): CLongLong = extern

class NullWindow(val width: Int, val height: Int, val scaleFactor: Double) extends Window:
  def nativeHandle: Ptr[Byte] = null
  def present(rgba: Array[Byte], w: Int, h: Int): Unit = ()
  def pollEvents(): Seq[Event] = Nil
  def close(): Unit = ()
  def setTitle(t: String): Unit = ()
