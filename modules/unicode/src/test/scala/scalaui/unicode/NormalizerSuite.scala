package scalaui.unicode

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*
import scalaui.testkit.LoggedSuite

class NormalizerSuite extends LoggedSuite:
  private def cps(f: String) = f.trim.split(" ").filter(_.nonEmpty).map(Integer.parseInt(_, 16))
  private def same(a: Array[Int], b: Array[Int]) = java.util.Arrays.equals(a, b)

  test("NFC / NFD conformance (NormalizationTest)") {
    val bad = List.newBuilder[String]; var total = 0
    Files.readAllLines(Paths.get("modules/unicode/src/test/resources/ucd/NormalizationTest.txt")).asScala.filter(_.nonEmpty).foreach { l =>
      val f = l.split(";").map(cps)
      if f.length >= 5 then
        total += 1
        val Array(c1, c2, c3, c4, c5) = f.take(5)
        val ok =
          same(Normalizer.nfc(c1), c2) && same(Normalizer.nfc(c2), c2) && same(Normalizer.nfc(c3), c2) &&
          same(Normalizer.nfc(c4), c4) && same(Normalizer.nfc(c5), c4) &&
          same(Normalizer.nfd(c1), c3) && same(Normalizer.nfd(c2), c3) && same(Normalizer.nfd(c3), c3) &&
          same(Normalizer.nfd(c4), c5) && same(Normalizer.nfd(c5), c5)
        if !ok then bad += l
    }
    val failures = bad.result()
    assert(failures.isEmpty, s"${failures.size}/$total failures, first: ${failures.take(4).mkString(" | ")}")
  }
  test("composition helpers") {
    assertEquals(Normalizer.composePair('e', 0x301), 0xE9)
    assertEquals(Normalizer.decomposeOnce(0xE9), Some(('e'.toInt, 0x301)))
    assertEquals(Normalizer.nfc("é"), "é")
    assertEquals(Normalizer.nfd("각"), "각")
  }
