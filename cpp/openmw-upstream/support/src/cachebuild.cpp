// The launcher's cache builder (Mod tools, "Build Alpha3 cache"): every distant mesh and card of the mod list made into the
// cache the game reads, so a first visit loads like a later one. Run in a process of its own (CacheBuildService,
// android:process=":cache"), which loads the engine's library for this and nothing else: the configuration, settings and
// VFS are set up as openmw-navmeshtool sets them up. The cache's lock files keep it apart from a game that runs meanwhile.
#include "support.hpp"

#include <atomic>
#include <filesystem>
#include <string>
#include <thread>
#include <vector>

#include <boost/program_options.hpp>
#include <jni.h>

#include <components/debug/debugging.hpp>
#include <components/debug/debuglog.hpp>
#include <components/files/collections.hpp>
#include <components/files/configurationmanager.hpp>
#include <components/settings/settings.hpp>
#include <components/toutf8/toutf8.hpp>
#include <components/vfs/manager.hpp>
#include <components/vfs/registerarchives.hpp>

namespace
{
    namespace bpo = boost::program_options;

    std::atomic<bool> sCancel{ false };

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
            bpo::value<std::vector<std::string>>()
                ->default_value(std::vector<std::string>(), "fallback-archive")
                ->multitoken()
                ->composing());
        add("encoding", bpo::value<std::string>()->default_value("win1252"));
        Files::ConfigurationManager::addCommonOptions(result);
        return result;
    }

    // 0: done, 1: cancelled, 2: distant meshes are off in settings.cfg, 3: failed (see the log)
    int run(int argc, const char* const* argv, const std::function<void(const Alpha3::DistantMeshProgress&)>& progress)
    {
        const bpo::options_description options = makeOptions();
        bpo::variables_map variables;
        bpo::store(bpo::command_line_parser(argc, argv)
                       .options(options)
                       .allow_unregistered()
                       .run(),
            variables);
        bpo::notify(variables);

        Files::ConfigurationManager config;
        config.processPaths(variables, std::filesystem::current_path());
        config.readConfiguration(variables, options);
        Debug::setupLogging(config.getLogPath(), "Alpha3Cache");
        Settings::Manager::load(config);
        Alpha3::applyAllocatorSetting();

        ToUTF8::Utf8Encoder encoder(ToUTF8::calculateEncoding(variables["encoding"].as<std::string>()));
        Files::PathContainer dataDirs(Files::asPathContainer(variables["data"].as<Files::MaybeQuotedPathContainer>()));
        if (auto local = variables["data-local"].as<Files::MaybeQuotedPathContainer::value_type>(); !local.empty())
            dataDirs.push_back(std::move(local));
        config.filterOutNonExistingPaths(dataDirs);
        dataDirs.insert(dataDirs.begin(), variables["resources"].as<Files::MaybeQuotedPath>() / "vfs");
        const Files::Collections collections(dataDirs);

        VFS::Manager vfs;
        VFS::registerArchives(&vfs, collections, variables["fallback-archive"].as<std::vector<std::string>>(), true,
            &encoder.getStatelessEncoder());

        // the cores are free: the game is not running alongside, as a rule
        const unsigned threads = std::max(1u, std::thread::hardware_concurrency());
        if (!Alpha3::buildDistantMeshes(threads, progress, sCancel))
            return sCancel ? 1 : 2;
        Log(Debug::Info) << "Alpha3 cache: distant meshes done";
        return 0;
    }

    // for wrapped, which wrapApplication calls on this thread
    const std::function<void(const Alpha3::DistantMeshProgress&)>* sProgress = nullptr;

    // Exceptions are caught here: wrapApplication would show them in a message box, which this process has no window for
    int wrapped(int argc, char* argv[])
    {
        try
        {
            return run(argc, argv, *sProgress);
        }
        catch (const std::exception& e)
        {
            Log(Debug::Error) << "Alpha3 cache: " << e.what();
            return 3;
        }
    }
}

// CacheBuildService.runNative(arguments, listener): the engine's arguments (--resources, the profile's --config ...);
// listener.onProgress(done, total, made, notMade) a few times a second. Returns what run() does.
extern "C" JNIEXPORT jint JNICALL Java_org_openmw_utils_CacheBuildService_runNative(
    JNIEnv* env, jclass, jobjectArray arguments, jobject listener)
{
    sCancel = false;
    std::vector<std::string> args{ "alpha3-cache" };
    for (jsize i = 0, n = env->GetArrayLength(arguments); i < n; ++i)
    {
        auto value = static_cast<jstring>(env->GetObjectArrayElement(arguments, i));
        const char* chars = env->GetStringUTFChars(value, nullptr);
        args.emplace_back(chars);
        env->ReleaseStringUTFChars(value, chars);
        env->DeleteLocalRef(value);
    }
    std::vector<char*> argv;
    for (std::string& arg : args)
        argv.push_back(arg.data());
    jclass listenerClass = env->GetObjectClass(listener);
    jmethodID onProgress = env->GetMethodID(listenerClass, "onProgress", "(IIII)V");
    const std::function<void(const Alpha3::DistantMeshProgress&)> progress = [&](const Alpha3::DistantMeshProgress& p) {
        env->CallVoidMethod(listener, onProgress, jint(p.done), jint(p.total), jint(p.made), jint(p.notMade));
        if (env->ExceptionCheck())
            env->ExceptionClear();
    };
    sProgress = &progress;
    // setupLogging writes through the streams wrapApplication sets up
    return Debug::wrapApplication(&wrapped, static_cast<int>(argv.size()), argv.data(), "Alpha3Cache");
}

extern "C" JNIEXPORT void JNICALL Java_org_openmw_utils_CacheBuildService_cancelNative(JNIEnv*, jclass)
{
    sCancel = true;
}
