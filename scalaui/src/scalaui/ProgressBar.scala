package scalaui

import ui._

class ProgressBar() extends Component {
  //value should be between 0 and 100
  def value: Int = {
    require(initialized)
    uiProgressBarValue(control)
  }
  def value_=(v: Int): Unit = {
    require(initialized)
    uiProgressBarSetValue(control, v)
  }

  private[scalaui] override def build(): Unit = {
    control = uiNewProgressBar()
  }
}
