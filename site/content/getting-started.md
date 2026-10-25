---
title: Getting started
section: Guide
order: 1
description: Set up sbt 2 with sbt-scala-native, depend on scalaui, and write a first immediate-mode app.
---
# Getting started

scalaui targets **Scala Native** only. You need:

- a JDK (17 or newer; CI uses Temurin 21) to run sbt,
- [sbt](https://www.scala-sbt.org/) 2.x (the repository uses 2.0.10),
- a C toolchain that Scala Native can use: Xcode command line tools on macOS, `clang` on Linux, and a Windows toolchain for native Windows builds,
- Scala 3 (the repository uses 3.9.0) and `sbt-scala-native` 0.5.x (0.5.12).

On Linux no X11, Wayland, GTK or Vulkan development packages are needed to build: the toolkit speaks the X11 and Wayland protocols itself and loads `libvulkan` at run time if it exists.

## Project setup

`project/build.properties`:

```text
sbt.version=2.0.10
```

`project/plugins.sbt`:

```scala
addSbtPlugin("org.scala-native" % "sbt-scala-native" % "0.5.12")
```

### Using the repository as a subproject

scalaui is not on Maven Central yet (the release workflow is ready, see [Build and cross-compile](building.md#publishing)). The simplest way to use it today is to put your app in the same sbt build, the way `examples` does. In the repository's `build.sbt`:

```scala
lazy val myapp = (project in file("myapp"))
  .enablePlugins(ScalaNativePlugin)
  .dependsOn(ui)               // pulls in window, gpu, text, raster, codecs, svg, ...
  .settings(nativeConfig ~= platformConfig)
```

`platformConfig` is defined at the top of the repository's `build.sbt`. It embeds resources (needed for the bundled fonts), turns on multithreading (needed for the Linux accessibility bus thread) and, when `SCALAUI_WIN_CROSS` is set, switches to the Windows cross-compilation target. See [Build and cross-compile](building.md).

### Using it from a separate build

Each module is an ordinary sbt project named `scalaui-<module>` in organization `com.github.lolgab`. Versions come from git tags (sbt-dynver via sbt-ci-release): the tag `v0.1.0` publishes `0.1.0`. Until a release is on Maven Central, publish every module from a checkout and depend on the version it prints:

```sh
sbt publishLocal    # all modules: `ui/publishLocal` alone is not enough, its dependencies are separate artifacts
```

An untagged checkout gets a version such as `0.0.0+36-fd153dfd+20261025-1025-SNAPSHOT` (commits since the last tag, hash, and a timestamp when the tree is dirty). Look in `~/.ivy2/local/com.github.lolgab/scalaui-ui_native0.5_3/` for the exact string. Then in your build:

```scala
ThisBuild / scalaVersion := "3.9.0"

lazy val app = project
  .enablePlugins(ScalaNativePlugin)
  .settings(
    // sbt 2: plain `%%` is platform aware and resolves scalaui-ui_native0.5_3 (there is no `%%%`)
    libraryDependencies += "com.github.lolgab" %% "scalaui-ui" % "<version>",
    nativeConfig ~= (_.withEmbedResources(true)   // bundled fonts are classpath resources
                      .withMultithreading(true)   // AT-SPI runs a bus thread on Linux
                      .withMode(scala.scalanative.build.Mode.releaseFast)) // the CPU renderer is ~10x slower in debug mode
  )
```

::: note
Verified locally (macOS, sbt 2.0.10): `sbt publishLocal` in the repository followed by `sbt nativeLink` in a separate one-file build that depends on `scalaui-ui` links a working binary. Resolving from Maven Central has not been exercised because nothing has been released yet, and CI has not run the Windows and Linux variants of this check.
:::

## Your first app

```scala
import scalaui.ui.*

@main def hello(): Unit =
  val name = Var("world")
  val count = Var(0)
  App.run("hello") { ui =>
    ui.heading("Hello")
    ui.horizontal { h => h.label("Name:"); h.textEdit(name) }
    if ui.button(s"Hi ${name.value}").clicked then count.value += 1
    ui.label(s"clicked ${count.value} times")
  }
```

Build and run it:

```sh
sbt myapp/run              # builds a native binary and runs it
sbt myapp/nativeLink       # just build; the binary lands under target/out/native0.5/scala-3.9.0/
```

## The frame loop

`App.run(title, width, height, style, followSystemTheme, menuBar)(frame)` creates a window with `Window.create`, picks a GPU backend with `GpuBackend.create`, and then loops until the window closes:

1. Wait for OS events. When idle it blocks (`Window.waitEvents`) so an idle app uses no CPU; while an animation runs it wakes every 8 ms.
2. Feed events into `ctx.input`.
3. If something changed, call your `frame` function with a root `Ui`, submit the resulting draw list to the GPU, update the cursor, the IME rectangle and the accessibility tree.

Because the closure runs every frame, you describe the UI with ordinary Scala control flow:

```scala
import scalaui.ui.*
import scala.collection.mutable.ArrayBuffer

final class Item(val title: String, val done: Var[Boolean] = Var(false))

@main def todo(): Unit =
  val items = ArrayBuffer(Item("Write docs"), Item("Ship it"))
  val draft = Var("")
  App.run("todo", 480, 600) { ui =>
    ui.heading("Todo")
    var remove = -1
    for (item, i) <- items.zipWithIndex do
      ui.horizontal { h =>
        h.checkbox(item.done, item.title)
        if h.button("Delete").clicked then remove = i
      }
    if remove >= 0 then items.remove(remove)   // mutate after drawing
    ui.horizontal { h =>
      h.textEdit(draft, hint = "New item")
      if h.button("Add").clicked && draft.value.nonEmpty then
        items += Item(draft.value)
        draft.value = ""
    }
  }
```

State lives in `Var[T]` cells you own (`Var(0)`, `Var("text")`, `Var(true)`); widgets read and write `var.value`. Every widget returns a [`Response`](widgets.md#response) with `clicked`, `changed`, `hovered`, `dragged` and so on.

### Layout

A `Ui` is a region with a cursor. `ui.vertical` (the default for the root) stacks widgets top to bottom, `ui.horizontal` places them left to right. Containers nest:

```scala
ui.horizontal { row =>
  row.vertical { col =>
    col.label("Left column")
    col.button("A")
  }
  row.group { g => g.label("Framed"); g.button("B") }
}
```

`leftPanel`, `rightPanel`, `topPanel`, `bottomPanel`, `statusBar`, `scrollArea`, `grid`, `collapsing` and `card` give you the usual desktop layouts. See the [widget reference](widgets.md).

### Style and theme

```scala
App.run("themed", style = Style.Light) { ui => ... }                 // fixed
App.run("auto", followSystemTheme = true) { ui => ... }              // follows the OS
ui.ctx.style = Style.withAccent(Style.Dark, "#ff7a59")               // change at run time
```

`Style` is a case class of design tokens (colors, type sizes, spacing, rounding), so `ui.ctx.style = ui.style.copy(rounding = 10)` works too.

## Drawing without widgets

Below the UI layer you can drive the renderer directly: `Window.create`, `GpuBackend.create(win)`, a `gpu.Canvas` for rectangles, circles, paths and images, and `text.TextRenderer` for text. `examples/src/main/scala/CanvasDemo.scala` is a complete example, and the [gpu](modules/gpu.md) and [text](modules/text.md) pages show the pieces.

## Try the demos

```sh
sbt examples/nativeLink
BIN=target/out/native0.5/scala-3.9.0/scalaui-examples/scalaui-examples
$BIN                   # widget gallery
$BIN canvas            # raw 2D renderer
$BIN viewer photo.jpg  # image viewer: decode, wheel zoom, drag pan
```
