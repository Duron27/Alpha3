// The graphics window's context when [Video] renderer = metal (iOS): frames go to the window through the CAMetalLayer of
// an SDL Metal view (context.mm). OSG still wants an OpenGL context to be current for what it asks the driver when the
// window is realized (extensions, limits, formats) and for objects it makes from then on: an Apple OpenGL ES 3.0 context
// with a 1x1 framebuffer, which nothing draws into (the renderer, renderer.mm, records Metal instead of OSG's draw). As
// src/vulkan/window.cpp does on Android with an EGL pbuffer.
#include "../support.hpp"

#include "context.hpp"

#include <alpha3/upstream.hpp>

#include <algorithm>
#include <dlfcn.h>

#import <OpenGLES/EAGL.h>
#include <SDL_metal.h>
#include <SDL_video.h>
#include <glad/gles2.h>

#include <components/debug/debuglog.hpp>

namespace Alpha3
{
    namespace
    {
        // OpenGL ES functions for glad, from Apple's OpenGL ES (SDL loads no OpenGL library for a Metal window)
        GLADapiproc glProc(const char* name)
        {
            static void* library = dlopen("/System/Library/Frameworks/OpenGLES.framework/OpenGLES", RTLD_NOW | RTLD_LOCAL);
            return library != nullptr ? reinterpret_cast<GLADapiproc>(dlsym(library, name)) : nullptr;
        }

        // The window's size in drawable pixels: its size in points at the render scale ([Video] resolution, which
        // setRenderResolution turns into pixels per point). Not SDL_Metal_GetDrawableSize: without SDL's high DPI flag
        // that is the size in points, whatever the scale.
        void drawableSize(SDL_Window* window, int& width, int& height)
        {
            int points[2] = { 0, 0 };
            SDL_GetWindowSize(window, &points[0], &points[1]);
            const float scale = std::max(windowPixelsPerPoint(), 0.25f);
            width = std::max(1, static_cast<int>(points[0] * scale + 0.5f));
            height = std::max(1, static_cast<int>(points[1] * scale + 0.5f));
        }

        class MetalWindowContext : public WindowContext
        {
        public:
            static std::unique_ptr<MetalWindowContext> create(SDL_Window* window)
            {
                std::unique_ptr<MetalWindowContext> context(new MetalWindowContext);
                context->mWindow = window;
                context->mView = SDL_Metal_CreateView(window);
                CAMetalLayer* layer
                    = context->mView ? (__bridge CAMetalLayer*)SDL_Metal_GetLayer(context->mView) : nil;
                if (layer == nil)
                {
                    Log(Debug::Error) << "Metal: no Metal view for the window: " << SDL_GetError();
                    return nullptr;
                }
                int width = 0, height = 0;
                drawableSize(window, width, height);
                context->mMetal = Mtl::Context::create(layer, width, height);
                if (context->mMetal == nullptr)
                    return nullptr;
                context->mLayer = layer;
                if (!context->createShim())
                    return nullptr;
                Log(Debug::Info) << "Metal: window " << width << "x" << height
                                 << " (OpenGL ES 3.0 context for OSG's queries only)";
                return context;
            }

            ~MetalWindowContext() override
            {
                release();
                if (mFramebuffer != 0 && mGl != nil)
                {
                    [EAGLContext setCurrentContext:mGl];
                    glDeleteFramebuffers(1, &mFramebuffer);
                    glDeleteRenderbuffers(2, mRenderbuffers);
                    [EAGLContext setCurrentContext:nil];
                }
                mGl = nil;
                if (mView != nullptr)
                    SDL_Metal_DestroyView(mView);
            }

            int depthBits() const override { return 24; }
            int stencilBits() const override { return 8; }

            bool makeCurrent() override
            {
                if (![EAGLContext setCurrentContext:mGl])
                    return false;
                if (mFramebuffer != 0)
                    glBindFramebuffer(GL_FRAMEBUFFER, mFramebuffer);
                return true;
            }

            bool release() override { return [EAGLContext setCurrentContext:nil]; }

            void present() override
            {
                int width = 0, height = 0;
                drawableSize(mWindow, width, height);
                mMetal->present(width, height);
            }

        private:
            MetalWindowContext() = default;

            bool createShim()
            {
                mGl = [[EAGLContext alloc] initWithAPI:kEAGLRenderingAPIOpenGLES3];
                if (mGl == nil || ![EAGLContext setCurrentContext:mGl])
                {
                    Log(Debug::Error) << "Metal: OSG's OpenGL ES context could not be made";
                    return false;
                }
                if (!gladLoadGLES2(glProc))
                    Log(Debug::Error) << "Failed to initialize GLAD";
                // Apple's OpenGL ES has no default framebuffer: a 1x1 one stands in, so that what OSG does on its own
                // (a clear, a query of the framebuffer) has one
                glGenFramebuffers(1, &mFramebuffer);
                glBindFramebuffer(GL_FRAMEBUFFER, mFramebuffer);
                glGenRenderbuffers(2, mRenderbuffers);
                glBindRenderbuffer(GL_RENDERBUFFER, mRenderbuffers[0]);
                glRenderbufferStorage(GL_RENDERBUFFER, GL_RGBA8, 1, 1);
                glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_RENDERBUFFER, mRenderbuffers[0]);
                glBindRenderbuffer(GL_RENDERBUFFER, mRenderbuffers[1]);
                glRenderbufferStorage(GL_RENDERBUFFER, GL_DEPTH24_STENCIL8, 1, 1);
                glFramebufferRenderbuffer(GL_FRAMEBUFFER, GL_DEPTH_STENCIL_ATTACHMENT, GL_RENDERBUFFER, mRenderbuffers[1]);
                if (glCheckFramebufferStatus(GL_FRAMEBUFFER) != GL_FRAMEBUFFER_COMPLETE)
                    Log(Debug::Warning) << "Metal: OSG's 1x1 OpenGL ES framebuffer is incomplete";
                release();
                return true;
            }

            SDL_Window* mWindow = nullptr;
            SDL_MetalView mView = nullptr;
            CAMetalLayer* mLayer = nil;
            Mtl::Context* mMetal = nullptr;
            EAGLContext* mGl = nil;
            GLuint mFramebuffer = 0;
            GLuint mRenderbuffers[2] = { 0, 0 };
        };
    }

    // iOS's (src/vulkan/window.cpp is Android's): Metal's context, when prepareMetal picked it
    std::unique_ptr<WindowContext> createWindowContext(SDL_Window* window, bool& wanted)
    {
        wanted = usesMetal();
        if (!wanted)
            return nullptr;
        return MetalWindowContext::create(window);
    }
}
