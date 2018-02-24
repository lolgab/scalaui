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
    extends GraphicObject
    with Freeable {

  private[scalaui] def build(): Unit = Zone { implicit z =>
    for (menu <- menus) menu.build()
    control = uiNewWindow(toCString(_title), width, height, menus.nonEmpty)
    content.build()
    uiWindowSetChild(control, content.control)
    uiWindowOnContentSizeChanged(control, onContentSizeChanged, null)
  }

  def openFile(): String = {
    require(initialized)
    fromCString(uiOpenFile(control))
  }

  def saveFile(): String = {
    require(initialized)
    fromCString(uiSaveFile(control))
  }

  def messageBox(title: String, description: String): Unit = Zone { implicit z =>
    require(initialized)
    uiMsgBox(control, toCString(title), toCString(description))
  }

  def errorBox(title: String, description: String): Unit = Zone { implicit z =>
    require(initialized)
    uiMsgBoxError(control, toCString(title), toCString(description))
  }

  def title: String = {
    require(initialized)
    fromCString(uiWindowTitle(control))
  }

  def title_=(v: String): Unit = Zone { implicit z =>
    require(initialized)
    uiWindowSetTitle(control, toCString(v))
  }

  def contentSize: (Width, Height) = {
    require(initialized)
    val width: Ptr[CInt]  = stackalloc[CInt]
    val height: Ptr[CInt] = stackalloc[CInt]
    uiWindowContentSize(control, width, height)
    (!width, !height)
  }

  def contentSize_=(width: Width, height: Height): Unit = {
    require(initialized)
    uiWindowSetContentSize(control, width, height)
  }

  def isFullScreen: Boolean = {
    require(initialized)
    uiWindowFullscreen(control)
  }

  def fullScreen_=(v: Boolean): Unit = {
    require(initialized)
    uiWindowSetFullscreen(control, v)
  }

  def isBorderLess: Boolean = {
    require(initialized)
    uiWindowBorderless(control)
  }

  def borderLess_=(v: Boolean): Unit = {
    require(initialized)
    uiWindowSetBorderless(control, v)
  }

  def isMargined: Boolean = {
    require(initialized)
    uiWindowMargined(control)
  }

  def margined_=(v: Boolean): Unit = {
    require(initialized)
    uiWindowSetMargined(control, v)
  }

  private[scalaui] override def free(): Unit = content.free()
}
