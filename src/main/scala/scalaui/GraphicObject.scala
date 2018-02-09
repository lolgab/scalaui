package scalaui

import scala.scalanative.native.Ptr
import ui._

trait GraphicObject {
  private[scalaui] def build(): Unit

  private[scalaui] var control: Ptr[uiControl] = _

  private[scalaui] def destroy(): Unit = uiControlDestroy(control)
  def visible: Boolean = uiControlVisible(control)
  def show(): Unit = uiControlShow(control)
  def hide(): Unit = uiControlHide(control)
  def enabled: Boolean = uiControlEnabled(control)
  def enable(): Unit = uiControlEnable(control)
  def disable(): Unit = uiControlDisable(control)

  private[scalaui] def free(): Unit = {}
}
