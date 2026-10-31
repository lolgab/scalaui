package scalaui.gpu

import scalaui.color.Color
import scalaui.geom.Rect

/** Smoke test for the (hardware-unverified) Direct3D 12 backend: renders a frame and uploads a texture. Skipped when
  * `d3d12.dll` is unavailable, which includes every non-Windows host. */
class D3D12Suite extends scalaui.testkit.LoggedSuite:
  test("d3d12 renders a frame") {
    if !D3D12Backend.available then
      assert(!sys.env.contains("SCALAUI_REQUIRE_D3D12"), "D3D12 required but unavailable")
      println("d3d12 unavailable: skipped")
    else
      val window = scalaui.window.Window.create("d3d12 test", 128, 128)
      try
        D3D12Backend.create(window) match
          case None => assert(!sys.env.contains("SCALAUI_REQUIRE_D3D12"), "D3D12 backend failed to initialise")
          case Some(gpu) =>
            val tex = gpu.createTexture(2, 2, Array.fill[Byte](16)(-1))
            tex.update(0, 0, 1, 1, Array[Byte](0, 0, 0, -1))
            val p = Color(0.9f, 0.2f, 0.1f).premultiplied
            val v = Array[Float](10, 10, 0, 0, p.r, p.g, p.b, p.a, 90, 10, 1, 0, p.r, p.g, p.b, p.a, 10, 90, 0, 1, p.r, p.g, p.b, p.a)
            gpu.submit(Frame(Color(0.1f, 0.1f, 0.12f), 128, 128, 1.0, v, Vector(DrawCall(gpu.whiteTexture, None, 0, 3))))
            gpu.submit(Frame(Color(0.1f, 0.1f, 0.12f), 200, 150, 1.0, v, Vector(DrawCall(tex, Some(Rect(0, 0, 50, 50)), 0, 3))))
            gpu.close()
      finally window.close()
  }
