package scalaui

import ui._

class PreferencesMenuItem(onClick: () => Unit) extends AbstractMenuItem {
  private[scalaui] def build(): Unit = {
    uiMenuItemOnClicked(control, cCallback3, PtrConverter.toPtr(onClick))
  }
}
