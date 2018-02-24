package scalaui

import scala.scalanative.native.{Zone, alloc, toCString, _}
import scalaui.ui._
import scalaui.uiOps._

// TODO Generalize control for font, fontfamily etc.
class Font private[scalaui] () extends Freeable {
  //TODO waiting for https://github.com/scala-native/scala-native/issues/367
  private[scalaui] var control: Ptr[uiDrawTextFont] = null

  private var _family: String         = _
  private var _size: Double           = _
  private var _weight: Weight.Value   = _
  private var _italic: Italic.Value   = _
  private var _stretch: Stretch.Value = _

  def this(family: String,
           size: Double,
           weight: Weight.Value = Weight.Normal,
           italic: Italic.Value = Italic.Normal,
           stretch: Stretch.Value = Stretch.Normal) = {
    this()
    _family = family
    _size = size
    _weight = weight
    _italic = italic
    _stretch = stretch

    if(initialized) build()
    else scalaui.fonts ::= this
  }

  private[scalaui] def build(): Unit = Zone { implicit z =>
    val descriptor = alloc[uiDrawTextFontDescriptor]
    descriptor.Family = toCString(_family)
    descriptor.Size = _size
    descriptor.Weight = _weight.id.toUInt
    descriptor.Italic = _italic.id.toUInt
    descriptor.Stretch = _stretch.id.toUInt
    control = uiDrawLoadClosestFont(descriptor)
  }

  override def free(): Unit = {
    require(initialized)
    uiDrawFreeTextFont(control)
    scalaui.fonts = fonts.filter(_ != this)
  }

//  TODO Scala Native doesn't allow it!
//  private def getMetricsValue(f: Ptr[uiDrawTextFontMetrics] => Double): Double = {
//    val metrics = stackalloc[uiDrawTextFontMetrics]
//    uiDrawTextFontGetMetrics(control, metrics)
//    f(metrics)
//  }

  def ascent: Double = {
    require(initialized)
    val metrics = stackalloc[uiDrawTextFontMetrics]
    uiDrawTextFontGetMetrics(control, metrics)
    val res = metrics.Ascent
    res
  }

  def descent: Double = {
    require(initialized)
    val metrics = stackalloc[uiDrawTextFontMetrics]
    uiDrawTextFontGetMetrics(control, metrics)
    val res = metrics.Descent
    res
  }

  def leading: Double = {
    require(initialized)
    val metrics = stackalloc[uiDrawTextFontMetrics]
    uiDrawTextFontGetMetrics(control, metrics)
    val res = metrics.Leading
    res
  }

  def underlinePos: Double = {
    require(initialized)
    val metrics = stackalloc[uiDrawTextFontMetrics]
    uiDrawTextFontGetMetrics(control, metrics)
    val res = metrics.UnderlinePos
    res
  }

  def underlineThickness: Double = {
    require(initialized)
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
