package scalaui

import scala.scalanative.native.stdlib.malloc
import scala.scalanative.native.{CFunctionPtr0, CInt, Ptr, sizeof}
import ui._

package object scalaui {
  private def onShouldQuit(data: Ptr[Byte]): CInt = {
    uiControlDestroy(data.cast[Ptr[uiWindow]])
    1
  }

  def onClosing(w: Ptr[uiWindow], data: Ptr[Byte]): CInt = {
    if(data.cast[CFunctionPtr0[Boolean]].apply()) {
      uiQuit()
      1
    } else 0
  }

  def render(window: Window): Unit = {
    val options: Ptr[uiInitOptions] = malloc(sizeof[uiInitOptions])
      .cast[Ptr[uiInitOptions]]
    uiInit(options)

    window.build()
    uiWindowOnClosing(window.control, onClosing _, window.onClosing.cast[Ptr[Byte]])
    uiOnShouldQuit(onShouldQuit _, window.control.cast[Ptr[Byte]])
    uiControlShow(window.control)

    uiMain()
    uiUninit()
  }

  type Stratchy = Boolean

  implicit def toBoolean(i: CInt): Boolean = i match {
    case 0 => false
    case _ => true
  }

  implicit def toCInt(b: Boolean): CInt = if (b) 1 else 0

  implicit def toTuplueStratchy(c: Component): (Component, Stratchy) =
    (c, false)

  def doNothing(): Unit = ()

  def doNothingThenClose(): Boolean = true
}
