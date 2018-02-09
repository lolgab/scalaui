package scalaui

import scala.scalanative.native._
import scala.scalanative.native.stdlib.malloc
import ui._
import uiOps._

abstract class AbstractArea(draw: DrawCallback,
                            onMouseEvent: MouseEventCallback,
                            onMouseCrossed: MouseCrossedCallback,
                            onKeyEvent: KeyEventCallback,
                            onDragBroken: DragBrokenCallback = doNothingOnDragBroken _)
extends Component {
  val handler: Ptr[uiAreaHandler] = malloc(sizeof[uiAreaHandler])
    .cast[Ptr[uiAreaHandler]]
  handler.Draw = draw
  handler.MouseEvent = onMouseEvent
  handler.MouseCrossed = onMouseCrossed.cast[uiMouseCrossedCallback]
  handler.DragBroken = onDragBroken
  handler.KeyEvent = onKeyEvent.cast[uiKeyEventCallback]
}