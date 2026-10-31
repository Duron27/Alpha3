// Host test driver for the support library's glesdialect.cpp: translate <vert|frag> <desktop|compat|gles> [clamp] [name]
// < in > out (clamp: the shader is drawn with GL_DEPTH_CLAMP, as the shadow casters are). With A3_EXPERIMENTAL set to
// the support library's src/experimental, every experimental part is first added to the shader named name
// (experimentalshaders.cpp), as the engine does with all of them switched on.
#include "experimentalshaders.hpp"
#include "glesdialect.hpp"
#include <cstdlib>
#include <fstream>
#include <iostream>
#include <iterator>
#include <string>

static std::string readFile(const std::string& path)
{
    std::ifstream in(path);
    return std::string((std::istreambuf_iterator<char>(in)), std::istreambuf_iterator<char>());
}
int main(int argc, char** argv)
{
    if (argc < 3) return 2;
    // as on a context with GL_EXT_clip_cull_distance (the phones Alpha3 targets)
    // NO_CLIP_DISTANCES: a GPU without GL_EXT_clip_cull_distance (Mali, ANGLE), the clip planes done by discarding
    Alpha3::setClipDistancesAvailable(std::getenv("NO_CLIP_DISTANCES") == nullptr);
    std::string src((std::istreambuf_iterator<char>(std::cin)), std::istreambuf_iterator<char>());
    const Alpha3::GlslStage stage = std::string(argv[1]) == "vert" ? Alpha3::GlslStage::Vertex : Alpha3::GlslStage::Fragment;
    std::string name;
    for (int i = 3; i < argc; ++i)
        if (std::string(argv[i]) != "clamp")
            name = argv[i];
    if (const char* dir = std::getenv("A3_EXPERIMENTAL"); dir != nullptr && !name.empty())
    {
        static const std::string procedural = readFile(std::string(dir) + "/procedural.glsl");
        static const std::string detail = readFile(std::string(dir) + "/detail.glsl");
        static const std::string terrain = readFile(std::string(dir) + "/terrain.glsl");
        static const std::string treeSway = readFile(std::string(dir) + "/treesway.glsl");
        Alpha3::ExperimentalSettings settings;
        settings.mProcedural = settings.mDetail = settings.mTriplanar = settings.mHeightBlending = true;
        settings.mTreeSway = true;
        settings.mReplace = std::getenv("A3_BLEND") == nullptr;
        Alpha3::injectExperimentalSource(name, stage == Alpha3::GlslStage::Vertex, settings,
            { procedural, detail, terrain, treeSway }, src, [](std::string_view shader, std::string_view anchor) {
                std::cerr << "experimental: '" << anchor << "' not found in " << shader << "\n";
            });
        // A3_SWAY=1 or 2: the variant OSG compiles for leaves or a trunk (its state define, put after the #version line)
        if (const char* sway = std::getenv("A3_SWAY"); sway != nullptr)
            src.insert(src.find('\n') + 1, std::string("#define A3_TREE_SWAY ") + sway + "\n");
        // A3_MATERIAL=1..9: a procedural material's variant
        if (const char* material = std::getenv("A3_MATERIAL"); material != nullptr)
            src.insert(src.find('\n') + 1, std::string("#define A3_MATERIAL ") + material + "\n");
    }
    const std::string t = argv[2];
    const Alpha3::GlslTarget target = t == "gles" ? Alpha3::GlslTarget::Gles : t == "compat" ? Alpha3::GlslTarget::DesktopCompat : Alpha3::GlslTarget::Desktop;
    std::cout << Alpha3::translateGlsl(src, stage, target, argc > 3 && std::string(argv[3]) == "clamp");
}
