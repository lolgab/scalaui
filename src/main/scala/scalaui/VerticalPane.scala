package scalaui

import ui._
import scalaui._

class VerticalPane(override val children: Seq[(Component, Stratchy)]) extends Pane {
  private[scalaui] override def build(): Unit = {
    control = uiNewVerticalBox()
    super.build()
  }
}
