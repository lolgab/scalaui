package scalaui.unicode

/** Word boundaries (UAX #29 WB rules). */
object Words:
  private def ahl(w: Int) = w == Wb.ALetter || w == Wb.Hebrew_Letter
  private def midNumLetQ(w: Int) = w == Wb.MidNumLet || w == Wb.Single_Quote
  private def newline(w: Int) = w == Wb.CR || w == Wb.LF || w == Wb.Newline
  private def ignorable(w: Int) = w == Wb.Extend || w == Wb.Format || w == Wb.ZWJ

  def boundaries(cp: CodePoints): Array[Boolean] =
    val c = cp.cps; val n = c.length
    val out = new Array[Boolean](n + 1)
    if n == 0 then return out
    out(0) = true; out(n) = true
    val w = Array.tabulate(n)(i => Wb.table(c(i)))
    // effective class after WB4: Extend/Format/ZWJ following a non-newline character are folded into it.
    // `eff(i)` is the index of the last non-ignorable code point at or before i (or -1 when none/at sot or after newline).
    val base = new Array[Int](n)
    var last = -1
    var i = 0
    while i < n do
      if !ignorable(w(i)) || last < 0 || newline(w(last)) then last = i
      base(i) = last
      i += 1
    // next non-ignorable index at or after j
    def nextBase(j: Int): Int =
      var k = j
      while k < n && ignorable(w(k)) && base(k) != k do k += 1
      if k < n then k else -1
    def wAt(k: Int) = if k < 0 then -1 else w(k)
    var ri = 0 // regional indicators among folded sequence before position
    i = 1
    while i < n do
      val cur = w(i); val pr = w(i - 1)
      val brk =
        if pr == Wb.CR && cur == Wb.LF then false                              // WB3
        else if newline(pr) then true                                           // WB3a
        else if newline(cur) then true                                          // WB3b
        else if pr == Wb.ZWJ && ExtPict.table(c(i)) == ExtPict.Extended_Pictographic then false // WB3c
        else if pr == Wb.WSegSpace && cur == Wb.WSegSpace then false            // WB3d
        else if ignorable(cur) then false                                       // WB4
        else
          val b = base(i - 1)
          val p = w(b)                                                         // previous effective class
          // previous-previous effective (skipping ignorables)
          def prevBefore(k: Int): Int =
            var j = k - 1
            while j >= 0 && base(j) != j do j -= 1
            if j < 0 then -1 else w(j)
          val pp = prevBefore(b)
          val nx = wAt(nextBase(i + 1))
          if ahl(p) && ahl(cur) then false                                      // WB5
          else if ahl(p) && (cur == Wb.MidLetter || midNumLetQ(cur)) && ahl(nx) then false // WB6
          else if ahl(pp) && (p == Wb.MidLetter || midNumLetQ(p)) && ahl(cur) then false   // WB7
          else if p == Wb.Hebrew_Letter && cur == Wb.Single_Quote then false    // WB7a
          else if p == Wb.Hebrew_Letter && cur == Wb.Double_Quote && nx == Wb.Hebrew_Letter then false // WB7b
          else if pp == Wb.Hebrew_Letter && p == Wb.Double_Quote && cur == Wb.Hebrew_Letter then false // WB7c
          else if p == Wb.Numeric && cur == Wb.Numeric then false               // WB8
          else if ahl(p) && cur == Wb.Numeric then false                        // WB9
          else if p == Wb.Numeric && ahl(cur) then false                        // WB10
          else if pp == Wb.Numeric && (p == Wb.MidNum || midNumLetQ(p)) && cur == Wb.Numeric then false // WB11
          else if p == Wb.Numeric && (cur == Wb.MidNum || midNumLetQ(cur)) && nx == Wb.Numeric then false // WB12
          else if p == Wb.Katakana && cur == Wb.Katakana then false             // WB13
          else if (ahl(p) || p == Wb.Numeric || p == Wb.Katakana || p == Wb.ExtendNumLet) && cur == Wb.ExtendNumLet then false // WB13a
          else if p == Wb.ExtendNumLet && (ahl(cur) || cur == Wb.Numeric || cur == Wb.Katakana) then false // WB13b
          else if p == Wb.Regional_Indicator && cur == Wb.Regional_Indicator then
            // WB15/16: break unless an odd number of RI precede
            var cnt = 0; var j = b
            while j >= 0 && w(j) == Wb.Regional_Indicator do
              cnt += 1
              j -= 1
              while j >= 0 && base(j) != j do j -= 1
            cnt % 2 == 0
          else true                                                            // WB999
      out(i) = brk
      i += 1
    out

  /** UTF-16 offsets of word boundaries in `s`, including 0 and `s.length`. */
  def offsets(s: String): Array[Int] =
    val cp = CodePoints(s)
    if cp.size == 0 then Array(0) else CodePoints.toOffsets(cp, boundaries(cp))
