package scalaui.deflate

class ZlibSuite extends scalaui.testkit.LoggedSuite:
  private def res(name: String): Array[Byte] =
    val in = getClass.getResourceAsStream(s"/zlib/$name"); require(in != null, name)
    try in.readAllBytes() finally in.close()

  private val corpus: Array[Byte] = res("corpus.txt")

  for lvl <- Seq(0, 1, 6, 9) do
    test(s"inflates data compressed by system zlib at level $lvl") {
      assertEquals(Zlib.decompress(res(s"corpus.$lvl.z")).toSeq, corpus.toSeq)
    }
  test("inflates fixed-Huffman stream (tiny input)") {
    assertEquals(new String(Zlib.decompress(res("hello.z")), "UTF-8"), "hello hello hello hello")
  }
  test("round trip: text, random, zeros, empty, tiny, long runs") {
    val rnd = new scala.util.Random(42)
    val random = new Array[Byte](50000); rnd.nextBytes(random)
    val inputs = Seq(corpus, random, new Array[Byte](100000), Array.emptyByteArray, Array[Byte](1), "abc".getBytes,
      Array.tabulate[Byte](70000)(i => (i / 300).toByte), (corpus ++ corpus ++ corpus))
    for data <- inputs; lvl <- Seq(0, 1, 6, 9) do
      val z = Zlib.compress(data, lvl)
      assertEquals(Zlib.decompress(z).toSeq, data.toSeq, s"len ${data.length} level $lvl")
  }
  test("compression actually compresses text") {
    val z = Zlib.compress(corpus, 6)
    assert(z.length < corpus.length / 2, s"${z.length} vs ${corpus.length}")
  }
  test("corrupt checksum is rejected") {
    val z = Zlib.compress("hello world".getBytes)
    z(z.length - 1) = (z(z.length - 1) ^ 0x55).toByte
    intercept[DataFormatException](Zlib.decompress(z))
  }
  test("crc32 and adler32 known values") {
    assertEquals(Crc32("123456789".getBytes), 0xcbf43926)
    assertEquals(Adler32("Wikipedia".getBytes), 0x11E60398)
  }
  test("checksums of a large buffer match system zlib (no Int overflow)") {
    val data = Array.tabulate[Byte](300000)(i => ((i * 31 + 7) % 256).toByte)
    assertEquals(Adler32(data), 0x2358c83a.toInt)
    assertEquals(Crc32(data), 0x730305b6.toInt)
    assertEquals(Zlib.decompress(Zlib.compress(data, 6)).toSeq, data.toSeq)
  }
  test("writes sample for external validation") {
    java.nio.file.Files.write(java.nio.file.Paths.get("target/corpus.ours.z"), Zlib.compress(corpus, 6))
    val big = Array.tabulate[Byte](300000)(i => ((i * 31 + 7) % 256).toByte)
    java.nio.file.Files.write(java.nio.file.Paths.get("target/big.ours.z"), Zlib.compress(big, 6))
  }

  test("truncated and garbage deflate data only ever raise DataFormatException (table-driven decoder)") {
    val rnd = new scala.util.Random(3)
    val text = Array.tabulate(20000)(i => ((i * 31) ^ (i / 7)).toByte)
    val z = Zlib.compress(text)
    for cut <- Seq(z.length / 4, z.length / 2, z.length - 9) do
      val r = scala.util.Try(Zlib.decompress(z.take(cut)))
      assert(r.isFailure && r.failed.get.isInstanceOf[DataFormatException], s"cut $cut: $r")
    for _ <- 0 until 200 do
      val g = Array.tabulate(300)(_ => rnd.nextInt(256).toByte); g(0) = 0x78; g(1) = 0x9c.toByte
      scala.util.Try(Zlib.decompress(g)) match
        case scala.util.Failure(e) => assert(e.isInstanceOf[DataFormatException], e.toString)
        case _ => ()
  }
  test("long overlapping back-references and long (>10 bit) codes decode correctly") {
    // run-length style data forces distance < length copies; skewed random data forces long Huffman codes
    val rnd = new scala.util.Random(11)
    val runs = Array.tabulate(50000)(i => ((i / 700) % 5).toByte)
    val skew = Array.tabulate(100000)(_ => { val r = rnd.nextDouble(); (math.min(255, (-math.log(1 - r) * 18).toInt)).toByte })
    for data <- Seq(runs, skew); lvl <- Seq(1, 6, 9) do
      assert(java.util.Arrays.equals(Zlib.decompress(Zlib.compress(data, lvl)), data), s"level $lvl")
  }

