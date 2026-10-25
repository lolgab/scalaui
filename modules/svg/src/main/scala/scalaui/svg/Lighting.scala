package scalaui.svg

import scalaui.color.Color
import scalaui.geom.*
import scalaui.raster.Pixmap

/** `feDiffuseLighting` and `feSpecularLighting` with distant, point and spot light sources (SVG Filter Effects, section 15). */
private[svg] object Lighting:
  private sealed trait Light
  private final case class Distant(azimuth: Double, elevation: Double) extends Light
  private final case class Point3(x: Double, y: Double, z: Double) extends Light
  private final case class Spot(x: Double, y: Double, z: Double, px: Double, py: Double, pz: Double, exponent: Double, cone: Option[Double]) extends Light

  def run(p: XmlElement, src: Pixmap, m: Affine, bbox: Option[Rect], primBBox: Boolean, lin: Boolean, rx0: Int, ry0: Int, r: Renderer, ctx: Ctx): Pixmap =
    val w = src.width; val h = src.height
    val out = new Pixmap(w, h)
    val pa = p.attrs
    val specular = p.name == "feSpecularLighting"
    def num(a: Map[String, String], k: String, d: Double): Double = a.get(k).flatMap(Values.parseNumber).getOrElse(d)
    val surfaceScale = num(pa, "surfaceScale", 1.0)
    val kd = num(pa, "diffuseConstant", 1.0); val ks = num(pa, "specularConstant", 1.0); val specExp = num(pa, "specularExponent", 1.0)
    if specular && (specExp < 1 || specExp > 128) then return out
    if !specular && kd < 0 then return out
    if specular && ks < 0 then return out
    val sx = math.hypot(m.a, m.b); val sy = math.hypot(m.c, m.d)
    val zScale = math.sqrt(sx * sx + sy * sy) / math.sqrt(2.0)
    def ux(v: Double) = if primBBox then bbox.get.x0 + v * bbox.get.width else v
    def uy(v: Double) = if primBBox then bbox.get.y0 + v * bbox.get.height else v
    def uz(v: Double) = if primBBox then v * math.sqrt((bbox.get.width * bbox.get.width + bbox.get.height * bbox.get.height) / 2) else v
    val lightEl = p.children.find(c => c.name == "feDistantLight" || c.name == "fePointLight" || c.name == "feSpotLight")
    val light: Light = lightEl match
      case None => return out
      case Some(e) =>
        val a = e.attrs
        def pos(xk: String, yk: String, zk: String): (Double, Double, Double) =
          val d = m(Point(ux(num(a, xk, 0)), uy(num(a, yk, 0))))
          (d.x - rx0, d.y - ry0, uz(num(a, zk, 0)) * zScale)
        e.name match
          case "feDistantLight" => Distant(num(a, "azimuth", 0), num(a, "elevation", 0))
          case "fePointLight" => val (x, y, z) = pos("x", "y", "z"); Point3(x, y, z)
          case _ =>
            val (x, y, z) = pos("x", "y", "z"); val (px, py, pz) = pos("pointsAtX", "pointsAtY", "pointsAtZ")
            val ex = num(a, "specularExponent", 1.0)
            Spot(x, y, z, px, py, pz, if ex <= 0 then 1.0 else ex, a.get("limitingConeAngle").flatMap(Values.parseNumber).map(c => math.abs(c)))
    val colorRaw = r.attrInherit(p, "lighting-color").getOrElse("white")
    val base = colorRaw match
      case "currentColor" => Some(r.definitionStyle(p).color)
      case v => SvgColors.parse(v)
    val lc0 = base.getOrElse(Color.White)
    val lc = if lin then Color(Color.srgbToLinear(lc0.r), Color.srgbToLinear(lc0.g), Color.srgbToLinear(lc0.b)) else lc0
    def alpha(x: Int, y: Int): Double = src.data((y * w + x) * 4 + 3)
    for y <- 0 until h; x <- 0 until w do
      // surface normal (spec table of edge kernels)
      val xl = if x > 0 then x - 1 else x; val xr = if x < w - 1 then x + 1 else x
      val yu = if y > 0 then y - 1 else y; val yd = if y < h - 1 then y + 1 else y
      var sumX = 0.0; var wsX = 0.0
      for row <- Seq(y - 1, y, y + 1) if row >= 0 && row < h do
        val wt = if row == y then 2.0 else 1.0
        sumX += wt * (alpha(xr, row) - alpha(xl, row)); wsX += wt
      var sumY = 0.0; var wsY = 0.0
      for col <- Seq(x - 1, x, x + 1) if col >= 0 && col < w do
        val wt = if col == x then 2.0 else 1.0
        sumY += wt * (alpha(col, yd) - alpha(col, yu)); wsY += wt
      val fx = (if x > 0 && x < w - 1 then 1.0 else 2.0) / wsX; val fy = (if y > 0 && y < h - 1 then 1.0 else 2.0) / wsY
      val nx = -surfaceScale * fx * sumX; val ny = -surfaceScale * fy * sumY
      val nl = math.sqrt(nx * nx + ny * ny + 1)
      val (normX, normY, normZ) = (nx / nl, ny / nl, 1 / nl)
      val z = surfaceScale * alpha(x, y)
      var lx = 0.0; var ly = 0.0; var lz = 0.0
      var cr = lc.r.toDouble; var cg = lc.g.toDouble; var cb = lc.b.toDouble
      var lit = true
      light match
        case Distant(az, el) =>
          val a = math.toRadians(az); val e = math.toRadians(el)
          lx = math.cos(a) * math.cos(e); ly = math.sin(a) * math.cos(e); lz = math.sin(e)
        case Point3(px, py, pz) =>
          lx = px - x; ly = py - y; lz = pz - z
          val l = math.sqrt(lx * lx + ly * ly + lz * lz); if l > 0 then { lx /= l; ly /= l; lz /= l }
        case Spot(px, py, pz, tx, ty, tz, ex, cone) =>
          lx = px - x; ly = py - y; lz = pz - z
          val l = math.sqrt(lx * lx + ly * ly + lz * lz); if l > 0 then { lx /= l; ly /= l; lz /= l }
          var sx0 = tx - px; var sy0 = ty - py; var sz0 = tz - pz
          val sl = math.sqrt(sx0 * sx0 + sy0 * sy0 + sz0 * sz0); if sl > 0 then { sx0 /= sl; sy0 /= sl; sz0 /= sl }
          val minusLS = -(lx * sx0 + ly * sy0 + lz * sz0)
          if minusLS <= 0 then lit = false
          else if cone.exists(c => minusLS < math.cos(math.toRadians(c))) then lit = false
          else { val f = math.pow(minusLS, ex); cr *= f; cg *= f; cb *= f }
      val i = (y * w + x) * 4
      if !lit then { out.data(i + 3) = if specular then 0f else 1f }
      else if !specular then
        val nDotL = math.max(0.0, normX * lx + normY * ly + normZ * lz)
        val f = kd * nDotL
        out.data(i) = math.min(1.0, f * cr).toFloat; out.data(i + 1) = math.min(1.0, f * cg).toFloat; out.data(i + 2) = math.min(1.0, f * cb).toFloat; out.data(i + 3) = 1f
      else
        var hx = lx; var hy = ly; var hz = lz + 1
        val hl = math.sqrt(hx * hx + hy * hy + hz * hz); if hl > 0 then { hx /= hl; hy /= hl; hz /= hl }
        val nDotH = math.max(0.0, normX * hx + normY * hy + normZ * hz)
        val f = ks * math.pow(nDotH, specExp)
        val rr = math.min(1.0, f * cr); val gg = math.min(1.0, f * cg); val bb = math.min(1.0, f * cb)
        val a = math.max(rr, math.max(gg, bb))
        out.data(i) = rr.toFloat; out.data(i + 1) = gg.toFloat; out.data(i + 2) = bb.toFloat; out.data(i + 3) = a.toFloat
    out
