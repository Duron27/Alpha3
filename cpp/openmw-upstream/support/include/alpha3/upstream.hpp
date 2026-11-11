#ifndef ALPHA3_UPSTREAM_H
#define ALPHA3_UPSTREAM_H

// What the Android build of stock OpenMW calls in Alpha3's support library (cpp/openmw-upstream/support). Most of the
// library works through OSG's RuntimeHooks and needs no call; these are the few places the engine has to ask.

#include <array>
#include <cstddef>
#include <functional>
#include <memory>
#include <string>
#include <string_view>
#include <vector>

#include <osg/BoundingSphere>
#include <osg/Camera>
#include <osg/Group>
#include <osg/Matrix>
#include <osg/Matrixd>
#include <osg/Texture>
#include <osg/Vec3f>
#include <osg/Vec4f>
#include <osg/ref_ptr>

struct SDL_Window;

namespace Resource
{
    class ImageManager;
}

namespace osg
{
    class Geometry;
    class GraphicsContext;
    class Node;
    class FrameBufferObject;
    class RenderInfo;
    class State;
    class Viewport;
}

namespace MyGUI
{
    class Widget;
}

namespace osgUtil
{
    class CullVisitor;
    class RenderBin;
}

namespace SceneUtil
{
    class LightManager;
}

namespace Shader
{
    class ShaderManager;
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

    // The Lua profiler's report for the performance HUD's Inspect (LuaManager::formatResourceUsageStats, as the F10
    // window shows it): a function returning it, called on the engine's main thread. Called from androidmain.
    void setLuaProfileSource(std::string (*source)());

    // .ktx textures (made ahead of time, e.g. by kram) are read in place of the .dds of the same name: adds the archive that
    // lists them under that name. Call after the data directories and archives are added, before the index is built.
    // Also adds the distant mesh archive after it (addDistantMeshes) and the experimental graphics' (generated maps,
    // post-processing passes; src/experimental.cpp), so the engine's one call covers them all.
    void addKtxTextures(VFS::Manager& vfs);

    // Distant versions (x_dist.nif) of meshes, made on first use (src/distantmeshes.cpp): trees as cards drawn from six
    // sides ([Terrain] impostors), the rest simplified ([Terrain] distant mesh simplification). Object paging reads them
    // only outside the active grid.
    void addDistantMeshes(VFS::Manager& vfs);

    // The image manager names the file it is about to read on this thread (empty after it): texture streaming keeps
    // pictures shown flat on the screen (menu art, splash screens, the GUI) at full size, by their path.
    void readingImage(std::string_view path);

    // Occlusion mesh splitting ([Camera] occlusion mesh splitting, src/meshsplitting.cpp): large static shapes cut into
    // pieces on a grid, so occlusion culling can hide the parts behind a wall. Does nothing when off.
    bool meshSplittingOn();
    // A mesh the scene manager loaded, before it is cached; path is for the log
    void splitMeshForCulling(osg::Node& node, std::string_view path);
    // An object paging active grid chunk after merging. The callback is given each piece made, with the original vertex
    // index of each of the piece's vertices (ascending), to carry over what refers to vertex ranges (refnum markers).
    using SplitPieceCallback
        = std::function<void(const osg::Geometry& original, osg::Geometry& piece, const std::vector<unsigned>& kept)>;
    void splitChunkForCulling(osg::Node& node, const SplitPieceCallback& callback);

    // Occlusion light culling ([Camera] occlusion light culling, src/occlusionculling.cpp): a point light whose sphere of
    // influence (in view space) is hidden behind the occlusion buffer's occluders, in the scene camera's cull. Always
    // false when off, and for other cameras (shadows, reflections).
    bool occlusionLightOccluded(osgUtil::CullVisitor& cullVisitor, const osg::BoundingSphere& viewSphere);

    // Point light shadows ([Shadows] point light shadows, src/pointshadows.cpp): the closest point lights cast shadows,
    // drawn with castMask from sceneRoot's children; their maps on textureUnit. Does nothing when off, on desktop GL and
    // with clustered lighting.
    void installPointShadows(SceneUtil::LightManager& sceneRoot, Shader::ShaderManager& shaderManager, int textureUnit,
        unsigned int castMask);
    // whether it is on in the settings (the unit is reserved only then)
    int pointShadowLights();
    // The state of an object lit by these lights (OpenMW's per-object light list: the lights' ids, in the view of
    // viewMatrix) when some of them are shadowed: the define A3_PS_ON, the number of them (the object and terrain
    // shaders have the point light shadow code under it, a varying for each), and which shadow maps they have. The
    // others draw without it (the code costs every vertex and pixel of a shader that has it, used or not).
    void pointShadowListState(const osg::RefMatrix* viewMatrix, const std::vector<int>& lightIds, osg::StateSet& state);

    // Seamless cells ([Cells] seamless cells, src/seamless.cpp): a change of cells in a game under way (a door, an
    // exterior cell border) shows no loading screen and no fade.
    bool seamlessCells();
    // Seamless doors ([Cells] seamless doors): an exterior door to an interior opens onto it (OpenMW's
    // mwworld/seamlessdoors.cpp picks the door and builds the interior's objects)
    bool seamlessDoors();
    // Cells loaded over frames ([Cells] load cells over frames, with seamless cells; OpenMW's scene.cpp): a change of
    // cells in a game under way puts the objects within cellLoadNearRadius() of the player in the scene at once and the
    // rest, nearest first, cellLoadBudget() milliseconds a frame
    bool cellsOverFrames();
    float cellLoadNearRadius();
    float cellLoadBudget();
    // Smoother exterior cell borders (src/cellborders.cpp), each its own [Cells] setting:
    // background object paging: a merged-statics chunk that has to be rebuilt (a cell going in or out of the active
    // grid, an object of a loading cell taken out of it) is built on the work queue while the old one is drawn
    // (OpenMW's objectpaging.cpp), instead of at once in the cull or the focus ray
    bool backgroundObjectPaging();
    // preload levelled creatures: the cell preloader also loads the meshes a levelled creature list can pick at the
    // player's level (OpenMW's creaturelevlist.cpp), so the creature spawned when the cell loads is already loaded
    bool preloadLevelledCreatures();
    // spread cell unloading: an unloaded exterior cell's collision objects are released and its fog of war saved a
    // little each frame (OpenMW's physicssystem.cpp and localmap.cpp) instead of all in the frame of the cell change;
    // cellUnloadBudget() milliseconds a frame for the collision objects
    bool spreadCellUnloading();
    float cellUnloadBudget();
    // While OpenMW's Scene::changeCellGrid runs (a CellBorderChange on its stack): only the cells it unloads have their
    // work spread; an unload outside it (a save loaded, which frees the cells next) first finishes what is left
    bool inCellBorderChange();
    void cellBorderChange(int step);
    struct CellBorderChange
    {
        CellBorderChange() { cellBorderChange(1); }
        ~CellBorderChange() { cellBorderChange(-1); }
        CellBorderChange(const CellBorderChange&) = delete;
        CellBorderChange& operator=(const CellBorderChange&) = delete;
    };
    // How the other side of a door portal is lit: as an interior (no fog, the cell's colours), or as the exterior
    // (the weather's colours, fogged out to the fill colour by fogEnd, where its objects stop)
    struct DoorPortalLight
    {
        osg::Vec4f mAmbient;
        osg::Vec4f mSun;
        // what is seen where the other side has nothing: the sky outside, from mFill at the horizon (the fog's
        // colour) up to mSky overhead; black inside
        osg::Vec4f mFill;
        osg::Vec4f mSky;
        bool mFog = false;
        float mFogStart = 0.f;
        float mFogEnd = 0.f;
        // the weather's clouds over the sky outside (none inside): its texture, the next weather's blended in, and how
        // far they have scrolled
        std::string mClouds;
        std::string mNextClouds;
        float mCloudBlend = 0.f;
        float mCloudOffset = 0.f;
    };
    // The doorway (quad, world space, where the closed door was; front: its normal, toward the side it is seen from)
    // and the other side placed behind it (otherToWorld): a group for the scene root (src/doorportal.cpp). With the
    // camera in the doorway (depth: how near it), the doorway is drawn as a box that deep behind it, over all, so that
    // the near plane does not cut it away as the player walks through.
    // door: the closed door's model where it was (world space), its shape the doorway's when the camera is not in it
    osg::ref_ptr<osg::Group> makeDoorPortal(const std::array<osg::Vec3f, 4>& quad, const osg::Vec3f& front, float depth,
        osg::ref_ptr<osg::Node> door, osg::ref_ptr<osg::Node> other, const osg::Matrixd& otherToWorld,
        const DoorPortalLight& light);
    // the light changed (the weather outside)
    void setDoorPortalLight(osg::Group& portal, const DoorPortalLight& light);

    // A doorway the player walks through ([Cells] seamless doors): what their collision meets in it, the door and the
    // wall behind it, does not stop them (OpenMW's mwphysics asks doorwayPassable of each wall it meets; floors still hold
    // them up). Set from the main thread, asked from the physics threads.
    struct Doorway
    {
        // the doorway's middle at the floor, its normal (horizontal) and the horizontal across it
        osg::Vec3f mCentre;
        osg::Vec3f mNormal;
        osg::Vec3f mAcross;
        float mHalfWidth = 0.f;
        float mHeight = 0.f;
        // how far in front of it and behind it
        float mDepth = 0.f;
    };
    // actor: the player's collision object; nullptr: no doorway
    void setDoorway(const void* actor, const Doorway* doorway);
    // Live cells: the doorways every actor walks through (the door's on each side, each in its own cell's space); at
    // most 4, none: count 0
    void setDoorways(const Doorway* doorways, std::size_t count);
    // actor: the actor's collision object (any actor)
    bool doorwayPassable(const void* actor, const osg::Vec3f& point, const osg::Vec3f& normal);

    // The weather outside while the player is in an interior (OpenMW's mwworld/weather.cpp), for the view out through
    // a door: the fog's colour (the sky's, at the horizon), the sky's overhead, the ambient light and the sun's
    void setOutsideLight(const osg::Vec4f& fog, const osg::Vec4f& sky, const osg::Vec4f& ambient, const osg::Vec4f& sun);
    bool outsideLight(osg::Vec4f& fog, osg::Vec4f& sky, osg::Vec4f& ambient, osg::Vec4f& sun);
    // and its clouds (textures, the blend between them, their speed), scrolled by duration as the sky scrolls them
    void setOutsideClouds(const std::string& texture, const std::string& next, float blend, float speed, float duration);
    // light's mClouds, mNextClouds, mCloudBlend and mCloudOffset
    void outsideClouds(DoorPortalLight& light);

    // Render scale ([Experimental] render scale, src/renderscale.cpp): the 3D scene drawn at this much of the window's
    // size (1: off, and then the calls below are not made), upscaled and sharpened onto the screen.
    float renderScale();
    // a window size scaled
    int renderScaled(int windowSize);
    // Where the post-processing chain's last pass draws when scaled (at the scene's size), instead of on the screen
    osg::FrameBufferObject& renderScaleChainOutput(int width, int height);
    osg::Texture* renderScaleChainOutputTexture();
    // Draws [source] (the scene's size) into what is bound for drawing, in [screen]: upscaled, then sharpened. [draw]
    // draws the fullscreen triangle.
    void renderScaleResolve(
        osg::State& state, osg::Texture& source, const osg::Viewport& screen, const std::function<void()>& draw);

    // Memory bandwidth on tile GPUs (src/bandwidth.cpp).
    // [Water] idle ripples: seconds without a ripple after which the ripple simulation clears its map and stops until
    // the next one (0: off, it always runs)
    float ripplesIdleAfter();
    // [Video] discard unused buffers: glInvalidateFramebuffer on what is bound for drawing (the window's, or an FBO's
    // first colour attachment and packed depth stencil). Does nothing when off.
    void discardDrawBuffers(bool color, bool depthStencil);
    // for the post processor's camera, which draws the finished scene over the whole window: a pre draw callback
    // discarding the window's colour, depth and stencil (null when off)
    osg::Camera::DrawCallback* discardWindowCallback();
    // [Video] skip unused opaque copy: whether the scene's colour and depth are copied after the opaque geometry this
    // frame (the OpaqueResolve bin): something drawn after reads them (water, soft particles, distortion), the
    // post-processing chain may, or frame generation does. Always true when off and for other cameras.
    bool opaqueCopyWanted(const osgUtil::RenderBin& resolveBin, osg::RenderInfo& renderInfo);
    // that frame's copy was skipped: what only adds to it (the first person model's depth) can be skipped too
    bool opaqueCopySkipped(unsigned frameNumber);
    // the post-processing chain has a technique of the user's (from the post processor, when its chain changes)
    void setPostProcessingReadsDepth(bool reads);

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

    // The context of a window SDL has no OpenGL context for (made without SDL_WINDOW_OPENGL): Zink's, or Vulkan's.
    // SDLUtil::GraphicsWindowSDL2 makes it current, releases it and presents through it.
    class WindowContext
    {
    public:
        virtual ~WindowContext() = default;
        // What the context was made with, for the traits of the graphics context
        virtual int depthBits() const = 0;
        virtual int stencilBits() const = 0;
        virtual bool makeCurrent() = 0;
        virtual bool release() = 0;
        // Hands the frame to the window; follows the window's size
        virtual void present() = 0;
    };

    // The renderer prepareRenderer() picked, when it needs a context of its own (wanted is then true): Zink's or
    // Vulkan's. Null when it could not start (the reason is logged), or when SDL makes the context (wanted false).
    std::unique_ptr<WindowContext> createWindowContext(SDL_Window* window, bool& wanted);

    // Under Vulkan, gives the main camera the renderer that records its culled frame as Vulkan commands instead of
    // drawing it with OpenGL (src/vulkan/renderer.cpp). Does nothing for the other renderers.
    void installVulkanRenderer(osg::Camera& camera);

    // Desktop OpenGL through Mesa Zink (libOSMesa_8.so) for a window that has no SDL/EGL context: the frame is drawn into
    // a buffer of OSMesa's and copied to the window's ANativeWindow on every swap.
    class ZinkContext : public WindowContext
    {
    public:
        // Null if Zink cannot start (the reason is logged)
        static std::unique_ptr<ZinkContext> create(SDL_Window* window);
        ~ZinkContext() override;

        int depthBits() const override { return 24; }
        int stencilBits() const override { return mStencilBits; }

        bool makeCurrent() override;
        bool release() override { return releaseCurrent(); }
        static bool releaseCurrent();
        void present() override;

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

    // Where buildNavMesh is. phase 0: reading the content files; 1: worldspaces being gathered and their tiles made
    // (tilesDone of tiles: those of the worldspaces gathered so far); 2: the tiles left after the last worldspace;
    // 3: compacting the database.
    struct NavMeshProgress
    {
        int phase;
        std::size_t worldspacesDone, worldspaces, tilesDone, tiles;
    };

    // navmesh.db for the mod list, as openmw-navmeshtool makes it and with its options (src/navmeshbuild.cpp); argv[0] is
    // the program name. progress is called on the calling thread a few times a second, and once at the end. 0: done,
    // 1: cancelled, 2: out of space, 3: failed (see navmeshtool.log). Android calls it in a process of its own
    // (androidmain.cpp); also exported as alpha3_navmesh_run for iOS.
    int buildNavMesh(
        int argc, const char* const* argv, const std::function<void(const NavMeshProgress&)>& progress = {});

    // End of HUD::onFrame (main: the HUD's main widget): its elements where the touch overlay's edit mode put them,
    // at their size (src/hudlayout.cpp)
    void hudLayoutFrame(MyGUI::Widget* main);
}

#endif
