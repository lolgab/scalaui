package scalaui.codecs

import java.nio.file.{Files, Paths}

/** Shared helpers for decoding fixtures against their ImageMagick/libwebp reference PNGs. */
object Fixtures:
  private val root = "modules/codecs/src/test/resources"
  def bytes(path: String): Array[Byte] = Files.readAllBytes(Paths.get(s"$root/$path"))
  def reference(path: String): Image = Png.decode(bytes(path))

  /** Largest per-channel difference between two equally sized images. */
  def maxDiff(a: Image, b: Image): Int =
    require(a.width == b.width && a.height == b.height, s"size ${a.width}x${a.height} vs ${b.width}x${b.height}")
    var m = 0
    var i = 0
    while i < a.rgba.length do
      // transparent pixels have no meaningful colour
      if (a.rgba(i + 3) & 0xff) != 0 || (b.rgba(i + 3) & 0xff) != 0 then
        var c = 0
        while c < 4 do { m = math.max(m, math.abs((a.rgba(i + c) & 0xff) - (b.rgba(i + c) & 0xff))); c += 1 }
      else m = math.max(m, math.abs((a.rgba(i + 3) & 0xff) - (b.rgba(i + 3) & 0xff)))
      i += 4
    m

  /** Mean absolute per-channel difference over all pixels. */
  def meanDiff(a: Image, b: Image): Double =
    var sum = 0L; var i = 0
    while i < a.rgba.length do { sum += math.abs((a.rgba(i) & 0xff) - (b.rgba(i) & 0xff)); i += 1 }
    sum.toDouble / a.rgba.length

  def assertMatches(path: String, tolerance: Int = 0): Unit =
    val got = Image.decode(bytes(path))
    val want = reference(path.substring(0, path.lastIndexOf('.')) + ".ref.png")
    val d = maxDiff(got, want)
    if d > tolerance then throw new AssertionError(s"$path differs from its reference by up to $d (tolerance $tolerance)")

class BmpSuite extends scalaui.testkit.LoggedSuite:
  for (name, tol) <- Seq("rgb24" -> 0, "rgb24_topdown" -> 0, "bgra32" -> 0, "pal4" -> 0, "pal8" -> 0, "mono1" -> 0, "rle8" -> 0, "rle4" -> 0, "rgb555" -> 1, "rgb565" -> 1) do
    test(s"decodes bmp/$name.bmp like ImageMagick") { Fixtures.assertMatches(s"bmp/$name.bmp", tol) }

  test("encode/decode round trip (opaque and with alpha)") {
    val rgba = Array.tabulate(7 * 5 * 4)(i => (i * 37 % 251).toByte)
    val translucent = Image(7, 5, rgba)
    val back = Bmp.decode(Bmp.encode(translucent))
    assert(java.util.Arrays.equals(back.rgba, translucent.rgba))
    val opaque = Image(7, 5, rgba.zipWithIndex.map((b, i) => if i % 4 == 3 then (-1).toByte else b))
    val enc = Bmp.encode(opaque)
    assertEquals(enc(28), 24.toByte)
    assert(java.util.Arrays.equals(Bmp.decode(enc).rgba, opaque.rgba))
  }

class GifSuite extends scalaui.testkit.LoggedSuite:
  for name <- Seq("static", "transparent", "interlaced") do
    test(s"decodes gif/$name.gif like ImageMagick") { Fixtures.assertMatches(s"gif/$name.gif") }

  test("animated gif: frames, delays and disposal match the coalesced reference") {
    val anim = Gif.decodeAnimation(Fixtures.bytes("gif/anim.gif"))
    assertEquals(anim.frames.size, 4)
    assertEquals(anim.frames.map(_.delayMs), Vector(100, 200, 300, 400))
    assertEquals(anim.loopCount, 0)
    anim.frames.zipWithIndex.foreach { (f, i) =>
      val d = Fixtures.maxDiff(f.image, Fixtures.reference(s"gif/anim-$i.ref.png"))
      assertEquals(d, 0, s"frame $i")
    }
  }
  test("encode/decode round trip keeps palette images exactly and approximates others") {
    val img = Image.decode(Fixtures.bytes("gif/static.gif"))
    val back = Gif.decode(Gif.encode(img))
    assertEquals(Fixtures.maxDiff(back, img), 0)
    val photo = Fixtures.reference("bmp/rgb24.ref.png")
    val q = Gif.decode(Gif.encode(photo))
    assert(Fixtures.maxDiff(q, photo) < 80, "quantization error should be bounded")
    val transparent = Fixtures.reference("gif/transparent.ref.png")
    val t = Gif.decode(Gif.encode(transparent))
    assertEquals((0 until t.width * t.height).count(i => (t.rgba(i * 4 + 3) & 0xff) == 0) > 0, true)
  }
  test("animation round trip preserves frame count and delays") {
    val a = Gif.decodeAnimation(Fixtures.bytes("gif/anim.gif"))
    val b = Gif.decodeAnimation(Gif.encodeAnimation(a))
    assertEquals(b.frames.map(_.delayMs), a.frames.map(_.delayMs))
    b.frames.zip(a.frames).foreach((x, y) => assert(Fixtures.maxDiff(x.image, y.image) <= 1))
  }

class TiffSuite extends scalaui.testkit.LoggedSuite:
  for (name, tol) <- Seq("rgb_none" -> 0, "rgba_none" -> 0, "rgb_lzw" -> 0, "rgb_zip" -> 0, "rgb_packbits" -> 0, "rgba_lzw_pred" -> 0, "gray" -> 0,
      "palette" -> 0, "rgb16" -> 1, "rgb_be" -> 0, "rgb_strips" -> 0, "rgb_tiled" -> 0) do
    test(s"decodes tiff/$name.tif like ImageMagick") { Fixtures.assertMatches(s"tiff/$name.tif", tol) }

  test("encode/decode round trip, plain and deflate with predictor") {
    val img = Fixtures.reference("gif/transparent.ref.png") // has fully transparent and opaque pixels
    assertEquals(Fixtures.maxDiff(Tiff.decode(Tiff.encode(img)), img), 0)
    assertEquals(Fixtures.maxDiff(Tiff.decode(Tiff.encode(img, deflate = true)), img), 0)
    val opaque = Fixtures.reference("tiff/rgb_none.ref.png")
    assertEquals(Fixtures.maxDiff(Tiff.decode(Tiff.encode(opaque, deflate = true)), opaque), 0)
  }

class WebpSuite extends scalaui.testkit.LoggedSuite:
  for name <- Seq("lossless_rgba", "lossless_rgb", "lossless_pal", "lossless_big_z0", "lossless_big_z3", "lossless_big_z6", "lossless_big_z9", "lossless_big_nl") do
    test(s"decodes webp/$name.webp bit-exactly like libwebp") { Fixtures.assertMatches(s"webp/$name.webp") }
  for name <- Seq("lossy_q80", "lossy_q30", "lossy_alpha", "lossy_partitions", "lossy_big_q85", "lossy_big_q20", "lossy_big_q95") do
    test(s"decodes webp/$name.webp bit-exactly like libwebp") { Fixtures.assertMatches(s"webp/$name.webp") }

class WebpAnimationSuite extends scalaui.testkit.LoggedSuite:
  test("the comparison is sensitive: a different quality does not match another file's reference") {
    val got = Image.decode(Fixtures.bytes("webp/lossy_q30.webp"))
    assert(Fixtures.maxDiff(got, Fixtures.reference("webp/lossy_q80.ref.png")) > 2)
  }
  test("lossless animation composites to the GIF's coalesced frames") {
    val anim = Webp.decodeAnimation(Fixtures.bytes("webp/anim_lossless.webp"))
    assertEquals(anim.frames.map(_.delayMs), Vector(100, 200, 300, 400))
    assertEquals((anim.width, anim.height), (64, 48))
    anim.frames.zipWithIndex.foreach { (f, i) => assertEquals(Fixtures.maxDiff(f.image, Fixtures.reference(s"gif/anim-$i.ref.png")), 0, s"frame $i") }
  }
  test("lossy animation decodes with the right shape and timing") {
    val anim = Webp.decodeAnimation(Fixtures.bytes("webp/anim_lossy.webp"))
    assertEquals(anim.frames.size, 4)
    assertEquals(anim.frames.map(_.delayMs), Vector(100, 200, 300, 400))
    assert(anim.frames.forall(f => f.image.width == 64 && f.image.height == 48))
    // lossy frames approximate the GIF frames
    anim.frames.zipWithIndex.foreach { (f, i) => assert(Fixtures.meanDiff(f.image, Fixtures.reference(s"gif/anim-$i.ref.png")) < 4.0, s"frame $i") }
  }

class JpegEncoderSuite extends scalaui.testkit.LoggedSuite:
  private def psnr(a: Image, b: Image): Double =
    var se = 0.0; var n = 0; var i = 0
    while i < a.rgba.length do
      if i % 4 != 3 then { val d = (a.rgba(i) & 0xff) - (b.rgba(i) & 0xff); se += d * d; n += 1 }
      i += 1
    10 * math.log10(255.0 * 255.0 / math.max(se / n, 1e-9))
  private val photo = Fixtures.reference("bmp/rgb24.ref.png")

  test("round trip through the decoder keeps high PSNR at quality 90, 4:4:4 and 4:2:0") {
    assert(psnr(photo, Jpeg.decode(JpegEncoder.encode(photo, 90, subsample = false))) > 32)
    assert(psnr(photo, Jpeg.decode(JpegEncoder.encode(photo, 90, subsample = true))) > 23) // chroma subsampling blurs this picture's hard colour edges
  }
  test("lower quality gives smaller files and lower PSNR") {
    val hi = JpegEncoder.encode(photo, 95); val lo = JpegEncoder.encode(photo, 20)
    assert(lo.length < hi.length)
    assert(psnr(photo, Jpeg.decode(lo)) < psnr(photo, Jpeg.decode(hi)))
  }
  test("grayscale and odd sizes work") {
    val g = Image(13, 7, Array.tabulate(13 * 7 * 4)(i => if i % 4 == 3 then (-1).toByte else ((i / 4 * 5) % 256).toByte))
    val d = Jpeg.decode(JpegEncoder.encode(g, 95, grayscale = true))
    assertEquals((d.width, d.height), (13, 7))
  }
  test("alpha is composited onto the background") {
    val clear = Image(8, 8, Array.fill(8 * 8 * 4)(0.toByte))
    val d = Jpeg.decode(JpegEncoder.encode(clear, 95, background = 0xFF0000))
    assert((d.rgba(0) & 0xff) > 240 && (d.rgba(1) & 0xff) < 20)
  }

class WebpEncoderSuite extends scalaui.testkit.LoggedSuite:
  test("lossless WebP round trip is exact, with and without alpha, for tiny and larger images") {
    for name <- Seq("bmp/rgb24.ref.png", "gif/transparent.ref.png", "webp/lossless_big_z6.ref.png") do
      val img = Fixtures.reference(name)
      assertEquals(Fixtures.maxDiff(Webp.decode(WebpEncoder.encode(img)), img), 0, name)
    val one = Image(1, 1, Array[Byte](10, 20, 30, 40))
    assert(java.util.Arrays.equals(Webp.decode(WebpEncoder.encode(one)).rgba, one.rgba))
    val flat = Image(5, 3, Array.fill(5 * 3 * 4)(7.toByte))
    assert(java.util.Arrays.equals(Webp.decode(WebpEncoder.encode(flat)).rgba, flat.rgba))
  }

class ImageFacadeSuite extends scalaui.testkit.LoggedSuite:
  test("every writable format round-trips through Image.decode (lossy ones within tolerance)") {
    val img = Fixtures.reference("bmp/rgb24.ref.png")
    for (f, tol) <- Seq(ImageFormat.Png -> 0, ImageFormat.Bmp -> 0, ImageFormat.Tiff -> 0, ImageFormat.Webp -> 0, ImageFormat.Jpeg -> -1, ImageFormat.Gif -> -1) do
      val back = Image.decode(Image.encode(img, f))
      assertEquals((back.width, back.height), (img.width, img.height), f.toString)
      if tol >= 0 then assertEquals(Fixtures.maxDiff(back, img), tol, f.toString) else assert(Fixtures.meanDiff(back, img) < 8.0, s"$f: ${Fixtures.meanDiff(back, img)}")
  }
  test("decodeAnimation gives frames for GIF/WebP and a single frame for stills") {
    assertEquals(Image.decodeAnimation(Fixtures.bytes("gif/anim.gif")).frames.size, 4)
    assertEquals(Image.decodeAnimation(Fixtures.bytes("webp/anim_lossless.webp")).frames.size, 4)
    assertEquals(Image.decodeAnimation(Fixtures.bytes("bmp/rgb24.bmp")).frames.size, 1)
  }
