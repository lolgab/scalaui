package scalaui

import ui._
import scalaui._

trait Pane extends Container {
  val stretchy: Boolean

  def padded = uiBoxPadded(control)
  def padded_=(v: Int): Unit = uiBoxSetPadded(control, v)

  protected def appendChild(child: Component): Unit = {
    uiBoxAppend(control, child.control, stretchy)
  }
}
