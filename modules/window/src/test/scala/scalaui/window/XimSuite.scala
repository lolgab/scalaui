package scalaui.window

import scala.scalanative.meta.LinktimeInfo
import java.nio.file.{Files, Paths}

/** Drives the XIM client against `tools/xim_server.py` (a protocol-level test server) inside Xvfb. Needs
  * `SCALAUI_X11_TESTS`, `DISPLAY` and `XMODIFIERS=@im=testxim`; skipped elsewhere. */
class XimSuite extends scalaui.testkit.LoggedSuite:
  private def enabled = if LinktimeInfo.isLinux then sys.env.contains("DISPLAY") && sys.env.contains("SCALAUI_X11_TESTS") && sys.env.get("XMODIFIERS").exists(m => m.contains("testxim")) else false
  private def realEnabled = if LinktimeInfo.isLinux then sys.env.contains("DISPLAY") && sys.env.contains("SCALAUI_X11_TESTS") && sys.env.get("XMODIFIERS").contains("@im=ibus") else false
  private val logPath = "/tmp/xim-test.log"

  private def withServer(opts: String*)(body: => Unit): Unit =
    Files.deleteIfExists(Paths.get(logPath))
    val server = new ProcessBuilder(("python3" +: "tools/xim_server.py" +: "--log" +: logPath +: opts)*).inheritIO().start()
    try
      var n = 0
      while !log.contains("ready") && n < 100 do { Thread.sleep(50); n += 1 }
      assert(log.contains("ready"), "XIM test server did not start")
      body
    finally { server.destroy(); server.waitFor(); () }

  private def log: String = if Files.exists(Paths.get(logPath)) then new String(Files.readAllBytes(Paths.get(logPath)), "UTF-8") else ""

  private def key(win: Window, name: String): Unit =
    new ProcessBuilder("xdotool", "key", "--window", win.nativeHandle.toLong.toString, name).inheritIO().start().waitFor()
    ()

  /** Polls events until `done` accepts the accumulated list (or 4 s pass). */
  private def collect(win: Window)(done: Seq[Event] => Boolean): Seq[Event] =
    val got = scala.collection.mutable.ArrayBuffer.empty[Event]
    val deadline = System.nanoTime() + 4_000_000_000L
    while !done(got.toSeq) && System.nanoTime() < deadline do got ++= win.waitEvents(50)
    got.toSeq

  private def texts(es: Seq[Event]) = es.collect { case Event.TextInput(t) => t }
  private def comps(es: Seq[Event]) = es.collect { case Event.Composition(t, a, _) => (t, a) }

  private def session(opts: String*)(body: X11Window => Unit): Unit =
    if enabled then
      withServer(opts*) {
        val win = X11Window("xim-test", 200, 100)
        try
          assert(win.inputMethodActive, "XIM connection was not established; server log:\n" + log)
          win.focus(); win.waitEvents(100)
          body(win)
        finally win.close()
      }

  test("dead-key composition goes through preedit callbacks and commits an accented letter (UTF-8)") {
    session() { win =>
      key(win, "grave"); key(win, "a")
      val es = collect(win)(e => texts(e).nonEmpty)
      assertEquals(texts(es), Seq("à"))
      assertEquals(comps(es).headOption, Some(("`", 1)))
      assert(comps(es).exists(_._1.isEmpty), "composition was not cleared")
      assert(log.contains("encoding List(UTF-8, COMPOUND_TEXT)") || log.contains("encoding ['UTF-8', 'COMPOUND_TEXT'] -> 0"), log)
    }
  }

  test("Compound Text commits and preedit text decode (Latin-1 and UTF-8 escape segments)") {
    session("--encoding", "ct") { win =>
      key(win, "grave"); key(win, "e")
      assertEquals(texts(collect(win)(e => texts(e).nonEmpty)), Seq("è"))
      key(win, "F9")
      assertEquals(texts(collect(win)(e => texts(e).nonEmpty)), Seq("你好"))
    }
  }

  test("synchronous event flow: keys are held until the server acknowledges") {
    session("--sync") { win =>
      key(win, "grave"); key(win, "o"); key(win, "F9")
      val es = collect(win)(e => texts(e).length >= 2)
      assertEquals(texts(es), Seq("ò", "你好"))
    }
  }

  test("keys the input method does not consume come back as ordinary key events") {
    session() { win =>
      key(win, "x")
      val es = collect(win)(_.exists { case _: Event.KeyDown => true; case _ => false })
      assert(es.exists { case Event.KeyDown(_, "x", _, _, _) => true; case _ => false }, es.toString)
      assertEquals(texts(es), Nil)
    }
  }

  test("input focus and the caret position are reported to the server") {
    session("--need-focus") { win =>
      win.setImeRect(10, 20, 2, 16)
      var n = 0
      while !(log.contains("spot 10 36") && log.contains("focus")) && n < 40 do { win.waitEvents(50); Thread.sleep(50); n += 1 }
      assert(log.contains("spot 10 36"), log)
      assert(log.contains("focus"), log)
      key(win, "F9")
      assertEquals(texts(collect(win)(e => texts(e).nonEmpty)), Seq("你好"))
    }
  }

  test("over-the-spot servers (no preedit callbacks) still commit text") {
    session("--style", "position") { win =>
      assert(log.contains("create-ic style=0x404"), log)
      assert(log.contains("has-spot"), log)
      key(win, "grave"); key(win, "u")
      val es = collect(win)(e => texts(e).nonEmpty)
      assertEquals(texts(es), Seq("ù"))
      assertEquals(comps(es), Nil)
    }
  }

  test("without a server the window falls back to plain key handling") {
    if enabled then
      val win = X11Window("xim-none", 100, 100)
      try assert(!win.inputMethodActive)
      finally win.close()
  }

  test("real server (ibus-daemon --xim, m17n vi-telex engine): a, s, space composes and commits a-acute") {
    if realEnabled then
      val win = X11Window("xim-real", 200, 100)
      try
        assert(win.inputMethodActive, "no XIM connection to ibus")
        win.focus(); win.waitEvents(200)
        new ProcessBuilder("xdotool", "key", "a", "s", "space").inheritIO().start().waitFor()
        val es = collect(win)(e => texts(e).exists(_.contains("\u00e1")))
        System.err.println("real server events: " + es.filter { case _: Event.KeyUp | _: Event.Focused => false; case _ => true })
        assert(comps(es).exists(_._1.contains("\u00e1")), "no preedit with a-acute: " + comps(es))
        assert(texts(es).exists(_.contains("\u00e1")), "no committed a-acute: " + es)
      finally win.close()
  }

  test("Compound Text decoder") {
    assertEquals(CompoundText.decode("abc é".getBytes("ISO-8859-1")), "abc é")
    assertEquals(CompoundText.decode(Array[Byte](0x1b, '%', 'G') ++ "你好".getBytes("UTF-8") ++ Array[Byte](0x1b, '%', '@') ++ "!".getBytes), "你好!")
    val seg = "iso10646-1".getBytes ++ Array[Byte](2) ++ "€".getBytes("UTF-8")
    assertEquals(CompoundText.decode(Array[Byte](0x1b, '%', '/', '1', (0x80 | (seg.length >> 7)).toByte, (0x80 | (seg.length & 0x7f)).toByte) ++ seg), "€")
  }
