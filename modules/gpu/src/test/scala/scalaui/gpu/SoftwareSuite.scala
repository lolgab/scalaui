package scalaui.gpu

import scalaui.color.Color
import scalaui.geom.*

class SoftwareSuite extends scalaui.testkit.LoggedSuite:
  private val white = new SoftTexture(1, 1); white.update(0, 0, 1, 1, Array(-1, -1, -1, -1))
  private def render(f: Canvas => Unit, w: Int = 20, h: Int = 20, scale: Double = 1.0, clear: Color = Color.Black) =
    val c = new Canvas(white); c.begin(w, h, scale, clear); f(c)
    SoftwareRenderer.render(c.finish())
  private def px(r: (Array[Byte], Int, Int), x: Int, y: Int): Seq[Int] =
    val i = (y * r._2 + x) * 4; Seq(r._1(i) & 0xff, r._1(i + 1) & 0xff, r._1(i + 2) & 0xff, r._1(i + 3) & 0xff)

  test("clear color fills the frame") {
    val r = render(_ => (), clear = Color.rgb8(10, 20, 30))
    assertEquals(px(r, 5, 5), Seq(10, 20, 30, 255))
  }
  test("opaque rect covers exactly its pixels") {
    val r = render(_.fillRect(Rect(5, 5, 10, 10), Color.White))
    assertEquals(px(r, 7, 7), Seq(255, 255, 255, 255))
    assertEquals(px(r, 4, 7), Seq(0, 0, 0, 255))
    assertEquals(px(r, 10, 7), Seq(0, 0, 0, 255))
  }
  test("half-covered pixel blends ~50%") {
    val r = render(_.fillRect(Rect(5, 0, 10.5, 20), Color.White))
    val v = px(r, 10, 5)(0)
    assert(v > 100 && v < 160, s"edge value $v")
  }
  test("translucent color blends over background") {
    val r = render(_.fillRect(Rect(0, 0, 20, 20), Color(1, 0, 0, 0.5f)), clear = Color.White)
    val p = px(r, 10, 10)
    assert(p(0) > 250 && math.abs(p(1) - 128) <= 2 && math.abs(p(2) - 128) <= 2, s"$p")
  }
  test("scale doubles output size and geometry") {
    val r = render(_.fillRect(Rect(0, 0, 5, 5), Color.White), w = 10, h = 10, scale = 2.0)
    assertEquals((r._2, r._3), (20, 20))
    assertEquals(px(r, 8, 8), Seq(255, 255, 255, 255))
    assertEquals(px(r, 11, 11), Seq(0, 0, 0, 255))
  }
  test("scissor clips drawing") {
    val r = render { c => c.pushClip(Rect(0, 0, 10, 20)); c.fillRect(Rect(0, 0, 20, 20), Color.White); c.popClip() }
    assertEquals(px(r, 5, 5)(0), 255)
    assertEquals(px(r, 15, 5)(0), 0)
  }
  test("textured quad samples the texture (premultiplied) with tint") {
    val t = new SoftTexture(2, 1)
    t.update(0, 0, 2, 1, Array[Byte](-1, 0, 0, -1, 0, 0, -1, -1)) // red | blue
    val r = render(_.drawImage(t, Rect(0, 0, 20, 4)), w = 20, h = 4)
    val left = px(r, 1, 2); val right = px(r, 18, 2)
    assert(left(0) > 230 && left(2) < 30, s"left $left")
    assert(right(2) > 230 && right(0) < 30, s"right $right")
  }
  test("circle area is about pi r^2") {
    val r = render(_.fillCircle(Point(20, 20), 10, Color.White), 40, 40)
    var sum = 0.0
    for y <- 0 until 40; x <- 0 until 40 do sum += px(r, x, y)(0) / 255.0
    assertEqualsDouble(sum, math.Pi * 100, 6.0)
  }

  test("damage rect: redrawing only the damaged region equals a full render") {
    def frame(shift: Double, tex: SoftTexture): Frame =
      val c = new Canvas(white); c.begin(64, 48, 1.5, Color(0.1f, 0.2f, 0.3f, 1f))
      c.fillRectGradient(Rect(0, 0, 64, 48), Color(1, 0, 0, 1), Color(0, 1, 0, 1), Color(0, 0, 1, 1), Color(1, 1, 0, 1))
      c.fillCircle(Point(20 + shift, 20), 9.3, Color(1, 1, 1, 0.6f))
      c.drawImage(tex, Rect(30, 10, 50, 22))
      c.pushClip(Rect(5, 30, 40, 44)); c.fillRoundedRect(Rect(0, 28, 60, 46), 5, Color(0, 0, 0, 0.5f)); c.popClip()
      c.finish()
    val tex = new SoftTexture(2, 2); tex.update(0, 0, 2, 2, Array.tabulate[Byte](16)(i => (i * 17).toByte))
    val a = frame(0, tex); val b = frame(6, tex)
    val (full, w, h) = SoftwareRenderer.render(b)
    val buf = new Array[Byte](w * h * 4)
    SoftwareRenderer.renderInto(a, buf, None)
    assertEquals(buf.toSeq, SoftwareRenderer.render(a)._1.toSeq)
    // the circle moved from x 10.7..29.3 to 16.7..35.3: damage covers both positions
    SoftwareRenderer.renderInto(b, buf, Some(Rect(10, 8, 37, 32)))
    assertEquals(buf.toSeq, full.toSeq)
    // an empty / off-screen damage rect draws nothing
    val before = buf.clone()
    SoftwareRenderer.renderInto(a, buf, Some(Rect(100, 100, 120, 120)))
    assertEquals(buf.toSeq, before.toSeq)
  }

  test("SoftwareBackend keeps the previous frame and applies damage") {
    var presented: Array[Byte] = null
    val win = new scalaui.window.Window:
      override def present(rgba: Array[Byte], w: Int, h: Int): Unit = presented = rgba.clone()
      def pollEvents(): Seq[scalaui.window.Event] = Nil
      def nativeHandle: scala.scalanative.unsafe.Ptr[Byte] = null
      def width: Int = 30
      def height: Int = 20
      def close(): Unit = ()
      def scaleFactor: Double = 1.0
      def setTitle(t: String): Unit = ()
    val be = new SoftwareBackend(win)
    def frame(x: Double): Frame =
      val c = new Canvas(be.whiteTexture); c.begin(30, 20, 1.0, Color.Black); c.fillRect(Rect(x, 5, x + 8, 15), Color.White); c.finish()
    be.submit(frame(2)); be.submit(frame(12), Some(Rect(0, 0, 30, 20)))
    be.submit(frame(14), Some(Rect(10, 4, 24, 16)))
    assertEquals(presented.toSeq, SoftwareRenderer.render(frame(14))._1.toSeq)
  }
