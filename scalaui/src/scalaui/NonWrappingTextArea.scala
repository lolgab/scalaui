package scalaui

import scala.scalanative.unsafe.CFuncPtr0
import ui._

class NonWrappingTextArea(val onChange: CFuncPtr0[Unit] = doNothing _)
    extends AbstractTextArea {
  private[scalaui] override def build(): Unit = {
    control = uiNewNonWrappingMultilineEntry()
    uiMultilineEntryOnChanged(control, onChange, null)
  }
}
