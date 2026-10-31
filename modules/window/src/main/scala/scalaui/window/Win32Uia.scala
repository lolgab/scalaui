package scalaui.window

import scala.scalanative.unsafe.*

/** Windows accessibility bridge: Microsoft UI Automation provider interfaces so Narrator, NVDA and JAWS can read the
  * widgets published with [[Window.setAccessibility]]. The COM objects live in `scalaui_uia.c` (UIA calls them from RPC
  * threads that the Scala Native runtime does not know); this file only hands over snapshots and polls for presses.
  */
@extern private[window] object UiaShim:
  def scalaui_uia_set_nodes(hwnd: Ptr[Byte], n: CInt, ctypes: Ptr[CInt], flags: Ptr[CInt], bounds: Ptr[CDouble], numbers: Ptr[CDouble],
      names: Ptr[Ptr[CShort]], values: Ptr[Ptr[CShort]], title: Ptr[CShort], textInfo: Ptr[CInt]): Unit = extern
  def scalaui_uia_root(hwnd: Ptr[Byte], wParam: CUnsignedLongLong, lParam: CLongLong): CLongLong = extern
  def scalaui_uia_poll_press(hwnd: Ptr[Byte]): CInt = extern
  def scalaui_uia_poll_edit(hwnd: Ptr[Byte], kind: Ptr[CInt], a: Ptr[CInt], b: Ptr[CInt], text: Ptr[Ptr[CShort]]): CInt = extern
  def scalaui_uia_free(p: Ptr[Byte]): Unit = extern
  def scalaui_uia_text_event(hwnd: Ptr[Byte], index: CInt, kind: CInt): Unit = extern
  def scalaui_uia_focus_changed(hwnd: Ptr[Byte], index: CInt): Unit = extern

private[window] object Win32Uia:
  private val Focused = 1; private val Disabled = 2; private val Invokable = 4; private val Togglable = 8; private val HasValue = 16
  private val HasRange = 32; private val Focusable = 64; private val Checked = 128; private val HasText = 256; private val Editable = 512; private val Multiline = 1024

  private def controlType(r: AccessRole): Int = r match
    case AccessRole.Label | AccessRole.Heading => 50020 // Text
    case AccessRole.Button => 50000; case AccessRole.CheckBox | AccessRole.Switch => 50002; case AccessRole.ComboBox => 50003
    case AccessRole.TextInput => 50004; case AccessRole.Link => 50005; case AccessRole.ListItem => 50007; case AccessRole.ProgressBar => 50012
    case AccessRole.RadioButton => 50013; case AccessRole.Slider => 50015; case AccessRole.Tab => 50019

  private def flagsOf(n: AccessNode): Int =
    (if n.focused then Focused else 0) | (if n.disabled then Disabled else 0) |
      (if AtSpi.actionable(n.role) then Invokable else 0) |
      (if n.role == AccessRole.CheckBox || n.role == AccessRole.Switch then Togglable else 0) |
      (if n.role == AccessRole.TextInput || n.role == AccessRole.ComboBox then HasValue else 0) |
      (if n.role == AccessRole.Slider || n.role == AccessRole.ProgressBar then HasRange else 0) |
      (if !n.disabled && (AtSpi.actionable(n.role) || n.role == AccessRole.TextInput || n.role == AccessRole.Slider) then Focusable else 0) |
      (if n.checked.contains(true) then Checked else 0) |
      (if n.text.isDefined then HasText else 0) | (if n.text.exists(_.editable) then Editable else 0) | (if n.text.exists(_.multiline) then Multiline else 0)

  /** Publishes the tree; bounds are converted to client-area pixels with `scale`. */
  def publish(hwnd: Ptr[Byte], title: String, nodes: Seq[AccessNode], scale: Double): Unit = Zone {
    val n = nodes.length
    val ctypes = alloc[CInt](n.max(1)); val flags = alloc[CInt](n.max(1))
    val bounds = alloc[CDouble](4 * n.max(1)); val numbers = alloc[CDouble](2 * n.max(1))
    val names = alloc[Ptr[CShort]](n.max(1)); val values = alloc[Ptr[CShort]](n.max(1)); val textInfo = alloc[CInt](3 * n.max(1))
    nodes.zipWithIndex.foreach { (a, i) =>
      ctypes(i) = controlType(a.role); flags(i) = flagsOf(a)
      bounds(4 * i) = a.bounds.x0 * scale; bounds(4 * i + 1) = a.bounds.y0 * scale
      bounds(4 * i + 2) = a.bounds.width * scale; bounds(4 * i + 3) = a.bounds.height * scale
      val progress = a.role == AccessRole.ProgressBar
      numbers(2 * i) = a.value.stripSuffix("%").toDoubleOption.getOrElse(0.0)
      numbers(2 * i + 1) = if progress then 100.0 else 1.0
      names(i) = Win32Window.wide(a.label); values(i) = Win32Window.wide(a.value)
      textInfo(3 * i) = a.text.fold(0)(_.caret); textInfo(3 * i + 1) = a.text.fold(0)(_.selStart); textInfo(3 * i + 2) = a.text.fold(0)(_.selEnd)
    }
    UiaShim.scalaui_uia_set_nodes(hwnd, n, ctypes, flags, bounds, numbers, names, values, Win32Window.wide(title), textInfo)
  }

  /** The LRESULT for `WM_GETOBJECT` with `UiaRootObjectId`. */
  def rootObject(hwnd: Ptr[Byte], wParam: CUnsignedLongLong, lParam: CLongLong): CLongLong = UiaShim.scalaui_uia_root(hwnd, wParam, lParam)

  /** Element indexes a screen reader activated since the last call. */
  def presses(hwnd: Ptr[Byte]): Seq[Int] =
    val out = Vector.newBuilder[Int]
    var i = UiaShim.scalaui_uia_poll_press(hwnd)
    while i >= 0 do { out += i; i = UiaShim.scalaui_uia_poll_press(hwnd) }
    out.result()

  /** Edits screen readers requested through ValuePattern.SetValue / TextRange.Select, as window events. */
  def edits(hwnd: Ptr[Byte]): Seq[Event] = Zone {
    val out = Vector.newBuilder[Event]
    val kind = alloc[CInt](1); val a = alloc[CInt](1); val b = alloc[CInt](1); val text = alloc[Ptr[CShort]](1)
    var i = UiaShim.scalaui_uia_poll_edit(hwnd, kind, a, b, text)
    while i >= 0 do
      if !kind == 0 then
        val p = !text
        val sb = new StringBuilder
        if p != null then
          var k = 0
          while p(k) != 0.toShort do { sb += (p(k) & 0xffff).toChar; k += 1 }
          UiaShim.scalaui_uia_free(p.asInstanceOf[Ptr[Byte]])
        out += Event.AccessSetText(i, sb.toString)
      else out += Event.AccessSetSelection(i, !a, !b)
      i = UiaShim.scalaui_uia_poll_edit(hwnd, kind, a, b, text)
    out.result()
  }

  /** Raises UIA text-changed / selection-changed events for fields that differ from the previously published tree. */
  def raiseTextEvents(hwnd: Ptr[Byte], before: Vector[AccessNode], after: Seq[AccessNode]): Unit =
    after.zipWithIndex.foreach { (a, i) =>
      before.lift(i).filter(p => p.text.isDefined && a.text.isDefined && p.label == a.label).foreach { p =>
        if p.value != a.value then UiaShim.scalaui_uia_text_event(hwnd, i, 0)
        if p.text.map(t => (t.caret, t.selStart, t.selEnd)) != a.text.map(t => (t.caret, t.selStart, t.selEnd)) then UiaShim.scalaui_uia_text_event(hwnd, i, 1)
      }
    }

  def focusChanged(hwnd: Ptr[Byte], nodeIndex: Int): Unit = if nodeIndex >= 0 then UiaShim.scalaui_uia_focus_changed(hwnd, nodeIndex)
