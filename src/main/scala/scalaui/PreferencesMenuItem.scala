package scalaui

import scala.scalanative.unsafe._
import ui._

class PreferencesMenuItem(onClick: CFuncPtr0[Unit]) extends AbstractMenuItem {
  private[scalaui] def build(): Unit = {
    uiMenuItemOnClicked(control, onClick, null)
  }
}
