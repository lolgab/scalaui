package scalaui.imaging

import scalaui.codecs.Image
import scalaui.raster.Pixmap

object Pixmaps:
  /** Converts a decoded image (non-premultiplied RGBA8) into a premultiplied float [[Pixmap]]. */
  def fromImage(img: Image): Pixmap =
    val pm = new Pixmap(img.width, img.height)
    var i = 0
    while i < img.width * img.height * 4 do
      val a = (img.rgba(i + 3) & 0xff) / 255f
      pm.data(i) = (img.rgba(i) & 0xff) / 255f * a
      pm.data(i + 1) = (img.rgba(i + 1) & 0xff) / 255f * a
      pm.data(i + 2) = (img.rgba(i + 2) & 0xff) / 255f * a
      pm.data(i + 3) = a
      i += 4
    pm

  def toImage(pm: Pixmap): Image = Image(pm.width, pm.height, pm.toRgba8)

  def copy(pm: Pixmap): Pixmap =
    val c = new Pixmap(pm.width, pm.height)
    System.arraycopy(pm.data, 0, c.data, 0, pm.data.length)
    c
