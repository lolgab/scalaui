package scalaui.svg

import scalaui.geom.*
import scalaui.raster.Path

/** SVG path `d` attribute parser (all commands, arcs converted to cubic Béziers). Malformed trailing data is ignored per the spec. */
private[svg] object PathData:
  def parse(d: String): Path =
    val pb = Path.builder
    var i = 0
    var cx = 0.0; var cy = 0.0; var sx = 0.0; var sy = 0.0
    var lastCtrlX = 0.0; var lastCtrlY = 0.0; var lastCmd = ' '
    def skipSep(): Unit = while i < d.length && (d.charAt(i).isWhitespace || d.charAt(i) == ',') do i += 1
    def hasNumber: Boolean = { skipSep(); i < d.length && (d.charAt(i).isDigit || d.charAt(i) == '-' || d.charAt(i) == '+' || d.charAt(i) == '.') }
    def number(): Double =
      skipSep()
      val s = i
      if i < d.length && (d.charAt(i) == '-' || d.charAt(i) == '+') then i += 1
      var dot = false
      while i < d.length && (d.charAt(i).isDigit || (d.charAt(i) == '.' && !dot)) do { if d.charAt(i) == '.' then dot = true; i += 1 }
      if i < d.length && (d.charAt(i) == 'e' || d.charAt(i) == 'E') && i + 1 < d.length && (d.charAt(i + 1).isDigit || d.charAt(i + 1) == '-' || d.charAt(i + 1) == '+') then
        i += 2; while i < d.length && d.charAt(i).isDigit do i += 1
      if s == i then throw new NumberFormatException(s"bad path number at $i") else d.substring(s, i).toDouble
    def flag(): Boolean = { skipSep(); val c = d.charAt(i); if c != '0' && c != '1' then throw new NumberFormatException("bad flag"); i += 1; c == '1' }
    try
      var cmd = ' '
      skipSep()
      while i < d.length do
        skipSep()
        if i >= d.length then return pb.build
        val c = d.charAt(i)
        if c.isLetter then { cmd = c; i += 1 }
        else if cmd == ' ' then return pb.build
        if cmd == ' ' || (lastCmd == ' ' && cmd != 'M' && cmd != 'm') then return pb.build
        if (lastCmd == 'Z' || lastCmd == 'z') && cmd != 'M' && cmd != 'm' && cmd != 'Z' && cmd != 'z' then pb.moveTo(cx, cy)
        val rel = cmd.isLower
        val ox = if rel then cx else 0.0; val oy = if rel then cy else 0.0
        cmd.toUpper match
          case 'M' =>
            val x = number() + ox; val y = number() + oy
            pb.moveTo(x, y); cx = x; cy = y; sx = x; sy = y
            cmd = if rel then 'l' else 'L' // implicit lineto for further pairs
          case 'L' => val x = number() + ox; val y = number() + oy; pb.lineTo(x, y); cx = x; cy = y
          case 'H' => val x = number() + ox; pb.lineTo(x, cy); cx = x
          case 'V' => val y = number() + oy; pb.lineTo(cx, y); cy = y
          case 'C' =>
            val x1 = number() + ox; val y1 = number() + oy; val x2 = number() + ox; val y2 = number() + oy; val x = number() + ox; val y = number() + oy
            pb.cubicTo(x1, y1, x2, y2, x, y); lastCtrlX = x2; lastCtrlY = y2; cx = x; cy = y
          case 'S' =>
            val (x1, y1) = if "CS".contains(lastCmd.toUpper) then (2 * cx - lastCtrlX, 2 * cy - lastCtrlY) else (cx, cy)
            val x2 = number() + ox; val y2 = number() + oy; val x = number() + ox; val y = number() + oy
            pb.cubicTo(x1, y1, x2, y2, x, y); lastCtrlX = x2; lastCtrlY = y2; cx = x; cy = y
          case 'Q' =>
            val x1 = number() + ox; val y1 = number() + oy; val x = number() + ox; val y = number() + oy
            pb.quadTo(x1, y1, x, y); lastCtrlX = x1; lastCtrlY = y1; cx = x; cy = y
          case 'T' =>
            val (x1, y1) = if "QT".contains(lastCmd.toUpper) then (2 * cx - lastCtrlX, 2 * cy - lastCtrlY) else (cx, cy)
            val x = number() + ox; val y = number() + oy
            pb.quadTo(x1, y1, x, y); lastCtrlX = x1; lastCtrlY = y1; cx = x; cy = y
          case 'A' =>
            val rx = number(); val ry = number(); val rot = number(); val large = flag(); val sweep = flag()
            val x = number() + ox; val y = number() + oy
            arc(pb, cx, cy, rx, ry, rot, large, sweep, x, y); cx = x; cy = y
          case 'Z' => pb.close(); cx = sx; cy = sy
          case _ => return pb.build
        lastCmd = cmd
    catch case _: Exception => ()
    pb.build

  /** Endpoint-parameterized elliptical arc -> cubic segments (SVG 1.1 appendix F.6). */
  private def arc(pb: Path.PathBuilder, x0: Double, y0: Double, rxIn: Double, ryIn: Double, rotDeg: Double, large: Boolean, sweep: Boolean, x: Double, y: Double): Unit =
    if x0 == x && y0 == y then return
    var rx = math.abs(rxIn); var ry = math.abs(ryIn)
    if rx == 0 || ry == 0 then { pb.lineTo(x, y); return }
    val phi = math.toRadians(rotDeg); val cosP = math.cos(phi); val sinP = math.sin(phi)
    val dx = (x0 - x) / 2; val dy = (y0 - y) / 2
    val x1p = cosP * dx + sinP * dy; val y1p = -sinP * dx + cosP * dy
    val lambda = x1p * x1p / (rx * rx) + y1p * y1p / (ry * ry)
    if lambda > 1 then { val s = math.sqrt(lambda); rx *= s; ry *= s }
    val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
    val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
    val coef = (if large == sweep then -1.0 else 1.0) * math.sqrt(math.max(0.0, num / den))
    val cxp = coef * rx * y1p / ry; val cyp = -coef * ry * x1p / rx
    val cxx = cosP * cxp - sinP * cyp + (x0 + x) / 2; val cyy = sinP * cxp + cosP * cyp + (y0 + y) / 2
    def angle(ux: Double, uy: Double, vx: Double, vy: Double): Double =
      val a = math.atan2(ux * vy - uy * vx, ux * vx + uy * vy); a
    val theta1 = angle(1, 0, (x1p - cxp) / rx, (y1p - cyp) / ry)
    var dTheta = angle((x1p - cxp) / rx, (y1p - cyp) / ry, (-x1p - cxp) / rx, (-y1p - cyp) / ry)
    if !sweep && dTheta > 0 then dTheta -= 2 * math.Pi else if sweep && dTheta < 0 then dTheta += 2 * math.Pi
    val segs = math.max(1, math.ceil(math.abs(dTheta) / (math.Pi / 2) - 1e-9).toInt)
    val delta = dTheta / segs
    val t = 4.0 / 3.0 * math.tan(delta / 4)
    var a = theta1
    for _ <- 0 until segs do
      val cos1 = math.cos(a); val sin1 = math.sin(a); val cos2 = math.cos(a + delta); val sin2 = math.sin(a + delta)
      def pt(px: Double, py: Double) = (cxx + rx * cosP * px - ry * sinP * py, cyy + rx * sinP * px + ry * cosP * py)
      val (c1x, c1y) = pt(cos1 - t * sin1, sin1 + t * cos1)
      val (c2x, c2y) = pt(cos2 + t * sin2, sin2 - t * cos2)
      val (ex, ey) = pt(cos2, sin2)
      pb.cubicTo(c1x, c1y, c2x, c2y, ex, ey)
      a += delta
