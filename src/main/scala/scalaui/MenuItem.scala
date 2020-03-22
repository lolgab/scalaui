package scalaui

import scala.scalanative.unsafe.CFuncPtr0
import ui._

class MenuItem(val name: String, onClick: () => Unit) extends AbstractMenuItem {
  private[scalaui] def build(): Unit = {
    uiMenuItemOnClicked(control, cCallback3, PtrConverter.toPtr(onClick))
  }
}
