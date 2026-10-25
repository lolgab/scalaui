package scalaui.svg

import scalaui.codecs.Image
import scalaui.color.Color
import scalaui.geom.*
import scalaui.imaging.{BlendMode, ColorGlyphs, Layers, Pixmaps}
import scalaui.raster.*
import scala.collection.mutable

/** Drawing state threaded through the element tree. `vw`/`vh` is the nearest viewport (for percentage lengths). */
private[svg] final case class Ctx(pm: Pixmap, ctm: Affine, style: Style, vw: Double, vh: Double, depth: Int = 0, stack: Set[XmlElement] = Set.empty,
    contextFill: Option[Paint] = None, contextStroke: Option[Paint] = None)

/** Renders a [[SvgDocument]] tree into a [[Pixmap]]. One instance per render call. */
private[svg] final class Renderer(val doc: SvgDocument):
  import Renderer.*

  private val styleCache = mutable.HashMap.empty[XmlElement, Style]
  private[svg] val activeFilters = mutable.ArrayBuffer.empty[XmlElement]
  private val activeRefs = mutable.ArrayBuffer.empty[XmlElement]
  private def withActive[T](e: XmlElement)(f: => T): T = { activeRefs += e; try f finally activeRefs -= e }
  private def allOnes(ctx: Ctx): Pixmap = { val p = new Pixmap(ctx.pm.width, ctx.pm.height); var i = 3; while i < p.data.length do { p.data(i) = 1f; i += 4 }; p }

  // ------------------------------------------------------------------ lookup helpers
  def byId(id: String): Option[XmlElement] = doc.ids.get(id)
  def href(e: XmlElement): Option[String] = e.attrs.get("href").orElse(e.attrs.get("xlink:href")).map(_.trim)
  def hrefTarget(e: XmlElement): Option[XmlElement] = href(e).filter(_.startsWith("#")).flatMap(h => byId(h.drop(1)))

  /** Style a definition (marker, pattern, mask, clipPath...) inherits from its own ancestors. */
  def definitionStyle(e: XmlElement): Style = styleCache.getOrElseUpdate(e, {
    val chain = Iterator.iterate(doc.parents.get(e))(p => p.flatMap(doc.parents.get)).takeWhile(_.isDefined).flatten.toList.reverse
    (chain :+ e).foldLeft(Style.Default)((s, x) => Style.derive(x.attrs, s, doc.vbWidth, doc.vbHeight))
  })

  /** Attribute value with explicit `inherit` resolved through the parent chain (for non-inherited properties such as `flood-color`). */
  def attrInherit(e: XmlElement, k: String): Option[String] =
    var cur: Option[XmlElement] = Some(e)
    while cur.isDefined do
      cur.get.attrs.get(k).map(_.trim) match
        case Some("inherit") => cur = doc.parents.get(cur.get)
        case v => return v
    None

  def len(a: Map[String, String], k: String, ctx: Ctx, axis: Char, default: Double = 0.0): Double =
    a.get(k).flatMap(Values.parseLen).map(Style.length(_, ctx.style.fontSize, ctx.vw, ctx.vh, axis)).getOrElse(default)
  def lenOpt(a: Map[String, String], k: String, ctx: Ctx, axis: Char): Option[Double] =
    a.get(k).flatMap(Values.parseLen).map(Style.length(_, ctx.style.fontSize, ctx.vw, ctx.vh, axis))

  // ------------------------------------------------------------------ entry
  def renderRoot(pm: Pixmap, base: Affine): Unit =
    val root = doc.root
    drawElement(root, Ctx(pm, base, Style.Default, doc.vbWidth, doc.vbHeight), isRoot = true)

  // ------------------------------------------------------------------ element dispatch
  private def conditionsPass(a: Map[String, String]): Boolean =
    a.get("systemLanguage").forall { v =>
      v.split(',').map(_.trim).exists(l => l == "en" || l.startsWith("en-"))
    } && a.get("requiredExtensions").forall(_ => false) // unsupported extensions are never available

  /** `transform` with `transform-origin` applied; percentages and keywords refer to the object bounding box. */
  def transformOf(e: XmlElement, key: String, ctx: Ctx, bbox: => Option[Rect], unitBox: Boolean = false): Option[Affine] =
    val t = e.attrs.get(key).map(Transforms.parseOpt).getOrElse(Some(Affine.Identity))
    t.map { tr =>
      e.attrs.get("transform-origin").map(_.trim).filter(_.nonEmpty) match
        case None => tr
        case Some(o) =>
          val parts = o.split("\\s+").toList
          val box = if unitBox then Some(Rect(0, 0, 1, 1)) else bbox
          def comp(s: String, horizontal: Boolean): Double =
            val size = box.map(b => if horizontal then b.width else b.height).getOrElse(0.0); val origin = box.map(b => if horizontal then b.x0 else b.y0).getOrElse(0.0)
            s match
              case "left" | "top" => origin
              case "center" => origin + size / 2
              case "right" | "bottom" => origin + size
              case v => Values.parseLen(v).map(l => if l.unit == "%" then origin + l.v / 100 * size else Style.length(l, ctx.style.fontSize, ctx.vw, ctx.vh, if horizontal then 'x' else 'y')).getOrElse(origin)
          val (hx, vy) = parts match
            case a :: b :: _ if Set("top", "bottom").contains(a) || Set("left", "right").contains(b) => (comp(b, true), comp(a, false))
            case a :: b :: _ => (comp(a, true), comp(b, false))
            case a :: Nil if Set("top", "bottom").contains(a) => (comp("center", true), comp(a, false))
            case a :: Nil => (comp(a, true), comp("center", false))
            case Nil => (0.0, 0.0)
          Affine.translate(Vec2(hx, vy)) * tr * Affine.translate(Vec2(-hx, -vy))
    }

  private def blendModeOf(s: Option[String]): BlendMode = s.map(_.trim) match
    case Some("multiply") => BlendMode.Multiply; case Some("screen") => BlendMode.Screen; case Some("overlay") => BlendMode.Overlay
    case Some("darken") => BlendMode.Darken; case Some("lighten") => BlendMode.Lighten; case Some("color-dodge") => BlendMode.ColorDodge
    case Some("color-burn") => BlendMode.ColorBurn; case Some("hard-light") => BlendMode.HardLight; case Some("soft-light") => BlendMode.SoftLight
    case Some("difference") => BlendMode.Difference; case Some("exclusion") => BlendMode.Exclusion; case Some("hue") => BlendMode.Hue
    case Some("saturation") => BlendMode.Saturation; case Some("color") => BlendMode.Color; case Some("luminosity") => BlendMode.Luminosity
    case _ => BlendMode.Normal

  def drawElement(e: XmlElement, ctx: Ctx, isRoot: Boolean = false, custom: Ctx => Unit = null): Unit =
    if ctx.depth > 60 then return
    if !Renderer.Graphic.contains(e.name) then return
    val a = e.attrs
    if a.get("display").exists(_.trim == "none") then return
    if !conditionsPass(a) then return
    val st = Style.derive(a, ctx.style, ctx.vw, ctx.vh)
    lazy val bbox = objectBBox(e, ctx.copy(ctm = Affine.Identity, style = st))
    val t = if isRoot then Affine.Identity else transformOf(e, "transform", ctx.copy(style = st), bbox).getOrElse(Affine.Identity)
    if !t.isInvertible then return
    val m = ctx.ctm * t
    val ctx1 = ctx.copy(ctm = m, style = st, depth = ctx.depth + 1)

    val opacity = a.get("opacity").flatMap(Values.parseFraction).map(Values.clamp01).getOrElse(1.0)
    val clipEl = a.get("clip-path").flatMap(Values.urlRef).flatMap(r => byId(r._1)).filter(_.name == "clipPath")
    val maskEl = a.get("mask").flatMap(Values.urlRef).flatMap(r => byId(r._1)).filter(_.name == "mask")
    val filterAttr = a.get("filter").map(_.trim).filter(f => f.nonEmpty && f != "none")
    val filters = filterAttr.map(FilterEngine.parseList(_, this))
    // a `filter` that references a missing element disables rendering of the element (spec); invalid functions are ignored
    if filters.exists(_.exists(_.isInvalid)) then return
    val blend = blendModeOf(a.get("mix-blend-mode"))
    val isolate = a.get("isolation").exists(_.trim == "isolate")
    val needLayer = opacity < 1.0 || clipEl.isDefined || maskEl.isDefined || filters.exists(_.nonEmpty) || blend != BlendMode.Normal || (isolate && e.name != "svg" || isolate && !isRoot)
    if isRoot then
      a.get("background-color").flatMap(SvgColors.parse).foreach { c =>
        fillPath(ctx.pm, Path.rect(Rect(0, 0, doc.vbWidth, doc.vbHeight).translate(Vec2(doc.viewBox.map(_.x0).getOrElse(0.0), doc.viewBox.map(_.y0).getOrElse(0.0)))), Paint.solid(c), FillRule.NonZero, ctx.ctm)
      }
    def content(c: Ctx): Unit = if custom != null then custom(c) else drawContent(e, c, isRoot)
    if !needLayer then content(ctx1)
    else
      var maskPm: Option[Pixmap] = None
      def mulMask(p: Pixmap): Unit = maskPm match
        case None => maskPm = Some(p)
        case Some(q) => var i = 3; while i < q.data.length do { q.data(i) *= p.data(i); i += 4 }
      var skip = false
      clipEl.foreach { c => buildClip(c, ctx1, bbox) match { case Some(p) => mulMask(p); case None => skip = true } }
      maskEl.foreach { mk => buildMask(mk, ctx1, bbox) match { case Some(p) => mulMask(p); case None => skip = true } }
      if !skip then
        Layers.layer(ctx.pm, opacity.toFloat, mode = blend, mask = maskPm) { l =>
          content(ctx1.copy(pm = l))
          filters.foreach(fs => FilterEngine.apply(this, l, fs, bbox, ctx1.copy(pm = l)))
        }

  private def drawChildren(e: XmlElement, ctx: Ctx): Unit = e.children.foreach(drawElement(_, ctx))

  private def drawContent(e: XmlElement, ctx: Ctx, isRoot: Boolean): Unit =
    val a = e.attrs
    e.name match
      case "g" | "a" => drawChildren(e, ctx)
      case "switch" =>
        e.children.find(c => Renderer.Graphic.contains(c.name) && conditionsPass(c.attrs)).foreach(drawElement(_, ctx))
      case "svg" =>
        if isRoot then drawChildren(e, ctx)
        else drawViewport(e, ctx, lenOpt(a, "width", ctx, 'x'), lenOpt(a, "height", ctx, 'y'))(c => drawChildren(e, c))
      case "use" => drawUse(e, ctx)
      case "image" => drawImage(e, ctx)
      case "text" => drawText(e, ctx)
      case "path" | "rect" | "circle" | "ellipse" | "line" | "polyline" | "polygon" =>
        shapePath(e, ctx).foreach(p => paintShape(p, ctx, markers = true))
      case _ => ()

  // ------------------------------------------------------------------ viewports (nested svg, symbol)
  /** Establishes a viewport at (x,y) of size w x h (defaults to 100%), applies viewBox/preserveAspectRatio and clips unless overflow is visible. */
  private def drawViewport(e: XmlElement, ctx: Ctx, wOverride: Option[Double], hOverride: Option[Double])(draw: Ctx => Unit): Unit =
    val a = e.attrs
    val x = len(a, "x", ctx, 'x'); val y = len(a, "y", ctx, 'y')
    val w = wOverride.getOrElse(ctx.vw); val h = hOverride.getOrElse(ctx.vh)
    if w <= 0 || h <= 0 then return
    val vb = Geometry.parseViewBox(a.get("viewBox"))
    val par = a.getOrElse("preserveAspectRatio", "xMidYMid meet")
    val inner = vb.map(Geometry.viewBoxTransform(_, par, w, h)).getOrElse(Affine.Identity)
    val ov = a.get("overflow").map(_.trim).getOrElse("hidden")
    val ctx2 = ctx.copy(ctm = ctx.ctm * Affine.translate(Vec2(x, y)) * inner, vw = vb.map(_.width).getOrElse(w), vh = vb.map(_.height).getOrElse(h))
    if ov == "visible" || ov == "auto" then draw(ctx2)
    else
      val clipPath = Path.rect(Rect(x, y, x + w, y + h))
      Layers.layer(ctx.pm, clip = Some(clipPath), transform = ctx.ctm) { l => draw(ctx2.copy(pm = l)) }

  private def drawUse(e: XmlElement, c0: Ctx): Unit =
    val a = e.attrs
    hrefTarget(e).foreach { target =>
      if c0.stack.contains(target) || isAncestor(target, e) then return
      val x = len(a, "x", c0, 'x'); val y = len(a, "y", c0, 'y')
      // the `use` element is the context element of its content (context-fill / context-stroke)
      val ubox = objectBBox(e, c0.copy(ctm = Affine.Identity))
      val ctx = c0.copy(stack = c0.stack + target,
        contextFill = resolvePaint(c0.style.fill, c0.style.fillOpacity, ubox, c0, isStroke = false, c0.style.color),
        contextStroke = resolvePaint(c0.style.stroke, c0.style.strokeOpacity, ubox, c0, isStroke = true, c0.style.color))
      target.name match
        case "symbol" =>
          val w = lenOpt(a, "width", ctx, 'x').orElse(lenOpt(target.attrs, "width", ctx, 'x')); val h = lenOpt(a, "height", ctx, 'y').orElse(lenOpt(target.attrs, "height", ctx, 'y'))
          val sym = new XmlElement("symbol", target.attrs ++ Map("x" -> x.toString, "y" -> y.toString), target.nodes)
          // the symbol is a group of its own: opacity, clip-path, mask and filter on it apply around its viewport
          val group = new XmlElement("g", target.attrs - "x" - "y" - "width" - "height" - "transform", target.nodes)
          drawElement(group, ctx, custom = c => drawViewport(sym, c, w, h)(cc => drawChildren(target, cc)))
        case "svg" =>
          val w = lenOpt(a, "width", ctx, 'x').orElse(lenOpt(target.attrs, "width", ctx, 'x')); val h = lenOpt(a, "height", ctx, 'y').orElse(lenOpt(target.attrs, "height", ctx, 'y'))
          val ta = target.attrs ++ Map("x" -> (x + len(target.attrs, "x", ctx, 'x')).toString, "y" -> (y + len(target.attrs, "y", ctx, 'y')).toString)
          val te = new XmlElement("svg", ta - "width" - "height", target.nodes)
          val group = new XmlElement("g", target.attrs - "x" - "y" - "width" - "height", target.nodes)
          drawElement(group, ctx, custom = c => drawViewport(te, c, w, h)(cc => drawChildren(target, cc)))
        case _ =>
          drawElement(target, ctx.copy(ctm = ctx.ctm * Affine.translate(Vec2(x, y))))
    }

  private def isAncestor(target: XmlElement, e: XmlElement): Boolean =
    var p = doc.parents.get(e)
    while p.isDefined do { if p.get eq target then return true; p = doc.parents.get(p.get) }
    false

  // ------------------------------------------------------------------ shapes
  def shapePath(e: XmlElement, ctx: Ctx): Option[Path] =
    val a = e.attrs
    def l(k: String, axis: Char) = len(a, k, ctx, axis)
    e.name match
      case "path" => a.get("d").map(PathData.parse).filter(_.elements.nonEmpty)
      case "rect" =>
        val w = l("width", 'x'); val h = l("height", 'y')
        if w <= 0 || h <= 0 then None
        else
          val rx0 = lenOpt(a, "rx", ctx, 'x').filter(_ >= 0); val ry0 = lenOpt(a, "ry", ctx, 'y').filter(_ >= 0)
          val rx = math.min(rx0.orElse(ry0).getOrElse(0.0), w / 2); val ry = math.min(ry0.orElse(rx0).getOrElse(0.0), h / 2)
          Some(Geometry.roundRect(l("x", 'x'), l("y", 'y'), w, h, rx, ry))
      case "circle" => val r = l("r", 'd'); if r > 0 then Some(Path.circle(Point(l("cx", 'x'), l("cy", 'y')), r)) else None
      case "ellipse" =>
        val rx0 = lenOpt(a, "rx", ctx, 'x').filter(_ >= 0); val ry0 = lenOpt(a, "ry", ctx, 'y').filter(_ >= 0)
        val rx = rx0.orElse(ry0).getOrElse(0.0); val ry = ry0.orElse(rx0).getOrElse(0.0)
        if rx > 0 && ry > 0 then Some(Path.ellipse(Point(l("cx", 'x'), l("cy", 'y')), rx, ry)) else None
      case "line" => Some(Path.builder.moveTo(l("x1", 'x'), l("y1", 'y')).lineTo(l("x2", 'x'), l("y2", 'y')).build)
      case "polyline" | "polygon" =>
        val nums = Values.numbers(a.getOrElse("points", ""))
        val n = nums.length / 2
        if n < 2 then None
        else
          val pb = Path.builder.moveTo(nums(0), nums(1))
          for i <- 1 until n do pb.lineTo(nums(2 * i), nums(2 * i + 1))
          if e.name == "polygon" then pb.close()
          Some(pb.build)
      case _ => None

  /** Fills, strokes and (for basic shapes) draws markers of a user-space path. */
  def paintShape(path: Path, ctx: Ctx, markers: Boolean, bboxOverride: Option[Rect] = None, st: Style = null): Unit =
    val s = if st == null then ctx.style else st
    if !s.visible then return
    val bbox = bboxOverride.orElse(Geometry.bbox(path))
    val m = ctx.ctm
    val fillP = resolvePaint(s.fill, s.fillOpacity, bbox, ctx, isStroke = false, s.color)
    val strokeP = if s.strokeWidth > 0 then resolvePaint(s.stroke, s.strokeOpacity, bbox, ctx, isStroke = true, s.color) else None
    def doFill(): Unit = fillP.foreach(p => fillPath(ctx.pm, path, p, s.fillRule, m, s.antialias))
    def doStroke(): Unit = strokeP.foreach(p => strokePath(ctx.pm, path, p, s, m))
    if s.paintOrderStrokeFirst then { doStroke(); doFill() } else { doFill(); doStroke() }
    if markers && (s.markerStart.isDefined || s.markerMid.isDefined || s.markerEnd.isDefined) then drawMarkers(path, ctx.copy(contextFill = fillP, contextStroke = strokeP), s)

  def toleranceFor(m: Affine): Double =
    val sc = math.sqrt(math.abs(m.determinant)); 0.1 / math.max(sc, 1e-6)

  def fillPath(pm: Pixmap, path: Path, paint: Paint, rule: FillRule, m: Affine, aa: Boolean = true): Unit =
    val polys = path.transform(m).flatten().map(_._1)
    fillPolys(pm, polys, paint, rule, aa)

  def fillPolys(pm: Pixmap, polys: Vector[Vector[Point]], paint: Paint, rule: FillRule, aa: Boolean = true): Unit =
    Rasterizer.fill(polys, rule, pm.width, pm.height) { (x, y, cov) =>
      val c = if aa then cov else if cov >= 0.5f then 1f else 0f
      if c > 0f then pm.blend(x, y, paint.colorAt(x + 0.5, y + 0.5), c)
    }

  /** Strokes in user space (so non-uniform scales distort the pen like SVG) and rasterizes the outline in device space. */
  def strokePath(pm: Pixmap, path: Path, paint: Paint, s: Style, m: Affine): Unit =
    if !m.isInvertible then return
    var subs = path.flatten(toleranceFor(m))
    s.dash.foreach(d => subs = Geometry.dash(subs, d, s.dashOffset))
    val polys = Stroker.stroke(subs, Stroke(s.strokeWidth, s.cap, s.join, s.miter))
    // the stroker emits some pieces (round joins/caps) with the opposite orientation of the segment quads; with non-zero filling they would cancel
    val dev = polys.map { p =>
      val q = p.map(m(_))
      var area = 0.0; var i = 0
      while i < q.length do { val a = q(i); val b = q((i + 1) % q.length); area += a.x * b.y - b.x * a.y; i += 1 }
      if area < 0 then q.reverse else q
    }
    fillPolys(pm, dev, paint, FillRule.NonZero, s.antialias)

  // ------------------------------------------------------------------ paint servers
  def resolvePaint(spec: PaintSpec, opacity: Double, bbox: Option[Rect], ctx: Ctx, isStroke: Boolean, color: Color): Option[Paint] = spec match
    case PaintSpec.CurrentColor => Some(Paint.solid(color.withAlpha((color.a * opacity).toFloat)))
    case PaintSpec.NoPaint => None
    case PaintSpec.Solid(c) => Some(Paint.solid(c.withAlpha((c.a * opacity).toFloat)))
    case PaintSpec.ContextFill => ctx.contextFill
    case PaintSpec.ContextStroke => ctx.contextStroke
    case PaintSpec.Ref(id, fb) =>
      val fallback = fb.flatMap(resolvePaint(_, opacity, bbox, ctx, isStroke, color))
      byId(id) match
        case Some(el) if el.name == "linearGradient" || el.name == "radialGradient" =>
          gradientPaint(el, opacity, bbox, ctx) match
            case Left(true) => fallback // not a usable server: use fallback
            case Left(false) => None
            case Right(p) => Some(p)
        case Some(el) if el.name == "pattern" => patternPaint(el, opacity, bbox, ctx)
        case _ => fb.flatMap(resolvePaint(_, opacity, bbox, ctx, isStroke, color))

  /** Applies `transform-origin` of a paint server to its (gradient/pattern) transform. */
  private def originFor(el: XmlElement, tr: Affine, bbox: Option[Rect], userSpace: Boolean): Affine =
    el.attrs.get("transform-origin").map(_.trim).filter(_.nonEmpty) match
      case None => tr
      case Some(o) =>
        val parts = o.split("\\s+").toList
        val box = if userSpace then bbox.map(_ => Rect(0, 0, 0, 0)) else Some(Rect(0, 0, 1, 1))
        def comp(s: String, horizontal: Boolean): Double =
          val b = bbox
          val size = if userSpace then b.map(x => if horizontal then x.width else x.height).getOrElse(0.0) else 1.0
          val origin = if userSpace then b.map(x => if horizontal then x.x0 else x.y0).getOrElse(0.0) else 0.0
          s match
            case "left" | "top" => origin
            case "center" => origin + size / 2
            case "right" | "bottom" => origin + size
            case v => Values.parseLen(v).map(l => if l.unit == "%" then origin + l.v / 100 * size else l.v).getOrElse(origin)
        val _ = box
        val (hx, vy) = parts match
          case a :: b :: _ => (comp(a, true), comp(b, false))
          case a :: Nil => (comp(a, true), comp("center", false))
          case Nil => (0.0, 0.0)
        Affine.translate(Vec2(hx, vy)) * tr * Affine.translate(Vec2(-hx, -vy))

  private def chain(el: XmlElement): List[XmlElement] =
    val out = mutable.ListBuffer(el); var cur = hrefTarget(el)
    def sameKind(x: XmlElement) = if el.name == "pattern" then x.name == "pattern" else x.name == "linearGradient" || x.name == "radialGradient"
    while cur.exists(sameKind) && !out.exists(_ eq cur.get) && out.size < 20 do { out += cur.get; cur = hrefTarget(cur.get) }
    out.toList

  private def stopsOf(el: XmlElement): Array[(Float, Color)] =
    val src = chain(el).find(_.children.exists(_.name == "stop")).toList.flatMap(_.children.filter(_.name == "stop"))
    var last = 0.0
    src.map { s =>
      val off = Values.parseFraction(s.attrs.getOrElse("offset", "0")).getOrElse(0.0)
      last = math.max(last, Values.clamp01(off))
      val sty = Style.derive(s.attrs, definitionStyle(s), 1, 1)
      val sc = attrInherit(s, "stop-color").flatMap(v => if v == "currentColor" then Some(sty.color) else SvgColors.parse(v)).getOrElse(Color.Black)
      val so = attrInherit(s, "stop-opacity").flatMap(Values.parseFraction).map(Values.clamp01).getOrElse(1.0)
      (last.toFloat, sc.copy(a = (sc.a * so).toFloat))
    }.toArray

  /** Left(true): invalid server (use fallback); Left(false): paint nothing; Right: paint. */
  private def gradientPaint(el: XmlElement, opacity: Double, bbox: Option[Rect], ctx: Ctx): Either[Boolean, Paint] =
    val stops = stopsOf(el)
    if stops.isEmpty then return Left(true)
    if stops.length == 1 then return Right(Paint.solid(stops(0)._2.copy(a = (stops(0)._2.a * opacity).toFloat)))
    val ch = chain(el)
    def attr(k: String): Option[String] = ch.view.flatMap(_.attrs.get(k)).headOption
    def attrOf(k: String, kinds: String*): Option[String] = ch.view.filter(x => kinds.contains(x.name)).flatMap(_.attrs.get(k)).headOption
    val userSpace = attr("gradientUnits").contains("userSpaceOnUse")
    val gtOwner = ch.find(_.attrs.contains("gradientTransform")).getOrElse(el)
    val gt0 = gtOwner.attrs.get("gradientTransform").map(Transforms.parseOpt).getOrElse(Some(Affine.Identity)).map(tr => originFor(gtOwner, tr, bbox, userSpace)).getOrElse(Affine.Identity)
    val gt = if gt0.isInvertible then gt0 else Affine.Identity // an invalid transform is ignored
    val spread = attr("spreadMethod") match { case Some("reflect") => Spread.Reflect; case Some("repeat") => Spread.Repeat; case _ => Spread.Pad }
    val radial = el.name == "radialGradient"
    val units: Affine =
      if userSpace then Affine.Identity
      else bbox match
        case Some(b) if b.width > 0 && b.height > 0 => Affine.translate(Vec2(b.x0, b.y0)) * Affine.scale(b.width, b.height)
        case _ => return Left(false)
    def coord(v: Option[String], default: String, axis: Char): Double =
      val s = v.getOrElse(default)
      Values.parseLen(s) match
        case Some(l) if userSpace => Style.length(l, ctx.style.fontSize, ctx.vw, ctx.vh, axis)
        case Some(l) => if l.unit == "%" then l.v / 100 else l.v
        case None => Values.parseLen(default).map(_.v).getOrElse(0.0)
    val full = ctx.ctm * units * gt
    if !full.isInvertible then return Left(false)
    val kind = if radial then Seq("radialGradient") else Seq("linearGradient")
    if !radial then
      val x1 = coord(attrOf("x1", kind*), "0%", 'x'); val y1 = coord(attrOf("y1", kind*), "0%", 'y')
      val x2 = coord(attrOf("x2", kind*), "100%", 'x'); val y2 = coord(attrOf("y2", kind*), "0%", 'y')
      if x1 == x2 && y1 == y2 then // degenerate: last stop color
        return Right(Paint.solid(stops.last._2.copy(a = (stops.last._2.a * opacity).toFloat)))
      Right(new GradientPaint(full.inverse, false, x1, y1, x2, y2, 0, 0, stops, spread, opacity.toFloat))
    else
      val cx = coord(attrOf("cx", kind*), "50%", 'x'); val cy = coord(attrOf("cy", kind*), "50%", 'y')
      val r = coord(attrOf("r", kind*), "50%", 'd')
      val fx = attrOf("fx", kind*).map(v => coord(Some(v), "50%", 'x')).getOrElse(cx)
      val fy = attrOf("fy", kind*).map(v => coord(Some(v), "50%", 'y')).getOrElse(cy)
      val fr = coord(attrOf("fr", kind*), "0%", 'd')
      if r <= 0 then return Right(Paint.solid(stops.last._2.copy(a = (stops.last._2.a * opacity).toFloat)))
      Right(new GradientPaint(full.inverse, true, fx, fy, cx, cy, fr, r, stops, spread, opacity.toFloat))

  private def patternPaint(el: XmlElement, opacity: Double, bbox: Option[Rect], ctx: Ctx): Option[Paint] =
    val ch = chain(el)
    def attr(k: String): Option[String] = ch.view.flatMap(_.attrs.get(k)).headOption
    val unitsBBox = !attr("patternUnits").contains("userSpaceOnUse")
    val contentBBox = attr("patternContentUnits").contains("objectBoundingBox")
    val ptOwner = ch.find(_.attrs.contains("patternTransform")).getOrElse(el)
    val pt0 = ptOwner.attrs.get("patternTransform").map(Transforms.parseOpt).getOrElse(Some(Affine.Identity)).map(tr => originFor(ptOwner, tr, bbox, !unitsBBox)).getOrElse(Affine.Identity)
    val pt = if pt0.isInvertible then pt0 else Affine.Identity
    val content = ch.find(_.children.exists(c => Renderer.Graphic.contains(c.name))).getOrElse(el)
    if (unitsBBox || contentBBox) && !bbox.exists(b => b.width > 0 && b.height > 0) then return None
    def dim(k: String, axis: Char): Double =
      attr(k).flatMap(Values.parseLen).map { l =>
        if unitsBBox then (if l.unit == "%" then l.v / 100 else l.v) else Style.length(l, ctx.style.fontSize, ctx.vw, ctx.vh, axis)
      }.getOrElse(0.0)
    var px = dim("x", 'x'); var py = dim("y", 'y'); var pw = dim("width", 'x'); var ph = dim("height", 'y')
    if unitsBBox then { val b = bbox.get; px = b.x0 + px * b.width; py = b.y0 + py * b.height; pw *= b.width; ph *= b.height }
    if pw <= 0 || ph <= 0 then return None
    val P = ctx.ctm * pt
    val sx = math.hypot(P.a, P.b); val sy = math.hypot(P.c, P.d)
    val tw = math.max(1, math.min(4096, math.ceil(pw * sx - 1e-6).toInt)); val th = math.max(1, math.min(4096, math.ceil(ph * sy - 1e-6).toInt))
    val tile = new Pixmap(tw, th)
    val vb = Geometry.parseViewBox(attr("viewBox"))
    val inner: Affine = vb match
      case Some(v) => Geometry.viewBoxTransform(v, attr("preserveAspectRatio").getOrElse("xMidYMid meet"), pw, ph)
      case None if contentBBox => val b = bbox.get; Affine.scale(b.width, b.height)
      case None => Affine.Identity
    val tctm = Affine.scale(tw / pw, th / ph) * inner
    val pStyle = definitionStyle(el)
    val tctx = Ctx(tile, tctm, pStyle, vb.map(_.width).getOrElse(pw), vb.map(_.height).getOrElse(ph), ctx.depth + 1, ctx.stack + el)
    if ctx.depth > 12 then return None
    // user space of the tile contents has its origin at the tile origin unless a viewBox/units say otherwise
    val tctx2 = tctx.copy(ctm = tctm * (if vb.isDefined || true then Affine.Identity else Affine.Identity))
    drawChildren(content, tctx2)
    val inv = Affine.scale(tw / pw, th / ph) * Affine.translate(Vec2(-px, -py)) * P.inverse
    Some(new PixmapPaint(inv, tile, wrap = true, smooth = true, opacity.toFloat))

  // ------------------------------------------------------------------ bounding boxes
  /** Object bounding box in the element's own coordinate system (without its transform); `ctx.ctm` is ignored. */
  def objectBBox(e: XmlElement, ctx: Ctx): Option[Rect] =
    var x0 = Double.MaxValue; var y0 = Double.MaxValue; var x1 = Double.MinValue; var y1 = Double.MinValue
    def addPts(pts: Iterable[Point]): Unit = pts.foreach { p => x0 = math.min(x0, p.x); y0 = math.min(y0, p.y); x1 = math.max(x1, p.x); y1 = math.max(y1, p.y) }
    def addPath(p: Path, m: Affine): Unit = p.transform(m).flatten(0.05).foreach((pts, _) => addPts(pts))
    def walk(n: XmlElement, m: Affine, st: Style, vw: Double, vh: Double, depth: Int, stack: Set[XmlElement]): Unit =
      if depth > 30 || !Renderer.Graphic.contains(n.name) || n.attrs.get("display").exists(_.trim == "none") then return
      val s = Style.derive(n.attrs, st, vw, vh)
      val c = Ctx(ctx.pm, Affine.Identity, s, vw, vh, depth)
      n.name match
        case "g" | "a" | "switch" | "svg" =>
          n.children.foreach { k =>
            val kt = k.attrs.get("transform").flatMap(Transforms.parseOpt).getOrElse(Affine.Identity)
            walk(k, m * kt, s, vw, vh, depth + 1, stack)
          }
        case "use" =>
          hrefTarget(n).filter(t => !stack.contains(t)).foreach { t =>
            val kt = t.attrs.get("transform").flatMap(Transforms.parseOpt).getOrElse(Affine.Identity)
            val sh = Affine.translate(Vec2(len(n.attrs, "x", c, 'x'), len(n.attrs, "y", c, 'y')))
            if t.name == "symbol" || t.name == "svg" then
              val w = lenOpt(n.attrs, "width", c, 'x').orElse(lenOpt(t.attrs, "width", c, 'x')).getOrElse(vw)
              val h = lenOpt(n.attrs, "height", c, 'y').orElse(lenOpt(t.attrs, "height", c, 'y')).getOrElse(vh)
              val vb = Geometry.parseViewBox(t.attrs.get("viewBox"))
              val inner = vb.map(Geometry.viewBoxTransform(_, t.attrs.getOrElse("preserveAspectRatio", "xMidYMid meet"), w, h)).getOrElse(Affine.Identity)
              t.children.foreach { k =>
                val kt2 = k.attrs.get("transform").flatMap(Transforms.parseOpt).getOrElse(Affine.Identity)
                walk(k, m * sh * inner * kt2, Style.derive(t.attrs, s, vw, vh), vb.map(_.width).getOrElse(w), vb.map(_.height).getOrElse(h), depth + 1, stack + t)
              }
            else walk(t, m * sh * kt, s, vw, vh, depth + 1, stack + t)
          }
        case "image" =>
          val w = len(n.attrs, "width", c, 'x'); val h = len(n.attrs, "height", c, 'y')
          if w > 0 && h > 0 then addPath(Path.rect(Rect(len(n.attrs, "x", c, 'x'), len(n.attrs, "y", c, 'y'), len(n.attrs, "x", c, 'x') + w, len(n.attrs, "y", c, 'y') + h)), m)
        case "text" => TextEngine.layout(this, n, c.copy(ctm = Affine.Identity)).foreach(it => addPath(it.path, m))
        case _ => shapePath(n, c).foreach(addPath(_, m))
    val et = Affine.Identity
    val parentStyle = ctx.style
    // `e` itself is walked without its own transform; its style was already derived into ctx.style by the caller
    e.name match
      case "g" | "a" | "switch" | "svg" | "text" | "use" | "image" | "path" | "rect" | "circle" | "ellipse" | "line" | "polyline" | "polygon" =>
        val self = new XmlElement(e.name, e.attrs - "transform" - "display", e.nodes)
        walk(self, et, parentStyleOf(e, parentStyle), ctx.vw, ctx.vh, ctx.depth, ctx.stack)
      case _ => ()
    if x0 > x1 then None else Some(Rect(x0, y0, x1, y1))

  // ctx.style already includes e's own derivation; walking re-derives, which is idempotent for the properties used in bbox calculation
  private def parentStyleOf(e: XmlElement, derived: Style): Style = derived

  // ------------------------------------------------------------------ clip paths and masks
  /** Coverage pixmap (alpha channel) for a clipPath applied to an element whose bounding box is `bbox`; `None` hides the element. */
  def buildClip(clip: XmlElement, ctx: Ctx, bbox: => Option[Rect]): Option[Pixmap] =
    if ctx.depth > 30 then return None
    if activeRefs.exists(_ eq clip) then return Some(allOnes(ctx)) // a reference back into itself is ignored
    withActive(clip)(buildClipInner(clip, ctx, bbox))

  private def buildClipInner(clip: XmlElement, ctx: Ctx, bbox: => Option[Rect]): Option[Pixmap] =
    val a = clip.attrs
    val unitBox = a.get("clipPathUnits").contains("objectBoundingBox")
    val ct = transformOf(clip, "transform", ctx, bbox, unitBox).getOrElse(Affine.Identity)
    if !ct.isInvertible then return None
    var m = ctx.ctm * ct
    if a.get("clipPathUnits").contains("objectBoundingBox") then
      bbox match
        case Some(b) if b.width > 0 && b.height > 0 => m = m * Affine.translate(Vec2(b.x0, b.y0)) * Affine.scale(b.width, b.height)
        case _ => return None
    val acc = new Pixmap(ctx.pm.width, ctx.pm.height)
    val cs = Style.derive(a, definitionStyle(clip), ctx.vw, ctx.vh)
    def drawClipChild(k: XmlElement, km: Affine, ks: Style, stack: Set[XmlElement]): Unit =
      if k.attrs.get("display").exists(_.trim == "none") then return
      val kt = k.attrs.get("transform").flatMap(Transforms.parseOpt)
      if kt.exists(!_.isInvertible) then return
      val kst = Style.derive(k.attrs, ks, ctx.vw, ctx.vh)
      val m2 = km * kt.getOrElse(Affine.Identity)
      if !kst.visible && k.name != "use" then return
      val cctx = Ctx(acc, m2, kst, ctx.vw, ctx.vh, ctx.depth + 1, stack)
      val inner = new Pixmap(acc.width, acc.height)
      val white = Paint.solid(Color.White)
      k.name match
        case "text" => TextEngine.layout(this, k, cctx.copy(pm = inner)).foreach(it => fillPath(inner, it.path, white, it.style.clipRule, m2))
        case "use" =>
          hrefTarget(k).filter(t => !stack.contains(t) && Set("path", "rect", "circle", "ellipse", "line", "polyline", "polygon", "text").contains(t.name)).foreach { t =>
            val sh = Affine.translate(Vec2(len(k.attrs, "x", cctx, 'x'), len(k.attrs, "y", cctx, 'y')))
            val tt = t.attrs.get("transform").flatMap(Transforms.parseOpt).getOrElse(Affine.Identity)
            val tst = Style.derive(t.attrs, kst, ctx.vw, ctx.vh)
            if t.name == "text" then TextEngine.layout(this, t, cctx.copy(pm = inner, ctm = m2 * sh * tt, style = tst)).foreach(it => fillPath(inner, it.path, white, it.style.clipRule, m2 * sh * tt))
            else shapePath(t, cctx).foreach(p => fillPath(inner, p, white, tst.clipRule, m2 * sh * tt))
          }
        case n if Set("path", "rect", "circle", "ellipse", "line", "polyline", "polygon").contains(n) =>
          shapePath(k, cctx).foreach(p => fillPath(inner, p, white, kst.clipRule, m2))
        case _ => return
      // a clip-path on the child itself intersects with that child
      k.attrs.get("clip-path").flatMap(Values.urlRef).flatMap(r => byId(r._1)).filter(_.name == "clipPath").foreach { cc =>
        buildClip(cc, cctx.copy(ctm = m2, depth = ctx.depth + 1), objectBBox(k, cctx.copy(ctm = Affine.Identity))) match
          case Some(cp) => var i = 3; while i < inner.data.length do { inner.data(i) *= cp.data(i); i += 4 }
          case None => java.util.Arrays.fill(inner.data, 0f)
      }
      var i = 0
      while i < inner.data.length do
        val ia = inner.data(i + 3); if ia > 0 then acc.data(i + 3) = ia + acc.data(i + 3) * (1 - ia)
        i += 4
    clip.children.foreach(drawClipChild(_, m, cs, Set(clip)))
    // the clipPath can itself be clipped
    a.get("clip-path").flatMap(Values.urlRef).flatMap(r => byId(r._1)).filter(c => c.name == "clipPath" && (c ne clip)).foreach { cc =>
      buildClip(cc, ctx.copy(depth = ctx.depth + 1), bbox) match
        case Some(cp) => var i = 3; while i < acc.data.length do { acc.data(i) *= cp.data(i); i += 4 }
        case None => java.util.Arrays.fill(acc.data, 0f)
    }
    Some(acc)

  /** Luminance mask pixmap (alpha channel holds the mask value); `None` hides the element. */
  def buildMask(mask: XmlElement, ctx: Ctx, bbox: => Option[Rect]): Option[Pixmap] =
    if ctx.depth > 30 then return None
    if activeRefs.exists(_ eq mask) then return Some(allOnes(ctx))
    withActive(mask)(buildMaskInner(mask, ctx, bbox))

  private def buildMaskInner(mask: XmlElement, ctx: Ctx, bbox: => Option[Rect]): Option[Pixmap] =
    val a = mask.attrs
    val unitsBBox = !a.get("maskUnits").contains("userSpaceOnUse")
    val contentBBox = a.get("maskContentUnits").contains("objectBoundingBox")
    val bb = bbox
    if (unitsBBox || contentBBox) && !bb.exists(b => b.width > 0 && b.height > 0) then return None
    def dim(k: String, default: String, axis: Char): Double =
      val l = Values.parseLen(a.getOrElse(k, default)).orElse(Values.parseLen(default)).get
      if unitsBBox then (if l.unit == "%" then l.v / 100 else l.v) else Style.length(l, ctx.style.fontSize, ctx.vw, ctx.vh, axis)
    var x = dim("x", "-10%", 'x'); var y = dim("y", "-10%", 'y'); var w = dim("width", "120%", 'x'); var h = dim("height", "120%", 'y')
    if unitsBBox then { val b = bb.get; x = b.x0 + x * b.width; y = b.y0 + y * b.height; w *= b.width; h *= b.height }
    if w <= 0 || h <= 0 then return None
    val ms = definitionStyle(mask)
    val contentM = if contentBBox then ctx.ctm * Affine.translate(Vec2(bb.get.x0, bb.get.y0)) * Affine.scale(bb.get.width, bb.get.height) else ctx.ctm
    val region = Path.rect(Rect(x, y, x + w, y + h))
    val out = new Pixmap(ctx.pm.width, ctx.pm.height)
    val tmp = new Pixmap(ctx.pm.width, ctx.pm.height)
    val mctx = Ctx(tmp, contentM, ms, ctx.vw, ctx.vh, ctx.depth + 1, ctx.stack + mask)
    drawChildren(mask, mctx)
    // clip to the mask region
    val regionCov = new Pixmap(ctx.pm.width, ctx.pm.height)
    fillPath(regionCov, region, Paint.solid(Color.White), FillRule.NonZero, ctx.ctm)
    val linear = ms.linearMask
    val alphaMask = a.get("mask-type").exists(_.trim == "alpha")
    var i = 0
    while i < tmp.data.length do
      val al = tmp.data(i + 3)
      if al > 0 then
        var r = tmp.data(i) / al; var g = tmp.data(i + 1) / al; var b = tmp.data(i + 2) / al
        if linear then { r = Color.srgbToLinear(r); g = Color.srgbToLinear(g); b = Color.srgbToLinear(b) }
        val lum = if alphaMask then 1f else 0.2125f * r + 0.7154f * g + 0.0721f * b
        out.data(i + 3) = (lum * al).min(1f) * regionCov.data(i + 3)
      i += 4
    mask.attrs.get("mask").flatMap(Values.urlRef).flatMap(r => byId(r._1)).filter(mm => mm.name == "mask" && (mm ne mask)).foreach { mm =>
      buildMask(mm, ctx.copy(depth = ctx.depth + 1), bbox) match
        case Some(p) => var k = 3; while k < out.data.length do { out.data(k) *= p.data(k); k += 4 }
        case None => java.util.Arrays.fill(out.data, 0f)
    }
    Some(out)

  // ------------------------------------------------------------------ markers
  private def drawMarkers(path: Path, ctx: Ctx, s: Style): Unit =
    val verts = Geometry.vertices(path)
    if verts.isEmpty then return
    val last = verts.size - 1
    def draw(id: Option[String], idx: Int, isStart: Boolean): Unit =
      id.flatMap(byId).filter(_.name == "marker").foreach { mk =>
        val (p, in, out) = verts(idx)
        val ang = if mk.attrs.get("orient").exists(_.trim == "auto-start-reverse") && isStart then Geometry.vertexAngle(in, out) + math.Pi else Geometry.vertexAngle(in, out)
        drawMarker(mk, p, ang, ctx, s)
      }
    draw(s.markerStart, 0, isStart = true)
    if s.markerMid.isDefined then for i <- 1 until last do draw(s.markerMid, i, isStart = false)
    if last > 0 || true then draw(s.markerEnd, last, isStart = false)

  private def drawMarker(mk: XmlElement, p: Point, autoAngle: Double, ctx: Ctx, s: Style): Unit =
    if ctx.depth > 20 || ctx.stack.contains(mk) then return
    val a = mk.attrs
    val mw = len(a, "markerWidth", ctx, 'x', 3.0); val mh = len(a, "markerHeight", ctx, 'y', 3.0)
    if mw <= 0 || mh <= 0 then return
    val units = if a.get("markerUnits").contains("userSpaceOnUse") then 1.0 else s.strokeWidth
    val orient = a.get("orient").map(_.trim).getOrElse("0")
    val angle = if orient == "auto" || orient == "auto-start-reverse" then autoAngle else Values.numbers(orient.stripSuffix("deg")).headOption.map(math.toRadians).getOrElse(0.0)
    val vb = Geometry.parseViewBox(a.get("viewBox"))
    val vbT = vb.map(Geometry.viewBoxTransform(_, a.getOrElse("preserveAspectRatio", "xMidYMid meet"), mw, mh)).getOrElse(Affine.Identity)
    val ref = vbT(Point(len(a, "refX", ctx, 'x'), len(a, "refY", ctx, 'y')))
    val base = ctx.ctm * Affine.translate(Vec2(p.x, p.y)) * Affine.rotate(angle) * Affine.scale(units) * Affine.translate(Vec2(-ref.x, -ref.y))
    val ms = definitionStyle(mk)
    val inner = Ctx(ctx.pm, base * vbT, ms, vb.map(_.width).getOrElse(mw), vb.map(_.height).getOrElse(mh), ctx.depth + 1, ctx.stack + mk, ctx.contextFill, ctx.contextStroke)
    val ov = a.get("overflow").map(_.trim).getOrElse("hidden")
    if ov == "visible" || ov == "auto" then drawChildren(mk, inner)
    else
      val clipRect = vb match
        case Some(v) if a.getOrElse("preserveAspectRatio", "").trim.startsWith("none") || true => Path.rect(Rect(0, 0, mw, mh))
        case _ => Path.rect(Rect(0, 0, mw, mh))
      Layers.layer(ctx.pm, clip = Some(clipRect), transform = base) { l => drawChildren(mk, inner.copy(pm = l)) }

  // ------------------------------------------------------------------ images
  private def drawImage(e: XmlElement, ctx: Ctx): Unit =
    val a = e.attrs
    if !ctx.style.visible then return
    val w0 = lenOpt(a, "width", ctx, 'x'); val h0 = lenOpt(a, "height", ctx, 'y')
    val x = len(a, "x", ctx, 'x'); val y = len(a, "y", ctx, 'y')
    val src = href(e).getOrElse("")
    val loaded = doc.loadImage(src)
    loaded match
      case None => ()
      case Some(Left(img)) =>
        val iw = img.width.toDouble; val ih = img.height.toDouble
        val w = w0.getOrElse(h0.map(_ * iw / ih).getOrElse(iw)); val h = h0.getOrElse(w0.map(_ * ih / iw).getOrElse(ih))
        if w <= 0 || h <= 0 then return
        val par = a.getOrElse("preserveAspectRatio", "xMidYMid meet")
        val t = Geometry.viewBoxTransform(Rect(0, 0, iw, ih), par, w, h)
        val pm = Pixmaps.fromImage(img)
        val toDev = ctx.ctm * Affine.translate(Vec2(x, y)) * t
        if !toDev.isInvertible then return
        val paint = new PixmapPaint(toDev.inverse, pm, wrap = false, smooth = ctx.style.imageSmooth, opacity = 1f)
        val imgRect = Path.rect(Rect(0, 0, iw, ih))
        // clip to the viewport (matters for `slice`) and the image itself
        val vp = Rect(x, y, x + w, y + h)
        val region = if par.contains("slice") then vp else Rect(x, y, x + w, y + h)
        val dstRect = t.transformRectBbox(Rect(0, 0, iw, ih)).translate(Vec2(x, y)).intersect(region)
        if dstRect.width <= 0 || dstRect.height <= 0 then return
        fillPath(ctx.pm, Path.rect(dstRect), paint, FillRule.NonZero, ctx.ctm)
        val _ = imgRect
      case Some(Right(sub)) =>
        val iw = sub.width; val ih = sub.height
        val w = w0.getOrElse(h0.map(_ * iw / ih).getOrElse(iw)); val h = h0.getOrElse(w0.map(_ * ih / iw).getOrElse(ih))
        if w <= 0 || h <= 0 then return
        val par = a.getOrElse("preserveAspectRatio", "xMidYMid meet")
        val t = Geometry.viewBoxTransform(Rect(0, 0, iw, ih), par, w, h)
        val m = ctx.ctm * Affine.translate(Vec2(x, y)) * t
        val clip = Path.rect(Rect(0, 0, iw, ih))
        val viewport = Path.rect(Rect(x, y, x + w, y + h))
        Layers.layer(ctx.pm, clip = Some(viewport), transform = ctx.ctm) { outer =>
          Layers.layer(outer, clip = Some(clip), transform = m) { l =>
            val r = new Renderer(sub)
            r.renderRoot(l, m * sub.rootTransform)
          }
        }

  // ------------------------------------------------------------------ text
  private def drawText(e: XmlElement, ctx: Ctx): Unit =
    val items = TextEngine.layout(this, e, ctx, colorGlyphs = true)
    if items.isEmpty then return
    val bb = {
      var x0 = Double.MaxValue; var y0 = Double.MaxValue; var x1 = Double.MinValue; var y1 = Double.MinValue
      items.foreach(it => Geometry.bbox(it.path).foreach(b => { x0 = math.min(x0, b.x0); y0 = math.min(y0, b.y0); x1 = math.max(x1, b.x1); y1 = math.max(y1, b.y1) }))
      if x0 > x1 then None else Some(Rect(x0, y0, x1, y1))
    }
    items.foreach { it =>
      if it.path.elements.nonEmpty then paintShape(it.path, ctx, markers = false, bboxOverride = bb, st = it.style)
      it.colors.foreach(drawColorGlyph(_, ctx))
    }

  /** Paints one colour glyph: COLR layers or a bitmap strike through [[ColorGlyphs]], else the glyph's `SVG ` table document.
    * The text `fill` only supplies the colour of "foreground" palette entries; `fill-opacity` fades the whole glyph.
    */
  private def drawColorGlyph(cg: ColorGlyphDraw, ctx: Ctx): Unit =
    val st = cg.style
    if !st.visible || !ctx.ctm.isInvertible then return
    val fg = st.fill match
      case PaintSpec.Solid(c) => c
      case _ => st.color
    val m = ctx.ctm * cg.matrix
    def paint(pm: Pixmap): Unit =
      if !ColorGlyphs.draw(pm, cg.face, cg.gid, m, 0, fg, largestStrike = true) then
        // SVG glyph documents use y-down coordinates with units of the font's em
        svgGlyphDoc(cg.face, cg.gid).foreach(sub => new Renderer(sub).renderRoot(pm, m * Affine(1, 0, 0, -1, 0, 0)))
    if st.fillOpacity < 1.0 then Layers.layer(ctx.pm, opacity = st.fillOpacity.toFloat)(paint) else paint(ctx.pm)

  /** The standalone document of an OpenType `SVG ` glyph: the table entry without the glyph groups of its neighbours and without a viewBox. */
  private def svgGlyphDoc(face: scalaui.font.FontFace, gid: Int): Option[SvgDocument] =
    doc.fontCache.getOrElseUpdate(s"svgglyph:${System.identityHashCode(face)}:$gid", {
      try
        face.svgGlyph(gid).map { g =>
          val raw = Xml.parse(g.svg)
          val mine = "glyph" + gid
          def keep(n: XmlNode | XmlElement): Boolean = n match
            case e: XmlElement => e.attrs.get("id").forall(id => !(id.startsWith("glyph") && id.drop(5).forall(_.isDigit) && id != mine))
            case _ => true
          val root = new XmlElement("svg", raw.attrs - "viewBox" - "width" - "height", raw.nodes.filter(keep))
          SvgDocument.fromElement(root, doc.options)
        }
      catch case _: Exception => None
    }).asInstanceOf[Option[SvgDocument]]

private[svg] object Renderer:
  val Graphic: Set[String] = Set("svg", "g", "a", "switch", "path", "rect", "circle", "ellipse", "line", "polyline", "polygon", "text", "image", "use")
