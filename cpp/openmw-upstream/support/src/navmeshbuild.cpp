// The navmesh baking of the launcher: navmesh.db for the whole mod list, made by apps/navmeshtool's work (navmesh.cpp,
// worldspacedata.cpp), which is linked into the engine instead of being a library of its own. Set up as navmeshtool's
// main.cpp sets it up, with the same options. Android runs it in a process of its own (NavMeshService, ":navmesh",
// through androidmain.cpp); iOS has no other process, so it calls alpha3_navmesh_run before the engine starts. The work
// is done on a thread of its own, and the caller's thread reports its progress.
#include <alpha3/upstream.hpp>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstddef>
#include <cstdint>
#include <filesystem>
#include <mutex>
#include <regex>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

#include <boost/program_options.hpp>

#include <apps/navmeshtool/navmesh.hpp>
#include <apps/navmeshtool/worldspacedata.hpp>

#include <components/debug/debugging.hpp>
#include <components/debug/debuglog.hpp>
#include <components/detournavigator/agentbounds.hpp>
#include <components/detournavigator/navmeshdb.hpp>
#include <components/detournavigator/recastglobalallocator.hpp>
#include <components/detournavigator/settings.hpp>
#include <components/esm3/readerscache.hpp>
#include <components/esm3/variant.hpp>
#include <components/esmloader/esmdata.hpp>
#include <components/esmloader/load.hpp>
#include <components/fallback/fallback.hpp>
#include <components/fallback/validate.hpp>
#include <components/files/collections.hpp>
#include <components/files/configurationmanager.hpp>
#include <components/files/conversion.hpp>
#include <components/resource/bgsmfilemanager.hpp>
#include <components/resource/bulletshapemanager.hpp>
#include <components/resource/imagemanager.hpp>
#include <components/resource/niffilemanager.hpp>
#include <components/resource/scenemanager.hpp>
#include <components/sceneutil/workqueue.hpp>
#include <components/settings/values.hpp>
#include <components/toutf8/toutf8.hpp>
#include <components/vfs/manager.hpp>
#include <components/vfs/registerarchives.hpp>

namespace
{
    namespace bpo = boost::program_options;
    using StringsVector = std::vector<std::string>;

    bpo::options_description makeOptions()
    {
        bpo::options_description result;
        auto add = result.add_options();
        add("data",
            bpo::value<Files::MaybeQuotedPathContainer>()
                ->default_value(Files::MaybeQuotedPathContainer(), "data")
                ->multitoken()
                ->composing());
        add("data-local",
            bpo::value<Files::MaybeQuotedPathContainer::value_type>()->default_value(
                Files::MaybeQuotedPathContainer::value_type(), ""));
        add("fallback-archive",
            bpo::value<StringsVector>()->default_value(StringsVector(), "fallback-archive")->multitoken()->composing());
        add("content", bpo::value<StringsVector>()->default_value(StringsVector(), "")->multitoken()->composing());
        add("encoding", bpo::value<std::string>()->default_value("win1252"));
        add("fallback",
            bpo::value<Fallback::FallbackMap>()->default_value(Fallback::FallbackMap(), "")->multitoken()->composing());
        // every core: the player waits for it, and the game is not running alongside
        add("threads", bpo::value<std::size_t>()->default_value(std::max(1u, std::thread::hardware_concurrency())));
        add("process-interior-cells", bpo::value<bool>()->implicit_value(true)->default_value(false));
        add("remove-unused-tiles", bpo::value<bool>()->implicit_value(true)->default_value(false));
        add("worldspace-filter", bpo::value<std::string>()->default_value(".*"));
        Files::ConfigurationManager::addCommonOptions(result);
        return result;
    }

    // What the worker has got to, for the thread that reports it
    struct Progress
    {
        std::atomic<int> mPhase{ 0 };
        std::atomic<std::size_t> mWorldspacesDone{ 0 };
        std::atomic<std::size_t> mWorldspaces{ 0 };
        std::mutex mMutex;
        // while it exists; then the tiles it had
        const NavMeshTool::NavMeshTilesGenerator* mGenerator = nullptr;
        std::pair<std::size_t, std::size_t> mTiles{ 0, 0 };

        Alpha3::NavMeshProgress get()
        {
            const std::lock_guard lock(mMutex);
            if (mGenerator != nullptr)
                mTiles = mGenerator->getProgress();
            return { mPhase, mWorldspacesDone, mWorldspaces, mTiles.first, mTiles.second };
        }

        void setGenerator(const NavMeshTool::NavMeshTilesGenerator* generator)
        {
            const std::lock_guard lock(mMutex);
            if (mGenerator != nullptr)
                mTiles = mGenerator->getProgress();
            mGenerator = generator;
        }
    };

    // Shows the generator to the reporting thread until it goes, an exception or not
    struct ShownGenerator
    {
        Progress& mProgress;

        ShownGenerator(Progress& progress, const NavMeshTool::NavMeshTilesGenerator& generator)
            : mProgress(progress)
        {
            mProgress.setGenerator(&generator);
        }

        ~ShownGenerator() { mProgress.setGenerator(nullptr); }
    };

    int run(int argc, const char* const* argv, Progress& progress)
    {
        using namespace NavMeshTool;

        const bpo::options_description options = makeOptions();
        bpo::variables_map variables;
        bpo::store(bpo::command_line_parser(argc, argv).options(options).allow_unregistered().run(), variables);
        bpo::notify(variables);

        Files::ConfigurationManager config;
        config.processPaths(variables, std::filesystem::current_path());
        config.readConfiguration(variables, options);
        // navmeshtool.log in the config folder: the launcher's progress screen follows it
        Debug::setupLogging(config.getLogPath(), "NavMeshTool");

        const std::string encoding(variables["encoding"].as<std::string>());
        Log(Debug::Info) << ToUTF8::encodingUsingMessage(encoding);
        ToUTF8::Utf8Encoder encoder(ToUTF8::calculateEncoding(encoding));

        Files::PathContainer dataDirs(Files::asPathContainer(variables["data"].as<Files::MaybeQuotedPathContainer>()));
        if (auto local = variables["data-local"].as<Files::MaybeQuotedPathContainer::value_type>(); !local.empty())
            dataDirs.push_back(std::move(local));
        config.filterOutNonExistingPaths(dataDirs);
        dataDirs.insert(dataDirs.begin(), variables["resources"].as<Files::MaybeQuotedPath>() / "vfs");
        const Files::Collections fileCollections(dataDirs);
        const auto& archives = variables["fallback-archive"].as<StringsVector>();
        StringsVector contentFiles{ "builtin.omwscripts" };
        const auto& configContentFiles = variables["content"].as<StringsVector>();
        contentFiles.insert(contentFiles.end(), configContentFiles.begin(), configContentFiles.end());

        const std::size_t threads = std::max<std::size_t>(1, variables["threads"].as<std::size_t>());
        const bool processInteriorCells = variables["process-interior-cells"].as<bool>();
        const bool removeUnusedTiles = variables["remove-unused-tiles"].as<bool>();
        const std::regex worldspaceFilter(variables["worldspace-filter"].as<std::string>());

        Fallback::Map::init(variables["fallback"].as<Fallback::FallbackMap>().mMap);

        VFS::Manager vfs;
        VFS::registerArchives(&vfs, fileCollections, archives, true, &encoder.getStatelessEncoder());

        Settings::Manager::load(config);

        const DetourNavigator::AgentBounds agentBounds{
            Settings::game().mActorCollisionShapeType,
            Settings::game().mDefaultActorPathfindHalfExtents,
        };
        const auto dbPath = Files::pathToUnicodeString(config.getUserDataPath() / "navmesh.db");
        Log(Debug::Info) << "Using navmeshdb at " << dbPath;
        DetourNavigator::NavMeshDb db(dbPath, Settings::navigator().mMaxNavmeshdbFileSize);

        ESM::ReadersCache readers;
        EsmLoader::Query query;
        query.mLoadActivators = true;
        query.mLoadCells = true;
        query.mLoadContainers = true;
        query.mLoadDoors = true;
        query.mLoadGameSettings = true;
        query.mLoadLands = true;
        query.mLoadStatics = true;
        const EsmLoader::EsmData esmData = EsmLoader::loadEsmData(query, contentFiles, fileCollections, readers, &encoder);

        constexpr double expiryDelay = 0;
        Resource::ImageManager imageManager(&vfs, expiryDelay);
        Resource::NifFileManager nifFileManager(&vfs, &encoder.getStatelessEncoder());
        Resource::BgsmFileManager bgsmFileManager(&vfs, expiryDelay);
        Resource::SceneManager sceneManager(&vfs, &imageManager, &nifFileManager, &bgsmFileManager, expiryDelay);
        Resource::BulletShapeManager bulletShapeManager(&vfs, &sceneManager, &nifFileManager, expiryDelay);
        DetourNavigator::RecastGlobalAllocator::init();
        DetourNavigator::Settings navigatorSettings
            = DetourNavigator::makeSettingsFromSettingsManager(Debug::getRecastMaxLogLevel());
        navigatorSettings.mRecast.mSwimHeightScale
            = EsmLoader::getGameSetting(esmData.mGameSettings, "fSwimHeightScale").getFloat();

        const std::unordered_map<ESM::RefId, std::vector<std::size_t>> worldspaceCells
            = collectWorldspaceCells(esmData, processInteriorCells, worldspaceFilter);
        progress.mWorldspaces = worldspaceCells.size();
        progress.mPhase = 1;

        GenerateTilesResult result;
        {
            SceneUtil::WorkQueue workQueue(threads);
            Log(Debug::Info) << "Using " << threads << " parallel workers...";
            const GenerateAllNavMeshTilesOptions generateOptions{
                .mRemoveUnusedTiles = removeUnusedTiles,
                .mWriteBinaryLog = false,
                .mCollectStats = false,
            };
            NavMeshTilesGenerator generator(agentBounds, navigatorSettings, generateOptions, db, workQueue);
            const ShownGenerator shown(progress, generator);
            std::size_t count = 0;
            for (const auto& [worldspace, cells] : worldspaceCells)
            {
                WorldspaceData worldspaceData = gatherWorldspaceData(
                    navigatorSettings, readers, vfs, bulletShapeManager, esmData, false, worldspace, cells);
                const Status status = generator.addWorldspace(std::move(worldspaceData));
                Log(Debug::Info) << "Processed worldspace (" << ++count << "/" << worldspaceCells.size() << ") "
                                 << worldspace;
                progress.mWorldspacesDone = count;
                if (status != Status::Ok)
                    break;
            }
            progress.mPhase = 2;
            result = generator.finish();
        }

        Log(Debug::Info) << "Generated navmesh for " << result.mProvided << " tiles: " << result.mInserted
                         << " inserted, " << result.mUpdated << " updated, " << result.mDeleted << " deleted";
        if (result.mStatus == Status::Ok && result.mInserted + result.mUpdated + result.mDeleted > 0)
        {
            Log(Debug::Info) << "Vacuuming the database...";
            progress.mPhase = 3;
            db.vacuum();
        }
        switch (result.mStatus)
        {
            case Status::Ok:
                // the launcher's progress screen ends on this line (the spelling is the old tool's)
                Log(Debug::Info) << "runNavMeshTool sucess";
                return 0;
            case Status::Cancelled:
                Log(Debug::Warning) << "Cancelled";
                return 1;
            case Status::NotEnoughSpace:
                Log(Debug::Warning) << "Navmesh generation stopped: out of disk space, or over [Navigator] max navmeshdb "
                                       "file size, at "
                                    << dbPath;
                return 2;
        }
        return 3;
    }
}

namespace
{
    // the worker's, for wrapped
    thread_local Progress* sProgress = nullptr;

    // Exceptions are caught here: wrapApplication would show them in a message box, which this process has no window for
    int wrapped(int argc, char* argv[])
    {
        try
        {
            return run(argc, argv, *sProgress);
        }
        catch (const std::exception& e)
        {
            Log(Debug::Error) << "Navmesh: " << e.what();
            return 3;
        }
    }
}

namespace Alpha3
{
    int buildNavMesh(int argc, const char* const* argv, const std::function<void(const NavMeshProgress&)>& report)
    {
        Progress progress;
        std::atomic<bool> finished{ false };
        int code = 3;
        std::thread worker([&] {
            // setupLogging writes through the streams wrapApplication sets up, as navmeshtool's main does
            sProgress = &progress;
            code = Debug::wrapApplication(&wrapped, argc, const_cast<char**>(argv), "NavMeshTool");
            finished = true;
        });
        while (!finished)
        {
            if (report)
                report(progress.get());
            for (int i = 0; i < 25 && !finished; ++i)
                std::this_thread::sleep_for(std::chrono::milliseconds(10));
        }
        worker.join();
        if (report)
            report(progress.get());
        return code;
    }
}

// For iOS, which finds it with dlsym: Alpha3::buildNavMesh, progress (may be null) called with user and the fields of
// Alpha3::NavMeshProgress
extern "C" __attribute__((visibility("default"), used)) int alpha3_navmesh_run(int argc, const char* const* argv,
    void (*progress)(void* user, int phase, std::size_t worldspacesDone, std::size_t worldspaces, std::size_t tilesDone,
        std::size_t tiles),
    void* user)
{
    if (progress == nullptr)
        return Alpha3::buildNavMesh(argc, argv);
    return Alpha3::buildNavMesh(argc, argv, [&](const Alpha3::NavMeshProgress& p) {
        progress(user, p.phase, p.worldspacesDone, p.worldspaces, p.tilesDone, p.tiles);
    });
}
