package scalaui.imaging

import java.nio.file.{Files, Paths}
import scalaui.codecs.{Image, Png}
import scalaui.font.FontFace
import scalaui.geom.*
import scalaui.raster.Pixmap

/** Renders every colour glyph of the fonts in modules/font/src/test/resources/color on a 160x160 canvas (100 px per em, origin at (20, 120))
  * into target/color-out; tools/color_glyph_ref.py renders the same glyphs with BlackRenderer/Skia and Pillow, tools/color_glyph_compare.py compares.
  */
class ColorGlyphSuite extends scalaui.testkit.LoggedSuite:
  private val dir = Paths.get("modules/font/src/test/resources/color")
  private val out = Paths.get(sys.env.getOrElse("COLOR_OUT", "target/color-out"))
  private def load(name: String) = FontFace(Files.readAllBytes(dir.resolve(name)))
  private def png(name: String, pm: Pixmap): Unit = { Files.createDirectories(out); Files.write(out.resolve(name), Png.encode(Image(pm.width, pm.height, pm.toRgba8), 6)) }

  private def renderAll(file: String): Int =
    val f = load(file); val base = file.stripSuffix(".ttf"); var n = 0
    for gid <- 0 until f.numGlyphs if ColorGlyphs.hasColor(f, gid) do
      val pm = new Pixmap(160, 160); val s = 100.0 / f.unitsPerEm
      assert(ColorGlyphs.draw(pm, f, gid, Affine(s, 0, 0, -s, 20, 120)))
      png(s"$base-g$gid.png", pm); n += 1
    n

  test("render all colour glyphs of the test fonts") {
    val counts = Files.list(dir).toArray.map(_.toString).filter(_.endsWith(".ttf")).sorted.map(p => Paths.get(p).getFileName.toString).filterNot(_.contains("svg-subset")).map(f => f -> renderAll(f))
    counts.foreach((f, n) => assert(n > 0, s"$f rendered nothing"))
  }

  private def flatten(rgba: Array[Byte], w: Int, h: Int): Array[Int] =
    Array.tabulate(w * h * 3) { i =>
      val px = i / 3; val c = i % 3; val a = (rgba(px * 4 + 3) & 0xff) / 255.0
      math.round((rgba(px * 4 + c) & 0xff) * a + 255 * (1 - a)).toInt
    }

  test("matches BlackRenderer/Skia (COLR) and Pillow (bitmap) references") {
    val refs = Files.list(Paths.get("modules/imaging/src/test/resources/color-ref")).toArray.map(_.toString).filter(_.endsWith(".png")).sorted
    assert(refs.length > 50)
    val fonts = Files.list(dir).toArray.map(_.toString).filter(_.endsWith(".ttf")).map(p => Paths.get(p).getFileName.toString.stripSuffix(".ttf") -> load(Paths.get(p).getFileName.toString)).toMap
    val failures = refs.flatMap { path =>
      val name = Paths.get(path).getFileName.toString.stripSuffix(".png")
      val k = name.lastIndexOf("-g"); val face = fonts(name.substring(0, k)); val gid = name.substring(k + 2).toInt
      val pm = new Pixmap(160, 160); val s = 100.0 / face.unitsPerEm
      ColorGlyphs.draw(pm, face, gid, Affine(s, 0, 0, -s, 20, 120))
      val ref = Png.decode(Files.readAllBytes(Paths.get(path)))
      val a = flatten(ref.rgba, 160, 160); val b = flatten(pm.toRgba8, 160, 160)
      val mean = a.zip(b).map((x, y) => math.abs(x - y)).sum.toDouble / a.length
      if mean > 4.0 then Some(f"$name mean error $mean%.2f") else None
    }
    assert(failures.isEmpty, failures.mkString("; "))
  }
