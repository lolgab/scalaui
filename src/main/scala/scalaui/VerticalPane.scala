package scalaui

import ui._

class VerticalPane(override val children: Seq[Component]) extends Pane {
  private[scalaui] override def build(): Unit = {
    control = uiNewVerticalBox()
    super.build()
  }
}
