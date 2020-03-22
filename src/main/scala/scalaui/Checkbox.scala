package scalaui

import scala.scalanative.unsafe.{CFuncPtr0, Zone, toCString}
import ui._

class Checkbox(text: String, onToggled: () => Unit = () => ())
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
