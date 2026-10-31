package scalaui.clipboard

import scalaui.window.{C, ObjC, PlatformClipboard}
import scala.scalanative.meta.LinktimeInfo
import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*
import ObjC.*

/** Win32 clipboard bindings. */
@link("user32") @link("kernel32") @extern private object Win:
  def OpenClipboard(owner: Ptr[Byte]): CInt = extern
  def CloseClipboard(): CInt = extern
  def EmptyClipboard(): CInt = extern
  def GetClipboardData(format: CUnsignedInt): Ptr[Byte] = extern
  def SetClipboardData(format: CUnsignedInt, mem: Ptr[Byte]): Ptr[Byte] = extern
  def GlobalAlloc(flags: CUnsignedInt, bytes: CUnsignedLongLong): Ptr[Byte] = extern
  def GlobalLock(mem: Ptr[Byte]): Ptr[Byte] = extern
  def GlobalUnlock(mem: Ptr[Byte]): CInt = extern
  def GlobalSize(mem: Ptr[Byte]): CUnsignedLongLong = extern
  def RegisterClipboardFormatW(name: Ptr[CShort]): CUnsignedInt = extern

/** System clipboard (arboard equivalent): text and PNG images. macOS, Windows and X11. */
object Clipboard:
  def getText(): Option[String] =
    if LinktimeInfo.isMac then
      val pool = send(send(cls("NSAutoreleasePool"), "alloc"), "init")
      val pb = send(cls("NSPasteboard"), "generalPasteboard")
      val s = send(pb, "stringForType:", nsString("public.utf8-plain-text"))
      val res = if s == null then None else Some(fromNSString(s))
      send(pool, "drain")
      res
    else if LinktimeInfo.isWindows then
      if Win.OpenClipboard(null) == 0 then None
      else
        try
          val h = Win.GetClipboardData(13.toUInt) // CF_UNICODETEXT
          if h == null then None
          else
            val p = Win.GlobalLock(h).asInstanceOf[Ptr[CShort]]
            if p == null then None
            else
              val sb = new StringBuilder; var i = 0
              while p(i) != 0 do { sb += p(i).toChar; i += 1 }
              Win.GlobalUnlock(h)
              Some(sb.toString.replace("\r\n", "\n"))
        finally Win.CloseClipboard()
    else PlatformClipboard.get()

  def setText(text: String): Unit =
    if LinktimeInfo.isMac then
      val pool = send(send(cls("NSAutoreleasePool"), "alloc"), "init")
      val pb = send(cls("NSPasteboard"), "generalPasteboard")
      send(pb, "clearContents")
      C.msgPP(pb, sel("setString:forType:"), nsString(text), nsString("public.utf8-plain-text"))
      send(pool, "drain")
    else if LinktimeInfo.isWindows then
      if Win.OpenClipboard(null) != 0 then
        try
          Win.EmptyClipboard()
          val s = text.replace("\n", "\r\n")
          val mem = Win.GlobalAlloc(0x0002.toUInt, ((s.length + 1) * 2).toULong) // GMEM_MOVEABLE
          val p = Win.GlobalLock(mem).asInstanceOf[Ptr[CShort]]
          var i = 0
          while i < s.length do { p(i) = s.charAt(i).toShort; i += 1 }
          p(s.length) = 0.toShort
          Win.GlobalUnlock(mem)
          Win.SetClipboardData(13.toUInt, mem)
        finally Win.CloseClipboard()
    else PlatformClipboard.set(text)

  // --- images (PNG bytes; decode with `scalaui.codecs`) ---

  /** The clipboard's image as PNG-encoded bytes, if it holds one. */
  def getImagePng(): Option[Array[Byte]] =
    if LinktimeInfo.isMac then
      val pool = send(send(cls("NSAutoreleasePool"), "alloc"), "init")
      val res = macGetData(send(cls("NSPasteboard"), "generalPasteboard"), "public.png")
      send(pool, "drain")
      res
    else if LinktimeInfo.isWindows then
      if Win.OpenClipboard(null) == 0 then None
      else
        try
          val fmt = Zone { Win.RegisterClipboardFormatW(scalaui.window.Win32Window.wideString("PNG")) }
          val h = Win.GetClipboardData(fmt)
          if h == null then None
          else
            val size = Win.GlobalSize(h).toInt
            val p = Win.GlobalLock(h)
            if p == null then None
            else
              val out = new Array[Byte](size)
              var i = 0; while i < size do { out(i) = p(i); i += 1 }
              Win.GlobalUnlock(h)
              Some(out)
        finally Win.CloseClipboard()
    else PlatformClipboard.getData("image/png")

  /** Replaces the clipboard contents with a PNG image. */
  def setImagePng(png: Array[Byte]): Unit =
    if LinktimeInfo.isMac then
      val pool = send(send(cls("NSAutoreleasePool"), "alloc"), "init")
      val pb = send(cls("NSPasteboard"), "generalPasteboard")
      send(pb, "clearContents")
      macSetData(pb, "public.png", png)
      send(pool, "drain")
    else if LinktimeInfo.isWindows then
      if Win.OpenClipboard(null) != 0 then
        try
          Win.EmptyClipboard()
          val fmt = Zone { Win.RegisterClipboardFormatW(scalaui.window.Win32Window.wideString("PNG")) }
          val mem = Win.GlobalAlloc(0x0002.toUInt, png.length.toULong)
          val p = Win.GlobalLock(mem)
          var i = 0; while i < png.length do { p(i) = png(i); i += 1 }
          Win.GlobalUnlock(mem)
          Win.SetClipboardData(fmt, mem)
        finally Win.CloseClipboard()
    else PlatformClipboard.setData(Map("image/png" -> png))

  private[clipboard] def macGetData(pb: Ptr[Byte], uti: String): Option[Array[Byte]] =
    val data = send(pb, "dataForType:", nsString(uti))
    val n = if data == null then 0 else long(data, "length").toInt
    if n == 0 then None
    else
      val bytes = send(data, "bytes")
      val out = new Array[Byte](n)
      var i = 0; while i < n do { out(i) = bytes(i); i += 1 }
      Some(out)

  private[clipboard] def macSetData(pb: Ptr[Byte], uti: String, bytes: Array[Byte]): Unit =
    val data = C.msgPL(cls("NSData"), sel("dataWithBytes:length:"), if bytes.isEmpty then null else bytes.atUnsafe(0), bytes.length.toLong)
    C.msgPP(pb, sel("setData:forType:"), data, nsString(uti))

