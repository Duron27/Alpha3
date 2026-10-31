#include "clientmods.hpp"

#include <algorithm>
#include <array>
#include <atomic>
#include <fstream>
#include <map>
#include <mutex>
#include <set>
#include <system_error>

#include "sha256.hpp"

#ifdef _WIN32
#include <windows.h>
#elif defined(__linux__) && !defined(__ANDROID__)
#include <unistd.h>
#endif

namespace Misc::ClientMods
{
    namespace
    {
        namespace fs = std::filesystem;

        fs::path sRoot;
        std::string sExtraId;
        fs::path sExtraDir;
        std::string sExtraList;
        const std::vector<std::string> sExtraFolders = { "scripts", "l10n", "textures" };
        std::atomic<bool> sRestart{ false };
        // "<mod>/<path>" of every file of the mods loaded at startup (startupEntries): what the engine can read again
        // without starting over
        std::set<std::string> sLoaded;

        bool nameChar(char c)
        {
            return (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= '0' && c <= '9') || c == '_' || c == '-'
                || c == '.';
        }

        bool allowedExtension(std::string_view path)
        {
            static const std::set<std::string, std::less<>> allowed
                = { "lua", "omwscripts", "yaml", "txt", "tga", "dds", "png", "jpg", "nif", "kf", "wav", "mp3", "ogg" };
            const std::size_t dot = path.rfind('.');
            if (dot == std::string_view::npos)
                return false;
            std::string ext(path.substr(dot + 1));
            std::transform(ext.begin(), ext.end(), ext.begin(), [](unsigned char c) { return std::tolower(c); });
            return allowed.count(ext) > 0;
        }

        std::optional<std::string> readWhole(const fs::path& path, std::uint64_t limit)
        {
            std::error_code ec;
            const auto size = fs::file_size(path, ec);
            if (ec || size > limit)
                return std::nullopt;
            std::ifstream in(path, std::ios::binary);
            if (!in)
                return std::nullopt;
            std::string data(static_cast<std::size_t>(size), '\0');
            in.read(data.data(), static_cast<std::streamsize>(size));
            if (static_cast<std::uint64_t>(in.gcount()) != size)
                return std::nullopt;
            return data;
        }

        struct CachedHash
        {
            std::uint64_t mSize;
            fs::file_time_type mTime;
            std::string mHash;
        };
        std::mutex sCacheMutex;
        std::map<std::string, CachedHash> sCache;

        std::string hashOf(const fs::path& path, std::uint64_t size)
        {
            std::error_code ec;
            const auto time = fs::last_write_time(path, ec);
            const std::string key = path.generic_string();
            {
                const std::lock_guard lock(sCacheMutex);
                const auto it = sCache.find(key);
                if (!ec && it != sCache.end() && it->second.mSize == size && it->second.mTime == time)
                    return it->second.mHash;
            }
            const auto data = readWhole(path, sMaxFileSize);
            if (!data)
                return {};
            std::string hash = sha256Hex(*data);
            if (!ec)
            {
                const std::lock_guard lock(sCacheMutex);
                sCache[key] = { size, time, hash };
            }
            return hash;
        }

        // The files of one mod folder (only plain files with allowed names; anything else is ignored)
        std::vector<File> filesOf(const fs::path& dir, bool withHashes, const std::vector<std::string>& only = {})
        {
            std::vector<File> files;
            std::error_code ec;
            std::vector<fs::path> starts;
            if (only.empty())
                starts.push_back(dir);
            else
                for (const std::string& sub : only)
                    starts.push_back(dir / sub);
            for (const fs::path& start : starts)
                for (fs::recursive_directory_iterator it(start, fs::directory_options::none, ec), end; !ec && it != end;
                     it.increment(ec))
                {
                    if (!it->is_regular_file(ec) || it->is_symlink(ec))
                        continue;
                    const std::string rel = fs::relative(it->path(), dir, ec).generic_string();
                    if (ec || !validPath(rel))
                        continue;
                    const std::uint64_t size = it->file_size(ec);
                    if (ec || size > sMaxFileSize)
                        continue;
                    if (files.size() >= sMaxFilesPerMod)
                        break;
                    File file;
                    file.mPath = rel;
                    file.mSize = size;
                    if (withHashes)
                        file.mHash = hashOf(it->path(), size);
                    files.push_back(std::move(file));
                }
            std::sort(files.begin(), files.end(), [](const File& a, const File& b) { return a.mPath < b.mPath; });
            return files;
        }

        bool isExtra(std::string_view id)
        {
            std::error_code ec;
            return !sExtraId.empty() && id == sExtraId && !fs::is_directory(sRoot / std::string(id), ec);
        }

        std::vector<File> extraFiles(bool withHashes)
        {
            std::vector<File> files = filesOf(sExtraDir, withHashes, sExtraFolders);
            // the script list, as the plain file it is (a client that has it built in replaces it by this one)
            std::error_code ec;
            const fs::path list = sExtraDir / sExtraList;
            if (validPath(sExtraList) && fs::is_regular_file(list, ec) && !fs::is_symlink(list, ec))
            {
                File file;
                file.mPath = sExtraList;
                file.mSize = fs::file_size(list, ec);
                if (withHashes)
                    file.mHash = hashOf(list, file.mSize);
                if (!ec && file.mSize <= sMaxFileSize)
                    files.push_back(std::move(file));
            }
            std::sort(files.begin(), files.end(), [](const File& a, const File& b) { return a.mPath < b.mPath; });
            return files;
        }

        std::vector<std::string> modIds()
        {
            std::vector<std::string> ids;
            std::error_code ec;
            for (fs::directory_iterator it(sRoot, ec), end; !sRoot.empty() && !ec && it != end; it.increment(ec))
            {
                const std::string id = it->path().filename().string();
                if (it->is_directory(ec) && !it->is_symlink(ec) && validId(id))
                    ids.push_back(id);
            }
            if (!sExtraId.empty() && !fs::is_directory(sRoot / sExtraId, ec))
                ids.push_back(sExtraId);
            std::sort(ids.begin(), ids.end());
            if (ids.size() > sMaxMods)
                ids.resize(sMaxMods);
            return ids;
        }
    }

    void setRoot(const fs::path& root)
    {
        sRoot = root;
    }

    void setExtraMod(const std::string& id, const fs::path& dir, const std::string& scriptList)
    {
        sExtraId = validId(id) ? id : std::string();
        sExtraDir = dir;
        sExtraList = scriptList;
    }

    const fs::path& root()
    {
        return sRoot;
    }

    bool validId(std::string_view id)
    {
        if (id.empty() || id.size() > 40)
            return false;
        for (char c : id)
            if (!((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-'))
                return false;
        return true;
    }

    bool validPath(std::string_view path)
    {
        if (path.empty() || path.size() > 160 || !allowedExtension(path))
            return false;
        int parts = 0;
        std::size_t start = 0;
        while (start <= path.size())
        {
            std::size_t slash = path.find('/', start);
            if (slash == std::string_view::npos)
                slash = path.size();
            const std::string_view part = path.substr(start, slash - start);
            if (part.empty() || part.front() == '.' || ++parts > 6)
                return false;
            for (char c : part)
                if (!nameChar(c))
                    return false;
            start = slash + 1;
        }
        return true;
    }

    std::vector<Mod> installed()
    {
        std::vector<Mod> mods;
        for (const std::string& id : modIds())
        {
            Mod mod;
            mod.mId = id;
            mod.mFiles = isExtra(id) ? extraFiles(true) : filesOf(sRoot / id, true);
            if (!mod.mFiles.empty())
                mods.push_back(std::move(mod));
        }
        return mods;
    }

    std::string manifestJson()
    {
        // (names are limited to letters, digits and . _ - /, so nothing needs escaping)
        std::string json = "[";
        bool firstMod = true;
        for (const Mod& mod : installed())
        {
            json += firstMod ? "" : ",";
            firstMod = false;
            json += "{\"id\":\"" + mod.mId + "\",\"files\":[";
            bool firstFile = true;
            for (const File& file : mod.mFiles)
            {
                json += firstFile ? "" : ",";
                firstFile = false;
                json += "{\"p\":\"" + file.mPath + "\",\"s\":" + std::to_string(file.mSize) + ",\"h\":\"" + file.mHash
                    + "\"}";
            }
            json += "]}";
        }
        return json + "]";
    }

    std::optional<std::string> read(std::string_view id, std::string_view path)
    {
        if (!validId(id) || !validPath(path))
            return std::nullopt;
        if (isExtra(id))
        {
            const std::size_t slash = path.find('/');
            if (path == sExtraList)
            {
                const fs::path list = sExtraDir / sExtraList;
                std::error_code ec;
                if (!fs::is_regular_file(list, ec) || fs::is_symlink(list, ec))
                    return std::nullopt;
                return readWhole(list, sMaxFileSize);
            }
            if (slash == std::string_view::npos
                || std::find(sExtraFolders.begin(), sExtraFolders.end(), std::string(path.substr(0, slash)))
                    == sExtraFolders.end())
                return std::nullopt;
            const fs::path extra = sExtraDir / std::string(path);
            std::error_code ec;
            if (!fs::is_regular_file(extra, ec) || fs::is_symlink(extra, ec))
                return std::nullopt;
            return readWhole(extra, sMaxFileSize);
        }
        if (sRoot.empty())
            return std::nullopt;
        const fs::path file = sRoot / std::string(id) / std::string(path);
        std::error_code ec;
        if (!fs::is_regular_file(file, ec) || fs::is_symlink(file, ec))
            return std::nullopt;
        return readWhole(file, sMaxFileSize);
    }

    std::string write(std::string_view id, std::string_view path, std::string_view data, std::string_view expectedHash)
    {
        if (sRoot.empty())
            return "client mods are not enabled";
        if (!validId(id))
            return "bad mod name";
        if (!validPath(path))
            return "bad or disallowed file name";
        if (data.size() > sMaxFileSize)
            return "file too large";
        std::string expected(expectedHash);
        std::transform(
            expected.begin(), expected.end(), expected.begin(), [](unsigned char c) { return std::tolower(c); });
        if (sha256Hex(data) != expected)
            return "hash mismatch";

        const fs::path dir = sRoot / std::string(id);
        std::error_code ec;
        if (!fs::exists(dir, ec) && modIds().size() >= sMaxMods)
            return "too many mods";
        const fs::path target = dir / std::string(path);
        // room for it: what the mod holds already, without the file being replaced
        std::uint64_t others = 0;
        std::size_t count = 0;
        for (const File& file : filesOf(dir, false))
        {
            ++count;
            if (file.mPath != path)
                others += file.mSize;
        }
        if (others + data.size() > sMaxModSize || count > sMaxFilesPerMod)
            return "the mod is too large";
        if (fs::is_symlink(target, ec))
            return "refusing to write through a link";

        fs::create_directories(target.parent_path(), ec);
        if (ec)
            return "could not create the folder: " + ec.message();
        // a folder that is not really inside the mods folder (a link somewhere): refuse
        const fs::path real = fs::weakly_canonical(target.parent_path(), ec);
        const fs::path rootReal = fs::weakly_canonical(sRoot, ec);
        if (real.generic_string().rfind(rootReal.generic_string(), 0) != 0)
            return "outside the mods folder";

        const fs::path temp = target.string() + ".part";
        {
            std::ofstream out(temp, std::ios::binary | std::ios::trunc);
            if (!out)
                return "could not write the file";
            out.write(data.data(), static_cast<std::streamsize>(data.size()));
            if (!out)
                return "could not write the file";
        }
        fs::rename(temp, target, ec);
        if (ec)
        {
            fs::remove(temp, ec);
            return "could not move the file into place";
        }
        return {};
    }

    std::string remove(std::string_view id, std::string_view path)
    {
        if (sRoot.empty() || !validId(id))
            return "bad mod name";
        std::error_code ec;
        if (path.empty())
        {
            fs::remove_all(sRoot / std::string(id), ec);
        }
        else
        {
            if (!validPath(path))
                return "bad file name";
            fs::remove(sRoot / std::string(id) / std::string(path), ec);
        }
        return ec ? ec.message() : std::string();
    }

    std::string base64Encode(std::string_view data)
    {
        static constexpr char table[] = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";
        std::string out;
        out.reserve((data.size() + 2) / 3 * 4);
        std::size_t i = 0;
        for (; i + 2 < data.size(); i += 3)
        {
            const std::uint32_t v
                = (std::uint8_t(data[i]) << 16) | (std::uint8_t(data[i + 1]) << 8) | std::uint8_t(data[i + 2]);
            out += table[v >> 18];
            out += table[(v >> 12) & 63];
            out += table[(v >> 6) & 63];
            out += table[v & 63];
        }
        if (i + 1 == data.size())
        {
            const std::uint32_t v = std::uint8_t(data[i]) << 16;
            out += table[v >> 18];
            out += table[(v >> 12) & 63];
            out += "==";
        }
        else if (i + 2 == data.size())
        {
            const std::uint32_t v = (std::uint8_t(data[i]) << 16) | (std::uint8_t(data[i + 1]) << 8);
            out += table[v >> 18];
            out += table[(v >> 12) & 63];
            out += table[(v >> 6) & 63];
            out += '=';
        }
        return out;
    }

    std::optional<std::string> base64Decode(std::string_view text)
    {
        if (text.size() % 4 != 0)
            return std::nullopt;
        auto value = [](char c) -> int {
            if (c >= 'A' && c <= 'Z')
                return c - 'A';
            if (c >= 'a' && c <= 'z')
                return c - 'a' + 26;
            if (c >= '0' && c <= '9')
                return c - '0' + 52;
            if (c == '+')
                return 62;
            if (c == '/')
                return 63;
            return -1;
        };
        std::string out;
        out.reserve(text.size() / 4 * 3);
        for (std::size_t i = 0; i < text.size(); i += 4)
        {
            const bool last = i + 4 == text.size();
            int v[4];
            int pad = 0;
            for (int k = 0; k < 4; ++k)
            {
                if (text[i + k] == '=' && last && k >= 2)
                {
                    v[k] = 0;
                    ++pad;
                }
                else if ((v[k] = value(text[i + k])) < 0 || pad > 0)
                    return std::nullopt;
            }
            const std::uint32_t n = (v[0] << 18) | (v[1] << 12) | (v[2] << 6) | v[3];
            out += static_cast<char>(n >> 16);
            if (pad < 2)
                out += static_cast<char>((n >> 8) & 0xff);
            if (pad < 1)
                out += static_cast<char>(n & 0xff);
        }
        return out;
    }

    void startupEntries(std::vector<fs::path>& dataDirs, std::vector<std::string>& scriptFiles)
    {
        for (const std::string& id : modIds())
        {
            const fs::path dir = sRoot / id;
            std::vector<std::string> scripts;
            std::error_code ec;
            for (fs::directory_iterator it(dir, ec), end; !ec && it != end; it.increment(ec))
            {
                const std::string name = it->path().filename().string();
                if (it->is_regular_file(ec) && !it->is_symlink(ec) && validPath(name)
                    && it->path().extension() == ".omwscripts")
                    scripts.push_back(name);
            }
            if (scripts.empty())
                continue;
            std::sort(scripts.begin(), scripts.end());
            dataDirs.push_back(dir);
            for (const File& file : filesOf(dir, false))
                sLoaded.insert(id + "/" + file.mPath);
            scriptFiles.insert(scriptFiles.end(), scripts.begin(), scripts.end());
        }
    }

    bool wasLoaded(std::string_view id, std::string_view path)
    {
        std::string key(id);
        key += '/';
        key += path;
        return sLoaded.count(key) > 0;
    }

    void requestRestart()
    {
        sRestart = true;
    }

    bool restartRequested()
    {
        return sRestart;
    }

    bool canRelaunch()
    {
#if defined(_WIN32) || (defined(__linux__) && !defined(__ANDROID__))
        return true;
#else
        return false;
#endif
    }

    bool relaunch(int argc, char** argv)
    {
#ifdef _WIN32
        (void)argc;
        (void)argv;
        STARTUPINFOW startup = {};
        startup.cb = sizeof(startup);
        PROCESS_INFORMATION info = {};
        std::wstring command = GetCommandLineW();
        if (!CreateProcessW(nullptr, command.data(), nullptr, nullptr, FALSE, 0, nullptr, nullptr, &startup, &info))
            return false;
        CloseHandle(info.hProcess);
        CloseHandle(info.hThread);
        return true;
#elif defined(__linux__) && !defined(__ANDROID__)
        std::vector<char*> args(argv, argv + argc);
        args.push_back(nullptr);
        execv("/proc/self/exe", args.data()); // (only returns when it failed)
        return false;
#else
        (void)argc;
        (void)argv;
        return false;
#endif
    }
}
