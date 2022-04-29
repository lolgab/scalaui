package scalaui

import scala.scalanative.unsafe._
import ui._

class Menu(name: String, items: AppendableToMenu*) extends GraphicObject {
  private[scalaui] def build(): Unit = Zone { implicit z =>
    control = uiNewMenu(toCString(name))
    for (item <- items) {
      item match {
        case item: MenuItem =>
          item.control = uiMenuAppendItem(control, toCString(item.name))
        case _: AboutMenuItem =>
          item.control = uiMenuAppendAboutItem(control)
        case _: PreferencesMenuItem =>
          item.control = uiMenuAppendPreferencesItem(control)
        case _: QuitMenuItem =>
          item.control = uiMenuAppendQuitItem(control)
        case SeparatorMenuItem =>
          uiMenuAppendSeparator(control)
      }
      item.build()
    }
  }
}
