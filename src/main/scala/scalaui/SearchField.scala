package scalaui

import ui._

class SearchField extends Field {
  private[scalaui] override def build(): Unit = {
    control = uiNewSearchEntry()
  }
}
