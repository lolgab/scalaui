package scalaui

import ui._

trait Pane extends Container {
  def padded = uiBoxPadded(control)
  def padded_=(v: Int): Unit = uiBoxSetPadded(control, v)

  protected def appendChild(child: Component): Unit = {
    uiBoxAppend(control, child.control, 0)
  }
}
