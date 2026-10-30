#ifndef ALPHA3_SUPPORT_SUPPORT_H
#define ALPHA3_SUPPORT_SUPPORT_H

// Internal to the support library: what its parts give each other

#include <filesystem>
#include <functional>
#include <string>
#include <string_view>
#include <vector>

#include <osg/Image>
#include <osg/Program>
#include <osg/ref_ptr>

namespace osg
{
    class GraphicsContext;
    class State;
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

    // ---- allocator.cpp: [General] allocator, once the settings are loaded
    void applyAllocatorSetting();
}

#endif
