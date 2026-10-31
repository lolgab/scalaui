package scalaui.unicode

import scala.collection.mutable

/** Canonical normalization forms NFD and NFC (UAX #15). Compatibility forms are not provided. */
object Normalizer:
  private inline val SBase = 0xAC00; private inline val LBase = 0x1100; private inline val VBase = 0x1161; private inline val TBase = 0x11A7
  private inline val LCount = 19; private inline val VCount = 21; private inline val TCount = 28
  private inline val NCount = VCount * TCount; private inline val SCount = LCount * NCount

  private val cccOfValue: Array[Int] = Ccc.names.map(_.toInt)
  def combiningClass(cp: Int): Int = cccOfValue(Ccc.table(cp))

  /** Combining classes remapped so text shapers order marks the way fonts expect (HarfBuzz's modified classes):
    * Hebrew points, Arabic vowels (shadda first), Syriac, Telugu, Thai, Lao and Tibetan marks get fixed positions.
    */
  private val modified: Array[Int] =
    val m = Array.tabulate(256)(identity)
    Seq(10 -> 22, 11 -> 15, 12 -> 16, 13 -> 17, 14 -> 23, 15 -> 18, 16 -> 19, 17 -> 20, 18 -> 21, 19 -> 14, 20 -> 24, 21 -> 12, 22 -> 25,
      23 -> 13, 24 -> 10, 25 -> 11, 26 -> 26, 27 -> 28, 28 -> 29, 29 -> 30, 30 -> 31, 31 -> 32, 32 -> 33, 33 -> 27, 34 -> 34, 35 -> 35,
      36 -> 36, 84 -> 4, 91 -> 5, 103 -> 3, 107 -> 107, 118 -> 118, 122 -> 122, 129 -> 129, 130 -> 132, 132 -> 131).foreach((a, b) => m(a) = b)
    m
  def modifiedCombiningClass(cp: Int): Int =
    val c = combiningClass(cp)
    // Tai Tham sakot and Tibetan padma/tsa-phru must sort late/early
    if cp == 0x1A60 then 254 else if cp == 0x0FC6 then 254 else if cp == 0x0F39 then 127 else modified(c)

  private lazy val decomp: Map[Int, (Int, Int)] =
    val d = NormData.decompositions
    Map.from((0 until d.length / 3).map(i => d(i * 3) -> (d(i * 3 + 1), d(i * 3 + 2))))

  private lazy val composition: Map[Long, Int] =
    val excl = NormData.exclusions.toSet
    val b = Map.newBuilder[Long, Int]
    decomp.foreach { case (cp, (a, c)) =>
      // singletons, script exclusions and non-starter decompositions never recompose
      if c != 0 && !excl(cp) && combiningClass(cp) == 0 && combiningClass(a) == 0 then b += ((a.toLong << 21) | c) -> cp
    }
    b.result()

  private def decomposeInto(cp: Int, out: mutable.ArrayBuilder[Int]): Unit =
    if cp >= SBase && cp < SBase + SCount then
      val si = cp - SBase
      out += LBase + si / NCount
      out += VBase + (si % NCount) / TCount
      val t = TBase + si % TCount
      if t != TBase then out += t
    else decomp.get(cp) match
      case Some((a, b)) => decomposeInto(a, out); if b != 0 then decomposeInto(b, out)
      case None => out += cp

  /** Canonical decomposition in canonical order. */
  def nfd(cps: Array[Int]): Array[Int] =
    val b = mutable.ArrayBuilder.make[Int]
    cps.foreach(decomposeInto(_, b))
    val d = b.result()
    // canonical ordering: stable sort of each run of non-starters by combining class
    var i = 0
    while i < d.length do
      if combiningClass(d(i)) == 0 then i += 1
      else
        var j = i
        while j < d.length && combiningClass(d(j)) != 0 do j += 1
        val run = d.slice(i, j).sortBy(combiningClass) // sortBy is stable
        System.arraycopy(run, 0, d, i, run.length)
        i = j
    d

  def nfc(cps: Array[Int]): Array[Int] =
    val d = nfd(cps)
    if d.isEmpty then return d
    val out = mutable.ArrayBuffer.empty[Int]
    var starter = -1
    var lastCcc = -1
    var i = 0
    while i < d.length do
      val c = d(i); val cc = combiningClass(c)
      var composed = -1
      if starter >= 0 && (lastCcc < cc || lastCcc == 0 && out.length - 1 == starter) then
        val s = out(starter)
        if s >= LBase && s < LBase + LCount && c >= VBase && c < VBase + VCount then
          composed = SBase + ((s - LBase) * VCount + (c - VBase)) * TCount
        else if s >= SBase && s < SBase + SCount && (s - SBase) % TCount == 0 && c > TBase && c < TBase + TCount then
          composed = s + (c - TBase)
        else composed = composition.getOrElse((s.toLong << 21) | c, -1)
      if composed >= 0 then out(starter) = composed
      else
        if cc == 0 then starter = out.length
        lastCcc = cc
        out += c
      i += 1
    out.toArray

  private def cpsOf(s: String): Array[Int] = CodePoints(s).cps
  private def str(cps: Array[Int]): String = new String(cps, 0, cps.length)
  def nfd(s: String): String = str(nfd(cpsOf(s)))
  def nfc(s: String): String = str(nfc(cpsOf(s)))

  /** Single-step composition of a pair (e.g. 'e' + U+0301 -> U+00E9), or -1. Used for font glyph selection. */
  def composePair(a: Int, b: Int): Int =
    if a >= LBase && a < LBase + LCount && b >= VBase && b < VBase + VCount then SBase + ((a - LBase) * VCount + (b - VBase)) * TCount
    else if a >= SBase && a < SBase + SCount && (a - SBase) % TCount == 0 && b > TBase && b < TBase + TCount then a + (b - TBase)
    else composition.getOrElse((a.toLong << 21) | b, -1)

  /** One level of canonical decomposition of `cp` (first, second or 0), or None. */
  def decomposeOnce(cp: Int): Option[(Int, Int)] =
    if cp >= SBase && cp < SBase + SCount then
      val si = cp - SBase
      if si % TCount == 0 then Some((LBase + si / NCount, VBase + (si % NCount) / TCount))
      else Some((cp - si % TCount, TBase + si % TCount))
    else decomp.get(cp)
