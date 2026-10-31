package scalaui.font

import java.nio.file.{Files, Path => JPath, Paths}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*

/** The four classic styles of a family; missing styles fall back to the closest available face. */
final class FontFamily(val name: String, val regular: FontFace, bold: Option[FontFace] = None, italic: Option[FontFace] = None, boldItalic: Option[FontFace] = None):
  def apply(isBold: Boolean, isItalic: Boolean): FontFace = (isBold, isItalic) match
    case (false, false) => regular
    case (true, false) => bold.getOrElse(regular)
    case (false, true) => italic.getOrElse(regular)
    case (true, true) => boldItalic.orElse(bold).orElse(italic).getOrElse(regular)

object FontFamily:
  lazy val NotoSans = new FontFamily("Noto Sans", FontFace.notoSans, Some(FontFace.notoSansBold), Some(FontFace.notoSansItalic), Some(FontFace.notoSansBoldItalic))
  lazy val Hack = new FontFamily("Hack", FontFace.hack, Some(FontFace.hackBold))
  /** Inter at three weights; "bold" of each is SemiBold and italics fall back to Noto Sans Italic (Inter italics are not bundled). */
  lazy val Inter = new FontFamily("Inter", FontFace.inter, Some(FontFace.interSemiBold), Some(FontFace.notoSansItalic), Some(FontFace.notoSansBoldItalic))
  lazy val InterMedium = new FontFamily("Inter Medium", FontFace.interMedium, Some(FontFace.interSemiBold), Some(FontFace.notoSansItalic), Some(FontFace.notoSansBoldItalic))
  lazy val InterSemiBold = new FontFamily("Inter SemiBold", FontFace.interSemiBold, Some(FontFace.interSemiBold), Some(FontFace.notoSansBoldItalic), Some(FontFace.notoSansBoldItalic))
  lazy val JetBrainsMono = new FontFamily("JetBrains Mono", FontFace.jetBrainsMono, Some(FontFace.hackBold))

/** Finds fonts installed on the system (macOS, Linux, Windows font directories). Scans lazily, once. */
object SystemFonts:
  /** Standard font directories for the running OS (existing ones only). */
  def directories: Seq[String] =
    import scala.scalanative.meta.LinktimeInfo
    val home = sys.env.getOrElse("HOME", sys.env.getOrElse("USERPROFILE", ""))
    val all =
      if LinktimeInfo.isMac then Seq("/System/Library/Fonts", "/System/Library/Fonts/Supplemental", "/Library/Fonts", s"$home/Library/Fonts")
      else if LinktimeInfo.isWindows then Seq(sys.env.getOrElse("WINDIR", "C:\\Windows") + "\\Fonts", s"$home\\AppData\\Local\\Microsoft\\Windows\\Fonts")
      else Seq("/usr/share/fonts", "/usr/local/share/fonts", s"$home/.fonts", s"$home/.local/share/fonts")
    all.filter(d => d.nonEmpty && Files.isDirectory(Paths.get(d)))

  final case class Entry(path: String, index: Int, family: String, bold: Boolean, italic: Boolean)

  private lazy val index: Vector[Entry] =
    val out = Vector.newBuilder[Entry]
    def walk(dir: JPath, depth: Int): Unit =
      if depth <= 4 then
        try
          val stream = Files.newDirectoryStream(dir)
          try stream.iterator.asScala.foreach { p =>
            if Files.isDirectory(p) then walk(p, depth + 1)
            else
              val n = p.getFileName.toString.toLowerCase
              if n.endsWith(".ttf") || n.endsWith(".ttc") || n.endsWith(".otf") then
                try
                  val data = Files.readAllBytes(p)
                  val count = if n.endsWith(".ttc") then FontFace.collectionSize(data) else 1
                  for i <- 0 until count do
                    try
                      val f = FontFace(data, i)
                      if f.hasTable("glyf") || f.hasTable("CFF ") then out += Entry(p.toString, i, f.familyName, f.isBold, f.isItalic)
                    catch case _: Exception => ()
                catch case _: Exception => ()
          } finally stream.close()
        catch case _: Exception => ()
    directories.foreach(d => walk(Paths.get(d), 0))
    out.result()

  def families: Seq[String] = index.map(_.family).distinct.sorted

  private val cache = mutable.HashMap.empty[(String, Int), FontFace]
  private def load(e: Entry): FontFace =
    cache.getOrElseUpdate((e.path, e.index), FontFace(Files.readAllBytes(Paths.get(e.path)), e.index))

  /** Finds an installed family by case-insensitive name and builds its four styles (missing ones fall back). */
  def family(name: String): Option[FontFamily] =
    val es = index.filter(_.family.equalsIgnoreCase(name))
    if es.isEmpty then None
    else
      def pick(b: Boolean, i: Boolean) = es.find(e => e.bold == b && e.italic == i).map(load)
      pick(false, false).orElse(Some(load(es.head))).map(reg => new FontFamily(name, reg, pick(true, false), pick(false, true), pick(true, true)))
