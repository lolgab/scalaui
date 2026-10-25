package scalaui.gpu

import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*
import scala.scalanative.libc.string
import scalaui.window.{DynLib, Window}

/** Minimal COM helpers: every interface pointer starts with a pointer to its vtable of function pointers. */
private[gpu] object Com:
  def method(obj: Ptr[Byte], index: Int): Ptr[Byte] =
    val vtable = !(obj.asInstanceOf[Ptr[Ptr[Ptr[Byte]]]])
    vtable(index)
  def release(obj: Ptr[Byte]): Unit = if obj != null then CFuncPtr.fromPtr[CFuncPtr1[Ptr[Byte], CInt]](method(obj, 2)).apply(obj)

/** Direct3D 11 implementation of [[GpuBackend]] for Windows: a flip-less DXGI swap chain on the window's HWND, the same
  * textured-triangle pipeline as the other backends (HLSL compiled at run time with the system's d3dcompiler), 4x MSAA
  * resolved into the back buffer. D3D 11 is available on every Windows GPU driver, including ones without Vulkan.
  */
final class D3D11Backend private (window: Window) extends GpuBackend:
  import D3D11Backend.*

  private type P = Ptr[Byte]
  private var device: P = null
  private var context: P = null
  private var swapChain: P = null
  private var vertexShader: P = null
  private var pixelShader: P = null
  private var inputLayout: P = null
  private var blend: P = null
  private var raster: P = null
  private var sampler: P = null
  private var constants: P = null
  private var vertexBuffer: P = null
  private var vertexCapacity = 0
  private var msaaTex: P = null
  private var msaaView: P = null
  private var targetW = 0
  private var targetH = 0

  // ----- vtable calls (indices from d3d11.h / dxgi.h) -----
  private def dev3(i: Int, a: P, b: P, c: P): Int = CFuncPtr.fromPtr[CFuncPtr4[P, P, P, P, CInt]](Com.method(device, i)).apply(device, a, b, c)
  private def ctx(i: Int): P = Com.method(context, i)

  private def check(hr: Int, what: String): Unit = if hr < 0 then throw new RuntimeException(f"D3D11 $what failed: HRESULT 0x$hr%08x")

  private def z(n: Int)(using Zone): P =
    val p = alloc[Byte](n)
    var i = 0; while i < n do { p(i) = 0.toByte; i += 1 }
    p
  extension (p: P)
    private def i32(off: Int, v: Int): Unit = (p + off).asInstanceOf[Ptr[CInt]](0) = v
    private def f32(off: Int, v: Float): Unit = (p + off).asInstanceOf[Ptr[CFloat]](0) = v
    private def ptr(off: Int, q: P): Unit = (p + off).asInstanceOf[Ptr[P]](0) = q
    private def getPtr(off: Int): P = (p + off).asInstanceOf[Ptr[P]](0)
    private def getI32(off: Int): Int = (p + off).asInstanceOf[Ptr[CInt]](0)

  final class D3DTexture(val width: Int, val height: Int) extends Texture:
    private[D3D11Backend] var tex: P = null
    private[D3D11Backend] var view: P = null
    Zone {
      val d = z(44)
      d.i32(0, width); d.i32(4, height); d.i32(8, 1); d.i32(12, 1); d.i32(16, 28) // R8G8B8A8_UNORM
      d.i32(20, 1) // SampleDesc.Count
      d.i32(28, 0); d.i32(32, 8) // USAGE_DEFAULT, BIND_SHADER_RESOURCE
      val out = z(8)
      check(dev3(5, d, null, out), "CreateTexture2D") // ID3D11Device::CreateTexture2D
      tex = out.getPtr(0)
      val v = z(8)
      check(dev3(7, tex, null, v), "CreateShaderResourceView")
      view = v.getPtr(0)
    }
    def update(x: Int, y: Int, w: Int, h: Int, rgba: Array[Byte]): Unit =
      require(rgba.length >= w * h * 4, "pixel buffer too small")
      if w <= 0 || h <= 0 then return
      Zone {
        val box = z(24); box.i32(0, x); box.i32(4, y); box.i32(8, 0); box.i32(12, x + w); box.i32(16, y + h); box.i32(20, 1)
        // UpdateSubresource(resource, subresource, box, data, rowPitch, depthPitch)
        CFuncPtr.fromPtr[CFuncPtr7[P, P, CInt, P, P, CInt, CInt, CInt]](ctx(48)).apply(context, tex, 0, box, rgba.atUnsafe(0), w * 4, 0)
      }

  lazy val whiteTexture: Texture = createTexture(1, 1, Array(-1, -1, -1, -1))

  def createTexture(width: Int, height: Int, rgba: Array[Byte]): Texture =
    val t = new D3DTexture(width, height)
    t.update(0, 0, width, height, if rgba != null then rgba else new Array[Byte](width * height * 4))
    t

  private def ensureTargets(w: Int, h: Int): Unit =
    if w != targetW || h != targetH then
      Zone {
        if targetW != 0 then
          Com.release(msaaView); Com.release(msaaTex)
          // ResizeBuffers(count 0 = keep, w, h, DXGI_FORMAT_UNKNOWN, flags)
          check(CFuncPtr.fromPtr[CFuncPtr6[P, CInt, CInt, CInt, CInt, CInt, CInt]](Com.method(swapChain, 13)).apply(swapChain, 0, w, h, 0, 0), "ResizeBuffers")
        val d = z(44)
        d.i32(0, w); d.i32(4, h); d.i32(8, 1); d.i32(12, 1); d.i32(16, 87) // B8G8R8A8_UNORM
        d.i32(20, Samples); d.i32(28, 0); d.i32(32, 0x20) // DEFAULT, BIND_RENDER_TARGET
        val out = z(8)
        check(dev3(5, d, null, out), "CreateTexture2D(msaa)")
        msaaTex = out.getPtr(0)
        val v = z(8)
        check(dev3(9, msaaTex, null, v), "CreateRenderTargetView") // CreateRenderTargetView(resource, desc, out)
        msaaView = v.getPtr(0)
      }
      targetW = w; targetH = h

  def submit(frame: Frame): Unit = Zone {
    val pw = math.max(1, math.round(frame.width * frame.scale).toInt)
    val ph = math.max(1, math.round(frame.height * frame.scale).toInt)
    ensureTargets(pw, ph)
    val bytes = frame.vertices.length * 4
    if bytes > 0 && bytes > vertexCapacity then
      Com.release(vertexBuffer)
      var cap = 64 * 1024; while cap < bytes do cap *= 2
      val d = z(24); d.i32(0, cap); d.i32(4, 2); d.i32(8, 1); d.i32(12, 0x10000) // DYNAMIC, BIND_VERTEX_BUFFER, CPU_ACCESS_WRITE
      val out = z(8)
      check(dev3(3, d, null, out), "CreateBuffer") // CreateBuffer(desc, init, out)
      vertexBuffer = out.getPtr(0); vertexCapacity = cap
    if bytes > 0 then
      val mapped = z(16)
      // Map(resource, subresource, MAP_WRITE_DISCARD = 4, flags, mapped)
      check(CFuncPtr.fromPtr[CFuncPtr6[P, P, CInt, CInt, CInt, P, CInt]](ctx(14)).apply(context, vertexBuffer, 0, 4, 0, mapped), "Map")
      string.memcpy(mapped.getPtr(0), frame.vertices.atUnsafe(0).asInstanceOf[P], bytes.toCSize)
      CFuncPtr.fromPtr[CFuncPtr3[P, P, CInt, CInt]](ctx(15)).apply(context, vertexBuffer, 0) // Unmap
    // viewport size constant
    val cb = z(16); cb.f32(0, frame.width.toFloat); cb.f32(4, frame.height.toFloat)
    CFuncPtr.fromPtr[CFuncPtr7[P, P, CInt, P, P, CInt, CInt, CInt]](ctx(48)).apply(context, constants, 0, null, cb, 0, 0)

    val rtvs = z(8); rtvs.ptr(0, msaaView)
    CFuncPtr.fromPtr[CFuncPtr4[P, CInt, P, P, CInt]](ctx(33)).apply(context, 1, rtvs, null) // OMSetRenderTargets
    val c = frame.clear.premultiplied
    val color = z(16); color.f32(0, c.r); color.f32(4, c.g); color.f32(8, c.b); color.f32(12, c.a)
    CFuncPtr.fromPtr[CFuncPtr3[P, P, P, CInt]](ctx(50)).apply(context, msaaView, color) // ClearRenderTargetView
    val vp = z(24); vp.f32(8, pw.toFloat); vp.f32(12, ph.toFloat); vp.f32(20, 1f)
    CFuncPtr.fromPtr[CFuncPtr3[P, CInt, P, CInt]](ctx(44)).apply(context, 1, vp) // RSSetViewports
    if bytes > 0 then
      CFuncPtr.fromPtr[CFuncPtr2[P, CInt, CInt]](ctx(24)).apply(context, 4) // IASetPrimitiveTopology(TRIANGLELIST)
      CFuncPtr.fromPtr[CFuncPtr2[P, P, CInt]](ctx(17)).apply(context, inputLayout)
      val bufs = z(8); bufs.ptr(0, vertexBuffer); val strides = z(4); strides.i32(0, Vertex.Floats * 4); val offs = z(4)
      CFuncPtr.fromPtr[CFuncPtr6[P, CInt, CInt, P, P, P, CInt]](ctx(18)).apply(context, 0, 1, bufs, strides, offs) // IASetVertexBuffers
      CFuncPtr.fromPtr[CFuncPtr4[P, P, P, CInt, CInt]](ctx(11)).apply(context, vertexShader, null, 0) // VSSetShader
      CFuncPtr.fromPtr[CFuncPtr4[P, P, P, CInt, CInt]](ctx(9)).apply(context, pixelShader, null, 0)  // PSSetShader
      val cbs = z(8); cbs.ptr(0, constants)
      CFuncPtr.fromPtr[CFuncPtr4[P, CInt, CInt, P, CInt]](ctx(7)).apply(context, 0, 1, cbs) // VSSetConstantBuffers
      val smp = z(8); smp.ptr(0, sampler)
      CFuncPtr.fromPtr[CFuncPtr4[P, CInt, CInt, P, CInt]](ctx(10)).apply(context, 0, 1, smp) // PSSetSamplers
      CFuncPtr.fromPtr[CFuncPtr4[P, P, P, CInt, CInt]](ctx(35)).apply(context, blend, null, 0xFFFFFFFF) // OMSetBlendState
      CFuncPtr.fromPtr[CFuncPtr2[P, P, CInt]](ctx(43)).apply(context, raster) // RSSetState
      val srv = z(8); val rect = z(16)
      frame.calls.foreach { call =>
        val (x0, y0, x1, y1) = call.scissor match
          case Some(r) => (math.max(0, math.floor(r.x0 * frame.scale).toInt), math.max(0, math.floor(r.y0 * frame.scale).toInt),
            math.min(pw, math.ceil(r.x1 * frame.scale).toInt), math.min(ph, math.ceil(r.y1 * frame.scale).toInt))
          case None => (0, 0, pw, ph)
        rect.i32(0, x0); rect.i32(4, y0); rect.i32(8, math.max(x0, x1)); rect.i32(12, math.max(y0, y1))
        CFuncPtr.fromPtr[CFuncPtr3[P, CInt, P, CInt]](ctx(45)).apply(context, 1, rect) // RSSetScissorRects
        srv.ptr(0, call.texture.asInstanceOf[D3DTexture].view)
        CFuncPtr.fromPtr[CFuncPtr4[P, CInt, CInt, P, CInt]](ctx(8)).apply(context, 0, 1, srv) // PSSetShaderResources
        CFuncPtr.fromPtr[CFuncPtr3[P, CInt, CInt, CInt]](ctx(13)).apply(context, call.vertexCount, call.firstVertex) // Draw
      }
    // resolve the multisampled target into the back buffer and present
    val iid = z(16) // IID_ID3D11Texture2D 6f15aaf2-d208-4e89-9ab4-489535d34f9c
    iid.i32(0, 0x6f15aaf2); (iid + 4).asInstanceOf[Ptr[CShort]](0) = 0xd208.toShort; (iid + 6).asInstanceOf[Ptr[CShort]](0) = 0x4e89.toShort
    Seq(0x9a, 0xb4, 0x48, 0x95, 0x35, 0xd3, 0x4f, 0x9c).zipWithIndex.foreach((b, i) => iid(8 + i) = b.toByte)
    val back = z(8)
    check(CFuncPtr.fromPtr[CFuncPtr4[P, CInt, P, P, CInt]](Com.method(swapChain, 9)).apply(swapChain, 0, iid, back), "GetBuffer")
    val bb = back.getPtr(0)
    CFuncPtr.fromPtr[CFuncPtr6[P, P, CInt, P, CInt, CInt, CInt]](ctx(57)).apply(context, bb, 0, msaaTex, 0, 87) // ResolveSubresource
    Com.release(bb)
    CFuncPtr.fromPtr[CFuncPtr3[P, CInt, CInt, CInt]](Com.method(swapChain, 8)).apply(swapChain, 1, 0) // Present(sync interval 1)
    ()
  }

  def close(): Unit = ()

  private def init(): Unit = Zone {
    val hwnd = window.nativeHandle
    val sd = z(72)
    sd.i32(0, 16); sd.i32(4, 16) // initial buffer size; resized on the first frame
    sd.i32(16, 87) // BufferDesc.Format B8G8R8A8_UNORM
    sd.i32(28, 1) // SampleDesc.Count
    sd.i32(36, 0x20) // BUFFERUSAGE_RENDER_TARGET_OUTPUT
    sd.i32(40, 1); sd.ptr(48, hwnd); sd.i32(56, 1) // BufferCount, OutputWindow, Windowed
    sd.i32(60, 0) // SWAPEFFECT_DISCARD
    val lib = DynLib.open("d3d11.dll")
    require(lib != null, "d3d11.dll not found")
    val create = CFuncPtr.fromPtr[CFuncPtr12[P, CInt, P, CInt, P, CInt, CInt, P, P, P, P, P, CInt]](DynLib.symbol(lib, "D3D11CreateDeviceAndSwapChain"))
    val outSc = z(8); val outDev = z(8); val outCtx = z(8); val level = z(4)
    check(create(null, 1, null, 0x20, null, 0, 7, sd, outSc, outDev, level, outCtx), "D3D11CreateDeviceAndSwapChain")
    if sys.env.contains("SCALAUI_DEBUG") then System.err.println(f"d3d11 feature level 0x${level.getI32(0)}%x")
    swapChain = outSc.getPtr(0); device = outDev.getPtr(0); context = outCtx.getPtr(0)

    // shaders
    val compiler = DynLib.open("d3dcompiler_47.dll")
    require(compiler != null, "d3dcompiler_47.dll not found")
    val d3dCompile = CFuncPtr.fromPtr[CFuncPtr11[P, CSize, P, P, P, CString, CString, CInt, CInt, P, P, CInt]](DynLib.symbol(compiler, "D3DCompile"))
    def compile(entry: String, target: String): P =
      val src = toCString(HlslSource)
      val code = z(8); val errs = z(8)
      val hr = d3dCompile(src, HlslSource.length.toCSize, null, null, null, toCString(entry), toCString(target), 0, 0, code, errs)
      if hr < 0 then
        val e = errs.getPtr(0)
        val msg = if e == null then "" else fromCString(CFuncPtr.fromPtr[CFuncPtr1[P, CString]](Com.method(e, 3)).apply(e))
        throw new RuntimeException(s"HLSL compile failed: $msg")
      code.getPtr(0)
    def bytecode(blob: P): (P, CSize) =
      (CFuncPtr.fromPtr[CFuncPtr1[P, P]](Com.method(blob, 3)).apply(blob), CFuncPtr.fromPtr[CFuncPtr1[P, CSize]](Com.method(blob, 4)).apply(blob))
    // feature level 9_x devices (old GPUs, some translation layers) need the level-9 shader profiles
    val profile = if level.getI32(0) >= 0xa000 then "4_0" else "4_0_level_9_3"
    val vsBlob = compile("vs_main", "vs_" + profile); val psBlob = compile("ps_main", "ps_" + profile)
    val (vsCode, vsLen) = bytecode(vsBlob); val (psCode, psLen) = bytecode(psBlob)
    if sys.env.contains("SCALAUI_DEBUG") then System.err.println(s"vs bytecode: len=$vsLen magic=${(0 until 4).map(i => vsCode(i).toChar).mkString}")
    val o1 = z(8)
    check(CFuncPtr.fromPtr[CFuncPtr5[P, P, CSize, P, P, CInt]](Com.method(device, 12)).apply(device, vsCode, vsLen, null, o1), "CreateVertexShader")
    vertexShader = o1.getPtr(0)
    val o2 = z(8)
    check(CFuncPtr.fromPtr[CFuncPtr5[P, P, CSize, P, P, CInt]](Com.method(device, 15)).apply(device, psCode, psLen, null, o2), "CreatePixelShader")
    pixelShader = o2.getPtr(0)

    // input layout: POSITION, TEXCOORD, COLOR
    val elems = z(3 * 32)
    def el(i: Int, name: String, format: Int, offset: Int): Unit =
      elems.ptr(i * 32, toCString(name).asInstanceOf[P]); elems.i32(i * 32 + 12, format); elems.i32(i * 32 + 20, offset)
    el(0, "POSITION", 16, 0); el(1, "TEXCOORD", 16, 8); el(2, "COLOR", 2, 16) // R32G32_FLOAT, R32G32B32A32_FLOAT
    val o3 = z(8)
    check(CFuncPtr.fromPtr[CFuncPtr6[P, P, CInt, P, CSize, P, CInt]](Com.method(device, 11)).apply(device, elems, 3, vsCode, vsLen, o3), "CreateInputLayout")
    inputLayout = o3.getPtr(0)

    // premultiplied alpha blending
    val bd = z(264)
    bd.i32(8, 1); bd.i32(12, 2); bd.i32(16, 6); bd.i32(20, 1) // RT0: enable, src ONE, dest INV_SRC_ALPHA, op ADD
    bd.i32(24, 2); bd.i32(28, 6); bd.i32(32, 1); (bd + 36).asInstanceOf[Ptr[Byte]](0) = 0xF.toByte
    val o4 = z(8); check(CFuncPtr.fromPtr[CFuncPtr3[P, P, P, CInt]](Com.method(device, 20)).apply(device, bd, o4), "CreateBlendState")
    blend = o4.getPtr(0)
    val rd = z(40); rd.i32(0, 3); rd.i32(4, 1); rd.i32(24, 1); rd.i32(28, 1); rd.i32(32, 1) // SOLID, CULL_NONE, DepthClip, Scissor, Multisample
    val o5 = z(8); check(CFuncPtr.fromPtr[CFuncPtr3[P, P, P, CInt]](Com.method(device, 22)).apply(device, rd, o5), "CreateRasterizerState")
    raster = o5.getPtr(0)
    val smd = z(52); smd.i32(0, 0x15) // FILTER_MIN_MAG_LINEAR_MIP_POINT
    smd.i32(4, 3); smd.i32(8, 3); smd.i32(12, 3); smd.i32(20, 1); smd.i32(24, 1) // clamp, MaxAnisotropy 1, COMPARISON_NEVER
    smd.f32(44, 0f); smd.f32(48, 3.4e38f)
    val o6 = z(8); check(CFuncPtr.fromPtr[CFuncPtr3[P, P, P, CInt]](Com.method(device, 23)).apply(device, smd, o6), "CreateSamplerState")
    sampler = o6.getPtr(0)
    val cd = z(24); cd.i32(0, 16); cd.i32(4, 0); cd.i32(8, 4) // 16 bytes, DEFAULT, BIND_CONSTANT_BUFFER
    val o7 = z(8); check(dev3(3, cd, null, o7), "CreateBuffer(constants)")
    constants = o7.getPtr(0)
  }

object D3D11Backend:
  private val Samples = 4

  private[gpu] val HlslSource = """
cbuffer Params : register(b0) { float2 size; float2 pad; };
struct VIn { float2 pos : POSITION; float2 uv : TEXCOORD0; float4 col : COLOR0; };
struct VOut { float4 pos : SV_Position; float2 uv : TEXCOORD0; float4 col : COLOR0; };
VOut vs_main(VIn i) {
  VOut o;
  o.pos = float4(i.pos.x / size.x * 2.0 - 1.0, 1.0 - i.pos.y / size.y * 2.0, 0.0, 1.0);
  o.uv = i.uv; o.col = i.col;
  return o;
}
Texture2D tex : register(t0);
SamplerState smp : register(s0);
float4 ps_main(VOut i) : SV_Target { return i.col * tex.Sample(smp, i.uv); }
"""

  /** A D3D11 backend for `window`'s HWND, or None when D3D11 is unavailable (not Windows, or no usable driver). */
  def create(window: Window): Option[D3D11Backend] =
    if !scala.scalanative.meta.LinktimeInfo.isWindows then None
    else
      try
        val b = new D3D11Backend(window)
        b.init()
        Some(b)
      catch case e: RuntimeException =>
        if sys.env.contains("SCALAUI_DEBUG") then System.err.println(s"backend unavailable: ${e.getMessage}")
        None
