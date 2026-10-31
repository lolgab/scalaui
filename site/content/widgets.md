---
title: Widget reference
section: Reference
order: 1
description: Every widget and layout container in the scalaui ui module, with signature and example.
---
# Widget reference

All widgets are methods on `scalaui.ui.Ui` (some are extension methods defined in the same package, so `import scalaui.ui.*` brings everything in). Snippets below run inside a frame function:

```scala
import scalaui.ui.*
App.run("demo") { ui => /* snippet here */ }
```

State is held in `Var[T]` cells (`Var(0)`, `Var("")`, `Var(true)`). Widgets that edit a value take a `Var` and set `.value`. The snippets are fragments: names such as `name`, `enabled`, `volume` or `tint` stand for `Var`s (or plain values) declared outside the frame function, and helpers such as `save()` for your own code.

## Response

Almost every widget returns a `Response`:

```scala
final case class Response(rect: Rect, hovered: Boolean = false, pressed: Boolean = false, clicked: Boolean = false,
    dragged: Boolean = false, changed: Boolean = false, focused: Boolean = false,
    dragDelta: Vec2 = Vec2.Zero, scroll: Vec2 = Vec2.Zero, mouse: Option[Point] = None, doubleClicked: Boolean = false,
    secondaryClicked: Boolean = false, link: Option[String] = None)
```

`link` is the target of the rich-text link activated this frame (see `richLinks`). `secondaryClicked` is true on the frame the right button goes down over the widget. `clicked` is true on the frame the mouse is released over the widget, `changed` when a bound `Var` was modified this frame, `rect` is the allocated area in window points.

```scala
val r = ui.button("Save")
if r.clicked then save()
if r.hovered then ui.ctx.cursorIcon = scalaui.window.CursorIcon.Pointer
```

## Layout

### horizontal, vertical, indent, space

```scala
def horizontal(f: Ui => Unit): Unit
def vertical(f: Ui => Unit): Unit
def indent(f: Ui => Unit): Unit
def space(amount: Double): Unit
```

```scala
ui.horizontal { row =>
  row.label("Name:")
  row.textEdit(name)
}
ui.indent { i => i.label("indented") }
ui.space(12)
```

In a row, shorter widgets are centered vertically on the row.

### group and card

```scala
def group(f: Ui => Unit): Unit
def card(title: String = "")(body: Ui => Unit): Unit
```

```scala
ui.group { g => g.label("Grouped"); g.monospace("val x = 42") }
ui.card("Settings") { c => c.toggleSwitch(enabled, "Enabled") }
```

Both draw a frame sized from the previous frame, so the frame can lag one frame on first appearance.

### Panels and bars

```scala
def topPanel(height: Double)(f: Ui => Unit): Unit
def leftPanel(width: Double)(f: Ui => Unit): Unit
def rightPanel(width: Double)(f: Ui => Unit): Unit
def bottomPanel(height: Double)(f: Ui => Unit): Unit
def bottomBar(height: Double, fill: Color)(f: Ui => Unit): Unit
def statusBar(height: Double = 26)(body: Ui => Unit): Unit
def toolbar(height: Double = 40)(body: Ui => Unit): Unit
```

A panel takes a slice of the current region; the remaining region is what is left, so call panels before the main content.

```scala
ui.topPanel(36) { t => t.heading("My app") }
ui.statusBar() { s => s.weak("Ready") }
ui.leftPanel(200) { l => l.navItem(Icons.home, "Home", selected = true) }
ui.label("Main content goes here")
```

### workspace and padded

```scala
def workspace(color: Option[Color] = None, padding: Double = 16)(body: Ui => Unit): Unit
def padded(amount: Double)(body: Ui => Unit): Unit
```

`workspace` fills the rest of the region with a color (default: `style.canvas`) and lays out inside it, typical for a document area. `padded` shrinks the region by `amount` on every side.

### scrollArea

```scala
def scrollArea(height: Double, width: Option[Double] = None)(body: Ui => Unit): Unit
```

```scala
ui.scrollArea(160) { s => (1 to 50).foreach(i => s.label(s"Row $i")) }
```

A fixed-height viewport scrolled by wheel, trackpad and a draggable bar. A nested widget that scrolls by the wheel itself (a multiline `textEdit` taller than its viewport) consumes the wheel while it can still scroll in that direction; the enclosing area only scrolls once the field is at its end (`ctx.claimWheel` lets custom widgets do the same).

### grid

```scala
def grid(cols: Int)(body: Grid => Unit): Unit   // Grid.cell(f: Ui => Unit), Grid.endRow()
```

```scala
ui.grid(3) { g =>
  for (k, v, u) <- Seq(("Width", "1920", "px"), ("Height", "1080", "px")) do
    g.cell(_.label(k)); g.cell(_.label(v)); g.cell(_.weak(u))
}
```

Rows wrap every `cols` cells; column widths settle after one frame.

### collapsing

```scala
def collapsing(title: String, defaultOpen: Boolean = false)(body: Ui => Unit): Unit
```

```scala
ui.collapsing("Advanced", defaultOpen = true) { c => c.checkbox(verbose, "Verbose") }
```

### disabled and animate

```scala
def disabled(cond: Boolean)(body: Ui => Unit): Unit
def animate(target: Double, seconds: Double = 0.12): Double
def animate(target: Boolean): Double
```

```scala
ui.disabled(!enabled.value) { d => d.button("Needs enabling") }
val t = ui.animate(open.value)   // eases 0.0 .. 1.0, requests repaints until settled
```

Inside `disabled`, widgets are inert and drawn dimmed (and reported as disabled to accessibility APIs).

## Text

### label, heading, weak, strong, caption, monospace

```scala
def label(text: String, color: Option[Color] = None, size: Option[Double] = None, mono: Boolean = false,
          weight: Weight = Weight.Regular, tracking: Double = 0.0): Response
def heading(text: String): Response
def weak(text: String): Response
def strong(text: String): Response
def caption(text: String): Response
def monospace(text: String): Response
```

```scala
ui.heading("Report")
ui.label("Warning", color = Some(ui.style.warning))
ui.weak("secondary text")
ui.monospace("val x = 42")
```

`Weight` is `Regular`, `Medium` or `SemiBold` (the bundled Inter family).

### richText and span

```scala
def richText(spans: Seq[Span], align: Align = Align.Left): Response
def span(text: String, bold: Boolean = false, italic: Boolean = false, monospace: Boolean = false,
         color: Option[Color] = None, /* ... size, underline, strike, background */): Span
```

```scala
ui.richText(Seq(
  ui.span("Rich ", bold = true, size = Some(20)),
  ui.span("text mixes "), ui.span("italic", italic = true), ui.span(" and "),
  ui.span("code", monospace = true), ui.span(" runs in one wrapped paragraph.")
))
```

### Links in rich text: link and richLinks

```scala
final case class RichLink(span: Span, target: String)
def link(text: String, target: String, bold: Boolean = false, italic: Boolean = false, monospace: Boolean = false): RichLink
def richLinks(parts: Seq[Span | RichLink], align: Align = Align.Left)(onLink: String => Unit = _ => ()): Response
```

```scala
val r = ui.richLinks(Seq(
  ui.span("See the "), ui.link("guide", "guide-page"), ui.span(" or the "), ui.link("API reference", "api"), ui.span(".")
))(id => openPage(id))
r.link.foreach(id => println(s"clicked $id"))
```

Like `richText`, but link spans are underlined in the accent color, show a pointer cursor and a brighter color on hover, and report the activated link's `target` both to the `onLink` callback and as `Response.link` (together with `clicked`). Links that wrap across lines stay one link: each line fragment is clickable. Every link takes part in the Tab order (Enter or Space activates it, with a focus ring) and is exposed to assistive technology with the `Link` role, its target as the value.

### selectableLabel, separatorLabel, hyperlink

```scala
def selectableLabel(selected: Boolean, text: String): Response
def separatorLabel(text: String): Unit
def hyperlink(text: String, url: String): Response
```

```scala
if ui.selectableLabel(current.value == "a", "Option A").clicked then current.value = "a"
ui.separatorLabel("Advanced")
ui.hyperlink("Documentation", "https://github.com/lolgab/scalaui")   // opens via Platform.openUrl
```

### separator

```scala
def separator(): Unit
```

## Buttons and toggles

### button, primaryButton

```scala
def button(text: String): Response
def button(text: String, fill: Option[Color]): Response
def button(text: String, kind: ButtonKind, icon: Option[Icon] = None): Response
def primaryButton(text: String, icon: Option[Icon] = None): Response
```

`ButtonKind` is `Default`, `Primary`, `Subtle` or `Danger`.

```scala
if ui.primaryButton("Save", Some(Icons.save)).clicked then save()
if ui.button("Delete", ButtonKind.Danger).clicked then delete()
```

### checkbox, radio, toggleSwitch

```scala
def checkbox(value: Var[Boolean], label: String): Response
def radio[T](selected: Var[T], value: T, label: String): Response
def toggleSwitch(value: Var[Boolean], label: String = ""): Response
```

```scala
ui.checkbox(enabled, "Enabled")
ui.horizontal { h =>
  h.radio(mode, "slow", "Slow"); h.radio(mode, "fast", "Fast")
}
ui.toggleSwitch(darkMode, "Dark mode")
```

### imageButton

```scala
def imageButton(texture: gpu.Texture, size: Size, tip: String = "", selected: Boolean = false): Response
```

A button showing a texture (thumbnail or app icon) with hover, press and selected states.

### iconButton, toolButton, menuButton, iconMenuButton

```scala
def iconButton(icon: Icon, tip: String = "", selected: Boolean = false, size: Double = 28): Response
def toolButton(icon: Icon, label: String, selected: Boolean = false, menu: Boolean = false): Response
def menuButton(label: String, icon: Option[Icon] = None, kind: ButtonKind = ButtonKind.Default)(body: Ui => Unit): Response
def iconMenuButton(icon: Icon, tip: String)(body: Ui => Unit): Response
```

```scala
ui.toolbar() { t =>
  if t.iconButton(Icons.bold, "Bold", selected = bold.value).clicked then bold.value = !bold.value
  t.iconMenuButton(Icons.alignLeft, "Align") { m =>
    if m.menuItem("Left").clicked then align.value = 0
    if m.menuItem("Center").clicked then align.value = 1
  }
}
```

`iconButton` shows a tooltip from `tip`, which is also its accessible name. `menuButton` and `iconMenuButton` open a popup beneath them while open, closed by clicking elsewhere.

### Keyboard focus

Tab and Shift-Tab move focus through buttons, checkboxes, radios, switches, sliders, combo boxes, tabs, tool/nav buttons, links, tree rows, tables, lists, splitters and text fields in layout order; the focused widget shows a ring after keyboard navigation. Enter and Space activate it (the response reports `clicked`), sliders take arrow keys, Home and End, and tree rows take Left/Right/Up/Down. Inside a modal dialog or open popup Tab cycles only through its content. Custom widgets join in with `ctx.interact(rect, id, focusable = true)`, or `ctx.registerFocus(id)` and `ctx.hasFocus(id)` for fully custom handling.

### segmented, badge, keycap, navItem

```scala
def segmented(selected: Var[Int], labels: Seq[String], icons: Seq[Icon] = Nil): Response
def badge(text: String, color: Option[Color] = None): Response
def keycap(text: String): Response
def navItem(icon: Icon, label: String, selected: Boolean, badge: String = ""): Response
```

```scala
ui.segmented(view, Seq("List", "Grid"))
ui.badge("beta"); ui.keycap("Ctrl+S")
if ui.navItem(Icons.home, "Home", selected = page.value == 0, badge = "3").clicked then page.value = 0
```

## Input

### slider, dragValue, progressBar, spinner

```scala
def slider(value: Var[Double], min: Double, max: Double, label: String = ""): Response
def dragValue(v: Var[Double], speed: Double = 0.1, min: Double = Double.NegativeInfinity,
              max: Double = Double.PositiveInfinity, suffix: String = ""): Response
def progressBar(fraction: Double, width: Double = 200): Unit
def spinner(diameter: Double = 18): Unit
```

```scala
ui.slider(volume, 0, 1, "volume")
ui.dragValue(width, speed = 0.5, min = 0, suffix = " px")
ui.progressBar(0.6)
ui.spinner()
ui.progressCircle(0.65)          // ring with percentage
ui.progressIndeterminate(160)    // sweeping bar for unknown duration
```

`progressCircle(fraction, diameter = 40)` and `progressIndeterminate(width = 200)` are the other progress variants. Sliders take arrow keys, Shift for ten times larger steps, Home and End while focused.

### textEdit and searchBox

```scala
def textEdit(value: Var[String], multiline: Boolean = false, width: Option[Double] = None, rows: Int = 1,
             mono: Boolean = false, hint: String = "", maxRows: Int = 0, lineNumbers: Boolean = false,
             wrap: Boolean = true, acceptTab: Boolean = true, tab: String = "    "): Response
def searchBox(value: Var[String], hint: String = "Search", width: Double = 220): Response
```

```scala
ui.textEdit(name, hint = "type here")
ui.textEdit(notes, multiline = true, rows = 4, width = Some(420))
ui.searchBox(query)
```

Text editing supports undo and redo, selection by grapheme and word, clipboard, bidi-aware caret movement and IME composition. Each text edit's state lives in the `Context`.

Multiline fields (a code or notes editor):

```scala
ui.textEdit(src, multiline = true, mono = true, rows = 6, maxRows = 12, lineNumbers = true)
```

- The field grows with its content from `rows` up to `maxRows` (0 means unbounded), then scrolls vertically (wheel, caret movement, drag-selecting past an edge, PageUp/PageDown, Cmd/Ctrl+Home/End).
- `wrap = false` keeps lines whole and scrolls horizontally.
- Tab inserts `tab` and Shift-Tab dedents; with several lines selected they indent or dedent every line. `acceptTab = false` lets Tab move keyboard focus instead.
- Enter keeps the indentation of the current line. `lineNumbers` adds a gutter with logical line numbers.

### comboBox

```scala
def comboBox(selected: Var[Int], options: Seq[String], width: Double = 160): Response
```

```scala
ui.comboBox(choice, Seq("Alpha", "Beta", "Gamma"))
```

### colorEdit

```scala
def colorEdit(color: Var[Color], alpha: Boolean = true): Response
```

```scala
val tint = Var(Color(0.9f, 0.4f, 0.2f, 1f))
ui.colorEdit(tint)
```

A swatch that opens a picker with a saturation/value square, hue bar and alpha bar.

### searchComboBox

```scala
def searchComboBox(selected: Var[Int], options: Seq[String], width: Double = 200, maxRows: Int = 8): Response
```

A combo box whose popup starts with a search field that filters the options (case-insensitive substring). Up/Down move the highlight, Enter or a click picks, Escape closes.

### datePicker

```scala
final case class Date(year: Int, month: Int, day: Int)   // plusDays, plusMonths, dayOfWeek, Date.today
def datePicker(date: Var[Date], width: Double = 150): Response
```

A date field opening a Monday-first calendar popup with month navigation; the picked day is written to `date`.

### rating

```scala
def rating(value: Var[Int], max: Int = 5, size: Double = 18): Response
```

Stars with hover preview; clicking the current value clears it; Left/Right adjust while focused.

### reorderList

```scala
def reorderList[T](items: Var[Vector[T]], rowHeight: Double = 30)(row: (T, Ui) => Unit): Response
```

```scala
ui.reorderList(todo, 32)((item, row) => row.label(item))
```

Drag the grip at the left of a row; rows slide aside while dragging and `items` is rewritten on release (`changed`).

## Data

### table

```scala
final case class Column(title: String, width: Double = 120, minWidth: Double = 40, sortable: Boolean = true)
final class TableState(columns: Seq[Column]):   // selected, sortColumn, ascending, widths, order(rows)(key)
def table(state: TableState, columns: Seq[Column], rows: Int, height: Double, rowHeight: Double = 24)
         (cell: (Int, Int, Ui) => Unit): Response
```

```scala
val cols = Seq(Column("Name", 160), Column("Size", 80))
val state = new TableState(cols)
val files = Vector(("a.txt", 12), ("b.png", 340), ("c.md", 3))

// in the frame:
val order = state.order(files.size)((row, col) => if col == 0 then files(row)._1 else files(row)._2.toString)
ui.table(state, cols, files.size, 160) { (row, col, cell) =>
  val f = files(order(row))
  cell.label(if col == 0 then f._1 else s"${f._2} KB")
}
state.selected.foreach(row => ui.label(s"selected: ${files(order(row))._1}"))
```

A scrollable table with a header, resizable and sortable columns, striped rows and keyboard selection (arrow keys). Only visible rows ask for cells. Clicking a header sets `state.sortColumn` and `state.ascending`, but you supply the row order: `TableState.order(rows)(key)` returns the row indices sorted by the current column using an `Ordering` for the key type you return. `state.selected` holds the selected row index (in display order).

### virtualList

```scala
final class VirtualListState { var scroll: Double; var selected: Option[Int]; val visible: Range
                               def scrollTo(row: Int, alignTop: Boolean = false): Unit; def invalidateHeights(): Unit }
def virtualList(state: VirtualListState, count: Int, rowHeight: Double, height: Double, selectable: Boolean = true)
               (row: (Int, Ui) => Unit): Response
```

```scala
val list = new VirtualListState
ui.virtualList(list, 1_000_000, 24, 220)((i, row) => { row.label(s"Record $i"); row.weak("ok") })
```

Rows have a uniform height and only the rows in view are built, so a million rows cost the same as ten. Click or Up, Down, PageUp, PageDown, Home, End select (`changed`); Enter or a double click set `doubleClicked` on the response. `state.visible` holds the row range built in the last frame. `scrollTo(row)` scrolls a row into view on the next frame.

#### Variable row heights: variableList and measuredList

```scala
def variableList(state: VirtualListState, count: Int, rowHeight: Int => Double, height: Double,
                 estimate: Double = 28.0, selectable: Boolean = true)(row: (Int, Ui) => Unit): Response
def measuredList(state: VirtualListState, count: Int, height: Double,
                 estimate: Double = 28.0, selectable: Boolean = true)(row: (Int, Ui) => Unit): Response
```

```scala
// heights from a function (e.g. wrapped text measured with ctx.layout)
ui.variableList(list, messages.size, i => heightOfMessage(i), 300)((i, row) => row.label(messages(i)))
// or let rows size themselves: each row is measured the first time it is built and the height is cached
ui.measuredList(list, 100_000, 300)((i, row) => { row.label(title(i)); (0 until lines(i)).foreach(k => row.weak(detail(i, k))) })
list.scrollTo(5000, alignTop = true)
```

Rows have different heights. Heights are only asked for (or measured) for the rows in view; every other row counts as `estimate` tall, which only affects the scroll bar. Offsets and the row at a position are found in O(log n) (a Fenwick tree over the measured heights), so large lists stay cheap. The scroll position is anchored to the row at the top: when heights get refined, content does not jump. `measuredList` measures a row by building it once inside an empty clip before painting, so even rows scrolled into view from above settle immediately. `scrollTo(row)` also works for rows far away (with a height function the heights before the target are computed once; with `measuredList` the position converges over a few frames as rows are measured). Selection, keyboard (Up, Down, PageUp, PageDown, Home, End), Enter and double click behave as in `virtualList`. Call `state.invalidateHeights()` when row contents change size (for example after a width change); `state.knownHeights` says how many rows have a known height.

### treeNode

```scala
def treeNode(label: String, leaf: Boolean = false, selected: Boolean = false,
             defaultOpen: Boolean = false)(children: Ui => Unit = _ => ()): Response
```

```scala
ui.treeNode("src", defaultOpen = true) { t =>
  t.treeNode("Main.scala", leaf = true)()
  t.treeNode("util") { u => u.treeNode("Strings.scala", leaf = true)() }
}
```

### tree

```scala
final class TreeState {
  val expanded, selected, checked      // sets of node keys
  var cursor: Option[Any]; var anchor: Option[Any]; var scroll: Double
  var activated: Option[Any]; var dropped: Option[(Any, Any)]; var toggledCheck: Option[Any]
  def expand(key: Any); def collapse(key: Any); def toggle(key: Any); def select(key: Any); def scrollTo(key: Any)
}
def tree[T](state: TreeState, roots: Seq[T], children: T => Seq[T], label: T => String, height: Double,
            key: T => Any = identity, multiSelect: Boolean = false, checkboxes: Boolean = false,
            initiallyExpanded: T => Boolean = _ => false,
            dragPayload: Option[T => Any] = None, canDrop: Option[(T, Any) => Boolean] = None): Response
```

```scala
final case class Node(name: String, kids: Seq[Node] = Nil)
val state = new TreeState
val r = ui.tree[Node](state, roots, _.kids, _.name, 220, key = _.name, multiSelect = true, checkboxes = true)
if r.changed then println(state.selected)
if r.doubleClicked then state.activated.foreach(open)
```

A data-driven tree view with its own state in a scrollable, virtualised viewport (`treeNode` stays available for hand-built trees). Expansion, selection, check marks and scrolling live in the `TreeState` and persist across frames; nodes are identified by `key`. Click selects the row and clicking the disclosure arrow expands or collapses it. With `multiSelect`, Ctrl/Cmd-click toggles a row and Shift-click selects the range from the anchor row (Shift with the arrow keys extends too). With `checkboxes` each row has a check box, toggled by clicking it or pressing Space (`state.toggledCheck` names the row). Keyboard, when the tree has focus: Up, Down, PageUp, PageDown, Home and End move; Right expands a node or enters its first child; Left collapses it or goes to the parent; Enter activates; typing jumps to the next row whose label starts with the typed text (typing the same letter repeatedly cycles, the buffer resets after a second). A double click activates the row and toggles its expansion. Activation sets `state.activated` and `doubleClicked` on the response; selection or check changes set `changed`.

For drag and drop, `dragPayload` turns every row into a drag source (it reuses `draggable`, with the label as the drag ghost) and `canDrop(node, payload)` makes rows drop targets (it reuses `dropTarget`): a drop on a row is reported in `state.dropped` as `(node key, payload)`, and the app moves the data.

### plot

```scala
final case class PlotSeries(name: String, points: Seq[(Double, Double)], color: Color, kind: PlotKind = PlotKind.Line)
enum PlotKind: Line, Points, Bars
final class PlotState   // var view: Option[Rect]; None = auto-fit
def plot(state: PlotState, size: Size, series: Seq[PlotSeries], xLabel: String = "", yLabel: String = ""): Response
```

```scala
val state = new PlotState
val pts = (0 to 60).map(i => (i / 6.0, math.sin(i / 8.0)))
ui.plot(state, Size(400, 220), Seq(PlotSeries("sin", pts, Color.rgb8(255, 140, 0))), "t", "v")
```

Axes, grid, legend, hover read-out, wheel zoom and drag pan; double-click resets the view.

## Drawing and media

### image, svg, icon

```scala
def image(texture: Texture, size: Size, tint: Color = Color.White): Response
def svg(doc: SvgDocument, size: Size, tint: Color = Color.White): Response
def icon(icon: Icon, size: Double = 16, color: Option[Color] = None): Response
```

```scala
val tex = ui.ctx.texture(scalaui.codecs.Image.decode(bytes))   // create once, then reuse
ui.image(tex, Size(128, 128))

val logo = scalaui.svg.SvgDocument.parse("""<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 24 24"><circle cx="12" cy="12" r="10" fill="#7a5cff"/></svg>""")
ui.svg(logo, Size(40, 40))

ui.icon(Icons.search)
```

`Icons` has about 110 built-in line icons on a 24x24 grid (`bold`, `calendar`, `check`, `copy`, `folder`, `home`, `save`, `search`, `trash`, `zoomIn`, ...). Look one up with `Icons.byName("save")` or author your own with `Icon("name", "<path d=\"...\"/>")`.

### custom

```scala
def custom(w: Double, h: Double, drag: Boolean = true)(paint: (Canvas, Rect, Response) => Unit): Response
```

```scala
val r = ui.custom(300, 200) { (canvas, rect, resp) =>
  canvas.fillRoundedRect(rect, 8, Color.rgb8(40, 44, 60))
  canvas.fillCircle(rect.center, if resp.hovered then 40 else 30, Color.White)
}
if r.dragged then pan = pan + r.dragDelta   // pan: a var Vec2
```

Reserves a `w x h` area and returns its interaction state. Paint into the `gpu.Canvas` within `rect`; content is clipped while `paint` runs. Drag and scroll deltas arrive on the response.

## Menus and popups

### menuBar, menu, menuItem

```scala
def menuBar(f: Ui => Unit): Unit
def menu(title: String)(body: Ui => Unit): Unit
def menuItem(text: String, shortcut: String = ""): Response
```

```scala
ui.menuBar { b =>
  b.menu("File") { m =>
    if m.menuItem("Open…", "Ctrl+O").clicked then open()
    m.separator()
    if m.menuItem("Quit", "Ctrl+Q").clicked then quit()
  }
}
```

A drawn menu bar. See below for native menus.

### Native menus: appMenuBar and menuPressed

```scala
def appMenuBar(menus: Seq[MenuEntry.Submenu]): Unit
def menuPressed(id: Int): Boolean
```

```scala
import scalaui.window.MenuEntry
val menus = Seq(
  MenuEntry.Submenu("File", Seq(
    MenuEntry.Item("New", id = 1, shortcut = "mod+n"),
    MenuEntry.Separator,
    MenuEntry.Item("Quit", id = 2, shortcut = "mod+q"))))

App.run("menus", menuBar = menus) { ui =>
  ui.appMenuBar(menus)            // native where the OS has one, drawn otherwise
  if ui.menuPressed(1) then newDocument()
}
```

`App.run(menuBar = ...)` installs the native menu bar on macOS (global menu bar with a standard application menu) and Windows (window menu). `appMenuBar` draws the same menu on platforms without native menus and does nothing where the native one is active. Shortcuts are written like `"mod+shift+s"` (`mod` is Cmd on macOS and Ctrl elsewhere). Item selections, by mouse or by shortcut, are reported by `menuPressed(id)` during the frame they happen.

### contextMenu and popup

```scala
def contextMenu(r: Response, minWidth: Double = 170)(body: Ui => Unit): Unit
def popup(open: Var[Boolean], anchor: Rect, minWidth: Double = 160)(body: Ui => Unit): Unit
```

```scala
val file = ui.button("report.pdf")
ui.contextMenu(file) { m =>
  if m.menuItem("Open").clicked then open()
  if m.menuItem("Delete", "Del").clicked then delete()
  m.separator()
  m.menuItem("Properties")
}
```

`contextMenu` opens at the pointer when the right button goes down over the widget, flips to stay on screen, is navigable with Up/Down/Enter, and closes on an outside click, Escape or after choosing an item. A second right click elsewhere moves it. `popup` shows arbitrary content below an anchor rectangle while `open.value` is true and sets it back to false when dismissed.

### tooltip

```scala
def tooltip(r: Response, text: String, delay: Double = 0.45): Unit
```

```scala
val b = ui.button("Click me")
ui.tooltip(b, "Increments the counter")
```

### hSplit and vSplit

```scala
def hSplit(fraction: Var[Double], height: Double = 0, minFirst: Double = 80, minSecond: Double = 80)(first: Ui => Unit)(second: Ui => Unit): Response
def vSplit(fraction: Var[Double], height: Double, minFirst: Double = 60, minSecond: Double = 60)(first: Ui => Unit)(second: Ui => Unit): Response
```

```scala
ui.hSplit(share, height = 300)(left => left.label("Files"))(right => right.label("Preview"))
```

Two resizable panes with a draggable divider (resize cursor, arrow keys when focused); `fraction` is the first pane's share and is kept inside the minimum sizes. `height = 0` fills the remaining height.

### closableTabBar

```scala
def closableTabBar(selected: Var[Int], titles: Seq[String]): Option[Int]
```

Tabs with a close button each (and Delete on a focused tab). Returns the index of the tab closed this frame; remove it from your data and clamp `selected`.

### window

```scala
def window(title: String, initial: Point = Point(80, 80), width: Double = 280,
           open: Option[Var[Boolean]] = None)(body: Ui => Unit): Unit
```

```scala
val show = Var(true)
ui.window("Floating", Point(430, 400), 300, Some(show)) { w =>
  w.label("Drag my title bar.")
  w.colorEdit(tint)
}
```

A draggable floating window; pass `open` for a close button. Content sizes itself from the previous frame.

### modal and dialogButtons

```scala
def modal(open: Var[Boolean], title: String, width: Double = 420)(body: Ui => Unit): Unit
def dialogButtons(secondary: String, primary: String, danger: Boolean = false): Option[Int]
```

```scala
val confirm = Var(false)
if ui.button("Delete…").clicked then confirm.value = true
ui.modal(confirm, "Delete file?") { m =>
  m.label("This cannot be undone.")
  m.dialogButtons("Cancel", "Delete", danger = true) match
    case Some(1) => delete(); confirm.value = false
    case Some(_) => confirm.value = false
    case None => ()
}
```

A modal with a dimmed backdrop, closed by its X button or Escape. `dialogButtons` returns `Some(0)` for the secondary action and `Some(1)` for the primary one.

### toast

```scala
// Context method
def toast(text: String, kind: ToastKind = ToastKind.Info, seconds: Double = 3.5): Unit
```

```scala
if ui.button("Save").clicked then ui.ctx.toast("Saved", ToastKind.Success)
```

`ToastKind` is `Info`, `Success`, `Warning` or `Error`.

### FileDialog

```scala
final class FileDialog(title: String, startDir: String, mode: FileDialogMode, extensions: Seq[String] = Nil):
  def show(): Unit
  def update(ui: Ui): Option[String]   // call every frame; returns the chosen path once
enum FileDialogMode  // OpenFile, SaveFile, OpenFolder
```

```scala
val picker = new FileDialog("Open image", sys.env.getOrElse("HOME", "/"), FileDialogMode.OpenFile, Seq("png", "jpg"))
if ui.button("Open…").clicked then picker.show()
picker.update(ui).foreach(path => load(path))
```

An in-app file picker, intended for Linux where `scalaui.dialogs.Dialogs` has no native dialogs (see the [dialogs](modules/dialogs.md) page). `examples/src/main/scala/Viewer.scala` uses it.

## Drag and drop

```scala
def draggable(rect: Rect, payload: Any, ghost: String = ""): Response
def dropTarget(rect: Rect)(accept: Any => Boolean = _ => true): Option[Any]
```

```scala
import scalaui.geom.Rect

val source = Rect(20, 120, 120, 160)
val target = Rect(200, 120, 320, 180)
ui.draggable(source, payload = "item-1", ghost = "Item 1")
ui.dropTarget(target)(_.isInstanceOf[String]).foreach(p => dropped.value = p.toString)
```

`draggable(rect, payload)` makes `rect` a drag source (a ghost of the `ghost` text follows the pointer once the pointer has moved a few points) and `dropTarget(rect)(accept)` returns `Some(payload)` on the frame the mouse is released over the rectangle. Draw the visuals for both rectangles yourself, for example with `ui.ctx.box`. Dropping files from the OS arrives separately as `ctx.input.droppedFiles` and `ctx.input.hoveredFiles`.

## Context

`ui.ctx` is the `Context` shared by the frame. Useful members:

| Member | Purpose |
|---|---|
| `style: Style` | read or replace the current theme tokens |
| `input: Input` | mouse position, buttons (`secondaryPressed` for the right one), scroll, modifiers, `keys`, `composition`, `droppedFiles`, `hoveredFiles`, `menuActions` |
| `canvas: gpu.Canvas` | draw primitives directly |
| `toast(...)` | transient notification |
| `shortcut("s")` / `shortcut(Key.F5, mods)` | consume a Cmd/Ctrl+key press (or a key with exact modifiers); true once per press |
| `focusId`, `requestFocus(id)`, `registerFocus(id)`, `keyboardNav` | keyboard focus state and traversal |
| `texture(img)` / `svgTexture(doc, w, h)` | upload an image or rasterized SVG |
| `shadow(rect, rounding, blur, color, offset)` | nine-slice blurred shadow |
| `box`, `outline`, `focusRing`, `paintIcon` | chrome helpers used by the widgets |
| `animate(id, target, seconds)` | animation primitive behind `Ui.animate` |
| `requestRepaint()` | keep the loop running (e.g. for a custom animation) |
| `cursorIcon` | set the mouse cursor for this frame |
| `imeRect` | caret rectangle for IME candidate windows |
| `layout(text, size, ...)` / `paintText` | measure and draw text |

## Style

`Style` is a case class of tokens: surfaces (`background`, `panel`, `faint`, `chrome`, `canvas`, `menu`), widget states, borders, semantic colors (`accent`, `success`, `warning`, `danger`, `info`), text colors, shadows, type sizes (`fontSize`, `headingSize`, `monoSize`, `captionSize`) and metrics (`itemSpacing`, `buttonPadding`, `minHeight`, `rounding`, `windowRounding`, `indent`, `sliderWidth`, `textEditWidth`). `Style.Dark` and `Style.Light` are provided; `Style.withAccent(base, "#rrggbb")` derives an accent set; `Style.mix(a, b, t)` interpolates colors.
