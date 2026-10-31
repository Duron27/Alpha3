// The shaders as OSG compiles them: translated for the driver (glesdialect, shaderconverter), and on OpenGL ES the
// library shaders of a stage merged into one shader object. Moved from OpenMW's ShaderManager, which gave the sources
// already translated; OSG now asks for them through RuntimeHooks when it compiles.
#include "support.hpp"

#include "glesdialect.hpp"

#include <algorithm>
#include <atomic>
#include <filesystem>
#include <functional>
#include <fstream>
#include <map>
#include <mutex>
#include <string_view>

#include <osg/Shader>
#include <osg/observer_ptr>

namespace Alpha3
{
    namespace
    {
        // Desktop GLSL with a version line: OpenMW's shaders and post-processing passes. Not the ones with OSG's
        // $OSG_GLSL_VERSION (osgText) or written for OpenGL ES already.
        bool isDesktopGlsl(const std::string& source)
        {
            const std::size_t version = source.find("#version");
            if (version == std::string::npos)
                return false;
            const std::size_t end = source.find('\n', version);
            return std::string_view(source).substr(version, end - version).find(" es") == std::string_view::npos;
        }

        // "uniform vec4 name;" -> the same declaration that is skipped when an earlier one is active. Separate shader
        // objects may declare the same uniform, one shader may not; the preprocessor decides what is active, whatever the
        // #if nesting. Anything but a plain declaration is left alone.
        std::string declareUniformOnce(std::string_view line)
        {
            size_t first = line.find_first_not_of(" \t");
            if (first == std::string_view::npos || !line.substr(first).starts_with("uniform "))
                return std::string(line);
            std::string_view decl = line.substr(first);
            size_t last = decl.find_last_not_of(" \t\r");
            if (decl[last] != ';' || decl.find_first_of("=,(") != std::string_view::npos)
                return std::string(line);
            decl = decl.substr(0, last); // without ';'
            if (!decl.empty() && decl.back() == ']')
            {
                size_t open = decl.rfind('[');
                if (open == std::string_view::npos)
                    return std::string(line);
                decl = decl.substr(0, open);
            }
            size_t end = decl.find_last_not_of(" \t");
            size_t begin = decl.find_last_of(" \t", end);
            if (end == std::string_view::npos || begin == std::string_view::npos)
                return std::string(line);
            std::string guard = "OMW_UNIFORM_" + std::string(decl.substr(begin + 1, end - begin));
            return "#ifndef " + guard + "\n#define " + guard + "\n" + std::string(line) + "\n#endif";
        }

        // Applies declareUniformOnce to each line and, for a library, drops its #version line
        std::string foldForMerge(const std::string& source, bool library)
        {
            std::string out;
            out.reserve(source.size() + source.size() / 8);
            size_t pos = 0;
            while (pos < source.size())
            {
                size_t end = source.find('\n', pos);
                size_t next = end == std::string::npos ? source.size() : end + 1;
                std::string_view line(source.data() + pos, (end == std::string::npos ? source.size() : end) - pos);
                size_t first = line.find_first_not_of(" \t");
                if (library && first != std::string_view::npos && line.substr(first).starts_with("#version"))
                    out.push_back('\n');
                else
                {
                    out += declareUniformOnce(line);
                    if (end != std::string::npos)
                        out.push_back('\n');
                }
                pos = next;
            }
            return out;
        }

        // Debug aid: with this folder present, every shader is written to it as the driver receives it
        void dump(const osg::Shader& shader, const std::string& source)
        {
            static const std::filesystem::path dumpDir("/storage/emulated/0/Alpha3/dump_shaders");
            static const bool enabled = [] {
                std::error_code ec;
                return std::filesystem::is_directory(dumpDir, ec);
            }();
            if (!enabled)
                return;
            static std::atomic<int> counter{ 0 };
            std::string name = shader.getName();
            std::replace(name.begin(), name.end(), '/', '_');
            std::ofstream(dumpDir / (std::to_string(counter++) + "_" + name), std::ios::binary) << source;
        }
    }

    bool translateShaderSource(const osg::Shader& shader, std::string& source)
    {
        GlslStage stage;
        switch (shader.getType())
        {
            case osg::Shader::VERTEX:
                stage = GlslStage::Vertex;
                break;
            case osg::Shader::FRAGMENT:
                stage = GlslStage::Fragment;
                break;
            case osg::Shader::COMPUTE:
                stage = GlslStage::Compute;
                break;
            default:
                return false;
        }
        if (!isDesktopGlsl(source))
            return false;
        injectExperimentalShaders(shader, source);
        // OpenMW draws its shadow casters, and nothing else, with GL_DEPTH_CLAMP
        const bool depthClamp = shader.getName().find("shadowcasting.vert") != std::string::npos;
        source = translateGlsl(source, stage, glslTarget(), depthClamp);
        dump(shader, source);
        return true;
    }

    osg::ref_ptr<osg::Shader> mergeShaderStage(const std::vector<const osg::Shader*>& shaders)
    {
        // The Adreno compiler refuses a shader that calls a function defined in another shader object ("Call to
        // undefined function") and one without main(), which is how OpenMW's library shaders are written. Desktop GL
        // (Zink) links them as they are.
        if (glslTarget() != GlslTarget::Gles)
            return nullptr;

        // the shader with main() first: the program has the engine's shaders before the libraries they link
        const osg::Shader* main = nullptr;
        for (const osg::Shader* shader : shaders)
        {
            if (shader->getShaderSource().find("void main") != std::string::npos)
            {
                main = shader;
                break;
            }
        }
        if (main == nullptr || !isDesktopGlsl(main->getShaderSource()))
            return nullptr;

        std::string source = foldForMerge(main->getShaderSource(), false);
        for (const osg::Shader* library : shaders)
        {
            if (library == main)
                continue;
            source += "\n// ---- " + library->getName() + "\n" + foldForMerge(library->getShaderSource(), true);
        }

        // Several programs link the same shaders: they share one merged shader, which is then compiled once. Held
        // weakly, by the programs that use it, and found by a hash of the source (checked on a hit)
        static std::mutex sMutex;
        static std::map<std::pair<std::size_t, std::size_t>, osg::observer_ptr<osg::Shader>> sMerged;
        const std::pair<std::size_t, std::size_t> key(std::hash<std::string>()(source), source.size());
        std::lock_guard lock(sMutex);
        osg::ref_ptr<osg::Shader> merged;
        auto found = sMerged.find(key);
        if (found != sMerged.end() && found->second.lock(merged) && merged->getShaderSource() == source)
            return merged;
        merged = new osg::Shader(main->getType(), source);
        merged->setName(main->getName() + " (merged)");
        if (sMerged.size() > 4096)
        {
            for (auto it = sMerged.begin(); it != sMerged.end();)
                it = it->second.valid() ? std::next(it) : sMerged.erase(it);
        }
        sMerged[key] = merged;
        return merged;
    }
}
