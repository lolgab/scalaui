package scalaui

import scala.scalanative.unsafe.{Zone, toCString}
import ui._

class TextField(initialText: String) extends Field {
  private[scalaui] override def build(): Unit = Zone { implicit z =>
    control = uiNewEntry()
    uiEntrySetText(control, toCString(initialText))
  }
}
