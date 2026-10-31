---
title: FAQ and limitations
section: Guide
order: 6
description: Frequently asked questions and an honest list of what is unverified or missing.
---
# FAQ and limitations

## What is unverified?

Be aware before shipping:

- **Direct3D 12** has not run on a real Windows GPU. It compiles, links, and under Wine (vkd3d on MoltenVK) renders the demo with output within about 0.4% RMSE of the software renderer, which checks the vtable indices and struct layouts against a real implementation but not against a real driver. The Windows default order is Vulkan, then Direct3D 12, then Direct3D 11, then software; `SCALAUI_BACKEND=d3d12` forces it.
- **Direct3D 11** compiles and links but has never run. Wine and macOS's GL stack reject its vertex shader at feature level 9_3. It is only picked when Vulkan and Direct3D 12 are missing or when you set `SCALAUI_BACKEND=d3d11`.
- **Win32 on real hardware.** The Windows executable runs under Wine, but nobody has run it on a real Windows machine: window creation, IME, tray, native menus, drag and drop, DPI changes and the UI Automation patterns are unverified there.
- **X11 on real hardware.** The X11 client passes its tests under Xvfb in a container; real window managers, GPU drivers and multi-monitor setups have not been tried.
- **Wayland** has been verified against headless sway 1.9 and weston 13 in a container (see the [platform table](platforms.md#how-well-is-each-platform-verified)), but not against GNOME (Mutter) or KDE (KWin), real GPUs or real input devices. Minimize is ignored by sway, so the request is untested, and there is no client-side decoration fallback: where the compositor does not offer `xdg-decoration` (GNOME, weston's desktop shell) the window has no title bar unless the application draws one.
- **UI Automation patterns** (Invoke, Toggle, Value, RangeValue, and the new TextPattern with its ranges) could not be exercised under Wine; the TextPattern code only compiles.
- **XIM** is verified against `ibus-daemon --xim` (m17n vi-telex engine, Compound Text, preedit callbacks) and a protocol-level test server (`tools/xim_server.py`) in an Ubuntu container under Xvfb. Other servers (fcitx5, scim, kinput2) and the dynamic event flow with trigger keys have not been tried; the over-the-spot style was only tested against the test server.
- **FreeBSD** is routed to the X11 backend but has never been built.

## What is missing?

- Tray icons and native menu bars on Linux (a drawn menu bar is the fallback), native message dialogs on Linux, native file dialogs on Linux (use `ui.FileDialog`).
- A few input-method and text-accessibility details: XIM decodes only Latin-1, UTF-8 and `iso10646-1` Compound Text (CJK ISO 2022 sets decode to U+FFFD), and does no status-area drawing; macOS VoiceOver can read and select text but not edit it; character rectangles are published only for short (up to 256 units) focused fields.
- Shaping gaps: Lao stacks of three or more marks, Arabic reordering of marks after a space, Zawgyi, vertical text. Hangul and Hebrew composition paths have no oracle font and are untested.
- WOFF2 font collections (`ttcf` flavour); single-font WOFF and WOFF2 are supported.
- The `shader` module is empty: GLSL is compiled to SPIR-V offline and HLSL/MSL are inline.
- A document model for `docx`: the module only reads and writes the zip and XML package.

## Is it a word processor?

No. scalaui is a generic UI toolkit, comparable to egui plus eframe, wgpu, winit, rfd and arboard. The `docx` module is a small container reader and writer, not a document engine.

## Why immediate mode?

State stays in your own data, there is no widget tree to synchronize, and a UI is plain Scala control flow. The cost is that a widget's response is known only while it is being drawn, and a few widgets (groups, grids, windows) size themselves from the previous frame and may lag one frame when they first appear.

## Does it use zlib, FreeType, HarfBuzz or SDL?

No. zlib is replaced by `scalaui.deflate`, fonts are parsed by `font`, shaping is implemented by `shaping` (ported from HarfBuzz and verified against `hb-shape`), and windowing talks to the OS directly. The only C is two system-header-only shims.

## Why is my debug build slow?

The CPU renderer is about ten times slower in debug mode. Use `Mode.releaseFast`. If the GPU backend is not being used, run with `SCALAUI_DEBUG=1` to see which backend was chosen and why a backend was unavailable.

## Text is missing or shows boxes

Bundled fonts are classpath resources. Set `withEmbedResources(true)` in `nativeConfig`.

## Does `java.util.zip` work?

On Scala Native it is implemented with zlib, which breaks sysroot cross-compiles. The toolkit never uses it (`docx` reads and writes zip containers itself). Use `scalaui.deflate.Zlib` in your own code if you cross-compile.

## How do I report a bug?

Open an issue at [github.com/lolgab/scalaui](https://github.com/lolgab/scalaui). Include the OS, whether you used Wayland or X11, and the output of `SCALAUI_DEBUG=1`.
