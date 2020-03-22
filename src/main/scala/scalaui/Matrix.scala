package scalaui

import scala.scalanative.native._
import scala.scalanative.native.stdlib.malloc
import ui._
import uiOps._

class Matrix private (private[scalaui] val m: Ptr[uiDrawMatrix]) {
  def this() = this(malloc(sizeof[uiDrawMatrix]).cast[Ptr[uiDrawMatrix]])

  def apply(x: Int, y: Int): Double = (x, y) match {
    case (0, 0) => m.M11
    case (0, 1) => m.M12
    case (1, 0) => m.M21
    case (1, 1) => m.M22
    case (2, 0) => m.M31
    case (2, 1) => m.M32
    case _      => throw new IndexOutOfBoundsException
  }

  def setToIdentity(): Unit = {
    require(initialized)
    uiDrawMatrixSetIdentity(m)
  }

  def translate(p: Point): Unit = {
    require(initialized)
    uiDrawMatrixTranslate(m, p.x, p.y)
  }

  def translate(p: Point, amount: Double): Unit = {
    require(initialized)
    uiDrawMatrixTranslate(m, p.x, p.y, amount)
  }

  //TODO find name for `to`
  def scale(center: Point, to: Point): Unit = {
    require(initialized)
    uiDrawMatrixScale(m, center.x, center.x, to.x, to.y)
  }

  def skew(p: Point, amount: Point): Unit = {
    require(initialized)
    uiDrawMatrixSkew(m, p.x, p.y, amount.x, amount.y)
  }

  def multiply(other: Matrix): Unit = {
    require(initialized)
    uiDrawMatrixMultiply(m, other.m)
  }

  def invertible: Boolean = {
    require(initialized)
    uiDrawMatrixInvertible(m)
  }

  def invert: Boolean = {
    require(initialized)
    uiDrawMatrixInvert(m)
  }

  private def createPoint(
      p: Point,
      f: (Ptr[CDouble], Ptr[CDouble]) => Unit
  ): Point = {
    val x, y = stackalloc[CDouble]
    !x = p.x
    !y = p.y
    f(x, y)
    Point(!x, !y)
  }

  def transformPoint(p: Point): Point = {
    require(initialized)
    createPoint(p, uiDrawMatrixTransformPoint(m, _, _))
  }

  def transformSize(p: Point): Point = {
    require(initialized)
    createPoint(p, uiDrawMatrixTransformSize(m, _, _))
  }

  //TODO context is private...
  def transform(drawContext: DrawContext): Unit = {
    require(initialized)
    uiDrawTransform(drawContext, m)
  }
}
