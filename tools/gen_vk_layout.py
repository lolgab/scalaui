#!/usr/bin/env python3
"""Generates modules/gpu/.../VkLayout.scala: sizes and field offsets of the Vulkan structs the backend pokes by hand,
measured with the system C compiler against the Vulkan headers (64-bit layouts). Run when adding structs.
usage: tools/gen_vk_layout.py [include-dir]"""
import subprocess, sys, tempfile, os
inc = sys.argv[1] if len(sys.argv) > 1 else "/opt/homebrew/include"
S = {
 "ApplicationInfo": ["sType","pApplicationName","apiVersion"],
 "InstanceCreateInfo": ["sType","flags","pApplicationInfo","enabledLayerCount","ppEnabledLayerNames","enabledExtensionCount","ppEnabledExtensionNames"],
 "DeviceQueueCreateInfo": ["sType","queueFamilyIndex","queueCount","pQueuePriorities"],
 "DeviceCreateInfo": ["sType","queueCreateInfoCount","pQueueCreateInfos","enabledExtensionCount","ppEnabledExtensionNames"],
 "QueueFamilyProperties": ["queueFlags"],
 "PhysicalDeviceProperties": ["deviceType","deviceName"],
 "PhysicalDeviceMemoryProperties": ["memoryTypeCount","memoryTypes"],
 "MemoryType": ["propertyFlags","heapIndex"],
 "MemoryRequirements": ["size","alignment","memoryTypeBits"],
 "ImageCreateInfo": ["sType","imageType","format","extent","mipLevels","arrayLayers","samples","tiling","usage","sharingMode","initialLayout"],
 "ImageViewCreateInfo": ["sType","image","viewType","format","components","subresourceRange"],
 "SamplerCreateInfo": ["sType","magFilter","minFilter","mipmapMode","addressModeU","addressModeV","addressModeW","maxLod","borderColor"],
 "BufferCreateInfo": ["sType","size","usage","sharingMode"],
 "MemoryAllocateInfo": ["sType","allocationSize","memoryTypeIndex"],
 "AttachmentDescription": ["format","samples","loadOp","storeOp","stencilLoadOp","stencilStoreOp","initialLayout","finalLayout"],
 "AttachmentReference": ["attachment","layout"],
 "SubpassDescription": ["pipelineBindPoint","colorAttachmentCount","pColorAttachments","pResolveAttachments"],
 "SubpassDependency": ["srcSubpass","dstSubpass","srcStageMask","dstStageMask","srcAccessMask","dstAccessMask"],
 "RenderPassCreateInfo": ["sType","attachmentCount","pAttachments","subpassCount","pSubpasses","dependencyCount","pDependencies"],
 "FramebufferCreateInfo": ["sType","renderPass","attachmentCount","pAttachments","width","height","layers"],
 "ShaderModuleCreateInfo": ["sType","codeSize","pCode"],
 "PipelineShaderStageCreateInfo": ["sType","stage","module","pName"],
 "VertexInputBindingDescription": ["binding","stride","inputRate"],
 "VertexInputAttributeDescription": ["location","binding","format","offset"],
 "PipelineVertexInputStateCreateInfo": ["sType","vertexBindingDescriptionCount","pVertexBindingDescriptions","vertexAttributeDescriptionCount","pVertexAttributeDescriptions"],
 "PipelineInputAssemblyStateCreateInfo": ["sType","topology"],
 "PipelineViewportStateCreateInfo": ["sType","viewportCount","pViewports","scissorCount","pScissors"],
 "PipelineRasterizationStateCreateInfo": ["sType","polygonMode","cullMode","frontFace","lineWidth"],
 "PipelineMultisampleStateCreateInfo": ["sType","rasterizationSamples"],
 "PipelineColorBlendAttachmentState": ["blendEnable","srcColorBlendFactor","dstColorBlendFactor","colorBlendOp","srcAlphaBlendFactor","dstAlphaBlendFactor","alphaBlendOp","colorWriteMask"],
 "PipelineColorBlendStateCreateInfo": ["sType","attachmentCount","pAttachments"],
 "PipelineDynamicStateCreateInfo": ["sType","dynamicStateCount","pDynamicStates"],
 "GraphicsPipelineCreateInfo": ["sType","stageCount","pStages","pVertexInputState","pInputAssemblyState","pViewportState","pRasterizationState","pMultisampleState","pColorBlendState","pDynamicState","layout","renderPass","subpass"],
 "PushConstantRange": ["stageFlags","offset","size"],
 "PipelineLayoutCreateInfo": ["sType","setLayoutCount","pSetLayouts","pushConstantRangeCount","pPushConstantRanges"],
 "DescriptorSetLayoutBinding": ["binding","descriptorType","descriptorCount","stageFlags"],
 "DescriptorSetLayoutCreateInfo": ["sType","bindingCount","pBindings"],
 "DescriptorPoolSize": ["type","descriptorCount"],
 "DescriptorPoolCreateInfo": ["sType","flags","maxSets","poolSizeCount","pPoolSizes"],
 "DescriptorSetAllocateInfo": ["sType","descriptorPool","descriptorSetCount","pSetLayouts"],
 "DescriptorImageInfo": ["sampler","imageView","imageLayout"],
 "WriteDescriptorSet": ["sType","dstSet","dstBinding","descriptorCount","descriptorType","pImageInfo"],
 "CommandPoolCreateInfo": ["sType","flags","queueFamilyIndex"],
 "CommandBufferAllocateInfo": ["sType","commandPool","level","commandBufferCount"],
 "CommandBufferBeginInfo": ["sType","flags"],
 "RenderPassBeginInfo": ["sType","renderPass","framebuffer","renderArea","clearValueCount","pClearValues"],
 "BufferImageCopy": ["bufferOffset","bufferRowLength","bufferImageHeight","imageSubresource","imageOffset","imageExtent"],
 "ImageMemoryBarrier": ["sType","srcAccessMask","dstAccessMask","oldLayout","newLayout","srcQueueFamilyIndex","dstQueueFamilyIndex","image","subresourceRange"],
 "SubmitInfo": ["sType","commandBufferCount","pCommandBuffers"],
 "FenceCreateInfo": ["sType","flags"],
 "Viewport": ["x","y","width","height","minDepth","maxDepth"],
 "Rect2D": ["offset","extent"],
 "ImageSubresourceRange": ["aspectMask","baseMipLevel","levelCount","baseArrayLayer","layerCount"],
 "ImageSubresourceLayers": ["aspectMask","mipLevel","baseArrayLayer","layerCount"],
}
src = ['#include <vulkan/vulkan.h>','#include <stdio.h>','#include <stddef.h>','int main(void){']
for name, fields in S.items():
    src.append(f'printf("%s %zu","{name}",sizeof(Vk{name}));')
    for f in fields:
        src.append(f'printf(" %s=%zu","{f}",offsetof(Vk{name},{f}));')
    src.append('printf("\\n");')
src.append('return 0;}')
d = tempfile.mkdtemp()
c = os.path.join(d, "l.c"); open(c, "w").write("\n".join(src))
subprocess.check_call(["cc", "-I", inc, c, "-o", os.path.join(d, "l")])
out = subprocess.check_output([os.path.join(d, "l")]).decode()
res = ["package scalaui.gpu", "", "/** GENERATED by tools/gen_vk_layout.py: Vulkan struct sizes and field offsets (64-bit). */", "private[gpu] object VkLayout:"]
for line in out.strip().splitlines():
    parts = line.split()
    name, size = parts[0], parts[1]
    res.append(f"  object {name}:")
    res.append(f"    val SIZE = {size}")
    for kv in parts[2:]:
        k, v = kv.split("=")
        res.append(f"    val {'`'+k+'`' if k in ('type',) else k} = {v}")
dest = os.path.join(os.path.dirname(__file__), "..", "modules/gpu/src/main/scala/scalaui/gpu/VkLayout.scala")
open(dest, "w").write("\n".join(res) + "\n")
print("wrote", os.path.normpath(dest))
