package scalaui.unicode

/** Extended grapheme cluster boundaries (UAX #29 rev. 49: GB9c Linker Extend* × Consonant, GB11 emoji sequences). */
object Graphemes:
  /** `flags(i)` is true when there is a boundary before code point `i`; length is `n + 1` (boundaries at 0 and n). */
  def boundaries(cp: CodePoints): Array[Boolean] =
    val c = cp.cps; val n = c.length
    val out = new Array[Boolean](n + 1)
    if n == 0 then return out
    out(0) = true; out(n) = true
    var riRun = 0              // regional indicators seen immediately before (incl. previous)
    var pict = 0               // 1: ExtPict Extend*, 2: ExtPict Extend* ZWJ
    var linker = false         // GB9c: a Linker followed only by InCB=Extend characters so far
    var prev = Gcb.Other
    // state for position 0's character
    def advance(ch: Int, g: Int): Unit =
      g match
        case Gcb.Regional_Indicator => riRun += 1
        case _ => riRun = 0
      val isPict = ExtPict.table(ch) == ExtPict.Extended_Pictographic
      pict = if isPict then 1
             else if g == Gcb.Extend && pict >= 1 && pict != 2 then 1
             else if g == Gcb.ZWJ && pict == 1 then 2
             else 0
      InCB.table(ch) match
        case InCB.Linker => linker = true
        case InCB.Extend => ()
        case _ => linker = false
    prev = Gcb.table(c(0)); advance(c(0), prev)
    var i = 1
    while i < n do
      val ch = c(i); val g = Gcb.table(ch)
      val brk =
        if prev == Gcb.CR && g == Gcb.LF then false                                          // GB3
        else if prev == Gcb.Control || prev == Gcb.CR || prev == Gcb.LF then true            // GB4
        else if g == Gcb.Control || g == Gcb.CR || g == Gcb.LF then true                     // GB5
        else if prev == Gcb.L && (g == Gcb.L || g == Gcb.V || g == Gcb.LV || g == Gcb.LVT) then false  // GB6
        else if (prev == Gcb.LV || prev == Gcb.V) && (g == Gcb.V || g == Gcb.T) then false   // GB7
        else if (prev == Gcb.LVT || prev == Gcb.T) && g == Gcb.T then false                  // GB8
        else if g == Gcb.Extend || g == Gcb.ZWJ then false                                   // GB9
        else if g == Gcb.SpacingMark then false                                              // GB9a
        else if prev == Gcb.Prepend then false                                               // GB9b
        else if linker && InCB.table(ch) == InCB.Consonant then false                          // GB9c
        else if pict == 2 && ExtPict.table(ch) == ExtPict.Extended_Pictographic then false   // GB11
        else if prev == Gcb.Regional_Indicator && g == Gcb.Regional_Indicator && riRun % 2 == 1 then false // GB12/13
        else true
      out(i) = brk
      advance(ch, g)
      prev = g
      i += 1
    out

  /** UTF-16 offsets of all cluster boundaries in `s`, including 0 and `s.length`. */
  def offsets(s: String): Array[Int] =
    val cp = CodePoints(s)
    if cp.size == 0 then Array(0) else CodePoints.toOffsets(cp, boundaries(cp))

  /** The next cluster boundary strictly after UTF-16 offset `i` (or `s.length`). */
  def next(s: String, i: Int): Int =
    val o = offsets(s)
    var k = 0
    while k < o.length && o(k) <= i do k += 1
    if k < o.length then o(k) else s.length

  /** The previous cluster boundary strictly before UTF-16 offset `i` (or 0). */
  def prev(s: String, i: Int): Int =
    val o = offsets(s)
    var k = o.length - 1
    while k >= 0 && o(k) >= i do k -= 1
    if k >= 0 then o(k) else 0
