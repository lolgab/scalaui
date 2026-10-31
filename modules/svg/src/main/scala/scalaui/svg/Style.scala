package scalaui.svg

import scalaui.color.Color
import scalaui.raster.{FillRule, LineCap, LineJoin}

private[svg] enum PaintSpec:
  case NoPaint
  case Solid(c: Color)
  /** `currentColor` stays a keyword so it follows the `color` of the element that is finally painted. */
  case CurrentColor
  case Ref(id: String, fallback: Option[PaintSpec])
  case ContextFill
  case ContextStroke

/** Inherited (and a few per-element) presentation properties, fully resolved to user units. */
private[svg] final case class Style(
    fill: PaintSpec = PaintSpec.Solid(Color.Black), fillOpacity: Double = 1.0,
    stroke: PaintSpec = PaintSpec.NoPaint, strokeOpacity: Double = 1.0, strokeWidth: Double = 1.0,
    dash: Option[Array[Double]] = None, dashOffset: Double = 0.0, cap: LineCap = LineCap.Butt, join: LineJoin = LineJoin.Miter, miter: Double = 4.0,
    fillRule: FillRule = FillRule.NonZero, clipRule: FillRule = FillRule.NonZero, color: Color = Color.Black,
    fontSize: Double = 12.0, fontFamily: Vector[String] = Vector("serif"), fontWeight: Int = 400, fontStyle: String = "normal", fontStretch: String = "normal",
    textAnchor: String = "start", letterSpacing: Double = 0.0, wordSpacing: Double = 0.0, textDecoration: Set[String] = Set.empty,
    direction: String = "ltr", writingMode: String = "lr-tb", textOrientation: String = "mixed", glyphOrientationVertical: String = "auto", dominantBaseline: String = "auto", baselineShift: String = "baseline",
    visible: Boolean = true, markerStart: Option[String] = None, markerMid: Option[String] = None, markerEnd: Option[String] = None,
    linearFilters: Boolean = true, linearMask: Boolean = false, imageSmooth: Boolean = true, antialias: Boolean = true, paintOrderStrokeFirst: Boolean = false)

private[svg] object Style:
  val Default: Style = Style()

  /** Resolves a CSS length against the viewport; `axis` is 'x', 'y' or 'd' (diagonal) for percentages. */
  def length(l: Values.Len, fontSize: Double, vw: Double, vh: Double, axis: Char): Double =
    l.unit match
      case "" | "px" => l.v
      case "pt" => l.v * 4.0 / 3.0
      case "pc" => l.v * 16
      case "mm" => l.v * 96 / 25.4
      case "cm" => l.v * 96 / 2.54
      case "in" => l.v * 96
      case "em" => l.v * fontSize
      case "ex" => l.v * fontSize / 2
      case "%" =>
        val ref = axis match { case 'x' => vw; case 'y' => vh; case _ => math.sqrt((vw * vw + vh * vh) / 2) }
        l.v / 100 * ref
      case _ => l.v

  private def simplePaint(v: String): Option[PaintSpec] =
    if v == "none" then Some(PaintSpec.NoPaint)
    else if v == "currentColor" then Some(PaintSpec.CurrentColor)
    else SvgColors.parse(v).map(PaintSpec.Solid(_))

  /** Parses a `fill`/`stroke` value; anything malformed (including trailing junk after a fallback) is rejected as a whole. */
  private def paint(v0: String): Option[PaintSpec] =
    val v = v0.trim
    if v == "context-fill" then Some(PaintSpec.ContextFill)
    else if v == "context-stroke" then Some(PaintSpec.ContextStroke)
    else Values.urlRef(v) match
      case Some((id, rest)) =>
        if rest.isEmpty then Some(PaintSpec.Ref(id, None)) else simplePaint(rest).map(fb => PaintSpec.Ref(id, Some(fb)))
      case None => simplePaint(v)

  private val weights = Map("normal" -> 400, "bold" -> 700, "lighter" -> 300, "bolder" -> 700)

  def derive(a: Map[String, String], p: Style, vw: Double, vh: Double): Style =
    var s = p
    def get(k: String): Option[String] = a.get(k).map(_.trim).filter(v => v.nonEmpty && v != "inherit")
    get("color").flatMap { v => if v == "currentColor" then None else SvgColors.parse(v) }.foreach(c => s = s.copy(color = c))
    get("font-size").foreach { v =>
      val fs = Values.parseLen(v) match
        case Some(l) if l.unit == "%" => Some(p.fontSize * l.v / 100)
        case Some(l) => Some(length(l, p.fontSize, vw, vh, 'd'))
        case None => v match
          case "xx-small" => Some(9.0); case "x-small" => Some(10.0); case "small" => Some(13.0); case "medium" => Some(16.0)
          case "large" => Some(18.0); case "x-large" => Some(24.0); case "xx-large" => Some(32.0)
          case "larger" => Some(p.fontSize * 1.2); case "smaller" => Some(p.fontSize / 1.2)
          case _ => None
      fs.filter(_ > 0).foreach(x => s = s.copy(fontSize = x))
    }
    get("fill").flatMap(paint).foreach(x => s = s.copy(fill = x))
    get("stroke").flatMap(paint).foreach(x => s = s.copy(stroke = x))
    get("fill-opacity").flatMap(Values.parseFraction).foreach(x => s = s.copy(fillOpacity = Values.clamp01(x)))
    get("stroke-opacity").flatMap(Values.parseFraction).foreach(x => s = s.copy(strokeOpacity = Values.clamp01(x)))
    get("stroke-width").flatMap(Values.parseLen).foreach(l => s = s.copy(strokeWidth = length(l, s.fontSize, vw, vh, 'd')))
    get("stroke-linecap").foreach(v => v match
      case "round" => s = s.copy(cap = LineCap.Round); case "square" => s = s.copy(cap = LineCap.Square); case "butt" => s = s.copy(cap = LineCap.Butt); case _ => ())
    get("stroke-linejoin").foreach(v => v match
      case "round" => s = s.copy(join = LineJoin.Round); case "bevel" => s = s.copy(join = LineJoin.Bevel)
      case "miter" | "miter-clip" | "arcs" => s = s.copy(join = LineJoin.Miter); case _ => ())
    get("stroke-miterlimit").flatMap(Values.parseNumber).foreach(x => s = s.copy(miter = if x < 1 then 1.0 else x))
    get("stroke-dasharray").foreach { v =>
      if v == "none" then s = s.copy(dash = None)
      else
        val parts = v.split("[\\s,]+").filter(_.nonEmpty).map(Values.parseLen)
        if parts.forall(_.isDefined) && parts.nonEmpty then
          val nums = parts.map(l => length(l.get, s.fontSize, vw, vh, 'd'))
          // negative values or an all-zero array disable dashing; an odd count is repeated
          if nums.exists(_ < 0) || nums.sum <= 0 then s = s.copy(dash = None)
          else s = s.copy(dash = Some(if nums.length % 2 == 1 then nums ++ nums else nums))
    }
    get("stroke-dashoffset").flatMap(Values.parseLen).foreach(l => s = s.copy(dashOffset = length(l, s.fontSize, vw, vh, 'd')))
    get("fill-rule").foreach(v => s = s.copy(fillRule = if v == "evenodd" then FillRule.EvenOdd else FillRule.NonZero))
    get("clip-rule").foreach(v => s = s.copy(clipRule = if v == "evenodd" then FillRule.EvenOdd else FillRule.NonZero))
    get("font-family").foreach { v =>
      val fam = splitFamilies(v)
      if fam.nonEmpty then s = s.copy(fontFamily = fam)
    }
    get("font-weight").foreach { v =>
      val w = weights.get(v).orElse(Values.parseNumber(v).map(_.toInt))
      w.foreach { x =>
        val r = if v == "bolder" then (if p.fontWeight < 400 then 400 else if p.fontWeight < 600 then 700 else 900)
                else if v == "lighter" then (if p.fontWeight > 700 then 700 else if p.fontWeight > 500 then 400 else 100) else x
        s = s.copy(fontWeight = r)
      }
    }
    get("font-style").foreach(v => if v == "normal" || v == "italic" || v == "oblique" then s = s.copy(fontStyle = v))
    get("font").foreach { v => // shorthand: [style] [weight] size[/lh] family
      val m = "^\\s*((?:(?:italic|oblique|normal|bold|bolder|lighter|small-caps|[1-9]00)\\s+)*)([^\\s/]+)(?:/\\S+)?\\s+(.+)$".r.findFirstMatchIn(v)
      m.foreach { mm =>
        mm.group(1).split("\\s+").filter(_.nonEmpty).foreach {
          case "italic" => s = s.copy(fontStyle = "italic"); case "oblique" => s = s.copy(fontStyle = "oblique")
          case "bold" => s = s.copy(fontWeight = 700); case w if w.length == 3 && w.forall(_.isDigit) => s = s.copy(fontWeight = w.toInt)
          case _ => ()
        }
        Values.parseLen(mm.group(2)).foreach(l => s = s.copy(fontSize = length(l, p.fontSize, vw, vh, 'd')))
        val fam = splitFamilies(mm.group(3)); if fam.nonEmpty then s = s.copy(fontFamily = fam)
      }
    }
    get("text-anchor").foreach(v => if v == "start" || v == "middle" || v == "end" then s = s.copy(textAnchor = v))
    get("letter-spacing").flatMap(Values.parseLen).foreach(l => s = s.copy(letterSpacing = length(l, s.fontSize, vw, vh, 'd')))
    if get("letter-spacing").contains("normal") then s = s.copy(letterSpacing = 0)
    get("word-spacing").flatMap(Values.parseLen).foreach(l => s = s.copy(wordSpacing = length(l, s.fontSize, vw, vh, 'd')))
    get("text-decoration").foreach { v =>
      val set = v.split("\\s+").filter(Set("underline", "overline", "line-through").contains).toSet
      s = s.copy(textDecoration = if v == "none" then Set.empty else set)
    }
    get("direction").foreach(v => if v == "ltr" || v == "rtl" then s = s.copy(direction = v))
    get("writing-mode").foreach(v => s = s.copy(writingMode = v))
    get("text-orientation").foreach(v => if v == "mixed" || v == "upright" || v == "sideways" || v == "sideways-right" || v == "use-glyph-orientation" then s = s.copy(textOrientation = v))
    get("glyph-orientation-vertical").foreach(v => s = s.copy(glyphOrientationVertical = v.replace("deg", "").trim))
    get("dominant-baseline").foreach(v => s = s.copy(dominantBaseline = v))
    a.get("baseline-shift").map(_.trim).filter(_.nonEmpty).foreach(v => s = s.copy(baselineShift = v)) // not inherited, but reset per element below
    get("visibility").foreach(v => s = s.copy(visible = v == "visible"))
    def marker(k: String): Option[Option[String]] = get(k).map(v => if v == "none" then None else Values.urlRef(v).map(_._1))
    marker("marker-start").foreach(m => s = s.copy(markerStart = m))
    marker("marker-mid").foreach(m => s = s.copy(markerMid = m))
    marker("marker-end").foreach(m => s = s.copy(markerEnd = m))
    get("color-interpolation-filters").foreach(v => s = s.copy(linearFilters = v != "sRGB"))
    get("color-interpolation").foreach(v => s = s.copy(linearMask = v == "linearRGB"))
    get("image-rendering").foreach(v => s = s.copy(imageSmooth = !(v == "optimizeSpeed" || v == "pixelated" || v == "crisp-edges")))
    get("shape-rendering").foreach(v => s = s.copy(antialias = !(v == "crispEdges" || v == "optimizeSpeed")))
    get("paint-order").foreach { v =>
      val parts = v.split("\\s+").toList
      s = s.copy(paintOrderStrokeFirst = parts.indexOf("stroke") >= 0 && (parts.indexOf("fill") < 0 || parts.indexOf("stroke") < parts.indexOf("fill")) || parts == List("markers", "stroke") && false)
    }
    s

  private def splitFamilies(v: String): Vector[String] =
    v.split(',').map(_.trim.stripPrefix("\"").stripSuffix("\"").stripPrefix("'").stripSuffix("'").trim).filter(_.nonEmpty).toVector
