package scalaui.svg

import scalaui.codecs.Image
import scalaui.color.Color
import scalaui.font.FontFamily
import scalaui.geom.*
import scalaui.raster.*
import scala.collection.mutable

/** Parsing options for [[SvgDocument.parse]].
  * @param resourceDir directory against which relative `<image href>` paths are resolved (`None` disables external files)
  * @param fonts       additional font families, matched by name before the bundled and system fonts
  * @param systemFonts whether installed fonts may be used for `font-family` names that are not bundled
  * @param defaultFont family used for `serif`/`sans-serif`/unknown names and as a glyph fallback
  */
final case class SvgOptions(resourceDir: Option[String] = None, fonts: Seq[FontFamily] = Nil, systemFonts: Boolean = true, defaultFont: String = "Noto Sans")

/** A parsed SVG document (usvg-like pre-processing: CSS folded into attributes, ids indexed) that renders to a premultiplied [[Pixmap]].
  *
  * Supported: shapes/paths, groups, `use`/`symbol`/nested `svg` with viewBox and preserveAspectRatio, CSS `<style>` (type/class/id/attribute
  * selectors, combinators, specificity), gradients (spread, focal point, transforms, href inheritance), patterns, clipPath, mask, markers,
  * dashes/caps/joins, `<image>` (PNG/JPEG/GIF/WebP/BMP/TIFF/SVG data URIs and files), text (`tspan`, `textPath`, anchors, decorations, shaping),
  * opacity groups and filters.
  */
final class SvgDocument private (val width: Double, val height: Double, private[svg] val root: XmlElement, private[svg] val ids: Map[String, XmlElement],
    private[svg] val parents: Map[XmlElement, XmlElement], private[svg] val viewBox: Option[Rect], private[svg] val options: SvgOptions):

  private[svg] val vbWidth: Double = viewBox.map(_.width).getOrElse(width)
  private[svg] val vbHeight: Double = viewBox.map(_.height).getOrElse(height)
  /** Maps viewBox coordinates to the document's `width x height` box. */
  private[svg] val rootTransform: Affine =
    val vb = viewBox.map(Geometry.viewBoxTransform(_, root.attrs.getOrElse("preserveAspectRatio", "xMidYMid meet"), width, height)).getOrElse(Affine.Identity)
    // a `transform` on the root element applies after the viewBox mapping
    root.attrs.get("transform").flatMap(Transforms.parseOpt).filter(_.isInvertible).getOrElse(Affine.Identity) * vb

  private val images = mutable.HashMap.empty[String, Option[Either[Image, SvgDocument]]]
  private[svg] val fontCache = mutable.HashMap.empty[String, AnyRef]

  /** Renders into a new `w x h` pixmap; the document is fitted with its aspect ratio preserved and centred. */
  def render(w: Int, h: Int, background: Option[Color] = None): Pixmap =
    val pm = new Pixmap(w, h)
    background.foreach(pm.clear)
    val s = math.min(w / width, h / height)
    val base = Affine.translate(Vec2((w - width * s) / 2, (h - height * s) / 2)) * Affine.scale(s)
    new Renderer(this).renderRoot(pm, base * rootTransform)
    pm

  /** Renders at the document's natural size multiplied by `scale` (canvas size rounded up, like resvg), without any fitting. */
  def renderScaled(scale: Double, background: Option[Color] = None): Pixmap =
    val pm = new Pixmap(math.max(1, math.ceil(width * scale - 1e-9).toInt), math.max(1, math.ceil(height * scale - 1e-9).toInt))
    background.foreach(pm.clear)
    new Renderer(this).renderRoot(pm, Affine.scale(scale) * rootTransform)
    pm

  /** Loads an `<image>` target: a decoded raster image or a nested SVG document. Results are cached. */
  private[svg] def loadImage(href: String): Option[Either[Image, SvgDocument]] =
    images.getOrElseUpdate(href, {
      try
        val bytes: Option[(Array[Byte], String)] =
          if href.startsWith("data:") then
            val comma = href.indexOf(',')
            if comma < 0 then None
            else
              val meta = href.substring(5, comma); val body = href.substring(comma + 1)
              val data =
                if meta.contains(";base64") then java.util.Base64.getMimeDecoder.decode(body.filter(c => !c.isWhitespace).getBytes("ISO-8859-1"))
                else percentDecode(body)
              Some((data, meta))
          else options.resourceDir.flatMap { dir =>
            val p = java.nio.file.Paths.get(dir).resolve(href.stripPrefix("file://"))
            if java.nio.file.Files.isRegularFile(p) then Some((java.nio.file.Files.readAllBytes(p), if href.endsWith(".svg") then "image/svg+xml" else "")) else None
          }
        bytes.flatMap { (data0, meta) =>
          val data = Gzip.maybeInflate(data0)
          val head = new String(data, 0, math.min(data.length, 512), "UTF-8").trim
          if meta.contains("svg") || head.startsWith("<?xml") || head.startsWith("<svg") || head.startsWith("<!DOCTYPE") then
            Some(Right(SvgDocument.parse(new String(data, "UTF-8"), options.copy(resourceDir = None))))
          else Some(Left(Image.decode(data)))
        }
      catch case _: Exception => None
    })

  private def percentDecode(s: String): Array[Byte] =
    val out = new java.io.ByteArrayOutputStream(); var i = 0
    val b = s.getBytes("UTF-8")
    while i < b.length do
      if b(i) == '%' && i + 2 < b.length + 0 && i + 2 <= b.length - 1 + 0 then
        out.write(Integer.parseInt(new String(b, i + 1, 2, "ISO-8859-1"), 16)); i += 3
      else { out.write(b(i)); i += 1 }
    out.toByteArray

private[svg] object Gzip:
  /** Inflates a gzip stream (`.svgz`); other data is returned unchanged. */
  def maybeInflate(d: Array[Byte]): Array[Byte] =
    if d.length < 18 || (d(0) & 0xff) != 0x1f || (d(1) & 0xff) != 0x8b then d
    else
      val flags = d(3) & 0xff
      var p = 10
      if (flags & 4) != 0 then p += 2 + ((d(p) & 0xff) | ((d(p + 1) & 0xff) << 8))
      if (flags & 8) != 0 then { while d(p) != 0 do p += 1; p += 1 }
      if (flags & 16) != 0 then { while d(p) != 0 do p += 1; p += 1 }
      if (flags & 2) != 0 then p += 2
      scalaui.deflate.Zlib.inflateRaw(d, p)

object SvgDocument:
  def parse(svg: String): SvgDocument = parse(svg, SvgOptions())

  def parse(svg: String, options: SvgOptions): SvgDocument = fromElement(Xml.parse(svg), options)

  private[svg] def fromElement(raw: XmlElement, options: SvgOptions): SvgDocument =
    require(raw.name == "svg", "root element is not <svg>")
    val root = Css.apply(raw)
    val ids = mutable.HashMap.empty[String, XmlElement]
    val parents = mutable.HashMap.empty[XmlElement, XmlElement]
    def collect(e: XmlElement): Unit =
      e.attrs.get("id").foreach(id => if !ids.contains(id) then ids(id) = e)
      e.children.foreach { c => parents(c) = e; collect(c) }
    collect(root)
    val vb = Geometry.parseViewBox(root.attrs.get("viewBox"))
    def dim(k: String): Option[Double] = root.attrs.get(k).flatMap(Values.parseLen).filter(_.unit != "%").map(l => Style.length(l, 12.0, 100, 100, 'x')).filter(_ > 0)
    var w = dim("width"); var h = dim("height")
    (w, h, vb) match
      case (Some(_), None, Some(v)) => h = Some(w.get * v.height / v.width)
      case (None, Some(_), Some(v)) => w = Some(h.get * v.width / v.height)
      case _ => ()
    val width = w.orElse(vb.map(_.width)).getOrElse(100.0)
    val height = h.orElse(vb.map(_.height)).getOrElse(100.0)
    new SvgDocument(width, height, root, ids.toMap, parents.toMap, vb, options)
