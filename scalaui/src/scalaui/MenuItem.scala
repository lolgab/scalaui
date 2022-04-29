package scalaui

import scala.scalanative.unsafe.CFuncPtr0
import ui._

class MenuItem(val name: String, onClick: CFuncPtr0[Unit])
    extends AbstractMenuItem {
  private[scalaui] def build(): Unit = {
    uiMenuItemOnClicked(control, onClick, null)
  }
}
