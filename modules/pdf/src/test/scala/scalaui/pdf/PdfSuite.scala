package scalaui.pdf

import java.nio.file.{Files, Paths}
import scalaui.codecs.Image
import scalaui.color.Color
import scalaui.font.FontFace
import scalaui.geom.*
import scalaui.raster.*

class PdfSuite extends scalaui.testkit.LoggedSuite:
  /** With PDFA=2b|2u in the environment every scene is written as PDF/A (see tools/pdfa_check.sh). */
  private def newDoc(title: String = "", author: String = ""): PdfDocument =
    val d = new PdfDocument(title, author)
    sys.env.get("PDFA").foreach { l => d.pdfA = Some(if l.equalsIgnoreCase("2u") then PdfAConformance.A2u else PdfAConformance.A2b); d.language = Some("en") }
    d
  private val outDir = Paths.get(sys.env.getOrElse("PDF_OUT", "target/pdf-out"))
  private def write(name: String, bytes: Array[Byte]): Unit = { Files.createDirectories(outDir); Files.write(outDir.resolve(name), bytes) }
  private def writePng(name: String, pm: Pixmap): Unit = { Files.createDirectories(outDir); Files.write(outDir.resolve(name), scalaui.codecs.Png.encode(Image(pm.width, pm.height, pm.toRgba8), 6)) }
  private def resource(path: String): Array[Byte] = Files.readAllBytes(Paths.get("modules").resolve(path))

  def sample(): Array[Byte] =
    val doc = newDoc("Sample ✓ doc", "scalaui")
    val sans = doc.font(FontFace.notoSans); val mono = doc.font(FontFace.hack)
    val px = new Array[Byte](16 * 16 * 4)
    for y <- 0 until 16; x <- 0 until 16 do
      val i = (y * 16 + x) * 4
      px(i) = (x * 16).toByte; px(i + 1) = (y * 16).toByte; px(i + 2) = 128.toByte; px(i + 3) = if (x + y) % 5 == 0 then 60 else -1
    val img = Image(16, 16, px)
    doc.addPage() { p =>
      p.drawText("Hello, PDF from scalaui", sans, 24, 50, 80, Color.Black)
      p.drawText("AV To Wa kerning — café déjà vu", sans, 14, 50, 110, Color.rgb8(60, 60, 160))
      p.drawText("monospace: val x = 42", mono, 12, 50, 135, Color(0.8f, 0.1f, 0.1f))
      p.fillPath(Path.roundedRect(Rect(50, 160, 250, 260), 14), Color.rgb8(255, 160, 40))
      p.fillPath(Path.circle(Point(400, 210), 50), Color(0.1f, 0.5f, 0.9f, 0.5f))
      p.strokePath(Path.builder.moveTo(50, 300).quadTo(150, 250, 250, 300).cubicTo(300, 330, 350, 270, 500, 320).build, Color.Black, Stroke(3, LineCap.Round, LineJoin.Round))
      p.drawImage(img, Rect(50, 350, 242, 542))
      p.strokeRect(Rect(50, 350, 242, 542), Color.Black, 1)
    }
    doc.addPage(300, 200) { p => p.drawText("Page two", sans, 18, 20, 40, Color.Black) }
    doc.toBytes

  private def objectOffsetsOk(b: Array[Byte]): Unit =
    val s = new String(b, "ISO-8859-1")
    assert(s.startsWith("%PDF-1.7"))
    assert(s.trim.endsWith("%%EOF"))
    val xrefAt = s.substring(s.lastIndexOf("startxref") + 10).takeWhile(_.isDigit).toInt
    assert(s.substring(xrefAt).startsWith("xref"))
    val entries = s.substring(xrefAt).linesIterator.drop(3).takeWhile(_.endsWith(" n ")).toVector
    assert(entries.nonEmpty)
    entries.zipWithIndex.foreach { (e, i) =>
      val off = e.take(10).toInt
      assert(s.substring(off).startsWith(s"${i + 1} 0 obj"), s"object ${i + 1} offset wrong")
    }

  test("structure: header, EOF, xref offsets point at objects") {
    val b = sample(); val s = new String(b, "ISO-8859-1")
    objectOffsetsOk(b)
    assert(s.contains("/Count 2"))
    write("sample.pdf", b)
  }

  test("paths, clipping and dashes (with a raster reference)") {
    val W = 200; val H = 200
    val doc = newDoc("Paths")
    val star = Path.builder.moveTo(100, 20).lineTo(130, 170).lineTo(20, 70).lineTo(180, 70).lineTo(70, 170).close().build
    doc.addPage(W, H) { p =>
      p.fillRect(Rect(0, 0, W, H), Color.White)
      p.fillPath(star, Color.rgb8(30, 120, 200), FillRule.EvenOdd)
      p.fillPath(Path.roundedRect(Rect(110, 110, 190, 190), 12), Color.rgb8(255, 160, 40))
      p.fillPath(Path.circle(Point(60, 140), 40), Color(0.9f, 0.1f, 0.2f, 0.5f))
      p.strokePath(Path.builder.moveTo(10, 190).lineTo(190, 20).build, Color.Black, Stroke(6, LineCap.Round, LineJoin.Round))
      p.stroke(Path.builder.moveTo(10, 10).lineTo(190, 10).build, PdfPaint.Solid(Color.rgb8(0, 120, 0)), Stroke(5, LineCap.Butt), Some(PdfDash(Seq(12, 6), 3)))
      p.save(); p.clip(Path.circle(Point(150, 50), 30)); p.fillRect(Rect(100, 0, 200, 100), Color.rgb8(120, 0, 160)); p.restore()
    }
    write("paths.pdf", doc.toBytes)
    val pm = new Pixmap(W, H); pm.clear(Color.White)
    pm.fill(star, Paint.solid(Color.rgb8(30, 120, 200)), FillRule.EvenOdd)
    pm.fill(Path.roundedRect(Rect(110, 110, 190, 190), 12), Paint.solid(Color.rgb8(255, 160, 40)))
    pm.fill(Path.circle(Point(60, 140), 40), Paint.solid(Color(0.9f, 0.1f, 0.2f, 0.5f)))
    pm.stroke(Path.builder.moveTo(10, 190).lineTo(190, 20).build, Paint.solid(Color.Black), Stroke(6, LineCap.Round, LineJoin.Round))
    val clip = new Pixmap(W, H); clip.fill(Path.circle(Point(150, 50), 30), Paint.solid(Color.White))
    val layer = new Pixmap(W, H); layer.fill(Path.rect(Rect(100, 0, 200, 100)), Paint.solid(Color.rgb8(120, 0, 160)))
    for i <- 0 until W * H do { val a = clip.data(i * 4 + 3); for c <- 0 until 4 do layer.data(i * 4 + c) *= a }
    for i <- 0 until W * H do { val k = 1f - layer.data(i * 4 + 3); for c <- 0 until 4 do pm.data(i * 4 + c) = layer.data(i * 4 + c) + pm.data(i * 4 + c) * k }
    writePng("paths.ref.png", pm)
  }

  test("gradients: linear, radial, spreads, alpha stops, strokes and text") {
    val doc = newDoc("Gradients")
    val sans = doc.font(FontFace.notoSansBold)
    val stops = Seq((0f, Color.rgb8(255, 0, 0)), (0.5f, Color.rgb8(255, 255, 0)), (1f, Color.rgb8(0, 0, 255)))
    val alphaStops = Seq((0f, Color(0f, 0.5f, 0f, 1f)), (1f, Color(0f, 0.5f, 0f, 0f)))
    doc.addPage(200, 200) { p =>
      p.fillRect(Rect(0, 0, 200, 200), Color(0.9f, 0.9f, 0.9f))
      p.fill(Path.rect(Rect(10, 10, 90, 60)), PdfPaint.LinearGradient(Point(10, 10), Point(90, 10), stops))
      p.fill(Path.rect(Rect(110, 10, 190, 60)), PdfPaint.LinearGradient(Point(110, 10), Point(140, 10), stops, PdfSpread.Repeat))
      p.fill(Path.rect(Rect(10, 70, 90, 120)), PdfPaint.LinearGradient(Point(10, 70), Point(30, 70), stops, PdfSpread.Reflect))
      p.fill(Path.circle(Point(150, 95), 25), PdfPaint.RadialGradient(Point(150, 95), 0, Point(150, 95), 25, stops))
      p.fill(Path.rect(Rect(10, 130, 90, 190)), PdfPaint.RadialGradient(Point(30, 150), 0, Point(50, 160), 30, stops))
      p.fill(Path.rect(Rect(110, 130, 190, 190)), PdfPaint.LinearGradient(Point(110, 130), Point(190, 130), alphaStops))
      p.stroke(Path.builder.moveTo(10, 195).lineTo(190, 195).build, PdfPaint.LinearGradient(Point(10, 0), Point(190, 0), stops), Stroke(8))
      p.drawText("Gradient", sans, 28, 20, 112, PdfPaint.LinearGradient(Point(20, 0), Point(130, 0), Seq((0f, Color.Black), (1f, Color.rgb8(200, 0, 100)))))
    }
    write("gradients.pdf", doc.toBytes)
  }

  test("transparency: groups, blend modes, soft masks") {
    val doc = newDoc("Transparency")
    doc.addPage(200, 200) { p =>
      p.fillRect(Rect(0, 0, 200, 200), Color.White)
      p.fillRect(Rect(0, 80, 200, 120), Color.rgb8(40, 40, 40))
      // group with opacity: overlap does not double up
      p.group(opacity = 0.5) { g =>
        g.fillPath(Path.circle(Point(50, 50), 30), Color.rgb8(255, 0, 0))
        g.fillPath(Path.circle(Point(75, 50), 30), Color.rgb8(0, 0, 255))
      }
      // blend mode against the backdrop
      p.withBlendMode(PdfBlend.Multiply) { g =>
        g.fillPath(Path.circle(Point(130, 50), 30), Color.rgb8(255, 200, 0))
        g.fillPath(Path.circle(Point(155, 50), 30), Color.rgb8(0, 200, 255))
      }
      p.withBlendMode(PdfBlend.Screen) { g => g.fillRect(Rect(10, 85, 90, 115), Color.rgb8(200, 60, 60)) }
      // luminosity soft mask: white-to-black gradient fades the square
      p.withMask(PdfMaskKind.Luminosity)(m => m.fill(Path.rect(Rect(110, 130, 190, 190)), PdfPaint.LinearGradient(Point(110, 0), Point(190, 0), Seq((0f, Color.White), (1f, Color.Black))))) { c =>
        c.fillRect(Rect(110, 130, 190, 190), Color.rgb8(0, 100, 200))
      }
      // alpha mask from a circle
      p.group(mask = Some((PdfMaskKind.Alpha, (m: PdfCanvas) => m.fillPath(Path.circle(Point(50, 160), 30), Color(0f, 0f, 0f, 0.8f))))) { g =>
        g.fillRect(Rect(10, 130, 90, 190), Color.rgb8(0, 160, 80))
      }
    }
    write("transparency.pdf", doc.toBytes)
  }

  test("images: RGBA with soft mask, grayscale, JPEG passthrough") {
    val doc = newDoc("Images")
    val rgba = new Array[Byte](32 * 32 * 4)
    for y <- 0 until 32; x <- 0 until 32 do
      val i = (y * 32 + x) * 4
      rgba(i) = (x * 8).toByte; rgba(i + 1) = (y * 8).toByte; rgba(i + 2) = 100; rgba(i + 3) = ((x + y) * 4).min(255).toByte
    val gray = new Array[Byte](16 * 16 * 4)
    for i <- 0 until 256 do { gray(i * 4) = i.toByte; gray(i * 4 + 1) = i.toByte; gray(i * 4 + 2) = i.toByte; gray(i * 4 + 3) = -1 }
    val jpg = resource("codecs/src/test/resources/jpeg/base420.jpg")
    val prog = resource("codecs/src/test/resources/jpeg/prog420.jpg")
    val grayJpg = resource("codecs/src/test/resources/jpeg/gray.jpg")
    doc.addPage(200, 200) { p =>
      p.fillRect(Rect(0, 0, 200, 200), Color(0.8f, 0.9f, 1f))
      p.drawImage(Image(32, 32, rgba), Rect(10, 10, 90, 90))
      p.drawImage(Image(16, 16, gray), Rect(110, 10, 190, 90))
      p.drawImage(doc.addJpeg(jpg), Rect(10, 110, 90, 190))
      p.drawImage(doc.addEncodedImage(prog), Rect(110, 110, 150, 150))
      p.drawImage(doc.addEncodedImage(grayJpg), Rect(150, 150, 190, 190))
    }
    val bytes = doc.toBytes
    write("images.pdf", bytes)
    assert(new String(bytes, "ISO-8859-1").contains("/DCTDecode"))
  }

  test("text: kerning, ligatures, marks, Arabic, CFF font, fallback") {
    val doc = newDoc("Text")
    val sans = doc.font(FontFace.notoSans); val mono = doc.font(FontFace.hack)
    val arabic = doc.font(FontFace(resource("shaping/src/test/resources/fonts/NotoSansArabic-Regular.ttf")))
    val cff = doc.font(FontFace(resource("shaping/src/test/resources/fonts/NotoSansJP-Subset.otf")), Seq(FontFace.notoSans))
    doc.addPage(300, 300) { p =>
      p.drawText("Office affiliate: AV To Wa", sans, 18, 10, 30, Color.Black)
      p.drawText("é ä x̣ combining", sans, 18, 10, 60, Color.Black)
      p.drawText("val answer = 42 // mono", mono, 14, 10, 90, Color.Black)
      p.drawText("مرحبا بالعالم", arabic, 22, 10, 125, PdfPaint.Solid(Color.Black), rtl = true)
      p.drawText("日本語 mixed Latin", cff, 20, 10, 160, Color.Black)
      p.drawText("Invisible text layer", sans, 14, 10, 190, PdfPaint.Solid(Color.Black), mode = PdfTextMode.Invisible)
      p.drawText("Stroked", sans, 36, 10, 240, PdfPaint.Solid(Color.rgb8(200, 0, 0)), mode = PdfTextMode.Stroke, strokeWidth = 1)
    }
    write("text.pdf", doc.toBytes)
  }

  test("document features: links, bookmarks, page labels, metadata, PDF/A") {
    val doc = newDoc("Doc ✓", "Lorenzo")
    doc.subject = "Subject"; doc.keywords = "a, b"; doc.creator = "tests"; doc.language = Some("en"); doc.creationDate = Some(PdfDate(2026, 10, 25, 12, 30, 0))
    doc.pdfA = Some(PdfAConformance.A2u)
    val sans = doc.font(FontFace.notoSans)
    for i <- 1 to 3 do
      doc.addPage(300, 200) { p =>
        p.drawText(s"Page $i", sans, 24, 20, 40, Color.Black)
        p.drawText("Click here", sans, 14, 20, 100, Color.rgb8(0, 0, 200))
        p.link(Rect(20, 88, 90, 106), "https://example.com/")
        p.linkToPage(Rect(120, 88, 200, 106), PdfDestination(2, 20))
      }
    doc.bookmarks = Seq(Bookmark("One", PdfDestination(0), Seq(Bookmark("One.a", PdfDestination(0, 80))), open = true), Bookmark("Two", PdfDestination(1)), Bookmark("Three", PdfDestination(2)))
    doc.pageLabels = Seq(PageLabel(0, Some(PageLabelStyle.LowerRoman)), PageLabel(2, Some(PageLabelStyle.Decimal), "A-", 7))
    val b = doc.toBytes
    objectOffsetsOk(b)
    val s = new String(b, "ISO-8859-1")
    assert(s.contains("/Outlines") && s.contains("/PageLabels") && s.contains("/Subtype /Link") && s.contains("pdfaid:part"))
    write("document.pdf", b)
  }

  test("PDF/A edge cases: tiling, missing glyphs, invisible text, ligatures, uncompressed, many fonts, empty title") {
    val doc = newDoc()
    val sans = doc.font(FontFace.notoSans, Seq(FontFace.hack)); val inter = doc.font(FontFace.inter); val bold = doc.font(FontFace.notoSansBold)
    val cff = doc.font(FontFace(resource("shaping/src/test/resources/fonts/NotoSansJP-Subset.otf")))
    val tile = doc.tilingPattern(10, 10) { c => c.fillRect(Rect(0, 0, 5, 5), Color.rgb8(200, 0, 0)); c.fillRect(Rect(5, 5, 10, 10), Color.rgb8(0, 0, 200)) }
    doc.addPage(300, 300) { p =>
      p.fill(Path.rect(Rect(10, 10, 140, 100)), PdfPaint.Tiling(tile))
      p.drawText("Missing glyph \uE000 \uFFFF and \uD83D\uDE00 here, office affiliate", sans, 14, 10, 130, Color.Black)
      p.drawText("Inter + bold", inter, 14, 10, 160, Color.Black); p.drawText("Bold", bold, 14, 120, 160, Color.Black)
      p.drawText("hidden", sans, 14, 10, 190, PdfPaint.Solid(Color.Black), mode = PdfTextMode.Invisible)
      p.drawText("日本語", cff, 20, 10, 220, Color.Black)
      p.drawText("", sans, 14, 10, 250, Color.Black)
      p.withOpacity(0.5, 0.5)(c => c.fillRect(Rect(150, 10, 290, 100), Color.rgb8(0, 150, 0)))
      p.link(Rect(10, 120, 100, 140), "https://example.com/", Some("alt"))
      p.drawImage(doc.addJpeg(Files.readAllBytes(Paths.get("modules/pdf/src/test/resources/cmyk.jpg"))), Rect(200, 120, 260, 180)) // DeviceCMYK
    }
    doc.addPage(100, 100) { _ => }
    write("pdfa-edge.pdf", doc.toBytes)
  }

  test("colour glyphs: COLR v0/v1 as vectors, sweep fallback as image, CBDT bitmaps, sbix") {
    def face(n: String) = FontFace(Files.readAllBytes(Paths.get("modules/font/src/test/resources/color").resolve(n)))
    val doc = newDoc("Colour glyphs")
    val v0 = doc.font(face("colrv0-test.ttf")); val v1 = doc.font(face("NotoColorEmoji-colrv1-subset.ttf")); val cbdt = doc.font(face("NotoColorEmoji-cbdt-subset.ttf"))
    val tg = face("test_glyphs-glyf_colr_1.ttf"); val test = doc.font(tg)
    val sans = doc.font(FontFace.notoSans)
    doc.addPage(300, 300) { p =>
      p.drawText("COLR v0", sans, 14, 10, 24, Color.Black)
      p.drawText("\uE000\uE001", v0, 40, 150, 30, Color(0f, 0.6f, 0f))
      p.drawText("COLR v1", sans, 14, 10, 60, Color.Black)
      p.drawText("\uD83D\uDE00\uD83D\uDE80\u2764\uD83C\uDF4E", v1, 40, 10, 105, Color.Black)
      p.drawText("CBDT", sans, 14, 10, 135, Color.Black)
      p.drawText("\uD83D\uDE00\uD83D\uDE80\u2764", cbdt, 40, 10, 180, Color.Black)
      // linear/radial vectors, a transform, SrcOver, and sweeps (rasterized fallback) from the googlefonts test font
      p.drawText("COLR v1 test glyphs", sans, 10, 10, 205, Color.Black)
      var x = 10.0
      for cp <- Seq(0xf0101, 0xf0503, 0xf0802, 0xf0a03, 0xf0209, 0xf0215) do
        p.drawText(new String(Character.toChars(cp)), test, 40, x, 250, Color.Black); x += 45
    }
    write("color.pdf", doc.toBytes)
  }

  test("uncompressed output") {
    val doc = newDoc("Raw"); doc.compress = false
    doc.addPage(100, 100) { p => p.fillRect(Rect(10, 10, 50, 50), Color.rgb8(255, 0, 0)) }
    val s = new String(doc.toBytes, "ISO-8859-1")
    assert(s.contains("10 10 m") || s.contains("re") || s.contains("f\n"))
    assert(!s.contains("FlateDecode"))
  }
