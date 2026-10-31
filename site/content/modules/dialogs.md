---
title: dialogs
section: Modules
order: 17
description: Native open, save and folder dialogs and message boxes on macOS and Windows.
---
# dialogs

`scalaui.dialogs.Dialogs` shows native modal dialogs (the role of rfd). Depends on [window](window.md).

```scala
import scalaui.dialogs.Dialogs
```

## API

| Method | Description |
|---|---|
| `openFile(title = "Open", extensions = Nil): Option[String]` | file picker; `extensions` such as `Seq("png", "jpg")` filter the list |
| `openDirectory(title = "Choose folder"): Option[String]` | folder picker |
| `saveFile(title = "Save", suggestedName = ""): Option[String]` | save picker |
| `message(title, message): Unit` | information box with an OK button |
| `confirm(title, message): Boolean` | Yes/No question; true for Yes |

All calls block until the dialog closes. File methods return `None` when cancelled.

## Platforms

| Platform | Implementation |
|---|---|
| macOS | `NSOpenPanel`, `NSSavePanel`, `NSAlert` |
| Windows | `GetOpenFileNameW`, `GetSaveFileNameW`, `SHBrowseForFolderW`, `MessageBoxW` |
| Linux | none: the file methods return `None`, `message` does nothing and `confirm` returns `false`. Use the in-app [`ui.FileDialog`](../widgets.md#filedialog) and `ui.modal` instead |

## Example

```scala
import scalaui.dialogs.Dialogs
import scalaui.ui.*

val status = Var("")
App.run("dialogs") { ui =>
  if ui.button("Open image…").clicked then
    Dialogs.openFile("Open image", Seq("png", "jpg", "webp")) match
      case Some(path) => status.value = s"Opened $path"
      case None => status.value = "Cancelled"
  if ui.button("Quit?").clicked && Dialogs.confirm("Quit", "Really quit?") then sys.exit(0)
  ui.label(status.value)
}
```

::: note
Showing a modal dialog blocks the frame loop. That is fine for short interactions; on Linux use `ui.FileDialog`, which is drawn by the toolkit and does not block.
:::
