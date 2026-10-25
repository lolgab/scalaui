package scalaui.codecs

import java.io.ByteArrayOutputStream
import scala.collection.mutable.ArrayBuffer

/** GIF87a/89a: LZW, global/local palettes, interlacing, transparency, animation with disposal methods. The encoder
  * quantizes to at most 255 colours (plus a transparent index) with median cut and no dithering.
  */
object Gif:
  def matches(d: Array[Byte]): Boolean = d.length >= 6 && d(0) == 'G' && d(1) == 'I' && d(2) == 'F' && d(3) == '8'

  private def u16(d: Array[Byte], o: Int) = (d(o) & 0xff) | ((d(o + 1) & 0xff) << 8)
  private def fail(msg: String): Nothing = throw new ImageFormatException("GIF: " + msg)

  /** The first frame composited on the logical screen. */
  def decode(d: Array[Byte]): Image = decodeAnimation(d).frames.head.image

  def decodeAnimation(d: Array[Byte]): Animation =
    if !matches(d) || d.length < 13 then fail("not a GIF")
    val width = u16(d, 6); val height = u16(d, 8)
    if width == 0 || height == 0 then fail("empty logical screen")
    val flags = d(10) & 0xff
    var p = 13
    var globalPalette: Array[Int] = Array.empty
    if (flags & 0x80) != 0 then
      val n = 1 << ((flags & 7) + 1)
      globalPalette = readPalette(d, p, n); p += n * 3
    var canvas = new Array[Byte](width * height * 4) // transparent black
    val frames = Vector.newBuilder[AnimationFrame]
    var loops = 0
    // graphic control extension state, applying to the next image
    var delay = 0; var transparent = -1; var disposal = 0
    var prevDisposal = 0; var prevRect = (0, 0, 0, 0); var saved: Array[Byte] = null
    var count = 0
    var done = false
    while !done && p < d.length do
      (d(p) & 0xff) match
        case 0x3B => done = true
        case 0x21 =>
          val label = d(p + 1) & 0xff
          p += 2
          label match
            case 0xF9 =>
              val f = d(p + 1) & 0xff
              disposal = (f >> 2) & 7; delay = u16(d, p + 2) * 10; transparent = if (f & 1) != 0 then d(p + 4) & 0xff else -1
              p += 1 + (d(p) & 0xff) + 1
            case 0xFF =>
              val sz = d(p) & 0xff
              val isNetscape = sz >= 11 && new String(d, p + 1, 11, "ISO-8859-1").startsWith("NETSCAPE") || new String(d, p + 1, 11, "ISO-8859-1").startsWith("ANIMEXTS")
              p += 1 + sz
              while p < d.length && (d(p) & 0xff) != 0 do
                val bl = d(p) & 0xff
                if isNetscape && bl >= 3 && (d(p + 1) & 0xff) == 1 then loops = u16(d, p + 2)
                p += 1 + bl
              p += 1
            case _ => p = skipBlocks(d, p)
        case 0x2C =>
          val ix = u16(d, p + 1); val iy = u16(d, p + 3); val iw = u16(d, p + 5); val ih = u16(d, p + 7)
          val lf = d(p + 9) & 0xff
          p += 10
          var palette = globalPalette
          if (lf & 0x80) != 0 then
            val n = 1 << ((lf & 7) + 1)
            palette = readPalette(d, p, n); p += n * 3
          val interlaced = (lf & 0x40) != 0
          val minCode = d(p) & 0xff; p += 1
          if minCode < 1 || minCode > 11 then fail("bad LZW code size")
          val (data, next) = collectBlocks(d, p); p = next
          val indices = lzwDecode(data, minCode, iw * ih)
          // dispose of the previous frame before drawing this one
          if count > 0 then prevDisposal match
            case 2 => clearRect(canvas, width, height, prevRect)
            case 3 => if saved != null then canvas = saved.clone()
            case _ => ()
          if disposal == 3 then saved = canvas.clone() else saved = null
          val rows = if interlaced then interlaceOrder(ih) else Array.range(0, ih)
          var r = 0
          while r < ih do
            val y = iy + rows(r)
            var x = 0
            while x < iw do
              val k = indices(r * iw + x)
              if k != transparent && ix + x < width && y < height && k < palette.length then
                val c = palette(k); val o = (y * width + ix + x) * 4
                canvas(o) = (c >> 16).toByte; canvas(o + 1) = (c >> 8).toByte; canvas(o + 2) = c.toByte; canvas(o + 3) = -1
              x += 1
            r += 1
          frames += AnimationFrame(Image(width, height, canvas.clone()), delay)
          count += 1
          prevDisposal = disposal; prevRect = (ix, iy, iw, ih)
          delay = 0; transparent = -1; disposal = 0
        case _ => done = true // trailing garbage
    val fs = frames.result()
    if fs.isEmpty then fail("no image data")
    Animation(width, height, fs, loops)

  private def readPalette(d: Array[Byte], o: Int, n: Int): Array[Int] =
    Array.tabulate(n)(i => if o + i * 3 + 2 < d.length then ((d(o + i * 3) & 0xff) << 16) | ((d(o + i * 3 + 1) & 0xff) << 8) | (d(o + i * 3 + 2) & 0xff) else 0)

  private def skipBlocks(d: Array[Byte], from: Int): Int =
    var p = from
    while p < d.length && (d(p) & 0xff) != 0 do p += 1 + (d(p) & 0xff)
    p + 1

  private def collectBlocks(d: Array[Byte], from: Int): (Array[Byte], Int) =
    val out = new ByteArrayOutputStream
    var p = from
    while p < d.length && (d(p) & 0xff) != 0 do
      val n = d(p) & 0xff
      out.write(d, p + 1, math.min(n, d.length - p - 1))
      p += 1 + n
    (out.toByteArray, p + 1)

  private def clearRect(canvas: Array[Byte], w: Int, h: Int, r: (Int, Int, Int, Int)): Unit =
    val (rx, ry, rw, rh) = r
    var y = ry
    while y < math.min(h, ry + rh) do
      var x = rx
      while x < math.min(w, rx + rw) do { val o = (y * w + x) * 4; canvas(o) = 0; canvas(o + 1) = 0; canvas(o + 2) = 0; canvas(o + 3) = 0; x += 1 }
      y += 1

  private def interlaceOrder(h: Int): Array[Int] =
    val order = ArrayBuffer.empty[Int]
    for (start, step) <- Seq((0, 8), (4, 8), (2, 4), (1, 2)) do
      var y = start
      while y < h do { order += y; y += step }
    order.toArray

  private def lzwDecode(data: Array[Byte], minCode: Int, pixels: Int): Array[Int] =
    val out = new Array[Int](pixels)
    val clear = 1 << minCode; val eoi = clear + 1
    val prefix = new Array[Int](4097); val suffix = new Array[Int](4097)
    var next = eoi + 1; var size = minCode + 1; var prev = -1
    var bitBuf = 0; var bitCnt = 0; var p = 0; var n = 0
    val stack = new Array[Int](4098)
    var i = 0
    while i < clear do { suffix(i) = i; prefix(i) = -1; i += 1 }
    /** Writes the string of `code` reversed onto the stack; returns its length. */
    def expand(code: Int): Int =
      var sp = 0; var c = code
      while c >= 0 && sp < 4097 do { stack(sp) = suffix(c); sp += 1; c = prefix(c) }
      sp
    var done = false
    while !done && n < pixels do
      while bitCnt < size && p < data.length do { bitBuf |= (data(p) & 0xff) << bitCnt; bitCnt += 8; p += 1 }
      if bitCnt < size then done = true
      else
        val code = bitBuf & ((1 << size) - 1)
        bitBuf >>>= size; bitCnt -= size
        if code == clear then { next = eoi + 1; size = minCode + 1; prev = -1 }
        else if code == eoi then done = true
        else if code < next && code != clear && code != eoi || (code == next && prev >= 0) then
          var len = 0
          var first = 0
          if code < next then { len = expand(code); first = stack(len - 1) }
          else
            // KwKwK: the previous string followed by its own first symbol
            val pl = expand(prev); first = stack(pl - 1)
            // shift to make room for the repeated symbol at the end of the output (stack holds reversed order)
            var k = pl
            while k > 0 do { stack(k) = stack(k - 1); k -= 1 }
            stack(0) = first; len = pl + 1
          var k = len - 1
          while k >= 0 && n < pixels do { out(n) = stack(k); n += 1; k -= 1 }
          if prev >= 0 && next < 4096 then
            prefix(next) = prev; suffix(next) = first; next += 1
            if next == (1 << size) && size < 12 then size += 1
          prev = code
        else done = true // corrupt stream
    out

  // ---- encoding ----

  def encode(img: Image): Array[Byte] = encodeAnimation(Animation(img.width, img.height, Vector(AnimationFrame(img, 0)), 0))

  /** Encodes frames of equal size as an animated GIF (a single frame gives a still image). */
  def encodeAnimation(anim: Animation): Array[Byte] =
    val out = new ByteArrayOutputStream
    def w16(v: Int): Unit = { out.write(v & 0xff); out.write((v >> 8) & 0xff) }
    out.write("GIF89a".getBytes("ISO-8859-1"))
    w16(anim.width); w16(anim.height)
    out.write(0x70); out.write(0); out.write(0) // no global table; 8-bit colour resolution
    if anim.frames.size > 1 then
      out.write(0x21); out.write(0xFF); out.write(11); out.write("NETSCAPE2.0".getBytes("ISO-8859-1"))
      out.write(3); out.write(1); w16(anim.loopCount); out.write(0)
    anim.frames.foreach { f =>
      val img = f.image
      require(img.width == anim.width && img.height == anim.height, "all frames must have the canvas size")
      val hasAlpha = (0 until img.width * img.height).exists(i => (img.rgba(i * 4 + 3) & 0xff) < 128)
      val maxColors = if hasAlpha then 255 else 256
      val (pal, idx0) = Quantizer.quantize(img.rgba, maxColors, i => (img.rgba(i * 4 + 3) & 0xff) < 128)
      val transparentIndex = if hasAlpha then pal.length else -1
      val idx = idx0.map(i => if i < 0 then transparentIndex else i)
      val entries = pal.length + (if hasAlpha then 1 else 0)
      var bits = 1
      while (1 << bits) < math.max(2, entries) do bits += 1
      // graphic control extension
      out.write(0x21); out.write(0xF9); out.write(4)
      out.write((if hasAlpha then 1 else 0) | (if hasAlpha then 2 << 2 else 1 << 2)); w16(math.max(0, f.delayMs / 10)); out.write(if hasAlpha then transparentIndex else 0); out.write(0)
      out.write(0x2C); w16(0); w16(0); w16(img.width); w16(img.height)
      out.write(0x80 | (bits - 1))
      var k = 0
      while k < (1 << bits) do
        val c = if k < pal.length then pal(k) else 0
        out.write((c >> 16) & 0xff); out.write((c >> 8) & 0xff); out.write(c & 0xff); k += 1
      val minCode = math.max(2, bits)
      out.write(minCode)
      val packed = lzwEncode(idx, minCode)
      var o = 0
      while o < packed.length do { val n = math.min(255, packed.length - o); out.write(n); out.write(packed, o, n); o += n }
      out.write(0)
    }
    out.write(0x3B)
    out.toByteArray

  private def lzwEncode(indices: Array[Int], minCode: Int): Array[Byte] =
    val out = new ByteArrayOutputStream
    var bitBuf = 0; var bitCnt = 0
    def emit(code: Int, size: Int): Unit =
      bitBuf |= code << bitCnt; bitCnt += size
      while bitCnt >= 8 do { out.write(bitBuf & 0xff); bitBuf >>>= 8; bitCnt -= 8 }
    val clear = 1 << minCode; val eoi = clear + 1
    var table = new java.util.HashMap[Int, Int]() // (prefixCode << 8 | symbol) -> code
    var next = eoi + 1; var size = minCode + 1
    emit(clear, size)
    var prefix = -1
    var i = 0
    while i < indices.length do
      val sym = indices(i)
      if prefix < 0 then prefix = sym
      else
        val key = (prefix << 8) | sym
        if table.containsKey(key) then prefix = table.get(key)
        else
          emit(prefix, size)
          if next < 4096 then
            table.put(key, next); next += 1
            if next > (1 << size) && size < 12 then size += 1
          else { emit(clear, size); table = new java.util.HashMap[Int, Int](); next = eoi + 1; size = minCode + 1 }
          prefix = sym
      i += 1
    if prefix >= 0 then emit(prefix, size)
    emit(eoi, size)
    if bitCnt > 0 then out.write(bitBuf & 0xff)
    out.toByteArray
