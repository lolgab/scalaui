package scalaui

import ui._

class VerticalSeparator extends Component {
  private[scalaui] override def build(): Unit = {
    control = uiNewVerticalSeparator()
  }
}
