package scalaui

import scala.scalanative.native.{CFunctionPtr0, CInt, Ptr, Zone, fromCString, toCString}
import scalaui._
import ui._

class Window(title: String,
             width: Int,
             height: Int,
             content: Component,
             menus: Seq[Menu] = Seq(),
             val onClosing: CFunctionPtr0[Unit] = doNothing _)
    extends GraphicObject {

  private[scalaui] def build(): Unit = Zone { implicit z =>
    for (menu <- menus) menu.build()
    control = uiNewWindow(toCString(title), width, height, menus.nonEmpty)
    content.build()
    uiWindowSetChild(control, content.control)
  }

  def openFile(): String = fromCString(uiOpenFile(control))
  def saveFile(): String = fromCString(uiSaveFile(control))

  def messageBox(title: String, description: String): Unit = Zone {
    implicit z =>
      uiMsgBox(control, toCString(title), toCString(description))
  }
  def errorBox(title: String, description: String): Unit = Zone { implicit z =>
    uiMsgBoxError(control, toCString(title), toCString(description))
  }
}