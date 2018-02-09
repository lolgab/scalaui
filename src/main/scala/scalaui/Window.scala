package scalaui

import scala.scalanative.native.{CFunctionPtr0, CInt, Ptr, Zone, fromCString, stackalloc, toCString}
import ui._

class Window(_title: String,
             width: Int,
             height: Int,
             content: Component,
             menus: Seq[Menu] = Seq(),
             private[scalaui] val onClosing: CFunctionPtr0[Boolean] = doNothingThenClose _,
             onContentSizeChanged: CFunctionPtr0[Unit] = doNothing _)
    extends GraphicObject {

  private[scalaui] def build(): Unit = Zone { implicit z =>
    for (menu <- menus) menu.build()
    control = uiNewWindow(toCString(_title), width, height, menus.nonEmpty)
    content.build()
    uiWindowSetChild(control, content.control)
    uiWindowOnContentSizeChanged(control, onContentSizeChanged, null)
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

  def title: String = fromCString(uiWindowTitle(control))

  def title_=(v: String): Unit = Zone { implicit z =>
    uiWindowSetTitle(control, toCString(v))
  }

  def contentSize: (Width, Height) = {
    val width: Ptr[CInt] = stackalloc[CInt]
    val height: Ptr[CInt] = stackalloc[CInt]
      uiWindowContentSize(control, width, height)
    (!width, !height)
  }

  def contentSize_=(width: Width, height: Height): Unit = uiWindowSetContentSize(control, width, height)

  def isFullScreen: Boolean = uiWindowFullscreen(control)

  def fullScreen_=(v: Boolean): Unit = uiWindowSetFullscreen(control, v)

  def isBorderLess: Boolean = uiWindowBorderless(control)

  def borderLess_=(v: Boolean): Unit = uiWindowSetBorderless(control, v)

  def isMargined: Boolean = uiWindowMargined(control)

  def margined_=(v: Boolean): Unit = uiWindowSetMargined(control, v)

  private[scalaui] override def free(): Unit = content.free()
}