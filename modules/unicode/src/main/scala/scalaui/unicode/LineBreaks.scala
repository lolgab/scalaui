package scalaui.unicode

/** Line break opportunities (UAX #14, rules LB1-LB31 with the default class resolution). */
object LineBreaks:
  final val NoBreak: Byte = 0
  final val Allowed: Byte = 1
  final val Mandatory: Byte = 2

  private def resolve(cp: Int): Int =
    val c = Lb.table(cp)
    c match
      case Lb.AI | Lb.SG | Lb.XX => Lb.AL
      case Lb.SA =>
        val g = Gc.table(cp)
        if g == Gc.Mn || g == Gc.Mc then Lb.CM else Lb.AL
      case Lb.CJ => Lb.NS
      case _ => c

  private def isEastAsian(cp: Int): Boolean =
    val e = Eaw.table(cp)
    e == Eaw.F || e == Eaw.W || e == Eaw.H

  /** Break opportunities per code point boundary: entry `i` describes the boundary before code point `i`
    * (entry 0 is always NoBreak); entry `n` is the end of text (Allowed, or Mandatory after a hard break). */
  def opportunities(cp: CodePoints): Array[Byte] =
    val c = cp.cps; val n = c.length
    val out = new Array[Byte](n + 1)
    if n == 0 then return out
    // --- tokenize: LB9 folds CM/ZWJ into the preceding non-excluded character; LB10 turns the rest into AL
    val first = new Array[Int](n)   // code point index of token start
    val cls = new Array[Int](n)     // resolved class of token
    val rep = new Array[Int](n)     // base code point of token
    var t = 0
    var i = 0
    while i < n do
      val k = resolve(c(i))
      val absorbed = (k == Lb.CM || k == Lb.ZWJ) && t > 0 && {
        val p = cls(t - 1); p != Lb.BK && p != Lb.CR && p != Lb.LF && p != Lb.NL && p != Lb.SP && p != Lb.ZW
      }
      if !absorbed then
        first(t) = i; rep(t) = c(i)
        cls(t) = if k == Lb.CM || k == Lb.ZWJ then Lb.AL else k
        t += 1
      i += 1
    val m = t
    def ea(k: Int) = isEastAsian(rep(k))
    def isDotted(k: Int) = rep(k) == 0x25CC
    def cn(k: Int) = Gc.table(rep(k)) == Gc.Cn
    // last non-SP token index strictly before k (or -1)
    def skipSp(k: Int): Int = { var j = k - 1; while j >= 0 && cls(j) == Lb.SP do j -= 1; j }
    def at(k: Int) = if k >= 0 && k < m then cls(k) else -1
    def isAkLike(k: Int) = k >= 0 && (cls(k) == Lb.AK || cls(k) == Lb.AS || isDotted(k))
    def isAkDot(k: Int) = k >= 0 && (cls(k) == Lb.AK || isDotted(k))
    def numCtx(k: Int): Boolean =   // NU (SY|IS)* ending exactly at token k
      var j = k
      while j >= 0 && (cls(j) == Lb.SY || cls(j) == Lb.IS) do j -= 1
      j >= 0 && cls(j) == Lb.NU
    def korean(x: Int) = x == Lb.JL || x == Lb.JV || x == Lb.JT || x == Lb.H2 || x == Lb.H3

    // decision for boundary before token j (j >= 1): 0 no, 1 allowed, 2 mandatory
    def decide(j: Int): Byte =
      val a = cls(j - 1); val b = cls(j)
      val ia = j - 1
      if a == Lb.BK then return Mandatory                                          // LB4
      if a == Lb.CR then return (if b == Lb.LF then NoBreak else Mandatory)        // LB5
      if a == Lb.LF || a == Lb.NL then return Mandatory
      if b == Lb.BK || b == Lb.CR || b == Lb.LF || b == Lb.NL then return NoBreak  // LB6
      if b == Lb.SP || b == Lb.ZW then return NoBreak                              // LB7
      val pre = skipSp(j)                                                          // last non-SP token before the boundary
      if pre >= 0 && cls(pre) == Lb.ZW then return Allowed                         // LB8
      if c(first(j) - 1) == 0x200D then return NoBreak                             // LB8a (raw ZWJ before the boundary)
      if a == Lb.WJ || b == Lb.WJ then return NoBreak                              // LB11
      if a == Lb.GL then return NoBreak                                            // LB12
      if b == Lb.GL && a != Lb.SP && a != Lb.HY && a != Lb.HH then return NoBreak // LB12a (Unicode 18: BA no longer exempt)
      if b == Lb.CL || b == Lb.CP || b == Lb.EX || b == Lb.SY then return NoBreak  // LB13
      if pre >= 0 && cls(pre) == Lb.OP then return NoBreak                         // LB14
      // LB15a: (sot|BK|CR|LF|NL|OP|QU|GL|SP|ZW) [Pi & QU] SP* ×
      if pre >= 0 && cls(pre) == Lb.QU && Gc.table(rep(pre)) == Gc.Pi then
        val x = at(pre - 1)
        if x == -1 || x == Lb.BK || x == Lb.CR || x == Lb.LF || x == Lb.NL || x == Lb.OP || x == Lb.QU ||
           x == Lb.GL || x == Lb.SP || x == Lb.ZW then return NoBreak
      // LB15b: × [Pf & QU] (SP|GL|WJ|CL|QU|CP|EX|IS|SY|BK|CR|LF|NL|ZW|eot)
      if b == Lb.QU && Gc.table(rep(j)) == Gc.Pf then
        val x = at(j + 1)
        if x == -1 || x == Lb.SP || x == Lb.GL || x == Lb.WJ || x == Lb.CL || x == Lb.QU || x == Lb.CP || x == Lb.EX ||
           x == Lb.IS || x == Lb.SY || x == Lb.BK || x == Lb.CR || x == Lb.LF || x == Lb.NL || x == Lb.ZW then return NoBreak
      if a == Lb.SP && b == Lb.IS && at(j + 1) == Lb.NU then return Allowed        // LB15c
      if b == Lb.IS then return NoBreak                                            // LB15d
      if pre >= 0 && (cls(pre) == Lb.CL || cls(pre) == Lb.CP) && b == Lb.NS then return NoBreak // LB16
      if pre >= 0 && cls(pre) == Lb.B2 && b == Lb.B2 then return NoBreak           // LB17
      if a == Lb.SP then return Allowed                                            // LB18
      if b == Lb.QU && Gc.table(rep(j)) != Gc.Pi then return NoBreak               // LB19
      if a == Lb.QU && Gc.table(rep(ia)) != Gc.Pf then return NoBreak
      if b == Lb.QU && !ea(ia) then return NoBreak                                 // LB19a
      if b == Lb.QU && (j + 1 >= m || !ea(j + 1)) then return NoBreak
      if a == Lb.QU && !ea(j) then return NoBreak
      if a == Lb.QU && (ia == 0 || !ea(ia - 1)) then return NoBreak
      if a == Lb.CB || b == Lb.CB then return Allowed                              // LB20
      if (a == Lb.HY || a == Lb.HH) && (b == Lb.AL || b == Lb.HL) then               // LB20a
        val x = at(ia - 1)
        if x == -1 || x == Lb.BK || x == Lb.CR || x == Lb.LF || x == Lb.NL || x == Lb.SP || x == Lb.ZW ||
           x == Lb.CB || x == Lb.GL then return NoBreak
      if b == Lb.BA || b == Lb.HH || b == Lb.HY || b == Lb.NS then return NoBreak  // LB21
      if a == Lb.BB then return NoBreak
      if (a == Lb.HY || a == Lb.HH) && at(ia - 1) == Lb.HL && b != Lb.HL then return NoBreak // LB21a
      if a == Lb.SY && b == Lb.HL then return NoBreak                              // LB21b
      if b == Lb.IN then return NoBreak                                            // LB22
      if (a == Lb.AL || a == Lb.HL) && b == Lb.NU then return NoBreak              // LB23
      if a == Lb.NU && (b == Lb.AL || b == Lb.HL) then return NoBreak
      if a == Lb.PR && (b == Lb.ID || b == Lb.EB || b == Lb.EM) then return NoBreak // LB23a
      if (a == Lb.ID || a == Lb.EB || a == Lb.EM) && b == Lb.PO then return NoBreak
      if (a == Lb.PR || a == Lb.PO) && (b == Lb.AL || b == Lb.HL) then return NoBreak // LB24
      if (a == Lb.AL || a == Lb.HL) && (b == Lb.PR || b == Lb.PO) then return NoBreak
      // LB25
      if (a == Lb.CL || a == Lb.CP) && (b == Lb.PO || b == Lb.PR) && numCtx(ia - 1) then return NoBreak
      if (a == Lb.NU || a == Lb.SY || a == Lb.IS) && (b == Lb.PO || b == Lb.PR || b == Lb.NU) && numCtx(ia) then return NoBreak
      if (a == Lb.PO || a == Lb.PR) && b == Lb.OP then
        val x = at(j + 1)
        if x == Lb.NU || (x == Lb.IS && at(j + 2) == Lb.NU) then return NoBreak
      if (a == Lb.PO || a == Lb.PR) && b == Lb.NU then return NoBreak
      if (a == Lb.HY || a == Lb.IS) && b == Lb.NU then return NoBreak
      // LB26 / LB27
      if a == Lb.JL && (b == Lb.JL || b == Lb.JV || b == Lb.H2 || b == Lb.H3) then return NoBreak
      if (a == Lb.JV || a == Lb.H2) && (b == Lb.JV || b == Lb.JT) then return NoBreak
      if (a == Lb.JT || a == Lb.H3) && b == Lb.JT then return NoBreak
      if korean(a) && b == Lb.PO then return NoBreak
      if a == Lb.PR && korean(b) then return NoBreak
      if (a == Lb.AL || a == Lb.HL) && (b == Lb.AL || b == Lb.HL) then return NoBreak // LB28
      // LB28a
      if a == Lb.AP && (b == Lb.AK || b == Lb.AS || isDotted(j)) then return NoBreak
      if isAkLike(ia) && (b == Lb.VF || b == Lb.VI) then return NoBreak
      if a == Lb.VI && isAkLike(ia - 1) && isAkDot(j) then return NoBreak
      if isAkLike(ia) && isAkLike(j) && at(j + 1) == Lb.VF then return NoBreak
      if a == Lb.IS && (b == Lb.AL || b == Lb.HL) then return NoBreak             // LB29
      if (a == Lb.AL || a == Lb.HL || a == Lb.NU) && b == Lb.OP && !ea(j) then return NoBreak // LB30
      if a == Lb.CP && !ea(ia) && (b == Lb.AL || b == Lb.HL || b == Lb.NU) then return NoBreak
      if a == Lb.RI && b == Lb.RI then                                             // LB30a
        var cnt = 0; var k = ia
        while k >= 0 && cls(k) == Lb.RI do { cnt += 1; k -= 1 }
        if cnt % 2 == 1 then return NoBreak
      if b == Lb.EM && (a == Lb.EB || (ExtPict.table(rep(ia)) == ExtPict.Extended_Pictographic && cn(ia))) then return NoBreak // LB30b
      Allowed                                                                      // LB31

    var j = 1
    while j < m do
      out(first(j)) = decide(j)
      j += 1
    // LB3: break at the end of text
    val lastCls = cls(m - 1)
    out(n) = if lastCls == Lb.BK || lastCls == Lb.CR || lastCls == Lb.LF || lastCls == Lb.NL then Mandatory else Allowed
    out

  /** UTF-16 offsets (and whether the break is mandatory) of every break opportunity in `s`, ending at `s.length`. */
  def offsets(s: String): Array[Int] =
    val cp = CodePoints(s)
    val o = opportunities(cp)
    val b = Array.newBuilder[Int]
    var i = 1
    while i < o.length do
      if o(i) != NoBreak then b += cp.offset(i)
      i += 1
    b.result()

  def mandatory(s: String): Array[Boolean] =
    val cp = CodePoints(s)
    val o = opportunities(cp)
    val b = Array.newBuilder[Boolean]
    var i = 1
    while i < o.length do
      if o(i) != NoBreak then b += (o(i) == Mandatory)
      i += 1
    b.result()
