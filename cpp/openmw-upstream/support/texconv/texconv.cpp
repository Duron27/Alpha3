// The launcher's texture pre-pass: converts, before the game starts, every DDS texture the game would load from the
// current mod list, into the cache the game reads (src/textureconversion.cpp, compiled into this library too). The game
// then finds them all as "loaded from the cache".
//
// Which file is used for a texture follows OpenMW's virtual file system: the BSA archives of fallback-archive= first,
// then the data= directories in order, each later one replacing what the ones before it have under the same path
// (lower case, '/' separators). A texture with a .ktx of the same name is skipped: the game loads that one instead.
// The DDS files are read with OSG's DDS reader and the options the game uses, so the cache keys come out the same.

#include "support.hpp"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstring>
#include <fstream>
#include <functional>
#include <map>
#include <mutex>
#include <sstream>
#include <streambuf>
#include <string>
#include <thread>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#ifdef __ANDROID__
#include <jni.h>
#endif

#include <osgDB/Options>
#include <osgDB/ReaderWriter>
#include <osgDB/Registry>

#include <components/debug/debuglog.hpp>

USE_OSGPLUGIN(dds)

#ifdef __ANDROID__
// osg::Image::readPixels refers to these glad pointers (the engine defines them); nothing here draws, so they stay null
extern "C"
{
    PFNGLPIXELSTOREIPROC glad_glPixelStorei = nullptr;
    PFNGLREADPIXELSPROC glad_glReadPixels = nullptr;
}
#endif

namespace
{
    // ---- what the support library asks of the engine (support.hpp), from the files the launcher names --------------

    std::map<std::string, std::string, std::less<>> sSettings; // "Category/name" -> value
    std::filesystem::path sCacheDirectory;

    std::string trim(std::string_view text)
    {
        const size_t first = text.find_first_not_of(" \t\r\n");
        if (first == std::string_view::npos)
            return {};
        const size_t last = text.find_last_not_of(" \t\r\n");
        return std::string(text.substr(first, last - first + 1));
    }

    std::string lower(std::string text)
    {
        for (char& c : text)
            c = (c >= 'A' && c <= 'Z') ? static_cast<char>(c - 'A' + 'a') : (c == '\\' ? '/' : c);
        return text;
    }

    // settings.cfg: [Category] sections of "name = value"
    void readSettings(const std::filesystem::path& path)
    {
        std::ifstream in(path);
        std::string line, category;
        while (std::getline(in, line))
        {
            const std::string text = trim(line);
            if (text.empty() || text[0] == '#')
                continue;
            if (text.front() == '[' && text.back() == ']')
            {
                category = trim(std::string_view(text).substr(1, text.size() - 2));
                continue;
            }
            const size_t equals = text.find('=');
            if (equals == std::string::npos || category.empty())
                continue;
            sSettings[category + "/" + trim(std::string_view(text).substr(0, equals))]
                = trim(std::string_view(text).substr(equals + 1));
        }
    }

    // ---- openmw.cfg ------------------------------------------------------------------------------------------------

    struct Config
    {
        std::vector<std::filesystem::path> data;
        std::vector<std::string> archives;
    };

    // A value in quotes keeps its spaces; inside, && is & and &" is "
    std::string unquote(const std::string& value)
    {
        if (value.empty() || value.front() != '"')
            return value;
        std::string out;
        for (size_t i = 1; i < value.size(); ++i)
        {
            const char c = value[i];
            if (c == '&' && i + 1 < value.size() && (value[i + 1] == '&' || value[i + 1] == '"'))
                out += value[++i];
            else if (c == '"')
                break;
            else
                out += c;
        }
        return out;
    }

    void readConfig(const std::filesystem::path& path, Config& config)
    {
        std::ifstream in(path);
        std::string line;
        while (std::getline(in, line))
        {
            const std::string text = trim(line);
            if (text.empty() || text[0] == '#')
                continue;
            const size_t equals = text.find('=');
            if (equals == std::string::npos)
                continue;
            const std::string key = trim(std::string_view(text).substr(0, equals));
            const std::string value = unquote(trim(std::string_view(text).substr(equals + 1)));
            if (key == "replace")
            {
                if (value == "data")
                    config.data.clear();
                else if (value == "fallback-archive")
                    config.archives.clear();
            }
            else if (key == "data" && !value.empty() && value.find('?') == std::string::npos)
            {
                std::filesystem::path dir(value);
                config.data.push_back(dir.is_relative() ? (path.parent_path() / dir).lexically_normal() : dir);
            }
            else if (key == "fallback-archive" && !value.empty())
                config.archives.push_back(value);
        }
    }

    // ---- the virtual file system, DDS and KTX files only --------------------------------------------------------------

    struct Source
    {
        int archive = -1; // index into Index::archives, or -1 for a loose file
        std::filesystem::path file;
        uint32_t offset = 0, size = 0; // offset into the archive; size of the file
    };

    struct Index
    {
        std::vector<std::filesystem::path> archives;
        std::unordered_map<std::string, Source> files; // virtual path -> where its bytes are
    };

    bool wanted(const std::string& path)
    {
        return path.size() > 4 && (path.compare(path.size() - 4, 4, ".dds") == 0 || path.compare(path.size() - 4, 4, ".ktx") == 0);
    }

    // Morrowind's BSA layout (version 0x100): sizes and offsets, name offsets, names, hashes, then the data
    bool addArchive(const std::filesystem::path& path, Index& index)
    {
        std::ifstream in(path, std::ios::binary);
        uint32_t header[3];
        if (!in.read(reinterpret_cast<char*>(header), sizeof header) || header[0] != 0x100)
        {
            Log(Debug::Warning) << "Texture pre-pass: " << path.string() << " is not a Morrowind BSA, skipped";
            return false;
        }
        const uint32_t hashOffset = header[1], count = header[2];
        std::vector<uint32_t> records(static_cast<size_t>(count) * 2), nameOffsets(count);
        if (!in.read(reinterpret_cast<char*>(records.data()), records.size() * 4)
            || !in.read(reinterpret_cast<char*>(nameOffsets.data()), nameOffsets.size() * 4))
            return false;
        const uint64_t namesStart = 12 + static_cast<uint64_t>(count) * 12;
        const uint64_t namesSize = hashOffset > count * 12u ? hashOffset - count * 12u : 0;
        std::string names(namesSize, '\0');
        in.seekg(static_cast<std::streamoff>(namesStart));
        if (!in.read(names.data(), static_cast<std::streamsize>(names.size())))
            return false;
        const uint64_t dataStart = 12 + static_cast<uint64_t>(hashOffset) + static_cast<uint64_t>(count) * 8;
        const int archive = static_cast<int>(index.archives.size());
        index.archives.push_back(path);
        for (uint32_t i = 0; i < count; ++i)
        {
            if (nameOffsets[i] >= names.size())
                continue;
            const std::string name = lower(names.c_str() + nameOffsets[i]);
            if (!wanted(name))
                continue;
            index.files[name] = Source{ archive, {}, static_cast<uint32_t>(dataStart + records[i * 2 + 1]), records[i * 2] };
        }
        return true;
    }

    void addDirectory(const std::filesystem::path& dir, Index& index)
    {
        std::error_code ec;
        for (std::filesystem::recursive_directory_iterator it(dir, std::filesystem::directory_options::skip_permission_denied, ec), end;
             !ec && it != end; it.increment(ec))
        {
            if (!it->is_regular_file(ec))
                continue;
            std::string name = lower(it->path().lexically_relative(dir).generic_string());
            if (wanted(name))
                index.files[std::move(name)] = Source{ -1, it->path(), 0, static_cast<uint32_t>(it->file_size(ec)) };
        }
    }

    // ---- reading -----------------------------------------------------------------------------------------------------

    struct MemoryBuffer : std::streambuf
    {
        explicit MemoryBuffer(std::vector<char>& bytes) { setg(bytes.data(), bytes.data(), bytes.data() + bytes.size()); }

        pos_type seekoff(off_type off, std::ios_base::seekdir dir, std::ios_base::openmode) override
        {
            char* target = (dir == std::ios_base::beg ? eback() : dir == std::ios_base::cur ? gptr() : egptr()) + off;
            if (target < eback() || target > egptr())
                return pos_type(off_type(-1));
            setg(eback(), target, egptr());
            return pos_type(target - eback());
        }

        pos_type seekpos(pos_type pos, std::ios_base::openmode mode) override
        {
            return seekoff(off_type(pos), std::ios_base::beg, mode);
        }
    };

    bool readBytes(const Index& index, const Source& source, std::vector<char>& bytes)
    {
        if (source.archive < 0)
        {
            std::ifstream in(source.file, std::ios::binary | std::ios::ate);
            if (!in)
                return false;
            bytes.resize(static_cast<size_t>(in.tellg()));
            in.seekg(0);
            return static_cast<bool>(in.read(bytes.data(), static_cast<std::streamsize>(bytes.size())));
        }
        std::ifstream in(index.archives[static_cast<size_t>(source.archive)], std::ios::binary);
        bytes.resize(source.size);
        in.seekg(source.offset);
        return static_cast<bool>(in.read(bytes.data(), static_cast<std::streamsize>(bytes.size())));
    }

    std::atomic<bool> sCancel{ false };
    std::mutex sRunMutex;

    enum Result
    {
        Done = 0,
        Cancelled = 1,
        ConversionOff = 2,
        Busy = 3,
    };
}

namespace Alpha3
{
    // The pre-pass is run by the player, who waits for it: always on every core, whatever [General] thermal mode says
    // (thermal.cpp is the game's)
    int thermalPressure()
    {
        return 0;
    }

    unsigned thermalThreads(unsigned threads)
    {
        return threads;
    }

    std::string setting(std::string_view category, std::string_view name, std::string_view fallback)
    {
        const auto it = sSettings.find(std::string(category) + "/" + std::string(name));
        return it == sSettings.end() ? std::string(fallback) : it->second;
    }

    bool settingBool(std::string_view category, std::string_view name, bool fallback)
    {
        const std::string value = lower(setting(category, name, fallback ? "true" : "false"));
        return value == "true" || value == "1";
    }

    int settingInt(std::string_view category, std::string_view name, int fallback)
    {
        const std::string value = setting(category, name, "");
        return value.empty() ? fallback : std::atoi(value.c_str());
    }

    float settingFloat(std::string_view category, std::string_view name, float fallback)
    {
        const std::string value = setting(category, name, "");
        return value.empty() ? fallback : static_cast<float>(std::atof(value.c_str()));
    }

    std::filesystem::path cacheDirectory()
    {
        return sCacheDirectory;
    }
}

namespace
{
    // The pre-pass itself, for the platform's entry point below. report(phase, done, total, converted, cached, failed)
    // is called a few times a second from the calling thread.
    using Report = std::function<void(int, int, int, int, int, int)>;

    int runPrepass(const std::vector<std::string>& configs, const std::string& settingsFile, const std::string& cacheDir,
        int workers, const Report& report)
    {
        std::unique_lock running(sRunMutex, std::try_to_lock);
        if (!running.owns_lock())
            return Busy;
        sCancel = false;

        auto progress = [&](int phase, size_t done, size_t total) {
            const Alpha3::TextureConversionCounts counts = Alpha3::textureConversionCounts();
            static Alpha3::TextureConversionCounts start{};
            if (phase == 0 && done == 0)
                start = counts;
            report(phase, static_cast<int>(done), static_cast<int>(total), static_cast<int>(counts.converted - start.converted),
                static_cast<int>(counts.cached - start.cached), static_cast<int>(counts.failed - start.failed));
        };

        // The settings are read once per process (textureconversion.cpp keeps them), as in the game
        if (sSettings.empty())
            readSettings(settingsFile);
        if (sCacheDirectory.empty())
            sCacheDirectory = cacheDir;
        if (!Alpha3::textureConversionEnabled())
            return ConversionOff;

        Config config;
        for (const std::string& path : configs)
            readConfig(path, config);

        const auto started = std::chrono::steady_clock::now();
        Index index;
        progress(0, 0, config.data.size());
        for (const std::string& archive : config.archives)
        {
            // the last data directory that has it, as Files::Collections finds it
            for (auto dir = config.data.rbegin(); dir != config.data.rend(); ++dir)
            {
                std::error_code ec;
                if (std::filesystem::exists(*dir / archive, ec))
                {
                    addArchive(*dir / archive, index);
                    break;
                }
            }
        }
        for (size_t i = 0; i < config.data.size(); ++i)
        {
            if (sCancel)
                return Cancelled;
            addDirectory(config.data[i], index);
            progress(0, i + 1, config.data.size());
        }

        std::vector<std::pair<std::string, const Source*>> jobs;
        for (const auto& [name, source] : index.files)
        {
            if (name.compare(name.size() - 4, 4, ".dds") != 0)
                continue;
            if (index.files.count(name.substr(0, name.size() - 4) + ".ktx"))
                continue;
            jobs.emplace_back(name, &source);
        }
        // the big ones first, so the last minutes are not one texture on one core
        std::sort(jobs.begin(), jobs.end(), [](const auto& a, const auto& b) {
            return a.second->size > b.second->size;
        });
        Log(Debug::Info) << "Texture pre-pass: " << jobs.size() << " DDS textures in " << config.data.size()
                         << " data directories and " << index.archives.size() << " archives, listed in "
                         << std::chrono::duration_cast<std::chrono::milliseconds>(std::chrono::steady_clock::now() - started).count()
                         << " ms";

        osgDB::ReaderWriter* reader = osgDB::Registry::instance()->getReaderWriterForExtension("dds");
        const osg::ref_ptr<osgDB::Options> options = new osgDB::Options("dds_dxt1_detect_rgba ignoreTga2Fields");
        std::atomic<size_t> next{ 0 }, done{ 0 };
        auto work = [&] {
            std::vector<char> bytes;
            for (size_t i; !sCancel && (i = next++) < jobs.size(); ++done)
            {
                if (!reader || !readBytes(index, *jobs[i].second, bytes))
                    continue;
                MemoryBuffer buffer(bytes);
                std::istream stream(&buffer);
                osgDB::ReaderWriter::ReadResult result = reader->readImage(stream, options.get());
                if (const osg::Image* image = result.getImage())
                    Alpha3::transcodeDxt(*image, jobs[i].first);
            }
        };
        std::vector<std::thread> threads;
        for (int i = 0; i < std::max(1, static_cast<int>(workers)); ++i)
            threads.emplace_back(work);
        while (done < jobs.size() && !sCancel)
        {
            progress(1, done, jobs.size());
            std::this_thread::sleep_for(std::chrono::milliseconds(250));
        }
        for (std::thread& thread : threads)
            thread.join();
        progress(1, done, jobs.size());
        const Alpha3::TextureConversionCounts counts = Alpha3::textureConversionCounts();
        Log(Debug::Info) << "Texture pre-pass " << (sCancel ? "cancelled" : "done") << " after "
                         << std::chrono::duration_cast<std::chrono::seconds>(std::chrono::steady_clock::now() - started).count()
                         << " s: " << counts.converted << " converted, " << counts.cached << " already in the cache, "
                         << counts.failed << " failed";
        return sCancel ? Cancelled : Done;
    }
}

#ifdef __ANDROID__
// TexturePrepass.runNative(configs, settingsFile, cacheDir, workers, listener): the openmw.cfg files in the order the game
// reads them, the user's settings.cfg ([Textures]), the game's cache directory (the cache is its astc folder unless
// [Textures] astc cache path says otherwise). listener.onProgress(phase, done, total, converted, cached, failed) is called
// a few times a second from the calling thread: phase 0 while the data directories are listed (done/total are
// directories), 1 while the textures are converted. Returns Done, Cancelled, ConversionOff or Busy (another run).
extern "C" JNIEXPORT jint JNICALL Java_org_openmw_utils_TexturePrepass_runNative(JNIEnv* env, jobject /* thiz */,
    jobjectArray configs, jstring settingsFile, jstring cacheDir, jint workers, jobject listener)
{
    auto string = [env](jstring value) {
        const char* chars = env->GetStringUTFChars(value, nullptr);
        std::string result = chars;
        env->ReleaseStringUTFChars(value, chars);
        return result;
    };
    // looked up on the interface: the listener is a lambda class (the method name is kept with @Keep in TexturePrepass.kt)
    jclass listenerClass = env->FindClass("org/openmw/utils/TexturePrepass$Listener");
    const jmethodID onProgress = listenerClass ? env->GetMethodID(listenerClass, "onProgress", "(IIIIII)V") : nullptr;
    if (onProgress == nullptr)
    {
        env->ExceptionClear();
        Log(Debug::Error) << "Texture pre-pass: TexturePrepass$Listener.onProgress not found";
        return Busy;
    }
    std::vector<std::string> paths;
    for (jsize i = 0; i < env->GetArrayLength(configs); ++i)
    {
        auto path = static_cast<jstring>(env->GetObjectArrayElement(configs, i));
        paths.push_back(string(path));
        env->DeleteLocalRef(path);
    }
    return runPrepass(paths, string(settingsFile), string(cacheDir), workers,
        [&](int phase, int done, int total, int converted, int cached, int failed) {
            env->CallVoidMethod(listener, onProgress, phase, done, total, converted, cached, failed);
        });
}

extern "C" JNIEXPORT void JNICALL Java_org_openmw_utils_TexturePrepass_cancelNative(JNIEnv*, jobject)
{
    sCancel = true;
}
#else
// iOS: the same as Android's runNative, as a C function the launcher looks up with dlsym in libalpha3_texconv.framework.
// onProgress(context, phase, done, total, converted, cached, failed).
extern "C" __attribute__((visibility("default"))) int alpha3_texconv_run(const char* const* configs, int configCount,
    const char* settingsFile, const char* cacheDir, int workers,
    void (*onProgress)(void*, int, int, int, int, int, int), void* context)
{
    const std::vector<std::string> paths(configs, configs + configCount);
    return runPrepass(paths, settingsFile, cacheDir, workers,
        [&](int phase, int done, int total, int converted, int cached, int failed) {
            onProgress(context, phase, done, total, converted, cached, failed);
        });
}

extern "C" __attribute__((visibility("default"))) void alpha3_texconv_cancel()
{
    sCancel = true;
}
#endif
