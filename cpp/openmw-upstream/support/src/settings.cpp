#include "support.hpp"

#include <alpha3/upstream.hpp>

#include <cstdlib>
#include <exception>

#include <SDL_hints.h>

#ifdef __ANDROID__
#include "vulkan/device.hpp"
#endif

#include <components/debug/debuglog.hpp>
#ifdef __ANDROID__
#include <components/files/androidpath.hpp>
#endif
#include <components/settings/settings.hpp>

namespace Alpha3
{
    std::string setting(std::string_view category, std::string_view name, std::string_view fallback)
    {
        try
        {
            return Settings::Manager::getString(name, category);
        }
        catch (const std::exception&)
        {
            return std::string(fallback);
        }
    }

    bool settingBool(std::string_view category, std::string_view name, bool fallback)
    {
        const std::string value = setting(category, name, "");
        if (value == "true" || value == "1")
            return true;
        if (value == "false" || value == "0")
            return false;
        return fallback;
    }

    int settingInt(std::string_view category, std::string_view name, int fallback)
    {
        const std::string value = setting(category, name, "");
        try
        {
            return value.empty() ? fallback : std::stoi(value);
        }
        catch (const std::exception&)
        {
            return fallback;
        }
    }

    float settingFloat(std::string_view category, std::string_view name, float fallback)
    {
        const std::string value = setting(category, name, "");
        try
        {
            return value.empty() ? fallback : std::stof(value);
        }
        catch (const std::exception&)
        {
            return fallback;
        }
    }

    std::filesystem::path cacheDirectory()
    {
#ifdef __ANDROID__
        return Files::AndroidPath("openmw").getCachePath();
#else
        // iOS: Library/Application Support of the app container (HOME is the container), not Library/Caches, which iOS
        // empties when storage runs low and with it hours of texture conversion. The launcher keeps it out of backups.
        const char* home = std::getenv("HOME");
        return std::filesystem::path(home ? home : ".") / "Library" / "Application Support" / "Alpha3" / "cache";
#endif
    }

    bool usesZink()
    {
        return std::getenv("OSG_USE_ZINK") != nullptr;
    }

    bool usesVulkan()
    {
        return std::getenv("OSG_USE_VULKAN") != nullptr;
    }

    bool usesGles()
    {
        return !usesZink();
    }

    bool prepareRenderer()
    {
        // OSG_USE_ZINK and OPENMW_GLES_VERSION in the environment (adb shell setprop does not reach them, but a launcher
        // intent does) win over the setting
        const std::string renderer = setting("Video", "renderer", "opengl es 3.2");
        // ANGLE turns Zink off: OSG's GL lookups (glad.patch) would otherwise still go to OSMesa
        if (std::getenv("OSG_USE_ANGLE") != nullptr)
            unsetenv("OSG_USE_ZINK");
        // Vulkan ([Video] renderer = vulkan, or OSG_USE_VULKAN): only on a phone whose driver can start it, checked
        // before the window is made (it is made without an OpenGL surface, so OpenGL ES cannot take over after)
#ifdef __ANDROID__
        if (std::getenv("OSG_USE_ZINK") == nullptr && std::getenv("OSG_USE_ANGLE") == nullptr
            && (std::getenv("OSG_USE_VULKAN") != nullptr || renderer == "vulkan"))
        {
            if (Vk::probe())
            {
                setenv("OSG_USE_VULKAN", "1", 1);
                setenv("OPENMW_GLES_VERSION", "32", 0);
                SDL_SetHint(SDL_HINT_VIDEO_EXTERNAL_CONTEXT, "1");
                // nothing is compiled ahead for OpenGL: the translator makes its objects when it first draws them
                // (an OpenGL upload would also let go of images the translator still reads)
                setenv("OPENMW_DONT_PRECOMPILE", "1", 1);
                Log(Debug::Info) << "Renderer: vulkan (native, Vulkan 1.0) (setting '" << renderer << "')";
                return true;
            }
            unsetenv("OSG_USE_VULKAN");
            Log(Debug::Warning) << "Renderer: Vulkan cannot start on this phone, using OpenGL ES 3.2";
        }
#endif
        const bool zink = std::getenv("OSG_USE_ZINK") != nullptr
            || (renderer == "opengl" && std::getenv("OPENMW_GLES_VERSION") == nullptr
                && std::getenv("OSG_USE_ANGLE") == nullptr);
        if (zink)
        {
            setenv("OSG_USE_ZINK", "1", 0);
            setenv("GALLIUM_DRIVER", "zink", 0);
            // SDL adds SDL_WINDOW_OPENGL to every window on Android, which connects an EGL surface to it and keeps the CPU
            // copy of ZinkContext::present from locking the ANativeWindow
            SDL_SetHint(SDL_HINT_VIDEO_EXTERNAL_CONTEXT, "1");
        }
        else
            setenv("OPENMW_GLES_VERSION", "32", 0);
        // ANGLE (OpenGL ES on Vulkan, shipped as lib*_angle.so): the setting value is the fork's. SDL makes the context
        // through ANGLE's EGL, glad loads through SDL, and OSG's own lookups follow OSG_USE_ANGLE (glad.patch).
        const bool angle = !zink && (std::getenv("OSG_USE_ANGLE") != nullptr || renderer == "opengl es 3.2 angle");
        if (angle)
        {
            setenv("OSG_USE_ANGLE", "1", 0);
            setenv("SDL_VIDEO_GL_DRIVER", "libGLESv2_angle.so", 0);
            setenv("SDL_VIDEO_EGL_DRIVER", "libEGL_angle.so", 0);
            // Frame generation queues a generated frame after each rendered one: with ANGLE's three swap chain images the
            // draw thread then waits for the GPU every frame (Angle/Alpha3SwapchainImages.patch reads this)
            if (frameGenRequested())
            {
                setenv("ALPHA3_SWAPCHAIN_IMAGES", "5", 0);
                // Android lets a frame be queued to the display only once the GPU has finished the one queued before it,
                // and ANGLE hands a frame's work to the GPU only at the swap: the generated frame, queued right after the
                // rendered one, then waited for all of it while the GPU had nothing else to do. Handed over at each
                // framebuffer change (shadows, reflections, the scene, post processing), most of it is done by then.
                const char* enabled = std::getenv("ANGLE_FEATURE_OVERRIDES_ENABLED");
                std::string features = enabled != nullptr ? enabled : "";
                if (features.find("preferSubmitAtFBOBoundary") == std::string::npos)
                {
                    features += features.empty() ? "preferSubmitAtFBOBoundary" : ":preferSubmitAtFBOBoundary";
                    setenv("ANGLE_FEATURE_OVERRIDES_ENABLED", features.c_str(), 1);
                }
            }
        }
        Log(Debug::Info) << "Renderer: "
                         << (zink ? "opengl (Mesa Zink)" : angle ? "opengl es 3.2 (ANGLE on Vulkan)" : "opengl es 3.2")
                         << " (setting '" << renderer << "')";
        return zink;
    }
}

// Called from OMW::Engine::createWindow (declared there with C linkage, so the engine needs no include of the library)
extern "C" bool alpha3PrepareRenderer()
{
    return Alpha3::prepareRenderer();
}
