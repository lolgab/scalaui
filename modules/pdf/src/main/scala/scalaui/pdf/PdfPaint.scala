package scalaui.pdf

import scalaui.color.Color
import scalaui.geom.*

enum PdfSpread:
  case Pad, Reflect, Repeat

/** A fill or stroke source: a flat colour, a gradient (emitted as PDF shading patterns, stops with alpha get a soft mask) or a tiling pattern. */
sealed trait PdfPaint
object PdfPaint:
  final case class Solid(color: Color) extends PdfPaint
  /** Axial gradient from `from` to `to`; `transform` maps gradient space to user space. */
  final case class LinearGradient(from: Point, to: Point, stops: Seq[(Float, Color)], spread: PdfSpread = PdfSpread.Pad, transform: Affine = Affine.Identity) extends PdfPaint
  /** Two-circle radial gradient (focal circle -> end circle). Reflect/repeat are supported for concentric circles only. */
  final case class RadialGradient(focal: Point, focalRadius: Double, center: Point, radius: Double, stops: Seq[(Float, Color)], spread: PdfSpread = PdfSpread.Pad,
      transform: Affine = Affine.Identity) extends PdfPaint
  final case class Tiling(pattern: PdfTilingPattern, transform: Affine = Affine.Identity) extends PdfPaint

  /** Converts a raster-module paint (solid / linear / radial gradient) for drawing into a PDF. */
  def from(p: scalaui.raster.Paint): PdfPaint = p match
    case scalaui.raster.Paint.Solid(c) => Solid(c)
    case scalaui.raster.Paint.LinearGradient(a, b, stops) => LinearGradient(a, b, stops)
    case scalaui.raster.Paint.RadialGradient(c, r, stops) => RadialGradient(c, 0, c, r, stops)
    case other => throw new IllegalArgumentException(s"unsupported paint $other")

/** A tiling pattern cell recorded with [[PdfDocument.tilingPattern]]. */
final class PdfTilingPattern private[pdf] (private[pdf] val canvas: PdfCanvas, val width: Double, val height: Double, val xStep: Double, val yStep: Double)

/** Blend modes of the PDF transparency model. */
enum PdfBlend(val pdfName: String):
  case Normal extends PdfBlend("Normal")
  case Multiply extends PdfBlend("Multiply")
  case Screen extends PdfBlend("Screen")
  case Overlay extends PdfBlend("Overlay")
  case Darken extends PdfBlend("Darken")
  case Lighten extends PdfBlend("Lighten")
  case ColorDodge extends PdfBlend("ColorDodge")
  case ColorBurn extends PdfBlend("ColorBurn")
  case HardLight extends PdfBlend("HardLight")
  case SoftLight extends PdfBlend("SoftLight")
  case Difference extends PdfBlend("Difference")
  case Exclusion extends PdfBlend("Exclusion")
  case Hue extends PdfBlend("Hue")
  case Saturation extends PdfBlend("Saturation")
  case Color extends PdfBlend("Color")
  case Luminosity extends PdfBlend("Luminosity")

/** How a soft mask turns its content into transparency. */
enum PdfMaskKind:
  case Luminosity, Alpha

/** Dash pattern for strokes (user units). */
final case class PdfDash(array: Seq[Double], phase: Double = 0.0)

private[pdf] object Gradients:
  /** Colour function (RGB, or gray alpha) over `[d0, d1]` made of stitched exponential segments, covering `periods` unit periods. */
  def function(stops0: Seq[(Float, Color)], spread: PdfSpread, d0: Int, d1: Int, alpha: Boolean): String =
    val sorted = stops0.sortBy(_._1)
    val stops =
      val s = if sorted.head._1 > 0f then (0f, sorted.head._2) +: sorted else sorted
      if s.last._1 < 1f then s :+ ((1f, s.last._2)) else s
    // nudge equal offsets so Bounds stay strictly increasing
    val fixed = stops.zipWithIndex.map { (s, i) => (s._1, s._2) }.foldLeft(Vector.empty[(Float, Color)]) { (acc, s) =>
      if acc.nonEmpty && s._1 <= acc.last._1 then acc :+ ((acc.last._1 + 1e-5f, s._2)) else acc :+ s
    }
    val segs0 = fixed.sliding(2).collect { case Seq(a, b) => (a._1.toDouble, b._1.toDouble, a._2, b._2) }.toVector
    def comp(c: Color): String = if alpha then Fmt.n(c.a) else s"${Fmt.n(c.r)} ${Fmt.n(c.g)} ${Fmt.n(c.b)}"
    val segs = (d0 until d1).flatMap { k =>
      val mirrored = spread == PdfSpread.Reflect && (((k % 2) + 2) % 2 == 1)
      if mirrored then segs0.reverse.map((a, b, ca, cb) => (k + 1 - b, k + 1 - a, cb, ca)) else segs0.map((a, b, ca, cb) => (k + a, k + b, ca, cb))
    }.toVector
    if segs.size == 1 then
      val (_, _, a, b) = segs.head
      s"<< /FunctionType 2 /Domain [0 1] /C0 [${comp(a)}] /C1 [${comp(b)}] /N 1 >>"
    else
      val fns = segs.map((_, _, a, b) => s"<< /FunctionType 2 /Domain [0 1] /C0 [${comp(a)}] /C1 [${comp(b)}] /N 1 >>").mkString(" ")
      val bounds = segs.init.map(s => Fmt.n(s._2)).mkString(" ")
      val encode = segs.map(_ => "0 1").mkString(" ")
      s"<< /FunctionType 3 /Domain [$d0 $d1] /Functions [$fns] /Bounds [$bounds] /Encode [$encode] >>"
