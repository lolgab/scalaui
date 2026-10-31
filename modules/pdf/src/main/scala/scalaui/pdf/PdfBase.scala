package scalaui.pdf

import scalaui.deflate.Zlib
import scala.collection.mutable

/** Low-level serialization helpers. */
private[pdf] object Fmt:
  /** A PDF number with at most 4 decimals and never an exponent. */
  def n(d0: Double): String =
    val d = if d0.isNaN || d0.isInfinite then 0.0 else d0
    val scaled = math.round(math.abs(d) * 10000.0)
    val ip = scaled / 10000; val fp = (scaled % 10000).toInt
    val sign = if d < 0 && scaled != 0 then "-" else ""
    if fp == 0 then sign + ip.toString
    else
      var f = f"$fp%04d"; while f.endsWith("0") do f = f.dropRight(1)
      sign + ip.toString + "." + f

  def bytes(s: String): Array[Byte] = s.getBytes("ISO-8859-1")

  /** A literal or hex text string: ASCII stays literal, anything else becomes UTF-16BE with a BOM. */
  def text(s: String): String =
    if s.forall(c => c >= 32 && c < 127) then "(" + s.flatMap { case '(' => "\\("; case ')' => "\\)"; case '\\' => "\\\\"; case c => c.toString } + ")"
    else "<FEFF" + s.map(c => f"${c.toInt}%04X").mkString + ">"

  def name(s: String): String = "/" + s.flatMap(c => if c > 32 && c < 127 && "()<>[]{}/%#".indexOf(c) < 0 then c.toString else f"#${c.toInt & 0xff}%02X")

  def hex(b: Array[Byte]): String = b.map(x => f"${x & 0xff}%02X").mkString

  /** A stream object; Flate-compressed (when that makes it smaller) with the deflate module. */
  def stream(dict: String, data: Array[Byte], compress: Boolean = true): Array[Byte] =
    val z = if compress && data.length > 32 then Zlib.compress(data, 6) else data
    val useZ = compress && data.length > 32 && z.length < data.length
    val out = new java.io.ByteArrayOutputStream(z.length + dict.length + 40)
    val body = if useZ then z else data
    out.write(bytes(s"<< $dict${if useZ then " /Filter /FlateDecode" else ""} /Length ${body.length} >>\nstream\n")); out.write(body); out.write(bytes("\nendstream"))
    out.toByteArray

/** Object numbering during serialization. Ids below `first` belong to objects registered while drawing; the rest are allocated here. */
final class EmitCtx private[pdf] (first: Int, val compress: Boolean = true):
  private[pdf] val slots = mutable.ArrayBuffer.empty[Array[Byte]]
  slots ++= Iterator.fill(first - 1)(null: Array[Byte])
  def reserve(): Int = { slots += null; slots.size }
  def set(id: Int, b: Array[Byte]): Unit = slots(id - 1) = b
  def add(b: Array[Byte]): Int = { slots += b; slots.size }
  def stream(dict: String, data: Array[Byte], compress: Boolean = true): Int = add(Fmt.stream(dict, data, compress && this.compress))
