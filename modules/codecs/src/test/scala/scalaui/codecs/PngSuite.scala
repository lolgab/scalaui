package scalaui.codecs

class PngSuite extends scalaui.testkit.LoggedSuite:
  private def gradient(w: Int, h: Int, alpha: Boolean): Image =
    val d = new Array[Byte](w * h * 4)
    for y <- 0 until h; x <- 0 until w do
      val i = (y * w + x) * 4
      d(i) = (x * 255 / math.max(1, w - 1)).toByte; d(i + 1) = (y * 255 / math.max(1, h - 1)).toByte
      d(i + 2) = ((x + y) * 7).toByte; d(i + 3) = if alpha then ((x * 31 + y * 17) & 0xff).toByte else -1
    Image(w, h, d)

  test("encode/decode round trip RGBA") {
    val img = gradient(37, 23, alpha = true)
    val back = Png.decode(Png.encode(img))
    assertEquals((back.width, back.height), (37, 23))
    assert(java.util.Arrays.equals(back.rgba, img.rgba))
  }
  test("encode/decode round trip opaque (RGB path)") {
    val img = gradient(64, 5, alpha = false)
    val bytes = Png.encode(img)
    assertEquals(bytes(25).toInt, 2) // IHDR color type 2
    assert(java.util.Arrays.equals(Png.decode(bytes).rgba, img.rgba))
  }
  test("Image.decode sniffs PNG and rejects junk") {
    assertEquals(Image.decode(Png.encode(gradient(2, 2, true))).width, 2)
    intercept[ImageFormatException](Image.decode(Array[Byte](1, 2, 3)))
  }
  test("premultiplied conversion") {
    val img = Image(1, 1, Array[Byte](-1, 0, 0, 128.toByte))
    val p = img.premultiplied
    assertEquals(p(0) & 0xff, 128)
    assertEquals(p(3) & 0xff, 128)
  }
  test("decodes a hand-built 2-bit grayscale PNG with Adam7 interlace") {
    // 3x3 image, 2-bit gray, interlaced; build raw passes manually, values = (x+y) % 4
    import java.io.ByteArrayOutputStream
    val xs = Array(0, 4, 0, 2, 0, 1, 0); val ys = Array(0, 0, 4, 0, 2, 0, 1)
    val dx = Array(8, 8, 4, 4, 2, 2, 1); val dy = Array(8, 8, 8, 4, 4, 2, 2)
    val raw = new ByteArrayOutputStream()
    for p <- 0 until 7 do
      val pw = (3 - xs(p) + dx(p) - 1) / dx(p); val ph = (3 - ys(p) + dy(p) - 1) / dy(p)
      if pw > 0 && ph > 0 then
        for y <- 0 until ph do
          raw.write(0)
          var byte = 0
          for x <- 0 until pw do
            val v = ((xs(p) + x * dx(p)) + (ys(p) + y * dy(p))) % 4
            byte |= v << (6 - 2 * x)
          raw.write(byte)
    val z = scalaui.deflate.Zlib.compress(raw.toByteArray, 6); val zn = z.length
    val out = new ByteArrayOutputStream()
    out.write(Array(0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a).map(_.toByte))
    def chunk(t: String, b: Array[Byte]): Unit =
      out.write(Array[Byte](0, 0, (b.length >> 8).toByte, b.length.toByte)); out.write(t.getBytes("ISO-8859-1")); out.write(b); out.write(Array[Byte](0, 0, 0, 0))
    chunk("IHDR", Array[Byte](0, 0, 0, 3, 0, 0, 0, 3, 2, 0, 0, 0, 1))
    chunk("IDAT", z.take(zn)); chunk("IEND", Array.emptyByteArray)
    val img = Png.decode(out.toByteArray)
    for y <- 0 until 3; x <- 0 until 3 do
      val expect = ((x + y) % 4) * 85
      assertEquals(img.rgba((y * 3 + x) * 4) & 0xff, expect, s"pixel $x,$y")
  }
