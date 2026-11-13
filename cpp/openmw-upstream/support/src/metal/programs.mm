// Metal's programs (programs.hpp). The passes over the GLSL and the compile to SPIR-V are src/vulkan/programs.cpp's,
// copied (the shader interface is the Vulkan renderer's); SPIRV-Cross then writes the stages in the Metal Shading
// Language, compiled into libraries, and pipelines are made from them.
#include "programs.hpp"

#include "context.hpp"
#include "resources.hpp"

#include "../experimentalshaders.hpp"
#include "../glesdialect.hpp"
#include "../shaderconverter.hpp"
#include "../support.hpp"

#include <algorithm>
#include <chrono>
#include <cstddef>
#include <cstring>
#include <dlfcn.h>
#include <filesystem>
#include <fstream>
#include <iomanip>
#include <iterator>
#include <mutex>
#include <regex>
#include <set>
#include <pthread.h>
#include <sys/stat.h>

#include <glslang/Public/ResourceLimits.h>
#include <glslang/Public/ShaderLang.h>
#include <glslang/SPIRV/GlslangToSpv.h>

#include <spirv_cross/spirv_msl.hpp>

#include <osg/Program>
#include <osg/State>

#include <components/debug/debuglog.hpp>

namespace Alpha3::Mtl
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

        // Puts the stage's storage blocks in set 1 at their OpenGL binding (the ShaderStorageBufferBinding's index),
        // read only unless the stage writes them (compute). The slots are the blocks glslang finds in use (toSpirv).
        std::string bindStorage(
            const std::string& source, bool readOnly, const std::map<std::string, uint32_t>& blockBindings = {})
        {
            static const std::regex declaration(
                R"(\blayout\s*\(([^)]*)\)\s*((?:(?:restrict|readonly|writeonly|coherent|volatile)\s+)*)buffer\s+(\w+))");
            static const std::regex hasBinding(R"(\bbinding\s*=)");
            std::string out;
            std::size_t last = 0;
            for (auto it = std::sregex_iterator(source.begin(), source.end(), declaration); it != std::sregex_iterator();
                 ++it)
            {
                const std::smatch& match = *it;
                const std::string args = match[1].str();
                if (!std::regex_search(args, hasBinding))
                    continue;
                std::string qualifiers = match[2].str();
                if (readOnly)
                {
                    static const std::regex access(R"(\b(readonly|writeonly)\s+)");
                    qualifiers = "readonly " + std::regex_replace(qualifiers, access, "");
                }
                out.append(source, last, match.position(0) - last);
                out += "layout(set = 1, " + args + ") " + qualifiers + "buffer " + match[3].str();
                last = match.position(0) + match.length(0);
            }
            out.append(source, last, std::string::npos);
            if (blockBindings.empty())
                return out;
            // the uniform blocks the program binds by name, at their index in set 1
            static const std::regex uniformBlock(R"((?:\blayout\s*\(([^)]*)\)\s*)?\buniform\s+(\w+)\s*\{)");
            std::string blocks;
            last = 0;
            for (auto it = std::sregex_iterator(out.begin(), out.end(), uniformBlock); it != std::sregex_iterator(); ++it)
            {
                const std::smatch& match = *it;
                auto found = blockBindings.find(match[2].str());
                if (found == blockBindings.end())
                    continue;
                static const std::regex binding(R"(\s*,?\s*\bbinding\s*=\s*\d+\s*,?)");
                std::string args = match[1].matched ? std::regex_replace(match[1].str(), binding, "")
                                                    : std::string("std140");
                if (args.empty())
                    args = "std140";
                blocks.append(out, last, match.position(0) - last);
                blocks += "layout(" + args + ", set = 1, binding = " + std::to_string(StorageSlot::sUniformBlockBase + found->second)
                    + ") uniform "
                    + match[2].str() + " {";
                last = match.position(0) + match.length(0);
            }
            blocks.append(out, last, std::string::npos);
            return blocks;
        }

        // the bindings of the storage blocks the stage uses, into slots
        void addStorage(const glslang::TProgram& program, VkShaderStageFlags stage, std::vector<StorageSlot>& slots)
        {
            for (int i = 0; i < program.getNumBufferBlocks(); ++i)
            {
                const int binding = program.getBufferBlock(i).getBinding();
                if (binding < 0)
                    continue;
                auto found = std::find_if(slots.begin(), slots.end(),
                    [&](const StorageSlot& slot) { return slot.mBinding == static_cast<uint32_t>(binding); });
                if (found == slots.end())
                {
                    slots.push_back({ static_cast<uint32_t>(binding), 0 });
                    found = slots.end() - 1;
                }
                found->mStages |= stage;
            }
            for (int i = 0; i < program.getNumUniformBlocks(); ++i)
            {
                const glslang::TObjectReflection& reflected = program.getUniformBlock(i);
                if (reflected.getBinding() < static_cast<int>(StorageSlot::sUniformBlockBase))
                    continue;
                const uint32_t binding = static_cast<uint32_t>(reflected.getBinding());
                auto found = std::find_if(slots.begin(), slots.end(),
                    [&](const StorageSlot& slot) { return slot.mBinding == binding; });
                if (found == slots.end())
                {
                    slots.push_back({ binding, 0, true });
                    found = slots.end() - 1;
                }
                found->mStages |= stage;
            }
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
                "vec4 osg_ClipPlane1; mat4 osg_ModelViewMatrixInverse; };\n");
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
            // OpenGL's clip z is -1..1 unless the state's ClipControl says ZERO_TO_ONE (reverse z; not the shadow casters,
            // which set NEGATIVE_ONE_TO_ONE): chosen per pipeline (PipelineState::mClipZeroToOne)
            out += "\nlayout(constant_id = 0) const float a3FlipY = 1.0;\nlayout(constant_id = 1) const bool a3HalfZ = "
                   "true;\nvoid main()\n{\n    a3_main();\n";
            out += "    if (a3HalfZ)\n        gl_Position.z = (gl_Position.z + gl_Position.w) * 0.5;\n";
            out += "    gl_Position.y = gl_Position.y * a3FlipY;\n}\n";
            return out;
        }

        void dumpFailure(const std::string& name, const std::string& source, const std::string& log)
        {
            static const std::filesystem::path dumpDir = cacheDirectory() / "dump_shaders";
            std::error_code ec;
            if (!std::filesystem::is_directory(dumpDir, ec))
                return;
            std::string file = "metal_failed_" + name;
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
            std::vector<unsigned>& spirv, UniformBlock& block, const std::string& name, UniformBlock* lights = nullptr,
            std::vector<StorageSlot>* storage = nullptr)
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
                Log(Debug::Error) << "Metal: " << name << " (" << blockName << "): " << shader.getInfoLog();
                dumpFailure(name + (language == EShLangVertex ? ".vert" : language == EShLangCompute ? ".comp" : ".frag"),
                    source, shader.getInfoLog());
                return false;
            }
            glslang::TProgram program;
            program.addShader(&shader);
            if (!program.link(messages) || !program.mapIO())
            {
                Log(Debug::Error) << "Metal: " << name << " (link): " << program.getInfoLog();
                dumpFailure(name + (language == EShLangVertex ? ".vert" : ".frag"), source, program.getInfoLog());
                return false;
            }
            program.buildReflection(EShReflectionAllBlockVariables | EShReflectionSeparateBuffers);
            if (storage != nullptr)
                addStorage(program,
                    language == EShLangVertex     ? VK_SHADER_STAGE_VERTEX_BIT
                        : language == EShLangCompute ? VK_SHADER_STAGE_COMPUTE_BIT
                                                     : VK_SHADER_STAGE_FRAGMENT_BIT,
                    *storage);
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
                // Both blocks are std140, where an array's elements are 16 bytes apart at least. glslang's reflection
                // gives the packed element size for arrays of scalars and vec2s in the default block (a float[4] at
                // stride 4, its next member 64 bytes on), so all but the first element would be written into the
                // first one's padding and read as 0 (the point light shadows' fades: only one light shadowed)
                if (member.mArraySize > 1 && member.mArrayStride % 16 != 0)
                    member.mArrayStride = (member.mArrayStride + 15) / 16 * 16;
                into->mMembers.push_back(member);
            }
            glslang::SpvOptions options;
            options.disableOptimizer = false;
            glslang::GlslangToSpv(*program.getIntermediate(language), spirv, &options);
            return !spirv.empty();
        }

        // ---- Metal ----

        spv::ExecutionModel modelOf(EShLanguage language)
        {
            return language == EShLangVertex ? spv::ExecutionModelVertex
                : language == EShLangCompute ? spv::ExecutionModelGLCompute
                                             : spv::ExecutionModelFragment;
        }

        void bindBuffer(spirv_cross::CompilerMSL& compiler, spv::ExecutionModel model, uint32_t set, uint32_t binding,
            uint32_t slot)
        {
            spirv_cross::MSLResourceBinding resource;
            resource.stage = model;
            resource.desc_set = set;
            resource.binding = binding;
            resource.count = 1;
            resource.msl_buffer = slot;
            compiler.add_msl_resource_binding(resource);
        }

        // The stage in the Metal Shading Language, each resource at its slot (programs.hpp); entry: its function's name
        bool toMsl(std::vector<unsigned>& spirv, EShLanguage language, const std::vector<SamplerSlot>* samplers,
            const std::vector<StorageSlot>& storage, uint32_t storageBase, std::string& out, std::string& entry,
            const std::string& name, MTLSize* group = nullptr)
        {
            try
            {
                spirv_cross::CompilerMSL compiler(std::vector<uint32_t>(spirv.begin(), spirv.end()));
                spirv_cross::CompilerMSL::Options options;
                options.platform = spirv_cross::CompilerMSL::Options::iOS;
                options.msl_version = spirv_cross::CompilerMSL::Options::make_msl_version(2, 1);
                // a fragment output narrower than its attachment is written as a vec4 (OpenGL leaves the rest)
                options.pad_fragment_output_components = true;
                compiler.set_msl_options(options);
                const spv::ExecutionModel model = modelOf(language);
                if (language == EShLangCompute)
                    bindBuffer(compiler, model, 0, 0, 0);
                else
                {
                    bindBuffer(compiler, model, 0, language == EShLangVertex ? 0 : 1, sStageBlockSlot);
                    bindBuffer(compiler, model, 0, 2, sDrawBlockSlot);
                    bindBuffer(compiler, model, 0, 3, sLightBlockSlot);
                }
                for (size_t i = 0; i < storage.size(); ++i)
                    bindBuffer(compiler, model, 1, storage[i].mBinding, storageBase + static_cast<uint32_t>(i));
                if (samplers != nullptr)
                {
                    const int stage = language == EShLangVertex ? 0 : 1;
                    for (const SamplerSlot& slot : *samplers)
                    {
                        if (slot.mSlot[stage] < 0)
                            continue;
                        spirv_cross::MSLResourceBinding resource;
                        resource.stage = model;
                        resource.desc_set = 0;
                        resource.binding = slot.mBinding;
                        resource.count = slot.mCount;
                        resource.msl_texture = static_cast<uint32_t>(slot.mSlot[stage]);
                        resource.msl_sampler = static_cast<uint32_t>(slot.mSlot[stage]);
                        compiler.add_msl_resource_binding(resource);
                    }
                }
                out = compiler.compile();
                entry = compiler.get_cleansed_entry_point_name("main", model);
                if (group != nullptr)
                {
                    const spirv_cross::SPIREntryPoint& point = compiler.get_entry_point("main", model);
                    *group = MTLSizeMake(std::max(point.workgroup_size.x, 1u), std::max(point.workgroup_size.y, 1u),
                        std::max(point.workgroup_size.z, 1u));
                }
                return true;
            }
            catch (const std::exception& error)
            {
                Log(Debug::Error) << "Metal: " << name << ": SPIRV-Cross: " << error.what();
                return false;
            }
        }

        id<MTLLibrary> makeLibrary(id<MTLDevice> device, const std::string& source, const std::string& name)
        {
            MTLCompileOptions* options = [[MTLCompileOptions alloc] init];
            options.languageVersion = MTLLanguageVersion2_1;
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
            options.fastMathEnabled = YES;
#pragma clang diagnostic pop
            NSError* error = nil;
            id<MTLLibrary> library = [device newLibraryWithSource:[NSString stringWithUTF8String:source.c_str()]
                                                          options:options
                                                            error:&error];
            if (library == nil)
            {
                const char* what = error ? [[error localizedDescription] UTF8String] : "unknown";
                Log(Debug::Error) << "Metal: " << name << ": the Metal compiler: " << what;
                dumpFailure(name + ".metal", source, what);
            }
            return library;
        }

        // Each sampler's texture and sampler slot in each stage
        void assignSamplerSlots(std::vector<SamplerSlot>& samplers, const std::string& name)
        {
            int next[2] = { 0, 0 };
            for (SamplerSlot& slot : samplers)
                for (int stage = 0; stage < 2; ++stage)
                {
                    const VkShaderStageFlags bit = stage == 0 ? VK_SHADER_STAGE_VERTEX_BIT : VK_SHADER_STAGE_FRAGMENT_BIT;
                    slot.mSlot[stage] = (slot.mStages & bit) ? next[stage] : -1;
                    if (slot.mStages & bit)
                        next[stage] += static_cast<int>(slot.mCount);
                }
            // a stage binds 16 samplers at most (its textures more)
            if (next[0] > 16 || next[1] > 16)
                Log(Debug::Warning) << "Metal: " << name << " samples " << std::max(next[0], next[1])
                                    << " textures in one stage; Metal binds 16 samplers";
        }

        MTLBlendFactor blendFactor(uint32_t factor)
        {
            switch (factor)
            {
                case VK_BLEND_FACTOR_ZERO:
                    return MTLBlendFactorZero;
                case VK_BLEND_FACTOR_ONE:
                    return MTLBlendFactorOne;
                case VK_BLEND_FACTOR_SRC_COLOR:
                    return MTLBlendFactorSourceColor;
                case VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR:
                    return MTLBlendFactorOneMinusSourceColor;
                case VK_BLEND_FACTOR_DST_COLOR:
                    return MTLBlendFactorDestinationColor;
                case VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR:
                    return MTLBlendFactorOneMinusDestinationColor;
                case VK_BLEND_FACTOR_SRC_ALPHA:
                    return MTLBlendFactorSourceAlpha;
                case VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA:
                    return MTLBlendFactorOneMinusSourceAlpha;
                case VK_BLEND_FACTOR_DST_ALPHA:
                    return MTLBlendFactorDestinationAlpha;
                case VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA:
                    return MTLBlendFactorOneMinusDestinationAlpha;
                case VK_BLEND_FACTOR_CONSTANT_COLOR:
                    return MTLBlendFactorBlendColor;
                case VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_COLOR:
                    return MTLBlendFactorOneMinusBlendColor;
                case VK_BLEND_FACTOR_CONSTANT_ALPHA:
                    return MTLBlendFactorBlendAlpha;
                case VK_BLEND_FACTOR_ONE_MINUS_CONSTANT_ALPHA:
                    return MTLBlendFactorOneMinusBlendAlpha;
                case VK_BLEND_FACTOR_SRC_ALPHA_SATURATE:
                    return MTLBlendFactorSourceAlphaSaturated;
                default:
                    return MTLBlendFactorOne;
            }
        }

        MTLBlendOperation blendOperation(uint32_t op)
        {
            switch (op)
            {
                case VK_BLEND_OP_SUBTRACT:
                    return MTLBlendOperationSubtract;
                case VK_BLEND_OP_REVERSE_SUBTRACT:
                    return MTLBlendOperationReverseSubtract;
                case VK_BLEND_OP_MIN:
                    return MTLBlendOperationMin;
                case VK_BLEND_OP_MAX:
                    return MTLBlendOperationMax;
                default:
                    return MTLBlendOperationAdd;
            }
        }

        MTLColorWriteMask writeMask(uint32_t mask)
        {
            MTLColorWriteMask out = MTLColorWriteMaskNone;
            if (mask & VK_COLOR_COMPONENT_R_BIT)
                out |= MTLColorWriteMaskRed;
            if (mask & VK_COLOR_COMPONENT_G_BIT)
                out |= MTLColorWriteMaskGreen;
            if (mask & VK_COLOR_COMPONENT_B_BIT)
                out |= MTLColorWriteMaskBlue;
            if (mask & VK_COLOR_COMPONENT_A_BIT)
                out |= MTLColorWriteMaskAlpha;
            return out;
        }

        // ---- the compiled programs kept on disk ----

        std::string cacheSalt(Context& context)
        {
            std::string salt = "a3mtl1 " __DATE__ " " __TIME__;
            Dl_info info{};
            if (dladdr(reinterpret_cast<const void*>(&cacheSalt), &info) != 0 && info.dli_fname != nullptr)
            {
                struct stat st{};
                if (stat(info.dli_fname, &st) == 0)
                    salt += " " + std::to_string(st.st_size) + " " + std::to_string(st.st_mtime);
            }
            salt += " ";
            salt += [[context.mtl() name] UTF8String];
            salt += " ";
            salt += [[[NSProcessInfo processInfo] operatingSystemVersionString] UTF8String];
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
            return cacheDirectory() / "metal";
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
                for (uint32_t i = 0; mOk && i < count && i < 4096; ++i)
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
        };

        const uint32_t sCacheMagic = 0x6c746d33; // "3mtl" + format version

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
                w.u32(static_cast<uint32_t>(slot.mSlot[0]));
                w.u32(static_cast<uint32_t>(slot.mSlot[1]));
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
            w.u32(variant.mInverseModelView ? 1 : 0);
            w.u32(static_cast<uint32_t>(variant.mStorage.size()));
            for (const StorageSlot& slot : variant.mStorage)
            {
                w.u32(slot.mBinding);
                w.u32(slot.mStages);
                w.u32(slot.mUniform ? 1 : 0);
            }
            for (int stage = 0; stage < 2; ++stage)
            {
                w.str(variant.mMsl[stage]);
                w.str(variant.mEntry[stage]);
            }
            std::error_code ec;
            std::filesystem::create_directories(programCacheDirectory(), ec);
            const std::filesystem::path file = programCacheDirectory() / (std::to_string(fnv(key)) + ".msl");
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
            const std::filesystem::path file = programCacheDirectory() / (std::to_string(fnv(key)) + ".msl");
            std::ifstream in(file, std::ios::binary);
            if (!in)
                return false;
            const std::string data((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
            Reader r{ data };
            if (r.u32() != sCacheMagic || r.str() != key)
                return false;
            UniformBlock blocks[3];
            for (UniformBlock& block : blocks)
                r.block(block);
            std::vector<SamplerSlot> samplers;
            const uint32_t samplerCount = r.u32();
            for (uint32_t i = 0; r.mOk && i < samplerCount && i < 256; ++i)
            {
                SamplerSlot slot;
                slot.mName = r.str();
                slot.mBinding = r.u32();
                slot.mCount = r.u32();
                slot.mViewType = static_cast<VkImageViewType>(r.u32());
                slot.mShadow = r.u32() != 0;
                slot.mStages = r.u32();
                slot.mSlot[0] = static_cast<int>(r.u32());
                slot.mSlot[1] = static_cast<int>(r.u32());
                samplers.push_back(std::move(slot));
            }
            std::vector<VertexInput> inputs;
            const uint32_t inputCount = r.u32();
            for (uint32_t i = 0; r.mOk && i < inputCount && i < 256; ++i)
            {
                VertexInput input;
                input.mName = r.str();
                input.mLocation = r.u32();
                input.mComponents = r.u32();
                input.mInteger = r.u32() != 0;
                input.classify();
                inputs.push_back(std::move(input));
            }
            const uint32_t drawStages = r.u32();
            const uint32_t lightStages = r.u32();
            const uint32_t varyingEnd = r.u32();
            const bool inverse = r.u32() != 0;
            std::vector<StorageSlot> storage;
            const uint32_t storageCount = r.u32();
            for (uint32_t i = 0; r.mOk && i < storageCount && i < 64; ++i)
            {
                StorageSlot slot;
                slot.mBinding = r.u32();
                slot.mStages = r.u32();
                slot.mUniform = r.u32() != 0;
                storage.push_back(slot);
            }
            std::string msl[2], entry[2];
            for (int stage = 0; stage < 2; ++stage)
            {
                msl[stage] = r.str();
                entry[stage] = r.str();
            }
            if (!r.mOk || msl[0].empty() || msl[1].empty())
                return false;
            variant.mVertexUniforms = std::move(blocks[0]);
            variant.mFragmentUniforms = std::move(blocks[1]);
            variant.mLightUniforms = std::move(blocks[2]);
            variant.mSamplers = std::move(samplers);
            variant.mInputs = std::move(inputs);
            variant.mDrawStages = drawStages;
            variant.mLightStages = lightStages;
            variant.mVaryingEnd = varyingEnd;
            variant.mInverseModelView = inverse;
            variant.mStorage = std::move(storage);
            for (int stage = 0; stage < 2; ++stage)
            {
                variant.mMsl[stage] = std::move(msl[stage]);
                variant.mEntry[stage] = std::move(entry[stage]);
            }
            variant.mCompiled = true;
            return true;
        }
    }

    MTLCompareFunction mtlCompare(uint32_t op)
    {
        switch (op)
        {
            case VK_COMPARE_OP_NEVER:
                return MTLCompareFunctionNever;
            case VK_COMPARE_OP_LESS:
                return MTLCompareFunctionLess;
            case VK_COMPARE_OP_EQUAL:
                return MTLCompareFunctionEqual;
            case VK_COMPARE_OP_LESS_OR_EQUAL:
                return MTLCompareFunctionLessEqual;
            case VK_COMPARE_OP_GREATER:
                return MTLCompareFunctionGreater;
            case VK_COMPARE_OP_NOT_EQUAL:
                return MTLCompareFunctionNotEqual;
            case VK_COMPARE_OP_GREATER_OR_EQUAL:
                return MTLCompareFunctionGreaterEqual;
            default:
                return MTLCompareFunctionAlways;
        }
    }

    MTLStencilOperation mtlStencilOp(uint32_t op)
    {
        switch (op)
        {
            case VK_STENCIL_OP_ZERO:
                return MTLStencilOperationZero;
            case VK_STENCIL_OP_REPLACE:
                return MTLStencilOperationReplace;
            case VK_STENCIL_OP_INCREMENT_AND_CLAMP:
                return MTLStencilOperationIncrementClamp;
            case VK_STENCIL_OP_DECREMENT_AND_CLAMP:
                return MTLStencilOperationDecrementClamp;
            case VK_STENCIL_OP_INVERT:
                return MTLStencilOperationInvert;
            case VK_STENCIL_OP_INCREMENT_AND_WRAP:
                return MTLStencilOperationIncrementWrap;
            case VK_STENCIL_OP_DECREMENT_AND_WRAP:
                return MTLStencilOperationDecrementWrap;
            default:
                return MTLStencilOperationKeep;
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
        return compileStages(variant, std::move(vertex), std::move(fragment), name, clip) && createObjects(variant);
    }

    bool Programs::compileStages(Variant& variant, std::string vertex, std::string fragment, const std::string& name,
        bool clip, const std::map<std::string, uint32_t>& blockBindings)
    {
        variant.mName = name;
        // as the Vulkan renderer's: Vulkan GLSL 4.50, the ES shaders' precision qualifiers allowed and meaning nothing
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
            // every pass is one sample a pixel, where centroid is the pixel's centre anyway
            static const std::regex centroid(R"(\bcentroid\s+)");
            vertex = std::regex_replace(vertex, centroid, "");
            fragment = std::regex_replace(fragment, centroid, "");
        }
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
        }
        // the clip distances are written only for draws with a clip plane on: Metal clips by every distance written
        if (!clip && vertex.find("gl_ClipDistance") != std::string::npos)
            insertAfterDirectives(vertex, "float a3NoClipDistance[8];\n#define gl_ClipDistance a3NoClipDistance\n");
        variant.mLightStages = blockLights(vertex, fragment);
        variant.mInverseModelView = false;
        {
            static const std::regex inverseModelView(R"(\binverse\s*\(\s*osg_ModelViewMatrix\s*\))");
            for (std::string* source : { &vertex, &fragment })
                if (std::regex_search(*source, inverseModelView)
                    && source->find("osg_ModelViewMatrix") != std::string::npos)
                {
                    *source = std::regex_replace(*source, inverseModelView, "osg_ModelViewMatrixInverse");
                    variant.mInverseModelView = true;
                }
        }
        if (blockMatrices(vertex))
            variant.mDrawStages |= VK_SHADER_STAGE_VERTEX_BIT;
        if (blockMatrices(fragment))
            variant.mDrawStages |= VK_SHADER_STAGE_FRAGMENT_BIT;
        vertex = bindStorage(vertex, true, blockBindings);
        fragment = bindStorage(fragment, true, blockBindings);
        vertex = finishVertex(vertex);
        variant.mSources[0] = vertex;
        variant.mSources[1] = fragment;

        variant.mStorage.clear();
        std::vector<unsigned> spirv[2];
        if (!toSpirv(vertex, EShLangVertex, "a3VertexUniforms", 0, spirv[0], variant.mVertexUniforms, name,
                &variant.mLightUniforms, &variant.mStorage)
            || !toSpirv(fragment, EShLangFragment, "a3FragmentUniforms", 1, spirv[1], variant.mFragmentUniforms, name,
                &variant.mLightUniforms, &variant.mStorage))
            return false;
        if (variant.mLightUniforms.mSize == 0)
            variant.mLightStages = 0;
        if (variant.mStorage.size() > sMaxStorageSlots)
        {
            Log(Debug::Error) << "Metal: " << name << " reads " << variant.mStorage.size() << " storage blocks, more than "
                              << sMaxStorageSlots;
            return false;
        }
        assignSamplerSlots(variant.mSamplers, name);
        if (!toMsl(spirv[0], EShLangVertex, &variant.mSamplers, variant.mStorage, sStorageSlotBase, variant.mMsl[0],
                variant.mEntry[0], name)
            || !toMsl(spirv[1], EShLangFragment, &variant.mSamplers, variant.mStorage, sStorageSlotBase,
                variant.mMsl[1], variant.mEntry[1], name))
            return false;
        variant.mCompiled = true;
        return true;
    }

    bool Programs::createObjects(Variant& variant)
    {
        if (!variant.mCompiled)
            return false;
        if (variant.mOk)
            return true;
        for (int stage = 0; stage < 2; ++stage)
        {
            variant.mLibrary[stage] = makeLibrary(
                mContext.mtl(), variant.mMsl[stage], variant.mName + (stage == 0 ? ".vert" : ".frag"));
            if (variant.mLibrary[stage] == nil)
            {
                if (!variant.mSources[stage].empty())
                    dumpFailure(variant.mName + (stage == 0 ? ".vert" : ".frag"), variant.mSources[stage], "MSL");
                return false;
            }
        }
        variant.mFragment
            = [variant.mLibrary[1] newFunctionWithName:[NSString stringWithUTF8String:variant.mEntry[1].c_str()]];
        variant.mUsesBuiltins = variant.mDrawStages != 0;
        variant.mOk = variant.mFragment != nil;
        return variant.mOk;
    }

    id<MTLFunction> Programs::vertexFunction(Variant& variant, bool flipY, bool halfZ)
    {
        const int which = (flipY ? 1 : 0) | (halfZ ? 2 : 0);
        std::lock_guard lock(variant.mFunctionsMutex);
        if (variant.mVertex[which] != nil)
            return variant.mVertex[which];
        MTLFunctionConstantValues* constants = [[MTLFunctionConstantValues alloc] init];
        const float flip = flipY ? -1.f : 1.f;
        const bool half = halfZ;
        [constants setConstantValue:&flip type:MTLDataTypeFloat atIndex:0];
        [constants setConstantValue:&half type:MTLDataTypeBool atIndex:1];
        NSError* error = nil;
        variant.mVertex[which] =
            [variant.mLibrary[0] newFunctionWithName:[NSString stringWithUTF8String:variant.mEntry[0].c_str()]
                                      constantValues:constants
                                               error:&error];
        if (variant.mVertex[which] == nil)
            Log(Debug::Error) << "Metal: the vertex function of " << variant.mName << ": "
                              << (error ? [[error localizedDescription] UTF8String] : "unknown");
        return variant.mVertex[which];
    }

    size_t Programs::sourceHash(const osg::Program& program)
    {
        size_t hash = 0;
        for (unsigned i = 0; i < program.getNumShaders(); ++i)
        {
            const osg::Shader* shader = program.getShader(i);
            const size_t own = std::hash<std::string>()(shader->getShaderSource());
            mShaderHashes[shader] = { shader, own };
            hash = hash * 1000003u ^ own;
        }
        return hash;
    }

    void Programs::checkSources()
    {
        if (++mSourceChecks % 60 != 0)
            return;
        bool changed = false;
        for (auto it = mShaderHashes.begin(); it != mShaderHashes.end();)
        {
            osg::ref_ptr<const osg::Shader> shader;
            if (!it->second.first.lock(shader) || shader.get() != it->first)
            {
                it = mShaderHashes.erase(it);
                continue;
            }
            const size_t hash = std::hash<std::string>()(shader->getShaderSource());
            if (hash != it->second.second)
            {
                it->second.second = hash;
                changed = true;
            }
            ++it;
        }
        if (changed)
            ++mSourceGeneration;
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
        {
            if (entry.mGeneration == mSourceGeneration)
                return entry.mVariant.get();
            entry.mGeneration = mSourceGeneration;
            if (sourceHash(program) == entry.mSourceHash)
                return entry.mVariant.get();
            Log(Debug::Verbose) << "Metal: program '" << program.getName() << "' made again: its sources changed";
        }
        entry.mProgram = &program;
        entry.mSourceHash = sourceHash(program);
        entry.mGeneration = mSourceGeneration;
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
        for (const auto& [blockName, index] : program.getUniformBlockBindingList())
            job.mBlockBindings[blockName] = index;
        for (const auto& [attribute, location] : program.getAttribBindingList())
            job.mAttribBindings[attribute] = location;
        for (int stage = 0; stage < 2; ++stage)
        {
            if (stages[stage].empty())
            {
                Log(Debug::Warning) << "Metal: program '" << program.getName() << "' has no "
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
                    Log(Debug::Verbose) << "Metal: program " << job.mName << " (from the cache)";
            }
            else if (job.mShaders[0] && job.mShaders[1])
            {
                run(job);
                if (variant.mCompiled && createObjects(variant))
                    Log(Debug::Verbose) << "Metal: program " << job.mName << " (waited for)";
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
                    Log(Debug::Verbose) << "Metal: program " << job.mName << " (from the cache)";
                else
                    run(job);
                // the libraries too (Metal's compiler, the slow part), here rather than on the draw thread
                @autoreleasepool
                {
                    createObjects(*job.mVariant);
                }
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
            // OSG's $OSG_ variables (osgText's shaders begin with $OSG_GLSL_VERSION: their #version is one), before
            // the defines go after the #version
            if (job.mSources[stage].find("$OSG_") != std::string::npos)
                job.mState->substituteShaderVariables(job.mSources[stage]);
            insertAfterVersion(job.mSources[stage], job.mDefines[stage]);
            job.mShaders[stage] = shader;
        }
        job.mName = name;
        variant.mName = name;
        std::string blocks;
        for (const auto& [blockName, index] : job.mBlockBindings)
            blocks += blockName + "=" + std::to_string(index) + ";";
        for (const auto& [attribute, location] : job.mAttribBindings)
            blocks += attribute + "@" + std::to_string(location) + ";";
        // what compile() adds to the sources (translateShaderSource: the dialect, the converter, the experimental
        // parts and the point light shadows with their settings), as the OpenGL ES program cache keys it
        // (programcache.cpp): the sources here are from before that, so a changed setting would load the old program
        const std::string stamps = std::string(translatorStamp()) + "\x02" + converterStamp() + "\x02" + injectionStamp()
            + "\x02" + experimentalShaderStamp() + "\x02" + pointShadowsShaderStamp();
        job.mCacheKey = job.mSalt + "\x01" + stamps + "\x01" + blocks + std::string(job.mClip ? "clip" : "") + "\x01" + job.mShaders[0]->getName()
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
            // and an ES one with OpenGL's built ins (osgText's: gl_Vertex, gl_ModelViewProjectionMatrix)
            const bool builtIns = source.find("gl_Vertex") != std::string::npos
                || source.find("gl_ModelViewProjectionMatrix") != std::string::npos
                || source.find("gl_MultiTexCoord") != std::string::npos || source.find("gl_Color") != std::string::npos;
            if (stage == 0 && (!es || builtIns))
                job.mState->convertVertexShaderSourceToOsgBuiltIns(source);
            else
                job.mState->substituteShaderVariables(source);
            insertAfterVersion(source, job.mDefines[stage]);
            sources[stage] = std::move(source);
        }
        const auto translated = std::chrono::steady_clock::now();
        if (!compileStages(*job.mVariant, sources[0], sources[1], job.mName, job.mClip, job.mBlockBindings))
            return;
        // attributes of names of their own bound to the locations of OSG's aliases (the post processor's
        // _omw_Vertex at osg_Vertex's): read from the arrays of those
        for (VertexInput& input : job.mVariant->mInputs)
        {
            auto bound = job.mAttribBindings.find(input.mName);
            if (bound == job.mAttribBindings.end())
                continue;
            osg::State& state = *job.mState;
            std::vector<const osg::VertexAttribAlias*> aliases = { &state.getVertexAlias(), &state.getNormalAlias(),
                &state.getColorAlias(), &state.getSecondaryColorAlias(), &state.getFogCoordAlias() };
            for (const osg::VertexAttribAlias& alias : state.getTexCoordAliasList())
                aliases.push_back(&alias);
            for (const osg::VertexAttribAlias* alias : aliases)
                if (alias->_location == bound->second)
                {
                    input.mName = alias->_osgName;
                    input.classify();
                    break;
                }
        }
        saveCompiled(*job.mVariant, job.mCacheKey);
        Log(Debug::Verbose) << "Metal: program " << job.mName << " compiled (" << std::fixed << std::setprecision(1)
                            << std::chrono::duration<double, std::milli>(translated - start).count()
                            << " ms translating, "
                            << std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - translated)
                                   .count()
                            << " ms compiling)";
    }

    void Programs::work()
    {
        // below the render thread: a compile does not hold up a frame
        pthread_set_qos_class_self_np(QOS_CLASS_UTILITY, 0);
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
            @autoreleasepool
            {
                task();
            }
        }
    }

    void Programs::poll()
    {
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

    ComputeVariant* Programs::compute(const osg::Program& program)
    {
        ComputeEntry& entry = mComputes[&program];
        osg::ref_ptr<const osg::Program> alive;
        if (entry.mVariant && entry.mProgram.lock(alive) && alive.get() == &program)
            return entry.mVariant.get();
        // a program made where a released one was: the old objects stay (a frame may still use them)
        entry.mVariant.release();
        entry.mProgram = &program;
        entry.mVariant = std::make_unique<ComputeVariant>();
        ComputeVariant& variant = *entry.mVariant;
        const osg::Shader* shader = nullptr;
        for (unsigned i = 0; i < program.getNumShaders(); ++i)
            if (program.getShader(i)->getType() == osg::Shader::COMPUTE)
                shader = program.getShader(i);
        if (shader == nullptr)
            return nullptr;
        variant.mName = shader->getName().empty() ? program.getName() : shader->getName();
        makeCompute(variant, shader->getShaderSource());
        return &variant;
    }

    bool Programs::makeCompute(ComputeVariant& variant, std::string source)
    {
        static const std::regex version(R"(#version[ \t]+[0-9]+[ \t]*(es|core|compatibility)?[^\n]*)");
        if (source.find("#version") == std::string::npos)
            source = "#version 450\n" + source;
        else
            source = std::regex_replace(source, version, "#version 450", std::regex_constants::format_first_only);
        preprocess(source, EShLangCompute);
        source = bindStorage(source, false);
        std::vector<unsigned> spirv;
        if (!toSpirv(source, EShLangCompute, "a3ComputeUniforms", 0, spirv, variant.mUniforms, variant.mName, nullptr,
                &variant.mStorage))
            return false;
        if (variant.mStorage.size() > sMaxStorageSlots)
            return false;
        std::string msl, entry;
        if (!toMsl(spirv, EShLangCompute, nullptr, variant.mStorage, 1, msl, entry, variant.mName, &variant.mGroup))
            return false;
        id<MTLLibrary> library = makeLibrary(mContext.mtl(), msl, variant.mName + ".comp");
        if (library == nil)
            return false;
        id<MTLFunction> function = [library newFunctionWithName:[NSString stringWithUTF8String:entry.c_str()]];
        NSError* error = nil;
        variant.mPipeline = function ? [mContext.mtl() newComputePipelineStateWithFunction:function error:&error] : nil;
        if (variant.mPipeline == nil)
        {
            Log(Debug::Error) << "Metal: the compute pipeline of " << variant.mName << ": "
                              << (error ? [[error localizedDescription] UTF8String] : "no function");
            return false;
        }
        variant.mOk = true;
        Log(Debug::Verbose) << "Metal: compute program " << variant.mName << " (" << variant.mStorage.size()
                            << " storage blocks, " << variant.mUniforms.mSize << " bytes of uniforms)";
        return true;
    }

    id<MTLRenderPipelineState> Programs::pipeline(
        Variant& variant, const PipelineState& state, const VertexLayout& layout, bool wait)
    {
        // what the pipeline is made of: the encoder's part of the state (culling, depth, stencil, bias) is left out
        PipelineState fixed = state;
        fixed.mTopology = 0;
        fixed.mCullMode = 0;
        fixed.mFrontFace = 0;
        fixed.mDepthTest = fixed.mDepthWrite = fixed.mDepthCompare = 0;
        fixed.mDepthBias = fixed.mDepthClamp = 0;
        fixed.mBiasConstant = fixed.mBiasSlope = 0.f;
        fixed.mStencil = fixed.mStencilCompare = fixed.mStencilFail = fixed.mStencilDepthFail = fixed.mStencilPass = 0;
        fixed.mStencilReadMask = fixed.mStencilWriteMask = 0;
        if (!fixed.mBlend)
            fixed.mSrcColor = fixed.mDstColor = fixed.mSrcAlpha = fixed.mDstAlpha = fixed.mColorOp = fixed.mAlphaOp = 0;
        // the key's memory is kept from call to call: a lookup allocates nothing
        static thread_local std::string key;
        key.assign(reinterpret_cast<const char*>(&fixed), sizeof(fixed));
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
            id<MTLRenderPipelineState> made = createPipeline(variant, fixed, layout);
            variant.mPipelines[key] = made;
            return made;
        }
        // made on a thread of its own; its draws are left out until poll puts it in
        variant.mPipelines[key] = nil;
        post([this, &variant, fixed, layout, made = key]() mutable {
            id<MTLRenderPipelineState> pipeline = createPipeline(variant, fixed, layout);
            std::lock_guard lock(mJobsMutex);
            mMadePipelines.push_back({ &variant, std::move(made), pipeline });
        });
        return nil;
    }

    id<MTLRenderPipelineState> Programs::createPipeline(
        Variant& variant, const PipelineState& state, const VertexLayout& layout)
    {
        MTLRenderPipelineDescriptor* info = [[MTLRenderPipelineDescriptor alloc] init];
        info.label = [NSString stringWithUTF8String:variant.mName.c_str()];
        info.vertexFunction = vertexFunction(variant, state.mFlipY != 0, state.mClipZeroToOne == 0);
        info.fragmentFunction = variant.mFragment;
        if (info.vertexFunction == nil || info.fragmentFunction == nil)
            return nil;

        MTLVertexDescriptor* vertex = [MTLVertexDescriptor vertexDescriptor];
        for (const VkVertexInputAttributeDescription& attribute : layout.mAttributes)
        {
            MTLVertexAttributeDescriptor* to = vertex.attributes[attribute.location];
            to.format = mtlVertexFormat(attribute.format);
            to.offset = attribute.offset;
            to.bufferIndex = sVertexBufferBase + attribute.binding;
        }
        for (const VkVertexInputBindingDescription& binding : layout.mBindings)
        {
            MTLVertexBufferLayoutDescriptor* to = vertex.layouts[sVertexBufferBase + binding.binding];
            if (binding.stride == 0)
            {
                // one value for every vertex (an overall binding, or the constant inputs)
                to.stepFunction = MTLVertexStepFunctionConstant;
                to.stepRate = 0;
                to.stride = 16;
            }
            else
            {
                to.stepFunction = MTLVertexStepFunctionPerVertex;
                to.stride = binding.stride;
            }
        }
        info.vertexDescriptor = vertex;

        const uint32_t formats[2] = { state.mColorFormat, state.mColorFormat1 };
        const uint32_t masks[2] = { state.mWriteMask, state.mWriteMask1 };
        for (uint32_t i = 0; i < std::min(state.mColorAttachments, 2u); ++i)
        {
            MTLRenderPipelineColorAttachmentDescriptor* color = info.colorAttachments[i];
            color.pixelFormat = mtlFormat(static_cast<VkFormat>(formats[i]));
            color.writeMask = writeMask(masks[i]);
            color.blendingEnabled = state.mBlend ? YES : NO;
            if (state.mBlend)
            {
                color.sourceRGBBlendFactor = blendFactor(state.mSrcColor);
                color.destinationRGBBlendFactor = blendFactor(state.mDstColor);
                color.rgbBlendOperation = blendOperation(state.mColorOp);
                color.sourceAlphaBlendFactor = blendFactor(state.mSrcAlpha);
                color.destinationAlphaBlendFactor = blendFactor(state.mDstAlpha);
                color.alphaBlendOperation = blendOperation(state.mAlphaOp);
            }
        }
        const VkFormat depth = static_cast<VkFormat>(state.mDepthFormat);
        if (isDepthFormat(depth))
        {
            info.depthAttachmentPixelFormat = mtlFormat(depth);
            if (hasStencil(depth))
                info.stencilAttachmentPixelFormat = mtlFormat(depth);
        }

        NSError* error = nil;
        id<MTLRenderPipelineState> pipeline = [mContext.mtl() newRenderPipelineStateWithDescriptor:info error:&error];
        if (pipeline == nil)
        {
            static std::mutex sDumpMutex;
            std::lock_guard lock(sDumpMutex);
            Log(Debug::Error) << "Metal: a pipeline of " << variant.mName << ": "
                              << (error ? [[error localizedDescription] UTF8String] : "unknown") << ", "
                              << variant.mSamplers.size() << " samplers, " << layout.mAttributes.size()
                              << " vertex inputs";
            if (!variant.mSources[0].empty())
            {
                dumpFailure(variant.mName + ".pipeline.vert", variant.mSources[0], "pipeline");
                dumpFailure(variant.mName + ".pipeline.frag", variant.mSources[1], "pipeline");
                dumpFailure(variant.mName + ".pipeline.vert.metal", variant.mMsl[0], "pipeline");
                dumpFailure(variant.mName + ".pipeline.frag.metal", variant.mMsl[1], "pipeline");
                variant.mSources[0].clear();
                variant.mSources[1].clear();
            }
        }
        return pipeline;
    }
}
