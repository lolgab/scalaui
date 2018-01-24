package scalaui

import ui._

class HorizontalPane(override val children: Seq[Component]) extends Pane {
  private[scalaui] override def build(): Unit = {
    control = uiNewHorizontalBox()
    super.build()
  }
}
