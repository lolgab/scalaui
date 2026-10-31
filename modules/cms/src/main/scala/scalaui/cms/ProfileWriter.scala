package scalaui.cms

import java.io.ByteArrayOutputStream
import Adaptation.{D50, XYZ}

/** Serialises matrix/TRC (RGB) and gray TRC profiles. */
object ProfileWriter:
  private final class Buf:
    val out = new ByteArrayOutputStream()
    def u8(v: Int): Unit = out.write(v)
    def u16(v: Int): Unit = { u8(v >> 8); u8(v) }
    def u32(v: Long): Unit = { u16((v >> 16).toInt & 0xffff); u16(v.toInt & 0xffff) }
    def s15(v: Double): Unit = u32(math.round(v * 65536.0))
    def sig(s: String): Unit = s.foreach(c => u8(c))
    def zeros(n: Int): Unit = (0 until n).foreach(_ => u8(0))
    def bytes: Array[Byte] = out.toByteArray
    def size: Int = out.size

  private def curveBytes(c: Curve): Array[Byte] =
    val b = new Buf
    c match
      case Curve.Identity => b.sig("curv"); b.zeros(4); b.u32(0)
      case Curve.Gamma(g) => b.sig("curv"); b.zeros(4); b.u32(1); b.u16(math.round(g * 256).toInt)
      case Curve.Table(t) =>
        b.sig("curv"); b.zeros(4); b.u32(t.length)
        t.foreach(v => b.u16(math.round(math.max(0f, math.min(1f, v)) * 65535f)))
      case Curve.Parametric(tpe, p) =>
        b.sig("para"); b.zeros(4); b.u16(tpe); b.u16(0); p.foreach(b.s15)
    b.bytes

  private def xyzBytes(v: XYZ): Array[Byte] =
    val b = new Buf; b.sig("XYZ "); b.zeros(4); b.s15(v._1); b.s15(v._2); b.s15(v._3); b.bytes

  private def textBytes(s: String, v4: Boolean, ascii: Boolean): Array[Byte] =
    val b = new Buf
    if v4 then
      val u = s.getBytes("UTF-16BE")
      b.sig("mluc"); b.zeros(4); b.u32(1); b.u32(12); b.sig("enUS"); b.u32(u.length); b.u32(28); u.foreach(x => b.u8(x))
    else if ascii then // v2 textDescriptionType
      val a = s.getBytes("ISO-8859-1")
      b.sig("desc"); b.zeros(4); b.u32(a.length + 1); a.foreach(x => b.u8(x)); b.u8(0)
      b.u32(0); b.u32(0); b.u16(0); b.u8(0); b.zeros(67)
    else
      b.sig("text"); b.zeros(4); s.getBytes("ISO-8859-1").foreach(x => b.u8(x)); b.u8(0)
    b.bytes

  private def assemble(colorSpace: String, version: Int, tags: Seq[(String, Array[Byte])]): Array[Byte] =
    val b = new Buf
    val tableEnd = 128 + 4 + tags.length * 12
    var off = (tableEnd + 3) & ~3
    val offsets = tags.map { (_, d) => val o = off; off += (d.length + 3) & ~3; o }
    val total = off
    // header
    b.u32(total); b.zeros(4)
    b.u8(if version >= 4 then 4 else 2); b.u8(if version >= 4 then 0x30 else 0x40); b.zeros(2)
    b.sig("mntr"); b.sig(colorSpace); b.sig("XYZ ")
    b.u16(2024); b.u16(1); b.u16(1); b.u16(0); b.u16(0); b.u16(0)
    b.sig("acsp"); b.sig("APPL"); b.u32(0); b.zeros(4 + 4); b.zeros(8)
    b.u32(0) // rendering intent: perceptual
    b.s15(D50._1); b.s15(D50._2); b.s15(D50._3)
    b.sig("sUI "); b.zeros(16 + 28)
    b.u32(tags.length)
    tags.zip(offsets).foreach { case ((s, d), o) => b.sig(s); b.u32(o); b.u32(d.length) }
    while b.size < tableEnd do b.u8(0)
    while b.size % 4 != 0 do b.u8(0)
    tags.foreach { (_, d) => d.foreach(x => b.u8(x)); while b.size % 4 != 0 do b.u8(0) }
    b.bytes

  /** Matrix/TRC RGB display profile from XYZ colorants already adapted to D50 (columns of the RGB-to-XYZ matrix). */
  def matrixTrc(description: String, red: XYZ, green: XYZ, blue: XYZ, trc: Seq[Curve], mediaWhite: XYZ = D50,
      chad: Option[Mat3.M] = None, copyright: String = "No copyright, use freely", version: Int = 4): Array[Byte] =
    require(trc.length == 1 || trc.length == 3, "one curve shared or one per channel")
    val v4 = version >= 4
    val curves = if trc.length == 1 then Seq.fill(3)(trc.head) else trc
    val cb = curves.map(curveBytes)
    val chadBytes = chad.map { m => val b = new Buf; b.sig("sf32"); b.zeros(4); m.foreach(b.s15); b.bytes }
    assemble("RGB ", version, Seq(
      "desc" -> textBytes(description, v4, true), "cprt" -> textBytes(copyright, v4, false),
      "wtpt" -> xyzBytes(mediaWhite)) ++ chadBytes.map("chad" -> _).toSeq ++ Seq(
      "rXYZ" -> xyzBytes(red), "gXYZ" -> xyzBytes(green), "bXYZ" -> xyzBytes(blue),
      "rTRC" -> cb(0), "gTRC" -> cb(1), "bTRC" -> cb(2)))

  /** Gray profile with a single tone curve `kTRC`. */
  def gray(description: String, trc: Curve, copyright: String = "No copyright, use freely", version: Int = 4): Array[Byte] =
    val v4 = version >= 4
    assemble("GRAY", version, Seq("desc" -> textBytes(description, v4, true), "cprt" -> textBytes(copyright, v4, false),
      "wtpt" -> xyzBytes(D50), "kTRC" -> curveBytes(trc)))

  /** Matrix/TRC profile from primaries (xy chromaticities) and a white point (xy); colorants are Bradford-adapted to D50. */
  def fromPrimaries(description: String, r: (Double, Double), g: (Double, Double), b: (Double, Double), white: (Double, Double),
      trc: Curve, version: Int = 4): Array[Byte] =
    val w = Adaptation.whiteFromXy(white._1, white._2)
    val adapt = Adaptation.bradford(w, D50)
    val m = Mat3.mul(adapt, Adaptation.rgbToXyz(r, g, b, w))
    matrixTrc(description, (m(0), m(3), m(6)), (m(1), m(4), m(7)), (m(2), m(5), m(8)), Seq(trc), D50, Some(adapt), version = version)
