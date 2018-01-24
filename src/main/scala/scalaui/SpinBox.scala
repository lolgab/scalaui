package scalaui

import scala.scalanative.native.CFunctionPtr0
import ui._
import scalaui._

class SpinBox(min: Int, max: Int, onChange: CFunctionPtr0[Unit] = doNothing _) extends Component {
  def value: Int = uiSpinboxValue(control)
  def value_=(v: Int): Unit = uiSpinboxSetValue(control, v)

  private[scalaui] override def build(): Unit = {
    control = uiNewSpinbox(min,max)
    uiSpinboxOnChanged(control, onChange, null)
  }
}
