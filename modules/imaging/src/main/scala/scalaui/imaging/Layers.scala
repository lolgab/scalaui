package scalaui.imaging

import scalaui.geom.*
import scalaui.raster.*

/** Offscreen layers (vello's push_layer/pop_layer): content is drawn in isolation, optionally clipped to an
  * anti-aliased path and multiplied by a mask, then composited with opacity and a blend mode.
  */
object Layers:
  /** Runs `draw` on a transparent pixmap of `dst`'s size and composites the result onto `dst`.
    * @param clip     path (transformed by `transform`) the layer is clipped to, with edge anti-aliasing
    * @param mask     pixmap of the same size whose alpha channel multiplies the layer
    */
  def layer(dst: Pixmap, opacity: Float = 1f, mode: BlendMode = BlendMode.Normal, clip: Option[Path] = None,
      rule: FillRule = FillRule.NonZero, transform: Affine = Affine.Identity, mask: Option[Pixmap] = None)(draw: Pixmap => Unit): Unit =
    val layer = new Pixmap(dst.width, dst.height)
    draw(layer)
    clip.foreach { p =>
      val cov = new Pixmap(dst.width, dst.height)
      cov.fill(p, Paint.solid(scalaui.color.Color.White), rule, transform)
      multiplyByAlpha(layer, cov)
    }
    mask.foreach { m =>
      require(m.width == dst.width && m.height == dst.height, "mask must match the destination size")
      multiplyByAlpha(layer, m)
    }
    Blend.composite(dst, layer, 0, 0, mode, opacity)

  /** Scales every (premultiplied) pixel of `target` by the alpha of `m`. */
  private def multiplyByAlpha(target: Pixmap, m: Pixmap): Unit =
    var i = 0
    while i < target.data.length do
      val a = m.data(i + 3)
      target.data(i) *= a; target.data(i + 1) *= a; target.data(i + 2) *= a; target.data(i + 3) *= a
      i += 4
