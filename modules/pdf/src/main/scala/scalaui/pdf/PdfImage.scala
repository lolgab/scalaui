package scalaui.pdf

import scalaui.codecs.Image

/** An image registered with a [[PdfDocument]] (decoded pixels re-encoded with Flate and an `SMask`, or a JPEG stored as-is with `DCTDecode`). */
final class PdfImage private[pdf] (private[pdf] val id: Int, val width: Int, val height: Int, val hasAlpha: Boolean)

private[pdf] object ImageData:
  final case class JpegInfo(width: Int, height: Int, components: Int, adobeTransform: Option[Int], bits: Int)

  /** Reads the frame header of a baseline/extended/progressive JPEG (without decoding it). */
  def jpegInfo(d: Array[Byte]): Option[JpegInfo] =
    if d.length < 4 || (d(0) & 0xff) != 0xff || (d(1) & 0xff) != 0xd8 then return None
    var i = 2; var adobe: Option[Int] = None
    while i + 4 < d.length do
      if (d(i) & 0xff) != 0xff then i += 1
      else
        val m = d(i + 1) & 0xff
        if m == 0xff then i += 1
        else if m == 0x01 || (m >= 0xd0 && m <= 0xd8) then i += 2
        else
          val len = ((d(i + 2) & 0xff) << 8) | (d(i + 3) & 0xff)
          if m == 0xee && len >= 14 && new String(d, i + 4, 5, "ISO-8859-1") == "Adobe" then adobe = Some(d(i + 15) & 0xff)
          if (m >= 0xc0 && m <= 0xcf) && m != 0xc4 && m != 0xc8 && m != 0xcc then
            val bits = d(i + 4) & 0xff
            val h = ((d(i + 5) & 0xff) << 8) | (d(i + 6) & 0xff); val w = ((d(i + 7) & 0xff) << 8) | (d(i + 8) & 0xff)
            val comps = d(i + 9) & 0xff
            return Some(JpegInfo(w, h, comps, adobe, bits))
          i += 2 + len
    None

  def rgbAndAlpha(img: Image): (Array[Byte], Option[Array[Byte]], Boolean) =
    val n = img.width * img.height
    var gray = true; var opaque = true
    var i = 0
    while i < n && (gray || opaque) do
      val r = img.rgba(i * 4); val g = img.rgba(i * 4 + 1); val b = img.rgba(i * 4 + 2)
      if r != g || g != b then gray = false
      if img.rgba(i * 4 + 3) != -1 then opaque = false
      i += 1
    val color = new Array[Byte](if gray then n else n * 3)
    val alpha = if opaque then None else Some(new Array[Byte](n))
    i = 0
    while i < n do
      if gray then color(i) = img.rgba(i * 4)
      else { color(i * 3) = img.rgba(i * 4); color(i * 3 + 1) = img.rgba(i * 4 + 1); color(i * 3 + 2) = img.rgba(i * 4 + 2) }
      alpha.foreach(a => a(i) = img.rgba(i * 4 + 3))
      i += 1
    (color, alpha, gray)
