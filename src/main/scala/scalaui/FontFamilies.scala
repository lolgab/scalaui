package scalaui

import ui._
import scalanative.native.{Zone, fromCString}

object FontFamilies {
  val list: Seq[String] = {
    val ff = uiDrawListFontFamilies()

    val l = 0.until(uiDrawFontFamiliesNumFamilies(ff))
      .map(i => fromCString(uiDrawFontFamiliesFamily(ff, i)))

    uiDrawFreeFontFamilies(ff)
    l
  }
}
