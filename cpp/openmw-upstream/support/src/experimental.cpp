// The experimental graphics (set in the F2 post-processing menu, see "the settings" below; every part its own switch, all off by default):
//  - generated normal maps: textures/foo_n.dds (or foo_nh.dds, with parallax) made from foo.dds's own colours
//    (generatedmaps.cpp) for every texture without one, and OpenMW's auto use of normal maps turned on for them
//  - generated specular maps: foo_spec.dds for objects, foo_diffusespec.dds for the terrain
//  - procedural materials: bark, wood, stone, masonry, plaster, dirt, grass, sand and metal drawn by the shaders
//    (experimental/procedural.glsl) on the textures the material rules name; the material is stored in the generated
//    normal map, so they make the normal maps too. procedural replaces textures: the texture's painted detail is not used,
//    only its average colour; otherwise the material is added to it up close.
//  - detail up close (experimental/detail.glsl), terrain triplanar and terrain height blending (experimental/terrain.glsl)
//  - ambient occlusion and look: post-processing passes (experimental/*.omwfx) put in the chain
//  - grass: blades growing out of grassy ground (grass.cpp)
//
// The shader parts are added to OpenMW's shader sources as OSG compiles them (injectExperimentalShaders, from
// translateShaderSource), at anchors in objects.frag, terrain.vert and terrain.frag; an anchor that is not found is
// logged and that part left out. The OpenMW settings a switch needs ([Shaders] auto use ..., [Post Processing]) are set
// while it is on and put back as they were when it is turned off: their values from before are kept in the cache
// directory (experimental-settings.txt).
#include "support.hpp"

#include "blobstore.hpp"
#include "experimentalshaders.hpp"
#include "experimentalsources.hpp"
#include "generatedmaps.hpp"

#include <alpha3/upstream.hpp>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cctype>
#include <cstring>
#include <fstream>
#include <iterator>
#include <locale>
#include <map>
#include <memory>
#include <mutex>
#include <optional>
#include <set>
#include <sstream>
#include <stdexcept>
#include <string>
#include <string_view>
#include <vector>

#include <osg/Shader>
#include <osg/StateSet>
#include <osg/Uniform>

#include <components/debug/debuglog.hpp>
#include <components/settings/shadermanager.hpp>
#include <components/settings/values.hpp>
#include <components/vfs/archive.hpp>
#include <components/vfs/file.hpp>
#include <components/vfs/manager.hpp>
#include <components/vfs/pathutil.hpp>
#include <components/vfs/recursivedirectoryiterator.hpp>

namespace Alpha3
{
    namespace
    {
        constexpr std::string_view sCategory = "Experimental";
        constexpr std::uint32_t sCacheVersion = 2; // 2: normal and specular maps at 512 at most
        constexpr std::string_view sAmbientOcclusionTechnique = "a3_ambientocclusion";
        constexpr std::string_view sLookTechnique = "a3_look";
        constexpr std::string_view sWaterTechnique = "a3_water";
        constexpr std::string_view sCloudsTechnique = "a3_clouds";
        constexpr std::string_view sAirTechnique = "a3_air";
        constexpr std::string_view sMistTechnique = "a3_mist";
        constexpr std::string_view sSharpenTechnique = "a3_sharpen";

        std::string fixed(float value)
        {
            std::ostringstream out;
            out.imbue(std::locale::classic());
            out.setf(std::ios::fixed);
            out.precision(4);
            out << value;
            return out.str();
        }

        std::uint64_t fnv1a(std::string_view data, std::uint64_t hash)
        {
            for (unsigned char c : data)
                hash = (hash ^ c) * 0x100000001b3ull;
            return hash;
        }

        // ---- the settings, in the F2 post-processing menu
        //
        // The settings that change while the game runs ("live") are uniforms of techniques in the F2 menu, kept by
        // OpenMW in shaders.yaml: the passes' own in their techniques (a3_water and the others, which are on while they
        // are in the chain), the rest in pages of settings only (a3_grass, a3_trees and the others, sPages) (made here,
        // never drawn). Those read only at start are in settings.cfg [Experimental], with the texts the menu cannot
        // hold (material rules, tree sway textures); a value of theirs set in the menu before is still taken if
        // settings.cfg has none. The live ones an older settings.cfg has in [Experimental] are moved to the menu once.
        constexpr std::string_view sSettingsTechnique = "a3_experimental"; // the one page there was first: what was moved there is still read, and its notes

        enum class Kind
        {
            Bool,
            Float,
        };

        struct Option
        {
            std::string_view mName; // as it was in settings.cfg, and what the menu shows
            std::string_view mHeader; // a group in the menu begins with it
            Kind mKind;
            float mDefault, mMin, mMax, mStep;
            bool mLive;
            std::string_view mDescription;
        };

        constexpr Option sOptions[] = {
            { "generated normal maps", "Generated maps", Kind::Bool, 0, 0, 1, 1, false,
                "Normal maps made from every texture's own colours, for those without one" },
            { "generated normal maps parallax", "", Kind::Bool, 0, 0, 1, 1, false, "Depth (parallax) in them too" },
            { "generated normal map strength", "", Kind::Float, 1, 0, 3, 0.05f, false, "How deep their relief" },
            { "generated maps sharp", "", Kind::Bool, 1, 0, 1, 1, false,
                "Crisp, detailed relief, as in an upscaled texture (off: softer, closer to vanilla)" },
            { "generated specular maps", "", Kind::Bool, 0, 0, 1, 1, false, "Shine maps too" },
            { "procedural materials", "Procedural materials", Kind::Bool, 0, 0, 1, 1, true,
                "Bark, wood, stone, masonry, plaster, dirt, grass, sand and metal drawn by the shaders (turned on while the game runs only if it was on at start)" },
            { "procedural replaces textures", "", Kind::Bool, 1, 0, 1, 1, true,
                "Only the texture's colour kept (off: the material added to its painted detail up close)" },
            { "procedural strength", "", Kind::Float, 1, 0, 2, 0.05f, true, "How strong the materials" },
            { "procedural distance", "", Kind::Float, 3000, 500, 20000, 250, true, "How far they reach" },
            { "detail up close", "", Kind::Bool, 0, 0, 1, 1, true, "Fine detail on every texture up close (turned on while the game runs only if it was on at start)" },
            { "detail strength", "", Kind::Float, 1, 0, 2, 0.05f, true, "How strong" },
            { "detail distance", "", Kind::Float, 1500, 200, 6000, 100, true, "How far it reaches" },
            { "terrain triplanar", "Terrain", Kind::Bool, 0, 0, 1, 1, false,
                "No stretched textures on cliffs and steep slopes" },
            { "terrain height blending", "", Kind::Bool, 0, 0, 1, 1, true,
                "Ground textures meeting by their height (stones standing out of the sand) and not a soft fade (turned on while the game runs only if it was on at start)" },
            { "terrain height blend sharpness", "", Kind::Float, 2.5f, 0.5f, 8, 0.1f, true, "How sharp they meet" },
            { "grass", "Grass", Kind::Bool, 0, 0, 1, 1, true, "Blades of grass growing out of grassy ground" },
            { "grass density", "", Kind::Float, 1, 0.05f, 4, 0.05f, false, "How thick" },
            { "grass height", "", Kind::Float, 1, 0.2f, 3, 0.05f, false, "How tall" },
            { "grass distance", "", Kind::Float, 2500, 500, 8000, 100, false, "How far it is drawn" },
            { "grass wind", "", Kind::Float, 1, 0, 3, 0.05f, true, "How much it sways" },
            { "grass storm", "", Kind::Float, 1, 0, 3, 0.05f, true, "How hard it leans in storms" },
            { "grass clearance", "", Kind::Bool, 1, 0, 1, 1, true, "None through rocks, walls and floors" },
            { "grass variety", "", Kind::Bool, 1, 0, 1, 1, false, "Seed stalks, broad clumps and drier patches" },
            { "grass soil", "", Kind::Bool, 1, 0, 1, 1, false,
                "Taller and thicker on green ground, sparse on ash, in meadow patches" },
            { "grass shapes", "", Kind::Bool, 1, 0, 1, 1, false, "Leaf rosettes, sedge, moss, lichen and mushrooms among it" },
            { "grass antialiasing", "", Kind::Bool, 1, 0, 1, 1, false, "No blade thinner than a pixel, far off" },
            { "grass bugs", "Critters", Kind::Bool, 1, 0, 1, 1, true,
                "Ants, beetles, butterflies, bees, dragonflies, gnats, fireflies, frogs and moths in the grass" },
            { "grass bug density", "", Kind::Float, 1, 0, 4, 0.05f, false, "How many" },
            { "grass birds", "", Kind::Bool, 1, 0, 1, 1, true, "Flocks of birds wheeling overhead" },
            { "tree sway", "Trees", Kind::Bool, 0, 0, 1, 1, true, "Leaves and branches moving in the wind (turned on while the game runs only if it was on at start)" },
            { "tree sway strength", "", Kind::Float, 2.5f, 0, 4, 0.05f, true, "How much" },
            { "tree trunk sway", "", Kind::Float, 1.5f, 0, 2, 0.05f, true,
                "How much the trunks bend (0 at start: they stay still until a restart)" },
            { "tree fuller leaves", "", Kind::Bool, 0, 0, 1, 1, true,
                "Thicker canopies, colour varying from cluster to cluster, the sun shining through (turned on while the game runs only if it was on at start)" },
            { "tree leaf translucency", "", Kind::Float, 1, 0, 2, 0.05f, true, "How much the sun shines through" },
            { "tree leaf variation", "", Kind::Float, 1, 0, 2, 0.05f, true, "How much their colour varies" },
            { "test weather", "Testing", Kind::Float, -1, -1, 9, 1, true,
                "Hold the weather, to test with: -1 the region's own, 0 clear, 1 cloudy, 2 foggy, 3 overcast, 4 rain, "
                "5 thunderstorm, 6 ash storm, 7 blight storm, 8 snow, 9 blizzard. Set it back to -1 after" },
            { "wet ground", "Wet ground", Kind::Bool, 0, 0, 1, 1, false,
                "The ground darkening and shining in the rain, drying after (turned off while the game runs: dry)" },
            { "wet ground puddles", "", Kind::Bool, 1, 0, 1, 1, true, "Puddles in hollows, rippling (turned on while the game runs only if it was on at start)" },
            { "wet ground dry under objects", "", Kind::Bool, 1, 0, 1, 1, true, "Dry under roofs, trees and rocks (turned on while the game runs only if it was on at start)" },
        };

        // The settings of the passes as they were in settings.cfg, now their techniques' uniforms
        struct PassOption
        {
            std::string_view mName;
            std::string_view mTechnique;
            std::string_view mUniform;
            char mKind; // b, f, i
        };

        constexpr PassOption sPassOptions[] = {
            { "water reflections", sWaterTechnique, "uA3ReflectionsOn", 'b' },
            { "water reflection strength", sWaterTechnique, "uA3Reflection", 'f' },
            { "water reflection steps", sWaterTechnique, "uA3Steps", 'i' },
            { "water waves", sWaterTechnique, "uA3Waves", 'f' },
            { "water shoreline", sWaterTechnique, "uA3ShorelineOn", 'b' },
            { "water shoreline foam", sWaterTechnique, "uA3Foam", 'f' },
            { "water shoreline width", sWaterTechnique, "uA3ShoreWidth", 'f' },
            { "water mist", sWaterTechnique, "uA3MistOn", 'b' },
            { "water mist strength", sWaterTechnique, "uA3Mist", 'f' },
            { "water tide", sWaterTechnique, "uA3TideOn", 'b' },
            { "water tide height", sWaterTechnique, "uA3Tide", 'f' },
            { "water tide period", sWaterTechnique, "uA3TidePeriod", 'f' },
            { "water realistic", sWaterTechnique, "uA3LiquidOn", 'b' },
            { "water realistic strength", sWaterTechnique, "uA3Liquid", 'f' },
            { "cloud coverage", sCloudsTechnique, "uA3Coverage", 'f' },
            { "cloud height", sCloudsTechnique, "uA3Height", 'f' },
            { "cloud thickness", sCloudsTechnique, "uA3Thickness", 'f' },
            { "cloud speed", sCloudsTechnique, "uA3Speed", 'f' },
            { "cloud density", sCloudsTechnique, "uA3Density", 'f' },
            { "cloud quality", sCloudsTechnique, "uA3Steps", 'i' },
            { "cloud light steps", sCloudsTechnique, "uA3LightSteps", 'i' },
            { "clouds follow weather", sCloudsTechnique, "uA3FollowWeather", 'b' },
            { "wind dust", sAirTechnique, "uA3DustOn", 'b' },
            { "wind dust strength", sAirTechnique, "uA3Dust", 'f' },
            { "light shafts", sAirTechnique, "uA3ShaftsOn", 'b' },
            { "light shaft strength", sAirTechnique, "uA3Shafts", 'f' },
            { "sharpen strength", sSharpenTechnique, "uA3Sharpen", 'f' },
        };

        // the uniform's name: "grass bug density" is a3_grass_bug_density
        std::string uniformName(std::string_view name)
        {
            std::string out = "a3_";
            for (char c : name)
                out += c == ' ' ? '_' : c;
            return out;
        }

        // The settings pages: each group its own technique in the menu's list (the critters with the grass)
        struct Page
        {
            std::string_view mTechnique;
            std::string_view mFirstHeader; // the group it begins with
            std::string_view mDescription;
        };

        constexpr Page sPages[] = {
            { "a3_maps", "Generated maps", "Normal and shine maps made from the textures' own colours" },
            { "a3_materials", "Procedural materials", "Materials drawn by the shaders, and fine detail up close" },
            { "a3_terrain", "Terrain", "Cliffs without stretched textures, ground textures meeting by their height" },
            { "a3_grass", "Grass", "Grass growing out of grassy ground, bugs, frogs, moths and birds" },
            { "a3_trees", "Trees", "Trees swaying in the wind, fuller leaves" },
            { "a3_testing", "Testing", "Hold the weather, to test with" },
            { "a3_wetground", "Wet ground", "The ground wet and shining in the rain, puddles, drying after" },
        };

        // the page an option is on: the one whose group it is in or follows
        std::string_view pageOf(const Option& option)
        {
            std::string_view page = sPages[0].mTechnique;
            for (const Option& o : sOptions)
            {
                for (const Page& p : sPages)
                    if (o.mHeader == p.mFirstHeader)
                        page = p.mTechnique;
                if (&o == &option)
                    break;
            }
            return page;
        }

        const Option& option(std::string_view name)
        {
            for (const Option& o : sOptions)
                if (o.mName == name)
                    return o;
            throw std::logic_error("no experimental option " + std::string(name));
        }

        bool shaderSettingsLoaded()
        {
            return !Settings::ShaderManager::get().getRoot().IsNull();
        }

        std::optional<float> shaderValue(std::string_view technique, std::string_view uniform, char kind)
        {
            if (!shaderSettingsLoaded())
                return std::nullopt;
            auto& manager = Settings::ShaderManager::get();
            const std::string t(technique), u(uniform);
            if (kind == 'b')
            {
                if (const auto value = manager.getValue<bool>(t, u))
                    return *value ? 1.f : 0.f;
                return std::nullopt;
            }
            if (kind == 'i')
            {
                if (const auto value = manager.getValue<int>(t, u))
                    return static_cast<float>(*value);
                return std::nullopt;
            }
            return manager.getValue<float>(t, u);
        }

        bool inChain(std::string_view technique)
        {
            const auto& chain = Settings::postProcessing().mChain.get();
            return std::find(chain.begin(), chain.end(), technique) != chain.end();
        }

        float optionValue(const Option& o)
        {
            const char kind = o.mKind == Kind::Bool ? 'b' : 'f';
            // what only takes effect at a restart is in settings.cfg [Experimental], not the F2 menu (which has only
            // what changes live); a value set in the menu before is still taken if settings.cfg has none
            if (!o.mLive)
            {
                const std::string text = setting(sCategory, o.mName, "");
                if (!text.empty())
                {
                    const float value = o.mKind == Kind::Bool ? (settingBool(sCategory, o.mName, o.mDefault > 0.5f) ? 1.f : 0.f)
                                                              : settingFloat(sCategory, o.mName, o.mDefault);
                    return std::clamp(value, o.mMin, o.mMax);
                }
            }
            // (moved over first into the one page there was)
            // a page of the menu counts only while it is enabled (in the chain, the right-hand list): otherwise its
            // switches are off and its values as they come (not those read at start from settings.cfg, which are
            // not on the page)
            if (o.mLive && !inChain(pageOf(o)))
                return o.mKind == Kind::Bool ? 0.f : std::clamp(o.mDefault, o.mMin, o.mMax);
            const std::string uniform = uniformName(o.mName);
            std::optional<float> value = shaderValue(pageOf(o), uniform, kind);
            if (!value)
                value = shaderValue(sSettingsTechnique, uniform, kind);
            return std::clamp(value.value_or(o.mDefault), o.mMin, o.mMax);
        }

        bool optionBool(std::string_view name)
        {
            return optionValue(option(name)) > 0.5f;
        }

        float optionFloat(std::string_view name)
        {
            return optionValue(option(name));
        }

        // a pass's switch: on while the pass is in the chain and its own toggle (true unless set otherwise) is
        bool passOn(std::string_view technique, std::string_view toggle = {})
        {
            return inChain(technique)
                && (toggle.empty() || shaderValue(technique, toggle, 'b').value_or(1.f) > 0.5f);
        }

        // Once: what settings.cfg [Experimental] held, into the menu's settings, and the passes it had on into the chain
        void migrateLegacySettings()
        {
            if (!shaderSettingsLoaded() || shaderValue(sSettingsTechnique, "a3_migrated", 'b'))
                return;
            auto& manager = Settings::ShaderManager::get();
            const std::string technique(sSettingsTechnique);
            unsigned moved = 0;
            for (const Option& o : sOptions)
            {
                if (!o.mLive || setting(sCategory, o.mName, "").empty())
                    continue;
                if (o.mKind == Kind::Bool)
                    manager.setValue(std::string(pageOf(o)), uniformName(o.mName), settingBool(sCategory, o.mName, o.mDefault > 0.5f));
                else
                    manager.setValue(std::string(pageOf(o)), uniformName(o.mName),
                        std::clamp(settingFloat(sCategory, o.mName, o.mDefault), o.mMin, o.mMax));
                ++moved;
            }
            for (const PassOption& o : sPassOptions)
            {
                if (setting(sCategory, o.mName, "").empty())
                    continue;
                const std::string t(o.mTechnique), u(o.mUniform);
                if (o.mKind == 'b')
                    manager.setValue(t, u, settingBool(sCategory, o.mName, true));
                else if (o.mKind == 'i')
                    manager.setValue(t, u, static_cast<int>(settingFloat(sCategory, o.mName, 0.f)));
                else
                    manager.setValue(t, u, settingFloat(sCategory, o.mName, 0.f));
                ++moved;
            }
            if (moved > 0)
            {
                // the chain as it was made from the switches: ambient occlusion first (before anything changes the
                // colours it darkens), then the water (its reflections of the scene as it is), clouds, the air,
                // sharpening, the look last
                auto on = [](std::string_view name) { return settingBool(sCategory, name, false); };
                const bool water = on("water reflections") || on("water shoreline") || on("water mist") || on("water tide")
                    || on("water realistic");
                const std::vector<std::pair<std::string_view, bool>> ours = { { sAmbientOcclusionTechnique,
                                                                                   on("ambient occlusion") },
                    { sWaterTechnique, water }, { sCloudsTechnique, on("volumetric clouds") },
                    { sAirTechnique, on("wind dust") || on("light shafts") }, { sSharpenTechnique, on("sharpen") },
                    { sLookTechnique, on("look") } };
                auto& post = Settings::postProcessing();
                std::vector<std::string> chain;
                for (const std::string& name : post.mChain.get())
                    if (std::none_of(ours.begin(), ours.end(), [&](const auto& pass) { return pass.first == name; }))
                        chain.push_back(name);
                if (ours[0].second)
                    chain.insert(chain.begin(), std::string(ours[0].first));
                for (std::size_t i = 1; i < ours.size(); ++i)
                    if (ours[i].second)
                        chain.push_back(std::string(ours[i].first));
                post.mChain.set(chain);
                // kept here too until settings.cfg has it (it is written when the game ends normally or the menu
                // closes; a game killed first would lose it)
                std::string joined;
                for (const std::string& name : chain)
                    joined += (joined.empty() ? "" : ",") + name;
                manager.setValue(technique, std::string("a3_chain"), joined);
            }
            manager.setValue(technique, std::string("a3_migrated"), true);
            manager.save();
            Log(Debug::Info) << "Experimental: " << moved
                             << " settings moved from settings.cfg [Experimental] to the F2 menu (shaders.yaml)";
        }

        bool pageHasLive(const Page& page)
        {
            for (const Option& o : sOptions)
                if (o.mLive && pageOf(o) == page.mTechnique)
                    return true;
            return false;
        }

        // A settings-only technique: a uniform for each option on its page that changes live, in groups
        std::string settingsTechnique(const Page& page)
        {
            std::ostringstream out;
            out.imbue(std::locale::classic());
            std::string_view header;
            for (const Option& o : sOptions)
            {
                if (!o.mHeader.empty())
                    header = o.mHeader;
                if (!o.mLive || pageOf(o) != page.mTechnique)
                    continue;
                const bool isBool = o.mKind == Kind::Bool;
                out << (isBool ? "uniform_bool " : "uniform_float ") << uniformName(o.mName) << " {\n";
                if (isBool)
                    out << "    default = " << (o.mDefault > 0.5f ? "true" : "false") << ";\n";
                else
                    out << "    default = " << fixed(o.mDefault) << ";\n    min = " << fixed(o.mMin)
                        << ";\n    max = " << fixed(o.mMax) << ";\n    step = " << fixed(o.mStep) << ";\n";
                if (!header.empty())
                    out << "    header = \"" << header << "\";\n";
                header = {};
                std::string label(o.mName);
                label[0] = static_cast<char>(std::toupper(static_cast<unsigned char>(label[0])));
                out << "    display_name = \"" << label << "\";\n";
                out << "    description = \"" << o.mDescription << "\";\n}\n\n";
            }
            out << "fragment settings {\n"
                   "    // Not drawn: only the settings of Alpha3's experimental graphics (src/experimental.cpp)\n"
                   "    omw_In vec2 omw_TexCoord;\n"
                   "    void main() { omw_FragColor = omw_GetLastShader(omw_TexCoord); }\n"
                   "}\n\n"
                   "technique {\n"
                   "    description = \"Alpha3: "
                << page.mDescription
                << ". Only settings, it draws nothing and need not be active.\";\n"
                   "    version = \"1.0\";\n"
                   "    author = \"Alpha3\";\n"
                   "    passes = settings;\n"
                   "}\n";
            return out.str();
        }

        // The chain the settings moved over with, until settings.cfg has it
        void applyPendingChain()
        {
            if (!shaderSettingsLoaded())
                return;
            auto& manager = Settings::ShaderManager::get();
            const std::string technique(sSettingsTechnique);
            const std::string pending = manager.getValue<std::string>(technique, "a3_chain").value_or("");
            if (pending.empty())
                return;
            std::vector<std::string> chain;
            for (std::size_t start = 0; start <= pending.size();)
            {
                std::size_t end = pending.find(',', start);
                if (end == std::string::npos)
                    end = pending.size();
                if (end > start)
                    chain.push_back(pending.substr(start, end - start));
                start = end + 1;
            }
            auto& post = Settings::postProcessing();
            if (post.mChain.get() == chain)
            {
                manager.setValue(technique, std::string("a3_chain"), std::string());
                manager.save();
            }
            else
                post.mChain.set(chain);
        }

        // What was moved into the one page there was at first (a3_experimental) onto the page each setting is on now, so
        // the menu shows it there
        void movePageValues()
        {
            if (!shaderSettingsLoaded())
                return;
            auto& manager = Settings::ShaderManager::get();
            unsigned moved = 0;
            for (const Option& o : sOptions)
            {
                const std::string name = uniformName(o.mName);
                const char kind = o.mKind == Kind::Bool ? 'b' : 'f';
                if (shaderValue(pageOf(o), name, kind))
                    continue;
                const auto old = shaderValue(sSettingsTechnique, name, kind);
                if (!old)
                    continue;
                if (kind == 'b')
                    manager.setValue(std::string(pageOf(o)), name, *old > 0.5f);
                else
                    manager.setValue(std::string(pageOf(o)), name, *old);
                ++moved;
            }
            if (moved > 0)
                manager.save();
        }

        ExperimentalSettings readSettings()
        {
            migrateLegacySettings();
            movePageValues();
            applyPendingChain();
            ExperimentalSettings s;
            s.mNormalMaps = optionBool("generated normal maps");
            s.mParallax = optionBool("generated normal maps parallax");
            s.mNormalStrength = optionFloat("generated normal map strength");
            s.mSharpMaps = optionBool("generated maps sharp");
            s.mSpecular = optionBool("generated specular maps");
            s.mRules = setting(sCategory, "material rules", "");
            s.mProcedural = optionBool("procedural materials");
            s.mReplace = optionBool("procedural replaces textures");
            s.mProceduralStrength = optionFloat("procedural strength");
            s.mProceduralDistance = optionFloat("procedural distance");
            s.mDetail = optionBool("detail up close");
            s.mDetailStrength = optionFloat("detail strength");
            s.mDetailDistance = optionFloat("detail distance");
            s.mTriplanar = optionBool("terrain triplanar");
            s.mHeightBlending = optionBool("terrain height blending");
            s.mHeightBlendSharpness = optionFloat("terrain height blend sharpness");
            s.mGrass = optionBool("grass");
            s.mGrassDensity = optionFloat("grass density");
            s.mGrassHeight = optionFloat("grass height");
            s.mGrassDistance = optionFloat("grass distance");
            s.mGrassWind = optionFloat("grass wind");
            s.mGrassClearance = optionBool("grass clearance");
            s.mGrassStorm = optionFloat("grass storm");
            s.mGrassVariety = optionBool("grass variety");
            s.mGrassAntialiasing = optionBool("grass antialiasing");
            s.mGrassSoil = optionBool("grass soil");
            s.mGrassShapes = optionBool("grass shapes");
            s.mGrassBugs = optionBool("grass bugs");
            s.mGrassBirds = optionBool("grass birds");
            s.mGrassBugDensity = optionFloat("grass bug density");
            s.mTreeSway = optionBool("tree sway");
            s.mTreeSwayStrength = optionFloat("tree sway strength");
            s.mTreeSwayTextures = setting(sCategory, "tree sway textures", "");
            s.mTrunkSway = optionFloat("tree trunk sway");
            s.mTrunkSwayTextures = setting(sCategory, "tree trunk textures", "");
            s.mTreeFull = optionBool("tree fuller leaves");
            s.mTreeTranslucency = optionFloat("tree leaf translucency");
            s.mTreeLeafVariation = optionFloat("tree leaf variation");
            s.mWetGround = optionBool("wet ground");
            s.mWetPuddles = optionBool("wet ground puddles");
            s.mWetDryUnder = optionBool("wet ground dry under objects");
            // the passes: on while in the chain
            s.mAmbientOcclusion = passOn(sAmbientOcclusionTechnique);
            s.mLook = passOn(sLookTechnique);
            s.mSharpen = passOn(sSharpenTechnique);
            s.mWaterReflections = passOn(sWaterTechnique, "uA3ReflectionsOn");
            s.mShoreline = passOn(sWaterTechnique, "uA3ShorelineOn");
            s.mWaterMist = passOn(sWaterTechnique, "uA3MistOn");
            s.mWaterTide = passOn(sWaterTechnique, "uA3TideOn");
            s.mWaterLiquid = passOn(sWaterTechnique, "uA3LiquidOn");
            s.mVolumetricClouds = passOn(sCloudsTechnique);
            s.mGroundMist = passOn(sMistTechnique);
            s.mWindDust = passOn(sAirTechnique, "uA3DustOn");
            s.mLightShafts = passOn(sAirTechnique, "uA3ShaftsOn");
            return s;
        }

        // ---- the values the shaders read while the game runs, from the menu

        // A value the shaders read while the game runs: the option's value (a switch: 1 or 0), zero while the switch it
        // belongs to (mGate) is off
        struct LiveUniform
        {
            std::string_view mOption;
            const char* mUniform;
            std::string_view mGate;
            osg::ref_ptr<osg::Uniform> mValue;
        };

        float liveValue(const LiveUniform& u)
        {
            if (!u.mGate.empty() && !optionBool(u.mGate))
                return 0.f;
            return optionFloat(u.mOption);
        }

        std::vector<LiveUniform>& liveUniforms()
        {
            static std::vector<LiveUniform> uniforms = [] {
                std::vector<LiveUniform> list = {
                    { "procedural strength", "a3ProceduralStrength", "procedural materials", {} },
                    { "procedural distance", "a3ProceduralDistance", "", {} },
                    { "procedural replaces textures", "a3ProceduralReplace", "procedural materials", {} },
                    { "detail strength", "a3DetailStrength", "detail up close", {} },
                    { "detail distance", "a3DetailDistance", "", {} },
                    { "terrain height blending", "a3HeightBlendOn", "", {} },
                    { "terrain height blend sharpness", "a3HeightSharpness", "", {} },
                    { "tree sway strength", "a3TreeSwayStrength", "tree sway", {} },
                    { "tree trunk sway", "a3TrunkSway", "tree sway", {} },
                    { "tree fuller leaves", "a3LeafOn", "tree sway", {} },
                    { "tree leaf translucency", "a3LeafTranslucency", "", {} },
                    { "tree leaf variation", "a3LeafVariation", "", {} },
                    { "grass wind", "a3GrassWind", "", {} },
                    { "grass storm", "a3GrassStorm", "", {} },
                    { "grass birds", "a3Birds", "", {} },
                    { "wet ground puddles", "a3Puddles", "", {} },
                };
                for (LiveUniform& u : list)
                {
                    u.mValue = new osg::Uniform(u.mUniform, liveValue(u));
                    u.mValue->setDataVariance(osg::Object::DYNAMIC);
                }
                return list;
            }();
            return uniforms;
        }

        // The generated maps are made for the procedural materials too: their material is stored in the normal map
        bool normalMapsWanted(const ExperimentalSettings& s)
        {
            return s.mNormalMaps || s.mProcedural;
        }

        // ---- the OpenMW settings the switches need

        // One setting a switch sets, as text both ways
        struct OwnedSetting
        {
            std::string mKey; // "category/name"
            bool mWanted; // the switch that needs it is on
            std::string mValue; // what it is set to then
            std::function<std::string()> mGet;
            std::function<void(const std::string&)> mSet;
        };

        OwnedSetting ownedBool(std::string key, bool wanted, Settings::SettingValue<bool>& value)
        {
            return OwnedSetting{ std::move(key), wanted, "true", [&value] { return value.get() ? "true" : "false"; },
                [&value](const std::string& text) { value.set(text == "true"); } };
        }

        OwnedSetting ownedString(std::string key, bool wanted, std::string to, Settings::SettingValue<std::string>& value)
        {
            return OwnedSetting{ std::move(key), wanted, std::move(to), [&value] { return value.get(); },
                [&value](const std::string& text) { value.set(text); } };
        }

        OwnedSetting ownedInt(std::string key, bool wanted, int to, Settings::SettingValue<int>& value)
        {
            return OwnedSetting{ std::move(key), wanted, std::to_string(to),
                [&value] { return std::to_string(value.get()); },
                [&value](const std::string& text) { value.set(std::atoi(text.c_str())); } };
        }

        bool waterPassWanted(const ExperimentalSettings& s)
        {
            return s.mWaterReflections || s.mShoreline || s.mWaterMist || s.mWaterTide || s.mWaterLiquid;
        }

        bool postProcessingWanted(const ExperimentalSettings& s)
        {
            return s.mAmbientOcclusion || s.mLook || waterPassWanted(s) || s.mVolumetricClouds || s.mGroundMist || s.mWindDust
                || s.mLightShafts || s.mSharpen;
        }

        std::filesystem::path ownedRecordPath()
        {
            return cacheDirectory() / "experimental-settings.txt";
        }

        // "key=value" lines: the values from before a switch took the setting over
        std::map<std::string, std::string> readOwnedRecord()
        {
            std::map<std::string, std::string> record;
            std::ifstream in(ownedRecordPath());
            std::string line;
            while (std::getline(in, line))
            {
                const std::size_t equals = line.find('=');
                if (equals != std::string::npos)
                    record[line.substr(0, equals)] = line.substr(equals + 1);
            }
            return record;
        }

        void writeOwnedRecord(const std::map<std::string, std::string>& record)
        {
            std::error_code ec;
            if (record.empty())
            {
                std::filesystem::remove(ownedRecordPath(), ec);
                return;
            }
            std::filesystem::create_directories(cacheDirectory(), ec);
            std::ofstream out(ownedRecordPath(), std::ios::trunc);
            for (const auto& [key, value] : record)
                out << key << '=' << value << '\n';
        }

        // A pass in the chain is drawn, a full screen's work, even with every one of its own switches off (the water's
        // and the air's): those are taken out of the chain at the start, so that they cost nothing (added again in
        // the F2 menu, they are drawn again)
        void dropIdlePasses(const ExperimentalSettings& s)
        {
            const std::vector<std::pair<std::string_view, bool>> idle
                = { { sWaterTechnique, !waterPassWanted(s) }, { sAirTechnique, !s.mWindDust && !s.mLightShafts } };
            auto& post = Settings::postProcessing();
            std::vector<std::string> chain = post.mChain.get();
            const std::size_t before = chain.size();
            for (const auto& [technique, off] : idle)
                if (off)
                    chain.erase(std::remove(chain.begin(), chain.end(), technique), chain.end());
            if (chain.size() == before)
                return;
            post.mChain.set(chain);
            Log(Debug::Info) << "Experimental: " << before - chain.size()
                             << " pass(es) with every switch off taken out of the post-processing chain";
        }

        void applyOwnedSettings(const ExperimentalSettings& s)
        {
            dropIdlePasses(s);
            auto& shaders = Settings::shaders();
            auto& post = Settings::postProcessing();
            const bool normalMaps = normalMapsWanted(s);
            const std::vector<OwnedSetting> owned = {
                ownedBool("Shaders/auto use object normal maps", normalMaps, shaders.mAutoUseObjectNormalMaps),
                ownedBool("Shaders/auto use terrain normal maps", normalMaps, shaders.mAutoUseTerrainNormalMaps),
                ownedString("Shaders/normal map pattern", normalMaps, "_n", shaders.mNormalMapPattern),
                ownedString("Shaders/normal height map pattern", normalMaps, "_nh", shaders.mNormalHeightMapPattern),
                ownedBool("Shaders/auto use object specular maps", s.mSpecular, shaders.mAutoUseObjectSpecularMaps),
                ownedBool("Shaders/auto use terrain specular maps", s.mSpecular, shaders.mAutoUseTerrainSpecularMaps),
                ownedString("Shaders/specular map pattern", s.mSpecular, "_spec", shaders.mSpecularMapPattern),
                ownedString("Shaders/terrain specular map pattern", s.mSpecular, "_diffusespec",
                    shaders.mTerrainSpecularMapPattern),
                ownedBool("Post Processing/enabled", postProcessingWanted(s), post.mEnabled),
                // the screen space reflections draw what is on screen; OpenMW's own reflection (slow: the scene drawn
                // again, mirrored) keeps only the sky, which fills in where they have nothing
                ownedBool("Water/shader", s.mWaterReflections, Settings::water().mShader),
                ownedInt("Water/reflection detail", s.mWaterReflections, 0, Settings::water().mReflectionDetail),
            };
            std::map<std::string, std::string> record = readOwnedRecord();
            for (const OwnedSetting& setting : owned)
            {
                const auto before = record.find(setting.mKey);
                if (setting.mWanted)
                {
                    if (before == record.end())
                        record[setting.mKey] = setting.mGet();
                    setting.mSet(setting.mValue);
                }
                else if (before != record.end())
                {
                    setting.mSet(before->second);
                    record.erase(before);
                }
            }
            writeOwnedRecord(record);

            // (the passes are in the chain or not as the F2 menu puts them)
        }

        // ---- the generated maps in the VFS

        enum class MapKind
        {
            Normal,
            Specular,
            DiffuseSpecular,
        };

        class MapCache
        {
        public:
            static MapCache& get()
            {
                static MapCache cache;
                return cache;
            }

            bool read(const BlobKey& key, std::string& data) const
            {
                if (!mStore)
                    return false;
                const std::size_t size = mStore->size(key);
                if (size == 0)
                    return false;
                data.resize(size);
                return mStore->read(key, 0, data.data(), size);
            }

            void store(const BlobKey& key, const std::string& data)
            {
                if (mStore)
                    mStore->append(key, data.data(), data.size());
            }

        private:
            MapCache()
            {
                const std::filesystem::path dir = cacheDirectory() / "generatedmaps";
                std::error_code ec;
                std::filesystem::create_directories(dir, ec);
                if (ec)
                    return;
                auto store = std::make_unique<BlobStore>(dir / "generatedmaps.bin", 0x50414d47, sCacheVersion); // "GMAP"
                if (store->valid())
                    mStore = std::move(store);
            }

            std::unique_ptr<BlobStore> mStore;
        };

        // A map of one texture, made the first time it is opened (or found in the cache)
        class GeneratedMapFile final : public VFS::File
        {
        public:
            GeneratedMapFile(VFS::File* source, std::string sourcePath, MapKind kind, Material material,
                std::uint64_t settingsHash, std::string stem)
                : mSource(source)
                , mSourcePath(std::move(sourcePath))
                , mKind(kind)
                , mMaterial(material)
                , mSettingsHash(settingsHash)
                , mStem(std::move(stem))
            {
            }

            // Read from the cache each time (made into it the first time): the maps are not kept here, a few thousand
            // of them would hold hundreds of megabytes the engine has already uploaded
            Files::IStreamPtr open() override
            {
                std::string data;
                {
                    std::lock_guard lock(mMutex);
                    data = make();
                }
                if (data.empty())
                    data = flat();
                return std::make_unique<std::istringstream>(std::move(data));
            }

            std::filesystem::file_time_type getLastModified() const override { return mSource->getLastModified(); }
            std::string getStem() const override { return mStem; }

        private:
            std::string make()
            {
                const auto start = std::chrono::steady_clock::now();
                std::string data;
                const std::uint64_t time
                    = static_cast<std::uint64_t>(mSource->getLastModified().time_since_epoch().count());
                const BlobKey key{ fnv1a(mSourcePath, 0xcbf29ce484222325ull) ^ time,
                    fnv1a(mSourcePath, 0x84222325cbf29ce4ull) ^ mSettingsHash ^ static_cast<std::uint64_t>(mKind) };
                if (MapCache::get().read(key, data))
                    return data;

                RgbaImage diffuse;
                try
                {
                    Files::IStreamPtr stream = mSource->open();
                    if (!readTextureForCpu(*stream, "dds", diffuse.mWidth, diffuse.mHeight, diffuse.mRgba))
                    {
                        Log(Debug::Warning) << "Generated maps: " << mSourcePath << " cannot be read";
                        return {};
                    }
                }
                catch (const std::exception& e)
                {
                    Log(Debug::Warning) << "Generated maps: " << mSourcePath << ": " << e.what();
                    return {};
                }
                // the normal and specular maps are made at 512 at most: they hold the texture's broad shapes (the
                // procedural surfaces add the fine ones), and each is a quarter of the memory and conversion of a 1024
                // one. The terrain's diffuse specular map is its colour as well, and keeps up to 1024.
                const int largest = mKind == MapKind::DiffuseSpecular ? 1024 : 512;
                while (diffuse.mWidth > largest || diffuse.mHeight > largest)
                    diffuse = halve(diffuse);

                const ExperimentalSettings& s = experimentalSettings();
                MapSettings settings;
                settings.mNormalStrength = s.mNormalStrength;
                settings.mSharp = s.mSharpMaps;
                const RgbaImage normalHeight = makeNormalHeight(diffuse, settings);
                switch (mKind)
                {
                    case MapKind::Normal:
                        data = writeDds(forParallax(normalHeight, settings), true, mMaterial, grainAlongU(diffuse));
                        break;
                    case MapKind::Specular:
                        data = writeDds(makeSpecular(diffuse, normalHeight, mMaterial), false, mMaterial, false);
                        break;
                    case MapKind::DiffuseSpecular:
                        data = writeDds(makeDiffuseSpecular(diffuse, normalHeight, mMaterial), false, mMaterial, false);
                        break;
                }
                MapCache::get().store(key, data);
                Log(Debug::Verbose) << "Generated maps: " << mStem << " made in "
                                    << std::chrono::duration<double, std::milli>(
                                           std::chrono::steady_clock::now() - start)
                                           .count()
                                    << " ms";
                return data;
            }

            // What stands in for a map that could not be made (a texture that cannot be read for the CPU, such as a
            // .ktx one): a flat normal map, no specular, or the texture as it is for the terrain
            std::string flat() const
            {
                if (mKind == MapKind::DiffuseSpecular)
                {
                    Files::IStreamPtr stream = mSource->open();
                    return std::string(std::istreambuf_iterator<char>(*stream), std::istreambuf_iterator<char>());
                }
                RgbaImage image;
                image.mWidth = image.mHeight = 4;
                const std::uint8_t texel[4] = { 128, 128, 255, 128 };
                const std::uint8_t black[4] = { 0, 0, 0, 0 };
                for (int i = 0; i < 16; ++i)
                    image.mRgba.insert(image.mRgba.end(), mKind == MapKind::Normal ? texel : black,
                        (mKind == MapKind::Normal ? texel : black) + 4);
                return writeDds(image, mKind == MapKind::Normal, Material::None, false);
            }

            // half the size, a box filter
            static RgbaImage halve(const RgbaImage& image)
            {
                RgbaImage out;
                out.mWidth = std::max(1, image.mWidth / 2);
                out.mHeight = std::max(1, image.mHeight / 2);
                out.mRgba.resize(static_cast<std::size_t>(out.mWidth) * out.mHeight * 4);
                for (int y = 0; y < out.mHeight; ++y)
                    for (int x = 0; x < out.mWidth; ++x)
                        for (int c = 0; c < 4; ++c)
                        {
                            int sum = 0;
                            for (int dy = 0; dy < 2; ++dy)
                                for (int dx = 0; dx < 2; ++dx)
                                {
                                    const int sx = std::min(image.mWidth - 1, x * 2 + dx);
                                    const int sy = std::min(image.mHeight - 1, y * 2 + dy);
                                    sum += image.mRgba[(static_cast<std::size_t>(sy) * image.mWidth + sx) * 4 + c];
                                }
                            out.mRgba[(static_cast<std::size_t>(y) * out.mWidth + x) * 4 + c]
                                = static_cast<std::uint8_t>(sum / 4);
                        }
                return out;
            }

            VFS::File* mSource; // belongs to an archive listed before this one
            std::string mSourcePath;
            MapKind mKind;
            Material mMaterial;
            std::uint64_t mSettingsHash;
            std::string mStem;
            std::mutex mMutex; // one thread makes it, the others wait and then read it from the cache
        };

        // A file of the library's own (the post-processing passes)
        // Where a pass can be put to be used in place of the built-in one (Alpha3/shaders/a3_water.omwfx, say): edited
        // there, it is read again while the game runs with the shaders' hot reload on (in the console, "lua player",
        // then require('openmw.debug').setShaderHotReloadEnabled(true))
        std::filesystem::path passOverride(std::string_view stem)
        {
            return cacheDirectory().parent_path() / "shaders" / (std::string(stem) + ".omwfx");
        }

        class EmbeddedFile final : public VFS::File
        {
        public:
            EmbeddedFile(std::string data, std::string stem)
                : mData(std::move(data))
                , mStem(std::move(stem))
            {
            }

            Files::IStreamPtr open() override
            {
                std::string source(mData);
                std::ifstream in(passOverride(mStem), std::ios::binary);
                if (in)
                    source.assign(std::istreambuf_iterator<char>(in), std::istreambuf_iterator<char>());
                return std::make_unique<std::istringstream>(std::move(source));
            }

            std::filesystem::file_time_type getLastModified() const override
            {
                std::error_code ec;
                const auto time = std::filesystem::last_write_time(passOverride(mStem), ec);
                return ec ? std::filesystem::file_time_type{} : time;
            }

            std::string getStem() const override { return mStem; }

        private:
            std::string mData;
            std::string mStem;
        };

        VFS::Manager* sVfs = nullptr; // set as the archive is added; the index is built before anything is opened

        // alpha3/vfs-sources.txt, for the VFS browser (lua/scripts/alpha3/vfsbrowser.lua): where each file of the VFS
        // comes from (the data directory or archive whose copy is used). Lines "#n<tab>description" name the sources,
        // then "n<tab>path" for each file. Made when it is first opened, as the browser opens.
        class SourceIndexFile final : public VFS::File
        {
        public:
            Files::IStreamPtr open() override
            {
                std::lock_guard lock(mMutex);
                if (mData.empty() && sVfs != nullptr)
                {
                    const auto start = std::chrono::steady_clock::now();
                    std::map<std::string, std::size_t> sources;
                    std::string files;
                    std::size_t count = 0;
                    for (const VFS::Path::Normalized& path : sVfs->getRecursiveDirectoryIterator())
                    {
                        const auto [it, added] = sources.emplace(sVfs->getArchive(path), sources.size());
                        files += std::to_string(it->second) + '\t' + std::string(path.value()) + '\n';
                        ++count;
                    }
                    std::vector<const std::string*> names(sources.size());
                    for (const auto& [name, index] : sources)
                        names[index] = &name;
                    for (std::size_t i = 0; i < names.size(); ++i)
                        mData += '#' + std::to_string(i) + '\t' + *names[i] + '\n';
                    mData += files;
                    Log(Debug::Info) << "VFS browser: " << count << " files from " << sources.size() << " sources indexed in "
                                     << std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start)
                                            .count()
                                     << " ms";
                }
                return std::make_unique<std::istringstream>(mData);
            }

            std::filesystem::file_time_type getLastModified() const override { return {}; }
            std::string getStem() const override { return "vfs-sources"; }

        private:
            std::mutex mMutex;
            std::string mData;
        };

        class ExperimentalArchive final : public VFS::Archive
        {
        public:
            void listResources(VFS::FileMap& out) override
            {
                mFiles.clear();
                const ExperimentalSettings& s = experimentalSettings();
                const std::pair<std::string_view, std::string_view> passes[] = {
                    { sAmbientOcclusionTechnique, ExperimentalSources::ambientOcclusion },
                    { sLookTechnique, ExperimentalSources::look }, { sWaterTechnique, ExperimentalSources::water },
                    { sCloudsTechnique, ExperimentalSources::clouds }, { sAirTechnique, ExperimentalSources::air },
                    { sMistTechnique, ExperimentalSources::mist },
                    { sSharpenTechnique, ExperimentalSources::sharpen } };
                for (const auto& [stem, source] : passes)
                    add(out, "shaders/" + std::string(stem) + ".omwfx",
                        std::make_unique<EmbeddedFile>(std::string(source), std::string(stem)));
                add(out, "alpha3/vfs-sources.txt", std::make_unique<SourceIndexFile>());
                for (const Page& page : sPages)
                    if (pageHasLive(page))
                        add(out, "shaders/" + std::string(page.mTechnique) + ".omwfx",
                        std::make_unique<EmbeddedFile>(settingsTechnique(page), std::string(page.mTechnique)));
                if (!normalMapsWanted(s) && !s.mSpecular)
                    return;
                const auto start = std::chrono::steady_clock::now();

                const std::vector<MaterialRule> rules
                    = parseMaterialRules(s.mRules.empty() ? defaultMaterialRules() : s.mRules);
                const std::string normalPattern = s.mParallax ? "_nh" : "_n";
                // (the version: maps made by an older generator are made again; 2: gentler parallax height)
                constexpr std::string_view generatorVersion = "2";
                const std::uint64_t settingsHash = fnv1a(fixed(s.mNormalStrength) + (s.mSharpMaps ? "s" : ""), 0xcbf29ce484222325ull)
                    ^ fnv1a(s.mRules, 0x84222325cbf29ce4ull) ^ fnv1a(generatorVersion, 0x9e3779b97f4a7c15ull);
                // the endings of maps: a texture with one of them is a map itself
                static constexpr std::string_view mapEndings[]
                    = { "_n", "_nh", "_spec", "_diffusespec", "_g", "_glow", "_bump", "_b", "_h", "_disp", "_em" };

                std::vector<std::pair<std::string, VFS::File*>> found;
                for (const auto& [path, file] : out)
                {
                    const std::string_view name = path.value();
                    if (!name.starts_with("textures/") || !name.ends_with(".dds")
                        || name.starts_with("textures/alpha3_impostors/"))
                        continue;
                    found.emplace_back(std::string(name), file);
                }

                unsigned normals = 0, speculars = 0, skipped = 0;
                for (const auto& [name, file] : found)
                {
                    const std::string base = name.substr(0, name.size() - 4);
                    const std::string stem = base.substr(base.rfind('/') + 1);
                    if (std::any_of(std::begin(mapEndings), std::end(mapEndings),
                            [&](std::string_view ending) { return std::string_view(stem).ends_with(ending); }))
                        continue;
                    Material material = Material::None;
                    if (!materialOf(rules, stem, material))
                    {
                        ++skipped;
                        continue;
                    }
                    if (normalMapsWanted(s) && !out.contains(VFS::Path::Normalized(base + "_n.dds"))
                        && !out.contains(VFS::Path::Normalized(base + "_nh.dds")))
                    {
                        add(out, base + normalPattern + ".dds",
                            std::make_unique<GeneratedMapFile>(
                                file, name, MapKind::Normal, material, settingsHash, stem + normalPattern));
                        ++normals;
                    }
                    if (s.mSpecular)
                    {
                        if (!out.contains(VFS::Path::Normalized(base + "_spec.dds")))
                            add(out, base + "_spec.dds",
                                std::make_unique<GeneratedMapFile>(
                                    file, name, MapKind::Specular, material, settingsHash, stem + "_spec"));
                        if (!out.contains(VFS::Path::Normalized(base + "_diffusespec.dds")))
                            add(out, base + "_diffusespec.dds",
                                std::make_unique<GeneratedMapFile>(file, name, MapKind::DiffuseSpecular, material,
                                    settingsHash, stem + "_diffusespec"));
                        ++speculars;
                    }
                }
                Log(Debug::Info) << "Experimental: generated maps listed for " << normals << " normal and " << speculars
                                 << " specular textures, " << skipped << " left out by the material rules, in "
                                 << std::chrono::duration<double, std::milli>(std::chrono::steady_clock::now() - start)
                                        .count()
                                 << " ms";
            }

            bool contains(VFS::Path::NormalizedView file) const override { return mFiles.find(file) != mFiles.end(); }

            std::string getDescription() const override { return "Experimental graphics"; }

        private:
            void add(VFS::FileMap& out, std::string path, std::unique_ptr<VFS::File> file)
            {
                VFS::Path::Normalized normalized(std::move(path));
                out[normalized] = file.get();
                mFiles[std::move(normalized)] = std::move(file);
            }

            std::map<VFS::Path::Normalized, std::unique_ptr<VFS::File>, std::less<>> mFiles;
        };

    }

    const ExperimentalSettings& experimentalSettings()
    {
        static const ExperimentalSettings settings = readSettings();
        return settings;
    }

    bool injectExperimentalShaders(const osg::Shader& shader, std::string& source)
    {
        if (shader.getType() != osg::Shader::VERTEX && shader.getType() != osg::Shader::FRAGMENT)
            return false;
        static const ExperimentalShaderParts parts{ ExperimentalSources::procedural, ExperimentalSources::detail,
            ExperimentalSources::terrain, ExperimentalSources::treeSway, ExperimentalSources::treeLeaves,
            ExperimentalSources::wetground };
        static const MissingAnchor missing = [](std::string_view name, std::string_view anchor) {
            static std::mutex mutex;
            static std::set<std::string> logged;
            std::lock_guard lock(mutex);
            if (logged.insert(std::string(name) + "|" + std::string(anchor)).second)
                Log(Debug::Warning) << "Experimental: '" << anchor << "' not found in " << name
                                    << ", that part is left out";
        };
        return injectExperimentalSource(
            shader.getName(), shader.getType() == osg::Shader::VERTEX, experimentalSettings(), parts, source, missing);
    }

    const std::string& experimentalShaderStamp()
    {
        static const std::string stamp = [] {
            const ExperimentalSettings& s = experimentalSettings();
            std::string text = experimentalShaderDefines(s);
            text += s.mProcedural ? "p" : "-";
            text += s.mDetail ? "d" : "-";
            text += s.mTriplanar ? "t" : "-";
            text += s.mHeightBlending ? "h" : "-";
            text += s.mTreeSway ? "w" : "-";
            text += s.mTreeFull ? "f" : "-";
            text += s.mWetGround ? "r" + std::string(s.mWetPuddles ? "p" : "") : "-";
            std::uint64_t hash = fnv1a(text, 0xcbf29ce484222325ull);
            for (std::string_view part :
                { ExperimentalSources::procedural, ExperimentalSources::detail, ExperimentalSources::terrain,
                    ExperimentalSources::treeSway, ExperimentalSources::treeLeaves, ExperimentalSources::wetground })
                hash = fnv1a(part, hash);
            return "experimental:" + std::to_string(hash);
        }();
        return stamp;
    }

    void experimentalLiveInstall(osg::StateSet& state)
    {
        for (const LiveUniform& u : liveUniforms())
            state.addUniform(u.mValue);
    }

    namespace
    {
        int sForcedWeather = -2; // not read yet
    }

    namespace
    {
        // read by the cull threads as they draw the terrain
        std::atomic<int> sGrassOn = -1; // not read yet

        struct LiveFlags
        {
            std::atomic<bool> mClearance{ true }, mBugs{ true }, mDryUnder{ true };
            bool mRead = false;
        };
        LiveFlags sLive;

        LiveFlags& liveFlags()
        {
            if (!sLive.mRead)
            {
                sLive.mRead = true;
                const ExperimentalSettings& s = experimentalSettings();
                sLive.mClearance = s.mGrassClearance;
                sLive.mBugs = s.mGrassBugs;
                sLive.mDryUnder = s.mWetDryUnder;
            }
            return sLive;
        }
    }

    bool grassClearanceOn()
    {
        return liveFlags().mClearance.load(std::memory_order_relaxed);
    }

    bool grassBugsOn()
    {
        return liveFlags().mBugs.load(std::memory_order_relaxed);
    }

    bool wetDryUnderOn()
    {
        return liveFlags().mDryUnder.load(std::memory_order_relaxed);
    }

    bool grassOn()
    {
        int on = sGrassOn.load(std::memory_order_relaxed);
        if (on < 0)
        {
            on = experimentalSettings().mGrass ? 1 : 0;
            sGrassOn.store(on, std::memory_order_relaxed);
        }
        return on == 1;
    }

    int forcedWeather()
    {
        if (sForcedWeather == -2)
            sForcedWeather = static_cast<int>(std::lround(optionFloat("test weather")));
        // called every frame: the menu's values are looked up (in shaders.yaml, slowly) only while it is open (its
        // debug mode), the only time they can change; otherwise the values from the start stand
        if (Settings::ShaderManager::get().getMode() == Settings::ShaderManager::Mode::Debug)
        {
            sForcedWeather = static_cast<int>(std::lround(optionFloat("test weather")));
            liveFlags();
            sGrassOn.store(optionBool("grass") ? 1 : 0, std::memory_order_relaxed);
            sLive.mClearance.store(optionBool("grass clearance"), std::memory_order_relaxed);
            sLive.mBugs.store(optionBool("grass bugs"), std::memory_order_relaxed);
            sLive.mDryUnder.store(optionBool("wet ground dry under objects"), std::memory_order_relaxed);
        }
        // the menu writes settings.cfg as it closes: the chain kept for it is no longer needed (and must not undo what
        // is changed there)
        static bool chainDone = false;
        if (!chainDone)
        {
            chainDone = true;
            Settings::ShaderManager::get().setValue(std::string(sSettingsTechnique), "a3_chain", std::string());
        }
        return sForcedWeather;
    }

    void experimentalLiveUpdate()
    {
        // only while the menu is open (its debug mode): the values change only there
        if (Settings::ShaderManager::get().getMode() != Settings::ShaderManager::Mode::Debug)
            return;
        sForcedWeather = static_cast<int>(std::lround(optionFloat("test weather")));
        // the menu writes settings.cfg as it closes: the chain kept for it is no longer needed (and must not undo what
        // is changed there)
        static bool chainDone = false;
        if (!chainDone)
        {
            chainDone = true;
            Settings::ShaderManager::get().setValue(std::string(sSettingsTechnique), "a3_chain", std::string());
        }
        for (const LiveUniform& u : liveUniforms())
            u.mValue->set(liveValue(u));
    }

    bool experimentalOptionOn(std::string_view name)
    {
        return optionBool(name);
    }

    // The F2 window as it was saved on a phone reached past the top and bottom of the screen: kept on it
    void fitPostprocessorWindow()
    {
        auto& windows = Settings::windows();
        const float y = std::clamp(windows.mPostprocessorY.get(), 0.f, 0.5f);
        const float h = std::clamp(windows.mPostprocessorH.get(), 0.2f, 0.98f - y);
        const float x = std::clamp(windows.mPostprocessorX.get(), 0.f, 0.8f);
        const float w = std::clamp(windows.mPostprocessorW.get(), 0.2f, 1.f - x);
        if (y != windows.mPostprocessorY.get() || h != windows.mPostprocessorH.get()
            || x != windows.mPostprocessorX.get() || w != windows.mPostprocessorW.get())
        {
            windows.mPostprocessorX.set(x);
            windows.mPostprocessorY.set(y);
            windows.mPostprocessorW.set(w);
            windows.mPostprocessorH.set(h);
            Log(Debug::Info) << "Experimental: the post-processing window fitted to the screen";
        }
    }

    void addExperimental(VFS::Manager& vfs)
    {
        const ExperimentalSettings& s = experimentalSettings();
        fitPostprocessorWindow();
        applyOwnedSettings(s);
        sVfs = &vfs;
        setGrassVfs(vfs);
        vfs.addArchive(std::make_unique<ExperimentalArchive>());
        if (normalMapsWanted(s) || s.mSpecular || s.mProcedural || s.mDetail || s.mTriplanar || s.mHeightBlending
            || s.mAmbientOcclusion || s.mLook || s.mGrass || s.mTreeSway)
            Log(Debug::Info) << "Experimental: generated normal maps " << normalMapsWanted(s) << ", specular maps "
                             << s.mSpecular << ", procedural materials " << s.mProcedural << " (replacing textures "
                             << s.mReplace << "), detail up close " << s.mDetail << ", terrain triplanar "
                             << s.mTriplanar << ", terrain height blending " << s.mHeightBlending
                             << ", ambient occlusion " << s.mAmbientOcclusion << ", look " << s.mLook << ", grass "
                             << s.mGrass << ", tree sway " << s.mTreeSway;
    }
}
