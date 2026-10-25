package scalaui.shaping

import scala.collection.mutable.ArrayBuffer

/** A tiny regular-expression engine over integer symbols with longest-match semantics, used to express the syllable
  * grammars of the complex-script shapers (HarfBuzz generates these with Ragel). */
private[shaping] sealed trait Re:
  def ~(o: Re): Re = Re.Concat(this, o)
  def |(o: Re): Re = Re.Alt(this, o)
  def ? : Re = Re.Alt(this, Re.Empty)
  def * : Re = Re.Star(this)
  def + : Re = Re.Concat(this, Re.Star(this))

private[shaping] object Re:
  final case class Sym(accepts: Int => Boolean) extends Re
  case object Empty extends Re
  final case class Concat(a: Re, b: Re) extends Re
  final case class Alt(a: Re, b: Re) extends Re
  final case class Star(a: Re) extends Re

  def sym(s: Int*): Re = { val set = s.toSet; Sym(set.contains) }
  val any: Re = Sym(_ => true)

private[shaping] final class Nfa(re: Re):
  // state kinds: 0 symbol, 1 split, 2 match
  private val kind = ArrayBuffer.empty[Int]
  private val pred = ArrayBuffer.empty[Int => Boolean]
  private val out1 = ArrayBuffer.empty[Int]
  private val out2 = ArrayBuffer.empty[Int]

  private def state(k: Int, p: Int => Boolean, a: Int, b: Int): Int =
    kind += k; pred += p; out1 += a; out2 += b
    kind.length - 1

  /** Builds `r` so that every way out of it continues at `next`; returns its entry state. */
  private def build(r: Re, next: Int): Int = r match
    case Re.Sym(p) => state(0, p, next, -1)
    case Re.Empty => next
    case Re.Concat(a, b) => build(a, build(b, next))
    case Re.Alt(a, b) => state(1, null, build(a, next), build(b, next))
    case Re.Star(a) =>
      val loop = state(1, null, -1, next)
      out1(loop) = build(a, loop)
      loop

  private val accept = state(2, null, -1, -1)
  private val start = build(re, accept)

  private def closure(s: Int, set: Array[Boolean], list: ArrayBuffer[Int]): Unit =
    if s >= 0 && !set(s) then
      set(s) = true
      if kind(s) == 1 then { closure(out1(s), set, list); closure(out2(s), set, list) } else list += s

  /** Length of the longest match starting at `from` in `syms[from, to)`, or -1. */
  def longest(syms: Array[Int], from: Int, to: Int): Int =
    var cur = ArrayBuffer.empty[Int]
    var seen = new Array[Boolean](kind.length)
    closure(start, seen, cur)
    var best = if cur.contains(accept) then 0 else -1
    var i = from
    while i < to && cur.nonEmpty do
      val nxt = ArrayBuffer.empty[Int]
      val ns = new Array[Boolean](kind.length)
      cur.foreach(s => if kind(s) == 0 && pred(s)(syms(i)) then closure(out1(s), ns, nxt))
      i += 1
      cur = nxt; seen = ns
      if cur.contains(accept) then best = i - from
    best

private[shaping] object Syllables:
  /** Scans `syms` left to right: at each position the rule with the longest match wins (earlier rules win ties).
    * Returns `(start, end, ruleIndex)` triples covering the whole input; unmatched symbols become one-symbol runs of rule -1. */
  def scan(syms: Array[Int], rules: Vector[Nfa]): Vector[(Int, Int, Int)] =
    val out = Vector.newBuilder[(Int, Int, Int)]
    var p = 0
    while p < syms.length do
      var best = -1; var bestRule = -1
      var r = 0
      while r < rules.length do
        val l = rules(r).longest(syms, p, syms.length)
        if l > best then { best = l; bestRule = r }
        r += 1
      val len = math.max(best, 1)
      out += ((p, p + len, if best < 1 then -1 else bestRule))
      p += len
    out.result()
