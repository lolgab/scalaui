package scalaui.window

import scala.scalanative.unsafe.*
import scala.scalanative.meta.LinktimeInfo
import ObjC.*

@link("objc") @extern private object TestMsg:
  @name("objc_msgSend") def text(self: Id, sel: Id, s: Id, loc: CLongLong, len: CLongLong): Id = extern
  @name("objc_msgSend") def size(self: Id, sel: Id, w: CDouble, h: CDouble): Id = extern
  @name("objc_msgSend") def marked(self: Id, sel: Id, s: Id, l1: CLongLong, n1: CLongLong, l2: CLongLong, n2: CLongLong): Id = extern

/** Drives the ScaluiView's NSTextInputClient and drag methods directly (no real input events are synthesized). */
class ViewSuite extends munit.FunSuite:
  // inline + link-time check: on other platforms the Cocoa calls are dropped from the binary entirely
  private inline def withView(inline f: Id => Unit): Unit =
    if LinktimeInfo.isMac then
      Frameworks.load()
      send(cls("NSApplication"), "sharedApplication")
      val v = ViewShim.scalaui_view_create(100, 100)
      f(v)

  private def drain(v: Id): Seq[(Int, Double, Double, Double, String)] =
    val e = stackalloc[ViewShim.ViewEvent]()
    val out = Seq.newBuilder[(Int, Double, Double, Double, String)]
    while ViewShim.scalaui_view_poll(v, e) != 0 do
      out += ((e._1, e._2, e._3, e._4, if e._5 == null then "" else fromCString(e._5)))
      if e._5 != null then ViewShim.scalaui_free(e._5.asInstanceOf[Ptr[Byte]])
    out.result()

  test("insertText queues committed text") {
    withView { v =>
      TestMsg.text(v, sel("insertText:replacementRange:"), nsString("héllo 😀"), Long.MaxValue, 0)
      assertEquals(drain(v), Seq((1, 0.0, 0.0, 0.0, "héllo 😀")))
    }
  }

  test("marked text composes then commits, ending the composition first") {
    withView { v =>
      TestMsg.marked(v, sel("setMarkedText:selectedRange:replacementRange:"), nsString("にほ"), 1, 0, Long.MaxValue, 0)
      assertEquals(long(v, "hasMarkedText"), 1L)
      TestMsg.text(v, sel("insertText:replacementRange:"), nsString("日本"), Long.MaxValue, 0)
      assertEquals(long(v, "hasMarkedText"), 0L)
      assertEquals(drain(v), Seq((2, 1.0, 0.0, 0.0, "にほ"), (2, 0.0, 0.0, 0.0, ""), (1, 0.0, 0.0, 0.0, "日本")))
    }
  }

  test("unmarkText ends a composition") {
    withView { v =>
      TestMsg.marked(v, sel("setMarkedText:selectedRange:replacementRange:"), nsString("a"), 0, 1, Long.MaxValue, 0)
      send(v, "unmarkText")
      assertEquals(long(v, "hasMarkedText"), 0L)
      assertEquals(drain(v).map(_._5), Seq("a", ""))
    }
  }

  test("view accepts first responder") {
    withView(v => assertEquals(long(v, "acceptsFirstResponder"), 1L))
  }

  test("accessibility tree is published as children and AXPress is queued") {
    withView { v =>
      import scalaui.geom.Rect
      val nodes = Seq(
        AccessNode(AccessRole.Button, "Save", Rect(10, 10, 60, 30)),
        AccessNode(AccessRole.CheckBox, "Dark", Rect(10, 40, 60, 60), checked = Some(true)),
        AccessNode(AccessRole.TextInput, "Name", Rect(10, 70, 100, 90), value = "Ada", focused = true))
      // reuse CocoaWindow's marshalling by building the arrays here
      Zone {
        val n = nodes.length
        val roles = alloc[CString](n); val labels = alloc[CString](n); val values = alloc[CString](n)
        val bounds = alloc[CDouble](4 * n); val flags = alloc[CInt](n)
        nodes.zipWithIndex.foreach { (a, i) =>
          roles(i) = toCString(CocoaWindow.axRole(a.role)); labels(i) = toCString(a.label); values(i) = toCString(a.value)
          bounds(4 * i) = a.bounds.x0; bounds(4 * i + 1) = a.bounds.y0; bounds(4 * i + 2) = a.bounds.width; bounds(4 * i + 3) = a.bounds.height
          flags(i) = (if a.focused then 1 else 0) | (if a.checked.isDefined then 4 else 0) | (if a.checked.contains(true) then 8 else 0)
        }
        ViewShim.scalaui_view_set_access(v, n, roles, labels, values, bounds, flags)
      }
      val kids = send(v, "accessibilityChildren")
      assertEquals(long(kids, "count"), 3L)
      val second = C.msgL(kids, sel("objectAtIndex:"), 1L)
      assertEquals(fromNSString(send(second, "accessibilityLabel")), "Dark")
      assertEquals(fromNSString(send(second, "accessibilityRole")), "AXCheckBox")
      assertEquals(long(send(second, "accessibilityValue"), "intValue"), 1L)
      val third = C.msgL(kids, sel("objectAtIndex:"), 2L)
      assertEquals(fromNSString(send(third, "accessibilityValue")), "Ada")
      assertEquals(long(second, "accessibilityPerformPress"), 1L)
      assertEquals(drain(v).map(e => (e._1, e._2)), Seq((7, 1.0)))
    }
  }

  test("text fields expose value, selection range and lines through NSAccessibility, and selection changes are queued") {
    withView { v =>
      Zone {
        val roles = alloc[CString](1); val labels = alloc[CString](1); val values = alloc[CString](1)
        val bounds = alloc[CDouble](4); val flags = alloc[CInt](1); val info = alloc[CInt](4)
        roles(0) = toCString("AXTextField"); labels(0) = toCString("Notes"); values(0) = toCString("one\ntwo \uD83D\uDE00x")
        bounds(0) = 10; bounds(1) = 10; bounds(2) = 100; bounds(3) = 40; flags(0) = 1
        ViewShim.scalaui_view_set_access(v, 1, roles, labels, values, bounds, flags)
        info(0) = 2; info(1) = 6; info(2) = 4; info(3) = 7 // multi-line, caret 6, selection 4..7
        ViewShim.scalaui_view_set_access_text(v, 1, info)
      }
      val e = C.msgL(send(v, "accessibilityChildren"), sel("objectAtIndex:"), 0L)
      assertEquals(fromNSString(send(e, "accessibilityRole")), "AXTextArea")
      assertEquals(long(e, "accessibilityNumberOfCharacters"), 11L)
      assertEquals(fromNSString(send(e, "accessibilitySelectedText")), "two")
      Zone {
        val out = alloc[CLongLong](2)
        ViewShim.scalaui_access_selection(e, out)
        assertEquals((out(0).toLong, out(1).toLong), (4L, 3L))
      }
      assertEquals(C.msgL(e, sel("accessibilityLineForIndex:"), 5L).toLong, 1L)
      assertEquals(fromNSString(ViewShim.scalaui_access_string_for_range(e, 0L, 3L)), "one")
      ViewShim.scalaui_access_set_selection(e, 1L, 2L)
      assertEquals(drain(v).map(e => (e._1, e._2, e._3, e._4)), Seq((10, 0.0, 1.0, 2.0)))
    }
  }

  test("native menu tree has an app menu, tagged items, shortcuts and routes actions through the view") {
    withView { v =>
      val bar = CocoaMenus.build(Seq(MenuEntry.Submenu("File", Seq(
        MenuEntry.Item("Save", 7, "mod+s"), MenuEntry.Separator, MenuEntry.Item("Export", 8, "mod+shift+e", enabled = false, checked = true)))))
      assertEquals(long(bar, "numberOfItems"), 2L) // application menu + File
      val file = send(C.msgL(bar, sel("itemAtIndex:"), 1L), "submenu")
      assertEquals(long(file, "numberOfItems"), 3L)
      val save = C.msgL(file, sel("itemAtIndex:"), 0L)
      assertEquals(fromNSString(send(save, "title")), "Save")
      assertEquals(fromNSString(send(save, "keyEquivalent")), "s")
      assertEquals(long(save, "keyEquivalentModifierMask"), 1L << 20)
      assertEquals(long(save, "tag"), 7L)
      val exportItem = C.msgL(file, sel("itemAtIndex:"), 2L)
      assertEquals(long(exportItem, "keyEquivalentModifierMask"), (1L << 20) | (1L << 17))
      assertEquals(long(exportItem, "isEnabled") & 0xff, 0L)
      assertEquals(long(exportItem, "state"), 1L)
      // an item's action reaches the view as a queued event
      send(v, "scaluiMenuAction:", save)
      assertEquals(drain(v).map(e => (e._1, e._2)), Seq((8, 7.0)))
    }
  }

  test("tray menus bind their items to the view") {
    withView { v =>
      val m = CocoaMenus.buildMenu("", Seq(MenuEntry.Item("Show", 5), MenuEntry.Separator, MenuEntry.Item("Quit", 6)), v)
      assertEquals(long(m, "numberOfItems"), 3L)
      val it = C.msgL(m, sel("itemAtIndex:"), 2L)
      assertEquals(send(it, "target"), v)
      send(v, "scaluiMenuAction:", it)
      assertEquals(drain(v).map(e => (e._1, e._2)), Seq((8, 6.0)))
    }
  }

  test("every frame-size change reaches the live-resize callback (the OS blocks the event loop while dragging)") {
    withView { v =>
      var calls = 0
      CocoaWindow.liveResize = () => calls += 1
      ViewShim.scalaui_set_resize_callback(CFuncPtr0.fromScalaFunction(CocoaWindow.liveResizeTrampoline))
      TestMsg.size(v, sel("setFrameSize:"), 300.0, 200.0)
      TestMsg.size(v, sel("setFrameSize:"), 320.0, 210.0)
      assertEquals(calls, 2)
      assertEquals(rect(v, "frame")._3, 320.0)
      ViewShim.scalaui_set_resize_callback(CFuncPtr0.fromScalaFunction(CocoaWindow.liveResizeTrampoline))
      CocoaWindow.liveResize = () => ()
    }
  }

  test("shortcut parsing") {
    assertEquals(Shortcut.parse("mod+shift+s", isMac = true), Some(Shortcut(true, false, false, true, "s")))
    assertEquals(Shortcut.parse("mod+s", isMac = false), Some(Shortcut(false, true, false, false, "s")))
    assertEquals(Shortcut.parse("", isMac = true), None)
  }


class PlatformSuite extends scalaui.testkit.LoggedSuite:
  test("print commands quote paths and choose the right tool") {
    assertEquals(Platform.printCommand("/tmp/a b.pdf", None, 1, windows = false), "lp -n 1 '/tmp/a b.pdf' >/dev/null 2>&1")
    assertEquals(Platform.printCommand("/tmp/it's.pdf", Some("Office"), 2, windows = false), "lp -d 'Office' -n 2 '/tmp/it'\\''s.pdf' >/dev/null 2>&1")
    assert(Platform.printCommand("C:\\a.pdf", None, 1, windows = true).contains("-Verb Print"))
    assert(Platform.printCommand("C:\\a.pdf", Some("HP"), 1, windows = true).contains("PrintTo"))
  }
  test("printers() lists without failing") {
    Platform.printers() // may be empty; must not crash
  }
