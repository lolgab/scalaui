package scalaui.font

import java.io.ByteArrayOutputStream

/** WOFF 2.0 (W3C Recommendation) unpacking: Brotli-decompresses the table stream and undoes the glyf/loca and hmtx
  * transforms, producing a plain sfnt that [[FontFace]] parses. Font collections (`ttcf` flavour) are not supported.
  */
private[font] object Woff2:
  private val KnownTags = Array(
    "cmap", "head", "hhea", "hmtx", "maxp", "name", "OS/2", "post", "cvt ", "fpgm", "glyf", "loca", "prep", "CFF ", "VORG",
    "EBDT", "EBLC", "gasp", "hdmx", "kern", "LTSH", "PCLT", "VDMX", "vhea", "vmtx", "BASE", "GDEF", "GPOS", "GSUB", "EBSC",
    "JSTF", "MATH", "CBDT", "CBLC", "COLR", "CPAL", "SVG ", "sbix", "acnt", "avar", "bdat", "bloc", "bsln", "cvar", "fdsc",
    "feat", "fmtx", "fvar", "gvar", "hsty", "just", "lcar", "mort", "morx", "opbd", "prop", "trak", "Zapf", "Silf", "Glat",
    "Gloc", "Feat", "Sill")

  private def bad(msg: String): Nothing = throw new IllegalArgumentException("invalid WOFF2: " + msg)
  private def u8(d: Array[Byte], o: Int): Int = d(o) & 0xff
  private def u16(d: Array[Byte], o: Int): Int = (u8(d, o) << 8) | u8(d, o + 1)
  private def i16(d: Array[Byte], o: Int): Int = (u16(d, o) << 16) >> 16
  private def u32(d: Array[Byte], o: Int): Int = (u16(d, o) << 16) | u16(d, o + 2)

  /** Sequential reader over a byte range. */
  private final class Cursor(val d: Array[Byte], var p: Int, val end: Int):
    def u8(): Int = { if p >= end then bad("stream underrun"); val v = d(p) & 0xff; p += 1; v }
    def u16(): Int = (u8() << 8) | u8()
    def i16(): Int = (u16() << 16) >> 16
    def u32(): Int = (u16() << 16) | u16()
    /** UIntBase128 */
    def base128(): Int =
      var acc = 0L; var i = 0
      while i < 5 do
        val b = u8()
        if i == 0 && b == 0x80 then bad("UIntBase128 leading zero")
        if (acc & 0xfe000000L) != 0 then bad("UIntBase128 overflow")
        acc = (acc << 7) | (b & 0x7f)
        if (b & 0x80) == 0 then return acc.toInt
        i += 1
      bad("UIntBase128 too long")
    /** 255UInt16 */
    def u255(): Int = u8() match
      case 253 => u16()
      case 255 => 253 + u8()
      case 254 => 506 + u8()
      case b => b
    def bytes(n: Int): Array[Byte] =
      if n < 0 || p + n > end then bad("stream underrun")
      val r = java.util.Arrays.copyOfRange(d, p, p + n); p += n; r

  private final class Entry(val tag: String, val origLength: Int, val transformLength: Int, val transformed: Boolean)

  def toSfnt(w: Array[Byte]): Array[Byte] =
    if w.length < 48 || new String(w, 0, 4, "ISO-8859-1") != "wOF2" then bad("bad signature")
    val flavor = u32(w, 4)
    if flavor == 0x74746366 then throw new UnsupportedOperationException("WOFF2 font collections are not supported")
    val numTables = u16(w, 12)
    val totalCompressed = u32(w, 20)
    val c = new Cursor(w, 48, w.length)
    val entries = (0 until numTables).map { _ =>
      val flags = c.u8()
      val tagIdx = flags & 0x3f; val version = flags >> 6
      val tag = if tagIdx == 63 then new String(c.bytes(4), "ISO-8859-1") else KnownTags(tagIdx)
      val orig = c.base128()
      val transformed = tag match
        case "glyf" | "loca" => version == 0
        case "hmtx" => version == 1
        case _ => version != 0
      val tlen = if transformed then c.base128() else orig
      new Entry(tag, orig, tlen, transformed)
    }
    val total = entries.map(_.transformLength.toLong).sum
    if total > Int.MaxValue / 2 then bad("table data too large")
    if c.p + totalCompressed > w.length || totalCompressed < 0 then bad("truncated compressed stream")
    val data = scalaui.brotli.Brotli.decompress(w, c.p, totalCompressed, total.toInt)
    if data.length < total then bad("decompressed stream too short")
    var o = 0
    val raw = scala.collection.mutable.LinkedHashMap.empty[String, Array[Byte]]
    entries.foreach { e => raw(e.tag) = java.util.Arrays.copyOfRange(data, o, o + e.transformLength); o += e.transformLength }
    val tables = scala.collection.mutable.LinkedHashMap.empty[String, Array[Byte]]
    entries.foreach { e => if !e.transformed then tables(e.tag) = raw(e.tag) }

    var xMins: Array[Int] = null
    if entries.exists(e => e.tag == "glyf" && e.transformed) then
      val g = reconstructGlyf(raw("glyf"))
      tables("glyf") = g.glyf; tables("loca") = g.loca; xMins = g.xMins
      tables.get("head").foreach(h => { h(50) = (g.indexFormat >> 8).toByte; h(51) = g.indexFormat.toByte })
    entries.find(e => e.tag == "hmtx" && e.transformed).foreach { _ =>
      if xMins == null then bad("transformed hmtx requires transformed glyf")
      val hhea = tables.getOrElse("hhea", bad("missing hhea")); val maxp = tables.getOrElse("maxp", bad("missing maxp"))
      tables("hmtx") = reconstructHmtx(raw("hmtx"), u16(hhea, 34), u16(maxp, 4), xMins)
    }
    buildSfnt(flavor, entries.map(_.tag).distinct.sortBy(identity), tables)

  private def buildSfnt(flavor: Int, tags: Seq[String], tables: scala.collection.Map[String, Array[Byte]]): Array[Byte] =
    val n = tags.size
    val out = new ByteArrayOutputStream()
    def w16(v: Int): Unit = { out.write(v >> 8); out.write(v) }
    def w32(v: Int): Unit = { w16(v >>> 16); w16(v & 0xffff) }
    w32(flavor); w16(n)
    var sr = 1; var es = 0; while sr * 2 <= n do { sr *= 2; es += 1 }
    w16(sr * 16); w16(es); w16(n * 16 - sr * 16)
    var offset = 12 + n * 16
    tags.foreach { t =>
      val b = tables(t)
      var sum = 0; var i = 0
      while i < b.length do
        var v = 0; var k = 0
        while k < 4 do { v = (v << 8) | (if i + k < b.length then b(i + k) & 0xff else 0); k += 1 }
        sum += v; i += 4
      out.write(t.getBytes("ISO-8859-1")); w32(sum); w32(offset); w32(b.length)
      offset += (b.length + 3) / 4 * 4
    }
    tags.foreach { t => val b = tables(t); out.write(b); for _ <- 0 until (4 - b.length % 4) % 4 do out.write(0) }
    out.toByteArray

  private final class Glyf(val glyf: Array[Byte], val loca: Array[Byte], val xMins: Array[Int], val indexFormat: Int)

  private def reconstructGlyf(t: Array[Byte]): Glyf =
    if t.length < 36 then bad("glyf transform too short")
    val optionFlags = u16(t, 2); val numGlyphs = u16(t, 4); val indexFormat = u16(t, 6)
    val sizes = (0 until 7).map(i => u32(t, 8 + i * 4))
    var p = 36
    val starts = sizes.map { s => val r = p; p += s; r }
    if p > t.length then bad("glyf streams exceed table")
    val nContourS = new Cursor(t, starts(0), starts(0) + sizes(0))
    val nPointsS = new Cursor(t, starts(1), starts(1) + sizes(1))
    val flagS = new Cursor(t, starts(2), starts(2) + sizes(2))
    val glyphS = new Cursor(t, starts(3), starts(3) + sizes(3))
    val compS = new Cursor(t, starts(4), starts(4) + sizes(4))
    val bboxBitmapLen = ((numGlyphs + 31) >> 5) << 2
    val bboxS = new Cursor(t, starts(5) + bboxBitmapLen, starts(5) + sizes(5))
    val bboxBitmap = starts(5)
    val instrS = new Cursor(t, starts(6), starts(6) + sizes(6))
    val overlapBitmap = if (optionFlags & 1) != 0 then starts(6) + sizes(6) else -1

    val out = new ByteArrayOutputStream()
    def w16(v: Int): Unit = { out.write(v >> 8); out.write(v) }
    val offsets = new Array[Int](numGlyphs + 1)
    val xMins = new Array[Int](numGlyphs)
    var g = 0
    while g < numGlyphs do
      offsets(g) = out.size()
      val nc = nContourS.i16()
      val hasBbox = (u8(t, bboxBitmap + (g >> 3)) & (0x80 >> (g & 7))) != 0
      if nc == 0 then
        if hasBbox then bad("empty glyph with bbox")
      else if nc > 0 then
        val endPts = new Array[Int](nc); var total = 0
        var k = 0
        while k < nc do { total += nPointsS.u255(); endPts(k) = total - 1; k += 1 }
        val xs = new Array[Int](total); val ys = new Array[Int](total); val on = new Array[Boolean](total)
        var x = 0; var y = 0
        k = 0
        while k < total do
          val f = flagS.u8()
          on(k) = (f & 0x80) == 0
          val fl = f & 0x7f
          def sgn(flag: Int, base: Int) = if (flag & 1) != 0 then base else -base
          var dx = 0; var dy = 0
          if fl < 10 then dy = sgn(fl, ((fl & 14) << 7) + glyphS.u8())
          else if fl < 20 then dx = sgn(fl, (((fl - 10) & 14) << 7) + glyphS.u8())
          else if fl < 84 then
            val b0 = fl - 20; val b1 = glyphS.u8()
            dx = sgn(fl, 1 + (b0 & 0x30) + (b1 >> 4)); dy = sgn(fl >> 1, 1 + ((b0 & 0x0c) << 2) + (b1 & 0x0f))
          else if fl < 120 then
            val b0 = fl - 84; val b1 = glyphS.u8(); val b2 = glyphS.u8()
            dx = sgn(fl, 1 + ((b0 / 12) << 8) + b1); dy = sgn(fl >> 1, 1 + (((b0 % 12) >> 2) << 8) + b2)
          else if fl < 124 then
            val b1 = glyphS.u8(); val b2 = glyphS.u8(); val b3 = glyphS.u8()
            dx = sgn(fl, (b1 << 4) + (b2 >> 4)); dy = sgn(fl >> 1, ((b2 & 0x0f) << 8) + b3)
          else
            val b1 = glyphS.u8(); val b2 = glyphS.u8(); val b3 = glyphS.u8(); val b4 = glyphS.u8()
            dx = sgn(fl, (b1 << 8) + b2); dy = sgn(fl >> 1, (b3 << 8) + b4)
          x += dx; y += dy; xs(k) = x; ys(k) = y
          k += 1
        val instrLen = glyphS.u255()
        val instr = instrS.bytes(instrLen)
        val (x0, y0, x1, y1) =
          if hasBbox then (bboxS.i16(), bboxS.i16(), bboxS.i16(), bboxS.i16())
          else if total == 0 then (0, 0, 0, 0)
          else (xs.min, ys.min, xs.max, ys.max)
        xMins(g) = x0
        w16(nc); w16(x0); w16(y0); w16(x1); w16(y1)
        endPts.foreach(w16)
        w16(instrLen); out.write(instr)
        val overlap = overlapBitmap >= 0 && (u8(t, overlapBitmap + (g >> 3)) & (0x80 >> (g & 7))) != 0
        val flags = new Array[Int](total)
        val xb = new ByteArrayOutputStream(); val yb = new ByteArrayOutputStream()
        var px = 0; var py = 0
        k = 0
        while k < total do
          var f = if on(k) then 1 else 0
          if k == 0 && overlap then f |= 0x40
          val dx = xs(k) - px; val dy = ys(k) - py
          if dx == 0 then f |= 0x10
          else if math.abs(dx) < 256 then { f |= 2 | (if dx > 0 then 0x10 else 0); xb.write(math.abs(dx)) }
          else { xb.write(dx >> 8); xb.write(dx) }
          if dy == 0 then f |= 0x20
          else if math.abs(dy) < 256 then { f |= 4 | (if dy > 0 then 0x20 else 0); yb.write(math.abs(dy)) }
          else { yb.write(dy >> 8); yb.write(dy) }
          flags(k) = f; px = xs(k); py = ys(k); k += 1
        flags.foreach(out.write(_))
        xb.writeTo(out); yb.writeTo(out)
      else
        if !hasBbox then bad("composite glyph without bbox")
        val x0 = bboxS.i16(); val y0 = bboxS.i16(); val x1 = bboxS.i16(); val y1 = bboxS.i16()
        xMins(g) = x0
        w16(-1); w16(x0); w16(y0); w16(x1); w16(y1)
        var more = true; var haveInstr = false
        while more do
          val flags = compS.u16()
          val start = compS.p - 2
          compS.u16() // glyph index
          var n = if (flags & 1) != 0 then 4 else 2
          if (flags & 8) != 0 then n += 2 else if (flags & 0x40) != 0 then n += 4 else if (flags & 0x80) != 0 then n += 8
          compS.bytes(n)
          out.write(t, start, compS.p - start)
          more = (flags & 0x20) != 0
          if (flags & 0x100) != 0 then haveInstr = true
        if haveInstr then
          val n = glyphS.u255()
          w16(n); out.write(instrS.bytes(n))
      while out.size() % 4 != 0 do out.write(0)
      g += 1
    offsets(numGlyphs) = out.size()
    val loca = new ByteArrayOutputStream()
    offsets.foreach { v =>
      if indexFormat == 0 then { loca.write((v / 2) >> 8); loca.write(v / 2) }
      else { loca.write(v >>> 24); loca.write(v >>> 16); loca.write(v >>> 8); loca.write(v) }
    }
    new Glyf(out.toByteArray, loca.toByteArray, xMins, indexFormat)

  private def reconstructHmtx(t: Array[Byte], numHMetrics: Int, numGlyphs: Int, xMins: Array[Int]): Array[Byte] =
    val c = new Cursor(t, 0, t.length)
    val flags = c.u8()
    val lsbOmitted = (flags & 1) != 0; val monoOmitted = (flags & 2) != 0
    if numHMetrics > numGlyphs || numHMetrics < 1 then bad("bad numberOfHMetrics")
    val adv = Array.fill(numHMetrics)(c.u16())
    val lsb = new Array[Int](numGlyphs)
    for i <- 0 until numHMetrics do lsb(i) = if lsbOmitted then xMins(i) else c.i16()
    for i <- numHMetrics until numGlyphs do lsb(i) = if monoOmitted then xMins(i) else c.i16()
    val out = new ByteArrayOutputStream()
    def w16(v: Int): Unit = { out.write(v >> 8); out.write(v) }
    for i <- 0 until numGlyphs do { if i < numHMetrics then w16(adv(i)); w16(lsb(i)) }
    out.toByteArray
