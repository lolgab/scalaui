package scalaui.window

import scala.scalanative.meta.LinktimeInfo
import java.nio.file.{Files, Paths}

/** Runs against a real compositor (headless sway) with wl-clipboard installed; see tools/wayland_test.sh. Skipped elsewhere. */
class WaylandSuite extends scalaui.testkit.LoggedSuite:
  private def enabled = if LinktimeInfo.isLinux then sys.env.contains("WAYLAND_DISPLAY") && sys.env.contains("SCALAUI_WAYLAND_TESTS") else false

  private def run(cmd: String): Int = new ProcessBuilder("sh", "-c", cmd).inheritIO().start().waitFor()

  /** Keeps serving the connection (selection offers, send requests) while `body` runs. */
  private def pumping[A](win: Window)(body: => A): A =
    @volatile var done = false
    val t = new Thread(() => while !done do { win.pollEvents(); Thread.sleep(2) })
    t.start()
    try body finally { done = true; t.join() }

  private def pump(win: Window, ms: Int): Unit =
    val end = System.nanoTime() + ms * 1_000_000L
    while System.nanoTime() < end do { win.waitEvents(20) }

  // one shared window: the compositor hands keyboard focus (needed for selections) to the first window of a headless session
  private lazy val shared = WaylandWindow("wl-suite", 320, 200)

  test("window gets configured, titled and reports its size") {
    if enabled then
      val win = shared
      assert(win.width > 0 && win.height > 0)
      win.setTitle("renamed"); win.setMinSize(100, 100)
      CursorIcon.values.foreach(win.setCursor)
      win.present(Array.fill[Byte](win.width * win.height * 4)(-1), win.width, win.height)
      pump(win, 300)
  }

  test("clipboard text and a large image written by another client are read through pipes") {
    if enabled then
      val win = shared
      pump(win, 300)
      assertEquals(run("printf 'héllo wayland' | wl-copy"), 0)
      pump(win, 500)
      assertEquals(PlatformClipboard.get(), Some("héllo wayland"))
      val big = Array.tabulate[Byte](3 * 1024 * 1024)(i => (i * 11 + i / 97).toByte)
      Files.write(Paths.get("/tmp/wl-big.bin"), big)
      assertEquals(run("wl-copy -t image/png < /tmp/wl-big.bin"), 0)
      pump(win, 500)
      val got = PlatformClipboard.getData("image/png")
      assert(got.exists(java.util.Arrays.equals(_, big)), s"received ${got.map(_.length)}")
  }

  test("data set by the app is served to other clients") {
    if enabled then
      val win = shared
      pump(win, 300)
      val big = Array.tabulate[Byte](1024 * 1024)(i => (i * 5 + 1).toByte)
      // compositors ignore set_selection with a serial older than the previous selection: produce fresh input first
      run("wtype abc")
      pump(win, 400)
      PlatformClipboard.setData(Map("text/plain" -> "ünï".getBytes("UTF-8"), "image/png" -> big))
      pump(win, 200)
      pumping(win) {
        val rcTypes = run("wl-paste -l > /tmp/wl-types.out 2>&1")
        val types = new String(Files.readAllBytes(Paths.get("/tmp/wl-types.out")), "UTF-8")
        System.err.println(s"wl: wl-paste -l rc=$rcTypes -> ${types.replace("\n", " | ")}")
        assertEquals(run("wl-paste -n -t text/plain > /tmp/wl-small.out 2> /tmp/wl-small.err"), 0, new String(Files.readAllBytes(Paths.get("/tmp/wl-small.err")), "UTF-8"))
        assertEquals(new String(Files.readAllBytes(Paths.get("/tmp/wl-small.out")), "UTF-8"), "ünï")
        assertEquals(run("wl-paste -n -t image/png > /tmp/wl-big.out"), 0)
        assert(java.util.Arrays.equals(Files.readAllBytes(Paths.get("/tmp/wl-big.out")), big), "image payload differs")
      }
  }

  /** A second client that injects pointer input through the wlroots virtual-pointer protocol. */
  private final class VirtualPointer:
    private val c = WlConn.connect()
    private var manager = 0; private var managerName = 0; private var managerVersion = 1
    private val registry = c.newObject { (op, r) =>
      if op == 0 then
        val name = r.uint(); val iface = r.str(); val version = r.uint()
        if iface == "zwlr_virtual_pointer_manager_v1" then { managerName = name; managerVersion = version }
    }
    c.send(1, 1, new WlArgs().obj(registry))
    private def roundtrip(): Unit =
      var done = false
      val cb = c.newObject((_, _) => done = true)
      c.send(1, 0, new WlArgs().obj(cb))
      val end = System.nanoTime() + 2_000_000_000L
      while !done && System.nanoTime() < end do { c.fill(20); c.dispatch() }
    roundtrip()
    require(managerName != 0, "compositor has no virtual pointer protocol")
    manager = c.newObject((_, _) => ())
    c.send(registry, 0, new WlArgs().uint(managerName).str("zwlr_virtual_pointer_manager_v1").uint(1).obj(manager))
    private val ptr = c.newObject((_, _) => ())
    c.send(manager, 0, new WlArgs().obj(0).obj(ptr)) // create_virtual_pointer(null seat)
    roundtrip()
    private var t = 1000
    private def time(): Int = { t += 10; t }
    def moveTo(x: Int, y: Int): Unit = { c.send(ptr, 1, new WlArgs().uint(time()).uint(x).uint(y).uint(1024).uint(768)); c.send(ptr, 4); roundtrip() }
    def button(code: Int, down: Boolean): Unit = { c.send(ptr, 2, new WlArgs().uint(time()).uint(code).uint(if down then 1 else 0)); c.send(ptr, 4); roundtrip() }
    def scroll(value: Double): Unit = { c.send(ptr, 3, new WlArgs().uint(time()).uint(0).fixed(value)); c.send(ptr, 4); roundtrip() }
    def close(): Unit = { c.send(ptr, 8); roundtrip(); c.close() }

  test("pointer motion, buttons and scrolling from a virtual pointer arrive as events") {
    if enabled then
      val win = shared
      win.present(Array.fill[Byte](win.width * win.height * 4)(-1), win.width, win.height)
      pump(win, 200)
      val vp = new VirtualPointer
      val events = scala.collection.mutable.ArrayBuffer.empty[Event]
      def drain(): Unit = { var n = 0; while n < 5 do { events ++= win.waitEvents(30); n += 1 } }
      drain() // the seat gains its pointer capability once the virtual pointer exists
      vp.moveTo(100, 50); drain()
      CursorIcon.values.foreach { i => win.setCursor(i); drain() } // with a live pointer: cursor-shape-v1 and the hidden cursor (set_cursor null)
      win.setCursor(CursorIcon.Default)
      vp.moveTo(200, 100); drain() // absolute coordinates within the 1024x768 extent
      vp.button(0x110, true); drain(); vp.button(0x110, false); drain()
      vp.button(0x111, true); drain(); vp.button(0x111, false); drain()
      vp.scroll(10); drain()
      vp.close()
      val moved = events.collect { case Event.MouseMoved(x, y) => (x, y) }
      assert(moved.nonEmpty, s"no motion in $events")
      assert(events.exists { case Event.MouseDown(MouseButton.Left, _, _, _) => true; case _ => false }, s"no left press in $events")
      assert(events.exists { case Event.MouseUp(MouseButton.Left, _, _) => true; case _ => false }, "no left release")
      assert(events.exists { case Event.MouseDown(MouseButton.Right, _, _, _) => true; case _ => false }, "no right press")
      assert(events.exists { case Event.Scroll(_, dy) => dy < 0 || dy > 0; case _ => false }, "no scroll")
  }

