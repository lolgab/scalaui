package scalaui.unicode

/** The Unicode Bidirectional Algorithm (UAX #9): explicit levels, isolating run sequences, weak/neutral/implicit
  * resolution including paired brackets (N0), line-level whitespace reset (L1) and reordering (L2).
  *
  * Operates on one paragraph of code points. `B` inside the input resets the embedding state, as in the reference
  * implementation; callers normally split paragraphs themselves.
  */
object Bidi:
  inline val Ltr = 0
  inline val Rtl = 1
  inline val Auto = -1
  private inline val MaxDepth = 125

  /** Resolved paragraph: `levels(i)` per code point (explicit formatting characters get the neighbouring level),
    * `paragraphLevel`, and `removed(i)` for characters X9 removes (embedding/override/PDF/BN). */
  final class Result(val paragraphLevel: Int, val levels: Array[Int], val removed: Array[Boolean], types: Array[Int]):
    private val origTypes = types

    /** Levels after L1 for the line `[start, end)` (indices into the paragraph). */
    def lineLevels(start: Int, end: Int): Array[Int] =
      val out = java.util.Arrays.copyOfRange(levels, start, end)
      def ws(t: Int) = t == BidiClass.LRE || t == BidiClass.RLE || t == BidiClass.LRO || t == BidiClass.RLO ||
        t == BidiClass.PDF || t == BidiClass.LRI || t == BidiClass.RLI || t == BidiClass.FSI || t == BidiClass.PDI ||
        t == BidiClass.BN || t == BidiClass.WS
      var i = 0
      while i < out.length do
        val t = origTypes(start + i)
        if t == BidiClass.B || t == BidiClass.S then
          out(i) = paragraphLevel
          var j = i - 1
          while j >= 0 && ws(origTypes(start + j)) do { out(j) = paragraphLevel; j -= 1 }
        i += 1
      var j = out.length - 1
      while j >= 0 && ws(origTypes(start + j)) do { out(j) = paragraphLevel; j -= 1 }
      out

    /** Visual order for a line: `order(v)` is the paragraph index shown at visual position `v`. */
    def visualOrder(start: Int, end: Int): Array[Int] = reorder(lineLevels(start, end), start)

  /** L2: reverses runs from the highest level down to the lowest odd level. `levels` are relative to `offset`. */
  def reorder(levels: Array[Int], offset: Int = 0): Array[Int] =
    val n = levels.length
    val order = Array.tabulate(n)(_ + offset)
    if n == 0 then return order
    var hi = 0; var lowOdd = Int.MaxValue
    levels.foreach { l => if l > hi then hi = l; if (l & 1) == 1 && l < lowOdd then lowOdd = l }
    var lvl = hi
    while lvl >= lowOdd && lvl > 0 do
      var i = 0
      while i < n do
        if levels(i) >= lvl then
          var j = i
          while j < n && levels(j) >= lvl do j += 1
          var a = i; var b = j - 1
          while a < b do { val t = order(a); order(a) = order(b); order(b) = t; a += 1; b -= 1 }
          i = j
        else i += 1
      lvl -= 1
    order

  def resolve(cps: Array[Int], direction: Int = Auto): Result =
    val n = cps.length
    val initial = Array.tabulate(n)(i => BidiClass.table(cps(i)))
    val types = initial.clone()
    val levels = new Array[Int](n)
    // --- BD9 matching isolates
    val matchPdi = Array.fill(n)(n)
    val matchInit = Array.fill(n)(-1)
    def isInit(t: Int) = t == BidiClass.LRI || t == BidiClass.RLI || t == BidiClass.FSI
    locally {
      val st = new Array[Int](n); var sp = 0
      var i = 0
      while i < n do
        if isInit(initial(i)) then { st(sp) = i; sp += 1 }
        else if initial(i) == BidiClass.PDI && sp > 0 then
          sp -= 1; matchPdi(st(sp)) = i; matchInit(i) = st(sp)
        i += 1
    }
    def firstStrong(from: Int, to: Int): Int = // 0 L, 1 R, -1 none (skips isolates)
      var i = from
      while i < to do
        val t = initial(i)
        if t == BidiClass.L then return 0
        if t == BidiClass.R || t == BidiClass.AL then return 1
        if isInit(t) then i = matchPdi(i)
        i += 1
      -1
    val paraLevel = if direction == Auto then (if firstStrong(0, n) == 1 then 1 else 0) else direction

    // --- X1-X8
    val stLevel = new Array[Int](MaxDepth + 2); val stOverride = new Array[Int](MaxDepth + 2); val stIso = new Array[Boolean](MaxDepth + 2)
    var sp = 0
    def push(l: Int, o: Int, iso: Boolean): Unit = { stLevel(sp) = l; stOverride(sp) = o; stIso(sp) = iso; sp += 1 }
    push(paraLevel, BidiClass.ON, false)
    var overflowIso = 0; var overflowEmb = 0; var validIso = 0
    var i = 0
    while i < n do
      val t = initial(i)
      def top = sp - 1
      t match
        case BidiClass.RLI | BidiClass.LRI | BidiClass.FSI =>
          val rtl = t == BidiClass.RLI || (t == BidiClass.FSI && firstStrong(i + 1, matchPdi(i)) == 1)
          levels(i) = stLevel(top)
          if stOverride(top) != BidiClass.ON then types(i) = stOverride(top)
          val nl = if rtl then (stLevel(top) + 1) | 1 else (stLevel(top) + 2) & ~1
          if nl <= MaxDepth && overflowIso == 0 && overflowEmb == 0 then { validIso += 1; push(nl, BidiClass.ON, true) }
          else overflowIso += 1
        case BidiClass.RLE | BidiClass.LRE | BidiClass.RLO | BidiClass.LRO =>
          val rtl = t == BidiClass.RLE || t == BidiClass.RLO
          val nl = if rtl then (stLevel(top) + 1) | 1 else (stLevel(top) + 2) & ~1
          levels(i) = stLevel(top)
          if nl <= MaxDepth && overflowIso == 0 && overflowEmb == 0 then
            val ov = if t == BidiClass.RLO then BidiClass.R else if t == BidiClass.LRO then BidiClass.L else BidiClass.ON
            push(nl, ov, false); levels(i) = nl
          else if overflowIso == 0 then overflowEmb += 1
        case BidiClass.PDI =>
          if overflowIso > 0 then overflowIso -= 1
          else if validIso > 0 then
            overflowEmb = 0
            while !stIso(top) do sp -= 1
            sp -= 1; validIso -= 1
          levels(i) = stLevel(sp - 1)
          if stOverride(sp - 1) != BidiClass.ON then types(i) = stOverride(sp - 1)
        case BidiClass.PDF =>
          levels(i) = stLevel(top)
          if overflowIso > 0 then ()
          else if overflowEmb > 0 then overflowEmb -= 1
          else if !stIso(top) && sp >= 2 then sp -= 1
        case BidiClass.B =>
          sp = 1; overflowIso = 0; overflowEmb = 0; validIso = 0
          levels(i) = paraLevel
        case _ =>
          levels(i) = stLevel(top)
          if stOverride(top) != BidiClass.ON then types(i) = stOverride(top)
      i += 1

    val explicit = levels.clone() // sos/eos of every sequence use explicit levels, not ones resolved by earlier sequences

    // --- X9: removed characters
    def removedType(t: Int) = t == BidiClass.RLE || t == BidiClass.LRE || t == BidiClass.RLO || t == BidiClass.LRO ||
      t == BidiClass.PDF || t == BidiClass.BN
    val removed = Array.tabulate(n)(k => removedType(initial(k)))

    // --- X10: level runs and isolating run sequences
    val runs = Array.newBuilder[Array[Int]]
    locally {
      var k = 0
      while k < n do
        if removed(k) then k += 1
        else
          val lvl = levels(k); val b = Array.newBuilder[Int]
          while k < n && (removed(k) || levels(k) == lvl) do { if !removed(k) then b += k; k += 1 }
          runs += b.result()
    }
    val runArr = runs.result()
    val runOf = new Array[Int](n)
    runArr.zipWithIndex.foreach((r, ri) => r.foreach(c => runOf(c) = ri))
    def typeForLevel(l: Int) = if (l & 1) == 0 then BidiClass.L else BidiClass.R
    def resolveSequence(idx: Array[Int]): Unit =
      val m = idx.length
      val level = explicit(idx(0))
      val ty = Array.tabulate(m)(k => types(idx(k)))
      var prev = idx(0) - 1
      while prev >= 0 && removed(prev) do prev -= 1
      val prevLevel = if prev >= 0 then explicit(prev) else paraLevel
      val sos = typeForLevel(math.max(prevLevel, level))
      val lastIdx = idx(m - 1)
      val succLevel =
        if isInit(initial(lastIdx)) then paraLevel
        else
          var lim = lastIdx + 1
          while lim < n && removed(lim) do lim += 1
          if lim < n then explicit(lim) else paraLevel
      val eos = typeForLevel(math.max(succLevel, level))
      val embDir = typeForLevel(level)
      def isNeutralOrIso(t: Int) = t == BidiClass.B || t == BidiClass.S || t == BidiClass.WS || t == BidiClass.ON ||
        isInit(t) || t == BidiClass.PDI
      // W1
      var pt = sos
      var k = 0
      while k < m do
        if ty(k) == BidiClass.NSM then ty(k) = if k > 0 && (isInit(ty(k - 1)) || ty(k - 1) == BidiClass.PDI) then BidiClass.ON else pt
        pt = ty(k); k += 1
      // W2, W3 (W2 sees AL before W3 turns it to R)
      var strong = sos
      k = 0
      while k < m do
        val t = ty(k)
        if t == BidiClass.L || t == BidiClass.R || t == BidiClass.AL then strong = t
        else if t == BidiClass.EN && strong == BidiClass.AL then ty(k) = BidiClass.AN
        k += 1
      k = 0
      while k < m do { if ty(k) == BidiClass.AL then ty(k) = BidiClass.R; k += 1 }
      // W4
      k = 1
      while k < m - 1 do
        val t = ty(k)
        if t == BidiClass.ES && ty(k - 1) == BidiClass.EN && ty(k + 1) == BidiClass.EN then ty(k) = BidiClass.EN
        else if t == BidiClass.CS && ty(k - 1) == BidiClass.EN && ty(k + 1) == BidiClass.EN then ty(k) = BidiClass.EN
        else if t == BidiClass.CS && ty(k - 1) == BidiClass.AN && ty(k + 1) == BidiClass.AN then ty(k) = BidiClass.AN
        k += 1
      // W5
      k = 0
      while k < m do
        if ty(k) == BidiClass.ET then
          val s = k; var e = k
          while e < m && ty(e) == BidiClass.ET do e += 1
          val before = if s == 0 then sos else ty(s - 1)
          val after = if e == m then eos else ty(e)
          if before == BidiClass.EN || after == BidiClass.EN then
            var q = s; while q < e do { ty(q) = BidiClass.EN; q += 1 }
          k = e
        else k += 1
      // W6
      k = 0
      while k < m do
        val t = ty(k)
        if t == BidiClass.ES || t == BidiClass.ET || t == BidiClass.CS then ty(k) = BidiClass.ON
        k += 1
      // W7
      strong = sos
      k = 0
      while k < m do
        val t = ty(k)
        if t == BidiClass.L || t == BidiClass.R then strong = t
        else if t == BidiClass.EN && strong == BidiClass.L then ty(k) = BidiClass.L
        k += 1
      // N0: paired brackets
      locally {
        val pairs = scala.collection.mutable.ArrayBuffer.empty[(Int, Int)]
        val stackCp = new Array[Int](63); val stackPos = new Array[Int](63); var bsp = 0
        def canon(cp: Int) = cp match { case 0x2329 => 0x3008; case 0x232A => 0x3009; case x => x }
        var overflow = false
        k = 0
        while k < m && !overflow do
          val cp = cps(idx(k))
          if ty(k) == BidiClass.ON then
            BracketType.table(cp) match
              case BracketType.o =>
                if bsp == 63 then overflow = true
                else { stackCp(bsp) = canon(BidiData.bracketPair.getOrElse(cp, cp)); stackPos(bsp) = k; bsp += 1 }
              case BracketType.c =>
                var s = bsp - 1
                while s >= 0 && stackCp(s) != canon(cp) do s -= 1
                if s >= 0 then { pairs += ((stackPos(s), k)); bsp = s }
              case _ => ()
          k += 1
        val sorted = pairs.sortBy(_._1)
        def strongOf(t: Int) = if t == BidiClass.L then BidiClass.L else if t == BidiClass.R || t == BidiClass.EN || t == BidiClass.AN then BidiClass.R else -1
        for (o, c) <- sorted do
          var foundE = false; var foundOpp = false
          var q = o + 1
          while q < c do
            val s = strongOf(ty(q))
            if s == embDir then foundE = true else if s != -1 then foundOpp = true
            q += 1
          val newT =
            if foundE then embDir
            else if foundOpp then
              var ctx = sos
              var b = o - 1
              var done = false
              while b >= 0 && !done do
                val s = strongOf(ty(b)); if s != -1 then { ctx = s; done = true }
                b -= 1
              if ctx != embDir then ctx else embDir
            else -1
          if newT != -1 then
            ty(o) = newT; ty(c) = newT
            // NSM following a changed bracket take its type
            for br <- Seq(o, c) do
              var q2 = br + 1
              while q2 < m && initial(idx(q2)) == BidiClass.NSM do { ty(q2) = newT; q2 += 1 }
      }
      // N1, N2
      k = 0
      while k < m do
        if isNeutralOrIso(ty(k)) then
          val s = k; var e = k
          while e < m && isNeutralOrIso(ty(e)) do e += 1
          def dir(t: Int) = if t == BidiClass.L then BidiClass.L else BidiClass.R  // R, EN, AN count as R
          val lead = if s == 0 then sos else dir(ty(s - 1))
          val trail = if e == m then eos else dir(ty(e))
          val res = if lead == trail then lead else embDir
          var q = s; while q < e do { ty(q) = res; q += 1 }
          k = e
        else k += 1
      // I1, I2
      k = 0
      while k < m do
        val t = ty(k)
        var l = level
        if (level & 1) == 0 then
          if t == BidiClass.R then l += 1 else if t == BidiClass.AN || t == BidiClass.EN then l += 2
        else if t == BidiClass.L || t == BidiClass.EN || t == BidiClass.AN then l += 1
        levels(idx(k)) = l
        k += 1

    for ri <- runArr.indices do
      val run0 = runArr(ri)
      val f = run0(0)
      if initial(f) != BidiClass.PDI || matchInit(f) == -1 then
        val seq = Array.newBuilder[Int]
        var cur = ri
        var go = true
        while go do
          val r = runArr(cur); seq ++= r
          val last = r(r.length - 1)
          if isInit(initial(last)) && matchPdi(last) != n then cur = runOf(matchPdi(last)) else go = false
        resolveSequence(seq.result())

    // removed characters take the level of the previous character (or the paragraph level)
    var q = 0
    while q < n do
      if removed(q) then levels(q) = if q == 0 then paraLevel else levels(q - 1)
      q += 1
    new Result(paraLevel, levels, removed, initial)

  /** Mirrored counterpart of `cp` for odd-level (RTL) runs, or `cp` itself. */
  def mirror(cp: Int): Int = BidiData.mirror.getOrElse(cp, cp)
