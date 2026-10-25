package scalaui.shaping

import scalaui.font.FontFace

class ShaperSuite extends scalaui.testkit.LoggedSuite:
  test("shapes ascii, clusters track utf16 index") {
    val s = new Shaper(FontFace.notoSans)
    val g = s.shape("Hi!", 16)
    assertEquals(g.map(_.cluster), Vector(0, 1, 2))
    assert(g.forall(_.xAdvance > 0))
  }
  test("kerning pair AV is tighter than sum of advances") {
    val f = FontFace.notoSans
    val s = new Shaper(f)
    val av = s.shape("AV", 1000)
    val plain = f.advance(f.glyphIndex('A')).toDouble
    assert(av.head.xAdvance < plain, s"AV kern missing: ${av.head.xAdvance} vs $plain")
  }
  test("surrogate pairs advance by two UTF-16 units") {
    val s = new Shaper(FontFace.notoSans)
    assertEquals(s.shape("a😀b", 10).map(_.cluster), Vector(0, 1, 3))
  }

  test("font fallback picks the first face that has the glyph") {
    val hack = FontFace.hack; val noto = FontFace.notoSans
    // a character only Hack has (box drawing / block elements), and one only Noto has
    val onlyHack = (0x2500 to 0x259f).find(cp => hack.glyphIndex(cp) != 0 && noto.glyphIndex(cp) == 0)
    assert(onlyHack.isDefined, "need a code point only Hack has")
    val s = new Shaper(Seq(noto, hack))
    val g = s.shape("a" + new String(Character.toChars(onlyHack.get)), 16)
    assert(g(0).font eq noto)
    assert(g(1).font eq hack)
    assert(g(1).glyph != 0)
  }
