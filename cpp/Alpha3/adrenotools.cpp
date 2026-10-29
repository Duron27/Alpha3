#include "main.h"
#include <adrenotools/driver.h>
#include <dlfcn.h>
#include <string>

#define VK_NO_PROTOTYPES
#include <vulkan/vulkan.h>

// Handle to a libvulkan that routes the GPU driver through libadrenotools (custom Turnip build).
// libopenmw.so can't see symbols exported from this library, so the handle is handed over in the
// ALPHA3_VULKAN_HANDLE environment variable. OpenMW's vulkanmobiledispatch uses it, and falls back to
// the system libvulkan when it isn't set.
static void* g_vulkanHandle = nullptr;

static std::string withTrailingSlash(std::string dir)
{
    if (!dir.empty() && dir.back() != '/')
        dir += '/';
    return dir;
}

// Tries to load `driverName` from `driverDir`. `hooksDir` has to be the app's native library directory.
static void* openDriver(const std::string& tmpDir, const std::string& hooksDir, const std::string& driverDir,
                        const std::string& driverName)
{
    return adrenotools_open_libvulkan(
        RTLD_NOW, ADRENOTOOLS_DRIVER_CUSTOM, tmpDir.c_str(), hooksDir.c_str(),
        driverDir.c_str(), driverName.c_str(), nullptr, nullptr);
}

// A driver that loads is not one that works: Turnip loads on Mali and finds no GPU, a PanVK built for another Mali
// generation may refuse the one it finds. It counts once an instance sees at least one GPU.
static bool findsGpu(void* handle)
{
    auto createInstance = reinterpret_cast<PFN_vkCreateInstance>(dlsym(handle, "vkCreateInstance"));
    auto enumerateDevices = reinterpret_cast<PFN_vkEnumeratePhysicalDevices>(dlsym(handle, "vkEnumeratePhysicalDevices"));
    auto destroyInstance = reinterpret_cast<PFN_vkDestroyInstance>(dlsym(handle, "vkDestroyInstance"));
    if (!createInstance || !enumerateDevices || !destroyInstance)
        return false;
    VkApplicationInfo app{VK_STRUCTURE_TYPE_APPLICATION_INFO};
    app.pApplicationName = "Alpha3 driver check";
    app.apiVersion = VK_API_VERSION_1_1;
    VkInstanceCreateInfo info{VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO};
    info.pApplicationInfo = &app;
    VkInstance instance = VK_NULL_HANDLE;
    if (createInstance(&info, nullptr, &instance) != VK_SUCCESS)
        return false;
    uint32_t count = 0;
    const bool found = enumerateDevices(instance, &count, nullptr) == VK_SUCCESS && count > 0;
    destroyInstance(instance, nullptr);
    return found;
}

// driverDir is where the driver library lives. It has to be internal to the app (files/gpu_drivers/<id>/ for a
// downloaded driver); an empty string means the bundled Turnip in nativeLibDir.
extern "C" JNIEXPORT jboolean JNICALL Java_org_openmw_EngineActivity_initVulkanDriver(
    JNIEnv* env, jobject /* thiz */, jboolean enabled, jstring nativeLibDir, jstring tmpLibDir, jstring driverDir,
    jstring driverName, jboolean bundledFallback)
{
    if (!enabled) {
        unsetenv("ALPHA3_VULKAN_HANDLE");
        spdlog::info("adrenotools: custom driver disabled, using the system Vulkan driver");
        return JNI_FALSE;
    }
    if (g_vulkanHandle)
        return JNI_TRUE;

    auto toString = [env](jstring s) {
        const char* chars = env->GetStringUTFChars(s, nullptr);
        std::string result(chars ? chars : "");
        if (chars) env->ReleaseStringUTFChars(s, chars);
        return result;
    };

    const std::string libDir = withTrailingSlash(toString(nativeLibDir));
    const std::string tmpDir = withTrailingSlash(toString(tmpLibDir));
    std::string customDir = withTrailingSlash(toString(driverDir));
    std::string name = toString(driverName);
    if (customDir == "/")
        customDir = libDir;

    // a handle whose driver finds no GPU is left alone (closing a namespace's library is not safe) and not used
    const auto openWorking = [&] {
        void* handle = openDriver(tmpDir, libDir, customDir, name);
        if (handle && !findsGpu(handle)) {
            spdlog::warn("adrenotools: driver {}{} loaded but found no GPU", customDir, name);
            return static_cast<void*>(nullptr);
        }
        return handle;
    };
    g_vulkanHandle = openWorking();

    if (!g_vulkanHandle && customDir != libDir && bundledFallback) {
        // a downloaded driver that will not load: go back to the bundled one rather than the system driver (Adreno only:
        // the bundled Turnip runs nowhere else)
        spdlog::warn("adrenotools: could not load custom driver {}{}, trying the bundled driver", customDir, name);
        customDir = libDir;
        name = "libvulkan_freedreno.so";
        g_vulkanHandle = openWorking();
    }

    if (!g_vulkanHandle) {
        spdlog::warn("adrenotools: could not load custom driver {}{}, using the system Vulkan driver", customDir, name);
        return JNI_FALSE;
    }
    // libopenmw.so reads the handle from here (see vulkanmobiledispatch in openmw/patches/android)
    char handleText[32];
    snprintf(handleText, sizeof(handleText), "%llx", static_cast<unsigned long long>(reinterpret_cast<uintptr_t>(g_vulkanHandle)));
    setenv("ALPHA3_VULKAN_HANDLE", handleText, 1);
    spdlog::info("adrenotools: loaded custom Vulkan driver {}{}", customDir, name);
    return JNI_TRUE;
}
