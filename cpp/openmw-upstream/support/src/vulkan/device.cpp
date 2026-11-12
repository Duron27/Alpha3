#include "device.hpp"

#include <algorithm>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <memory>
#include <mutex>
#include <vector>

#define VMA_STATIC_VULKAN_FUNCTIONS 0
#define VMA_DYNAMIC_VULKAN_FUNCTIONS 1
#define VMA_VULKAN_VERSION 1002000
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

        const char* versionName(uint32_t version)
        {
            if (version >= VK_API_VERSION_1_2)
                return "1.2";
            if (version >= VK_API_VERSION_1_1)
                return "1.1";
            return "1.0";
        }

        // 1.2, else 1.1, else 1.0: the highest of the three the loader takes (a 1.0 loader refuses any apiVersion
        // above 1.0 with VK_ERROR_INCOMPATIBLE_DRIVER; a later one takes any, whatever its own version)
        VkInstance createInstance(bool quiet, uint32_t* version = nullptr)
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
            uint32_t loader = VK_API_VERSION_1_0;
            if (vkEnumerateInstanceVersion == nullptr || vkEnumerateInstanceVersion(&loader) != VK_SUCCESS)
                loader = VK_API_VERSION_1_0;
            VkInstanceCreateInfo info{ VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO };
            info.pApplicationInfo = &app;
            info.enabledExtensionCount = 2;
            info.ppEnabledExtensionNames = enabled;
            VkResult result = VK_ERROR_INCOMPATIBLE_DRIVER;
            for (uint32_t tried : { VK_API_VERSION_1_2, VK_API_VERSION_1_1, VK_API_VERSION_1_0 })
            {
                // a 1.0 loader refuses any apiVersion above 1.0; a 1.1 or later one takes any, and the device's own
                // version decides what the device gets (Samsung's Android 12 loader says 1.1 while Turnip has 1.3)
                if (loader < VK_API_VERSION_1_1 && tried != VK_API_VERSION_1_0)
                    continue;
                app.apiVersion = tried;
                VkInstance instance = VK_NULL_HANDLE;
                result = vkCreateInstance(&info, nullptr, &instance);
                if (result == VK_SUCCESS)
                {
                    loadInstance(instance);
                    if (!quiet)
                        Log(Debug::Info) << "Vulkan: instance " << versionName(tried) << " (the loader has "
                                         << VK_VERSION_MAJOR(loader) << "." << VK_VERSION_MINOR(loader) << ")";
                    if (version != nullptr)
                        *version = tried;
                    return instance;
                }
                if (result != VK_ERROR_INCOMPATIBLE_DRIVER)
                    break;
                Log(Debug::Info) << "Vulkan: no " << versionName(tried) << " instance, trying the next version down";
            }
            Log(Debug::Warning) << "Vulkan: vkCreateInstance: " << resultName(result);
            return VK_NULL_HANDLE;
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
            uint32_t instanceVersion = VK_API_VERSION_1_0;
            device->mInstance = createInstance(false, &instanceVersion);
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

            // the version: the lowest of the instance's and the GPU's, as 1.0, 1.1 or 1.2
            {
                const uint32_t gpu = device->mProperties.apiVersion;
                uint32_t version = VK_API_VERSION_1_0;
                if (gpu >= VK_API_VERSION_1_2)
                    version = VK_API_VERSION_1_2;
                else if (gpu >= VK_API_VERSION_1_1)
                    version = VK_API_VERSION_1_1;
                device->mApiVersion = std::min(version, instanceVersion);
                if (device->mApiVersion >= VK_API_VERSION_1_1 && vkGetPhysicalDeviceFeatures2 == nullptr)
                    device->mApiVersion = VK_API_VERSION_1_0;
            }
            uint32_t extensionCount = 0;
            vkEnumerateDeviceExtensionProperties(device->mPhysical, nullptr, &extensionCount, nullptr);
            std::vector<VkExtensionProperties> deviceExtensions(extensionCount);
            vkEnumerateDeviceExtensionProperties(device->mPhysical, nullptr, &extensionCount, deviceExtensions.data());

            // what 1.1 and 1.2 add, read through vkGetPhysicalDeviceFeatures2 and enabled in the same chain
            VkPhysicalDeviceVulkan11Features available11{ VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES };
            VkPhysicalDeviceVulkan12Features available12{ VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES };
            VkPhysicalDeviceAccelerationStructureFeaturesKHR availableAccel{
                VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR
            };
            VkPhysicalDeviceRayQueryFeaturesKHR availableRayQuery{ VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_QUERY_FEATURES_KHR };
            const bool rayExtensions = device->mApiVersion >= VK_API_VERSION_1_2
                && hasExtension(deviceExtensions, VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME)
                && hasExtension(deviceExtensions, VK_KHR_RAY_QUERY_EXTENSION_NAME)
                && hasExtension(deviceExtensions, VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME);
            if (device->mApiVersion >= VK_API_VERSION_1_1)
            {
                VkPhysicalDeviceFeatures2 query{ VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2 };
                if (device->mApiVersion >= VK_API_VERSION_1_2)
                {
                    // VkPhysicalDeviceVulkan11Features is 1.2's; on 1.1 the 1.1 features are left off
                    query.pNext = &available11;
                    available11.pNext = &available12;
                    if (rayExtensions)
                    {
                        available12.pNext = &availableAccel;
                        availableAccel.pNext = &availableRayQuery;
                    }
                }
                vkGetPhysicalDeviceFeatures2(device->mPhysical, &query);
            }

            // what the renderer uses when it is there; nothing is required
            VkPhysicalDeviceFeatures& enabled = device->mFeatures;
            enabled.samplerAnisotropy = available.samplerAnisotropy;
            enabled.textureCompressionBC = available.textureCompressionBC;
            enabled.textureCompressionETC2 = available.textureCompressionETC2;
            enabled.textureCompressionASTC_LDR = available.textureCompressionASTC_LDR;
            // how much of the screen the water covers, for its reflection (the translator's water queries)
            enabled.occlusionQueryPrecise = available.occlusionQueryPrecise;
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
            std::vector<const char*> extensions{ VK_KHR_SWAPCHAIN_EXTENSION_NAME };
            VkDeviceCreateInfo info{ VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO };
            info.queueCreateInfoCount = 1;
            info.pQueueCreateInfos = &queue;

            // 1.1 and 1.2: only what the GPU has, so the device is made whatever it lacks
            VkPhysicalDeviceVulkan11Features enabled11{ VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_1_FEATURES };
            VkPhysicalDeviceVulkan12Features enabled12{ VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_VULKAN_1_2_FEATURES };
            VkPhysicalDeviceAccelerationStructureFeaturesKHR enabledAccel{
                VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_ACCELERATION_STRUCTURE_FEATURES_KHR
            };
            VkPhysicalDeviceRayQueryFeaturesKHR enabledRayQuery{ VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_RAY_QUERY_FEATURES_KHR };
            VkPhysicalDeviceFeatures2 features2{ VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FEATURES_2 };
            if (device->mApiVersion >= VK_API_VERSION_1_2)
            {
                enabled11.storageBuffer16BitAccess = available11.storageBuffer16BitAccess;
                enabled11.uniformAndStorageBuffer16BitAccess = available11.uniformAndStorageBuffer16BitAccess;
                enabled11.shaderDrawParameters = available11.shaderDrawParameters;
                enabled12.timelineSemaphore = available12.timelineSemaphore;
                enabled12.hostQueryReset = available12.hostQueryReset;
                enabled12.scalarBlockLayout = available12.scalarBlockLayout;
                enabled12.shaderFloat16 = available12.shaderFloat16;
                enabled12.uniformBufferStandardLayout = available12.uniformBufferStandardLayout;
                enabled12.samplerMirrorClampToEdge = available12.samplerMirrorClampToEdge;
                enabled12.drawIndirectCount = available12.drawIndirectCount;
                enabled12.imagelessFramebuffer = available12.imagelessFramebuffer;
                enabled12.separateDepthStencilLayouts = available12.separateDepthStencilLayouts;
                enabled12.bufferDeviceAddress = available12.bufferDeviceAddress;
                const bool indexing = available12.descriptorIndexing && available12.runtimeDescriptorArray
                    && available12.descriptorBindingPartiallyBound
                    && available12.shaderSampledImageArrayNonUniformIndexing;
                if (indexing)
                {
                    enabled12.descriptorIndexing = VK_TRUE;
                    enabled12.runtimeDescriptorArray = VK_TRUE;
                    enabled12.descriptorBindingPartiallyBound = VK_TRUE;
                    enabled12.shaderSampledImageArrayNonUniformIndexing = VK_TRUE;
                    enabled12.descriptorBindingVariableDescriptorCount
                        = available12.descriptorBindingVariableDescriptorCount;
                }
                device->mTimelineSemaphores = enabled12.timelineSemaphore;
                device->mDescriptorIndexing = indexing;
                device->mBufferDeviceAddress = enabled12.bufferDeviceAddress;
                device->mScalarBlockLayout = enabled12.scalarBlockLayout;
                device->mShaderFloat16 = enabled12.shaderFloat16;
                device->mHostQueryReset = enabled12.hostQueryReset;

                features2.pNext = &enabled11;
                enabled11.pNext = &enabled12;
                if (rayExtensions && availableAccel.accelerationStructure && availableRayQuery.rayQuery
                    && device->mBufferDeviceAddress && indexing)
                {
                    enabledAccel.accelerationStructure = VK_TRUE;
                    enabledRayQuery.rayQuery = VK_TRUE;
                    enabled12.pNext = &enabledAccel;
                    enabledAccel.pNext = &enabledRayQuery;
                    extensions.push_back(VK_KHR_ACCELERATION_STRUCTURE_EXTENSION_NAME);
                    extensions.push_back(VK_KHR_RAY_QUERY_EXTENSION_NAME);
                    extensions.push_back(VK_KHR_DEFERRED_HOST_OPERATIONS_EXTENSION_NAME);
                    device->mRayQuery = true;
                }
            }
            if (device->mApiVersion >= VK_API_VERSION_1_1)
            {
                // the 1.0 features go in the chain too (pEnabledFeatures must then be null)
                features2.features = enabled;
                info.pNext = &features2;
            }
            else
                info.pEnabledFeatures = &enabled;
            info.enabledExtensionCount = static_cast<uint32_t>(extensions.size());
            info.ppEnabledExtensionNames = extensions.data();
            VkResult result = vkCreateDevice(device->mPhysical, &info, nullptr, &device->mDevice);
            if (result != VK_SUCCESS && device->mApiVersion >= VK_API_VERSION_1_1)
            {
                // a driver that lists what it then refuses: the 1.0 device the renderer was written for
                Log(Debug::Warning) << "Vulkan: vkCreateDevice (" << versionName(device->mApiVersion)
                                    << "): " << resultName(result) << ", making a 1.0 device";
                extensions.resize(1);
                info.pNext = nullptr;
                info.pEnabledFeatures = &enabled;
                info.enabledExtensionCount = 1;
                device->mApiVersion = VK_API_VERSION_1_0;
                device->mTimelineSemaphores = device->mDescriptorIndexing = device->mBufferDeviceAddress = false;
                device->mScalarBlockLayout = device->mShaderFloat16 = device->mHostQueryReset = false;
                device->mRayQuery = false;
                result = vkCreateDevice(device->mPhysical, &info, nullptr, &device->mDevice);
            }
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
            allocatorInfo.vulkanApiVersion = device->mApiVersion;
            if (device->mBufferDeviceAddress)
                allocatorInfo.flags |= VMA_ALLOCATOR_CREATE_BUFFER_DEVICE_ADDRESS_BIT;
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
                             << p.driverVersion << ", using " << versionName(device->mApiVersion) << "; BC textures "
                             << (device->mBcTextures ? "yes" : "no") << ", ETC2 " << (device->mEtc2Textures ? "yes" : "no")
                             << ", ASTC " << (device->mAstcTextures ? "yes" : "no");
            Log(Debug::Info) << "Vulkan: timeline semaphores " << (device->mTimelineSemaphores ? "yes" : "no")
                             << ", descriptor indexing " << (device->mDescriptorIndexing ? "yes" : "no")
                             << ", buffer device address " << (device->mBufferDeviceAddress ? "yes" : "no")
                             << ", float16 " << (device->mShaderFloat16 ? "yes" : "no") << ", ray queries "
                             << (device->mRayQuery ? "yes" : "no (needs 1.2 and VK_KHR_ray_query)");
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
