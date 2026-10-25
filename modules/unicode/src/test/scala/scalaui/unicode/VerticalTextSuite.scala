package scalaui.unicode

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*
import scalaui.testkit.LoggedSuite
import VerticalText.Orientation.*

class VerticalTextSuite extends LoggedSuite:
  test("Vertical_Orientation matches VerticalOrientation.txt for every code point") {
    val expected = Array.fill[String](0x110000)("R") // @missing: 0000..10FFFF; R
    var listed = 0
    Files.readAllLines(Paths.get("modules/unicode/src/test/resources/ucd/VerticalOrientation.txt")).asScala.foreach { raw =>
      val line = raw.split("#", 2)(0).trim
      if line.nonEmpty then
        val Array(r, v) = line.split(";").map(_.trim)
        val Array(lo, hi) = (if r.contains("..") then r.split("\\.\\.") else Array(r, r)).map(Integer.parseInt(_, 16))
        var c = lo
        while c <= hi do { expected(c) = v; c += 1; listed += 1 }
    }
    assert(listed > 100000, s"only $listed code points listed")
    val name = Map(Upright -> "U", Rotated -> "R", TransformedUpright -> "Tu", TransformedRotated -> "Tr")
    val bad = (0 to 0x10FFFF).filter(c => name(VerticalText.orientation(c)) != expected(c))
    assert(bad.isEmpty, s"${bad.size} mismatches, first: ${bad.take(5).map(c => f"U+$c%04X got ${name(VerticalText.orientation(c))} want ${expected(c)}")}")
  }

  test("well-known orientations (UAX #50)") {
    assertEquals(VerticalText.orientation('A'), Rotated)
    assertEquals(VerticalText.orientation(' '), Rotated)
    assertEquals(VerticalText.orientation(0x65E5), Upright)  // 日
    assertEquals(VerticalText.orientation(0x3042), Upright)  // あ
    assertEquals(VerticalText.orientation(0x3001), TransformedUpright) // 、
    assertEquals(VerticalText.orientation(0x300C), TransformedRotated) // 「 fallback rotated
    assertEquals(VerticalText.orientation(0x0627), Rotated) // Arabic alef
    assertEquals(VerticalText.orientation(0x1F600), Upright) // emoji
    assertEquals(VerticalText.orientation(0xFF21), Upright) // fullwidth A
  }

  test("orientation runs follow grapheme clusters") {
    val rs = VerticalText.runs(CodePoints("日本abc語"))
    assertEquals(rs.toSeq, Seq(VerticalText.Run(0, 2, true), VerticalText.Run(2, 5, false), VerticalText.Run(5, 6, true)))
    // a combining acute stays with its Latin base; a combining mark after a kanji stays upright
    val rs2 = VerticalText.runs(CodePoints("é日"))
    assertEquals(rs2.toSeq, Seq(VerticalText.Run(0, 2, false), VerticalText.Run(2, 3, true)))
    // Tr characters turn upright when the font has a vertical form
    assertEquals(VerticalText.runs(CodePoints("ab「c"), _ == 0x300C).toSeq,
      Seq(VerticalText.Run(0, 2, false), VerticalText.Run(2, 3, true), VerticalText.Run(3, 4, false)))
    assertEquals(VerticalText.runs(CodePoints("")).length, 0)
  }
