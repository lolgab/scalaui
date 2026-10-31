package scalaui.window

import scalaui.geom.Rect

enum AccessRole:
  case Label, Heading, Button, CheckBox, RadioButton, Switch, Slider, TextInput, ComboBox, Link, ProgressBar, Tab, ListItem

/** Text-entry state of an [[AccessNode]] (`value` holds the text). Offsets are UTF-16 indices into `value`; `selStart ==
  * selEnd` means no selection. `caret` is where typing happens (one end of the selection if there is one). `charBounds`,
  * when given, is the rectangle of every UTF-16 unit of `value` in window points (used for character extents / hit tests). */
final case class AccessText(caret: Int, selStart: Int, selEnd: Int, multiline: Boolean = false, editable: Boolean = true,
    charBounds: Vector[Rect] = Vector.empty)

/** One element of the accessibility tree, in reading order. `bounds` is in window points (top-left origin).
  * `checked` is set for checkboxes, radios, switches and selected tabs/items. `text` is set for text-entry widgets. */
final case class AccessNode(role: AccessRole, label: String, bounds: Rect, value: String = "", checked: Option[Boolean] = None,
    focused: Boolean = false, disabled: Boolean = false, text: Option[AccessText] = None)
