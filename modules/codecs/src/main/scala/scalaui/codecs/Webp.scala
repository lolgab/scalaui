package scalaui.codecs

/** WebP: lossless (VP8L) and lossy (VP8) stills, with alpha, and animations (VP8X/ANMF). */
object Webp:
  def matches(d: Array[Byte]): Boolean =
    d.length >= 12 && d(0) == 'R' && d(1) == 'I' && d(2) == 'F' && d(3) == 'F' && d(8) == 'W' && d(9) == 'E' && d(10) == 'B' && d(11) == 'P'

  private def fail(msg: String): Nothing = throw new ImageFormatException("WebP: " + msg)
  private def u24(d: Array[Byte], o: Int) = (d(o) & 0xff) | ((d(o + 1) & 0xff) << 8) | ((d(o + 2) & 0xff) << 16)
  private def u32(d: Array[Byte], o: Int) = u24(d, o) | ((d(o + 3) & 0xff) << 24)

  private final case class Chunk(tag: String, start: Int, end: Int)

  private def chunks(d: Array[Byte], from: Int, to: Int): Vector[Chunk] =
    val out = Vector.newBuilder[Chunk]
    var p = from
    while p + 8 <= to do
      val size = u32(d, p + 4)
      val end = p + 8 + size
      if size < 0 || end > to then { out += Chunk(new String(d, p, 4, "ISO-8859-1"), p + 8, to); p = to }
      else { out += Chunk(new String(d, p, 4, "ISO-8859-1"), p + 8, end); p = end + (size & 1) }
    out.result()

  def decode(d: Array[Byte]): Image =
    val anim = decodeAnimation(d)
    anim.frames.head.image

  /** All frames of an animated WebP composited on the canvas; a still image yields a single frame. */
  def decodeAnimation(d: Array[Byte]): Animation =
    if !matches(d) then fail("not a WebP file")
    val riffEnd = math.min(d.length, u32(d, 4) + 8)
    val cs = chunks(d, 12, riffEnd)
    val vp8x = cs.find(_.tag == "VP8X")
    val frames = cs.filter(_.tag == "ANMF")
    if frames.isEmpty then
      val img = decodeStill(d, cs)
      Animation(img.width, img.height, Vector(AnimationFrame(img, 0)), 0)
    else
      val x = vp8x.getOrElse(fail("animation without VP8X"))
      val cw = u24(d, x.start + 4) + 1; val ch = u24(d, x.start + 7) + 1
      val anim = cs.find(_.tag == "ANIM")
      val background = anim.map(a => u32(d, a.start)).getOrElse(0)
      val loops = anim.map(a => (d(a.start + 4) & 0xff) | ((d(a.start + 5) & 0xff) << 8)).getOrElse(0)
      var canvas = new Array[Byte](cw * ch * 4)
      val bgBytes = Array((background >> 16).toByte, (background >> 8).toByte, background.toByte, (background >>> 24).toByte)
      val out = Vector.newBuilder[AnimationFrame]
      var prevDispose = 0; var prevRect = (0, 0, 0, 0)
      frames.foreach { f =>
        val fx = u24(d, f.start) * 2; val fy = u24(d, f.start + 3) * 2
        val fw = u24(d, f.start + 6) + 1; val fh = u24(d, f.start + 9) + 1
        val duration = u24(d, f.start + 12)
        val flags = d(f.start + 15) & 0xff
        val blend = (flags & 2) == 0; val dispose = flags & 1
        // dispose the previous frame's area to the background colour (transparent unless the file says otherwise)
        if prevDispose == 1 then
          val (px, py, pw, ph) = prevRect
          var yy = py
          while yy < math.min(ch, py + ph) do
            var xx = px
            while xx < math.min(cw, px + pw) do { System.arraycopy(Array[Byte](0, 0, 0, 0), 0, canvas, (yy * cw + xx) * 4, 4); xx += 1 }
            yy += 1
        val sub = chunks(d, f.start + 16, f.end)
        val img = decodeStill(d, sub)
        if img.width != fw || img.height != fh then fail("frame size mismatch")
        var yy = 0
        while yy < fh do
          var xx = 0
          while xx < fw do
            val s = (yy * fw + xx) * 4
            val o = ((fy + yy) * cw + fx + xx) * 4
            if fx + xx < cw && fy + yy < ch then
              val sa = img.rgba(s + 3) & 0xff
              if !blend || sa == 255 then System.arraycopy(img.rgba, s, canvas, o, 4)
              else if sa != 0 then
                val da = canvas(o + 3) & 0xff
                val oa = sa + da * (255 - sa) / 255
                var c = 0
                while c < 3 do
                  val sc = img.rgba(s + c) & 0xff; val dc = canvas(o + c) & 0xff
                  canvas(o + c) = (if oa == 0 then 0 else (sc * sa * 255 + dc * da * (255 - sa)) / (oa * 255)).toByte
                  c += 1
                canvas(o + 3) = oa.toByte
            xx += 1
          yy += 1
        out += AnimationFrame(Image(cw, ch, canvas.clone()), duration)
        prevDispose = dispose; prevRect = (fx, fy, fw, fh)
      }
      Animation(cw, ch, out.result(), loops)

  private def decodeStill(d: Array[Byte], cs: Vector[Chunk]): Image =
    cs.find(_.tag == "VP8L") match
      case Some(c) =>
        val (w, h, argb) = WebpLossless.decode(d, c.start, c.end)
        val rgba = new Array[Byte](w * h * 4)
        var i = 0
        while i < w * h do
          val p = argb(i)
          rgba(i * 4) = (p >> 16).toByte; rgba(i * 4 + 1) = (p >> 8).toByte; rgba(i * 4 + 2) = p.toByte; rgba(i * 4 + 3) = (p >>> 24).toByte
          i += 1
        Image(w, h, rgba)
      case None =>
        cs.find(_.tag == "VP8 ") match
          case Some(c) =>
            val frame = WebpLossy.decode(d, c.start, c.end)
            val alpha = cs.find(_.tag == "ALPH").map(a => WebpAlpha.decode(d, a.start, a.end, frame.width, frame.height))
            WebpLossy.toImage(frame, alpha)
          case None => fail("no image data")
