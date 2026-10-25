package scalaui.unicode

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*
import scalaui.testkit.LoggedSuite

class BidiSuite extends LoggedSuite:
  private def lines(name: String) =
    Files.readAllLines(Paths.get(s"modules/unicode/src/test/resources/ucd/$name")).asScala.toList.filter(_.nonEmpty)

  private val classCp = Map(
    "L" -> 0x41, "R" -> 0x5D0, "AL" -> 0x627, "EN" -> 0x30, "ES" -> 0x2B, "ET" -> 0x24, "AN" -> 0x660, "CS" -> 0x2C,
    "B" -> 0x2029, "S" -> 0x9, "WS" -> 0x20, "ON" -> 0x21, "BN" -> 0xAD, "NSM" -> 0x300, "LRE" -> 0x202A,
    "LRO" -> 0x202D, "RLE" -> 0x202B, "RLO" -> 0x202E, "PDF" -> 0x202C, "LRI" -> 0x2066, "RLI" -> 0x2067,
    "FSI" -> 0x2068, "PDI" -> 0x2069)

  private def check(cps: Array[Int], dir: Int, expLevels: Array[Option[Int]], expOrder: Array[Int], expPara: Option[Int]): Option[String] =
    val r = Bidi.resolve(cps, dir)
    val lv = r.lineLevels(0, cps.length)
    val levelsOk = expLevels.indices.forall(i => expLevels(i).forall(_ == lv(i)))
    val order = r.visualOrder(0, cps.length).filterNot(r.removed(_))
    val paraOk = expPara.forall(_ == r.paragraphLevel)
    if levelsOk && order.sameElements(expOrder) && paraOk then None
    else Some(s"cps=${cps.map(_.toHexString).mkString(" ")} dir=$dir\n    levels got=${lv.mkString(" ")} exp=${expLevels.map(_.fold("x")(_.toString)).mkString(" ")}\n    order got=${order.mkString(" ")} exp=${expOrder.mkString(" ")} para=${r.paragraphLevel}")

  private def report(name: String, fails: List[String], total: Int): Unit =
    assert(fails.isEmpty, s"$name: ${fails.size}/$total failures\n  ${fails.take(6).mkString("\n  ")}")

  test("BidiCharacterTest") {
    val fails = List.newBuilder[String]; var total = 0
    for l <- lines("BidiCharacterTest.txt") do
      val f = l.split(";")
      val cps = f(0).trim.split(" ").map(Integer.parseInt(_, 16))
      val dir = f(1).trim.toInt match { case 2 => Bidi.Auto; case d => d }
      val lv = f(3).trim.split(" ").map(s => if s == "x" then None else Some(s.toInt))
      val ord = if f(4).trim.isEmpty then Array.empty[Int] else f(4).trim.split(" ").map(_.toInt)
      total += 1
      check(cps, dir, lv, ord, Some(f(2).trim.toInt)).foreach(fails += _)
    report("BidiCharacterTest", fails.result(), total)
  }

  test("BidiTest") {
    val fails = List.newBuilder[String]; var total = 0
    var lv = Array.empty[Option[Int]]; var ord = Array.empty[Int]
    for l <- lines("BidiTest.txt") do
      if l.startsWith("@Levels:") then lv = l.drop(8).trim.split("\\s+").filter(_.nonEmpty).map(s => if s == "x" then None else Some(s.toInt))
      else if l.startsWith("@Reorder:") then ord = l.drop(9).trim.split("\\s+").filter(_.nonEmpty).map(_.toInt)
      else if !l.startsWith("@") then
        val Array(cl, bits) = l.split(";")
        val cps = cl.trim.split("\\s+").map(classCp)
        val mask = bits.trim.toInt
        for (bit, dir) <- List((1, Bidi.Auto), (2, Bidi.Ltr), (4, Bidi.Rtl)) if (mask & bit) != 0 do
          total += 1
          check(cps, dir, lv, ord, None).foreach(fails += _)
    report("BidiTest", fails.result(), total)
  }
