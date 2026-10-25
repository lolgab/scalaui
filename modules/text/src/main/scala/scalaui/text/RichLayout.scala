package scalaui.text

import scalaui.color.Color
import scalaui.font.FontFace
import scalaui.geom.*
import scalaui.shaping.{FontFeature, Shaper}
import scalaui.unicode.Bidi

/** Per-run character style. `shaper` carries the font (and its fallbacks); `size` is in pixels. */
final case class TextStyle(shaper: Shaper, size: Double, color: Color = Color.Black, underline: Boolean = false,
    strikethrough: Boolean = false, background: Option[Color] = None, letterSpacing: Double = 0.0, baselineShift: Double = 0.0,
    features: Seq[FontFeature] = Nil, language: Option[String] = None)

final case class Span(text: String, style: TextStyle)

final case class RichGlyph(glyph: Int, font: FontFace, x: Double, baseline: Double, advance: Double, cluster: Int, style: TextStyle,
    end: Int = -1, level: Int = 0) extends PlacedGlyph

/** A visual line covering `text[start, end)` of the concatenated spans. Glyphs are in visual order. */
final case class RichLine(start: Int, end: Int, glyphs: Vector[RichGlyph], x: Double, top: Double, baseline: Double, height: Double, width: Double) extends LineLike

/** Laid-out multi-style text. Indices refer to the concatenation of all span texts. */
final class RichLayout(val text: String, val lines: Vector[RichLine], val width: Double, val height: Double):
  def caret(index: Int): (Double, Double, Double) = Carets.caret(lines, text, index)
  def indexAt(x: Double, y: Double): Int = Carets.indexAt(lines, text, x, y)

  /** Caret stop one step visually right (`toRight`) or left of `index` in bidirectional text; -1 means "use logical movement". */
  def moveVisual(index: Int, toRight: Boolean): Int = Carets.moveVisual(lines, text, index, toRight)
  def selectionRects(from: Int, to: Int): Vector[Rect] = Carets.selectionRects(lines, from, to)

object RichLayout:
  def layout(spans: Seq[Span], maxWidth: Option[Double] = None, align: Align = Align.Left, lineSpacing: Double = 1.0,
      direction: Int = Bidi.Auto): RichLayout =
    val text = spans.map(_.text).mkString
    val runs = Vector.newBuilder[Run]
    var off = 0
    spans.foreach { sp =>
      if sp.text.nonEmpty then runs += Run(off, off + sp.text.length, sp.style.shaper, sp.style.size, sp.style.letterSpacing, sp.style.features, sp.style.language)
      off += sp.text.length
    }
    val runVec = runs.result()
    // runs skip empty spans, so map each run back to its span's style
    val styleOfRun = spans.filter(_.text.nonEmpty).map(_.style).toVector
    val limit = maxWidth.getOrElse(Double.PositiveInfinity)
    val flow = if runVec.isEmpty then Vector(FlowLine(0, 0, Vector.empty, 0.0, 0.0)) else Flow.flow(text, runVec, limit, direction)

    val fallback = spans.headOption.map(_.style)
    val contentWidth = maxWidth.getOrElse(flow.map(_.width).maxOption.getOrElse(0.0))
    var top = 0.0
    val out = Vector.newBuilder[RichLine]
    flow.foreach { fl =>
      val lineStyles = fl.pieces.map(p => styleOfRun(p.run)).distinct match
        case Vector() => fallback.toVector
        case v => v
      def asc(s: TextStyle) = s.shaper.font.ascent.toDouble * s.size / s.shaper.font.unitsPerEm
      def desc(s: TextStyle) = -s.shaper.font.descent.toDouble * s.size / s.shaper.font.unitsPerEm
      def gap(s: TextStyle) = s.shaper.font.lineGap.toDouble * s.size / s.shaper.font.unitsPerEm
      val a = lineStyles.map(asc).maxOption.getOrElse(0.0); val d = lineStyles.map(desc).maxOption.getOrElse(0.0)
      val g = lineStyles.map(gap).maxOption.getOrElse(0.0)
      val h = (a + d + g) * lineSpacing
      val baseline = top + (h - (a + d)) / 2 + a
      val x0 = align match
        case Align.Left => 0.0
        case Align.Center => (contentWidth - fl.width) / 2
        case Align.Right => contentWidth - fl.width
      var x = x0 + fl.lead
      val glyphs = fl.pieces.map { p =>
        val st = styleOfRun(p.run)
        val r = RichGlyph(p.glyph, p.font, x + p.xOff, baseline - st.baselineShift - p.yOff, p.adv, p.cluster, st, p.end, p.level)
        x += p.adv; r
      }
      out += RichLine(fl.start, fl.end, glyphs, x0, top, baseline, h, fl.width)
      top += h
    }
    new RichLayout(text, out.result(), contentWidth, top)
