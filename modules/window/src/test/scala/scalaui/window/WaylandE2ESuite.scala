package scalaui.window

import scala.scalanative.meta.LinktimeInfo
import scala.collection.mutable.ArrayBuffer
import java.nio.file.{Files, Paths}

/** End-to-end checks against a real wlroots compositor (headless sway): configure/resize, pixel exactness through grim,
  * keyboard through wtype, output scale (integer and fractional), window controls, server-side decorations and
  * compositor-initiated close. Needs sway, grim, wtype and jq; see tools/wayland_unit.sh. Skipped elsewhere.
  */
class WaylandE2ESuite extends scalaui.testkit.LoggedSuite:
  private def enabled = if LinktimeInfo.isLinux then sys.env.contains("WAYLAND_DISPLAY") && sys.env.contains("SCALAUI_WAYLAND_TESTS") && sys.env.contains("SWAYSOCK") else false

  private def sh(cmd: String): String =
    val out = "/tmp/wl-e2e.out"
    val rc = new ProcessBuilder("sh", "-c", cmd + " > " + out + " 2>&1").start().waitFor()
    val s = new String(Files.readAllBytes(Paths.get(out)), "UTF-8")
    if rc != 0 then System.err.println(s"wl: `$cmd` rc=$rc: $s")
    s.trim

  private def pump(win: Window, ms: Int): Seq[Event] =
    val end = System.nanoTime() + ms * 1_000_000L
    val all = ArrayBuffer.empty[Event]
    while System.nanoTime() < end do all ++= win.waitEvents(20)
    all.toSeq

  private val Title = "wl-e2e"
  private lazy val win = WaylandWindow(Title, 400, 300)

  /** Content rectangle of the test window in compositor (layout) coordinates. */
  private def rect(title: String = Title): (Int, Int, Int, Int) =
    val s = sh(s"""swaymsg -t get_tree | jq -r '.. | select(.name? == "$title" and .pid? != null) | "\\(.rect.x + .window_rect.x) \\(.rect.y + .window_rect.y) \\(.window_rect.width) \\(.window_rect.height)"' | head -1""")
    val p = s.split(" ").map(_.toInt)
    (p(0), p(1), p(2), p(3))

  private def gradient(w: Int, h: Int): Array[Byte] =
    val a = new Array[Byte](w * h * 4)
    var y = 0
    while y < h do
      var x = 0
      while x < w do
        val i = (y * w + x) * 4
        a(i) = (x * 255 / math.max(1, w - 1)).toByte; a(i + 1) = (y * 255 / math.max(1, h - 1)).toByte; a(i + 2) = ((x ^ y) & 0xff).toByte; a(i + 3) = -1
        x += 1
      y += 1
    a

  /** Screenshot through grim (scale = physical pixels per layout pixel) as RGB, plus its size. */
  private def grim(scale: Double): (Array[Byte], Int, Int) =
    sh(s"grim -s $scale -t ppm /tmp/wl-shot.ppm")
    val b = Files.readAllBytes(Paths.get("/tmp/wl-shot.ppm"))
    var pos = 0
    def token(): String =
      while Character.isWhitespace(b(pos)) do pos += 1
      val s = pos; while !Character.isWhitespace(b(pos)) do pos += 1
      new String(b, s, pos - s)
    assertEquals(token(), "P6"); val w = token().toInt; val h = token().toInt; token(); pos += 1
    (java.util.Arrays.copyOfRange(b, pos, b.length), w, h)

  /** Counts pixels of the window region (x0, y0 in screenshot pixels) that differ from the presented RGBA buffer. */
  private def mismatches(shot: (Array[Byte], Int, Int), x0: Int, y0: Int, rgba: Array[Byte], w: Int, h: Int): Int =
    var bad = 0; var y = 0
    while y < h do
      var x = 0
      while x < w do
        val s = ((y0 + y) * shot._2 + x0 + x) * 3; val i = (y * w + x) * 4
        if shot._1(s) != rgba(i) || shot._1(s + 1) != rgba(i + 1) || shot._1(s + 2) != rgba(i + 2) then bad += 1
        x += 1
      y += 1
    bad

  test("opens, is configured and titled by the compositor") {
    if enabled then
      val evs = pump(win, 500)
      assert(win.width > 0 && win.height > 0)
      win.present(gradient(win.width, win.height), win.width, win.height)
      pump(win, 300)
      assertEquals(sh("swaymsg -t get_tree | jq -r '.. | select(.name? == \"" + Title + "\") | .app_id' | head -1"), "wl-e2e")
      win.setTitle("renamed ✓")
      pump(win, 300)
      assertEquals(rect("renamed ✓")._3, win.width)
      win.setTitle(Title)
      pump(win, 200)
  }

  test("wl_shm pixels reach the screen unchanged (grim vs presented buffer)") {
    if enabled then
      pump(win, 200)
      val (w, h) = (win.width, win.height)
      val px = gradient(w, h)
      win.present(px, w, h)
      pump(win, 400)
      val (x, y, rw, rh) = rect()
      assertEquals((rw, rh), (w, h))
      assertEquals(mismatches(grim(1.0), x, y, px, w, h), 0)
  }

  test("xdg-shell resize configures arrive as Resized and re-allocated buffers render exactly") {
    if enabled then
      pump(win, 200)
      sh(s"""swaymsg '[title="$Title"] floating enable, resize set 500 360'""")
      val evs = pump(win, 700)
      assert(evs.contains(Event.Resized(500, 360)), s"events: $evs")
      assertEquals((win.width, win.height), (500, 360))
      val px = gradient(500, 360)
      win.present(px, 500, 360)
      pump(win, 400)
      val (x, y, rw, rh) = rect()
      assertEquals((rw, rh), (500, 360))
      assertEquals(mismatches(grim(1.0), x, y, px, 500, 360), 0)
      sh(s"""swaymsg '[title="$Title"] resize set 333 222'""")
      assert(pump(win, 700).contains(Event.Resized(333, 222)))
      val px2 = gradient(333, 222)
      win.present(px2, 333, 222)
      pump(win, 400)
      val (x2, y2, _, _) = rect()
      assertEquals(mismatches(grim(1.0), x2, y2, px2, 333, 222), 0)
      sh(s"""swaymsg '[title="$Title"] floating disable'""")
      pump(win, 500)
      win.present(gradient(win.width, win.height), win.width, win.height)
      pump(win, 200)
  }

  test("keyboard through wtype: keysyms, text, modifiers, repeat and a non-US keymap") {
    if enabled then
      pump(win, 300)
      def typed(cmd: String): Seq[Event] =
        val t = new Thread(() => { sh(cmd); () }); t.start()
        val evs = ArrayBuffer.empty[Event]
        while t.isAlive do evs ++= win.waitEvents(20)
        evs ++= pump(win, 300)
        evs.toSeq
      val abc = typed("wtype ab; wtype -M shift c -m shift")
      // wtype builds a one-level keymap per typed key, so shifted `c` is still keysym c (as with libxkbcommon), but Shift is reported
      assertEquals(abc.collect { case Event.TextInput(t) => t }, Seq("a", "b", "c"))
      assert(abc.exists { case Event.KeyDown(_, "", "a", m, false) => !m.shift; case _ => false }, s"$abc")
      assert(abc.exists { case Event.KeyDown(_, _, "c", m, _) => m.shift; case _ => false }, s"shift state lost: $abc")
      val nav = typed("wtype -k Left -k BackSpace -k Return -k Tab -k Escape -k Home -k Delete -k space")
      val keys = nav.collect { case Event.KeyDown(k, _, _, _, _) => k }
      assertEquals(keys, Seq(Key.Left, Key.Backspace, Key.Enter, Key.Tab, Key.Escape, Key.Home, Key.Delete, Key.Space))
      val ctrl = typed("wtype -M ctrl a -m ctrl")
      assert(ctrl.exists { case Event.KeyDown(_, _, "a", m, _) => m.control; case _ => false }, s"$ctrl")
      assert(!ctrl.exists(_.isInstanceOf[Event.TextInput]), "ctrl+a must not produce text")
      val uni = typed("wtype 'é€ñ😀'")
      assertEquals(uni.collect { case Event.TextInput(t) => t }.mkString, "é€ñ😀")
      val rep = typed("wtype -P x -s 1500 -p x")
      val repeats = rep.count { case Event.KeyDown(_, _, _, _, true) => true; case _ => false }
      assert(repeats >= 5, s"client-side key repeat produced $repeats repeats")
      assert(rep.exists(_.isInstanceOf[Event.KeyUp]), "no key release")
  }

  test("cursor shapes and Hidden are accepted without a protocol error") {
    if enabled then
      pump(win, 200)
      CursorIcon.values.foreach { i => win.setCursor(i); pump(win, 60) }
      win.setCursor(CursorIcon.Default)
      assert(pump(win, 100).forall(_ != Event.CloseRequested))
  }

  test("fullscreen request and release") {
    if enabled then
      pump(win, 200)
      assert(!win.isFullscreen)
      win.setFullscreen(true)
      pump(win, 700)
      assert(win.isFullscreen)
      assertEquals(sh("swaymsg -t get_tree | jq -r '.. | select(.name? == \"" + Title + "\") | .fullscreen_mode' | head -1"), "1")
      win.setFullscreen(false)
      pump(win, 700)
      assert(!win.isFullscreen)
  }

  test("server-side decorations are negotiated through xdg-decoration") {
    if enabled then
      pump(win, 200)
      sh(s"""swaymsg '[title="$Title"] border normal'""")
      pump(win, 500)
      val deco = sh("swaymsg -t get_tree | jq -r '.. | select(.name? == \"" + Title + "\") | .deco_rect.height' | head -1").toInt
      assert(deco > 0, "compositor draws no title bar for the window")
      sh(s"""swaymsg '[title="$Title"] border none'""")
      pump(win, 300)
  }

  test("output scale 2 and 1.5: ScaleChanged, logical size, exact physical pixels") {
    if enabled then
      pump(win, 200)
      sh(s"""swaymsg '[title="$Title"] floating enable, resize set 400 300'""")
      pump(win, 500)
      def check(scale: Double, mode: String): Unit =
        // modes chosen so the layout size is integral: grim then returns the output pixels without resampling
        sh(s"swaymsg output HEADLESS-1 resolution $mode scale $scale")
        val evs = pump(win, 1000)
        assert(evs.contains(Event.ScaleChanged(scale)) || win.scaleFactor == scale, s"scale ${win.scaleFactor} events $evs")
        assertEquals(win.scaleFactor, scale)
        assertEquals((win.width, win.height), (400, 300), s"events $evs")
        sh(s"""swaymsg '[title="$Title"] move position 20 20'""") // the old floating position may lie outside the smaller layout
        pump(win, 200)
        val (pw, ph) = (math.round(win.width * scale).toInt, math.round(win.height * scale).toInt)
        val px = gradient(pw, ph)
        win.present(px, pw, ph)
        pump(win, 500)
        val (x, y, rw, rh) = rect()
        assertEquals((rw, rh), (win.width, win.height))
        val shot = grim(scale)
        assertEquals(shot._2, mode.takeWhile(_ != 'x').toInt)
        val bad = mismatches(shot, math.round(x * scale).toInt, math.round(y * scale).toInt, px, pw, ph)
        assertEquals(bad, 0, s"scale $scale")
      try { check(2.0, "1024x768"); check(1.5, "1200x900") }
      finally
        sh("swaymsg output HEADLESS-1 resolution 1024x768 scale 1")
        pump(win, 800)
        sh(s"""swaymsg '[title="$Title"] floating disable'""")
        pump(win, 500)
        win.present(gradient(win.width, win.height), win.width, win.height)
        pump(win, 200)
      assertEquals(win.scaleFactor, 1.0)
  }

  test("compositor close request becomes CloseRequested") {
    if enabled then
      val w2 = WaylandWindow("closer", 200, 150)
      w2.present(gradient(200, 150), 200, 150)
      pump(w2, 300)
      sh("""swaymsg '[title="closer"] kill'""")
      assert(pump(w2, 800).contains(Event.CloseRequested))
      w2.close()
  }
