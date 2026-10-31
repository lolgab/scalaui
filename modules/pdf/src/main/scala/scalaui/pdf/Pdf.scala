package scalaui.pdf

import scalaui.codecs.Image
import scalaui.font.FontFace
import scalaui.geom.*
import java.io.ByteArrayOutputStream
import scala.collection.mutable

/** A destination inside the document, used by links and bookmarks. `y` is measured from the top of the page in points. */
final case class PdfDestination(page: Int, y: Double = 0.0)

/** A bookmark (outline entry); `open` shows its children expanded in the viewer. */
final case class Bookmark(title: String, dest: PdfDestination, children: Seq[Bookmark] = Nil, open: Boolean = false)

enum PageLabelStyle(val code: String):
  case Decimal extends PageLabelStyle("D")
  case UpperRoman extends PageLabelStyle("R")
  case LowerRoman extends PageLabelStyle("r")
  case UpperAlpha extends PageLabelStyle("A")
  case LowerAlpha extends PageLabelStyle("a")

/** From page index `startPage` on, pages are labelled `prefix` + number in `style` (the first one numbered `firstNumber`). */
final case class PageLabel(startPage: Int, style: Option[PageLabelStyle] = Some(PageLabelStyle.Decimal), prefix: String = "", firstNumber: Int = 1)

/** Date/time with a UTC offset, written as a PDF date string and in XMP. */
final case class PdfDate(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0, second: Int = 0, offsetMinutes: Int = 0):
  private def off(sep: String) = if offsetMinutes == 0 then "Z" else f"${if offsetMinutes < 0 then "-" else "+"}${math.abs(offsetMinutes) / 60}%02d$sep${math.abs(offsetMinutes) % 60}%02d"
  def pdf: String = f"D:$year%04d$month%02d$day%02d$hour%02d$minute%02d$second%02d" + (if offsetMinutes == 0 then "Z" else off("'") + "'")
  def iso: String = f"$year%04d-$month%02d-$day%02dT$hour%02d:$minute%02d:$second%02d" + off(":")
object PdfDate:
  /** Current UTC time. */
  def now: PdfDate =
    val secs = System.currentTimeMillis() / 1000; val days = Math.floorDiv(secs, 86400L); val rem = Math.floorMod(secs, 86400L)
    // civil-from-days (Howard Hinnant)
    val z = days + 719468; val era = Math.floorDiv(z, 146097L); val doe = z - era * 146097
    val yoe = (doe - doe / 1460 + doe / 36524 - doe / 146096) / 365; val y0 = yoe + era * 400
    val doy = doe - (365 * yoe + yoe / 4 - yoe / 100); val mp = (5 * doy + 2) / 153
    val d = doy - (153 * mp + 2) / 5 + 1; val m = if mp < 10 then mp + 3 else mp - 9
    PdfDate((if m <= 2 then y0 + 1 else y0).toInt, m.toInt, d.toInt, (rem / 3600).toInt, ((rem % 3600) / 60).toInt, (rem % 60).toInt)

/** PDF/A conformance levels written into the XMP metadata (with an sRGB output intent). */
enum PdfAConformance(val part: Int, val level: String):
  case A2b extends PdfAConformance(2, "B")
  case A2u extends PdfAConformance(2, "U")

private enum Annotation:
  case Uri(rect: Rect, uri: String, contents: Option[String])
  case GoTo(rect: Rect, dest: PdfDestination)

/** Drawing surface for one page. Coordinates are points with the origin at the top-left and y pointing down. */
final class PdfPage private[pdf] (width: Double, height: Double, doc: PdfDocument)
    extends PdfCanvas(doc, width, height, Affine(1, 0, 0, -1, 0, height), Rect(0, 0, width, height)):
  private[pdf] val annots = mutable.ArrayBuffer.empty[Annotation]
  /** A clickable area opening `uri`. */
  def link(area: Rect, uri: String, altText: Option[String] = None): Unit = annots += Annotation.Uri(area, uri, altText)
  /** A clickable area jumping to a place in this document. */
  def linkToPage(area: Rect, dest: PdfDestination): Unit = annots += Annotation.GoTo(area, dest)

/** Creates PDF 1.7 documents: vector paths with solid/gradient/pattern paints, clipping, transparency groups, soft masks and blend modes, text
  * (subset-embedded TrueType and CFF fonts, shaped with GSUB/GPOS, ToUnicode for extraction), images (Flate with alpha, or JPEG passthrough),
  * links, bookmarks, page labels, metadata and optional PDF/A-2 markers.
  */
final class PdfDocument(var title: String = "", var author: String = ""):
  var subject: String = ""
  var keywords: String = ""
  var creator: String = ""
  var producer: String = "scalaui"
  var language: Option[String] = None
  var creationDate: Option[PdfDate] = None
  var modificationDate: Option[PdfDate] = None
  /** Write an XMP metadata stream (implied by `pdfA`). */
  var xmp: Boolean = false
  var pdfA: Option[PdfAConformance] = None
  /** Flate-compress streams with the deflate module. */
  var compress: Boolean = true
  var bookmarks: Seq[Bookmark] = Nil
  var pageLabels: Seq[PageLabel] = Nil

  private val pages = mutable.ArrayBuffer.empty[PdfPage]
  private val fontList = mutable.ArrayBuffer.empty[PdfFont]
  private val imageList = mutable.ArrayBuffer.empty[(AnyRef, PdfImage)]
  private val pending = mutable.ArrayBuffer.empty[(Int, (Int, EmitCtx) => Unit)]
  private val cache = mutable.HashMap.empty[String, Int]
  private var lastId = 0
  /** Per-document cache of colour glyph images (see PdfColorGlyphs). */
  private[pdf] val colorCache = mutable.HashMap.empty[AnyRef, Any]

  /** Reserves an object number whose body is produced when the document is serialized. */
  private[pdf] def register(emit: (Int, EmitCtx) => Unit): Int = { lastId += 1; pending += ((lastId, emit)); lastId }
  /** Like `register`, but identical keys share one object. */
  private[pdf] def cached(key: String)(emit: (Int, EmitCtx) => Unit): Int = cache.getOrElseUpdate(key, register(emit))

  /** Registers `face` (with optional per-character fallback faces) for use with `drawText`. */
  def font(face: FontFace, fallbacks: Seq[FontFace] = Nil): PdfFont =
    fontList.find(f => (f.face eq face) && f.fallbacks.size == fallbacks.size && f.fallbacks.zip(fallbacks).forall(_ eq _)).getOrElse {
      var created: PdfFont = null
      val id = register { (_, ctx) => if created.used.isEmpty then ctx.set(created.id, Fmt.bytes("null")) else { created.pdfA = pdfA.isDefined; PdfFonts.emit(created, ctx) } }
      created = new PdfFont(face, fontList.size + 1, fallbacks, id); fontList += created; created
    }
  private[pdf] def fontFor(face: FontFace): PdfFont = fontList.find(f => (f.face eq face) && f.fallbacks.isEmpty).getOrElse(font(face))

  /** Registers decoded pixels (Flate; gray detected; `SMask` when any alpha is not opaque). */
  def addImage(img: Image): PdfImage =
    imageList.find(_._1 eq img).map(_._2).getOrElse {
      val hasAlpha = (0 until img.width * img.height).exists(i => img.rgba(i * 4 + 3) != -1)
      var self: PdfImage = null
      val id = register { (id, ctx) =>
        val (color, alpha, gray) = ImageData.rgbAndAlpha(img)
        val smask = alpha.map(a => ctx.stream(s"/Type /XObject /Subtype /Image /Width ${img.width} /Height ${img.height} /ColorSpace /DeviceGray /BitsPerComponent 8", a))
        ctx.set(id, Fmt.stream(s"/Type /XObject /Subtype /Image /Width ${img.width} /Height ${img.height} /ColorSpace ${if gray then "/DeviceGray" else "/DeviceRGB"} /BitsPerComponent 8" +
          smask.map(m => s" /SMask $m 0 R").getOrElse(""), color, compress))
        val _ = self
      }
      self = new PdfImage(id, img.width, img.height, hasAlpha); imageList += ((img, self)); self
    }

  /** Registers a JPEG file as-is (`DCTDecode`, no re-encoding). Baseline and progressive, gray/RGB/CMYK (Adobe-inverted CMYK handled). */
  def addJpeg(data: Array[Byte]): PdfImage =
    val info = ImageData.jpegInfo(data).getOrElse(throw new IllegalArgumentException("not a JPEG image"))
    val cs = info.components match { case 1 => "/DeviceGray"; case 4 => "/DeviceCMYK"; case _ => "/DeviceRGB" }
    val decode = if info.components == 4 && info.adobeTransform.isDefined then " /Decode [1 0 1 0 1 0 1 0]" else ""
    val id = register { (id, ctx) =>
      if info.components == 4 && pdfA.isDefined then
        // PDF/A: DeviceCMYK needs a CMYK output intent, which is not written; the image is converted to RGB instead
        val img = Image.decode(data); val (color, _, gray) = ImageData.rgbAndAlpha(img)
        ctx.set(id, Fmt.stream(s"/Type /XObject /Subtype /Image /Width ${img.width} /Height ${img.height} /ColorSpace ${if gray then "/DeviceGray" else "/DeviceRGB"} /BitsPerComponent 8", color, compress))
      else ctx.set(id, Fmt.stream(s"/Type /XObject /Subtype /Image /Width ${info.width} /Height ${info.height} /ColorSpace $cs /BitsPerComponent ${info.bits} /Filter /DCTDecode$decode", data, compress = false))
    }
    new PdfImage(id, info.width, info.height, hasAlpha = false)

  /** Registers an encoded image: JPEG is passed through, anything else is decoded by the codecs module. */
  def addEncodedImage(data: Array[Byte]): PdfImage =
    if ImageData.jpegInfo(data).exists(i => i.bits == 8 && Set(1, 3, 4).contains(i.components)) then addJpeg(data) else addImage(Image.decode(data))

  /** Records a tiling pattern cell of `width x height` (y down); use it with [[PdfPaint.Tiling]]. */
  def tilingPattern(width: Double, height: Double, xStep: Double = 0, yStep: Double = 0)(draw: PdfCanvas => Unit): PdfTilingPattern =
    val c = new PdfCanvas(this, width, height, Affine.Identity, Rect(0, 0, width, height))
    draw(c)
    new PdfTilingPattern(c, width, height, if xStep == 0 then width else xStep, if yStep == 0 then height else yStep)

  private[pdf] def addForm(c: PdfCanvas, group: Boolean, bbox: Rect, knockout: Boolean = false): Int =
    register { (id, ctx) =>
      val box = bbox.normalized
      val grp = if group then s" /Group << /S /Transparency /I true /K $knockout /CS /DeviceRGB >>" else ""
      ctx.set(id, Fmt.stream(s"/Type /XObject /Subtype /Form /BBox [${Fmt.n(box.x0)} ${Fmt.n(box.y0)} ${Fmt.n(box.x1)} ${Fmt.n(box.y1)}] /Resources ${c.resourceDict}$grp",
        Fmt.bytes(c.content.toString), ctx.compress))
    }

  /** Adds a page of `width x height` points (A4 = 595 x 842) and runs `draw` on it. */
  def addPage(width: Double = 595, height: Double = 842)(draw: PdfPage => Unit): this.type = { draw(newPage(width, height)); this }
  /** Adds an empty page and returns it for drawing. */
  def newPage(width: Double = 595, height: Double = 842): PdfPage = { val p = new PdfPage(width, height, this); pages += p; p }
  def pageCount: Int = pages.size

  private def xmpPacket(): Array[Byte] =
    def esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    val sb = new StringBuilder("<?xpacket begin=\"﻿\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>\n<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">\n")
    sb ++= "<rdf:Description rdf:about=\"\" xmlns:dc=\"http://purl.org/dc/elements/1.1/\" xmlns:xmp=\"http://ns.adobe.com/xap/1.0/\" xmlns:pdf=\"http://ns.adobe.com/pdf/1.3/\" xmlns:pdfaid=\"http://www.aiim.org/pdfa/ns/id/\">\n"
    if title.nonEmpty then sb ++= s"<dc:title><rdf:Alt><rdf:li xml:lang=\"x-default\">${esc(title)}</rdf:li></rdf:Alt></dc:title>\n"
    if author.nonEmpty then sb ++= s"<dc:creator><rdf:Seq><rdf:li>${esc(author)}</rdf:li></rdf:Seq></dc:creator>\n"
    if subject.nonEmpty then sb ++= s"<dc:description><rdf:Alt><rdf:li xml:lang=\"x-default\">${esc(subject)}</rdf:li></rdf:Alt></dc:description>\n"
    if keywords.nonEmpty then sb ++= s"<pdf:Keywords>${esc(keywords)}</pdf:Keywords>\n"
    sb ++= s"<pdf:Producer>${esc(producer)}</pdf:Producer>\n"
    if creator.nonEmpty then sb ++= s"<xmp:CreatorTool>${esc(creator)}</xmp:CreatorTool>\n"
    creationDate.foreach(d => sb ++= s"<xmp:CreateDate>${d.iso}</xmp:CreateDate>\n")
    modificationDate.orElse(creationDate).foreach(d => sb ++= s"<xmp:ModifyDate>${d.iso}</xmp:ModifyDate>\n")
    pdfA.foreach(a => sb ++= s"<pdfaid:part>${a.part}</pdfaid:part>\n<pdfaid:conformance>${a.level}</pdfaid:conformance>\n")
    sb ++= "</rdf:Description>\n</rdf:RDF></x:xmpmeta>\n<?xpacket end=\"w\"?>"
    sb.toString.getBytes("UTF-8")

  def toBytes: Array[Byte] =
    val ctx = new EmitCtx(lastId + 1, compress)
    pending.foreach((id, emit) => emit(id, ctx))
    def str(s: String): Array[Byte] = Fmt.bytes(s)

    val catalog = ctx.reserve(); val pagesId = ctx.reserve()
    val pageIds = pages.map(_ => ctx.reserve()).toVector
    def destOf(d: PdfDestination): String =
      val pi = math.max(0, math.min(d.page, pages.size - 1))
      s"[${pageIds(pi)} 0 R /XYZ 0 ${Fmt.n(pages(pi).height - d.y)} null]"
    pages.zipWithIndex.foreach { (p, i) =>
      val c = ctx.stream("", str(s"1 0 0 -1 0 ${Fmt.n(p.height)} cm\n" + p.content.toString))
      val annots = p.annots.map { a =>
        def rect(r: Rect) = s"[${Fmt.n(r.x0)} ${Fmt.n(p.height - r.y1)} ${Fmt.n(r.x1)} ${Fmt.n(p.height - r.y0)}]"
        a match
          case Annotation.Uri(r, uri, alt) => ctx.add(str(s"<< /Type /Annot /Subtype /Link /Rect ${rect(r)} /Border [0 0 0] /F 4 ${alt.map(t => s"/Contents ${Fmt.text(t)} ").getOrElse("")}/A << /S /URI /URI ${Fmt.text(uri)} >> >>"))
          case Annotation.GoTo(r, d) => ctx.add(str(s"<< /Type /Annot /Subtype /Link /Rect ${rect(r)} /Border [0 0 0] /F 4 /Dest ${destOf(d)} >>"))
      }
      val grp = if pdfA.isDefined then " /Group << /S /Transparency /CS /DeviceRGB /I true >>" else ""
      ctx.set(pageIds(i), str(s"<< /Type /Page /Parent $pagesId 0 R /MediaBox [0 0 ${Fmt.n(p.width)} ${Fmt.n(p.height)}] /Contents $c 0 R /Resources ${p.resourceDict}" +
        (if annots.nonEmpty then s" /Annots [${annots.map(a => s"$a 0 R").mkString(" ")}]" else "") + grp + " >>"))
    }
    ctx.set(pagesId, str(s"<< /Type /Pages /Kids [${pageIds.map(i => s"$i 0 R").mkString(" ")}] /Count ${pageIds.size} >>"))

    // bookmarks
    var outlinesId = 0
    if bookmarks.nonEmpty && pages.nonEmpty then
      outlinesId = ctx.reserve()
      def visible(b: Bookmark): Int = 1 + (if b.open then b.children.map(visible).sum else 0)
      def build(items: Seq[Bookmark], parent: Int): Seq[Int] =
        val ids = items.map(_ => ctx.reserve())
        items.zip(ids).zipWithIndex.foreach { case ((b, id), i) =>
          val kids = build(b.children, id)
          val sb = new StringBuilder(s"<< /Title ${Fmt.text(b.title)} /Parent $parent 0 R /Dest ${destOf(b.dest)}")
          if i > 0 then sb ++= s" /Prev ${ids(i - 1)} 0 R"
          if i < ids.size - 1 then sb ++= s" /Next ${ids(i + 1)} 0 R"
          if kids.nonEmpty then
            val count = b.children.map(visible).sum
            sb ++= s" /First ${kids.head} 0 R /Last ${kids.last} 0 R /Count ${if b.open then count else -count}"
          sb ++= " >>"
          ctx.set(id, str(sb.toString))
        }
        ids
      val top = build(bookmarks, outlinesId)
      ctx.set(outlinesId, str(s"<< /Type /Outlines /First ${top.head} 0 R /Last ${top.last} 0 R /Count ${bookmarks.map(visible).sum} >>"))

    val labels =
      if pageLabels.isEmpty then ""
      else " /PageLabels << /Nums [" + pageLabels.sortBy(_.startPage).map { l =>
        s"${l.startPage} << ${l.style.map(s => s"/S /${s.code} ").getOrElse("")}${if l.prefix.nonEmpty then s"/P ${Fmt.text(l.prefix)} " else ""}/St ${l.firstNumber} >>"
      }.mkString(" ") + "] >>"

    val metadataId = if xmp || pdfA.isDefined then ctx.stream("/Type /Metadata /Subtype /XML", xmpPacket(), compress = false) else 0
    val outputIntent = pdfA.map { _ =>
      val icc = ctx.stream("/N 3", scalaui.cms.Profiles.sRGB.bytes)
      s" /OutputIntents [<< /Type /OutputIntent /S /GTS_PDFA1 /OutputConditionIdentifier (sRGB IEC61966-2.1) /Info (sRGB IEC61966-2.1) /DestOutputProfile $icc 0 R >>]"
    }.getOrElse("")
    ctx.set(catalog, str(s"<< /Type /Catalog /Pages $pagesId 0 R" + (if outlinesId != 0 then s" /Outlines $outlinesId 0 R /PageMode /UseOutlines" else "") + labels +
      (if metadataId != 0 then s" /Metadata $metadataId 0 R" else "") + language.map(l => s" /Lang ${Fmt.text(l)}").getOrElse("") +
      (if title.nonEmpty then " /ViewerPreferences << /DisplayDocTitle true >>" else "") + outputIntent + " >>"))

    val info = new StringBuilder("<< /Producer " + Fmt.text(producer))
    if title.nonEmpty then info ++= " /Title " + Fmt.text(title)
    if author.nonEmpty then info ++= " /Author " + Fmt.text(author)
    if subject.nonEmpty then info ++= " /Subject " + Fmt.text(subject)
    if keywords.nonEmpty then info ++= " /Keywords " + Fmt.text(keywords)
    if creator.nonEmpty then info ++= " /Creator " + Fmt.text(creator)
    creationDate.foreach(d => info ++= " /CreationDate (" + d.pdf + ")")
    modificationDate.orElse(creationDate).foreach(d => info ++= " /ModDate (" + d.pdf + ")")
    info ++= " >>"
    val infoId = ctx.add(str(info.toString))

    val out = new ByteArrayOutputStream()
    out.write(Array[Byte]('%', 'P', 'D', 'F', '-', '1', '.', '7', '\n', '%', 0xe2.toByte, 0xe3.toByte, 0xcf.toByte, 0xd3.toByte, '\n'))
    val objs = ctx.slots
    val offsets = new Array[Int](objs.size)
    var h1 = 0xcbf29ce484222325L; var h2 = 0x100000001b3L
    objs.zipWithIndex.foreach { (b, i) =>
      offsets(i) = out.size()
      val body = if b == null then str("null") else b
      out.write(str(s"${i + 1} 0 obj\n")); out.write(body); out.write(str("\nendobj\n"))
      var k = 0; while k < body.length do { h1 = (h1 ^ (body(k) & 0xff)) * 0x100000001b3L; h2 = (h2 + (body(k) & 0xff)) * 0x9e3779b97f4a7c15L; k += 1 }
    }
    val docId = f"$h1%016X$h2%016X"
    val xref = out.size()
    out.write(str(s"xref\n0 ${objs.size + 1}\n0000000000 65535 f \n"))
    offsets.foreach(o => out.write(str(f"$o%010d 00000 n \n")))
    out.write(str(s"trailer\n<< /Size ${objs.size + 1} /Root $catalog 0 R /Info $infoId 0 R /ID [<$docId> <$docId>] >>\nstartxref\n$xref\n%%EOF\n"))
    out.toByteArray
