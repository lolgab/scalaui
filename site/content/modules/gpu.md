---
title: gpu
section: Modules
order: 16
description: A 2D canvas that records vertices, and Metal, Vulkan, Direct3D 12, Direct3D 11 and software backends that render them.
---
# gpu

`scalaui.gpu` renders 2D scenes (the role of wgpu in a deliberately small form). You draw into a `Canvas`, which produces a `Frame` of vertices and draw calls; a `GpuBackend` renders the frame. Depends on [geom](geom.md), [color](color.md), [raster](raster.md), [window](window.md) and [codecs](codecs.md).

```scala
import scalaui.gpu.*
```

## Backends

```scala
val backend = GpuBackend.create(window)
```

| Backend | Platforms | Notes |
|---|---|---|
| `MetalBackend` | macOS | default on macOS |
| `VulkanBackend` | Linux, Windows, macOS (MoltenVK) | loads the system Vulkan loader at run time (nothing linked); renders offscreen with 4x MSAA and reads back into `Window.present`, so no window-system integration is needed |
| `D3D12Backend` | Windows | COM vtables, flip-model swap chain, root signature, fence-synchronised frames, HLSL through `d3dcompiler_47`. **Unverified on real hardware**: the demo runs under Wine's vkd3d (on MoltenVK) and matches the software renderer, but it has never run on a Windows GPU (see [FAQ](../faq.md)). Tried after Vulkan and before Direct3D 11, or forced with `SCALAUI_BACKEND=d3d12` |
| `D3D11Backend` | Windows | COM vtables, HLSL through `d3dcompiler_47`, swapchain. **Unverified** (see [FAQ](../faq.md)); used only when Vulkan is missing or requested |
| `SoftwareBackend` | everywhere | CPU renderer (`SoftwareRenderer.render(frame)`), the fallback |
| `HeadlessBackend` | everywhere | CPU renderer with no window; `screenshot()` returns the last frame as an `Image`. Used by snapshot tests |

`GpuBackend.create` order: Metal on macOS; Vulkan, then Direct3D 12, then Direct3D 11, then software on Windows; Vulkan, then software on Linux. `SCALAUI_SOFTWARE=1` forces software and `SCALAUI_BACKEND=vulkan|d3d12|d3d11|metal|software` picks one (see [Build and cross-compile](../building.md)).

```scala
trait GpuBackend:
  def createTexture(width: Int, height: Int, rgba: Array[Byte]): Texture   // premultiplied RGBA8
  def whiteTexture: Texture
  def submit(frame: Frame): Unit
  def close(): Unit

trait Texture:
  def width: Int; def height: Int
  def update(x: Int, y: Int, w: Int, h: Int, rgba: Array[Byte]): Unit
```

## Canvas

```scala
val canvas = new Canvas(backend.whiteTexture)
canvas.begin(width, height, scaleFactor, clearColor)
// ... draw ...
backend.submit(canvas.finish())
```

Coordinates are logical points with the origin at the top left. Drawing methods:

| Method | Description |
|---|---|
| `fillRect(r, c)`, `fillRectGradient(r, tl, tr, bl, br)` | rectangles, four-corner gradient |
| `fillRoundedRect(r, radius, c)` | |
| `fillCircle(center, radius, c)`, `fillEllipse(center, rx, ry, c)` | |
| `fillConvex(points, c)` | convex polygon |
| `strokeLine(a, b, width, c)`, `strokeRect(r, width, c)`, `strokeCircle(center, radius, width, c)` | |
| `strokePath(path, style: Stroke, c)` | arbitrary `raster.Path` outlines |
| `drawImage(texture, dst, src = Rect(0, 0, 1, 1), tint = Color.White)` | textured quad; `src` in UV space |
| `pushClip(r)`, `popClip()` | scissor rectangles |

`Frame(clear, width, height, scale, vertices, calls)` is the plain-data hand-off: a float vertex buffer (`Vertex.Floats` = 8 per vertex) and `DrawCall(texture, scissor, firstVertex, vertexCount)` entries.

## Example

```scala
import scalaui.color.Color
import scalaui.geom.*
import scalaui.gpu.*
import scalaui.window.*

val win = Window.create("canvas", 800, 500)
val gpu = GpuBackend.create(win)
val canvas = new Canvas(gpu.whiteTexture)

// checkerboard texture, premultiplied RGBA8
val checker = gpu.createTexture(8, 8, Array.tabulate(8 * 8 * 4) { i =>
  val px = i / 4; val on = ((px % 8) / 2 + (px / 8) / 2) % 2 == 0
  (if i % 4 == 3 then 255 else if on then 230 else 60).toByte
})

var running = true
var mouse = Point(400, 250)
while running do
  win.pollEvents().foreach {
    case Event.CloseRequested => running = false
    case Event.MouseMoved(x, y) => mouse = Point(x, y)
    case _ => ()
  }
  canvas.begin(win.width, win.height, win.scaleFactor, Color.rgb8(30, 32, 40))
  canvas.fillRoundedRect(Rect(40, 40, 340, 180), 18, Color.rgb8(70, 90, 200))
  canvas.pushClip(Rect(400, 40, 560, 180))
  canvas.drawImage(checker, Rect(380, 30, 600, 200), Rect(0, 0, 4, 2))
  canvas.popClip()
  canvas.fillCircle(mouse, 50, Color(1, 1, 1, 0.35f))
  gpu.submit(canvas.finish())
  Thread.sleep(16)
win.close()
```

For text on the canvas see [text](text.md). The complete demo is `examples/src/main/scala/CanvasDemo.scala`.

## Damage rectangles

`GpuBackend.submit(frame, damage: Option[Rect])` tells the backend that only `damage` (logical points) differs from the previous frame (same size, scale and texture contents). The frame must still describe the whole picture: damage is a hint, never a clip, and `None` means everything changed. Every backend honors it, and the pixels inside the region are identical to a full redraw (tested per backend against a full render):

| Backend | What a partial frame does |
|---|---|
| `SoftwareBackend` | keeps the previous pixels, clears and redraws only the damaged pixels, then `Window.present` with the dirty rectangle |
| `VulkanBackend` | the 4x multisample image is persistent; the damaged rectangle is cleared with `vkCmdClearAttachments`, the draws are scissored to it, the image is resolved, and only that rectangle is copied back and presented |
| `MetalBackend` | the multisample attachment is persistent (`load`/`storeAndMultisampleResolve`); the damaged rectangle is overwritten with the clear colour by an unblended quad and redrawn with scissors, then resolved into a fresh drawable |
| `D3D12Backend` | the whole frame is rendered (flip-model back buffers do not keep their contents); the rectangle becomes the dirty rectangle of `IDXGISwapChain1::Present1` (not run on real hardware yet) |
| `D3D11Backend` | ignores the hint |

Every backend falls back to a full redraw on the first frame and after a size or scale change; Metal also after a skipped drawable. An empty damage rectangle draws and presents nothing. `HeadlessBackend` honors the hint too (its `last` array is then patched in place, copy it to keep a frame).

`ui.App` computes the damage itself: see `DamageTracker` in [ui](ui.md#damage-tracking). `Window.present(rgba, w, h, x, y, dirtyW, dirtyH)` is the matching presentation call, see [window](window.md). Numbers are on the [Performance](../performance.md) page.
