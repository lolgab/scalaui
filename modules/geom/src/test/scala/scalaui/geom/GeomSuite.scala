package scalaui.geom

class GeomSuite extends scalaui.testkit.LoggedSuite:
  test("affine inverse round trips") {
    val m = Affine.translate(Vec2(3, 4)) * Affine.rotate(0.7) * Affine.scale(2, 3)
    val p = Point(1.5, -2)
    val q = m.inverse(m(p))
    assertEqualsDouble(q.x, p.x, 1e-9)
    assertEqualsDouble(q.y, p.y, 1e-9)
  }
  test("rect intersect and union") {
    val a = Rect(0, 0, 10, 10); val b = Rect(5, 5, 20, 20)
    assertEquals(a.intersect(b), Rect(5, 5, 10, 10))
    assertEquals(a.union(b), Rect(0, 0, 20, 20))
    assert(a.intersect(Rect(11, 11, 12, 12)).isEmpty)
  }
  test("affine composition order: right operand applies first") {
    val m = Affine.translate(Vec2(10, 0)) * Affine.scale(2)
    assertEquals(m(Point(1, 1)), Point(12, 2))
  }
