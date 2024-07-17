package scalaui

import scala.scalanative.unsafe._
import ui._

class Group(t: String, content: Component, margin: Int = 0) extends Component {
  private[scalaui] override def build(): Unit = Zone {
    control = uiNewGroup(toCString(t))
    content.build()
    uiGroupSetChild(control, content.control)
    uiGroupSetMargined(control, margin)
  }

  def title: String = {
    require(initialized)
    fromCString(uiGroupTitle(control))
  }

  def title_=(title: String): Unit = Zone {
    require(initialized)
    uiGroupSetTitle(control, toCString(title))
  }
}
