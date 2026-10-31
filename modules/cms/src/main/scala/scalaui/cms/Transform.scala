package scalaui.cms

import Adaptation.D50

/**
 * Colour transform from `src` to `dst` through the D50 PCS.
 * `intent` selects the A2B/B2A tags and, for absolute colorimetric, scales by the media white points;
 * `blackPointCompensation` maps the source black point to the destination black point.
 * Device values are 0..1 per channel (CMYK: 1 = full ink). Instances are immutable and thread-safe.
 */
final class Transform(val src: Profile, val dst: Profile, val intent: Intent = Intent.Perceptual, val blackPointCompensation: Boolean = false):
  private val inN = src.channels
  private val outN = dst.channels
  if !src.canInput(intent) then throw new CmsException("source profile cannot convert to the PCS")
  if !dst.canOutput(intent) then throw new CmsException("destination profile cannot convert from the PCS")

  private val absScale: Option[(Double, Double, Double)] =
    if intent == Intent.AbsoluteColorimetric then
      val s = src.whitePoint; val d = dst.whitePoint
      Some((s._1 / d._1, s._2 / d._2, s._3 / d._3))
    else None

  private val bpc: Option[(Array[Double], Array[Double])] =
    if !blackPointCompensation || intent == Intent.AbsoluteColorimetric then None
    else
      val bs = Transform.blackPoint(src); val bd = Transform.blackPoint(dst)
      if bs == bd then None
      else
        val w = Array(D50._1, D50._2, D50._3)
        val a = Array.tabulate(3)(i => (w(i) - bd(i)) / (w(i) - bs(i)))
        val b = Array.tabulate(3)(i => w(i) * (bd(i) - bs(i)) / (w(i) - bs(i)))
        Some((a, b))

  def inputChannels: Int = inN
  def outputChannels: Int = outN

  /** Converts one pixel of device values. */
  def apply(in: Array[Double]): Array[Double] =
    var (x, y, z) = src.toXyz(in, intent)
    absScale.foreach { s => x *= s._1; y *= s._2; z *= s._3 }
    bpc.foreach { (a, b) => x = a(0) * x + b(0); y = a(1) * y + b(1); z = a(2) * z + b(2) }
    dst.fromXyz(x, y, z, intent)

  /** Converts `pixels` pixels of tightly packed floats (`inputChannels` in, `outputChannels` out); `src` and `dst` may not alias unless equal channel counts. */
  def convert(src: Array[Float], dst: Array[Float], pixels: Int): Unit =
    val in = new Array[Double](inN)
    var p = 0
    while p < pixels do
      var c = 0
      while c < inN do { in(c) = src(p * inN + c); c += 1 }
      val o = apply(in)
      c = 0
      while c < outN do { dst(p * outN + c) = o(c).toFloat; c += 1 }
      p += 1

  /**
   * Converts 8-bit pixels. Each pixel has `srcStride` source bytes (at least the input channels) and `dstStride` destination bytes;
   * when both strides exceed their channel counts the byte after the colour channels (alpha) is copied unchanged.
   */
  def convert8(src: Array[Byte], dst: Array[Byte], pixels: Int, srcStride: Int, dstStride: Int): Unit =
    require(srcStride >= inN && dstStride >= outN, "stride smaller than channel count")
    val in = new Array[Double](inN)
    val cache = new Array[Byte](outN)
    val last = new Array[Byte](inN)
    var haveLast = false
    var p = 0
    while p < pixels do
      val so = p * srcStride; val dO = p * dstStride
      var same = haveLast; var c = 0
      while c < inN do
        if src(so + c) != last(c) then same = false
        c += 1
      if !same then
        c = 0
        while c < inN do { last(c) = src(so + c); in(c) = (src(so + c) & 0xff) / 255.0; c += 1 }
        val o = apply(in)
        c = 0
        while c < outN do { cache(c) = Math.round(o(c) * 255).toByte; c += 1 }
        haveLast = true
      System.arraycopy(cache, 0, dst, dO, outN)
      if srcStride > inN && dstStride > outN then dst(dO + outN) = src(so + inN)
      p += 1

  /** RGBA8 to RGBA8 (alpha copied). Gray profiles read/write the first channel only (the rest is replicated on output). */
  def rgba8(src: Array[Byte], dst: Array[Byte], pixels: Int): Unit =
    if outN == 3 then convert8(src, dst, pixels, 4, 4)
    else
      val tmp = new Array[Byte](pixels * 4)
      convert8(src, tmp, pixels, 4, 4)
      var p = 0
      while p < pixels do
        val o = p * 4
        dst(o) = tmp(o); dst(o + 1) = tmp(o); dst(o + 2) = tmp(o); dst(o + 3) = src(o + 3)
        p += 1

  /** One colour. */
  def apply(r: Float, g: Float, b: Float): (Float, Float, Float) =
    val o = apply(Array(r.toDouble, g.toDouble, b.toDouble))
    (o(0).toFloat, o(1).toFloat, o(2).toFloat)

object Transform:
  /** Darkest neutral the profile can reproduce (device black: all inks for CMYK), as XYZ relative to D50. */
  private def blackPoint(p: Profile): Array[Double] =
    val rel = Intent.RelativeColorimetric
    val maxY = Adaptation.labToXyz(50, 0, 0)._2 // like littleCMS: neutralised, L* <= 50
    val y0 =
      if p.colorSpace == "CMYK" && p.profileClass == "prtr" && p.canOutput(Intent.Perceptual) then
        // printer profiles: round trip Lab black through the perceptual tables (discounts the ink limit)
        val dev = p.fromXyz(0, 0, 0, Intent.Perceptual)
        p.toXyz(dev, Intent.Perceptual)._2
      else
        val candidates: Seq[Array[Double]] = p.colorSpace match
          case "CMYK" => Seq(Array(1.0, 1.0, 1.0, 1.0))
          case "GRAY" => Seq(Array(0.0))
          case _ => Seq(Array.fill(p.channels)(0.0))
        candidates.map(c => p.toXyz(c, rel)._2).min
    val y = math.max(0.0, math.min(maxY, y0))
    Array(D50._1 * y, y, D50._3 * y)

  def apply(src: Profile, dst: Profile): Transform = new Transform(src, dst)

  /** Applies `convert` on a colour `(r, g, b)` via a throwaway transform; for one-offs. */
  def convertColor(src: Profile, dst: Profile, c: (Float, Float, Float)): (Float, Float, Float) = new Transform(src, dst)(c._1, c._2, c._3)

