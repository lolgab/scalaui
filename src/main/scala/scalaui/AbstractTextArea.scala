package scalaui

import scala.scalanative.native.fromCString
import ui._
import scalaui._

trait AbstractTextArea extends Component {
  def text: String = fromCString(uiMultilineEntryText(control))

  def readOnly: Boolean = uiMultilineEntryReadOnly(control)
  def readOnly_=(v: Boolean): Unit = uiMultilineEntrySetReadOnly(control, v)
}
