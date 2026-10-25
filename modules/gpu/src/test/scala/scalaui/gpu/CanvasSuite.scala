package scalaui.gpu

import scalaui.color.Color
import scalaui.geom.*

class CanvasSuite extends scalaui.testkit.LoggedSuite:
  private object Stub extends Texture:
    def width = 1; def height = 1
    def update(x: Int, y: Int, w: Int, h: Int, rgba: Array[Byte]): Unit = ()
  private object Other extends Texture:
    def width = 1; def height = 1
    def update(x: Int, y: Int, w: Int, h: Int, rgba: Array[Byte]): Unit = ()

  private def canvas = { val c = new Canvas(Stub); c.begin(100, 100, 2.0, Color.Black); c }

  test("rect is two triangles, premultiplied") {
    val c = canvas
    c.fillRect(Rect(0, 0, 10, 10), Color(1, 0, 0, 0.5f))
    val f = c.finish()
    assertEquals(f.calls.map(_.vertexCount), Vector(6))
    assertEquals(f.vertices.length, 6 * Vertex.Floats)
    assertEqualsDouble(f.vertices(4), 0.5, 1e-6) // premultiplied red
    assertEqualsDouble(f.vertices(7), 0.5, 1e-6)
  }
  test("same state batches into one call; texture change splits") {
    val c = canvas
    c.fillRect(Rect(0, 0, 1, 1), Color.White); c.fillCircle(Point(5, 5), 3, Color.White)
    c.drawImage(Other, Rect(0, 0, 4, 4))
    c.fillRect(Rect(0, 0, 1, 1), Color.White)
    val f = c.finish()
    assertEquals(f.calls.size, 3)
    assert(f.calls(1).texture eq Other)
    assertEquals(f.calls.map(_.vertexCount).sum * Vertex.Floats, f.vertices.length)
  }
  test("clips intersect and pop restores") {
    val c = canvas
    c.pushClip(Rect(0, 0, 50, 50)); c.pushClip(Rect(25, 25, 80, 80))
    c.fillRect(Rect(0, 0, 90, 90), Color.White)
    c.popClip()
    c.fillRect(Rect(0, 0, 90, 90), Color.White)
    c.popClip()
    c.fillRect(Rect(0, 0, 90, 90), Color.White)
    val f = c.finish()
    assertEquals(f.calls.map(_.scissor), Vector(Some(Rect(25, 25, 50, 50)), Some(Rect(0, 0, 50, 50)), None))
  }
  test("rounded rect with zero radius degrades to rect") {
    val c = canvas
    c.fillRoundedRect(Rect(0, 0, 10, 10), 0, Color.White)
    assertEquals(c.finish().calls.head.vertexCount, 6)
  }
