---
title: clipboard
section: Modules
order: 18
description: System clipboard text and PNG images on macOS, Windows and Linux.
---
# clipboard

`scalaui.clipboard.Clipboard` reads and writes the system clipboard (the role of arboard). Depends on [window](window.md), because the X11 and Wayland implementations need the window's connection.

```scala
import scalaui.clipboard.Clipboard
```

## API

| Method | Description |
|---|---|
| `getText(): Option[String]` | UTF-8 text, if any |
| `setText(text)` | replaces the clipboard text |
| `getImagePng(): Option[Array[Byte]]` | an image on the clipboard as PNG bytes |
| `setImagePng(png)` | puts a PNG image on the clipboard |

## Platforms

| Platform | Implementation |
|---|---|
| macOS | `NSPasteboard` (`public.utf8-plain-text`, `public.png`) |
| Windows | Win32 clipboard (`CF_UNICODETEXT`, registered `PNG` format) |
| Linux (X11) | selection protocol with `CLIPBOARD`, `UTF8_STRING`, `TARGETS`; INCR transfers in both directions for large text; `image/png` without INCR |
| Linux (Wayland) | the data-device protocol |

On Linux the selection is owned by the application's window, so create a window first (`App.run` does); without one `getText` returns `None` and `setText` does nothing. The `ui` text editor uses this module for copy and paste.

## Example

```scala
import scalaui.clipboard.Clipboard
import scalaui.codecs.*

Clipboard.setText("hello")
val t = Clipboard.getText()

// copy an image
val img = Image.decode(pngOrJpegBytes)
Clipboard.setImagePng(Image.encode(img, ImageFormat.Png))

// paste an image
Clipboard.getImagePng().foreach { png => val pasted = Image.decode(png) }
```
