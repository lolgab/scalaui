package scalaui

import scala.scalanative.native._
import ui._

class AttributedString private () extends Freeable {
  private[scalaui] var control: Ptr[uiAttributedString] = null

  def this(s: String) = {
    this()
    Zone { implicit z => control = uiNewAttributedString(toCString(s)) }
  }

  def text: String = fromCString(uiAttributedStringString(control))

  def length: Int = uiAttributedStringLen(control).toInt

  def withFamily(
      family: String,
      start: Int = 0,
      end: Int = length
  ): AttributedString = Zone { implicit z =>
    val attr = uiNewFamilyAttribute(toCString(family))
    uiAttributedStringSetAttribute(control, attr, start, end)
    this
  }
  def withSize(
      size: Double,
      start: Int = 0,
      end: Int = length
  ): AttributedString = {
    val attr = uiNewSizeAttribute(size)
    uiAttributedStringSetAttribute(control, attr, start, end)
    this
  }
  def withBackground(
      color: Color,
      start: Int = 0,
      end: Int = length
  ): AttributedString = {
    val attr =
      uiNewBackgroundAttribute(color.red, color.green, color.blue, color.alpha)
    uiAttributedStringSetAttribute(control, attr, start, end)
    this
  }
  def withColor(
      color: Color,
      start: Int = 0,
      end: Int = length
  ): AttributedString = {
    val attr =
      uiNewColorAttribute(color.red, color.green, color.blue, color.alpha)
    uiAttributedStringSetAttribute(control, attr, start, end)
    this
  }
  def withWeight(
      weight: Int,
      start: Int = 0,
      end: Int = length
  ): AttributedString = {
    val attr = uiNewWeightAttribute(weight.toUInt)
    uiAttributedStringSetAttribute(control, attr, start, end)
    this
  }
  def withItalic(
      italic: Italic.Value,
      start: Int = 0,
      end: Int = length
  ): AttributedString = {
    val attr = uiNewItalicAttribute(italic.id.toUInt)
    uiAttributedStringSetAttribute(control, attr, start, end)
    this
  }

  override /*private[scalaui]*/ def free()
      : Unit = { //TODO make free private and find a way to clean memory
    uiFreeAttributedString(control)
  }
}
