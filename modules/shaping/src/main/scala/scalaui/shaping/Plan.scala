package scalaui.shaping

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scalaui.font.FontFace

/** A feature request. `global` features apply to every glyph; others only to glyphs whose mask carries their own bit.
  * `manualZwj`/`manualZwnj` stop joiners from being skipped by contextual matching; `perSyllable` confines matching to a syllable.
  */
private[shaping] final case class Feat(tag: String, global: Boolean = true, manualZwj: Boolean = false,
    manualZwnj: Boolean = false, perSyllable: Boolean = false)

/** What shaper callbacks (pauses between lookup stages) can see and change. */
private[shaping] final class Ctx(val buf: ArrayBuffer[G], val font: FontFace, val plan: Compiled, val gsub: Option[GsubEngine],
    val gdef: Gdef, val rtl: Boolean, val iso: String):
  /** Scratch values shared by the callbacks of one shaping run. */
  val vars = mutable.HashMap.empty[String, Int]
  def gdefHasClasses: Boolean = gdef.hasGlyphClasses
  def glyphClass(id: Int): Int = gdef.glyphClass(id)
  def mask(tag: String): Int = plan.mask(tag)
  /** Whether one of the lookups of feature `tag` would substitute exactly the glyph sequence `glyphs`. */
  def wouldSubstitute(tag: String, glyphs: Array[Int], zeroContext: Boolean): Boolean =
    gsub.exists(e => plan.featureLookups(tag).exists(i => e.wouldApply(i, glyphs, zeroContext)))

private[shaping] final case class LookupRun(index: Int, mask: Int, autoZwj: Boolean, autoZwnj: Boolean, perSyllable: Boolean)
private[shaping] final case class Stage(runs: Array[LookupRun], pause: Option[Ctx => Unit])

/** A plan compiled against one font: GSUB stages (each followed by an optional callback), the GPOS lookups and feature masks. */
private[shaping] final class Compiled(val stages: Vector[Stage], val gpos: Array[LookupRun], masks: Map[String, Int],
    lookupsByTag: Map[String, Array[Int]], val gsubScriptTag: String, val defaultMask: Int):
  val globalMask = 1
  /** Mask bit of a non-global feature (0 for global or unknown features). */
  def mask(tag: String): Int = masks.getOrElse(tag, 0)
  /** GSUB lookup indices of a feature. */
  def featureLookups(tag: String): Array[Int] = lookupsByTag.getOrElse(tag, Array.empty)

/** Collects features and pauses in application order, like HarfBuzz's map builder. */
private[shaping] final class PlanBuilder:
  private final case class Entry(feat: Feat, stage: Int, seq: Int)
  private val entries = ArrayBuffer.empty[Entry]
  private val pauses = mutable.Map.empty[Int, Ctx => Unit]
  private val disabled = mutable.Set.empty[String]
  private var stage = 0

  def add(f: Feat): Unit = entries += Entry(f, stage, entries.size)
  def enable(tag: String, manualZwj: Boolean = false, manualZwnj: Boolean = false, perSyllable: Boolean = false): Unit =
    add(Feat(tag, true, manualZwj, manualZwnj, perSyllable))
  def disable(tag: String): Unit = disabled += tag
  def has(tag: String): Boolean = entries.exists(_.feat.tag == tag)
  /** Ends the current stage; `callback` runs once its lookups have been applied. */
  def pause(callback: Ctx => Unit = null): Unit =
    if callback != null then pauses(stage) = callback
    stage += 1

  def compile(gsub: Option[LayoutTable], gposT: Option[LayoutTable], gsubLs: Option[(Int, String)], gposLs: Option[(Int, String)]): Compiled =
    // merge duplicates: first insertion wins, the later one decides `global`, earliest stage
    val merged = mutable.LinkedHashMap.empty[String, (Feat, Int)]
    val defaultOn = mutable.Set.empty[String] // features enabled for every glyph initially (global ones, and merged ones that started global)
    entries.sortBy(_.seq).foreach { e =>
      if !disabled(e.feat.tag) then
        merged.get(e.feat.tag) match
          case None =>
            merged(e.feat.tag) = (e.feat, e.stage)
            if e.feat.global then defaultOn += e.feat.tag
          case Some((f, st)) =>
            merged(e.feat.tag) = (f.copy(global = e.feat.global), math.min(st, e.stage))
            if e.feat.global then defaultOn += e.feat.tag
    }
    val present: Set[String] =
      def tags(t: Option[LayoutTable], ls: Option[(Int, String)]): Set[String] = (t, ls) match
        case (Some(tt), Some((off, _))) => tt.featureTags(off)
        case _ => Set.empty
      tags(gsub, gsubLs) ++ tags(gposT, gposLs)
    val masks = mutable.Map.empty[String, Int]
    var nextBit = 2
    // like HarfBuzz, a non-global feature the font does not have gets no mask bit
    merged.values.foreach { (f, _) => if !f.global && present(f.tag) then { masks(f.tag) = nextBit; nextBit <<= 1 } }
    def maskOf(f: Feat) = if f.global then 1 else masks.getOrElse(f.tag, 0)

    def runs(tbl: Option[LayoutTable], ls: Option[(Int, String)], feats: Iterable[(Feat, Int)]): Array[LookupRun] =
      (tbl, ls) match
        case (Some(t), Some((off, _))) =>
          val m = mutable.TreeMap.empty[Int, LookupRun]
          feats.foreach { (f, _) =>
            t.featureLookups(off, f.tag).foreach { i =>
              val prev = m.get(i)
              m(i) = LookupRun(i, maskOf(f) | prev.map(_.mask).getOrElse(0), prev.forall(_.autoZwj) && !f.manualZwj,
                prev.forall(_.autoZwnj) && !f.manualZwnj, prev.exists(_.perSyllable) || f.perSyllable)
            }
          }
          m.values.toArray
        case _ => Array.empty

    val lastStage = (merged.values.map(_._2) ++ pauses.keys).maxOption.getOrElse(0)
    val stages = (0 to lastStage).map { s =>
      Stage(runs(gsub, gsubLs, merged.values.filter(_._2 == s)), pauses.get(s))
    }.toVector
    val gp = runs(gposT, gposLs, merged.values)
    val byTag = merged.values.map((f, _) => f.tag -> (gsub, gsubLs match
      case Some((off, _)) => gsub.map(_.featureLookups(off, f.tag)).getOrElse(Array.empty[Int])
      case None => Array.empty[Int])._2).toMap
    val defaultMask = masks.iterator.filter((tag, _) => defaultOn(tag)).map(_._2).foldLeft(1)(_ | _)
    new Compiled(stages, gp, masks.toMap, byTag, gsubLs.map(_._2).getOrElse(""), defaultMask)
