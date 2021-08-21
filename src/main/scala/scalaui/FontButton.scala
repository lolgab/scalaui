package scalaui

import ui._

import scala.scalanative.unsafe._

class FontButton private (
    changeFont: CFuncPtr2[Ptr[uiFontButton], Ptr[Byte], Unit],
    onFontChange: CFuncPtr0[Unit]
) extends Component {
  var createdFonts = List[Font]()

  def font: Font = {
    val f = new Font()
//    f.control =
    uiFontButtonFont(control, f.control)
    createdFonts = f :: createdFonts
    f
  }

  def this(onFontChange: CFuncPtr0[Unit] = doNothing _) =
    this(FontButton.changeFont _, onFontChange)

  override private[scalaui] def build(): Unit = {
    control = uiNewFontButton()
    uiFontButtonOnChanged(
      control,
      changeFont,
      onFontChange.asInstanceOf[Ptr[Byte]]
    )
  }

  private[scalaui] override def free(): Unit = {
    createdFonts.foreach(_.free())
  }
}

object FontButton {
  private def changeFont(b: Ptr[uiFontButton], data: Ptr[Byte]): Unit = {
    val onChange = data.asInstanceOf[CFuncPtr0[Unit]]
    onChange()
  }
}
