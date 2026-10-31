---
title: ui
section: Modules
order: 19
description: The immediate-mode widget layer and App runner (egui and eframe for Scala Native).
---
# ui

`scalaui.ui` is the immediate-mode UI toolkit: widgets, layout, text editing, theming, drag and drop, accessibility, and `App.run`, which owns the window and frame loop (the roles of egui and eframe). Depends on [raster](raster.md), [text](text.md), [gpu](gpu.md), [window](window.md), [dialogs](dialogs.md), [clipboard](clipboard.md), [codecs](codecs.md), [svg](svg.md) and [imaging](imaging.md).

```scala
import scalaui.ui.*
```

Every widget is documented with signature and example in the [Widget reference](../widgets.md); this page covers the runtime pieces around them.

## App

```scala
object App:
  def run(title: String, width: Int = 900, height: Int = 600, style: Style = Style.Dark,
          followSystemTheme: Boolean = false, menuBar: Seq[MenuEntry.Submenu] = Nil)(frame: Ui => Unit): Unit
```

`run` creates the window (`Window.create`), the GPU backend (`GpuBackend.create`) and a `Context`, then loops until `CloseRequested`:

- It blocks in `Window.waitEvents` while nothing changes (up to 250 ms, 8 ms while an animation runs), so an idle app uses no CPU.
- Each event goes to `ctx.input`; theme changes restyle the UI when `followSystemTheme` is set; screen-reader presses are replayed as clicks.
- It calls `frame` with a root `Ui`, submits the frame (with the damage rectangle, see below), then pushes the mouse cursor, the IME rectangle and (when it changed) the accessibility tree to the window.
- A live-resize callback repaints from inside the OS's modal resize loop, so content follows the window edge.

Set `SCALAUI_DEBUG=1` to print per-frame timings and the damage rectangle of each frame.

### Damage tracking

`App` wraps the backend in a `TrackedBackend` (textures remember which texels were uploaded) and runs every frame through a `DamageTracker`. The tracker hashes each triangle of the frame (vertices, texture, scissor) into 32-point tiles in draw order and compares the tile hashes with the previous frame's: a hover fade, a caret blink or a moving progress bar dirties only the tiles they touch, and a triangle that samples a texture region uploaded since the last frame (glyph atlas, images) dirties its tiles even though its vertices are unchanged. The bounding box of the dirty tiles is passed to `GpuBackend.submit(frame, damage)` and on to `Window.present`. An identical frame has an empty damage rectangle and costs no draw or present.

It is conservative: the first frame, a resize, a scale change, a new clear colour, a style (theme) or zoom change, non-finite coordinates, textures it does not track and damage covering more than 85% of the window all mean a full redraw. `SCALAUI_DAMAGE=0` turns the optimisation off. The test `DamageSuite` drives hover, clicks, typing, caret blink, animation, a resize and a theme change and checks every frame is byte-identical to a full render.

## Ui

A `Ui` is a layout region with a cursor (`region`, `cursorPoint`, `availableWidth`, `availableHeight`, `usedRect`, `allocate(w, h)`) and the widget methods. `ui.style` is the current `Style`, `ui.ctx` the shared `Context`. Containers hand you a child `Ui` (`horizontal`, `vertical`, `group`, panels, `scrollArea`, `collapsing`, ...). `ui.nextId()` generates the stable widget ids that make state (focus, open/closed, scroll) survive between frames.

## Context

`Context` holds everything shared across a frame and between frames:

- `style`, `padX`, `padY`, `zoom`, `animations`
- `input: Input`: `mouse`, `down`, `pressed`, `released`, `doubleClicked`, `scroll`, `mods`, `keys: ArrayBuffer[KeyPress]`, `composition`, `droppedFiles`, `dropPos`, `hoveredFiles`, `windowFocused`, `menuActions`, `themeChanged`, `mouseDelta`
- `canvas: gpu.Canvas` for custom drawing, plus `texture(img)`, `svgTexture(doc, w, h)`
- `toast(text, kind, seconds)`, `shadow(...)`, `box(...)`, `outline(...)`, `focusRing(...)`, `paintIcon(...)`
- `animate(id, target, seconds)`, `requestRepaint()`, `time`
- `cursorIcon`, `imeRect`
- `layout(text, size, ...)` and `paintText` for custom text

You can drive a `Context` yourself with a `GpuBackend` (this is what the test harnesses do): call `ctx.begin(width, height, scale)`, run your UI against `new Ui(ctx, Rect(...), true, 1L)`, then `ctx.end()` to get a `gpu.Frame`.

## Style and Icons

`Style` is a case class of design tokens; `Style.Dark`, `Style.Light`, `Style.withAccent(base, "#rrggbb")` and `Style.mix(a, b, t)`. Icons are small SVG line icons on a 24x24 grid, tinted at paint time and rasterized crisply per display scale: `Icons.save`, `Icons.search`, ... (about 110), `Icons.byName`, and `Icon(name, svgBody)` for your own.

## Text editing

`textEdit` is built on the immutable `Edit(text, cursor, anchor)` model (`insert`, `deleteSelection`, `backspace`, `delete`, `left`, `right`, `selectAll`, `lineStart`, `lineEnd`, `selectWordAt`, ...). Movement and deletion are by grapheme cluster and word using the [unicode](unicode.md) module, caret movement is bidi-aware, and each field keeps an undo and redo history (`EditState`). IME composition (`input.composition`) is previewed inline, and the field reports the caret rectangle via `ctx.imeRect`.

## Accessibility

Widgets call `ctx.access(role, label, rect, ...)` as they draw. `App` publishes the resulting `AccessNode` list through `Window.setAccessibility` when it changes. See [Platform support](../platforms.md#accessibility).

## Native menus

`App.run(menuBar = ...)` installs a native menu bar; `ui.appMenuBar(menus)` draws the same entries where the OS has none and `ui.menuPressed(id)` reports choices. See the [widget reference](../widgets.md#native-menus-appmenubar-and-menupressed).

## A larger example

```scala
import scalaui.color.Color
import scalaui.geom.*
import scalaui.ui.*

@main def app(): Unit =
  val tab = Var(0)
  val name = Var("Ferris")
  val notes = Var("Multi-line\ntext editing.")
  val volume = Var(0.35)
  val tint = Var(Color(0.9f, 0.4f, 0.2f, 1f))
  val dark = Var(true)
  val showWin = Var(true)

  App.run("scalaui demo", 760, 600) { ui =>
    ui.menuBar { b =>
      b.menu("View") { m => if m.menuItem("Toggle window").clicked then showWin.value = !showWin.value }
    }
    ui.window("Floating window", Point(430, 300), 300, Some(showWin)) { w =>
      w.label("Drag my title bar.")
      w.colorEdit(tint)
    }
    ui.tabBar(tab, Seq("Basics", "Notes"))
    ui.space(4)
    tab.value match
      case 0 =>
        ui.horizontal { h =>
          h.label("Name:")
          h.textEdit(name, hint = "type here")
        }
        ui.label(s"Hello, ${name.value}!")
        ui.slider(volume, 0, 1, "volume")
        if ui.checkbox(dark, "Dark theme").changed then
          ui.ctx.style = if dark.value then Style.Dark else Style.Light
      case _ =>
        ui.textEdit(notes, multiline = true, rows = 6, width = Some(420))
  }
```

The shipped gallery, [`examples/src/main/scala/Demo.scala`](https://github.com/lolgab/scalaui/blob/master/examples/src/main/scala/Demo.scala), shows every widget family.
