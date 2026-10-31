package scalaui.window

import scala.scalanative.unsafe.*
import scala.collection.mutable

type Id = Ptr[Byte]

/** Pointer-based wrappers around struct-by-value calls, implemented in `resources/scala-native/scalaui_objc.c`. */
@link("objc") @extern private[scalaui] object Shim:
  def scalaui_ret_rect(self: Ptr[Byte], sel: Ptr[Byte], out: Ptr[CDouble]): Unit = extern
  def scalaui_ret_point(self: Ptr[Byte], sel: Ptr[Byte], out: Ptr[CDouble]): Unit = extern
  def scalaui_init_window(self: Ptr[Byte], sel: Ptr[Byte], rect: Ptr[CDouble], style: CUnsignedLongLong, backing: CLongLong, defer: CChar): Ptr[Byte] = extern
  def scalaui_set_drawable_size(self: Ptr[Byte], sel: Ptr[Byte], w: CDouble, h: CDouble): Unit = extern
  def scalaui_set_clear_color(self: Ptr[Byte], sel: Ptr[Byte], r: CDouble, g: CDouble, b: CDouble, a: CDouble): Unit = extern
  def scalaui_set_scissor(self: Ptr[Byte], sel: Ptr[Byte], x: CUnsignedLongLong, y: CUnsignedLongLong, w: CUnsignedLongLong, h: CUnsignedLongLong): Unit = extern
  def scalaui_replace_region(self: Ptr[Byte], sel: Ptr[Byte], x: CUnsignedLongLong, y: CUnsignedLongLong, w: CUnsignedLongLong, h: CUnsignedLongLong, bytes: Ptr[Byte], bytesPerRow: CUnsignedLongLong): Unit = extern
  def scalaui_get_bytes(self: Ptr[Byte], sel: Ptr[Byte], bytes: Ptr[Byte], bytesPerRow: CUnsignedLongLong, w: CUnsignedLongLong, h: CUnsignedLongLong): Unit = extern
  def scalaui_set_viewport(self: Ptr[Byte], sel: Ptr[Byte], w: CDouble, h: CDouble): Unit = extern

/** ScaluiView and window helpers from the C shim. */
@link("objc") @extern private[scalaui] object ViewShim:
  /** Mirrors `ScaluiViewEvent` in the shim: type, 3 doubles, malloc'ed UTF-8 text. */
  type ViewEvent = CStruct5[CInt, CDouble, CDouble, CDouble, CString]
  def scalaui_view_create(w: CDouble, h: CDouble): Ptr[Byte] = extern
  def scalaui_view_poll(view: Ptr[Byte], out: Ptr[ViewEvent]): CInt = extern
  def scalaui_free(p: Ptr[Byte]): Unit = extern
  def scalaui_view_set_ime_rect(view: Ptr[Byte], x: CDouble, y: CDouble, w: CDouble, h: CDouble): Unit = extern
  def scalaui_view_set_cursor(view: Ptr[Byte], cursor: Ptr[Byte]): Unit = extern
  def scalaui_window_set_min_size(win: Ptr[Byte], w: CDouble, h: CDouble): Unit = extern
  def scalaui_window_set_origin(win: Ptr[Byte], x: CDouble, y: CDouble): Unit = extern
  def scalaui_window_origin(win: Ptr[Byte], out: Ptr[CDouble]): Unit = extern
  def scalaui_view_set_access(view: Ptr[Byte], n: CInt, roles: Ptr[CString], labels: Ptr[CString], values: Ptr[CString], bounds: Ptr[CDouble], flags: Ptr[CInt]): Unit = extern
  def scalaui_view_set_access_text(view: Ptr[Byte], n: CInt, info: Ptr[CInt]): Unit = extern
  def scalaui_access_selection(e: Ptr[Byte], out: Ptr[CLongLong]): Unit = extern
  def scalaui_access_set_selection(e: Ptr[Byte], loc: CLongLong, len: CLongLong): Unit = extern
  def scalaui_access_string_for_range(e: Ptr[Byte], loc: CLongLong, len: CLongLong): Ptr[Byte] = extern
  def scalaui_view_post_text(view: Ptr[Byte], index: CInt, kind: CInt): Unit = extern
  def scalaui_set_resize_callback(cb: CFuncPtr0[Unit]): Unit = extern
  def scalaui_nsimage(cgImage: Ptr[Byte], w: CDouble, h: CDouble): Ptr[Byte] = extern
  def scalaui_set_app_icon(cgImage: Ptr[Byte], w: CDouble, h: CDouble): Unit = extern

/** Objective-C runtime plus the handful of CoreGraphics/CoreFoundation C functions we need. All Apple system libraries. */
@link("objc") @extern private[scalaui] object C:
  def objc_getClass(name: CString): Id = extern
  def sel_registerName(name: CString): Id = extern

  @name("objc_msgSend") def msg(self: Id, sel: Id): Id = extern
  @name("objc_msgSend") def msgP(self: Id, sel: Id, a: Id): Id = extern
  @name("objc_msgSend") def msgL(self: Id, sel: Id, a: CLongLong): Id = extern
  @name("objc_msgSend") def msgPL(self: Id, sel: Id, a: Id, b: CLongLong): Id = extern
  @name("objc_msgSend") def msgPLL(self: Id, sel: Id, a: Id, b: CLongLong, c: CLongLong): Id = extern
  @name("objc_msgSend") def msgLLLC(self: Id, sel: Id, a: CLongLong, b: CLongLong, c: CLongLong, d: CChar): Id = extern
  @name("objc_msgSend") def msgLLL(self: Id, sel: Id, a: CLongLong, b: CLongLong, c: CLongLong): Id = extern
  @name("objc_msgSend") def msgPPP(self: Id, sel: Id, a: Id, b: Id, c: Id): Id = extern
  @name("objc_msgSend") def msgPPPP(self: Id, sel: Id, a: Id, b: Id, c: Id, d: Id): Id = extern
  @name("objc_msgSend") def msgPP(self: Id, sel: Id, a: Id, b: Id): Id = extern
  @name("objc_msgSend") def msgD(self: Id, sel: Id, a: CDouble): Id = extern
  @name("objc_msgSend") def msgEvent(self: Id, sel: Id, mask: CUnsignedLongLong, date: Id, mode: Id, dequeue: CChar): Id = extern
  @name("objc_msgSend") def retLong(self: Id, sel: Id): CLongLong = extern
  @name("objc_msgSend") def retDouble(self: Id, sel: Id): CDouble = extern
  @name("objc_msgSend") def retCStr(self: Id, sel: Id): CString = extern



@extern private[scalaui] object Dl:
  def dlopen(path: CString, mode: CInt): Ptr[Byte] = extern
  def dlsym(handle: Ptr[Byte], name: CString): Ptr[Byte] = extern

/** Apple system frameworks are loaded with dlopen and their C functions looked up with dlsym, so binaries need no
  * `-framework` linker flags (so no build-tool configuration is needed).
  */
private[scalaui] object Frameworks:
  private var loaded = false
  def load(): Unit =
    if !loaded then
      loaded = true
      List("AppKit", "QuartzCore", "Metal", "CoreGraphics", "CoreFoundation").foreach { f =>
        Zone { Dl.dlopen(toCString(s"/System/Library/Frameworks/$f.framework/$f"), 1) } // RTLD_LAZY
      }
  private def sym(name: String): Ptr[Byte] =
    load()
    val p = Zone { Dl.dlsym((-2L).toPtr[Byte], toCString(name)) } // RTLD_DEFAULT
    if p == null then throw new RuntimeException(s"missing system symbol $name")
    p
  private lazy val fColorSpace = CFuncPtr.fromPtr[CFuncPtr0[Id]](sym("CGColorSpaceCreateDeviceRGB"))
  private lazy val fCFData = CFuncPtr.fromPtr[CFuncPtr3[Id, Ptr[Byte], CLongLong, Id]](sym("CFDataCreate"))
  private lazy val fProvider = CFuncPtr.fromPtr[CFuncPtr1[Id, Id]](sym("CGDataProviderCreateWithCFData"))
  private lazy val fProviderData = CFuncPtr.fromPtr[CFuncPtr4[Ptr[Byte], Ptr[Byte], CSize, Ptr[Byte], Id]](sym("CGDataProviderCreateWithData"))
  private lazy val fImage = CFuncPtr.fromPtr[CFuncPtr11[CSize, CSize, CSize, CSize, CSize, Id, CUnsignedInt, Id, Ptr[CDouble], CChar, CInt, Id]](sym("CGImageCreate"))
  private lazy val fRelease = CFuncPtr.fromPtr[CFuncPtr1[Id, Unit]](sym("CFRelease"))
  private lazy val fDevice = CFuncPtr.fromPtr[CFuncPtr0[Id]](sym("MTLCreateSystemDefaultDevice"))
  def CGColorSpaceCreateDeviceRGB(): Id = fColorSpace()
  def CFDataCreate(alloc: Id, bytes: Ptr[Byte], length: CLongLong): Id = fCFData(alloc, bytes, length)
  def CGDataProviderCreateWithCFData(data: Id): Id = fProvider(data)
  /** A provider over caller-owned memory (no copy, no release callback): the memory must outlive every image made from it. */
  def CGDataProviderCreateWithData(data: Ptr[Byte], size: CSize): Id = fProviderData(null, data, size, null)
  def CGImageCreate(w: CSize, h: CSize, bpc: CSize, bpp: CSize, bpr: CSize, space: Id, info: CUnsignedInt, provider: Id,
      decode: Ptr[CDouble], interpolate: CChar, intent: CInt): Id = fImage(w, h, bpc, bpp, bpr, space, info, provider, decode, interpolate, intent)
  def CFRelease(o: Id): Unit = fRelease(o)
  def MTLCreateSystemDefaultDevice(): Id = fDevice()

/** Thin, string-based Objective-C messaging helpers. */
private[scalaui] object ObjC:
  private val selCache = mutable.HashMap.empty[String, Id]
  private val clsCache = mutable.HashMap.empty[String, Id]

  def sel(name: String): Id = selCache.getOrElseUpdate(name, Zone { C.sel_registerName(toCString(name)) })
  def cls(name: String): Id = clsCache.getOrElseUpdate(name, { Frameworks.load(); Zone { C.objc_getClass(toCString(name)) } })

  def send(o: Id, s: String): Id = C.msg(o, sel(s))
  def send(o: Id, s: String, a: Id): Id = C.msgP(o, sel(s), a)
  def sendL(o: Id, s: String, a: Long): Id = C.msgL(o, sel(s), a)
  def sendD(o: Id, s: String, a: Double): Id = C.msgD(o, sel(s), a)
  def bool(o: Id, s: String, v: Boolean): Id = C.msgL(o, sel(s), if v then 1L else 0L)
  def long(o: Id, s: String): Long = C.retLong(o, sel(s))
  def double(o: Id, s: String): Double = C.retDouble(o, sel(s))
  /** `(x, y)` of a zero-arg method returning NSPoint. */
  def point(o: Id, s: String): (Double, Double) =
    val b = stackalloc[CDouble](2)
    Shim.scalaui_ret_point(o, sel(s), b)
    (b(0), b(1))
  /** `(x, y, width, height)` of a zero-arg method returning NSRect. */
  def rect(o: Id, s: String): (Double, Double, Double, Double) =
    val b = stackalloc[CDouble](4)
    Shim.scalaui_ret_rect(o, sel(s), b)
    (b(0), b(1), b(2), b(3))

  def isNull(p: Id): Boolean = p == null

  def nsString(s: String): Id = Zone { C.msgP(cls("NSString"), sel("stringWithUTF8String:"), toCString(s)) }
  def fromNSString(s: Id): String = if s == null then "" else fromCString(C.retCStr(s, sel("UTF8String")))
