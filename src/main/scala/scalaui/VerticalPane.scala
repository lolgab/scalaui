package scalaui

import ui._

class VerticalPane(override val children: Seq[Component], override val stretchy: Boolean) extends Pane {
  private[scalaui] override def build(): Unit = {
    control = uiNewVerticalBox()
    super.build()
  }
}
