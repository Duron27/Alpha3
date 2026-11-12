#ifndef ALPHA3_SUPPORT_VULKAN_VK_H
#define ALPHA3_SUPPORT_VULKAN_VK_H

// Vulkan 1.0, 1.1 or 1.2 for the native renderer ([Video] renderer = vulkan): the API through function pointers loaded at run time,
// from the custom driver libadrenotools loaded (Turnip; its handle is in ALPHA3_VULKAN_HANDLE) or the system's
// libvulkan.so. Nothing links libvulkan: a phone without Vulkan still starts, on OpenGL ES.

#ifndef VK_NO_PROTOTYPES
#define VK_NO_PROTOTYPES
#endif
#ifndef VK_USE_PLATFORM_ANDROID_KHR
#define VK_USE_PLATFORM_ANDROID_KHR
#endif
#include <vulkan/vulkan.h>


// Loaded before an instance exists
#define ALPHA3_VK_GLOBAL_FUNCTIONS(X)                                                                                   \
    X(vkCreateInstance)                                                                                                \
    X(vkEnumerateInstanceExtensionProperties)                                                                          \
    X(vkEnumerateInstanceLayerProperties)                                                                          \
    X(vkEnumerateInstanceVersion) /* 1.1; null from a 1.0 loader */

// Loaded from the instance
#define ALPHA3_VK_INSTANCE_FUNCTIONS(X)                                                                                 \
    X(vkDestroyInstance)                                                                                               \
    X(vkEnumeratePhysicalDevices)                                                                                      \
    X(vkGetPhysicalDeviceProperties)                                                                                   \
    X(vkGetPhysicalDeviceFeatures)                                                                                     \
    X(vkGetPhysicalDeviceFormatProperties)                                                                             \
    X(vkGetPhysicalDeviceImageFormatProperties)                                                                        \
    X(vkGetPhysicalDeviceMemoryProperties)                                                                             \
    X(vkGetPhysicalDeviceQueueFamilyProperties)                                                                        \
    X(vkEnumerateDeviceExtensionProperties)                                                                            \
    X(vkCreateDevice)                                                                                                  \
    X(vkGetDeviceProcAddr)                                                                                             \
    X(vkDestroySurfaceKHR)                                                                                             \
    X(vkGetPhysicalDeviceSurfaceSupportKHR)                                                                            \
    X(vkGetPhysicalDeviceSurfaceCapabilitiesKHR)                                                                       \
    X(vkGetPhysicalDeviceSurfaceFormatsKHR)                                                                            \
    X(vkGetPhysicalDeviceSurfacePresentModesKHR)                                                                       \
    X(vkCreateAndroidSurfaceKHR)                                                                                       \
    X(vkGetPhysicalDeviceFeatures2) /* 1.1; null on a 1.0 instance */                                                \
    X(vkGetPhysicalDeviceProperties2) /* 1.1 */

// Loaded from the device
#define ALPHA3_VK_DEVICE_FUNCTIONS(X)                                                                                   \
    X(vkDestroyDevice)                                                                                                 \
    X(vkGetDeviceQueue)                                                                                                \
    X(vkDeviceWaitIdle)                                                                                                \
    X(vkQueueSubmit)                                                                                                   \
    X(vkQueueWaitIdle)                                                                                                 \
    X(vkAllocateMemory)                                                                                                \
    X(vkFreeMemory)                                                                                                    \
    X(vkMapMemory)                                                                                                     \
    X(vkUnmapMemory)                                                                                                   \
    X(vkFlushMappedMemoryRanges)                                                                                       \
    X(vkInvalidateMappedMemoryRanges)                                                                                  \
    X(vkBindBufferMemory)                                                                                              \
    X(vkBindImageMemory)                                                                                               \
    X(vkGetBufferMemoryRequirements)                                                                                   \
    X(vkGetImageMemoryRequirements)                                                                                    \
    X(vkCreateFence)                                                                                                   \
    X(vkDestroyFence)                                                                                                  \
    X(vkResetFences)                                                                                                   \
    X(vkGetFenceStatus)                                                                                                \
    X(vkWaitForFences)                                                                                                 \
    X(vkCreateSemaphore)                                                                                               \
    X(vkDestroySemaphore)                                                                                              \
    X(vkCreateQueryPool)                                                                                               \
    X(vkDestroyQueryPool)                                                                                              \
    X(vkGetQueryPoolResults)                                                                                           \
    X(vkCreateBuffer)                                                                                                  \
    X(vkDestroyBuffer)                                                                                                 \
    X(vkCreateImage)                                                                                                   \
    X(vkDestroyImage)                                                                                                  \
    X(vkCreateImageView)                                                                                               \
    X(vkDestroyImageView)                                                                                              \
    X(vkCreateShaderModule)                                                                                            \
    X(vkDestroyShaderModule)                                                                                           \
    X(vkCreatePipelineCache)                                                                                           \
    X(vkDestroyPipelineCache)                                                                                          \
    X(vkGetPipelineCacheData)                                                                                          \
    X(vkCreateGraphicsPipelines)                                                                                       \
    X(vkCreateComputePipelines)                                                                                        \
    X(vkDestroyPipeline)                                                                                               \
    X(vkCreatePipelineLayout)                                                                                          \
    X(vkDestroyPipelineLayout)                                                                                         \
    X(vkCreateSampler)                                                                                                 \
    X(vkDestroySampler)                                                                                                \
    X(vkCreateDescriptorSetLayout)                                                                                     \
    X(vkDestroyDescriptorSetLayout)                                                                                    \
    X(vkCreateDescriptorPool)                                                                                          \
    X(vkDestroyDescriptorPool)                                                                                         \
    X(vkResetDescriptorPool)                                                                                           \
    X(vkAllocateDescriptorSets)                                                                                        \
    X(vkFreeDescriptorSets)                                                                                            \
    X(vkUpdateDescriptorSets)                                                                                          \
    X(vkCreateFramebuffer)                                                                                             \
    X(vkDestroyFramebuffer)                                                                                            \
    X(vkCreateRenderPass)                                                                                              \
    X(vkDestroyRenderPass)                                                                                             \
    X(vkCreateCommandPool)                                                                                             \
    X(vkDestroyCommandPool)                                                                                            \
    X(vkResetCommandPool)                                                                                              \
    X(vkAllocateCommandBuffers)                                                                                        \
    X(vkFreeCommandBuffers)                                                                                            \
    X(vkBeginCommandBuffer)                                                                                            \
    X(vkEndCommandBuffer)                                                                                              \
    X(vkCmdBindPipeline)                                                                                               \
    X(vkCmdSetViewport)                                                                                                \
    X(vkCmdSetScissor)                                                                                                 \
    X(vkCmdSetDepthBias)                                                                                               \
    X(vkCmdSetStencilReference)                                                                                        \
    X(vkCmdSetStencilCompareMask)                                                                                      \
    X(vkCmdSetStencilWriteMask)                                                                                        \
    X(vkCmdSetBlendConstants)                                                                                          \
    X(vkCmdBindDescriptorSets)                                                                                         \
    X(vkCmdBindIndexBuffer)                                                                                            \
    X(vkCmdBindVertexBuffers)                                                                                          \
    X(vkCmdDraw)                                                                                                       \
    X(vkCmdDispatch)                                                                                                   \
    X(vkCmdDrawIndexed)                                                                                                \
    X(vkCmdCopyBuffer)                                                                                                 \
    X(vkCmdCopyImage)                                                                                                  \
    X(vkCmdBlitImage)                                                                                                  \
    X(vkCmdCopyImageToBuffer)                                                                                          \
    X(vkCmdCopyBufferToImage)                                                                                          \
    X(vkCmdClearColorImage)                                                                                            \
    X(vkCmdClearDepthStencilImage)                                                                                     \
    X(vkCmdClearAttachments)                                                                                           \
    X(vkCmdPipelineBarrier)                                                                                            \
    X(vkCmdBeginQuery)                                                                                                 \
    X(vkCmdEndQuery)                                                                                                   \
    X(vkCmdResetQueryPool)                                                                                             \
    X(vkCmdWriteTimestamp)                                                                                             \
    X(vkCmdPushConstants)                                                                                              \
    X(vkCmdBeginRenderPass)                                                                                            \
    X(vkCmdEndRenderPass)                                                                                              \
    X(vkCreateSwapchainKHR)                                                                                            \
    X(vkDestroySwapchainKHR)                                                                                           \
    X(vkGetSwapchainImagesKHR)                                                                                         \
    X(vkAcquireNextImageKHR)                                                                                           \
    X(vkQueuePresentKHR)                                                                                               \
    X(vkGetBufferDeviceAddress) /* 1.2; null below */                                                                \
    X(vkCreateAccelerationStructureKHR) /* VK_KHR_acceleration_structure; null without it */                        \
    X(vkDestroyAccelerationStructureKHR)                                                                               \
    X(vkGetAccelerationStructureBuildSizesKHR)                                                                         \
    X(vkGetAccelerationStructureDeviceAddressKHR)                                                                      \
    X(vkCmdBuildAccelerationStructuresKHR)

namespace Alpha3::Vk
{
#define ALPHA3_VK_DECLARE(name) extern PFN_##name name;
    extern PFN_vkGetInstanceProcAddr vkGetInstanceProcAddr;
    ALPHA3_VK_GLOBAL_FUNCTIONS(ALPHA3_VK_DECLARE)
    ALPHA3_VK_INSTANCE_FUNCTIONS(ALPHA3_VK_DECLARE)
    ALPHA3_VK_DEVICE_FUNCTIONS(ALPHA3_VK_DECLARE)
#undef ALPHA3_VK_DECLARE

    // Finds vkGetInstanceProcAddr and the global functions: false when there is no Vulkan (the reason is logged)
    bool loadGlobal();
    void loadInstance(VkInstance instance);
    void loadDevice(VkDevice device);

    // VK_SUCCESS as "success", VK_ERROR_DEVICE_LOST as "VK_ERROR_DEVICE_LOST", ...
    const char* resultName(VkResult result);
}

#endif
