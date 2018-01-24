package scalaui

import ui._

class TextArea() extends AbstractTextArea {
  private[scalaui] override def build(): Unit = {
    control = uiNewMultilineEntry()
  }
}
