package scalaui

import ui._

import scala.scalanative.unsafe._

class ComboBox(
    names: Seq[String],
    initialSelected: Int = 0,
    onSelected: CFuncPtr0[Unit] = doNothing _
) extends Component {
  def selected: Int = {
    require(initialized)
    uiComboboxSelected(control)
  }
  def selected_=(v: Int): Unit = {
    require(initialized)
    uiComboboxSetSelected(control, v)
  }
  def selectedName: String = names(selected)

  private[scalaui] override def build(): Unit = Zone { implicit z =>
    control = uiNewCombobox()
    for (name <- names) {
      uiComboboxAppend(control, toCString(name))
    }
    selected = initialSelected
  }
}
