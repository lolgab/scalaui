package scalaui.unicode

/** Script lookup and itemization (runs of one script, Common/Inherited characters joining the surrounding run). */
object Scripts:
  def of(cp: Int): String = Script.names(Script.table(cp))

  /** ISO 15924 code (`Latn`, `Arab`, ...) of a script name from [[of]]; `Zyyy` for unknown names. */
  def isoCode(name: String): String = ScriptCodes.codes.getOrElse(name, "Zyyy")

  /** A run `[start, end)` of code point indices in one script (`Common` if the text has no specific script). */
  final case class Run(start: Int, end: Int, script: String)

  def itemize(cps: Array[Int]): Array[Run] =
    val n = cps.length
    if n == 0 then return Array.empty
    val sc = Array.tabulate(n)(i => Script.table(cps(i)))
    def neutral(s: Int) = s == Script.Common || s == Script.Inherited || s == Script.Unknown
    // resolve neutral characters: inherit the previous script, leading ones take the next specific script
    val res = sc.clone()
    var cur = -1
    var i = 0
    while i < n do
      if neutral(sc(i)) then { if cur >= 0 then res(i) = cur } else cur = sc(i)
      i += 1
    var nxt = -1
    i = n - 1
    while i >= 0 do
      if neutral(sc(i)) then { if res(i) == sc(i) && nxt >= 0 then res(i) = nxt } else nxt = sc(i)
      i -= 1
    val runs = Array.newBuilder[Run]
    var s = 0
    i = 1
    while i <= n do
      if i == n || res(i) != res(s) then
        runs += Run(s, i, Script.names(if res(s) == Script.Unknown then Script.Common else res(s)))
        s = i
      i += 1
    runs.result()
