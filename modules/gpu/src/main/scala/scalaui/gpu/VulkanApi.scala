package scalaui.gpu

import scala.scalanative.unsafe.*
import scala.scalanative.unsigned.*
import scalaui.window.DynLib

/** The Vulkan entry points the backend uses, resolved at run time from the system loader (no link-time dependency,
  * so binaries start on machines without Vulkan). Handles and 32-bit integers are all passed as `Long`, which matches
  * the 64-bit calling conventions of every supported platform; struct pointers are `Ptr[Byte]`.
  */
private[gpu] final class VulkanApi private (lib: Ptr[Byte]):
  import VulkanApi.*

  private val getInstanceProcAddr =
    CFuncPtr.fromPtr[CFuncPtr2[Long, CString, Ptr[Byte]]](DynLib.symbol(lib, "vkGetInstanceProcAddr"))

  private def global(name: String): Ptr[Byte] = Zone(getInstanceProcAddr(0L, toCString(name)))

  val createInstance = CFuncPtr.fromPtr[CFuncPtr3[Ptr[Byte], Ptr[Byte], Ptr[Byte], CInt]](global("vkCreateInstance"))

  private var instance = 0L
  private var device = 0L
  private def inst(name: String): Ptr[Byte] =
    val p = Zone(getInstanceProcAddr(instance, toCString(name)))
    if p == null then throw new RuntimeException(s"Vulkan function $name not available")
    p

  // --- instance level (loaded by `loadInstance`) ---
  var enumeratePhysicalDevices: CFuncPtr3[Long, Ptr[Byte], Ptr[Byte], CInt] = null
  var getQueueFamilyProperties: CFuncPtr3[Long, Ptr[Byte], Ptr[Byte], CInt] = null
  var getPhysicalDeviceProperties: CFuncPtr2[Long, Ptr[Byte], CInt] = null
  var getMemoryProperties: CFuncPtr2[Long, Ptr[Byte], CInt] = null
  var createDevice: CFuncPtr4[Long, Ptr[Byte], Ptr[Byte], Ptr[Byte], CInt] = null
  private var getDeviceProcAddr: CFuncPtr2[Long, CString, Ptr[Byte]] = null

  def loadInstance(i: Long): Unit =
    instance = i
    enumeratePhysicalDevices = CFuncPtr.fromPtr(inst("vkEnumeratePhysicalDevices"))
    getQueueFamilyProperties = CFuncPtr.fromPtr(inst("vkGetPhysicalDeviceQueueFamilyProperties"))
    getPhysicalDeviceProperties = CFuncPtr.fromPtr(inst("vkGetPhysicalDeviceProperties"))
    getMemoryProperties = CFuncPtr.fromPtr(inst("vkGetPhysicalDeviceMemoryProperties"))
    createDevice = CFuncPtr.fromPtr(inst("vkCreateDevice"))
    getDeviceProcAddr = CFuncPtr.fromPtr(inst("vkGetDeviceProcAddr"))

  // --- device level (loaded by `loadDevice`) ---
  var getDeviceQueue: CFuncPtr4[Long, Long, Long, Ptr[Byte], CInt] = null
  var createCommandPool: CFuncPtr4[Long, Ptr[Byte], Ptr[Byte], Ptr[Byte], CInt] = null
  var allocateCommandBuffers: CFuncPtr3[Long, Ptr[Byte], Ptr[Byte], CInt] = null
  var createFence: CFuncPtr4[Long, Ptr[Byte], Ptr[Byte], Ptr[Byte], CInt] = null
  var waitForFences: CFuncPtr5[Long, Long, Ptr[Byte], Long, Long, CInt] = null
  var resetFences: CFuncPtr3[Long, Long, Ptr[Byte], CInt] = null
  var createRenderPass: CFuncPtr4[Long, Ptr[Byte], Ptr[Byte], Ptr[Byte], CInt] = null
  var createFramebuffer: CFuncPtr4[Long, Ptr[Byte], Ptr[Byte], Ptr[Byte], CInt] = null
  var createImage: CFuncPtr4[Long, Ptr[Byte], Ptr[Byte], Ptr[Byte], CInt] = null
  var getImageMemoryRequirements: CFuncPtr3[Long, Long, Ptr[Byte], CInt] = null
  var allocateMemory: CFuncPtr4[Long, Ptr[Byte], Ptr[Byte], Ptr[Byte], CInt] = null
  var bindImageMemory: CFuncPtr4[Long, Long, Long, Long, CInt] = null
  var createImageView: CFuncPtr4[Long, Ptr[Byte], Ptr[Byte], Ptr[Byte], CInt] = null
  var createSampler: CFuncPtr4[Long, Ptr[Byte], Ptr[Byte], Ptr[Byte], CInt] = null
  var createBuffer: CFuncPtr4[Long, Ptr[Byte], Ptr[Byte], Ptr[Byte], CInt] = null
  var getBufferMemoryRequirements: CFuncPtr3[Long, Long, Ptr[Byte], CInt] = null
  var bindBufferMemory: CFuncPtr4[Long, Long, Long, Long, CInt] = null
  var mapMemory: CFuncPtr6[Long, Long, Long, Long, Long, Ptr[Byte], CInt] = null
  var destroyImage: CFuncPtr3[Long, Long, Ptr[Byte], CInt] = null
  var destroyImageView: CFuncPtr3[Long, Long, Ptr[Byte], CInt] = null
  var destroyFramebuffer: CFuncPtr3[Long, Long, Ptr[Byte], CInt] = null
  var destroyBuffer: CFuncPtr3[Long, Long, Ptr[Byte], CInt] = null
  var freeMemory: CFuncPtr3[Long, Long, Ptr[Byte], CInt] = null
  var createShaderModule: CFuncPtr4[Long, Ptr[Byte], Ptr[Byte], Ptr[Byte], CInt] = null
  var createDescriptorSetLayout: CFuncPtr4[Long, Ptr[Byte], Ptr[Byte], Ptr[Byte], CInt] = null
  var createPipelineLayout: CFuncPtr4[Long, Ptr[Byte], Ptr[Byte], Ptr[Byte], CInt] = null
  var createGraphicsPipelines: CFuncPtr6[Long, Long, Long, Ptr[Byte], Ptr[Byte], Ptr[Byte], CInt] = null
  var createDescriptorPool: CFuncPtr4[Long, Ptr[Byte], Ptr[Byte], Ptr[Byte], CInt] = null
  var allocateDescriptorSets: CFuncPtr3[Long, Ptr[Byte], Ptr[Byte], CInt] = null
  var updateDescriptorSets: CFuncPtr5[Long, Long, Ptr[Byte], Long, Ptr[Byte], CInt] = null
  var resetCommandBuffer: CFuncPtr2[Long, Long, CInt] = null
  var beginCommandBuffer: CFuncPtr2[Long, Ptr[Byte], CInt] = null
  var endCommandBuffer: CFuncPtr1[Long, CInt] = null
  var cmdBeginRenderPass: CFuncPtr3[Long, Ptr[Byte], Long, CInt] = null
  var cmdEndRenderPass: CFuncPtr1[Long, CInt] = null
  var cmdBindPipeline: CFuncPtr3[Long, Long, Long, CInt] = null
  var cmdBindVertexBuffers: CFuncPtr5[Long, Long, Long, Ptr[Byte], Ptr[Byte], CInt] = null
  var cmdPushConstants: CFuncPtr6[Long, Long, Long, Long, Long, Ptr[Byte], CInt] = null
  var cmdSetViewport: CFuncPtr4[Long, Long, Long, Ptr[Byte], CInt] = null
  var cmdSetScissor: CFuncPtr4[Long, Long, Long, Ptr[Byte], CInt] = null
  var cmdBindDescriptorSets: CFuncPtr8[Long, Long, Long, Long, Long, Ptr[Byte], Long, Ptr[Byte], CInt] = null
  var cmdClearAttachments: CFuncPtr5[Long, Long, Ptr[Byte], Long, Ptr[Byte], CInt] = null
  var cmdDraw: CFuncPtr5[Long, Long, Long, Long, Long, CInt] = null
  var cmdPipelineBarrier: CFuncPtr10[Long, Long, Long, Long, Long, Ptr[Byte], Long, Ptr[Byte], Long, Ptr[Byte], CInt] = null
  var cmdCopyBufferToImage: CFuncPtr6[Long, Long, Long, Long, Long, Ptr[Byte], CInt] = null
  var cmdCopyImageToBuffer: CFuncPtr6[Long, Long, Long, Long, Long, Ptr[Byte], CInt] = null
  var queueSubmit: CFuncPtr4[Long, Long, Ptr[Byte], Long, CInt] = null
  var queueWaitIdle: CFuncPtr1[Long, CInt] = null

  def loadDevice(d: Long): Unit =
    device = d
    def f(name: String): Ptr[Byte] =
      val p = Zone(getDeviceProcAddr(d, toCString(name)))
      if p == null then throw new RuntimeException(s"Vulkan function $name not available")
      p
    getDeviceQueue = CFuncPtr.fromPtr(f("vkGetDeviceQueue"))
    createCommandPool = CFuncPtr.fromPtr(f("vkCreateCommandPool"))
    allocateCommandBuffers = CFuncPtr.fromPtr(f("vkAllocateCommandBuffers"))
    createFence = CFuncPtr.fromPtr(f("vkCreateFence"))
    waitForFences = CFuncPtr.fromPtr(f("vkWaitForFences"))
    resetFences = CFuncPtr.fromPtr(f("vkResetFences"))
    createRenderPass = CFuncPtr.fromPtr(f("vkCreateRenderPass"))
    createFramebuffer = CFuncPtr.fromPtr(f("vkCreateFramebuffer"))
    createImage = CFuncPtr.fromPtr(f("vkCreateImage"))
    getImageMemoryRequirements = CFuncPtr.fromPtr(f("vkGetImageMemoryRequirements"))
    allocateMemory = CFuncPtr.fromPtr(f("vkAllocateMemory"))
    bindImageMemory = CFuncPtr.fromPtr(f("vkBindImageMemory"))
    createImageView = CFuncPtr.fromPtr(f("vkCreateImageView"))
    createSampler = CFuncPtr.fromPtr(f("vkCreateSampler"))
    createBuffer = CFuncPtr.fromPtr(f("vkCreateBuffer"))
    getBufferMemoryRequirements = CFuncPtr.fromPtr(f("vkGetBufferMemoryRequirements"))
    bindBufferMemory = CFuncPtr.fromPtr(f("vkBindBufferMemory"))
    mapMemory = CFuncPtr.fromPtr(f("vkMapMemory"))
    destroyImage = CFuncPtr.fromPtr(f("vkDestroyImage"))
    destroyImageView = CFuncPtr.fromPtr(f("vkDestroyImageView"))
    destroyFramebuffer = CFuncPtr.fromPtr(f("vkDestroyFramebuffer"))
    destroyBuffer = CFuncPtr.fromPtr(f("vkDestroyBuffer"))
    freeMemory = CFuncPtr.fromPtr(f("vkFreeMemory"))
    createShaderModule = CFuncPtr.fromPtr(f("vkCreateShaderModule"))
    createDescriptorSetLayout = CFuncPtr.fromPtr(f("vkCreateDescriptorSetLayout"))
    createPipelineLayout = CFuncPtr.fromPtr(f("vkCreatePipelineLayout"))
    createGraphicsPipelines = CFuncPtr.fromPtr(f("vkCreateGraphicsPipelines"))
    createDescriptorPool = CFuncPtr.fromPtr(f("vkCreateDescriptorPool"))
    allocateDescriptorSets = CFuncPtr.fromPtr(f("vkAllocateDescriptorSets"))
    updateDescriptorSets = CFuncPtr.fromPtr(f("vkUpdateDescriptorSets"))
    resetCommandBuffer = CFuncPtr.fromPtr(f("vkResetCommandBuffer"))
    beginCommandBuffer = CFuncPtr.fromPtr(f("vkBeginCommandBuffer"))
    endCommandBuffer = CFuncPtr.fromPtr(f("vkEndCommandBuffer"))
    cmdBeginRenderPass = CFuncPtr.fromPtr(f("vkCmdBeginRenderPass"))
    cmdEndRenderPass = CFuncPtr.fromPtr(f("vkCmdEndRenderPass"))
    cmdBindPipeline = CFuncPtr.fromPtr(f("vkCmdBindPipeline"))
    cmdBindVertexBuffers = CFuncPtr.fromPtr(f("vkCmdBindVertexBuffers"))
    cmdPushConstants = CFuncPtr.fromPtr(f("vkCmdPushConstants"))
    cmdSetViewport = CFuncPtr.fromPtr(f("vkCmdSetViewport"))
    cmdSetScissor = CFuncPtr.fromPtr(f("vkCmdSetScissor"))
    cmdBindDescriptorSets = CFuncPtr.fromPtr(f("vkCmdBindDescriptorSets"))
    cmdDraw = CFuncPtr.fromPtr(f("vkCmdDraw"))
    cmdClearAttachments = CFuncPtr.fromPtr(f("vkCmdClearAttachments"))
    cmdPipelineBarrier = CFuncPtr.fromPtr(f("vkCmdPipelineBarrier"))
    cmdCopyBufferToImage = CFuncPtr.fromPtr(f("vkCmdCopyBufferToImage"))
    cmdCopyImageToBuffer = CFuncPtr.fromPtr(f("vkCmdCopyImageToBuffer"))
    queueSubmit = CFuncPtr.fromPtr(f("vkQueueSubmit"))
    queueWaitIdle = CFuncPtr.fromPtr(f("vkQueueWaitIdle"))

private[gpu] object VulkanApi:
  /** Opens the system Vulkan loader, or None when there is none. */
  def open(): Option[VulkanApi] =
    import scala.scalanative.meta.LinktimeInfo
    val candidates =
      if LinktimeInfo.isWindows then Seq("vulkan-1.dll")
      else if LinktimeInfo.isMac then Seq("libvulkan.1.dylib", "/opt/homebrew/lib/libvulkan.1.dylib", "/usr/local/lib/libvulkan.1.dylib", "libMoltenVK.dylib", "/opt/homebrew/lib/libMoltenVK.dylib")
      else Seq("libvulkan.so.1", "libvulkan.so")
    candidates.iterator.map(DynLib.open).find(_ != null).map(new VulkanApi(_))
