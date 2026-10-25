package scalaui.gpu

import scalaui.color.Color
import scalaui.geom.{Point, Rect}

/** A scripted sequence of frames (each paired with the damage rectangle against the previous one) shared by the backend suites:
  * running it with the damage hints and running every frame as a full redraw must give identical pixels. */
object DamageScenario:
  def frame(be: GpuBackend, tex: Texture, scale: Double, shift: Double, extra: Boolean): Frame =
    val c = new Canvas(be.whiteTexture); c.begin(64, 48, scale, Color(0.1f, 0.2f, 0.3f, 1f))
    c.fillRectGradient(Rect(0, 0, 64, 48), Color(1, 0, 0, 1), Color(0, 1, 0, 1), Color(0, 0, 1, 1), Color(1, 1, 0, 1))
    c.fillCircle(Point(20 + shift, 20), 9.3, Color(1, 1, 1, 0.6f))
    c.drawImage(tex, Rect(30, 10, 50, 22))
    c.pushClip(Rect(5, 30, 40, 44)); c.fillRoundedRect(Rect(0, 28, 60, 46), 5, Color(0, 0, 0, 0.5f)); c.popClip()
    if extra then c.strokeCircle(Point(50, 36), 7.4, 2.2, Color(1, 0.5f, 0, 0.8f))
    c.finish()

  /** (frame parameters, damage) steps: moved circle, added ring, an off-pixel-grid rectangle, an empty and a whole-frame damage. */
  val steps: Seq[(Double, Boolean, Option[Rect])] = Seq(
    (0.0, false, None),
    (6.0, false, Some(Rect(10, 8, 37, 32))),
    (6.0, true, Some(Rect(41.3, 27.7, 59.1, 45.2))),
    (3.5, true, Some(Rect(7.1, 8, 36.4, 32))),
    (3.5, true, Some(Rect(100, 100, 120, 120))), // outside: nothing to draw
    (9.0, true, Some(Rect(0, 0, 64, 48))))

  /** Runs the script on `be`; after each step `read` returns the backend's pixels, which are compared with a full redraw of the same frame. */
  def run(be: GpuBackend, scale: Double, read: () => (Array[Byte], Int, Int)): Unit =
    val tex = be.createTexture(2, 2, Array.tabulate[Byte](16)(i => (i * 17).toByte))
    steps.zipWithIndex.foreach { case ((shift, extra, damage), i) =>
      val f = frame(be, tex, scale, shift, extra)
      be.submit(f, damage)
      val (partial, w, h) = read(); val got = partial.clone()
      be.submit(f, None)
      val (full, w2, h2) = read()
      assertEq((w, h), (w2, h2), s"step $i size")
      assertEq(full.count(_ != 0) > w * h, true, s"step $i: the readback must hold the picture")
      assertEq((w, h), ((64 * scale).toInt, (48 * scale).toInt), s"step $i pixel size")
      val diff = got.indices.find(k => got(k) != full(k))
      assertEq(diff, None, s"step $i scale $scale: partial differs from full at byte ${diff.getOrElse(-1)} (pixel ${diff.map(_ / 4 % w)}, ${diff.map(_ / 4 / w)})")
    }
    // a size change falls back to a full redraw: the new frame must still be correct
    val c = new Canvas(be.whiteTexture); c.begin(40, 30, scale, Color.Black); c.fillRect(Rect(2, 2, 20, 20), Color.White)
    val small = c.finish()
    be.submit(small, Some(Rect(0, 0, 5, 5)))
    val (a, aw, ah) = read(); val got = a.clone()
    be.submit(small, None)
    val (b, bw, bh) = read()
    assertEq((aw, ah), (bw, bh), "resize size"); assertEq(got.sameElements(b), true, "after resize the partial hint must fall back to a full redraw")
    // a scale change as well
    val c2 = new Canvas(be.whiteTexture); c2.begin(40, 30, scale * 2, Color.Black); c2.fillRect(Rect(2, 2, 20, 20), Color.White)
    val scaled = c2.finish()
    be.submit(scaled, Some(Rect(0, 0, 5, 5)))
    val (d, _, _) = read(); val got2 = d.clone()
    be.submit(scaled, None)
    assertEq(got2.sameElements(read()._1), true, "after a scale change the partial hint must fall back to a full redraw")

  private def assertEq[A](a: A, b: A, msg: String): Unit = if a != b then throw new AssertionError(s"$msg: $a != $b")
