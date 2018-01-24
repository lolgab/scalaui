package scalaui

import scala.scalanative.native.{Zone, fromCString, toCString}
import ui._
import scalaui._

class Window(title: String, width: Int, height: Int, content: Component, menus: Seq[Menu] = Seq()) extends GraphicObject {
  private[scalaui] def build(): Unit = Zone { implicit z =>
    for (menu <- menus) menu.build()
    control = uiNewWindow(toCString(title), width, height, menus.nonEmpty)
    uiWindowOnClosing(control, null, null)
    content.build()
    uiWindowSetChild(control, content.control)
  }

  def openFile(): String = fromCString(uiOpenFile(control))
  def saveFile(): String = fromCString(uiSaveFile(control))

  def messageBox(title: String, description: String): Unit = Zone { implicit z =>
    uiMsgBox(control, toCString(title), toCString(description))
  }
  def errorBox(title: String, description: String): Unit = Zone { implicit z =>
    uiMsgBoxError(control, toCString(title), toCString(description))
  }
}
