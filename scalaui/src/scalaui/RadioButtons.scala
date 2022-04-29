package scalaui

import scala.scalanative.unsafe._
import ui._

class RadioButtons(
    names: Seq[String],
    initialSelected: Int = 0,
    onSelected: CFuncPtr0[Unit] = doNothing _
) extends Component {
  def selected: Int = {
    require(initialized)
    uiRadioButtonsSelected(control)
  }
  def selected_=(v: Int): Unit = {
    require(initialized)
    uiRadioButtonsSetSelected(control, v)
  }
  def selectedName: String = {
    require(initialized)
    names(selected)
  }

  private[scalaui] override def build(): Unit = Zone { implicit z =>
    control = uiNewRadioButtons()
    for (name <- names) {
      uiRadioButtonsAppend(control, toCString(name))
    }
    //selected = initialSelected  // this caused a "requirement failed" exception
    uiRadioButtonsSetSelected(control, initialSelected)
  }
}
