package scalaui.imaging

import scalaui.codecs.Image
import scalaui.color.Color
import scalaui.raster.Pixmap

class ImagingSuite extends scalaui.testkit.LoggedSuite:
  private def solid(w: Int, h: Int, c: Color): Pixmap = { val p = new Pixmap(w, h); p.clear(c); p }
  private def px(p: Pixmap, x: Int, y: Int) = { val i = (y * p.width + x) * 4; Seq(p.data(i), p.data(i + 1), p.data(i + 2), p.data(i + 3)) }
  private def near(a: Seq[Float], b: Seq[Double], eps: Double = 1e-3) = a.zip(b).foreach((x, y) => assertEqualsDouble(x, y, eps, s"$a vs $b"))

  test("image <-> pixmap round trip keeps straight alpha") {
    val img = Image(1, 1, Array[Byte](100, 150.toByte, 200.toByte, 128.toByte))
    val back = Pixmaps.toImage(Pixmaps.fromImage(img))
    for c <- 0 until 4 do assert(math.abs((back.rgba(c) & 0xff) - (img.rgba(c) & 0xff)) <= 1, s"channel $c")
  }
  test("normal composite equals source-over") {
    val d = solid(2, 2, Color.White); val s = solid(2, 2, Color(1, 0, 0, 0.5f))
    Blend.composite(d, s)
    near(px(d, 0, 0), Seq(1, 0.5, 0.5, 1))
  }
  test("multiply and screen of opaque colors") {
    val a = Color(0.5f, 0.5f, 0.5f); val b = Color(0.5f, 1f, 0f)
    val m = solid(1, 1, a); Blend.composite(m, solid(1, 1, b), mode = BlendMode.Multiply)
    near(px(m, 0, 0), Seq(0.25, 0.5, 0, 1))
    val s = solid(1, 1, a); Blend.composite(s, solid(1, 1, b), mode = BlendMode.Screen)
    near(px(s, 0, 0), Seq(0.75, 1, 0.5, 1))
  }
  test("difference of equal colors is black; luminosity keeps backdrop hue") {
    val d = solid(1, 1, Color(0.3f, 0.6f, 0.9f)); Blend.composite(d, solid(1, 1, Color(0.3f, 0.6f, 0.9f)), mode = BlendMode.Difference)
    near(px(d, 0, 0), Seq(0, 0, 0, 1))
    val l = solid(1, 1, Color(1, 0, 0)); Blend.composite(l, solid(1, 1, Color(0.5f, 0.5f, 0.5f)), mode = BlendMode.Luminosity)
    val p = px(l, 0, 0); assert(p(0) > p(1) && p(0) > p(2), s"$p")
  }
  test("opacity and offset clip to destination") {
    val d = solid(4, 4, Color.Black); Blend.composite(d, solid(4, 4, Color.White), ox = 2, oy = 2, opacity = 0.5f)
    near(px(d, 3, 3), Seq(0.5, 0.5, 0.5, 1)); near(px(d, 1, 1), Seq(0, 0, 0, 1))
  }
  test("gaussian blur preserves energy and softens an impulse") {
    val p = new Pixmap(21, 21); p.data((10 * 21 + 10) * 4 + 3) = 1f
    val b = Filters.gaussianBlur(p, 2.0)
    var sum = 0.0; for y <- 0 until 21; x <- 0 until 21 do sum += b.data((y * 21 + x) * 4 + 3)
    assertEqualsDouble(sum, 1.0, 1e-3)
    assert(b.data((10 * 21 + 10) * 4 + 3) < 0.2f && b.data((10 * 21 + 12) * 4 + 3) > 0.01f)
  }
  test("color adjustments") {
    val p = solid(1, 1, Color(0.2f, 0.4f, 0.6f)); Filters.invert(p); near(px(p, 0, 0), Seq(0.8, 0.6, 0.4, 1))
    val g = solid(1, 1, Color(1, 0, 0)); Filters.grayscale(g); near(px(g, 0, 0), Seq(0.2126, 0.2126, 0.2126, 1))
    val s = solid(1, 1, Color(1, 0, 0)); Filters.saturation(s, 0f); near(px(s, 0, 0), Seq(0.2126, 0.2126, 0.2126, 1))
    val h = solid(1, 1, Color(1, 0, 0)); Filters.hueRotate(h, 120f); near(px(h, 0, 0), Seq(0, 1, 0, 1))
    val lv = solid(1, 1, Color(0.5f, 0.5f, 0.5f)); Filters.levels(lv, 0.25f, 0.75f); near(px(lv, 0, 0), Seq(0.5, 0.5, 0.5, 1))
    val lut = Array.tabulate(256)(i => 1f - i / 255f); val c = solid(1, 1, Color(0.25f, 0.5f, 1f)); Filters.curve(c, lut); near(px(c, 0, 0), Seq(0.75, 0.5, 0, 1), 0.01)
  }
  test("filters respect alpha (premultiplied)") {
    val p = solid(1, 1, Color(1, 0, 0, 0.5f)); Filters.invert(p)
    near(px(p, 0, 0), Seq(0, 0.5, 0.5, 0.5))
  }
  test("resize: constant color stays constant; sizes correct; downscale averages") {
    val p = solid(10, 8, Color(0.2f, 0.4f, 0.6f))
    for f <- ResizeFilter.values do
      val r = Resample.resize(p, 5, 4, f); assertEquals((r.width, r.height), (5, 4))
      near(px(r, 2, 2), Seq(0.2, 0.4, 0.6, 1), 0.02)
    val chk = new Pixmap(4, 1)
    for x <- 0 until 4 do { val v = if x % 2 == 0 then 1f else 0f; for c <- 0 until 4 do chk.data(x * 4 + c) = v }
    val d = Resample.resize(chk, 1, 1, ResizeFilter.Bilinear)
    assertEqualsDouble(d.data(0), 0.5, 0.05)
  }
  test("flip, rotate, crop") {
    val p = new Pixmap(3, 2); for x <- 0 until 3 do p.data(x * 4) = (x + 1) / 10f // row 0 red channel 0.1 0.2 0.3
    assertEqualsDouble(Resample.flipHorizontal(p).data(0), 0.3, 1e-6)
    val r = Resample.rotate90(p, 1); assertEquals((r.width, r.height), (2, 3))
    assertEqualsDouble(r.data((0 * 2 + 1) * 4), 0.1, 1e-6) // top-left pixel moves to top-right
    assertEquals(Resample.rotate90(Resample.rotate90(p, 1), 3).data.toSeq, p.data.toSeq)
    val c = Resample.crop(p, 1, 0, 2, 1); assertEquals((c.width, c.height), (2, 1)); assertEqualsDouble(c.data(0), 0.2, 1e-6)
  }
  test("histogram counts pixels") {
    val h = Filters.histogram(solid(4, 4, Color(1, 0, 0)))
    assertEquals(h(0)(255), 16); assertEquals(h(1)(0), 16)
  }

  test("layer applies opacity to isolated content") {
    val d = solid(4, 4, Color.White)
    Layers.layer(d, opacity = 0.5f)(_.clear(Color(1, 0, 0)))
    near(px(d, 1, 1), Seq(1, 0.5, 0.5, 1))
  }
  test("layer content is isolated: overlapping shapes do not blend with the backdrop inside the layer") {
    val d = solid(8, 8, Color.White)
    val full = scalaui.raster.Paint.solid(Color(0, 0, 1))
    Layers.layer(d, opacity = 0.5f) { l =>
      l.fill(scalaui.raster.Path.rect(scalaui.geom.Rect(0, 0, 6, 6)), full)
      l.fill(scalaui.raster.Path.rect(scalaui.geom.Rect(2, 2, 8, 8)), full)
    }
    near(px(d, 3, 3), Seq(0.5, 0.5, 1, 1)) // the overlap is not darker than the rest
    near(px(d, 1, 1), Seq(0.5, 0.5, 1, 1))
  }
  test("layer clip path restricts content with anti-aliased edges") {
    val d = solid(20, 20, Color.White)
    Layers.layer(d, clip = Some(scalaui.raster.Path.circle(scalaui.geom.Point(10, 10), 6)))(_.clear(Color(1, 0, 0)))
    near(px(d, 10, 10), Seq(1, 0, 0, 1))
    near(px(d, 0, 0), Seq(1, 1, 1, 1))
    val edge = px(d, 5, 5)(1) // partially covered boundary pixel
    assert(edge > 0.02f && edge < 0.98f, s"edge coverage $edge")
  }
  test("layer blend mode and mask") {
    val d = solid(2, 1, Color(0.5f, 0.5f, 0.5f))
    val mask = new Pixmap(2, 1); mask.data(3) = 1f // left pixel opaque, right transparent
    Layers.layer(d, mode = BlendMode.Multiply, mask = Some(mask))(_.clear(Color(1, 0, 0)))
    near(px(d, 0, 0), Seq(0.5, 0, 0, 1))
    near(px(d, 1, 0), Seq(0.5, 0.5, 0.5, 1))
  }

  test("gaussianBlurApprox (sliding box blurs) stays close to the exact kernel") {
    val p = new Pixmap(80, 60)
    for y <- 0 until 60; x <- 0 until 80 do
      val i = (y * 80 + x) * 4
      val on = ((x / 10) + (y / 10)) % 2 == 0
      p.data(i) = if on then 0.9f else 0.1f; p.data(i + 1) = if on then 0.2f else 0.7f; p.data(i + 2) = 0.5f; p.data(i + 3) = 1f
    for sigma <- Seq(3.0, 6.0) do
      val exact = Filters.gaussianBlur(p, sigma); val fast = Filters.gaussianBlurApprox(p, sigma)
      var maxDiff = 0f; var meanDiff = 0.0
      for i <- exact.data.indices do { val d = math.abs(exact.data(i) - fast.data(i)); maxDiff = math.max(maxDiff, d); meanDiff += d }
      meanDiff /= exact.data.length
      assert(maxDiff < 0.06f, s"sigma $sigma max diff $maxDiff")
      assert(meanDiff < 0.01, s"sigma $sigma mean diff $meanDiff")
    // flat images stay flat, alpha stays 1
    val flat = Filters.gaussianBlurApprox(solid(20, 20, Color(0.3f, 0.6f, 0.9f)), 5.0)
    near(px(flat, 10, 10), Seq(0.3, 0.6, 0.9, 1.0), 1e-4)
  }
