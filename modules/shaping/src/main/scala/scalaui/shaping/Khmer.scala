package scalaui.shaping

import scalaui.unicode.{Gc, Normalizer, ShaperCat as Cat}

/** Khmer shaping: syllables with coeng (subscript) consonants, pre-base vowels and the Ro coeng reordered before the base. */
private[shaping] object KhmerShaper extends ComplexShaper:
  private object Type:
    inline val Consonant = 0; inline val Broken = 1; inline val NonKhmer = 2

  override def collect(b: PlanBuilder, rtl: Boolean): Unit =
    b.pause(setupSyllables)
    b.pause(reorder)
    b.enable("locl", perSyllable = true); b.enable("ccmp", perSyllable = true)
    Seq("pref", "blwf", "abvf", "pstf", "cfar").foreach(t => b.add(Feat(t, global = false, manualZwj = true, manualZwnj = true, perSyllable = true)))
    b.pause()
    Seq("pres", "abvs", "blws", "psts").foreach(t => b.add(Feat(t, global = true, manualZwj = true, manualZwnj = true)))

  override def overrideFeatures(b: PlanBuilder): Unit =
    b.enable("clig")
    b.disable("liga")

  override def zeroMarks: Boolean = false

  override val normalization: NormalizeHooks = new NormalizeHooks(
    shortCircuit = false,
    decompose = ab => ab match
      case 0x17BE | 0x17BF | 0x17C0 | 0x17C4 | 0x17C5 => Some((0x17C1, ab))
      case _ => Normalizer.decomposeOnce(ab),
    compose = (a, b) =>
      val g = Gc.table(a)
      if g == Gc.Mn || g == Gc.Mc || g == Gc.Me then -1 else Normalizer.composePair(a, b))

  override def setupMasks(ctx: Ctx): Unit = ctx.buf.foreach(g => g.cat = Cat.table(g.cp))

  private val rules: Vector[Nfa] =
    import Re.*
    def s(c: Int*): Re = sym(c*)
    val C = s(Cat.C); val V = s(Cat.V); val H = s(Cat.H); val ZWNJ = s(Cat.ZWNJ); val ZWJ = s(Cat.ZWJ)
    val PLACEHOLDER = s(Cat.PLACEHOLDER); val DOTTEDCIRCLE = s(Cat.DOTTEDCIRCLE); val Ra = s(Cat.Ra)
    val VAbv = s(Cat.VAbv); val VBlw = s(Cat.VBlw); val VPre = s(Cat.VPre); val VPst = s(Cat.VPst)
    val Robatic = s(Cat.Robatic); val Xgroup = s(Cat.Xgroup); val Ygroup = s(Cat.Ygroup)
    val c = C | Ra | V
    val joiner = ZWJ | ZWNJ
    val cn = c ~ ((joiner.? ~ Robatic).?)
    val xgroup = (joiner.* ~ Xgroup).*
    val ygroup = Ygroup.*
    val matraGroup = VPre.? ~ xgroup ~ VBlw.? ~ xgroup ~ (joiner.? ~ VAbv).? ~ xgroup ~ VPst.?
    val syllableTail = xgroup ~ matraGroup ~ xgroup ~ (H ~ c).? ~ ygroup
    val broken = Robatic.? ~ (H ~ cn).* ~ (H | syllableTail)
    val consonant = (cn | PLACEHOLDER | DOTTEDCIRCLE) ~ broken
    Vector(consonant, broken).map(new Nfa(_))
  private val ruleType = Vector(Type.Consonant, Type.Broken)

  private def setupSyllables(ctx: Ctx): Unit =
    val buf = ctx.buf
    val syms = Array.tabulate(buf.length)(i => buf(i).cat)
    var serial = 1
    Syllables.scan(syms, rules).foreach { (from, to, rule) =>
      val tpe = if rule < 0 then Type.NonKhmer else ruleType(rule)
      var i = from
      while i < to do { buf(i).syllable = (serial << 4) | tpe; i += 1 }
      serial += 1
      if serial == 16 then serial = 1
    }

  private def reorder(ctx: Ctx): Unit =
    Syllabic.insertDottedCircles(ctx, Type.Broken, Cat.DOTTEDCIRCLE, -1, -1)
    Syllabic.foreachSyllable(ctx.buf) { (start, end) =>
      (ctx.buf(start).syllable & 0xF) match
        case Type.Consonant | Type.Broken => reorderConsonantSyllable(ctx, start, end)
        case _ => ()
    }

  private def reorderConsonantSyllable(ctx: Ctx, start: Int, end: Int): Unit =
    val info = ctx.buf
    val blwf = ctx.mask("blwf"); val abvf = ctx.mask("abvf"); val pstf = ctx.mask("pstf")
    val pref = ctx.mask("pref"); val cfar = ctx.mask("cfar")
    var i = start + 1
    while i < end do { info(i).mask |= blwf | abvf | pstf; i += 1 }
    var numCoengs = 0
    i = start + 1
    while i < end do
      if info(i).cat == Cat.H && numCoengs <= 2 && i + 1 < end then
        numCoengs += 1
        if info(i + 1).cat == Cat.Ra then
          info(i).mask |= pref; info(i + 1).mask |= pref
          // move the Coeng,Ro pair to the start
          Syllabic.mergeClusters(info, start, i + 2)
          val t0 = info(i); val t1 = info(i + 1)
          var k = i + 1
          while k >= start + 2 do { info(k) = info(k - 2); k -= 1 }
          info(start) = t0; info(start + 1) = t1
          if cfar != 0 then
            var j = i + 2
            while j < end do { info(j).mask |= cfar; j += 1 }
          numCoengs = 2
      else if info(i).cat == Cat.VPre then
        Syllabic.mergeClusters(info, start, i + 1)
        val t = info.remove(i); info.insert(start, t)
      i += 1
