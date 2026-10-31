---
title: Build and cross-compile
section: Guide
order: 4
description: Build modes, running tests, cross-compiling to Windows, and the environment variables that control scalaui at run time.
---
# Build and cross-compile

## Everyday commands

```sh
sbt test                     # all unit tests (one project at a time is more reliable: sbt ui/test)
sbt "geom/test; ui/test"     # several, quoted as one sbt command
sbt examples/nativeLink      # widget gallery, canvas demo and image viewer
```

Notes from experience:

- Scala Native test processes print nothing to sbt. Failures are appended to `target/test-failures.log` by `scalaui.testkit.LoggedSuite`.
- A full `sbt test` compiles many modules in parallel; give sbt `-J-Xmx8g` or the parallel NIR compile can run out of memory.
- Run one project per invocation when you chain a test and `nativeLink`; chaining silently skipped one in earlier experiments.
- `ui/test` may print `Total 0` when everything is cached; use `testOnly` to force a run.
- A `rm -rf target/out/.../<module>/classes` fixes an `AccessDeniedException` caused by stale `classes/*.nir` symlinks into sbt's content-addressed store.

## Release mode matters

The CPU renderer is about ten times slower in debug builds. Use release mode for anything you want to look at:

```scala
import scala.scalanative.build.Mode
set examples/nativeConfig ~= (_.withMode(Mode.releaseFast))
```

or put `withMode(Mode.releaseFast)` in your `nativeConfig`. Apps that use the bundled fonts need `withEmbedResources(true)`, and `withMultithreading(true)` is required for the Linux AT-SPI thread (without it Scala Native runs `Thread` bodies on the caller).

## `platformConfig`

The repository's `build.sbt` defines `platformConfig`, applied to every module and to `examples`:

```scala
nativeConfig ~= platformConfig
```

It turns on resource embedding and multithreading and, when the `SCALAUI_WIN_CROSS` environment variable is set, redirects the build to a Windows target.

## Cross-compiling to Windows from macOS

With Homebrew's `mingw-w64`:

```sh
SCALAUI_WIN_CROSS=/opt/homebrew/opt/mingw-w64/toolchain-x86_64/x86_64-w64-mingw32 \
  sbt --server examples/nativeLink
```

`SCALAUI_WIN_CROSS` is the mingw sysroot. It sets the target triple to `x86_64-pc-windows-gnu`, adds `--sysroot`, the libstdc++ include paths and the library paths, and links with the mingw `ld`. The paths in `build.sbt` assume mingw-w64 with GCC 16.2.0; adjust them for your toolchain.

::: warning
Run sbt with `--server`. The default thin client talks to a background server that does not see your environment variable. If you switch settings, stop the old server first (`pkill -f sbt.script`).
:::

To run the result under Wine, copy `libgcc_s_seh-1.dll`, `libstdc++-6.dll` and `libwinpthread-1.dll` next to the `.exe`.

Because `java.util.zip` depends on zlib (which the cross sysroot lacks) the toolkit modules use `scalaui.deflate.Zlib` instead.

Native Windows and Linux binaries are normally built on their own OS (see the CI matrix in `.github/workflows/ci.yml`).

## Continuous integration

`.github/workflows/ci.yml` runs on macOS, Ubuntu x64, Ubuntu arm64 and Windows. Each job installs JDK 21, sbt and the system pieces (Linux: clang, Xvfb, lavapipe, D-Bus and AT-SPI; Windows: LLVM), then runs `tools/ci-test.sh`, which executes `sbt --server "<module>/testOnly *"` for each module in turn. Tests that only apply to another OS skip themselves. On Linux the whole run is wrapped in `xvfb-run` and a private D-Bus session with `SCALAUI_X11_TESTS=1`, so the X11, XDND, clipboard and AT-SPI tests execute; `SCALAUI_REQUIRE_VULKAN=1` turns a missing lavapipe into a failure. On Windows the Win32, Vulkan/D3D11/D3D12 (WARP) and UIA tests run on the real desktop session with `SCALAUI_REQUIRE_D3D12=1`. On failure `target/test-failures.log` is printed and uploaded. Every job also links `examples` in release mode (`SCALAUI_RELEASE=1`) and uploads the binary as an artifact, and a separate job runs `python3 site/build.py`.

::: note
The Windows job is the first time the Win32, D3D11 and D3D12 code runs on real Windows; expect to fix what it finds. Run the same script locally with `tools/ci-test.sh [module...]`.
:::

## Publishing

Publishing uses sbt-ci-release 1.12.1 (declared in `project/plugins.sbt`; it supports sbt 2 and brings sbt-dynver, sbt-pgp and the Sonatype Central client). Versions come from git tags. Push a tag to release:

```sh
git tag v0.1.0 && git push origin v0.1.0
```

`.github/workflows/release.yml` then runs `sbt ci-release` (sign and upload every module to Sonatype Central) and builds release binaries on all four OS runners and attaches them to the GitHub release. Required repository secrets: `SONATYPE_PGP_PRIVATE_KEY` (base64 of the armored private key), `SONATYPE_PGP_PRIVATE_KEY_PASSWORD`, `SONATYPE_USER` and `SONATYPE_PASSWORD` (a Central user token, not the old OSSRH login). The namespace `com.github.lolgab` must be verified in Central and the public key published to a keyserver. This workflow has not run yet. `sbt publishLocal` is the local equivalent and has been verified, and a signed `publishSigned` under a simulated tag produces the expected bundle layout (jar, sources, javadoc and POM, each with `.asc`, `.md5` and `.sha1`).

## Environment variables

Read at run time by the binary unless noted.

| Variable | Effect |
|---|---|
| `SCALAUI_BACKEND` | Force a GPU backend: `vulkan`, `d3d12`, `d3d11`, `metal` or `software`. Vulkan also works on macOS through MoltenVK. An unavailable choice falls back to software. |
| `SCALAUI_SOFTWARE` | `1` forces the CPU renderer (any non-empty value except `0`). |
| `SCALAUI_X11` | `1` forces the X11 backend even when `WAYLAND_DISPLAY` is set (XWayland). |
| `SCALAUI_RELEASE` | **Build time.** `1` links in release-fast mode (used by CI). |
| `SCALAUI_WIN_CROSS` | **Build time.** Mingw sysroot for the Windows cross-build. |
| `SCALAUI_DEBUG` | Print backend choice, per-frame timings and backend fallbacks to stderr. |
| `SCALAUI_SCALE` | Override the UI scale factor on X11 and Wayland. |
| `SCALAUI_ATSPI` | `0` disables the AT-SPI accessibility bridge on Linux. |
| `SCALAUI_VULKAN_VALIDATION` | Enable `VK_LAYER_KHRONOS_validation` for the Vulkan backend (any value except `0`). |
| `SCALAUI_X11_TESTS` | `1` runs the X11 tests in `window` (needs a display, e.g. Xvfb). |

## Windows and Wine notes

- Default backend order on Windows is Vulkan, then Direct3D 11, then software. Direct3D 11 is only used when Vulkan is missing or when you ask for it, because it has not been run on real hardware.
- Under Wine, Vulkan works through winevulkan and MoltenVK; Direct3D 11 does not work because Wine rejects the vertex shader.

## Regenerating generated sources

Some sources are generated by scripts in `tools/`: `gen_unicode.py` (Unicode tables from the UCD), `gen_shaping_golden.py` (HarfBuzz golden cases, needs `hb-shape`), `gen_vk_layout.py` (Vulkan struct layouts from the real headers), `gen_shaders.sh` (SPIR-V, needs `glslang`), `gen_keysyms.py`, `gen_webp_tables.py` and `gen_image_fixtures.sh` (needs ImageMagick, libwebp tools and ffmpeg).
