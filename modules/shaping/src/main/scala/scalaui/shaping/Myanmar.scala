package scalaui.shaping

import scala.collection.mutable.ArrayBuffer
import scalaui.unicode.ShaperCat as Cat

/** Myanmar shaping: syllable reordering (medial Ra, left vowels, kinzi) before the basic features. */
private[shaping] object MyanmarShaper extends ComplexShaper:
  private object Type:
    inline val Consonant = 0; inline val Broken = 1; inline val NonMyanmar = 2

  override def collect(b: PlanBuilder, rtl: Boolean): Unit =
    b.pause(setupSyllables)
    b.enable("locl", perSyllable = true); b.enable("ccmp", perSyllable = true)
    b.pause(reorder)
    Seq("rphf", "pref", "blwf", "pstf").foreach { t => b.enable(t, manualZwj = true, perSyllable = true); b.pause() }
    b.pause()
    Seq("pres", "abvs", "blws", "psts").foreach(t => b.enable(t, manualZwj = true))

  override val normalization: NormalizeHooks = new NormalizeHooks(shortCircuit = false)
  override def zeroMarksEarly: Boolean = true
  override def zeroMarks: Boolean = false

  override def setupMasks(ctx: Ctx): Unit = ctx.buf.foreach(g => g.cat = Cat.table(g.cp))

  private val rules: Vector[Nfa] =
    import Re.*
    def s(c: Int*): Re = sym(c*)
    // IV, DB and GB share category ids with V, N and PLACEHOLDER in the shared table
    val C = s(Cat.C); val IV = s(Cat.V, Cat.IV); val DB = s(Cat.N, Cat.DB); val H = s(Cat.H); val ZWNJ = s(Cat.ZWNJ); val ZWJ = s(Cat.ZWJ)
    val SM = s(Cat.SM); val GB = s(Cat.PLACEHOLDER, Cat.GB); val DOTTEDCIRCLE = s(Cat.DOTTEDCIRCLE); val A = s(Cat.A); val Ra = s(Cat.Ra)
    val CS = s(Cat.CS); val SMPst = s(Cat.SMPst); val VAbv = s(Cat.VAbv); val VBlw = s(Cat.VBlw); val VPre = s(Cat.VPre)
    val VPst = s(Cat.VPst); val As = s(Cat.As); val MH = s(Cat.MH); val MR = s(Cat.MR); val MW = s(Cat.MW); val MY = s(Cat.MY)
    val PT = s(Cat.PT); val VS = s(Cat.VS); val ML = s(Cat.ML)
    val j = ZWJ | ZWNJ
    val k = Ra ~ As ~ H
    val sm = SM | SMPst
    val c = C | Ra
    val medial = MY.? ~ As.? ~ MR.? ~ (((MW ~ MH.? ~ ML.?) | (MH ~ ML.?) | ML) ~ As.?).?
    val mainVowel = (VPre ~ VS.?).* ~ VAbv.* ~ VBlw.* ~ A.* ~ (DB ~ As.?).?
    val postVowel = VPst ~ MH.? ~ ML.? ~ As.* ~ VAbv.* ~ A.* ~ (DB ~ As.?).?
    val tone = sm | (PT ~ A.* ~ DB.? ~ As.?)
    val complexTail = As.* ~ medial ~ mainVowel ~ postVowel.* ~ tone.* ~ j.?
    val syllableTail = (H ~ (c | IV) ~ VS.?).* ~ (H | complexTail)
    val consonant = (k | CS).? ~ (c | IV | GB | DOTTEDCIRCLE) ~ VS.? ~ syllableTail
    val broken = k.? ~ VS.? ~ syllableTail
    Vector(consonant, j | SMPst, broken).map(new Nfa(_))
  private val ruleType = Vector(Type.Consonant, Type.NonMyanmar, Type.Broken)

  private def setupSyllables(ctx: Ctx): Unit =
    val buf = ctx.buf
    val syms = Array.tabulate(buf.length)(i => buf(i).cat)
    var serial = 1
    Syllables.scan(syms, rules).foreach { (from, to, rule) =>
      val tpe = if rule < 0 then Type.NonMyanmar else ruleType(rule)
      var i = from
      while i < to do { buf(i).syllable = (serial << 4) | tpe; i += 1 }
      serial += 1
      if serial == 16 then serial = 1
    }

  private def reorder(ctx: Ctx): Unit =
    Syllabic.insertDottedCircles(ctx, Type.Broken, Cat.DOTTEDCIRCLE, -1, -1)
    Syllabic.foreachSyllable(ctx.buf) { (start, end) =>
      (ctx.buf(start).syllable & 0xF) match
        case Type.Consonant | Type.Broken => initialReorderingConsonantSyllable(ctx.buf, start, end)
        case _ => ()
    }

  private def isConsonant(g: G): Boolean =
    !g.ligated && (g.cat == Cat.C || g.cat == Cat.CS || g.cat == Cat.Ra || g.cat == Cat.V || g.cat == Cat.IV || g.cat == Cat.PLACEHOLDER || g.cat == Cat.GB || g.cat == Cat.DOTTEDCIRCLE)

  private def initialReorderingConsonantSyllable(info: ArrayBuffer[G], start: Int, end: Int): Unit =
    var base = end
    var hasReph = false
    var limit = start
    if start + 3 <= end && info(start).cat == Cat.Ra && info(start + 1).cat == Cat.As && info(start + 2).cat == Cat.H then
      limit += 3; base = start; hasReph = true
    if !hasReph then base = limit
    var q = limit
    var found = false
    while q < end && !found do
      if isConsonant(info(q)) then { base = q; found = true }
      q += 1

    var i = start
    while i < start + (if hasReph then 3 else 0) do { info(i).pos = Pos.AFTER_MAIN; i += 1 }
    while i < base do { info(i).pos = Pos.PRE_C; i += 1 }
    if i < end then { info(i).pos = Pos.BASE_C; i += 1 }
    var pos = Pos.AFTER_MAIN
    while i < end do
      val g = info(i)
      if g.cat == Cat.MR then g.pos = Pos.PRE_C
      else if g.cat == Cat.VPre then g.pos = Pos.PRE_M
      else if g.cat == Cat.VS then g.pos = info(i - 1).pos
      else if pos == Pos.AFTER_MAIN && g.cat == Cat.VBlw then { pos = Pos.BELOW_C; g.pos = pos }
      else if pos == Pos.BELOW_C && g.cat == Cat.A then g.pos = Pos.BEFORE_SUB
      else if pos == Pos.BELOW_C && g.cat == Cat.VBlw then g.pos = pos
      else if pos == Pos.BELOW_C && g.cat != Cat.A then { pos = Pos.AFTER_SUB; g.pos = pos }
      else g.pos = pos
      i += 1

    Syllabic.stableSort(info, start, end)(_.pos)

    var firstLeft = end; var lastLeft = end
    i = start
    while i < end do
      if info(i).pos == Pos.PRE_M then
        if firstLeft == end then firstLeft = i
        lastLeft = i
      i += 1
    if firstLeft < lastLeft then
      reverse(info, firstLeft, lastLeft + 1)
      var ii = firstLeft
      var j = ii
      while j <= lastLeft do
        if info(j).cat == Cat.VPre then { reverse(info, ii, j + 1); ii = j + 1 }
        j += 1

  private def reverse(buf: ArrayBuffer[G], from: Int, to: Int): Unit =
    var a = from; var b = to - 1
    while a < b do { val t = buf(a); buf(a) = buf(b); buf(b) = t; a += 1; b -= 1 }
