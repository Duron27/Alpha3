#ifndef ALPHA3_SUPPORT_SUPPORT_H
#define ALPHA3_SUPPORT_SUPPORT_H

// Internal to the support library: what its parts give each other

#include <atomic>
#include <filesystem>
#include <functional>
#include <istream>
#include <string>
#include <string_view>
#include <vector>

#include <osg/BoundingBox>
#include <osg/Image>
#include <osg/Matrix>
#include <osg/Program>
#include <osg/ref_ptr>

#include "experimentalshaders.hpp"

namespace VFS
{
    class Manager;
}

namespace osg
{
    class CullStack;
    class Drawable;
    class GraphicsContext;
    class Node;
    class NodeVisitor;
    class State;
    class StateSet;
}

namespace Alpha3
{
    // ---- settings.cpp: OpenMW's settings, read through Settings::Manager. A setting the defaults do not declare is
    // still read from the user's settings.cfg; one that is missing everywhere gives the fallback.
    std::string setting(std::string_view category, std::string_view name, std::string_view fallback);
    bool settingBool(std::string_view category, std::string_view name, bool fallback);
    int settingInt(std::string_view category, std::string_view name, int fallback);
    float settingFloat(std::string_view category, std::string_view name, float fallback);

    // OpenMW's cache directory (OPENMW_ANDROID_CACHE_PATH, which the app points at shared storage: /storage/emulated/0/
    // Alpha3/cache, kept when the app is reinstalled)
    std::filesystem::path cacheDirectory();

    // The renderer prepareRenderer() picked
    bool usesZink();
    bool usesGles();

    // ---- angle.cpp (iOS): OSG_USE_ANGLE, and ANGLE's eglGetProcAddress (null when ANGLE is not loaded)
    bool usesAngle();
    void* angleProcAddress(const char* name);

    // ---- shaders.cpp
    bool translateShaderSource(const osg::Shader& shader, std::string& source);
    osg::ref_ptr<osg::Shader> mergeShaderStage(const std::vector<const osg::Shader*>& shaders);

    // ---- programcache.cpp
    std::string programBinaryKey(const osg::Program& program, const std::string& defines);
    osg::ref_ptr<osg::Program::ProgramBinary> loadProgramBinary(const std::string& key);
    void storeProgramBinary(const std::string& key, const osg::Program::ProgramBinary& binary);

    // ---- textureconversion.cpp / bcdecode.cpp
    bool textureConversionUsesEtc2();
    bool textureConversionEnabled();
    // since the process started; the launcher's pre-pass (texconv/) reports them
    struct TextureConversionCounts
    {
        unsigned converted, cached, failed;
    };
    TextureConversionCounts textureConversionCounts();
    osg::ref_ptr<osg::Image> transcodeDxt(const osg::Image& image, std::string_view name);
    osg::ref_ptr<osg::Image> decodeS3tc(const osg::Image& image);

    // ---- framepacing.cpp: the swap of an OpenGL ES window (Swappy), and the frame times for the launcher's overlay
    void swapBuffers(osg::GraphicsContext& gc);
    // Zink presents by copying into the window: called just before the copy is posted, holds it to a steady number of display
    // periods
    void paceZinkPost();

    // ---- framegen.cpp: frame generation (OPENMW_FRAMEGEN, the launcher's switch), in the swap. Shows the drawn frame through
    // present (and generated ones between, each through present too); false when frame generation is off, and the caller
    // swaps as it would. desktop: the Zink context (desktop GLSL, no Swappy).
    bool frameGenRequested();
    // present(presentAt): presentAt is when the frame is to be shown (CLOCK_MONOTONIC nanoseconds), 0 as soon as it can.
    bool frameGenSwap(osg::GraphicsContext& gc, int width, int height, bool desktop,
        const std::function<void(int64_t presentAt)>& present);

    // ---- zink.cpp: a clip plane in eye coordinates for the desktop OpenGL context (its modelview stays the identity)
    void zinkClipPlane(unsigned int index, const double* eyePlane);

    // ---- texturepreload.cpp: started by the first DDS image read, the list kept from the textures the game draws, stopped
    // before the engine's image manager is destroyed (its viewer closes the window's context first)
    void texturePreloadImageRead();
    void texturePreloadTextureLoading(const osg::Image& image);
    void texturePreloadStop();

    // ---- distantmeshes.cpp: true while the distant meshes read a texture for the CPU; the image hooks leave it as it is
    bool imageHooksBypassed();
    // A texture read for the CPU (the image hooks bypassed) as RGBA8, rows in the engine's order; extension picks the reader
    bool readTextureForCpu(std::istream& stream, std::string_view extension, int& width, int& height,
        std::vector<unsigned char>& rgba);
    // Every distant mesh the VFS lists made, or found in the cache, on that many threads, for
    // the launcher's cache builder (cachebuild.cpp). progress is called on the calling thread a few times a second; false
    // when cancelled or no distant mesh archive was added.
    struct DistantMeshProgress
    {
        unsigned done, total, made, notMade;
    };
    bool buildDistantMeshes(
        unsigned threads, const std::function<void(const DistantMeshProgress&)>& progress, const std::atomic<bool>& cancel);

    // ---- texturestreaming.cpp ([Textures] streaming): a large mipmapped image read as its smaller levels only, given to
    // prepare (null from it: as it is); the full image follows, through prepare on a background thread, once the small one
    // is uploaded, and is swapped in on the draw thread after a frame. Null: the image is not streamed.
    osg::ref_ptr<osg::Image> textureStreamingRead(
        osg::Image& image, osg::ref_ptr<osg::Image> (*prepare)(osg::Image&));
    void textureStreamingTextureLoading(const osg::Image& image);
    // The engine's main thread: an image it reads without a name (not through the image manager, which names its files
    // through readingImage) is not streamed. Noted when the engine sets up, and every frame.
    void noteMainThread();
    // The file the image manager is reading on this thread (empty: none), as it named it through readingImage
    std::string_view readingImagePath();
    // the scene camera's cull drawing something: its small textures are made full size once it covers more pixels across
    // than they have
    void textureStreamingDrawableAdded(osg::NodeVisitor& cullVisitor, osg::Drawable& drawable, const osg::Matrix& modelView);
    void textureStreamingFrame(); // on the draw thread, after the frame is drawn
    void textureStreamingStop(); // before the context closes

    // ---- occlusionculling.cpp ([Camera] occlusion culling): the OSG cull visitor's occlusion hooks. Each is a no-op
    // returning false for any traversal but the scene camera's.
    bool occlusionNodeOccluded(osg::CullStack& cullStack, const osg::Node& node);
    bool occlusionBoxOccluded(osg::CullStack& cullStack, const osg::BoundingBox& box);
    void occlusionDrawableAdded(osg::NodeVisitor& cullVisitor, osg::Drawable& drawable, const osg::Matrix& modelView);

    // ---- thermal.cpp ([General] thermal mode): how close Android forecasts the phone is to throttling, checked at most
    // every two seconds by whoever asks. 0: cool, or the mode is full; 1: warm; 2: hot.
    int thermalPressure();
    // threads for background work: all of them when cool, half when warm, one when hot
    unsigned thermalThreads(unsigned threads);

    // ---- experimental.cpp ([Experimental]): the switches, read once
    const ExperimentalSettings& experimentalSettings();
    // The engine's shaders with the experimental parts added, before they are translated; false: left as they are
    bool injectExperimentalShaders(const osg::Shader& shader, std::string& source);
    // What the injection depends on, for the program binary cache's key
    const std::string& experimentalShaderStamp();
    // The generated maps and the post-processing passes in the VFS, and the OpenMW settings that use them (called from
    // addKtxTextures, before the renderer reads its settings)
    void addExperimental(VFS::Manager& vfs);

    // ---- grass.cpp ([Experimental] grass): the terrain's chunks get blades as the scene camera culls them; the VFS for
    // reading their textures' colours
    void setGrassVfs(const VFS::Manager& vfs);
    void grassDrawableAdded(osg::NodeVisitor& cullVisitor, osg::Drawable& drawable, const osg::Matrix& modelView);
    // ---- experimentalstate.cpp: the state that gives a drawable its own variant of its program by its texture (tree
    // sway, the procedural material), and the defines OpenMW's shaders import for them
    const osg::StateSet* experimentalDrawableState(osg::NodeVisitor& cullVisitor, osg::Drawable& drawable);
    void experimentalImportDefines(const osg::Shader& shader, osg::ShaderDefines& defines);

    // ---- allocator.cpp: [General] allocator, once the settings are loaded
    void applyAllocatorSetting();
}

#endif
