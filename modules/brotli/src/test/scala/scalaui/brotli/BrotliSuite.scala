package scalaui.brotli

class BrotliSuite extends scalaui.testkit.LoggedSuite:
  private def res(name: String): Array[Byte] =
    val s = getClass.getResourceAsStream(s"/brotli/$name"); require(s != null, name)
    try s.readAllBytes() finally s.close()

  private val cases = Seq(
    "text.txt" -> Seq("text.txt.q5.br", "text.txt.q11.br", "text.txt.w10.br", "text.txt.w24.br"),
    "binary.bin" -> Seq("binary.bin.q1.br", "binary.bin.q5.br", "binary.bin.q11.br"),
    "random.bin" -> Seq("random.bin.q0.br"), // stored (uncompressed) meta-blocks
    "empty.bin" -> Seq("empty.bin.q0.br", "empty.bin.q5.br", "empty.bin.q11.br")
  )
  for (plain, compressed) <- cases; c <- compressed do
    test(s"decodes $c") {
      val expected = res(plain)
      val got = Brotli.decompress(res(c))
      assertEquals(got.length, expected.length)
      assert(java.util.Arrays.equals(got, expected), s"$c differs")
    }

  test("static dictionary is embedded") {
    assertEquals(Brotli.dictionary.length, 122784)
    assertEquals(new String(Brotli.dictionary, 0, 4, "ISO-8859-1"), "time")
  }

  test("offset/length slice and truncated input") {
    val c = res("text.txt.q11.br")
    val padded = Array[Byte](1, 2, 3) ++ c ++ Array[Byte](9, 9)
    assert(java.util.Arrays.equals(Brotli.decompress(padded, 3, c.length), res("text.txt")))
    intercept[IllegalArgumentException](Brotli.decompress(c.take(c.length / 2)))
  }
