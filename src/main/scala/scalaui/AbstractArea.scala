// package scalaui

// import scala.scalanative.unsafe._
// import scala.scalanative.libc.stdlib.malloc
// import ui._
// import uiOps._

// abstract class AbstractArea(draw: DrawCallback,
//                             onMouseEvent: MouseEventCallback,
//                             onMouseCrossed: MouseCrossedCallback,
//                             onKeyEvent: KeyEventCallback,
//                             onDragBroken: DragBrokenCallback = doNothingOnDragBroken _)
// extends Component {
//   val handler: Ptr[uiAreaHandler] = malloc(sizeof[uiAreaHandler])
//     .asInstanceOf[Ptr[uiAreaHandler]]
//   handler.Draw = draw
//   handler.MouseEvent = onMouseEvent
//   handler.MouseCrossed = onMouseCrossed.asInstanceOf[uiMouseCrossedCallback]
//   handler.DragBroken = onDragBroken
//   handler.KeyEvent = onKeyEvent.asInstanceOf[uiKeyEventCallback]
// }
