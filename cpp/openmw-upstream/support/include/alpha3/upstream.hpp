#ifndef ALPHA3_UPSTREAM_H
#define ALPHA3_UPSTREAM_H

// What the Android build of stock OpenMW calls in Alpha3's support library (cpp/openmw-upstream/support). Most of the
// library works through OSG's RuntimeHooks and needs no call; these are the few places the engine has to ask.

#include <memory>

#include <osg/Camera>
#include <osg/Matrixd>
#include <osg/Texture>
#include <osg/ref_ptr>

struct SDL_Window;

namespace Resource
{
    class ImageManager;
}

namespace osg
{
    class GraphicsContext;
}

namespace VFS
{
    class Manager;
}

namespace Alpha3
{
    // Reads [Video] renderer and sets up the environment the rest of the run reads ("opengl" = desktop OpenGL through
    // Mesa Zink, OSG_USE_ZINK; anything else = the phone's OpenGL ES 3.2 driver, OPENMW_GLES_VERSION). Call before
    // creating the window. True for Zink: the window must then be made without SDL_WINDOW_OPENGL, since an EGL surface on
    // it keeps the frames from being copied to it.
    bool prepareRenderer();

    // How the texture preload reaches the engine's image manager: a function returning it, or null while there is none.
    // Called from androidmain before the engine starts; [Textures] preload threads (default 3, 0 = off) is read later.
    void setImageManagerSource(Resource::ImageManager* (*source)());

    // .ktx textures (made ahead of time, e.g. by kram) are read in place of the .dds of the same name: adds the archive that
    // lists them under that name. Call after the data directories and archives are added, before the index is built.
    void addKtxTextures(VFS::Manager& vfs);

    // Frame generation (OPENMW_FRAMEGEN) builds the frame between two rendered ones from the camera's motion and the
    // scene's depth when the engine hands them over: each frame, from the main thread before it is rendered.
    struct FrameGenView
    {
        unsigned frame = 0; // the viewer's frame number
        osg::Matrixd view;
        osg::Matrixd projection; // the camera's, not reversed
        bool reversedDepth = false;
        // The post processor changes the scene's colours: the frame before the GUI is copied, the scene texture is not it
        bool postProcessing = false;
        osg::ref_ptr<osg::Texture> scene; // what the scene was drawn into, before post processing and the GUI
        osg::ref_ptr<osg::Texture> depth;
        osg::ref_ptr<osg::Texture> opaqueDepth; // with the first person model's depth, which the depth above has not
    };
    bool frameGenWanted();
    void frameGenSubmitView(FrameGenView view);
    // For the post processor's HUD camera (it draws the scene to the window, the GUI comes after): keeps that image
    osg::Camera::DrawCallback* frameGenPreGuiCapture();

    // Compute shaders (clustered lighting, ripples) are written for GLSL 430/440. On OpenGL ES they only exist as the ESSL
    // 3.20 that glslang and SPIRV-Cross make of them, so they need that conversion on (OPENMW_DISABLE_SPIRV turns it off).
    bool computeShadersAvailable();

    // Desktop OpenGL through Mesa Zink (libOSMesa_8.so) for a window that has no SDL/EGL context: the frame is drawn into
    // a buffer of OSMesa's and copied to the window's ANativeWindow on every swap.
    class ZinkContext
    {
    public:
        // Null if Zink cannot start (the reason is logged)
        static std::unique_ptr<ZinkContext> create(SDL_Window* window);
        ~ZinkContext();

        // What the context was made with, for the traits of the graphics context
        int depthBits() const { return 24; }
        int stencilBits() const { return mStencilBits; }

        bool makeCurrent();
        static bool release();
        // Hands the frame to the window; follows the window's size
        void present();

    private:
        ZinkContext() = default;

        SDL_Window* mWindow = nullptr;
        void* mContext = nullptr;
        unsigned char* mBuffer = nullptr;
        void* mNativeWindow = nullptr;
        int mWidth = 0;
        int mHeight = 0;
        int mStencilBits = 8;
    };

    // iOS: the window always fills the screen at its size in points, and [Video] resolution is the scale it is rendered at.
    // nativeScreenScale: the screen's pixels per point. renderScale: drawable pixels per point now (1 until set).
    float nativeScreenScale();
    float renderScale();
    // Renders the window at about width x height pixels (the width decides, the window keeps its shape): sets the scale of
    // SDL's view, the graphics context takes the new size on its draw thread, then the engine gets a size change event.
    void setRenderResolution(SDL_Window* window, osg::GraphicsContext* context, int width, int height);

    // iOS, OSG_USE_ANGLE: OpenGL ES 3.0 through ANGLE on Metal (the app's libGLESv2_angle.framework, opened at runtime)
    // for a window that has no SDL context: ANGLE's EGL draws into a CAMetalLayer view SDL puts in the window.
    class AngleContext
    {
    public:
        // Null if ANGLE cannot start (the reason is logged). samples: MSAA of the window, 0 for none
        static std::unique_ptr<AngleContext> create(SDL_Window* window, int samples);
        ~AngleContext();

        // What the context was made with, for the traits of the graphics context
        int depthBits() const { return mDepthBits; }
        int stencilBits() const { return mStencilBits; }
        int samples() const { return mSamples; }

        bool makeCurrent();
        bool release();
        void present();
        void setSwapInterval(int interval);

    private:
        AngleContext() = default;

        SDL_Window* mWindow = nullptr;
        void* mView = nullptr;
        void* mDisplay = nullptr;
        void* mSurface = nullptr;
        void* mContext = nullptr;
        int mDepthBits = 24;
        int mStencilBits = 8;
        int mSamples = 0;
    };
}

#endif
