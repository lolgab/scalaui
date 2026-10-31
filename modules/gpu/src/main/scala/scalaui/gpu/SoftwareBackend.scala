package scalaui.gpu

import scalaui.geom.Rect
import scalaui.window.Window

/** CPU texture: premultiplied RGBA8. */
final class SoftTexture(val width: Int, val height: Int) extends Texture:
  val data = new Array[Byte](width * height * 4)
  def update(x: Int, y: Int, w: Int, h: Int, rgba: Array[Byte]): Unit =
    require(rgba.length >= w * h * 4, "pixel buffer too small")
    for row <- 0 until h do
      if y + row >= 0 && y + row < height then System.arraycopy(rgba, row * w * 4, data, ((y + row) * width + x) * 4, w * 4)

/** Pure-Scala triangle rasterizer for [[Frame]]s: 4-sample anti-aliasing, bilinear sampling, premultiplied alpha blending
  * and scissor rects. Output matches the GPU backends closely enough for snapshot tests and serves as the fallback
  * backend on systems without a GPU path.
  */
object SoftwareRenderer:
  /** Renders `frame` to premultiplied RGBA8 of `ceil(width*scale) x ceil(height*scale)` pixels. */
  def render(frame: Frame): (Array[Byte], Int, Int) =
    val (pw, ph) = pixelSize(frame)
    val resolved = new Array[Byte](pw * ph * 4)
    renderInto(frame, resolved, None)
    (resolved, pw, ph)

  /** The pixel rectangle (x0, y0, x1, y1), ends exclusive, that `renderInto` redraws for `damage`: the logical rectangle
    * rounded outwards and clamped to the image. */
  def damagePixels(frame: Frame, damage: Option[Rect]): (Int, Int, Int, Int) =
    val (pw, ph) = pixelSize(frame)
    val s = frame.scale.toFloat
    damage match
      case Some(r) => (math.max(0, math.floor(r.x0 * s).toInt), math.max(0, math.floor(r.y0 * s).toInt), math.min(pw, math.ceil(r.x1 * s).toInt), math.min(ph, math.ceil(r.y1 * s).toInt))
      case None => (0, 0, pw, ph)

  /** Pixel size of the image `render` produces for `frame`. */
  def pixelSize(frame: Frame): (Int, Int) =
    (math.max(1, math.round(frame.width * frame.scale).toInt), math.max(1, math.round(frame.height * frame.scale).toInt))

  // the 4x sample buffer is large (16 bytes per pixel) and only ever read within the region just written, so it is reused across frames
  private var sampleBuf: Array[Byte] = new Array[Byte](0)

  /** Renders `frame` into `target` (`pw * ph * 4` bytes, see [[pixelSize]]). With `damage` (logical points) only the pixels
    * inside that rectangle are drawn and resolved; every other byte of `target` is left untouched, so the caller must pass the
    * previous frame's pixels there. Pixels inside the region are identical to what a full render produces.
    */
  def renderInto(frame: Frame, target: Array[Byte], damage: Option[Rect]): Unit = synchronized {
    val (pw, ph) = pixelSize(frame)
    require(target.length >= pw * ph * 4, "target buffer too small")
    val s = frame.scale.toFloat
    // region to (re)draw, in pixels: [rx0, rx1) x [ry0, ry1)
    val (rx0, ry0, rx1, ry1) = damagePixels(frame, damage)
    if rx0 >= rx1 || ry0 >= ry1 then return
    // 4 samples per pixel, each RGBA8 premultiplied: sample k of pixel p lives at ((p * 4) + k) * 4
    if sampleBuf.length != pw * ph * 16 then sampleBuf = new Array[Byte](pw * ph * 16)
    val out = sampleBuf
    val c = frame.clear.premultiplied
    val cr = toByte(c.r); val cg = toByte(c.g); val cb = toByte(c.b); val ca = toByte(c.a)
    // clear: write one pixel's 16 bytes, double the filled prefix of the first row with arraycopy, then copy that row down
    val rowBytes = (rx1 - rx0) * 16
    val first = (ry0 * pw + rx0) * 16
    var q = 0
    while q < 16 do { out(first + q) = cr; out(first + q + 1) = cg; out(first + q + 2) = cb; out(first + q + 3) = ca; q += 4 }
    var filled = 16
    while filled < rowBytes do { val n = math.min(filled, rowBytes - filled); System.arraycopy(out, first, out, first + filled, n); filled += n }
    var cy = ry0 + 1
    while cy < ry1 do { System.arraycopy(out, first, out, (cy * pw + rx0) * 16, rowBytes); cy += 1 }
    val v = frame.vertices
    frame.calls.foreach { call =>
      val tex = call.texture.asInstanceOf[SoftTexture]
      val (cx0, cy0, cx1, cy1) = call.scissor match
        case Some(r) => (math.max(rx0, math.floor(r.x0 * s).toInt), math.max(ry0, math.floor(r.y0 * s).toInt), math.min(rx1, math.ceil(r.x1 * s).toInt), math.min(ry1, math.ceil(r.y1 * s).toInt))
        case None => (rx0, ry0, rx1, ry1)
      if cx0 < cx1 && cy0 < cy1 then
        var t = 0
        while t + 2 < call.vertexCount do
          val a = (call.firstVertex + t) * Vertex.Floats
          triangle(out, pw, tex, v, a, a + 8, a + 16, s, cx0, cy0, cx1, cy1)
          t += 3
    }
    // resolve: average the 4 samples
    var py = ry0
    while py < ry1 do
      var p = py * pw + rx0
      val pend = py * pw + rx1
      while p < pend do
        val b = p * 16
        // most pixels are fully covered: all four samples identical
        if out(b) == out(b + 4) && out(b) == out(b + 8) && out(b) == out(b + 12) &&
           out(b + 1) == out(b + 5) && out(b + 1) == out(b + 9) && out(b + 1) == out(b + 13) &&
           out(b + 2) == out(b + 6) && out(b + 2) == out(b + 10) && out(b + 2) == out(b + 14) &&
           out(b + 3) == out(b + 7) && out(b + 3) == out(b + 11) && out(b + 3) == out(b + 15) then
          target(p * 4) = out(b); target(p * 4 + 1) = out(b + 1); target(p * 4 + 2) = out(b + 2); target(p * 4 + 3) = out(b + 3)
        else
          var ch = 0
          while ch < 4 do
            val sum = (out(b + ch) & 0xff) + (out(b + 4 + ch) & 0xff) + (out(b + 8 + ch) & 0xff) + (out(b + 12 + ch) & 0xff)
            target(p * 4 + ch) = ((sum + 2) / 4).toByte
            ch += 1
        p += 1
      py += 1
  }

  private def toByte(f: Float): Byte = (f.max(0f).min(1f) * 255f + 0.5f).toInt.toByte

  // 4 rotated-grid sample offsets inside a pixel
  private val SX = Array(0.375f, 0.875f, 0.125f, 0.625f)
  private val SY = Array(0.125f, 0.375f, 0.625f, 0.875f)
  private val loBuf = new Array[Float](4); private val hiBuf = new Array[Float](4) // scratch for `triangle` (renderInto is synchronized)

  /** Rasterizes one triangle. Per scanline the x-interval covered by each of the 4 samples is computed analytically
    * from the three edge equations, so pixels are never tested one by one; opaque single-color triangles then fill spans directly.
    */
  private def triangle(out: Array[Byte], pw: Int, tex: SoftTexture, v: Array[Float], ia: Int, ib: Int, ic: Int, s: Float,
      cx0: Int, cy0: Int, cx1: Int, cy1: Int): Unit =
    val ax = v(ia) * s; val ay = v(ia + 1) * s; val bx = v(ib) * s; val by = v(ib + 1) * s; val cx = v(ic) * s; val cy = v(ic + 1) * s
    val area = (bx - ax) * (cy - ay) - (by - ay) * (cx - ax)
    if area == 0f || area.isNaN then return
    val minY = math.max(cy0, math.floor(math.min(ay, math.min(by, cy))).toInt)
    val maxY = math.min(cy1 - 1, math.ceil(math.max(ay, math.max(by, cy))).toInt)
    if minY > maxY then return
    val sign = if area > 0 then 1f else -1f
    val inv = 1f / area
    val whiteTex = tex.width == 1 && tex.height == 1
    val sameColor = v(ia + 4) == v(ib + 4) && v(ia + 4) == v(ic + 4) && v(ia + 5) == v(ib + 5) && v(ia + 5) == v(ic + 5) &&
      v(ia + 6) == v(ib + 6) && v(ia + 6) == v(ic + 6) && v(ia + 7) == v(ib + 7) && v(ia + 7) == v(ic + 7)
    val solid = whiteTex && sameColor
    var sr = 0f; var sg = 0f; var sb = 0f; var sa = 0f
    if solid then
      val d = tex.data
      sr = v(ia + 4) * (d(0) & 0xff) / 255f; sg = v(ia + 5) * (d(1) & 0xff) / 255f
      sb = v(ia + 6) * (d(2) & 0xff) / 255f; sa = v(ia + 7) * (d(3) & 0xff) / 255f
    val opaque = solid && sa >= 0.9999f
    val r8 = ((sr * 255f + 0.5f).toInt.min(255)).toByte; val g8 = ((sg * 255f + 0.5f).toInt.min(255)).toByte
    val b8 = ((sb * 255f + 0.5f).toInt.min(255)).toByte; val a8 = ((sa * 255f + 0.5f).toInt.min(255)).toByte
    // edge i has implicit form  e_i(px, py) = ei_a * px + ei_b * py + ei_c  (>= 0 inside, after sign correction)
    // e0 opposite A: (b - p) x (c - p), e1 opposite B: (c - p) x (a - p), e2 opposite C: (a - p) x (b - p)
    // (p0 - p) x (p1 - p) = (x0-px)(y1-py) - (y0-py)(x1-px) = (x0*y1 - y0*x1) + px*(y0 - y1) + py*(x1 - x0)
    val a0 = (by - cy) * sign; val b0 = (cx - bx) * sign; val c0 = (bx * cy - by * cx) * sign
    val a1 = (cy - ay) * sign; val b1 = (ax - cx) * sign; val c1 = (cx * ay - cy * ax) * sign
    val a2 = (ay - by) * sign; val b2 = (bx - ax) * sign; val c2 = (ax * by - ay * bx) * sign
    val lo = loBuf; val hi = hiBuf // per-sample px interval (sample position, not pixel index)
    var y = minY
    while y <= maxY do
      var rowMin = Float.MaxValue; var rowMax = Float.MinValue
      var k = 0
      while k < 4 do
        val py = y + SY(k)
        var l = Float.NegativeInfinity; var h = Float.PositiveInfinity
        // e(px) = a*px + (b*py + c) >= 0
        inline def clipEdge(a: Float, rest: Float): Unit =
          if a > 0f then { val t = -rest / a; if t > l then l = t }
          else if a < 0f then { val t = -rest / a; if t < h then h = t }
          else if rest < 0f then { l = Float.PositiveInfinity; h = Float.NegativeInfinity }
        clipEdge(a0, b0 * py + c0); clipEdge(a1, b1 * py + c1); clipEdge(a2, b2 * py + c2)
        lo(k) = l - SX(k); hi(k) = h - SX(k) // in pixel-index units: pixel x covers sample k when lo <= x <= hi
        if l <= h then { if lo(k) < rowMin then rowMin = lo(k); if hi(k) > rowMax then rowMax = hi(k) }
        k += 1
      if rowMin <= rowMax then
        val xs = math.max(cx0, math.ceil(rowMin).toInt - 0); val xe = math.min(cx1 - 1, math.floor(rowMax).toInt)
        // fully covered range: every sample covers x
        val fullLo = math.ceil(math.max(math.max(lo(0), lo(1)), math.max(lo(2), lo(3)))).toInt
        val fullHi = math.floor(math.min(math.min(hi(0), hi(1)), math.min(hi(2), hi(3)))).toInt
        var x = xs
        while x <= xe do
          var mask = 0
          if x >= fullLo && x <= fullHi then
            mask = 15
            if opaque then
              // span fill: skip straight to fullHi writing 16 bytes per pixel
              val end = math.min(xe, fullHi)
              var o = (y * pw + x) * 16
              while x <= end do
                var q = 0
                while q < 16 do { out(o + q) = r8; out(o + q + 1) = g8; out(o + q + 2) = b8; out(o + q + 3) = a8; q += 4 }
                o += 16; x += 1
              mask = -1
          else
            var q = 0
            while q < 4 do
              if lo(q) <= x && x <= hi(q) then mask |= (1 << q)
              q += 1
          if mask > 0 then shade(out, pw, tex, v, ia, ib, ic, x, y, mask, solid, sr, sg, sb, sa, ax, ay, bx, by, cx, cy, inv, whiteTex)
          if mask != -1 then x += 1
      y += 1

  private def shade(out: Array[Byte], pw: Int, tex: SoftTexture, v: Array[Float], ia: Int, ib: Int, ic: Int, x: Int, y: Int, mask: Int,
      solid: Boolean, sr: Float, sg: Float, sb: Float, sa: Float, ax: Float, ay: Float, bx: Float, by: Float, cx: Float, cy: Float,
      inv: Float, whiteTex: Boolean): Unit =
    var r = sr; var g = sg; var b = sb; var al = sa
    if !solid then
      val px = x + 0.5f; val py = y + 0.5f
      val l0 = ((bx - px) * (cy - py) - (by - py) * (cx - px)) * inv
      val l1 = ((cx - px) * (ay - py) - (cy - py) * (ax - px)) * inv
      val l2 = 1f - l0 - l1
      r = l0 * v(ia + 4) + l1 * v(ib + 4) + l2 * v(ic + 4)
      g = l0 * v(ia + 5) + l1 * v(ib + 5) + l2 * v(ic + 5)
      b = l0 * v(ia + 6) + l1 * v(ib + 6) + l2 * v(ic + 6)
      al = l0 * v(ia + 7) + l1 * v(ib + 7) + l2 * v(ic + 7)
      if whiteTex then
        val d = tex.data; val f = 1f / 255f
        r *= (d(0) & 0xff) * f; g *= (d(1) & 0xff) * f; b *= (d(2) & 0xff) * f; al *= (d(3) & 0xff) * f
      else
        val u = l0 * v(ia + 2) + l1 * v(ib + 2) + l2 * v(ic + 2)
        val vv = l0 * v(ia + 3) + l1 * v(ib + 3) + l2 * v(ic + 3)
        // bilinear sample (clamp-to-edge) multiplied by the interpolated vertex color
        val tw = tex.width; val th = tex.height
        val fx0 = u * tw - 0.5f; val fy0 = vv * th - 0.5f
        val fx = if fx0 < 0f then 0f else if fx0 > tw - 1f then tw - 1f else fx0
        val fy = if fy0 < 0f then 0f else if fy0 > th - 1f then th - 1f else fy0
        val x0 = fx.toInt; val y0 = fy.toInt
        val x1 = if x0 + 1 < tw - 1 then x0 + 1 else tw - 1; val y1 = if y0 + 1 < th - 1 then y0 + 1 else th - 1
        val tx = fx - x0; val ty = fy - y0
        val td = tex.data
        val o00 = (y0 * tw + x0) * 4; val o10 = (y0 * tw + x1) * 4; val o01 = (y1 * tw + x0) * 4; val o11 = (y1 * tw + x1) * 4
        r *= bilinear(td, o00, o10, o01, o11, tx, ty)
        g *= bilinear(td, o00 + 1, o10 + 1, o01 + 1, o11 + 1, tx, ty)
        b *= bilinear(td, o00 + 2, o10 + 2, o01 + 2, o11 + 2, tx, ty)
        al *= bilinear(td, o00 + 3, o10 + 3, o01 + 3, o11 + 3, tx, ty)
    val inva = 1f - al
    val base = (y * pw + x) * 16
    if mask == 15 &&
       out(base) == out(base + 4) && out(base) == out(base + 8) && out(base) == out(base + 12) &&
       out(base + 1) == out(base + 5) && out(base + 1) == out(base + 9) && out(base + 1) == out(base + 13) &&
       out(base + 2) == out(base + 6) && out(base + 2) == out(base + 10) && out(base + 2) == out(base + 14) &&
       out(base + 3) == out(base + 7) && out(base + 3) == out(base + 11) && out(base + 3) == out(base + 15) then
      // all four samples hold the same color (the interior of earlier shapes): blend once, store four times
      val nr = ((r * 255f + (out(base) & 0xff) * inva) + 0.5f).toInt.min(255).toByte
      val ng = ((g * 255f + (out(base + 1) & 0xff) * inva) + 0.5f).toInt.min(255).toByte
      val nb = ((b * 255f + (out(base + 2) & 0xff) * inva) + 0.5f).toInt.min(255).toByte
      val na = ((al * 255f + (out(base + 3) & 0xff) * inva) + 0.5f).toInt.min(255).toByte
      var q = 0
      while q < 16 do { out(base + q) = nr; out(base + q + 1) = ng; out(base + q + 2) = nb; out(base + q + 3) = na; q += 4 }
    else
      var q = 0
      while q < 4 do
        if (mask & (1 << q)) != 0 then
          val o = base + q * 4
          out(o) = ((r * 255f + (out(o) & 0xff) * inva) + 0.5f).toInt.min(255).toByte
          out(o + 1) = ((g * 255f + (out(o + 1) & 0xff) * inva) + 0.5f).toInt.min(255).toByte
          out(o + 2) = ((b * 255f + (out(o + 2) & 0xff) * inva) + 0.5f).toInt.min(255).toByte
          out(o + 3) = ((al * 255f + (out(o + 3) & 0xff) * inva) + 0.5f).toInt.min(255).toByte
        q += 1

  @inline private def bilinear(d: Array[Byte], o00: Int, o10: Int, o01: Int, o11: Int, tx: Float, ty: Float): Float =
    val p00 = d(o00) & 0xff; val p10 = d(o10) & 0xff; val p01 = d(o01) & 0xff; val p11 = d(o11) & 0xff
    ((p00 * (1 - tx) + p10 * tx) * (1 - ty) + (p01 * (1 - tx) + p11 * tx) * ty) / 255f

/** [[GpuBackend]] that renders on the CPU and hands the pixels to the window. Works anywhere `Window.present` does.
  * Keeps the last frame's pixels, so `submit(frame, Some(damage))` redraws only the damaged rectangle.
  */
final class SoftwareBackend(window: Window) extends GpuBackend:
  val whiteTexture: Texture = createTexture(1, 1, Array(-1, -1, -1, -1))
  private var pixels: Array[Byte] = new Array[Byte](0)
  private var pw = 0; private var ph = 0; private var lastScale = 0.0
  def createTexture(width: Int, height: Int, rgba: Array[Byte]): Texture =
    val t = new SoftTexture(width, height)
    if rgba != null then t.update(0, 0, width, height, rgba)
    t
  def submit(frame: Frame): Unit = submit(frame, None)
  override def submit(frame: Frame, damage: Option[Rect]): Unit =
    val (w, h) = SoftwareRenderer.pixelSize(frame)
    val sameSize = w == pw && h == ph && frame.scale == lastScale
    if !sameSize then { pixels = new Array[Byte](w * h * 4); pw = w; ph = h; lastScale = frame.scale }
    // a size change (or the first frame) invalidates the retained pixels: draw everything
    val region = if sameSize then damage else None
    SoftwareRenderer.renderInto(frame, pixels, region)
    region match
      case Some(_) =>
        val (x0, y0, x1, y1) = SoftwareRenderer.damagePixels(frame, region)
        if x1 > x0 && y1 > y0 then window.present(pixels, w, h, x0, y0, x1 - x0, y1 - y0)
      case None => window.present(pixels, w, h)
  def close(): Unit = ()
