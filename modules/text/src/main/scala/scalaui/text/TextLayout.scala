package scalaui.text

import scalaui.geom.*
import scalaui.shaping.{FontFeature, Shaper}
import scalaui.unicode.Bidi

enum Align:
  case Left, Center, Right

/** A glyph placed in layout space: `x` is the left of its advance, `y` the line's baseline. `[cluster, end)` is the
  * UTF-16 range it represents; odd `level`s are right-to-left.
  */
final case class GlyphPos(glyph: Int, x: Double, y: Double, cluster: Int, advance: Double, font: scalaui.font.FontFace,
    end: Int = -1, level: Int = 0) extends PlacedGlyph

/** One visual line covering `text[start, end)` (UTF-16 indices; `end` includes the break character). Glyphs are in visual order. */
final case class Line(start: Int, end: Int, glyphs: Vector[GlyphPos], x: Double, top: Double, baseline: Double, height: Double, width: Double) extends LineLike

/** Laid-out text. Coordinates are in pixels relative to the layout origin, y down. */
final class TextLayout(val text: String, val shaper: Shaper, val size: Double, val lines: Vector[Line], val width: Double, val height: Double):

  /** Caret position for a UTF-16 index: `(x, top, height)`. */
  def caret(index: Int): (Double, Double, Double) = Carets.caret(lines, text, index)

  /** Closest UTF-16 index to a point (clamped to the text). */
  def indexAt(x: Double, y: Double): Int = Carets.indexAt(lines, text, x, y)

  /** Caret stop one step visually right (`toRight`) or left of `index` in bidirectional text; -1 means "use logical movement". */
  def moveVisual(index: Int, toRight: Boolean): Int = Carets.moveVisual(lines, text, index, toRight)

  /** Highlight rectangles for the selection `[from, to)`: one per contiguous visual stretch on each line it touches. */
  def selectionRects(from: Int, to: Int): Vector[Rect] = Carets.selectionRects(lines, from, to)

object TextLayout:
  /** Lays out `text`. `direction` is the paragraph direction (`Bidi.Auto` picks it from the first strong character);
    * `features` switch OpenType features on/off (e.g. `FontFeature("liga", 0)`) and `language` selects a language system (`Some("TRK ")`). */
  def layout(text: String, shaper: Shaper, size: Double, maxWidth: Option[Double] = None, align: Align = Align.Left,
      lineHeightScale: Double = 1.0, direction: Int = Bidi.Auto, features: Seq[FontFeature] = Nil,
      language: Option[String] = None, tracking: Double = 0.0): TextLayout =
    // Layouts are immutable and fully determined by these arguments (a Shaper is compared by identity), so repeated requests,
    // e.g. the same label every frame of an immediate-mode UI, are served from a small bounded cache. Long texts bypass it.
    if text.length > CacheMaxChars then compute(text, shaper, size, maxWidth, align, lineHeightScale, direction, features, language, tracking)
    else
      val key = LayoutKey(text, shaper, size, maxWidth, align, lineHeightScale, direction, features, language, tracking)
      cache.synchronized(cache.get(key)) match
        case Some(l) => l
        case None =>
          val l = compute(text, shaper, size, maxWidth, align, lineHeightScale, direction, features, language, tracking)
          cache.synchronized {
            if cache.size >= CacheMaxEntries then cache.clear()
            cache.update(key, l)
          }
          l

  private final case class LayoutKey(text: String, shaper: Shaper, size: Double, maxWidth: Option[Double], align: Align, lineHeightScale: Double,
      direction: Int, features: Seq[FontFeature], language: Option[String], tracking: Double)
  private val CacheMaxChars = 2000
  private val CacheMaxEntries = 1024
  private val cache = scala.collection.mutable.HashMap.empty[LayoutKey, TextLayout]

  private def compute(text: String, shaper: Shaper, size: Double, maxWidth: Option[Double], align: Align, lineHeightScale: Double,
      direction: Int, features: Seq[FontFeature], language: Option[String], tracking: Double): TextLayout =
    val font = shaper.font
    val s = size / font.unitsPerEm
    val ascent = font.ascent * s; val descent = -font.descent * s
    val lineH = (ascent + descent + font.lineGap * s) * lineHeightScale
    val limit = maxWidth.getOrElse(Double.PositiveInfinity)

    val flow = Flow.flow(text, Vector(Run(0, text.length, shaper, size, tracking, features, language)), limit, direction)
    val contentWidth = maxWidth.getOrElse(flow.map(_.width).maxOption.getOrElse(0.0))
    val out = flow.zipWithIndex.map { case (fl, i) =>
      val x0 = align match
        case Align.Left => 0.0
        case Align.Center => (contentWidth - fl.width) / 2
        case Align.Right => contentWidth - fl.width
      val top = i * lineH
      val baseline = top + (lineH - (ascent + descent)) / 2 + ascent
      var x = x0 + fl.lead
      val glyphs = fl.pieces.map { p =>
        val g = GlyphPos(p.glyph, x + p.xOff, baseline - p.yOff, p.cluster, p.adv, p.font, p.end, p.level)
        x += p.adv; g
      }
      Line(fl.start, fl.end, glyphs, x0, top, baseline, lineH, fl.width)
    }
    new TextLayout(text, shaper, size, out, contentWidth, flow.size * lineH)

  /** Lays out `text` as vertical text (columns right to left, glyphs top to bottom, upright CJK and sideways Latin per UAX #50);
    * see [[VerticalLayout.layout]]. */
  def layoutVertical(text: String, shaper: Shaper, size: Double, maxHeight: Option[Double] = None, align: Align = Align.Left,
      lineHeightScale: Double = 1.0, features: Seq[FontFeature] = Nil, language: Option[String] = None,
      tracking: Double = 0.0): VerticalLayout =
    VerticalLayout.layout(text, shaper, size, maxHeight, align, lineHeightScale, features, language, tracking)
