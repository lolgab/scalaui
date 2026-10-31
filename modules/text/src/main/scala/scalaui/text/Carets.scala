package scalaui.text

import scalaui.geom.*
import scalaui.unicode.Graphemes

/** A positioned glyph; `[cluster, end)` is the UTF-16 range it covers and odd `level`s run right-to-left. */
trait PlacedGlyph:
  def cluster: Int
  def end: Int
  def x: Double
  def advance: Double
  def level: Int

/** A visual line of placed glyphs (glyphs in visual order). */
trait LineLike:
  def start: Int
  def end: Int
  def glyphs: Vector[PlacedGlyph]
  def x: Double
  def top: Double
  def height: Double
  def width: Double

/** Caret placement, hit testing and selection geometry over bidirectional lines. */
private[text] object Carets:
  private def rtl(g: PlacedGlyph) = (g.level & 1) == 1

  private def edge(g: PlacedGlyph, frac: Double): Double = if rtl(g) then g.x + g.advance * (1 - frac) else g.x + g.advance * frac

  private[text] def caretX(line: LineLike, text: String, idx: Int): Double =
    val gs = line.glyphs
    if gs.isEmpty then return line.x
    gs.find(g => g.cluster <= idx && idx < g.end) match
      case Some(g) =>
        val b = Graphemes.offsets(text.substring(g.cluster, g.end))
        val k = b.indexWhere(_ >= idx - g.cluster).max(0)
        edge(g, if b.length <= 1 then 0.0 else k.toDouble / (b.length - 1))
      case None =>
        val first = gs.minBy(_.cluster); val last = gs.maxBy(_.cluster)
        if idx < first.cluster then edge(first, 0.0) else edge(last, 1.0)

  def caret(lines: Vector[LineLike], text: String, index: Int): (Double, Double, Double) =
    val idx = index.max(0).min(text.length)
    val line = lines.findLast(_.start <= idx).getOrElse(lines.head)
    (caretX(line, text, idx), line.top, line.height)

  /** Index of the caret stop one step to the visual right/left of `index` on its line, or -1 when the line is purely
    * left-to-right (use logical movement) or `index` is already at that visual edge of the line (cross to the neighbouring line). */
  def moveVisual(lines: Vector[LineLike], text: String, index: Int, toRight: Boolean): Int =
    val idx = index.max(0).min(text.length)
    val line = lines.findLast(_.start <= idx).getOrElse(lines.head)
    if !line.glyphs.exists(rtl) then return -1
    val end = if line.end > line.start && text.charAt(line.end - 1) == '\n' then line.end - 1 else line.end
    val stops = Graphemes.offsets(text.substring(line.start, end)).map(_ + line.start).distinct
    val x0 = caretX(line, text, idx)
    val eps = 1e-6
    val ahead = stops.filter(i => if toRight then caretX(line, text, i) > x0 + eps else caretX(line, text, i) < x0 - eps)
    if ahead.isEmpty then -1
    else if toRight then ahead.minBy(caretX(line, text, _)) else ahead.maxBy(caretX(line, text, _))

  def indexAt(lines: Vector[LineLike], text: String, x: Double, y: Double): Int =
    indexInLine(lines.find(l => y < l.top + l.height).getOrElse(lines.last), text, x)

  /** Closest index to coordinate `x` along `line`. */
  private[text] def indexInLine(line: LineLike, text: String, x: Double): Int =
    val gs = line.glyphs
    if gs.isEmpty then return line.start
    gs.find(g => x >= g.x && x < g.x + g.advance) match
      case Some(g) =>
        val f0 = if g.advance <= 0 then 0.0 else (x - g.x) / g.advance
        val f = if rtl(g) then 1 - f0 else f0
        val b = Graphemes.offsets(text.substring(g.cluster, g.end))
        g.cluster + b(math.round(f * (b.length - 1)).toInt.max(0).min(b.length - 1))
      case None =>
        if x < gs.head.x then (if rtl(gs.head) then gs.head.end else gs.head.cluster)
        else (if rtl(gs.last) then gs.last.cluster else gs.last.end)

  /** One rectangle per contiguous visual stretch of selected glyphs on each line the selection touches. */
  def selectionRects(lines: Vector[LineLike], from: Int, to: Int): Vector[Rect] =
    val a = from.min(to); val b = from.max(to)
    if a == b then return Vector.empty
    lines.flatMap { l =>
      val spans = Vector.newBuilder[(Double, Double)]
      l.glyphs.foreach { g =>
        val lo = a.max(g.cluster); val hi = b.min(g.end)
        if lo < hi && g.end > g.cluster then
          val len = (g.end - g.cluster).toDouble
          val f0 = (lo - g.cluster) / len; val f1 = (hi - g.cluster) / len
          if rtl(g) then spans += ((g.x + g.advance * (1 - f1), g.x + g.advance * (1 - f0)))
          else spans += ((g.x + g.advance * f0, g.x + g.advance * f1))
      }
      val merged = spans.result().sortBy(_._1).foldLeft(Vector.empty[(Double, Double)]) { (acc, s) =>
        if acc.nonEmpty && s._1 <= acc.last._2 + 0.01 then acc.init :+ ((acc.last._1, math.max(acc.last._2, s._2))) else acc :+ s
      }
      merged.map((x0, x1) => Rect(x0, l.top, x1, l.top + l.height))
    }
