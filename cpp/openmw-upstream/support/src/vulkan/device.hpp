#ifndef ALPHA3_SUPPORT_VULKAN_DEVICE_H
#define ALPHA3_SUPPORT_VULKAN_DEVICE_H

#include "vk.hpp"

#include <cstdint>
#include <filesystem>

struct VmaAllocator_T;
struct VmaAllocation_T;

namespace Alpha3::Vk
{
    // The instance, the GPU and its device: Vulkan 1.0 core, with VK_KHR_surface, VK_KHR_android_surface and
    // VK_KHR_swapchain the only extensions required. One queue does graphics, transfers and presents.
    struct Device
    {
        VkInstance mInstance = VK_NULL_HANDLE;
        VkPhysicalDevice mPhysical = VK_NULL_HANDLE;
        VkDevice mDevice = VK_NULL_HANDLE;
        VkQueue mQueue = VK_NULL_HANDLE;
        uint32_t mQueueFamily = 0;
        VmaAllocator_T* mAllocator = nullptr;
        VkPhysicalDeviceProperties mProperties{};
        VkPhysicalDeviceFeatures mFeatures{}; // what was enabled
        VkPipelineCache mPipelineCache = VK_NULL_HANDLE;

        // What the GPU can do beyond the 1.0 minimum, read once
        bool mBcTextures = false; // DXT1/3/5 (S3TC) sampled as they are
        bool mEtc2Textures = false;
        bool mAstcTextures = false;
        VkFormat mDepthFormat = VK_FORMAT_UNDEFINED; // a depth and stencil format for attachments that are sampled too
    };

    // The device, made on the first call (null when Vulkan cannot start; the reason is logged)
    Device* device();

    // The pipeline cache on disk (the cache directory's vulkan/pipelines.bin): loaded with the device, saved by
    // savePipelineCache (the process is killed, not closed, so it is saved as it grows)
    std::filesystem::path pipelineCacheFile();
    void savePipelineCache(const Device& device);

    // Whether this phone can run the renderer at all (an instance and a GPU with a graphics queue), before the window
    // is made: false means OpenGL ES. Leaves nothing behind.
    bool probe();

    // Waits for the GPU and destroys everything (at exit)
    void shutdown();
}

#endif
