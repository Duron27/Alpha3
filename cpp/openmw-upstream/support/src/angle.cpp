// iOS, OSG_USE_ANGLE: OpenGL ES 3.0 through ANGLE on Metal instead of Apple's OpenGL ES. The app ships ANGLE as
// libGLESv2_angle.framework (cpp/Angle), which carries both its OpenGL ES and its EGL; it is opened here, at runtime, so
// a run on Apple's OpenGL ES never loads it. The window gets a CAMetalLayer view from SDL, and ANGLE's window surface
// draws into that layer. SDL's own OpenGL context is not made.
#include <alpha3/upstream.hpp>
#include "support.hpp"

#include <cstdint>
#include <cstdlib>
#include <type_traits>

#include <dlfcn.h>

#include <SDL_metal.h>
#include <SDL_video.h>

#include <components/debug/debuglog.hpp>

namespace Alpha3
{
    namespace
    {
        // In the app's Frameworks; OSG's own GL lookups open the same file (openmw-osg ios/angle.patch)
        constexpr const char* angleLibraryPath = "@executable_path/Frameworks/libGLESv2_angle.framework/libGLESv2_angle";

        // The few EGL declarations needed: everything is looked up in ANGLE, so no EGL header or library is linked
        using EGLDisplay = void*;
        using EGLConfig = void*;
        using EGLSurface = void*;
        using EGLContext = void*;
        using EGLBoolean = unsigned;
        using EGLint = std::int32_t;
        using EGLenum = unsigned;

        constexpr EGLint EGL_NONE = 0x3038;
        constexpr EGLint EGL_ALPHA_SIZE = 0x3021;
        constexpr EGLint EGL_BLUE_SIZE = 0x3022;
        constexpr EGLint EGL_GREEN_SIZE = 0x3023;
        constexpr EGLint EGL_RED_SIZE = 0x3024;
        constexpr EGLint EGL_DEPTH_SIZE = 0x3025;
        constexpr EGLint EGL_STENCIL_SIZE = 0x3026;
        constexpr EGLint EGL_SAMPLES = 0x3031;
        constexpr EGLint EGL_SAMPLE_BUFFERS = 0x3032;
        constexpr EGLint EGL_SURFACE_TYPE = 0x3033;
        constexpr EGLint EGL_RENDERABLE_TYPE = 0x3040;
        constexpr EGLint EGL_WINDOW_BIT = 0x0004;
        constexpr EGLint EGL_OPENGL_ES3_BIT = 0x0040;
        constexpr EGLint EGL_CONTEXT_MAJOR_VERSION = 0x3098;
        constexpr EGLint EGL_CONTEXT_MINOR_VERSION = 0x30FB;
        constexpr EGLenum EGL_OPENGL_ES_API = 0x30A0;
        constexpr EGLenum EGL_PLATFORM_ANGLE_ANGLE = 0x3202;
        constexpr EGLint EGL_PLATFORM_ANGLE_TYPE_ANGLE = 0x3203;
        constexpr EGLint EGL_PLATFORM_ANGLE_TYPE_METAL_ANGLE = 0x3489;

        struct EglApi
        {
            void* (*getProcAddress)(const char*) = nullptr;
            EGLint (*getError)() = nullptr;
            EGLDisplay (*getPlatformDisplayEXT)(EGLenum, void*, const EGLint*) = nullptr;
            EGLBoolean (*initialize)(EGLDisplay, EGLint*, EGLint*) = nullptr;
            EGLBoolean (*terminate)(EGLDisplay) = nullptr;
            EGLBoolean (*bindAPI)(EGLenum) = nullptr;
            EGLBoolean (*chooseConfig)(EGLDisplay, const EGLint*, EGLConfig*, EGLint, EGLint*) = nullptr;
            EGLBoolean (*getConfigAttrib)(EGLDisplay, EGLConfig, EGLint, EGLint*) = nullptr;
            EGLSurface (*createWindowSurface)(EGLDisplay, EGLConfig, void*, const EGLint*) = nullptr;
            EGLContext (*createContext)(EGLDisplay, EGLConfig, EGLContext, const EGLint*) = nullptr;
            EGLBoolean (*makeCurrent)(EGLDisplay, EGLSurface, EGLSurface, EGLContext) = nullptr;
            EGLBoolean (*swapBuffers)(EGLDisplay, EGLSurface) = nullptr;
            EGLBoolean (*swapInterval)(EGLDisplay, EGLint) = nullptr;
            EGLBoolean (*destroySurface)(EGLDisplay, EGLSurface) = nullptr;
            EGLBoolean (*destroyContext)(EGLDisplay, EGLContext) = nullptr;
        };

        // The one the engine runs with; null if ANGLE could not be loaded (the reason is logged once)
        const EglApi* eglApi()
        {
            static const EglApi* const api = []() -> const EglApi* {
                void* lib = dlopen(angleLibraryPath, RTLD_NOW | RTLD_LOCAL);
                if (lib == nullptr)
                {
                    Log(Debug::Error) << "ANGLE: cannot load " << angleLibraryPath << ": " << dlerror();
                    return nullptr;
                }
                static EglApi egl;
                bool complete = true;
                auto bind = [&](auto& function, const char* name) {
                    function = reinterpret_cast<std::remove_reference_t<decltype(function)>>(dlsym(lib, name));
                    if (function == nullptr)
                    {
                        Log(Debug::Error) << "ANGLE: " << name << " is missing";
                        complete = false;
                    }
                };
                bind(egl.getProcAddress, "eglGetProcAddress");
                bind(egl.getError, "eglGetError");
                bind(egl.getPlatformDisplayEXT, "eglGetPlatformDisplayEXT");
                bind(egl.initialize, "eglInitialize");
                bind(egl.terminate, "eglTerminate");
                bind(egl.bindAPI, "eglBindAPI");
                bind(egl.chooseConfig, "eglChooseConfig");
                bind(egl.getConfigAttrib, "eglGetConfigAttrib");
                bind(egl.createWindowSurface, "eglCreateWindowSurface");
                bind(egl.createContext, "eglCreateContext");
                bind(egl.makeCurrent, "eglMakeCurrent");
                bind(egl.swapBuffers, "eglSwapBuffers");
                bind(egl.swapInterval, "eglSwapInterval");
                bind(egl.destroySurface, "eglDestroySurface");
                bind(egl.destroyContext, "eglDestroyContext");
                return complete ? &egl : nullptr;
            }();
            return api;
        }
    }

    bool usesAngle()
    {
        return std::getenv("OSG_USE_ANGLE") != nullptr;
    }

    void* angleProcAddress(const char* name)
    {
        const EglApi* egl = eglApi();
        return egl ? egl->getProcAddress(name) : nullptr;
    }

    std::unique_ptr<AngleContext> AngleContext::create(SDL_Window* window, int samples)
    {
        const EglApi* egl = eglApi();
        if (egl == nullptr)
            return nullptr;

        std::unique_ptr<AngleContext> context(new AngleContext);
        context->mWindow = window;
        // Turns the window into a Metal one (SDL drops its OpenGL flag) and puts a view with a CAMetalLayer in it
        context->mView = SDL_Metal_CreateView(window);
        void* layer = context->mView ? SDL_Metal_GetLayer(context->mView) : nullptr;
        if (layer == nullptr)
        {
            Log(Debug::Error) << "ANGLE: no Metal view for the window: " << SDL_GetError();
            return nullptr;
        }

        const EGLint displayAttributes[] = { EGL_PLATFORM_ANGLE_TYPE_ANGLE, EGL_PLATFORM_ANGLE_TYPE_METAL_ANGLE, EGL_NONE };
        context->mDisplay = egl->getPlatformDisplayEXT(EGL_PLATFORM_ANGLE_ANGLE, nullptr, displayAttributes);
        EGLint major = 0;
        EGLint minor = 0;
        if (context->mDisplay == nullptr || !egl->initialize(context->mDisplay, &major, &minor))
        {
            Log(Debug::Error) << "ANGLE: cannot initialize the Metal display (EGL error 0x" << std::hex
                              << egl->getError() << ")";
            context->mDisplay = nullptr;
            return nullptr;
        }
        egl->bindAPI(EGL_OPENGL_ES_API);

        // The engine's window attributes: RGB8, no alpha, 24-bit depth (and stencil for the post processing)
        const EGLint configAttributes[] = { EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_ALPHA_SIZE, 0,
            EGL_DEPTH_SIZE, 24, EGL_STENCIL_SIZE, 8, EGL_SAMPLE_BUFFERS, samples > 0 ? 1 : 0, EGL_SAMPLES, samples,
            EGL_SURFACE_TYPE, EGL_WINDOW_BIT, EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT, EGL_NONE };
        EGLConfig config = nullptr;
        EGLint configs = 0;
        if (!egl->chooseConfig(context->mDisplay, configAttributes, &config, 1, &configs) || configs == 0)
        {
            Log(Debug::Error) << "ANGLE: no EGL config for the window (EGL error 0x" << std::hex << egl->getError()
                              << ")";
            return nullptr;
        }
        egl->getConfigAttrib(context->mDisplay, config, EGL_DEPTH_SIZE, &context->mDepthBits);
        egl->getConfigAttrib(context->mDisplay, config, EGL_STENCIL_SIZE, &context->mStencilBits);
        egl->getConfigAttrib(context->mDisplay, config, EGL_SAMPLES, &context->mSamples);

        context->mSurface = egl->createWindowSurface(context->mDisplay, config, layer, nullptr);
        if (context->mSurface == nullptr)
        {
            Log(Debug::Error) << "ANGLE: cannot make a surface on the Metal layer (EGL error 0x" << std::hex
                              << egl->getError() << ")";
            return nullptr;
        }

        // ANGLE's Metal back end offers OpenGL ES 3.0 (3.1 and 3.2 are not conformant on it)
        const EGLint contextAttributes[] = { EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 0, EGL_NONE };
        context->mContext = egl->createContext(context->mDisplay, config, nullptr, contextAttributes);
        if (context->mContext == nullptr)
        {
            Log(Debug::Error) << "ANGLE: cannot make an OpenGL ES 3.0 context (EGL error 0x" << std::hex
                              << egl->getError() << ")";
            return nullptr;
        }

        Log(Debug::Info) << "ANGLE " << major << "." << minor << " on Metal: OpenGL ES 3.0 context";
        return context;
    }

    AngleContext::~AngleContext()
    {
        if (const EglApi* egl = eglApi(); egl != nullptr && mDisplay != nullptr)
        {
            egl->makeCurrent(mDisplay, nullptr, nullptr, nullptr);
            if (mContext != nullptr)
                egl->destroyContext(mDisplay, mContext);
            if (mSurface != nullptr)
                egl->destroySurface(mDisplay, mSurface);
            egl->terminate(mDisplay);
        }
        if (mView != nullptr)
            SDL_Metal_DestroyView(mView);
    }

    bool AngleContext::makeCurrent()
    {
        return eglApi()->makeCurrent(mDisplay, mSurface, mSurface, mContext);
    }

    bool AngleContext::release()
    {
        return eglApi()->makeCurrent(mDisplay, nullptr, nullptr, nullptr);
    }

    void AngleContext::present()
    {
        eglApi()->swapBuffers(mDisplay, mSurface);
    }

    void AngleContext::setSwapInterval(int interval)
    {
        eglApi()->swapInterval(mDisplay, interval);
    }
}
