package scalaui

import scala.scalanative.native.fromCString
import ui._

abstract class Field extends Component {
  def text: String = {
    require(initialized)
    fromCString(uiEntryText(control))
  }
}
