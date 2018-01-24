package scalaui
import ui._

trait AbstractMenuItem extends AppendableToMenu {
  override def enable(): Unit = uiMenuItemEnable(control)
  override def disable(): Unit = uiMenuItemDisable(control)
}
