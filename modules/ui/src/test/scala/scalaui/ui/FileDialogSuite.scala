package scalaui.ui

import java.nio.file.{Files, Paths}
import scalaui.geom.*

class FileDialogSuite extends scalaui.testkit.LoggedSuite:
  private def fixture(): String =
    val root = Paths.get("target/fd-fixture"); Files.createDirectories(root.resolve("sub"))
    Files.write(root.resolve("b.png"), Array[Byte](1)); Files.write(root.resolve("A.txt"), Array[Byte](1, 2)); Files.write(root.resolve(".hidden"), Array[Byte](1))
    Files.write(root.resolve("sub").resolve("c.jpg"), Array[Byte](1))
    root.toAbsolutePath.toString

  test("listing: folders first, case-insensitive sort, hidden skipped, extension filter") {
    val d = fixture()
    assertEquals(FileDialog.list(d, Nil).map(_.name), Vector("sub", "A.txt", "b.png"))
    assertEquals(FileDialog.list(d, Seq("PNG")).map(_.name), Vector("sub", "b.png"))
    assertEquals(FileDialog.list(d, Nil, foldersOnly = true).map(_.name), Vector("sub"))
    assertEquals(FileDialog.list("/definitely/not/here", Nil), Vector.empty)
  }

  test("dialog opens, picks a file by double click, returns its path") {
    val d = fixture()
    val h = new Harness
    val dlg = new FileDialog("Open", d, FileDialogMode.OpenFile)
    var picked: Option[String] = None
    def frame(): Unit = h.frame { ui => dlg.update(ui).foreach(p => picked = Some(p)) }
    frame(); assert(!dlg.isShown)
    dlg.show(); frame(); frame(); frame()
    assert(dlg.isShown)
    def clickRow(name: String, times: Int): Unit =
      val p = dlg.rowRects.find(_._1 == name).get._2.center
      h.move(p); frame()
      for _ <- 0 until times do { h.press(p); frame(); h.release(p); frame() }
      frame() // result surfaces one frame later
    clickRow("b.png", 2)
    assertEquals(picked.map(Paths.get(_).getFileName.toString), Some("b.png"))
    assert(!dlg.isShown)
  }
