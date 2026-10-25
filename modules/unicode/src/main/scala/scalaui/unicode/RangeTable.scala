package scalaui.unicode

/** Code point -> property value index, stored as ascending range starts plus one value per range. */
final class RangeTable(starts: Array[Int], values: Array[Int]):
  private val ascii: Array[Int] = Array.tabulate(256)(lookup)

  def apply(cp: Int): Int = if cp >= 0 && cp < 256 then ascii(cp) else lookup(cp)

  private def lookup(cp: Int): Int =
    if cp < 0 || cp > 0x10FFFF then return 0
    var lo = 0; var hi = starts.length - 1
    while lo < hi do
      val mid = (lo + hi + 1) >>> 1
      if starts(mid) <= cp then lo = mid else hi = mid - 1
    values(lo)

object RangeTable:
  private def digit(c: Char): Int =
    if c >= 'A' && c <= 'Z' then c - 'A'
    else if c >= 'a' && c <= 'z' then c - 'a' + 26
    else if c >= '0' && c <= '9' then c - '0' + 52
    else if c == '+' then 62
    else 63

  /** `chunks` concatenate to `2 * count` numbers of four 6-bit digits: `count` starts, then `count` values. */
  def apply(count: Int, chunks: Array[String]): RangeTable =
    val all = chunks.mkString
    def num(i: Int) =
      val o = i * 4
      (digit(all(o)) << 18) | (digit(all(o + 1)) << 12) | (digit(all(o + 2)) << 6) | digit(all(o + 3))
    new RangeTable(Array.tabulate(count)(num), Array.tabulate(count)(i => num(count + i)))

  /** Decodes `count` numbers stored as in [[apply]]. */
  def decodeInts(count: Int, chunks: Array[String]): Array[Int] =
    val all = chunks.mkString
    Array.tabulate(count) { i =>
      val o = i * 4
      (digit(all(o)) << 18) | (digit(all(o + 1)) << 12) | (digit(all(o + 2)) << 6) | digit(all(o + 3))
    }
