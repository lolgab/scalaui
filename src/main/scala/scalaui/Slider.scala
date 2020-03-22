package scalaui

import scala.scalanative.unsafe.CFuncPtr0
import ui._

class Slider(min: Int, max: Int, onChange: () => Unit = () => ())
    extends Component {
  def value: Int = {
    require(initialized)
    uiSliderValue(control)
  }
  def value_=(v: Int): Unit = {
    require(initialized)
    uiSliderSetValue(control, v)
  }

  private[scalaui] override def build(): Unit = {
    control = uiNewSlider(min, max)
    uiSliderOnChanged(control, cCallback2, PtrConverter.toPtr(onChange))
  }
}
