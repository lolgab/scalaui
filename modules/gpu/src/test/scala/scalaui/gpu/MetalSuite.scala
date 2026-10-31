package scalaui.gpu

import scala.scalanative.meta.LinktimeInfo
import scala.scalanative.unsafe.*
import scalaui.window.{Frameworks, ObjC, ViewShim, Window}
import ObjC.*

/** Metal backend on a detached layer-backed view (no window is shown): partial redraws with damage must produce exactly the
  * pixels of a full redraw. Skipped where Metal is unavailable. */
class MetalSuite extends scalaui.testkit.LoggedSuite:
  test("metal partial redraw with damage is pixel-identical to a full redraw") {
    if LinktimeInfo.isMac then
      Frameworks.load()
      send(cls("NSApplication"), "sharedApplication")
      val v = ViewShim.scalaui_view_create(64, 48)
      val win = new Window:
        def nativeHandle: Ptr[Byte] = v
        def present(rgba: Array[Byte], w: Int, h: Int): Unit = ()
        def pollEvents(): Seq[scalaui.window.Event] = Nil
        def width: Int = 64
        def height: Int = 48
        def close(): Unit = ()
        def scaleFactor: Double = 1.0
        def setTitle(t: String): Unit = ()
      val be = new MetalBackend(win)
      be.capture = true
      DamageScenario.run(be, 1.0, () => be.captured.get)
      DamageScenario.run(be, 2.0, () => be.captured.get)
    else println("metal unavailable: skipped")
  }
