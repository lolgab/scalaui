package scalaui

import scala.scalanative.unsafe._
import ui._

class Checkbox(text: String, onToggled: CFuncPtr0[Unit] = doNothing _)
    extends Component {
  def checked: Boolean = {
    require(initialized)
    uiCheckboxChecked(control)
  }
  def checked_=(value: Boolean): Unit = {
    require(initialized)
    uiCheckboxSetChecked(control, value)
  }

  private[scalaui] override def build(): Unit = {
    Zone { control = uiNewCheckbox(toCString(text)) }
  }
}
