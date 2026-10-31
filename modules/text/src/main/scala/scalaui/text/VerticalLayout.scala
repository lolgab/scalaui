package scalaui.text

import scalaui.font.FontFace
import scalaui.geom.*
import scalaui.shaping.{FontFeature, Shaper}
import scalaui.unicode.Bidi

/** A glyph in vertical text. `(originX, originY)` is where the glyph's own origin (baseline start) goes, y down; `sideways`
  * glyphs (Latin and other rotated scripts, UAX #50) are drawn rotated 90 degrees clockwise about that origin, so their
  * outline's "up" points right. `pen` is the top of the glyph's advance along the column and `advance` its height; `[cluster, end)` is the
  * UTF-16 range it represents, and odd `level`s are right-to-left runs.
  */
final case class VGlyph(glyph: Int, originX: Double, originY: Double, cluster: Int, pen: Double, advance: Double, font: FontFace,
    end: Int = -1, level: Int = 0, sideways: Boolean = false) extends PlacedGlyph:
  def x: Double = pen

/** One column of vertical text covering `text[start, end)`: `left` is its left edge, `breadth` its width and `top`/`length`
  * the stretch it occupies along the vertical axis. Glyphs are in visual order, top to bottom.
  */
final case class VColumn(start: Int, end: Int, glyphs: Vector[VGlyph], left: Double, breadth: Double, top: Double, length: Double) extends LineLike:
  def x: Double = top
  def width: Double = length
  def height: Double = breadth
  /** Horizontal centre line of the column, the axis upright glyphs are centred on. */
  def centerX: Double = left + breadth / 2

/** Laid-out vertical text: columns run right to left, glyphs top to bottom; upright CJK and sideways Latin per UAX #50.
  * Coordinates are in pixels relative to the layout origin (top-left), y down.
  */
final class VerticalLayout(val text: String, val shaper: Shaper, val size: Double, val columns: Vector[VColumn], val width: Double, val height: Double):

  /** Caret for a UTF-16 index as a horizontal line across its column: `(x, y, length)`. */
  def caret(index: Int): (Double, Double, Double) =
    val idx = index.max(0).min(text.length)
    val col = columns.findLast(_.start <= idx).getOrElse(columns.head)
    (col.left, Carets.caretX(col, text, idx), col.breadth)

  /** Closest UTF-16 index to a point (clamped to the text). */
  def indexAt(x: Double, y: Double): Int =
    val col = columns.find(c => x >= c.left && x < c.left + c.breadth)
      .getOrElse(if x >= columns.head.left then columns.head else columns.minBy(c => math.abs(c.centerX - x)))
    Carets.indexInLine(col, text, y)

  /** Highlight rectangles for the selection `[from, to)`: one per contiguous stretch on each column it touches. */
  def selectionRects(from: Int, to: Int): Vector[Rect] =
    val a = from.min(to); val b = from.max(to)
    if a == b then return Vector.empty
    columns.flatMap { c =>
      val spans = Vector.newBuilder[(Double, Double)]
      c.glyphs.foreach { g =>
        val lo = a.max(g.cluster); val hi = b.min(g.end)
        if lo < hi && g.end > g.cluster then
          val len = (g.end - g.cluster).toDouble
          val f0 = (lo - g.cluster) / len; val f1 = (hi - g.cluster) / len
          if (g.level & 1) == 1 then spans += ((g.pen + g.advance * (1 - f1), g.pen + g.advance * (1 - f0)))
          else spans += ((g.pen + g.advance * f0, g.pen + g.advance * f1))
      }
      val merged = spans.result().sortBy(_._1).foldLeft(Vector.empty[(Double, Double)]) { (acc, s) =>
        if acc.nonEmpty && s._1 <= acc.last._2 + 0.01 then acc.init :+ ((acc.last._1, math.max(acc.last._2, s._2))) else acc :+ s
      }
      merged.map((y0, y1) => Rect(c.left, y0, c.left + c.breadth, y1))
    }

object VerticalLayout:
  /** Lays out `text` as vertical text (UAX #50, `vertical-rl`): columns flow right to left, glyphs top to bottom, wrapping at
    * `maxHeight`. CJK and other upright characters are shaped with vertical metrics (`vert`/`vrt2`/`vkna`, vmtx, VORG);
    * Latin and other rotated characters are shaped horizontally and rotated 90 degrees clockwise. `align` places each column's
    * content along the vertical axis (`Left` = top, `Right` = bottom). `lineHeightScale` scales the column width.
    */
  def layout(text: String, shaper: Shaper, size: Double, maxHeight: Option[Double] = None, align: Align = Align.Left,
      lineHeightScale: Double = 1.0, features: Seq[FontFeature] = Nil, language: Option[String] = None,
      tracking: Double = 0.0): VerticalLayout =
    val font = shaper.font
    val s = size / font.unitsPerEm
    val ascent = font.ascent * s; val descent = -font.descent * s
    val colW = (ascent + descent + font.lineGap * s) * lineHeightScale
    val limit = maxHeight.getOrElse(Double.PositiveInfinity)
    val flow = Flow.flow(text, Vector(Run(0, text.length, shaper, size, tracking, features, language)), limit, Bidi.Ltr, vertical = true)
    val contentHeight = maxHeight.getOrElse(flow.map(_.width).maxOption.getOrElse(0.0))
    val totalWidth = flow.size * colW
    val cols = flow.zipWithIndex.map { (fl, i) =>
      val left = totalWidth - (i + 1) * colW
      val cx = left + colW / 2
      val y0 = align match
        case Align.Left => 0.0
        case Align.Center => (contentHeight - fl.width) / 2
        case Align.Right => contentHeight - fl.width
      var pen = y0 + fl.lead
      val glyphs = fl.pieces.map { p =>
        val g = VGlyph(p.glyph, cx + p.xOff, pen + p.yOff, p.cluster, pen, p.adv, p.font, p.end, p.level, p.sideways)
        pen += p.adv; g
      }
      VColumn(fl.start, fl.end, glyphs, left, colW, y0, fl.width)
    }
    new VerticalLayout(text, shaper, size, cols, totalWidth, contentHeight)
