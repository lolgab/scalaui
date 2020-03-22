package scalaui

import scala.scalanative.unsafe.{Ptr, sizeof}
import scala.scalanative.libc.stdlib.malloc
import ui.{uiDrawBrush, uiDrawBrushGradientStop}
import uiOps._

abstract class Brush {
  private[scalaui] val control: Ptr[uiDrawBrush] = malloc(sizeof[uiDrawBrush])
    .asInstanceOf[Ptr[uiDrawBrush]]
}

class SolidBrush(color: Color) extends Brush {
  control.Type = uiDrawBrushType.uiDrawBrushTypeSolid

  control.R = color.red
  control.G = color.green
  control.B = color.blue
  control.A = color.alpha
}

class GradientBrush(
    colors: Seq[Color],
    start: Point,
    end: Point,
    transitionFunction: Double => Double = identity
) extends Brush {
  control.Type = uiDrawBrushType.uiDrawBrushTypeLinearGradient

  control.X0 = start.x
  control.Y0 = start.y
  control.X1 = end.x
  control.Y1 = end.y

  private[scalaui] val stopsArray: Ptr[uiDrawBrushGradientStop] =
    malloc(colors.length * sizeof[uiDrawBrushGradientStop])
      .asInstanceOf[Ptr[uiDrawBrushGradientStop]]

  for (i <- colors.indices) {
    val s = stopsArray + i * sizeof[uiDrawBrushGradientStop]
    s.R = colors(i).red
    s.G = colors(i).green
    s.B = colors(i).blue
    s.A = colors(i).alpha
    s.Pos =
      transitionFunction((colors.length - 1 - i).toDouble) / (colors.length - 1)
  }

  control.NumStops = colors.length
  control.Stops = stopsArray
}

class RadialGradientBrush(
    colors: Seq[Color],
    start: Point,
    end: Point,
    outerRadious: Double
) extends GradientBrush(colors, start, end) {
  control.OuterRadius = outerRadious
}
