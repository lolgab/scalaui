package scalaui

import scala.scalanative.unsafe._
import ui._

class Window(
    _title: String,
    width: Int,
    height: Int,
    content: Component,
    menus: Seq[Menu] = Seq(),
    _borderLess: Boolean = false,
    _fullScreen: Boolean = false,
    _margined: Boolean = false,
    private[scalaui] val onClosing: CFuncPtr0[Boolean] = doNothingThenClose _,
    onContentSizeChanged: CFuncPtr0[Unit] = doNothing _
) extends GraphicObject
    with Freeable {

  private[scalaui] def build(): Unit = Zone { implicit z =>
    for (menu <- menus) menu.build()
    control = uiNewWindow(toCString(_title), width, height, menus.nonEmpty)
    content.build()
    uiWindowSetChild(control, content.control)
    uiWindowOnContentSizeChanged(control, onContentSizeChanged, null)
    borderLess_=(_borderLess)
    fullScreen_=(_fullScreen)
    margined_=(_margined)
  }

  def openFile(): String = {
    require(initialized)
    fromCString(uiOpenFile(control))
  }

  def saveFile(): String = {
    require(initialized)
    fromCString(uiSaveFile(control))
  }

  def messageBox(title: String, description: String): Unit = Zone {
    implicit z =>
      require(initialized)
      uiMsgBox(control, toCString(title), toCString(description))
  }

  def errorBox(title: String, description: String): Unit = Zone { implicit z =>
    require(initialized)
    uiMsgBoxError(control, toCString(title), toCString(description))
  }

  def title: String = {
    if (initialized)
      fromCString(uiWindowTitle(control))
    else _title
  }

  def title_=(v: String): Unit = Zone { implicit z =>
    require(initialized)
    uiWindowSetTitle(control, toCString(v))
  }

  private def contentSize: (Int, Int) = {
    require(initialized)
    val width: Ptr[CInt] = stackalloc[CInt]
    val height: Ptr[CInt] = stackalloc[CInt]
    uiWindowContentSize(control, width, height)
    (!width, !height)
  }

  def contentWidth: Int = {
    contentSize._1
  }

  def contentHeight: Int = {
    contentSize._2
  }

  def contentWidth_=(width: Int): Unit = {
    require(initialized)
    uiWindowSetContentSize(control, width, contentHeight)
  }

  def contentHeight_=(height: Int): Unit = {
    require(initialized)
    uiWindowSetContentSize(control, contentWidth, height)
  }

  def fullScreen: Boolean = {
    if (initialized)
      uiWindowFullscreen(control)
    else
      _fullScreen
  }

  def fullScreen_=(v: Boolean): Unit = {
    uiWindowSetFullscreen(control, v)
  }

  def borderLess: Boolean = {
    if (initialized)
      uiWindowBorderless(control)
    else
      _borderLess
  }

  def borderLess_=(v: Boolean): Unit = {
    uiWindowSetBorderless(control, v)
  }

  def margined: Boolean = {
    if (initialized)
      uiWindowMargined(control)
    else
      _margined
  }

  def margined_=(v: Boolean): Unit = {
    uiWindowSetMargined(control, v)
  }

  private[scalaui] override def free(): Unit = content.free()
}
