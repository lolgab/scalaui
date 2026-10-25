package scalaui.shaping

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*
import scalaui.font.FontFace
import scalaui.testkit.LoggedSuite

/** Compares shaping output with HarfBuzz (`tools/gen_shaping_golden.py`): glyph ids, clusters, advances and offsets. */
class GoldenSuite extends LoggedSuite:
  private val fontPaths = Map(
    "NotoSans" -> "modules/font/src/main/resources/fonts/NotoSans-Regular.ttf",
    "NotoSansArabic" -> "modules/shaping/src/test/resources/fonts/NotoSansArabic-Regular.ttf",
    "NotoSansHebrew" -> "modules/shaping/src/test/resources/fonts/NotoSansHebrew-Regular.ttf",
    "NotoSansDevanagari" -> "modules/shaping/src/test/resources/fonts/NotoSansDevanagari-Regular.ttf") ++
    Seq("Bengali", "Tamil", "Telugu", "Malayalam", "Kannada", "Gujarati", "Gurmukhi", "Oriya", "Sinhala", "Thai", "Lao")
      .map(n => s"NotoSans$n" -> s"modules/shaping/src/test/resources/fonts/NotoSans$n-Regular.ttf") ++
    Seq("Javanese", "Balinese", "Khmer", "Myanmar").map(n => s"NotoSans$n" -> s"modules/shaping/src/test/resources/fonts/NotoSans$n-Regular.ttf") ++
    Map("NotoSerifTibetan" -> "modules/shaping/src/test/resources/fonts/NotoSerifTibetan-Regular.ttf")
  private val faces = scala.collection.mutable.Map.empty[String, FontFace]
  private def face(n: String) = faces.getOrElseUpdate(n, FontFace(Files.readAllBytes(Paths.get(fontPaths(n)))))

  private def parseFeatures(s: String): Seq[FontFeature] =
    if s.isEmpty then Nil else s.split(",").toSeq.map(f => if f.startsWith("-") then FontFeature(f.drop(1), 0) else FontFeature(f, 1))

  private def run(only: String => Boolean): Unit =
    val bad = List.newBuilder[String]; var total = 0
    Files.readAllLines(Paths.get("modules/shaping/src/test/resources/shaping-golden.txt")).asScala.filter(_.nonEmpty).foreach { l =>
      val Array(fontName, dir, feats, cpsHex, expected) = l.split("\\|", -1)
      if only(fontName) then
        total += 1
        val cps = cpsHex.split(" ").map(Integer.parseInt(_, 16))
        val text = new String(cps, 0, cps.length)
        val f = face(fontName)
        val s = new Shaper(f)
        val cpIndexOfUtf16 = cps.indices.map(i => text.offsetByCodePoints(0, i) -> i).toMap
        val got = s.shape(text, f.unitsPerEm, dir == "rtl", parseFeatures(feats)).map { g =>
          s"${g.glyph}:${cpIndexOfUtf16.getOrElse(g.cluster, -1)}:${math.round(g.xAdvance)}:${math.round(g.xOffset)}:${math.round(g.yOffset)}"
        }.mkString(",")
        if got != expected then bad += s"$fontName $dir [$feats] ${text}\n    got:  $got\n    want: $expected"
    }
    val failures = bad.result()
    assert(failures.isEmpty, s"${failures.size}/$total cases differ from HarfBuzz\n  ${failures.take(5).mkString("\n  ")}")

  test("Latin (Noto Sans) matches HarfBuzz")(run(_ == "NotoSans"))
  test("Arabic matches HarfBuzz")(run(_ == "NotoSansArabic"))
  test("Hebrew matches HarfBuzz")(run(_ == "NotoSansHebrew"))
  test("Devanagari matches HarfBuzz")(run(_ == "NotoSansDevanagari"))
  for n <- Seq("Bengali", "Tamil", "Telugu", "Malayalam", "Kannada", "Gujarati", "Gurmukhi", "Oriya", "Sinhala", "Thai", "Lao") do
    test(s"$n matches HarfBuzz")(run(_ == s"NotoSans$n"))
  test("Tibetan matches HarfBuzz")(run(_ == "NotoSerifTibetan"))
  for n <- Seq("Javanese", "Balinese", "Khmer", "Myanmar") do
    test(s"$n matches HarfBuzz")(run(_ == s"NotoSans$n"))
