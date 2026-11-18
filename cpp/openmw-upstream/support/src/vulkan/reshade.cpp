// ReShade effects on the Vulkan renderer, see reshade.hpp.
//
// The effect runtime: the compiled module's textures, samplers, uniforms and passes made into Vulkan objects, each pass
// a render pass of its own with a full-screen (or the pass's own) draw. Written for this renderer after the design of
// vkShade (github.com/ralgar/vkShade, BSD 2-clause, Copyright (c) 2026 Ryan Algar): Vulkan 1.0 render passes in place of
// its dynamic rendering, inside our frame's command buffer, the scene's real depth, the back buffer as two images
// that take turns (as ReShade's own runtime does) rather than one copy per effect.
//
// Threads: the overlay (JNI, its UI thread) reads and changes the preset under sMutex (Shared); the render thread
// notices the change by its generation numbers, compiles on a thread of its own and makes the Vulkan objects itself.

#include "reshade.hpp"

#include "context.hpp"
#include "device.hpp"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstring>
#include <ctime>
#include <filesystem>
#include <fstream>
#include <map>
#include <mutex>
#include <random>
#include <regex>
#include <set>
#include <sstream>
#include <thread>
#include <vector>

#include <effect_codegen.hpp>
#include <effect_module.hpp>
#include <effect_parser.hpp>
#include <effect_preprocessor.hpp>

#include <osg/Image>
#include <osgDB/ReadFile>
#include <vk_mem_alloc.h>

#include <components/debug/debuglog.hpp>

#ifdef __ANDROID__
#include <jni.h>
#endif

namespace Alpha3::Vk
{
    namespace
    {
        namespace fs = std::filesystem;

        const fs::path sRoot = "/storage/emulated/0/Alpha3/reshade";
        const char* const sStarter = "Alpha3 starter"; // the bundled set's folders (UpstreamEngine.installReshadeStarter)

        // ---- the preset: ReShade's own .ini format ----

        struct Preset
        {
            bool mOn = true;
            std::vector<std::string> mTechniques; // the ones turned on, in the order they run: "Name@File.fx"
            std::vector<std::string> mSorting; // every technique's place in the list (TechniqueSorting)
            std::vector<std::pair<std::string, std::string>> mDefines; // PreprocessorDefinitions
            std::map<std::string, std::map<std::string, std::string>> mValues; // [File.fx] Name=v,v,v
            std::vector<std::pair<std::string, std::string>> mOther; // top level keys kept as they were
        };

        std::vector<std::string> splitList(const std::string& text)
        {
            std::vector<std::string> out;
            std::string item;
            std::istringstream stream(text);
            while (std::getline(stream, item, ','))
            {
                const size_t a = item.find_first_not_of(" \t");
                const size_t b = item.find_last_not_of(" \t\r");
                if (a != std::string::npos)
                    out.push_back(item.substr(a, b - a + 1));
            }
            return out;
        }

        std::string joinList(const std::vector<std::string>& items)
        {
            std::string out;
            for (const std::string& item : items)
                out += (out.empty() ? "" : ",") + item;
            return out;
        }

        Preset readPreset(const fs::path& path)
        {
            Preset preset;
            std::ifstream file(path);
            std::string line, section;
            while (std::getline(file, line))
            {
                if (!line.empty() && line.back() == '\r')
                    line.pop_back();
                if (line.empty() || line[0] == ';' || line[0] == '#')
                    continue;
                if (line[0] == '[')
                {
                    section = line.substr(1, line.find(']') - 1);
                    continue;
                }
                const size_t equals = line.find('=');
                if (equals == std::string::npos)
                    continue;
                const std::string key = line.substr(0, equals);
                const std::string value = line.substr(equals + 1);
                if (!section.empty())
                    preset.mValues[section][key] = value;
                else if (key == "Techniques")
                    preset.mTechniques = splitList(value);
                else if (key == "TechniqueSorting")
                    preset.mSorting = splitList(value);
                else if (key == "PreprocessorDefinitions")
                {
                    for (const std::string& define : splitList(value))
                    {
                        const size_t at = define.find('=');
                        if (at == std::string::npos)
                            preset.mDefines.emplace_back(define, "1");
                        else
                            preset.mDefines.emplace_back(define.substr(0, at), define.substr(at + 1));
                    }
                }
                else if (key == "Alpha3EffectsOn")
                    preset.mOn = value != "0";
                else
                    preset.mOther.emplace_back(key, value);
            }
            return preset;
        }

        void writePreset(const fs::path& path, const Preset& preset)
        {
            std::error_code error;
            fs::create_directories(path.parent_path(), error);
            const fs::path temporary = path.string() + ".tmp";
            {
                std::ofstream file(temporary, std::ios::trunc);
                file << "Alpha3EffectsOn=" << (preset.mOn ? 1 : 0) << "\n";
                for (const auto& [key, value] : preset.mOther)
                    file << key << "=" << value << "\n";
                std::vector<std::string> defines;
                for (const auto& [name, value] : preset.mDefines)
                    defines.push_back(name + "=" + value);
                file << "PreprocessorDefinitions=" << joinList(defines) << "\n";
                file << "Techniques=" << joinList(preset.mTechniques) << "\n";
                file << "TechniqueSorting=" << joinList(preset.mSorting) << "\n";
                for (const auto& [section, values] : preset.mValues)
                {
                    if (values.empty())
                        continue;
                    file << "\n[" << section << "]\n";
                    for (const auto& [key, value] : values)
                        file << key << "=" << value << "\n";
                }
            }
            fs::rename(temporary, path, error);
        }

        // ---- what the overlay and the render thread share ----

        struct FileInfo
        {
            std::string mName; // File.fx
            fs::path mPath;
            bool mStarter = false;
            std::vector<std::string> mTechniques; // as found in the text (the compiled module is the truth)
        };

        struct Shared
        {
            std::mutex mMutex;
            bool mLoaded = false;
            Preset mPreset;
            uint64_t mStructure = 1; // techniques, defines, a rescan: the files to compile again
            uint64_t mValues = 1; // the uniforms' values
            std::vector<FileInfo> mFiles; // what Shaders/ has
            std::map<std::string, std::string> mStatus; // File.fx: "compiling", "ok" or the errors
            std::map<std::string, std::string> mEffectJson; // File.fx: its uniforms and #defines (the report's part)
        };

        Shared& shared()
        {
            static Shared sShared;
            return sShared;
        }

        const fs::path& presetPath()
        {
            static const fs::path sPath = sRoot / "ReShadePreset.ini";
            return sPath;
        }

        // the .fx files under Shaders/ (the bundled set last, so a user's copy of a file of it wins), and the techniques
        // their text declares
        std::vector<FileInfo> scanFiles()
        {
            std::vector<FileInfo> files;
            std::error_code error;
            const fs::path shaders = sRoot / "Shaders";
            if (!fs::is_directory(shaders, error))
                return files;
            static const std::regex technique(R"(^\s*technique\s+([A-Za-z_][A-Za-z0-9_]*))");
            for (fs::recursive_directory_iterator it(shaders, fs::directory_options::skip_permission_denied, error), end;
                 it != end; it.increment(error))
            {
                if (error)
                    break;
                if (!it->is_regular_file(error) || it->path().extension() != ".fx")
                    continue;
                FileInfo info;
                info.mName = it->path().filename().string();
                info.mPath = it->path();
                info.mStarter = it->path().string().find(sStarter) != std::string::npos;
                std::ifstream text(it->path());
                std::string line;
                std::smatch match;
                while (std::getline(text, line))
                    if (std::regex_search(line, match, technique))
                        info.mTechniques.push_back(match[1]);
                files.push_back(std::move(info));
            }
            std::stable_sort(files.begin(), files.end(), [](const FileInfo& a, const FileInfo& b) {
                return a.mStarter != b.mStarter ? !a.mStarter : a.mName < b.mName;
            });
            // one file a name (ReShade finds effects by file name)
            std::set<std::string> seen;
            files.erase(std::remove_if(files.begin(), files.end(),
                            [&](const FileInfo& info) { return !seen.insert(info.mName).second; }),
                files.end());
            return files;
        }

        // with sMutex held
        void ensureLoaded(Shared& state)
        {
            if (state.mLoaded)
                return;
            state.mLoaded = true;
            state.mPreset = readPreset(presetPath());
            state.mFiles = scanFiles();
        }

        std::string jsonString(const std::string& text)
        {
            std::string out = "\"";
            for (const unsigned char c : text)
            {
                switch (c)
                {
                    case '"':
                        out += "\\\"";
                        break;
                    case '\\':
                        out += "\\\\";
                        break;
                    case '\n':
                        out += "\\n";
                        break;
                    case '\r':
                        break;
                    case '\t':
                        out += "\\t";
                        break;
                    default:
                        if (c < 0x20)
                        {
                            char buffer[8];
                            std::snprintf(buffer, sizeof(buffer), "\\u%04x", c);
                            out += buffer;
                        }
                        else
                            out += static_cast<char>(c);
                }
            }
            return out + "\"";
        }

        std::string fileOf(const std::string& technique)
        {
            const size_t at = technique.find('@');
            return at == std::string::npos ? std::string() : technique.substr(at + 1);
        }

        std::string nameOf(const std::string& technique)
        {
            return technique.substr(0, technique.find('@'));
        }

        // ---- formats ----

        VkFormat vkFormat(reshadefx::texture_format format)
        {
            using F = reshadefx::texture_format;
            switch (format)
            {
                case F::r8:
                    return VK_FORMAT_R8_UNORM;
                case F::r16f:
                    return VK_FORMAT_R16_SFLOAT;
                case F::r16:
                    return VK_FORMAT_R16_UNORM;
                case F::r32f:
                    return VK_FORMAT_R32_SFLOAT;
                case F::r32u:
                    return VK_FORMAT_R32_UINT;
                case F::r32i:
                    return VK_FORMAT_R32_SINT;
                case F::rg8:
                    return VK_FORMAT_R8G8_UNORM;
                case F::rg16f:
                    return VK_FORMAT_R16G16_SFLOAT;
                case F::rg16:
                    return VK_FORMAT_R16G16_UNORM;
                case F::rg32f:
                    return VK_FORMAT_R32G32_SFLOAT;
                case F::rgba16f:
                    return VK_FORMAT_R16G16B16A16_SFLOAT;
                case F::rgba16:
                    return VK_FORMAT_R16G16B16A16_UNORM;
                case F::rgba32f:
                    return VK_FORMAT_R32G32B32A32_SFLOAT;
                case F::rgba32u:
                    return VK_FORMAT_R32G32B32A32_UINT;
                case F::rgba32i:
                    return VK_FORMAT_R32G32B32A32_SINT;
                case F::rgb10a2:
                    return VK_FORMAT_A2B10G10R10_UNORM_PACK32;
                case F::rg11b10f:
                    return VK_FORMAT_B10G11R11_UFLOAT_PACK32;
                case F::rgba8:
                default:
                    return VK_FORMAT_R8G8B8A8_UNORM;
            }
        }

        VkFormat srgbOf(VkFormat format)
        {
            switch (format)
            {
                case VK_FORMAT_R8G8B8A8_UNORM:
                    return VK_FORMAT_R8G8B8A8_SRGB;
                case VK_FORMAT_B8G8R8A8_UNORM:
                    return VK_FORMAT_B8G8R8A8_SRGB;
                default:
                    return VK_FORMAT_UNDEFINED;
            }
        }

        uint32_t bitDepth(VkFormat format)
        {
            switch (format)
            {
                case VK_FORMAT_A2B10G10R10_UNORM_PACK32:
                case VK_FORMAT_A2R10G10B10_UNORM_PACK32:
                    return 10;
                case VK_FORMAT_R16G16B16A16_SFLOAT:
                    return 16;
                default:
                    return 8;
            }
        }

        VkBlendFactor blendFactor(reshadefx::blend_factor factor)
        {
            using B = reshadefx::blend_factor;
            switch (factor)
            {
                case B::zero:
                    return VK_BLEND_FACTOR_ZERO;
                case B::one:
                    return VK_BLEND_FACTOR_ONE;
                case B::source_color:
                    return VK_BLEND_FACTOR_SRC_COLOR;
                case B::one_minus_source_color:
                    return VK_BLEND_FACTOR_ONE_MINUS_SRC_COLOR;
                case B::dest_color:
                    return VK_BLEND_FACTOR_DST_COLOR;
                case B::one_minus_dest_color:
                    return VK_BLEND_FACTOR_ONE_MINUS_DST_COLOR;
                case B::source_alpha:
                    return VK_BLEND_FACTOR_SRC_ALPHA;
                case B::one_minus_source_alpha:
                    return VK_BLEND_FACTOR_ONE_MINUS_SRC_ALPHA;
                case B::dest_alpha:
                    return VK_BLEND_FACTOR_DST_ALPHA;
                case B::one_minus_dest_alpha:
                    return VK_BLEND_FACTOR_ONE_MINUS_DST_ALPHA;
            }
            return VK_BLEND_FACTOR_ONE;
        }

        VkBlendOp blendOp(reshadefx::blend_op op)
        {
            using O = reshadefx::blend_op;
            switch (op)
            {
                case O::subtract:
                    return VK_BLEND_OP_SUBTRACT;
                case O::reverse_subtract:
                    return VK_BLEND_OP_REVERSE_SUBTRACT;
                case O::min:
                    return VK_BLEND_OP_MIN;
                case O::max:
                    return VK_BLEND_OP_MAX;
                case O::add:
                default:
                    return VK_BLEND_OP_ADD;
            }
        }

        VkPrimitiveTopology topology(reshadefx::primitive_topology topology)
        {
            using T = reshadefx::primitive_topology;
            switch (topology)
            {
                case T::point_list:
                    return VK_PRIMITIVE_TOPOLOGY_POINT_LIST;
                case T::line_list:
                    return VK_PRIMITIVE_TOPOLOGY_LINE_LIST;
                case T::line_strip:
                    return VK_PRIMITIVE_TOPOLOGY_LINE_STRIP;
                case T::triangle_strip:
                    return VK_PRIMITIVE_TOPOLOGY_TRIANGLE_STRIP;
                case T::triangle_list:
                default:
                    return VK_PRIMITIVE_TOPOLOGY_TRIANGLE_LIST;
            }
        }

        VkSamplerAddressMode addressMode(reshadefx::texture_address_mode mode)
        {
            using A = reshadefx::texture_address_mode;
            switch (mode)
            {
                case A::wrap:
                    return VK_SAMPLER_ADDRESS_MODE_REPEAT;
                case A::mirror:
                    return VK_SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT;
                case A::border:
                    return VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER;
                case A::clamp:
                default:
                    return VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
            }
        }

        // ---- compiling (on a thread of its own) ----

        struct CompileJob
        {
            // in
            std::string mName;
            fs::path mPath;
            std::vector<fs::path> mIncludes;
            std::vector<std::pair<std::string, std::string>> mMacros;
            bool mSpirv10 = false; // the device is Vulkan 1.0: the code says SPIR-V 1.0 (reshadefx writes 1.3)
            // out
            std::atomic<bool> mDone{ false };
            bool mOk = false;
            std::string mErrors;
            reshadefx::effect_module mModule;
            std::vector<std::pair<std::vector<uint32_t>, std::vector<uint32_t>>> mCode; // a pass's vertex, pixel code
            std::vector<std::vector<int>> mPassCode; // [technique][pass]: index in mCode, -1 for a compute pass
            std::vector<std::pair<std::string, std::string>> mUsedDefines;
        };

        void compile(CompileJob& job)
        {
            reshadefx::preprocessor preprocessor;
            for (const auto& [name, value] : job.mMacros)
                preprocessor.add_macro_definition(name, value);
            for (const fs::path& include : job.mIncludes)
                preprocessor.add_include_path(include);
            // what older ReShade had, as its runtime and vkShade define it
            preprocessor.append_string("#define tex2Doffset(s, coords, offset) tex2D(s, coords, offset)\n"
                                       "#define tex2Dlodoffset(s, coords, offset) tex2Dlod(s, coords, offset)\n"
                                       "#define tex2Dgather(s, t, c) tex2Dgather##c(s, t)\n"
                                       "#define tex2Dgatheroffset(s, t, o, c) tex2Dgather##c(s, t, o)\n"
                                       "#define tex2Dgather0 tex2DgatherR\n"
                                       "#define tex2Dgather1 tex2DgatherG\n"
                                       "#define tex2Dgather2 tex2DgatherB\n"
                                       "#define tex2Dgather3 tex2DgatherA\n");
            if (!preprocessor.append_file(job.mPath))
            {
                job.mErrors = preprocessor.errors();
                return;
            }
            job.mUsedDefines = preprocessor.used_macro_definitions();
            std::unique_ptr<reshadefx::codegen> codegen(reshadefx::create_codegen_spirv(true /* Vulkan */,
                false /* debug info */, false /* uniforms as spec constants */, false /* 16 bit */,
                true /* flip y: ReShade's top left texture origin */));
            reshadefx::parser parser;
            if (!parser.parse(preprocessor.output(), codegen.get()))
            {
                job.mErrors = preprocessor.errors() + parser.errors();
                return;
            }
            job.mModule = codegen->module();
            const auto spirv = [&](const std::string& entry, std::vector<uint32_t>& out) {
                std::string binary, assembly, errors;
                if (!codegen->assemble_code_for_entry_point(entry, binary, assembly, errors))
                {
                    job.mErrors += errors;
                    return false;
                }
                out.resize(binary.size() / 4);
                std::memcpy(out.data(), binary.data(), out.size() * 4);
                if (job.mSpirv10 && out.size() > 1)
                    out[1] = 0x00010000;
                return true;
            };
            for (const reshadefx::technique& technique : job.mModule.techniques)
            {
                job.mPassCode.emplace_back();
                for (const reshadefx::pass& pass : technique.passes)
                {
                    if (!pass.cs_entry_point.empty() || pass.ps_entry_point.empty())
                    {
                        job.mPassCode.back().push_back(-1);
                        continue;
                    }
                    std::vector<uint32_t> vertex, pixel;
                    if (!spirv(pass.vs_entry_point, vertex) || !spirv(pass.ps_entry_point, pixel))
                        return;
                    job.mPassCode.back().push_back(static_cast<int>(job.mCode.size()));
                    job.mCode.emplace_back(std::move(vertex), std::move(pixel));
                }
            }
            job.mErrors += parser.errors(); // warnings
            job.mOk = true;
        }

        // ---- uniforms ----

        enum class Source
        {
            None,
            FrameTime,
            FrameCount,
            Date,
            Timer,
            PingPong,
            Random,
            DepthReady,
            Zero,
        };

        struct UniformSlot
        {
            std::string mName;
            uint32_t mOffset = 0;
            uint32_t mSize = 0;
            reshadefx::type mType;
            Source mSource = Source::None;
            std::vector<uint8_t> mDefault; // the initializer (or zeros)
            float mMin = 0.f, mMax = 0.f, mStep = 0.f, mSmoothing = 0.f; // annotations of the sources that take them
            float mPing[2] = { 0.f, 1.f }; // pingpong's state
        };

        // a preset value ("0.5,1,2") into the uniform's bytes
        bool parseValue(const UniformSlot& slot, const std::string& text, std::vector<uint8_t>& out)
        {
            const std::vector<std::string> parts = splitList(text);
            const unsigned components = slot.mType.components();
            if (parts.empty() || components == 0 || slot.mSize < components * 4)
                return false;
            out = slot.mDefault;
            out.resize(slot.mSize, 0);
            for (unsigned i = 0; i < components && i < parts.size(); ++i)
            {
                uint8_t* at = out.data() + i * 4;
                try
                {
                    if (slot.mType.is_floating_point())
                    {
                        const float value = std::stof(parts[i]);
                        std::memcpy(at, &value, 4);
                    }
                    else if (slot.mType.is_boolean())
                    {
                        const uint32_t value = (parts[i] == "true" || std::stof(parts[i]) != 0.f) ? 1u : 0u;
                        std::memcpy(at, &value, 4);
                    }
                    else if (slot.mType.is_signed())
                    {
                        const int32_t value = static_cast<int32_t>(std::lround(std::stod(parts[i])));
                        std::memcpy(at, &value, 4);
                    }
                    else
                    {
                        const uint32_t value = static_cast<uint32_t>(std::max(0l, std::lround(std::stod(parts[i]))));
                        std::memcpy(at, &value, 4);
                    }
                }
                catch (const std::exception&)
                {
                    return false;
                }
            }
            return true;
        }

        std::string formatValue(const reshadefx::type& type, const uint8_t* data, unsigned components)
        {
            std::ostringstream out;
            for (unsigned i = 0; i < components; ++i)
            {
                if (i > 0)
                    out << ",";
                if (type.is_floating_point())
                {
                    float value;
                    std::memcpy(&value, data + i * 4, 4);
                    out << value;
                }
                else if (type.is_signed())
                {
                    int32_t value;
                    std::memcpy(&value, data + i * 4, 4);
                    out << value;
                }
                else
                {
                    uint32_t value;
                    std::memcpy(&value, data + i * 4, 4);
                    out << value;
                }
            }
            return out.str();
        }

        float annotationNumber(const reshadefx::annotation& annotation)
        {
            if (annotation.type.is_floating_point())
                return annotation.value.as_float[0];
            if (annotation.type.is_signed())
                return static_cast<float>(annotation.value.as_int[0]);
            return static_cast<float>(annotation.value.as_uint[0]);
        }

        const reshadefx::annotation* findAnnotation(
            const std::vector<reshadefx::annotation>& annotations, const char* name)
        {
            for (const reshadefx::annotation& annotation : annotations)
                if (annotation.name == name)
                    return &annotation;
            return nullptr;
        }

        bool isString(const reshadefx::type& type)
        {
            return type.base == reshadefx::type::t_string;
        }

        // ---- Vulkan helpers ----

        struct Texture
        {
            VkImage mImage = VK_NULL_HANDLE;
            VmaAllocation_T* mMemory = nullptr;
            VkFormat mFormat = VK_FORMAT_UNDEFINED;
            VkImageView mView = VK_NULL_HANDLE; // all mips
            VkImageView mSrgbView = VK_NULL_HANDLE; // where the format has an sRGB twin
            VkImageView mTarget = VK_NULL_HANDLE; // mip 0, for rendering
            VkImageView mSrgbTarget = VK_NULL_HANDLE;
            uint32_t mWidth = 1, mHeight = 1, mMips = 1;
            VkImageLayout mLayout = VK_IMAGE_LAYOUT_UNDEFINED;
            bool mMipsBlittable = false;
        };

        void barrier(VkCommandBuffer commands, VkImage image, VkImageLayout from, VkImageLayout to, uint32_t mips = 1,
            VkImageAspectFlags aspect = VK_IMAGE_ASPECT_COLOR_BIT, uint32_t baseMip = 0)
        {
            VkImageMemoryBarrier b{ VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER };
            b.oldLayout = from;
            b.newLayout = to;
            // broad on purpose: a handful of these a pass
            b.srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
            b.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_READ_BIT
                | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_TRANSFER_READ_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
            b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            b.image = image;
            b.subresourceRange = { aspect, baseMip, mips, 0, 1 };
            const VkPipelineStageFlags stages = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT
                | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT
                | VK_PIPELINE_STAGE_TRANSFER_BIT;
            vkCmdPipelineBarrier(commands, stages, stages, 0, 0, nullptr, 0, nullptr, 1, &b);
        }

        void toLayout(VkCommandBuffer commands, Texture& texture, VkImageLayout layout)
        {
            if (texture.mLayout == layout)
                return;
            barrier(commands, texture.mImage, texture.mLayout, layout, texture.mMips);
            texture.mLayout = layout;
        }

        // ---- an effect file, compiled and made ----

        struct Pass
        {
            const reshadefx::pass* mInfo = nullptr;
            VkDescriptorSetLayout mSetLayout = VK_NULL_HANDLE;
            VkPipelineLayout mLayout = VK_NULL_HANDLE;
            VkPipeline mPipeline = VK_NULL_HANDLE;
            std::vector<VkFormat> mFormats; // the attachments' (empty: the back buffer)
            bool mBackBuffer = false;
            bool mBackBufferSrgb = false;
            bool mOverwrites = false; // draws every pixel of the back buffer as it is (no blending, all channels)
        };

        struct Technique
        {
            std::string mName;
            std::vector<Pass> mPasses;
            bool mUsable = true;
        };

        struct Effect
        {
            std::string mName;
            std::string mKey; // what it was compiled for (size, format, macros): compiled again when it differs
            bool mReady = false;
            reshadefx::effect_module mModule;
            std::map<std::string, Texture> mTextures; // by unique name (not the COLOR and DEPTH ones)
            std::vector<VkSampler> mSamplers;
            std::vector<std::string> mSamplerTexture; // per sampler: the texture's unique name
            std::vector<std::string> mSamplerSemantic; // per sampler: "COLOR", "DEPTH" or ""
            std::vector<bool> mSamplerSrgb;
            VkDescriptorSetLayout mUniformLayout = VK_NULL_HANDLE;
            VkBuffer mUniformBuffer = VK_NULL_HANDLE;
            VmaAllocation_T* mUniformMemory = nullptr;
            uint8_t* mUniformMapped = nullptr;
            VkDeviceSize mUniformStride = 0; // a frame slot's part
            std::vector<uint8_t> mUniforms; // this frame's block
            std::vector<UniformSlot> mSlots;
            std::vector<Technique> mTechniques;
            std::vector<VkShaderModule> mModules;
        };
    }

    struct Effects::Impl
    {
        Context& mContext;
        Device& mDevice;
        uint64_t mStructure = 0, mValues = 0;
        Preset mPreset; // the render thread's copy
        std::map<std::string, std::unique_ptr<Effect>> mEffects; // File.fx
        std::map<std::string, std::shared_ptr<CompileJob>> mJobs;
        std::map<std::string, std::string> mFailedKeys; // File.fx: the key it failed with (not tried again)
        std::vector<FileInfo> mFiles;
        VkExtent2D mExtent{ 0, 0 };
        VkFormat mFormat = VK_FORMAT_UNDEFINED;
        bool mDepthReversed = true;
        Texture mBack[2]; // the back buffer's two images, taking turns
        Texture mDummyDepth;
        std::map<std::string, VkRenderPass> mPasses;
        std::map<std::tuple<VkRenderPass, std::vector<VkImageView>, uint32_t, uint32_t>, VkFramebuffer> mFramebuffers;
        std::chrono::steady_clock::time_point mStart = std::chrono::steady_clock::now();
        std::chrono::steady_clock::time_point mLast = mStart;
        uint64_t mFrames = 0;
        std::mt19937 mRandom{ 12345 };
        bool mActive = false;

        explicit Impl(Context& context)
            : mContext(context)
            , mDevice(context.device())
        {
        }

        ~Impl()
        {
            vkDeviceWaitIdle(mDevice.mDevice);
            for (auto& [name, effect] : mEffects)
                destroyEffect(*effect);
            destroyTexture(mBack[0]);
            destroyTexture(mBack[1]);
            destroyTexture(mDummyDepth);
            for (auto& [key, framebuffer] : mFramebuffers)
                vkDestroyFramebuffer(mDevice.mDevice, framebuffer, nullptr);
            for (auto& [key, pass] : mPasses)
                vkDestroyRenderPass(mDevice.mDevice, pass, nullptr);
        }

        // ---- objects ----

        bool supports(VkFormat format, VkFormatFeatureFlags features) const
        {
            VkFormatProperties properties{};
            vkGetPhysicalDeviceFormatProperties(mDevice.mPhysical, format, &properties);
            return (properties.optimalTilingFeatures & features) == features;
        }

        // a format the GPU can render to and sample, the asked one if it can
        VkFormat targetFormat(VkFormat format) const
        {
            const VkFormatFeatureFlags needed
                = VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT;
            if (supports(format, needed))
                return format;
            switch (format)
            {
                case VK_FORMAT_R16_UNORM:
                    return VK_FORMAT_R16_SFLOAT;
                case VK_FORMAT_R16G16_UNORM:
                    return VK_FORMAT_R16G16_SFLOAT;
                case VK_FORMAT_R16G16B16A16_UNORM:
                case VK_FORMAT_B10G11R11_UFLOAT_PACK32:
                case VK_FORMAT_R32G32B32A32_SFLOAT:
                    return VK_FORMAT_R16G16B16A16_SFLOAT;
                case VK_FORMAT_R32G32_SFLOAT:
                    return VK_FORMAT_R16G16_SFLOAT;
                default:
                    return VK_FORMAT_R8G8B8A8_UNORM;
            }
        }

        bool makeTexture(Texture& texture, VkFormat format, uint32_t width, uint32_t height, uint32_t mips,
            VkImageUsageFlags usage)
        {
            texture.mFormat = format;
            texture.mWidth = std::max(width, 1u);
            texture.mHeight = std::max(height, 1u);
            const uint32_t most = 1 + static_cast<uint32_t>(std::floor(std::log2(std::max(texture.mWidth, texture.mHeight))));
            texture.mMips = std::clamp(mips, 1u, most);
            const VkFormat srgb = srgbOf(format);
            VkImageCreateInfo info{ VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO };
            info.flags = srgb != VK_FORMAT_UNDEFINED ? VK_IMAGE_CREATE_MUTABLE_FORMAT_BIT : 0;
            info.imageType = VK_IMAGE_TYPE_2D;
            info.format = format;
            info.extent = { texture.mWidth, texture.mHeight, 1 };
            info.mipLevels = texture.mMips;
            info.arrayLayers = 1;
            info.samples = VK_SAMPLE_COUNT_1_BIT;
            info.tiling = VK_IMAGE_TILING_OPTIMAL;
            info.usage = usage;
            VmaAllocationCreateInfo memory{};
            memory.usage = VMA_MEMORY_USAGE_GPU_ONLY;
            if (const VkResult result
                = vmaCreateImage(mDevice.mAllocator, &info, &memory, &texture.mImage, &texture.mMemory, nullptr);
                result != VK_SUCCESS)
            {
                Log(Debug::Error) << "Vulkan effects: image " << texture.mWidth << "x" << texture.mHeight << " format "
                                  << format << ": " << resultName(result);
                texture.mImage = VK_NULL_HANDLE;
                return false;
            }
            const auto view = [&](VkFormat viewFormat, uint32_t levels) {
                VkImageViewCreateInfo create{ VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO };
                create.image = texture.mImage;
                create.viewType = VK_IMAGE_VIEW_TYPE_2D;
                create.format = viewFormat;
                create.subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, levels, 0, 1 };
                VkImageView handle = VK_NULL_HANDLE;
                vkCreateImageView(mDevice.mDevice, &create, nullptr, &handle);
                return handle;
            };
            texture.mView = view(format, texture.mMips);
            texture.mTarget = (usage & VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT) ? view(format, 1) : VK_NULL_HANDLE;
            if (srgb != VK_FORMAT_UNDEFINED && supports(srgb, VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT))
            {
                texture.mSrgbView = view(srgb, texture.mMips);
                if ((usage & VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
                    && supports(srgb, VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT))
                    texture.mSrgbTarget = view(srgb, 1);
            }
            texture.mMipsBlittable = texture.mMips > 1
                && supports(format,
                    VK_FORMAT_FEATURE_BLIT_SRC_BIT | VK_FORMAT_FEATURE_BLIT_DST_BIT
                        | VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT);
            texture.mLayout = VK_IMAGE_LAYOUT_UNDEFINED;
            return true;
        }

        void destroyTexture(Texture& texture) { destroyTextureOn(mDevice, texture); }

        static void destroyTextureOn(Device& device, Texture& texture)
        {
            for (VkImageView view : { texture.mView, texture.mSrgbView, texture.mTarget, texture.mSrgbTarget })
                if (view != VK_NULL_HANDLE)
                    vkDestroyImageView(device.mDevice, view, nullptr);
            if (texture.mImage != VK_NULL_HANDLE)
                vmaDestroyImage(device.mAllocator, texture.mImage, texture.mMemory);
            texture = Texture{};
        }

        // a texture let go once the frames using it are done (the device outlives this, the context runs it later)
        void destroyLater(Texture texture)
        {
            Device* device = &mDevice;
            mContext.releaseLater([device, texture]() mutable { destroyTextureOn(*device, texture); });
        }

        void clear(VkCommandBuffer commands, Texture& texture, float value = 0.f)
        {
            toLayout(commands, texture, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            const VkClearColorValue color = { { value, value, value, value } };
            const VkImageSubresourceRange range{ VK_IMAGE_ASPECT_COLOR_BIT, 0, texture.mMips, 0, 1 };
            vkCmdClearColorImage(commands, texture.mImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &color, 1, &range);
        }

        void generateMips(VkCommandBuffer commands, Texture& texture)
        {
            if (!texture.mMipsBlittable)
                return;
            toLayout(commands, texture, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            int32_t width = texture.mWidth, height = texture.mHeight;
            for (uint32_t mip = 1; mip < texture.mMips; ++mip)
            {
                barrier(commands, texture.mImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
                    VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, 1, VK_IMAGE_ASPECT_COLOR_BIT, mip - 1);
                VkImageBlit blit{};
                blit.srcSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, mip - 1, 0, 1 };
                blit.srcOffsets[1] = { width, height, 1 };
                width = std::max(width / 2, 1);
                height = std::max(height / 2, 1);
                blit.dstSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, mip, 0, 1 };
                blit.dstOffsets[1] = { width, height, 1 };
                vkCmdBlitImage(commands, texture.mImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, texture.mImage,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit, VK_FILTER_LINEAR);
                barrier(commands, texture.mImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, VK_IMAGE_ASPECT_COLOR_BIT, mip - 1);
            }
        }

        // an image file (Textures/, any folder under it) into the texture, scaled to its size
        bool loadImage(VkCommandBuffer commands, Texture& texture, const std::string& source)
        {
            std::error_code error;
            fs::path found;
            const fs::path textures = sRoot / "Textures";
            if (fs::is_regular_file(textures / source, error))
                found = textures / source;
            else
                for (fs::recursive_directory_iterator it(textures, fs::directory_options::skip_permission_denied, error), end;
                     it != end; it.increment(error))
                {
                    if (error)
                        break;
                    if (it->is_regular_file(error) && it->path().filename() == fs::path(source).filename())
                    {
                        found = it->path();
                        break;
                    }
                }
            if (found.empty())
            {
                Log(Debug::Warning) << "Vulkan effects: texture '" << source << "' not found in " << textures.string();
                return false;
            }
            osg::ref_ptr<osg::Image> image = osgDB::readImageFile(found.string());
            if (!image || image->s() <= 0 || image->t() <= 0 || image->getDataType() != GL_UNSIGNED_BYTE)
            {
                Log(Debug::Warning) << "Vulkan effects: could not read " << found.string();
                return false;
            }
            const int channels = osg::Image::computeNumComponents(image->getPixelFormat());
            const GLenum pixelFormat = image->getPixelFormat();
            const uint32_t width = texture.mWidth, height = texture.mHeight;
            std::vector<uint8_t> rgba(size_t(width) * height * 4);
            const bool flip = image->getOrigin() != osg::Image::TOP_LEFT; // OSG's readers keep rows bottom up
            for (uint32_t y = 0; y < height; ++y)
            {
                int sy = static_cast<int>((y + 0.5) * image->t() / height);
                sy = std::clamp(sy, 0, image->t() - 1);
                if (flip)
                    sy = image->t() - 1 - sy;
                for (uint32_t x = 0; x < width; ++x)
                {
                    const int sx = std::clamp(static_cast<int>((x + 0.5) * image->s() / width), 0, image->s() - 1);
                    const uint8_t* in = image->data(sx, sy);
                    uint8_t* out = &rgba[(size_t(y) * width + x) * 4];
                    if (channels >= 3)
                    {
                        const bool bgr = pixelFormat == GL_BGR || pixelFormat == GL_BGRA;
                        out[0] = in[bgr ? 2 : 0];
                        out[1] = in[1];
                        out[2] = in[bgr ? 0 : 2];
                        out[3] = channels == 4 ? in[3] : 255;
                    }
                    else
                    {
                        out[0] = out[1] = out[2] = in[0];
                        out[3] = channels == 2 ? in[1] : 255;
                    }
                }
            }
            if (texture.mFormat != VK_FORMAT_R8G8B8A8_UNORM)
            {
                Log(Debug::Warning) << "Vulkan effects: texture '" << source << "' is not RGBA8 in the effect: left black";
                return false;
            }
            VkBufferCreateInfo bufferInfo{ VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO };
            bufferInfo.size = rgba.size();
            bufferInfo.usage = VK_BUFFER_USAGE_TRANSFER_SRC_BIT;
            VmaAllocationCreateInfo memory{};
            memory.usage = VMA_MEMORY_USAGE_CPU_ONLY;
            memory.flags = VMA_ALLOCATION_CREATE_MAPPED_BIT;
            VkBuffer staging = VK_NULL_HANDLE;
            VmaAllocation_T* stagingMemory = nullptr;
            VmaAllocationInfo mapped{};
            if (vmaCreateBuffer(mDevice.mAllocator, &bufferInfo, &memory, &staging, &stagingMemory, &mapped) != VK_SUCCESS)
                return false;
            std::memcpy(mapped.pMappedData, rgba.data(), rgba.size());
            vmaFlushAllocation(mDevice.mAllocator, stagingMemory, 0, VK_WHOLE_SIZE);
            toLayout(commands, texture, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            VkBufferImageCopy copy{};
            copy.imageSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
            copy.imageExtent = { width, height, 1 };
            vkCmdCopyBufferToImage(
                commands, staging, texture.mImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &copy);
            generateMips(commands, texture);
            VmaAllocator allocator = mDevice.mAllocator;
            mContext.releaseLater([allocator, staging, stagingMemory] {
                vmaDestroyBuffer(allocator, staging, stagingMemory);
            });
            return true;
        }

        VkRenderPass renderPass(const std::vector<VkFormat>& formats, const std::vector<VkAttachmentLoadOp>& loads)
        {
            std::string key;
            for (size_t i = 0; i < formats.size(); ++i)
                key += std::to_string(formats[i]) + ":" + std::to_string(loads[i]) + ",";
            auto found = mPasses.find(key);
            if (found != mPasses.end())
                return found->second;
            std::vector<VkAttachmentDescription> attachments(formats.size());
            std::vector<VkAttachmentReference> references(formats.size());
            for (size_t i = 0; i < formats.size(); ++i)
            {
                attachments[i].format = formats[i];
                attachments[i].samples = VK_SAMPLE_COUNT_1_BIT;
                attachments[i].loadOp = loads[i];
                attachments[i].storeOp = VK_ATTACHMENT_STORE_OP_STORE;
                attachments[i].stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
                attachments[i].stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
                attachments[i].initialLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
                attachments[i].finalLayout = VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL;
                references[i] = { static_cast<uint32_t>(i), VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL };
            }
            VkSubpassDescription subpass{};
            subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
            subpass.colorAttachmentCount = static_cast<uint32_t>(references.size());
            subpass.pColorAttachments = references.data();
            VkRenderPassCreateInfo info{ VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO };
            info.attachmentCount = static_cast<uint32_t>(attachments.size());
            info.pAttachments = attachments.data();
            info.subpassCount = 1;
            info.pSubpasses = &subpass;
            VkRenderPass pass = VK_NULL_HANDLE;
            if (const VkResult result = vkCreateRenderPass(mDevice.mDevice, &info, nullptr, &pass); result != VK_SUCCESS)
                Log(Debug::Error) << "Vulkan effects: vkCreateRenderPass: " << resultName(result);
            mPasses[key] = pass;
            return pass;
        }

        VkFramebuffer framebuffer(VkRenderPass pass, const std::vector<VkImageView>& views, uint32_t width, uint32_t height)
        {
            const auto key = std::make_tuple(pass, views, width, height);
            auto found = mFramebuffers.find(key);
            if (found != mFramebuffers.end())
                return found->second;
            VkFramebufferCreateInfo info{ VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO };
            info.renderPass = pass;
            info.attachmentCount = static_cast<uint32_t>(views.size());
            info.pAttachments = views.data();
            info.width = width;
            info.height = height;
            info.layers = 1;
            VkFramebuffer handle = VK_NULL_HANDLE;
            vkCreateFramebuffer(mDevice.mDevice, &info, nullptr, &handle);
            mFramebuffers[key] = handle;
            return handle;
        }

        // framebuffers on views that are going away (an effect or the back buffer made again)
        void dropFramebuffers()
        {
            std::vector<VkFramebuffer> old;
            for (auto& [key, handle] : mFramebuffers)
                old.push_back(handle);
            mFramebuffers.clear();
            VkDevice device = mDevice.mDevice;
            mContext.releaseLater([device, old] {
                for (VkFramebuffer framebuffer : old)
                    vkDestroyFramebuffer(device, framebuffer, nullptr);
            });
        }

        void destroyEffect(Effect& effect) { destroyEffectOn(mDevice, effect); }

        static void destroyEffectOn(Device& owner, Effect& effect)
        {
            VkDevice device = owner.mDevice;
            for (Technique& technique : effect.mTechniques)
                for (Pass& pass : technique.mPasses)
                {
                    if (pass.mPipeline != VK_NULL_HANDLE)
                        vkDestroyPipeline(device, pass.mPipeline, nullptr);
                    if (pass.mLayout != VK_NULL_HANDLE)
                        vkDestroyPipelineLayout(device, pass.mLayout, nullptr);
                    if (pass.mSetLayout != VK_NULL_HANDLE)
                        vkDestroyDescriptorSetLayout(device, pass.mSetLayout, nullptr);
                }
            for (VkShaderModule module : effect.mModules)
                vkDestroyShaderModule(device, module, nullptr);
            for (VkSampler sampler : effect.mSamplers)
                vkDestroySampler(device, sampler, nullptr);
            if (effect.mUniformLayout != VK_NULL_HANDLE)
                vkDestroyDescriptorSetLayout(device, effect.mUniformLayout, nullptr);
            if (effect.mUniformBuffer != VK_NULL_HANDLE)
                vmaDestroyBuffer(owner.mAllocator, effect.mUniformBuffer, effect.mUniformMemory);
            for (auto& [name, texture] : effect.mTextures)
                destroyTextureOn(owner, texture);
        }

        void destroyEffectLater(std::unique_ptr<Effect> effect)
        {
            if (!effect)
                return;
            dropFramebuffers();
            std::shared_ptr<Effect> kept(effect.release());
            Device* device = &mDevice;
            mContext.releaseLater([device, kept] { destroyEffectOn(*device, *kept); });
        }

        // the job's module into Vulkan objects
        std::unique_ptr<Effect> build(CompileJob& job, VkCommandBuffer commands)
        {
            auto effect = std::make_unique<Effect>();
            effect->mName = job.mName;
            effect->mModule = std::move(job.mModule);
            reshadefx::effect_module& module = effect->mModule;
            VkDevice device = mDevice.mDevice;

            // textures
            for (const reshadefx::texture& info : module.textures)
            {
                if (!info.semantic.empty())
                    continue;
                if (info.type != reshadefx::texture_type::texture_2d)
                {
                    Log(Debug::Warning) << "Vulkan effects: " << job.mName << ": texture " << info.name
                                        << " is not 2D (not supported)";
                    return nullptr;
                }
                Texture& texture = effect->mTextures[info.unique_name];
                VkFormat format = vkFormat(info.format);
                VkImageUsageFlags usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT
                    | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
                if (info.render_target)
                {
                    format = targetFormat(format);
                    usage |= VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT;
                }
                if (!makeTexture(texture, format, info.width, info.height, info.levels, usage))
                    return nullptr;
                clear(commands, texture);
                if (const reshadefx::annotation* source = findAnnotation(info.annotations, "source"))
                    loadImage(commands, texture, source->value.string_data);
                toLayout(commands, texture, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            }

            // samplers
            for (const reshadefx::sampler& info : module.samplers)
            {
                VkSamplerCreateInfo create{ VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO };
                const auto filter = static_cast<uint32_t>(info.filter);
                create.minFilter = (filter & 0x30) ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
                create.magFilter = (filter & 0x0C) ? VK_FILTER_LINEAR : VK_FILTER_NEAREST;
                create.mipmapMode = (filter & 0x03) ? VK_SAMPLER_MIPMAP_MODE_LINEAR : VK_SAMPLER_MIPMAP_MODE_NEAREST;
                create.addressModeU = addressMode(info.address_u);
                create.addressModeV = addressMode(info.address_v);
                create.addressModeW = addressMode(info.address_w);
                create.mipLodBias = info.lod_bias;
                create.minLod = std::max(info.min_lod, 0.f);
                create.maxLod = std::min(info.max_lod, VK_LOD_CLAMP_NONE);
                create.borderColor = VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK;
                VkSampler sampler = VK_NULL_HANDLE;
                vkCreateSampler(device, &create, nullptr, &sampler);
                effect->mSamplers.push_back(sampler);
                effect->mSamplerTexture.push_back(info.texture_name);
                effect->mSamplerSrgb.push_back(info.srgb);
                std::string semantic;
                for (const reshadefx::texture& texture : module.textures)
                    if (texture.unique_name == info.texture_name)
                        semantic = texture.semantic;
                effect->mSamplerSemantic.push_back(semantic);
            }

            // uniforms: set 0, one block, a frame slot's part each
            {
                VkDescriptorSetLayoutBinding binding{};
                binding.binding = 0;
                binding.descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC;
                binding.descriptorCount = 1;
                binding.stageFlags = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
                VkDescriptorSetLayoutCreateInfo layout{ VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO };
                layout.bindingCount = module.total_uniform_size > 0 ? 1 : 0;
                layout.pBindings = &binding;
                vkCreateDescriptorSetLayout(device, &layout, nullptr, &effect->mUniformLayout);
            }
            if (module.total_uniform_size > 0)
            {
                const VkDeviceSize alignment
                    = std::max<VkDeviceSize>(mDevice.mProperties.limits.minUniformBufferOffsetAlignment, 16);
                effect->mUniformStride = (module.total_uniform_size + alignment - 1) / alignment * alignment;
                VkBufferCreateInfo info{ VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO };
                info.size = effect->mUniformStride * sFramesInFlight;
                info.usage = VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;
                VmaAllocationCreateInfo memory{};
                memory.usage = VMA_MEMORY_USAGE_CPU_TO_GPU;
                memory.flags = VMA_ALLOCATION_CREATE_MAPPED_BIT;
                VmaAllocationInfo mapped{};
                if (vmaCreateBuffer(mDevice.mAllocator, &info, &memory, &effect->mUniformBuffer,
                        &effect->mUniformMemory, &mapped)
                    != VK_SUCCESS)
                    return nullptr;
                effect->mUniformMapped = static_cast<uint8_t*>(mapped.pMappedData);
                effect->mUniforms.assign(module.total_uniform_size, 0);
            }
            for (const reshadefx::uniform& uniform : module.uniforms)
            {
                UniformSlot slot;
                slot.mName = uniform.name;
                slot.mOffset = uniform.offset;
                slot.mSize = uniform.size;
                slot.mType = uniform.type;
                slot.mDefault.assign(uniform.size, 0);
                if (uniform.has_initializer_value)
                    std::memcpy(slot.mDefault.data(), &uniform.initializer_value.as_uint[0],
                        std::min<size_t>(uniform.size, sizeof(uniform.initializer_value.as_uint)));
                if (const reshadefx::annotation* source = findAnnotation(uniform.annotations, "source"))
                {
                    const std::string& name = source->value.string_data;
                    slot.mSource = name == "frametime" ? Source::FrameTime
                        : name == "framecount"         ? Source::FrameCount
                        : name == "date"               ? Source::Date
                        : name == "timer"              ? Source::Timer
                        : name == "pingpong"           ? Source::PingPong
                        : name == "random"             ? Source::Random
                        : name == "bufready_depth"     ? Source::DepthReady
                                                       : Source::Zero; // keys, mouse, overlay, screenshot
                    for (const char* key : { "min", "max", "step", "smoothing" })
                        if (const reshadefx::annotation* a = findAnnotation(uniform.annotations, key))
                        {
                            const float value = a->type.is_floating_point() ? a->value.as_float[0]
                                : a->type.is_signed()                       ? float(a->value.as_int[0])
                                                                            : float(a->value.as_uint[0]);
                            if (!std::strcmp(key, "min"))
                                slot.mMin = value;
                            else if (!std::strcmp(key, "max"))
                                slot.mMax = value;
                            else if (!std::strcmp(key, "step"))
                                slot.mStep = value;
                            else
                                slot.mSmoothing = value;
                        }
                    if (slot.mSource == Source::PingPong)
                        slot.mPing[0] = slot.mMin;
                    // ReShade starts a source uniform at zero, not at its initializer
                    std::fill(slot.mDefault.begin(), slot.mDefault.end(), 0);
                }
                if (slot.mOffset + slot.mSize <= effect->mUniforms.size())
                    std::memcpy(effect->mUniforms.data() + slot.mOffset, slot.mDefault.data(), slot.mSize);
                effect->mSlots.push_back(std::move(slot));
            }

            // techniques and their passes
            for (size_t t = 0; t < module.techniques.size(); ++t)
            {
                const reshadefx::technique& info = module.techniques[t];
                Technique technique;
                technique.mName = info.name;
                for (size_t p = 0; p < info.passes.size(); ++p)
                {
                    const reshadefx::pass& passInfo = info.passes[p];
                    const int code = t < job.mPassCode.size() && p < job.mPassCode[t].size() ? job.mPassCode[t][p] : -1;
                    if (code < 0)
                    {
                        Log(Debug::Warning) << "Vulkan effects: " << job.mName << ": technique " << info.name
                                            << " has a compute pass (not supported)";
                        technique.mUsable = false;
                        break;
                    }
                    Pass pass;
                    pass.mInfo = &passInfo;
                    if (!makePass(*effect, pass, job.mCode[code].first, job.mCode[code].second))
                    {
                        technique.mUsable = false;
                        break;
                    }
                    technique.mPasses.push_back(pass);
                }
                effect->mTechniques.push_back(std::move(technique));
            }
            effect->mReady = true;
            return effect;
        }

        bool makePass(Effect& effect, Pass& pass, const std::vector<uint32_t>& vertexCode,
            const std::vector<uint32_t>& pixelCode)
        {
            VkDevice device = mDevice.mDevice;
            const reshadefx::pass& info = *pass.mInfo;
            // the attachments: the pass's render targets, else the back buffer
            for (const std::string& name : info.render_target_names)
            {
                if (name.empty())
                    break;
                auto found = effect.mTextures.find(name);
                if (found == effect.mTextures.end())
                    return false;
                VkFormat format = found->second.mFormat;
                if (info.srgb_write_enable && found->second.mSrgbTarget != VK_NULL_HANDLE)
                    format = srgbOf(format);
                pass.mFormats.push_back(format);
            }
            if (pass.mFormats.empty())
            {
                pass.mBackBuffer = true;
                pass.mBackBufferSrgb = info.srgb_write_enable && mBack[0].mSrgbTarget != VK_NULL_HANDLE;
                pass.mFormats.push_back(pass.mBackBufferSrgb ? srgbOf(mFormat) : mFormat);
                pass.mOverwrites = !info.blend_enable[0] && info.render_target_write_mask[0] == 0xF
                    && info.topology == reshadefx::primitive_topology::triangle_list && info.num_vertices == 3
                    && info.viewport_width == 0 && info.viewport_height == 0;
            }
            if (info.stencil_enable)
                Log(Debug::Warning) << "Vulkan effects: " << effect.mName << ": a pass uses the stencil (left out: it "
                                    << "draws everywhere instead, slower but the same picture for most effects)";

            std::vector<VkDescriptorSetLayoutBinding> bindings;
            for (const reshadefx::texture_binding& binding : info.texture_bindings)
            {
                VkDescriptorSetLayoutBinding b{};
                b.binding = binding.entry_point_binding;
                b.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
                b.descriptorCount = 1;
                b.stageFlags = VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT;
                bindings.push_back(b);
            }
            VkDescriptorSetLayoutCreateInfo setLayout{ VK_STRUCTURE_TYPE_DESCRIPTOR_SET_LAYOUT_CREATE_INFO };
            setLayout.bindingCount = static_cast<uint32_t>(bindings.size());
            setLayout.pBindings = bindings.data();
            vkCreateDescriptorSetLayout(device, &setLayout, nullptr, &pass.mSetLayout);
            const VkDescriptorSetLayout layouts[] = { effect.mUniformLayout, pass.mSetLayout };
            VkPipelineLayoutCreateInfo layout{ VK_STRUCTURE_TYPE_PIPELINE_LAYOUT_CREATE_INFO };
            layout.setLayoutCount = 2;
            layout.pSetLayouts = layouts;
            vkCreatePipelineLayout(device, &layout, nullptr, &pass.mLayout);

            VkShaderModule modules[2] = {};
            const std::vector<uint32_t>* code[2] = { &vertexCode, &pixelCode };
            for (int i = 0; i < 2; ++i)
            {
                VkShaderModuleCreateInfo create{ VK_STRUCTURE_TYPE_SHADER_MODULE_CREATE_INFO };
                create.codeSize = code[i]->size() * 4;
                create.pCode = code[i]->data();
                if (const VkResult result = vkCreateShaderModule(device, &create, nullptr, &modules[i]);
                    result != VK_SUCCESS)
                {
                    Log(Debug::Error) << "Vulkan effects: " << effect.mName << ": vkCreateShaderModule: "
                                      << resultName(result);
                    return false;
                }
                effect.mModules.push_back(modules[i]);
            }
            VkPipelineShaderStageCreateInfo stages[2]{};
            stages[0].sType = stages[1].sType = VK_STRUCTURE_TYPE_PIPELINE_SHADER_STAGE_CREATE_INFO;
            stages[0].stage = VK_SHADER_STAGE_VERTEX_BIT;
            stages[0].module = modules[0];
            stages[0].pName = info.vs_entry_point.c_str();
            stages[1].stage = VK_SHADER_STAGE_FRAGMENT_BIT;
            stages[1].module = modules[1];
            stages[1].pName = info.ps_entry_point.c_str();
            VkPipelineVertexInputStateCreateInfo vertexInput{ VK_STRUCTURE_TYPE_PIPELINE_VERTEX_INPUT_STATE_CREATE_INFO };
            VkPipelineInputAssemblyStateCreateInfo assembly{
                VK_STRUCTURE_TYPE_PIPELINE_INPUT_ASSEMBLY_STATE_CREATE_INFO };
            assembly.topology = topology(info.topology);
            VkPipelineViewportStateCreateInfo viewport{ VK_STRUCTURE_TYPE_PIPELINE_VIEWPORT_STATE_CREATE_INFO };
            viewport.viewportCount = 1;
            viewport.scissorCount = 1;
            VkPipelineRasterizationStateCreateInfo raster{ VK_STRUCTURE_TYPE_PIPELINE_RASTERIZATION_STATE_CREATE_INFO };
            raster.polygonMode = VK_POLYGON_MODE_FILL;
            raster.cullMode = VK_CULL_MODE_NONE;
            raster.frontFace = VK_FRONT_FACE_COUNTER_CLOCKWISE;
            raster.lineWidth = 1.f;
            VkPipelineMultisampleStateCreateInfo multisample{ VK_STRUCTURE_TYPE_PIPELINE_MULTISAMPLE_STATE_CREATE_INFO };
            multisample.rasterizationSamples = VK_SAMPLE_COUNT_1_BIT;
            std::vector<VkPipelineColorBlendAttachmentState> blends(pass.mFormats.size());
            for (size_t i = 0; i < blends.size(); ++i)
            {
                blends[i].blendEnable = info.blend_enable[i] ? VK_TRUE : VK_FALSE;
                blends[i].srcColorBlendFactor = blendFactor(info.source_color_blend_factor[i]);
                blends[i].dstColorBlendFactor = blendFactor(info.dest_color_blend_factor[i]);
                blends[i].colorBlendOp = blendOp(info.color_blend_op[i]);
                blends[i].srcAlphaBlendFactor = blendFactor(info.source_alpha_blend_factor[i]);
                blends[i].dstAlphaBlendFactor = blendFactor(info.dest_alpha_blend_factor[i]);
                blends[i].alphaBlendOp = blendOp(info.alpha_blend_op[i]);
                blends[i].colorWriteMask = info.render_target_write_mask[i] & 0xF;
            }
            VkPipelineColorBlendStateCreateInfo blend{ VK_STRUCTURE_TYPE_PIPELINE_COLOR_BLEND_STATE_CREATE_INFO };
            blend.attachmentCount = static_cast<uint32_t>(blends.size());
            blend.pAttachments = blends.data();
            VkPipelineDepthStencilStateCreateInfo depthStencil{
                VK_STRUCTURE_TYPE_PIPELINE_DEPTH_STENCIL_STATE_CREATE_INFO };
            const VkDynamicState dynamicStates[] = { VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR };
            VkPipelineDynamicStateCreateInfo dynamic{ VK_STRUCTURE_TYPE_PIPELINE_DYNAMIC_STATE_CREATE_INFO };
            dynamic.dynamicStateCount = 2;
            dynamic.pDynamicStates = dynamicStates;
            VkGraphicsPipelineCreateInfo create{ VK_STRUCTURE_TYPE_GRAPHICS_PIPELINE_CREATE_INFO };
            create.stageCount = 2;
            create.pStages = stages;
            create.pVertexInputState = &vertexInput;
            create.pInputAssemblyState = &assembly;
            create.pViewportState = &viewport;
            create.pRasterizationState = &raster;
            create.pMultisampleState = &multisample;
            create.pDepthStencilState = &depthStencil;
            create.pColorBlendState = &blend;
            create.pDynamicState = &dynamic;
            create.layout = pass.mLayout;
            create.renderPass = renderPass(
                pass.mFormats, std::vector<VkAttachmentLoadOp>(pass.mFormats.size(), VK_ATTACHMENT_LOAD_OP_LOAD));
            if (const VkResult result
                = vkCreateGraphicsPipelines(device, VK_NULL_HANDLE, 1, &create, nullptr, &pass.mPipeline);
                result != VK_SUCCESS)
            {
                Log(Debug::Error) << "Vulkan effects: " << effect.mName << ": vkCreateGraphicsPipelines: "
                                  << resultName(result);
                pass.mPipeline = VK_NULL_HANDLE;
                return false;
            }
            return true;
        }

        // ---- the overlay's changes ----

        std::vector<std::pair<std::string, std::string>> baseMacros() const
        {
            std::vector<std::pair<std::string, std::string>> macros = {
                { "__RESHADE__", "60703" },
                { "__RESHADE_PERFORMANCE_MODE__", "0" },
                { "__RENDERER__", "0x20000" }, // Vulkan, as ReShade says it (D3D numbers below it)
                { "__VENDOR__", std::to_string(mDevice.mProperties.vendorID) },
                { "__DEVICE__", std::to_string(mDevice.mProperties.deviceID) },
                { "__APPLICATION__", "0x0" },
                { "BUFFER_WIDTH", std::to_string(mExtent.width) },
                { "BUFFER_HEIGHT", std::to_string(mExtent.height) },
                { "BUFFER_RCP_WIDTH", "(1.0 / BUFFER_WIDTH)" },
                { "BUFFER_RCP_HEIGHT", "(1.0 / BUFFER_HEIGHT)" },
                { "BUFFER_COLOR_BIT_DEPTH", std::to_string(bitDepth(mFormat)) },
                { "BUFFER_COLOR_SPACE", "1" }, // sRGB
                // the scene's depth as this renderer has it: bottom row first (OpenGL's way), reversed when OpenMW
                // uses reverse z; the preset's own definitions go after these and win
                { "RESHADE_DEPTH_INPUT_IS_UPSIDE_DOWN", "1" },
                { "RESHADE_DEPTH_INPUT_IS_REVERSED", mDepthReversed ? "1" : "0" },
            };
            for (const auto& define : mPreset.mDefines)
                macros.push_back(define);
            return macros;
        }

        std::string keyOf(const std::vector<std::pair<std::string, std::string>>& macros) const
        {
            std::string key;
            for (const auto& [name, value] : macros)
                key += name + "=" + value + ";";
            return key;
        }

        // the files the preset's techniques are in
        std::set<std::string> wantedFiles() const
        {
            std::set<std::string> files;
            if (mPreset.mOn)
                for (const std::string& technique : mPreset.mTechniques)
                    files.insert(fileOf(technique));
            return files;
        }

        const FileInfo* findFile(const std::string& name) const
        {
            for (const FileInfo& file : mFiles)
                if (file.mName == name)
                    return &file;
            return nullptr;
        }

        std::vector<fs::path> includePaths(const fs::path& effect) const
        {
            std::vector<fs::path> paths{ effect.parent_path() };
            std::error_code error;
            const fs::path shaders = sRoot / "Shaders";
            std::vector<fs::path> user, starter;
            for (fs::recursive_directory_iterator it(shaders, fs::directory_options::skip_permission_denied, error), end;
                 it != end; it.increment(error))
            {
                if (error)
                    break;
                if (it->is_directory(error))
                    (it->path().string().find(sStarter) != std::string::npos ? starter : user).push_back(it->path());
            }
            paths.push_back(shaders);
            paths.insert(paths.end(), user.begin(), user.end());
            paths.insert(paths.end(), starter.begin(), starter.end());
            return paths;
        }

        void readShared()
        {
            Shared& state = shared();
            std::lock_guard lock(state.mMutex);
            ensureLoaded(state);
            if (state.mStructure != mStructure)
            {
                mStructure = state.mStructure;
                mValues = 0; // and the values with it
                mPreset = state.mPreset;
                mFiles = state.mFiles;
                mFailedKeys.clear();
            }
            if (state.mValues != mValues)
            {
                mValues = state.mValues;
                mPreset.mValues = state.mPreset.mValues;
                mPreset.mOn = state.mPreset.mOn;
                for (auto& [name, effect] : mEffects)
                    applyValues(*effect);
            }
            mActive = mPreset.mOn && !mPreset.mTechniques.empty();
        }

        void setStatus(const std::string& file, const std::string& status, const std::string& json = std::string())
        {
            Shared& state = shared();
            std::lock_guard lock(state.mMutex);
            state.mStatus[file] = status;
            if (!json.empty())
                state.mEffectJson[file] = json;
        }

        // the preset's values into the effect's block (a value the preset does not have: the shader's initializer)
        void applyValues(Effect& effect)
        {
            auto values = mPreset.mValues.find(effect.mName);
            for (const UniformSlot& slot : effect.mSlots)
            {
                if (slot.mSource != Source::None || slot.mOffset + slot.mSize > effect.mUniforms.size())
                    continue;
                std::vector<uint8_t> bytes = slot.mDefault;
                if (values != mPreset.mValues.end())
                {
                    auto value = values->second.find(slot.mName);
                    if (value != values->second.end())
                        parseValue(slot, value->second, bytes);
                }
                std::memcpy(effect.mUniforms.data() + slot.mOffset, bytes.data(), slot.mSize);
            }
        }

        // the overlay's view of a compiled effect: its uniforms (with their defaults) and the #defines it tests
        std::string describe(const Effect& effect, const CompileJob& job) const
        {
            std::ostringstream out;
            out << "{\"uniforms\":[";
            bool first = true;
            for (const reshadefx::uniform& uniform : effect.mModule.uniforms)
            {
                if (findAnnotation(uniform.annotations, "source") != nullptr || uniform.type.cols > 1
                    || uniform.type.is_array())
                    continue;
                const reshadefx::annotation* hidden = findAnnotation(uniform.annotations, "hidden");
                if (hidden != nullptr && hidden->value.as_uint[0] != 0)
                    continue;
                const auto text = [&](const char* name) -> std::string {
                    const reshadefx::annotation* a = findAnnotation(uniform.annotations, name);
                    return a != nullptr && isString(a->type) ? a->value.string_data : std::string();
                };
                const auto number = [&](const char* name, std::ostringstream& to) {
                    const reshadefx::annotation* a = findAnnotation(uniform.annotations, name);
                    if (a != nullptr && !isString(a->type))
                        to << ",\"" << name + 3 << "\":" << annotationNumber(*a);
                };
                std::string items = text("ui_items");
                std::vector<std::string> itemList;
                for (size_t start = 0; start < items.size();)
                {
                    size_t end = items.find('\0', start);
                    if (end == std::string::npos)
                        end = items.size();
                    if (end > start)
                        itemList.push_back(items.substr(start, end - start));
                    start = end + 1;
                }
                const std::string type = uniform.type.is_floating_point() ? "float"
                    : uniform.type.is_boolean()                             ? "bool"
                    : uniform.type.is_signed()                              ? "int"
                                                                            : "uint";
                std::vector<uint8_t> initial(uniform.size, 0);
                if (uniform.has_initializer_value)
                    std::memcpy(initial.data(), &uniform.initializer_value.as_uint[0],
                        std::min<size_t>(uniform.size, sizeof(uniform.initializer_value.as_uint)));
                out << (first ? "" : ",") << "{\"name\":" << jsonString(uniform.name)
                    << ",\"label\":" << jsonString(text("ui_label").empty() ? uniform.name : text("ui_label"))
                    << ",\"type\":\"" << type << "\",\"components\":" << uniform.type.components()
                    << ",\"ui\":" << jsonString(text("ui_type")) << ",\"tooltip\":" << jsonString(text("ui_tooltip"))
                    << ",\"category\":" << jsonString(text("ui_category"))
                    << ",\"default\":" << jsonString(formatValue(uniform.type, initial.data(), uniform.type.components()));
                std::ostringstream numbers;
                number("ui_min", numbers);
                number("ui_max", numbers);
                number("ui_step", numbers);
                out << numbers.str() << ",\"items\":[";
                for (size_t i = 0; i < itemList.size(); ++i)
                    out << (i ? "," : "") << jsonString(itemList[i]);
                out << "]}";
                first = false;
            }
            out << "],\"techniques\":[";
            for (size_t i = 0; i < effect.mTechniques.size(); ++i)
            {
                const reshadefx::technique& info = effect.mModule.techniques[i];
                const reshadefx::annotation* hidden = findAnnotation(info.annotations, "hidden");
                const reshadefx::annotation* label = findAnnotation(info.annotations, "ui_label");
                const reshadefx::annotation* tooltip = findAnnotation(info.annotations, "ui_tooltip");
                out << (i ? "," : "") << "{\"name\":" << jsonString(info.name) << ",\"label\":"
                    << jsonString(label && isString(label->type) ? label->value.string_data : info.name)
                    << ",\"tooltip\":"
                    << jsonString(tooltip && isString(tooltip->type) ? tooltip->value.string_data : "")
                    << ",\"usable\":" << (effect.mTechniques[i].mUsable ? "true" : "false")
                    << ",\"hidden\":" << (hidden && hidden->value.as_uint[0] ? "true" : "false") << "}";
            }
            out << "],\"defines\":[";
            first = true;
            for (const auto& [name, value] : job.mUsedDefines)
            {
                // ReShade's own and the buffer's are not the user's to change
                if (name.rfind("__", 0) == 0 || name.rfind("BUFFER_", 0) == 0)
                    continue;
                out << (first ? "" : ",") << "[" << jsonString(name) << "," << jsonString(value) << "]";
                first = false;
            }
            out << "]}";
            return out.str();
        }

        // compiles what the preset needs and is not compiled for this size, format and macros; takes in finished ones
        void reconcile(VkCommandBuffer commands)
        {
            const auto macros = baseMacros();
            const std::string key = keyOf(macros);
            const std::set<std::string> wanted = wantedFiles();
            // finished jobs
            for (auto it = mJobs.begin(); it != mJobs.end();)
            {
                CompileJob& job = *it->second;
                if (!job.mDone.load())
                {
                    ++it;
                    continue;
                }
                const std::string name = it->first;
                if (!job.mOk)
                {
                    Log(Debug::Warning) << "Vulkan effects: " << name << " did not compile:\n" << job.mErrors;
                    setStatus(name, job.mErrors.empty() ? "compile failed" : job.mErrors);
                    mFailedKeys[name] = it->second->mMacros.empty() ? key : keyOf(it->second->mMacros);
                }
                else
                {
                    std::unique_ptr<Effect> effect = build(job, commands);
                    if (effect)
                    {
                        effect->mKey = keyOf(job.mMacros);
                        applyValues(*effect);
                        const std::string json = describe(*effect, job);
                        destroyEffectLater(std::move(mEffects[name]));
                        mEffects[name] = std::move(effect);
                        setStatus(name, job.mErrors.empty() ? "ok" : "ok", json);
                        Log(Debug::Info) << "Vulkan effects: " << name << " ready";
                    }
                    else
                    {
                        setStatus(name, "could not be made on this GPU (see openmw.log)");
                        mFailedKeys[name] = keyOf(job.mMacros);
                    }
                }
                it = mJobs.erase(it);
            }
            // effects no longer wanted
            for (auto it = mEffects.begin(); it != mEffects.end();)
            {
                if (!wanted.count(it->first))
                {
                    destroyEffectLater(std::move(it->second));
                    it = mEffects.erase(it);
                }
                else
                    ++it;
            }
            // new or changed ones
            for (const std::string& name : wanted)
            {
                auto effect = mEffects.find(name);
                if ((effect != mEffects.end() && effect->second->mKey == key) || mJobs.count(name))
                    continue;
                auto failed = mFailedKeys.find(name);
                if (failed != mFailedKeys.end() && failed->second == key)
                    continue;
                const FileInfo* file = findFile(name);
                if (file == nullptr)
                {
                    setStatus(name, "file not found in " + (sRoot / "Shaders").string());
                    mFailedKeys[name] = key;
                    continue;
                }
                auto job = std::make_shared<CompileJob>();
                job->mName = name;
                job->mPath = file->mPath;
                job->mIncludes = includePaths(file->mPath);
                job->mMacros = macros;
                job->mSpirv10 = mDevice.mApiVersion < VK_API_VERSION_1_1;
                mJobs[name] = job;
                setStatus(name, "compiling");
                std::thread([job] { compile(*job); job->mDone.store(true); }).detach();
            }
        }

        // ---- a frame ----

        void updateUniforms(Effect& effect, bool depthReady)
        {
            const auto now = std::chrono::steady_clock::now();
            const float frameMs = std::chrono::duration<float, std::milli>(now - mLast).count();
            const float timerMs = std::chrono::duration<float, std::milli>(now - mStart).count();
            for (UniformSlot& slot : effect.mSlots)
            {
                if (slot.mSource == Source::None || slot.mOffset + slot.mSize > effect.mUniforms.size())
                    continue;
                uint8_t* at = effect.mUniforms.data() + slot.mOffset;
                const auto putFloat = [&](unsigned i, float value) {
                    if ((i + 1) * 4 <= slot.mSize)
                    {
                        if (slot.mType.is_floating_point())
                            std::memcpy(at + i * 4, &value, 4);
                        else
                        {
                            const int32_t whole = static_cast<int32_t>(value);
                            std::memcpy(at + i * 4, &whole, 4);
                        }
                    }
                };
                switch (slot.mSource)
                {
                    case Source::FrameTime:
                        putFloat(0, frameMs);
                        break;
                    case Source::FrameCount:
                    {
                        const uint32_t count = static_cast<uint32_t>(mFrames);
                        if (slot.mType.is_floating_point())
                            putFloat(0, static_cast<float>(count));
                        else
                            std::memcpy(at, &count, 4);
                        break;
                    }
                    case Source::Timer:
                        putFloat(0, timerMs);
                        break;
                    case Source::Date:
                    {
                        const std::time_t time = std::time(nullptr);
                        std::tm local{};
                        localtime_r(&time, &local);
                        putFloat(0, float(local.tm_year + 1900));
                        putFloat(1, float(local.tm_mon + 1));
                        putFloat(2, float(local.tm_mday));
                        putFloat(3, float(local.tm_hour * 3600 + local.tm_min * 60 + local.tm_sec));
                        break;
                    }
                    case Source::PingPong:
                    {
                        const float step = slot.mStep * frameMs / 1000.f;
                        slot.mPing[0] += step * slot.mPing[1];
                        if (slot.mPing[0] >= slot.mMax)
                        {
                            slot.mPing[0] = slot.mMax;
                            slot.mPing[1] = -1.f;
                        }
                        else if (slot.mPing[0] <= slot.mMin)
                        {
                            slot.mPing[0] = slot.mMin;
                            slot.mPing[1] = 1.f;
                        }
                        putFloat(0, slot.mPing[0]);
                        putFloat(1, slot.mPing[1]);
                        break;
                    }
                    case Source::Random:
                    {
                        const int lo = static_cast<int>(slot.mMin);
                        const int hi = std::max(lo, static_cast<int>(slot.mMax == 0.f ? 32767.f : slot.mMax));
                        const int32_t value = std::uniform_int_distribution<int>(lo, hi)(mRandom);
                        if (slot.mType.is_floating_point())
                            putFloat(0, float(value));
                        else
                            std::memcpy(at, &value, 4);
                        break;
                    }
                    case Source::DepthReady:
                    {
                        const uint32_t ready = depthReady ? 1 : 0;
                        std::memcpy(at, &ready, 4);
                        break;
                    }
                    default:
                        std::memset(at, 0, slot.mSize);
                }
            }
            if (effect.mUniformMapped != nullptr)
            {
                const VkDeviceSize offset = effect.mUniformStride * mContext.frameSlot();
                std::memcpy(effect.mUniformMapped + offset, effect.mUniforms.data(), effect.mUniforms.size());
                vmaFlushAllocation(mDevice.mAllocator, effect.mUniformMemory, offset, effect.mUniforms.size());
            }
        }

        bool ensureBackBuffer(VkCommandBuffer commands)
        {
            for (Texture& back : mBack)
                if (back.mImage == VK_NULL_HANDLE || back.mWidth != mExtent.width || back.mHeight != mExtent.height
                    || back.mFormat != mFormat)
                {
                    if (back.mImage != VK_NULL_HANDLE)
                    {
                        dropFramebuffers();
                        destroyLater(back);
                        back = Texture{};
                    }
                    if (!makeTexture(back, mFormat, mExtent.width, mExtent.height, 1,
                            VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
                                | VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT))
                        return false;
                }
            if (mDummyDepth.mImage == VK_NULL_HANDLE)
            {
                if (!makeTexture(mDummyDepth, VK_FORMAT_R8G8B8A8_UNORM, 1, 1, 1,
                        VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT))
                    return false;
                clear(commands, mDummyDepth, 0.f);
                toLayout(commands, mDummyDepth, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            }
            return true;
        }

        void copyImage(VkCommandBuffer commands, VkImage from, VkImage to)
        {
            VkImageCopy region{};
            region.srcSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
            region.dstSubresource = region.srcSubresource;
            region.extent = { mExtent.width, mExtent.height, 1 };
            vkCmdCopyImage(commands, from, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, to,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region);
        }

        void apply(VkImage window, VkExtent2D extent, VkFormat format, VkImageView depthView, VkImageLayout depthLayout,
            bool depthReversed)
        {
            if (extent.width != mExtent.width || extent.height != mExtent.height || format != mFormat
                || depthReversed != mDepthReversed)
            {
                mExtent = extent;
                mFormat = format;
                mDepthReversed = depthReversed;
                mFailedKeys.clear();
            }
            VkCommandBuffer commands = mContext.commands();
            readShared();
            if (!ensureBackBuffer(commands))
                return;
            reconcile(commands);

            // what runs this frame: the preset's techniques, in its order, that are ready
            std::vector<std::pair<Effect*, Technique*>> runs;
            if (mPreset.mOn)
                for (const std::string& id : mPreset.mTechniques)
                {
                    auto effect = mEffects.find(fileOf(id));
                    if (effect == mEffects.end() || !effect->second->mReady)
                        continue;
                    for (Technique& technique : effect->second->mTechniques)
                        if (technique.mName == nameOf(id) && technique.mUsable && !technique.mPasses.empty())
                            runs.emplace_back(effect->second.get(), &technique);
                }
            const auto now = std::chrono::steady_clock::now();
            if (runs.empty())
            {
                mLast = now;
                return;
            }
            std::set<Effect*> updated;
            for (auto& [effect, technique] : runs)
                if (updated.insert(effect).second)
                    updateUniforms(*effect, depthView != VK_NULL_HANDLE);
            mLast = now;
            ++mFrames;

            // the window into the first back buffer image
            barrier(commands, window, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            toLayout(commands, mBack[0], VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            copyImage(commands, window, mBack[0].mImage);
            toLayout(commands, mBack[0], VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
            int read = 0; // the back buffer as it is now

            for (auto& [effect, technique] : runs)
            {
                // set 0: the uniforms, at this frame slot's part
                VkDescriptorSet uniformSet = mContext.allocateSet(effect->mUniformLayout);
                if (uniformSet == VK_NULL_HANDLE)
                    return;
                uint32_t dynamicOffset = 0;
                if (effect->mUniformBuffer != VK_NULL_HANDLE)
                {
                    VkDescriptorBufferInfo buffer{ effect->mUniformBuffer, 0, effect->mUniforms.size() };
                    VkWriteDescriptorSet write{ VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET };
                    write.dstSet = uniformSet;
                    write.descriptorCount = 1;
                    write.descriptorType = VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC;
                    write.pBufferInfo = &buffer;
                    vkUpdateDescriptorSets(mDevice.mDevice, 1, &write, 0, nullptr);
                    dynamicOffset = static_cast<uint32_t>(effect->mUniformStride * mContext.frameSlot());
                }
                for (Pass& pass : technique->mPasses)
                {
                    const reshadefx::pass& info = *pass.mInfo;
                    const int write = 1 - read;
                    // the targets this pass draws into
                    std::vector<Texture*> targets;
                    std::vector<VkImageView> views;
                    std::vector<VkAttachmentLoadOp> loads;
                    uint32_t width = 0, height = 0;
                    if (pass.mBackBuffer)
                    {
                        Texture& target = mBack[write];
                        if (!pass.mOverwrites && !info.clear_render_targets)
                        {
                            // blending onto (or drawing only part of) the back buffer: it starts as it is now
                            toLayout(commands, mBack[read], VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
                            toLayout(commands, target, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
                            copyImage(commands, mBack[read].mImage, target.mImage);
                        }
                        toLayout(commands, mBack[read], VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                        targets.push_back(&target);
                        views.push_back(pass.mBackBufferSrgb ? target.mSrgbTarget : target.mTarget);
                        loads.push_back(info.clear_render_targets ? VK_ATTACHMENT_LOAD_OP_CLEAR
                                : pass.mOverwrites                ? VK_ATTACHMENT_LOAD_OP_DONT_CARE
                                                                  : VK_ATTACHMENT_LOAD_OP_LOAD);
                        width = target.mWidth;
                        height = target.mHeight;
                    }
                    else
                        for (const std::string& name : info.render_target_names)
                        {
                            if (name.empty())
                                break;
                            Texture& target = effect->mTextures[name];
                            targets.push_back(&target);
                            views.push_back(info.srgb_write_enable && target.mSrgbTarget != VK_NULL_HANDLE
                                    ? target.mSrgbTarget
                                    : target.mTarget);
                            loads.push_back(
                                info.clear_render_targets ? VK_ATTACHMENT_LOAD_OP_CLEAR : VK_ATTACHMENT_LOAD_OP_LOAD);
                            if (width == 0)
                            {
                                width = target.mWidth;
                                height = target.mHeight;
                            }
                        }

                    // set 1: the pass's textures
                    VkDescriptorSet textureSet = mContext.allocateSet(pass.mSetLayout);
                    if (textureSet == VK_NULL_HANDLE)
                        return;
                    std::vector<VkDescriptorImageInfo> images(info.texture_bindings.size());
                    std::vector<VkWriteDescriptorSet> writes;
                    for (size_t i = 0; i < info.texture_bindings.size(); ++i)
                    {
                        const reshadefx::texture_binding& binding = info.texture_bindings[i];
                        if (binding.index >= effect->mSamplers.size())
                            continue;
                        const std::string& semantic = effect->mSamplerSemantic[binding.index];
                        const bool srgb = binding.srgb || effect->mSamplerSrgb[binding.index];
                        VkDescriptorImageInfo& image = images[i];
                        image.sampler = effect->mSamplers[binding.index];
                        image.imageLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
                        if (semantic == "COLOR")
                            image.imageView = srgb && mBack[read].mSrgbView ? mBack[read].mSrgbView : mBack[read].mView;
                        else if (semantic == "DEPTH")
                        {
                            image.imageView = depthView != VK_NULL_HANDLE ? depthView : mDummyDepth.mView;
                            if (depthView != VK_NULL_HANDLE)
                                image.imageLayout = depthLayout;
                        }
                        else
                        {
                            auto found = effect->mTextures.find(effect->mSamplerTexture[binding.index]);
                            if (found == effect->mTextures.end())
                                continue;
                            Texture& texture = found->second;
                            // a texture this pass also draws into is not read in the same pass (ReShade unbinds it)
                            if (std::find(targets.begin(), targets.end(), &texture) != targets.end())
                                image.imageView = mDummyDepth.mView;
                            else
                            {
                                toLayout(commands, texture, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                                image.imageView = srgb && texture.mSrgbView ? texture.mSrgbView : texture.mView;
                            }
                        }
                        VkWriteDescriptorSet w{ VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET };
                        w.dstSet = textureSet;
                        w.dstBinding = binding.entry_point_binding;
                        w.descriptorCount = 1;
                        w.descriptorType = VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
                        w.pImageInfo = &image;
                        writes.push_back(w);
                    }
                    if (!writes.empty())
                        vkUpdateDescriptorSets(
                            mDevice.mDevice, static_cast<uint32_t>(writes.size()), writes.data(), 0, nullptr);

                    for (Texture* target : targets)
                        toLayout(commands, *target, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
                    std::vector<VkFormat> formats = pass.mFormats;
                    VkRenderPass renderPassHandle = renderPass(formats, loads);
                    VkRenderPassBeginInfo begin{ VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO };
                    begin.renderPass = renderPassHandle;
                    begin.framebuffer = framebuffer(renderPassHandle, views, width, height);
                    begin.renderArea = { { 0, 0 }, { width, height } };
                    std::vector<VkClearValue> clears(views.size());
                    begin.clearValueCount = static_cast<uint32_t>(clears.size());
                    begin.pClearValues = clears.data();
                    vkCmdBeginRenderPass(commands, &begin, VK_SUBPASS_CONTENTS_INLINE);
                    vkCmdBindPipeline(commands, VK_PIPELINE_BIND_POINT_GRAPHICS, pass.mPipeline);
                    const uint32_t viewportWidth = info.viewport_width ? info.viewport_width : width;
                    const uint32_t viewportHeight = info.viewport_height ? info.viewport_height : height;
                    const VkViewport viewport{ 0.f, 0.f, float(viewportWidth), float(viewportHeight), 0.f, 1.f };
                    const VkRect2D scissor{ { 0, 0 }, { std::min(viewportWidth, width), std::min(viewportHeight, height) } };
                    vkCmdSetViewport(commands, 0, 1, &viewport);
                    vkCmdSetScissor(commands, 0, 1, &scissor);
                    const VkDescriptorSet sets[] = { uniformSet, textureSet };
                    vkCmdBindDescriptorSets(commands, VK_PIPELINE_BIND_POINT_GRAPHICS, pass.mLayout, 0, 2, sets,
                        effect->mUniformBuffer != VK_NULL_HANDLE ? 1 : 0, &dynamicOffset);
                    vkCmdDraw(commands, info.num_vertices, 1, 0, 0);
                    vkCmdEndRenderPass(commands);

                    if (pass.mBackBuffer)
                    {
                        read = write;
                        toLayout(commands, mBack[read], VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                    }
                    else
                        for (Texture* target : targets)
                        {
                            if (info.generate_mipmaps && target->mMips > 1)
                                generateMips(commands, *target);
                            toLayout(commands, *target, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
                        }
                }
            }

            // the result into the window, for the GUI to be drawn on
            toLayout(commands, mBack[read], VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL);
            barrier(commands, window, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL);
            copyImage(commands, mBack[read].mImage, window);
            barrier(commands, window, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            toLayout(commands, mBack[read], VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);
        }
    };

    Effects::Effects(Context& context)
        : mImpl(std::make_unique<Impl>(context))
    {
    }

    Effects::~Effects() = default;

    bool Effects::active()
    {
        mImpl->readShared();
        return mImpl->mActive;
    }

    void Effects::apply(VkImage window, VkExtent2D extent, VkFormat format, VkImageView depthView,
        VkImageLayout depthLayout, bool depthReversed)
    {
        mImpl->apply(window, extent, format, depthView, depthLayout, depthReversed);
    }

    std::string Effects::report()
    {
        Shared& state = shared();
        std::lock_guard lock(state.mMutex);
        ensureLoaded(state);
        const Preset& preset = state.mPreset;
        std::ostringstream out;
        out << "{\"on\":" << (preset.mOn ? "true" : "false") << ",\"folder\":" << jsonString(sRoot.string())
            << ",\"enabled\":[";
        for (size_t i = 0; i < preset.mTechniques.size(); ++i)
            out << (i ? "," : "") << jsonString(preset.mTechniques[i]);
        out << "],\"files\":[";
        for (size_t i = 0; i < state.mFiles.size(); ++i)
        {
            const FileInfo& file = state.mFiles[i];
            auto status = state.mStatus.find(file.mName);
            out << (i ? "," : "") << "{\"name\":" << jsonString(file.mName)
                << ",\"starter\":" << (file.mStarter ? "true" : "false")
                << ",\"status\":" << jsonString(status != state.mStatus.end() ? status->second : "") << ",\"techniques\":[";
            for (size_t t = 0; t < file.mTechniques.size(); ++t)
                out << (t ? "," : "") << jsonString(file.mTechniques[t]);
            out << "]";
            auto json = state.mEffectJson.find(file.mName);
            if (json != state.mEffectJson.end())
                out << ",\"effect\":" << json->second;
            auto values = preset.mValues.find(file.mName);
            out << ",\"values\":{";
            if (values != preset.mValues.end())
            {
                bool first = true;
                for (const auto& [name, value] : values->second)
                {
                    out << (first ? "" : ",") << jsonString(name) << ":" << jsonString(value);
                    first = false;
                }
            }
            out << "}}";
        }
        out << "],\"defines\":[";
        for (size_t i = 0; i < preset.mDefines.size(); ++i)
            out << (i ? "," : "") << "[" << jsonString(preset.mDefines[i].first) << ","
                << jsonString(preset.mDefines[i].second) << "]";
        out << "]}";
        return out.str();
    }

    // One change from the overlay, words separated by tabs:
    //   on <0|1>                          all effects on or off
    //   technique <Name@File.fx> <0|1>    a technique on (at the end of the order) or off
    //   move <Name@File.fx> <index>       a technique that is on to that place in the order
    //   value <File.fx> <uniform> <v,v>   a uniform's value
    //   reset <File.fx> [uniform]         a uniform (or the whole file) back to the effect's defaults
    //   define <NAME> <value>             a preprocessor definition (an empty value removes it)
    //   rescan                            the Shaders folder read again, everything compiled again
    void Effects::command(const std::string& command)
    {
        std::vector<std::string> words;
        {
            std::string word;
            std::istringstream stream(command);
            while (std::getline(stream, word, '\t'))
                words.push_back(word);
        }
        if (words.empty())
            return;
        Shared& state = shared();
        std::lock_guard lock(state.mMutex);
        ensureLoaded(state);
        Preset& preset = state.mPreset;
        const std::string& verb = words[0];
        bool structure = false;
        if (verb == "on" && words.size() >= 2)
        {
            preset.mOn = words[1] != "0";
            structure = true;
        }
        else if (verb == "technique" && words.size() >= 3)
        {
            auto& list = preset.mTechniques;
            list.erase(std::remove(list.begin(), list.end(), words[1]), list.end());
            if (words[2] != "0")
                list.push_back(words[1]);
            if (std::find(preset.mSorting.begin(), preset.mSorting.end(), words[1]) == preset.mSorting.end())
                preset.mSorting.push_back(words[1]);
            structure = true;
        }
        else if (verb == "move" && words.size() >= 3)
        {
            auto& list = preset.mTechniques;
            auto found = std::find(list.begin(), list.end(), words[1]);
            if (found != list.end())
            {
                list.erase(found);
                const size_t index = std::min<size_t>(std::strtoul(words[2].c_str(), nullptr, 10), list.size());
                list.insert(list.begin() + index, words[1]);
            }
            structure = true;
        }
        else if (verb == "value" && words.size() >= 4)
            preset.mValues[words[1]][words[2]] = words[3];
        else if (verb == "reset" && words.size() >= 2)
        {
            if (words.size() >= 3)
                preset.mValues[words[1]].erase(words[2]);
            else
                preset.mValues.erase(words[1]);
        }
        else if (verb == "define" && words.size() >= 2)
        {
            auto& defines = preset.mDefines;
            defines.erase(std::remove_if(defines.begin(), defines.end(),
                              [&](const auto& define) { return define.first == words[1]; }),
                defines.end());
            if (words.size() >= 3 && !words[2].empty())
                defines.emplace_back(words[1], words[2]);
            structure = true;
        }
        else if (verb == "rescan")
        {
            state.mFiles = scanFiles();
            state.mStatus.clear();
            state.mEffectJson.clear();
            structure = true;
        }
        else
            return;
        if (structure)
            ++state.mStructure;
        ++state.mValues;
        writePreset(presetPath(), preset);
    }
}

#ifdef __ANDROID__
// org.openmw.SdlEngineBridge: the launcher's overlay, on its UI thread
extern "C" JNIEXPORT jbyteArray JNICALL Java_org_openmw_SdlEngineBridge_omwEffectsReport(JNIEnv* env, jobject)
{
    const std::string report = Alpha3::Vk::Effects::report();
    jbyteArray out = env->NewByteArray(static_cast<jsize>(report.size()));
    if (out != nullptr)
        env->SetByteArrayRegion(out, 0, static_cast<jsize>(report.size()), reinterpret_cast<const jbyte*>(report.data()));
    return out;
}

extern "C" JNIEXPORT void JNICALL Java_org_openmw_SdlEngineBridge_omwEffectsCommand(JNIEnv* env, jobject, jstring text)
{
    const char* chars = env->GetStringUTFChars(text, nullptr);
    if (chars == nullptr)
        return;
    const std::string command(chars);
    env->ReleaseStringUTFChars(text, chars);
    Alpha3::Vk::Effects::command(command);
}
#endif
