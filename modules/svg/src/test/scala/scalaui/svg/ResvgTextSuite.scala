package scalaui.svg

import java.nio.file.{Files, Path, Paths}
import scalaui.codecs.{Image, Png}
import scalaui.font.{FontFace, FontFamily}
import scala.jdk.CollectionConverters.*

/** Text fixtures from the resvg test suite that need extra fonts (resources/resvg-text, fonts in resources/resvg-fonts): vertical writing modes and
  * colour fonts (COLR v0/v1, CBDT, `SVG ` table). Rendered at 300 px width and compared with resvg's PNGs by ink overlap: the dark or coloured pixels of
  * each image must lie within 2 px of the other image's (at least 95% of them each way). The pages are mostly empty, so a plain pixel-difference limit would
  * accept horizontal text in place of vertical text; the overlap measure ignores the 1 px offsets that differ between resvg and scalaui (vertical metrics).
  */
class ResvgTextSuite extends scalaui.testkit.LoggedSuite:
  private val root = Paths.get("modules/svg/src/test/resources/resvg-text")
  private val fontDir = Paths.get("modules/svg/src/test/resources/resvg-fonts")
  private val minOverlap = 0.95
  /** Fixtures that differ from resvg on purpose, with the overlap they reach:
    * - `arabic-with-rl`: resvg lays `rl` text out left to right without reordering, scalaui applies the bidi order of the shaper;
    * - `mixed-languages-with-tb-and-underline`: RTL runs inside vertical text are not reordered by scalaui (resvg reorders them bottom to top);
    * - `tb-with-rotate-and-underline`: undefined behaviour, resvg splits the underline into one piece per rotated glyph;
    * - `textPath-complex`: undefined behaviour (a chunk continuing after a vertical text path).
    */
  private val limits = Map("writing-mode/arabic-with-rl.svg" -> 0.78, "writing-mode/mixed-languages-with-tb-and-underline.svg" -> 0.80,
    "writing-mode/tb-with-rotate-and-underline.svg" -> 0.88, "misc/textPath-complex.svg" -> 0.70)

  private lazy val fonts: Seq[FontFamily] =
    val faces = Files.list(fontDir).iterator.asScala.filter(p => p.toString.endsWith(".ttf") || p.toString.endsWith(".otf")).toSeq.sortBy(_.toString).map(p => FontFace(Files.readAllBytes(p)))
    faces.groupBy(_.familyName).map((name, fs) => new FontFamily(name, fs.head)).toSeq

  /** Pixels clearly darker than white once composited over white. */
  private def ink(img: Image): Array[Boolean] =
    Array.tabulate(img.width * img.height) { i =>
      val a = (img.rgba(i * 4 + 3) & 0xff) / 255.0
      (0 until 3).exists(c => 1.0 - ((img.rgba(i * 4 + c) & 0xff) / 255.0 * a + (1 - a)) > 0.5)
    }

  /** Share of `a`'s ink within `r` pixels of ink in `b`. */
  private def near(a: Array[Boolean], b: Array[Boolean], w: Int, h: Int, r: Int): Double =
    var total = 0; var hit = 0
    for y <- 0 until h; x <- 0 until w if a(y * w + x) do
      total += 1
      if (for yy <- math.max(0, y - r) to math.min(h - 1, y + r); xx <- math.max(0, x - r) to math.min(w - 1, x + r) yield b(yy * w + xx)).exists(identity) then hit += 1
    if total == 0 then 1.0 else hit.toDouble / total

  test("vertical writing modes and colour fonts match resvg") {
    val svgs = Files.walk(root).iterator.asScala.filter(_.toString.endsWith(".svg")).toVector.sortBy(_.toString)
    assert(svgs.size >= 35, s"fixtures missing under $root")
    val report = new StringBuilder
    val failures = svgs.flatMap { f =>
      val name = root.relativize(f).toString
      try
        val doc = SvgDocument.parse(new String(Files.readAllBytes(f), "UTF-8"), SvgOptions(resourceDir = Some(f.getParent.toString), fonts = fonts, systemFonts = false))
        val ours = scalaui.imaging.Pixmaps.toImage(doc.renderScaled(300.0 / doc.width))
        val ref = Png.decode(Files.readAllBytes(Paths.get(f.toString.stripSuffix(".svg") + ".png")))
        if ours.width != ref.width || ours.height != ref.height then Some(s"$name: size ${ours.width}x${ours.height} vs ${ref.width}x${ref.height}")
        else
          val a = ink(ours); val b = ink(ref)
          val precision = near(a, b, ours.width, ours.height, 2); val recall = near(b, a, ours.width, ours.height, 2)
          report ++= f"$name%-70s precision ${precision * 100}%.1f%% recall ${recall * 100}%.1f%%\n"
          if math.min(precision, recall) < limits.getOrElse(name, minOverlap) then Some(f"$name%s (ink overlap ${precision * 100}%.1f%% / ${recall * 100}%.1f%%)") else None
      catch case e: Throwable => Some(s"$name: $e")
    }
    Files.createDirectories(Paths.get("target"))
    Files.writeString(Paths.get("target/resvg-text-report.txt"), report.toString)
    assert(failures.isEmpty, s"${failures.size} of ${svgs.size} fixtures differ from resvg:\n" + failures.mkString("\n"))
  }
