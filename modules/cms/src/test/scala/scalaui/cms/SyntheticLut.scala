package scalaui.cms

import java.io.ByteArrayOutputStream

/** Builds RGB -> Lab LUT profiles by sampling a matrix profile (lut8, lut16 and mAB with an 8-bit CLUT). */
object SyntheticLut:
  def rgbToLab(ref: Profile, kind: String): Array[Byte] =
    val grid = 33
    val tag = new ByteArrayOutputStream()
    def u8(v: Int) = tag.write(v)
    def u16(v: Int) = { u8(v >> 8); u8(v) }
    def u32(v: Int) = { u16(v >>> 16); u16(v & 0xffff) }
    def sig(s: String) = s.foreach(c => u8(c))
    def sample(r: Int, g: Int, b: Int): (Double, Double, Double) =
      val (x, y, z) = ref.toXyz(Array(r / (grid - 1.0), g / (grid - 1.0), b / (grid - 1.0)))
      Adaptation.xyzToLab(x, y, z)
    def enc(l: Double, a: Double, bb: Double): Array[Int] = kind match
      case "mft2" => Array(l / 100 * 65280, (a + 128) / 255 * 65280, (bb + 128) / 255 * 65280).map(v => math.round(math.max(0, math.min(65535, v))).toInt)
      case "mft1" => Array(l / 100 * 255, a + 128, bb + 128).map(v => math.round(math.max(0, math.min(255, v))).toInt)
      case _ => Array(l / 100 * 65535, (a + 128) / 255 * 65535, (bb + 128) / 255 * 65535).map(v => math.round(math.max(0, math.min(65535, v))).toInt)
    kind match
      case "mAB " =>
        sig("mAB "); u32(0); u8(3); u8(3); u16(0)
        val bOff = 32; val clutOff = bOff + 3 * 12 // three identity curv (12 bytes each)
        u32(bOff); u32(0); u32(0); u32(clutOff); u32(0)
        for _ <- 0 until 3 do { sig("curv"); u32(0); u32(0) }
        for i <- 0 until 16 do u8(if i < 3 then grid else 0)
        u8(2); u8(0); u8(0); u8(0)
      case _ =>
        sig(kind); u32(0); u8(3); u8(3); u8(grid); u8(0)
        for v <- Mat3.identity do u32(math.round(v * 65536).toInt)
        if kind == "mft2" then { u16(2); u16(2) }
        val wide = kind == "mft2"
        for _ <- 0 until 3 do if wide then { u16(0); u16(65535) } else (0 until 256).foreach(u8)
        // CLUT written below
    for r <- 0 until grid; g <- 0 until grid; b <- 0 until grid do
      val (l, a, bb) = sample(r, g, b)
      for v <- enc(l, a, bb) do if kind == "mft1" then u8(v) else u16(v)
    if kind != "mAB " then
      for _ <- 0 until 3 do if kind == "mft2" then { u16(0); u16(65535) } else (0 until 256).foreach(u8)
    val data = tag.toByteArray
    // wrap in a profile reusing the sRGB header, with just desc/wtpt/A2B0
    val header = java.util.Arrays.copyOf(ref.bytes, 128)
    "Lab ".zipWithIndex.foreach((c, i) => header(20 + i) = c.toByte)
    val padded = java.util.Arrays.copyOf(data, (data.length + 3) & ~3)
    val tableLen = 4 + 12
    val total = 128 + tableLen + padded.length
    val out = new ByteArrayOutputStream()
    out.write(header)
    def w32(v: Int) = { out.write(v >>> 24); out.write(v >>> 16); out.write(v >>> 8); out.write(v) }
    w32(1); out.write("A2B0".getBytes("ISO-8859-1")); w32(128 + tableLen); w32(data.length)
    out.write(padded)
    val res = out.toByteArray
    res(0) = (total >>> 24).toByte; res(1) = (total >>> 16).toByte; res(2) = (total >>> 8).toByte; res(3) = total.toByte
    res
