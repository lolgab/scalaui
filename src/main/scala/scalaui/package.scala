package scalaui

import scala.scalanative.native.stdlib.malloc
import scala.scalanative.native.{CInt, Ptr, sizeof}
import ui._

package object scalaui {
  def render(window: Window): Unit = {
    val options: Ptr[uiInitOptions] = malloc(sizeof[uiInitOptions])
      .cast[Ptr[uiInitOptions]]
    uiInit(options)

    window.build()
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

  implicit def toTuplueStratchy(c: Component): (Component, Stratchy) = (c, false)

  def doNothing(): Unit = {}
}
