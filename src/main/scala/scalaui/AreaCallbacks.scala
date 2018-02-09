package scalaui

trait AreaCallbacks {
  def draw(ha: AreaHandler, area: Area, p: DrawParams): Unit

  def onMouseEvent(ha: AreaHandler, area: Area, e: MouseEvent): Unit

  def onMouseCrossed(ha: AreaHandler, area: Area, left: Boolean): Unit

  def onKeyEvent(ha: AreaHandler, area: Area, e: KeyEvent): Boolean
}
