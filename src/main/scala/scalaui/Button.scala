package scalaui

import scala.scalanative.unsafe.{Ptr, CFuncPtr2, Zone, toCString}
import ui._

class Button(text: String, val onClick: () => Unit) extends Component {
  private[scalaui] def build(): Unit = Zone { implicit z =>
    control = uiNewButton(toCString(text))
    uiButtonOnClicked(control, cCallback2, PtrConverter.toPtr(onClick))
  }
}
