package scalaui.shaping

import scala.collection.mutable.ArrayBuffer
import scalaui.font.FontFace
import scalaui.unicode.{Gc, Normalizer, UseCat}

/** The Universal Shaping Engine (Microsoft's script-development spec, as HarfBuzz implements it): syllable clusters
  * for Tibetan, Sinhala, Balinese, Javanese, the Brahmi family, Mongolian and many more scripts.
  */
private[shaping] final class UseShaper(iso: String) extends ComplexShaper:
  private object Type:
    inline val ViramaTerminated = 0; inline val SakotTerminated = 1; inline val Standard = 2; inline val NumberJoinerTerminated = 3
    inline val Numeral = 4; inline val Symbol = 5; inline val Hieroglyph = 6; inline val Broken = 7; inline val NonCluster = 8

  private val arabicJoining = Set("Adlm", "Chrs", "Rohg", "Mand", "Mani", "Mong", "Nkoo", "Ougr", "Phag", "Phlp", "Sogd", "Syrc")
  private val U = UseCat

  // ---- features ----
  private val basic = Vector("rkrf", "abvf", "blwf", "half", "pstf", "vatu", "cjct")
  private val topographical = Vector("isol", "init", "medi", "fina")
  private val other = Vector("abvs", "blws", "haln", "pres", "psts")

  override def collect(b: PlanBuilder, rtl: Boolean): Unit =
    b.pause(setupSyllables)
    b.enable("locl", perSyllable = true); b.enable("ccmp", perSyllable = true); b.enable("nukt", perSyllable = true)
    b.enable("akhn", manualZwj = true, perSyllable = true)
    b.pause(clearSubstitutionFlags)
    b.add(Feat("rphf", global = false, manualZwj = true, perSyllable = true))
    b.pause(recordRphf)
    b.pause(clearSubstitutionFlags)
    b.enable("pref", manualZwj = true, perSyllable = true)
    b.pause(recordPref)
    basic.foreach(t => b.enable(t, manualZwj = true, perSyllable = true))
    b.pause(reorder)
    b.pause()
    topographical.foreach(t => b.add(Feat(t, global = false)))
    b.pause()
    other.foreach(t => b.enable(t, manualZwj = true))

  override def preprocess(font: FontFace, cps: Array[Int], clusters: Array[Int]): (Array[Int], Array[Int], Array[Int]) =
    VowelConstraints(iso, cps, clusters)

  override val normalization: NormalizeHooks = new NormalizeHooks(
    shortCircuit = false,
    compose = (a, b) =>
      val g = Gc.table(a)
      if g == Gc.Mn || g == Gc.Mc || g == Gc.Me then -1 else Normalizer.composePair(a, b))

  override def zeroMarksEarly: Boolean = true
  override def zeroMarks: Boolean = false

  override def setupMasks(ctx: Ctx): Unit =
    if arabicJoining(iso) then ArabicJoining.setup(ctx)
    ctx.buf.foreach(g => g.cat = UseCat.table(g.cp))

  // ---- syllable grammar (use-machine.rl) ----
  private val rules: Vector[Nfa] =
    import Re.*
    def s(c: Int*): Re = sym(c*)
    val O = s(U.O); val B = s(U.B); val N = s(U.N); val GB = s(U.GB); val SUB = s(U.SUB); val H = s(U.H); val HN = s(U.HN)
    val ZWNJ = s(U.ZWNJ); val R = s(U.R); val CS = s(U.CS); val IS = s(U.IS); val Sk = s(U.Sk); val G = s(U.G); val J = s(U.J)
    val SB = s(U.SB); val SE = s(U.SE); val HVM = s(U.HVM); val HM = s(U.HM); val HR = s(U.HR); val RK = s(U.RK)
    val FAbv = s(U.FAbv); val FBlw = s(U.FBlw); val FPst = s(U.FPst); val MAbv = s(U.MAbv); val MBlw = s(U.MBlw)
    val MPst = s(U.MPst); val MPre = s(U.MPre); val CMAbv = s(U.CMAbv); val CMBlw = s(U.CMBlw)
    val VAbv = s(U.VAbv); val VBlw = s(U.VBlw); val VPst = s(U.VPst); val VPre = s(U.VPre)
    val VMAbv = s(U.VMAbv); val VMBlw = s(U.VMBlw); val VMPst = s(U.VMPst); val VMPre = s(U.VMPre)
    val SMAbv = s(U.SMAbv); val SMBlw = s(U.SMBlw); val FMAbv = s(U.FMAbv); val FMBlw = s(U.FMBlw); val FMPst = s(U.FMPst)
    val h = H | HVM | IS | Sk
    val consonantModifiers = CMAbv.* ~ CMBlw.* ~ (((h ~ B) | SUB) ~ CMAbv.* ~ CMBlw.*).*
    val medialConsonants = MPre.? ~ MAbv.? ~ MBlw.? ~ MPst.?
    val dependentVowels = (VPre.* ~ VAbv.* ~ VBlw.* ~ VPst.*) | H
    val vowelModifiers = HVM.? ~ VMPre.* ~ VMAbv.* ~ VMBlw.* ~ VMPst.*
    val finalConsonants = FAbv.* ~ FBlw.* ~ FPst.*
    val finalModifiers = (FMAbv.* ~ FMBlw.*) | FMPst.?
    val complexStart = (R | CS).? ~ (B | GB)
    val complexMiddle = consonantModifiers ~ medialConsonants ~ dependentVowels ~ vowelModifiers ~ (Sk ~ B).*
    val complexTail = complexMiddle ~ finalConsonants ~ finalModifiers
    val numberJoinerTail = (HN ~ N).* ~ HN
    val numeralTail = (HN ~ N).+
    val symbolTail = (SMAbv.+ ~ SMBlw.*) | SMBlw.+
    val viramaTail = consonantModifiers ~ (IS | RK)
    val viramaTerminated = complexStart ~ viramaTail
    val sakotTail = complexMiddle ~ Sk
    val sakotTerminated = complexStart ~ sakotTail
    val standard = complexStart ~ complexTail
    val tail = complexTail | sakotTail | symbolTail | viramaTail
    val broken = R.? ~ (tail | numberJoinerTail | numeralTail)
    val numberJoinerTerminated = N ~ numberJoinerTail
    val numeral = N ~ numeralTail.?
    val symbol = (O | GB | SB) ~ tail.?
    val hieroglyph = SB.* ~ G ~ HR.? ~ HM.? ~ SE.* ~ (J ~ SB.* ~ (G ~ HR.? ~ HM.? ~ SE.*).?).*
    Vector(viramaTerminated ~ ZWNJ.?, sakotTerminated ~ ZWNJ.?, standard ~ ZWNJ.?, numberJoinerTerminated ~ ZWNJ.?, numeral ~ ZWNJ.?,
      symbol ~ ZWNJ.?, hieroglyph ~ ZWNJ.?, FMPst, broken ~ ZWNJ.?).map(new Nfa(_))
  private val ruleType = Vector(Type.ViramaTerminated, Type.SakotTerminated, Type.Standard, Type.NumberJoinerTerminated, Type.Numeral,
    Type.Symbol, Type.Hieroglyph, Type.NonCluster, Type.Broken)

  private def isMark(cp: Int): Boolean = { val g = Gc.table(cp); g == Gc.Mn || g == Gc.Mc || g == Gc.Me }

  private def setupSyllables(ctx: Ctx): Unit =
    val buf = ctx.buf
    // CGJ is invisible to the grammar, and a ZWNJ that precedes a mark does not end a syllable
    val keep = ArrayBuffer.empty[Int]
    var i = 0
    while i < buf.length do
      val g = buf(i)
      var drop = g.cat == U.CGJ
      if !drop && g.cat == U.ZWNJ then
        var j = i + 1
        var decided = false
        while j < buf.length && !decided do
          if buf(j).cat != U.CGJ then { drop = isMark(buf(j).cp); decided = true }
          j += 1
      if !drop then keep += i
      i += 1
    val syms = keep.map(buf(_).cat).toArray
    var serial = 1
    Syllables.scan(syms, rules).foreach { (from, to, rule) =>
      val tpe = if rule < 0 then Type.NonCluster else ruleType(rule)
      val a = keep(from); val b = if to < keep.length then keep(to) else buf.length
      var k = a
      while k < b do { buf(k).syllable = (serial << 4) | tpe; k += 1 }
      serial += 1
      if serial == 16 then serial = 1
    }
    setupRphfMask(ctx)
    setupTopographicalMasks(ctx)

  private def setupRphfMask(ctx: Ctx): Unit =
    val mask = ctx.mask("rphf")
    if mask != 0 then
      Syllabic.foreachSyllable(ctx.buf) { (start, end) =>
        val limit = if ctx.buf(start).cat == U.R then 1 else math.min(3, end - start)
        var i = start
        while i < start + limit do { ctx.buf(i).mask |= mask; i += 1 }
      }

  private def setupTopographicalMasks(ctx: Ctx): Unit =
    if arabicJoining(iso) then return
    val masks = topographical.map(ctx.mask).toArray
    val all = masks.foldLeft(0)(_ | _)
    if all == 0 then return
    val otherMasks = ~all
    val JoinFina = 3; val JoinIsol = 0; val JoinInit = 1; val JoinMedi = 2; val None = -1
    var lastStart = 0; var lastForm = None
    val buf = ctx.buf
    Syllabic.foreachSyllable(buf) { (start, end) =>
      (buf(start).syllable & 0xF) match
        case Type.Hieroglyph | Type.NonCluster => lastForm = None
        case _ =>
          val join = lastForm == JoinFina || lastForm == JoinIsol
          if join then
            lastForm = if lastForm == JoinFina then JoinMedi else JoinInit
            var i = lastStart
            while i < start do { buf(i).mask = (buf(i).mask & otherMasks) | masks(lastForm); i += 1 }
          lastForm = if join then JoinFina else JoinIsol
          var i = start
          while i < end do { buf(i).mask = (buf(i).mask & otherMasks) | masks(lastForm); i += 1 }
      lastStart = start
    }

  private def clearSubstitutionFlags(ctx: Ctx): Unit = ctx.buf.foreach(_.substituted = false)

  private def recordRphf(ctx: Ctx): Unit =
    val mask = ctx.mask("rphf")
    if mask != 0 then
      val info = ctx.buf
      Syllabic.foreachSyllable(info) { (start, end) =>
        var i = start
        var done = false
        while i < end && (info(i).mask & mask) != 0 && !done do
          if info(i).substituted then { info(i).cat = U.R; done = true }
          i += 1
      }

  private def recordPref(ctx: Ctx): Unit =
    val info = ctx.buf
    Syllabic.foreachSyllable(info) { (start, end) =>
      var i = start
      var done = false
      while i < end && !done do
        if info(i).substituted then { info(i).cat = U.VPre; done = true }
        i += 1
    }

  private def isHalant(g: G): Boolean = (g.cat == U.H || g.cat == U.HVM || g.cat == U.IS) && !g.ligated

  private val postBase: Long =
    Seq(U.FAbv, U.FBlw, U.FPst, U.FMAbv, U.FMBlw, U.FMPst, U.MAbv, U.MBlw, U.MPst, U.MPre, U.VAbv, U.VBlw, U.VPst, U.VPre,
      U.VMAbv, U.VMBlw, U.VMPst, U.VMPre).foldLeft(0L)((a, c) => a | (1L << c))

  private def reorder(ctx: Ctx): Unit =
    Syllabic.insertDottedCircles(ctx, Type.Broken, U.B, U.R, -1)
    Syllabic.foreachSyllable(ctx.buf)((start, end) => reorderSyllable(ctx, start, end))

  private def reorderSyllable(ctx: Ctx, start: Int, end: Int): Unit =
    val info = ctx.buf
    val tpe = info(start).syllable & 0xF
    if !(tpe == Type.ViramaTerminated || tpe == Type.SakotTerminated || tpe == Type.Standard || tpe == Type.Symbol || tpe == Type.Broken) then return

    // move the repha towards the end, before the first post-base glyph
    if info(start).cat == U.R && end - start > 1 then
      var i = start + 1
      var done = false
      while i < end && !done do
        val postBaseGlyph = (((1L << info(i).cat) & postBase) != 0) || isHalant(info(i))
        if postBaseGlyph || i == end - 1 then
          if postBaseGlyph then i -= 1
          Syllabic.mergeClusters(info, start, i + 1)
          val t = info.remove(start); info.insert(i, t)
          done = true
        i += 1

    // move pre-base vowels back, after the last halant
    var j = start
    var i = start
    while i < end do
      val g = info(i)
      if isHalant(g) then j = i + 1
      else if (g.cat == U.VPre || g.cat == U.VMPre) && g.ligComp == 0 && j < i then
        Syllabic.mergeClusters(info, j, i + 1)
        val t = info.remove(i); info.insert(j, t)
      i += 1
