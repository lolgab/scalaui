package scalaui.codecs

/** A decoded raster image: 8-bit non-premultiplied RGBA, row-major, `width * height * 4` bytes. */
final case class Image(width: Int, height: Int, rgba: Array[Byte]):
  require(rgba.length == width * height * 4, s"expected ${width * height * 4} bytes, got ${rgba.length}")

  def pixel(x: Int, y: Int): Int =
    val i = (y * width + x) * 4
    ((rgba(i) & 0xff) << 24) | ((rgba(i + 1) & 0xff) << 16) | ((rgba(i + 2) & 0xff) << 8) | (rgba(i + 3) & 0xff)

  /** Premultiplied RGBA8, the layout GPU textures use. */
  def premultiplied: Array[Byte] =
    val out = new Array[Byte](rgba.length)
    var i = 0
    while i < rgba.length do
      val a = rgba(i + 3) & 0xff
      if a == 255 then { out(i) = rgba(i); out(i + 1) = rgba(i + 1); out(i + 2) = rgba(i + 2) }
      else
        out(i) = (((rgba(i) & 0xff) * a + 127) / 255).toByte
        out(i + 1) = (((rgba(i + 1) & 0xff) * a + 127) / 255).toByte
        out(i + 2) = (((rgba(i + 2) & 0xff) * a + 127) / 255).toByte
      out(i + 3) = rgba(i + 3)
      i += 4
    out

/** One frame of an [[Animation]]: a full-canvas image shown for `delayMs`. */
final case class AnimationFrame(image: Image, delayMs: Int)

/** A multi-frame image (GIF, animated WebP). Frames are already composited onto the full canvas. `loopCount` 0 means forever. */
final case class Animation(width: Int, height: Int, frames: Vector[AnimationFrame], loopCount: Int = 0)

class ImageFormatException(msg: String) extends RuntimeException(msg)

/** Image file formats the codecs can write. */
enum ImageFormat:
  case Png, Jpeg, Bmp, Gif, Tiff, Webp

object Image:
  /** The embedded ICC profile bytes of an encoded PNG (iCCP), JPEG (APP2) or WebP (ICCP) image, if any; parse with `scalaui.cms.Profile.parse`. */
  def iccProfile(data: Array[Byte]): Option[Array[Byte]] = IccProfile.extract(data)

  /** Encodes `img`. `quality` (1..100) applies to JPEG only; WebP is written lossless, TIFF uncompressed, GIF palettised. */
  def encode(img: Image, format: ImageFormat, quality: Int = 90): Array[Byte] = format match
    case ImageFormat.Png => Png.encode(img)
    case ImageFormat.Jpeg => JpegEncoder.encode(img, quality)
    case ImageFormat.Bmp => Bmp.encode(img)
    case ImageFormat.Gif => Gif.encode(img)
    case ImageFormat.Tiff => Tiff.encode(img, deflate = true)
    case ImageFormat.Webp => WebpEncoder.encode(img)

  /** Frames of an animated image (GIF or WebP); other formats give a single frame. */
  def decodeAnimation(data: Array[Byte]): Animation =
    if Gif.matches(data) then Gif.decodeAnimation(data)
    else if Webp.matches(data) then Webp.decodeAnimation(data)
    else { val i = decode(data); Animation(i.width, i.height, Vector(AnimationFrame(i, 0)), 0) }

  /** Builds an image from premultiplied RGBA8 (the layout of GPU textures and software-rendered frames). */
  def fromPremultiplied(width: Int, height: Int, premul: Array[Byte]): Image =
    val out = new Array[Byte](premul.length)
    var i = 0
    while i < premul.length do
      val a = premul(i + 3) & 0xff
      if a == 255 || a == 0 then { out(i) = premul(i); out(i + 1) = premul(i + 1); out(i + 2) = premul(i + 2) }
      else
        out(i) = math.min(255, ((premul(i) & 0xff) * 255 + a / 2) / a).toByte
        out(i + 1) = math.min(255, ((premul(i + 1) & 0xff) * 255 + a / 2) / a).toByte
        out(i + 2) = math.min(255, ((premul(i + 2) & 0xff) * 255 + a / 2) / a).toByte
      out(i + 3) = premul(i + 3)
      i += 4
    Image(width, height, out)

  /** Decodes PNG (and, as formats are added, others) by sniffing the header. */
  def decode(data: Array[Byte]): Image =
    if Png.matches(data) then Png.decode(data)
    else if Jpeg.matches(data) then Jpeg.decode(data)
    else if Bmp.matches(data) then Bmp.decode(data)
    else if Gif.matches(data) then Gif.decode(data)
    else if Tiff.matches(data) then Tiff.decode(data)
    else if Webp.matches(data) then Webp.decode(data)
    else throw new ImageFormatException("unrecognized image format")
