package scalaui.clipboard

import scala.scalanative.meta.LinktimeInfo
import scalaui.window.ObjC.*

/** Uses a private named pasteboard so the user's real clipboard is never touched. */
class ClipboardSuite extends scalaui.testkit.LoggedSuite:
  test("binary data round-trips through a private pasteboard") {
    if LinktimeInfo.isMac then
      val pool = send(send(cls("NSAutoreleasePool"), "alloc"), "init")
      val pb = send(cls("NSPasteboard"), "pasteboardWithUniqueName")
      send(pb, "clearContents")
      val png = Array.tabulate[Byte](5000)(i => (i * 31).toByte)
      Clipboard.macSetData(pb, "public.png", png)
      assert(java.util.Arrays.equals(Clipboard.macGetData(pb, "public.png").get, png))
      assertEquals(Clipboard.macGetData(pb, "com.example.nothing"), None)
      send(pb, "releaseGlobally")
      send(pool, "drain")
  }
