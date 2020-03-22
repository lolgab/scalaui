package scalaui

import scala.scalanative.native.{CFunctionPtr0, Zone, toCString}
import ui._

class Checkbox(text: String, onToggled: CFunctionPtr0[Unit] = doNothing _)
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
    Zone { implicit z => control = uiNewCheckbox(toCString(text)) }
  }
}
