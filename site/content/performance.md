---
title: Performance
section: Reference
order: 2
description: Benchmarks, before/after numbers, Scala Native performance pitfalls and the damage-rectangle API.
---
# Performance

This page records where the CPU time of scalaui goes, what was done about it and how to measure it again. Every optimization listed here keeps the output **bit-identical** (checksummed in the benchmark) unless it says otherwise, and all existing tests and golden images pass unchanged.

## Measuring

`modules/bench` is a small benchmark `main` (not part of the aggregate build, one file, no framework). It prints best-of-N milliseconds and a checksum of each result, so an optimization can be verified to leave the output untouched:

```
sbt --server -J-Xmx8g "bench/nativeLink"
target/out/native0.5/scala-3.9.0/scalaui-bench/scalaui-bench               # everything
target/out/native0.5/scala-3.9.0/scalaui-bench/scalaui-bench jpeg raster   # cases whose name contains a filter
BENCH_ITERS=40 .../scalaui-bench "SoftwareRenderer 1080p"                  # more iterations, e.g. to sample
```

The bench project is always linked in `release-fast` mode (debug builds are 5-10x slower and meaningless for timing). To find hot spots use the macOS sampler on a long-running case: `/usr/bin/sample <pid> 3 -file out.txt`, then read the *Sort by top of stack* section. (Homebrew ships an unrelated `sample` script earlier on the PATH, use the full path.)

Numbers below were taken on an Apple Silicon Mac, single thread, best of several runs; "before" is the code at the start of this work built with the previous configuration, "after" is the current tree.

## Results

| Case | Before (ms) | After (ms) | Speed-up |
|---|---:|---:|---:|
| Raster: fill 20 self-intersecting stars, 1080p, even-odd | 2070 | 42 | 49x |
| Raster: fill 30 cubic blobs | 5454 | 23 | 234x |
| Raster: stroke 30 cubic blobs (round caps/joins) | 82935 | 225 | 369x |
| Raster: linear gradient fill, 1080p | 448 | 37 | 12x |
| Raster: radial gradient fill, 1080p | 442 | 39 | 11x |
| Raster: solid translucent rect, 1080p | 141 | 19 | 7x |
| `Pixmap.toRgba8` 1080p | 48 | 11 | 4x |
| Gaussian blur 1280x720, sigma 8 | 228 | 134 | 1.7x |
| Gaussian blur 1280x720, sigma 2 | 59 | 36 | 1.6x |
| Gaussian blur approximation (new, sigma 8) | n/a | 33 | 6.8x vs exact |
| 3x3 convolution 720p | 1126 | 30 | 37x |
| Blend `composite` Hue 720p | 557 | 31 | 18x |
| Blend `composite` Normal / Multiply 720p | 19 / 32 | 5 / 16 | 3.9x / 2x |
| Lanczos3 resize 1280x720 to 640x360 | 58 | 20 | 2.9x |
| Lanczos3 resize 1280x720 to 1920x1080 | 322 | 85 | 3.8x |
| JPEG decode 1280x720 (4:2:0) | 196 | 22 | 8.9x |
| JPEG decode 1280x720 (4:4:4) | 302 | 28 | 11x |
| JPEG encode 1280x720 | 508 | 20 | 25x |
| PNG decode 1280x720 | 57 | 29 | 2x |
| PNG encode 1280x720 | 326 | 202 | 1.6x |
| WebP lossless encode 1280x720 | 23735 | 30 | 796x |
| WebP lossy / lossless decode (test fixtures, small) | 1.5 / 2.3 | 0.9 / 1.4 | 1.6x |
| Text layout, 21,800 characters wrapped | 102 | 31 | 3.3x |
| Text layout, 200 short labels (cold) | 15 | 4.3 | 3.5x |
| Text layout, 200 short labels (cached) | 9.7 | 0.03 | 320x |
| GlyphAtlas, 1000 cold glyphs | 247 | 6.3 | 39x |
| `SoftwareRenderer` 1080p text-heavy frame | 482 | 83 | 5.8x |
| `SoftwareRenderer` 4K (1080p at 2x) | 618 | 270 | 2.3x |
| `SoftwareRenderer` 1080p, 5% damage rect | n/a | 7 | 12x vs full |
| `SoftwareRenderer` 1080p, 25% damage rect | n/a | 25 | 3.3x vs full |

Checksums of every output are identical before and after, with two intended exceptions: the WebP encoder (see below) and the cold-label case, whose strings contain a counter.

## What changed

### Scala Native pitfalls that dominated the profiles

These cost more than any algorithmic issue, and apply to all code in this repository:

- **`java.util.Arrays.fill`, `equals` and `hashCode` are generic and slow.** Their Scala Native implementations go through `Array[T]` with boxing, about 50 ns per element. In hot code use a `while` loop (or `System.arraycopy`, which is a real `memcpy`).
- **Multithreaded builds poll for GC safepoints at every loop back edge.** `withMultithreading(true)` made every tight pixel loop 1.2x to 2.3x slower. Threads are only used by the Linux window code (AT-SPI, X11, Wayland), so `build.sbt` now enables multithreading only when building for Linux (`SCALAUI_MT=1` or `0` overrides). The `window` test binary stays multithreaded because its Linux suites start threads on every OS.
- **Closures, tuples and `for` comprehensions over ranges box.** `Function3` is not specialized, `for x <- 0 until n` with a closure body boxes the `Int`, a method returning a tuple allocates, and captured `var`s become heap `Ref` objects. Inner loops use `while`, primitive arrays and no local functions that capture mutable state.

### raster

- `Rasterizer` keeps edges in flat primitive arrays, buckets them by first scanline and walks an active list that stays in original edge order, so equal crossings are resolved exactly like a stable sort of all edges. Coverage is delivered per row (`Rasterizer.fillRows` with a `RowSink`) over just the touched x range; `Rasterizer.fill` keeps its old callback signature. A test compares the coverage bit for bit with the straightforward formulation on random polygons.
- `Pixmap.fill` and `stroke` use a solid-color row sink (opaque spans are plain stores) and allocation-free gradient evaluation (`Paint.colorAtInto`, precomputed stop tables). `toRgba8` has an opaque fast path.

### imaging

- Gaussian blur: the same tap-order accumulation (so the result is bit-identical), but four pixels per sweep horizontally (independent add chains hide float latency) and row-wise, four-taps-per-sweep accumulation vertically.
- `Filters.gaussianBlurApprox` is new and **not** bit-identical: three sliding-window box blurs per axis, O(1) per pixel regardless of sigma, within a few percent of the exact kernel (tested). Use it for large radii; the exact blur stays the default.
- Resize computes the weights once per output sample and runs the vertical pass over whole rows. The non-separable blend modes no longer allocate per pixel. `convolve3` uses plain loops.

### codecs and deflate

- JPEG decode: zero-skipping separable float IDCT (adding `0 * cos` never changes a sum, so the result is unchanged), chroma upsampling with per-column indices precomputed, and a flat color-convert loop. The JPEG encoder's DCT and block sampling are loops without closures.
- Inflate is table-driven: a 10-bit direct lookup for short codes with the original bit-at-a-time decoder as the fallback for long or invalid codes (so errors behave as before), and a 64-bit bit buffer.
- **Bug fixed:** the lossless WebP encoder wrote an undecodable stream whenever a channel's code-length alphabet had a single used symbol (for example a smooth image using all 256 values with equal lengths). A regression test (`WebpEncoderRoundTripSuite`) covers it. The encoder also recounted the code's symbols for every pixel, which made it 800x slower than necessary.

### text

- `TextLayout.layout` keeps a small bounded cache (1024 entries, texts up to 2000 characters) keyed by every argument including the `Shaper` instance. Layouts are immutable, so sharing them is safe. `ui` already caches per `Context`; this serves other callers.
- Shaping itself is not cached per word: HarfBuzz-style shaping depends on context across word boundaries (kerning over spaces, contextual forms), so a per-word cache would change output.
- Glyph rasterization (`GlyphAtlas`) got the rasterizer speed-up (39x).

### gpu: `SoftwareRenderer` and damage rectangles

- The 4x sample buffer is reused between frames, the per-row edge clipping no longer allocates closures, bilinear sampling no longer allocates a tuple per pixel and a blend into four identical samples is computed once.
- **Damage rectangles.** `GpuBackend.submit(frame, damage: Option[Rect])` (logical points) tells a backend that only `damage` differs from the previous frame (same size, scale and texture contents). The frame must still describe the whole picture. `SoftwareBackend` keeps the previous pixels and redraws, clears and resolves only the damaged pixels (`SoftwareRenderer.renderInto(frame, target, damage)`); pixels in the region are identical to a full render (tested). Other backends inherit a default that ignores the hint, so callers can pass it unconditionally.
- **All GPU backends honor it** (see [gpu](modules/gpu.md#damage-rectangles)). Vulkan and Metal keep a persistent multisample image (4x memory, but no extra passes): a partial frame clears just the damaged rectangle of it, draws scissored to the rectangle, resolves, and (Vulkan) copies back and presents only that rectangle; `Window.present(rgba, w, h, x, y, dirtyW, dirtyH)` forwards the rectangle to the OS (Cocoa: copy only the dirty rows into one of two native buffers; Win32: convert and `StretchDIBits` the rectangle; X11: `PutImage` of the sub-rectangle; Wayland: copy plus `wl_surface.damage_buffer` of the rectangle; D3D12: `Present1` dirty rect). `VulkanSuite` (MoltenVK) and `MetalSuite` replay a scripted sequence (moved translucent circle, ring, off-grid rectangles, empty and whole-frame damage, a resize and a scale change, at 1x and 2x) and require byte-identical pixels against a full redraw of the same frame. Win32, X11 and Wayland `present` and the D3D12 `Present1` call are only compile-checked on macOS.
- **The UI layer uses it.** `App` runs every frame through a `DamageTracker` (see [ui](modules/ui.md#damage-tracking)): the draw output is hashed per 32-point tile, textures are tracked for uploads, and the bounding box of the changed tiles goes to `submit`/`present`. It falls back to a full redraw after a resize, scale, theme or zoom change, for the first frame and when more than 85% of the window changed. `DamageSuite` checks byte-identical output on every frame of a hover / click / typing / caret blink / animation / resize / theme script, with the headless backend.

### Idle caret blink and hover, before and after

`examples` `damageBench` (the `demo damageBench` entry point; release-fast, Apple Silicon): a 900x600 window at 2x (1800x1200 px) with a focused text field, a multiline field and 18 labels. Mean milliseconds per frame, `ui` is building the frame, `submit` includes hashing (damage on) and the backend call; "cpu" is process CPU time per frame. Metal is asynchronous, so its submit is CPU encode time only.

| Backend | Case | damage off: ui + submit (ms) | damage on: ui + submit (ms) | Mean area redrawn |
|---|---|---:|---:|---:|
| Software | caret blink | 0.4 + 27.8 | 0.4 + 0.16 | 0% (blink frames: the caret tiles) |
| Software | hover | 0.2 + 25.6 | 0.2 + 0.6 | 0.6% |
| Vulkan (MoltenVK) | caret blink | 0.3 + 1.9 | 0.7 + 0.3 | 0% |
| Vulkan (MoltenVK) | hover | 0.2 + 1.4 | 0.9 + 1.6 | 0.6% |
| Metal | caret blink | 0.9 + 0.3 | 1.2 + 0.3 | 0% |
| Metal | hover | 0.7 + 7.7 | 1.2 + 7.5 | 0.5% |

Reading it: the CPU renderer goes from about 27 ms to under 1 ms per frame; a blink or hover frame no longer costs a full-window render. On the GPU backends the frame is already cheap, so the win is mostly that identical frames cost nothing (blink frames between toggles, hover frames after the fade ends: no encode, no readback, no present) and that the OS receives a small dirty rectangle (less copying on Cocoa/Win32/X11/Wayland, less recomposition on DXGI). The tracker itself costs roughly 0.3 to 0.6 ms for a frame of this size (it appears in the `ui + submit` of the damage-on rows), so a frame that really changes a lot is about as fast as before and a Vulkan hover frame is no faster, because its 1.5 ms is dominated by waiting for the fence, not by pixel work. The Metal hover `submit` of 7.5 ms is waiting for the next drawable and is the same with and without damage; the process CPU per frame was 1.3 to 1.4 ms either way.

## Not done

- The stroker still emits one polygon per segment, join and cap. Merging them into a single outline would cut the stroke time further but would change coverage rounding.
- Deflate compression is a greedy hash-chain matcher (about 18 MB/s at level 6); lazy matching would improve the ratio but changes the bytes written.
- Per-word shaping caches, as explained above.
