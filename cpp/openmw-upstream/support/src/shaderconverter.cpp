#include "shaderconverter.hpp"

#include <glslang/Public/ResourceLimits.h>
#include <glslang/Public/ShaderLang.h>
#include <glslang/SPIRV/GlslangToSpv.h>
#include <spirv_cross/spirv_cross_c.h>

#include <components/debug/debuglog.hpp>
#include <components/misc/strings/algorithm.hpp>

#include <cctype>
#include <cstdlib>
#include <mutex>
#include <regex>
#include <vector>

namespace Alpha3
{
    namespace
    {
        std::once_flag sGlslangInit;

        int sourceVersion(const std::string& source)
        {
            const std::size_t at = source.find("#version");
            if (at == std::string::npos)
                return 120;
            std::size_t i = at + 8;
            while (i < source.size() && (source[i] == ' ' || source[i] == '\t'))
                ++i;
            int version = 0;
            while (i < source.size() && source[i] >= '0' && source[i] <= '9')
                version = version * 10 + (source[i++] - '0');
            return version != 0 ? version : 120;
        }

        // texture2D(...) and the other GLSL 1.20 texture functions as the overloads of texture() that replaced them
        std::string modernTextureCalls(const std::string& source)
        {
            static const std::regex call(R"(\b(texture2DProjLod|texture2DProj|texture2DArrayLod|texture2DArray|texture2DLod|)"
                                         R"(texture2D|texture3DLod|texture3D|textureCubeLod|textureCube|texture1DLod|texture1D)\s*\()");
            std::string out;
            auto last = source.cbegin();
            for (std::sregex_iterator it(source.begin(), source.end(), call), end; it != end; ++it)
            {
                const std::smatch& match = *it;
                out.append(last, match[0].first);
                const std::string name = match[1].str();
                const bool lod = name.size() > 3 && name.compare(name.size() - 3, 3, "Lod") == 0;
                const bool proj = name.find("Proj") != std::string::npos;
                out += proj ? (lod ? "textureProjLod(" : "textureProj(") : (lod ? "textureLod(" : "texture(");
                last = match[0].second;
            }
            out.append(last, source.cend());
            return out;
        }

        bool toSpirv(const std::string& source, GlslStage stage, int version, std::vector<unsigned int>& spirv)
        {
            const EShLanguage language = stage == GlslStage::Vertex ? EShLangVertex
            : stage == GlslStage::Compute                                  ? EShLangCompute
                                                                           : EShLangFragment;
            const char* text = source.c_str();

            glslang::TShader shader(language);
            shader.setStrings(&text, 1);
            shader.setEnvInput(glslang::EShSourceGlsl, language, glslang::EShClientOpenGL, version);
            shader.setEnvClient(glslang::EShClientOpenGL, glslang::EShTargetOpenGL_450);
            shader.setEnvTarget(glslang::EShTargetSpv, glslang::EShTargetSpv_1_0);
            shader.setAutoMapLocations(true);
            shader.setAutoMapBindings(true);

            if (!shader.parse(GetDefaultResources(), version, version >= 330, EShMsgDefault))
            {
                Log(Debug::Error) << "glslang parse error: " << shader.getInfoLog();
                return false;
            }

            glslang::TProgram program;
            program.addShader(&shader);
            if (!program.link(EShMsgDefault))
            {
                Log(Debug::Error) << "glslang link error: " << program.getInfoLog();
                return false;
            }

            glslang::SpvOptions options;
            options.disableOptimizer = false;
            glslang::GlslangToSpv(*program.getIntermediate(language), spirv, &options);
            return !spirv.empty();
        }

        bool toEssl(const std::vector<unsigned int>& spirv, unsigned int version, std::string& essl)
        {
            spvc_context context = nullptr;
            if (spvc_context_create(&context) != SPVC_SUCCESS)
                return false;

            bool ok = false;
            spvc_parsed_ir ir = nullptr;
            spvc_compiler compiler = nullptr;
            spvc_compiler_options options = nullptr;
            const char* result = nullptr;
            if (spvc_context_parse_spirv(context, spirv.data(), spirv.size(), &ir) == SPVC_SUCCESS
                && spvc_context_create_compiler(context, SPVC_BACKEND_GLSL, ir, SPVC_CAPTURE_MODE_TAKE_OWNERSHIP, &compiler)
                    == SPVC_SUCCESS
                && spvc_compiler_create_compiler_options(compiler, &options) == SPVC_SUCCESS)
            {
                spvc_compiler_options_set_uint(options, SPVC_COMPILER_OPTION_GLSL_VERSION, version);
                spvc_compiler_options_set_bool(options, SPVC_COMPILER_OPTION_GLSL_ES, SPVC_TRUE);
                spvc_compiler_install_compiler_options(compiler, options);
                if (spvc_compiler_compile(compiler, &result) == SPVC_SUCCESS && result != nullptr)
                {
                    essl = result;
                    ok = true;
                }
            }
            if (!ok)
                Log(Debug::Error) << "SPIRV-Cross error: " << spvc_context_get_last_error_string(context);
            spvc_context_destroy(context);
            return ok;
        }
    }

    std::string pruneUnusedStorageBlocks(const std::string& source)
    {
        if (source.find("buffer ") == std::string::npos)
            return source;

        static const std::regex block(
            R"(layout\s*\([^)]*\)\s*(?:(?:restrict|readonly|writeonly|coherent|volatile)\s+)*buffer\s+\w+\s*\{([^}]*)\}\s*;)");
        static const std::regex member(R"((\w+)\s*(?:\[[^\]]*\])*\s*$)");

        auto countWord = [&](const std::string& text, const std::string& word) {
            std::size_t count = 0;
            for (std::size_t at = text.find(word); at != std::string::npos; at = text.find(word, at + word.size()))
            {
                const bool startOk = at == 0 || !(std::isalnum(static_cast<unsigned char>(text[at - 1])) || text[at - 1] == '_');
                const std::size_t end = at + word.size();
                const bool endOk = end >= text.size() || !(std::isalnum(static_cast<unsigned char>(text[end])) || text[end] == '_');
                if (startOk && endOk)
                    ++count;
            }
            return count;
        };

        std::vector<std::pair<std::size_t, std::size_t>> unused;
        for (auto it = std::sregex_iterator(source.begin(), source.end(), block); it != std::sregex_iterator(); ++it)
        {
            const std::string body = (*it)[1].str();
            bool used = false;
            std::size_t start = 0;
            while (start < body.size() && !used)
            {
                std::size_t semicolon = body.find(';', start);
                if (semicolon == std::string::npos)
                    break;
                const std::string statement = body.substr(start, semicolon - start);
                start = semicolon + 1;
                std::smatch name;
                if (std::regex_search(statement, name, member) && countWord(source, name[1].str()) > countWord(body, name[1].str()))
                    used = true;
            }
            if (!used)
                unused.emplace_back(it->position(0), it->length(0));
        }

        std::string out = source;
        for (auto it = unused.rbegin(); it != unused.rend(); ++it)
            out.erase(it->first, it->second);
        return out;
    }

    const char* converterStamp()
    {
        return __DATE__ " " __TIME__;
    }

    bool spirvConversionEnabled()
    {
        static const bool enabled = std::getenv("OPENMW_DISABLE_SPIRV") == nullptr;
        return enabled;
    }

    std::string convertGlslToGles(const std::string& source, GlslStage stage, unsigned int glesVersion)
    {
        std::call_once(sGlslangInit, [] {
            glslang::InitializeProcess();
            Log(Debug::Info) << "Shaders go through glslang and SPIRV-Cross (OPENMW_DISABLE_SPIRV turns it off)";
        });

        // glslang takes the desktop dialect, which allows what a GLES compiler rejects; SPIRV-Cross writes the GLES form
        // Library snippets without an entry point are only ever linked into a shader, they cannot be converted alone
        if (source.find("void main") == std::string::npos)
            return source;

        std::string glsl = source;
        // The built-in clip distance needs no extension in the desktop dialect
        for (std::size_t at; (at = glsl.find("#extension GL_EXT_clip_cull_distance")) != std::string::npos;)
        {
            const std::size_t end = glsl.find('\n', at);
            glsl.erase(at, end == std::string::npos ? std::string::npos : end - at);
        }
        for (const char* es : { "#version 320 es", "#version 310 es", "#version 300 es" })
            Misc::StringUtils::replaceAll(glsl, es, "#version 460");
        Misc::StringUtils::replaceAll(glsl, "defined(GL_ES)", "1");

        const int version = sourceVersion(glsl);
        // GLSL 1.20's texture functions in a newer shader: post-processing techniques are built as 3.30 when uniform
        // buffers are on (components/fx/technique.cpp), and many still call texture2D() (the MOMW pack's ssao and
        // ssao_hq). glslang parses 3.30 forward compatible, without them; drivers that allow them anyway are why
        // these shaders work elsewhere. Without this the pass came out unconverted, and on Vulkan, where texture2D is
        // a type, did not compile at all.
        if (version >= 130)
            glsl = modernTextureCalls(glsl);

        std::vector<unsigned int> spirv;
        if (!toSpirv(glsl, stage, version, spirv))
            return source;

        std::string essl;
        if (!toEssl(spirv, glesVersion >= 300 ? glesVersion : 300, essl))
            return source;

        // The clip distance and derivative extensions are enabled, not required, and the built-in is not redeclared;
        // every sampler and image gets a precision for the stages that have no default
        Misc::StringUtils::replaceAll(essl, "#extension GL_EXT_clip_cull_distance : require", "");
        Misc::StringUtils::replaceAll(essl, "out float gl_ClipDistance", "//out float gl_ClipDistance");
        // Vertex attributes are bound by name through glBindAttribLocation, the locations glslang gave them are arbitrary
        if (stage == GlslStage::Vertex)
            essl = std::regex_replace(essl, std::regex("layout\\(location = [0-9]+\\) in "), "in ");
        const std::size_t afterVersion = essl.find('\n');
        if (afterVersion != std::string::npos)
            essl.insert(afterVersion + 1,
                std::string("#extension GL_OES_standard_derivatives : enable\n#extension GL_EXT_clip_cull_distance : enable\n"
                "precision highp float;\nprecision highp int;\nprecision highp sampler2D;\nprecision highp sampler3D;\n"
                "precision highp sampler2DShadow;\nprecision highp sampler2DArray;\n")
                // image types came with ESSL 3.10
                + (glesVersion >= 310 ? "precision highp image2D;\n" : ""));
        return essl;
    }
}
