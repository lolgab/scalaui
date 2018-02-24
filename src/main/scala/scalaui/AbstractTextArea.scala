package scalaui

import scala.scalanative.native.{Zone, fromCString, toCString}
import ui._

trait AbstractTextArea extends Component {
  def text: String = {
    require(initialized)
    fromCString(uiMultilineEntryText(control))
  }

  def text_=(s: String): Unit = Zone { implicit z =>
    require(initialized)
    uiMultilineEntrySetText(control, toCString(s))
  }

  def readOnly: Boolean = {
    require(initialized)
    uiMultilineEntryReadOnly(control)
  }

  def readOnly_=(v: Boolean): Unit = {
    require(initialized)
    uiMultilineEntrySetReadOnly(control, v)
  }
}
