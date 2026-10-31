package scalaui.cms

import java.nio.file.{Files, Paths}

class CmsSuite extends scalaui.testkit.LoggedSuite:
  private val SysDir = "/System/Library/ColorSync/Profiles/"
  private def sys(name: String): Option[Profile] =
    val p = Paths.get(SysDir + name)
    if Files.exists(p) then Some(Profile.parse(Files.readAllBytes(p))) else None
  private def golden: Vector[Array[String]] =
    val in = getClass.getResourceAsStream("/cms/golden.txt")
    require(in != null, "missing golden.txt")
    try new String(in.readAllBytes(), "UTF-8").linesIterator.filter(_.nonEmpty).map(_.split('|')).toVector finally in.close()
  private def nums(s: String) = s.trim.split(' ').map(_.toDouble)
  private def intentOf(i: Int) = i match
    case 0 => Intent.Perceptual; case 1 => Intent.RelativeColorimetric; case 2 => Intent.Saturation; case _ => Intent.AbsoluteColorimetric

  /** Max 8-bit error against littleCMS golden values, per (src, dst, intent, bpc) group. */
  private def check(tol: Double, only: Array[String] => Boolean, resolve: String => Option[Profile]): Unit =
    val groups = golden.filter(only).groupBy(g => (g(0), g(1), g(2), g(3)))
    assert(groups.nonEmpty)
    val bad = scala.collection.mutable.ArrayBuffer.empty[String]
    for ((s, d, i, b), rows) <- groups do
      for sp <- resolve(s); dp <- resolve(d) do
        val t = new Transform(sp, dp, intentOf(i.toInt), b == "1")
        var worst = 0.0; var wr = ""
        for r <- rows do
          val out = t(nums(r(4)))
          val exp = nums(r(5)).map(v => math.max(0, math.min(1, v)))
          val e = out.zip(exp).map((a, x) => math.abs(a - x) * 255).max
          if e > worst then { worst = e; wr = s"in=${r(4)} got=${out.map(v => f"$v%.4f").mkString(" ")} want=${r(5)}" }
        if worst > tol then bad += f"$s -> $d intent=$i bpc=$b max error ${worst}%.2f/255 ($wr)"
    assert(bad.isEmpty, bad.mkString("\n"))

  test("system profiles vs littleCMS: matrix/TRC and gray") {
    check(1.5, g => g(0) != "Generic CMYK Profile.icc" && g(1) != "Generic CMYK Profile.icc", sys)
  }

  test("system CMYK LUT profile vs littleCMS") {
    // sRGB/AdobeRGB -> CMYK: most saturated sources are outside the CMYK gamut, where B2A values depend on CLUT edge behaviour; allow 6/255
    check(2.5, g => g(0) == "Generic CMYK Profile.icc", sys)
    check(6.0, g => g(1) == "Generic CMYK Profile.icc", sys)
  }

  test("built-in sRGB, Display P3, Adobe RGB vs littleCMS") {
    val builtin = Map("sRGB Profile.icc" -> Profile.sRGB, "Display P3.icc" -> Profile.displayP3, "AdobeRGB1998.icc" -> Profile.adobeRGB)
    check(2.0, g => builtin.contains(g(0)) && builtin.contains(g(1)), builtin.get)
  }

  test("built-in profiles round-trip through the writer and parser") {
    for p <- Seq(Profile.sRGB, Profile.displayP3, Profile.adobeRGB, Profile.linearSRGB) do
      val q = Profile.parse(p.bytes)
      assertEquals(q.colorSpace, "RGB ")
      assertEquals(q.pcs, "XYZ ")
      assertEquals(q.version, (4, 3))
      assert(q.description.exists(_.nonEmpty))
      assert(q.chad.isDefined)
    val t = new Transform(Profile.sRGB, Profile.linearSRGB)
    val (r, _, _) = t(0.5f, 0.5f, 0.5f)
    assertEqualsDouble(r, 0.2140, 1e-3)
  }

  test("built-in sRGB colorants agree with Apple's sRGB profile") {
    sys("sRGB Profile.icc").foreach { apple =>
      val a = Profile.sRGB
      for t <- Seq("rXYZ", "gXYZ", "bXYZ") do
        val x = a.tag(t).get; val y = apple.tag(t).get
        for o <- Seq(8, 12, 16) do
          val va = Profile.s15(x, o); val vb = Profile.s15(y, o)
          assertEqualsDouble(va, vb, 0.0015, s"$t@$o")
    }
  }

  test("sRGB identity transform is exact at 8 bits and keeps alpha") {
    val t = new Transform(Profile.sRGB, Profile.sRGB)
    val src = Array.tabulate(256 * 4)(i => if i % 4 == 3 then (i / 4).toByte else ((i * 7 + i / 4) & 0xff).toByte)
    val dst = new Array[Byte](src.length)
    t.rgba8(src, dst, 256)
    for i <- src.indices do assertEquals(dst(i) & 0xff, src(i) & 0xff, s"byte $i")
  }

  test("P3 primaries map outside sRGB, sRGB white stays white") {
    val t = new Transform(Profile.displayP3, Profile.sRGB, Intent.RelativeColorimetric)
    val (r, g, b) = t(1f, 0f, 0f)
    assertEquals((r, g, b), (1f, 0f, 0f)) // clipped
    val w = t(1f, 1f, 1f)
    assertEqualsDouble(w._1, 1.0, 1e-4); assertEqualsDouble(w._3, 1.0, 1e-4)
    val s2p = new Transform(Profile.sRGB, Profile.displayP3)
    val o = s2p(Array(1.0, 0.0, 0.0))
    assertEqualsDouble(o(0), 0.9175, 2e-3) // sRGB red in P3
  }

  test("float and 8-bit conversion agree") {
    val t = new Transform(Profile.adobeRGB, Profile.sRGB)
    val bytes = Array[Byte](10, 200.toByte, 90, 77, 250.toByte, 3, 128.toByte, 255.toByte)
    val out = new Array[Byte](8)
    t.rgba8(bytes, out, 2)
    val f = Array(10 / 255f, 200 / 255f, 90 / 255f, 250 / 255f, 3 / 255f, 128 / 255f)
    val fo = new Array[Float](6)
    t.convert(f, fo, 2)
    for (i, j) <- Seq((0, 0), (1, 1), (2, 2), (4, 3), (5, 4), (6, 5)) do assert(math.abs((out(i) & 0xff) - fo(j) * 255) <= 0.51, s"$i")
    assertEquals(out(3), bytes(3), "a0"); assertEquals(out(7), bytes(7), "a1")
  }

  test("Bradford adaptation maps white to white and is invertible") {
    val m = Adaptation.bradford(Adaptation.D65, Adaptation.D50)
    val (x, y, z) = Mat3(m, Adaptation.D65._1, Adaptation.D65._2, Adaptation.D65._3)
    assertEqualsDouble(x, Adaptation.D50._1, 1e-9); assertEqualsDouble(y, 1.0, 1e-9); assertEqualsDouble(z, Adaptation.D50._3, 1e-9)
    val back = Mat3.mul(Mat3.inverse(m).get, m)
    for i <- 0 until 9 do assertEqualsDouble(back(i), Mat3.identity(i), 1e-9)
  }

  test("curves: inverse of every type") {
    val cs = Seq(Curve.Gamma(2.2), Curve.Parametric(0, Array(1.8)), Curve.Parametric(1, Array(2.0, 0.9, 0.1)), Curve.Parametric(2, Array(2.0, 0.9, 0.1, 0.05)),
      Curve.Parametric(3, Array(2.4, 1 / 1.055, 0.055 / 1.055, 1 / 12.92, 0.04045)),
      Curve.Parametric(4, Array(2.4, 1 / 1.055, 0.055 / 1.055, 1 / 12.92, 0.04045, 0.0, 0.0)),
      Curve.Table(Array.tabulate(64)(i => math.pow(i / 63.0, 1.7).toFloat)))
    for c <- cs; x <- Seq(0.1, 0.3, 0.55, 0.9) do assertEqualsDouble(c.inverse(c.eval(x)), x, 2e-3, c.toString)
  }

  test("profile writer: v2 profile and gray profile parse back") {
    val v2 = Profile.parse(ProfileWriter.fromPrimaries("v2 test", (0.64, 0.33), (0.30, 0.60), (0.15, 0.06), (0.3127, 0.3290), Curve.Gamma(2.2), version = 2))
    assertEquals(v2.version._1, 2)
    assertEquals(v2.description, Some("v2 test"))
    val g = Profile.parse(ProfileWriter.gray("g", Curve.Gamma(2.2)))
    assertEquals(g.channels, 1)
    val t = new Transform(g, Profile.sRGB)
    val o = t(Array(0.5))
    assertEqualsDouble(o(0), o(1), 1e-4)
    assertEqualsDouble(o(0), 0.5, 0.05)
  }

  test("synthetic lut16 (Lab PCS) and mAB profiles reproduce the matrix profile") {
    val ref = Profile.sRGB
    for kind <- Seq("mft2", "mft1", "mAB ") do
      val lutProfile = Profile.parse(SyntheticLut.rgbToLab(ref, kind))
      assertEquals(lutProfile.pcs, "Lab ")
      val t = new Transform(lutProfile, Profile.displayP3, Intent.RelativeColorimetric)
      val r = new Transform(ref, Profile.displayP3, Intent.RelativeColorimetric)
      val rnd = new scala.util.Random(3)
      for _ <- 0 until 200 do
        val in = Array.fill(3)(rnd.nextDouble())
        val a = t(in); val b = r(in)
        val e = a.zip(b).map((x, y) => math.abs(x - y) * 255).max
        assert(e < (if kind == "mft1" then 4.0 else 2.0), s"$kind $in error $e")
  }

  test("black point compensation darkens CMYK black into sRGB black") {
    for cmyk <- sys("Generic CMYK Profile.icc") do
      val t = new Transform(cmyk, Profile.sRGB, Intent.RelativeColorimetric, true)
      val o = t(Array(0.0, 0.0, 0.0, 1.0))
      assert(o.forall(_ < 0.15), o.mkString(","))
  }

  test("rejects corrupt profiles") {
    intercept[CmsException](Profile.parse(new Array[Byte](200)))
    val b = Profile.sRGB.bytes.clone(); b(128) = 0x7f
    intercept[CmsException](Profile.parse(b))
  }
