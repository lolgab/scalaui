package scalaui.shaping

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*
import scalaui.font.FontFace
import scalaui.testkit.LoggedSuite

/** Vertical (top-to-bottom) shaping against `hb-shape --direction=ttb` (`tools/gen_shaping_golden.py`): glyph ids, clusters,
  * advances (y advances are negative) and offsets that include the shift to the vertical origin (VORG / vmtx / fallbacks).
  */
class VerticalGoldenSuite extends LoggedSuite:
  private val dir = "modules/shaping/src/test/resources/fonts/"
  private val fontPaths = Map(
    "NotoSans" -> "modules/font/src/main/resources/fonts/NotoSans-Regular.ttf",
    "NotoSansJP" -> (dir + "NotoSansJP-Subset.otf")) ++
    Seq("Arabic", "Hebrew", "Devanagari", "Thai").map(n => s"NotoSans$n" -> s"${dir}NotoSans$n-Regular.ttf")
  private val faces = scala.collection.mutable.Map.empty[String, FontFace]
  private def face(n: String) = faces.getOrElseUpdate(n, FontFace(Files.readAllBytes(Paths.get(fontPaths(n)))))
  private def parseFeatures(s: String): Seq[FontFeature] =
    if s.isEmpty then Nil else s.split(",").toSeq.map(f => if f.startsWith("-") then FontFeature(f.drop(1), 0) else FontFeature(f, 1))

  private def run(only: String => Boolean): Unit =
    val bad = List.newBuilder[String]; var total = 0
    Files.readAllLines(Paths.get("modules/shaping/src/test/resources/shaping-golden-vertical.txt")).asScala.filter(_.nonEmpty).foreach { l =>
      val Array(fontName, feats, cpsHex, expected) = l.split("\\|", -1)
      if only(fontName) then
        total += 1
        val cps = cpsHex.split(" ").map(Integer.parseInt(_, 16))
        val text = new String(cps, 0, cps.length)
        val f = face(fontName)
        val idx = cps.indices.map(i => text.offsetByCodePoints(0, i) -> i).toMap
        val got = new Shaper(f).shape(text, f.unitsPerEm, features = parseFeatures(feats), vertical = true).map { g =>
          s"${g.glyph}:${idx.getOrElse(g.cluster, -1)}:${math.round(g.xAdvance)}:${math.round(g.yAdvance)}:${math.round(g.xOffset)}:${math.round(g.yOffset)}"
        }.mkString(",")
        if got != expected then bad += s"$fontName [$feats] $text\n    got:  $got\n    want: $expected"
    }
    val failures = bad.result()
    assert(total > 0)
    assert(failures.isEmpty, s"${failures.size}/$total cases differ from HarfBuzz\n  ${failures.take(4).mkString("\n  ")}")

  test("CJK (Noto Sans JP, vert/vrt2/vkna/vpal/vkrn/VORG) matches HarfBuzz TTB")(run(_ == "NotoSansJP"))
  test("Latin without vertical tables matches HarfBuzz TTB")(run(_ == "NotoSans"))
  test("Arabic/Hebrew/Devanagari/Thai TTB match HarfBuzz")(run(n => n != "NotoSans" && n != "NotoSansJP"))

  test("vertical metrics of the CJK subset") {
    val f = face("NotoSansJP")
    assert(f.hasVerticalMetrics)
    val g = f.glyphIndex('日')
    assertEquals(f.verticalAdvance(g), 1000)
    assertEquals(f.verticalOrigin(g), (500, f.verticalOrigin(g)._2))
    assertEquals(f.verticalOrigin(f.glyphIndex('日'))._2, 880) // VORG default
  }
