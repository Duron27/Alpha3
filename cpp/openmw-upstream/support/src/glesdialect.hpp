#ifndef ALPHA3_SUPPORT_GLESDIALECT_H
#define ALPHA3_SUPPORT_GLESDIALECT_H

#include <string>
#include <vector>

namespace Alpha3
{
    // OpenMW's compatibility shaders are written in GLSL 1.20 and rely on its built-in variables. OSG here is built
    // for OpenGL ES 3 (no fixed function: matrices and vertex attributes come as the uniforms and attributes
    // osg_ModelViewMatrix, osg_Vertex, ...), so a context cannot be handed the sources as they are:
    //   * Gles: the system's OpenGL ES driver, 3.2 on Android phones, 3.0 on iOS (glesVersion()). The whole source is moved
    //     to "#version 320 es" or "#version 300 es".
    //   * DesktopCompat: desktop OpenGL (compatibility profile) through Mesa Zink. Stays "#version 120"; only what the OSG
    //     build cannot feed is changed: matrices read in fragment shaders (OSG renames the built-ins of vertex shaders by
    //     itself, but not of other stages).
    enum class GlslTarget
    {
        Desktop, // sources used as they are
        DesktopCompat,
        Gles,
    };

    // The ESSL version shaders are translated to: 300 when OPENMW_GLES_VERSION asks for OpenGL ES 3.0 ("30", which the
    // iOS app sets: Apple's OpenGL ES stops at 3.0), 310 for "31", else 320
    unsigned int glesVersion();

    enum class GlslStage
    {
        Vertex,
        Fragment,
        Compute,
    };

    // The target of this run: Mesa Zink (OSG_USE_ZINK) takes desktop GLSL, anything else is OpenGL ES (glesVersion())
    GlslTarget glslTarget();

    // Returns the source translated for the target, or the source itself when there is nothing to do (a target of Desktop,
    // a source that is not "#version 120", one that was translated already). depthClamp: a vertex shader drawn with
    // GL_DEPTH_CLAMP, which OpenGL ES does not have, clamps its z to the near plane itself.
    std::string translateGlsl(const std::string& source, GlslStage stage, GlslTarget target, bool depthClamp = false);

    // Whether the OpenGL ES context has GL_EXT_clip_cull_distance, read with its extensions. With it, the gl_ClipVertex
    // written by vertex shaders becomes gl_ClipDistance against OSG's osg_ClipPlane<n> uniforms; without it, it goes to
    // the fragment shaders, which discard what is behind a plane.
    void setClipDistancesAvailable(bool available);

    // A uniform declared with an initializer ("uniform vec2 scaling = vec2(1.0, 1.0);"), which OpenGL ES does not have: the
    // translation drops it, and the program is given the value once it is linked. type is the GLSL type (bool, int, uint,
    // float or one of their vectors), values one per component.
    struct UniformInitializer
    {
        std::string name;
        std::string type;
        std::vector<float> values;
    };

    // The initialized uniforms of a source; ones whose value is not a literal (or a constructor of literals) are left out
    std::vector<UniformInitializer> uniformInitializers(const std::string& source);

    // Changes whenever this file or the conversion is rebuilt: part of the key of saved program binaries, which hold
    // what the translation made of the shaders
    const char* translatorStamp();
}

#endif
