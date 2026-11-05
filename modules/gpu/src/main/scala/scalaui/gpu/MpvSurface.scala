package scalaui.gpu

import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*
import scala.scalanative.libc.errno.errno
import scalaui.window.DynLib

/** The few libc calls needed to feed libmpv a live byte stream through a pipe. */
@extern private object MpvLibc:
  def pipe(fds: Ptr[CInt]): CInt = extern
  def write(fd: CInt, buf: Ptr[Byte], n: CSize): CSSize = extern
  def close(fd: CInt): CInt = extern
  def fcntl(fd: CInt, cmd: CInt, args: CVarArg*): CInt = extern

/** libmpv's render API, resolved at run time: `libmpv` is a Homebrew package that may not be installed, so it is never
  * linked. A build without it still links; [[MpvSurface.create]] then fails with a readable message.
  */
private[gpu] object Mpv:
  /** `mpv_render_param`: a type tag followed by a pointer whose meaning depends on the tag. */
  type RenderParam = CStruct2[CInt, Ptr[Byte]]

  val ParamApiType = 1
  val ParamSwSize = 17
  val ParamSwFormat = 18
  val ParamSwStride = 19
  val ParamSwPointer = 20

  private val candidates = List(
    "libmpv.2.dylib", "libmpv.dylib", // the loader's own search path (DYLD_LIBRARY_PATH, rpaths)
    "/opt/homebrew/lib/libmpv.dylib", "/opt/homebrew/lib/libmpv.2.dylib", // Homebrew on Apple silicon
    "/usr/local/lib/libmpv.dylib", "/usr/local/lib/libmpv.2.dylib" // Homebrew on Intel
  )

  private lazy val lib: Ptr[Byte] = candidates.iterator.map(DynLib.open).find(_ != null).getOrElse(null)

  def available: Boolean = lib != null

  private def sym(name: String): Ptr[Byte] =
    val p = DynLib.symbol(lib, name)
    if p == null then throw new RuntimeException(s"libmpv is missing symbol $name")
    p

  private lazy val fCreate = CFuncPtr.fromPtr[CFuncPtr0[Ptr[Byte]]](sym("mpv_create"))
  private lazy val fInitialize = CFuncPtr.fromPtr[CFuncPtr1[Ptr[Byte], CInt]](sym("mpv_initialize"))
  private lazy val fSetOption = CFuncPtr.fromPtr[CFuncPtr3[Ptr[Byte], CString, CString, CInt]](sym("mpv_set_option_string"))
  private lazy val fCommand = CFuncPtr.fromPtr[CFuncPtr2[Ptr[Byte], Ptr[CString], CInt]](sym("mpv_command"))
  private lazy val fErrorString = CFuncPtr.fromPtr[CFuncPtr1[CInt, CString]](sym("mpv_error_string"))
  private lazy val fDestroy = CFuncPtr.fromPtr[CFuncPtr1[Ptr[Byte], Unit]](sym("mpv_terminate_destroy"))
  private lazy val fRenderCreate = CFuncPtr.fromPtr[CFuncPtr3[Ptr[Ptr[Byte]], Ptr[Byte], Ptr[RenderParam], CInt]](sym("mpv_render_context_create"))
  private lazy val fRender = CFuncPtr.fromPtr[CFuncPtr2[Ptr[Byte], Ptr[RenderParam], CInt]](sym("mpv_render_context_render"))
  private lazy val fRenderFree = CFuncPtr.fromPtr[CFuncPtr1[Ptr[Byte], Unit]](sym("mpv_render_context_free"))

  def mpv_create(): Ptr[Byte] = fCreate()
  def mpv_initialize(h: Ptr[Byte]): Int = fInitialize(h)
  def mpv_set_option_string(h: Ptr[Byte], name: CString, value: CString): Int = fSetOption(h, name, value)
  /** `args` is a null-terminated array of C strings. */
  def mpv_command(h: Ptr[Byte], args: Ptr[CString]): Int = fCommand(h, args)
  def mpv_terminate_destroy(h: Ptr[Byte]): Unit = fDestroy(h)
  def mpv_render_context_create(out: Ptr[Ptr[Byte]], h: Ptr[Byte], params: Ptr[RenderParam]): Int = fRenderCreate(out, h, params)
  def mpv_render_context_render(ctx: Ptr[Byte], params: Ptr[RenderParam]): Int = fRender(ctx, params)
  def mpv_render_context_free(ctx: Ptr[Byte]): Unit = fRenderFree(ctx)
  def errorString(code: Int): String = fromCString(fErrorString(code))

/** An in-process libmpv player that renders into CPU memory and uploads each frame into a scalaui [[Texture]], so video
  * composites with the rest of the immediate-mode UI like any other image (`canvas.drawImage(texture, rect)`).
  *
  * Typical use from a paint loop:
  * {{{
  * val surface = MpvSurface.create(640, 360).toOption.get
  * val texture = gpu.createTexture(640, 360, null)
  * // feeder thread: surface.pushBytes(chunk) for every chunk of the stream
  * // each frame:
  * if surface.renderInto(texture) then ()
  * canvas.drawImage(texture, Rect(20, 20, 660, 380))
  * }}}
  *
  * Video bytes are pushed through a `pipe(2)` that mpv reads as `fd://<readfd>` (see [[MpvSurface.create]]). Rendering uses
  * libmpv's software renderer (`MPV_RENDER_API_TYPE_SW`): mpv itself scales and color-converts on the CPU single-threaded,
  * which is fine for modest sizes and is the known performance limit of this implementation.
  */
trait MpvSurface:
  /** Width of the rendered frame in pixels (fixed at creation). */
  def width: Int
  /** Height of the rendered frame in pixels (fixed at creation). */
  def height: Int
  /** Offers `length` bytes of `data` (from `offset`) to the player without ever blocking: the pipe holds about 64 KiB, so
    * the return value is how many bytes were accepted, which may be fewer than `length` or 0 while mpv catches up. The
    * caller keeps the rest and retries later (for example on the next frame). -1 means the surface is closed or the pipe
    * is broken. Never blocking matters because Scala Native threads are off by default on macOS, so a feeder "thread"
    * would run on the caller.
    */
  def pushBytes(data: Array[Byte], offset: Int, length: Int): Int
  /** [[pushBytes(data:Array[Byte],offset:Int,length:Int)*]] for a whole array. */
  def pushBytes(data: Array[Byte]): Int = pushBytes(data, 0, data.length)
  /** Signals end of stream: the player drains what it has and finishes. */
  def endOfStream(): Unit
  /** Renders the current video frame into `target` (premultiplied RGBA8; video is opaque so alpha is 255). `target` must be
    * at least `width` x `height`. Returns false when no frame could be rendered yet (nothing decoded so far, or closed), in
    * which case `target` is untouched. Call it from the paint loop; it never blocks on the network.
    */
  def renderInto(target: Texture): Boolean
  /** Same as [[renderInto]] but into a caller-owned tightly packed RGBA8 buffer of at least `width * height * 4` bytes. */
  def renderPixels(rgba: Array[Byte]): Boolean
  /** Stops playback and releases the render context, the player and the pipe. Idempotent. */
  def close(): Unit

object MpvSurface:
  /** True when `libmpv` can be loaded on this machine (on macOS: `brew install mpv`). */
  def available: Boolean = Mpv.available

  /** Creates a player whose frames are `width` x `height` pixels and starts it reading from a fresh pipe.
    *
    * The stream goes through `pipe(2)` + `fd://` rather than `mpv_stream_cb_add_ro`: the pipe is mpv's own stdin-style
    * demuxing path (what `mpv -` does), needs no C callbacks into Scala from mpv's demuxer thread, and the kernel pipe gives
    * back-pressure for free. The cost is that the stream is not seekable, which a live MPEG-TS feed isn't anyway.
    *
    * Left carries a human-readable reason (libmpv missing, or mpv's own error string).
    */
  def create(width: Int, height: Int): Either[String, MpvSurface] =
    if !Mpv.available then Left("libmpv not found: install it (macOS: `brew install mpv`) and make sure libmpv.dylib is on the loader path")
    else
      try Right(open(width, height))
      catch case e: RuntimeException => Left(e.getMessage)

  private def check(what: String, code: Int): Unit =
    if code < 0 then throw new RuntimeException(s"$what failed: ${Mpv.errorString(code)} ($code)")

  private def open(width: Int, height: Int): MpvSurface =
    val handle = Mpv.mpv_create()
    if handle == null then throw new RuntimeException("mpv_create failed")
    var ctx: Ptr[Byte] = null
    val fds = stackalloc[CInt](2)
    var piped = false
    try
      Zone {
        // mpv must not open a window of its own; frames only go to the render context below
        check("vo option", Mpv.mpv_set_option_string(handle, c"vo", c"libmpv"))
        check("mpv_initialize", Mpv.mpv_initialize(handle))
        val ctxOut = stackalloc[Ptr[Byte]]()
        val params = stackalloc[Mpv.RenderParam](2)
        params(0)._1 = Mpv.ParamApiType; params(0)._2 = c"sw" // OpenGL would need a GL context; see the class comment
        params(1)._1 = 0; params(1)._2 = null
        check("mpv_render_context_create", Mpv.mpv_render_context_create(ctxOut, handle, params))
        ctx = !ctxOut
        if MpvLibc.pipe(fds) != 0 then throw new RuntimeException("pipe failed")
        piped = true
        // only the write end is non-blocking (mpv's demuxer wants a blocking read end): F_GETFL = 3, F_SETFL = 4, O_NONBLOCK = 4 on macOS
        MpvLibc.fcntl(fds(1), 4, MpvLibc.fcntl(fds(1), 3) | 4)
        val args = stackalloc[CString](3)
        args(0) = c"loadfile"; args(1) = toCString(s"fd://${fds(0)}"); args(2) = null
        check("loadfile", Mpv.mpv_command(handle, args))
      }
      new MpvSurfaceImpl(handle, ctx, fds(0), fds(1), width, height)
    catch
      case e: Throwable =>
        if ctx != null then Mpv.mpv_render_context_free(ctx)
        Mpv.mpv_terminate_destroy(handle)
        if piped then { MpvLibc.close(fds(0)); MpvLibc.close(fds(1)) }
        throw e

  private val EAGAIN = 35 // macOS value

  private final class MpvSurfaceImpl(handle: Ptr[Byte], ctx: Ptr[Byte], readFd: Int, writeFd: Int, val width: Int, val height: Int) extends MpvSurface:
    private var closed = false
    private var writeOpen = true
    private val lock = new Object
    private val scratch = new Array[Byte](width * height * 4)

    def pushBytes(data: Array[Byte], offset: Int, length: Int): Int = lock.synchronized {
      require(offset >= 0 && length >= 0 && offset + length <= data.length, "range out of bounds")
      if closed || !writeOpen then -1
      else if length == 0 then 0
      else
        val n = MpvLibc.write(writeFd, data.atUnsafe(offset), length.toCSize).toInt
        if n >= 0 then n
        else if errno == EAGAIN then 0 // pipe full
        else -1
    }

    def endOfStream(): Unit = lock.synchronized {
      if writeOpen then { writeOpen = false; MpvLibc.close(writeFd) }
    }

    def renderPixels(rgba: Array[Byte]): Boolean = lock.synchronized {
      require(rgba.length >= width * height * 4, "pixel buffer too small")
      if closed then false
      else
        Zone {
          val size = stackalloc[CInt](2)
          size(0) = width; size(1) = height
          val stride = stackalloc[CSize]()
          !stride = (width * 4).toCSize
          val params = stackalloc[Mpv.RenderParam](5)
          params(0)._1 = Mpv.ParamSwSize; params(0)._2 = size.asInstanceOf[Ptr[Byte]]
          params(1)._1 = Mpv.ParamSwFormat; params(1)._2 = c"rgb0".asInstanceOf[Ptr[Byte]] // r, g, b, then an unspecified byte
          params(2)._1 = Mpv.ParamSwStride; params(2)._2 = stride.asInstanceOf[Ptr[Byte]]
          params(3)._1 = Mpv.ParamSwPointer; params(3)._2 = rgba.atUnsafe(0)
          params(4)._1 = 0; params(4)._2 = null
          if Mpv.mpv_render_context_render(ctx, params) < 0 then false
          else
            // the fourth byte is "0" in name only; make it opaque so the premultiplied-RGBA texture contract holds
            var i = 3
            while i < width * height * 4 do { rgba(i) = -1; i += 4 }
            true
        }
    }

    def renderInto(target: Texture): Boolean =
      require(target.width >= width && target.height >= height, "texture smaller than the surface")
      if renderPixels(scratch) then { target.update(0, 0, width, height, scratch); true } else false

    def close(): Unit = lock.synchronized {
      if !closed then
        closed = true
        // order matters: the render context must go before the core (libmpv docs), and the pipe's write end closes first so the demuxer sees EOF
        endOfStreamLocked()
        Mpv.mpv_render_context_free(ctx)
        Mpv.mpv_terminate_destroy(handle)
        MpvLibc.close(readFd)
    }

    private def endOfStreamLocked(): Unit =
      if writeOpen then { writeOpen = false; MpvLibc.close(writeFd) }
