package scalaui.ui

import java.nio.file.{Files, Paths}
import scalaui.codecs.{Image, Png}
import scalaui.geom.*
import scalaui.gpu.HeadlessBackend
import scalaui.window.*

/** Renders UI frames offscreen and compares them with golden PNGs in `modules/ui/src/test/resources/snapshots`.
  * Create or refresh goldens by touching `/tmp/scalaui-update-snapshots` (an environment variable would not reach the
  * Scala Native test process).
  */
class SnapshotHarness(width: Int = 400, height: Int = 300, scale: Double = 1.0):
  val backend = new HeadlessBackend
  val ctx = new Context(backend)
  def move(p: Point): Unit = ctx.input.handle(Event.MouseMoved(p.x, p.y))
  def press(p: Point): Unit = ctx.input.handle(Event.MouseDown(MouseButton.Left, p.x, p.y, Modifiers.None))
  def release(p: Point): Unit = ctx.input.handle(Event.MouseUp(MouseButton.Left, p.x, p.y))

  /** Runs one frame, renders it and returns the screenshot. */
  def render(f: Ui => Any): Image =
    ctx.begin(width, height, scale)
    f(new Ui(ctx, Rect(10, 8, width - 10.0, height.toDouble), true, 1L))
    backend.submit(ctx.end())
    backend.screenshot()

object Snapshot:
  private val dir = "modules/ui/src/test/resources/snapshots"

  /** Fails unless `img` matches the stored snapshot `name` within `channelTolerance` per channel for all but `maxDiffFraction` of pixels. */
  def check(name: String, img: Image, channelTolerance: Int = 6, maxDiffFraction: Double = 0.002): Unit =
    val path = Paths.get(s"$dir/$name.png")
    if Files.exists(Paths.get("/tmp/scalaui-update-snapshots")) || !Files.exists(path) then
      Files.createDirectories(path.getParent)
      Files.write(path, Png.encode(img))
    else
      val want = Image.decode(Files.readAllBytes(path))
      assert(want.width == img.width && want.height == img.height, s"$name: size ${img.width}x${img.height}, snapshot ${want.width}x${want.height}")
      var bad = 0
      var i = 0
      while i < img.rgba.length do
        if (0 until 4).exists(c => math.abs((img.rgba(i + c) & 0xff) - (want.rgba(i + c) & 0xff)) > channelTolerance) then bad += 1
        i += 4
      val frac = bad.toDouble / (img.width * img.height)
      if frac > maxDiffFraction then
        Files.write(Paths.get(s"/tmp/$name.actual.png"), Png.encode(img))
        fail(f"$name: ${frac * 100}%.2f%% of pixels differ (actual written to /tmp/$name.actual.png)")

  private def fail(msg: String): Nothing = throw new AssertionError(msg)

class SnapshotSuite extends scalaui.testkit.LoggedSuite:
  test("widget gallery renders as before") {
    val h = new SnapshotHarness(420, 330)
    val checked = Var(true); val slider = Var(0.4); val text = Var("snapshot")
    val st = new TableState(Seq(Column("Name", 120), Column("Size", 80)))
    val img = h.render { ui =>
      ui.heading("Gallery")
      ui.horizontal { r => r.button("Button"); r.checkbox(checked, "Check"); r.toggleSwitch(checked, "Switch") }
      ui.slider(slider, 0, 1, "value")
      ui.textEdit(text)
      ui.progressBar(0.6, 180)
      ui.table(st, Seq(Column("Name", 120), Column("Size", 80)), 3, 96)((r, c, u) => u.label(if c == 0 then s"file$r" else s"${r * 12} KB"))
    }
    Snapshot.check("gallery", img)
  }
  test("plot renders as before") {
    val h = new SnapshotHarness(340, 200)
    val st = new PlotState
    val pts = (0 to 30).map(i => (i / 3.0, math.sin(i / 4.0)))
    val img = h.render { ui => ui.plot(st, Size(320, 180), Seq(PlotSeries("sin", pts, scalaui.color.Color.rgb8(255, 140, 0))), "t", "v") }
    Snapshot.check("plot", img)
  }
