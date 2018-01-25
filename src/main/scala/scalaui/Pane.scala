package scalaui

import ui._
import scalaui._

trait Pane extends Container[Stratchy] {
  def padded = uiBoxPadded(control)
  def padded_=(v: Int): Unit = uiBoxSetPadded(control, v)

  protected def appendChild(child: Component, stratchy: Stratchy): Unit = {
    uiBoxAppend(control, child.control, stratchy)
  }
}