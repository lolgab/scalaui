package scalaui.gpu

import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*
import scalaui.window.{C, Frameworks, ObjC, Shim, Window, Id}
import ObjC.*

/** Metal implementation of [[GpuBackend]]: a CAMetalLayer on the window's view, a single
  * alpha-blended textured-triangle pipeline, and 4x MSAA resolved into the drawable.
  */
final class MetalBackend(window: Window) extends GpuBackend:
  private val BGRA8Unorm = 80L
  private val SampleCount = 4L

  private val shaderSource = """
    #include <metal_stdlib>
    using namespace metal;
    struct Vertex { packed_float2 pos; packed_float2 uv; packed_float4 color; };
    struct VOut { float4 position [[position]]; float2 uv; float4 color; };
    vertex VOut vs_main(uint vid [[vertex_id]], const device Vertex* v [[buffer(0)]], constant float2& size [[buffer(1)]]) {
      Vertex vx = v[vid];
      VOut o;
      o.position = float4(vx.pos.x / size.x * 2.0 - 1.0, 1.0 - vx.pos.y / size.y * 2.0, 0.0, 1.0);
      o.uv = float2(vx.uv);
      o.color = float4(vx.color);
      return o;
    }
    fragment float4 fs_main(VOut in [[stage_in]], texture2d<float> tex [[texture(0)]], sampler smp [[sampler(0)]]) {
      return in.color * tex.sample(smp, in.uv);
    }
  """

  private val view: Id = window.nativeHandle
  private val device: Id = Frameworks.MTLCreateSystemDefaultDevice()
  require(device != null, "no Metal device available")
  private val layer: Id = send(cls("CAMetalLayer"), "layer")
  send(layer, "setDevice:", device)
  sendL(layer, "setPixelFormat:", BGRA8Unorm)
  bool(layer, "setFramebufferOnly:", true)
  send(layer, "setContentsGravity:", nsString("topLeft")) // never stretch a stale frame while resizing
  send(view, "setLayer:", layer)
  bool(view, "setWantsLayer:", true)
  private val queue: Id = send(device, "newCommandQueue")
  private val pipeline: Id = buildPipeline(blend = true)
  private val replacePipeline: Id = buildPipeline(blend = false) // writes the source color unblended (clears a damaged rectangle)
  private val sampler: Id =
    val d = send(send(cls("MTLSamplerDescriptor"), "alloc"), "init")
    sendL(d, "setMinFilter:", 1); sendL(d, "setMagFilter:", 1) // linear
    send(device, "newSamplerStateWithDescriptor:", d)
  private var msaa: Id = null
  private var msaaW = 0L
  private var lastW = 0L
  private var lastH = 0L
  private var msaaH = 0L
  private var haveFrame = false // msaa holds a complete previous frame of the current size and scale
  private var lastScale = 0.0
  // test hook: read each presented drawable back (needs a layer that is not framebuffer-only)
  private[gpu] var capture = false
  private[gpu] var captured: Option[(Array[Byte], Int, Int)] = None

  final class MetalTexture(val handle: Id, val width: Int, val height: Int) extends Texture:
    def update(x: Int, y: Int, w: Int, h: Int, rgba: Array[Byte]): Unit =
      require(rgba.length >= w * h * 4, "pixel buffer too small")
      Shim.scalaui_replace_region(handle, sel("replaceRegion:mipmapLevel:withBytes:bytesPerRow:"), x.toULong, y.toULong, w.toULong, h.toULong, rgba.atUnsafe(0), (w * 4).toULong)

  val whiteTexture: Texture = createTexture(1, 1, Array(-1, -1, -1, -1))

  def createTexture(width: Int, height: Int, rgba: Array[Byte]): Texture =
    val desc = C.msgLLLC(cls("MTLTextureDescriptor"), sel("texture2DDescriptorWithPixelFormat:width:height:mipmapped:"), 70L /* RGBA8Unorm */, width.toLong, height.toLong, 0)
    val tex = send(device, "newTextureWithDescriptor:", desc)
    val t = MetalTexture(tex, width, height)
    if rgba != null then t.update(0, 0, width, height, rgba)
    t

  def submit(frame: Frame): Unit = submit(frame, None)

  /** With `damage` the persistent multisample attachment is loaded, the damaged rectangle is overwritten with the clear color
    * and only that rectangle is redrawn (scissored); the attachment is then resolved into a fresh drawable. A first frame,
    * size or scale change, or a skipped drawable falls back to a full redraw. */
  override def submit(frame: Frame, damage: scala.Option[scalaui.geom.Rect]): Unit =
    val pool = send(send(cls("NSAutoreleasePool"), "alloc"), "init")
    val pw = math.max(1.0, math.round(frame.width * frame.scale).toDouble)
    val ph = math.max(1.0, math.round(frame.height * frame.scale).toDouble)
    // While the size changes (live resize) the drawable must reach the screen in the same Core Animation transaction as
    // the new layer geometry, otherwise the old and new sizes alternate and the window flickers.
    val resizing = (pw.toLong != lastW || ph.toLong != lastH) && lastW != 0L
    lastW = pw.toLong; lastH = ph.toLong
    bool(layer, "setPresentsWithTransaction:", resizing)
    sendD(layer, "setContentsScale:", frame.scale)
    Shim.scalaui_set_drawable_size(layer, sel("setDrawableSize:"), pw, ph)
    if capture then bool(layer, "setFramebufferOnly:", false)
    val drawable = send(layer, "nextDrawable")
    if drawable == null then haveFrame = false
    if drawable != null then
      ensureMsaa(pw.toLong, ph.toLong) // a new attachment has no previous frame (haveFrame is reset there)
      val partial = damage.isDefined && haveFrame && lastScale == frame.scale && !resizing
      lastScale = frame.scale
      val (rx0, ry0, rx1, ry1) = if partial then SoftwareRenderer.damagePixels(frame, damage) else (0, 0, pw.toInt, ph.toInt)
      if partial && (rx0 >= rx1 || ry0 >= ry1) then { send(pool, "drain"); return } // nothing visible changed
      val pass = send(cls("MTLRenderPassDescriptor"), "renderPassDescriptor")
      val ca = sendL(send(pass, "colorAttachments"), "objectAtIndexedSubscript:", 0)
      send(ca, "setTexture:", msaa)
      send(ca, "setResolveTexture:", send(drawable, "texture"))
      sendL(ca, "setLoadAction:", if partial then 1L else 2L) // load the previous samples / clear
      sendL(ca, "setStoreAction:", 3) // store the samples for the next partial frame and resolve them
      val clear = frame.clear
      Shim.scalaui_set_clear_color(ca, sel("setClearColor:"), clear.r, clear.g, clear.b, clear.a)
      val cmd = send(queue, "commandBuffer")
      val enc = send(cmd, "renderCommandEncoderWithDescriptor:", pass)
      if partial then
        // overwrite the damaged rectangle with the clear color (same value the full path clears to), unblended
        val q = stackalloc[CFloat](6 * 8)
        val corners = Array((0f, 0f), (frame.width.toFloat, 0f), (0f, frame.height.toFloat), (frame.width.toFloat, 0f), (frame.width.toFloat, frame.height.toFloat), (0f, frame.height.toFloat))
        var i = 0
        while i < 6 do
          val b = i * 8
          q(b) = corners(i)._1; q(b + 1) = corners(i)._2; q(b + 2) = 0f; q(b + 3) = 0f
          q(b + 4) = frame.clear.r; q(b + 5) = frame.clear.g; q(b + 6) = frame.clear.b; q(b + 7) = frame.clear.a
          i += 1
        send(enc, "setRenderPipelineState:", replacePipeline)
        C.msgPLL(enc, sel("setVertexBytes:length:atIndex:"), q.asInstanceOf[Id], 6L * 32L, 0L)
        val size = stackalloc[CFloat](2)
        size(0) = frame.width.toFloat; size(1) = frame.height.toFloat
        C.msgPLL(enc, sel("setVertexBytes:length:atIndex:"), size.asInstanceOf[Id], 8L, 1L)
        C.msgPL(enc, sel("setFragmentSamplerState:atIndex:"), sampler, 0L)
        C.msgPL(enc, sel("setFragmentTexture:atIndex:"), whiteTexture.asInstanceOf[MetalTexture].handle, 0L)
        Shim.scalaui_set_scissor(enc, sel("setScissorRect:"), rx0.toULong, ry0.toULong, (rx1 - rx0).toULong, (ry1 - ry0).toULong)
        C.msgLLL(enc, sel("drawPrimitives:vertexStart:vertexCount:"), 3, 0L, 6L)
      if frame.vertices.nonEmpty then
        send(enc, "setRenderPipelineState:", pipeline)
        val buf = C.msgPLL(device, sel("newBufferWithBytes:length:options:"), frame.vertices.atUnsafe(0).asInstanceOf[Id], frame.vertices.length * 4L, 0L)
        C.msgPLL(enc, sel("setVertexBuffer:offset:atIndex:"), buf, 0L, 0L)
        val size = stackalloc[CFloat](2)
        size(0) = frame.width.toFloat; size(1) = frame.height.toFloat
        C.msgPLL(enc, sel("setVertexBytes:length:atIndex:"), size.asInstanceOf[Id], 8L, 1L)
        C.msgPL(enc, sel("setFragmentSamplerState:atIndex:"), sampler, 0L)
        frame.calls.foreach { call =>
          C.msgPL(enc, sel("setFragmentTexture:atIndex:"), call.texture.asInstanceOf[MetalTexture].handle, 0L)
          val (sx, sy, sw, sh) = call.scissor match
            case Some(r) =>
              val x0 = math.max(0.0, math.floor(r.x0 * frame.scale)); val y0 = math.max(0.0, math.floor(r.y0 * frame.scale))
              val x1 = math.min(pw, math.ceil(r.x1 * frame.scale)); val y1 = math.min(ph, math.ceil(r.y1 * frame.scale))
              (x0.toLong, y0.toLong, math.max(0.0, x1 - x0).toLong, math.max(0.0, y1 - y0).toLong)
            case None => (0L, 0L, pw.toLong, ph.toLong)
          // partial frame: draw only inside the damage rectangle
          val ix0 = if partial then math.max(sx, rx0.toLong) else sx; val iy0 = if partial then math.max(sy, ry0.toLong) else sy
          val ix1 = if partial then math.min(sx + sw, rx1.toLong) else sx + sw; val iy1 = if partial then math.min(sy + sh, ry1.toLong) else sy + sh
          if ix1 > ix0 && iy1 > iy0 then
            Shim.scalaui_set_scissor(enc, sel("setScissorRect:"), ix0.toULong, iy0.toULong, (ix1 - ix0).toULong, (iy1 - iy0).toULong)
            C.msgLLL(enc, sel("drawPrimitives:vertexStart:vertexCount:"), 3, call.firstVertex.toLong, call.vertexCount.toLong)
        }
        send(buf, "release")
      send(enc, "endEncoding")
      haveFrame = true
      if resizing then
        send(cmd, "commit")
        send(cmd, "waitUntilScheduled")
        send(drawable, "present")
      else
        send(cmd, "presentDrawable:", drawable)
        send(cmd, "commit")
      if capture then
        send(cmd, "waitUntilCompleted")
        val out = new Array[Byte](pw.toInt * ph.toInt * 4)
        Shim.scalaui_get_bytes(send(drawable, "texture"), sel("getBytes:bytesPerRow:fromRegion:mipmapLevel:"), out.atUnsafe(0), (pw.toInt * 4).toULong, pw.toLong.toULong, ph.toLong.toULong)
        var i = 0
        while i < out.length do { val b = out(i); out(i) = out(i + 2); out(i + 2) = b; i += 4 } // BGRA -> RGBA
        captured = Some((out, pw.toInt, ph.toInt))
    send(pool, "drain")

  def close(): Unit = ()

  private def ensureMsaa(w: Long, h: Long): Unit =
    if msaa == null || w != msaaW || h != msaaH then
      if msaa != null then send(msaa, "release")
      val d = C.msgLLLC(cls("MTLTextureDescriptor"), sel("texture2DDescriptorWithPixelFormat:width:height:mipmapped:"), BGRA8Unorm, w, h, 0)
      sendL(d, "setTextureType:", 4)  // 2D multisample
      sendL(d, "setSampleCount:", SampleCount)
      sendL(d, "setUsage:", 4)        // render target
      sendL(d, "setStorageMode:", 2)  // private
      msaa = send(device, "newTextureWithDescriptor:", d)
      msaaW = w; msaaH = h; haveFrame = false

  private def buildPipeline(blend: Boolean): Id =
    val err = stackalloc[Id]()
    !err = null
    val lib = C.msgPPP(device, sel("newLibraryWithSource:options:error:"), nsString(shaderSource), null, err.asInstanceOf[Id])
    if lib == null then throw new RuntimeException(s"MSL compile failed: ${describe(!err)}")
    val desc = send(send(cls("MTLRenderPipelineDescriptor"), "alloc"), "init")
    send(desc, "setVertexFunction:", send(lib, "newFunctionWithName:", nsString("vs_main")))
    send(desc, "setFragmentFunction:", send(lib, "newFunctionWithName:", nsString("fs_main")))
    sendL(desc, "setRasterSampleCount:", SampleCount)
    val att = sendL(send(desc, "colorAttachments"), "objectAtIndexedSubscript:", 0)
    sendL(att, "setPixelFormat:", BGRA8Unorm)
    bool(att, "setBlendingEnabled:", blend)
    // premultiplied alpha: src + dst * (1 - srcAlpha)
    sendL(att, "setSourceRGBBlendFactor:", 1); sendL(att, "setSourceAlphaBlendFactor:", 1)
    sendL(att, "setDestinationRGBBlendFactor:", 5); sendL(att, "setDestinationAlphaBlendFactor:", 5)
    !err = null
    val ps = C.msgPP(device, sel("newRenderPipelineStateWithDescriptor:error:"), desc, err.asInstanceOf[Id])
    if ps == null then throw new RuntimeException(s"pipeline creation failed: ${describe(!err)}")
    ps

  private def describe(error: Id): String =
    if error == null then "unknown error" else fromNSString(send(send(error, "localizedDescription"), "description"))
