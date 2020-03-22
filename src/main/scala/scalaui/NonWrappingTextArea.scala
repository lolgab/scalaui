package scalaui

import ui._

class NonWrappingTextArea(val onChange: () => Unit = () => ())
    extends AbstractTextArea {
  private[scalaui] override def build(): Unit = {
    control = uiNewNonWrappingMultilineEntry()
    uiMultilineEntryOnChanged(control, cCallback2, PtrConverter.toPtr(onChange))
  }
}
