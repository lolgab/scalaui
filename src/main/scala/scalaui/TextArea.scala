package scalaui

import scala.scalanative.native.CFunctionPtr0
import ui._

class TextArea(val onChange: CFunctionPtr0[Unit] = doNothing _)
    extends AbstractTextArea {
  private[scalaui] override def build(): Unit = {
    control = uiNewMultilineEntry()
    uiMultilineEntryOnChanged(control, onChange, null)
  }
}