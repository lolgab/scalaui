package scalaui.window

import scala.scalanative.meta.LinktimeInfo
import java.nio.file.{Files, Paths}

/** Runs against a real X server (e.g. Xvfb) with `xclip` installed; skipped elsewhere. */
class X11Suite extends scalaui.testkit.LoggedSuite:
  private def enabled = if LinktimeInfo.isLinux then sys.env.contains("DISPLAY") && sys.env.contains("SCALAUI_X11_TESTS") else false

  private def run(cmd: String*): Int =
    new ProcessBuilder(cmd*).inheritIO().start().waitFor()

  /** Serves our selection requests while `body` runs a foreign client. */
  private def pumping[A](win: Window)(body: => A): A =
    @volatile var done = false
    val t = new Thread(() => while !done do { win.pollEvents(); Thread.sleep(2) })
    t.start()
    try body finally { done = true; t.join() }

  test("small and large (INCR) clipboard data is served to other X clients") {
    if enabled then
      val win = X11Window("t", 100, 100)
      val big = Array.tabulate[Byte](3 * 1024 * 1024)(i => (i * 7 + i / 251).toByte)
      val small = "héllo".getBytes("UTF-8")
      PlatformClipboard.setData(Map("text/plain" -> small, "image/png" -> big))
      pumping(win) {
        assertEquals(run("sh", "-c", "xclip -o -selection clipboard -t UTF8_STRING > /tmp/x11-small.out"), 0)
        assertEquals(new String(Files.readAllBytes(Paths.get("/tmp/x11-small.out")), "UTF-8"), "héllo")
        assertEquals(run("sh", "-c", "xclip -o -selection clipboard -t image/png > /tmp/x11-big.out"), 0)
        assert(java.util.Arrays.equals(Files.readAllBytes(Paths.get("/tmp/x11-big.out")), big), "INCR payload differs")
      }
      win.close()
  }

  test("large data owned by another client is received through INCR") {
    if enabled then
      val win = X11Window("t", 100, 100)
      val big = Array.tabulate[Byte](2 * 1024 * 1024)(i => (i * 13 + 5).toByte)
      Files.write(Paths.get("/tmp/x11-in.bin"), big)
      val owner = new ProcessBuilder("sh", "-c", "xclip -i -selection clipboard -t image/png -loops 1 /tmp/x11-in.bin").inheritIO().start()
      Thread.sleep(500)
      val got = PlatformClipboard.getData("image/png")
      assert(got.exists(java.util.Arrays.equals(_, big)), s"received ${got.map(_.length)}")
      owner.destroy()
      win.close()
  }

  test("cursor, min size, fullscreen and position calls do not break the connection") {
    if enabled then
      val win = X11Window("t", 100, 100)
      CursorIcon.values.foreach(win.setCursor)
      win.setMinSize(50, 50); win.setResizable(false); win.setResizable(true)
      win.setFullscreen(true); win.setFullscreen(false); win.setAlwaysOnTop(true)
      win.setPosition(20, 30)
      win.setIcon(Array.fill[Byte](16 * 16 * 4)(-1), 16, 16)
      win.pollEvents()
      assertEquals(win.width, 100)
      win.close()
  }

  test("files dropped through XDND arrive as FilesDropped with the pointer position") {
    if enabled then
      val win = X11Window("xdnd-target", 200, 200)
      win.pollEvents()
      val src = new ProcessBuilder("python3", "tools/xdnd_source.py", "xdnd-target", "/tmp/a b.txt", "/tmp/c.png").inheritIO().start()
      var dropped: Option[Event.FilesDropped] = None
      var hovered = false
      val deadline = System.nanoTime() + 8_000_000_000L
      while dropped.isEmpty && System.nanoTime() < deadline do
        win.waitEvents(50).foreach {
          case d: Event.FilesDropped => dropped = Some(d)
          case _: Event.FilesHovered => hovered = true
          case _ => ()
        }
      src.waitFor()
      assert(hovered, "no hover events")
      assertEquals(dropped.map(_.paths), Some(Seq("/tmp/a b.txt", "/tmp/c.png")))
      win.close()
  }

