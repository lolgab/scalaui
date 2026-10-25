package scalaui.pdf

import scalaui.font.FontFace
import java.io.ByteArrayOutputStream
import scala.collection.mutable

/** Builds the OpenType file embedded for CFF-flavoured fonts: the original CFF with the charstrings of unused glyphs replaced by `endchar`
  * (glyph ids stay valid, so CID = GID), plus the metric tables viewers need. Only the CharStrings and FDArray INDEXes are moved; the other
  * CFF structures keep their layout and have their absolute offsets shifted.
  */
private[pdf] object CffFont:
  private final case class Entry(op: Int, operands: Vector[Array[Byte]])

  private def u8(d: Array[Byte], o: Int) = d(o) & 0xff
  private def u16(d: Array[Byte], o: Int) = (u8(d, o) << 8) | u8(d, o + 1)

  /** Parsed INDEX: element byte ranges and the offset just past the structure. */
  private final case class Index(items: Vector[(Int, Int)], end: Int)
  private def readIndex(d: Array[Byte], at: Int): Index =
    val count = u16(d, at)
    if count == 0 then Index(Vector.empty, at + 2)
    else
      val offSize = u8(d, at + 2)
      def off(i: Int): Int = (0 until offSize).foldLeft(0)((a, k) => (a << 8) | u8(d, at + 3 + i * offSize + k))
      val base = at + 3 + (count + 1) * offSize - 1
      Index((0 until count).map(i => (base + off(i), base + off(i + 1))).toVector, base + off(count))

  private def writeIndex(items: Seq[Array[Byte]]): Array[Byte] =
    val out = new ByteArrayOutputStream
    out.write(items.size >> 8); out.write(items.size)
    if items.nonEmpty then
      val total = items.map(_.length).sum + 1
      val offSize = if total < 0x100 then 1 else if total < 0x10000 then 2 else if total < 0x1000000 then 3 else 4
      out.write(offSize)
      var o = 1
      def wr(v: Int): Unit = for k <- offSize - 1 to 0 by -1 do out.write(v >> (8 * k))
      wr(o); items.foreach { it => o += it.length; wr(o) }
      items.foreach(out.write)
    out.toByteArray

  private def parseDict(d: Array[Byte], from: Int, to: Int): Vector[Entry] =
    val out = Vector.newBuilder[Entry]; var ops = Vector.empty[Array[Byte]]; var i = from
    while i < to do
      val b = u8(d, i)
      if b <= 21 then
        val op = if b == 12 then { i += 1; 1200 + u8(d, i) } else b
        i += 1; out += Entry(op, ops); ops = Vector.empty
      else
        val len = b match
          case 28 => 3
          case 29 => 5
          case 30 => var j = i + 1; var done = false; while !done do { val x = u8(d, j); j += 1; if (x & 0x0f) == 0x0f || (x >> 4) == 0x0f then done = true }; j - i
          case _ if b >= 32 && b <= 246 => 1
          case _ => 2
        ops :+= java.util.Arrays.copyOfRange(d, i, i + len); i += len
    out.result()

  private def intOf(b: Array[Byte]): Int =
    val b0 = b(0) & 0xff
    if b0 >= 32 && b0 <= 246 then b0 - 139
    else if b0 >= 247 && b0 <= 250 then (b0 - 247) * 256 + (b(1) & 0xff) + 108
    else if b0 >= 251 && b0 <= 254 then -(b0 - 251) * 256 - (b(1) & 0xff) - 108
    else if b0 == 28 then (((b(1) & 0xff) << 8) | (b(2) & 0xff)).toShort
    else (b(1) << 24) | ((b(2) & 0xff) << 16) | ((b(3) & 0xff) << 8) | (b(4) & 0xff)

  private def int5(v: Int): Array[Byte] = Array(29.toByte, (v >> 24).toByte, (v >> 16).toByte, (v >> 8).toByte, v.toByte)

  private def encodeDict(es: Seq[Entry]): Array[Byte] =
    val out = new ByteArrayOutputStream
    es.foreach { e => e.operands.foreach(out.write); if e.op >= 1200 then { out.write(12); out.write(e.op - 1200) } else out.write(e.op) }
    out.toByteArray

  def build(face: FontFace, glyphs: Set[Int]): Array[Byte] =
    val (cffStart, cffLen) = face.tableRange("CFF ")
    val (data, _) = face.rawTable("CFF ").get
    val cff = java.util.Arrays.copyOfRange(data, cffStart, cffStart + cffLen)
    val subset = try rewrite(cff, glyphs + 0) catch case _: Exception => cff
    def tbl(tag: String): Option[(String, Array[Byte])] =
      if face.hasTable(tag) then { val (o, l) = face.tableRange(tag); Some(tag -> java.util.Arrays.copyOfRange(data, o, o + l)) } else None
    val tables = Seq(Some("CFF " -> subset), tbl("head"), tbl("hhea"), tbl("hmtx"), tbl("maxp"), tbl("OS/2"), tbl("post"), tbl("name"), tbl("cmap")).flatten
    Sfnt.write(0x4F54544F, tables)

  private def rewrite(cff: Array[Byte], keep: Set[Int]): Array[Byte] =
    val hdrSize = u8(cff, 2)
    val nameIdx = readIndex(cff, hdrSize)
    val topIdx = readIndex(cff, nameIdx.end)
    val strIdx = readIndex(cff, topIdx.end)
    val gsubrIdx = readIndex(cff, strIdx.end)
    val restStart = gsubrIdx.end
    val (ts, te) = topIdx.items.head
    val top = parseDict(cff, ts, te)
    val offsetOps = Set(15, 16, 17, 18, 1236, 1237)
    // new top dict size: every offset-bearing entry gets 5-byte operands
    def fixedSize(e: Entry): Int = e.op match
      case 18 => e.operands.head.length + 5 + (1)
      case 15 | 16 | 17 | 1236 | 1237 => 5 + (if e.op >= 1200 then 2 else 1)
      case _ => e.operands.map(_.length).sum + (if e.op >= 1200 then 2 else 1)
    val newTopLen = top.map(fixedSize).sum
    val newTopIndexLen = writeIndex(Seq(new Array[Byte](newTopLen))).length
    val oldTopIndexLen = topIdx.end - nameIdx.end
    val delta = newTopIndexLen - oldTopIndexLen
    val charStringsOff = top.find(_.op == 17).map(e => intOf(e.operands.head)).get
    val csIdx = readIndex(cff, charStringsOff)
    val isCid = top.exists(_.op == 1230)
    val newCs = writeIndex(csIdx.items.zipWithIndex.map { (r, g) => if keep.contains(g) then java.util.Arrays.copyOfRange(cff, r._1, r._2) else Array(0x0e.toByte) })
    val fdArrayOff = top.find(_.op == 1236).map(e => intOf(e.operands.head))
    // layout: header, name, top, strings, gsubrs, rest, charstrings, fdarray
    val restLen = cff.length - restStart
    val csOffNew = restStart + delta + restLen
    val charsetOffNew = csOffNew + newCs.length
    // CID-keyed fonts get an identity charset (CID = GID) so every viewer agrees on how character ids map to glyphs, whatever the original numbering
    val newCharset: Option[Array[Byte]] =
      if isCid && csIdx.items.size > 1 then { val left = csIdx.items.size - 2; Some(Array(2.toByte, 0.toByte, 1.toByte, (left >> 8).toByte, left.toByte)) } else None
    val fdOffNew = charsetOffNew + newCharset.map(_.length).getOrElse(0)
    val newFd: Option[Array[Byte]] = fdArrayOff.map { fo =>
      val fdIdx = readIndex(cff, fo)
      writeIndex(fdIdx.items.map { (a, b) =>
        encodeDict(parseDict(cff, a, b).map { e =>
          if e.op == 18 then Entry(18, Vector(e.operands.head, int5(intOf(e.operands(1)) + delta))) else e
        })
      })
    }
    def shifted(e: Entry): Entry = e.op match
      case 15 => val v = intOf(e.operands.head); Entry(15, Vector(if newCharset.isDefined then int5(charsetOffNew) else if v > 2 then int5(v + delta) else int5(v)))
      case 16 => val v = intOf(e.operands.head); Entry(16, Vector(if v > 1 then int5(v + delta) else int5(v)))
      case 17 => Entry(17, Vector(int5(csOffNew)))
      case 18 => Entry(18, Vector(e.operands.head, int5(intOf(e.operands(1)) + delta)))
      case 1236 => Entry(1236, Vector(int5(fdOffNew)))
      case 1237 => Entry(1237, Vector(int5(intOf(e.operands.head) + delta)))
      case _ => e
    val newTop = encodeDict(top.map(shifted))
    require(newTop.length == newTopLen)
    val out = new ByteArrayOutputStream
    out.write(cff, 0, nameIdx.end)
    out.write(writeIndex(Seq(newTop)))
    out.write(cff, topIdx.end, restStart - topIdx.end)
    out.write(cff, restStart, restLen)
    out.write(newCs)
    newCharset.foreach(out.write)
    newFd.foreach(out.write)
    val _ = offsetOps
    out.toByteArray
