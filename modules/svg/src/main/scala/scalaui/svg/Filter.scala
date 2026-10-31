package scalaui.svg

import scalaui.color.Color
import scalaui.geom.*
import scalaui.imaging.{Blend, BlendMode, Pixmaps}
import scalaui.raster.*
import scala.collection.mutable

/** One entry of a `filter` property: a reference to a `<filter>` element or a CSS filter function. */
private[svg] enum FilterOp:
  case Ref(el: Option[XmlElement])
  case Fn(name: String, args: String)
  def isInvalid: Boolean = this match { case Ref(None) => true; case _ => false }

/** SVG filter effects. Primitives work on region-sized premultiplied float pixmaps; the working colour space follows
  * `color-interpolation-filters` (linearRGB by default).
  */
private[svg] object FilterEngine:
  def parseList(s: String, r: Renderer): Vector[FilterOp] =
    val out = Vector.newBuilder[FilterOp]
    var i = 0
    while i < s.length do
      while i < s.length && s.charAt(i).isWhitespace do i += 1
      if i < s.length then
        val open = s.indexOf('(', i); val close = if open < 0 then -1 else s.indexOf(')', open)
        if open < 0 || close < 0 then { out += FilterOp.Ref(None); i = s.length }
        else
          val name = s.substring(i, open).trim; val args = s.substring(open + 1, close).trim
          if name == "url" then
            val id = args.stripPrefix("\"").stripSuffix("\"").stripPrefix("'").stripSuffix("'").stripPrefix("#")
            out += FilterOp.Ref(r.byId(id).filter(_.name == "filter"))
          else out += FilterOp.Fn(name, args)
          i = close + 1
    out.result()

  // ---------------------------------------------------------------- colour space helpers
  private val toLinLut: Array[Float] = Array.tabulate(4097)(i => Color.srgbToLinear(i / 4096f))
  private val toSrgbLut: Array[Float] = Array.tabulate(4097)(i => Color.linearToSrgb(i / 4096f))
  private def lut(t: Array[Float], v: Float): Float =
    val x = (if v < 0f then 0f else if v > 1f then 1f else v) * 4096f; val i = x.toInt
    if i >= 4096 then t(4096) else t(i) + (t(i + 1) - t(i)) * (x - i)

  private def convert(pm: Pixmap, toLinear: Boolean): Pixmap =
    val out = new Pixmap(pm.width, pm.height); val t = if toLinear then toLinLut else toSrgbLut
    var i = 0
    while i < pm.data.length do
      val a = pm.data(i + 3)
      if a > 0f then
        val inv = 1f / a
        out.data(i) = lut(t, pm.data(i) * inv) * a; out.data(i + 1) = lut(t, pm.data(i + 1) * inv) * a; out.data(i + 2) = lut(t, pm.data(i + 2) * inv) * a; out.data(i + 3) = a
      i += 4
    out

  private final class Img(val pm: Pixmap, val linear: Boolean):
    def in(wantLinear: Boolean): Pixmap = if linear == wantLinear then pm else convert(pm, wantLinear)

  // ---------------------------------------------------------------- entry point
  def apply(r: Renderer, layer: Pixmap, ops: Vector[FilterOp], bbox: Option[Rect], ctx: Ctx): Unit =
    ops.foreach {
      case FilterOp.Ref(Some(el)) => applyFilter(r, layer, el, bbox, ctx)
      case FilterOp.Ref(None) => ()
      case FilterOp.Fn(name, args) =>
        synth(name, args, bbox, ctx) match
          case Some(el) => applyFilter(r, layer, el, bbox, ctx)
          case None => ()
    }

  private def el(name: String, attrs: (String, String)*): XmlElement = new XmlElement(name, attrs.toMap, Vector.empty)

  /** CSS filter functions as equivalent primitive chains. */
  private def synth(name: String, args: String, bbox: Option[Rect], ctx: Ctx): Option[XmlElement] =
    def amount(default: Double): Double = Values.parseFraction(args.trim).filter(_ >= 0).getOrElse(default)
    def lenArg(s: String): Option[Double] = Values.parseLen(s).map(Style.length(_, ctx.style.fontSize, ctx.vw, ctx.vh, 'd'))
    val prims: Vector[XmlElement] = name match
      case "blur" =>
        Values.parseLen(args).filter(l => l.unit != "%" && l.v >= 0).map(l => Style.length(l, ctx.style.fontSize, ctx.vw, ctx.vh, 'd')) match
          case Some(sd) => Vector(el("feGaussianBlur", "stdDeviation" -> sd.toString, "color-interpolation-filters" -> "sRGB"))
          case None => Vector.empty
      case "grayscale" => val a = math.min(1.0, amount(1.0)); Vector(el("feColorMatrix", "type" -> "matrix", "values" -> grayMatrix(a)))
      case "sepia" => val a = math.min(1.0, amount(1.0)); Vector(el("feColorMatrix", "type" -> "matrix", "values" -> sepiaMatrix(a)))
      case "saturate" => Vector(el("feColorMatrix", "type" -> "saturate", "values" -> amount(1.0).toString))
      case "hue-rotate" => angleDegrees(args).map(a => Vector(el("feColorMatrix", "type" -> "hueRotate", "values" -> a.toString))).getOrElse(Vector.empty)
      case "invert" =>
        val a = math.min(1.0, amount(1.0)); val tv = s"$a ${1 - a}"
        Vector(el("feComponentTransfer").withKids(Seq("R", "G", "B").map(c => el("feFunc" + c, "type" -> "table", "tableValues" -> tv))))
      case "opacity" =>
        val a = math.min(1.0, amount(1.0))
        Vector(el("feComponentTransfer").withKids(Seq(el("feFuncA", "type" -> "table", "tableValues" -> s"0 $a"))))
      case "brightness" =>
        Vector(el("feComponentTransfer").withKids(Seq("R", "G", "B").map(c => el("feFunc" + c, "type" -> "linear", "slope" -> amount(1.0).toString))))
      case "contrast" =>
        val a = amount(1.0)
        Vector(el("feComponentTransfer").withKids(Seq("R", "G", "B").map(c => el("feFunc" + c, "type" -> "linear", "slope" -> a.toString, "intercept" -> (-(0.5 * a) + 0.5).toString))))
      case "drop-shadow" =>
        val parts = splitTop(args)
        val color = parts.find(p => SvgColors.parse(p).isDefined || p.startsWith("rgb") || p.startsWith("hsl")).getOrElse("black")
        val lens = parts.filter(_ != color).flatMap(lenArg)
        Vector(el("feDropShadow", "dx" -> lens.lift(0).getOrElse(0.0).toString, "dy" -> lens.lift(1).getOrElse(0.0).toString,
          "stdDeviation" -> (lens.lift(2).getOrElse(0.0) / 2).toString, "flood-color" -> color))
      case _ => Vector.empty
    if prims.isEmpty then None
    else
      val margin = name match
        case "blur" => lenArg(args).getOrElse(0.0) * 3
        case "drop-shadow" => (lenArg(args.split("\\s+").head).map(math.abs).getOrElse(0.0) + 30)
        case _ => 0.0
      bbox match
        case Some(b) => Some(new XmlElement("filter", Map("filterUnits" -> "userSpaceOnUse", "x" -> (b.x0 - math.max(margin, b.width * 0.1)).toString, "y" -> (b.y0 - math.max(margin, b.height * 0.1)).toString,
          "width" -> (b.width + 2 * math.max(margin, b.width * 0.1)).toString, "height" -> (b.height + 2 * math.max(margin, b.height * 0.1)).toString, "color-interpolation-filters" -> "sRGB"), prims.toVector))
        case None => None

  /** CSS angle (`deg`, `grad`, `rad`, `turn`; unitless = degrees) in degrees. */
  private def angleDegrees(s: String): Option[Double] =
    val t = s.trim
    if t.isEmpty then return Some(0.0)
    val num = Values.numbers(t).headOption
    num.flatMap { n =>
      if t.endsWith("grad") then Some(n * 0.9) else if t.endsWith("deg") then Some(n) else if t.endsWith("rad") then Some(math.toDegrees(n)) else if t.endsWith("turn") then Some(n * 360)
      else if n == 0.0 then Some(0.0) else None // a unitless non-zero angle is invalid
    }

  private def splitTop(s: String): Vector[String] =
    val out = Vector.newBuilder[String]; var depth = 0; var start = 0
    for i <- s.indices do
      val c = s.charAt(i)
      if c == '(' then depth += 1 else if c == ')' then depth -= 1
      else if c.isWhitespace && depth == 0 then { if i > start then out += s.substring(start, i); start = i + 1 }
    if s.length > start then out += s.substring(start)
    out.result()

  extension (e: XmlElement) private def withKids(k: Seq[XmlElement]): XmlElement = new XmlElement(e.name, e.attrs, k.toVector)

  private def grayMatrix(a: Double): String =
    val r = 1 - a
    Seq(0.2126 + 0.7874 * r, 0.7152 - 0.7152 * r, 0.0722 - 0.0722 * r, 0, 0, 0.2126 - 0.2126 * r, 0.7152 + 0.2848 * r, 0.0722 - 0.0722 * r, 0, 0,
      0.2126 - 0.2126 * r, 0.7152 - 0.7152 * r, 0.0722 + 0.9278 * r, 0, 0, 0, 0, 0, 1, 0).mkString(" ")
  private def sepiaMatrix(a: Double): String =
    val r = 1 - a
    Seq(0.393 + 0.607 * r, 0.769 - 0.769 * r, 0.189 - 0.189 * r, 0, 0, 0.349 - 0.349 * r, 0.686 + 0.314 * r, 0.168 - 0.168 * r, 0, 0,
      0.272 - 0.272 * r, 0.534 - 0.534 * r, 0.131 + 0.869 * r, 0, 0, 0, 0, 0, 1, 0).mkString(" ")

  // ---------------------------------------------------------------- applying a <filter>
  private def applyFilter(r: Renderer, layer: Pixmap, fe: XmlElement, bbox: Option[Rect], ctx: Ctx): Unit =
    if r.activeFilters.exists(_ eq fe) then return // a filter that (indirectly) uses itself is dropped
    r.activeFilters += fe
    try applyFilterInner(r, layer, fe, bbox, ctx) finally r.activeFilters -= fe

  private def applyFilterInner(r: Renderer, layer: Pixmap, fe: XmlElement, bbox: Option[Rect], ctx: Ctx): Unit =
    val chainEls = { val out = mutable.ListBuffer(fe); var cur = r.hrefTarget(fe); while cur.exists(c => c.name == "filter" && !out.exists(_ eq c)) do { out += cur.get; cur = r.hrefTarget(cur.get) }; out.toList }
    def attr(k: String): Option[String] = chainEls.view.flatMap(_.attrs.get(k)).headOption
    val prims = chainEls.find(_.children.exists(_.name.startsWith("fe"))).map(_.children.filter(_.name.startsWith("fe"))).getOrElse(Vector.empty)
    val unitsBBox = !attr("filterUnits").contains("userSpaceOnUse")
    val primBBox = attr("primitiveUnits").contains("objectBoundingBox")
    def clear(): Unit = java.util.Arrays.fill(layer.data, 0f)
    if (unitsBBox || primBBox) && !bbox.exists(b => b.width > 0 && b.height > 0) then { clear(); return }
    def dim(k: String, default: String, axis: Char): Double =
      val l = Values.parseLen(attr(k).getOrElse(default)).orElse(Values.parseLen(default)).get
      if unitsBBox then (if l.unit == "%" then l.v / 100 else l.v) else Style.length(l, ctx.style.fontSize, ctx.vw, ctx.vh, axis)
    var fx = dim("x", "-10%", 'x'); var fy = dim("y", "-10%", 'y'); var fw = dim("width", "120%", 'x'); var fh = dim("height", "120%", 'y')
    if unitsBBox then { val b = bbox.get; fx = b.x0 + fx * b.width; fy = b.y0 + fy * b.height; fw *= b.width; fh *= b.height }
    if fw <= 0 || fh <= 0 || prims.isEmpty then { clear(); return }
    val m = ctx.ctm
    val devRegion = m.transformRectBbox(Rect(fx, fy, fx + fw, fy + fh))
    val rx0 = math.max(0, math.floor(devRegion.x0 + 1e-6).toInt); val ry0 = math.max(0, math.floor(devRegion.y0 + 1e-6).toInt)
    val rx1 = math.min(layer.width, math.ceil(devRegion.x1 - 1e-6).toInt); val ry1 = math.min(layer.height, math.ceil(devRegion.y1 - 1e-6).toInt)
    val rw = rx1 - rx0; val rh = ry1 - ry0
    if rw <= 0 || rh <= 0 then { clear(); return }
    val sx = math.hypot(m.a, m.b); val sy = math.hypot(m.c, m.d)

    // source graphic cropped to the region
    val src = new Pixmap(rw, rh)
    for y <- 0 until rh do System.arraycopy(layer.data, ((ry0 + y) * layer.width + rx0) * 4, src.data, y * rw * 4, rw * 4)

    val filterLinear = attr("color-interpolation-filters").map(_ != "sRGB").getOrElse(r.definitionStyle(fe).linearFilters)
    val results = mutable.HashMap.empty[String, Res]
    val regionRect = (0, 0, rw, rh)
    val filterUser = Rect(fx, fy, fx + fw, fy + fh)
    var last: Option[Res] = None
    val srcImg = Res(new Img(src, false), regionRect, filterUser)
    val rotated = math.abs(m.b) > 1e-6 || math.abs(m.c) > 1e-6
    def alphaOnly(p: Pixmap): Pixmap = { val o = new Pixmap(p.width, p.height); var i = 3; while i < p.data.length do { o.data(i) = p.data(i); i += 4 }; o }

    def input(name: Option[String]): Res = name.map(_.trim) match
      case Some("SourceGraphic") => srcImg
      case Some("SourceAlpha") => Res(new Img(alphaOnly(src), false), regionRect, filterUser)
      case Some("BackgroundImage" | "BackgroundAlpha" | "FillPaint" | "StrokePaint") => Res(new Img(new Pixmap(rw, rh), false), regionRect, filterUser)
      case Some(n) if results.contains(n) => results(n)
      case _ => last.getOrElse(srcImg)

    // user-space number scaled to device (primitiveUnits aware)
    def scaleX(v: Double): Double = (if primBBox then v * bbox.get.width else v) * sx
    def scaleY(v: Double): Double = (if primBBox then v * bbox.get.height else v) * sy

    prims.foreach { p =>
      val pa = p.attrs
      val lin = pa.get("color-interpolation-filters").map(_ != "sRGB").getOrElse(filterLinear)
      // subregion in region-local device pixels
      def psub(inherit: Option[Res]): ((Int, Int, Int, Int), Rect) =
        def pl(k: String, axis: Char): Option[Double] = pa.get(k).flatMap(Values.parseLen).map { l =>
          if primBBox then (if l.unit == "%" then l.v / 100 else l.v) else Style.length(l, ctx.style.fontSize, ctx.vw, ctx.vh, axis)
        }
        val x = pl("x", 'x'); val y = pl("y", 'y'); val w = pl("width", 'x'); val h = pl("height", 'y')
        if x.isEmpty && y.isEmpty && w.isEmpty && h.isEmpty then inherit.map(i => (i.sub, i.user)).getOrElse((regionRect, filterUser))
        else
          val ux = x.map(v => if primBBox then bbox.get.x0 + v * bbox.get.width else v).getOrElse(fx)
          val uy = y.map(v => if primBBox then bbox.get.y0 + v * bbox.get.height else v).getOrElse(fy)
          val uw = w.map(v => if primBBox then v * bbox.get.width else v).getOrElse(fx + fw - ux)
          val uh = h.map(v => if primBBox then v * bbox.get.height else v).getOrElse(fy + fh - uy)
          val d = m.transformRectBbox(Rect(ux, uy, ux + uw, uy + uh))
          val x0 = math.max(0, math.floor(d.x0 + 1e-6).toInt - rx0); val y0 = math.max(0, math.floor(d.y0 + 1e-6).toInt - ry0)
          val x1 = math.min(rw, math.ceil(d.x1 - 1e-6).toInt - rx0); val y1 = math.min(rh, math.ceil(d.y1 - 1e-6).toInt - ry0)
          ((x0, y0, math.max(x0, x1), math.max(y0, y1)), Rect(ux, uy, ux + uw, uy + uh))
      val in1 = input(pa.get("in")); val in2 = input(pa.get("in2"))
      // feOffset without an explicit subregion inherits the one of its input
      val (sub, userSub) = psub(if p.name == "feOffset" then Some(in1) else None)
      def px1: Pixmap = in1.img.in(lin); def px2: Pixmap = in2.img.in(lin)
      val result: Option[Pixmap] = p.name match
        case "feGaussianBlur" =>
          val sd = Values.numbers(pa.getOrElse("stdDeviation", "0"))
          val (sdx, sdy) = (sd.lift(0).getOrElse(0.0), sd.lift(1).getOrElse(sd.lift(0).getOrElse(0.0)))
          if sdx < 0 || sdy < 0 then Some(Pixmaps.copy(px1)) // invalid: the primitive has no effect
          else if sdx == 0 && sdy == 0 then Some(Pixmaps.copy(px1))
          else Some(blur(px1, scaleX(sdx), scaleY(sdy)))
        case "feOffset" =>
          val dx = scaleX(Values.parseNumber(pa.getOrElse("dx", "0")).getOrElse(0.0)); val dy = scaleY(Values.parseNumber(pa.getOrElse("dy", "0")).getOrElse(0.0))
          Some(offset(px1, dx.toInt, dy.toInt))
        case "feFlood" =>
          val (c, _) = floodColor(p, r, lin)
          val o = new Pixmap(rw, rh); val pc = c.premultiplied
          fillRect(o, sub, pc.r, pc.g, pc.b, pc.a); Some(o)
        case "feMerge" =>
          val o = new Pixmap(rw, rh)
          p.children.filter(_.name == "feMergeNode").foreach { k => Blend.composite(o, input(k.attrs.get("in")).img.in(lin)) }
          Some(o)
        case "feBlend" =>
          val mode = pa.getOrElse("mode", "normal") match
            case "multiply" => BlendMode.Multiply; case "screen" => BlendMode.Screen; case "darken" => BlendMode.Darken; case "lighten" => BlendMode.Lighten
            case "overlay" => BlendMode.Overlay; case "color-dodge" => BlendMode.ColorDodge; case "color-burn" => BlendMode.ColorBurn
            case "hard-light" => BlendMode.HardLight; case "soft-light" => BlendMode.SoftLight; case "difference" => BlendMode.Difference
            case "exclusion" => BlendMode.Exclusion; case "hue" => BlendMode.Hue; case "saturation" => BlendMode.Saturation
            case "color" => BlendMode.Color; case "luminosity" => BlendMode.Luminosity; case _ => BlendMode.Normal
          val o = Pixmaps.copy(px2); Blend.composite(o, px1, 0, 0, mode); Some(o)
        case "feComposite" => Some(composite(px1, px2, pa))
        case "feColorMatrix" => Some(colorMatrix(px1, pa))
        case "feComponentTransfer" => Some(componentTransfer(px1, p))
        case "feMorphology" =>
          // radius rules follow resvg: one or two values, a zero (or invalid) radius becomes 1, negative values fall back to 1
          val list = Values.numbers(pa.getOrElse("radius", "1"))
          var (rxr, ryr) = if list.length == 1 then (list(0), list(0)) else if list.length == 2 then (list(0), list(1)) else (0.0, 0.0)
          if rxr == 0 && ryr == 0 then { rxr = 1; ryr = 1 }
          else if rxr == 0 then rxr = 1 else if ryr == 0 then ryr = 1
          if rxr < 0 || ryr < 0 then { rxr = 1; ryr = 1 }
          Some(morphology(px1, scaleX(rxr).round.toInt, scaleY(ryr).round.toInt, pa.getOrElse("operator", "erode") == "dilate"))
        case "feTile" => Some(tile(px1, in1.sub))
        case "feDropShadow" =>
          val dx = scaleX(Values.parseNumber(pa.getOrElse("dx", "2")).getOrElse(2.0)); val dy = scaleY(Values.parseNumber(pa.getOrElse("dy", "2")).getOrElse(2.0))
          val sd = Values.numbers(pa.getOrElse("stdDeviation", "2")); val sdx = sd.lift(0).getOrElse(2.0); val sdy = sd.lift(1).getOrElse(sdx)
          val (c, _) = floodColor(p, r, lin)
          val base = px1
          val shadow = new Pixmap(rw, rh); val pc = c.premultiplied
          var i = 0
          while i < base.data.length do { val a = base.data(i + 3); shadow.data(i) = pc.r * a; shadow.data(i + 1) = pc.g * a; shadow.data(i + 2) = pc.b * a; shadow.data(i + 3) = pc.a * a; i += 4 }
          val b = if sdx == 0 && sdy == 0 then shadow else blur(shadow, scaleX(sdx), scaleY(sdy))
          val o = offset(b, dx.toInt, dy.toInt); Blend.composite(o, base); Some(o)
        case "feImage" => Some(feImage(r, p, rw, rh, rx0, ry0, ctx, lin, userSub, primBBox, bbox))
        case "feTurbulence" => Some(turbulence(p, rw, rh, rx0, ry0, m, lin))
        case "feConvolveMatrix" => convolve(px1, pa)
        case "feDisplacementMap" => Some(displacement(px1, px2, pa, scaleX(1.0), scaleY(1.0), lin))
        case "feDiffuseLighting" | "feSpecularLighting" => Some(Lighting.run(p, px1, m, bbox, primBBox, lin, rx0, ry0, r, ctx))
        case _ => Some(new Pixmap(rw, rh))
      result.foreach { px =>
        // restrict to the primitive subregion
        val cut =
          if rotated && p.name != "feOffset" then
            val poly = Vector(Point(userSub.x0, userSub.y0), Point(userSub.x1, userSub.y0), Point(userSub.x1, userSub.y1), Point(userSub.x0, userSub.y1)).map(q => { val d = m(q); Point(d.x - rx0, d.y - ry0) })
            clipPolygon(px, poly)
          else if sub == regionRect || p.name == "feOffset" then px else clipTo(px, sub) // no clipping on feOffset (as in resvg)
        val img = Res(new Img(cut, lin), sub, userSub)
        last = Some(img)
        pa.get("result").foreach(n => results(n) = img)
      }
    }
    // write the final result back (in sRGB)
    clear()
    last.foreach { res =>
      val out = res.img.in(false)
      for y <- 0 until rh do System.arraycopy(out.data, y * rw * 4, layer.data, ((ry0 + y) * layer.width + rx0) * 4, rw * 4)
    }

  private final case class Res(img: Img, sub: (Int, Int, Int, Int), user: Rect)

  private def clipPolygon(p: Pixmap, poly: Vector[Point]): Pixmap =
    val o = new Pixmap(p.width, p.height)
    Rasterizer.fill(Vector(poly), FillRule.NonZero, p.width, p.height) { (x, y, cov) =>
      val i = (y * p.width + x) * 4
      o.data(i) = p.data(i) * cov; o.data(i + 1) = p.data(i + 1) * cov; o.data(i + 2) = p.data(i + 2) * cov; o.data(i + 3) = p.data(i + 3) * cov
    }
    o

  private def clipTo(p: Pixmap, sub: (Int, Int, Int, Int)): Pixmap =
    val o = new Pixmap(p.width, p.height)
    for y <- sub._2 until sub._4; x <- sub._1 until sub._3 do
      val i = (y * p.width + x) * 4
      o.data(i) = p.data(i); o.data(i + 1) = p.data(i + 1); o.data(i + 2) = p.data(i + 2); o.data(i + 3) = p.data(i + 3)
    o

  private def fillRect(o: Pixmap, sub: (Int, Int, Int, Int), r: Float, g: Float, b: Float, a: Float): Unit =
    for y <- sub._2 until sub._4; x <- sub._1 until sub._3 do
      val i = (y * o.width + x) * 4; o.data(i) = r; o.data(i + 1) = g; o.data(i + 2) = b; o.data(i + 3) = a

  private def floodColor(p: XmlElement, r: Renderer, linear: Boolean): (Color, Boolean) =
    val raw = r.attrInherit(p, "flood-color").getOrElse("black")
    val base = (if raw == "currentColor" then Some(Color.Black) else SvgColors.parse(raw)).getOrElse(Color.Black)
    val op = r.attrInherit(p, "flood-opacity").flatMap(Values.parseFraction).map(Values.clamp01).getOrElse(1.0).toFloat
    val c = if linear then Color(Color.srgbToLinear(base.r), Color.srgbToLinear(base.g), Color.srgbToLinear(base.b), base.a * op) else base.copy(a = base.a * op)
    (c, linear)

  // ---------------------------------------------------------------- primitives
  private def offset(src: Pixmap, dx: Int, dy: Int): Pixmap =
    val o = new Pixmap(src.width, src.height)
    for y <- 0 until src.height do
      val ty = y - dy
      if ty >= 0 && ty < src.height then
        val x0 = math.max(0, dx); val x1 = math.min(src.width, src.width + dx)
        if x1 > x0 then System.arraycopy(src.data, (ty * src.width + (x0 - dx)) * 4, o.data, (y * src.width + x0) * 4, (x1 - x0) * 4)
    o

  /** Gaussian blur: box-blur approximation (three passes, as in the spec) for large deviations, a true kernel for small ones. */
  def blur(src: Pixmap, sx: Double, sy: Double): Pixmap =
    var cur = Pixmaps.copy(src)
    val w = src.width; val h = src.height
    def axis(sd: Double, horizontal: Boolean): Unit =
      if sd <= 0 then return
      val len = if horizontal then w else h; val lines = if horizontal then h else w
      val stride = if horizontal then 4 else w * 4; val lineStride = if horizontal then w * 4 else 4
      val tmp = new Array[Float](len)
      if sd >= 2.0 then
        val d = math.floor(sd * 3 * math.sqrt(2 * math.Pi) / 4 + 0.5).toInt
        if d <= 1 then return
        val offs = if d % 2 == 1 then Array((d - 1) / 2, (d - 1) / 2, (d - 1) / 2) else Array(d / 2, d / 2 - 1, d / 2)
        val widths = Array(d, d, d + (if d % 2 == 1 then 0 else 1))
        // for even d the spec uses two boxes of size d (offset left/right) and one of size d+1 centred
        val dd = Array(d, d, if d % 2 == 1 then d else d + 1)
        val left = if d % 2 == 1 then Array(d / 2, d / 2, d / 2) else Array(d / 2, d / 2 - 1, d / 2)
        val _ = (offs, widths)
        for c <- 0 until 4; l <- 0 until lines do
          val base = l * lineStride + c
          for pass <- 0 until 3 do
            var k = 0
            while k < len do { tmp(k) = cur.data(base + k * stride); k += 1 }
            val bw = dd(pass); val lf = left(pass)
            var acc = 0.0f
            // window [i - lf, i - lf + bw - 1]
            var j = -lf
            while j < -lf + bw do { if j >= 0 && j < len then acc += tmp(j); j += 1 }
            val inv = 1f / bw
            var i = 0
            while i < len do
              cur.data(base + i * stride) = acc * inv
              val out = i - lf; val in = i - lf + bw
              if out >= 0 && out < len then acc -= tmp(out)
              if in >= 0 && in < len then acc += tmp(in)
              i += 1
      else
        val rad = math.max(1, math.ceil(sd * 3).toInt)
        val k = Array.tabulate(2 * rad + 1)(i => math.exp(-((i - rad) * (i - rad)) / (2 * sd * sd)).toFloat)
        val sum = k.sum; for i <- k.indices do k(i) /= sum
        for c <- 0 until 4; l <- 0 until lines do
          val base = l * lineStride + c
          var q = 0
          while q < len do { tmp(q) = cur.data(base + q * stride); q += 1 }
          var i = 0
          while i < len do
            var acc = 0f; var t = -rad
            while t <= rad do { val j = i + t; if j >= 0 && j < len then acc += tmp(j) * k(t + rad); t += 1 }
            cur.data(base + i * stride) = acc
            i += 1
    axis(sx, true); axis(sy, false)
    cur

  private def composite(a: Pixmap, b: Pixmap, pa: Map[String, String]): Pixmap =
    val o = new Pixmap(a.width, a.height)
    val op = pa.getOrElse("operator", "over")
    val k = Seq("k1", "k2", "k3", "k4").map(n => Values.parseNumber(pa.getOrElse(n, "0")).getOrElse(0.0).toFloat)
    var i = 0
    while i < o.data.length do
      val aa = a.data(i + 3); val ba = b.data(i + 3)
      var c = 0
      op match
        case "arithmetic" =>
          val ra = (k(0) * aa * ba + k(1) * aa + k(2) * ba + k(3)).max(0f).min(1f)
          while c < 3 do
            val v = k(0) * a.data(i + c) * b.data(i + c) + k(1) * a.data(i + c) + k(2) * b.data(i + c) + k(3)
            o.data(i + c) = v.max(0f).min(ra); c += 1
          o.data(i + 3) = ra
        case _ =>
          val (fa, fb) = op match
            case "in" => (ba, 0f); case "out" => (1 - ba, 0f); case "atop" => (ba, 1 - aa); case "xor" => (1 - ba, 1 - aa); case "lighter" => (1f, 1f); case _ => (1f, 1 - aa)
          while c < 4 do { o.data(i + c) = a.data(i + c) * fa + b.data(i + c) * fb; c += 1 }
      i += 4
    o

  private def colorMatrix(src: Pixmap, pa: Map[String, String]): Pixmap =
    val tpe = pa.getOrElse("type", "matrix")
    val vals = Values.numbers(pa.getOrElse("values", ""))
    val m: Array[Float] = tpe match
      case "saturate" =>
        val s = vals.headOption.map(_.max(0.0)).getOrElse(1.0).toFloat
        Array(0.213f + 0.787f * s, 0.715f - 0.715f * s, 0.072f - 0.072f * s, 0, 0, 0.213f - 0.213f * s, 0.715f + 0.285f * s, 0.072f - 0.072f * s, 0, 0,
          0.213f - 0.213f * s, 0.715f - 0.715f * s, 0.072f + 0.928f * s, 0, 0, 0, 0, 0, 1, 0)
      case "hueRotate" =>
        val a = math.toRadians(vals.headOption.getOrElse(0.0)); val c = math.cos(a).toFloat; val s = math.sin(a).toFloat
        Array(0.213f + c * 0.787f - s * 0.213f, 0.715f - c * 0.715f - s * 0.715f, 0.072f - c * 0.072f + s * 0.928f, 0, 0,
          0.213f - c * 0.213f + s * 0.143f, 0.715f + c * 0.285f + s * 0.140f, 0.072f - c * 0.072f - s * 0.283f, 0, 0,
          0.213f - c * 0.213f - s * 0.787f, 0.715f - c * 0.715f + s * 0.715f, 0.072f + c * 0.928f + s * 0.072f, 0, 0, 0, 0, 0, 1, 0)
      case "luminanceToAlpha" => Array(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0.2125f, 0.7154f, 0.0721f, 0, 0)
      case _ => if vals.length == 20 then vals.map(_.toFloat) else Array(1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 1, 0).map(_.toFloat)
    val o = new Pixmap(src.width, src.height)
    var i = 0
    while i < src.data.length do
      val a = src.data(i + 3)
      val r = if a > 0 then src.data(i) / a else 0f; val g = if a > 0 then src.data(i + 1) / a else 0f; val b = if a > 0 then src.data(i + 2) / a else 0f
      def cl(v: Float) = if v < 0f then 0f else if v > 1f then 1f else v
      val nr = cl(m(0) * r + m(1) * g + m(2) * b + m(3) * a + m(4)); val ng = cl(m(5) * r + m(6) * g + m(7) * b + m(8) * a + m(9))
      val nb = cl(m(10) * r + m(11) * g + m(12) * b + m(13) * a + m(14)); val na = cl(m(15) * r + m(16) * g + m(17) * b + m(18) * a + m(19))
      o.data(i) = nr * na; o.data(i + 1) = ng * na; o.data(i + 2) = nb * na; o.data(i + 3) = na
      i += 4
    o

  private def componentTransfer(src: Pixmap, p: XmlElement): Pixmap =
    def fn(ch: String): Float => Float =
      p.children.findLast(_.name == "feFunc" + ch) match
        case None => identity
        case Some(f) =>
          val fa = f.attrs
          def n(k: String, d: Double) = Values.parseNumber(fa.getOrElse(k, d.toString)).getOrElse(d).toFloat
          fa.getOrElse("type", "identity") match
            case "table" =>
              val tv = Values.numbers(fa.getOrElse("tableValues", "")).map(_.toFloat)
              if tv.isEmpty then identity else c => { val nn = tv.length - 1; val k = math.min((c * nn).toInt, nn - 1).max(0); if nn == 0 then tv(0) else tv(k) + (c - k.toFloat / nn) * nn * (tv(k + 1) - tv(k)) }
            case "discrete" =>
              val tv = Values.numbers(fa.getOrElse("tableValues", "")).map(_.toFloat)
              if tv.isEmpty then identity else c => tv(math.min((c * tv.length).toInt, tv.length - 1))
            case "linear" => val s = n("slope", 1); val ic = n("intercept", 0); c => s * c + ic
            case "gamma" => val a = n("amplitude", 1); val e = n("exponent", 1); val o = n("offset", 0); c => (a * math.pow(c, e) + o).toFloat
            case _ => identity
    val fr = fn("R"); val fg = fn("G"); val fb = fn("B"); val fa = fn("A")
    val o = new Pixmap(src.width, src.height)
    def cl(v: Float) = if v < 0f then 0f else if v > 1f then 1f else v
    var i = 0
    while i < src.data.length do
      val a = src.data(i + 3)
      val r = if a > 0 then src.data(i) / a else 0f; val g = if a > 0 then src.data(i + 1) / a else 0f; val b = if a > 0 then src.data(i + 2) / a else 0f
      val na = cl(fa(a))
      o.data(i) = cl(fr(r)) * na; o.data(i + 1) = cl(fg(g)) * na; o.data(i + 2) = cl(fb(b)) * na; o.data(i + 3) = na
      i += 4
    o

  private def morphology(src: Pixmap, rx: Int, ry: Int, dilate: Boolean): Pixmap =
    val w = src.width; val h = src.height
    val tmp = new Pixmap(w, h); val out = new Pixmap(w, h)
    def pass(from: Pixmap, to: Pixmap, r: Int, horizontal: Boolean): Unit =
      for y <- 0 until h; x <- 0 until w; c <- 0 until 4 do
        var v = if dilate then 0f else 1f
        var t = -r
        while t <= r do
          val xx = if horizontal then x + t else x; val yy = if horizontal then y else y + t
          val s = if xx < 0 || yy < 0 || xx >= w || yy >= h then 0f else from.data((yy * w + xx) * 4 + c)
          v = if dilate then math.max(v, s) else math.min(v, s)
          t += 1
        to.data((y * w + x) * 4 + c) = v
    pass(src, tmp, math.max(0, math.min(rx, w)), true); pass(tmp, out, math.max(0, math.min(ry, h)), false)
    out

  private def tile(src: Pixmap, sub: (Int, Int, Int, Int)): Pixmap =
    val tw = sub._3 - sub._1; val th = sub._4 - sub._2
    val o = new Pixmap(src.width, src.height)
    if tw <= 0 || th <= 0 then return o
    for y <- 0 until src.height; x <- 0 until src.width do
      val sx = sub._1 + (((x - sub._1) % tw) + tw) % tw; val sy = sub._2 + (((y - sub._2) % th) + th) % th
      val i = (y * src.width + x) * 4; val j = (sy * src.width + sx) * 4
      o.data(i) = src.data(j); o.data(i + 1) = src.data(j + 1); o.data(i + 2) = src.data(j + 2); o.data(i + 3) = src.data(j + 3)
    o

  private def feImage(r: Renderer, p: XmlElement, rw: Int, rh: Int, rx0: Int, ry0: Int, ctx: Ctx, lin: Boolean, userSub: Rect, primBBox: Boolean, bbox: Option[Rect]): Pixmap =
    val o = new Pixmap(rw, rh)
    val h = r.href(p).getOrElse("")
    val shift = Affine.translate(Vec2(-rx0, -ry0))
    if h.startsWith("#") then
      r.byId(h.drop(1)).foreach { t =>
        // an element reference is drawn like `use`, offset by the primitive's x/y when given
        def off(k: String, size: Double): Double = p.attrs.get(k).flatMap(Values.parseLen).map(l => if primBBox then (if l.unit == "%" then l.v / 100 else l.v) * size else Style.length(l, ctx.style.fontSize, ctx.vw, ctx.vh, 'x')).getOrElse(0.0)
        val tx = off("x", bbox.map(_.width).getOrElse(0.0)); val ty = off("y", bbox.map(_.height).getOrElse(0.0))
        r.drawElement(t, Ctx(o, shift * ctx.ctm * Affine.translate(Vec2(tx, ty)), ctx.style, ctx.vw, ctx.vh, ctx.depth + 1, ctx.stack + t))
      }
    else
      r.doc.loadImage(h) match
        case Some(Left(img)) =>
          val par = p.attrs.getOrElse("preserveAspectRatio", "xMidYMid meet")
          val t = Geometry.viewBoxTransform(Rect(0, 0, img.width, img.height), par, userSub.width, userSub.height)
          val toDev = shift * ctx.ctm * Affine.translate(Vec2(userSub.x0, userSub.y0)) * t
          if toDev.isInvertible then
            val paint = new PixmapPaint(toDev.inverse, Pixmaps.fromImage(img), wrap = false, smooth = true, opacity = 1f)
            val area = t.transformRectBbox(Rect(0, 0, img.width, img.height)).translate(Vec2(userSub.x0, userSub.y0)).intersect(userSub)
            if !area.isEmpty then r.fillPath(o, Path.rect(area), paint, FillRule.NonZero, shift * ctx.ctm)
        case Some(Right(sub)) =>
          val par = p.attrs.getOrElse("preserveAspectRatio", "xMidYMid meet")
          val t = Geometry.viewBoxTransform(Rect(0, 0, sub.width, sub.height), par, userSub.width, userSub.height)
          val mm = shift * ctx.ctm * Affine.translate(Vec2(userSub.x0, userSub.y0)) * t
          scalaui.imaging.Layers.layer(o, clip = Some(Path.rect(Rect(0, 0, sub.width, sub.height))), transform = mm) { l =>
            new Renderer(sub).renderRoot(l, mm * sub.rootTransform)
          }
        case None => ()
    if lin then convert(o, toLinear = true) else o

  // ---- feTurbulence (reference implementation from the SVG specification)
  private def turbulence(p: XmlElement, rw: Int, rh: Int, rx0: Int, ry0: Int, m: Affine, lin: Boolean): Pixmap =
    val pa = p.attrs
    val bf = Values.numbers(pa.getOrElse("baseFrequency", "0"))
    val bfx = bf.lift(0).getOrElse(0.0); val bfy = bf.lift(1).getOrElse(bfx)
    val octaves = Values.parseNumber(pa.getOrElse("numOctaves", "1")).map(_.toInt).getOrElse(1)
    val seed = Values.parseNumber(pa.getOrElse("seed", "0")).map(d => math.round(d).toInt).getOrElse(0)
    val fractal = pa.getOrElse("type", "turbulence") == "fractalNoise"
    val stitch = pa.get("stitchTiles").contains("stitch")
    val o = new Pixmap(rw, rh)
    if bfx < 0 || bfy < 0 || octaves < 0 || !m.isInvertible then return o
    val noise = new Turbulence(seed)
    val inv = m.inverse
    for y <- 0 until rh; x <- 0 until rw do
      val pt = inv(Point(x + rx0, y + ry0))
      val v = Array(0.0, 0.0, 0.0, 0.0)
      for ch <- 0 until 4 do
        val t = noise.turbulence(ch, pt.x, pt.y, bfx, bfy, octaves, fractal, stitch, 0, 0, rw, rh)
        v(ch) = if fractal then (t * 255 + 255) / 2 else t * 255
      def cl(d: Double) = (if d < 0 then 0.0 else if d > 255 then 255.0 else d) / 255.0
      val a = cl(v(3)).toFloat
      val i = (y * rw + x) * 4
      o.data(i) = cl(v(0)).toFloat * a; o.data(i + 1) = cl(v(1)).toFloat * a; o.data(i + 2) = cl(v(2)).toFloat * a; o.data(i + 3) = a
    // the generated values are interpreted in the working colour space already
    val _ = lin
    o

  private def convolve(src: Pixmap, pa: Map[String, String]): Option[Pixmap] =
    val dummy = Some(new Pixmap(src.width, src.height))
    val order = Values.numbers(pa.getOrElse("order", "3"))
    if order.isEmpty || order.length > 2 then return dummy
    val ox0 = order(0); val oy0 = order.lift(1).getOrElse(ox0)
    if ox0 <= 0 || oy0 <= 0 || ox0 != ox0.floor || oy0 != oy0.floor then return dummy
    val ox = ox0.toInt; val oy = oy0.toInt
    val km = Values.numbers(pa.getOrElse("kernelMatrix", "")).map(_.toFloat)
    if km.length != ox * oy then return dummy
    val sum = km.sum
    val div = pa.get("divisor").flatMap(Values.parseNumber) match
      case Some(0.0) => return dummy
      case Some(d) => d.toFloat
      case None => if sum == 0f then 1f else sum
    val bias = Values.parseNumber(pa.getOrElse("bias", "0")).getOrElse(0.0).toFloat
    val tx = pa.get("targetX").flatMap(Values.parseNumber).map(_.toInt).getOrElse(ox / 2)
    val ty = pa.get("targetY").flatMap(Values.parseNumber).map(_.toInt).getOrElse(oy / 2)
    if tx < 0 || tx >= ox || ty < 0 || ty >= oy then return dummy
    val edge = pa.getOrElse("edgeMode", "duplicate")
    val preserveAlpha = pa.get("preserveAlpha").contains("true")
    val w = src.width; val h = src.height
    val o = new Pixmap(w, h)
    def get(x: Int, y: Int, c: Int): Float =
      var xx = x; var yy = y
      if xx < 0 || yy < 0 || xx >= w || yy >= h then
        edge match
          case "wrap" => xx = ((xx % w) + w) % w; yy = ((yy % h) + h) % h
          case "none" => return 0f
          case _ => xx = math.max(0, math.min(w - 1, xx)); yy = math.max(0, math.min(h - 1, yy))
      src.data((yy * w + xx) * 4 + c)
    for y <- 0 until h; x <- 0 until w do
      val acc = Array(0f, 0f, 0f, 0f)
      for j <- 0 until oy; i <- 0 until ox do
        val k = km((oy - 1 - j) * ox + (ox - 1 - i))
        val sxp = x - tx + i; val syp = y - ty + j
        if preserveAlpha then
          val a = get(sxp, syp, 3)
          for c <- 0 until 3 do acc(c) += (if a > 0 then get(sxp, syp, c) / a else 0f) * k
        else for c <- 0 until 4 do acc(c) += get(sxp, syp, c) * k
      val idx = (y * w + x) * 4
      val a = if preserveAlpha then src.data(idx + 3) else (acc(3) / div + bias).max(0f).min(1f)
      for c <- 0 until 3 do
        val v = (acc(c) / div + bias * (if preserveAlpha then 1f else a)).max(0f).min(1f)
        o.data(idx + c) = if preserveAlpha then v * a else math.min(v, a)
      o.data(idx + 3) = a
    Some(o)

  private def displacement(src: Pixmap, map: Pixmap, pa: Map[String, String], sx: Double, sy: Double, lin: Boolean): Pixmap =
    val scale = Values.parseNumber(pa.getOrElse("scale", "0")).getOrElse(0.0)
    def chan(n: String) = pa.getOrElse(n, "A") match { case "R" => 0; case "G" => 1; case "B" => 2; case _ => 3 }
    val xc = chan("xChannelSelector"); val yc = chan("yChannelSelector")
    val w = src.width; val h = src.height
    val o = new Pixmap(w, h)
    val _ = lin
    for y <- 0 until h; x <- 0 until w do
      val i = (y * w + x) * 4
      val a = map.data(i + 3)
      def v(c: Int): Double = if c == 3 then a else if a > 0 then map.data(i + c) / a else 0f
      val dx = (scale * sx * (v(xc) - 0.5)); val dy = (scale * sy * (v(yc) - 0.5))
      val px = (x + dx).round.toInt; val py = (y + dy).round.toInt
      if px >= 0 && py >= 0 && px < w && py < h then
        val j = (py * w + px) * 4
        o.data(i) = src.data(j); o.data(i + 1) = src.data(j + 1); o.data(i + 2) = src.data(j + 2); o.data(i + 3) = src.data(j + 3)
    o

/** Perlin turbulence generator, a transcription of the reference code in the SVG 1.1 specification. */
private[svg] final class Turbulence(seed0: Int):
  private val BSize = 0x100; private val BM = 0xff; private val PerlinN = 0x1000
  private val lattice = new Array[Int](BSize + BSize + 2)
  private val gradient = Array.ofDim[Double](4, BSize + BSize + 2, 2)
  private def random(seed: Long): Long =
    val RAND_m = 2147483647L; val RAND_a = 16807L; val RAND_q = 127773L; val RAND_r = 2836L
    var result = RAND_a * (seed % RAND_q) - RAND_r * (seed / RAND_q)
    if result <= 0 then result += RAND_m
    result
  init()
  private def init(): Unit =
    var seed: Long = seed0.toLong
    if seed <= 0 then seed = -(seed % 2147483646L) + 1
    if seed > 2147483646L then seed = 2147483646L
    for k <- 0 until 4; i <- 0 until BSize do
      lattice(i) = i
      for j <- 0 until 2 do
        seed = random(seed)
        gradient(k)(i)(j) = (((seed % (BSize + BSize)) - BSize).toDouble) / BSize
      val s = math.sqrt(gradient(k)(i)(0) * gradient(k)(i)(0) + gradient(k)(i)(1) * gradient(k)(i)(1))
      gradient(k)(i)(0) /= s; gradient(k)(i)(1) /= s
    var i = BSize - 1
    while i > 0 do
      val k = lattice(i); seed = random(seed); val j = (seed % BSize).toInt
      lattice(i) = lattice(j); lattice(j) = k; i -= 1
    for i <- 0 until BSize + 2 do
      lattice(BSize + i) = lattice(i)
      for k <- 0 until 4; j <- 0 until 2 do gradient(k)(BSize + i)(j) = gradient(k)(i)(j)

  private def sCurve(t: Double) = t * t * (3.0 - 2.0 * t)
  private def lerp(t: Double, a: Double, b: Double) = a + t * (b - a)

  private def noise2(ch: Int, vx: Double, vy: Double, stitch: Boolean, wrapX: Int, wrapY: Int, w: Int, h: Int): Double =
    var t = vx + PerlinN
    var bx0 = t.toInt; var bx1 = bx0 + 1; val rx0 = t - t.toInt; val rx1 = rx0 - 1.0
    t = vy + PerlinN
    var by0 = t.toInt; var by1 = by0 + 1; val ry0 = t - t.toInt; val ry1 = ry0 - 1.0
    if stitch then
      if bx0 >= wrapX then bx0 -= w; if bx1 >= wrapX then bx1 -= w
      if by0 >= wrapY then by0 -= h; if by1 >= wrapY then by1 -= h
    bx0 &= BM; bx1 &= BM; by0 &= BM; by1 &= BM
    val i = lattice(bx0); val j = lattice(bx1)
    val b00 = lattice(i + by0); val b10 = lattice(j + by0); val b01 = lattice(i + by1); val b11 = lattice(j + by1)
    val sx = sCurve(rx0); val sy = sCurve(ry0)
    var u = rx0 * gradient(ch)(b00)(0) + ry0 * gradient(ch)(b00)(1)
    var v = rx1 * gradient(ch)(b10)(0) + ry0 * gradient(ch)(b10)(1)
    val a = lerp(sx, u, v)
    u = rx0 * gradient(ch)(b01)(0) + ry1 * gradient(ch)(b01)(1)
    v = rx1 * gradient(ch)(b11)(0) + ry1 * gradient(ch)(b11)(1)
    val b = lerp(sx, u, v)
    lerp(sy, a, b)

  def turbulence(ch: Int, px: Double, py: Double, bfx0: Double, bfy0: Double, octaves: Int, fractal: Boolean, stitch0: Boolean, tx: Double, ty: Double, tw: Double, th: Double): Double =
    var bfx = bfx0; var bfy = bfy0
    var stitch = stitch0
    var wrapX = 0; var wrapY = 0; var w = 0; var h = 0
    if stitch then
      if bfx != 0.0 then { val lo = math.floor(tw * bfx) / tw; val hi = math.ceil(tw * bfx) / tw; bfx = if bfx / lo < hi / bfx then lo else hi }
      if bfy != 0.0 then { val lo = math.floor(th * bfy) / th; val hi = math.ceil(th * bfy) / th; bfy = if bfy / lo < hi / bfy then lo else hi }
      w = (tw * bfx + 0.5).toInt; wrapX = (tx * bfx + PerlinN + w).toInt
      h = (th * bfy + 0.5).toInt; wrapY = (ty * bfy + PerlinN + h).toInt
    var sum = 0.0; var vx = px * bfx; var vy = py * bfy; var ratio = 1.0
    for _ <- 0 until octaves do
      val n = noise2(ch, vx, vy, stitch, wrapX, wrapY, w, h)
      sum += (if fractal then n else math.abs(n)) / ratio
      vx *= 2; vy *= 2; ratio *= 2
      if stitch then { w *= 2; wrapX = 2 * wrapX - PerlinN; h *= 2; wrapY = 2 * wrapY - PerlinN }
    sum
