---
title: Home
nav: Home
section: Guide
order: 0
layout: home
description: scalaui is a cross-platform desktop application toolkit for Scala Native with no third-party C libraries.
---
<section class="hero">
<div>
<h1><span>scalaui</span></h1>
<p class="lead">A cross-platform desktop application toolkit for <strong>Scala Native</strong> with no third-party C libraries. Immediate-mode widgets, GPU rendering, OpenType text, image codecs and a PDF writer, all in Scala.</p>
<div class="cta">
<a class="btn primary" href="getting-started.html">Get started</a>
<a class="btn" href="architecture.html">Architecture</a>
<a class="btn" href="widgets.html">Widget reference</a>
</div>
</div>
<img src="assets/gallery.png" alt="Screenshot of the scalaui widget gallery">
</section>
<div class="home-body">

scalaui is for building apps in the spirit of [PhotoCraft](https://github.com/storytold/photocraft) and [WordCraft](https://github.com/storytold/wordcraft): a generic UI toolkit (egui, eframe, wgpu, winit, rfd, arboard, vello, parley and krilla, translated to Scala Native), not a word processor. Platform backends talk to the OS directly: Cocoa and Metal on macOS, Win32 on Windows, the X11 and Wayland wire protocols on Linux. No `libX11`, GTK, SDL, FreeType, HarfBuzz or zlib is linked.

## Hello, scalaui

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

The closure runs once per frame. There is no widget tree to keep in sync: state lives in your own `Var`s and a widget call both draws and reports interaction. See [Getting started](getting-started.md).

## Features

<div class="features">
<div class="feature"><h3>Immediate-mode UI</h3><p>Buttons, sliders, text editing with undo, tables, trees, plots, tabs, menus, drag and drop, modals, toasts, floating windows, animation and shadows. See the <a href="widgets.html">widget reference</a>.</p></div>
<div class="feature"><h3>GPU rendering</h3><p>Metal on macOS, Vulkan on Linux and Windows (and on macOS through MoltenVK), Direct3D 11 on Windows, and a CPU renderer that works everywhere.</p></div>
<div class="feature"><h3>Real text</h3><p>Pure-Scala OpenType shaping verified against HarfBuzz, Unicode bidi, line breaking and grapheme clusters, rich text, IME and bidi-aware carets.</p></div>
<div class="feature"><h3>Accessibility</h3><p>VoiceOver on macOS, UI Automation on Windows and AT-SPI on Linux, generated automatically from the widgets you call.</p></div>
<div class="feature"><h3>Native integration</h3><p>Cursors, system theme, native menu bars, tray icons, file dialogs, clipboard text and images, drag and drop of files, printing.</p></div>
<div class="feature"><h3>Image codecs</h3><p>PNG, JPEG (progressive decode, baseline encode), BMP, GIF, TIFF and WebP, checked against ImageMagick, libwebp and libjpeg fixtures.</p></div>
<div class="feature"><h3>2D graphics</h3><p>A CPU path rasterizer with fills, strokes and gradients, blend modes, filters, Lanczos resampling, offscreen layers, an SVG renderer and a PDF writer.</p></div>
<div class="feature"><h3>Testable</h3><p>A headless harness drives widgets with scripted input, and a snapshot harness renders frames offscreen and compares them to golden PNGs. See <a href="testing.html">testing</a>.</p></div>
<div class="feature"><h3>No C dependencies</h3><p>Everything is Scala plus a few system-header-only C shims. Binaries cross-compile with a plain sbt build.</p></div>
</div>

<div class="badges">
<span class="badge">Scala 3.9</span><span class="badge">Scala Native 0.5</span><span class="badge">sbt 2</span><span class="badge">macOS</span><span class="badge">Windows</span><span class="badge">Linux</span>
</div>

## Where to go next

- [Getting started](getting-started.md): sbt setup, a first app, the frame loop.
- [Architecture](architecture.md): how the modules fit together.
- [Platform support](platforms.md): what is verified on which OS, and what is not.
- [Modules](modules/index.md): the public API of each module.
- [FAQ and limitations](faq.md): honest notes on what is unverified.

</div>
