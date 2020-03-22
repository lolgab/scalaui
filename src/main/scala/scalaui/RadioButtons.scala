package scalaui

import scala.scalanative.unsafe.{CFuncPtr0, Zone, toCString}
import ui._

class RadioButtons(
    names: Seq[String],
    initialSelected: Int = 0,
    onSelected: () => Unit = () => ()
) extends Component {
  def selected: Int = {
    // require(initialized)
    uiRadioButtonsSelected(control)
  }
  def selected_=(v: Int): Unit = {
    // require(initialized)
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
    selected = initialSelected
    uiRadioButtonsOnSelected(
      control,
      cCallback2,
      PtrConverter.toPtr(onSelected)
    )
  }
}
