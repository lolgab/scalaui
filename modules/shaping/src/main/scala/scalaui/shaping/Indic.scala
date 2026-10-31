package scalaui.shaping

import scala.collection.mutable.ArrayBuffer
import scalaui.font.FontFace
import scalaui.unicode.{Gc, Normalizer, ShaperCat as Cat, ShaperPos}

/** Visual positions within a syllable, from left to right (Indic shaping). */
private[shaping] object Pos:
  inline val START = 0
  inline val RA_TO_BECOME_REPH = 1
  inline val PRE_M = 2
  inline val PRE_C = 3
  inline val BASE_C = 4
  inline val AFTER_MAIN = 5
  inline val ABOVE_C = 6
  inline val BEFORE_SUB = 7
  inline val BELOW_C = 8
  inline val AFTER_SUB = 9
  inline val BEFORE_POST = 10
  inline val POST_C = 11
  inline val AFTER_POST = 12
  inline val SMVD = 13
  inline val END = 14

  private val byName = Map("START" -> START, "RA_TO_BECOME_REPH" -> RA_TO_BECOME_REPH, "PRE_M" -> PRE_M, "PRE_C" -> PRE_C,
    "BASE_C" -> BASE_C, "AFTER_MAIN" -> AFTER_MAIN, "ABOVE_C" -> ABOVE_C, "BEFORE_SUB" -> BEFORE_SUB, "BELOW_C" -> BELOW_C,
    "AFTER_SUB" -> AFTER_SUB, "BEFORE_POST" -> BEFORE_POST, "POST_C" -> POST_C, "AFTER_POST" -> AFTER_POST, "SMVD" -> SMVD, "END" -> END)
  val fromTable: Array[Int] = ShaperPos.names.map(byName)

/** Shared helpers of the syllable-based shapers (Indic, Khmer, Myanmar, USE). */
private[shaping] object Syllabic:
  /** Cluster merging as HarfBuzz does it: the range (extended to whole clusters) takes the smallest cluster value. */
  def mergeClusters(buf: ArrayBuffer[G], from: Int, to: Int): Unit =
    if to - from >= 2 then
      var start = from; var end = to
      var cluster = Int.MaxValue
      var i = start
      while i < end do { cluster = math.min(cluster, buf(i).cluster); i += 1 }
      while end < buf.length && buf(end - 1).cluster == buf(end).cluster do end += 1
      while start > 0 && buf(start - 1).cluster == buf(start).cluster do start -= 1
      i = start
      while i < end do { buf(i).cluster = cluster; i += 1 }

  /** Stable insertion sort of `[start, end)` by `key`; moved ranges merge their clusters (like HarfBuzz's buffer sort). */
  def stableSort(buf: ArrayBuffer[G], start: Int, end: Int)(key: G => Int): Unit =
    var i = start + 1
    while i < end do
      var j = i
      while j > start && key(buf(j - 1)) > key(buf(i)) do j -= 1
      if i != j then
        mergeClusters(buf, j, i + 1)
        val t = buf.remove(i); buf.insert(j, t)
      i += 1

  def foreachSyllable(buf: ArrayBuffer[G])(f: (Int, Int) => Unit): Unit =
    var i = 0
    while i < buf.length do
      val start = i; val s = buf(i).syllable
      i += 1
      while i < buf.length && buf(i).syllable == s do i += 1
      f(start, i)

  /** Inserts a dotted circle at the start (after a possible Repha) of every broken syllable; true if any was inserted. */
  def insertDottedCircles(ctx: Ctx, brokenType: Int, dottedCategory: Int, rephaCategory: Int, position: Int): Boolean =
    val buf = ctx.buf
    if !buf.exists(g => (g.syllable & 0xF) == brokenType) then return false
    val dc = ctx.font.glyphIndex(0x25CC)
    if dc == 0 then return false
    val out = ArrayBuffer.empty[G]
    var lastSyllable = 0
    var i = 0
    while i < buf.length do
      val g = buf(i)
      if lastSyllable != g.syllable && (g.syllable & 0xF) == brokenType then
        lastSyllable = g.syllable
        val ng = new G(dc, g.cluster, 0x25CC, g.mask)
        ng.cat = dottedCategory; ng.pos = position; ng.syllable = g.syllable
        ng.cls = if ctx.gdefHasClasses then ctx.glyphClass(dc) else 1
        if rephaCategory != -1 then
          while i < buf.length && buf(i).syllable == lastSyllable && buf(i).cat == rephaCategory do { out += buf(i); i += 1 }
        out += ng
      else { out += g; i += 1 }
    buf.clear(); buf ++= out
    true

private[shaping] final class IndicShaper(iso: String) extends ComplexShaper:
  private object SyllableType:
    inline val Consonant = 0; inline val Vowel = 1; inline val Standalone = 2; inline val Symbol = 3; inline val Broken = 4; inline val NonIndic = 5

  private enum RephMode:
    case Implicit, Explicit, LogRepha
  private final case class Config(hasOldSpec: Boolean, virama: Int, rephPos: Int, rephMode: RephMode, blwfPreAndPost: Boolean)
  private val cfg: Config = iso match
    case "Deva" => Config(true, 0x094D, Pos.BEFORE_POST, RephMode.Implicit, true)
    case "Beng" => Config(true, 0x09CD, Pos.AFTER_SUB, RephMode.Implicit, true)
    case "Guru" => Config(true, 0x0A4D, Pos.BEFORE_SUB, RephMode.Implicit, true)
    case "Gujr" => Config(true, 0x0ACD, Pos.BEFORE_POST, RephMode.Implicit, true)
    case "Orya" => Config(true, 0x0B4D, Pos.AFTER_MAIN, RephMode.Implicit, true)
    case "Taml" => Config(true, 0x0BCD, Pos.AFTER_POST, RephMode.Implicit, true)
    case "Telu" => Config(true, 0x0C4D, Pos.AFTER_POST, RephMode.Explicit, false)
    case "Knda" => Config(true, 0x0CCD, Pos.AFTER_POST, RephMode.Implicit, false)
    case "Mlym" => Config(true, 0x0D4D, Pos.AFTER_MAIN, RephMode.LogRepha, true)
    case _ => Config(false, 0, Pos.BEFORE_POST, RephMode.Implicit, true)

  private def flag(c: Int): Long = 1L << c
  private val ConsonantFlags = flag(Cat.C) | flag(Cat.CS) | flag(Cat.Ra) | flag(Cat.CM) | flag(Cat.V) | flag(Cat.PLACEHOLDER) | flag(Cat.DOTTEDCIRCLE)
  private val JoinerFlags = flag(Cat.ZWJ) | flag(Cat.ZWNJ)

  /** A glyph that ligated no longer counts as its original category. */
  private def isOneOf(g: G, flags: Long): Boolean = !g.ligated && (flag(g.cat) & flags) != 0
  private def isConsonant(g: G) = isOneOf(g, ConsonantFlags)
  private def isJoiner(g: G) = isOneOf(g, JoinerFlags)
  private def isHalant(g: G) = isOneOf(g, flag(Cat.H))

  // ---- features ----
  override def collect(b: PlanBuilder, rtl: Boolean): Unit =
    b.pause(setupSyllables)
    b.enable("locl", perSyllable = true)
    b.enable("ccmp", perSyllable = true)
    b.pause(initialReordering)
    def basic(tag: String, global: Boolean, manual: Boolean = true) = b.add(Feat(tag, global, manual, manual, perSyllable = true))
    Vector(("nukt", true), ("akhn", true), ("rphf", false), ("rkrf", true), ("pref", false), ("blwf", false), ("abvf", false),
      ("half", false), ("pstf", false), ("vatu", true), ("cjct", true)).foreach { (tag, global) => basic(tag, global); b.pause() }
    b.pause(finalReordering)
    Vector(("init", false), ("pres", true), ("abvs", true), ("blws", true), ("psts", true), ("haln", true)).foreach((tag, global) => basic(tag, global))

  override def overrideFeatures(b: PlanBuilder): Unit = b.disable("liga")

  override val normalization: NormalizeHooks = new NormalizeHooks(
    shortCircuit = false,
    decompose = ab => ab match
      case 0x0931 | 0x09DC | 0x09DD | 0x0B94 => None
      case _ => Normalizer.decomposeOnce(ab),
    compose = (a, b) =>
      val g = Gc.table(a)
      if g == Gc.Mn || g == Gc.Mc || g == Gc.Me then -1
      else if a == 0x09AF && b == 0x09BC then 0x09DF
      else Normalizer.composePair(a, b))

  override def zeroMarks: Boolean = false

  override def preprocess(font: FontFace, cps: Array[Int], clusters: Array[Int]): (Array[Int], Array[Int], Array[Int]) =
    VowelConstraints(iso, cps, clusters)

  override def setupMasks(ctx: Ctx): Unit =
    ctx.buf.foreach { g => g.cat = Cat.table(g.cp); g.pos = Pos.fromTable(ShaperPos.table(g.cp)) }

  // ---- syllable grammar (indic-machine.rl) ----
  private val rules: Vector[Nfa] =
    import Re.*
    def s(c: Int*): Re = sym(c*)
    val C = s(Cat.C); val V = s(Cat.V); val N = s(Cat.N); val H = s(Cat.H); val ZWNJ = s(Cat.ZWNJ); val ZWJ = s(Cat.ZWJ)
    val M = s(Cat.M); val A = s(Cat.A); val PLACEHOLDER = s(Cat.PLACEHOLDER); val DOTTEDCIRCLE = s(Cat.DOTTEDCIRCLE)
    val RS = s(Cat.RS); val MPst = s(Cat.MPst); val Repha = s(Cat.Repha); val Ra = s(Cat.Ra); val CM = s(Cat.CM)
    val Symbol = s(Cat.Symbol); val CS = s(Cat.CS); val SM = s(Cat.SM); val SMPst = s(Cat.SMPst)
    val c = C | Ra
    val n = (ZWNJ.? ~ RS).? ~ (N ~ N.?).?
    val z = ZWJ | ZWNJ
    val reph = (Ra ~ H) | Repha
    val sm = SM | SMPst
    val cn = c ~ ZWJ.? ~ n.?
    val symbol = Symbol ~ N.?
    val matraGroup = z.* ~ (M | (sm.? ~ MPst)) ~ N.? ~ H.?
    val syllableTail = (z.? ~ sm ~ sm.? ~ ZWNJ.?).? ~ A.*
    val halantGroup = z.? ~ H ~ (ZWJ ~ N.?).?
    val finalHalantGroup = halantGroup | (H ~ ZWNJ)
    val medialGroup = CM.?
    val halantOrMatraGroup = finalHalantGroup | matraGroup.*
    val complexTail = (halantGroup ~ cn).* ~ medialGroup ~ halantOrMatraGroup ~ syllableTail
    val consonantSyllable = (Repha | CS).? ~ cn ~ complexTail
    val vowelSyllable = reph.? ~ V ~ n.? ~ (ZWJ | complexTail)
    val standalone = (((Repha | CS).? ~ PLACEHOLDER) | (reph.? ~ DOTTEDCIRCLE)) ~ n.? ~ complexTail
    val symbolCluster = symbol ~ syllableTail
    val broken = reph.? ~ n.? ~ complexTail
    Vector(consonantSyllable, vowelSyllable, standalone, symbolCluster, SMPst, broken).map(new Nfa(_))
  // rule index -> syllable type (the trailing "other" rule is the unmatched case)
  private val ruleType = Vector(SyllableType.Consonant, SyllableType.Vowel, SyllableType.Standalone, SyllableType.Symbol, SyllableType.NonIndic, SyllableType.Broken)

  private def setupSyllables(ctx: Ctx): Unit =
    val buf = ctx.buf
    val syms = Array.tabulate(buf.length)(i => buf(i).cat)
    var serial = 1
    Syllables.scan(syms, rules).foreach { (from, to, rule) =>
      val tpe = if rule < 0 then SyllableType.NonIndic else ruleType(rule)
      var i = from
      while i < to do { buf(i).syllable = (serial << 4) | tpe; i += 1 }
      serial += 1
      if serial == 16 then serial = 1
    }

  // ---- plan data ----
  private def oldSpec(ctx: Ctx): Boolean = cfg.hasOldSpec && !ctx.plan.gsubScriptTag.endsWith("2")
  private def zeroContext(ctx: Ctx): Boolean = !oldSpec(ctx) && iso != "Mlym"

  private def viramaGlyph(ctx: Ctx): Int =
    ctx.vars.getOrElseUpdate("virama", if cfg.virama != 0 then ctx.font.glyphIndex(cfg.virama) else 0)

  private def consonantPositionFromFace(ctx: Ctx, consonant: Int, virama: Int): Int =
    val zc = zeroContext(ctx)
    def would(tag: String): Boolean =
      ctx.wouldSubstitute(tag, Array(virama, consonant), zc) || ctx.wouldSubstitute(tag, Array(consonant, virama), zc)
    if would("blwf") || would("vatu") then Pos.BELOW_C
    else if would("pstf") then Pos.POST_C
    else if would("pref") then Pos.POST_C
    else Pos.BASE_C

  private def updateConsonantPositions(ctx: Ctx): Unit =
    val virama = viramaGlyph(ctx)
    if virama != 0 then
      ctx.buf.foreach(g => if g.pos == Pos.BASE_C then g.pos = consonantPositionFromFace(ctx, g.id, virama))

  private def initialReordering(ctx: Ctx): Unit =
    updateConsonantPositions(ctx)
    Syllabic.insertDottedCircles(ctx, SyllableType.Broken, Cat.DOTTEDCIRCLE, Cat.Repha, Pos.END)
    Syllabic.foreachSyllable(ctx.buf) { (start, end) =>
      (ctx.buf(start).syllable & 0xF) match
        case SyllableType.Vowel | SyllableType.Consonant | SyllableType.Broken | SyllableType.Standalone =>
          initialReorderingConsonantSyllable(ctx, start, end)
        case _ => ()
    }

  private def initialReorderingConsonantSyllable(ctx: Ctx, start: Int, end: Int): Unit =
    val info = ctx.buf
    val rphfMask = ctx.mask("rphf"); val halfMask = ctx.mask("half"); val blwfMask = ctx.mask("blwf")
    val abvfMask = ctx.mask("abvf"); val pstfMask = ctx.mask("pstf"); val prefMask = ctx.mask("pref")
    val old = oldSpec(ctx)

    // Kannada: Ra+H+ZWJ must behave like Ra+ZWJ+H
    if iso == "Knda" && start + 3 <= end && isOneOf(info(start), flag(Cat.Ra)) && isOneOf(info(start + 1), flag(Cat.H)) && isOneOf(info(start + 2), flag(Cat.ZWJ)) then
      Syllabic.mergeClusters(info, start + 1, start + 3)
      val t = info(start + 1); info(start + 1) = info(start + 2); info(start + 2) = t

    // 1. find the base consonant
    var base = end
    var hasReph = false
    locally {
      var limit = start
      if rphfMask != 0 && start + 3 <= end &&
         ((cfg.rephMode == RephMode.Implicit && !isJoiner(info(start + 2))) ||
          (cfg.rephMode == RephMode.Explicit && info(start + 2).cat == Cat.ZWJ)) then
        val explicit = cfg.rephMode == RephMode.Explicit
        val g2 = Array(info(start).id, info(start + 1).id)
        val g3 = Array(info(start).id, info(start + 1).id, info(start + 2).id)
        val zc = zeroContext(ctx)
        if ctx.wouldSubstitute("rphf", g2, zc) || (explicit && ctx.wouldSubstitute("rphf", g3, zc)) then
          limit += 2
          while limit < end && isJoiner(info(limit)) do limit += 1
          base = start
          hasReph = true
      else if cfg.rephMode == RephMode.LogRepha && info(start).cat == Cat.Repha then
        limit += 1
        while limit < end && isJoiner(info(limit)) do limit += 1
        base = start
        hasReph = true

      var i = end
      var seenBelow = false
      var stop = false
      while !stop do
        i -= 1
        if isConsonant(info(i)) then
          if info(i).pos != Pos.BELOW_C && (info(i).pos != Pos.POST_C || seenBelow) then
            base = i
            stop = true
          else
            if info(i).pos == Pos.BELOW_C then seenBelow = true
            base = i
        else if start < i && info(i).cat == Cat.ZWJ && info(i - 1).cat == Cat.H then stop = true
        if !stop && !(i > limit) then stop = true

      if hasReph && base == start && limit - base <= 2 then hasReph = false
    }

    // reorder characters
    var i = start
    while i < base do { info(i).pos = math.min(Pos.PRE_C, info(i).pos); i += 1 }
    if base < end then info(base).pos = Pos.BASE_C
    if hasReph then info(start).pos = Pos.RA_TO_BECOME_REPH

    // old-spec scripts move the first post-base halant after the last consonant
    if old then
      val disallowDouble = iso == "Knda"
      var k = base + 1
      var moved = false
      while k < end && !moved do
        if info(k).cat == Cat.H then
          var j = end - 1
          while j > k && !(isConsonant(info(j)) || (disallowDouble && info(j).cat == Cat.H)) do j -= 1
          if info(j).cat != Cat.H && j > k then
            val t = info.remove(k); info.insert(j, t)
          moved = true
        k += 1

    // attach misc marks to the previous character so they move with it
    locally {
      var lastPos = Pos.START
      var k = start
      while k < end do
        val g = info(k)
        if (flag(g.cat) & (JoinerFlags | flag(Cat.N) | flag(Cat.RS) | flag(Cat.CM) | flag(Cat.H))) != 0 then
          g.pos = lastPos
          if g.cat == Cat.H && g.pos == Pos.PRE_M then
            // Uniscribe does not move the halant with a left matra
            var j = k
            var found = false
            while j > start && !found do
              if info(j - 1).pos != Pos.PRE_M then { g.pos = info(j - 1).pos; found = true }
              j -= 1
        else if g.pos != Pos.SMVD then
          if g.cat == Cat.MPst && k > start && info(k - 1).cat == Cat.SM then info(k - 1).pos = g.pos
          lastPos = g.pos
        k += 1
    }
    // post-base consonants own anything before them since the last consonant or matra
    locally {
      var last = base
      var k = base + 1
      while k < end do
        if isConsonant(info(k)) then
          var j = last + 1
          while j < k do { if info(j).pos < Pos.SMVD then info(j).pos = info(k).pos; j += 1 }
          last = k
        else if (flag(info(k).cat) & (flag(Cat.M) | flag(Cat.MPst))) != 0 then last = k
        k += 1
    }

    // stable sort by position; `syllable` temporarily holds the original index for cluster accounting
    locally {
      val syllable = info(start).syllable
      var k = start
      while k < end do { info(k).syllable = k - start; k += 1 }
      val sorted = info.slice(start, end).sortBy(_.pos)
      var q = 0
      while q < sorted.length do { info(start + q) = sorted(q); q += 1 }

      var firstLeft = end; var lastLeft = end
      base = end
      var stop = false
      k = start
      while k < end && !stop do
        if info(k).pos == Pos.BASE_C then { base = k; stop = true }
        else if info(k).pos == Pos.PRE_M then
          if firstLeft == end then firstLeft = k
          lastLeft = k
        k += 1
      if firstLeft < lastLeft then
        reverseRange(info, firstLeft, lastLeft + 1)
        var ii = firstLeft
        var j = ii
        while j <= lastLeft do
          if (flag(info(j).cat) & (flag(Cat.M) | flag(Cat.MPst))) != 0 then
            reverseRange(info, ii, j + 1)
            ii = j + 1
          j += 1

      if old || end - start > 127 then Syllabic.mergeClusters(info, base, end)
      else
        k = base
        while k < end do
          if info(k).syllable != 255 then
            var min = k; var max = k
            var j = start + info(k).syllable
            while j != k do
              min = math.min(min, j); max = math.max(max, j)
              val nxt = start + info(j).syllable
              info(j).syllable = 255
              j = nxt
            Syllabic.mergeClusters(info, math.max(base, min), max + 1)
          k += 1
      k = start
      while k < end do { info(k).syllable = syllable; k += 1 }
    }

    if Trace.enabled then Trace.log(s"syllable $start..$end base=$base reph=$hasReph glyphs=${(start until end).map(q => s"${info(q).id}/c${info(q).cat}/p${info(q).pos}").mkString(" ")}")
    // masks
    locally {
      var k = start
      while k < end && info(k).pos == Pos.RA_TO_BECOME_REPH do { info(k).mask |= rphfMask; k += 1 }
      var m = halfMask
      if !old && cfg.blwfPreAndPost then m |= blwfMask
      k = start
      while k < base do { info(k).mask |= m; k += 1 }
      m = blwfMask | abvfMask | pstfMask
      k = base + 1
      while k < end do { info(k).mask |= m; k += 1 }
    }

    if old && iso == "Deva" then
      var k = start
      while k + 1 < base do
        if info(k).cat == Cat.Ra && info(k + 1).cat == Cat.H && (k + 2 == base || info(k + 2).cat != Cat.ZWJ) then
          info(k).mask |= blwfMask; info(k + 1).mask |= blwfMask
        k += 1

    val prefLen = 2
    if prefMask != 0 && base + prefLen < end then
      var k = base + 1
      var done = false
      while k + prefLen - 1 < end && !done do
        val glyphs = Array(info(k).id, info(k + 1).id)
        if ctx.wouldSubstitute("pref", glyphs, zeroContext(ctx)) then
          info(k).mask |= prefMask; info(k + 1).mask |= prefMask
          done = true
        k += 1

    // ZWJ/ZWNJ effects
    var k = start + 1
    while k < end do
      if isJoiner(info(k)) then
        val nonJoiner = info(k).cat == Cat.ZWNJ
        var j = k
        var go = true
        while go do
          j -= 1
          if nonJoiner then info(j).mask &= ~halfMask
          go = j > start && !isConsonant(info(j))
      k += 1

  private def reverseRange(buf: ArrayBuffer[G], from: Int, to: Int): Unit =
    var a = from; var b = to - 1
    while a < b do { val t = buf(a); buf(a) = buf(b); buf(b) = t; a += 1; b -= 1 }

  private def finalReordering(ctx: Ctx): Unit =
    if ctx.buf.nonEmpty then Syllabic.foreachSyllable(ctx.buf)((start, end) => finalReorderingSyllable(ctx, start, end))

  private def finalReorderingSyllable(ctx: Ctx, start: Int, end: Int): Unit =
    val info = ctx.buf
    val prefMask = ctx.mask("pref"); val initMask = ctx.mask("init")

    // recover a virama that ligated and multiplied
    val virama = viramaGlyph(ctx)
    if virama != 0 then
      var k = start
      while k < end do
        if info(k).id == virama && info(k).ligated && info(k).multiplied then
          info(k).cat = Cat.H; info(k).ligated = false; info(k).multiplied = false
        k += 1

    var tryPref = prefMask != 0

    // find the base again
    var base = start
    var found = false
    while base < end && !found do
      if info(base).pos >= Pos.BASE_C then
        if tryPref && base + 1 < end then
          var i = base + 1
          var stop = false
          while i < end && !stop do
            if (info(i).mask & prefMask) != 0 then
              if !(info(i).substituted && info(i).ligatedAndDidntMultiply) then
                // a pref candidate that formed nothing: the base is around here
                base = i
                while base < end && isHalant(info(base)) do base += 1
                if base < end then info(base).pos = Pos.BASE_C
                tryPref = false
              stop = true
            i += 1
        if base != end then
          if iso == "Mlym" then
            var i = base + 1
            var go = true
            while i < end && go do
              while i < end && isJoiner(info(i)) do i += 1
              if i == end || !isHalant(info(i)) then go = false
              else
                i += 1
                while i < end && isJoiner(info(i)) do i += 1
                if i < end && isConsonant(info(i)) && info(i).pos == Pos.BELOW_C then
                  base = i
                  info(base).pos = Pos.BASE_C
                i += 1
          if start < base && info(base).pos > Pos.BASE_C then base -= 1
          found = true
        else found = true
      else base += 1
    if base == end && start < base && isOneOf(info(base - 1), flag(Cat.ZWJ)) then base -= 1
    if base < end then
      while start < base && isOneOf(info(base), flag(Cat.N) | flag(Cat.H)) do base -= 1

    // reorder matras
    if start + 1 < end && start < base then
      var newPos = if base == end then base - 2 else base - 1
      if iso != "Mlym" && iso != "Taml" then
        var searching = true
        while searching do
          searching = false
          while newPos > start && !isOneOf(info(newPos), flag(Cat.M) | flag(Cat.MPst) | flag(Cat.H)) do newPos -= 1
          if isHalant(info(newPos)) && info(newPos).pos != Pos.PRE_M then
            if newPos + 1 < end then
              // a ZWJ after this halant keeps the matra from moving behind it
              if info(newPos + 1).cat == Cat.ZWJ && newPos > start then
                newPos -= 1
                searching = true
          else newPos = start
      if start < newPos && info(newPos).pos != Pos.PRE_M then
        var i = newPos
        while i > start do
          if info(i - 1).pos == Pos.PRE_M then
            val oldPos = i - 1
            if oldPos < base && base <= newPos then base -= 1
            val t = info.remove(oldPos); info.insert(newPos, t)
            Syllabic.mergeClusters(info, newPos, math.min(end, base + 1))
            newPos -= 1
          i -= 1
      else
        var i = start
        var done = false
        while i < base && !done do
          if info(i).pos == Pos.PRE_M then { Syllabic.mergeClusters(info, i, math.min(end, base + 1)); done = true }
          i += 1

    // reorder reph
    if start + 1 < end && info(start).pos == Pos.RA_TO_BECOME_REPH &&
       ((info(start).cat == Cat.Repha) ^ info(start).ligatedAndDidntMultiply) then
      var newRephPos = -1
      def step2(): Boolean =
        var p = start + 1
        while p < base && !isHalant(info(p)) do p += 1
        if p < base && isHalant(info(p)) then
          if p + 1 < base && isJoiner(info(p + 1)) then p += 1
          newRephPos = p
          true
        else false
      def step6(): Unit =
        var p = end - 1
        while p > start && info(p).pos == Pos.SMVD do p -= 1
        if isHalant(info(p)) then
          var i = base + 1
          while i < p do
            if (flag(info(i).cat) & (flag(Cat.M) | flag(Cat.MPst))) != 0 then p -= 1
            i += 1
        newRephPos = p
      val rp = cfg.rephPos
      var decided = false
      if rp == Pos.AFTER_POST then
        if step2() then decided = true else { step6(); decided = true }
      if !decided then
        if step2() then decided = true
      if !decided && rp == Pos.AFTER_MAIN then
        var p = base
        while p + 1 < end && info(p + 1).pos <= Pos.AFTER_MAIN do p += 1
        if p < end then { newRephPos = p; decided = true }
      if !decided && rp == Pos.AFTER_SUB then
        var p = base
        while p + 1 < end && (flag(info(p + 1).pos) & (flag(Pos.POST_C) | flag(Pos.AFTER_POST) | flag(Pos.SMVD))) == 0 do p += 1
        if p < end then { newRephPos = p; decided = true }
      if !decided && rp != Pos.AFTER_POST then
        // step 5 (same as step 2 again), then step 6
        if step2() then decided = true else { step6(); decided = true }
      Syllabic.mergeClusters(info, start, newRephPos + 1)
      val reph = info.remove(start); info.insert(newRephPos, reph)
      if start < base && base <= newRephPos then base -= 1

    // reorder pre-base-reordering consonants
    if tryPref && base + 1 < end then
      var i = base + 1
      var stop = false
      while i < end && !stop do
        if (info(i).mask & prefMask) != 0 then
          if info(i).ligatedAndDidntMultiply then
            var newPos = base
            if iso != "Mlym" && iso != "Taml" then
              while newPos > start && !isOneOf(info(newPos - 1), flag(Cat.M) | flag(Cat.MPst) | flag(Cat.H)) do newPos -= 1
            if newPos > start && isHalant(info(newPos - 1)) then
              if newPos < end && isJoiner(info(newPos)) then newPos += 1
            val oldPos = i
            Syllabic.mergeClusters(info, newPos, oldPos + 1)
            val t = info.remove(oldPos); info.insert(newPos, t)
            if newPos <= base && base < oldPos then base += 1
          stop = true
        i += 1

    // 'init' applies to a left matra at a word start
    if info(start).pos == Pos.PRE_M then
      val wordChars = Set(Gc.Cf, Gc.Cn, Gc.Co, Gc.Cs, Gc.Ll, Gc.Lm, Gc.Lo, Gc.Lt, Gc.Lu, Gc.Mc, Gc.Me, Gc.Mn)
      if start == 0 || !wordChars(Gc.table(info(start - 1).cp)) then info(start).mask |= initMask

/** Vowel sequences that look like another vowel get a dotted circle in between (HarfBuzz's vowel constraints). */
private[shaping] object VowelConstraints:
  def apply(iso: String, cps: Array[Int], clusters: Array[Int]): (Array[Int], Array[Int], Array[Int]) =
    val pairs = VowelConstraintData.pairs.getOrElse(iso, Array.empty[Int])
    val triple = VowelConstraintData.triples.get(iso)
    if pairs.isEmpty then return (cps, clusters, new Array[Int](cps.length))
    val oc = ArrayBuffer.empty[Int]; val ol = ArrayBuffer.empty[Int]
    var i = 0
    def isPair(a: Int, b: Int): Boolean = (0 until pairs.length / 2).exists(k => pairs(2 * k) == a && pairs(2 * k + 1) == b)
    while i + 1 < cps.length do
      var matched = false
      var extra = 0
      triple match
        case Some(t) if cps(i) == t(0) && cps(i + 1) == t(1) && i + 2 < cps.length && cps(i + 2) == t(2) =>
          extra = 1; matched = true
        case _ => if isPair(cps(i), cps(i + 1)) then matched = true
      (0 to extra).foreach { k => oc += cps(i + k); ol += clusters(i + k) }
      i += 1 + extra
      if matched then { oc += 0x25CC; ol += clusters(i) }
    while i < cps.length do { oc += cps(i); ol += clusters(i); i += 1 }
    (oc.toArray, ol.toArray, new Array[Int](oc.length))
