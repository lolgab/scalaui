package scalaui

import scala.scalanative.native.{CFunctionPtr0, Zone, toCString}
import ui._
import scalaui._

class ComboBox(names: Seq[String], initialSelected: Int = 0, onSelected: CFunctionPtr0[Unit] = doNothing _) extends Component {
  def selected: Int = uiComboboxSelected(control)
  def selected_=(v: Int): Unit = uiComboboxSetSelected(control,v)
  def selectedName: String = names(selected)

  private[scalaui] override def build(): Unit = Zone { implicit z =>
    control = uiNewCombobox()
    for(name <- names) {
      uiComboboxAppend(control, toCString(name))
    }
    selected = initialSelected
  }
}
