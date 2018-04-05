package scalaui

import ui._

import scala.scalanative.native.{CFunctionPtr0, CFunctionPtr2, Ptr, stdlib, sizeof}

class FontButton private (changeFont: CFunctionPtr2[Ptr[uiFontButton], Ptr[Byte], Unit],
                          onFontChange: CFunctionPtr0[Unit])
    extends Component {
  var createdFonts = List[Font]()

  def font: Font = {
    val f = new Font()
//    f.control =
    uiFontButtonFont(control, f.control)
    createdFonts = f :: createdFonts
    f
  }

  def this(onFontChange: CFunctionPtr0[Unit] = doNothing _) =
    this(FontButton.changeFont _, onFontChange)

  override private[scalaui] def build(): Unit = {
    control = uiNewFontButton()
    uiFontButtonOnChanged(control, changeFont, onFontChange.cast[Ptr[Byte]])
  }

  private[scalaui] override def free(): Unit = {
    createdFonts.foreach(_.free())
  }
}

object FontButton {
  private def changeFont(b: Ptr[uiFontButton], data: Ptr[Byte]): Unit = {
    val onChange = data.cast[CFunctionPtr0[Unit]]
    onChange()
  }
}
