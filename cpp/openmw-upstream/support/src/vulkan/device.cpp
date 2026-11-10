#include "device.hpp"

#include <cstring>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <memory>
#include <mutex>
#include <vector>

#define VMA_STATIC_VULKAN_FUNCTIONS 0
#define VMA_DYNAMIC_VULKAN_FUNCTIONS 1
#define VMA_VULKAN_VERSION 1000000
#include <vk_mem_alloc.h>

#include <components/debug/debuglog.hpp>

#include "../support.hpp"

namespace Alpha3::Vk
{
    std::filesystem::path pipelineCacheFile()
    {
        return cacheDirectory() / "vulkan" / "pipelines.bin";
    }

    void savePipelineCache(const Device& device)
    {
        if (device.mPipelineCache == VK_NULL_HANDLE)
            return;
        size_t size = 0;
        if (vkGetPipelineCacheData(device.mDevice, device.mPipelineCache, &size, nullptr) != VK_SUCCESS || size == 0)
            return;
        std::vector<char> data(size);
        if (vkGetPipelineCacheData(device.mDevice, device.mPipelineCache, &size, data.data()) != VK_SUCCESS)
            return;
        const std::filesystem::path file = pipelineCacheFile();
        std::error_code ec;
        std::filesystem::create_directories(file.parent_path(), ec);
        const std::filesystem::path temporary = file.string() + ".tmp";
        {
            std::ofstream out(temporary, std::ios::binary);
            out.write(data.data(), static_cast<std::streamsize>(size));
            if (!out)
                return;
        }
        std::filesystem::rename(temporary, file, ec);
    }

    namespace
    {
        bool hasExtension(const std::vector<VkExtensionProperties>& list, const char* name)
        {
            for (const VkExtensionProperties& e : list)
                if (std::strcmp(e.extensionName, name) == 0)
                    return true;
            return false;
        }

        VkInstance createInstance(bool quiet)
        {
            if (!loadGlobal())
                return VK_NULL_HANDLE;
            uint32_t count = 0;
            vkEnumerateInstanceExtensionProperties(nullptr, &count, nullptr);
            std::vector<VkExtensionProperties> extensions(count);
            vkEnumerateInstanceExtensionProperties(nullptr, &count, extensions.data());
            for (const char* needed : { VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME })
                if (!hasExtension(extensions, needed))
                {
                    Log(Debug::Warning) << "Vulkan: the driver has no " << needed;
                    return VK_NULL_HANDLE;
                }
            const char* enabled[] = { VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME };

            VkApplicationInfo app{ VK_STRUCTURE_TYPE_APPLICATION_INFO };
            app.pApplicationName = "Alpha3 OpenMW";
            app.pEngineName = "OpenMW";
            app.apiVersion = VK_API_VERSION_1_0;
            VkInstanceCreateInfo info{ VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO };
            info.pApplicationInfo = &app;
            info.enabledExtensionCount = 2;
            info.ppEnabledExtensionNames = enabled;
            VkInstance instance = VK_NULL_HANDLE;
            const VkResult result = vkCreateInstance(&info, nullptr, &instance);
            if (result != VK_SUCCESS)
            {
                Log(Debug::Warning) << "Vulkan: vkCreateInstance: " << resultName(result);
                return VK_NULL_HANDLE;
            }
            loadInstance(instance);
            (void)quiet;
            return instance;
        }

        // The first GPU with a queue that does graphics (and so transfers); Android's queues all present
        bool pickGpu(VkInstance instance, VkPhysicalDevice& physical, uint32_t& family)
        {
            uint32_t count = 0;
            vkEnumeratePhysicalDevices(instance, &count, nullptr);
            std::vector<VkPhysicalDevice> gpus(count);
            vkEnumeratePhysicalDevices(instance, &count, gpus.data());
            for (VkPhysicalDevice gpu : gpus)
            {
                uint32_t queues = 0;
                vkGetPhysicalDeviceQueueFamilyProperties(gpu, &queues, nullptr);
                std::vector<VkQueueFamilyProperties> families(queues);
                vkGetPhysicalDeviceQueueFamilyProperties(gpu, &queues, families.data());
                uint32_t extensionCount = 0;
                vkEnumerateDeviceExtensionProperties(gpu, nullptr, &extensionCount, nullptr);
                std::vector<VkExtensionProperties> extensions(extensionCount);
                vkEnumerateDeviceExtensionProperties(gpu, nullptr, &extensionCount, extensions.data());
                if (!hasExtension(extensions, VK_KHR_SWAPCHAIN_EXTENSION_NAME))
                    continue;
                for (uint32_t i = 0; i < queues; ++i)
                    if (families[i].queueFlags & VK_QUEUE_GRAPHICS_BIT)
                    {
                        physical = gpu;
                        family = i;
                        return true;
                    }
            }
            return false;
        }

        bool sampledFormat(VkPhysicalDevice gpu, VkFormat format)
        {
            VkFormatProperties properties{};
            vkGetPhysicalDeviceFormatProperties(gpu, format, &properties);
            return (properties.optimalTilingFeatures & VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT) != 0;
        }

        std::mutex sMutex;
        Device* sDevice = nullptr;
        bool sTried = false;

        Device* create()
        {
            auto device = std::make_unique<Device>();
            device->mInstance = createInstance(false);
            if (device->mInstance == VK_NULL_HANDLE)
                return nullptr;
            if (!pickGpu(device->mInstance, device->mPhysical, device->mQueueFamily))
            {
                Log(Debug::Error) << "Vulkan: no GPU with a graphics queue and swap chains";
                vkDestroyInstance(device->mInstance, nullptr);
                return nullptr;
            }
            vkGetPhysicalDeviceProperties(device->mPhysical, &device->mProperties);
            VkPhysicalDeviceFeatures available{};
            vkGetPhysicalDeviceFeatures(device->mPhysical, &available);

            // what the renderer uses when it is there; nothing is required
            VkPhysicalDeviceFeatures& enabled = device->mFeatures;
            enabled.samplerAnisotropy = available.samplerAnisotropy;
            enabled.textureCompressionBC = available.textureCompressionBC;
            enabled.textureCompressionETC2 = available.textureCompressionETC2;
            enabled.textureCompressionASTC_LDR = available.textureCompressionASTC_LDR;
            enabled.depthClamp = available.depthClamp;
            enabled.depthBiasClamp = available.depthBiasClamp;
            enabled.fillModeNonSolid = available.fillModeNonSolid;
            enabled.wideLines = available.wideLines;
            enabled.largePoints = available.largePoints;
            enabled.shaderClipDistance = available.shaderClipDistance;
            enabled.fragmentStoresAndAtomics = available.fragmentStoresAndAtomics;
            enabled.independentBlend = available.independentBlend;
            enabled.occlusionQueryPrecise = available.occlusionQueryPrecise;
            enabled.fullDrawIndexUint32 = available.fullDrawIndexUint32;

            const float priority = 1.f;
            VkDeviceQueueCreateInfo queue{ VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO };
            queue.queueFamilyIndex = device->mQueueFamily;
            queue.queueCount = 1;
            queue.pQueuePriorities = &priority;
            const char* extensions[] = { VK_KHR_SWAPCHAIN_EXTENSION_NAME };
            VkDeviceCreateInfo info{ VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO };
            info.queueCreateInfoCount = 1;
            info.pQueueCreateInfos = &queue;
            info.enabledExtensionCount = 1;
            info.ppEnabledExtensionNames = extensions;
            info.pEnabledFeatures = &enabled;
            VkResult result = vkCreateDevice(device->mPhysical, &info, nullptr, &device->mDevice);
            if (result != VK_SUCCESS)
            {
                Log(Debug::Error) << "Vulkan: vkCreateDevice: " << resultName(result);
                vkDestroyInstance(device->mInstance, nullptr);
                return nullptr;
            }
            loadDevice(device->mDevice);
            vkGetDeviceQueue(device->mDevice, device->mQueueFamily, 0, &device->mQueue);

            VmaVulkanFunctions functions{};
            functions.vkGetInstanceProcAddr = vkGetInstanceProcAddr;
            functions.vkGetDeviceProcAddr = vkGetDeviceProcAddr;
            VmaAllocatorCreateInfo allocatorInfo{};
            allocatorInfo.vulkanApiVersion = VK_API_VERSION_1_0;
            allocatorInfo.physicalDevice = device->mPhysical;
            allocatorInfo.device = device->mDevice;
            allocatorInfo.instance = device->mInstance;
            allocatorInfo.pVulkanFunctions = &functions;
            result = vmaCreateAllocator(&allocatorInfo, &device->mAllocator);
            if (result != VK_SUCCESS)
            {
                Log(Debug::Error) << "Vulkan: vmaCreateAllocator: " << resultName(result);
                vkDestroyDevice(device->mDevice, nullptr);
                vkDestroyInstance(device->mInstance, nullptr);
                return nullptr;
            }

            // the pipelines the driver made in earlier runs (Programs saves it); the driver checks the data is its own
            VkPipelineCacheCreateInfo cacheInfo{ VK_STRUCTURE_TYPE_PIPELINE_CACHE_CREATE_INFO };
            std::string cached;
            {
                std::ifstream in(pipelineCacheFile(), std::ios::binary);
                if (in)
                    cached.assign(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
            }
            cacheInfo.initialDataSize = cached.size();
            cacheInfo.pInitialData = cached.empty() ? nullptr : cached.data();
            if (vkCreatePipelineCache(device->mDevice, &cacheInfo, nullptr, &device->mPipelineCache) != VK_SUCCESS
                && !cached.empty())
            {
                cacheInfo.initialDataSize = 0;
                cacheInfo.pInitialData = nullptr;
                vkCreatePipelineCache(device->mDevice, &cacheInfo, nullptr, &device->mPipelineCache);
            }
            else if (!cached.empty())
                Log(Debug::Info) << "Vulkan: pipeline cache of " << cached.size() / 1024 << " KiB loaded";

            device->mBcTextures = enabled.textureCompressionBC && sampledFormat(device->mPhysical, VK_FORMAT_BC1_RGBA_UNORM_BLOCK)
                && sampledFormat(device->mPhysical, VK_FORMAT_BC3_UNORM_BLOCK);
            device->mEtc2Textures
                = enabled.textureCompressionETC2 && sampledFormat(device->mPhysical, VK_FORMAT_ETC2_R8G8B8A8_UNORM_BLOCK);
            device->mAstcTextures
                = enabled.textureCompressionASTC_LDR && sampledFormat(device->mPhysical, VK_FORMAT_ASTC_4x4_UNORM_BLOCK);
            for (VkFormat format : { VK_FORMAT_D24_UNORM_S8_UINT, VK_FORMAT_D32_SFLOAT_S8_UINT })
            {
                VkFormatProperties properties{};
                vkGetPhysicalDeviceFormatProperties(device->mPhysical, format, &properties);
                if (properties.optimalTilingFeatures & VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT)
                {
                    device->mDepthFormat = format;
                    break;
                }
            }

            const VkPhysicalDeviceProperties& p = device->mProperties;
            Log(Debug::Info) << "Vulkan: " << p.deviceName << ", API " << VK_VERSION_MAJOR(p.apiVersion) << "."
                             << VK_VERSION_MINOR(p.apiVersion) << "." << VK_VERSION_PATCH(p.apiVersion) << ", driver "
                             << p.driverVersion << " (renderer asks for 1.0); BC textures "
                             << (device->mBcTextures ? "yes" : "no") << ", ETC2 " << (device->mEtc2Textures ? "yes" : "no")
                             << ", ASTC " << (device->mAstcTextures ? "yes" : "no");
            return device.release();
        }
    }

    Device* device()
    {
        std::lock_guard lock(sMutex);
        if (!sTried)
        {
            sTried = true;
            sDevice = create();
        }
        return sDevice;
    }

    bool probe()
    {
        VkInstance instance = createInstance(true);
        if (instance == VK_NULL_HANDLE)
            return false;
        VkPhysicalDevice physical = VK_NULL_HANDLE;
        uint32_t family = 0;
        const bool found = pickGpu(instance, physical, family);
        if (found)
        {
            VkPhysicalDeviceProperties properties{};
            vkGetPhysicalDeviceProperties(physical, &properties);
            Log(Debug::Info) << "Vulkan: found " << properties.deviceName;
        }
        else
            Log(Debug::Warning) << "Vulkan: no GPU with a graphics queue and swap chains";
        vkDestroyInstance(instance, nullptr);
        return found;
    }

    void shutdown()
    {
        std::lock_guard lock(sMutex);
        if (sDevice == nullptr)
            return;
        vkDeviceWaitIdle(sDevice->mDevice);
    }
}
