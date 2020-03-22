package scalaui

import scala.scalanative.unsafe.fromCString
import ui._

abstract class Field extends Component {
  def text: String = {
    require(initialized)
    fromCString(uiEntryText(control))
  }
}
