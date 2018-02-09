package scalaui

import scala.scalanative.native.{Zone, alloc, toCString, _}
import scalaui.ui._
import scalaui.uiOps._

// TODO Generalize control for font, fontfamily etc.
class Font(family: String = "",
           size: Double = 0.0,
           weight: Weight.Value = Weight.Normal,
           italic: Italic.Value = Italic.Normal,
           stretch: Stretch.Value = Stretch.Normal) {
  //TODO waiting for https://github.com/scala-native/scala-native/issues/367
  private[scalaui] var control: Ptr[uiDrawTextFont] = null

  private[scalaui] def build(): Unit = Zone { implicit z =>
    val descriptor = alloc[uiDrawTextFontDescriptor]
    descriptor.Family = toCString(family)
    descriptor.Size = size
    descriptor.Weight = weight.id.toUInt
    descriptor.Italic = italic.id.toUInt
    descriptor.Stretch = stretch.id.toUInt
    control = uiDrawLoadClosestFont(descriptor)
  }

//  TODO Scala Native doesn't allow it!
//  private def getMetricsValue(f: Ptr[uiDrawTextFontMetrics] => Double): Double = {
//    val metrics = stackalloc[uiDrawTextFontMetrics]
//    uiDrawTextFontGetMetrics(control, metrics)
//    f(metrics)
//  }

  def ascent: Double = {
    val metrics = stackalloc[uiDrawTextFontMetrics]
    uiDrawTextFontGetMetrics(control, metrics)
    val res = metrics.Ascent
    res
  }

  def descent: Double = {
    val metrics = stackalloc[uiDrawTextFontMetrics]
    uiDrawTextFontGetMetrics(control, metrics)
    val res = metrics.Descent
    res
  }

  def leading: Double = {
    val metrics = stackalloc[uiDrawTextFontMetrics]
    uiDrawTextFontGetMetrics(control, metrics)
    val res = metrics.Leading
    res
  }

  def underlinePos: Double = {
    val metrics = stackalloc[uiDrawTextFontMetrics]
    uiDrawTextFontGetMetrics(control, metrics)
    val res = metrics.UnderlinePos
    res
  }

  def underlineThickness: Double = {
    val metrics = stackalloc[uiDrawTextFontMetrics]
    uiDrawTextFontGetMetrics(control, metrics)
    val res = metrics.UnderlineThickness
    res
  }
}

object Weight extends Enumeration {
  val Thin, UltraLight, Light, Book, Normal, Medium, SemiBold, Bold, UltraBold, Heavy, UltraHeavy =
    Value
}

object Italic extends Enumeration {
  val Normal, Oblique, Italic = Value
}

object Stretch extends Enumeration {
  val UltraCondensed, ExtraCondensed, Condensed, SemiCondensed, Normal, SemiExpanded, Expanded,
  ExtraExpanded, UltraExpanded = Value
}
