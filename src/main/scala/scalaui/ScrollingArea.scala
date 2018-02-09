package scalaui

import ui._
import uiOps._

class ScrollingArea(width: Int,
                    height: Int,
                    draw: DrawCallback,
                    onMouseEvent: MouseEventCallback,
                    onMouseCrossed: MouseCrossedCallback,
                    onKeyEvent: KeyEventCallback)
    extends AbstractArea(draw, onMouseEvent, onMouseCrossed, onKeyEvent) {
  override private[scalaui] def build(): Unit = {
    control = uiNewScrollingArea(handler, width, height)
  }
}
