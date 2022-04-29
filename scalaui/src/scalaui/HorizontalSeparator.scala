package scalaui

import ui._

class HorizontalSeparator extends Component {
  private[scalaui] override def build(): Unit = {
    control = uiNewHorizontalSeparator()
  }
}
