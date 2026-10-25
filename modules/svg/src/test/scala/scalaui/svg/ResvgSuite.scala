package scalaui.svg

import java.nio.file.{Files, Path, Paths}
import scalaui.codecs.{Image, Png}
import scala.jdk.CollectionConverters.*

/** Renders SVG files from the resvg test suite (see resources/resvg/README.md) at 300 px width and compares them with resvg's own output:
  * a render passes when at most 5% of its pixels differ by more than 25% in any channel (resvg quantizes edge coverage to quarter pixels) (anti-aliasing and filter rounding differences stay below that).
  */
class ResvgSuite extends scalaui.testkit.LoggedSuite:
  private val root = Paths.get("modules/svg/src/test/resources/resvg")
  private val width = 300.0

  private def diffFraction(a: Image, b: Image): Double =
    if a.width != b.width || a.height != b.height then 1.0
    else
      var bad = 0; val n = a.width * a.height
      var i = 0
      while i < n do
        val pa = (a.rgba(i * 4 + 3) & 0xff) / 255.0; val pb = (b.rgba(i * 4 + 3) & 0xff) / 255.0
        var maxd = math.abs(pa - pb)
        var c = 0
        while c < 3 do
          // compare composited over white so fully transparent pixels do not count their hidden colour
          val ca = (a.rgba(i * 4 + c) & 0xff) / 255.0 * pa + (1 - pa); val cb = (b.rgba(i * 4 + c) & 0xff) / 255.0 * pb + (1 - pb)
          maxd = math.max(maxd, math.abs(ca - cb)); c += 1
        if maxd > 0.25 then bad += 1
        i += 1
      bad.toDouble / n

  test("renders match resvg") {
    val svgs = Files.walk(root).iterator.asScala.filter(_.toString.endsWith(".svg")).toVector.sortBy(_.toString)
    assert(svgs.size > 100, s"fixtures missing under $root")
    val failures = svgs.flatMap { f =>
      val name = root.relativize(f).toString
      try
        val doc = SvgDocument.parse(new String(Files.readAllBytes(f), "UTF-8"), SvgOptions(resourceDir = Some(f.getParent.toString), systemFonts = false))
        val pm = doc.renderScaled(width / doc.width)
        val ref = Png.decode(Files.readAllBytes(Paths.get(f.toString.stripSuffix(".svg") + ".png")))
        val frac = diffFraction(scalaui.imaging.Pixmaps.toImage(pm), ref)
        if frac > 0.05 then Some(f"$name%s (${frac * 100}%.2f%% differ)") else None
      catch case e: Throwable => Some(s"$name: $e")
    }
    assert(failures.isEmpty, s"${failures.size} of ${svgs.size} fixtures differ from resvg:\n" + failures.mkString("\n"))
  }
