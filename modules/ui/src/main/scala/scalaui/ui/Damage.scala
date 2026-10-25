package scalaui.ui

import scala.collection.mutable.ArrayBuffer
import scalaui.geom.Rect
import scalaui.gpu.{DrawCall, Frame, GpuBackend, Texture}

/** A texture made by a [[TrackedBackend]]: forwards to the real texture and remembers which texels were overwritten since the
  * last frame, so the damage tracker knows when a triangle that is otherwise unchanged now shows different pixels. */
final class TrackedTexture private[ui] (val inner: Texture, private[ui] val id: Long) extends Texture:
  def width: Int = inner.width
  def height: Int = inner.height
  private[ui] val updates = ArrayBuffer.empty[(Int, Int, Int, Int)] // x, y, w, h since the last frame was analysed
  def update(x: Int, y: Int, w: Int, h: Int, rgba: Array[Byte]): Unit =
    inner.update(x, y, w, h, rgba)
    if updates.length >= 64 then // many small uploads: keep one box
      val b = updates.foldLeft((Int.MaxValue, Int.MaxValue, Int.MinValue, Int.MinValue)) { case ((a, b0, c, d), (ux, uy, uw, uh)) => (math.min(a, ux), math.min(b0, uy), math.max(c, ux + uw), math.max(d, uy + uh)) }
      updates.clear(); updates += ((b._1, b._2, b._3 - b._1, b._4 - b._2))
    updates += ((x, y, w, h))

/** Wraps a backend so that every texture is a [[TrackedTexture]]; frames are unwrapped again before they reach the real backend. */
final class TrackedBackend(val inner: GpuBackend) extends GpuBackend:
  private var nextId = 1L
  val whiteTexture: Texture = new TrackedTexture(inner.whiteTexture, 0L)
  def createTexture(width: Int, height: Int, rgba: Array[Byte]): Texture =
    nextId += 1
    new TrackedTexture(inner.createTexture(width, height, rgba), nextId)
  /** The frame with the real backend's textures. */
  def unwrap(frame: Frame): Frame =
    frame.copy(calls = frame.calls.map(c => c.texture match
      case t: TrackedTexture => DrawCall(t.inner, c.scissor, c.firstVertex, c.vertexCount)
      case _ => c))
  def submit(frame: Frame): Unit = inner.submit(unwrap(frame))
  override def submit(frame: Frame, damage: Option[Rect]): Unit = inner.submit(unwrap(frame), damage)
  def close(): Unit = inner.close()

/** Works out which part of the window changed between two consecutive frames, by hashing the draw output tile by tile.
  *
  * Every triangle is hashed (vertices, texture, scissor) and folded, in draw order, into the hash of each tile its bounding
  * box (clipped to its scissor, widened for anti-aliasing) touches. Tiles whose hash differs from the previous frame's are
  * dirty, and so are tiles touched by a triangle whose texture region was overwritten since the previous frame. The damage is
  * the bounding box of the dirty tiles. The result is conservative: a changed pixel is never outside it (up to 64-bit hash
  * collisions). `None` (everything) is returned for the first frame, after a size, scale or clear-colour change, when asked to
  * `force`, when coordinates are not finite, and when the damage covers nearly the whole window anyway.
  */
final class DamageTracker(val backend: TrackedBackend):
  private val Tile = 32.0
  private var prev: Array[Long] = null
  private var prevW = 0.0; private var prevH = 0.0; private var prevScale = 0.0; private var prevClear: AnyRef = null
  /** Fraction of tiles that were dirty in the last analysed frame (for diagnostics). */
  var lastDirtyFraction = 1.0

  private inline def mix(h: Long, v: Long): Long =
    val x = (h ^ v) * 0x9E3779B97F4A7C15L
    x ^ (x >>> 29)

  /** Compares `frame` with the previous analysed frame and remembers it. */
  def damage(frame: Frame, force: Boolean = false): Option[Rect] =
    val cols = math.max(1, math.ceil(frame.width / Tile).toInt); val rows = math.max(1, math.ceil(frame.height / Tile).toInt)
    val cur = new Array[Long](cols * rows)
    val forced = new Array[Boolean](cols * rows)
    val grow = math.max(1.0, 1.0 / math.max(frame.scale, 1e-3)) + 0.5 // anti-aliasing / scissor rounding reach, in points
    var finite = true
    val v = frame.vertices
    val clearKey = frame.clear
    frame.calls.foreach { call =>
      val tracked = call.texture match { case t: TrackedTexture => t; case _ => null }
      val texId = if tracked != null then tracked.id else -1L
      val pending = if tracked != null then tracked.updates.toVector else Vector.empty
      var sh = mix(0L, texId)
      var clip: Rect = Rect(0, 0, frame.width, frame.height)
      call.scissor.foreach { r =>
        sh = mix(mix(mix(mix(sh, java.lang.Double.doubleToLongBits(r.x0)), java.lang.Double.doubleToLongBits(r.y0)), java.lang.Double.doubleToLongBits(r.x1)), java.lang.Double.doubleToLongBits(r.y1))
        clip = Rect(math.max(0, r.x0 - grow), math.max(0, r.y0 - grow), math.min(frame.width, r.x1 + grow), math.min(frame.height, r.y1 + grow))
      }
      var t = 0
      while t + 2 < call.vertexCount do
        val a = (call.firstVertex + t) * 8
        var h = sh
        var k = 0
        while k < 24 do { h = mix(h, java.lang.Float.floatToRawIntBits(v(a + k)).toLong); k += 1 }
        val x0 = v(a); val x1 = v(a + 8); val x2 = v(a + 16); val y0 = v(a + 1); val y1 = v(a + 9); val y2 = v(a + 17)
        val sum = x0 + x1 + x2 + y0 + y1 + y2 // NaN or infinity anywhere makes the sum non-finite
        if sum.isNaN || sum.isInfinite then finite = false
        else
          val bx0 = math.max(clip.x0, math.min(x0, math.min(x1, x2)) - grow); val by0 = math.max(clip.y0, math.min(y0, math.min(y1, y2)) - grow)
          val bx1 = math.min(clip.x1, math.max(x0, math.max(x1, x2)) + grow); val by1 = math.min(clip.y1, math.max(y0, math.max(y1, y2)) + grow)
          if bx0 < bx1 && by0 < by1 then
            // texels this triangle samples (bilinear: one extra texel each side); an overlap with an upload makes its tiles dirty
            var uploaded = tracked == null
            if !uploaded && pending.nonEmpty then
              val ua = v(a + 2); val ub = v(a + 10); val uc = v(a + 18); val va = v(a + 3); val vb = v(a + 11); val vc = v(a + 19)
              val tw = tracked.width; val th = tracked.height
              val usum = ua + ub + uc + va + vb + vc
              if usum.isNaN || usum.isInfinite then uploaded = true
              else
                val u0 = math.max(0, math.min(tw - 1, math.floor(math.min(ua, math.min(ub, uc)).toDouble * tw).toInt - 1))
                val u1 = math.min(tw, math.max(1, math.ceil(math.max(ua, math.max(ub, uc)).toDouble * tw).toInt + 1))
                val v0 = math.max(0, math.min(th - 1, math.floor(math.min(va, math.min(vb, vc)).toDouble * th).toInt - 1))
                val v1 = math.min(th, math.max(1, math.ceil(math.max(va, math.max(vb, vc)).toDouble * th).toInt + 1))
                uploaded = pending.exists { case (ux, uy, uw, uh) => ux < u1 && ux + uw > u0 && uy < v1 && uy + uh > v0 }
            val c0 = math.max(0, (bx0 / Tile).toInt); val c1 = math.min(cols - 1, (bx1 / Tile).toInt)
            val r0 = math.max(0, (by0 / Tile).toInt); val r1 = math.min(rows - 1, (by1 / Tile).toInt)
            var r = r0
            while r <= r1 do
              var c = c0
              while c <= c1 do
                val i = r * cols + c
                cur(i) = mix(cur(i), h)
                if uploaded then forced(i) = true
                c += 1
              r += 1
        t += 3
    }
    frame.calls.foreach(c => c.texture match { case t: TrackedTexture => t.updates.clear(); case _ => () })
    val comparable = !force && finite && prev != null && prev.length == cur.length && prevW == frame.width && prevH == frame.height &&
      prevScale == frame.scale && prevClear == clearKey
    val old = prev
    prev = cur; prevW = frame.width; prevH = frame.height; prevScale = frame.scale; prevClear = clearKey
    if !comparable then { lastDirtyFraction = 1.0; None }
    else
      var c0 = Int.MaxValue; var c1 = -1; var r0 = Int.MaxValue; var r1 = -1; var n = 0
      var i = 0
      while i < cur.length do
        if cur(i) != old(i) || forced(i) then
          val c = i % cols; val r = i / cols
          if c < c0 then c0 = c; if c > c1 then c1 = c; if r < r0 then r0 = r; if r > r1 then r1 = r
          n += 1
        i += 1
      lastDirtyFraction = n.toDouble / cur.length
      if n == 0 then Some(Rect(0, 0, 0, 0))
      else
        val rect = Rect(c0 * Tile, r0 * Tile, math.min(frame.width, (c1 + 1) * Tile), math.min(frame.height, (r1 + 1) * Tile))
        if rect.width * rect.height >= 0.85 * frame.width * frame.height then None else Some(rect)

  /** Analyses `frame` and submits it with the resulting damage; returns what was passed. */
  def submit(frame: Frame, force: Boolean = false): Option[Rect] =
    val d = damage(frame, force)
    backend.submit(frame, d)
    d
