package scalaui.shaping

import scalaui.font.FontFace

/** Big-endian readers over font bytes. */
private[shaping] object Bin:
  def u8(d: Array[Byte], o: Int): Int = d(o) & 0xff
  def u16(d: Array[Byte], o: Int): Int = ((d(o) & 0xff) << 8) | (d(o + 1) & 0xff)
  def i16(d: Array[Byte], o: Int): Int = (u16(d, o) << 16) >> 16
  def u32(d: Array[Byte], o: Int): Int = (u16(d, o) << 16) | u16(d, o + 2)

import Bin.*

/** OpenType Coverage table: glyph -> coverage index, or -1. */
private[shaping] final class Coverage(d: Array[Byte], o: Int):
  private val format = u16(d, o)
  private val count = u16(d, o + 2)
  def index(g: Int): Int =
    var lo = 0; var hi = count - 1
    format match
      case 1 =>
        while lo <= hi do
          val mid = (lo + hi) >>> 1; val v = u16(d, o + 4 + mid * 2)
          if v < g then lo = mid + 1 else if v > g then hi = mid - 1 else return mid
        -1
      case 2 =>
        while lo <= hi do
          val mid = (lo + hi) >>> 1; val r = o + 4 + mid * 6
          if g > u16(d, r + 2) then lo = mid + 1
          else if g < u16(d, r) then hi = mid - 1
          else return u16(d, r + 4) + (g - u16(d, r))
        -1
      case _ => -1
  def covers(g: Int): Boolean = index(g) >= 0

/** OpenType ClassDef table; offset 0 means "everything is class 0". */
private[shaping] final class ClassDef(d: Array[Byte], o: Int):
  def classOf(g: Int): Int =
    if o < 0 then return 0
    u16(d, o) match
      case 1 =>
        val start = u16(d, o + 2); val n = u16(d, o + 4)
        if g >= start && g < start + n then u16(d, o + 6 + (g - start) * 2) else 0
      case 2 =>
        val n = u16(d, o + 2); var lo = 0; var hi = n - 1
        while lo <= hi do
          val mid = (lo + hi) >>> 1; val r = o + 4 + mid * 6
          if g > u16(d, r + 2) then lo = mid + 1
          else if g < u16(d, r) then hi = mid - 1
          else return u16(d, r + 4)
        0
      case _ => 0

/** GDEF: glyph classes (1 base, 2 ligature, 3 mark, 4 component), mark attachment classes and mark glyph sets. */
private[shaping] final class Gdef(font: FontFace):
  private val raw = font.rawTable("GDEF")
  private val (d, base) = raw.getOrElse((new Array[Byte](0), 0))
  private def sub(field: Int): Int =
    if raw.isEmpty then -1 else { val off = u16(d, base + field); if off == 0 then -1 else base + off }
  private val glyphDef = new ClassDef(d, sub(4))
  private val attachDef = new ClassDef(d, sub(10))
  val hasGlyphClasses: Boolean = raw.isDefined && sub(4) >= 0
  private val markSets: Array[Coverage] =
    if raw.isEmpty || u16(d, base + 2) < 2 then Array.empty
    else
      val off = u16(d, base + 12)
      if off == 0 then Array.empty
      else
        val o = base + off
        Array.tabulate(u16(d, o + 2))(i => new Coverage(d, o + u32(d, o + 4 + i * 4)))

  def glyphClass(g: Int): Int = glyphDef.classOf(g)
  def markAttachClass(g: Int): Int = attachDef.classOf(g)
  def inMarkSet(set: Int, g: Int): Boolean = set < markSets.length && markSets(set).covers(g)

/** One lookup: its (extension-resolved) type, flags and subtable offsets. */
private[shaping] final class Lookup(val kind: Int, val flag: Int, val markSet: Int, val subs: Array[Int])

/** A GSUB or GPOS table: script/feature selection and the lookup list. */
private[shaping] final class LayoutTable(val d: Array[Byte], val base: Int, gsub: Boolean):
  private val scriptList = base + u16(d, base + 4)
  private val featureList = base + u16(d, base + 6)
  private val lookupList = base + u16(d, base + 8)

  val lookups: Array[Lookup] =
    val n = u16(d, lookupList)
    Array.tabulate(n) { i =>
      val lo = lookupList + u16(d, lookupList + 2 + i * 2)
      val typ = u16(d, lo); val flag = u16(d, lo + 2); val cnt = u16(d, lo + 4)
      val markSet = if (flag & 0x10) != 0 then u16(d, lo + 6 + cnt * 2) else 0
      val extType = if gsub then 7 else 9
      val offs = Array.tabulate(cnt)(s => lo + u16(d, lo + 6 + s * 2))
      if typ == extType && cnt > 0 then
        val real = u16(d, offs(0) + 2)
        new Lookup(real, flag, markSet, offs.map(o => o + u32(d, o + 4)))
      else new Lookup(typ, flag, markSet, offs)
    }

  private def tagAt(o: Int): String = new String(Array(d(o), d(o + 1), d(o + 2), d(o + 3)), "ISO-8859-1")

  /** The LangSys (offset, chosen script tag) for the first available script in `scripts` (then DFLT, dflt, latn),
    * using language `lang` when the script has it.
    */
  def langSys(scripts: Seq[String], lang: Option[String]): Option[(Int, String)] =
    val n = u16(d, scriptList)
    def find(tag: String): Option[(Int, String)] =
      (0 until n).find(i => tagAt(scriptList + 2 + i * 6) == tag).map(i => (scriptList + u16(d, scriptList + 2 + i * 6 + 4), tag))
    (scripts ++ Seq("DFLT", "dflt", "latn")).iterator.map(find).collectFirst { case Some(s) => s }.flatMap { (s, tag) =>
      val defOff = u16(d, s)
      val cnt = u16(d, s + 2)
      val byLang = lang.flatMap(l => (0 until cnt).find(i => tagAt(s + 4 + i * 6) == l).map(i => s + u16(d, s + 4 + i * 6 + 4)))
      byLang.orElse(if defOff != 0 then Some(s + defOff) else None).map((_, tag))
    }

  /** The script tag that `langSys` selected (for diagnostics and shaper choice). */
  def hasScript(tag: String): Boolean =
    val n = u16(d, scriptList)
    (0 until n).exists(i => tagAt(scriptList + 2 + i * 6) == tag)

  /** Lookup indices of the features named `tag` in a LangSys (plus its required feature when it has that tag). */
  def featureLookups(ls: Int, tag: String): Array[Int] =
    val req = u16(d, ls + 2)
    val cnt = u16(d, ls + 4)
    val idx = (if req != 0xFFFF then Seq(req) else Seq.empty) ++ (0 until cnt).map(i => u16(d, ls + 6 + i * 2))
    val out = scala.collection.mutable.LinkedHashSet.empty[Int]
    idx.foreach { fi =>
      val rec = featureList + 2 + fi * 6
      if tagAt(rec) == tag then
        val fo = featureList + u16(d, rec + 4)
        (0 until u16(d, fo + 2)).foreach(k => out += u16(d, fo + 4 + k * 2))
    }
    out.toArray

  /** All feature tags a LangSys provides. */
  def featureTags(ls: Int): Set[String] =
    val cnt = u16(d, ls + 4)
    val req = u16(d, ls + 2)
    ((if req != 0xFFFF then Seq(req) else Seq.empty) ++ (0 until cnt).map(i => u16(d, ls + 6 + i * 2))).map(fi => tagAt(featureList + 2 + fi * 6)).toSet
