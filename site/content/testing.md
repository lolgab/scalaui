---
title: Testing guide
section: Guide
order: 5
description: LoggedSuite, the headless UI harness and golden-image snapshot tests.
---
# Testing guide

scalaui's own tests use [munit](https://scalameta.org/munit/) on Scala Native. Three pieces make UI code testable without a screen.

## LoggedSuite

`scalaui.testkit.LoggedSuite` (module `testkit`) extends `munit.FunSuite`. Scala Native test processes print nothing to sbt, so a failing assertion would be invisible. `LoggedSuite` appends every failure (test name, exception, cause) to `target/test-failures.log`.

```scala
import scalaui.testkit.LoggedSuite

class ColorSuite extends LoggedSuite:
  test("hex parsing") {
    assertEquals(scalaui.color.Color.hex("#ff0000").map(_.toArgb), Some(0xFFFF0000))
  }
```

Every module wires `testkit` as a test dependency in `build.sbt` (`module(id)` does `dependsOn(testkit % Test)`), with `munit` 1.3.6. After a failing run:

```sh
sbt ui/test
cat target/test-failures.log
```

CI prints that log when a job fails.

## The headless Harness

`modules/ui/src/test/scala/scalaui/ui/UiSuite.scala` defines `Harness`, a stub GPU backend plus scripted input. It lives in the `ui` test sources, so it is not published; copy the 30 lines into your own test tree. It looks like this:

```scala
class Harness:
  val backend = new GpuBackend { /* stub textures, submit does nothing */ }
  val ctx = new Context(backend)

  def frame(f: Ui => Any): Unit =
    ctx.begin(400, 400, 1)
    f(new Ui(ctx, Rect(10, 8, 390, 400), true, 1L))
    ctx.end()

  def move(p: Point): Unit    = ctx.input.handle(Event.MouseMoved(p.x, p.y))
  def press(p: Point): Unit   = ctx.input.handle(Event.MouseDown(MouseButton.Left, p.x, p.y, Modifiers.None))
  def release(p: Point): Unit = ctx.input.handle(Event.MouseUp(MouseButton.Left, p.x, p.y))
  def type_(s: String): Unit  = ctx.input.handle(Event.KeyDown(Key.Other(0), s, s, Modifiers.None, false))
  def key(k: Key, mods: Modifiers = Modifiers.None): Unit = ctx.input.handle(Event.KeyDown(k, "", "", mods, false))
```

Because the UI is immediate-mode, a "click" is several frames: hover, press, release. The harness' `click` helper does exactly that and returns whether the widget reported `clicked` on the release frame:

```scala
test("button click fires on release over the button") {
  val h = new Harness
  var rect = Rect.Zero
  h.frame { ui => rect = ui.button("OK").rect }   // first frame: find where the widget is
  assert(h.click(rect)(_.button("OK")))
}

test("checkbox toggles") {
  val h = new Harness
  val v = Var(false)
  var rect = Rect.Zero
  h.frame { ui => rect = ui.checkbox(v, "x").rect }
  h.click(rect)(_.checkbox(v, "x"))
  assert(v.value)
}
```

Other tests in the suite drive text editing with `type_` and `key(Key.Left, ...)`, tables with header clicks and arrow keys, and platform input (`PlatformInputSuite`: IME composition, file drops, theme changes).

::: note
Never synthesize real OS mouse events from tests (for example with `CGEvent` on macOS): they land on the developer's actual desktop. Feed `Event` values into `ctx.input.handle` instead.
:::

## Snapshot tests

`SnapshotHarness` and `Snapshot` (in `modules/ui/src/test/scala/scalaui/ui/Snapshot.scala`) render a frame offscreen with `gpu.HeadlessBackend` (the software renderer, which is published in `gpu`) and compare it with a golden PNG.

```scala
class SnapshotSuite extends scalaui.testkit.LoggedSuite:
  test("plot renders as before") {
    val h = new SnapshotHarness(340, 200)
    val st = new PlotState
    val pts = (0 to 30).map(i => (i / 3.0, math.sin(i / 4.0)))
    val img = h.render { ui =>
      ui.plot(st, Size(320, 180), Seq(PlotSeries("sin", pts, Color.rgb8(255, 140, 0))), "t", "v")
    }
    Snapshot.check("plot", img)
  }
```

- Goldens live in `modules/ui/src/test/resources/snapshots/` (`gallery.png`, `plot.png`, `shadow.png`).
- `Snapshot.check` tolerates small anti-aliasing noise: by default 6 per channel for all but 0.2 percent of pixels. Tune with `channelTolerance` and `maxDiffFraction`.
- When a snapshot differs, the actual image is written to `/tmp/<name>.actual.png`.
- A missing golden is created on first run. To refresh existing goldens, **touch `/tmp/scalaui-update-snapshots`** and run the suite; remove the file afterwards. A marker file is used because an environment variable would not reach the Scala Native test process.

`HeadlessBackend` also has `screenshot(): Image`, which is handy for rendering widgets into PNGs for documentation.

## Platform tests

- `window` has `ViewSuite` (macOS: calls `NSTextInputClient` methods and `setFrameSize:` directly on a view, so no real events are needed) and `X11Suite` (Linux; set `DISPLAY` and `SCALAUI_X11_TESTS=1`, for example under Xvfb).
- `gpu` has `VulkanSuite`, which renders the same frames with the Vulkan backend and the software renderer and compares them (MoltenVK on macOS).
- `shaping` has `GoldenSuite`: about 2200 cases whose expected output comes from `hb-shape`. Regenerate with `tools/gen_shaping_golden.py`.
- `unicode` runs sampled copies of the official UCD conformance files.
- `codecs` decodes fixtures in `src/test/resources` and compares with reference PNGs generated by `tools/gen_image_fixtures.sh`.

## Running a subset

```sh
sbt "ui/testOnly scalaui.ui.UiSuite"
sbt "geom/test; deflate/test; color/test; raster/test; codecs/test; font/test; shaping/test; pdf/test; gpu/test; ui/test"
```

XIM and AT-SPI text support are checked on Linux inside the test container: `XimSuite` starts `tools/xim_server.py` (a protocol-level XIM server written with python-xlib) under Xvfb with `XMODIFIERS=@im=testxim`, and with `XMODIFIERS=@im=ibus` plus a running `ibus-daemon --xim` it runs one test against the real server. `tools/atspi_check.py` exercises the `Text` and `EditableText` interfaces and their signals through libatspi.
