package scalaui.font

import scalaui.raster.Path
import scala.collection.mutable.ArrayBuffer

/** Compact Font Format (CFF1, as found in OpenType `CFF ` tables) outline reader with a Type 2 charstring interpreter. */
private[font] final class Cff(d: Array[Byte], base: Int, length: Int):
  private def u8(o: Int) = d(o) & 0xff
  private def u16(o: Int) = (u8(o) << 8) | u8(o + 1)
  private def uN(o: Int, n: Int): Int = { var v = 0; var i = 0; while i < n do { v = (v << 8) | u8(o + i); i += 1 }; v }

  /** An INDEX: offsets of `count` objects; returns (object start offsets, end offset of the structure). */
  private final class Index(val count: Int, starts: Array[Int], val end: Int):
    def start(i: Int): Int = starts(i)
    def length(i: Int): Int = starts(i + 1) - starts(i)

  private def readIndex(o: Int): Index =
    val count = u16(o)
    if count == 0 then new Index(0, Array(0), o + 2)
    else
      val offSize = u8(o + 2)
      val offs = Array.tabulate(count + 1)(i => uN(o + 3 + i * offSize, offSize))
      val dataBase = o + 3 + (count + 1) * offSize - 1
      new Index(count, offs.map(_ + dataBase), dataBase + offs(count))

  private val hdrSize = u8(base + 2)
  private val nameIdx = readIndex(base + hdrSize)
  private val topIdx = readIndex(nameIdx.end)
  private val stringIdx = readIndex(topIdx.end)
  private val gsubrIdx = readIndex(stringIdx.end)

  /** Parses a DICT into operator -> operands. Two-byte operators are keyed as 1200 + second byte. */
  private def parseDict(o: Int, len: Int): Map[Int, Vector[Double]] =
    val out = scala.collection.mutable.Map.empty[Int, Vector[Double]]
    var stack = Vector.empty[Double]
    var p = o; val end = o + len
    while p < end do
      val b = u8(p)
      if b <= 21 then
        val op = if b == 12 then { p += 1; 1200 + u8(p) } else b
        out(op) = stack; stack = Vector.empty; p += 1
      else if b == 28 then { stack :+= ((u8(p + 1) << 8) | u8(p + 2)).toShort.toDouble; p += 3 }
      else if b == 29 then { stack :+= ((u8(p + 1) << 24) | (u8(p + 2) << 16) | (u8(p + 3) << 8) | u8(p + 4)).toDouble; p += 5 }
      else if b == 30 then
        // real number: nibbles
        val sb = new StringBuilder; var done = false; p += 1
        while !done do
          val byte = u8(p); p += 1
          for nib <- Seq(byte >> 4, byte & 15) if !done do
            nib match
              case n if n <= 9 => sb += ('0' + n).toChar
              case 10 => sb += '.'
              case 11 => sb += 'E'
              case 12 => sb ++= "E-"
              case 14 => sb += '-'
              case 15 => done = true
              case _ => ()
        stack :+= sb.toString.toDoubleOption.getOrElse(0.0)
      else if b >= 32 && b <= 246 then { stack :+= (b - 139).toDouble; p += 1 }
      else if b >= 247 && b <= 250 then { stack :+= ((b - 247) * 256 + u8(p + 1) + 108).toDouble; p += 2 }
      else if b >= 251 && b <= 254 then { stack :+= (-(b - 251) * 256 - u8(p + 1) - 108).toDouble; p += 2 }
      else p += 1
    out.toMap

  private val top = parseDict(topIdx.start(0), topIdx.length(0))
  private val charStrings = readIndex(base + top(17).head.toInt)
  val numGlyphs: Int = charStrings.count
  private val isCid = top.contains(1230)

  private final class Priv(val subrs: Index, val defaultWidth: Double, val nominalWidth: Double)
  private def readPrivate(size: Int, offset: Int): Priv =
    val pd = parseDict(base + offset, size)
    val subrs = pd.get(19).map(v => readIndex(base + offset + v.head.toInt)).getOrElse(new Index(0, Array(0), 0))
    new Priv(subrs, pd.get(20).map(_.head).getOrElse(0.0), pd.get(21).map(_.head).getOrElse(0.0))

  private val privates: Array[Priv] =
    if isCid then
      val fdArray = readIndex(base + top(1236).head.toInt)
      Array.tabulate(fdArray.count) { i =>
        val fd = parseDict(fdArray.start(i), fdArray.length(i))
        val pv = fd(18); readPrivate(pv(0).toInt, pv(1).toInt)
      }
    else
      val pv = top.getOrElse(18, Vector(0.0, 0.0)); Array(readPrivate(pv(0).toInt, pv(1).toInt))

  private val fdSelect: Int => Int =
    if !isCid then _ => 0
    else
      val o = base + top(1237).head.toInt
      u8(o) match
        case 0 => g => u8(o + 1 + g)
        case 3 =>
          val n = u16(o + 1)
          g =>
            var i = 0
            while i + 1 < n && u16(o + 3 + (i + 1) * 3) <= g do i += 1
            u8(o + 3 + i * 3 + 2)
        case _ => _ => 0

  private def bias(n: Int) = if n < 1240 then 107 else if n < 33900 then 1131 else 32768

  /** Interprets glyph `g`'s charstring into a path (font units, y up). */
  def outline(g: Int): Path =
    if g < 0 || g >= numGlyphs then return Path.builder.build
    val priv = privates(math.min(fdSelect(g), privates.length - 1))
    val pb = Path.builder
    var x = 0.0; var y = 0.0
    var open = false
    val stack = ArrayBuffer.empty[Double]
    var stems = 0
    var haveWidth = false
    var finished = false

    def moveTo(dx: Double, dy: Double): Unit = { if open then pb.close(); x += dx; y += dy; pb.moveTo(x, y); open = true }
    def lineTo(dx: Double, dy: Double): Unit = { x += dx; y += dy; pb.lineTo(x, y) }
    def curveTo(a: Double, b: Double, c: Double, dd: Double, e: Double, f: Double): Unit =
      val x1 = x + a; val y1 = y + b; val x2 = x1 + c; val y2 = y1 + dd; x = x2 + e; y = y2 + f
      pb.cubicTo(x1, y1, x2, y2, x, y)
    def takeWidth(expectedArgs: Int, even: Boolean = false): Unit =
      if !haveWidth then
        haveWidth = true
        val extra = if even then stack.length % 2 == 1 else stack.length > expectedArgs
        if extra then stack.remove(0)

    def run(o: Int, len: Int, depth: Int): Unit =
      var p = o; val end = o + len
      while p < end && !finished do
        val b = u8(p); p += 1
        if b >= 32 || b == 28 then
          if b == 28 then { stack += ((u8(p) << 8) | u8(p + 1)).toShort.toDouble; p += 2 }
          else if b <= 246 then stack += (b - 139).toDouble
          else if b <= 250 then { stack += ((b - 247) * 256 + u8(p) + 108).toDouble; p += 1 }
          else if b <= 254 then { stack += (-(b - 251) * 256 - u8(p) - 108).toDouble; p += 1 }
          else { stack += ((u8(p) << 24) | (u8(p + 1) << 16) | (u8(p + 2) << 8) | u8(p + 3)) / 65536.0; p += 4 }
        else b match
          case 1 | 3 | 18 | 23 => takeWidth(0, even = true); stems += stack.length / 2; stack.clear()
          case 19 | 20 =>
            takeWidth(0, even = true); stems += stack.length / 2; stack.clear()
            p += (stems + 7) / 8
          case 21 => takeWidth(2); moveTo(stack(0), stack(1)); stack.clear()
          case 22 => takeWidth(1); moveTo(stack(0), 0); stack.clear()
          case 4 => takeWidth(1); moveTo(0, stack(0)); stack.clear()
          case 5 => var i = 0; while i + 1 < stack.length do { lineTo(stack(i), stack(i + 1)); i += 2 }; stack.clear()
          case 6 | 7 =>
            var horiz = b == 6; var i = 0
            while i < stack.length do { if horiz then lineTo(stack(i), 0) else lineTo(0, stack(i)); horiz = !horiz; i += 1 }
            stack.clear()
          case 8 => var i = 0; while i + 5 < stack.length do { curveTo(stack(i), stack(i + 1), stack(i + 2), stack(i + 3), stack(i + 4), stack(i + 5)); i += 6 }; stack.clear()
          case 24 =>
            var i = 0; while i + 7 < stack.length do { curveTo(stack(i), stack(i + 1), stack(i + 2), stack(i + 3), stack(i + 4), stack(i + 5)); i += 6 }
            lineTo(stack(i), stack(i + 1)); stack.clear()
          case 25 =>
            var i = 0; while i + 7 < stack.length do { lineTo(stack(i), stack(i + 1)); i += 2 }
            curveTo(stack(i), stack(i + 1), stack(i + 2), stack(i + 3), stack(i + 4), stack(i + 5)); stack.clear()
          case 26 => // vvcurveto
            var i = 0; var dx1 = 0.0
            if stack.length % 4 == 1 then { dx1 = stack(0); i = 1 }
            while i + 3 < stack.length do { curveTo(dx1, stack(i), stack(i + 1), stack(i + 2), 0, stack(i + 3)); dx1 = 0; i += 4 }
            stack.clear()
          case 27 => // hhcurveto
            var i = 0; var dy1 = 0.0
            if stack.length % 4 == 1 then { dy1 = stack(0); i = 1 }
            while i + 3 < stack.length do { curveTo(stack(i), dy1, stack(i + 1), stack(i + 2), stack(i + 3), 0); dy1 = 0; i += 4 }
            stack.clear()
          case 30 | 31 => // vhcurveto / hvcurveto
            var horiz = b == 31; var i = 0
            while i + 3 < stack.length do
              val last = stack.length - i == 5
              if horiz then curveTo(stack(i), 0, stack(i + 1), stack(i + 2), if last then stack(i + 4) else 0, stack(i + 3))
              else curveTo(0, stack(i), stack(i + 1), stack(i + 2), stack(i + 3), if last then stack(i + 4) else 0)
              horiz = !horiz; i += 4
            stack.clear()
          case 10 => // callsubr
            val idx = stack.remove(stack.length - 1).toInt + bias(priv.subrs.count)
            if depth < 10 && idx >= 0 && idx < priv.subrs.count then run(priv.subrs.start(idx), priv.subrs.length(idx), depth + 1)
          case 29 => // callgsubr
            val idx = stack.remove(stack.length - 1).toInt + bias(gsubrIdx.count)
            if depth < 10 && idx >= 0 && idx < gsubrIdx.count then run(gsubrIdx.start(idx), gsubrIdx.length(idx), depth + 1)
          case 11 => p = end // return
          case 14 => takeWidth(0, even = false); finished = true
          case 12 =>
            val b2 = u8(p); p += 1
            b2 match
              case 35 => // flex
                curveTo(stack(0), stack(1), stack(2), stack(3), stack(4), stack(5)); curveTo(stack(6), stack(7), stack(8), stack(9), stack(10), stack(11)); stack.clear()
              case 34 => // hflex
                curveTo(stack(0), 0, stack(1), stack(2), stack(3), 0); curveTo(stack(4), 0, stack(5), -stack(2), stack(6), 0); stack.clear()
              case 36 => // hflex1
                curveTo(stack(0), stack(1), stack(2), stack(3), stack(4), 0); curveTo(stack(5), 0, stack(6), stack(7), stack(8), -(stack(1) + stack(3) + stack(7))); stack.clear()
              case 37 => // flex1
                val dx = stack(0) + stack(2) + stack(4) + stack(6) + stack(8); val dy = stack(1) + stack(3) + stack(5) + stack(7) + stack(9)
                curveTo(stack(0), stack(1), stack(2), stack(3), stack(4), stack(5))
                if math.abs(dx) > math.abs(dy) then curveTo(stack(6), stack(7), stack(8), stack(9), stack(10), -dy) else curveTo(stack(6), stack(7), stack(8), stack(9), -dx, stack(10))
                stack.clear()
              case _ => stack.clear()
          case _ => stack.clear()
    run(charStrings.start(g), charStrings.length(g), 0)
    if open then pb.close()
    pb.build
