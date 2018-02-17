package scalaui

import scala.scalanative.native.{Ptr, sizeof}
import scala.scalanative.native.stdlib.malloc
import ui.uiDrawBrush
import uiOps._

abstract class Brush(color: Color) {
  private[scalaui] val control: Ptr[uiDrawBrush] = malloc(sizeof[uiDrawBrush])
    .cast[Ptr[uiDrawBrush]]

  control.R = color.red
  control.G = color.green
  control.B = color.blue
  control.A = color.alpha
}

class SolidBrush(color: Color)
    extends Brush(color: Color) {
  control.Type = uiDrawBrushType.uiDrawBrushTypeSolid
}

//class GradientBrush(color: Color) extends Brush(color) {
//  control.Type = uiDrawBrushType.
//
//  control.
//}
