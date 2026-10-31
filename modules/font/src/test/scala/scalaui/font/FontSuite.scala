package scalaui.font

import scalaui.raster.PathEl

class FontSuite extends scalaui.testkit.LoggedSuite:
  test("bundled Noto Sans basics") {
    val f = FontFace.notoSans
    assertEquals(f.unitsPerEm, 1000)
    assert(f.numGlyphs > 1000)
    val a = f.glyphIndex('A')
    assert(a > 0)
    assert(f.advance(a) > 300 && f.advance(a) < 900)
    assert(f.outline(a).elements.nonEmpty)
    assert(f.outline(a).elements.last == PathEl.Close)
    assert(f.outline(f.glyphIndex(' ')).elements.isEmpty)
    assertEquals(f.glyphIndex(0x10ffff), 0)
  }
  test("composite glyph resolves (é)") {
    val f = FontFace.notoSans
    val g = f.glyphIndex('é')
    assert(g > 0)
    assert(f.outline(g).elements.size > f.outline(f.glyphIndex('e')).elements.size)
  }
  test("Hack is monospaced") {
    val f = FontFace.hack
    assertEquals(f.advance(f.glyphIndex('i')), f.advance(f.glyphIndex('W')))
  }

  test("names and styles of bundled fonts") {
    assertEquals(FontFace.notoSans.familyName, "Noto Sans")
    assert(!FontFace.notoSans.isBold && !FontFace.notoSans.isItalic)
    assert(FontFace.notoSansBold.isBold && !FontFace.notoSansBold.isItalic)
    assert(FontFace.notoSansItalic.isItalic && !FontFace.notoSansItalic.isBold)
    assert(FontFace.notoSansBoldItalic.isBold && FontFace.notoSansBoldItalic.isItalic)
    assert(FontFace.hack.isMonospace && !FontFace.notoSans.isMonospace)
    assertEquals(FontFamily.NotoSans(true, true), FontFace.notoSansBoldItalic)
    assertEquals(FontFamily.Hack(false, true), FontFace.hack) // no italic: falls back to regular
  }
  test("system font scan finds some family (when the OS has fonts)") {
    if SystemFonts.directories.nonEmpty then assert(SystemFonts.families.nonEmpty)
  }

  test("CFF OpenType (Source Sans 3): metrics, names, outlines") {
    val in = getClass.getResourceAsStream("/fonts/SourceSans3-Regular.otf"); require(in != null)
    val f = try FontFace(in.readAllBytes()) finally in.close()
    assert(!f.isTrueType)
    assertEquals(f.unitsPerEm, 1000)
    assert(f.familyName.startsWith("Source Sans"), f.familyName)
    val a = f.glyphIndex('A'); assert(a > 0)
    val path = f.outline(a)
    assert(path.elements.nonEmpty)
    val pts = path.flatten().flatMap(_._1)
    val (minX, maxX) = (pts.map(_.x).min, pts.map(_.x).max)
    val (minY, maxY) = (pts.map(_.y).min, pts.map(_.y).max)
    // capital A: sits on the baseline, about cap-height tall, and about as wide as its advance
    assert(minY > -20 && minY < 20, s"minY $minY")
    assert(maxY > 600 && maxY < 760, s"maxY $maxY")
    assert(maxX - minX > 400 && maxX - minX < f.advance(a), s"width ${maxX - minX} adv ${f.advance(a)}")
    assert(f.outline(f.glyphIndex(' ')).elements.isEmpty)
    // every glyph interprets without exceptions
    for g <- 0 until f.numGlyphs by 7 do f.outline(g)
  }

  test("render CFF text sample to target/cff.png for visual check") {
    import scalaui.raster.*; import scalaui.geom.*; import scalaui.color.Color
    val in = getClass.getResourceAsStream("/fonts/SourceSans3-Regular.otf")
    val f = try FontFace(in.readAllBytes()) finally in.close()
    val pm = new Pixmap(520, 120); pm.clear(Color.White)
    var x = 10.0; val s = 56.0 / f.unitsPerEm
    "Hamburgefonts & 0123".foreach { ch =>
      val g = f.glyphIndex(ch)
      pm.fill(f.outline(g), Paint.solid(Color.Black), transform = Affine(s, 0, 0, -s, x, 80))
      x += f.advance(g) * s
    }
    java.nio.file.Files.write(java.nio.file.Paths.get("target/cff.png"), scalaui.codecs.Png.encode(scalaui.codecs.Image(pm.width, pm.height, pm.toRgba8)))
  }

  test("WOFF 1.0 round trip (tables compressed with our zlib)") {
    import java.io.ByteArrayOutputStream
    val in = getClass.getResourceAsStream("/fonts/Hack-Regular.ttf"); val ttf = try in.readAllBytes() finally in.close()
    def u16(o: Int) = ((ttf(o) & 0xff) << 8) | (ttf(o + 1) & 0xff)
    def u32(o: Int) = (u16(o) << 16) | u16(o + 2)
    val n = u16(4)
    val tabs = (0 until n).map(i => (new String(ttf, 12 + i * 16, 4, "ISO-8859-1"), u32(12 + i * 16 + 4), u32(12 + i * 16 + 8), u32(12 + i * 16 + 12)))
    val comp = tabs.map { (_, _, off, len) => val raw = java.util.Arrays.copyOfRange(ttf, off, off + len); val z = scalaui.deflate.Zlib.compress(raw, 6); if z.length < raw.length then z else raw }
    val out = new ByteArrayOutputStream()
    def w32(v: Int): Unit = { out.write(v >>> 24); out.write(v >>> 16); out.write(v >>> 8); out.write(v) }
    def w16(v: Int): Unit = { out.write(v >>> 8); out.write(v) }
    out.write("wOFF".getBytes); w32(0x00010000); w32(0); w16(n); w16(0); w32(0); w16(1); w16(0); w32(0); w32(0); w32(0); w32(0); w32(0)
    var off = 44 + n * 20
    tabs.zip(comp).foreach { case ((tag, cs, _, len), c) =>
      out.write(tag.getBytes); w32(off); w32(c.length); w32(len); w32(cs); off += (c.length + 3) / 4 * 4 }
    comp.foreach { c => out.write(c); for _ <- 0 until ((4 - c.length % 4) % 4) do out.write(0) }
    val woff = FontFace.fromWoff(out.toByteArray)
    val orig = FontFace(ttf)
    assertEquals(woff.numGlyphs, orig.numGlyphs)
    for ch <- "AgW@" do
      assertEquals(woff.glyphIndex(ch), orig.glyphIndex(ch))
      assertEquals(woff.outline(woff.glyphIndex(ch)).elements, orig.outline(orig.glyphIndex(ch)).elements)
  }

  // --- WOFF2: fixtures made from the TTF/OTF next to them (fonttools `ttLib.woff2 compress` and Google's woff2_compress) ---
  private def fixture(name: String): Array[Byte] =
    val in = getClass.getResourceAsStream(s"/fonts/$name"); require(in != null, name)
    try in.readAllBytes() finally in.close()

  private def sameFont(a: FontFace, b: FontFace, cps: Seq[Int]): Unit =
    assertEquals(a.unitsPerEm, b.unitsPerEm)
    assertEquals(a.numGlyphs, b.numGlyphs)
    assertEquals((a.ascent, a.descent, a.lineGap), (b.ascent, b.descent, b.lineGap))
    assertEquals(a.familyName, b.familyName)
    assertEquals(a.weight, b.weight)
    for cp <- cps do assertEquals(a.glyphIndex(cp), b.glyphIndex(cp), s"cmap U+${cp.toHexString}")
    for g <- 0 until a.numGlyphs do
      assertEquals(a.advance(g), b.advance(g), s"advance of glyph $g")
      assertEquals(a.leftSideBearing(g), b.leftSideBearing(g), s"lsb of glyph $g")
      assertEquals(a.outline(g).elements, b.outline(g).elements, s"outline of glyph $g")
    for tag <- Seq("GSUB", "GPOS", "GDEF", "name", "OS/2", "cmap") if a.hasTable(tag) do
      assert(b.hasTable(tag), tag)
      val (ra, oa) = a.rawTable(tag).get; val (rb, ob) = b.rawTable(tag).get
      val len = a.tableRange(tag)._2
      assertEquals(b.tableRange(tag)._2, len, s"$tag length")
      assert(java.util.Arrays.equals(ra, oa, oa + len, rb, ob, ob + len), s"$tag bytes")

  private val latin = (0x20 to 0x2200) ++ (0x600 to 0x6ff)

  test("WOFF2 (fonttools, glyf+loca+hmtx transforms, composite glyphs, mono hmtx tail) matches the TTF") {
    val ttf = FontFace(fixture("NotoSansHm.ttf"))
    val w = fixture("NotoSansHm.fonttools.woff2")
    assertEquals(new String(w, 0, 4, "ISO-8859-1"), "wOF2")
    sameFont(ttf, FontFace(w), latin)
    sameFont(ttf, FontFace.fromWoff2(w), latin)
  }
  test("WOFF2 (Google woff2_compress) matches the TTF") {
    sameFont(FontFace(fixture("NotoSansHm.ttf")), FontFace(fixture("NotoSansHm.google.woff2")), latin)
  }
  test("WOFF2 Arabic subset matches the TTF") {
    sameFont(FontFace(fixture("ArabicSub.ttf")), FontFace(fixture("ArabicSub.woff2")), latin)
  }
  test("WOFF2 with CFF outlines (untransformed) matches the OTF") {
    val otf = FontFace(fixture("SSub.otf")); val w = FontFace(fixture("SSub.woff2"))
    assert(!w.isTrueType)
    sameFont(otf, w, latin)
  }
  test("WOFF2 rejects garbage") {
    val w = fixture("ArabicSub.woff2")
    intercept[IllegalArgumentException](Woff2.toSfnt(w.take(30)))
    intercept[IllegalArgumentException](Woff2.toSfnt(w.take(w.length - 2000)))
  }

