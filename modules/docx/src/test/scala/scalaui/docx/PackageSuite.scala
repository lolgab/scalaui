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

  test("package keeps part order and bytes for deflated, stored and empty parts") {
    val text = ("lorem ipsum dolor sit amet " * 400).getBytes("UTF-8")
    val noise = new java.util.Random(7).ints(300, 0, 256).toArray.map(_.toByte)
    val p = new Package(mutable.LinkedHashMap("a/text.xml" -> text, "b/noise.bin" -> noise, "c/empty" -> Array.emptyByteArray))
    val bytes = p.toBytes
    assert(bytes.length < text.length, "compressible part should shrink")
    val back = Package.read(bytes)
    assertEquals(back.parts.keys.toList, List("a/text.xml", "b/noise.bin", "c/empty"))
    p.parts.foreach((name, data) => assert(java.util.Arrays.equals(back.parts(name), data), name))
  }

  test("corrupt entry is rejected") {
    val bytes = new Package(mutable.LinkedHashMap("x" -> "hello hello hello".getBytes("UTF-8"))).toBytes
    bytes(31) = (bytes(31) ^ 0x55).toByte // inside the entry data, after the 30-byte header and 1-byte name
    intercept[Exception](Package.read(bytes))
  }
