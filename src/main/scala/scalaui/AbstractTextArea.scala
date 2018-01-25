package scalaui

import scala.scalanative.native.{Zone, fromCString, toCString}
import scalaui._
import ui._

trait AbstractTextArea extends Component {
  def text: String = fromCString(uiMultilineEntryText(control))

  def text_=(s: String): Unit = Zone { implicit z =>
    uiMultilineEntrySetText(control, toCString(s))
  }

  def readOnly: Boolean = uiMultilineEntryReadOnly(control)

  def readOnly_=(v: Boolean): Unit = uiMultilineEntrySetReadOnly(control, v)
}
