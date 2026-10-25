---
title: window
section: Modules
order: 15
description: Native windows, input events, cursors, menus, tray icons and accessibility hooks for macOS, Windows and Linux.
---
# window

`scalaui.window` is the platform layer (the role of winit): one `Window` trait with backends for Cocoa, Win32, X11 and Wayland, a common `Event` type, and the bridges to each OS's accessibility API. Depends on [geom](geom.md). Use it directly for custom-rendered apps, or let [ui](ui.md)'s `App.run` drive it.

```scala
import scalaui.window.*
```

## Creating a window

```scala
val win = Window.create("My app", 800, 500)
```

`Window.create` picks the backend with link-time OS checks: `CocoaWindow` on macOS, `Win32Window` on Windows, and on Linux and FreeBSD `WaylandWindow` when `WAYLAND_DISPLAY` is set (falling back to X11 if the Wayland connection fails; `SCALAUI_X11=1` forces X11), otherwise `X11Window`.

## The Window trait

Sizes are in logical points; `scaleFactor` converts to device pixels.

| Member | Description |
|---|---|
| `width`, `height`, `scaleFactor` | current size and DPI scale |
| `pollEvents(): Seq[Event]` | drain pending events without blocking |
| `waitEvents(timeoutMs): Seq[Event]` | block up to a timeout for an event, then drain |
| `present(rgba, pixelWidth, pixelHeight)` | show a premultiplied RGBA8 image as the window contents |
| `present(rgba, pixelWidth, pixelHeight, x, y, w, h)` | like `present`, but only the pixel rectangle changed since the previous call (same size): Cocoa copies just those rows into one of two native buffers, Win32 converts and `StretchDIBits`es the rectangle, X11 sends a `PutImage` of the sub-rectangle, Wayland copies it into the shm buffer and sends `wl_surface.damage_buffer` for it. `rgba` must still hold the whole image; the default implementation presents everything |
| `setTitle`, `close()` | |
| `setCursor(CursorIcon)` | `Default`, `Pointer`, `Text`, `Crosshair`, `Grab`, `Grabbing`, `ResizeHorizontal`, `ResizeVertical`, `NotAllowed`, `Hidden` |
| `setImeRect(x, y, w, h)` | caret rectangle where the input-method candidate window anchors (Cocoa, Win32 and, as the spot location, XIM) |
| `supportsTextInput` | the backend delivers typed text as `Event.TextInput` instead of `KeyDown.text` |
| `systemTheme: Theme` | `Theme.Light` or `Theme.Dark` |
| `setMinSize`, `setResizable`, `setFullscreen`, `isFullscreen`, `setMinimized`, `setAlwaysOnTop`, `setVisible`, `focus`, `requestAttention`, `position`, `setPosition`, `setIcon` | window management |
| `setLiveResizeCallback(f)` | called while the OS is inside its modal resize loop so the app can repaint at the new size |
| `setMenuBar(menus)`, `hasNativeMenus` | native menu bar (macOS, Windows) |
| `setTray(rgba, w, h, tooltip, menu)`, `removeTray()` | system tray or menu-bar icon (macOS, Windows) |
| `setAccessibility(nodes: Seq[AccessNode])` | publish the accessibility tree to VoiceOver, UI Automation or AT-SPI |
| `nativeHandle` | the OS view or surface (an `NSView*` on macOS) |

Most of these have no-op defaults, so backends only implement what their OS supports. See [Platform support](../platforms.md).

## Events

```scala
enum Event:
  case CloseRequested
  case Resized(width: Int, height: Int)
  case MouseMoved(x: Double, y: Double)
  case MouseDown(button: MouseButton, x: Double, y: Double, mods: Modifiers)
  case MouseUp(button: MouseButton, x: Double, y: Double)
  case Scroll(dx: Double, dy: Double)
  case KeyDown(key: Key, text: String, chars: String, mods: Modifiers, repeat: Boolean)
  case KeyUp(key: Key, mods: Modifiers)
  case TextInput(text: String)                                   // committed text, incl. IME, dead keys, emoji picker
  case Composition(text: String, cursorStart: Int, cursorLength: Int)   // in-progress IME text
  case FilesHovered(paths: Seq[String], x: Double, y: Double)
  case FilesHoverCancelled
  case FilesDropped(paths: Seq[String], x: Double, y: Double)
  case ThemeChanged(theme: Theme)
  case Focused(focused: Boolean)
  case ScaleChanged(scaleFactor: Double)
  case AccessPress(index: Int)                                   // screen-reader activation
  case MenuAction(id: Int)                                       // native menu or tray item chosen
```

`MouseButton` is `Left`, `Right` or `Other`. `Key` covers `Left`, `Right`, `Up`, `Down`, `Home`, `End`, `PageUp`, `PageDown`, `Backspace`, `Delete`, `Enter`, `Tab`, `Escape`, `Space` and `Other(code)`. `Modifiers(shift, control, alt, command)` has a `shortcut` helper (Cmd on macOS, Ctrl elsewhere) and `Modifiers.None`.

On backends where `supportsTextInput` is true (macOS, Windows, Wayland), typed text arrives as `TextInput` and `KeyDown.text` is empty; `KeyDown.chars` holds the unmodified key for shortcut matching.

## Menus and tray

```scala
enum MenuEntry:
  case Item(title: String, id: Int, shortcut: String = "", enabled: Boolean = true, checked: Boolean = false)
  case Separator
  case Submenu(title: String, entries: Seq[MenuEntry])
```

Shortcuts are written like `"mod+shift+s"`: `mod` is Cmd on macOS and Ctrl elsewhere; `cmd`, `ctrl`, `alt` and `shift` are literal; the key is a character or one of `enter`, `tab`, `esc`, `delete`, `backspace`, `space`, `left`, `right`, `up`, `down`, `f1` to `f12`. Chosen items arrive as `Event.MenuAction(id)`.

## Accessibility

`AccessNode(role, label, bounds, value = "", checked = None, focused = false, disabled = false, text = None)` with roles `Label`, `Heading`, `Button`, `CheckBox`, `RadioButton`, `Switch`, `Slider`, `TextInput`, `ComboBox`, `Link`, `ProgressBar`, `Tab`, `ListItem`. Bounds are in window points with a top-left origin. `ui` builds the list for you every frame.

Text-entry nodes carry `text = Some(AccessText(caret, selStart, selEnd, multiline, editable, charBounds))`: `value` is the text and the offsets are UTF-16 indices into it. Screen readers can ask for the text, caret, selection, word/line segments and character rectangles, and can move the caret or edit the field; those requests arrive as `Event.AccessSetSelection(index, anchor, caret)` and `Event.AccessSetText(index, text)`, which `App` applies to the field on the next frame. AT-SPI (`Text`, `EditableText`, text signals) and UI Automation (`TextPattern`, settable `ValuePattern`) implement these; on macOS the element answers the `NSAccessibility` text attributes and VoiceOver can set the selection.

## Platform

`Platform.openUrl(url)` opens a URL or file with the default application, `Platform.printers()` lists CUPS printers (empty on Windows) and `Platform.print(file, printer = None, copies = 1)` sends a document to the print command.

## Example: a bare event loop

```scala
import scalaui.window.*

val win = Window.create("events", 640, 400)
var running = true
while running do
  win.waitEvents(250).foreach {
    case Event.CloseRequested => running = false
    case Event.MouseDown(b, x, y, _) => println(s"$b at $x,$y")
    case Event.KeyDown(Key.Escape, _, _, _, _) => running = false
    case Event.TextInput(t) => println(s"typed: $t")
    case Event.FilesDropped(paths, _, _) => println(paths.mkString(", "))
    case _ => ()
  }
win.close()
```
