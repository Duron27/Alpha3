#include "device.hpp"

#include <algorithm>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <iterator>
#include <memory>
#include <mutex>
#include <sstream>
#include <vector>

#define VMA_STATIC_VULKAN_FUNCTIONS 0
#define VMA_DYNAMIC_VULKAN_FUNCTIONS 1
#define VMA_VULKAN_VERSION 1002000
#include <vk_mem_alloc.h>

#include <atomic>

#include <components/debug/debuglog.hpp>

#include "../support.hpp"

namespace Alpha3
{
    namespace
    {
        // set once the device is made, before any texture is read; textures are read on several threads
        std::atomic<unsigned> sTextureFormats{ 0 };
        constexpr unsigned sKnown = 1, sBc = 2, sEtc2 = 4, sAstc = 8;
    }

    VulkanTextureFormats vulkanTextureFormats()
    {
        const unsigned bits = sTextureFormats.load(std::memory_order_acquire);
        VulkanTextureFormats formats;
        formats.mKnown = (bits & sKnown) != 0;
        formats.mBc = (bits & sBc) != 0;
        formats.mEtc2 = (bits & sEtc2) != 0;
        formats.mAstc = (bits & sAstc) != 0;
        return formats;
    }
}

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

        std::string versionText(uint32_t version)
        {
            return std::to_string(VK_VERSION_MAJOR(version)) + "." + std::to_string(VK_VERSION_MINOR(version)) + "."
                + std::to_string(VK_VERSION_PATCH(version));
        }

        const char* vendorName(uint32_t vendor)
        {
            switch (vendor)
            {
                case 0x13B5:
                    return "ARM (Mali)";
                case 0x5143:
                    return "Qualcomm (Adreno)";
                case 0x1010:
                    return "Imagination (PowerVR)";
                case 0x144D:
                    return "Samsung (Xclipse)";
                case 0x10DE:
                    return "NVIDIA";
                case 0x1002:
                    return "AMD";
                case 0x8086:
                    return "Intel";
                case 0x10005:
                    return "Mesa (software)";
                default:
                    return "unknown";
            }
        }

        // the driver's version as its vendor packs it (Mali's driverInfo, where there is one, names the release: r38p1)
        std::string driverVersionText(uint32_t vendor, uint32_t version)
        {
            std::ostringstream out;
            out << version << " (0x" << std::hex << version << std::dec << ", ";
            if (vendor == 0x10DE)
                out << ((version >> 22) & 0x3ff) << "." << ((version >> 14) & 0xff) << "." << ((version >> 6) & 0xff);
            else if (vendor == 0x5143 && version >= 0x80000000u)
                out << "V@" << ((version >> 24) & 0x7f) << "." << ((version >> 12) & 0xfff); // 0x80000000 | major<<24
            else
                out << VK_VERSION_MAJOR(version) << "." << VK_VERSION_MINOR(version) << "." << VK_VERSION_PATCH(version);
            out << ")";
            return out.str();
        }

        // the driver's messages (VK_EXT_debug_utils, asked for with diagnostics()): the first 300, then every 100th
        VKAPI_ATTR VkBool32 VKAPI_CALL driverMessage(VkDebugUtilsMessageSeverityFlagBitsEXT severity,
            VkDebugUtilsMessageTypeFlagsEXT type, const VkDebugUtilsMessengerCallbackDataEXT* data, void*)
        {
            static std::atomic<unsigned> count{ 0 };
            const unsigned n = count++;
            if (n >= 300 && n % 100 != 0)
                return VK_FALSE;
            const char* level = (severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) ? "error"
                : (severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT)             ? "warning"
                : (severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT)                ? "info"
                                                                                           : "verbose";
            const char* kind = (type & VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT) ? "validation"
                : (type & VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)             ? "performance"
                                                                                       : "general";
            Log((severity & VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) ? Debug::Warning : Debug::Info)
                << "Vulkan driver " << level << " (" << kind << ", #" << n << "): "
                << (data && data->pMessageIdName ? data->pMessageIdName : "") << " "
                << (data && data->pMessage ? data->pMessage : "");
            return VK_FALSE;
        }
        VkDebugUtilsMessengerEXT sMessenger = VK_NULL_HANDLE;

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
            std::vector<const char*> enabled{ VK_KHR_SURFACE_EXTENSION_NAME, VK_KHR_ANDROID_SURFACE_EXTENSION_NAME };
            const bool debugUtils
                = !quiet && diagnostics() && hasExtension(extensions, VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
            if (debugUtils)
                enabled.push_back(VK_EXT_DEBUG_UTILS_EXTENSION_NAME);
            if (!quiet)
            {
                std::string names;
                for (const VkExtensionProperties& e : extensions)
                    names += std::string(" ") + e.extensionName;
                uint32_t layerCount = 0;
                vkEnumerateInstanceLayerProperties(&layerCount, nullptr);
                std::vector<VkLayerProperties> layers(layerCount);
                vkEnumerateInstanceLayerProperties(&layerCount, layers.data());
                std::string layerNames;
                for (const VkLayerProperties& l : layers)
                    layerNames += std::string(" ") + l.layerName;
                Log(Debug::Info) << "Vulkan: instance extensions:" << names << "; layers:"
                                 << (layerNames.empty() ? " none" : layerNames);
            }

            VkApplicationInfo app{ VK_STRUCTURE_TYPE_APPLICATION_INFO };
            app.pApplicationName = "Alpha3 OpenMW";
            app.pEngineName = "OpenMW";
            uint32_t loader = VK_API_VERSION_1_0;
            if (vkEnumerateInstanceVersion == nullptr || vkEnumerateInstanceVersion(&loader) != VK_SUCCESS)
                loader = VK_API_VERSION_1_0;
            VkInstanceCreateInfo info{ VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO };
            info.pApplicationInfo = &app;
            info.enabledExtensionCount = static_cast<uint32_t>(enabled.size());
            info.ppEnabledExtensionNames = enabled.data();
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
                    if (debugUtils && vkCreateDebugUtilsMessengerEXT != nullptr)
                    {
                        VkDebugUtilsMessengerCreateInfoEXT messenger{
                            VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT
                        };
                        messenger.messageSeverity = VK_DEBUG_UTILS_MESSAGE_SEVERITY_INFO_BIT_EXT
                            | VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT
                            | VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT;
                        messenger.messageType = VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT
                            | VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
                            | VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT;
                        messenger.pfnUserCallback = driverMessage;
                        const VkResult made
                            = vkCreateDebugUtilsMessengerEXT(instance, &messenger, nullptr, &sMessenger);
                        Log(Debug::Info) << "Vulkan: the driver's messages are logged (VK_EXT_debug_utils): "
                                         << resultName(made);
                    }
                    else if (!quiet && diagnostics())
                        Log(Debug::Info) << "Vulkan: the driver has no VK_EXT_debug_utils: no driver messages";
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

        std::string memoryFlags(VkMemoryPropertyFlags flags)
        {
            std::string out;
            if (flags & VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT)
                out += "device-local ";
            if (flags & VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT)
                out += "host-visible ";
            if (flags & VK_MEMORY_PROPERTY_HOST_COHERENT_BIT)
                out += "coherent ";
            if (flags & VK_MEMORY_PROPERTY_HOST_CACHED_BIT)
                out += "cached ";
            if (flags & VK_MEMORY_PROPERTY_LAZILY_ALLOCATED_BIT)
                out += "lazy ";
            if (out.empty())
                return "none";
            out.pop_back();
            return out;
        }

        // Once at start, at Info: everything about the GPU and driver a report from a phone we don't have needs (the
        // driver and its version, memory, limits, which formats can be sampled, filtered, blitted and drawn into)
        void report(Device& device, const VkPhysicalDeviceFeatures& available,
            const std::vector<VkExtensionProperties>& extensions)
        {
            const VkPhysicalDeviceProperties& p = device.mProperties;
            static const char* const types[] = { "other", "integrated", "discrete", "virtual", "CPU" };
            std::ostringstream out;
            out << "Vulkan: vendor 0x" << std::hex << p.vendorID << " " << vendorName(p.vendorID) << ", device 0x"
                << p.deviceID << std::dec << ", " << (p.deviceType <= 4 ? types[p.deviceType] : "?") << " GPU, API "
                << versionText(p.apiVersion) << ", driver version "
                << driverVersionText(p.vendorID, p.driverVersion);
            if (vkGetPhysicalDeviceProperties2 != nullptr && device.mApiVersion >= VK_API_VERSION_1_1
                && (device.mApiVersion >= VK_API_VERSION_1_2
                    || hasExtension(extensions, VK_KHR_DRIVER_PROPERTIES_EXTENSION_NAME)))
            {
                VkPhysicalDeviceDriverProperties driver{ VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_DRIVER_PROPERTIES };
                VkPhysicalDeviceProperties2 properties{ VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_PROPERTIES_2 };
                properties.pNext = &driver;
                vkGetPhysicalDeviceProperties2(device.mPhysical, &properties);
                out << "; driver " << driver.driverName << " (id " << driver.driverID << ") '" << driver.driverInfo
                    << "', conformance " << int(driver.conformanceVersion.major) << "."
                    << int(driver.conformanceVersion.minor) << "." << int(driver.conformanceVersion.subminor) << "."
                    << int(driver.conformanceVersion.patch);
                device.mDriverName = driver.driverName;
            }
            else
                out << "; no driver properties (needs 1.2 or VK_KHR_driver_properties)";
            Log(Debug::Info) << out.str();

            // memory: a phone's GPU shares the system's memory; what is device-local and host-visible matters for
            // how buffers are written
            {
                VkPhysicalDeviceMemoryProperties memory{};
                vkGetPhysicalDeviceMemoryProperties(device.mPhysical, &memory);
                std::ostringstream line;
                line << "Vulkan: memory heaps:";
                for (uint32_t i = 0; i < memory.memoryHeapCount; ++i)
                    line << " [" << i << "] " << memory.memoryHeaps[i].size / (1024 * 1024) << " MiB"
                         << ((memory.memoryHeaps[i].flags & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) ? " device-local" : "");
                line << "; types:";
                for (uint32_t i = 0; i < memory.memoryTypeCount; ++i)
                    line << " [" << i << "] heap " << memory.memoryTypes[i].heapIndex << " "
                         << memoryFlags(memory.memoryTypes[i].propertyFlags) << ";";
                line << " budget extension " << (device.mMemoryBudget ? "yes" : "no") << ", device fault extension "
                     << (device.mDeviceFault ? "yes" : "no");
                Log(Debug::Info) << line.str();
            }

            // the queues (timestamps: the GPU times in the periodic log line need timestampValidBits)
            {
                uint32_t count = 0;
                vkGetPhysicalDeviceQueueFamilyProperties(device.mPhysical, &count, nullptr);
                std::vector<VkQueueFamilyProperties> families(count);
                vkGetPhysicalDeviceQueueFamilyProperties(device.mPhysical, &count, families.data());
                std::ostringstream line;
                line << "Vulkan: queue families (using " << device.mQueueFamily << "):";
                for (uint32_t i = 0; i < count; ++i)
                    line << " [" << i << "] flags 0x" << std::hex << families[i].queueFlags << std::dec << " x"
                         << families[i].queueCount << " timestamp bits " << families[i].timestampValidBits << ";";
                Log(Debug::Info) << line.str();
            }

            const VkPhysicalDeviceLimits& l = p.limits;
            Log(Debug::Info) << "Vulkan: limits: image 2D " << l.maxImageDimension2D << ", cube "
                             << l.maxImageDimensionCube << ", layers " << l.maxImageArrayLayers << ", anisotropy "
                             << l.maxSamplerAnisotropy << ", LOD bias " << l.maxSamplerLodBias << ", uniform range "
                             << l.maxUniformBufferRange << ", storage range " << l.maxStorageBufferRange
                             << ", push constants " << l.maxPushConstantsSize << ", sets " << l.maxBoundDescriptorSets
                             << ", per stage: samplers " << l.maxPerStageDescriptorSamplers << ", sampled images "
                             << l.maxPerStageDescriptorSampledImages << ", uniform buffers "
                             << l.maxPerStageDescriptorUniformBuffers << ", storage buffers "
                             << l.maxPerStageDescriptorStorageBuffers << ", resources " << l.maxPerStageResources
                             << "; per set: samplers " << l.maxDescriptorSetSamplers << ", uniform buffers "
                             << l.maxDescriptorSetUniformBuffers << " (dynamic "
                             << l.maxDescriptorSetUniformBuffersDynamic << "), storage buffers "
                             << l.maxDescriptorSetStorageBuffers << "; vertex attributes "
                             << l.maxVertexInputAttributes << ", bindings " << l.maxVertexInputBindings
                             << ", stride " << l.maxVertexInputBindingStride << ", vertex outputs "
                             << l.maxVertexOutputComponents << ", fragment inputs " << l.maxFragmentInputComponents
                             << ", colour attachments " << l.maxColorAttachments << ", clip distances "
                             << l.maxClipDistances << ", max index " << l.maxDrawIndexedIndexValue
                             << "; alignment: uniform " << l.minUniformBufferOffsetAlignment << ", storage "
                             << l.minStorageBufferOffsetAlignment << ", copy " << l.optimalBufferCopyOffsetAlignment
                             << ", row " << l.optimalBufferCopyRowPitchAlignment << ", atom " << l.nonCoherentAtomSize
                             << ", granularity " << l.bufferImageGranularity << "; compute: invocations "
                             << l.maxComputeWorkGroupInvocations << ", shared " << l.maxComputeSharedMemorySize
                             << "; timestamps " << (l.timestampComputeAndGraphics ? "yes" : "no") << " period "
                             << l.timestampPeriod << " ns; allocations " << l.maxMemoryAllocationCount
                             << ", samplers " << l.maxSamplerAllocationCount;

            Log(Debug::Info) << "Vulkan: features the GPU has: anisotropy " << available.samplerAnisotropy << ", BC "
                             << available.textureCompressionBC << ", ETC2 " << available.textureCompressionETC2
                             << ", ASTC " << available.textureCompressionASTC_LDR << ", depth clamp "
                             << available.depthClamp << ", depth bias clamp " << available.depthBiasClamp
                             << ", clip distance " << available.shaderClipDistance << ", independent blend "
                             << available.independentBlend << ", 32 bit indices " << available.fullDrawIndexUint32
                             << ", precise occlusion " << available.occlusionQueryPrecise << ", fragment stores "
                             << available.fragmentStoresAndAtomics << ", wide lines " << available.wideLines
                             << ", large points " << available.largePoints << ", non-solid fill "
                             << available.fillModeNonSolid << ", geometry shader " << available.geometryShader
                             << ", float64 " << available.shaderFloat64 << ", int16 " << available.shaderInt16
                             << ", dynamic indexing (sampler arrays) "
                             << available.shaderSampledImageArrayDynamicIndexing << ", (uniform arrays) "
                             << available.shaderUniformBufferArrayDynamicIndexing;

            // every format the renderer may make an image in: s sampled, f filtered linearly, b blit source and
            // target, c colour attachment, a with blending, d depth attachment (optimal tiling). A texture in a
            // format without s or f samples as undefined (white or black on some drivers)
            {
                struct Named
                {
                    VkFormat mFormat;
                    const char* mName;
                };
                static const Named formats[] = { { VK_FORMAT_R8G8B8A8_UNORM, "RGBA8" },
                    { VK_FORMAT_B8G8R8A8_UNORM, "BGRA8" }, { VK_FORMAT_R8G8B8A8_SRGB, "RGBA8_SRGB" },
                    { VK_FORMAT_R8_UNORM, "R8" }, { VK_FORMAT_R8G8_UNORM, "RG8" },
                    { VK_FORMAT_R5G6B5_UNORM_PACK16, "R5G6B5" }, { VK_FORMAT_R16G16B16A16_UNORM, "RGBA16" },
                    { VK_FORMAT_R16_UNORM, "R16" }, { VK_FORMAT_R16G16B16A16_SFLOAT, "RGBA16F" },
                    { VK_FORMAT_R16_SFLOAT, "R16F" }, { VK_FORMAT_R16G16_SFLOAT, "RG16F" },
                    { VK_FORMAT_R32_SFLOAT, "R32F" }, { VK_FORMAT_R32G32_SFLOAT, "RG32F" },
                    { VK_FORMAT_R32G32B32A32_SFLOAT, "RGBA32F" }, { VK_FORMAT_A2B10G10R10_UNORM_PACK32, "A2B10G10R10" },
                    { VK_FORMAT_B10G11R11_UFLOAT_PACK32, "B10G11R11F" }, { VK_FORMAT_D16_UNORM, "D16" },
                    { VK_FORMAT_X8_D24_UNORM_PACK32, "X8D24" }, { VK_FORMAT_D32_SFLOAT, "D32F" },
                    { VK_FORMAT_D24_UNORM_S8_UINT, "D24S8" }, { VK_FORMAT_D32_SFLOAT_S8_UINT, "D32FS8" },
                    { VK_FORMAT_BC1_RGBA_UNORM_BLOCK, "BC1" }, { VK_FORMAT_BC2_UNORM_BLOCK, "BC2" },
                    { VK_FORMAT_BC3_UNORM_BLOCK, "BC3" }, { VK_FORMAT_ETC2_R8G8B8A8_UNORM_BLOCK, "ETC2_RGBA" },
                    { VK_FORMAT_ETC2_R8G8B8_UNORM_BLOCK, "ETC2_RGB" }, { VK_FORMAT_ASTC_4x4_UNORM_BLOCK, "ASTC4x4" },
                    { VK_FORMAT_ASTC_6x6_UNORM_BLOCK, "ASTC6x6" }, { VK_FORMAT_ASTC_8x8_UNORM_BLOCK, "ASTC8x8" } };
                std::string line = "Vulkan: formats (s sampled, f linear filter, b blit, c colour target, a blend, d "
                                   "depth target):";
                for (const Named& named : formats)
                {
                    VkFormatProperties properties{};
                    vkGetPhysicalDeviceFormatProperties(device.mPhysical, named.mFormat, &properties);
                    const VkFormatFeatureFlags f = properties.optimalTilingFeatures;
                    std::string bits;
                    if (f & VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT)
                        bits += 's';
                    if (f & VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT)
                        bits += 'f';
                    if ((f & VK_FORMAT_FEATURE_BLIT_SRC_BIT) && (f & VK_FORMAT_FEATURE_BLIT_DST_BIT))
                        bits += 'b';
                    if (f & VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT)
                        bits += 'c';
                    if (f & VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BLEND_BIT)
                        bits += 'a';
                    if (f & VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT)
                        bits += 'd';
                    line += std::string(" ") + named.mName + "=" + (bits.empty() ? "-" : bits);
                }
                line += "; depth format " + std::to_string(device.mDepthFormat);
                Log(Debug::Info) << line;
            }

            std::string names;
            for (const VkExtensionProperties& e : extensions)
                names += std::string(" ") + e.extensionName;
            Log(Debug::Info) << "Vulkan: device extensions (" << extensions.size() << "):" << names;
            Log(Debug::Info) << "Vulkan: diagnostics " << (diagnostics() ? "on" : "off (ALPHA3_VKDIAG=1 to turn on)");
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
            // diagnostics: what the driver says when the GPU faults, and its memory budgets (VMA reads them)
#ifdef VK_EXT_device_fault
            VkPhysicalDeviceFaultFeaturesEXT availableFault{ VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FAULT_FEATURES_EXT };
            VkPhysicalDeviceFaultFeaturesEXT enabledFault{ VK_STRUCTURE_TYPE_PHYSICAL_DEVICE_FAULT_FEATURES_EXT };
            const bool faultExtension = device->mApiVersion >= VK_API_VERSION_1_1
                && hasExtension(deviceExtensions, VK_EXT_DEVICE_FAULT_EXTENSION_NAME);
#endif
            const bool budgetExtension = device->mApiVersion >= VK_API_VERSION_1_1
                && hasExtension(deviceExtensions, VK_EXT_MEMORY_BUDGET_EXTENSION_NAME);
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
#ifdef VK_EXT_device_fault
                if (faultExtension)
                {
                    VkBaseOutStructure* tail = reinterpret_cast<VkBaseOutStructure*>(&query);
                    while (tail->pNext != nullptr)
                        tail = tail->pNext;
                    tail->pNext = reinterpret_cast<VkBaseOutStructure*>(&availableFault);
                }
#endif
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
            if (budgetExtension)
            {
                extensions.push_back(VK_EXT_MEMORY_BUDGET_EXTENSION_NAME);
                device->mMemoryBudget = true;
            }
            if (device->mApiVersion >= VK_API_VERSION_1_1)
            {
#ifdef VK_EXT_device_fault
                if (faultExtension && availableFault.deviceFault)
                {
                    enabledFault.deviceFault = VK_TRUE;
                    VkBaseOutStructure* tail = reinterpret_cast<VkBaseOutStructure*>(&features2);
                    while (tail->pNext != nullptr)
                        tail = tail->pNext;
                    tail->pNext = reinterpret_cast<VkBaseOutStructure*>(&enabledFault);
                    extensions.push_back(VK_EXT_DEVICE_FAULT_EXTENSION_NAME);
                    device->mDeviceFault = true;
                }
#endif
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
                device->mMemoryBudget = device->mDeviceFault = false;
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
            if (device->mMemoryBudget)
                allocatorInfo.flags |= VMA_ALLOCATOR_CREATE_EXT_MEMORY_BUDGET_BIT;
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

            sTextureFormats.store(sKnown | (device->mBcTextures ? sBc : 0u) | (device->mEtc2Textures ? sEtc2 : 0u)
                    | (device->mAstcTextures ? sAstc : 0u),
                std::memory_order_release);

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
            device->mDebugUtils = sMessenger != VK_NULL_HANDLE;
            report(*device, available, deviceExtensions);
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

    void logDeviceFault()
    {
        Device* device = sDevice;
        if (device == nullptr)
            return;
        Log(Debug::Error) << "Vulkan: device lost on " << device->mProperties.deviceName << " ("
                          << (device->mDriverName.empty() ? "driver name unknown" : device->mDriverName)
                          << "); memory then: " << memoryBudgetLine();
#ifdef VK_EXT_device_fault
        if (!device->mDeviceFault)
        {
            Log(Debug::Error) << "Vulkan: the driver has no VK_EXT_device_fault: it cannot say what faulted";
            return;
        }
        auto getFaultInfo = reinterpret_cast<PFN_vkGetDeviceFaultInfoEXT>(
            vkGetDeviceProcAddr(device->mDevice, "vkGetDeviceFaultInfoEXT"));
        if (getFaultInfo == nullptr)
            return;
        VkDeviceFaultCountsEXT counts{ VK_STRUCTURE_TYPE_DEVICE_FAULT_COUNTS_EXT };
        if (getFaultInfo(device->mDevice, &counts, nullptr) != VK_SUCCESS)
        {
            Log(Debug::Error) << "Vulkan: vkGetDeviceFaultInfoEXT gave nothing";
            return;
        }
        std::vector<VkDeviceFaultAddressInfoEXT> addresses(counts.addressInfoCount);
        std::vector<VkDeviceFaultVendorInfoEXT> vendor(counts.vendorInfoCount);
        counts.vendorBinarySize = 0;
        VkDeviceFaultInfoEXT info{ VK_STRUCTURE_TYPE_DEVICE_FAULT_INFO_EXT };
        info.pAddressInfos = addresses.empty() ? nullptr : addresses.data();
        info.pVendorInfos = vendor.empty() ? nullptr : vendor.data();
        const VkResult result = getFaultInfo(device->mDevice, &counts, &info);
        Log(Debug::Error) << "Vulkan: device fault (" << resultName(result) << "): " << info.description;
        static const char* const kinds[]
            = { "none", "read invalid", "write invalid", "execute invalid", "instruction pointer unknown",
                  "instruction pointer invalid", "instruction pointer fault" };
        for (const VkDeviceFaultAddressInfoEXT& a : addresses)
            Log(Debug::Error) << "Vulkan: fault address 0x" << std::hex << a.reportedAddress << " precision 0x"
                              << a.addressPrecision << std::dec << " ("
                              << (static_cast<unsigned>(a.addressType) < 7 ? kinds[a.addressType] : "?") << ")";
        for (const VkDeviceFaultVendorInfoEXT& v : vendor)
            Log(Debug::Error) << "Vulkan: fault vendor info: " << v.description << " code 0x" << std::hex
                              << v.vendorFaultCode << " data 0x" << v.vendorFaultData << std::dec;
#endif
    }

    std::string memoryBudgetLine()
    {
        Device* device = sDevice;
        if (device == nullptr || device->mAllocator == nullptr)
            return "no device";
        VkPhysicalDeviceMemoryProperties memory{};
        vkGetPhysicalDeviceMemoryProperties(device->mPhysical, &memory);
        VmaBudget budgets[VK_MAX_MEMORY_HEAPS]{};
        vmaGetHeapBudgets(device->mAllocator, budgets);
        std::ostringstream out;
        for (uint32_t i = 0; i < memory.memoryHeapCount; ++i)
            out << (i ? "; " : "") << "heap " << i << ": " << budgets[i].statistics.allocationBytes / (1024 * 1024)
                << " MiB in " << budgets[i].statistics.allocationCount << " allocations ("
                << budgets[i].statistics.blockBytes / (1024 * 1024) << " MiB of blocks), process uses "
                << budgets[i].usage / (1024 * 1024) << " of a budget of " << budgets[i].budget / (1024 * 1024)
                << " MiB" << (device->mMemoryBudget ? "" : " (estimated: no VK_EXT_memory_budget)");
        return out.str();
    }
}
