package scalaui

import scala.scalanative.native.{Zone, toCString}
import ui._

class Label(text: String) extends Component {
  private[scalaui] def build(): Unit = Zone { implicit z =>
    control = uiNewLabel(toCString(text))
  }
}
