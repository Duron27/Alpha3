// The experimental graphics ([Experimental] in settings.cfg, every part its own switch, all off by default):
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
#include <chrono>
#include <cstring>
#include <fstream>
#include <iterator>
#include <map>
#include <memory>
#include <mutex>
#include <set>
#include <sstream>
#include <string>
#include <string_view>
#include <vector>

#include <osg/Shader>

#include <components/debug/debuglog.hpp>
#include <components/settings/values.hpp>
#include <components/vfs/archive.hpp>
#include <components/vfs/file.hpp>
#include <components/vfs/manager.hpp>
#include <components/vfs/pathutil.hpp>

namespace Alpha3
{
    namespace
    {
        constexpr std::string_view sCategory = "Experimental";
        constexpr std::uint32_t sCacheVersion = 2; // 2: normal and specular maps at 512 at most
        constexpr std::string_view sAmbientOcclusionTechnique = "a3_ambientocclusion";
        constexpr std::string_view sLookTechnique = "a3_look";

        ExperimentalSettings readSettings()
        {
            ExperimentalSettings s;
            s.mNormalMaps = settingBool(sCategory, "generated normal maps", false);
            s.mParallax = settingBool(sCategory, "generated normal maps parallax", false);
            s.mNormalStrength = settingFloat(sCategory, "generated normal map strength", 1.f);
            s.mSpecular = settingBool(sCategory, "generated specular maps", false);
            s.mRules = setting(sCategory, "material rules", "");
            s.mProcedural = settingBool(sCategory, "procedural materials", false);
            s.mReplace = settingBool(sCategory, "procedural replaces textures", true);
            s.mProceduralStrength = settingFloat(sCategory, "procedural strength", 1.f);
            s.mProceduralDistance = settingFloat(sCategory, "procedural distance", 3000.f);
            s.mDetail = settingBool(sCategory, "detail up close", false);
            s.mDetailStrength = settingFloat(sCategory, "detail strength", 1.f);
            s.mDetailDistance = settingFloat(sCategory, "detail distance", 1500.f);
            s.mTriplanar = settingBool(sCategory, "terrain triplanar", false);
            s.mHeightBlending = settingBool(sCategory, "terrain height blending", false);
            s.mHeightBlendSharpness = settingFloat(sCategory, "terrain height blend sharpness", 2.5f);
            s.mAmbientOcclusion = settingBool(sCategory, "ambient occlusion", false);
            s.mLook = settingBool(sCategory, "look", false);
            s.mGrass = settingBool(sCategory, "grass", false);
            s.mGrassDensity = std::clamp(settingFloat(sCategory, "grass density", 1.f), 0.05f, 4.f);
            s.mGrassHeight = std::clamp(settingFloat(sCategory, "grass height", 1.f), 0.2f, 3.f);
            s.mGrassDistance = std::clamp(settingFloat(sCategory, "grass distance", 2500.f), 500.f, 8000.f);
            s.mGrassWind = std::clamp(settingFloat(sCategory, "grass wind", 1.f), 0.f, 3.f);
            s.mGrassClearance = settingBool(sCategory, "grass clearance", true);
            s.mGrassStorm = std::clamp(settingFloat(sCategory, "grass storm", 1.f), 0.f, 3.f);
            s.mGrassVariety = settingBool(sCategory, "grass variety", true);
            s.mGrassAntialiasing = settingBool(sCategory, "grass antialiasing", true);
            s.mTreeSway = settingBool(sCategory, "tree sway", false);
            s.mTreeSwayStrength = std::clamp(settingFloat(sCategory, "tree sway strength", 2.5f), 0.f, 4.f);
            s.mTreeSwayTextures = setting(sCategory, "tree sway textures", "");
            s.mTrunkSway = std::clamp(settingFloat(sCategory, "tree trunk sway", 1.5f), 0.f, 2.f);
            s.mTrunkSwayTextures = setting(sCategory, "tree trunk textures", "");
            return s;
        }

        // The generated maps are made for the procedural materials too: their material is stored in the normal map
        bool normalMapsWanted(const ExperimentalSettings& s)
        {
            return s.mNormalMaps || s.mProcedural;
        }

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

        void applyOwnedSettings(const ExperimentalSettings& s)
        {
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
                ownedBool("Post Processing/enabled", s.mAmbientOcclusion || s.mLook, post.mEnabled),
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

            // the passes in the chain: ambient occlusion first (before anything changes the colours it darkens), the look
            // last; taken out again when their switch is off
            std::vector<std::string> chain = post.mChain.get();
            const auto place = [&](std::string_view name, bool wanted, bool first) {
                const auto found = std::find(chain.begin(), chain.end(), name);
                if (wanted && found == chain.end())
                    chain.insert(first ? chain.begin() : chain.end(), std::string(name));
                else if (!wanted && found != chain.end())
                    chain.erase(found);
            };
            place(sAmbientOcclusionTechnique, s.mAmbientOcclusion, true);
            place(sLookTechnique, s.mLook, false);
            if (chain != post.mChain.get())
                post.mChain.set(chain);
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
        class EmbeddedFile final : public VFS::File
        {
        public:
            EmbeddedFile(std::string_view data, std::string stem)
                : mData(data)
                , mStem(std::move(stem))
            {
            }

            Files::IStreamPtr open() override { return std::make_unique<std::istringstream>(std::string(mData)); }
            std::filesystem::file_time_type getLastModified() const override { return {}; }
            std::string getStem() const override { return mStem; }

        private:
            std::string_view mData;
            std::string mStem;
        };

        class ExperimentalArchive final : public VFS::Archive
        {
        public:
            void listResources(VFS::FileMap& out) override
            {
                mFiles.clear();
                const ExperimentalSettings& s = experimentalSettings();
                add(out, "shaders/a3_ambientocclusion.omwfx",
                    std::make_unique<EmbeddedFile>(ExperimentalSources::ambientOcclusion, "a3_ambientocclusion"));
                add(out, "shaders/a3_look.omwfx", std::make_unique<EmbeddedFile>(ExperimentalSources::look, "a3_look"));
                if (!normalMapsWanted(s) && !s.mSpecular)
                    return;
                const auto start = std::chrono::steady_clock::now();

                const std::vector<MaterialRule> rules
                    = parseMaterialRules(s.mRules.empty() ? defaultMaterialRules() : s.mRules);
                const std::string normalPattern = s.mParallax ? "_nh" : "_n";
                // (the version: maps made by an older generator are made again; 2: gentler parallax height)
                constexpr std::string_view generatorVersion = "2";
                const std::uint64_t settingsHash = fnv1a(fixed(s.mNormalStrength), 0xcbf29ce484222325ull)
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
            ExperimentalSources::terrain, ExperimentalSources::treeSway };
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
            std::uint64_t hash = fnv1a(text, 0xcbf29ce484222325ull);
            for (std::string_view part :
                { ExperimentalSources::procedural, ExperimentalSources::detail, ExperimentalSources::terrain,
                    ExperimentalSources::treeSway })
                hash = fnv1a(part, hash);
            return "experimental:" + std::to_string(hash);
        }();
        return stamp;
    }

    void addExperimental(VFS::Manager& vfs)
    {
        const ExperimentalSettings& s = experimentalSettings();
        applyOwnedSettings(s);
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
