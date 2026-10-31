package scalaui.dialogs

import scalaui.window.{C, ObjC, Id}
import scala.scalanative.meta.LinktimeInfo
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*
import ObjC.*

@link("user32") @link("comdlg32") @link("shell32") @extern private object Win:
  def MessageBoxW(owner: Ptr[Byte], text: Ptr[CShort], caption: Ptr[CShort], flags: CUnsignedInt): CInt = extern
  def GetOpenFileNameW(ofn: Ptr[Byte]): CInt = extern
  def GetSaveFileNameW(ofn: Ptr[Byte]): CInt = extern
  def SHBrowseForFolderW(bi: Ptr[Byte]): Ptr[Byte] = extern
  def SHGetPathFromIDListW(pidl: Ptr[Byte], path: Ptr[CShort]): CInt = extern

/** Native file and message dialogs (rfd equivalent). All calls block until the dialog closes and must run on the main thread. */
object Dialogs:
  /** Shows a file-open panel; returns the chosen path. `extensions` like `Seq("png", "jpg")` filters (empty = any). */
  def openFile(title: String = "Open", extensions: Seq[String] = Nil): Option[String] =
    if LinktimeInfo.isWindows then winFile(title, extensions, save = false, "")
    else if !LinktimeInfo.isMac then None
    else panel("NSOpenPanel", title, extensions) { p =>
      bool(p, "setCanChooseFiles:", true); bool(p, "setCanChooseDirectories:", false)
    }

  def openDirectory(title: String = "Choose folder"): Option[String] =
    if LinktimeInfo.isWindows then winFolder(title)
    else if !LinktimeInfo.isMac then None
    else panel("NSOpenPanel", title, Nil) { p =>
      bool(p, "setCanChooseFiles:", false); bool(p, "setCanChooseDirectories:", true)
    }

  def saveFile(title: String = "Save", suggestedName: String = ""): Option[String] =
    if LinktimeInfo.isWindows then winFile(title, Nil, save = true, suggestedName)
    else if !LinktimeInfo.isMac then None
    else panel("NSSavePanel", title, Nil) { p =>
      if suggestedName.nonEmpty then send(p, "setNameFieldStringValue:", nsString(suggestedName))
    }

  /** Shows an alert with `message` and an OK button. */
  def message(title: String, message: String): Unit =
    if LinktimeInfo.isWindows then Zone { Win.MessageBoxW(null, wide(message), wide(title), 0x40.toUInt) }
    else if LinktimeInfo.isMac then
      val pool = pool0()
      val a = send(send(cls("NSAlert"), "alloc"), "init")
      send(a, "setMessageText:", nsString(title)); send(a, "setInformativeText:", nsString(message))
      send(a, "addButtonWithTitle:", nsString("OK"))
      send(a, "runModal")
      send(pool, "drain")

  /** Shows a Yes/No question; true for Yes. */
  def confirm(title: String, message: String): Boolean =
    if LinktimeInfo.isWindows then Zone { Win.MessageBoxW(null, wide(message), wide(title), 0x24.toUInt) == 6 } // MB_YESNO | MB_ICONQUESTION -> IDYES
    else if LinktimeInfo.isMac then
      val pool = pool0()
      val a = send(send(cls("NSAlert"), "alloc"), "init")
      send(a, "setMessageText:", nsString(title)); send(a, "setInformativeText:", nsString(message))
      send(a, "addButtonWithTitle:", nsString("Yes")); send(a, "addButtonWithTitle:", nsString("No"))
      val r = long(a, "runModal")
      send(pool, "drain")
      r == 1000 // NSAlertFirstButtonReturn
    else false

  private def pool0(): Id = send(send(cls("NSAutoreleasePool"), "alloc"), "init")

  private def panel(clazz: String, title: String, extensions: Seq[String])(configure: Id => Unit): Option[String] =
    val pool = pool0()
    val p = send(cls(clazz), if clazz == "NSOpenPanel" then "openPanel" else "savePanel")
    send(p, "setMessage:", nsString(title))
    if extensions.nonEmpty then
      val arr = send(send(cls("NSMutableArray"), "alloc"), "init")
      extensions.foreach(e => send(arr, "addObject:", nsString(e)))
      send(p, "setAllowedFileTypes:", arr)
    configure(p)
    val result =
      if long(p, "runModal") == 1 then // NSModalResponseOK
        val url = send(p, "URL")
        if url == null then None else Some(fromNSString(send(url, "path")))
      else None
    send(pool, "drain")
    result

  private def wide(s: String)(using Zone): Ptr[CShort] =
    val p = alloc[CShort](s.length + 1)
    var i = 0
    while i < s.length do { p(i) = s.charAt(i).toShort; i += 1 }
    p(s.length) = 0.toShort
    p

  private def fromWide(p: Ptr[CShort]): String =
    val sb = new StringBuilder; var i = 0
    while p(i) != 0 do { sb += p(i).toChar; i += 1 }
    sb.toString

  private def winFile(title: String, extensions: Seq[String], save: Boolean, suggested: String): Option[String] = Zone {
    val ofn = alloc[Byte](152)
    var i = 0; while i < 152 do { ofn(i) = 0.toByte; i += 1 }
    val maxFile = 1024
    val file = alloc[CShort](maxFile)
    var k = 0; while k < maxFile do { file(k) = 0.toShort; k += 1 }
    suggested.zipWithIndex.foreach((c, j) => file(j) = c.toShort)
    (ofn.asInstanceOf[Ptr[CUnsignedInt]])(0) = 152.toUInt
    if extensions.nonEmpty then
      val spec = extensions.map(e => s"*.$e").mkString(";")
      val filter = s"Supported files\u0000$spec\u0000All files\u0000*.*\u0000"
      val f = alloc[CShort](filter.length + 1)
      filter.zipWithIndex.foreach((c, j) => f(j) = c.toShort); f(filter.length) = 0.toShort
      (ofn + 24).asInstanceOf[Ptr[Ptr[CShort]]](0) = f
    (ofn + 48).asInstanceOf[Ptr[Ptr[CShort]]](0) = file
    (ofn + 56).asInstanceOf[Ptr[CUnsignedInt]](0) = maxFile.toUInt
    (ofn + 88).asInstanceOf[Ptr[Ptr[CShort]]](0) = wide(title)
    (ofn + 96).asInstanceOf[Ptr[CUnsignedInt]](0) = (0x80000 | 0x800 | (if save then 0x2 else 0x1000)).toUInt
    val ok = if save then Win.GetSaveFileNameW(ofn) else Win.GetOpenFileNameW(ofn)
    if ok != 0 then Some(fromWide(file)) else None
  }

  private def winFolder(title: String): Option[String] = Zone {
    val bi = alloc[Byte](64)
    var i = 0; while i < 64 do { bi(i) = 0.toByte; i += 1 }
    (bi + 24).asInstanceOf[Ptr[Ptr[CShort]]](0) = wide(title) // lpszTitle
    (bi + 32).asInstanceOf[Ptr[CUnsignedInt]](0) = 0x41.toUInt // BIF_RETURNONLYFSDIRS | BIF_NEWDIALOGSTYLE
    val pidl = Win.SHBrowseForFolderW(bi)
    if pidl == null then None
    else
      val path = alloc[CShort](1024)
      if Win.SHGetPathFromIDListW(pidl, path) != 0 then Some(fromWide(path)) else None
  }
