package scalaui.gpu

import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*
import scalaui.window.{Dl, DynLib, Frameworks, Native, ObjC}
import ObjC.*

/** Headless CGL (OpenGL) context plus an IOSurface-backed render target for libmpv's OpenGL renderer on macOS.
  *
  * Everything is resolved with dlopen/dlsym at run time (OpenGL.framework, IOSurface.framework), like the other Apple
  * frameworks, so nothing is linked. The color attachment of the FBO is a GL_TEXTURE_RECTANGLE bound to an IOSurface
  * (`CGLTexImageIOSurface2D`); the same IOSurface is imported into Metal ([[GpuBackend.importIOSurface]]), so a frame mpv
  * draws is visible to the UI compositor without any copy.
  */
private[gpu] final class MpvGl private (cglCtx: Ptr[Byte], pixelFormat: Ptr[Byte]):
  import MpvGl.*

  private var io: Ptr[Byte] = null
  private var glTex = 0
  private var fboId = 0
  private var tex: Texture = null
  private var curW = 0
  private var curH = 0

  def fbo: Int = fboId
  def texture: Texture = tex

  def makeCurrent(): Unit = Native.blocking(fSetCurrent(cglCtx))
  def finish(): Unit = Native.blocking(fFinish())

  /** Makes the target exactly `w` x `h` (recreating the IOSurface, GL texture, FBO and Metal texture when it differs).
    * The context must be current. Returns true when a new target was created. */
  def ensureSize(gpu: GpuBackend, w: Int, h: Int): Boolean =
    if tex != null && w == curW && h == curH then false
    else
      val newIo = createIOSurface(w, h)
      if newIo == null then throw new RuntimeException(s"IOSurfaceCreate failed for ${w}x$h")
      val imported = gpu.importIOSurface(newIo, w, h)
      if imported.isEmpty then { Frameworks.CFRelease(newIo); throw new RuntimeException("Metal could not import the IOSurface") }
      releaseTarget()
      io = newIo
      tex = imported.get
      val ids = stackalloc[CUnsignedInt](1)
      Native.blocking(fGenTextures(1, ids)); glTex = ids(0).toInt
      Native.blocking(fBindTexture(GL_TEXTURE_RECTANGLE, glTex))
      val err = Native.blocking(fTexImageIOSurface(cglCtx, GL_TEXTURE_RECTANGLE, GL_RGBA, w, h, GL_BGRA, GL_UNSIGNED_INT_8_8_8_8_REV, io, 0))
      if err != 0 then throw new RuntimeException(s"CGLTexImageIOSurface2D failed: $err")
      Native.blocking(fGenFramebuffers(1, ids)); fboId = ids(0).toInt
      Native.blocking(fBindFramebuffer(GL_FRAMEBUFFER, fboId))
      Native.blocking(fFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_RECTANGLE, glTex, 0))
      val status = Native.blocking(fCheckFramebuffer(GL_FRAMEBUFFER))
      if status != GL_FRAMEBUFFER_COMPLETE then throw new RuntimeException(s"framebuffer incomplete: 0x${status.toHexString}")
      curW = w; curH = h
      true

  private def releaseTarget(): Unit =
    if tex != null then
      val ids = stackalloc[CUnsignedInt](1)
      ids(0) = fboId.toUInt; Native.blocking(fDeleteFramebuffers(1, ids))
      ids(0) = glTex.toUInt; Native.blocking(fDeleteTextures(1, ids))
      tex.dispose()
      Frameworks.CFRelease(io)
      tex = null; io = null; fboId = 0; glTex = 0

  def destroy(): Unit =
    makeCurrent()
    releaseTarget()
    Native.blocking(fSetCurrent(null))
    Native.blocking(fDestroyContext(cglCtx))
    Native.blocking(fDestroyPixelFormat(pixelFormat))

private[gpu] object MpvGl:
  private val GL_TEXTURE_RECTANGLE = 0x84F5
  private val GL_FRAMEBUFFER = 0x8D40
  private val GL_COLOR_ATTACHMENT0 = 0x8CE0
  private val GL_FRAMEBUFFER_COMPLETE = 0x8CD5
  private val GL_RGBA = 0x1908
  private val GL_BGRA = 0x80E1
  private val GL_UNSIGNED_INT_8_8_8_8_REV = 0x8367
  private val BGRA_FOURCC = 0x42475241 // 'BGRA'

  private lazy val glLib: Ptr[Byte] = DynLib.open("/System/Library/Frameworks/OpenGL.framework/OpenGL")
  private lazy val ioLib: Ptr[Byte] = DynLib.open("/System/Library/Frameworks/IOSurface.framework/IOSurface")

  def supported: Boolean =
    if scala.scalanative.meta.LinktimeInfo.isMac then glLib != null && ioLib != null else false

  private def gl(name: String): Ptr[Byte] =
    val p = DynLib.symbol(glLib, name)
    if p == null then throw new RuntimeException(s"OpenGL is missing $name")
    p

  private lazy val fChoosePixelFormat = CFuncPtr.fromPtr[CFuncPtr3[Ptr[CInt], Ptr[Ptr[Byte]], Ptr[CInt], CInt]](gl("CGLChoosePixelFormat"))
  private lazy val fCreateContext = CFuncPtr.fromPtr[CFuncPtr3[Ptr[Byte], Ptr[Byte], Ptr[Ptr[Byte]], CInt]](gl("CGLCreateContext"))
  private lazy val fSetCurrent = CFuncPtr.fromPtr[CFuncPtr1[Ptr[Byte], CInt]](gl("CGLSetCurrentContext"))
  private lazy val fDestroyContext = CFuncPtr.fromPtr[CFuncPtr1[Ptr[Byte], Unit]](gl("CGLDestroyContext"))
  private lazy val fDestroyPixelFormat = CFuncPtr.fromPtr[CFuncPtr1[Ptr[Byte], Unit]](gl("CGLDestroyPixelFormat"))
  private lazy val fTexImageIOSurface = CFuncPtr.fromPtr[CFuncPtr9[Ptr[Byte], CInt, CInt, CInt, CInt, CInt, CInt, Ptr[Byte], CInt, CInt]](gl("CGLTexImageIOSurface2D"))
  private lazy val fGenTextures = CFuncPtr.fromPtr[CFuncPtr2[CInt, Ptr[CUnsignedInt], Unit]](gl("glGenTextures"))
  private lazy val fDeleteTextures = CFuncPtr.fromPtr[CFuncPtr2[CInt, Ptr[CUnsignedInt], Unit]](gl("glDeleteTextures"))
  private lazy val fBindTexture = CFuncPtr.fromPtr[CFuncPtr2[CInt, CInt, Unit]](gl("glBindTexture"))
  private lazy val fGenFramebuffers = CFuncPtr.fromPtr[CFuncPtr2[CInt, Ptr[CUnsignedInt], Unit]](gl("glGenFramebuffers"))
  private lazy val fDeleteFramebuffers = CFuncPtr.fromPtr[CFuncPtr2[CInt, Ptr[CUnsignedInt], Unit]](gl("glDeleteFramebuffers"))
  private lazy val fBindFramebuffer = CFuncPtr.fromPtr[CFuncPtr2[CInt, CInt, Unit]](gl("glBindFramebuffer"))
  private lazy val fFramebufferTexture2D = CFuncPtr.fromPtr[CFuncPtr5[CInt, CInt, CInt, CInt, CInt, Unit]](gl("glFramebufferTexture2D"))
  private lazy val fCheckFramebuffer = CFuncPtr.fromPtr[CFuncPtr1[CInt, CInt]](gl("glCheckFramebufferStatus"))
  private lazy val fFinish = CFuncPtr.fromPtr[CFuncPtr0[Unit]](gl("glFinish"))
  private lazy val fIOSurfaceCreate = CFuncPtr.fromPtr[CFuncPtr1[Ptr[Byte], Ptr[Byte]]](DynLib.symbol(ioLib, "IOSurfaceCreate"))

  private def procAddress(@annotation.unused ctx: Ptr[Byte], name: CString): Ptr[Byte] = Dl.dlsym(glLib, name)
  /** `get_proc_address` for `mpv_opengl_init_params`. */
  val getProcAddress: CFuncPtr2[Ptr[Byte], CString, Ptr[Byte]] = CFuncPtr2.fromScalaFunction(procAddress)

  private def createIOSurface(w: Int, h: Int): Ptr[Byte] =
    val dict = send(cls("NSMutableDictionary"), "dictionary")
    def put(key: String, value: Long): Unit =
      C_msgPP(dict, "setObject:forKey:", sendL(cls("NSNumber"), "numberWithLongLong:", value), nsString(key))
    put("IOSurfaceWidth", w); put("IOSurfaceHeight", h); put("IOSurfaceBytesPerElement", 4); put("IOSurfacePixelFormat", BGRA_FOURCC)
    Native.blocking(fIOSurfaceCreate(dict))

  private def C_msgPP(o: Ptr[Byte], s: String, a: Ptr[Byte], b: Ptr[Byte]): Unit = scalaui.window.C.msgPP(o, sel(s), a, b)

  def create(): MpvGl =
    val attrs = stackalloc[CInt](8)
    attrs(0) = 99; attrs(1) = 0x4100 // kCGLPFAOpenGLProfile = OpenGL 4.1 core
    attrs(2) = 73 // kCGLPFAAccelerated
    attrs(3) = 8; attrs(4) = 24 // kCGLPFAColorSize
    attrs(5) = 0
    val pix = stackalloc[Ptr[Byte]]()
    val n = stackalloc[CInt]()
    var err = Native.blocking(fChoosePixelFormat(attrs, pix, n))
    if err != 0 || !pix == null then throw new RuntimeException(s"CGLChoosePixelFormat failed: $err")
    val ctx = stackalloc[Ptr[Byte]]()
    err = Native.blocking(fCreateContext(!pix, null, ctx))
    if err != 0 || !ctx == null then
      Native.blocking(fDestroyPixelFormat(!pix))
      throw new RuntimeException(s"CGLCreateContext failed: $err")
    new MpvGl(!ctx, !pix)
