package scalaui

import ui._
import uiOps._
import scalanative.unsafe._
import scalanative.unsigned._

private[scalaui] class TextLayout(
    text: AttributedString,
    defaultFont: Font,
    width: Double,
    align: Align.Value
) {
  //TODO waiting for issue (returning Ptr from Zone) being solved
  private[scalaui] var control: Ptr[uiDrawTextLayout] = null
  def build(): Unit = Zone { implicit z =>
    val params = alloc[uiDrawTextLayoutParams]()
    params.String = text.control
    params.DefaultFont = defaultFont.control
    params.Width = width
    params.Align = align.id.toUInt

    control = uiDrawNewTextLayout(params)
  }
}
