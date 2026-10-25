package scalaui.unicode

import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters.*
import scalaui.testkit.LoggedSuite

class ZawgyiSuite extends LoggedSuite:
  private val dir = "modules/unicode/src/test/resources/zawgyi/"
  private def lines(f: String) = Files.readAllLines(Paths.get(dir + f)).asScala.toList

  test("detector: no Myanmar text is -Infinity") {
    assertEquals(Zawgyi.probability("hello world"), Double.NegativeInfinity)
    assertEquals(Zawgyi.probability("၉၆.၀ kHz"), Double.NegativeInfinity)
  }
  test("detector: strong Unicode / strong Zawgyi") {
    assert(Zawgyi.probability("အပြည်ပြည်ဆိုင်ရာ လူ့အခွင့်အရေး ကြေညာစာတမ်း") < 0.001)
    assert(Zawgyi.probability("အျပည္ျပည္ဆိုင္ရာ လူ႔အခြင့္အေရး ေၾကညာစာတမ္း") > 0.999)
    assert(Zawgyi.probability("blah blah ဒဂုန်ဦးစန်းငွေ </span> blah") < 0.01)
    assert(Zawgyi.probability("blah blah blah သို႔သြားပါ။ blah blah") > 0.99)
    assert(!Zawgyi.isZawgyi("အပြည်ပြည်ဆိုင်ရာ လူ့အခွင့်အရေး ကြေညာစာတမ်း"))
    assert(Zawgyi.isZawgyi("အျပည္ျပည္ဆိုင္ရာ လူ႔အခြင့္အေရး ေၾကညာစာတမ္း"))
  }
  test("detector: myanmar-tools compatibility.tsv") {
    val bad = lines("compatibility.tsv").filter(_.nonEmpty).flatMap { l =>
      val i = l.indexOf('\t')
      val exp = l.substring(0, i).toDouble
      val s = l.substring(i + 1)
      val act = Zawgyi.probability(s)
      val ok = if exp.isInfinite then act == exp else math.abs(act - exp) < 1e-6
      if ok then None else Some(s"$l -> $act")
    }
    assert(bad.isEmpty, bad.take(3).mkString(" | "))
  }
  test("converter: known Zawgyi -> Unicode strings") {
    val cases = List(
      "အျပည္ျပည္ဆိုင္ရာ လူ႔အခြင့္အေရး ေၾကညာစာတမ္း" -> "အပြည်ပြည်ဆိုင်ရာ လူ့အခွင့်အရေး ကြေညာစာတမ်း",
      "သို႔သြားပါ။" -> "သို့သွားပါ။",
      "ေမာင္" -> "မောင်",
      "ၾကီး" -> "ကြီး",
      "hello" -> "hello"
    )
    for (z, u) <- cases do assertEquals(Zawgyi.toUnicode(z), u, z)
  }
  test("converter: myanmar-tools mmgov corpus") {
    val src = lines("mmgov_zawgyi_src.txt"); val exp = lines("mmgov_unicode_out.txt")
    assertEquals(src.size, exp.size)
    val bad = src.zip(exp).filter((z, u) => Zawgyi.toUnicode(z) != u)
    assert(bad.isEmpty, s"${bad.size}/${src.size} mismatches, first: ${bad.headOption}")
  }
  test("autoConvert converts only Zawgyi text") {
    val uni = "အပြည်ပြည်ဆိုင်ရာ လူ့အခွင့်အရေး ကြေညာစာတမ်း"
    assertEquals(Zawgyi.autoConvert("အျပည္ျပည္ဆိုင္ရာ လူ႔အခြင့္အေရး ေၾကညာစာတမ္း"), uni)
    assertEquals(Zawgyi.autoConvert(uni), uni)
    assertEquals(Zawgyi.autoConvert("plain"), "plain")
  }
