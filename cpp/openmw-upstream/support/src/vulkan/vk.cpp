#include "vk.hpp"

#include <cstdlib>
#include <dlfcn.h>

#include <components/debug/debuglog.hpp>

namespace Alpha3::Vk
{
#define ALPHA3_VK_DEFINE(name) PFN_##name name = nullptr;
    PFN_vkGetInstanceProcAddr vkGetInstanceProcAddr = nullptr;
    ALPHA3_VK_GLOBAL_FUNCTIONS(ALPHA3_VK_DEFINE)
    ALPHA3_VK_INSTANCE_FUNCTIONS(ALPHA3_VK_DEFINE)
    ALPHA3_VK_DEVICE_FUNCTIONS(ALPHA3_VK_DEFINE)
#undef ALPHA3_VK_DEFINE

    bool loadGlobal()
    {
        if (vkGetInstanceProcAddr != nullptr)
            return true;
        // the custom driver (Turnip through libadrenotools), handed over by the launcher as a dlopen handle in hex
        void* library = nullptr;
        if (const char* text = std::getenv("ALPHA3_VULKAN_HANDLE"); text != nullptr && *text != '\0')
        {
            library = reinterpret_cast<void*>(std::strtoull(text, nullptr, 16));
            Log(Debug::Info) << "Vulkan: the custom driver (ALPHA3_VULKAN_HANDLE)";
        }
        if (library == nullptr)
            library = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
        if (library == nullptr)
        {
            Log(Debug::Warning) << "Vulkan: no libvulkan.so: " << dlerror();
            return false;
        }
        vkGetInstanceProcAddr = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(library, "vkGetInstanceProcAddr"));
        if (vkGetInstanceProcAddr == nullptr)
        {
            Log(Debug::Warning) << "Vulkan: the library has no vkGetInstanceProcAddr";
            return false;
        }
#define ALPHA3_VK_LOAD(name) name = reinterpret_cast<PFN_##name>(vkGetInstanceProcAddr(VK_NULL_HANDLE, #name));
        ALPHA3_VK_GLOBAL_FUNCTIONS(ALPHA3_VK_LOAD)
#undef ALPHA3_VK_LOAD
        return vkCreateInstance != nullptr;
    }

    void loadInstance(VkInstance instance)
    {
#define ALPHA3_VK_LOAD(name) name = reinterpret_cast<PFN_##name>(vkGetInstanceProcAddr(instance, #name));
        ALPHA3_VK_INSTANCE_FUNCTIONS(ALPHA3_VK_LOAD)
#undef ALPHA3_VK_LOAD
    }

    void loadDevice(VkDevice device)
    {
#define ALPHA3_VK_LOAD(name) name = reinterpret_cast<PFN_##name>(vkGetDeviceProcAddr(device, #name));
        ALPHA3_VK_DEVICE_FUNCTIONS(ALPHA3_VK_LOAD)
#undef ALPHA3_VK_LOAD
    }

    const char* resultName(VkResult result)
    {
        switch (result)
        {
#define ALPHA3_VK_RESULT(r)                                                                                            \
    case r:                                                                                                            \
        return #r;
            ALPHA3_VK_RESULT(VK_SUCCESS)
            ALPHA3_VK_RESULT(VK_NOT_READY)
            ALPHA3_VK_RESULT(VK_TIMEOUT)
            ALPHA3_VK_RESULT(VK_INCOMPLETE)
            ALPHA3_VK_RESULT(VK_SUBOPTIMAL_KHR)
            ALPHA3_VK_RESULT(VK_ERROR_OUT_OF_HOST_MEMORY)
            ALPHA3_VK_RESULT(VK_ERROR_OUT_OF_DEVICE_MEMORY)
            ALPHA3_VK_RESULT(VK_ERROR_INITIALIZATION_FAILED)
            ALPHA3_VK_RESULT(VK_ERROR_DEVICE_LOST)
            ALPHA3_VK_RESULT(VK_ERROR_MEMORY_MAP_FAILED)
            ALPHA3_VK_RESULT(VK_ERROR_LAYER_NOT_PRESENT)
            ALPHA3_VK_RESULT(VK_ERROR_EXTENSION_NOT_PRESENT)
            ALPHA3_VK_RESULT(VK_ERROR_FEATURE_NOT_PRESENT)
            ALPHA3_VK_RESULT(VK_ERROR_INCOMPATIBLE_DRIVER)
            ALPHA3_VK_RESULT(VK_ERROR_TOO_MANY_OBJECTS)
            ALPHA3_VK_RESULT(VK_ERROR_FORMAT_NOT_SUPPORTED)
            ALPHA3_VK_RESULT(VK_ERROR_SURFACE_LOST_KHR)
            ALPHA3_VK_RESULT(VK_ERROR_NATIVE_WINDOW_IN_USE_KHR)
            ALPHA3_VK_RESULT(VK_ERROR_OUT_OF_DATE_KHR)
#undef ALPHA3_VK_RESULT
            default:
                return "VK_ERROR (other)";
        }
    }
}
