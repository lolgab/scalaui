package scalaui

import scala.scalanative.native.{Ptr, sizeof, _}
import scala.scalanative.native.stdlib.malloc
import ui._
import uiOps._

class Stroke(
    cap: Cap.Value,
    join: Join.Value,
    thickness: Double,
    miterLimit: Double = 10.0
    //Dashes TODO Add dashes support
    //NumDashes
    //DashPhase
) {
  private[scalaui] val control: Ptr[uiDrawStrokeParams] = malloc(sizeof[uiDrawStrokeParams])
    .cast[Ptr[uiDrawStrokeParams]]

  control.Cap = cap.id.toUInt
  control.Join = join.id.toUInt
  control.Thickness = thickness
  control.MiterLimit = miterLimit
}

object Cap extends Enumeration {
  val Flat, Round, Square = Value
}
object Join extends Enumeration {
  val Miter, Round, Bevel = Value
}
