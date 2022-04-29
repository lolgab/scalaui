package scalaui

import scala.scalanative.unsafe.CFuncPtr0
import ui._

class SpinBox(min: Int, max: Int, onChange: CFuncPtr0[Unit] = doNothing _)
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
    uiSpinboxOnChanged(control, onChange, null)
  }
}
