package scalaui.unicode

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*
import scalaui.testkit.LoggedSuite

/** Runs the official Unicode conformance files (comments stripped) against the segmenters. */
class ConformanceSuite extends LoggedSuite:
  private def lines(name: String): List[String] =
    Files.readAllLines(Paths.get(s"modules/unicode/src/test/resources/ucd/$name")).asScala.toList.filter(_.nonEmpty)

  /** "× 0041 ÷ 0042 ×" -> (code points, boundary flags for 0..n where true = ÷). */
  private def parse(line: String): (Array[Int], Array[Boolean]) =
    val toks = line.split("\\s+").toList
    val cps = Array.newBuilder[Int]; val br = Array.newBuilder[Boolean]
    toks.foreach {
      case "×" => br += false
      case "÷" => br += true
      case h => cps += Integer.parseInt(h, 16)
    }
    (cps.result(), br.result())

  private def runAll(name: String, f: CodePoints => Array[Boolean], interiorOnly: Boolean = true): Unit =
    var failures = 0
    val sb = new StringBuilder
    for l <- lines(name) do
      val (cps, expected) = parse(l)
      val s = new String(cps, 0, cps.length)
      val got = f(CodePoints(s))
      val ok = (1 until cps.length).forall(i => got(i) == expected(i))
      if !ok then
        failures += 1
        if failures <= 8 then sb ++= s"\n  $l\n  got: ${got.map(b => if b then "÷" else "×").mkString(" ")}"
    assert(failures == 0, s"$name: $failures failures$sb")

  test("grapheme clusters (GraphemeBreakTest)")(runAll("GraphemeBreakTest.txt", Graphemes.boundaries))
  test("word boundaries (WordBreakTest)")(runAll("WordBreakTest.txt", Words.boundaries))
  test("line breaks (LineBreakTest)")(runAll("LineBreakTest.txt", cp => LineBreaks.opportunities(cp).map(_ != 0)))
