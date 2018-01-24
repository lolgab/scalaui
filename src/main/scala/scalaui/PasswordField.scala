package scalaui

import ui._

class PasswordField extends Field {
  private[scalaui] override def build(): Unit = {
    control = uiNewPasswordEntry()
  }
}
