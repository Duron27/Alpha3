// The graphics window's context when [Video] renderer = vulkan: frames go to the window through a Vulkan swap chain
// (context.cpp). OSG still wants an OpenGL context to be current for what it asks the driver when the window is
// realized (extensions, limits, formats) and for objects it makes from then on: a 1x1 OpenGL ES pbuffer of the system's
// EGL, which nothing draws into (the renderer, renderer.cpp, records Vulkan instead of OSG's draw).
#include "../support.hpp"

#include "context.hpp"

#include <alpha3/upstream.hpp>

#include <dlfcn.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <SDL_syswm.h>
#include <SDL_video.h>
#include <glad/gles2.h>

#include <components/debug/debuglog.hpp>

namespace Alpha3
{
    namespace
    {
        ANativeWindow* nativeWindow(SDL_Window* window)
        {
            SDL_SysWMinfo info;
            SDL_VERSION(&info.version);
            if (!SDL_GetWindowWMInfo(window, &info) || info.subsystem != SDL_SYSWM_ANDROID)
                return nullptr;
            return info.info.android.window;
        }

        // OpenGL ES functions for glad: the core ones from libGLESv3 (Android's eglGetProcAddress may not give them),
        // the extensions from EGL
        GLADapiproc glProc(const char* name)
        {
            static void* library = dlopen("libGLESv3.so", RTLD_NOW | RTLD_LOCAL);
            if (library != nullptr)
                if (void* proc = dlsym(library, name))
                    return reinterpret_cast<GLADapiproc>(proc);
            return reinterpret_cast<GLADapiproc>(eglGetProcAddress(name));
        }

        class VulkanWindowContext : public WindowContext
        {
        public:
            static std::unique_ptr<VulkanWindowContext> create(SDL_Window* window)
            {
                std::unique_ptr<VulkanWindowContext> context(new VulkanWindowContext);
                context->mWindow = window;
                ANativeWindow* native = nativeWindow(window);
                if (native == nullptr)
                {
                    Log(Debug::Error) << "Vulkan: no Android native window: " << SDL_GetError();
                    return nullptr;
                }
                int width = 0, height = 0;
                SDL_GL_GetDrawableSize(window, &width, &height);
                context->mVulkan = Vk::Context::create(native, width, height);
                if (context->mVulkan == nullptr)
                    return nullptr;
                context->mNative = native;
                if (!context->createShim())
                    return nullptr;
                Log(Debug::Info) << "Vulkan: window " << width << "x" << height
                                 << " (OpenGL ES pbuffer for OSG's queries only)";
                return context;
            }

            ~VulkanWindowContext() override
            {
                if (mDisplay != EGL_NO_DISPLAY)
                {
                    eglMakeCurrent(mDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT);
                    if (mSurface != EGL_NO_SURFACE)
                        eglDestroySurface(mDisplay, mSurface);
                    if (mContext != EGL_NO_CONTEXT)
                        eglDestroyContext(mDisplay, mContext);
                }
            }

            int depthBits() const override { return 24; }
            int stencilBits() const override { return 8; }

            bool makeCurrent() override { return eglMakeCurrent(mDisplay, mSurface, mSurface, mContext) == EGL_TRUE; }

            bool release() override
            {
                return eglMakeCurrent(mDisplay, EGL_NO_SURFACE, EGL_NO_SURFACE, EGL_NO_CONTEXT) == EGL_TRUE;
            }

            void present() override
            {
                // the app went to the background and came back with a new window
                ANativeWindow* native = nativeWindow(mWindow);
                if (native != mNative)
                {
                    mNative = native;
                    mVulkan->setWindow(native);
                }
                if (native == nullptr)
                    return;
                int width = 0, height = 0;
                SDL_GL_GetDrawableSize(mWindow, &width, &height);
                mVulkan->present(width, height);
            }

        private:
            VulkanWindowContext() = default;

            bool createShim()
            {
                mDisplay = eglGetDisplay(EGL_DEFAULT_DISPLAY);
                if (mDisplay == EGL_NO_DISPLAY || !eglInitialize(mDisplay, nullptr, nullptr))
                {
                    Log(Debug::Error) << "Vulkan: no EGL display for OSG's context";
                    return false;
                }
                const EGLint configAttributes[] = { EGL_RENDERABLE_TYPE, EGL_OPENGL_ES3_BIT_KHR, EGL_SURFACE_TYPE,
                    EGL_PBUFFER_BIT, EGL_RED_SIZE, 8, EGL_GREEN_SIZE, 8, EGL_BLUE_SIZE, 8, EGL_DEPTH_SIZE, 24,
                    EGL_STENCIL_SIZE, 8, EGL_NONE };
                EGLConfig config = nullptr;
                EGLint configs = 0;
                if (!eglChooseConfig(mDisplay, configAttributes, &config, 1, &configs) || configs == 0)
                {
                    Log(Debug::Error) << "Vulkan: no EGL configuration for OSG's context";
                    return false;
                }
                const EGLint contextAttributes[]
                    = { EGL_CONTEXT_MAJOR_VERSION, 3, EGL_CONTEXT_MINOR_VERSION, 2, EGL_NONE };
                mContext = eglCreateContext(mDisplay, config, EGL_NO_CONTEXT, contextAttributes);
                const EGLint surfaceAttributes[] = { EGL_WIDTH, 1, EGL_HEIGHT, 1, EGL_NONE };
                mSurface = eglCreatePbufferSurface(mDisplay, config, surfaceAttributes);
                if (mContext == EGL_NO_CONTEXT || mSurface == EGL_NO_SURFACE || !makeCurrent())
                {
                    Log(Debug::Error) << "Vulkan: OSG's OpenGL ES context could not be made (EGL error 0x" << std::hex
                                      << eglGetError() << std::dec << ")";
                    return false;
                }
                if (!gladLoadGLES2(glProc))
                    Log(Debug::Error) << "Failed to initialize GLAD";
                release();
                return true;
            }

            SDL_Window* mWindow = nullptr;
            ANativeWindow* mNative = nullptr;
            Vk::Context* mVulkan = nullptr;
            EGLDisplay mDisplay = EGL_NO_DISPLAY;
            EGLContext mContext = EGL_NO_CONTEXT;
            EGLSurface mSurface = EGL_NO_SURFACE;
        };
    }

    std::unique_ptr<WindowContext> createWindowContext(SDL_Window* window, bool& wanted)
    {
        wanted = usesZink() || usesVulkan();
        if (usesZink())
            return ZinkContext::create(window);
        if (usesVulkan())
            return VulkanWindowContext::create(window);
        return nullptr;
    }
}
