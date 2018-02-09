package scalaui

import ui._
import scalanative.native.{Ptr, Zone, toCString}

private[scalaui] class TextLayout(text: String, font: Font, width: Double) {
  //TODO waiting for issue (returning Ptr from Zone) being solved
  private[scalaui] var control: Ptr[uiDrawTextLayout] = null
  def build(): Unit = Zone { implicit z =>
    control = uiDrawNewTextLayout(toCString(text), font.control, width)
    uiDrawTextLayoutSetColor(control, 0, text.length, 0.0, 0.0, 0.0, 1.0)
    //TODO allow colored text
  }
}
