package scalaui.gpu

import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*
import scala.scalanative.libc.{stdlib, string}
import scalaui.window.{DynLib, Window}

/** Direct3D 12 implementation of [[GpuBackend]] for Windows: a flip-model DXGI swap chain on the window's HWND, one root
  * signature (4 root constants for the viewport size, a one-SRV descriptor table and a static linear sampler) and one
  * pipeline state with premultiplied-alpha blending, 4x MSAA resolved into the back buffer. HLSL is compiled at run time
  * through `d3dcompiler_47` (the source is shared with [[D3D11Backend]]).
  *
  * UNVERIFIED on real hardware: written from the D3D12 headers (vtable indices and struct layouts), compiled and linked
  * for Windows and run under Wine (vkd3d on MoltenVK), where it matches the software renderer, but never run on a
  * Windows GPU. Frames are fully synchronised (one fence wait per submit and per texture
  * upload), which is simple and safe rather than fast.
  */
final class D3D12Backend private (window: Window) extends GpuBackend:
  import D3D12Backend.*

  private type P = Ptr[Byte]
  private var factory: P = null
  private var device: P = null
  private var queue: P = null
  private var swapChain: P = null
  private var allocator: P = null
  private var list: P = null
  private var fence: P = null
  private var fenceValue = 0L
  private var event: P = null
  private var rootSig: P = null
  private var pso: P = null
  private var rtvHeap: P = null
  private var srvHeap: P = null
  private var rtvStart = 0L
  private var srvCpuStart = 0L
  private var srvGpuStart = 0L
  private var srvInc = 0
  private var srvNext = 0
  private var msaa: P = null
  private var vertexBuffer: P = null
  private var vertexMapped: P = null
  private var vertexCapacity = 0
  private var targetW = 0
  private var targetH = 0
  private var waitForSingleObject: CFuncPtr2[P, CInt, CInt] = null

  private def check(hr: Int, what: String): Unit = if hr < 0 then throw new RuntimeException(f"D3D12 $what failed: HRESULT 0x$hr%08x")

  private def z(n: Int)(using Zone): P =
    val p = alloc[Byte](n)
    var i = 0; while i < n do { p(i) = 0.toByte; i += 1 }
    p
  extension (p: P)
    private def i32(off: Int, v: Int): Unit = (p + off).asInstanceOf[Ptr[CInt]](0) = v
    private def i64(off: Int, v: Long): Unit = (p + off).asInstanceOf[Ptr[CLongLong]](0) = v
    private def f32(off: Int, v: Float): Unit = (p + off).asInstanceOf[Ptr[CFloat]](0) = v
    private def ptr(off: Int, q: P): Unit = (p + off).asInstanceOf[Ptr[P]](0) = q
    private def getPtr(off: Int): P = (p + off).asInstanceOf[Ptr[P]](0)
    private def getI64(off: Int): Long = (p + off).asInstanceOf[Ptr[CLongLong]](0)

  // ----- small vtable call helpers (indices from d3d12.h / dxgi1_4.h) -----
  /** `device->CreateXxx(desc, riid, out)`. */
  private def devCreate(i: Int, desc: P, iid: P, out: P): Int =
    CFuncPtr.fromPtr[CFuncPtr4[P, P, P, P, CInt]](Com.method(device, i)).apply(device, desc, iid, out)

  /** `GetCPU/GPUDescriptorHandleForHeapStart(this, ret*)`: structs are returned through a hidden pointer on x64. */
  private def heapStart(heap: P, gpu: Boolean)(using Zone): Long =
    val out = z(8)
    CFuncPtr.fromPtr[CFuncPtr2[P, P, P]](Com.method(heap, if gpu then 10 else 9)).apply(heap, out)
    out.getI64(0)

  private def newResource(heapType: Int, dim: Int, w: Long, h: Int, format: Int, samples: Int, layout: Int, flags: Int, state: Int)(using Zone): P =
    val hp = z(20); hp.i32(0, heapType); hp.i32(12, 1); hp.i32(16, 1) // D3D12_HEAP_PROPERTIES: type, node masks
    val rd = z(56) // D3D12_RESOURCE_DESC
    rd.i32(0, dim); rd.i64(16, w); rd.i32(24, h)
    (rd + 28).asInstanceOf[Ptr[CShort]](0) = 1.toShort; (rd + 30).asInstanceOf[Ptr[CShort]](0) = 1.toShort // DepthOrArraySize, MipLevels
    rd.i32(32, format); rd.i32(36, samples); rd.i32(44, layout); rd.i32(48, flags)
    val out = z(8)
    // CreateCommittedResource(heapProps, heapFlags, desc, initialState, clearValue, riid, out)
    check(CFuncPtr.fromPtr[CFuncPtr8[P, P, CInt, P, CInt, P, P, P, CInt]](Com.method(device, 27)).apply(device, hp, 0, rd, state, null, IidResource, out), "CreateCommittedResource")
    out.getPtr(0)

  /** A D3D12_RESOURCE_BARRIER (transition, all subresources). */
  private def barrier(res: P, before: Int, after: Int)(using Zone): P =
    val b = z(32); b.i32(0, 0); b.ptr(8, res); b.i32(16, -1); b.i32(20, before); b.i32(24, after)
    b
  private def resourceBarrier(barriers: P, n: Int): Unit =
    CFuncPtr.fromPtr[CFuncPtr3[P, CInt, P, CInt]](Com.method(list, 26)).apply(list, n, barriers)

  /** Signals the fence and blocks until the GPU has passed it. */
  private def flush(): Unit =
    fenceValue += 1
    check(CFuncPtr.fromPtr[CFuncPtr3[P, P, CLongLong, CInt]](Com.method(queue, 14)).apply(queue, fence, fenceValue), "Signal")
    val done = CFuncPtr.fromPtr[CFuncPtr1[P, CLongLong]](Com.method(fence, 8)).apply(fence)
    if done < fenceValue then
      check(CFuncPtr.fromPtr[CFuncPtr3[P, CLongLong, P, CInt]](Com.method(fence, 9)).apply(fence, fenceValue, event), "SetEventOnCompletion")
      waitForSingleObject(event, -1) // INFINITE

  private def beginList(): Unit =
    check(CFuncPtr.fromPtr[CFuncPtr1[P, CInt]](Com.method(allocator, 8)).apply(allocator), "CommandAllocator.Reset")
    check(CFuncPtr.fromPtr[CFuncPtr3[P, P, P, CInt]](Com.method(list, 10)).apply(list, allocator, null), "CommandList.Reset")

  private def endList(): Unit = Zone {
    check(CFuncPtr.fromPtr[CFuncPtr1[P, CInt]](Com.method(list, 9)).apply(list), "CommandList.Close")
    val ls = z(8); ls.ptr(0, list)
    CFuncPtr.fromPtr[CFuncPtr3[P, CInt, P, CInt]](Com.method(queue, 10)).apply(queue, 1, ls) // ExecuteCommandLists
    ()
  }

  final class D3DTexture(val width: Int, val height: Int) extends Texture:
    private[D3D12Backend] var res: P = null
    private[D3D12Backend] var index = 0
    Zone {
      require(srvNext < SrvCapacity, "too many D3D12 textures")
      res = newResource(1, 3, width.toLong, height, 28, 1, 0, 0, 0x80) // DEFAULT heap, TEXTURE2D, R8G8B8A8_UNORM, PIXEL_SHADER_RESOURCE
      index = srvNext; srvNext += 1
      // CreateShaderResourceView(resource, desc = null (default view), cpuHandle)
      CFuncPtr.fromPtr[CFuncPtr4[P, P, P, CLongLong, CInt]](Com.method(device, 18)).apply(device, res, null, srvCpuStart + index.toLong * srvInc)
      ()
    }
    def update(x: Int, y: Int, w: Int, h: Int, rgba: Array[Byte]): Unit =
      require(rgba.length >= w * h * 4, "pixel buffer too small")
      if w <= 0 || h <= 0 then return
      Zone {
        val pitch = (w * 4 + 255) & ~255 // D3D12_TEXTURE_DATA_PITCH_ALIGNMENT
        val upload = newResource(2, 1, pitch.toLong * h, 1, 0, 1, 1, 0, 0xAC3) // UPLOAD heap, BUFFER, ROW_MAJOR, GENERIC_READ
        val mapped = z(8)
        check(CFuncPtr.fromPtr[CFuncPtr4[P, CInt, P, P, CInt]](Com.method(upload, 8)).apply(upload, 0, z(16), mapped), "Map(upload)") // empty read range
        val base = mapped.getPtr(0)
        var row = 0
        while row < h do
          string.memcpy(base + row * pitch, rgba.atUnsafe(row * w * 4).asInstanceOf[P], (w * 4).toCSize)
          row += 1
        CFuncPtr.fromPtr[CFuncPtr3[P, CInt, P, CInt]](Com.method(upload, 9)).apply(upload, 0, null) // Unmap
        beginList()
        resourceBarrier(barrier(res, 0x80, 0x400), 1) // PIXEL_SHADER_RESOURCE -> COPY_DEST
        val dst = z(48); dst.ptr(0, res); dst.i32(8, 0) // D3D12_TEXTURE_COPY_LOCATION: TYPE_SUBRESOURCE_INDEX 0
        val src = z(48); src.ptr(0, upload); src.i32(8, 1) // TYPE_PLACED_FOOTPRINT
        src.i32(24, 28); src.i32(28, w); src.i32(32, h); src.i32(36, 1); src.i32(40, pitch) // Offset 0, RGBA8, w, h, depth 1, row pitch
        // CopyTextureRegion(dst, x, y, z, src, srcBox)
        CFuncPtr.fromPtr[CFuncPtr7[P, P, CInt, CInt, CInt, P, P, CInt]](Com.method(list, 16)).apply(list, dst, x, y, 0, src, null)
        resourceBarrier(barrier(res, 0x400, 0x80), 1)
        endList()
        flush()
        Com.release(upload)
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
          flush()
          Com.release(msaa)
        // ResizeBuffers(count 0 = keep, w, h, DXGI_FORMAT_UNKNOWN, flags); no back buffer reference is held between frames
        check(CFuncPtr.fromPtr[CFuncPtr6[P, CInt, CInt, CInt, CInt, CInt, CInt]](Com.method(swapChain, 13)).apply(swapChain, 0, w, h, 0, 0), "ResizeBuffers")
        msaa = newResource(1, 3, w.toLong, h, 87, Samples, 0, 1, 0x4) // B8G8R8A8_UNORM, ALLOW_RENDER_TARGET, RENDER_TARGET
        CFuncPtr.fromPtr[CFuncPtr4[P, P, P, CLongLong, CInt]](Com.method(device, 20)).apply(device, msaa, null, rtvStart) // CreateRenderTargetView
        ()
      }
      targetW = w; targetH = h

  def submit(frame: Frame): Unit = submit(frame, None)

  private var lastScale = 0.0
  private var presentedW = 0; private var presentedH = 0

  /** The whole frame is always rendered (the flip-model back buffers do not keep their contents); `damage` only becomes the
    * dirty rectangle of `IDXGISwapChain1::Present1`, so the compositor re-composes just that region. */
  override def submit(frame: Frame, damage: scala.Option[scalaui.geom.Rect]): Unit = Zone {
    val pw = math.max(1, math.round(frame.width * frame.scale).toInt)
    val ph = math.max(1, math.round(frame.height * frame.scale).toInt)
    ensureTargets(pw, ph)
    val bytes = frame.vertices.length * 4
    if bytes > 0 && bytes > vertexCapacity then
      if vertexBuffer != null then Com.release(vertexBuffer) // safe: every submit ends with a fence wait
      var cap = 64 * 1024; while cap < bytes do cap *= 2
      vertexBuffer = newResource(2, 1, cap.toLong, 1, 0, 1, 1, 0, 0xAC3)
      val mapped = z(8)
      check(CFuncPtr.fromPtr[CFuncPtr4[P, CInt, P, P, CInt]](Com.method(vertexBuffer, 8)).apply(vertexBuffer, 0, z(16), mapped), "Map(vertices)")
      vertexMapped = mapped.getPtr(0); vertexCapacity = cap // stays mapped
    if bytes > 0 then string.memcpy(vertexMapped, frame.vertices.atUnsafe(0).asInstanceOf[P], bytes.toCSize)

    beginList()
    val idx = CFuncPtr.fromPtr[CFuncPtr1[P, CInt]](Com.method(swapChain, 36)).apply(swapChain) // IDXGISwapChain3::GetCurrentBackBufferIndex
    val back = z(8)
    check(CFuncPtr.fromPtr[CFuncPtr4[P, CInt, P, P, CInt]](Com.method(swapChain, 9)).apply(swapChain, idx, IidResource, back), "GetBuffer")
    val bb = back.getPtr(0)

    val heaps = z(8); heaps.ptr(0, srvHeap)
    CFuncPtr.fromPtr[CFuncPtr3[P, CInt, P, CInt]](Com.method(list, 28)).apply(list, 1, heaps) // SetDescriptorHeaps
    val rtv = z(8); rtv.i64(0, rtvStart)
    CFuncPtr.fromPtr[CFuncPtr5[P, CInt, P, CInt, P, CInt]](Com.method(list, 46)).apply(list, 1, rtv, 0, null) // OMSetRenderTargets
    val c = frame.clear.premultiplied
    val color = z(16); color.f32(0, c.r); color.f32(4, c.g); color.f32(8, c.b); color.f32(12, c.a)
    CFuncPtr.fromPtr[CFuncPtr5[P, CLongLong, P, CInt, P, CInt]](Com.method(list, 48)).apply(list, rtvStart, color, 0, null) // ClearRenderTargetView
    val vp = z(24); vp.f32(8, pw.toFloat); vp.f32(12, ph.toFloat); vp.f32(20, 1f)
    CFuncPtr.fromPtr[CFuncPtr3[P, CInt, P, CInt]](Com.method(list, 21)).apply(list, 1, vp) // RSSetViewports
    if bytes > 0 then
      CFuncPtr.fromPtr[CFuncPtr2[P, P, CInt]](Com.method(list, 30)).apply(list, rootSig) // SetGraphicsRootSignature
      CFuncPtr.fromPtr[CFuncPtr2[P, P, CInt]](Com.method(list, 25)).apply(list, pso) // SetPipelineState
      CFuncPtr.fromPtr[CFuncPtr2[P, CInt, CInt]](Com.method(list, 20)).apply(list, 4) // IASetPrimitiveTopology(TRIANGLELIST)
      val consts = z(16); consts.f32(0, frame.width.toFloat); consts.f32(4, frame.height.toFloat)
      CFuncPtr.fromPtr[CFuncPtr5[P, CInt, CInt, P, CInt, CInt]](Com.method(list, 36)).apply(list, 0, 4, consts, 0) // SetGraphicsRoot32BitConstants
      val vbv = z(16)
      vbv.i64(0, CFuncPtr.fromPtr[CFuncPtr1[P, CLongLong]](Com.method(vertexBuffer, 11)).apply(vertexBuffer)) // GetGPUVirtualAddress
      vbv.i32(8, bytes); vbv.i32(12, Vertex.Floats * 4)
      CFuncPtr.fromPtr[CFuncPtr4[P, CInt, CInt, P, CInt]](Com.method(list, 44)).apply(list, 0, 1, vbv) // IASetVertexBuffers
      val rect = z(16)
      frame.calls.foreach { call =>
        val (x0, y0, x1, y1) = call.scissor match
          case Some(r) => (math.max(0, math.floor(r.x0 * frame.scale).toInt), math.max(0, math.floor(r.y0 * frame.scale).toInt),
            math.min(pw, math.ceil(r.x1 * frame.scale).toInt), math.min(ph, math.ceil(r.y1 * frame.scale).toInt))
          case None => (0, 0, pw, ph)
        rect.i32(0, x0); rect.i32(4, y0); rect.i32(8, math.max(x0, x1)); rect.i32(12, math.max(y0, y1))
        CFuncPtr.fromPtr[CFuncPtr3[P, CInt, P, CInt]](Com.method(list, 22)).apply(list, 1, rect) // RSSetScissorRects
        val tex = call.texture.asInstanceOf[D3DTexture]
        CFuncPtr.fromPtr[CFuncPtr3[P, CInt, CLongLong, CInt]](Com.method(list, 32)).apply(list, 1, srvGpuStart + tex.index.toLong * srvInc) // SetGraphicsRootDescriptorTable(slot 1)
        CFuncPtr.fromPtr[CFuncPtr5[P, CInt, CInt, CInt, CInt, CInt]](Com.method(list, 12)).apply(list, call.vertexCount, 1, call.firstVertex, 0) // DrawInstanced
      }
    // resolve the multisampled target into the back buffer (RT -> RESOLVE_SOURCE, PRESENT -> RESOLVE_DEST, and back)
    val to1 = z(64)
    string.memcpy(to1, barrier(msaa, 0x4, 0x2000), 32.toCSize); string.memcpy(to1 + 32, barrier(bb, 0, 0x1000), 32.toCSize)
    resourceBarrier(to1, 2)
    CFuncPtr.fromPtr[CFuncPtr6[P, P, CInt, P, CInt, CInt, CInt]](Com.method(list, 19)).apply(list, bb, 0, msaa, 0, 87) // ResolveSubresource
    val to2 = z(64)
    string.memcpy(to2, barrier(msaa, 0x2000, 0x4), 32.toCSize); string.memcpy(to2 + 32, barrier(bb, 0x1000, 0), 32.toCSize)
    resourceBarrier(to2, 2)
    endList()
    val sameAsPrevious = presentedW == pw && presentedH == ph && lastScale == frame.scale
    presentedW = pw; presentedH = ph; lastScale = frame.scale
    val (dx0, dy0, dx1, dy1) = SoftwareRenderer.damagePixels(frame, damage)
    val hr =
      if damage.isDefined && sameAsPrevious && dx1 > dx0 && dy1 > dy0 then
        val rect = z(16); rect.i32(0, dx0); rect.i32(4, dy0); rect.i32(8, dx1); rect.i32(12, dy1) // RECT
        val params = z(32) // DXGI_PRESENT_PARAMETERS: DirtyRectsCount, pDirtyRects, pScrollRect, pScrollOffset
        params.i32(0, 1); params.ptr(8, rect)
        CFuncPtr.fromPtr[CFuncPtr4[P, CInt, CInt, P, CInt]](Com.method(swapChain, 22)).apply(swapChain, 1, 0, params) // IDXGISwapChain1::Present1
      else CFuncPtr.fromPtr[CFuncPtr3[P, CInt, CInt, CInt]](Com.method(swapChain, 8)).apply(swapChain, 1, 0) // Present(sync interval 1)
    flush()
    Com.release(bb)
    if hr < 0 then check(hr, "Present")
  }

  def close(): Unit =
    if queue != null && fence != null then flush()

  private def init(): Unit = Zone {
    val hwnd = window.nativeHandle
    val dxgi = DynLib.open("dxgi.dll"); require(dxgi != null, "dxgi.dll not found")
    val d3d12 = DynLib.open("d3d12.dll"); require(d3d12 != null, "d3d12.dll not found")
    val k32 = DynLib.open("kernel32.dll")
    val createEvent = CFuncPtr.fromPtr[CFuncPtr4[P, CInt, CInt, P, P]](DynLib.symbol(k32, "CreateEventW"))
    waitForSingleObject = CFuncPtr.fromPtr[CFuncPtr2[P, CInt, CInt]](DynLib.symbol(k32, "WaitForSingleObject"))
    event = createEvent(null, 0, 0, null)

    val outF = z(8)
    check(CFuncPtr.fromPtr[CFuncPtr2[P, P, CInt]](DynLib.symbol(dxgi, "CreateDXGIFactory1")).apply(IidFactory4, outF), "CreateDXGIFactory1")
    factory = outF.getPtr(0)
    val outD = z(8)
    // D3D12CreateDevice(adapter = default, minimum feature level 11_0, riid, out)
    check(CFuncPtr.fromPtr[CFuncPtr4[P, CInt, P, P, CInt]](DynLib.symbol(d3d12, "D3D12CreateDevice")).apply(null, 0xb000, IidDevice, outD), "D3D12CreateDevice")
    device = outD.getPtr(0)

    val qd = z(16) // D3D12_COMMAND_QUEUE_DESC: TYPE_DIRECT, normal priority
    val outQ = z(8); check(devCreate(8, qd, IidQueue, outQ), "CreateCommandQueue"); queue = outQ.getPtr(0)

    // swap chain: flip-discard, two buffers, size taken from the window on creation
    val sd = z(48) // DXGI_SWAP_CHAIN_DESC1
    sd.i32(8, 87); sd.i32(16, 1); sd.i32(24, 0x20); sd.i32(28, 2); sd.i32(36, 4) // B8G8R8A8, 1 sample, RENDER_TARGET_OUTPUT, 2 buffers, FLIP_DISCARD
    val outS = z(8)
    check(CFuncPtr.fromPtr[CFuncPtr7[P, P, P, P, P, P, P, CInt]](Com.method(factory, 15)).apply(factory, queue, hwnd, sd, null, null, outS), "CreateSwapChainForHwnd")
    val sc1 = outS.getPtr(0)
    val outS3 = z(8)
    check(CFuncPtr.fromPtr[CFuncPtr3[P, P, P, CInt]](Com.method(sc1, 0)).apply(sc1, IidSwapChain3, outS3), "QueryInterface(IDXGISwapChain3)")
    swapChain = outS3.getPtr(0)
    CFuncPtr.fromPtr[CFuncPtr3[P, P, CInt, CInt]](Com.method(factory, 8)).apply(factory, hwnd, 2) // MakeWindowAssociation(NO_ALT_ENTER)

    val outA = z(8) // CreateCommandAllocator(type, riid, out)
    check(CFuncPtr.fromPtr[CFuncPtr4[P, CInt, P, P, CInt]](Com.method(device, 9)).apply(device, 0, IidAllocator, outA), "CreateCommandAllocator")
    allocator = outA.getPtr(0)
    val outL = z(8) // CreateCommandList(nodeMask, type, allocator, initialPso, riid, out): created recording, closed until the first beginList
    check(CFuncPtr.fromPtr[CFuncPtr7[P, CInt, CInt, P, P, P, P, CInt]](Com.method(device, 12)).apply(device, 0, 0, allocator, null, IidList, outL), "CreateCommandList")
    list = outL.getPtr(0)
    check(CFuncPtr.fromPtr[CFuncPtr1[P, CInt]](Com.method(list, 9)).apply(list), "CommandList.Close")
    val outFe = z(8) // CreateFence(initial value, flags, riid, out)
    check(CFuncPtr.fromPtr[CFuncPtr5[P, CLongLong, CInt, P, P, CInt]](Com.method(device, 36)).apply(device, 0L, 0, IidFence, outFe), "CreateFence")
    fence = outFe.getPtr(0)

    // descriptor heaps: one RTV (the MSAA target), SrvCapacity shader-visible SRVs
    val hd = z(16); hd.i32(0, 2); hd.i32(4, 1) // RTV, 1 descriptor
    val outR = z(8); check(devCreate(14, hd, IidHeap, outR), "CreateDescriptorHeap(rtv)"); rtvHeap = outR.getPtr(0)
    val hd2 = z(16); hd2.i32(0, 0); hd2.i32(4, SrvCapacity); hd2.i32(8, 1) // CBV_SRV_UAV, SHADER_VISIBLE
    val outH = z(8); check(devCreate(14, hd2, IidHeap, outH), "CreateDescriptorHeap(srv)"); srvHeap = outH.getPtr(0)
    rtvStart = heapStart(rtvHeap, false); srvCpuStart = heapStart(srvHeap, false); srvGpuStart = heapStart(srvHeap, true)
    srvInc = CFuncPtr.fromPtr[CFuncPtr2[P, CInt, CInt]](Com.method(device, 15)).apply(device, 0)

    // root signature: [0] 4 root constants (b0, vertex stage), [1] table with one SRV (t0, pixel stage); static linear clamp sampler (s0)
    val range = z(20); range.i32(0, 0); range.i32(4, 1); range.i32(16, -1) // SRV, 1 descriptor, offset APPEND
    val params = z(64)
    params.i32(0, 1); params.i32(8, 0); params.i32(12, 0); params.i32(16, 4); params.i32(24, 1) // 32BIT_CONSTANTS b0 space0, 4 values, VERTEX
    params.i32(32, 0); params.i32(40, 1); params.ptr(48, range); params.i32(56, 5) // DESCRIPTOR_TABLE, 1 range, PIXEL
    val smp = z(52); smp.i32(0, 0x15); smp.i32(4, 3); smp.i32(8, 3); smp.i32(12, 3); smp.i32(20, 1); smp.i32(24, 1) // MIN_MAG_MIP_LINEAR, CLAMP, aniso 1, NEVER
    smp.f32(36, 3.4e38f); smp.i32(48, 5) // MaxLOD, PIXEL visibility
    val rs = z(40); rs.i32(0, 2); rs.ptr(8, params); rs.i32(16, 1); rs.ptr(24, smp); rs.i32(32, 1) // ALLOW_INPUT_ASSEMBLER_INPUT_LAYOUT
    val outBlob = z(8); val outErr = z(8)
    val hrSer = CFuncPtr.fromPtr[CFuncPtr4[P, CInt, P, P, CInt]](DynLib.symbol(d3d12, "D3D12SerializeRootSignature")).apply(rs, 1, outBlob, outErr) // VERSION_1
    if hrSer < 0 then throw new RuntimeException(s"root signature: ${blobText(outErr.getPtr(0))}")
    val rsBlob = outBlob.getPtr(0)
    val outRs = z(8) // CreateRootSignature(nodeMask, bytecode, size, riid, out)
    check(CFuncPtr.fromPtr[CFuncPtr6[P, CInt, P, CSize, P, P, CInt]](Com.method(device, 16)).apply(device, 0, blobPtr(rsBlob), blobSize(rsBlob), IidRootSig, outRs), "CreateRootSignature")
    rootSig = outRs.getPtr(0)

    // shaders (shader model 5.0 is the minimum D3D12 accepts)
    val compiler = DynLib.open("d3dcompiler_47.dll"); require(compiler != null, "d3dcompiler_47.dll not found")
    val d3dCompile = CFuncPtr.fromPtr[CFuncPtr11[P, CSize, P, P, P, CString, CString, CInt, CInt, P, P, CInt]](DynLib.symbol(compiler, "D3DCompile"))
    val source = D3D11Backend.HlslSource
    def compile(entry: String, target: String): P =
      val code = z(8); val errs = z(8)
      val hr = d3dCompile(toCString(source), source.length.toCSize, null, null, null, toCString(entry), toCString(target), 0, 0, code, errs)
      if hr < 0 then throw new RuntimeException(s"HLSL compile failed: ${blobText(errs.getPtr(0))}")
      code.getPtr(0)
    val vs = compile("vs_main", "vs_5_0"); val ps = compile("ps_main", "ps_5_0")

    val elems = z(3 * 32)
    def el(i: Int, name: String, format: Int, offset: Int): Unit =
      elems.ptr(i * 32, toCString(name).asInstanceOf[P]); elems.i32(i * 32 + 12, format); elems.i32(i * 32 + 20, offset)
    el(0, "POSITION", 16, 0); el(1, "TEXCOORD", 16, 8); el(2, "COLOR", 2, 16) // R32G32_FLOAT, R32G32B32A32_FLOAT

    // D3D12_GRAPHICS_PIPELINE_STATE_DESC (x64 layout, 656 bytes)
    val pd = z(656)
    pd.ptr(0, rootSig)
    pd.ptr(8, blobPtr(vs)); pd.i64(16, blobSize(vs).toLong)
    pd.ptr(24, blobPtr(ps)); pd.i64(32, blobSize(ps).toLong)
    // BlendState at 120, RenderTarget[0] at 128: enable, src ONE, dest INV_SRC_ALPHA, ADD (colour and alpha), logic NOOP, mask RGBA
    pd.i32(128, 1); pd.i32(136, 2); pd.i32(140, 6); pd.i32(144, 1); pd.i32(148, 2); pd.i32(152, 6); pd.i32(156, 1); pd.i32(160, 4); pd.i32(164, 0xF)
    pd.i32(448, -1) // SampleMask
    pd.i32(452, 3); pd.i32(456, 1); pd.i32(476, 1); pd.i32(480, 1) // RasterizerState at 452: SOLID, CULL_NONE, DepthClipEnable, MultisampleEnable
    pd.i32(500, 1); pd.i32(504, 8) // DepthStencilState at 496: depth test off; write mask ALL, func ALWAYS
    pd.i32(516, 1); pd.i32(520, 1); pd.i32(524, 1); pd.i32(528, 8) // front face stencil: KEEP x3, ALWAYS
    pd.i32(532, 1); pd.i32(536, 1); pd.i32(540, 1); pd.i32(544, 8) // back face
    pd.ptr(552, elems); pd.i32(560, 3) // InputLayout
    pd.i32(572, 3); pd.i32(576, 1); pd.i32(580, 87) // TRIANGLE topology type, 1 render target, B8G8R8A8_UNORM
    pd.i32(616, Samples); pd.i32(620, 0) // SampleDesc
    val outP = z(8); check(devCreate(10, pd, IidPso, outP), "CreateGraphicsPipelineState"); pso = outP.getPtr(0)
  }

object D3D12Backend:
  private val Samples = 4
  private val SrvCapacity = 4096

  private def blobPtr(b: Ptr[Byte]): Ptr[Byte] = CFuncPtr.fromPtr[CFuncPtr1[Ptr[Byte], Ptr[Byte]]](Com.method(b, 3)).apply(b)
  private def blobSize(b: Ptr[Byte]): CSize = CFuncPtr.fromPtr[CFuncPtr1[Ptr[Byte], CSize]](Com.method(b, 4)).apply(b)
  private def blobText(b: Ptr[Byte]): String = if b == null then "" else fromCString(blobPtr(b))

  /** A heap-allocated GUID in the order Windows stores it (`Data1`, `Data2`, `Data3`, then 8 bytes). */
  private def guid(d1: Int, d2: Int, d3: Int, d4: Long): Ptr[Byte] =
    val p = stdlib.malloc(16.toCSize)
    p.asInstanceOf[Ptr[CInt]](0) = d1
    (p + 4).asInstanceOf[Ptr[CShort]](0) = d2.toShort
    (p + 6).asInstanceOf[Ptr[CShort]](0) = d3.toShort
    var i = 0
    while i < 8 do { p(8 + i) = ((d4 >>> (56 - 8 * i)) & 0xff).toByte; i += 1 }
    p

  private lazy val IidFactory4 = guid(0x1bc6ea02, 0xef36, 0x464f, 0xbf0c21ca39e5168aL)
  private lazy val IidSwapChain3 = guid(0x94d99bdb, 0xf1f8, 0x4ab0, 0xb2367da0170edab1L)
  private lazy val IidDevice = guid(0x189819f1, 0x1db6, 0x4b57, 0xbe541821339b85f7L)
  private lazy val IidQueue = guid(0x0ec870a6, 0x5d7e, 0x4c22, 0x8cfc5baae07616edL)
  private lazy val IidAllocator = guid(0x6102dee4, 0xaf59, 0x4b09, 0xb999b44d73f09b24L)
  private lazy val IidList = guid(0x5b160d0f, 0xac1b, 0x4185, 0x8ba8b3ae42a5a455L)
  private lazy val IidFence = guid(0x0a753dcf, 0xc4d8, 0x4b91, 0xadf6be5a60d95a76L)
  private lazy val IidHeap = guid(0x8efb471d, 0x616c, 0x4f49, 0x90f7127bb763fa51L)
  private lazy val IidRootSig = guid(0xc54a6b66, 0x72df, 0x4ee8, 0x8be5a946a1429214L)
  private lazy val IidPso = guid(0x765a30f3, 0xf624, 0x4c6f, 0xa828ace948622445L)
  private lazy val IidResource = guid(0x696442be, 0xa72e, 0x4059, 0xbc795b5c98040fadL)

  /** True when `d3d12.dll` and `dxgi.dll` can be loaded (Windows with a D3D12 runtime, or Wine with vkd3d). */
  def available: Boolean =
    if !scala.scalanative.meta.LinktimeInfo.isWindows then false
    else DynLib.open("d3d12.dll") != null && DynLib.open("dxgi.dll") != null

  /** A D3D12 backend for `window`'s HWND, or None when D3D12 is unavailable (not Windows, or no usable driver). */
  def create(window: Window): Option[D3D12Backend] =
    if !available then None
    else
      try
        val b = new D3D12Backend(window)
        b.init()
        Some(b)
      catch case e: RuntimeException =>
        if sys.env.contains("SCALAUI_DEBUG") then System.err.println(s"backend unavailable: ${e.getMessage}")
        None
