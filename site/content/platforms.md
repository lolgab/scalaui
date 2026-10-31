---
title: Platform support
section: Guide
order: 3
description: What scalaui supports on macOS, Windows and Linux, and how well each backend has been verified.
---
# Platform support

"Verified" below means exercised by tests or a manual run during development. "Type-checks only" means it compiles and links but has never been run on that OS.

## Window and rendering

| | macOS | Windows | Linux / BSD |
|---|---|---|---|
| Window system | Cocoa (`CocoaWindow`) | Win32 (`Win32Window`) | Wayland (`WaylandWindow`) when `WAYLAND_DISPLAY` is set, otherwise X11 (`X11Window`, pure-Scala wire protocol) |
| Default GPU backend | Metal | Vulkan, then Direct3D 12, then Direct3D 11, then software | Vulkan, then software |
| Other backends | Vulkan via MoltenVK, software | `d3d12` (unverified on hardware), `d3d11` on request, software | software |
| Presentation | `CAMetalLayer` | Vulkan readback or `StretchDIBits` | `PutImage` (X11) or `wl_shm` (Wayland) |
| HiDPI | backing scale factor | system DPI | `Xft.dpi` (X11), fractional scale (Wayland); `SCALAUI_SCALE` overrides |
| System theme and change events | yes | registry | GTK setting on X11 |
| Cursors, fullscreen, minimize, always-on-top, icon | yes | yes | yes (X11 cursor font and `_NET_WM_*`; Wayland cursor-shape) |

## Input and integration

| Feature | macOS | Windows | Linux |
|---|---|---|---|
| Keyboard and text input | `NSTextInputClient` | `WM_CHAR` and IME messages | X11 key mapping; Wayland XKB keymap |
| IME and dead keys | yes (`NSTextInputClient`, emoji picker) | `WM_IME_COMPOSITION` | Wayland `text-input-v3`; X11 through XIM (`Xim.scala`) |
| Clipboard text | `NSPasteboard` | Win32 clipboard | X11 selections (with INCR) and Wayland data device |
| Clipboard PNG image | yes | yes (`PNG` format) | X11 `image/png` (without INCR for images) |
| File drag and drop in | yes | `WM_DROPFILES` | XDND on X11, data device on Wayland |
| In-app drag and drop | yes (widgets) | yes | yes |
| Native menu bar | yes | `SetMenu` | drawn fallback (`Ui.menuBar`) |
| Tray icon | `NSStatusItem` | `Shell_NotifyIconW` | not implemented |
| Native file dialogs | `NSOpenPanel` / `NSSavePanel` | `GetOpenFileName` etc. | none: use the in-app `ui.FileDialog` |
| Message and confirm dialogs | `NSAlert` | `MessageBoxW` | none |
| Printing (`Platform.print`) | `lp` | PowerShell verbs | `lp` |
| Open URL (`Platform.openUrl`) | yes | yes | yes |

## Accessibility

| Platform | Bridge | Status |
|---|---|---|
| macOS | `NSAccessibilityElement` list inside the `ScaluiView` shim | VoiceOver tree, press actions and, for text fields, value, selected range, selected text, string for range, line queries and change notifications; selection can be set by VoiceOver (text editing by VoiceOver is not wired). Tested through direct calls in `ViewSuite` |
| Windows | UI Automation provider in C (`scalaui_uia.c`) plus `Win32Uia.scala` | tree, names, control types and bounding rectangles verified with Wine's UIA client; pattern invocation (Invoke, Toggle, Value, RangeValue) could not be exercised because Wine stubs `GetCurrentPattern`. Text fields also provide TextPattern (document range, selection, `ITextRangeProvider` with unit moves, `FindText`, `Select`) and a settable ValuePattern; this code compiles with mingw but has never run |
| Linux | AT-SPI2 over a pure-Scala D-Bus client (`DBus`, `AtSpi`) | tree, roles, states, extents and `DoAction` verified against libatspi; text fields implement `Text` (text, caret, selection, word/sentence/line boundaries, character extents, `text-changed` / `text-caret-moved` / `text-selection-changed` signals) and `EditableText`, checked with libatspi in `tools/atspi_check.py`; Orca itself was not run |

Set `SCALAUI_ATSPI=0` to disable the Linux bridge.

## X11 input methods

`X11Window` speaks the XIM protocol itself (no libX11): it finds the server through `XMODIFIERS=@im=name` or the root `XIM_SERVERS` property, connects over client messages and properties (`_XIM_XCONNECT`, `CONNECT`, `OPEN`, `ENCODING_NEGOTIATION`, `GET_IM_VALUES`, `CREATE_IC`, `SET_IC_VALUES`, `SET_IC_FOCUS`, `FORWARD_EVENT`, `COMMIT`, `SYNC`, `PREEDIT_START/DRAW/CARET/DONE`), prefers the on-the-spot (preedit callbacks) style and falls back to over-the-spot (it sends the caret through `Window.setImeRect`) or root-window styles. Committed text arrives as `Event.TextInput`, preedit as `Event.Composition`, and keys the server does not consume come back as ordinary `KeyDown`. With no server (or `SCALAUI_XIM=0`, or `XMODIFIERS=@im=none`) the old keyboard handling is used unchanged. `X11Window.inputMethodActive` tells whether a server is connected.

The accessibility tree is built automatically: `label`, `heading`, `button`, `checkbox`, `radio`, `slider`, `progressBar`, `collapsing`, `comboBox`, `textEdit`, `hyperlink`, `toggleSwitch`, `selectableLabel` and tabs register nodes. A screen reader's press action is replayed as a click at the element's center.

## How well is each platform verified?

| Platform | Verified | Not verified |
|---|---|---|
| macOS | Everything runs on a real Mac: Metal, Vulkan through MoltenVK, VoiceOver tree, IME through direct `NSTextInputClient` calls, menus, tray. | Visual check of live resize, Metal frame pacing during resize. |
| Linux (X11) | Test suites pass in an arm64 Ubuntu container under Xvfb with Vulkan on lavapipe: clipboard INCR both ways, cursors, window controls, XDND drops, AT-SPI. The demo renders correctly. | Real hardware, real window managers, GPU drivers, multi-monitor DPI. |
| Linux (Wayland) | Run in an Ubuntu 24.04 container against headless sway 1.9 (`WaylandSuite`, `WaylandE2ESuite`, `tools/wayland_test.sh`, `tools/wayland_dnd_test.sh`): window open and xdg-shell configure, resize and re-allocated `wl_shm` buffers, pixel-exact output compared with `grim` at scale 1, 1.5 (`wp_fractional_scale` plus `wp_viewporter`) and 2, fullscreen, title, server-side decorations (`xdg-decoration`), compositor close, keyboard from `wtype` (XKB keymap parser, `KeyDown` plus `TextInput`, modifiers, client-side repeat, a per-key non-US keymap), pointer through wlroots virtual-pointer (motion, buttons, wheel), cursor shapes and hidden cursor without protocol errors, clipboard both ways with `wl-copy`/`wl-paste` (text and 3 MB images), file drop from a second client over the data device (and declining other payloads), `text-input-v3` preedit and commit against a scripted `input-method-v2` client. The demo runs on the Vulkan backend over lavapipe and the output matches the software backend to within 9 of 786432 pixels. Under weston 13 (headless, pixman) the window opens, renders and honours integer output scale 2. | GNOME (Mutter) and KDE (KWin), real GPUs and input devices, `wp_cursor_shape` visuals (shapes are only checked for protocol errors), minimize (sway ignores it), IME with a real engine (ibus/fcitx), weston input (no virtual keyboard/pointer protocols), and any client-side decoration fallback (none exists: the window is undecorated where `xdg-decoration` is missing). |
| Windows | Cross-compiled executable runs under Wine; Vulkan backend runs there through winevulkan and MoltenVK; Direct3D 12 runs there through vkd3d and renders the demo matching the software renderer; UIA tree read by Wine's UIA client. | **Win32 on real Windows**, **Direct3D 12 on a real GPU**, **Direct3D 11** (Wine rejects its vertex shader at feature level 9_3), UIA patterns, IME, tray, native menus on real hardware. |
| FreeBSD | Routed to the X11 backend. | Never built or run. |

See the [FAQ](faq.md) for the practical consequences.
