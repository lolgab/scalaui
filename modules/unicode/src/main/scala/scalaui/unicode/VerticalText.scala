package scalaui.unicode

/** Unicode Vertical Text Layout (UAX #50): the Vertical_Orientation property and orientation runs. */
object VerticalText:
  /** Vertical_Orientation: how a character is oriented in vertical text. */
  enum Orientation:
    /** Same orientation as in the code charts. */
    case Upright
    /** Rotated 90 degrees clockwise. */
    case Rotated
    /** Typographically transformed (the font's `vert` feature), upright when the font has no vertical form. */
    case TransformedUpright
    /** Typographically transformed, rotated when the font has no vertical form. */
    case TransformedRotated

  private val all = Array(Orientation.Rotated, Orientation.Upright, Orientation.TransformedUpright, Orientation.TransformedRotated)

  /** The Vertical_Orientation value of `cp` (`R` for everything not explicitly listed). */
  def orientation(cp: Int): Orientation = all(VerticalOrientation.table(cp))

  /** A run `[start, end)` of code point indices with one orientation: `upright` runs stack glyphs upright, the others
    * are laid out sideways (rotated 90 degrees clockwise).
    */
  final case class Run(start: Int, end: Int, upright: Boolean)

  /** Splits `cps` into orientation runs. Each grapheme cluster takes the orientation of its first code point, so combining
    * marks and joiners follow their base. `Tu` is upright and `Tr` sideways; callers that know the font's vertical glyphs
    * can pass `transformed` to treat `Tr` characters the font has a vertical form for as upright.
    */
  def runs(cps: CodePoints, transformed: Int => Boolean = _ => false): Array[Run] =
    val n = cps.size
    if n == 0 then return Array.empty
    val gb = Graphemes.boundaries(cps)
    val up = new Array[Boolean](n)
    var cur = false
    var k = 0
    while k < n do
      if gb(k) then cur = isUpright(cps.cps(k), transformed)
      up(k) = cur
      k += 1
    val out = Array.newBuilder[Run]
    var s = 0
    k = 1
    while k <= n do
      if k == n || up(k) != up(s) then { out += Run(s, k, up(s)); s = k }
      k += 1
    out.result()

  /** True if `cp` is laid out upright (`U`, `Tu`, or `Tr` with a vertical form according to `transformed`). */
  def isUpright(cp: Int, transformed: Int => Boolean = _ => false): Boolean =
    VerticalOrientation.table(cp) match
      case VerticalOrientation.U | VerticalOrientation.Tu => true
      case VerticalOrientation.Tr => transformed(cp)
      case _ => false
