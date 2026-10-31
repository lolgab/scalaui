package scalaui.codecs

class JpegSuite extends scalaui.testkit.LoggedSuite:
  private def res(name: String): Array[Byte] =
    val in = getClass.getResourceAsStream(s"/jpeg/$name")
    require(in != null, s"missing fixture $name")
    try in.readAllBytes() finally in.close()

  /** Mean absolute channel error against libjpeg's own decode of the same file. */
  private def meanError(name: String): (Double, Int) =
    val img = Jpeg.decode(res(s"$name.jpg"))
    val ref = Png.decode(res(s"$name.ref.png"))
    assertEquals((img.width, img.height), (ref.width, ref.height))
    var sum = 0L; var max = 0
    for i <- 0 until img.width * img.height; c <- 0 until 3 do
      val e = math.abs((img.rgba(i * 4 + c) & 0xff) - (ref.rgba(i * 4 + c) & 0xff))
      sum += e; max = math.max(max, e)
    (sum.toDouble / (img.width * img.height * 3), max)

  for name <- Seq("base420", "base422", "base444_rst", "prog420", "gray", "ycck") do
    test(s"$name matches libjpeg output") {
      val (mean, max) = meanError(name)
      assert(mean < 1.5, s"$name mean error $mean (max $max)")
    }

  test("Image.decode sniffs JPEG") {
    assertEquals(Image.decode(res("base420.jpg")).width, 203)
  }
