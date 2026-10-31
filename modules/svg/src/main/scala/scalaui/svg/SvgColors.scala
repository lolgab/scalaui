package scalaui.svg

import scalaui.color.Color
import scalaui.geom.Affine

private[svg] object SvgColors:
  /** The 148 CSS color keywords. */
  private val named: Map[String, Int] = Map(
    "lightgrey" -> 0xd3d3d3, "lavenderblush" -> 0xfff0f5, "deeppink" -> 0xff1493, "seashell" -> 0xfff5ee, "lightsalmon" -> 0xffa07a, "green" -> 0x008000,
    "lightgreen" -> 0x90ee90, "black" -> 0x000000, "deepskyblue" -> 0x00bfff, "mistyrose" -> 0xffe4e1, "silver" -> 0xc0c0c0, "dimgray" -> 0x696969,
    "navajowhite" -> 0xffdead, "royalblue" -> 0x4169e1, "peru" -> 0xcd853f, "darkgrey" -> 0xa9a9a9, "steelblue" -> 0x4682b4, "teal" -> 0x008080,
    "orangered" -> 0xff4500, "mediumslateblue" -> 0x7b68ee, "blueviolet" -> 0x8a2be2, "cornflowerblue" -> 0x6495ed, "cyan" -> 0x00ffff, "beige" ->
    0xf5f5dc, "goldenrod" -> 0xdaa520, "rosybrown" -> 0xbc8f8f, "yellow" -> 0xffff00, "blue" -> 0x0000ff, "darkblue" -> 0x00008b, "aliceblue" -> 0xf0f8ff,
    "white" -> 0xffffff, "mediumblue" -> 0x0000cd, "dodgerblue" -> 0x1e90ff, "limegreen" -> 0x32cd32, "purple" -> 0x800080, "lightsteelblue" -> 0xb0c4de,
    "lightslategray" -> 0x778899, "seagreen" -> 0x2e8b57, "mediumvioletred" -> 0xc71585, "slategrey" -> 0x708090, "darkslategrey" -> 0x2f4f4f, "turquoise"
    -> 0x40e0d0, "paleturquoise" -> 0xafeeee, "lightgoldenrodyellow" -> 0xfafad2, "magenta" -> 0xff00ff, "darkseagreen" -> 0x8fbc8f, "lightcyan" ->
    0xe0ffff, "lightcoral" -> 0xf08080, "mediumseagreen" -> 0x3cb371, "palegoldenrod" -> 0xeee8aa, "palegreen" -> 0x98fb98, "darkslateblue" -> 0x483d8b,
    "moccasin" -> 0xffe4b5, "forestgreen" -> 0x228b22, "darkkhaki" -> 0xbdb76b, "chartreuse" -> 0x7fff00, "floralwhite" -> 0xfffaf0, "snow" -> 0xfffafa,
    "fuchsia" -> 0xff00ff, "orchid" -> 0xda70d6, "darkorchid" -> 0x9932cc, "darkred" -> 0x8b0000, "darksalmon" -> 0xe9967a, "crimson" -> 0xdc143c, "lime"
    -> 0x00ff00, "palevioletred" -> 0xdb7093, "lightseagreen" -> 0x20b2aa, "ivory" -> 0xfffff0, "powderblue" -> 0xb0e0e6, "aquamarine" -> 0x7fffd4,
    "darkturquoise" -> 0x00ced1, "lavender" -> 0xe6e6fa, "azure" -> 0xf0ffff, "mediumturquoise" -> 0x48d1cc, "lightgray" -> 0xd3d3d3, "transparent" ->
    0x000000, "gainsboro" -> 0xdcdcdc, "olivedrab" -> 0x6b8e23, "papayawhip" -> 0xffefd5, "tomato" -> 0xff6347, "midnightblue" -> 0x191970, "pink" ->
    0xffc0cb, "yellowgreen" -> 0x9acd32, "slategray" -> 0x708090, "red" -> 0xff0000, "indigo" -> 0x4b0082, "orange" -> 0xffa500, "grey" -> 0x808080,
    "wheat" -> 0xf5deb3, "darkgoldenrod" -> 0xb8860b, "lawngreen" -> 0x7cfc00, "lightslategrey" -> 0x778899, "burlywood" -> 0xdeb887, "aqua" -> 0x00ffff,
    "saddlebrown" -> 0x8b4513, "oldlace" -> 0xfdf5e6, "lightskyblue" -> 0x87cefa, "violet" -> 0xee82ee, "dimgrey" -> 0x696969, "darkorange" -> 0xff8c00,
    "lightblue" -> 0xadd8e6, "khaki" -> 0xf0e68c, "coral" -> 0xff7f50, "brown" -> 0xa52a2a, "mediumpurple" -> 0x9370db, "linen" -> 0xfaf0e6,
    "mediumorchid" -> 0xba55d3, "indianred" -> 0xcd5c5c, "maroon" -> 0x800000, "firebrick" -> 0xb22222, "skyblue" -> 0x87ceeb, "darkgray" -> 0xa9a9a9,
    "hotpink" -> 0xff69b4, "olive" -> 0x808000, "sienna" -> 0xa0522d, "cadetblue" -> 0x5f9ea0, "darkslategray" -> 0x2f4f4f, "slateblue" -> 0x6a5acd,
    "plum" -> 0xdda0dd, "mediumspringgreen" -> 0x00fa9a, "thistle" -> 0xd8bfd8, "mintcream" -> 0xf5fffa, "darkmagenta" -> 0x8b008b, "lemonchiffon" ->
    0xfffacd, "bisque" -> 0xffe4c4, "antiquewhite" -> 0xfaebd7, "darkgreen" -> 0x006400, "whitesmoke" -> 0xf5f5f5, "lightpink" -> 0xffb6c1, "darkcyan" ->
    0x008b8b, "tan" -> 0xd2b48c, "blanchedalmond" -> 0xffebcd, "honeydew" -> 0xf0fff0, "salmon" -> 0xfa8072, "lightyellow" -> 0xffffe0, "springgreen" ->
    0x00ff7f, "cornsilk" -> 0xfff8dc, "sandybrown" -> 0xf4a460, "mediumaquamarine" -> 0x66cdaa, "darkviolet" -> 0x9400d3, "darkolivegreen" -> 0x556b2f,
    "gold" -> 0xffd700, "peachpuff" -> 0xffdab9, "greenyellow" -> 0xadff2f, "gray" -> 0x808080, "navy" -> 0x000080, "ghostwhite" -> 0xf8f8ff, "chocolate"
    -> 0xd2691e)

  private def num(p: String): Option[Float] = p.stripSuffix("%").toFloatOption
  private def hue(h0: Float, s: Float, l: Float): (Float, Float, Float) =
    val h = ((h0 % 360f) + 360f) % 360f / 360f
    val q = if l < 0.5f then l * (1 + s) else l + s - l * s
    val p = 2 * l - q
    def f(t0: Float): Float =
      val t = if t0 < 0 then t0 + 1 else if t0 > 1 then t0 - 1 else t0
      if t < 1f / 6 then p + (q - p) * 6 * t else if t < 0.5f then q else if t < 2f / 3 then p + (q - p) * (2f / 3 - t) * 6 else p
    (f(h + 1f / 3), f(h), f(h - 1f / 3))

  /** Parses a CSS color: keywords, `#rgb[a]`/`#rrggbb[aa]`, `rgb()/rgba()`, `hsl()/hsla()`, `transparent`. `currentColor` is handled by callers. */
  def parse(s0: String): Option[Color] =
    val s = s0.trim.toLowerCase
    if s == "transparent" then Some(Color.Transparent)
    else if s.startsWith("#") then
      val h = s.drop(1)
      if !h.forall(c => Character.digit(c, 16) >= 0) then None
      else h.length match
        case 3 | 4 =>
          val v = h.map(c => Character.digit(c, 16) * 17)
          Some(Color.rgb8(v(0), v(1), v(2), if v.length == 4 then v(3) else 255))
        case 6 | 8 =>
          val v = h.grouped(2).map(Integer.parseInt(_, 16)).toVector
          Some(Color.rgb8(v(0), v(1), v(2), if v.length == 4 then v(3) else 255))
        case _ => None
    else if s.startsWith("rgb") || s.startsWith("hsl") then
      val open = s.indexOf('('); val close = s.indexOf(')')
      if open < 0 || close < open then return None
      val parts = s.substring(open + 1, close).split("[\\s,/]+").filter(_.nonEmpty)
      if parts.length < 3 then return None
      val alpha = if parts.length >= 4 then num(parts(3)).map(a => if parts(3).endsWith("%") then a / 100f else a) else Some(1f)
      if alpha.isEmpty then return None
      val a = alpha.get.max(0f).min(1f)
      if s.startsWith("rgb") then
        val pct = parts.take(3).count(_.endsWith("%"))
        if pct != 0 && pct != 3 then return None // mixing percentages and numbers is invalid
        def comp(p: String): Option[Float] = num(p).map(v => (if p.endsWith("%") then v / 100f else v / 255f).max(0f).min(1f))
        for r <- comp(parts(0)); g <- comp(parts(1)); b <- comp(parts(2)) yield Color(r, g, b, a)
      else
        for h <- num(parts(0).stripSuffix("deg")); sa <- num(parts(1)); l <- num(parts(2)) yield
          val (r, g, b) = hue(h, (sa / 100f).max(0f).min(1f), (l / 100f).max(0f).min(1f))
          Color(r, g, b, a)
    else named.get(s).map(v => Color.rgb8((v >> 16) & 0xff, (v >> 8) & 0xff, v & 0xff))

private[svg] object Transforms:
  private val num = "[-+]?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE][-+]?\\d+)?".r
  /** Parses `matrix() translate() scale() rotate() skewX() skewY()` lists; `None` when the list is malformed. */
  def parseOpt(s: String): Option[Affine] =
    val re = "\\s*,?\\s*([a-zA-Z]+)\\s*\\(([^)]*)\\)".r
    var acc = Affine.Identity; var pos = 0; var ok = true
    val t = s.trim
    var m = re.findPrefixMatchOf(t)
    while ok && m.isDefined do
      val mm = m.get
      val a = num.findAllIn(mm.group(2)).map(_.toDouble).toArray
      val tr: Option[Affine] = mm.group(1) match
        case "matrix" if a.length == 6 => Some(Affine(a(0), a(1), a(2), a(3), a(4), a(5)))
        case "translate" if a.length == 1 || a.length == 2 => Some(Affine.translate(scalaui.geom.Vec2(a(0), a.lift(1).getOrElse(0.0))))
        case "scale" if a.length == 1 || a.length == 2 => Some(Affine.scale(a(0), a.lift(1).getOrElse(a(0))))
        case "rotate" if a.length == 1 || a.length == 3 =>
          val r = Affine.rotate(math.toRadians(a(0)))
          Some(if a.length == 3 then Affine.translate(scalaui.geom.Vec2(a(1), a(2))) * r * Affine.translate(scalaui.geom.Vec2(-a(1), -a(2))) else r)
        case "skewX" if a.length == 1 => Some(Affine(1, 0, math.tan(math.toRadians(a(0))), 1, 0, 0))
        case "skewY" if a.length == 1 => Some(Affine(1, math.tan(math.toRadians(a(0))), 0, 1, 0, 0))
        case _ => None
      tr match
        case Some(x) => acc = acc * x
        case None => ok = false
      pos += mm.end
      m = re.findPrefixMatchOf(t.substring(pos))
    if ok && t.substring(pos).trim.isEmpty then Some(acc) else None
  def parse(s: String): Affine = parseOpt(s).getOrElse(Affine.Identity)
