package scalaui.color

class ColorSuite extends scalaui.testkit.LoggedSuite:
  test("hex parsing") {
    assertEquals(Color.hex("#ff0000").map(_.toArgb), Some(0xffff0000))
    assertEquals(Color.hex("#0f0").map(_.toArgb), Some(0xff00ff00))
    assertEquals(Color.hex("#00000080").map(_.toArgb), Some(0x80000000))
    assertEquals(Color.hex("#12"), None)
  }
  test("srgb round trip") {
    val c = 0.37f
    assertEqualsDouble(Color.linearToSrgb(Color.srgbToLinear(c)), c, 1e-5)
  }
  test("over") {
    val r = Color(1, 0, 0, 0.5f).premultiplied.over(Color.White.premultiplied)
    assertEqualsDouble(r.a, 1.0, 1e-6)
    assertEqualsDouble(r.g, 0.5, 1e-6)
  }
  test("hsv round trip") {
    for c <- Seq(Color(1, 0, 0), Color(0.2f, 0.7f, 0.4f), Color(0.5f, 0.5f, 0.5f), Color(0.1f, 0.2f, 0.9f), Color.Black, Color.White) do
      val (h, s, v) = Color.toHsv(c)
      val b = Color.fromHsv(h, s, v)
      assertEqualsDouble(b.r, c.r, 1e-4); assertEqualsDouble(b.g, c.g, 1e-4); assertEqualsDouble(b.b, c.b, 1e-4)
    assertEqualsDouble(Color.toHsv(Color(0, 1, 0))._1, 120.0, 1e-3)
  }

