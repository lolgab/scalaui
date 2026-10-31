package scalaui.gpu

import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*
import scala.scalanative.libc.string
import scalaui.window.Window

/** Vulkan implementation of [[GpuBackend]]: the same textured-triangle pipeline as the Metal backend (4x MSAA, premultiplied
  * alpha), rendered into an offscreen image that is read back and handed to `Window.present`. Because no window-system
  * integration is involved it works the same on Linux (including the pure-Scala X11 client), Windows and macOS (MoltenVK).
  * Without a window the finished frame is available from [[lastFrame]].
  */
final class VulkanBackend private (api: VulkanApi, window: Option[Window]) extends GpuBackend:
  import VulkanBackend.*

  private val L = VkLayout

  private var instance = 0L
  private var physical = 0L
  private var device = 0L
  private var queue = 0L
  private var queueFamily = 0
  private var memTypes = Vector.empty[(Int, Int)] // (propertyFlags, heapIndex)

  /** Last rendered frame (premultiplied RGBA8, width, height); filled for every submit. */
  var lastFrame: Option[(Array[Byte], Int, Int)] = None
  /** Name of the GPU in use. */
  var deviceName = ""

  // ---------- helpers ----------
  private def check(r: Int, what: String): Unit = if r != 0 then throw new RuntimeException(s"Vulkan $what failed: VkResult $r")

  private def z(n: Int)(using Zone): Ptr[Byte] =
    val p = alloc[Byte](n)
    var i = 0; while i < n do { p(i) = 0.toByte; i += 1 }
    p
  extension (p: Ptr[Byte])
    private def i32(off: Int, v: Int): Unit = (p + off).asInstanceOf[Ptr[CInt]](0) = v
    private def i64(off: Int, v: Long): Unit = (p + off).asInstanceOf[Ptr[CLongLong]](0) = v
    private def f32(off: Int, v: Float): Unit = (p + off).asInstanceOf[Ptr[CFloat]](0) = v
    private def ptr(off: Int, q: Ptr[Byte]): Unit = (p + off).asInstanceOf[Ptr[Ptr[Byte]]](0) = q
    private def getI32(off: Int): Int = (p + off).asInstanceOf[Ptr[CInt]](0)
    private def getI64(off: Int): Long = (p + off).asInstanceOf[Ptr[CLongLong]](0)

  /** Runs a `vkCreate*(device, info, null, &out)` style call and returns the created handle. */
  private def create(what: String)(call: Ptr[Byte] => Int)(using Zone): Long =
    val out = z(8)
    check(call(out), what)
    out.getI64(0)

  private def memoryType(bits: Int, wanted: Int): Int =
    memTypes.indices.find(i => (bits & (1 << i)) != 0 && (memTypes(i)._1 & wanted) == wanted)
      .getOrElse(throw new RuntimeException("no suitable Vulkan memory type"))

  private final case class Buf(handle: Long, memory: Long, size: Long, mapped: Ptr[Byte])

  private def newBuffer(size: Long, usage: Int, hostVisible: Boolean): Buf = Zone {
    val ci = z(L.BufferCreateInfo.SIZE)
    ci.i32(L.BufferCreateInfo.sType, 12); ci.i64(L.BufferCreateInfo.size, size); ci.i32(L.BufferCreateInfo.usage, usage)
    val buf = create("vkCreateBuffer")(o => api.createBuffer(device, ci, null, o))
    val req = z(L.MemoryRequirements.SIZE)
    api.getBufferMemoryRequirements(device, buf, req)
    val flags = if hostVisible then 2 | 4 else 1 // HOST_VISIBLE | HOST_COHERENT, or DEVICE_LOCAL
    val ai = z(L.MemoryAllocateInfo.SIZE)
    ai.i32(L.MemoryAllocateInfo.sType, 5); ai.i64(L.MemoryAllocateInfo.allocationSize, req.getI64(L.MemoryRequirements.size))
    ai.i32(L.MemoryAllocateInfo.memoryTypeIndex, memoryType(req.getI32(L.MemoryRequirements.memoryTypeBits), flags))
    val mem = create("vkAllocateMemory")(o => api.allocateMemory(device, ai, null, o))
    check(api.bindBufferMemory(device, buf, mem, 0L), "vkBindBufferMemory")
    val mapped =
      if hostVisible then
        val out = z(8)
        check(api.mapMemory(device, mem, 0L, size, 0L, out), "vkMapMemory")
        out.asInstanceOf[Ptr[Ptr[Byte]]](0)
      else null
    Buf(buf, mem, size, mapped)
  }

  private def freeBuffer(b: Buf): Unit = { api.destroyBuffer(device, b.handle, null); api.freeMemory(device, b.memory, null) }

  private final case class Img(handle: Long, memory: Long, view: Long)

  private def newImage(w: Int, h: Int, usage: Int, samples: Int): Img = Zone {
    val ci = z(L.ImageCreateInfo.SIZE)
    ci.i32(L.ImageCreateInfo.sType, 14); ci.i32(L.ImageCreateInfo.imageType, 1) // 2D
    ci.i32(L.ImageCreateInfo.format, Format)
    ci.i32(L.ImageCreateInfo.extent, w); ci.i32(L.ImageCreateInfo.extent + 4, h); ci.i32(L.ImageCreateInfo.extent + 8, 1)
    ci.i32(L.ImageCreateInfo.mipLevels, 1); ci.i32(L.ImageCreateInfo.arrayLayers, 1)
    ci.i32(L.ImageCreateInfo.samples, samples); ci.i32(L.ImageCreateInfo.tiling, 0) // optimal
    ci.i32(L.ImageCreateInfo.usage, usage); ci.i32(L.ImageCreateInfo.initialLayout, 0)
    val img = create("vkCreateImage")(o => api.createImage(device, ci, null, o))
    val req = z(L.MemoryRequirements.SIZE)
    api.getImageMemoryRequirements(device, img, req)
    val ai = z(L.MemoryAllocateInfo.SIZE)
    ai.i32(L.MemoryAllocateInfo.sType, 5); ai.i64(L.MemoryAllocateInfo.allocationSize, req.getI64(L.MemoryRequirements.size))
    ai.i32(L.MemoryAllocateInfo.memoryTypeIndex, memoryType(req.getI32(L.MemoryRequirements.memoryTypeBits), 1))
    val mem = create("vkAllocateMemory")(o => api.allocateMemory(device, ai, null, o))
    check(api.bindImageMemory(device, img, mem, 0L), "vkBindImageMemory")
    val vi = z(L.ImageViewCreateInfo.SIZE)
    vi.i32(L.ImageViewCreateInfo.sType, 15); vi.i64(L.ImageViewCreateInfo.image, img); vi.i32(L.ImageViewCreateInfo.viewType, 1)
    vi.i32(L.ImageViewCreateInfo.format, Format)
    vi.i32(L.ImageViewCreateInfo.subresourceRange + L.ImageSubresourceRange.aspectMask, 1)
    vi.i32(L.ImageViewCreateInfo.subresourceRange + L.ImageSubresourceRange.levelCount, 1)
    vi.i32(L.ImageViewCreateInfo.subresourceRange + L.ImageSubresourceRange.layerCount, 1)
    val view = create("vkCreateImageView")(o => api.createImageView(device, vi, null, o))
    Img(img, mem, view)
  }

  private def freeImage(i: Img): Unit = { api.destroyImageView(device, i.view, null); api.destroyImage(device, i.handle, null); api.freeMemory(device, i.memory, null) }

  // ---------- one-time commands (texture uploads) ----------
  private var commandPool = 0L
  private var frameCmd = 0L
  private var uploadCmd = 0L
  private var fence = 0L

  private def barrier(cmd: Long, image: Long, oldLayout: Int, newLayout: Int, srcAccess: Int, dstAccess: Int, srcStage: Int, dstStage: Int): Unit = Zone {
    val b = z(L.ImageMemoryBarrier.SIZE)
    b.i32(L.ImageMemoryBarrier.sType, 45); b.i32(L.ImageMemoryBarrier.srcAccessMask, srcAccess); b.i32(L.ImageMemoryBarrier.dstAccessMask, dstAccess)
    b.i32(L.ImageMemoryBarrier.oldLayout, oldLayout); b.i32(L.ImageMemoryBarrier.newLayout, newLayout)
    b.i32(L.ImageMemoryBarrier.srcQueueFamilyIndex, -1); b.i32(L.ImageMemoryBarrier.dstQueueFamilyIndex, -1) // QUEUE_FAMILY_IGNORED
    b.i64(L.ImageMemoryBarrier.image, image)
    val r = L.ImageMemoryBarrier.subresourceRange
    b.i32(r + L.ImageSubresourceRange.aspectMask, 1); b.i32(r + L.ImageSubresourceRange.levelCount, 1); b.i32(r + L.ImageSubresourceRange.layerCount, 1)
    api.cmdPipelineBarrier(cmd, srcStage, dstStage, 0L, 0L, null, 0L, null, 1L, b)
  }

  private def begin(cmd: Long, oneTime: Boolean): Unit = Zone {
    check(api.resetCommandBuffer(cmd, 0L), "vkResetCommandBuffer")
    val bi = z(L.CommandBufferBeginInfo.SIZE)
    bi.i32(L.CommandBufferBeginInfo.sType, 42); bi.i32(L.CommandBufferBeginInfo.flags, if oneTime then 1 else 0)
    check(api.beginCommandBuffer(cmd, bi), "vkBeginCommandBuffer")
  }

  private def submitAndWait(cmd: Long): Unit = Zone {
    check(api.endCommandBuffer(cmd), "vkEndCommandBuffer")
    val cb = z(8); cb.i64(0, cmd)
    val si = z(L.SubmitInfo.SIZE)
    si.i32(L.SubmitInfo.sType, 4); si.i32(L.SubmitInfo.commandBufferCount, 1); si.ptr(L.SubmitInfo.pCommandBuffers, cb)
    val f = z(8); f.i64(0, fence)
    check(api.resetFences(device, 1L, f), "vkResetFences")
    check(api.queueSubmit(queue, 1L, si, fence), "vkQueueSubmit")
    check(api.waitForFences(device, 1L, f, 1L, -1L), "vkWaitForFences")
  }

  // ---------- textures ----------
  private var descriptorPool = 0L
  private var descriptorsLeft = 0
  private var setLayout = 0L
  private var sampler = 0L

  private def allocateSet(): Long = Zone {
    def tryAlloc(): Option[Long] =
      if descriptorPool == 0L || descriptorsLeft == 0 then None
      else
        val layouts = z(8); layouts.i64(0, setLayout)
        val ai = z(L.DescriptorSetAllocateInfo.SIZE)
        ai.i32(L.DescriptorSetAllocateInfo.sType, 34); ai.i64(L.DescriptorSetAllocateInfo.descriptorPool, descriptorPool)
        ai.i32(L.DescriptorSetAllocateInfo.descriptorSetCount, 1); ai.ptr(L.DescriptorSetAllocateInfo.pSetLayouts, layouts)
        val out = z(8)
        if api.allocateDescriptorSets(device, ai, out) == 0 then { descriptorsLeft -= 1; Some(out.getI64(0)) } else None
    tryAlloc().getOrElse {
      val size = z(L.DescriptorPoolSize.SIZE); size.i32(L.DescriptorPoolSize.`type`, 1); size.i32(L.DescriptorPoolSize.descriptorCount, 256)
      val pi = z(L.DescriptorPoolCreateInfo.SIZE)
      pi.i32(L.DescriptorPoolCreateInfo.sType, 33); pi.i32(L.DescriptorPoolCreateInfo.maxSets, 256)
      pi.i32(L.DescriptorPoolCreateInfo.poolSizeCount, 1); pi.ptr(L.DescriptorPoolCreateInfo.pPoolSizes, size)
      descriptorPool = create("vkCreateDescriptorPool")(o => api.createDescriptorPool(device, pi, null, o))
      descriptorsLeft = 256
      tryAlloc().getOrElse(throw new RuntimeException("vkAllocateDescriptorSets failed"))
    }
  }

  final class VkTexture(val width: Int, val height: Int) extends Texture:
    private[VulkanBackend] val img = newImage(width, height, 2 | 4, 1) // TRANSFER_DST | SAMPLED
    private[VulkanBackend] val set = allocateSet()
    private var layoutReady = false
    Zone {
      val ii = z(L.DescriptorImageInfo.SIZE)
      ii.i64(L.DescriptorImageInfo.sampler, sampler); ii.i64(L.DescriptorImageInfo.imageView, img.view); ii.i32(L.DescriptorImageInfo.imageLayout, 5)
      val w = z(L.WriteDescriptorSet.SIZE)
      w.i32(L.WriteDescriptorSet.sType, 35); w.i64(L.WriteDescriptorSet.dstSet, set); w.i32(L.WriteDescriptorSet.descriptorCount, 1)
      w.i32(L.WriteDescriptorSet.descriptorType, 1); w.ptr(L.WriteDescriptorSet.pImageInfo, ii)
      api.updateDescriptorSets(device, 1L, w, 0L, null)
    }

    def update(x: Int, y: Int, w: Int, h: Int, rgba: Array[Byte]): Unit =
      require(rgba.length >= w * h * 4, "pixel buffer too small")
      if w <= 0 || h <= 0 then return
      val staging = newBuffer(w.toLong * h * 4, 1, hostVisible = true)
      string.memcpy(staging.mapped, rgba.atUnsafe(0), (w * h * 4).toCSize)
      begin(uploadCmd, oneTime = true)
      barrier(uploadCmd, img.handle, if layoutReady then 5 else 0, 7, if layoutReady then 0x20 else 0, 0x1000, if layoutReady then 0x80 else 0x1, 0x1000)
      Zone {
        val r = z(L.BufferImageCopy.SIZE)
        r.i32(L.BufferImageCopy.imageSubresource + L.ImageSubresourceLayers.aspectMask, 1)
        r.i32(L.BufferImageCopy.imageSubresource + L.ImageSubresourceLayers.layerCount, 1)
        r.i32(L.BufferImageCopy.imageOffset, x); r.i32(L.BufferImageCopy.imageOffset + 4, y)
        r.i32(L.BufferImageCopy.imageExtent, w); r.i32(L.BufferImageCopy.imageExtent + 4, h); r.i32(L.BufferImageCopy.imageExtent + 8, 1)
        api.cmdCopyBufferToImage(uploadCmd, staging.handle, img.handle, 7L, 1L, r)
      }
      barrier(uploadCmd, img.handle, 7, 5, 0x1000, 0x20, 0x1000, 0x80) // -> shader read
      submitAndWait(uploadCmd)
      freeBuffer(staging)
      layoutReady = true

  lazy val whiteTexture: Texture = createTexture(1, 1, Array(-1, -1, -1, -1))

  def createTexture(width: Int, height: Int, rgba: Array[Byte]): Texture =
    val t = new VkTexture(width, height)
    t.update(0, 0, width, height, if rgba != null then rgba else new Array[Byte](width * height * 4))
    t

  // ---------- render targets ----------
  private var renderPass = 0L     // clears the multisampled image (full frame)
  private var renderPassLoad = 0L // keeps its contents (partial frame)
  private var pipelineLayout = 0L
  private var pipeline = 0L
  private var targetW = 0
  private var targetH = 0
  private var msaa: Img = null
  private var resolve: Img = null
  private var framebuffer = 0L
  private var readback: Buf = null
  private var vertexBuf: Buf = null
  private var pixels: Array[Byte] = new Array[Byte](0) // CPU copy of the resolved frame, patched in place by partial frames
  private var haveFrame = false // the multisampled image holds a complete previous frame of the current size and scale
  private var lastScale = 0.0

  private def ensureTargets(w: Int, h: Int): Unit =
    if w != targetW || h != targetH then
      if targetW != 0 then
        api.queueWaitIdle(queue)
        api.destroyFramebuffer(device, framebuffer, null); freeImage(msaa); freeImage(resolve); freeBuffer(readback)
      msaa = newImage(w, h, 0x10, Samples)        // COLOR_ATTACHMENT; persistent so partial frames can load the previous samples
      resolve = newImage(w, h, 0x10 | 1, 1)       // COLOR_ATTACHMENT | TRANSFER_SRC
      readback = newBuffer(w.toLong * h * 4, 2, hostVisible = true)
      Zone {
        val views = z(16); views.i64(0, msaa.view); views.i64(8, resolve.view)
        val fi = z(L.FramebufferCreateInfo.SIZE)
        fi.i32(L.FramebufferCreateInfo.sType, 37); fi.i64(L.FramebufferCreateInfo.renderPass, renderPass)
        fi.i32(L.FramebufferCreateInfo.attachmentCount, 2); fi.ptr(L.FramebufferCreateInfo.pAttachments, views)
        fi.i32(L.FramebufferCreateInfo.width, w); fi.i32(L.FramebufferCreateInfo.height, h); fi.i32(L.FramebufferCreateInfo.layers, 1)
        framebuffer = create("vkCreateFramebuffer")(o => api.createFramebuffer(device, fi, null, o))
      }
      targetW = w; targetH = h; haveFrame = false; pixels = new Array[Byte](w * h * 4)

  def submit(frame: Frame): Unit = submit(frame, None)

  /** With `damage`, only that rectangle is redrawn (the previous multisampled image is loaded, the rectangle cleared and
    * redrawn with scissors), copied back and presented; the rest of the previous frame is reused. The image of the last frame in
    * [[lastFrame]] is then patched in place. Falls back to a full frame on the first frame and after a size or scale change. */
  override def submit(frame: Frame, damage: Option[scalaui.geom.Rect]): Unit =
    val pw = math.max(1, math.round(frame.width * frame.scale).toInt)
    val ph = math.max(1, math.round(frame.height * frame.scale).toInt)
    ensureTargets(pw, ph)
    val partial = damage.isDefined && haveFrame && lastScale == frame.scale
    lastScale = frame.scale
    val (rx0, ry0, rx1, ry1) = if partial then SoftwareRenderer.damagePixels(frame, damage) else (0, 0, pw, ph)
    if partial && (rx0 >= rx1 || ry0 >= ry1) then return // nothing is visible in the damage rectangle
    val bytes = frame.vertices.length * 4L
    if bytes > 0 && (vertexBuf == null || vertexBuf.size < bytes) then
      if vertexBuf != null then { api.queueWaitIdle(queue); freeBuffer(vertexBuf) }
      var cap = 64L * 1024; while cap < bytes do cap *= 2
      vertexBuf = newBuffer(cap, 0x80, hostVisible = true) // VERTEX_BUFFER
    if bytes > 0 then
      string.memcpy(vertexBuf.mapped, frame.vertices.atUnsafe(0).asInstanceOf[Ptr[Byte]], bytes.toCSize)
    Zone {
      begin(frameCmd, oneTime = true)
      val clear = z(16)
      val c = frame.clear.premultiplied
      clear.f32(0, c.r); clear.f32(4, c.g); clear.f32(8, c.b); clear.f32(12, c.a)
      val clears = z(32); // two attachments; the resolve target is not cleared (loadOp DONT_CARE) but needs a slot
      var k = 0; while k < 16 do { clears(k) = clear(k); k += 1 }
      val rb = z(L.RenderPassBeginInfo.SIZE)
      rb.i32(L.RenderPassBeginInfo.sType, 43); rb.i64(L.RenderPassBeginInfo.renderPass, if partial then renderPassLoad else renderPass); rb.i64(L.RenderPassBeginInfo.framebuffer, framebuffer)
      rb.i32(L.RenderPassBeginInfo.renderArea + L.Rect2D.extent, pw); rb.i32(L.RenderPassBeginInfo.renderArea + L.Rect2D.extent + 4, ph)
      rb.i32(L.RenderPassBeginInfo.clearValueCount, 2); rb.ptr(L.RenderPassBeginInfo.pClearValues, clears)
      api.cmdBeginRenderPass(frameCmd, rb, 0L)
      if partial then
        // clear just the damaged rectangle of the retained multisampled image
        val ca = z(24) // VkClearAttachment: aspectMask, colorAttachment, VkClearValue (4 floats)
        ca.i32(0, 1); ca.i32(4, 0); var q = 0; while q < 16 do { ca(8 + q) = clear(q); q += 1 }
        val cr = z(24) // VkClearRect: rect2D (offset, extent), baseArrayLayer, layerCount
        cr.i32(0, rx0); cr.i32(4, ry0); cr.i32(8, rx1 - rx0); cr.i32(12, ry1 - ry0); cr.i32(16, 0); cr.i32(20, 1)
        api.cmdClearAttachments(frameCmd, 1L, ca, 1L, cr)
      if bytes > 0 then
        api.cmdBindPipeline(frameCmd, 0L, pipeline)
        val bufs = z(8); bufs.i64(0, vertexBuf.handle); val offs = z(8)
        api.cmdBindVertexBuffers(frameCmd, 0L, 1L, bufs, offs)
        val size = z(8); size.f32(0, frame.width.toFloat); size.f32(4, frame.height.toFloat)
        api.cmdPushConstants(frameCmd, pipelineLayout, 1L, 0L, 8L, size)
        val vp = z(L.Viewport.SIZE)
        vp.f32(L.Viewport.width, pw.toFloat); vp.f32(L.Viewport.height, ph.toFloat); vp.f32(L.Viewport.maxDepth, 1f)
        api.cmdSetViewport(frameCmd, 0L, 1L, vp)
        val sc = z(L.Rect2D.SIZE)
        val sets = z(8)
        frame.calls.foreach { call =>
          val (sx0, sy0, sw0, sh0) = call.scissor match
            case Some(r) =>
              val x0 = math.max(0.0, math.floor(r.x0 * frame.scale)); val y0 = math.max(0.0, math.floor(r.y0 * frame.scale))
              val x1 = math.min(pw.toDouble, math.ceil(r.x1 * frame.scale)); val y1 = math.min(ph.toDouble, math.ceil(r.y1 * frame.scale))
              (x0.toInt, y0.toInt, math.max(0.0, x1 - x0).toInt, math.max(0.0, y1 - y0).toInt)
            case None => (0, 0, pw, ph)
          // partial frame: draw only inside the damage rectangle
          val (sx, sy, sw, sh) = if !partial then (sx0, sy0, sw0, sh0) else
            val ix0 = math.max(sx0, rx0); val iy0 = math.max(sy0, ry0); val ix1 = math.min(sx0 + sw0, rx1); val iy1 = math.min(sy0 + sh0, ry1)
            (ix0, iy0, math.max(0, ix1 - ix0), math.max(0, iy1 - iy0))
          sc.i32(L.Rect2D.offset, sx); sc.i32(L.Rect2D.offset + 4, sy); sc.i32(L.Rect2D.extent, sw); sc.i32(L.Rect2D.extent + 4, sh)
          if sw > 0 && sh > 0 then
            api.cmdSetScissor(frameCmd, 0L, 1L, sc)
            sets.i64(0, call.texture.asInstanceOf[VkTexture].set)
            api.cmdBindDescriptorSets(frameCmd, 0L, pipelineLayout, 0L, 1L, sets, 0L, null)
            api.cmdDraw(frameCmd, call.vertexCount.toLong, 1L, call.firstVertex.toLong, 0L)
        }
      api.cmdEndRenderPass(frameCmd) // the pass leaves the resolved image in TRANSFER_SRC_OPTIMAL
      val copy = z(L.BufferImageCopy.SIZE)
      copy.i32(L.BufferImageCopy.imageSubresource + L.ImageSubresourceLayers.aspectMask, 1)
      copy.i32(L.BufferImageCopy.imageSubresource + L.ImageSubresourceLayers.layerCount, 1)
      // read back only the redrawn rectangle (the whole image for a full frame)
      copy.i64(L.BufferImageCopy.bufferOffset, (ry0.toLong * pw + rx0) * 4)
      copy.i32(L.BufferImageCopy.bufferRowLength, pw); copy.i32(L.BufferImageCopy.bufferImageHeight, ph)
      copy.i32(L.BufferImageCopy.imageOffset, rx0); copy.i32(L.BufferImageCopy.imageOffset + 4, ry0)
      copy.i32(L.BufferImageCopy.imageExtent, rx1 - rx0); copy.i32(L.BufferImageCopy.imageExtent + 4, ry1 - ry0); copy.i32(L.BufferImageCopy.imageExtent + 8, 1)
      api.cmdCopyImageToBuffer(frameCmd, resolve.handle, 6L, readback.handle, 1L, copy)
      submitAndWait(frameCmd)
    }
    haveFrame = true
    if partial then
      var y = ry0
      while y < ry1 do
        val o = (y.toLong * pw + rx0) * 4
        string.memcpy(pixels.atUnsafe(o.toInt), readback.mapped + o, ((rx1 - rx0) * 4).toCSize)
        y += 1
      lastFrame = Some((pixels, pw, ph))
      window.foreach(_.present(pixels, pw, ph, rx0, ry0, rx1 - rx0, ry1 - ry0))
    else
      // a full frame gets a fresh array: callers may keep earlier ones
      val out = new Array[Byte](pw * ph * 4)
      string.memcpy(out.atUnsafe(0), readback.mapped, out.length.toCSize)
      pixels = out
      lastFrame = Some((out, pw, ph))
      window.foreach(_.present(out, pw, ph))

  def close(): Unit =
    if device != 0L then api.queueWaitIdle(queue)

  // ---------- initialisation ----------
  private def init(): Unit = Zone {
    val isMac = scala.scalanative.meta.LinktimeInfo.isMac
    val instExts = if isMac then Seq("VK_KHR_portability_enumeration", "VK_KHR_get_physical_device_properties2") else Seq.empty
    def strings(xs: Seq[String]): Ptr[Byte] =
      val arr = z(8 * xs.length.max(1))
      xs.zipWithIndex.foreach((s, i) => arr.ptr(8 * i, toCString(s).asInstanceOf[Ptr[Byte]]))
      arr
    val app = z(L.ApplicationInfo.SIZE)
    app.i32(L.ApplicationInfo.sType, 0); app.i32(L.ApplicationInfo.apiVersion, (1 << 22) | (1 << 12)) // 1.1... and up
    app.ptr(L.ApplicationInfo.pApplicationName, toCString("scalaui").asInstanceOf[Ptr[Byte]])
    val ci = z(L.InstanceCreateInfo.SIZE)
    ci.i32(L.InstanceCreateInfo.sType, 1); ci.i32(L.InstanceCreateInfo.flags, if isMac then 1 else 0) // PORTABILITY_ENUMERATION
    ci.ptr(L.InstanceCreateInfo.pApplicationInfo, app)
    val layers = sys.env.get("SCALAUI_VULKAN_VALIDATION").filter(_ != "0").toSeq.map(_ => "VK_LAYER_KHRONOS_validation")
    ci.i32(L.InstanceCreateInfo.enabledLayerCount, layers.length); ci.ptr(L.InstanceCreateInfo.ppEnabledLayerNames, strings(layers))
    ci.i32(L.InstanceCreateInfo.enabledExtensionCount, instExts.length); ci.ptr(L.InstanceCreateInfo.ppEnabledExtensionNames, strings(instExts))
    instance = create("vkCreateInstance")(o => api.createInstance(ci, null, o))
    api.loadInstance(instance)

    // physical device: prefer discrete, then integrated
    val count = z(4)
    check(api.enumeratePhysicalDevices(instance, count, null), "vkEnumeratePhysicalDevices")
    val n = count.getI32(0)
    if n == 0 then throw new RuntimeException("no Vulkan devices")
    val devs = z(8 * n)
    check(api.enumeratePhysicalDevices(instance, count, devs), "vkEnumeratePhysicalDevices")
    val props = z(L.PhysicalDeviceProperties.SIZE)
    def rank(d: Long): Int =
      api.getPhysicalDeviceProperties(d, props)
      props.getI32(L.PhysicalDeviceProperties.deviceType) match { case 2 => 3; case 1 => 2; case 3 => 1; case _ => 0 }
    val dl = (0 until n).map(i => devs.getI64(8 * i)).maxBy(rank)
    physical = dl
    api.getPhysicalDeviceProperties(physical, props)
    deviceName = fromCString((props + L.PhysicalDeviceProperties.deviceName).asInstanceOf[CString])

    val mp = z(L.PhysicalDeviceMemoryProperties.SIZE)
    api.getMemoryProperties(physical, mp)
    memTypes = Vector.tabulate(mp.getI32(L.PhysicalDeviceMemoryProperties.memoryTypeCount)) { i =>
      val o = L.PhysicalDeviceMemoryProperties.memoryTypes + i * L.MemoryType.SIZE
      (mp.getI32(o + L.MemoryType.propertyFlags), mp.getI32(o + L.MemoryType.heapIndex))
    }

    val qc = z(4)
    api.getQueueFamilyProperties(physical, qc, null)
    val qn = qc.getI32(0)
    val qp = z(L.QueueFamilyProperties.SIZE * qn)
    api.getQueueFamilyProperties(physical, qc, qp)
    queueFamily = (0 until qn).find(i => (qp.getI32(i * L.QueueFamilyProperties.SIZE + L.QueueFamilyProperties.queueFlags) & 1) != 0)
      .getOrElse(throw new RuntimeException("no graphics queue"))

    val prio = z(4); prio.f32(0, 1f)
    val qci = z(L.DeviceQueueCreateInfo.SIZE)
    qci.i32(L.DeviceQueueCreateInfo.sType, 2); qci.i32(L.DeviceQueueCreateInfo.queueFamilyIndex, queueFamily)
    qci.i32(L.DeviceQueueCreateInfo.queueCount, 1); qci.ptr(L.DeviceQueueCreateInfo.pQueuePriorities, prio)
    val devExts = if isMac then Seq("VK_KHR_portability_subset") else Seq.empty
    val dci = z(L.DeviceCreateInfo.SIZE)
    dci.i32(L.DeviceCreateInfo.sType, 3); dci.i32(L.DeviceCreateInfo.queueCreateInfoCount, 1); dci.ptr(L.DeviceCreateInfo.pQueueCreateInfos, qci)
    dci.i32(L.DeviceCreateInfo.enabledExtensionCount, devExts.length); dci.ptr(L.DeviceCreateInfo.ppEnabledExtensionNames, strings(devExts))
    device = create("vkCreateDevice")(o => api.createDevice(physical, dci, null, o))
    api.loadDevice(device)
    val q = z(8); api.getDeviceQueue(device, queueFamily.toLong, 0L, q); queue = q.getI64(0)

    val pci = z(L.CommandPoolCreateInfo.SIZE)
    pci.i32(L.CommandPoolCreateInfo.sType, 39); pci.i32(L.CommandPoolCreateInfo.flags, 2) // RESET_COMMAND_BUFFER
    pci.i32(L.CommandPoolCreateInfo.queueFamilyIndex, queueFamily)
    commandPool = create("vkCreateCommandPool")(o => api.createCommandPool(device, pci, null, o))
    val cai = z(L.CommandBufferAllocateInfo.SIZE)
    cai.i32(L.CommandBufferAllocateInfo.sType, 40); cai.i64(L.CommandBufferAllocateInfo.commandPool, commandPool)
    cai.i32(L.CommandBufferAllocateInfo.commandBufferCount, 2)
    val cbs = z(16)
    check(api.allocateCommandBuffers(device, cai, cbs), "vkAllocateCommandBuffers")
    frameCmd = cbs.getI64(0); uploadCmd = cbs.getI64(8)
    val fci = z(L.FenceCreateInfo.SIZE); fci.i32(L.FenceCreateInfo.sType, 8)
    fence = create("vkCreateFence")(o => api.createFence(device, fci, null, o))

    // sampler: bilinear, clamp to edge
    val si = z(L.SamplerCreateInfo.SIZE)
    si.i32(L.SamplerCreateInfo.sType, 31); si.i32(L.SamplerCreateInfo.magFilter, 1); si.i32(L.SamplerCreateInfo.minFilter, 1)
    si.i32(L.SamplerCreateInfo.addressModeU, 2); si.i32(L.SamplerCreateInfo.addressModeV, 2); si.i32(L.SamplerCreateInfo.addressModeW, 2)
    sampler = create("vkCreateSampler")(o => api.createSampler(device, si, null, o))

    // descriptor set layout: one combined image sampler in the fragment stage
    val bind = z(L.DescriptorSetLayoutBinding.SIZE)
    bind.i32(L.DescriptorSetLayoutBinding.descriptorType, 1); bind.i32(L.DescriptorSetLayoutBinding.descriptorCount, 1)
    bind.i32(L.DescriptorSetLayoutBinding.stageFlags, 0x10)
    val sli = z(L.DescriptorSetLayoutCreateInfo.SIZE)
    sli.i32(L.DescriptorSetLayoutCreateInfo.sType, 32); sli.i32(L.DescriptorSetLayoutCreateInfo.bindingCount, 1); sli.ptr(L.DescriptorSetLayoutCreateInfo.pBindings, bind)
    setLayout = create("vkCreateDescriptorSetLayout")(o => api.createDescriptorSetLayout(device, sli, null, o))
    val layouts = z(8); layouts.i64(0, setLayout)
    val pcr = z(L.PushConstantRange.SIZE); pcr.i32(L.PushConstantRange.stageFlags, 1); pcr.i32(L.PushConstantRange.size, 8)
    val pli = z(L.PipelineLayoutCreateInfo.SIZE)
    pli.i32(L.PipelineLayoutCreateInfo.sType, 30); pli.i32(L.PipelineLayoutCreateInfo.setLayoutCount, 1); pli.ptr(L.PipelineLayoutCreateInfo.pSetLayouts, layouts)
    pli.i32(L.PipelineLayoutCreateInfo.pushConstantRangeCount, 1); pli.ptr(L.PipelineLayoutCreateInfo.pPushConstantRanges, pcr)
    pipelineLayout = create("vkCreatePipelineLayout")(o => api.createPipelineLayout(device, pli, null, o))

    buildRenderPass()
    buildPipeline()
  }

  private def buildRenderPass(): Unit =
    renderPass = makeRenderPass(load = false)
    renderPassLoad = makeRenderPass(load = true)

  /** The multisampled image is kept (store, layout COLOR_ATTACHMENT_OPTIMAL at the end) so the next frame can load it. */
  private def makeRenderPass(load: Boolean): Long = Zone {
    val att = z(2 * L.AttachmentDescription.SIZE)
    def a(i: Int, samples: Int, loadOp: Int, storeOp: Int, finalLayout: Int, initialLayout: Int = 0): Unit =
      val o = i * L.AttachmentDescription.SIZE
      att.i32(o + L.AttachmentDescription.format, Format); att.i32(o + L.AttachmentDescription.samples, samples)
      att.i32(o + L.AttachmentDescription.loadOp, loadOp); att.i32(o + L.AttachmentDescription.storeOp, storeOp)
      att.i32(o + L.AttachmentDescription.stencilLoadOp, 2); att.i32(o + L.AttachmentDescription.stencilStoreOp, 1) // DONT_CARE
      att.i32(o + L.AttachmentDescription.initialLayout, initialLayout); att.i32(o + L.AttachmentDescription.finalLayout, finalLayout)
    if load then a(0, Samples, 0, 0, 2, 2) else a(0, Samples, 1, 0, 2) // clear or load; always store the multisampled data
    a(1, 1, 2, 0, 6)       // resolve target: store, end in TRANSFER_SRC_OPTIMAL
    val refs = z(2 * L.AttachmentReference.SIZE)
    refs.i32(L.AttachmentReference.attachment, 0); refs.i32(L.AttachmentReference.layout, 2)
    refs.i32(L.AttachmentReference.SIZE + L.AttachmentReference.attachment, 1); refs.i32(L.AttachmentReference.SIZE + L.AttachmentReference.layout, 2)
    val sub = z(L.SubpassDescription.SIZE)
    sub.i32(L.SubpassDescription.pipelineBindPoint, 0); sub.i32(L.SubpassDescription.colorAttachmentCount, 1)
    sub.ptr(L.SubpassDescription.pColorAttachments, refs); sub.ptr(L.SubpassDescription.pResolveAttachments, refs + L.AttachmentReference.SIZE)
    val dep = z(2 * L.SubpassDependency.SIZE)
    def d(i: Int, src: Int, dst: Int, srcStage: Int, dstStage: Int, srcAcc: Int, dstAcc: Int): Unit =
      val o = i * L.SubpassDependency.SIZE
      dep.i32(o + L.SubpassDependency.srcSubpass, src); dep.i32(o + L.SubpassDependency.dstSubpass, dst)
      dep.i32(o + L.SubpassDependency.srcStageMask, srcStage); dep.i32(o + L.SubpassDependency.dstStageMask, dstStage)
      dep.i32(o + L.SubpassDependency.srcAccessMask, srcAcc); dep.i32(o + L.SubpassDependency.dstAccessMask, dstAcc)
    d(0, -1, 0, 0x400, 0x400, 0x100, 0x180)     // external -> color output (read too: partial frames load the retained samples)
    d(1, 0, -1, 0x400, 0x1000, 0x100, 0x800)    // color write -> transfer read (readback)
    val ri = z(L.RenderPassCreateInfo.SIZE)
    ri.i32(L.RenderPassCreateInfo.sType, 38); ri.i32(L.RenderPassCreateInfo.attachmentCount, 2); ri.ptr(L.RenderPassCreateInfo.pAttachments, att)
    ri.i32(L.RenderPassCreateInfo.subpassCount, 1); ri.ptr(L.RenderPassCreateInfo.pSubpasses, sub)
    ri.i32(L.RenderPassCreateInfo.dependencyCount, 2); ri.ptr(L.RenderPassCreateInfo.pDependencies, dep)
    create("vkCreateRenderPass")(o => api.createRenderPass(device, ri, null, o))
  }

  private def shaderModule(resource: String): Long = Zone {
    val in = getClass.getResourceAsStream("/shaders/" + resource)
    require(in != null, s"missing shader resource $resource")
    val code = in.readAllBytes(); in.close()
    val buf = z(code.length)
    var i = 0; while i < code.length do { buf(i) = code(i); i += 1 }
    val ci = z(L.ShaderModuleCreateInfo.SIZE)
    ci.i32(L.ShaderModuleCreateInfo.sType, 16); ci.i64(L.ShaderModuleCreateInfo.codeSize, code.length.toLong); ci.ptr(L.ShaderModuleCreateInfo.pCode, buf)
    create("vkCreateShaderModule")(o => api.createShaderModule(device, ci, null, o))
  }

  private def buildPipeline(): Unit = Zone {
    val vs = shaderModule("ui.vert.spv"); val fs = shaderModule("ui.frag.spv")
    val main = toCString("main").asInstanceOf[Ptr[Byte]]
    val stages = z(2 * L.PipelineShaderStageCreateInfo.SIZE)
    def st(i: Int, stage: Int, module: Long): Unit =
      val o = i * L.PipelineShaderStageCreateInfo.SIZE
      stages.i32(o + L.PipelineShaderStageCreateInfo.sType, 18); stages.i32(o + L.PipelineShaderStageCreateInfo.stage, stage)
      stages.i64(o + L.PipelineShaderStageCreateInfo.module, module); stages.ptr(o + L.PipelineShaderStageCreateInfo.pName, main)
    st(0, 1, vs); st(1, 0x10, fs)
    val vb = z(L.VertexInputBindingDescription.SIZE); vb.i32(L.VertexInputBindingDescription.stride, Vertex.Floats * 4)
    val va = z(3 * L.VertexInputAttributeDescription.SIZE)
    def at(i: Int, format: Int, offset: Int): Unit =
      val o = i * L.VertexInputAttributeDescription.SIZE
      va.i32(o + L.VertexInputAttributeDescription.location, i); va.i32(o + L.VertexInputAttributeDescription.format, format)
      va.i32(o + L.VertexInputAttributeDescription.offset, offset)
    at(0, 103, 0); at(1, 103, 8); at(2, 109, 16) // R32G32_SFLOAT x2, R32G32B32A32_SFLOAT
    val vi = z(L.PipelineVertexInputStateCreateInfo.SIZE)
    vi.i32(L.PipelineVertexInputStateCreateInfo.sType, 19); vi.i32(L.PipelineVertexInputStateCreateInfo.vertexBindingDescriptionCount, 1)
    vi.ptr(L.PipelineVertexInputStateCreateInfo.pVertexBindingDescriptions, vb)
    vi.i32(L.PipelineVertexInputStateCreateInfo.vertexAttributeDescriptionCount, 3); vi.ptr(L.PipelineVertexInputStateCreateInfo.pVertexAttributeDescriptions, va)
    val ia = z(L.PipelineInputAssemblyStateCreateInfo.SIZE); ia.i32(L.PipelineInputAssemblyStateCreateInfo.sType, 20); ia.i32(L.PipelineInputAssemblyStateCreateInfo.topology, 3) // TRIANGLE_LIST
    val vp = z(L.PipelineViewportStateCreateInfo.SIZE)
    vp.i32(L.PipelineViewportStateCreateInfo.sType, 22); vp.i32(L.PipelineViewportStateCreateInfo.viewportCount, 1); vp.i32(L.PipelineViewportStateCreateInfo.scissorCount, 1)
    val rs = z(L.PipelineRasterizationStateCreateInfo.SIZE)
    rs.i32(L.PipelineRasterizationStateCreateInfo.sType, 23); rs.f32(L.PipelineRasterizationStateCreateInfo.lineWidth, 1f) // no culling: UI winding varies
    val ms = z(L.PipelineMultisampleStateCreateInfo.SIZE); ms.i32(L.PipelineMultisampleStateCreateInfo.sType, 24); ms.i32(L.PipelineMultisampleStateCreateInfo.rasterizationSamples, Samples)
    val ba = z(L.PipelineColorBlendAttachmentState.SIZE)
    // premultiplied alpha: src + dst * (1 - srcAlpha)
    ba.i32(L.PipelineColorBlendAttachmentState.blendEnable, 1)
    ba.i32(L.PipelineColorBlendAttachmentState.srcColorBlendFactor, 1); ba.i32(L.PipelineColorBlendAttachmentState.dstColorBlendFactor, 7) // ONE, ONE_MINUS_SRC_ALPHA
    ba.i32(L.PipelineColorBlendAttachmentState.srcAlphaBlendFactor, 1); ba.i32(L.PipelineColorBlendAttachmentState.dstAlphaBlendFactor, 7)
    ba.i32(L.PipelineColorBlendAttachmentState.colorWriteMask, 0xF)
    val cb = z(L.PipelineColorBlendStateCreateInfo.SIZE)
    cb.i32(L.PipelineColorBlendStateCreateInfo.sType, 26); cb.i32(L.PipelineColorBlendStateCreateInfo.attachmentCount, 1); cb.ptr(L.PipelineColorBlendStateCreateInfo.pAttachments, ba)
    val dyn = z(8); dyn.i32(0, 0); dyn.i32(4, 1) // VIEWPORT, SCISSOR
    val ds = z(L.PipelineDynamicStateCreateInfo.SIZE)
    ds.i32(L.PipelineDynamicStateCreateInfo.sType, 27); ds.i32(L.PipelineDynamicStateCreateInfo.dynamicStateCount, 2); ds.ptr(L.PipelineDynamicStateCreateInfo.pDynamicStates, dyn)
    val gp = z(L.GraphicsPipelineCreateInfo.SIZE)
    gp.i32(L.GraphicsPipelineCreateInfo.sType, 28); gp.i32(L.GraphicsPipelineCreateInfo.stageCount, 2); gp.ptr(L.GraphicsPipelineCreateInfo.pStages, stages)
    gp.ptr(L.GraphicsPipelineCreateInfo.pVertexInputState, vi); gp.ptr(L.GraphicsPipelineCreateInfo.pInputAssemblyState, ia)
    gp.ptr(L.GraphicsPipelineCreateInfo.pViewportState, vp); gp.ptr(L.GraphicsPipelineCreateInfo.pRasterizationState, rs)
    gp.ptr(L.GraphicsPipelineCreateInfo.pMultisampleState, ms); gp.ptr(L.GraphicsPipelineCreateInfo.pColorBlendState, cb)
    gp.ptr(L.GraphicsPipelineCreateInfo.pDynamicState, ds); gp.i64(L.GraphicsPipelineCreateInfo.layout, pipelineLayout)
    gp.i64(L.GraphicsPipelineCreateInfo.renderPass, renderPass)
    pipeline = create("vkCreateGraphicsPipelines")(o => api.createGraphicsPipelines(device, 0L, 1L, gp, null, o))
  }

object VulkanBackend:
  private val Format = 37  // VK_FORMAT_R8G8B8A8_UNORM
  private val Samples = 4

  /** A Vulkan backend presenting into `window` (or headless when `None`); None if no Vulkan driver or device is usable. */
  def create(window: Option[Window]): Option[VulkanBackend] =
    try
      VulkanApi.open().map { api =>
        val b = new VulkanBackend(api, window)
        b.init()
        b
      }
    catch case e: RuntimeException =>
      if sys.env.contains("SCALAUI_DEBUG") then System.err.println(s"backend unavailable: ${e.getMessage}")
      None

  /** Like [[create]] but reports why it failed. */
  def createOrThrow(window: Option[Window]): VulkanBackend =
    val api = VulkanApi.open().getOrElse(throw new RuntimeException("no Vulkan loader found"))
    val b = new VulkanBackend(api, window)
    b.init()
    b
