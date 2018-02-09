package scalaui

import ui._

class HorizontalPanel(override val children: StretchableComponent*) extends Panel {
  private[scalaui] override def build(): Unit = {
    control = uiNewHorizontalBox()
    super.build()
  }
}
