package scalaui.svg

private[svg] object Values:
  private val numRe = "[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?".r

  /** All numbers in a whitespace/comma separated list; stops at the first token that is not a number. */
  def numbers(s: String): Array[Double] =
    val out = Array.newBuilder[Double]; var i = 0; val n = s.length
    def sep(): Unit = while i < n && (s.charAt(i).isWhitespace || s.charAt(i) == ',') do i += 1
    sep()
    var go = true
    while go && i < n do
      numRe.findPrefixMatchOf(s.substring(i)) match
        case Some(m) => out += m.matched.toDouble; i += m.end; sep()
        case None => go = false
    out.result()

  final case class Len(v: Double, unit: String)

  def parseLen(s0: String): Option[Len] =
    val s = s0.trim
    numRe.findPrefixMatchOf(s) match
      case Some(m) =>
        val u = s.substring(m.end).trim
        if u.isEmpty || u == "%" || Set("px", "pt", "pc", "mm", "cm", "in", "em", "ex").contains(u) then Some(Len(m.matched.toDouble, u)) else None
      case None => None

  def parseNumber(s: String): Option[Double] =
    val t = s.trim
    numRe.findPrefixMatchOf(t).filter(_.end == t.length).map(_.matched.toDouble)

  /** Number or percentage (`50%` -> 0.5), as used by offsets and opacities. */
  def parseFraction(s: String): Option[Double] =
    val t = s.trim
    if t.endsWith("%") then parseNumber(t.dropRight(1)).map(_ / 100) else parseNumber(t)

  /** `url(#id)` possibly followed by a fallback; returns the id and the rest of the string. */
  def urlRef(s0: String): Option[(String, String)] =
    val s = s0.trim
    if !s.startsWith("url(") then None
    else
      val e = s.indexOf(')')
      if e < 0 then None
      else
        val inner = s.substring(4, e).trim.stripPrefix("\"").stripSuffix("\"").stripPrefix("'").stripSuffix("'").trim
        if inner.startsWith("#") then Some((inner.drop(1), s.substring(e + 1).trim)) else None

  def clamp01(d: Double): Double = if d < 0 then 0 else if d > 1 then 1 else d
