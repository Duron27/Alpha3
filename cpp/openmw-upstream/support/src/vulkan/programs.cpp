#include "programs.hpp"

#include "context.hpp"

#include "../support.hpp"

#include <algorithm>
#include <dlfcn.h>
#include <iterator>
#include <sys/resource.h>
#include <sys/stat.h>
#include <unistd.h>
#include <chrono>
#include <iomanip>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <mutex>
#include <regex>
#include <set>

#include <glslang/Public/ResourceLimits.h>
#include <glslang/Public/ShaderLang.h>
#include <glslang/SPIRV/GlslangToSpv.h>

#include <osg/Program>
#include <osg/State>

#include <components/debug/debuglog.hpp>
#include <components/sceneutil/depth.hpp>

namespace Alpha3::Vk
{
    namespace
    {
        std::once_flag sGlslang;

        // The number of locations a varying of a type takes
        uint32_t locationsOf(const std::string& type)
        {
            if (type.rfind("mat", 0) == 0 || type.rfind("dmat", 0) == 0)
            {
                const std::size_t at = type.find("mat") + 3;
                return at < type.size() && type[at] >= '2' && type[at] <= '4' ? type[at] - '0' : 4;
            }
            return 1;
        }

        uint32_t componentsOf(const std::string& type)
        {
            if (type == "float" || type == "int" || type == "uint" || type == "bool")
                return 1;
            const char last = type.empty() ? '4' : type.back();
            return last >= '2' && last <= '4' ? last - '0' : 4;
        }

        bool integerType(const std::string& type)
        {
            return type == "int" || type == "uint" || type.rfind("ivec", 0) == 0 || type.rfind("uvec", 0) == 0;
        }

        VkImageViewType samplerViewType(const std::string& type)
        {
            if (type.find("Cube") != std::string::npos)
                return VK_IMAGE_VIEW_TYPE_CUBE;
            if (type.find("3D") != std::string::npos)
                return VK_IMAGE_VIEW_TYPE_3D;
            if (type.find("Array") != std::string::npos)
                return VK_IMAGE_VIEW_TYPE_2D_ARRAY;
            return VK_IMAGE_VIEW_TYPE_2D;
        }

        // Puts text after the #version line (at the start when there is none)
        void insertAfterVersion(std::string& source, const std::string& text)
        {
            std::size_t at = source.find("#version");
            if (at != std::string::npos)
            {
                at = source.find('\n', at);
                at = at == std::string::npos ? source.size() : at + 1;
            }
            else
                at = 0;
            source.insert(at, text);
        }

        // Gives every sampler a binding of the set: the same name the same binding in both stages
        std::string bindSamplers(const std::string& source, VkShaderStageFlags stage, std::vector<SamplerSlot>& slots)
        {
            static const std::regex declaration(
                R"((^|\n)[ \t]*(?:layout\s*\([^)]*\)\s*)?uniform\s+((?:(?:lowp|mediump|highp)\s+)?)((?:i|u)?sampler\w+)\s+(\w+)\s*(?:\[\s*(\d+)\s*\])?\s*;)");
            std::string out;
            std::size_t last = 0;
            for (auto it = std::sregex_iterator(source.begin(), source.end(), declaration); it != std::sregex_iterator();
                 ++it)
            {
                const std::smatch& match = *it;
                const std::string type = match[3].str();
                const std::string name = match[4].str();
                const uint32_t count = match[5].matched ? static_cast<uint32_t>(std::stoul(match[5].str())) : 1;
                auto found = std::find_if(
                    slots.begin(), slots.end(), [&](const SamplerSlot& slot) { return slot.mName == name; });
                if (found == slots.end())
                {
                    SamplerSlot slot;
                    slot.mName = name;
                    slot.mBinding = 4; // after the blocks: the stages' (0, 1), the matrices (2), the lights (3)
                    for (const SamplerSlot& other : slots)
                        slot.mBinding = std::max(slot.mBinding, other.mBinding + other.mCount);
                    slot.mCount = count;
                    slot.mViewType = samplerViewType(type);
                    slot.mShadow = type.find("Shadow") != std::string::npos;
                    slots.push_back(slot);
                    found = slots.end() - 1;
                }
                found->mStages |= stage;
                out.append(source, last, match.position(0) - last);
                out += match[1].str() + "layout(set = 0, binding = " + std::to_string(found->mBinding) + ") uniform "
                    + match[2].str() + type + " " + name + (match[5].matched ? "[" + match[5].str() + "]" : "") + ";";
                last = match.position(0) + match.length(0);
            }
            out.append(source, last, std::string::npos);
            return out;
        }

        // Gives the stage's inputs and outputs locations: varyings by name (the vertex stage's outputs and the fragment
        // stage's inputs), vertex attributes and fragment outputs in order
        std::string locateVaryings(const std::string& source, bool vertex, std::map<std::string, uint32_t>& varyings,
            std::vector<VertexInput>* attributes)
        {
            static const std::regex declaration(
                R"((^|\n)([ \t]*)((?:(?:flat|smooth|noperspective|centroid|invariant|highp|mediump|lowp)\s+)*)(in|out)\s+((?:(?:highp|mediump|lowp)\s+)?)(\w+)\s+(\w+)\s*(?:\[\s*(\d+)\s*\])?\s*;)");
            std::string out;
            std::size_t last = 0;
            uint32_t nextAttribute = 0;
            uint32_t nextOutput = 0;
            for (auto it = std::sregex_iterator(source.begin(), source.end(), declaration); it != std::sregex_iterator();
                 ++it)
            {
                const std::smatch& match = *it;
                const bool in = match[4].str() == "in";
                const std::string type = match[6].str();
                const std::string name = match[7].str();
                const uint32_t array = match[8].matched ? static_cast<uint32_t>(std::stoul(match[8].str())) : 1;
                const uint32_t size = locationsOf(type) * array;
                uint32_t location = 0;
                if (vertex && in)
                {
                    location = nextAttribute;
                    nextAttribute += size;
                    if (attributes != nullptr)
                    {
                        attributes->push_back({ name, location, componentsOf(type), integerType(type) });
                        attributes->back().classify();
                    }
                }
                else if (!vertex && !in)
                {
                    location = nextOutput;
                    nextOutput += size;
                }
                else
                {
                    auto found = varyings.find(name);
                    if (found == varyings.end())
                    {
                        // one the vertex stage does not write: past the others (4 locations each at most)
                        location = 0;
                        for (const auto& [other, at] : varyings)
                            location = std::max(location, at + 4);
                        varyings[name] = location;
                    }
                    else
                        location = found->second;
                }
                out.append(source, last, match.position(0) - last);
                out += match[1].str() + match[2].str() + "layout(location = " + std::to_string(location) + ") "
                    + match[3].str() + match[4].str() + " " + match[5].str() + type + " " + name
                    + (match[8].matched ? "[" + match[8].str() + "]" : "") + ";";
                last = match.position(0) + match.length(0);
            }
            out.append(source, last, std::string::npos);
            return out;
        }

        // The stage after the preprocessor (#if, #ifdef and macros resolved; #version, #extension and #pragma kept): the
        // passes below find declarations by their text, and those in a branch left out must not take locations or
        // bindings. Counted from the raw text, the objects shader's varyings in all its branches (point light shadows'
        // under A3_PS_ON among them) went past what the Adreno 615's driver takes, and its draws came out of the wrong
        // place. The source is kept as it is if the preprocessor fails (the compile then says why).
        void preprocess(std::string& source, EShLanguage language)
        {
            const char* text = source.c_str();
            glslang::TShader shader(language);
            shader.setStrings(&text, 1);
            shader.setEnvInput(glslang::EShSourceGlsl, language, glslang::EShClientVulkan, 100);
            shader.setEnvClient(glslang::EShClientVulkan, glslang::EShTargetVulkan_1_0);
            shader.setEnvTarget(glslang::EShTargetSpv, glslang::EShTargetSpv_1_0);
            shader.setEnvInputVulkanRulesRelaxed();
            glslang::TShader::ForbidIncluder includer;
            std::string out;
            const EShMessages messages = static_cast<EShMessages>(EShMsgSpvRules | EShMsgVulkanRules);
            if (shader.preprocess(GetDefaultResources(), 450, ENoProfile, false, false, messages, &out, includer)
                && out.find("void main") != std::string::npos)
                source = std::move(out);
        }

        // SPIRV-Cross (the GLES dialect's sources) writes a short-circuit `a && b` as
        //     bool _76; if (a) { _76 = b; } else { _76 = a; }
        // and the Adreno 615's Vulkan driver gets _76 wrong when a is a bool of a uniform block: shadowcasting.frag's
        // `alphaTestShadows && gl_FragData[0].a <= 0.5` was always false, and leaves cast solid shadows. The else gives
        // the constant it must be (false for &&, true for ||), which the driver gets right.
        std::string fixShortCircuits(const std::string& source)
        {
            static const std::regex conjunction(
                R"(if \((!?\w+)\)(\s*\{\s*)(_\w+)( = [^;]+;\s*\}\s*else\s*\{\s*)\3 = \1;)");
            static const std::regex disjunction(
                R"(if \((!?\w+)\)(\s*\{\s*)(_\w+) = \1;(\s*\}\s*else\s*\{\s*)\3 = )");
            if (source.find("else") == std::string::npos)
                return source;
            std::string out = std::regex_replace(source, conjunction, "if ($1)$2$3$4$3 = false;");
            return std::regex_replace(out, disjunction, "if ($1)$2$3 = true;$4$3 = ");
        }

        // The varyings of the vertex stage in declaration order, given consecutive locations with the room each takes
        void planVaryings(const std::string& vertex, std::map<std::string, uint32_t>& varyings)
        {
            static const std::regex declaration(
                R"((?:^|\n)[ \t]*(?:(?:flat|smooth|noperspective|centroid|invariant|highp|mediump|lowp)\s+)*out\s+(?:(?:highp|mediump|lowp)\s+)?(\w+)\s+(\w+)\s*(?:\[\s*(\d+)\s*\])?\s*;)");
            uint32_t next = 0;
            for (auto it = std::sregex_iterator(vertex.begin(), vertex.end(), declaration); it != std::sregex_iterator();
                 ++it)
            {
                const std::string name = (*it)[2].str();
                if (varyings.count(name))
                    continue;
                const uint32_t array = (*it)[3].matched ? static_cast<uint32_t>(std::stoul((*it)[3].str())) : 1;
                varyings[name] = next;
                next += locationsOf((*it)[1].str()) * array;
            }
        }

        // The vertex stage's end: OpenGL's depth range to Vulkan's, and y turned over for the window
        // Puts text after the directives that have to come first (#version, #extension)
        void insertAfterDirectives(std::string& source, const std::string& text)
        {
            std::size_t at = 0;
            for (const char* directive : { "#version", "#extension" })
            {
                std::size_t found = source.rfind(std::string("\n") + directive);
                if (found == std::string::npos && source.compare(0, std::strlen(directive), directive) == 0)
                    found = 0;
                if (found != std::string::npos)
                {
                    const std::size_t end = source.find('\n', found + 1);
                    at = std::max(at, end == std::string::npos ? source.size() : end + 1);
                }
            }
            source.insert(at, text);
        }

        // OSG's matrices, which change from draw to draw, go into a block of their own (binding 2, the same in both
        // stages): a draw then sends 256 bytes, the rest of the uniforms once for all the draws of a state
        bool blockMatrices(std::string& source)
        {
            static const std::regex declaration(
                R"((^|\n)[ \t]*uniform\s+(?:(?:lowp|mediump|highp)\s+)?mat[34]\s+(osg_ModelViewMatrix|osg_ProjectionMatrix|osg_ModelViewProjectionMatrix|osg_NormalMatrix)\s*;)");
            // the clip planes too (also declared on one line with others, by the dialect's fragment shader fallback)
            static const std::regex planes(
                R"(\buniform\s+(?:(?:lowp|mediump|highp)\s+)?vec4\s+osg_ClipPlane[01]\s*;)");
            if (!std::regex_search(source, declaration) && !std::regex_search(source, planes))
                return false;
            source = std::regex_replace(source, declaration, "$1");
            source = std::regex_replace(source, planes, "");
            insertAfterDirectives(source,
                "layout(std140, set = 0, binding = 2) uniform a3DrawUniforms { mat4 osg_ModelViewMatrix; mat4 "
                "osg_ProjectionMatrix; mat4 osg_ModelViewProjectionMatrix; mat3 osg_NormalMatrix; vec4 osg_ClipPlane0; "
                "vec4 osg_ClipPlane1; };\n");
            return true;
        }

        // The light list's uniforms, which OpenMW sets for each object (a state set made each frame), go into a block
        // of their own (binding 3), declared the same in both stages: a draw then sends these, shared by all the draws
        // of the light list, instead of the stages' whole blocks again. The stages with the block (0: none).
        VkShaderStageFlags blockLights(std::string& vertex, std::string& fragment)
        {
            static const char* const names[] = { "LightBuffer", "PointLightCount", "a3PointShadowSlots" };
            static const std::regex declaration(
                R"((^|\n)[ \t]*uniform\s+(?:(?:lowp|mediump|highp)\s+)?(\w+)\s+(LightBuffer|PointLightCount|a3PointShadowSlots)\s*(\[\s*\d+\s*\])?\s*;)");
            std::map<std::string, std::string> members; // name: its declaration in the block
            std::string* stages[2] = { &vertex, &fragment };
            bool has[2] = { false, false };
            for (int stage = 0; stage < 2; ++stage)
                for (auto it = std::sregex_iterator(stages[stage]->begin(), stages[stage]->end(), declaration);
                     it != std::sregex_iterator(); ++it)
                {
                    has[stage] = true;
                    const std::string name = (*it)[3].str();
                    if (!members.count(name))
                    {
                        std::string array = (*it)[4].str();
                        array.erase(std::remove_if(array.begin(), array.end(), ::isspace), array.end());
                        members[name] = (*it)[2].str() + " " + name + array + ";";
                    }
                }
            if (members.empty())
                return 0;
            // a member both stages read must have one type: the block is one buffer
            std::string block = "layout(std140, set = 0, binding = 3) uniform a3LightUniforms {";
            for (const char* name : names)
                if (members.count(name))
                    block += " " + members[name];
            block += " };\n";
            VkShaderStageFlags flags = 0;
            for (int stage = 0; stage < 2; ++stage)
            {
                if (!has[stage])
                    continue;
                *stages[stage] = std::regex_replace(*stages[stage], declaration, "$1");
                insertAfterDirectives(*stages[stage], block);
                flags |= stage == 0 ? VK_SHADER_STAGE_VERTEX_BIT : VK_SHADER_STAGE_FRAGMENT_BIT;
            }
            return flags;
        }

        std::string finishVertex(const std::string& source)
        {
            static const std::regex mainFunction(R"(\bvoid\s+main\s*\(\s*(?:void)?\s*\))");
            std::string out = std::regex_replace(source, mainFunction, "void a3_main()");
            out += "\nlayout(constant_id = 0) const float a3FlipY = 1.0;\nvoid main()\n{\n    a3_main();\n";
            if (!SceneUtil::AutoDepth::isReversed())
                out += "    gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;\n";
            out += "    gl_Position.y = gl_Position.y * a3FlipY;\n}\n";
            return out;
        }

        void dumpFailure(const std::string& name, const std::string& source, const std::string& log)
        {
            static const std::filesystem::path dumpDir("/storage/emulated/0/Alpha3/dump_shaders");
            std::error_code ec;
            if (!std::filesystem::is_directory(dumpDir, ec))
                return;
            std::string file = "vulkan_failed_" + name;
            std::replace(file.begin(), file.end(), '/', '_');
            std::replace(file.begin(), file.end(), ' ', '_');
            std::ofstream(dumpDir / file, std::ios::binary) << source << "\n/*\n" << log << "\n*/\n";
        }

        std::string stripArraySuffix(std::string name)
        {
            if (name.size() > 3 && name.compare(name.size() - 3, 3, "[0]") == 0)
                name.erase(name.size() - 3);
            return name;
        }

        bool toSpirv(const std::string& source, EShLanguage language, const char* blockName, unsigned binding,
            std::vector<unsigned>& spirv, UniformBlock& block, const std::string& name, UniformBlock* lights = nullptr)
        {
            const char* text = source.c_str();
            glslang::TShader shader(language);
            shader.setStrings(&text, 1);
            shader.setEnvInput(glslang::EShSourceGlsl, language, glslang::EShClientVulkan, 100);
            shader.setEnvClient(glslang::EShClientVulkan, glslang::EShTargetVulkan_1_0);
            shader.setEnvTarget(glslang::EShTargetSpv, glslang::EShTargetSpv_1_0);
            shader.setEnvInputVulkanRulesRelaxed();
            shader.setGlobalUniformBlockName(blockName);
            shader.setGlobalUniformSet(0);
            shader.setGlobalUniformBinding(binding);
            shader.setAutoMapBindings(true);
            shader.setAutoMapLocations(true);
            const EShMessages messages = static_cast<EShMessages>(EShMsgSpvRules | EShMsgVulkanRules);
            if (!shader.parse(GetDefaultResources(), 320, false, messages))
            {
                Log(Debug::Error) << "Vulkan: " << name << " (" << blockName << "): " << shader.getInfoLog();
                dumpFailure(name + (language == EShLangVertex ? ".vert" : ".frag"), source, shader.getInfoLog());
                return false;
            }
            glslang::TProgram program;
            program.addShader(&shader);
            if (!program.link(messages) || !program.mapIO())
            {
                Log(Debug::Error) << "Vulkan: " << name << " (link): " << program.getInfoLog();
                dumpFailure(name + (language == EShLangVertex ? ".vert" : ".frag"), source, program.getInfoLog());
                return false;
            }
            program.buildReflection(EShReflectionAllBlockVariables | EShReflectionSeparateBuffers);
            // the stage's block, and the lights' (binding 3) if it is not known yet
            int blockIndex = -1, lightIndex = -1;
            const bool wantLights = lights != nullptr && lights->mSize == 0;
            for (int i = 0; i < program.getNumUniformBlocks(); ++i)
            {
                const glslang::TObjectReflection& reflected = program.getUniformBlock(i);
                if (reflected.getBinding() == static_cast<int>(binding))
                {
                    blockIndex = i;
                    block.mSize = static_cast<uint32_t>(reflected.size);
                }
                else if (wantLights && reflected.getBinding() == 3)
                {
                    lightIndex = i;
                    lights->mSize = static_cast<uint32_t>(reflected.size);
                }
            }
            for (int i = 0; i < program.getNumUniformVariables(); ++i)
            {
                const glslang::TObjectReflection& uniform = program.getUniform(i);
                UniformBlock* into = nullptr;
                if (blockIndex >= 0 && uniform.index == blockIndex)
                    into = &block;
                else if (lightIndex >= 0 && uniform.index == lightIndex)
                    into = lights;
                if (into == nullptr)
                    continue;
                UniformMember member;
                member.mName = stripArraySuffix(uniform.name);
                // a member of a named block is reflected as "block.member"
                if (into == lights && member.mName.rfind("a3LightUniforms.", 0) == 0)
                    member.mName.erase(0, std::strlen("a3LightUniforms."));
                member.mOffset = static_cast<uint32_t>(uniform.offset);
                member.mGlType = uniform.glDefineType;
                member.mArraySize = static_cast<uint32_t>(std::max(uniform.size, 1));
                member.mArrayStride = static_cast<uint32_t>(std::max(uniform.arrayStride, 0));
                into->mMembers.push_back(member);
            }
            glslang::SpvOptions options;
            options.disableOptimizer = false;
            glslang::GlslangToSpv(*program.getIntermediate(language), spirv, &options);
            return !spirv.empty();
        }

        VkShaderModule createModule(VkDevice device, const std::vector<unsigned>& spirv)
        {
            VkShaderModuleCreateInfo info{ VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO };
            info.codeSize = spirv.size() * sizeof(unsigned);
            info.pCode = spirv.data();
            VkShaderModule module = VK_NULL_HANDLE;
            vkCreateShaderModule(device, &info, nullptr, &module);
            return module;
        }

        // ---- the compiled programs kept on disk ----

        // What any change to this library (the translation, the passes here) or the driver makes different: the
        // library file's size and time, and the driver's pipeline cache identity
        std::string cacheSalt(Context& context)
        {
            std::string salt = "a3vk3";
            Dl_info info{};
            if (dladdr(reinterpret_cast<const void*>(&cacheSalt), &info) != 0 && info.dli_fname != nullptr)
            {
                struct stat st{};
                if (stat(info.dli_fname, &st) == 0)
                    salt += " " + std::to_string(st.st_size) + " " + std::to_string(st.st_mtime);
            }
            const VkPhysicalDeviceProperties& properties = context.device().mProperties;
            salt += " " + std::to_string(properties.driverVersion) + " "
                + std::string(reinterpret_cast<const char*>(properties.pipelineCacheUUID), VK_UUID_SIZE);
            return salt;
        }

        uint64_t fnv(const std::string& text, uint64_t hash = 0xcbf29ce484222325ull)
        {
            for (const unsigned char c : text)
                hash = (hash ^ c) * 0x100000001b3ull;
            return hash;
        }

        std::filesystem::path programCacheDirectory()
        {
            return cacheDirectory() / "vulkan";
        }

        struct Writer
        {
            std::string mOut;
            void u32(uint32_t v) { mOut.append(reinterpret_cast<const char*>(&v), 4); }
            void str(const std::string& v)
            {
                u32(static_cast<uint32_t>(v.size()));
                mOut += v;
            }
            void block(const UniformBlock& b)
            {
                u32(b.mSize);
                u32(static_cast<uint32_t>(b.mMembers.size()));
                for (const UniformMember& m : b.mMembers)
                {
                    str(m.mName);
                    u32(m.mOffset);
                    u32(static_cast<uint32_t>(m.mGlType));
                    u32(m.mArraySize);
                    u32(m.mArrayStride);
                }
            }
            void words(const std::vector<unsigned>& v)
            {
                u32(static_cast<uint32_t>(v.size()));
                mOut.append(reinterpret_cast<const char*>(v.data()), v.size() * sizeof(unsigned));
            }
        };

        struct Reader
        {
            const std::string& mIn;
            size_t mAt = 0;
            bool mOk = true;
            uint32_t u32()
            {
                uint32_t v = 0;
                if (mAt + 4 > mIn.size())
                {
                    mOk = false;
                    return 0;
                }
                std::memcpy(&v, mIn.data() + mAt, 4);
                mAt += 4;
                return v;
            }
            std::string str()
            {
                const uint32_t n = u32();
                if (!mOk || mAt + n > mIn.size())
                {
                    mOk = false;
                    return {};
                }
                std::string v = mIn.substr(mAt, n);
                mAt += n;
                return v;
            }
            void block(UniformBlock& b)
            {
                b.mSize = u32();
                const uint32_t count = u32();
                for (uint32_t i = 0; mOk && i < count; ++i)
                {
                    UniformMember m;
                    m.mName = str();
                    m.mOffset = u32();
                    m.mGlType = static_cast<int>(u32());
                    m.mArraySize = u32();
                    m.mArrayStride = u32();
                    b.mMembers.push_back(std::move(m));
                }
            }
            void words(std::vector<unsigned>& v)
            {
                const uint32_t n = u32();
                if (!mOk || mAt + size_t(n) * sizeof(unsigned) > mIn.size())
                {
                    mOk = false;
                    return;
                }
                v.resize(n);
                std::memcpy(v.data(), mIn.data() + mAt, size_t(n) * sizeof(unsigned));
                mAt += size_t(n) * sizeof(unsigned);
            }
        };

        const uint32_t sCacheMagic = 0x6b763361; // "a3vk"

        void saveCompiled(const Variant& variant, const std::string& key)
        {
            Writer w;
            w.u32(sCacheMagic);
            w.str(key);
            w.block(variant.mVertexUniforms);
            w.block(variant.mFragmentUniforms);
            w.block(variant.mLightUniforms);
            w.u32(static_cast<uint32_t>(variant.mSamplers.size()));
            for (const SamplerSlot& slot : variant.mSamplers)
            {
                w.str(slot.mName);
                w.u32(slot.mBinding);
                w.u32(slot.mCount);
                w.u32(static_cast<uint32_t>(slot.mViewType));
                w.u32(slot.mShadow ? 1 : 0);
                w.u32(slot.mStages);
            }
            w.u32(static_cast<uint32_t>(variant.mInputs.size()));
            for (const VertexInput& input : variant.mInputs)
            {
                w.str(input.mName);
                w.u32(input.mLocation);
                w.u32(input.mComponents);
                w.u32(input.mInteger ? 1 : 0);
            }
            w.u32(variant.mDrawStages);
            w.u32(variant.mLightStages);
            w.u32(variant.mVaryingEnd);
            w.words(variant.mSpirv[0]);
            w.words(variant.mSpirv[1]);
            std::error_code ec;
            std::filesystem::create_directories(programCacheDirectory(), ec);
            const std::filesystem::path file = programCacheDirectory() / (std::to_string(fnv(key)) + ".spv");
            const std::filesystem::path temporary = file.string() + ".tmp";
            {
                std::ofstream out(temporary, std::ios::binary);
                out.write(w.mOut.data(), static_cast<std::streamsize>(w.mOut.size()));
                if (!out)
                    return;
            }
            std::filesystem::rename(temporary, file, ec);
        }

        bool loadCompiled(Variant& variant, const std::string& key)
        {
            const std::filesystem::path file = programCacheDirectory() / (std::to_string(fnv(key)) + ".spv");
            std::ifstream in(file, std::ios::binary);
            if (!in)
                return false;
            const std::string data((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
            Reader r{ data };
            if (r.u32() != sCacheMagic || r.str() != key)
                return false;
            Variant loaded;
            r.block(loaded.mVertexUniforms);
            r.block(loaded.mFragmentUniforms);
            r.block(loaded.mLightUniforms);
            const uint32_t samplers = r.u32();
            for (uint32_t i = 0; r.mOk && i < samplers; ++i)
            {
                SamplerSlot slot;
                slot.mName = r.str();
                slot.mBinding = r.u32();
                slot.mCount = r.u32();
                slot.mViewType = static_cast<VkImageViewType>(r.u32());
                slot.mShadow = r.u32() != 0;
                slot.mStages = r.u32();
                loaded.mSamplers.push_back(std::move(slot));
            }
            const uint32_t inputs = r.u32();
            for (uint32_t i = 0; r.mOk && i < inputs; ++i)
            {
                VertexInput input;
                input.mName = r.str();
                input.mLocation = r.u32();
                input.mComponents = r.u32();
                input.mInteger = r.u32() != 0;
                input.classify();
                loaded.mInputs.push_back(std::move(input));
            }
            loaded.mDrawStages = r.u32();
            loaded.mLightStages = r.u32();
            loaded.mVaryingEnd = r.u32();
            r.words(loaded.mSpirv[0]);
            r.words(loaded.mSpirv[1]);
            if (!r.mOk || loaded.mSpirv[0].empty() || loaded.mSpirv[1].empty())
                return false;
            variant.mVertexUniforms = std::move(loaded.mVertexUniforms);
            variant.mFragmentUniforms = std::move(loaded.mFragmentUniforms);
            variant.mLightUniforms = std::move(loaded.mLightUniforms);
            variant.mSamplers = std::move(loaded.mSamplers);
            variant.mInputs = std::move(loaded.mInputs);
            variant.mDrawStages = loaded.mDrawStages;
            variant.mLightStages = loaded.mLightStages;
            variant.mVaryingEnd = loaded.mVaryingEnd;
            variant.mSpirv[0] = std::move(loaded.mSpirv[0]);
            variant.mSpirv[1] = std::move(loaded.mSpirv[1]);
            variant.mCompiled = true;
            return true;
        }
    }

    Programs::Programs(Context& context)
        : mContext(context)
    {
        std::call_once(sGlslang, [] { glslang::InitializeProcess(); });
    }

    Programs::~Programs()
    {
        {
            std::lock_guard lock(mJobsMutex);
            mStopping = true;
            mJobs.clear();
        }
        mJobsReady.notify_all();
        for (std::thread& worker : mWorkers)
            if (worker.joinable())
                worker.join();
    }

    bool Programs::compile(
        Variant& variant, std::string vertex, std::string fragment, const std::string& name, bool clip)
    {
        return compileSpirv(variant, std::move(vertex), std::move(fragment), name, clip) && createObjects(variant);
    }

    bool Programs::compileSpirv(
        Variant& variant, std::string vertex, std::string fragment, const std::string& name, bool clip)
    {
        variant.mName = name;
        // Vulkan GLSL 4.50: the ES profile has no gl_ClipDistance in glslang (GL_EXT_clip_cull_distance), and older
        // ES versions none for SPIR-V; the ES shaders' precision qualifiers are allowed and mean nothing there
        static const std::regex version(R"(#version[ \t]+[0-9]+[ \t]*(es|core|compatibility)?[^\n]*)");
        static const std::regex clipExtension(R"(#extension[ \t]+GL_EXT_clip_cull_distance[^\n]*)");
        static const std::regex derivatives(R"(#extension[ \t]+GL_OES_standard_derivatives[^\n]*)");
        for (std::string* source : { &vertex, &fragment })
        {
            *source = std::regex_replace(*source, clipExtension, "");
            *source = std::regex_replace(*source, derivatives, "");
            if (source->find("#version") == std::string::npos)
                *source = "#version 450\n" + *source;
            else
                *source = std::regex_replace(*source, version, "#version 450", std::regex_constants::format_first_only);
        }
        preprocess(vertex, EShLangVertex);
        preprocess(fragment, EShLangFragment);
        vertex = fixShortCircuits(vertex);
        fragment = fixShortCircuits(fragment);
        {
            // every pass is one sample a pixel, where centroid is the pixel's centre anyway; the Adreno 615's driver
            // charged the scene pass 3.2 ms of its 26 for it (the objects shader's centroid varyings)
            static const std::regex centroid(R"(\bcentroid\s+)");
            vertex = std::regex_replace(vertex, centroid, "");
            fragment = std::regex_replace(fragment, centroid, "");
        }
        // the same varyings get the same locations in both stages
        std::map<std::string, uint32_t> varyings;
        planVaryings(vertex, varyings);
        vertex = bindSamplers(vertex, VK_SHADER_STAGE_VERTEX_BIT, variant.mSamplers);
        fragment = bindSamplers(fragment, VK_SHADER_STAGE_FRAGMENT_BIT, variant.mSamplers);
        vertex = locateVaryings(vertex, true, varyings, &variant.mInputs);
        fragment = locateVaryings(fragment, false, varyings, nullptr);
        {
            uint32_t end = 0;
            for (const auto& [varying, location] : varyings)
                end = std::max(end, location + 1);
            variant.mVaryingEnd = end;
            const uint32_t limit = mContext.device().mProperties.limits.maxVertexOutputComponents / 4;
            if (end > limit)
                Log(Debug::Warning) << "Vulkan: " << name << " uses varying locations up to " << end - 1
                                    << ", the device takes " << limit;
        }
        // the clip distances are written only for draws with a clip plane on (OpenGL clips only where
        // GL_CLIP_DISTANCEi is enabled; Vulkan clips by every distance written, which cost the SM-T720 1.6 ms of its
        // 6.8 ms reflection pass and 1.2 ms of the scene's when every draw wrote them), and not on a device without
        // them (written to an array of no use)
        if ((!mContext.device().mFeatures.shaderClipDistance || !clip) && vertex.find("gl_ClipDistance") != std::string::npos)
            insertAfterDirectives(vertex, "float a3NoClipDistance[8];\n#define gl_ClipDistance a3NoClipDistance\n");
        variant.mLightStages = blockLights(vertex, fragment);
        if (blockMatrices(vertex))
            variant.mDrawStages |= VK_SHADER_STAGE_VERTEX_BIT;
        if (blockMatrices(fragment))
            variant.mDrawStages |= VK_SHADER_STAGE_FRAGMENT_BIT;
        vertex = finishVertex(vertex);
        variant.mSources[0] = vertex;
        variant.mSources[1] = fragment;

        if (!toSpirv(vertex, EShLangVertex, "a3VertexUniforms", 0, variant.mSpirv[0], variant.mVertexUniforms, name,
                &variant.mLightUniforms)
            || !toSpirv(fragment, EShLangFragment, "a3FragmentUniforms", 1, variant.mSpirv[1], variant.mFragmentUniforms,
                name, &variant.mLightUniforms))
            return false;
        if (variant.mLightUniforms.mSize == 0)
            variant.mLightStages = 0;
        variant.mCompiled = true;
        return true;
    }

    bool Programs::createObjects(Variant& variant)
    {
        if (!variant.mCompiled)
            return false;
        const VkDevice device = mContext.vkDevice();
        variant.mVertex = createModule(device, variant.mSpirv[0]);
        variant.mFragment = createModule(device, variant.mSpirv[1]);
        std::vector<unsigned>().swap(variant.mSpirv[0]);
        std::vector<unsigned>().swap(variant.mSpirv[1]);

        std::vector<VkDescriptorSetLayoutBinding> bindings;
        if (variant.mVertexUniforms.mSize > 0)
            bindings.push_back({ 0, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC, 1, VK_SHADER_STAGE_VERTEX_BIT, nullptr });
        if (variant.mFragmentUniforms.mSize > 0)
            bindings.push_back(
                { 1, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC, 1, VK_SHADER_STAGE_FRAGMENT_BIT, nullptr });
        if (variant.mDrawStages != 0)
            bindings.push_back({ 2, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC, 1, variant.mDrawStages, nullptr });
        if (variant.mLightStages != 0)
            bindings.push_back({ 3, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC, 1, variant.mLightStages, nullptr });
        for (const SamplerSlot& slot : variant.mSamplers)
            bindings.push_back(
                { slot.mBinding, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, slot.mCount, slot.mStages, nullptr });
        VkDescriptorSetLayoutCreateInfo setInfo{ VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO };
        setInfo.bindingCount = static_cast<uint32_t>(bindings.size());
        setInfo.pBindings = bindings.data();
        vkCreateDescriptorSetLayout(device, &setInfo, nullptr, &variant.mSetLayout);
        VkPipelineLayoutCreateInfo layoutInfo{ VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO };
        layoutInfo.setLayoutCount = 1;
        layoutInfo.pSetLayouts = &variant.mSetLayout;
        vkCreatePipelineLayout(device, &layoutInfo, nullptr, &variant.mLayout);

        variant.mUsesBuiltins = variant.mDrawStages != 0;
        variant.mOk = variant.mVertex != VK_NULL_HANDLE && variant.mFragment != VK_NULL_HANDLE;
        return variant.mOk;
    }

    Variant* Programs::variant(const osg::Program& program, const osg::StateSet::DefineList& defines,
        osg::State& state, bool clip, bool wait)
    {
        // the defines each stage asks for that the state has, as OSG puts them in (State::getDefineString)
        std::vector<const osg::Shader*> stages[2];
        for (unsigned i = 0; i < program.getNumShaders(); ++i)
        {
            const osg::Shader* shader = program.getShader(i);
            if (shader->getType() == osg::Shader::VERTEX)
                stages[0].push_back(shader);
            else if (shader->getType() == osg::Shader::FRAGMENT)
                stages[1].push_back(shader);
        }
        // As OSG does (Program::addShader gathers them, PerContextProgram puts them in every shader): the defines any
        // of the program's shaders imports go into both stages. A define only the fragment shader imports (point light
        // shadows' A3_PS_ON) still shapes the vertex shader's outputs; without it the fragment stage read varyings
        // no vertex output wrote, and the driver refused the pipeline.
        std::set<std::string> wanted;
        for (const auto& shaders : stages)
            for (const osg::Shader* shader : shaders)
                wanted.insert(shader->getShaderDefines().begin(), shader->getShaderDefines().end());
        std::string defineStrings[2];
        for (int stage = 0; stage < 2; ++stage)
        {
            for (const std::string& define : wanted)
            {
                auto found = defines.find(define);
                if (found == defines.end() || !(found->second.second & osg::StateAttribute::ON))
                    continue;
                defineStrings[stage] += "#define " + define;
                if (!found->second.first.empty())
                {
                    if (found->second.first[0] != '(')
                        defineStrings[stage] += " ";
                    defineStrings[stage] += found->second.first;
                }
                defineStrings[stage] += "\n";
            }
        }

        const auto key = std::make_pair(&program, defineStrings[0] + "\x01" + defineStrings[1] + (clip ? "\x02" : ""));
        Entry& entry = mVariants[key];
        osg::ref_ptr<const osg::Program> alive;
        if (entry.mVariant && entry.mProgram.lock(alive) && alive.get() == &program)
            return entry.mVariant.get();
        entry.mProgram = &program;
        // a variant the translator may still point at (a program made where a released one was) is kept
        if (entry.mVariant)
            mRetired.push_back(std::move(entry.mVariant));
        entry.mVariant = std::make_unique<Variant>();
        Variant& variant = *entry.mVariant;
        variant.mClips = clip;

        Job job;
        job.mVariant = &variant;
        job.mClip = clip;
        job.mState = &state;
        job.mProgramName = program.getName();
        for (int stage = 0; stage < 2; ++stage)
        {
            if (stages[stage].empty())
            {
                Log(Debug::Warning) << "Vulkan: program '" << program.getName() << "' has no "
                                    << (stage == 0 ? "vertex" : "fragment") << " shader";
                return &variant;
            }
            for (const osg::Shader* shader : stages[stage])
                job.mStageShaders[stage].emplace_back(shader);
            job.mDefines[stage] = defineStrings[stage];
        }
        if (mSalt.empty())
            mSalt = cacheSalt(mContext);
        job.mSalt = mSalt;

        // the rest (the stages merged, their sources read, the cache looked in, or the compile) goes to the
        // threads: a program first seen while turning does not hold the frame up
        if (wait)
        {
            if (prepare(job) && loadCompiled(variant, job.mCacheKey))
            {
                if (createObjects(variant))
                    Log(Debug::Verbose) << "Vulkan: program " << job.mName << " (from the cache)";
            }
            else if (job.mShaders[0] && job.mShaders[1])
            {
                run(job);
                if (variant.mCompiled && createObjects(variant))
                    Log(Debug::Verbose) << "Vulkan: program " << job.mName << " (waited for)";
            }
            variant.mStage = 0;
            return &variant;
        }
        variant.mStage = 1;
        {
            std::lock_guard lock(mJobsMutex);
            ++mCompiling;
        }
        post([this, job = std::move(job)]() mutable {
            if (prepare(job))
            {
                if (loadCompiled(*job.mVariant, job.mCacheKey))
                    Log(Debug::Verbose) << "Vulkan: program " << job.mName << " (from the cache)";
                else
                    run(job);
            }
            std::lock_guard lock(mJobsMutex);
            job.mVariant->mStage = 2;
            mCompiled.push_back(job.mVariant);
        });
        return &variant;
    }

    // A variant's stages as one shader each (as the OpenGL ES path links them, src/shaders.cpp), with the defines put
    // in, its uniforms' initializers and its cache key; any thread
    bool Programs::prepare(Job& job)
    {
        Variant& variant = *job.mVariant;
        std::string name = job.mProgramName;
        for (int stage = 0; stage < 2; ++stage)
        {
            const auto& shaders = job.mStageShaders[stage];
            if (shaders.empty())
                return false;
            osg::ref_ptr<const osg::Shader> shader = shaders.front();
            if (shaders.size() > 1)
            {
                std::vector<const osg::Shader*> list;
                for (const auto& candidate : shaders)
                    list.push_back(candidate.get());
                osg::ref_ptr<osg::Shader> merged = Alpha3::mergeShaderStage(list);
                if (merged)
                    shader = merged;
                else
                    for (const auto& candidate : shaders)
                        if (candidate->getShaderSource().find("void main") != std::string::npos)
                            shader = candidate;
            }
            name += (stage == 0 ? " " : " + ") + shader->getName();
            for (const auto& original : shaders)
                for (UniformInitializer& initializer : uniformInitializers(original->getShaderSource()))
                    variant.mInitializers[initializer.name] = initializer;
            job.mSources[stage] = shader->getShaderSource();
            insertAfterVersion(job.mSources[stage], job.mDefines[stage]);
            job.mShaders[stage] = shader;
        }
        job.mName = name;
        variant.mName = name;
        job.mCacheKey = job.mSalt + "\x01" + std::string(job.mClip ? "clip" : "") + "\x01" + job.mShaders[0]->getName()
            + "\x01" + job.mShaders[1]->getName() + "\x01" + job.mSources[0] + "\x01" + job.mSources[1];
        return true;
    }

    void Programs::post(std::function<void()> task)
    {
        {
            std::lock_guard lock(mJobsMutex);
            mJobs.push_back(std::move(task));
            // two: a program's compile (half a second on the SM-T720) does not hold up the pipelines behind it
            while (mWorkers.size() < 2)
                mWorkers.emplace_back([this] { work(); });
        }
        mJobsReady.notify_one();
    }

    // The translation (as the OpenGL ES path makes it) and compile of a variant; any thread
    void Programs::run(Job& job)
    {
        const auto start = std::chrono::steady_clock::now();
        std::string sources[2];
        for (int stage = 0; stage < 2; ++stage)
        {
            std::string source = std::move(job.mSources[stage]);
            const osg::Shader& shader = *job.mShaders[stage];
            Alpha3::translateShaderSource(shader, source);
            const std::size_t versionAt = source.find("#version ");
            const bool es = versionAt != std::string::npos
                && source.substr(versionAt, source.find('\n', versionAt) - versionAt).find(" es") != std::string::npos;
            if (stage == 0 && !es)
                job.mState->convertVertexShaderSourceToOsgBuiltIns(source);
            else
                job.mState->substituteShaderVariables(source);
            insertAfterVersion(source, job.mDefines[stage]);
            sources[stage] = std::move(source);
        }
        const auto translated = std::chrono::steady_clock::now();
        if (!compileSpirv(*job.mVariant, sources[0], sources[1], job.mName, job.mClip))
            return;
        saveCompiled(*job.mVariant, job.mCacheKey);
        Log(Debug::Verbose) << "Vulkan: program " << job.mName << " compiled (" << std::fixed << std::setprecision(1)
                            << std::chrono::duration<double, std::milli>(translated - start).count()
                            << " ms translating, "
                            << std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - translated)
                                   .count()
                            << " ms compiling)";
    }

    void Programs::work()
    {
#ifdef __linux__
        // below the render thread: the scheduler keeps the fast cores for it, and a compile does not hold up a frame
        setpriority(PRIO_PROCESS, static_cast<id_t>(gettid()), 10);
#endif
        while (true)
        {
            std::function<void()> task;
            {
                std::unique_lock lock(mJobsMutex);
                mJobsReady.wait(lock, [this] { return mStopping || !mJobs.empty(); });
                if (mStopping)
                    return;
                task = std::move(mJobs.front());
                mJobs.pop_front();
            }
            task();
        }
    }

    void Programs::poll()
    {
        // the pipeline cache to disk, when there are new pipelines in it (every 10 s at most)
        const auto now = std::chrono::steady_clock::now();
        if (mPipelineCount != mSavedPipelines && now - mLastSave > std::chrono::seconds(10))
        {
            mSavedPipelines = mPipelineCount;
            mLastSave = now;
            savePipelineCache(mContext.device());
        }
        std::vector<Variant*> compiled;
        std::vector<MadePipeline> made;
        {
            std::lock_guard lock(mJobsMutex);
            if (mCompiled.empty() && mMadePipelines.empty())
                return;
            compiled.swap(mCompiled);
            made.swap(mMadePipelines);
            mCompiling -= compiled.size();
        }
        for (MadePipeline& pipeline : made)
            pipeline.mVariant->mPipelines[pipeline.mKey] = pipeline.mPipeline;
        if (!made.empty())
            ++mPipelineGeneration;
        for (Variant* variant : compiled)
        {
            createObjects(*variant);
            variant->mStage = 0;
        }
    }

    Variant* Programs::builtin(const std::string& name, const std::string& vertex, const std::string& fragment)
    {
        std::unique_ptr<Variant>& variant = mBuiltins[name];
        if (!variant)
        {
            variant = std::make_unique<Variant>();
            variant->mBuiltin = true;
            compile(*variant, vertex, fragment, name);
        }
        return variant.get();
    }

    VkPipeline Programs::pipeline(
        Variant& variant, const PipelineState& state, const VertexLayout& layout, VkRenderPass pass, bool wait)
    {
        // the key's memory is kept from call to call: a lookup allocates nothing
        static thread_local std::string key;
        key.assign(reinterpret_cast<const char*>(&state), sizeof(state));
        key.append(reinterpret_cast<const char*>(layout.mBindings.data()),
            layout.mBindings.size() * sizeof(VkVertexInputBindingDescription));
        key += '|';
        key.append(reinterpret_cast<const char*>(layout.mAttributes.data()),
            layout.mAttributes.size() * sizeof(VkVertexInputAttributeDescription));
        auto found = variant.mPipelines.find(key);
        if (found != variant.mPipelines.end())
            return found->second;
        ++mPipelineCount;
        if (wait || variant.mBuiltin)
        {
            const VkPipeline made = createPipeline(variant, state, layout, pass);
            variant.mPipelines[key] = made;
            return made;
        }
        // made on a thread of its own (the SM-T720's driver takes 50 to 300 ms for one, a hitch when something new
        // comes into view); its draws are left out until poll puts it in
        variant.mPipelines[key] = VK_NULL_HANDLE;
        post([this, &variant, state, layout, pass, made = key]() mutable {
            const VkPipeline pipeline = createPipeline(variant, state, layout, pass);
            std::lock_guard lock(mJobsMutex);
            mMadePipelines.push_back({ &variant, std::move(made), pipeline });
        });
        return VK_NULL_HANDLE;
    }

    VkPipeline Programs::createPipeline(
        Variant& variant, const PipelineState& state, const VertexLayout& layout, VkRenderPass pass)
    {

        const float flip = state.mFlipY ? -1.f : 1.f;
        VkSpecializationMapEntry entry{ 0, 0, sizeof(float) };
        VkSpecializationInfo specialization{ 1, &entry, sizeof(float), &flip };
        VkPipelineShaderStageCreateInfo stages[2]{};
        stages[0].sType = stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
        stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
        stages[0].module = variant.mVertex;
        stages[0].pName = "main";
        stages[0].pSpecializationInfo = &specialization;
        stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
        stages[1].module = variant.mFragment;
        stages[1].pName = "main";

        VkPipelineVertexInputStateCreateInfo vertexInput{ VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO };
        vertexInput.vertexBindingDescriptionCount = static_cast<uint32_t>(layout.mBindings.size());
        vertexInput.pVertexBindingDescriptions = layout.mBindings.data();
        vertexInput.vertexAttributeDescriptionCount = static_cast<uint32_t>(layout.mAttributes.size());
        vertexInput.pVertexAttributeDescriptions = layout.mAttributes.data();

        VkPipelineInputAssemblyStateCreateInfo assembly{ VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO };
        assembly.topology = static_cast<VkPrimitiveTopology>(state.mTopology);

        VkPipelineViewportStateCreateInfo viewport{ VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO };
        viewport.viewportCount = 1;
        viewport.scissorCount = 1;

        VkPipelineRasterizationStateCreateInfo raster{ VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO };
        raster.polygonMode = VK_POLYGON_MODE_FILL;
        raster.cullMode = state.mCullMode;
        raster.frontFace = static_cast<VkFrontFace>(state.mFrontFace);
        raster.depthClampEnable = state.mDepthClamp && mContext.device().mFeatures.depthClamp ? VK_TRUE : VK_FALSE;
        raster.depthBiasEnable = state.mDepthBias;
        raster.depthBiasConstantFactor = state.mBiasConstant;
        raster.depthBiasSlopeFactor = state.mBiasSlope;
        raster.lineWidth = 1.f;

        VkPipelineMultisampleStateCreateInfo multisample{ VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO };
        multisample.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;

        VkPipelineDepthStencilStateCreateInfo depth{ VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO };
        depth.depthTestEnable = state.mDepthTest;
        depth.depthWriteEnable = state.mDepthWrite;
        depth.depthCompareOp = static_cast<VkCompareOp>(state.mDepthCompare);
        depth.stencilTestEnable = state.mStencil;
        VkStencilOpState stencil{};
        stencil.failOp = static_cast<VkStencilOp>(state.mStencilFail);
        stencil.depthFailOp = static_cast<VkStencilOp>(state.mStencilDepthFail);
        stencil.passOp = static_cast<VkStencilOp>(state.mStencilPass);
        stencil.compareOp = static_cast<VkCompareOp>(state.mStencilCompare);
        stencil.compareMask = state.mStencilReadMask;
        stencil.writeMask = state.mStencilWriteMask;
        depth.front = depth.back = stencil;
        depth.maxDepthBounds = 1.f;

        VkPipelineColorBlendAttachmentState attachment{};
        attachment.blendEnable = state.mBlend;
        attachment.srcColorBlendFactor = static_cast<VkBlendFactor>(state.mSrcColor);
        attachment.dstColorBlendFactor = static_cast<VkBlendFactor>(state.mDstColor);
        attachment.colorBlendOp = static_cast<VkBlendOp>(state.mColorOp);
        attachment.srcAlphaBlendFactor = static_cast<VkBlendFactor>(state.mSrcAlpha);
        attachment.dstAlphaBlendFactor = static_cast<VkBlendFactor>(state.mDstAlpha);
        attachment.alphaBlendOp = static_cast<VkBlendOp>(state.mAlphaOp);
        attachment.colorWriteMask = state.mWriteMask;
        VkPipelineColorBlendStateCreateInfo blend{ VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO };
        blend.attachmentCount = state.mColorAttachments;
        blend.pAttachments = &attachment;

        const VkDynamicState dynamics[] = { VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR,
            VK_DYNAMIC_STATE_STENCIL_REFERENCE, VK_DYNAMIC_STATE_BLEND_CONSTANTS };
        VkPipelineDynamicStateCreateInfo dynamic{ VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO };
        dynamic.dynamicStateCount = 4;
        dynamic.pDynamicStates = dynamics;

        VkGraphicsPipelineCreateInfo info{ VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO };
        info.stageCount = 2;
        info.pStages = stages;
        info.pVertexInputState = &vertexInput;
        info.pInputAssemblyState = &assembly;
        info.pViewportState = &viewport;
        info.pRasterizationState = &raster;
        info.pMultisampleState = &multisample;
        info.pDepthStencilState = &depth;
        info.pColorBlendState = &blend;
        info.pDynamicState = &dynamic;
        info.layout = variant.mLayout;
        info.renderPass = pass;
        VkPipeline pipeline = VK_NULL_HANDLE;
        const VkResult result = vkCreateGraphicsPipelines(
            mContext.vkDevice(), mContext.device().mPipelineCache, 1, &info, nullptr, &pipeline);
        if (result != VK_SUCCESS)
        {
            static std::mutex sDumpMutex;
            std::lock_guard lock(sDumpMutex);
            Log(Debug::Error) << "Vulkan: a pipeline of " << variant.mName << ": " << resultName(result) << " ("
                              << static_cast<int>(result) << "), varyings to location " << variant.mVaryingEnd
                              << " of " << mContext.device().mProperties.limits.maxVertexOutputComponents / 4
                              << ", " << variant.mSamplers.size() << " sampler bindings";
            if (!variant.mSources[0].empty())
            {
                dumpFailure(variant.mName + ".pipeline.vert", variant.mSources[0], resultName(result));
                dumpFailure(variant.mName + ".pipeline.frag", variant.mSources[1], resultName(result));
                variant.mSources[0].clear();
                variant.mSources[1].clear();
            }
        }
        return pipeline;
    }
}
