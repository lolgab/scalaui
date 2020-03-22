package scalaui

import scala.scalanative.unsafe.{Zone, toCString}
import ui._

class Tabs(tabs: Seq[(String, Component)]) extends Component {
  private[scalaui] override def build(): Unit = Zone { implicit z =>
    control = uiNewTab()
    for ((name, component) <- tabs) {
      component.build()
      uiTabAppend(control, toCString(name), component.control)
    }
  }
}
