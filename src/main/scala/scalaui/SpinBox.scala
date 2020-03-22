package scalaui

import scala.scalanative.unsafe.CFuncPtr0
import ui._

class SpinBox(min: Int, max: Int, onChange: () => Unit = () => ())
    extends Component {
  def value: Int = {
    require(initialized)
    uiSpinboxValue(control)
  }
  def value_=(v: Int): Unit = {
    require(initialized)
    uiSpinboxSetValue(control, v)
  }

  private[scalaui] override def build(): Unit = {
    control = uiNewSpinbox(min, max)
    uiSpinboxOnChanged(control, cCallback2, PtrConverter.toPtr(onChange))
  }
}
