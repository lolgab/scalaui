package scalaui

import ui._

class VerticalPanel(override val children: StretchableComponent*) extends Panel {
  private[scalaui] override def build(): Unit = {
    control = uiNewVerticalBox()
    super.build()
  }
}
