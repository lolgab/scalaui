package scalaui

import scala.scalanative.native.{CFunctionPtr0, Zone, toCString}
import ui._
import scalaui._

class RadioButtons(names: Seq[String], initialSelected: Int = 0, onSelected: CFunctionPtr0[Unit] = doNothing _) extends Component {
  def selected: Int = uiRadioButtonsSelected(control)
  def selected_=(v: Int): Unit = uiRadioButtonsSetSelected(control,v)
  def selectedName: String = names(selected)

  private[scalaui] override def build(): Unit = Zone { implicit z =>
    control = uiNewRadioButtons()
    for(name <- names) {
      uiRadioButtonsAppend(control, toCString(name))
    }
    selected = initialSelected
  }
}
