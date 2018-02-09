package scalaui

import ui._

trait Panel extends Container {
  def padded: Int            = uiBoxPadded(control)
  def padded_=(v: Int): Unit = uiBoxSetPadded(control, v)

  protected def appendChild(child: StretchableComponent): Unit = {
    val stretchy = child match {
      case Stretchy(_)    => true
      case NonStretchy(_) => false
    }
    uiBoxAppend(control, child.component.control, stretchy)
  }
}
