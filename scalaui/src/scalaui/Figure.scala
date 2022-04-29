package scalaui

import ui._
//TODO make Drawable and GraphicObject siblings classes
sealed trait Drawable {
  private[scalaui] def addTo(path: DrawPath): Unit
}
case class Point(x: Double, y: Double)

class Rectangle(start: Point, width: Double, height: Double) extends Drawable {
  private[scalaui] override def addTo(path: DrawPath): Unit =
    uiDrawPathAddRectangle(path, start.x, start.y, width, height)
}

class Figure(start: Point, lines: Seq[Line]) extends Drawable {
  private[scalaui] override def addTo(path: DrawPath): Unit = {
    uiDrawPathNewFigure(path, start.x, start.y)
    for (line <- lines)
      line.addTo(path)
    uiDrawPathCloseFigure(path)
  }
}

sealed trait Line extends Drawable

class StraitLine(to: Point) extends Line {
  private[scalaui] override def addTo(path: DrawPath): Unit =
    uiDrawPathLineTo(path, to.x, to.y)
}
class ArcLine(
    center: Point,
    radius: Double,
    startAngle: Double,
    sweep: Double,
    negative: Boolean
) extends Line {
  private[scalaui] override def addTo(path: DrawPath): Unit =
    uiDrawPathArcTo(
      path,
      center.x,
      center.y,
      radius,
      startAngle,
      sweep,
      negative
    )
}
class BezierLine(to: Point, c1: Point, c2: Point) extends Line {
  private[scalaui] override def addTo(path: DrawPath): Unit =
    uiDrawPathBezierTo(path, c1.x, c1.y, c2.x, c2.y, to.x, to.y)
}
