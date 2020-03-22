package scalaui

import scala.scalanative.native.{CFunctionPtr0, Zone, fromCString, toCString}
import ui._

class EditableComboBox(
    names: Seq[String],
    onSelected: CFunctionPtr0[Unit] = doNothing _
) extends Component {
  def currentText: String = {
    require(initialized)
    fromCString(uiEditableComboboxText(control))
  }
  def currentText_=(v: String): Unit = Zone { implicit z =>
    require(initialized)
    uiEditableComboboxSetText(control, toCString(v))
  }

  private[scalaui] override def build(): Unit = Zone { implicit z =>
    control = uiNewEditableCombobox()
    for (name <- names) {
      uiEditableComboboxAppend(control, toCString(name))
    }
  }
}
