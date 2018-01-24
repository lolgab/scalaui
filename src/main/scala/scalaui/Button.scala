package scalaui

import scala.scalanative.native.{CFunctionPtr0, Zone, toCString}
import ui._

// TODO waiting for https://github.com/scala-native/scala-native/issues/707 being solved
class Button(text: String, onClick: CFunctionPtr0[Unit]) extends Component {
  //private val cCallback = CFunctionPtr.fromFunction0(onClick)

  private[scalaui] def build(): Unit = Zone { implicit z =>
    control = uiNewButton(toCString(text))
    uiButtonOnClicked(control, onClick, null)
  }
}

//object Button {
//  def apply(text: String, onClick: CFunctionPtr0[Unit]): Button = new Button(text, onClick)
//}