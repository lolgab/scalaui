package scalaui.imaging

import scalaui.cms.{Intent, Profile, Transform}
import scalaui.codecs.Image
import scalaui.raster.Pixmap

/** ICC colour conversion of pixel buffers. Only RGB (3 channel) source/destination profiles are accepted. */
object ColorManagement:
  /** Converts a premultiplied float [[Pixmap]] between RGB profiles (alpha preserved; colour is unpremultiplied around the transform). */
  def convert(pm: Pixmap, from: Profile, to: Profile, intent: Intent = Intent.Perceptual, blackPointCompensation: Boolean = false): Pixmap =
    val t = new Transform(from, to, intent, blackPointCompensation)
    require(t.inputChannels == 3 && t.outputChannels == 3, "pixmap conversion needs RGB profiles")
    val out = new Pixmap(pm.width, pm.height)
    val in = new Array[Double](3)
    var i = 0
    while i < pm.data.length do
      val a = pm.data(i + 3)
      if a > 0f then
        in(0) = pm.data(i) / a; in(1) = pm.data(i + 1) / a; in(2) = pm.data(i + 2) / a
        val o = t(in)
        out.data(i) = o(0).toFloat * a; out.data(i + 1) = o(1).toFloat * a; out.data(i + 2) = o(2).toFloat * a
      out.data(i + 3) = a
      i += 4
    out

  /** Converts a decoded 8-bit [[Image]] between RGB profiles. */
  def convert(img: Image, from: Profile, to: Profile, intent: Intent, blackPointCompensation: Boolean): Image =
    val out = new Array[Byte](img.rgba.length)
    new Transform(from, to, intent, blackPointCompensation).rgba8(img.rgba, out, img.width * img.height)
    Image(img.width, img.height, out)

  def convert(img: Image, from: Profile, to: Profile): Image = convert(img, from, to, Intent.Perceptual, false)

  /** Converts an image to sRGB using its embedded ICC profile (PNG iCCP, JPEG APP2, WebP ICCP); unchanged if there is none or it is not RGB. */
  def toSrgb(encoded: Array[Byte]): Image =
    val img = Image.decode(encoded)
    Image.iccProfile(encoded).flatMap(b => scala.util.Try(Profile.parse(b)).toOption).filter(_.colorSpace == "RGB ") match
      case Some(p) if p.canInput() => convert(img, p, Profile.sRGB)
      case _ => img
