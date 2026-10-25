package scalaui.codecs

class WebpEncoderRoundTripSuite extends scalaui.testkit.LoggedSuite:
  private def roundTrip(name: String, img: Image): Unit =
    val back = Webp.decode(WebpEncoder.encode(img))
    assertEquals((back.width, back.height), (img.width, img.height), name)
    assert(java.util.Arrays.equals(back.rgba, img.rgba), s"$name: pixels differ")

  test("lossless encoder round-trips smooth gradients (all 256 literals used with equal code lengths)") {
    // regression: a code-length code with a single used symbol must be written without bits
    val w = 64; val h = 64
    val d = new Array[Byte](w * h * 4)
    for y <- 0 until h; x <- 0 until w do
      val i = (y * w + x) * 4
      d(i) = ((x * 255 / w) ^ ((y >> 3) & 31)).toByte; d(i + 1) = (y * 255 / h + (x >> 4 & 15)).toByte
      d(i + 2) = (128 + 100 * math.sin(x * 0.05) * math.cos(y * 0.04)).toInt.toByte; d(i + 3) = -1
    roundTrip("gradient", Image(w, h, d))
  }
  test("lossless encoder round-trips noise, flat and translucent images") {
    val rnd = new scala.util.Random(7)
    roundTrip("noise", Image(37, 23, Array.tabulate(37 * 23 * 4)(_ => rnd.nextInt(256).toByte)))
    roundTrip("flat", Image(9, 9, Array.tabulate(9 * 9 * 4)(i => if i % 4 == 3 then -1 else 7)))
    roundTrip("alpha", Image(16, 16, Array.tabulate(16 * 16 * 4)(i => if i % 4 == 3 then (i / 4).toByte else (i * 3).toByte)))
  }
