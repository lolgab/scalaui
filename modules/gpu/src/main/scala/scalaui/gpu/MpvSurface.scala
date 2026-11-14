package scalaui.gpu

import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*
import scala.scalanative.libc.errno.errno
import scalaui.window.{DynLib, Native}

/** The few libc calls needed to feed libmpv a live byte stream through a pipe. */
@extern private object MpvLibc:
  @blocking def pipe(fds: Ptr[CInt]): CInt = extern
  @blocking def write(fd: CInt, buf: Ptr[Byte], n: CSize): CSSize = extern
  @blocking def close(fd: CInt): CInt = extern
  @blocking def fcntl(fd: CInt, cmd: CInt, args: CVarArg*): CInt = extern

/** libmpv's render API, resolved at run time: `libmpv` is a Homebrew package that may not be installed, so it is never
  * linked. A build without it still links; [[MpvSurface.create]] then fails with a readable message.
  *
  * These are calls through function pointers, which Scala Native never treats as blocking, so each one that can wait on mpv's
  * internal locks (the demuxer lock is held while it ingests data) is wrapped in [[Native.blocking]]: otherwise a garbage
  * collection started on any thread would wait for it, with the whole app stopped.
  */
private[gpu] object Mpv:
  /** `mpv_render_param`: a type tag followed by a pointer whose meaning depends on the tag. */
  type RenderParam = CStruct2[CInt, Ptr[Byte]]

  val ParamApiType = 1
  val ParamOpenGlInit = 2
  val ParamOpenGlFbo = 3
  val ParamFlipY = 4
  val ParamBlockForTargetTime = 12
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
  private lazy val fGetProperty = CFuncPtr.fromPtr[CFuncPtr4[Ptr[Byte], CString, CInt, Ptr[Byte], CInt]](sym("mpv_get_property"))
  private lazy val fCommandAsync = CFuncPtr.fromPtr[CFuncPtr3[Ptr[Byte], CUnsignedLongLong, Ptr[CString], CInt]](sym("mpv_command_async"))
  private lazy val fSetProperty = CFuncPtr.fromPtr[CFuncPtr3[Ptr[Byte], CString, CString, CInt]](sym("mpv_set_property_string"))
  private lazy val fRenderFree = CFuncPtr.fromPtr[CFuncPtr1[Ptr[Byte], Unit]](sym("mpv_render_context_free"))

  def mpv_set_property_string(h: Ptr[Byte], name: CString, value: CString): Int = Native.blocking(fSetProperty(h, name, value))
  /** Queues a command and returns at once (the result would arrive as an event, which nobody reads here). */
  def mpv_command_async(h: Ptr[Byte], args: Ptr[CString]): Int = Native.blocking(fCommandAsync(h, 0.toULong, args))
  def mpv_create(): Ptr[Byte] = fCreate()
  def mpv_initialize(h: Ptr[Byte]): Int = Native.blocking(fInitialize(h))
  def mpv_set_option_string(h: Ptr[Byte], name: CString, value: CString): Int = Native.blocking(fSetOption(h, name, value))
  /** `args` is a null-terminated array of C strings. */
  def mpv_command(h: Ptr[Byte], args: Ptr[CString]): Int = Native.blocking(fCommand(h, args))
  def mpv_terminate_destroy(h: Ptr[Byte]): Unit = Native.blocking(fDestroy(h))
  /** Not wrapped in `Native.blocking`: with the OpenGL path mpv calls back into [[MpvGl.getProcAddress]] from in here, and that
    * is Scala code. It runs once per player. */
  def mpv_render_context_create(out: Ptr[Ptr[Byte]], h: Ptr[Byte], params: Ptr[RenderParam]): Int = fRenderCreate(out, h, params)
  def mpv_render_context_render(ctx: Ptr[Byte], params: Ptr[RenderParam]): Int = Native.blocking(fRender(ctx, params))
  def mpv_render_context_free(ctx: Ptr[Byte]): Unit = Native.blocking(fRenderFree(ctx))
  /** `MPV_FORMAT_INT64` (4) property, or -1 while unavailable. */
  def getInt(h: Ptr[Byte], name: CString): Long =
    val out = stackalloc[CLongLong]()
    if Native.blocking(fGetProperty(h, name, 4, out.asInstanceOf[Ptr[Byte]])) < 0 then -1L else !out
  /** A double-valued property (`MPV_FORMAT_DOUBLE` = 5), or -1.0 if mpv has no value for it right now. */
  def getDouble(h: Ptr[Byte], name: CString): Double =
    val out = stackalloc[CDouble]()
    if Native.blocking(fGetProperty(h, name, 5, out.asInstanceOf[Ptr[Byte]])) < 0 then -1.0 else !out
  def errorString(code: Int): String = fromCString(fErrorString(code))

/** An in-process libmpv player whose frames end up in a scalaui [[Texture]], so video composites with the rest of the
  * immediate-mode UI like any other image (`canvas.drawImage(texture, rect)`).
  *
  * Two render paths exist:
  *  - GPU (macOS, [[MpvSurface.createGpu]]): mpv's OpenGL renderer draws into an IOSurface-backed FBO on a headless CGL
  *    context; the same IOSurface is wrapped as a Metal texture. No CPU copies, no CPU scaling, any size.
  *  - Software ([[MpvSurface.create]], and the automatic fallback of `createGpu`): mpv renders on the CPU into a buffer that
  *    is uploaded each frame. Fixed size, CPU-bound.
  *
  * Typical use from a paint loop:
  * {{{
  * val surface = MpvSurface.createGpu(gpu, 1280, 720).toOption.get
  * // feeder: surface.pushBytes(chunk) for every chunk of the stream
  * surface.resize(pixelWidth, pixelHeight)           // GPU path: follow the on-screen rect x scale factor
  * surface.renderTexture().foreach(t => canvas.drawImage(t, rect))
  * }}}
  *
  * Video bytes are pushed through a `pipe(2)` that mpv reads as `fd://<readfd>` (see [[MpvSurface.create]]).
  */
trait MpvSurface:
  /** Current width of the rendered frame in pixels. */
  def width: Int
  /** Current height of the rendered frame in pixels. */
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
    * which case `target` is untouched. Software path only: a GPU surface returns false (use [[renderTexture]]).
    */
  def renderInto(target: Texture): Boolean
  /** Same as [[renderInto]] but into a caller-owned tightly packed RGBA8 buffer of at least `width * height * 4` bytes. */
  def renderPixels(rgba: Array[Byte]): Boolean
  /** True when the surface owns its output texture ([[renderTexture]] works): created by [[MpvSurface.createGpu]]. */
  def managesTexture: Boolean = false
  /** True when frames are produced by the GPU path (false for the software path, including the `createGpu` fallback). */
  def isGpu: Boolean = false
  /** Requests a new frame size in pixels; applied by the next [[renderTexture]]. The GPU path follows it (aspect ratio is
    * preserved by letterboxing inside the frame); the software path keeps its fixed size and ignores it.
    */
  def resize(w: Int, h: Int): Unit = ()
  /** Display size of the video (width, height) once mpv knows it, otherwise None. */
  def videoSize: Option[(Int, Int)] = None
  /** Renders the current picture at the current `width` x `height` and returns the texture to draw (upright, opaque,
    * letterboxed to the video's aspect ratio). None until the first frame is available, or when closed. The texture may
    * change identity after [[resize]]: fetch it again every frame. Only for surfaces from [[MpvSurface.createGpu]].
    */
  def renderTexture(): Option[Texture] = None
  /** Seconds of video mpv has already buffered ahead of the picture on screen (its demuxer cache), 0 if unknown. Data handed
    * to the player is not "behind" the player's position until this much of it has played. */
  def cacheSeconds: Double = 0.0
  /** Pauses or resumes playback (mpv's `pause` property). A paused player stops reading the pipe, which back-pressures the feeder. */
  def setPause(paused: Boolean): Unit = ()
  /** Stops playback and releases the render context, the player and the pipe. Idempotent. */
  def close(): Unit

object MpvSurface:
  /** True when `libmpv` can be loaded on this machine (on macOS: `brew install mpv`). */
  def available: Boolean = Mpv.available

  /** Creates a software-rendering player whose frames are `width` x `height` pixels and starts it reading from a fresh pipe.
    *
    * The stream goes through `pipe(2)` + `fd://` rather than `mpv_stream_cb_add_ro`: the pipe is mpv's own stdin-style
    * demuxing path (what `mpv -` does), needs no C callbacks into Scala from mpv's demuxer thread, and the kernel pipe gives
    * back-pressure for free. The cost is that the stream is not seekable, which a live MPEG-TS feed isn't anyway.
    *
    * Left carries a human-readable reason (libmpv missing, or mpv's own error string).
    */
  def create(width: Int, height: Int): Either[String, MpvSurface] =
    if !Mpv.available then Left(missingMessage)
    else
      try Right(open(width, height, null, null))
      catch case e: RuntimeException => Left(e.getMessage)

  /** Creates a player that renders on the GPU when possible (macOS with a Metal [[GpuBackend]]: OpenGL into an IOSurface
    * shared with Metal), starting at `width` x `height` pixels (change it with [[MpvSurface.resize]]). When GL/IOSurface
    * setup fails, or the backend cannot import IOSurfaces, falls back to the software path at the given fixed size
    * (check [[MpvSurface.isGpu]]). The returned surface always [[MpvSurface.managesTexture]].
    */
  def createGpu(gpu: GpuBackend, width: Int, height: Int): Either[String, MpvSurface] =
    if !Mpv.available then Left(missingMessage)
    else
      // `isMac` must be the `if` condition itself for the linker to drop the Objective-C code (MpvGl.create) elsewhere
      val glTarget: Option[MpvGl] =
        if scala.scalanative.meta.LinktimeInfo.isMac then
          if MpvGl.supported && gpu.supportsIOSurface then
            try Some(MpvGl.create()) catch case e: Throwable => { debug(s"GL setup failed: ${e.getMessage}"); None }
          else None
        else None
      try Right(open(width, height, gpu, glTarget.orNull))
      catch
        case e: Throwable =>
          debug(s"GPU path failed (${e.getMessage}), using software rendering")
          glTarget.foreach(_.destroy())
          try Right(open(width, height, gpu, null))
          catch case e2: RuntimeException => Left(e2.getMessage)

  private def debug(msg: String): Unit = if sys.env.contains("SCALAUI_DEBUG") then System.err.println(s"mpv: $msg")

  private val missingMessage = "libmpv not found: install it (macOS: `brew install mpv`) and make sure libmpv.dylib is on the loader path"

  private def check(what: String, code: Int): Unit =
    if code < 0 then throw new RuntimeException(s"$what failed: ${Mpv.errorString(code)} ($code)")

  /** `gl` non-null selects the OpenGL render path, `gpu` non-null makes the surface own its texture. */
  private def open(width: Int, height: Int, gpu: GpuBackend, gl: MpvGl): MpvSurface =
    // the stream goes through a POSIX pipe; `isWindows` as the `if` condition keeps pipe/fcntl/dlsym out of Windows links
    if scala.scalanative.meta.LinktimeInfo.isWindows then throw new RuntimeException("libmpv playback is not supported on Windows")
    else openPipe(width, height, gpu, gl)

  private def openPipe(width: Int, height: Int, gpu: GpuBackend, gl: MpvGl): MpvSurface =
    val handle = Mpv.mpv_create()
    if handle == null then throw new RuntimeException("mpv_create failed")
    var ctx: Ptr[Byte] = null
    val fds = stackalloc[CInt](2)
    var piped = false
    try
      Zone {
        // mpv must not open a window of its own; frames only go to the render context below
        check("vo option", Mpv.mpv_set_option_string(handle, c"vo", c"libmpv"))
        // copy-back hardware decoding, not zero-copy: deinterlacing is a software filter and needs the frames in system memory
        if gl != null then Mpv.mpv_set_option_string(handle, c"hwdec", c"auto-copy-safe") // best effort
        // broadcast streams are often interlaced (visible as combing lines on motion); this is a no-op for progressive video
        Mpv.mpv_set_option_string(handle, c"deinterlace", c"yes")
        // Bound mpv's own read-ahead: whatever it buffers is video the feeder has already handed over, so it is no longer
        // "behind" in the feeder's books (pausing and jumping to live both need that to stay small and measurable).
        Mpv.mpv_set_option_string(handle, c"demuxer-max-bytes", c"48MiB")
        Mpv.mpv_set_option_string(handle, c"demuxer-readahead-secs", c"10")
        Mpv.mpv_set_option_string(handle, c"cache-secs", c"10")
        check("mpv_initialize", Mpv.mpv_initialize(handle))
        val ctxOut = stackalloc[Ptr[Byte]]()
        if gl != null then
          gl.makeCurrent()
          val init = stackalloc[Ptr[Byte]](2)
          init(0) = CFuncPtr.toPtr(MpvGl.getProcAddress).asInstanceOf[Ptr[Byte]]; init(1) = null
          val params = stackalloc[Mpv.RenderParam](3)
          params(0)._1 = Mpv.ParamApiType; params(0)._2 = c"opengl"
          params(1)._1 = Mpv.ParamOpenGlInit; params(1)._2 = init.asInstanceOf[Ptr[Byte]]
          params(2)._1 = 0; params(2)._2 = null
          check("mpv_render_context_create (opengl)", Mpv.mpv_render_context_create(ctxOut, handle, params))
        else
          val params = stackalloc[Mpv.RenderParam](2)
          params(0)._1 = Mpv.ParamApiType; params(0)._2 = c"sw"
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
      new MpvSurfaceImpl(handle, ctx, fds(0), fds(1), width, height, gpu, gl)
    catch
      case e: Throwable =>
        if ctx != null then Mpv.mpv_render_context_free(ctx)
        Mpv.mpv_terminate_destroy(handle)
        if piped then { MpvLibc.close(fds(0)); MpvLibc.close(fds(1)) }
        throw e

  private val EAGAIN = 35 // macOS value

  private final class MpvSurfaceImpl(handle: Ptr[Byte], ctx: Ptr[Byte], readFd: Int, writeFd: Int, initW: Int, initH: Int, gpu: GpuBackend, gl: MpvGl) extends MpvSurface:
    @volatile private var closed = false
    private var writeOpen = true
    private val lock = new Object
    private var w = math.max(1, initW)
    private var h = math.max(1, initH)
    private var wantW = w
    private var wantH = h
    private var known: Option[(Int, Int)] = None
    // software path
    private val scratch = new Array[Byte](if gl == null then w * h * 4 else 0)
    private var swTexture: Texture = null

    def width: Int = w
    def height: Int = h
    override def managesTexture: Boolean = gpu != null
    override def isGpu: Boolean = gl != null
    override def resize(nw: Int, nh: Int): Unit = lock.synchronized { if gl != null then { wantW = math.max(1, nw); wantH = math.max(1, nh) } }

    // Reading an mpv property can wait on mpv's internal locks (the demuxer lock is held while it ingests data), so it must
    // not run under `lock`, which the render path needs: that would stall painting. It has its own lock, which `close()` also
    // takes before destroying the handle. Callers should still poll it from a background thread, not the paint loop.
    private val propLock = new Object
    override def cacheSeconds: Double = propLock.synchronized {
      if closed then 0.0 else math.max(0.0, Mpv.getDouble(handle, c"demuxer-cache-duration"))
    }

    // Asked every frame until the size is known; each ask is a synchronous mpv call, so ask at most a few times a second.
    private var lastSizeAsk = 0L
    override def videoSize: Option[(Int, Int)] =
      val k = known
      if k.isDefined then k
      else
        val now = System.currentTimeMillis()
        if now - lastSizeAsk < 250 then None
        else
          lastSizeAsk = now
          propLock.synchronized {
            if !closed then
              val vw = Mpv.getInt(handle, c"dwidth"); val vh = Mpv.getInt(handle, c"dheight")
              if vw > 0 && vh > 0 then known = Some((vw.toInt, vh.toInt))
          }
          known

    // The pipe has its own lock. A feeder whose pipe is full retries every millisecond, and if those retries took the same lock as
    // rendering, painting would queue behind them (frames of hundreds of ms once the buffer was deep). `close()` takes both.
    private val pipeLock = new Object

    def pushBytes(data: Array[Byte], offset: Int, length: Int): Int = pipeLock.synchronized {
      require(offset >= 0 && length >= 0 && offset + length <= data.length, "range out of bounds")
      if closed || !writeOpen then -1
      else if length == 0 then 0
      else
        val n = MpvLibc.write(writeFd, data.atUnsafe(offset), length.toCSize).toInt
        if n >= 0 then n
        else if errno == EAGAIN then 0 // pipe full
        else -1
    }

    def endOfStream(): Unit = pipeLock.synchronized {
      if writeOpen then { writeOpen = false; MpvLibc.close(writeFd) }
    }

    def renderPixels(rgba: Array[Byte]): Boolean = lock.synchronized {
      require(rgba.length >= w * h * 4 || gl != null, "pixel buffer too small")
      if closed || gl != null then false
      else
        Zone {
          val size = stackalloc[CInt](2)
          size(0) = w; size(1) = h
          val stride = stackalloc[CSize]()
          !stride = (w * 4).toCSize
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
            while i < w * h * 4 do { rgba(i) = -1; i += 4 }
            true
        }
    }

    def renderInto(target: Texture): Boolean =
      if gl != null then false
      else
        require(target.width >= w && target.height >= h, "texture smaller than the surface")
        if renderPixels(scratch) then { target.update(0, 0, w, h, scratch); true } else false

    override def renderTexture(): Option[Texture] = lock.synchronized {
      if closed || gpu == null then None
      else if gl == null then
        if videoSize.isEmpty then None
        else
          if swTexture == null then swTexture = gpu.createTexture(w, h, null)
          if renderPixels(scratch) then { swTexture.update(0, 0, w, h, scratch); Some(swTexture) } else None
      else if videoSize.isEmpty then None // nothing decoded yet: let the caller show black
      else
        gl.makeCurrent()
        if gl.ensureSize(gpu, wantW, wantH) then { w = wantW; h = wantH }
        Zone {
          val fbo = stackalloc[CInt](4)
          fbo(0) = gl.fbo; fbo(1) = w; fbo(2) = h; fbo(3) = 0
          val flip = stackalloc[CInt]()
          !flip = 0 // empirically verified: with the IOSurface texture mpv's default (unflipped) output lands upright in Metal
          val block = stackalloc[CInt]()
          !block = 0 // never wait for the frame's target time inside the UI loop
          val params = stackalloc[Mpv.RenderParam](4)
          params(0)._1 = Mpv.ParamOpenGlFbo; params(0)._2 = fbo.asInstanceOf[Ptr[Byte]]
          params(1)._1 = Mpv.ParamFlipY; params(1)._2 = flip.asInstanceOf[Ptr[Byte]]
          params(2)._1 = Mpv.ParamBlockForTargetTime; params(2)._2 = block.asInstanceOf[Ptr[Byte]]
          params(3)._1 = 0; params(3)._2 = null
          if Mpv.mpv_render_context_render(ctx, params) < 0 then None
          else
            gl.finish() // Metal reads the same IOSurface next
            Some(gl.texture)
        }
    }

    // Pausing is queued to mpv instead of set synchronously. A synchronous call from the UI thread can wait on mpv's core, which
    // in turn can wait on its video-output thread, which needs the UI thread to call render: a deadlock that showed up as the
    // player freezing after pausing and resuming. The command goes through `propLock` only (never the render lock).
    override def setPause(paused: Boolean): Unit = propLock.synchronized {
      if !closed then Zone {
        val args = stackalloc[CString](4)
        args(0) = c"set"; args(1) = c"pause"; args(2) = if paused then c"yes" else c"no"; args(3) = null
        Mpv.mpv_command_async(handle, args); ()
      }
    }

    def close(): Unit = lock.synchronized {
      if !closed then
        propLock.synchronized { closed = true } // waits out a property read in flight on another thread
        // order matters: the render context must go before the core (libmpv docs), and the pipe's write end closes first so the demuxer sees EOF
        endOfStreamLocked()
        if gl != null then gl.makeCurrent()
        Mpv.mpv_render_context_free(ctx)
        Mpv.mpv_terminate_destroy(handle)
        MpvLibc.close(readFd)
        if gl != null then gl.destroy()
    }

    private def endOfStreamLocked(): Unit = pipeLock.synchronized {
      if writeOpen then { writeOpen = false; MpvLibc.close(writeFd) }
    }
