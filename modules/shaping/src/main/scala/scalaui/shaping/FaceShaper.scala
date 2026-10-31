package scalaui.shaping

import scalaui.font.FontFace
import scalaui.unicode.{Bidi, DefaultIgnorable, Gc}
import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

/** An OpenType feature request: `value` 0 switches a default feature off, anything else switches it on. */
final case class FontFeature(tag: String, value: Int = 1)

/** One shaped glyph in font units, in logical order. */
private[shaping] final case class Out(glyph: Int, cluster: Int, xAdvance: Int, xOffset: Int, yOffset: Int, yAdvance: Int = 0)

private[shaping] object ScriptTags:
  private val indicNew = Map("Deva" -> "dev2", "Beng" -> "bng2", "Guru" -> "gur2", "Gujr" -> "gjr2", "Orya" -> "ory2",
    "Taml" -> "tml2", "Telu" -> "tel2", "Knda" -> "knd2", "Mlym" -> "mlm2", "Mymr" -> "mym2")

  /** OpenType script tags to try, most specific first. */
  def tags(iso: String): Seq[String] = iso match
    case "Zyyy" | "Zinh" | "Zzzz" | "Qaai" => Seq("DFLT")
    case "Hira" | "Kana" => Seq("kana")
    case "Laoo" => Seq("lao ")
    case "Nkoo" => Seq("nko ")
    case _ => indicNew.get(iso).toSeq :+ iso.toLowerCase

/** Shapes runs of one script with a single face using its GSUB/GPOS tables. */
private[shaping] final class FaceShaper(val font: FontFace):
  private val gdef = new Gdef(font)
  private val gsubTable = font.rawTable("GSUB").map((d, o) => new LayoutTable(d, o, true))
  private val gposTable = font.rawTable("GPOS").map((d, o) => new LayoutTable(d, o, false))
  private val gsub = gsubTable.map(new GsubEngine(_, gdef))
  private val gpos = gposTable.map(new GposEngine(_, gdef))

  private final case class PlanEntry(shaper: ComplexShaper, plan: Compiled)
  private val plans = mutable.HashMap.empty[(String, Option[String], Boolean, Boolean, Seq[FontFeature]), PlanEntry]

  private def buildPlan(iso: String, lang: Option[String], rtl: Boolean, vertical: Boolean, features: Seq[FontFeature]): PlanEntry =
    val tags = ScriptTags.tags(iso)
    val gsubLs = gsubTable.flatMap(_.langSys(tags, lang))
    val shaper = ShaperRegistry.forScript(iso, gsubLs.map(_._2).getOrElse("DFLT"), vertical)
    val b = new PlanBuilder
    // the common prelude and the features every shaper gets
    b.enable("rvrn"); b.pause()
    if vertical then ()
    else if rtl then { b.enable("rtla"); b.enable("rtlm") } else { b.enable("ltra"); b.enable("ltrm") }
    shaper.collect(b, rtl)
    Seq("abvm", "blwm", "ccmp", "locl", "mark", "mkmk", "rlig").foreach(t => b.enable(t))
    if vertical then b.enable("vert")
    else Seq("calt", "clig", "curs", "dist", "kern", "liga", "rclt").foreach(t => b.enable(t))
    features.foreach(f => if f.value == 0 then b.disable(f.tag) else if !b.has(f.tag) then b.enable(f.tag))
    shaper.overrideFeatures(b)
    PlanEntry(shaper, b.compile(gsubTable, gposTable, gsubLs, gposTable.flatMap(_.langSys(tags, lang))))

  private def isInvisible(cp: Int): Boolean = DefaultIgnorable.table(cp) == DefaultIgnorable.Yes

  /** Shapes `cps` (clusters are caller-defined indices, one per code point) into logical-order glyphs in font units. */
  def shape(cps0: Array[Int], clusters0: Array[Int], rtl: Boolean, scriptName: String, lang: Option[String],
      features: Seq[FontFeature], vertical: Boolean = false): Array[Out] =
    if cps0.isEmpty then return Array.empty
    val iso = scalaui.unicode.Scripts.isoCode(scriptName)
    val entry = plans.getOrElseUpdate((iso, lang, rtl, vertical, features), buildPlan(iso, lang, rtl, vertical, features))
    val (pre, preClusters, preTags) = entry.shaper.preprocess(font, cps0, clusters0)
    val (cps, clusters, tags) = Normalize(font, pre, preClusters, preTags, entry.shaper.normalization)

    val buf = ArrayBuffer.empty[G]
    var i = 0
    while i < cps.length do
      val cp = cps(i)
      val shown = if rtl then Bidi.mirror(cp) else cp
      var id = font.glyphIndex(shown)
      if id == 0 && isInvisible(cp) then id = font.glyphIndex(0x20)
      val g = new G(id, clusters(i), cp, entry.plan.defaultMask)
      g.feat = tags(i)
      g.cls =
        if gdef.hasGlyphClasses then gdef.glyphClass(id)
        else if Gc.table(cp) == Gc.Mn then 3 else 1
      g.aux = if isInvisible(cp) then 1 else 0
      buf += g
      i += 1

    val ctx = new Ctx(buf, font, entry.plan, gsub, gdef, rtl, iso)
    entry.shaper.setupMasks(ctx)

    gsub.foreach { e =>
      e.buf = buf; e.rtl = rtl
      entry.plan.stages.foreach { stage =>
        stage.runs.foreach(r => { e.buf = ctx.buf; e.run(r.index, r.mask, r.autoZwj, r.autoZwnj, r.perSyllable) })
        stage.pause.foreach(_(ctx))
      }
    }
    // pauses may have rebuilt the buffer contents in place; positioning works on the same buffer
    buf.foreach { g =>
      if vertical then
        // vertical advances run downward (negative); the glyph origin moves to its vertical origin (top centre)
        val (ox, oy) = font.verticalOrigin(g.id)
        g.xAdv = 0; g.yAdv = -font.verticalAdvance(g.id); g.xOff -= ox; g.yOff -= oy
      else g.xAdv = font.advance(g.id)
      if g.aux == 1 then { g.xAdv = 0; g.yAdv = 0 }
      if entry.shaper.zeroMarksEarly && g.cls == 3 then { g.xAdv = 0; g.yAdv = 0 }
    }
    gpos.foreach { e =>
      e.buf = buf; e.rtl = rtl; e.vertical = vertical
      entry.plan.gpos.foreach(r => e.run(r.index, r.mask, r.autoZwj, r.autoZwnj, r.perSyllable))
    }
    buf.foreach(g => if g.aux == 1 then { g.xAdv = 0; g.yAdv = 0; g.xOff = 0; g.yOff = 0 })
    // default-ignorable characters become an invisible glyph (the space) only now: during GPOS they still block attachment
    val space = font.glyphIndex(0x20)
    if space != 0 then buf.foreach(g => if g.aux == 1 then g.id = space) else buf.filterInPlace(_.aux == 0)
    Positioning.finish(buf, rtl, zeroMarks = entry.shaper.zeroMarks, vertical = vertical)
    buf.map(g => Out(g.id, g.cluster, g.xAdv, g.xOff, g.yOff, g.yAdv)).toArray
