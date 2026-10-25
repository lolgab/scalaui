---
title: Architecture
section: Guide
order: 2
description: How the scalaui modules depend on each other, and how a frame travels from your code to the screen.
---
# Architecture

scalaui is a set of small sbt projects, one per concern. Each is named `scalaui-<module>` and lives in `modules/<module>`. The layering mirrors the Rust stack the toolkit is modeled on:

| Rust crate | scalaui module |
|---|---|
| kurbo | [geom](modules/geom.md) |
| miniz_oxide | [deflate](modules/deflate.md) |
| color | [color](modules/color.md) |
| cms (lcms2) | [cms](modules/cms.md) |
| brotli-decompressor | [brotli](modules/brotli.md) |
| image-ops | [imaging](modules/imaging.md) |
| vello_cpu, tiny-skia | [raster](modules/raster.md) |
| image, zune-jpeg | [codecs](modules/codecs.md) |
| skrifa, wuff | [font](modules/font.md) |
| harfrust | [shaping](modules/shaping.md) |
| unicode-bidi, unicode-segmentation, unicode-linebreak, ICU normalizer | [unicode](modules/unicode.md) |
| parley | [text](modules/text.md) |
| krilla | [pdf](modules/pdf.md) |
| usvg, resvg | [svg](modules/svg.md) |
| wgpu | [gpu](modules/gpu.md) |
| winit | [window](modules/window.md) |
| rfd | [dialogs](modules/dialogs.md) |
| arboard | [clipboard](modules/clipboard.md) |
| egui, eframe | [ui](modules/ui.md) |

## Dependency graph

The diagram below is generated from `build.sbt` when the site is built, so it always matches the code. Arrows point from a module to the modules that depend on it. Hover a node to highlight its neighbors; click it to open its page. Test-only dependencies (`testkit`, `codecs` in `font`) are left out.

{{diagram}}

{{deps-table}}

## Layers

**Foundation.** `geom` (points, rectangles, affine transforms), `color`, `cms` (ICC profiles and color transforms), `deflate` (zlib, CRC-32 and Adler-32 in pure Scala), `brotli` (decompression for WOFF2) and `unicode` (bidi, segmentation, line breaking, normalization) have no dependencies beyond `geom`/`color`.

**Graphics.** `raster` fills and strokes paths into a floating-point `Pixmap`. `imaging` adds blend modes, filters, resampling and layers on top of pixmaps. `codecs` reads and writes image files. `svg` renders a static SVG subset into a pixmap. `pdf` writes documents with shaped, subset-embedded text.

**Text.** `font` parses TrueType, CFF and WOFF fonts. `shaping` turns text into positioned glyphs using the font's own GSUB and GPOS tables. `text` runs bidi analysis, shaping, line breaking and alignment to produce a `TextLayout` with carets and hit testing, and draws it through a glyph atlas.

**Platform.** `window` abstracts a native window, its events, cursor, menus, tray and accessibility. `gpu` turns draw lists into pixels with Metal, Vulkan, Direct3D 11 or the CPU. `dialogs` and `clipboard` cover native file and message dialogs and the system clipboard.

**UI.** `ui` is the immediate-mode widget layer and the `App` runner that ties window, GPU, text and input together.

`docx` (a zip and XML Open Packaging Convention reader and writer) is a standalone helper that depends only on `geom` and `deflate`; `testkit` holds the shared test base class; `shader` is a placeholder for a future shader-translation module and is empty today.

## From `App.run` to pixels

1. `Window.create` picks `CocoaWindow`, `Win32Window`, `WaylandWindow` or `X11Window` at run time with link-time OS checks, so other platforms' bindings are not linked into your binary.
2. `GpuBackend.create(window)` chooses Metal, Vulkan, Direct3D 11 or the software renderer (see [Build and cross-compile](building.md) for the environment overrides).
3. Each frame, `App` calls your function with a root `Ui`. Widgets lay themselves out, read `ctx.input`, and append quads to the `gpu.Canvas`. Text goes through `text.TextRenderer` and a `GlyphAtlas` texture.
4. `Context.end()` returns a `gpu.Frame` (vertex buffer plus draw calls with scissor rectangles). `backend.submit(frame)` renders it. GPU backends render offscreen with 4x MSAA and hand the pixels to `Window.present`, so they need no window-system integration and work with the pure-Scala X11 and Wayland clients.
5. The frame's cursor icon, IME rectangle and accessibility tree are pushed to the window.

## Design rules

- **No third-party C libraries.** The only C in the repository is a pair of shims that use system headers only: `scalaui_objc.c` (Scala Native cannot pass structs by value to Objective-C) and `scalaui_uia.c` (UI Automation calls providers from RPC threads the Scala runtime does not know). System frameworks are `dlopen`ed, so no `-framework` flags are needed.
- **No `java.util.zip` in the toolkit modules.** On Scala Native it needs zlib and breaks cross-compiles with a sysroot; use `scalaui.deflate.Zlib`. (`docx` is the exception: it uses the zip streams for containers.)
- **Reuse existing Scala libraries** for XML (`scala-xml`) and JSON (`jsoniter-scala`) rather than reimplementing them.
- **Verify against oracles.** Shaping is checked against about 2200 `hb-shape` cases, codecs against ImageMagick, libwebp and libjpeg fixtures, Unicode algorithms against the official conformance files, and the Vulkan backend against the CPU renderer.
