package scalaui.shaping

import scala.collection.mutable.ArrayBuffer
import scalaui.font.FontFace
import scalaui.unicode.{Joining, Normalizer}

/** Script-specific shaping behaviour, modelled on HarfBuzz's shapers. Instances are shared by all shaping calls of a plan
  * and hold no per-run state; per-run data lives in the glyph buffer.
  */
private[shaping] trait ComplexShaper:
  /** Adds script features after the common prelude (rvrn, direction features). */
  def collect(b: PlanBuilder, rtl: Boolean): Unit = ()
  /** Adjusts the final feature list (e.g. disables features). */
  def overrideFeatures(b: PlanBuilder): Unit = ()
  /** Runs on the code points before normalization (e.g. inserting dotted circles into invalid vowel sequences). */
  def preprocess(font: FontFace, cps: Array[Int], clusters: Array[Int]): (Array[Int], Array[Int], Array[Int]) = (cps, clusters, new Array[Int](cps.length))
  def normalization: NormalizeHooks = new NormalizeHooks()
  /** Called once the buffer holds the normalized glyphs; sets per-glyph masks and shaper data. */
  def setupMasks(ctx: Ctx): Unit = ()
  def zeroMarks: Boolean = true
  /** Zero the advance of marks before GPOS runs instead of after. */
  def zeroMarksEarly: Boolean = false

private[shaping] object DefaultShaper extends ComplexShaper

private[shaping] object ShaperRegistry:
  /** Scripts HarfBuzz hands to the Universal Shaping Engine. */
  private val useScripts = Set("Tibt", "Mong", "Sinh", "Buhd", "Hano", "Tglg", "Tagb", "Limb", "Tale", "Bugi", "Khar", "Sylo", "Tfng", "Bali", "Nkoo", "Phag", "Cham", "Kali", "Lepc", "Rjng", "Saur", "Sund", "Egyp", "Java", "Kthi", "Mtei", "Lana", "Tavt", "Batk", "Brah", "Mand", "Cakm", "Plrd", "Shrd", "Takr", "Dupl", "Gran", "Khoj", "Sind", "Mahj", "Mani", "Modi", "Hmng", "Phlp", "Sidd", "Tirh", "Ahom", "Mult", "Adlm", "Bhks", "Marc", "Newa", "Gonm", "Soyo", "Zanb", "Dogr", "Gong", "Rohg", "Maka", "Medf", "Sogo", "Sogd", "Elym", "Nand", "Hmnp", "Wcho", "Chrs", "Diak", "Kits", "Yezi", "Cpmn", "Ougr", "Tnsa", "Toto", "Vith", "Kawi", "Nagm", "Gara", "Gukh", "Krai", "Onao", "Sunu", "Todr", "Tutg", "Berf", "Sidt", "Tayo", "Tols", "Jurc", "Pcun", "Seal")
  private val indic = Set("Deva", "Beng", "Guru", "Gujr", "Orya", "Taml", "Telu", "Knda", "Mlym")

  /** Picks the shaper like HarfBuzz does, from the script and the GSUB script tag the font offered for it. */
  /** Cursive-joining scripts that HarfBuzz shapes with the default shaper in vertical text (joining is horizontal-only). */
  private val joiningScripts = Set("Arab", "Mong", "Syrc", "Nkoo", "Phag", "Mand", "Mani", "Phlp", "Adlm", "Sogd", "Chrs", "Rohg", "Ougr", "Sogo", "Elym")

  def forScript(iso: String, gsubTag: String, vertical: Boolean = false): ComplexShaper =
    if vertical && joiningScripts(iso) then DefaultShaper
    else if iso == "Arab" || (iso == "Syrc" && gsubTag != "DFLT") then new ArabicShaper(iso)
    else if iso == "Thai" || iso == "Laoo" then ThaiShaper
    else if iso == "Hang" then HangulShaper
    else if iso == "Khmr" then KhmerShaper
    else if iso == "Mymr" then (if gsubTag == "DFLT" || gsubTag == "latn" || gsubTag == "mymr" then DefaultShaper else MyanmarShaper)
    else if indic(iso) then
      if gsubTag == "DFLT" || gsubTag == "latn" then DefaultShaper
      else if gsubTag.endsWith("3") then new UseShaper(iso)
      else new IndicShaper(iso)
    else if useScripts(iso) then new UseShaper(iso)
    else DefaultShaper

private[shaping] object ShapeUtil:
  /** Gives every position in `[from, to)` the smallest cluster of the range, extended to whole clusters like HarfBuzz. */
  def merge(cl: scala.collection.mutable.ArrayBuffer[Int], from: Int, to: Int): Unit =
    if to - from >= 2 then
      var start = from; var end = to
      val m = cl.slice(from, to).min
      while end < cl.length && cl(end - 1) == cl(end) do end += 1
      while start > 0 && cl(start - 1) == cl(start) do start -= 1
      (start until end).foreach(cl(_) = m)

/** Thai and Lao: SARA AM is decomposed into NIKHAHIT + SARA AA and the NIKHAHIT moves back over above-base marks. */
private[shaping] object ThaiShaper extends ComplexShaper:
  private def isSaraAm(x: Int) = (x & ~0x80) == 0x0E33
  private def isAboveBase(x: Int) =
    val y = x & ~0x80
    (y >= 0x0E34 && y <= 0x0E37) || (y >= 0x0E47 && y <= 0x0E4E) || y == 0x0E31 || y == 0x0E3B

  override def preprocess(font: FontFace, cps: Array[Int], clusters: Array[Int]): (Array[Int], Array[Int], Array[Int]) =
    val oc = ArrayBuffer.empty[Int]; val ol = ArrayBuffer.empty[Int]
    val merges = ArrayBuffer.empty[(Int, Int)] // applied once everything is output so they can extend over the rest of the text
    var i = 0
    while i < cps.length do
      val u = cps(i)
      if !isSaraAm(u) then { oc += u; ol += clusters(i) }
      else
        oc += u - 0x0E33 + 0x0E4D; ol += clusters(i)  // NIKHAHIT
        oc += u - 1; ol += clusters(i)                 // SARA AA
        val end = oc.length
        var start = end - 2
        while start > 0 && isAboveBase(oc(start - 1)) do start -= 1
        if start + 2 < end then
          val t = oc.remove(end - 2); val tl = ol.remove(end - 2)
          oc.insert(start, t); ol.insert(start, tl)
          merges += ((start, end))
        if start > 0 then merges += ((start - 1, end))
      i += 1
    merges.foreach((a, b) => ShapeUtil.merge(ol, a, b))
    (oc.toArray, ol.toArray, new Array[Int](oc.length))

/** Hangul: precompose jamo sequences when the font has the syllable, otherwise decompose and apply ljmo/vjmo/tjmo. */
private[shaping] object HangulShaper extends ComplexShaper:
  private inline val LBase = 0x1100; private inline val VBase = 0x1161; private inline val TBase = 0x11A7
  private inline val LCount = 19; private inline val VCount = 21; private inline val TCount = 28
  private inline val SBase = 0xAC00; private inline val NCount = VCount * TCount; private inline val SCount = LCount * NCount
  private def isCombiningL(u: Int) = u >= LBase && u < LBase + LCount
  private def isCombiningV(u: Int) = u >= VBase && u < VBase + VCount
  private def isCombiningT(u: Int) = u > TBase && u < TBase + TCount
  private def isCombinedS(u: Int) = u >= SBase && u < SBase + SCount
  private def isL(u: Int) = (u >= 0x1100 && u <= 0x115F) || (u >= 0xA960 && u <= 0xA97C)
  private def isV(u: Int) = (u >= 0x1160 && u <= 0x11A7) || (u >= 0xD7B0 && u <= 0xD7C6)
  private def isT(u: Int) = (u >= 0x11A8 && u <= 0x11FF) || (u >= 0xD7CB && u <= 0xD7FB)
  private def isTone(u: Int) = u == 0x302E || u == 0x302F
  private val Ljmo = 1; private val Vjmo = 2; private val Tjmo = 3
  private val featureTags = Array("", "ljmo", "vjmo", "tjmo")

  override def collect(b: PlanBuilder, rtl: Boolean): Unit =
    Seq("ljmo", "vjmo", "tjmo").foreach(t => b.add(Feat(t, global = false)))
  // 'calt' gets its own mask so jamo can be kept away from calt lookups that implement jamo shaping
  override def overrideFeatures(b: PlanBuilder): Unit = b.add(Feat("calt", global = false))
  override val normalization: NormalizeHooks = new NormalizeHooks(alwaysShortCircuit = true, recompose = false)
  override def zeroMarks: Boolean = false

  override def preprocess(font: FontFace, cps: Array[Int], clusters: Array[Int]): (Array[Int], Array[Int], Array[Int]) =
    val oc = ArrayBuffer.empty[Int]; val ol = ArrayBuffer.empty[Int]; val ot = ArrayBuffer.empty[Int]
    val n = cps.length
    def zeroWidth(u: Int) = font.hasGlyph(u) && font.advance(font.glyphIndex(u)) == 0
    def put(u: Int, cl: Int, tag: Int = 0): Unit = { oc += u; ol += cl; ot += tag }
    val merges = ArrayBuffer.empty[(Int, Int)]
    var i = 0; var start = 0; var end = 0
    while i < n do
      val u = cps(i)
      if isTone(u) then
        if start < end && end == oc.length then
          put(u, clusters(i)); i += 1
          if !zeroWidth(u) then
            merges += ((start, end + 1))
            val t = oc.remove(end); val tl = ol.remove(end); val tt = ot.remove(end)
            oc.insert(start, t); ol.insert(start, tl); ot.insert(start, tt)
        else if font.hasGlyph(0x25CC) then
          if !zeroWidth(u) then { put(u, clusters(i)); put(0x25CC, clusters(i)) } else { put(0x25CC, clusters(i)); put(u, clusters(i)) }
          i += 1
        else { put(u, clusters(i)); i += 1 }
        start = oc.length; end = start
      else
        start = oc.length
        var consumed = false
        if isL(u) && i + 1 < n && isV(cps(i + 1)) then
          val l = u; val v = cps(i + 1)
          var t = 0; var tindex = 0
          if i + 2 < n then { t = cps(i + 2); if isT(t) then tindex = t - TBase else t = 0 }
          val len = if t != 0 then 3 else 2
          if isCombiningL(l) && isCombiningV(v) && (t == 0 || isCombiningT(t)) then
            val s = SBase + (l - LBase) * NCount + (v - VBase) * TCount + tindex
            if font.hasGlyph(s) then
              put(s, (0 until len).map(k => clusters(i + k)).min); i += len; end = start + 1; consumed = true
          if !consumed then
            put(l, clusters(i), Ljmo); put(v, clusters(i + 1), Vjmo)
            if t != 0 then put(t, clusters(i + 2), Tjmo)
            end = start + len
            merges += ((start, end))
            i += len; consumed = true
        else if isCombinedS(u) then
          val hasGlyph = font.hasGlyph(u)
          val lindex = (u - SBase) / NCount; val nindex = (u - SBase) % NCount
          val vindex = nindex / TCount; val tindex = nindex % TCount
          if tindex == 0 && i + 1 < n && isCombiningT(cps(i + 1)) then
            val newS = u + (cps(i + 1) - TBase)
            if font.hasGlyph(newS) then
              put(newS, math.min(clusters(i), clusters(i + 1))); i += 2; end = start + 1; consumed = true
          if !consumed && (!hasGlyph || (tindex == 0 && i + 1 < n && isT(cps(i + 1)))) then
            val dl = LBase + lindex; val dv = VBase + vindex; val dt = TBase + tindex
            if font.hasGlyph(dl) && font.hasGlyph(dv) && (tindex == 0 || font.hasGlyph(dt)) then
              put(dl, clusters(i), Ljmo); put(dv, clusters(i), Vjmo)
              var sLen = 2
              if tindex != 0 then { put(dt, clusters(i), Tjmo); sLen = 3 }
              i += 1
              if hasGlyph && tindex == 0 then { put(cps(i), clusters(i), Tjmo); i += 1; sLen += 1 }
              end = start + sLen
              merges += ((start, end))
              consumed = true
          if !consumed && hasGlyph then end = start + 1
        if !consumed then { put(u, clusters(i)); i += 1 }
    merges.foreach((a, b) => ShapeUtil.merge(ol, a, b))
    (oc.toArray, ol.toArray, ot.toArray)

  override def setupMasks(ctx: Ctx): Unit =
    val calt = ctx.mask("calt")
    ctx.buf.foreach { g =>
      if g.feat != 0 then g.mask |= ctx.mask(featureTags(g.feat))
      if isL(g.cp) || isV(g.cp) || isT(g.cp) then g.mask &= ~calt
    }

/** Arabic-family shaping: contextual joining forms, with HarfBuzz's feature staging. */
private[shaping] final class ArabicShaper(iso: String) extends ComplexShaper:
  private val forms = Vector("isol", "fina", "fin2", "fin3", "medi", "med2", "init")

  override def collect(b: PlanBuilder, rtl: Boolean): Unit =
    b.enable("stch"); b.pause()
    b.enable("ccmp", manualZwj = true); b.enable("locl", manualZwj = true)
    b.pause()
    forms.foreach { tag => b.add(Feat(tag, global = false, manualZwj = true)); b.pause() }
    b.pause()
    b.enable("rlig", manualZwj = true)
    if iso == "Arab" then b.pause()
    b.enable("calt", manualZwj = true)
    b.pause()
    b.enable("liga", manualZwj = true); b.enable("clig", manualZwj = true); b.enable("mset", manualZwj = true)

  private val modifierMarks = Set(0x0654, 0x0655, 0x0658, 0x06DC, 0x06E3, 0x06E7, 0x06E8, 0x08CA, 0x08CB, 0x08CD, 0x08CE, 0x08CF, 0x08D3, 0x08F3)

  /** Modifier combining marks (UTR #53) move in front of the other marks of the same class. */
  private def reorderMarks(c: ArrayBuffer[Int], cl: ArrayBuffer[Int], from: Int, end: Int): Unit =
    var start = from
    var i = start
    var cc = 220
    var done = false
    while cc <= 230 && !done do
      while i < end && Normalizer.modifiedCombiningClass(c(i)) < cc do i += 1
      if i == end then done = true
      else if Normalizer.modifiedCombiningClass(c(i)) <= cc then
        var j = i
        while j < end && Normalizer.modifiedCombiningClass(c(j)) == cc && modifierMarks(c(j)) do j += 1
        if i != j then
          val m = cl.slice(start, j).min
          (start until j).foreach(cl(_) = m)
          val tmp = c.slice(i, j).toVector
          val head = c.slice(start, i).toVector
          (0 until tmp.length).foreach(k => c(start + k) = tmp(k))
          (0 until head.length).foreach(k => c(start + tmp.length + k) = head(k))
          start += j - i
          i = j
      cc += 10

  override val normalization: NormalizeHooks = new NormalizeHooks(reorderMarks = reorderMarks)

  override def setupMasks(ctx: Ctx): Unit = ArabicJoining.setup(ctx)

/** Assigns the joining-form feature masks (isol/init/medi/fina ...) from the Unicode joining types. */
private[shaping] object ArabicJoining:
  private def joiningType(cp: Int): Char =
    if cp == 0x200D then 'C' else Joining.names(Joining.table(cp)).charAt(0)

  def setup(ctx: Ctx): Unit =
    val g = ctx.buf
    val n = g.length
    val jt = Array.tabulate(n)(i => joiningType(g(i).cp))
    val joinsPrev = new Array[Boolean](n)
    var prev = -1 // previous non-transparent glyph
    var i = 0
    while i < n do
      if jt(i) != 'T' then
        val pj = prev >= 0 && (jt(prev) == 'D' || jt(prev) == 'L' || jt(prev) == 'C')
        joinsPrev(i) = pj && (jt(i) == 'D' || jt(i) == 'R' || jt(i) == 'C')
        prev = i
      i += 1
    var next = -1
    i = n - 1
    while i >= 0 do
      if jt(i) != 'T' then
        val joinsNext = next >= 0 && joinsPrev(next) && (jt(i) == 'D' || jt(i) == 'L' || jt(i) == 'C')
        val form =
          if g(i).cp == 0x200D then ""
          else jt(i) match
            case 'D' | 'C' => if joinsPrev(i) && joinsNext then "medi" else if joinsPrev(i) then "fina" else if joinsNext then "init" else "isol"
            case 'R' => if joinsPrev(i) then "fina" else "isol"
            case 'L' => if joinsNext then "init" else "isol"
            case _ => ""
        if form.nonEmpty then g(i).mask |= ctx.mask(form)
        next = i
      i -= 1
