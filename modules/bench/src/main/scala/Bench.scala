import scalaui.codecs.*
import scalaui.color.Color
import scalaui.geom.*
import scalaui.gpu.*
import scalaui.imaging.*
import scalaui.raster.*
import scalaui.shaping.Shaper
import scalaui.text.*

/** Micro benchmarks: `sbt --server -J-Xmx8g "bench/run"` (optionally with case-name filters as arguments).
  * Each line prints best-of-N milliseconds and a checksum of the output, so an optimization can be checked to leave output unchanged.
  */
object Bench:
  private var filters = Seq.empty[String]
  /** `f` returns the output (a float/byte array or an Int); the checksum is computed outside the timed region. */
  private def time(name: String, iters: Int = 5)(f: () => Any): Unit =
    if filters.isEmpty || filters.exists(name.contains) then
      var out = f() // warm-up
      var best = Long.MaxValue
      var i = 0
      val n = sys.env.get("BENCH_ITERS").map(_.toInt).getOrElse(iters)
      while i < n do
        val t0 = System.nanoTime(); out = f(); best = math.min(best, System.nanoTime() - t0); i += 1
      println(f"$name%-34s ${best / 1e6}%9.2f ms   checksum ${checksum(out)}%d")

  // same value as java.util.Arrays.hashCode (whose Scala Native implementation is generic and slow)
  private def checksum(o: Any): Int = o match
    case a: Array[Float] => var r = 1; var i = 0; while i < a.length do { r = 31 * r + java.lang.Float.floatToIntBits(a(i)); i += 1 }; r
    case a: Array[Byte] => var r = 1; var i = 0; while i < a.length do { r = 31 * r + a(i); i += 1 }; r
    case n: Int => n
    case _ => 0
  private def h(a: Array[Float]): Array[Float] = a
  private def hb(a: Array[Byte]): Array[Byte] = a

  private def star(cx: Double, cy: Double, r: Double, n: Int): Path =
    val b = Path.builder
    for i <- 0 until n do
      val a = i * 2 * math.Pi * 2 / n; val rr = if i % 2 == 0 then r else r * 0.9
      val x = cx + rr * math.cos(a); val y = cy + rr * math.sin(a)
      if i == 0 then b.moveTo(x, y) else b.lineTo(x, y)
    b.close().build

  private def curvy(cx: Double, cy: Double, r: Double, n: Int): Path =
    val b = Path.builder.moveTo(cx + r, cy)
    for i <- 1 to n do
      val a = i * 2 * math.Pi / n; val rr = r * (1 + 0.2 * math.sin(a * 7))
      b.cubicTo(cx + rr * math.cos(a - 0.1) * 1.05, cy + rr * math.sin(a - 0.1) * 1.05, cx + rr * math.cos(a - 0.05), cy + rr * math.sin(a - 0.05), cx + rr * math.cos(a), cy + rr * math.sin(a))
    b.close().build

  private def synthImage(w: Int, hh: Int): Image =
    val d = new Array[Byte](w * hh * 4)
    var y = 0
    while y < hh do
      var x = 0
      while x < w do
        val i = (y * w + x) * 4
        d(i) = ((x * 255 / w) ^ ((y >> 3) & 31)).toByte; d(i + 1) = ((y * 255 / hh) + (x >> 4 & 15)).toByte
        d(i + 2) = (128 + 100 * math.sin(x * 0.05) * math.cos(y * 0.04)).toInt.toByte; d(i + 3) = -1
        x += 1
      y += 1
    Image(w, hh, d)

  private def pixmapOf(w: Int, hh: Int): Pixmap =
    val pm = new Pixmap(w, hh); pm.clear(Color(0.2f, 0.3f, 0.4f, 1f))
    pm.fill(curvy(w / 2.0, hh / 2.0, hh / 3.0, 40), Paint.solid(Color(1, 0.5f, 0, 1)))
    pm

  private lazy val shared = new Pixmap(1920, 1080)
  /** A zeroed 1080p pixmap (reused, so allocation and page faults do not pollute the timings). */
  private def fresh(): Pixmap = { val d = shared.data; var i = 0; while i < d.length do { d(i) = 0f; i += 1 }; shared }

  def main(args: Array[String]): Unit =
    filters = args.toSeq
    val W = 1920; val H = 1080

    // --- raster ---
    lazy val stars = (0 until 20).map(i => star(200 + i * 80, 540, 400, 301)).toVector
    time("raster.fill star x20 (1080p)", 3) { () =>
      val pm = fresh(); stars.foreach(p => pm.fill(p, Paint.solid(Color(0, 0.5f, 1, 0.3f)), FillRule.EvenOdd)); h(pm.data) }
    lazy val blobs = (0 until 30).map(i => curvy(150 + (i % 10) * 180, 200 + (i / 10) * 300, 140, 60)).toVector
    time("raster.fill curvy x30", 3) { () =>
      val pm = fresh(); blobs.foreach(p => pm.fill(p, Paint.solid(Color(1, 0, 0, 0.5f)))); h(pm.data) }
    time("raster.stroke curvy x30", 3) { () =>
      val pm = fresh(); blobs.foreach(p => pm.stroke(p, Paint.solid(Color.White), Stroke(3, LineCap.Round, LineJoin.Round))); h(pm.data) }
    lazy val big = Path.rect(Rect(0, 0, W, H))
    time("raster.fill linear gradient 1080p", 3) { () =>
      val pm = fresh()
      pm.fill(big, Paint.LinearGradient(Point(0, 0), Point(W, H), Seq((0f, Color(1, 0, 0, 1)), (0.5f, Color(0, 1, 0, 1)), (1f, Color(0, 0, 1, 1))))); h(pm.data) }
    time("raster.fill radial gradient 1080p", 3) { () =>
      val pm = fresh()
      pm.fill(big, Paint.RadialGradient(Point(W / 2.0, H / 2.0), 900, Seq((0f, Color(1, 1, 0, 1)), (1f, Color(0.5f, 0, 0.5f, 1f))))); h(pm.data) }
    time("raster.fill solid rect 1080p", 5) { () =>
      val pm = fresh(); pm.fill(big, Paint.solid(Color(0.3f, 0.6f, 0.9f, 0.5f))); h(pm.data) }
    time("pixmap.toRgba8 1080p", 5) { () => hb(pixmapOf(W, H).toRgba8) }
    lazy val pmB = pixmapOf(W, H)
    time("pixmap.toRgba8 (conv only)", 5) { () => hb(pmB.toRgba8) }

    // --- imaging ---
    lazy val pm1 = pixmapOf(1280, 720)
    time("filters.gaussianBlur 720p s=8", 3) { () => h(Filters.gaussianBlur(pm1, 8).data) }
    time("filters.gaussianBlurApprox 720p s=8", 3) { () => h(Filters.gaussianBlurApprox(pm1, 8).data) }
    time("filters.gaussianBlur 720p s=2", 3) { () => h(Filters.gaussianBlur(pm1, 2).data) }
    time("filters.convolve3 720p", 3) { () => h(Filters.convolve3(pm1, Filters.Sharpen).data) }
    time("filters.grayscale 720p (mapRgb)", 3) { () => val c = Pixmaps.copy(pm1); Filters.grayscale(c); h(c.data) }
    time("filters.saturation 720p", 3) { () => val c = Pixmaps.copy(pm1); Filters.saturation(c, 1.3f); h(c.data) }
    time("blend.composite normal 720p", 3) { () => val c = Pixmaps.copy(pm1); Blend.composite(c, pm1, 0, 0, BlendMode.Normal, 0.5f); h(c.data) }
    time("blend.composite multiply 720p", 3) { () => val c = Pixmaps.copy(pm1); Blend.composite(c, pm1, 0, 0, BlendMode.Multiply, 0.5f); h(c.data) }
    time("blend.composite hue 720p", 3) { () => val c = Pixmaps.copy(pm1); Blend.composite(c, pm1, 0, 0, BlendMode.Hue, 0.5f); h(c.data) }
    time("pixmaps.fromImage+toImage 1280x720", 3) { () => hb(Pixmaps.toImage(Pixmaps.fromImage(img)).rgba) }
    time("resample.lanczos 1280x720->640x360", 3) { () => h(Resample.resize(pm1, 640, 360).data) }
    time("resample.lanczos 1280x720->1920x1080", 3) { () => h(Resample.resize(pm1, 1920, 1080).data) }
    time("resample.bilinear 1920x1080->480x270", 3) { () => h(Resample.resize(pmB, 480, 270, ResizeFilter.Bilinear).data) }

    // --- codecs ---
    lazy val img = synthImage(1280, 720)
    lazy val jpg = JpegEncoder.encode(img, 85)
    lazy val jpgNoSub = JpegEncoder.encode(img, 85, subsample = false)
    lazy val png = Png.encode(img)
    lazy val webpLl = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("modules/codecs/src/test/resources/webp/lossless_big_z6.webp"))
    time("jpeg.decode 1280x720 420", 5) { () => hb(Jpeg.decode(jpg).rgba) }
    time("jpeg.decode 1280x720 444", 5) { () => hb(Jpeg.decode(jpgNoSub).rgba) }
    time("jpeg.encode 1280x720", 3) { () => hb(JpegEncoder.encode(img, 85)) }
    time("png.decode 1280x720", 5) { () => hb(Png.decode(png).rgba) }
    time("png.encode 1280x720", 3) { () => hb(Png.encode(img)) }
    time("webp.lossless decode fixture big_z6", 5) { () => hb(Webp.decode(webpLl).rgba) }
    time("webp.lossless encode 1280x720", 3) { () => hb(WebpEncoder.encode(img)) }
    if filters.contains("rt-debug") then
      def tryRt(name: String, im: Image): Unit =
        val r = scala.util.Try(Webp.decode(WebpEncoder.encode(im)))
        println(s"$name: " + (r match { case scala.util.Success(d) => if java.util.Arrays.equals(d.rgba, im.rgba) then "ok" else "MISMATCH"; case scala.util.Failure(e) => "FAIL " + e.getMessage }))
      for (w0, h0) <- Seq((1, 1), (4, 4), (16, 16), (64, 64), (200, 100), (1280, 720)) do tryRt(s"synth ${w0}x$h0", synthImage(w0, h0))
      val rnd = new scala.util.Random(1)
      tryRt("noise 64x64", Image(64, 64, Array.tabulate(64 * 64 * 4)(i => if i % 4 == 3 then -1 else rnd.nextInt(256).toByte)))
      tryRt("flat 64x64", Image(64, 64, Array.tabulate(64 * 64 * 4)(i => if i % 4 == 3 then -1 else 7)))
    time("webp.lossless roundtrip 1280x720", 1) { () =>
      scala.util.Try(Webp.decode(WebpEncoder.encode(img))) match
        case scala.util.Success(dec) => if !java.util.Arrays.equals(dec.rgba, img.rgba) then println("  ROUNDTRIP MISMATCH"); hb(dec.rgba)
        case scala.util.Failure(e) => println("  ROUNDTRIP FAILED: " + e.getMessage); 0 }
    val webpDir = new java.io.File("modules/codecs/src/test/resources/webp")
    val lossyFiles = Option(webpDir.listFiles()).toSeq.flatten.filter(f => f.getName.endsWith(".webp") && f.getName.contains("lossy")).sortBy(_.length)
    lossyFiles.lastOption.foreach { f =>
      val data = java.nio.file.Files.readAllBytes(f.toPath)
      time(s"webp.lossy decode ${f.getName}", 10) { () => hb(Webp.decode(data).rgba) }
    }

    // --- text ---
    lazy val shaper = new Shaper(scalaui.font.FontFace.notoSans)
    val words = Array("The", "quick", "brown", "fox", "jumps", "over", "the", "lazy", "dog", "typography", "AVATAR", "office", "fi", "waffle", "Lorem", "ipsum")
    lazy val para = (0 until 4000).map(i => words((i * 7 + i / 3) % words.length)).mkString(" ")
    time(s"text.layout ${para.length} chars wrap", 3) { () =>
      val l = TextLayout.layout(para, shaper, 16, Some(700)); l.lines.size * 31 + l.lines.map(_.glyphs.size).sum }
    var uniq = 0
    time("text.layout cold x200 short labels", 3) { () =>
      var s = 0
      var i = 0
      while i < 200 do { uniq += 1; s += TextLayout.layout("Button label " + uniq, shaper, 14).lines.head.glyphs.size; i += 1 }
      s }
    time("text.layout repeated x200 (cache)", 3) { () =>
      var s = 0
      var i = 0
      while i < 200 do { s += TextLayout.layout("Button label " + (i % 10), shaper, 14).lines.head.glyphs.size; i += 1 }
      s }
    lazy val hb0 = new HeadlessBackend
    lazy val atlasLayout = TextLayout.layout(para.take(6000), shaper, 16, Some(700))
    time("glyphatlas cold (fresh atlas)", 3) { () =>
      val atlas = new GlyphAtlas(hb0); var s = 0
      for sz <- Seq(12.0, 16.0, 24.0, 40.0); c <- ('A' to 'z') ++ ('0' to '9') do
        val gi = scalaui.font.FontFace.notoSans.glyphIndex(c)
        atlas.get(scalaui.font.FontFace.notoSans, gi, sz).foreach(g => s += g.w * g.h)
      s }
    lazy val atlas = new GlyphAtlas(hb0)
    lazy val renderer = new TextRenderer(atlas)
    val white = hb0.whiteTexture
    def frameWithText(w: Int, hh: Int, scale: Double): Frame =
      val cv = new Canvas(white); cv.begin(w, hh, scale, Color(0.1f, 0.1f, 0.12f, 1f))
      var y = 10.0
      while y < hh - 20 do
        cv.fillRoundedRect(Rect(10, y, w - 10, y + 40), 6, Color(0.2f, 0.25f, 0.35f, 1f))
        y += 48
      cv.fillRectGradient(Rect(0, 0, w, hh / 4.0), Color(1, 0, 0, 0.3f), Color(0, 1, 0, 0.3f), Color(0, 0, 1, 0.3f), Color(1, 1, 0, 0.3f))
      renderer.draw(cv, atlasLayout, Point(20, 20), Color.White)
      renderer.draw(cv, atlasLayout, Point(w / 2.0, 20), Color(0.8f, 0.9f, 1f, 1f))
      for i <- 0 until 40 do cv.fillCircle(Point(100 + i * 40.0, hh / 2.0), 30, Color(1, 0.4f, 0.1f, 0.5f))
      cv.finish()
    time("canvas.build text frame 1080p", 3) { () => frameWithText(W, H, 1.0).vertices.length }
    lazy val f1080 = frameWithText(W, H, 1.0)
    time("SoftwareRenderer 1080p", 3) { () => hb(SoftwareRenderer.render(f1080)._1) }
    lazy val f4k = frameWithText(1920, 1080, 2.0)
    time("SoftwareRenderer 4K (1080@2x)", 3) { () => hb(SoftwareRenderer.render(f4k)._1) }
    lazy val target1080 = new Array[Byte](W * H * 4)
    time("SoftwareRenderer 1080p damage 5%", 5) { () => SoftwareRenderer.renderInto(f1080, target1080, Some(Rect(100, 100, 520, 330))); target1080.length }
    time("SoftwareRenderer 1080p damage 25%", 5) { () => SoftwareRenderer.renderInto(f1080, target1080, Some(Rect(0, 0, 960, 540))); target1080.length }
    time("SoftwareRenderer 1080p full (renderInto)", 5) { () => SoftwareRenderer.renderInto(f1080, target1080, None); target1080.length }

