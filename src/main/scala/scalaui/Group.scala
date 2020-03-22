package scalaui

import scala.scalanative.unsafe.{Zone, fromCString, toCString}
import ui._

class Group(t: String, content: Component, margin: Int = 0) extends Component {
  private[scalaui] override def build(): Unit = Zone { implicit z =>
    control = uiNewGroup(toCString(t))
    content.build()
    uiGroupSetChild(control, content.control)
    uiGroupSetMargined(control, margin)
  }

  def title: String = {
    require(initialized)
    fromCString(uiGroupTitle(control))
  }

  def title_=(title: String): Unit = Zone { implicit z =>
    require(initialized)
    uiGroupSetTitle(control, toCString(title))
  }
}
