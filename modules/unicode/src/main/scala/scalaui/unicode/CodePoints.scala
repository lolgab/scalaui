package scalaui.unicode

/** A string decoded to code points, remembering each code point's UTF-16 offset. */
final class CodePoints(val cps: Array[Int], val offsets: Array[Int], val length: Int):
  def size: Int = cps.length

  /** UTF-16 offset of boundary `i` (0..size); `size` maps to the string length. */
  def offset(i: Int): Int = if i >= cps.length then length else offsets(i)

object CodePoints:
  def apply(s: String): CodePoints =
    val cps = new Array[Int](s.length)
    val offs = new Array[Int](s.length)
    var n = 0; var i = 0
    while i < s.length do
      val cp = s.codePointAt(i)
      cps(n) = cp; offs(n) = i; n += 1
      i += Character.charCount(cp)
    new CodePoints(java.util.Arrays.copyOf(cps, n), java.util.Arrays.copyOf(offs, n), s.length)

  /** Boundary flags over code point boundaries 0..n turned into sorted UTF-16 offsets. */
  def toOffsets(cp: CodePoints, flags: Array[Boolean]): Array[Int] =
    val b = Array.newBuilder[Int]
    var i = 0
    while i < flags.length do
      if flags(i) then b += cp.offset(i)
      i += 1
    b.result()
