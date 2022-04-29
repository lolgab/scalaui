package scalaui

import scala.scalanative.unsafe.{CFuncPtr0, Zone, toCString}
import ui._

// TODO waiting for https://github.com/scala-native/scala-native/issues/707 being solved
class Button(text: String, onClick: CFuncPtr0[Unit]) extends Component {
  //private val cCallback = CFuncPtr.fromFunction0(onClick)

  private[scalaui] def build(): Unit = Zone { implicit z =>
    control = uiNewButton(toCString(text))
    uiButtonOnClicked(control, onClick, null)
  }
}
