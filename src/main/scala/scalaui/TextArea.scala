package scalaui

import scala.scalanative.unsafe.CFuncPtr0
import ui._

class TextArea(val onChange: () => Unit = () => ()) extends AbstractTextArea {
  private[scalaui] override def build(): Unit = {
    control = uiNewMultilineEntry()
    uiMultilineEntryOnChanged(control, cCallback2, PtrConverter.toPtr(onChange))
  }
}
