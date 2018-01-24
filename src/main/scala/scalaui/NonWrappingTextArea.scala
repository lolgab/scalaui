package scalaui

import ui._

class NonWrappingTextArea extends AbstractTextArea {
  private[scalaui] override def build(): Unit = {
    control = uiNewNonWrappingMultilineEntry()
  }
}
