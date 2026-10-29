// [Video] renderer = opengl: desktop OpenGL through Mesa Zink (libOSMesa_8.so) instead of an SDL/EGL context. The
// frame is rendered into a buffer of OSMesa's and copied to the window's ANativeWindow on swap. Moved from OpenMW's
// SDLUtil::GraphicsWindowSDL2, which now only calls ZinkContext.
#include <alpha3/upstream.hpp>
#include "support.hpp"

#include <glad/gles2.h>

#include <algorithm>
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <string>

#include <android/native_window.h>
#include <dlfcn.h>
#include <sys/system_properties.h>

#include <SDL_syswm.h>
#include <SDL_video.h>

#include <components/debug/debuglog.hpp>

namespace Alpha3
{
    namespace
    {
        constexpr int OSMESA_FORMAT = 0x22;
        constexpr int OSMESA_RGBA = 0x1908;
        constexpr int OSMESA_DEPTH_BITS = 0x30;
        constexpr int OSMESA_STENCIL_BITS = 0x31;
        constexpr int OSMESA_PROFILE = 0x33;
        constexpr int OSMESA_COMPAT_PROFILE = 0x35;
        constexpr int OSMESA_CONTEXT_MAJOR_VERSION = 0x36;
        constexpr int OSMESA_CONTEXT_MINOR_VERSION = 0x37;
        constexpr int OSMESA_Y_UP = 0x11;
        constexpr unsigned GL_UNSIGNED_BYTE_ENUM = 0x1401;

        struct OSMesaApi
        {
            void* (*createContextAttribs)(const int*, void*) = nullptr;
            int (*makeCurrent)(void*, void*, unsigned, int, int) = nullptr;
            void (*destroyContext)(void*) = nullptr;
            void (*pixelStore)(int, int) = nullptr;
            void* (*getProcAddress)(const char*) = nullptr;

            bool load()
            {
                if (getProcAddress)
                    return true;
                void* lib = dlopen("libOSMesa_8.so", RTLD_NOW);
                if (!lib)
                {
                    Log(Debug::Error) << "Zink: cannot load libOSMesa_8.so: " << dlerror();
                    return false;
                }
                createContextAttribs
                    = reinterpret_cast<decltype(createContextAttribs)>(dlsym(lib, "OSMesaCreateContextAttribs"));
                makeCurrent = reinterpret_cast<decltype(makeCurrent)>(dlsym(lib, "OSMesaMakeCurrent"));
                destroyContext = reinterpret_cast<decltype(destroyContext)>(dlsym(lib, "OSMesaDestroyContext"));
                pixelStore = reinterpret_cast<decltype(pixelStore)>(dlsym(lib, "OSMesaPixelStore"));
                getProcAddress = reinterpret_cast<decltype(getProcAddress)>(dlsym(lib, "OSMesaGetProcAddress"));
                return createContextAttribs && makeCurrent && destroyContext && pixelStore && getProcAddress;
            }
        };

        OSMesaApi& osmesa()
        {
            static OSMesaApi api;
            return api;
        }
    }

    void zinkClipPlane(unsigned int index, const double* eyePlane)
    {
        using ClipPlane = void (*)(unsigned int, const double*);
        static const ClipPlane clipPlane = osmesa().getProcAddress
            ? reinterpret_cast<ClipPlane>(osmesa().getProcAddress("glClipPlane"))
            : nullptr;
        if (clipPlane)
            clipPlane(0x3000 + index, eyePlane); // GL_CLIP_PLANE0
    }

    namespace
    {

        // Dev hook: adb shell setprop debug.alpha3.zinkenv "ZINK_DESCRIPTORS=db;ZINK_DEBUG=nogpl" (Mesa cannot read these
        // from Android properties inside an app, so apply them as environment variables before Zink starts).
        void applyEnvironmentProperty()
        {
            char envList[PROP_VALUE_MAX] = "";
            if (__system_property_get("debug.alpha3.zinkenv", envList) <= 0)
                return;
            std::string list(envList);
            std::size_t start = 0;
            while (start < list.size())
            {
                std::size_t end = list.find(';', start);
                if (end == std::string::npos)
                    end = list.size();
                const std::string item = list.substr(start, end - start);
                const std::size_t eq = item.find('=');
                if (eq != std::string::npos && eq > 0)
                {
                    setenv(item.substr(0, eq).c_str(), item.c_str() + eq + 1, 1);
                    Log(Debug::Info) << "Zink env: " << item;
                }
                start = end + 1;
            }
        }
    }

    std::unique_ptr<ZinkContext> ZinkContext::create(SDL_Window* window)
    {
        auto& api = osmesa();
        if (!api.load())
            return nullptr;

        applyEnvironmentProperty();

        SDL_SysWMinfo info;
        SDL_VERSION(&info.version);
        if (!SDL_GetWindowWMInfo(window, &info) || info.subsystem != SDL_SYSWM_ANDROID)
        {
            Log(Debug::Error) << "Zink: no Android native window: " << SDL_GetError();
            return nullptr;
        }

        std::unique_ptr<ZinkContext> zink(new ZinkContext);
        zink->mWindow = window;
        zink->mNativeWindow = info.info.android.window;
        SDL_GL_GetDrawableSize(window, &zink->mWidth, &zink->mHeight);
        ANativeWindow_setBuffersGeometry(
            static_cast<ANativeWindow*>(zink->mNativeWindow), zink->mWidth, zink->mHeight, WINDOW_FORMAT_RGBA_8888);

        // Dev probe: adb shell setprop debug.alpha3.zink_stencil 0 asks for a depth buffer without stencil
        char stencilProp[PROP_VALUE_MAX] = "";
        zink->mStencilBits
            = (__system_property_get("debug.alpha3.zink_stencil", stencilProp) > 0 && stencilProp[0] == '0') ? 0 : 8;
        const int attribs[] = { OSMESA_FORMAT, OSMESA_RGBA, OSMESA_DEPTH_BITS, 24, OSMESA_STENCIL_BITS, zink->mStencilBits,
            OSMESA_PROFILE, OSMESA_COMPAT_PROFILE, OSMESA_CONTEXT_MAJOR_VERSION, 4, OSMESA_CONTEXT_MINOR_VERSION, 5, 0 };
        zink->mContext = api.createContextAttribs(attribs, nullptr);
        if (!zink->mContext)
        {
            // the usual cause: the device's own Vulkan driver (Freedreno off) is older than Zink needs; Mesa logs which
            // feature is missing just before
            Log(Debug::Error) << "Zink: OSMesaCreateContextAttribs failed (Zink needs Vulkan 1.2 or newer: turn on \"Use "
                                 "Freedreno driver\", or pick another renderer)";
            return nullptr;
        }
        zink->mBuffer = static_cast<unsigned char*>(std::calloc(static_cast<size_t>(zink->mWidth) * zink->mHeight, 4));
        if (!zink->makeCurrent())
        {
            Log(Debug::Error) << "Zink: OSMesaMakeCurrent failed";
            return nullptr;
        }

        if (!gladLoadGLES2(reinterpret_cast<GLADloadfunc>(api.getProcAddress)))
            Log(Debug::Error) << "Failed to initialize GLAD";
        Log(Debug::Info) << "Zink: OSMesa context " << zink->mWidth << "x" << zink->mHeight;

        release();
        return zink;
    }

    ZinkContext::~ZinkContext()
    {
        if (mContext)
            osmesa().destroyContext(mContext);
        std::free(mBuffer);
    }

    bool ZinkContext::makeCurrent()
    {
        auto& api = osmesa();
        if (!api.makeCurrent(mContext, mBuffer, GL_UNSIGNED_BYTE_ENUM, mWidth, mHeight))
            return false;
        api.pixelStore(OSMESA_Y_UP, 0); // rows top to bottom, like the ANativeWindow
        return true;
    }

    bool ZinkContext::release()
    {
        return osmesa().makeCurrent(nullptr, nullptr, 0, 0, 0) != 0;
    }

    void ZinkContext::present()
    {
        // Dev probes: adb shell setprop debug.alpha3.zink_stats 1 logs frame timing every 120 frames, and
        // debug.alpha3.zink <finish|nocopy|nofinish> changes the sync/copy of the present path to see what they cost.
        using Clock = std::chrono::steady_clock;
        static Clock::time_point windowStart = Clock::now();
        static int frames = 0;
        static double finishMs = 0.0, copyMs = 0.0, betweenMs = 0.0;
        static Clock::time_point lastSwapEnd = Clock::now();
        static char mode[PROP_VALUE_MAX] = "";
        static bool statsOn = false;
        if (frames == 0)
        {
            __system_property_get("debug.alpha3.zink", mode);
            char stats[PROP_VALUE_MAX] = "";
            statsOn = __system_property_get("debug.alpha3.zink_stats", stats) > 0 && stats[0] == '1';
        }

        const auto t0 = Clock::now();
        betweenMs += std::chrono::duration<double, std::milli>(t0 - lastSwapEnd).count();
        // glFlush hands the frame to OSMesa's pipelined read-back: mBuffer then holds the previous frame, and the GPU
        // keeps drawing this one while the CPU moves on. glFinish would wait for the GPU every frame.
        if (std::strcmp(mode, "finish") == 0)
            glFinish();
        else if (std::strcmp(mode, "nofinish") != 0)
        {
            glFlush();
            // Mesa runs GL calls on its own thread (glthread) and the flush copies the frame into mBuffer there: a call
            // that returns a value waits for that thread
            glGetError();
        }
        const auto t1 = Clock::now();

        auto* window = static_cast<ANativeWindow*>(mNativeWindow);
        ANativeWindow_Buffer out;
        if (std::strcmp(mode, "nocopy") != 0 && ANativeWindow_lock(window, &out, nullptr) == 0)
        {
            const int rows = std::min<int>(mHeight, out.height);
            const size_t rowBytes = static_cast<size_t>(std::min<int>(mWidth, out.width)) * 4;
            // a window as wide as the frame takes it in one copy
            if (out.stride == mWidth && rowBytes == static_cast<size_t>(mWidth) * 4)
                std::memcpy(out.bits, mBuffer, rowBytes * rows);
            else
                for (int y = 0; y < rows; ++y)
                    std::memcpy(static_cast<unsigned char*>(out.bits) + static_cast<size_t>(y) * out.stride * 4,
                        mBuffer + static_cast<size_t>(y) * mWidth * 4, rowBytes);
            paceZinkPost();
            ANativeWindow_unlockAndPost(window);
        }
        const auto t2 = Clock::now();
        finishMs += std::chrono::duration<double, std::milli>(t1 - t0).count();
        copyMs += std::chrono::duration<double, std::milli>(t2 - t1).count();
        lastSwapEnd = t2;
        if (++frames >= 120)
        {
            const double secs = std::chrono::duration<double>(t2 - windowStart).count();
            if (statsOn)
                Log(Debug::Info) << "Zink present[" << mode << "]: " << frames / secs << " fps, per frame: scene+queue "
                                 << betweenMs / frames << " ms, finish " << finishMs / frames << " ms, copy "
                                 << copyMs / frames << " ms";
            frames = 0;
            finishMs = copyMs = betweenMs = 0.0;
            windowStart = Clock::now();
            __system_property_get("debug.alpha3.zink", mode);
            char stats[PROP_VALUE_MAX] = "";
            statsOn = __system_property_get("debug.alpha3.zink_stats", stats) > 0 && stats[0] == '1';
        }

        int w = 0, h = 0;
        SDL_GL_GetDrawableSize(mWindow, &w, &h);
        if (w > 0 && h > 0 && (w != mWidth || h != mHeight))
        {
            mWidth = w;
            mHeight = h;
            std::free(mBuffer);
            mBuffer = static_cast<unsigned char*>(std::calloc(static_cast<size_t>(w) * h, 4));
            ANativeWindow_setBuffersGeometry(window, w, h, WINDOW_FORMAT_RGBA_8888);
            makeCurrent();
        }
    }
}
