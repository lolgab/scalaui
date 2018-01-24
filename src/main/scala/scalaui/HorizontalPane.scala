package scalaui

import ui._

class HorizontalPane(override val children: Seq[Component], override val stretchy: Boolean) extends Pane {
  private[scalaui] override def build(): Unit = {
    control = uiNewHorizontalBox()
    super.build()
  }
}
