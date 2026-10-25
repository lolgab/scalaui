package scalaui.docx

import scalaui.testkit.LoggedSuite
import scala.collection.mutable

class PackageSuite extends LoggedSuite:
  test("package round-trips through zip and parses xml parts") {
    val doc = "<w:document xmlns:w='urn:w'><w:body><w:p>hi</w:p></w:body></w:document>"
    val p = new Package(mutable.LinkedHashMap("word/document.xml" -> doc.getBytes("UTF-8")))
    val back = Package.read(p.toBytes)
    assertEquals(back.parts.keys.toList, List("word/document.xml"))
    assertEquals((back.xml("word/document.xml").get \\ "p").text, "hi")
  }
