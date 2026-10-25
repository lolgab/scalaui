package scalaui.imaging

import scalaui.cms.{Intent, Profile, Transform}
import scalaui.codecs.{Image, Png}
import scalaui.deflate.{Crc32, Zlib}
import scalaui.raster.Pixmap

class ColorManagementSuite extends scalaui.testkit.LoggedSuite:
  private def be32(v: Int) = Array((v >>> 24).toByte, (v >>> 16).toByte, (v >>> 8).toByte, v.toByte)
  private def chunk(typ: String, body: Array[Byte]): Array[Byte] =
    val tb = typ.getBytes("ISO-8859-1") ++ body
    be32(body.length) ++ tb ++ be32(Crc32(tb))

  private def pngWithIcc(img: Image, icc: Array[Byte]): Array[Byte] =
    val png = Png.encode(img)
    val iccp = chunk("iCCP", "ICC\u0000\u0000".getBytes("ISO-8859-1") ++ Zlib.compress(icc))
    png.take(33) ++ iccp ++ png.drop(33)

  private val img = Image(2, 1, Array[Byte](10, 200.toByte, 90, 255.toByte, 250.toByte, 3, 128.toByte, 255.toByte))

  test("PNG iCCP profile is extracted and parsed") {
    val data = pngWithIcc(img, Profile.adobeRGB.bytes)
    val icc = Image.iccProfile(data).getOrElse(fail("no profile"))
    assertEquals(icc.toSeq, Profile.adobeRGB.bytes.toSeq)
    assertEquals(Profile.parse(icc).description, Some("Adobe RGB (1998)"))
    assertEquals(Image.iccProfile(Png.encode(img)), None)
    assertEquals(Png.decode(data).rgba.toSeq, img.rgba.toSeq) // decoder still happy with the extra chunk
  }

  test("JPEG APP2 profile chunks are reassembled in sequence order") {
    val icc = Profile.displayP3.bytes
    val magic = "ICC_PROFILE\u0000".getBytes("ISO-8859-1")
    val (a, b) = icc.splitAt(icc.length / 2)
    def app2(seq: Int, part: Array[Byte]) =
      val body = magic ++ Array(seq.toByte, 2.toByte) ++ part
      Array(0xff.toByte, 0xe2.toByte, ((body.length + 2) >> 8).toByte, (body.length + 2).toByte) ++ body
    val jpg = Array(0xff.toByte, 0xd8.toByte) ++ app2(2, b) ++ app2(1, a) ++ Array(0xff.toByte, 0xd9.toByte)
    assertEquals(Image.iccProfile(jpg).map(_.toSeq), Some(icc.toSeq))
  }

  test("toSrgb converts using the embedded profile") {
    val out = ColorManagement.toSrgb(pngWithIcc(img, Profile.adobeRGB.bytes))
    val expect = new Array[Byte](8)
    new Transform(Profile.adobeRGB, Profile.sRGB).rgba8(img.rgba, expect, 2)
    assertEquals(out.rgba.toSeq, expect.toSeq)
    assert(out.rgba.toSeq != img.rgba.toSeq)
    assertEquals(ColorManagement.toSrgb(Png.encode(img)).rgba.toSeq, img.rgba.toSeq)
  }

  test("pixmap conversion works on unpremultiplied colour and keeps alpha") {
    val pm = new Pixmap(1, 1)
    pm.data(0) = 0.4f * 0.5f; pm.data(1) = 0.6f * 0.5f; pm.data(2) = 0.2f * 0.5f; pm.data(3) = 0.5f
    val out = ColorManagement.convert(pm, Profile.displayP3, Profile.sRGB)
    val e = new Transform(Profile.displayP3, Profile.sRGB)(0.4f, 0.6f, 0.2f)
    assertEqualsDouble(out.data(0), e._1 * 0.5, 1e-5); assertEqualsDouble(out.data(1), e._2 * 0.5, 1e-5)
    assertEqualsDouble(out.data(3), 0.5, 1e-6)
    val same = ColorManagement.convert(pm, Profile.sRGB, Profile.sRGB, Intent.RelativeColorimetric)
    assertEqualsDouble(same.data(1), pm.data(1), 1e-4)
  }
