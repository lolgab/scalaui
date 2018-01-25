package scalaui

import ui._
import scalaui._

class HorizontalPane(override val children: Seq[(Component, Stratchy)]) extends Pane {
  private[scalaui] override def build(): Unit = {
    control = uiNewHorizontalBox()
    super.build()
  }
}
