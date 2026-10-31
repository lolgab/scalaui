package scalaui.gpu

import scalaui.color.Color
import scalaui.geom.Rect

/** Renders the same frame with Vulkan (MoltenVK on macOS) and with the software renderer and compares them. Skipped when
  * no Vulkan driver is available. */
class VulkanSuite extends scalaui.testkit.LoggedSuite:
  private def quad(v: scala.collection.mutable.ArrayBuffer[Float], r: Rect, u0: Float, v0: Float, u1: Float, v1: Float, c: Color): Unit =
    val p = c.premultiplied
    def vert(x: Double, y: Double, u: Float, w: Float) = v ++= Seq(x.toFloat, y.toFloat, u, w, p.r, p.g, p.b, p.a)
    vert(r.x0, r.y0, u0, v0); vert(r.x1, r.y0, u1, v0); vert(r.x0, r.y1, u0, v1)
    vert(r.x1, r.y0, u1, v0); vert(r.x1, r.y1, u1, v1); vert(r.x0, r.y1, u0, v1)

  private def meanDiff(a: Array[Byte], b: Array[Byte]): Double =
    a.indices.map(i => math.abs((a(i) & 0xff) - (b(i) & 0xff))).sum.toDouble / a.length

  test("vulkan output matches the software renderer") {
    val vk = VulkanBackend.create(None)
    if vk.isEmpty then
      assert(!sys.env.contains("SCALAUI_REQUIRE_VULKAN"), "Vulkan required but unavailable")
      println("vulkan unavailable: skipped")
    vk.foreach { gpu =>
      val soft = new HeadlessBackend
      // 4x4 checker texture
      val checker = Array.tabulate[Byte](4 * 4 * 4)(i => if ((i / 4) % 4 + (i / 16)) % 2 == 0 then -1 else (if i % 4 == 3 then -1 else 40))
      def build(white: Texture, tex: Texture): Frame =
        val v = scala.collection.mutable.ArrayBuffer.empty[Float]
        quad(v, Rect(10, 10, 90, 60), 0, 0, 1, 1, Color(0.9f, 0.2f, 0.1f))
        quad(v, Rect(50, 30, 120, 90), 0, 0, 1, 1, Color(0.1f, 0.4f, 0.9f, 0.5f)) // translucent overlap
        val n1 = 12
        quad(v, Rect(20, 70, 100, 110), 0, 0, 1, 1, Color.White) // textured
        quad(v, Rect(0, 0, 128, 128), 0, 0, 1, 1, Color(0f, 1f, 0f, 0.3f)) // scissored overlay
        Frame(Color(0.1f, 0.1f, 0.12f), 128, 128, 1.0, v.toArray, Vector(
          DrawCall(white, None, 0, n1),
          DrawCall(tex, None, n1, 6),
          DrawCall(white, Some(Rect(60, 0, 100, 128)), n1 + 6, 6)))
      val a = soft.createTexture(4, 4, checker); val sw = soft.whiteTexture
      val b = gpu.createTexture(4, 4, checker); val gw = gpu.whiteTexture
      soft.submit(build(sw, a)); gpu.submit(build(gw, b))
      val (sp, w, h) = soft.last.get
      val (gp, gw2, gh) = gpu.lastFrame.get
      assertEquals((gw2, gh), (w, h))
      java.nio.file.Files.writeString(java.nio.file.Paths.get("target/vk-device.txt"), gpu.deviceName)
      val d = meanDiff(sp, gp)
      assert(d < 2.0, s"mean abs diff $d")
      // a pixel inside the opaque red quad (not covered by anything else)
      val i = (20 * w + 20) * 4
      assert(math.abs((gp(i) & 0xff) - 230) <= 2 && math.abs((gp(i + 1) & 0xff) - 51) <= 2, s"red quad pixel ${(gp(i) & 0xff, gp(i + 1) & 0xff)}")
      // scissor: pixel at x=110 must not carry the green overlay
      val j = (100 * w + 110) * 4
      assertEquals(gp(j + 1) & 0xff, sp(j + 1) & 0xff)
      gpu.close()
    }
  }

  test("vulkan partial redraw with damage is pixel-identical to a full redraw") {
    val vk = VulkanBackend.create(None)
    if vk.isEmpty then println("vulkan unavailable: skipped")
    vk.foreach { gpu =>
      DamageScenario.run(gpu, 1.0, () => gpu.lastFrame.get)
      DamageScenario.run(gpu, 2.0, () => gpu.lastFrame.get)
      gpu.close()
    }
  }
