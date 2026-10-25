package scalaui.svg

import java.nio.file.{Files, Paths, StandardOpenOption}
import scalaui.codecs.Png
import scalaui.font.{FontFace, FontFamily}
import scalaui.imaging.Pixmaps
import scala.jdk.CollectionConverters.*

/** Development harness: renders every `.svg` below `$SVG_CORPUS` into `$SVG_OUT` (PNG, same relative path); `$SVG_FONTS` is a
  * directory of fonts registered as families. Compared against resvg renders by tools/svg_compare.py. Does nothing when unset.
  */
class CorpusSuite extends scalaui.testkit.LoggedSuite:
  override val munitTimeout: scala.concurrent.duration.Duration = scala.concurrent.duration.Duration(3600, "s")
  test("render corpus") {
    for corpus <- sys.env.get("SVG_CORPUS"); out <- sys.env.get("SVG_OUT") do
      val filter = sys.env.get("SVG_FILTER").getOrElse("")
      val fonts: Seq[FontFamily] = sys.env.get("SVG_FONTS").toSeq.flatMap { dir =>
        val faces = Files.list(Paths.get(dir)).iterator.asScala.filter(p => p.toString.endsWith(".ttf") || p.toString.endsWith(".otf")).toSeq.sortBy(_.toString).flatMap { p =>
          try Some(FontFace(Files.readAllBytes(p))) catch case _: Exception => None
        }
        faces.groupBy(_.familyName).map { (name, fs) =>
          def pick(b: Boolean, i: Boolean) = fs.filter(f => f.isItalic == i).sortBy(f => math.abs(f.weight - (if b then 700 else 400))).headOption.filter(f => f.isBold == b || !b && f.weight <= 500 || b && f.weight >= 600)
          new FontFamily(name, pick(false, false).getOrElse(fs.head), pick(true, false), pick(false, true), pick(true, true))
        }.toSeq
      }
      val log = Paths.get(out, "progress.log")
      Files.createDirectories(Paths.get(out))
      Files.writeString(log, "")
      val files = Files.walk(Paths.get(corpus)).iterator.asScala.filter(_.toString.endsWith(".svg")).filter(_.toString.contains(filter)).toSeq.sortBy(_.toString)
      files.foreach { f =>
        val rel = Paths.get(corpus).relativize(f).toString
        Files.writeString(log, rel + "\n", StandardOpenOption.APPEND)
        val target = Paths.get(out, rel.stripSuffix(".svg") + ".png")
        try
          val opts = SvgOptions(resourceDir = Some(f.getParent.toString), fonts = fonts, systemFonts = false)
          val doc = SvgDocument.parse(new String(Files.readAllBytes(f), "UTF-8"), opts)
          val pm = doc.renderScaled(sys.env.get("SVG_WIDTH").map(_.toDouble / doc.width).getOrElse(1.0))
          Files.createDirectories(target.getParent)
          Files.write(target, Png.encode(Pixmaps.toImage(pm)))
        catch case e: Throwable => Files.writeString(Paths.get(out, "errors.log"), s"$rel: $e\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND)
      }
  }
