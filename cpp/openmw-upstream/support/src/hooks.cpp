// OSG's RuntimeHooks for the Android build of stock OpenMW, installed when libopenmw_upstream.so loads. They do what
// the OpenMW patch series used to do inside the engine: GL entry points through glad, shader translation and merging,
// the program binary cache, frame pacing and texture conversion.
#include "glesdialect.hpp"
#include "support.hpp"

#include <alpha3/upstream.hpp>

#include <glad/gles2.h>

#include <algorithm>
#include <cxxabi.h>
#include <dlfcn.h>
#include <unwind.h>
#include <atomic>
#include <cstdlib>
#include <mutex>
#include <utility>
#include <vector>

#include <SDL_video.h>

#include <osg/GLExtensions>
#include <osg/GraphicsContext>
#include <osg/Program>
#include <osg/RuntimeHooks>

#include <components/debug/debuglog.hpp>

// apps/openmw/androidmain.cpp: the player state the app polls, read where the game state is not changing
extern "C" void omw_frame_update();

namespace Alpha3
{
    namespace
    {
        constexpr GLenum sDxt1Rgb = 0x83F0, sDxt1Rgba = 0x83F1, sDxt3Rgba = 0x83F2, sDxt5Rgba = 0x83F3;

        bool isDxt(GLenum format)
        {
            return format == sDxt1Rgb || format == sDxt1Rgba || format == sDxt3Rgba || format == sDxt5Rgba;
        }

        // OpenGL ES has no BGR(A) upload with an RGB(A) internal format (the uncompressed DDS files of the sky are BGRA):
        // the channels are swapped once on load. Zink is desktop GL and takes them as they are.
        void swapBgrToRgb(osg::Image& image)
        {
            const bool alpha = image.getPixelFormat() == GL_BGRA;
            const unsigned channels = alpha ? 4 : 3;
            // every mip level, rows padded to the image's packing
            const unsigned levels = std::max(1u, image.getNumMipmapLevels());
            for (unsigned level = 0; level < levels; ++level)
            {
                const int width = std::max(1, image.s() >> level);
                const int height = std::max(1, image.t() >> level);
                const unsigned rowBytes = osg::Image::computeRowWidthInBytes(
                    width, image.getPixelFormat(), image.getDataType(), image.getPacking());
                unsigned char* row = image.isMipmap() ? const_cast<unsigned char*>(image.getMipmapData(level)) : image.data();
                for (int t = 0; t < height; ++t, row += rowBytes)
                    for (int x = 0; x < width; ++x)
                        std::swap(row[x * channels], row[x * channels + 2]);
            }
            image.setPixelFormat(alpha ? GL_RGBA : GL_RGB);
            image.setInternalTextureFormat(alpha ? GL_RGBA : GL_RGB);
        }

        void logBacktrace()
        {
            struct Frames
            {
                void* pc[24];
                int count = 0;
            } frames;
            _Unwind_Backtrace(
                [](_Unwind_Context* context, void* data) {
                    auto* f = static_cast<Frames*>(data);
                    if (f->count >= 24)
                        return _URC_END_OF_STACK;
                    if (void* pc = reinterpret_cast<void*>(_Unwind_GetIP(context)))
                        f->pc[f->count++] = pc;
                    return _URC_NO_REASON;
                },
                &frames);
            // the first two are this function and the debug callback
            for (int i = 2; i < frames.count; ++i)
            {
                Dl_info info{};
                std::string name = "?";
                if (dladdr(frames.pc[i], &info) && info.dli_sname)
                {
                    int status = 0;
                    char* demangled = abi::__cxa_demangle(info.dli_sname, nullptr, nullptr, &status);
                    name = status == 0 && demangled ? demangled : info.dli_sname;
                    std::free(demangled);
                }
                Log(Debug::Warning) << "    #" << (i - 2) << " " << name;
            }
        }

        // With OSG_GL_ERROR_CHECKING set (Launch options), the driver says which call failed and why: OSG only sees that
        // a GL error happened somewhere in a frame or a state change. Errors only, the first 100.
        void GLAD_API_PTR onDebugMessage(GLenum, GLenum type, GLuint id, GLenum, GLsizei, const GLchar* message,
            const void*)
        {
            static std::atomic<int> count{ 0 };
            // errors only: Adreno reports its own performance notes ("Packing allocations ...") as high severity
            if (type != GL_DEBUG_TYPE_ERROR)
                return;
            const int n = ++count;
            if (n > 100)
                return;
            Log(Debug::Warning) << "GL debug (" << id << "): " << message
                                << (n == 100 ? " (no more GL debug messages are logged)" : "");
            // the output is synchronous: the stack is the call that failed
            if (n <= 5)
                logBacktrace();
        }

        void enableDebugOutput()
        {
            // the values OSG takes for checking after every state change (State.cpp); OFF and ONCE_PER_FRAME leave it off
            const char* checking = std::getenv("OSG_GL_ERROR_CHECKING");
            const std::string_view value = checking ? checking : "";
            if ((value != "ON" && value != "on" && value != "ONCE_PER_ATTRIBUTE") || glad_glDebugMessageCallback == nullptr)
                return;
            glEnable(GL_DEBUG_OUTPUT);
            // reported from the call that failed, in order with the engine's own log
            glEnable(GL_DEBUG_OUTPUT_SYNCHRONOUS);
            glDebugMessageCallback(onDebugMessage, nullptr);
            Log(Debug::Info) << "GL debug output on (OSG_GL_ERROR_CHECKING=" << value << ")";
        }

        // A DXT image as the GPU can sample it: null when it can as it is (or nothing can be done, and the engine decides)
        osg::ref_ptr<osg::Image> prepareDxtForGpu(osg::Image& image)
        {
            const osg::GLExtensions* gl = osg::GLExtensions::Get(0, false);
            if (gl == nullptr || gl->isTextureCompressionS3TCSupported)
                return nullptr;

            // ASTC textures need a GPU that can sample ASTC; ETC2 is part of OpenGL ES 3
            if (textureConversionUsesEtc2() || gl->isTextureCompressionASTCSupported)
            {
                if (osg::ref_ptr<osg::Image> converted = transcodeDxt(image, image.getFileName()))
                    return converted;
            }
            else
            {
                static std::once_flag logged;
                std::call_once(logged,
                    [] { Log(Debug::Info) << "Texture conversion is off: this GPU does not support ASTC textures"; });
            }

            // decompressed on the CPU, block-wise with all mip levels
            static const bool decompress = std::getenv("OPENMW_DECOMPRESS_TEXTURES") != nullptr;
            if (decompress)
            {
                if (osg::ref_ptr<osg::Image> decoded = decodeS3tc(image))
                    return decoded;
            }
            return nullptr;
        }

        class Hooks : public osg::RuntimeHooks
        {
        public:
            void contextMadeCurrent(osg::GraphicsContext&) override
            {
                std::call_once(mFirstContext, [] {
                    // OSG is built against glad's OpenGL ES header; the Zink context loads them from OSMesa itself
                    GLADloadfunc load = reinterpret_cast<GLADloadfunc>(SDL_GL_GetProcAddress);
#ifndef __ANDROID__
                    // iOS with ANGLE: SDL has no OpenGL context, the functions are ANGLE's
                    if (usesAngle())
                        load = reinterpret_cast<GLADloadfunc>(angleProcAddress);
#endif
                    if (!usesZink() && !gladLoadGLES2(load))
                        Log(Debug::Error) << "Failed to initialize GLAD";
                    enableDebugOutput();
                    // the settings are loaded by the time there is a window
                    applyAllocatorSetting();
                });
            }

            void extensionsRead(osg::GLExtensions& extensions) override
            {
                Log(Debug::Info) << "Storage buffer blocks per stage: vertex " << extensions.maxVertexShaderStorageBlocks
                                 << ", fragment " << extensions.maxFragmentShaderStorageBlocks << ", compute "
                                 << extensions.maxComputeShaderStorageBlocks << "; compute work group invocations "
                                 << extensions.maxComputeWorkGroupInvocations;
                const bool clipDistances = extensions.isOpenGLES
                    && osg::isGLExtensionSupported(extensions.contextID, "GL_EXT_clip_cull_distance");
                setClipDistancesAvailable(clipDistances);
                if (extensions.isOpenGLES && !clipDistances)
                    Log(Debug::Warning) << "No GL_EXT_clip_cull_distance: water reflections are not clipped";

                // Stock OpenMW reads desktop versions: compute shader ripples need OpenGL and GLSL 4.4, clustered lighting
                // GLSL 4.3, a post processing technique the GLSL version it asks for. OpenGL ES 3.2 has what they use and
                // the shaders are converted for it, so the context reports the desktop version that matches: 4.6 when
                // the compute shaders convert and the driver has the work group size (16x16) and storage blocks they
                // need (3 per stage for the lighting, 5 for its culling), else 3.3. OSG has read its features already.
                // OpenGL ES 3.0 (iOS) has uniform blocks but no compute shaders or storage blocks: 3.3.
                if (extensions.isOpenGLES && extensions.glVersion >= 3.0f)
                {
                    const float glesVersion = extensions.glVersion;
                    const bool compute = computeShadersAvailable() && extensions.maxComputeWorkGroupInvocations >= 256
                        && extensions.maxVertexShaderStorageBlocks >= 3 && extensions.maxFragmentShaderStorageBlocks >= 3
                        && extensions.maxComputeShaderStorageBlocks >= 5;
                    extensions.glslLanguageVersion = compute ? 4.6f : 3.3f;
                    if (compute)
                        extensions.glVersion = 4.6f;
                    Log(Debug::Info) << "OpenGL ES " << glesVersion << " reported to the engine as GLSL "
                                     << extensions.glslLanguageVersion << (compute ? "" : " (no compute shaders)");
                }
            }

            void swapBuffers(osg::GraphicsContext& gc) override
            {
                // the frame is drawn: no texture of this context is being uploaded
                textureStreamingFrame();
                Alpha3::swapBuffers(gc);
            }

            void clipPlaneApplied(osg::State&, unsigned int index, const osg::Vec4d& eyePlane) override
            {
                // OpenGL ES reads the plane from OSG's uniform; desktop OpenGL still has glClipPlane for gl_ClipVertex
                if (usesZink())
                    zinkClipPlane(index, eyePlane.ptr());
            }

            void programLinked(const osg::Program& program, GLuint handle, osg::State& state) override
            {
                // The translation for OpenGL ES drops the initializers of uniforms: the program gets their values now
                if (!state.get<osg::GLExtensions>()->isOpenGLES)
                    return;
                std::vector<UniformInitializer> initializers;
                for (unsigned int i = 0; i < program.getNumShaders(); ++i)
                {
                    std::vector<UniformInitializer> more = uniformInitializers(program.getShader(i)->getShaderSource());
                    initializers.insert(initializers.end(), more.begin(), more.end());
                }
                if (initializers.empty())
                    return;

                GLint previous = 0;
                glGetIntegerv(GL_CURRENT_PROGRAM, &previous);
                glUseProgram(handle);
                for (const UniformInitializer& uniform : initializers)
                {
                    const GLint location = glGetUniformLocation(handle, uniform.name.c_str());
                    if (location < 0)
                        continue;
                    const std::vector<float>& v = uniform.values;
                    const GLsizei count = static_cast<GLsizei>(v.size());
                    if (uniform.type == "float" || uniform.type[0] == 'v')
                    {
                        if (count == 1) glUniform1fv(location, 1, v.data());
                        else if (count == 2) glUniform2fv(location, 1, v.data());
                        else if (count == 3) glUniform3fv(location, 1, v.data());
                        else glUniform4fv(location, 1, v.data());
                    }
                    else if (uniform.type[0] == 'u')
                    {
                        const GLuint u[4] = { GLuint(v[0]), GLuint(count > 1 ? v[1] : 0), GLuint(count > 2 ? v[2] : 0),
                            GLuint(count > 3 ? v[3] : 0) };
                        if (count == 1) glUniform1uiv(location, 1, u);
                        else if (count == 2) glUniform2uiv(location, 1, u);
                        else if (count == 3) glUniform3uiv(location, 1, u);
                        else glUniform4uiv(location, 1, u);
                    }
                    else
                    {
                        // bool and int and their vectors
                        const GLint n[4] = { GLint(v[0]), GLint(count > 1 ? v[1] : 0), GLint(count > 2 ? v[2] : 0),
                            GLint(count > 3 ? v[3] : 0) };
                        if (count == 1) glUniform1iv(location, 1, n);
                        else if (count == 2) glUniform2iv(location, 1, n);
                        else if (count == 3) glUniform3iv(location, 1, n);
                        else glUniform4iv(location, 1, n);
                    }
                }
                glUseProgram(static_cast<GLuint>(previous));
            }

            void textureImageLoading(const osg::Image& image, osg::State&) override
            {
                texturePreloadTextureLoading(image);
                textureStreamingTextureLoading(image);
            }

            void contextClosing(osg::GraphicsContext&) override
            {
                textureStreamingStop();
                texturePreloadStop();
            }

            // the engine's main thread, between its update and the frame's rendering
            void renderingTraversals() override { omw_frame_update(); }

            bool nodeOccluded(osg::CullStack& cullStack, const osg::Node& node) override
            {
                return occlusionNodeOccluded(cullStack, node);
            }

            bool drawableOccluded(osg::CullStack& cullStack, const osg::Drawable&, const osg::BoundingBox& box) override
            {
                return occlusionDrawableOccluded(cullStack, box);
            }

            void drawableAdded(osg::NodeVisitor& cullVisitor, osg::Drawable& drawable, const osg::Matrix& modelView) override
            {
                occlusionDrawableAdded(cullVisitor, drawable, modelView);
            }

            bool translateShaderSource(const osg::Shader& shader, std::string& source) override
            {
                return Alpha3::translateShaderSource(shader, source);
            }

            osg::ref_ptr<osg::Shader> mergeShaderStage(const std::vector<const osg::Shader*>& shaders) override
            {
                return Alpha3::mergeShaderStage(shaders);
            }

            std::string programBinaryKey(const osg::Program& program, const std::string& defines, osg::State&) override
            {
                return Alpha3::programBinaryKey(program, defines);
            }

            osg::ref_ptr<osg::Program::ProgramBinary> loadProgramBinary(const std::string& key, osg::State&) override
            {
                return Alpha3::loadProgramBinary(key);
            }

            void storeProgramBinary(
                const std::string& key, const osg::Program::ProgramBinary& binary, osg::State&) override
            {
                Alpha3::storeProgramBinary(key, binary);
            }

            osg::ref_ptr<osg::Image> imageRead(osg::Image& image, const std::string& extension) override
            {
                // a texture the distant meshes draw from on the CPU
                if (imageHooksBypassed())
                    return nullptr;
                // the first image the engine reads starts the preload of the last session's textures
                texturePreloadImageRead();
                if (extension != "dds" || image.data() == nullptr)
                    return nullptr;

                const GLenum format = image.getPixelFormat();
                if ((format == GL_BGRA || format == GL_BGR) && !image.isCompressed()
                    && image.getDataType() == GL_UNSIGNED_BYTE && usesGles())
                {
                    swapBgrToRgb(image);
                    return nullptr;
                }

                if (!isDxt(format))
                    return nullptr;
                // the GPU is known once there is a context; images read before that are left to the engine
                if (osg::GLExtensions::Get(0, false) == nullptr)
                    return nullptr;

                // the small mip levels now, the rest once the texture is drawn
                if (osg::ref_ptr<osg::Image> streamed = textureStreamingRead(image, prepareDxtForGpu))
                    return streamed;
                return prepareDxtForGpu(image);
            }

        private:
            std::once_flag mFirstContext;
        };

        struct Install
        {
            Install() { osg::RuntimeHooks::set(new Hooks); }
        };

        const Install sInstall;
    }
}
