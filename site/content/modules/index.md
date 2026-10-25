---
title: Modules overview
nav: Overview
section: Modules
order: 0
description: All scalaui modules, what each one does and what it replaces from the Rust stack.
---
# Modules

Each module is an sbt project `scalaui-<module>` under `modules/<module>`. Pick the layer you need: you can use `codecs` or `unicode` without ever opening a window.

| Module | Role | Rust counterpart |
|---|---|---|
| [geom](geom.md) | points, vectors, rectangles, affine transforms | kurbo |
| [color](color.md) | sRGB colors, premultiplied blending, HSV | color |
| [cms](cms.md) | ICC color management | cms |
| [deflate](deflate.md) | zlib, CRC-32, Adler-32 | miniz_oxide, flate2 |
| [brotli](brotli.md) | Brotli decompression (used for WOFF2) | brotli-decompressor |
| [unicode](unicode.md) | bidi, segmentation, line breaking, normalization | unicode-bidi, unicode-segmentation, unicode-linebreak, icu |
| [raster](raster.md) | CPU path rasterizer | vello_cpu, tiny-skia |
| [imaging](imaging.md) | blend modes, filters, resampling, layers | image-ops |
| [codecs](codecs.md) | PNG, JPEG, BMP, GIF, TIFF, WebP | image, zune-jpeg |
| [svg](svg.md) | static SVG renderer | usvg, resvg |
| [pdf](pdf.md) | PDF writer | krilla |
| [font](font.md) | TrueType, CFF, WOFF, WOFF2, bundled fonts | skrifa, wuff |
| [shaping](shaping.md) | OpenType shaping | harfrust |
| [text](text.md) | paragraph layout, carets, glyph atlas | parley |
| [window](window.md) | native windows and events | winit |
| [gpu](gpu.md) | 2D canvas, Metal, Vulkan, D3D11, software | wgpu |
| [dialogs](dialogs.md) | native file and message dialogs | rfd |
| [clipboard](clipboard.md) | text and image clipboard | arboard |
| [ui](ui.md) | immediate-mode widgets and the app runner | egui, eframe |
| [docx](docx.md) | zip and XML package container | quick-xml, zip, cfb |
| [testkit](testkit.md) | shared test base class | |

`shader` is declared in `build.sbt` but empty: GLSL is compiled to SPIR-V offline (`tools/gen_shaders.sh`) and HLSL and MSL sources are inline in the backends.

## Choosing modules

- A command-line image converter: `codecs`, `imaging`, maybe `cms`.
- A PDF report generator: `pdf` (it brings `font`, `shaping`, `raster`, `codecs`, `deflate`).
- A custom-drawn app without widgets: `window`, `gpu`, `text`.
- A full desktop app: `ui`, which depends on almost everything above.
