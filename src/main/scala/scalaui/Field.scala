package scalaui

import scala.scalanative.native.fromCString
import ui._

abstract class Field extends Component {
  def text: String = fromCString(uiEntryText(control))
}
