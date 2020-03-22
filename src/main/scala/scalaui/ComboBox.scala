package scalaui

import scala.scalanative.unsafe.{Zone, toCString}
import ui._

class ComboBox(
    names: Seq[String],
    initialSelected: Int = 0,
    onSelected: () => Unit = () => ()
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
  // TODO set onSelected callback
  }
}
