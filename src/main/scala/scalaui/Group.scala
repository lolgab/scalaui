package scalaui

import scala.scalanative.native.{Zone, toCString}
import ui._

class Group(title: String, content: Component, margin: Int = 0) extends Component {
  private[scalaui] override def build(): Unit = Zone { implicit z =>
    control = uiNewGroup(toCString(title))
    content.build()
    uiGroupSetChild(control, content.control)
    uiGroupSetMargined(control, margin)
  }
}
