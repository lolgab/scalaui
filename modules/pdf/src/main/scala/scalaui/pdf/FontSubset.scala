package scalaui.pdf

import scalaui.font.FontFace
import java.io.ByteArrayOutputStream

/** Builds a minimal sfnt (head, hhea, maxp, hmtx, loca, glyf) keeping only the outlines of `glyphs` (and the
  * components of composites), with glyph ids unchanged so a PDF `CIDToGIDMap /Identity` keeps working.
  */
private[pdf] object FontSubset:
  def build(font: FontFace, glyphs: Set[Int]): Array[Byte] =
    val (data, _) = font.rawTable("head").get
    def tableOf(tag: String): (Int, Int) = font.tableRange(tag)
    def u16(o: Int) = ((data(o) & 0xff) << 8) | (data(o + 1) & 0xff)
    def u32(o: Int) = (u16(o) << 16) | u16(o + 2)
    val (headO, headL) = tableOf("head")
    val longLoca = ((u16(headO + 50) << 16) >> 16) != 0
    val (locaO, _) = tableOf("loca"); val (glyfO, _) = tableOf("glyf")
    val numGlyphs = font.numGlyphs
    def range(g: Int): (Int, Int) =
      if g < 0 || g >= numGlyphs then (0, 0)
      else if longLoca then (u32(locaO + g * 4), u32(locaO + g * 4 + 4))
      else (u16(locaO + g * 2) * 2, u16(locaO + g * 2 + 2) * 2)

    // closure over composite components
    val keep = scala.collection.mutable.TreeSet[Int](0)
    val work = scala.collection.mutable.Stack.from(glyphs)
    while work.nonEmpty do
      val g = work.pop()
      if g >= 0 && g < numGlyphs && keep.add(g) then
        val (s, e) = range(g)
        if e > s && ((u16(glyfO + s) << 16) >> 16) < 0 then
          var p = glyfO + s + 10; var more = true
          while more do
            val flags = u16(p); work.push(u16(p + 2)); p += 4
            p += (if (flags & 1) != 0 then 4 else 2)
            p += (if (flags & 8) != 0 then 2 else if (flags & 0x40) != 0 then 4 else if (flags & 0x80) != 0 then 8 else 0)
            more = (flags & 0x20) != 0

    val glyf = new ByteArrayOutputStream()
    val loca = new ByteArrayOutputStream()
    def w32(o: ByteArrayOutputStream, v: Int): Unit = { o.write(v >>> 24); o.write(v >>> 16); o.write(v >>> 8); o.write(v) }
    for g <- 0 to numGlyphs do
      w32(loca, glyf.size())
      if g < numGlyphs && keep.contains(g) then
        val (s, e) = range(g)
        if e > s then
          glyf.write(data, glyfO + s, e - s)
          while glyf.size() % 4 != 0 do glyf.write(0)

    val (hheaO, hheaL) = tableOf("hhea"); val (maxpO, maxpL) = tableOf("maxp"); val (hmtxO, hmtxL) = tableOf("hmtx")
    val head = java.util.Arrays.copyOfRange(data, headO, headO + headL)
    head(8) = 0; head(9) = 0; head(10) = 0; head(11) = 0 // checkSumAdjustment
    head(50) = 0; head(51) = 1 // long loca
    val tables = Seq(
      "glyf" -> glyf.toByteArray, "head" -> head,
      "hhea" -> java.util.Arrays.copyOfRange(data, hheaO, hheaO + hheaL),
      "hmtx" -> java.util.Arrays.copyOfRange(data, hmtxO, hmtxO + hmtxL),
      "loca" -> loca.toByteArray,
      "maxp" -> java.util.Arrays.copyOfRange(data, maxpO, maxpO + maxpL)
    )
    Sfnt.write(0x00010000, tables)

/** Assembles an sfnt container from tables (sorted by tag, checksummed and padded). */
private[pdf] object Sfnt:
  def write(version: Int, tables0: Seq[(String, Array[Byte])]): Array[Byte] =
    val tables = tables0.sortBy(_._1)
    val out = new ByteArrayOutputStream()
    def w32(o: ByteArrayOutputStream, v: Int): Unit = { o.write(v >>> 24); o.write(v >>> 16); o.write(v >>> 8); o.write(v) }
    val n = tables.size
    w32(out, version)
    out.write(n >> 8); out.write(n)
    var sr = 1; var es = 0
    while sr * 2 <= n do { sr *= 2; es += 1 }
    out.write((sr * 16) >> 8); out.write(sr * 16); out.write(es >> 8); out.write(es)
    out.write((n * 16 - sr * 16) >> 8); out.write(n * 16 - sr * 16)
    var offset = 12 + n * 16
    val body = new ByteArrayOutputStream()
    tables.foreach { (tag, bytes) =>
      out.write(tag.getBytes("ISO-8859-1"))
      var sum = 0L; var i = 0
      while i < bytes.length do
        var word = 0L
        for k <- 0 until 4 do word = (word << 8) | (if i + k < bytes.length then bytes(i + k) & 0xff else 0)
        sum += word; i += 4
      w32(out, (sum & 0xffffffffL).toInt); w32(out, offset); w32(out, bytes.length)
      body.write(bytes)
      val pad = (4 - bytes.length % 4) % 4
      for _ <- 0 until pad do body.write(0)
      offset += bytes.length + pad
    }
    out.write(body.toByteArray)
    out.toByteArray
