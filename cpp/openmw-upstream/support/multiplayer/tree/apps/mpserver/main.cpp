// openmw-mp-server: a standalone multiplayer server. By default it opens a window with the players, the sky, buttons
// for kick/ban/mute and a log; with --cli-only (or where there is no display) it runs in the terminal instead.
#include <atomic>
#include <cctype>
#include <chrono>
#include <csignal>
#include <cstdio>
#include <cstdlib>
#include <deque>
#include <filesystem>
#include <iostream>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include "servercore.hpp"
#ifdef MPSERVER_WITH_GUI
#include "gui.hpp"
#ifdef _WIN32
#include <windows.h>
#else
#include <dlfcn.h>
#endif
#endif

namespace
{
    using MpServer::Options;

    std::atomic<bool> gStop{ false };
    void onSignal(int)
    {
        gStop = true;
    }

    bool gCliOnly = false;

    std::filesystem::path executableDir(const char* argv0)
    {
        std::error_code ec;
        std::filesystem::path exe = std::filesystem::read_symlink("/proc/self/exe", ec);
        if (ec || exe.empty())
            exe = std::filesystem::absolute(argv0, ec);
        return exe.parent_path();
    }

    bool isModDir(const std::filesystem::path& dir)
    {
        std::error_code ec;
        return std::filesystem::exists(dir / "scripts" / "mp" / "relay.lua", ec);
    }

    // Without --mod: the mod's directory (the one holding scripts/mp) next to the program, in the source tree above it,
    // or below the directory the program was started from.
    std::filesystem::path findMod(const std::filesystem::path& exeDir, std::vector<std::filesystem::path>& tried)
    {
        const std::filesystem::path cwd = std::filesystem::current_path();
        const std::filesystem::path candidates[] = {
            exeDir / "multiplayer" / "mod",
            exeDir / "mod",
            cwd / "multiplayer" / "mod",
            cwd / "mod",
            exeDir / ".." / "multiplayer" / "mod",
            exeDir / ".." / ".." / "multiplayer" / "mod",
            exeDir / ".." / "share" / "openmw-mp" / "mod",
        };
        for (const auto& c : candidates)
        {
            tried.push_back(c.lexically_normal());
            if (isModDir(c))
                return c.lexically_normal();
        }
        return {};
    }

    void usage()
    {
        std::puts(
            "openmw-mp-server: a dedicated server for the OpenMW multiplayer mod\n\n"
            "It opens a window (players, sky, buttons for kick/ban/mute, a log and a command line). Use --cli-only for "
            "a\n"
            "plain terminal instead; without a display it does that by itself.\n\n"
            "  --cli-only               no window: run in the terminal\n"
            "  --mod <dir>              the mod's directory (the one containing scripts/mp); by default it is looked "
            "for\n"
            "                           next to the program and in the source tree above it\n"
            "  --script <file>          the server script; default <mod>/../server/server.lua\n"
            "  --client-mods <dir>      the client mods handed out to players (<dir>/<id>/..., each with an "
            "<id>.omwscripts);\n"
            "                           default <mod>/../clientmods, or clientmods next to the program\n"
            "  --port <n>               TCP port to listen on (default 25565)\n"
            "  --password <text>        password to join (or set MP_PASSWORD)\n"
            "  --admin-password <text>  password for /login (or set MP_ADMIN_PASSWORD)\n"
            "  --max-players <n>        most players at once (default 64, at most 128)\n"
            "  --motd <text>            message shown to players who join\n"
            "  --weather <id|cycle>     a fixed weather id, or cycle (default) to let it change by itself\n"
            "  --weather-minutes <n>    about how often the weather changes (default 15)\n"
            "  --time-scale <n>         game seconds per real second (default 30)\n"
            "  --start-hour <h>         time of day when the server starts (default 9)\n\n"
            "\n"
            "Server console commands (type them while the server runs; help lists them again):\n"
            "  players | who              list everybody online: id, name, flags, latency, address and location\n"
            "  kick <name>                disconnect a player\n"
            "  ban <name>                 disconnect a player and refuse that name until the server restarts\n"
            "  unban <name>               let a banned name join again\n"
            "  mute <name> | unmute <name>   stop or allow a player's chat\n"
            "  announce <text> | say <text>  send a message to everybody\n"
            "  weather                    show the current weather\n"
            "  weather <id>               fix the weather: clear, cloudy, overcast, foggy, rain, thunder, snow, ash, "
            "blight, blizzard\n"
            "  weather cycle              let the weather change by itself again\n"
            "  time <hour>                set the time of day, 0 to 24 (for example 14.5); the date is never synced\n"
            "  help | ?                   this list\n"
            "  quit | exit | stop         stop the server (Ctrl+C does the same)\n\n"
            "In the game, admins (after /login <admin password>) can use: /who /kick /ban /unban /mute /unmute\n"
            "/announce /weather /time. Everyone can use /players /me /w /r /g /p /goto /accept /decline /leave "
            "/pkick.");
    }

    bool parseArgs(int argc, char** argv, Options& o)
    {
        if (const char* v = std::getenv("MP_PASSWORD"))
            o.mPassword = v;
        if (const char* v = std::getenv("MP_ADMIN_PASSWORD"))
            o.mAdminPassword = v;
        for (int i = 1; i < argc; ++i)
        {
            const std::string a = argv[i];
            auto next = [&]() -> const char* {
                if (i + 1 >= argc)
                {
                    std::fprintf(stderr, "%s needs a value\n", a.c_str());
                    return nullptr;
                }
                return argv[++i];
            };
            if (a == "--cli-only" || a == "-cli-only")
            {
                gCliOnly = true;
                continue;
            }
            const char* v = nullptr;
            if (a == "--help" || a == "-h")
                return usage(), false;
            if (!(v = next()))
                return false;
            if (a == "--mod")
                o.mMod = v;
            else if (a == "--script")
                o.mScript = v;
            else if (a == "--client-mods")
                o.mClientMods = v;
            else if (a == "--port")
                o.mPort = std::atoi(v);
            else if (a == "--password")
                o.mPassword = v;
            else if (a == "--admin-password")
                o.mAdminPassword = v;
            else if (a == "--max-players")
                o.mMaxPlayers = std::atoi(v);
            else if (a == "--motd")
                o.mMotd = v;
            else if (a == "--weather")
                o.mWeather = v;
            else if (a == "--weather-minutes")
                o.mWeatherMinutes = std::atof(v);
            else if (a == "--time-scale")
                o.mTimeScale = std::atof(v);
            else if (a == "--start-hour")
                o.mStartHour = std::atof(v);
            else
            {
                std::fprintf(stderr, "unknown option %s (try --help)\n", a.c_str());
                return false;
            }
        }
        if (o.mPort < 1 || o.mPort > 65535)
        {
            std::fprintf(stderr, "the port must be 1-65535\n");
            return false;
        }
        return true;
    }

#ifdef MPSERVER_WITH_GUI
    // Whether a window can be opened here: always on Windows and macOS, on Linux only with a display (or when Qt was
    // told which platform to use, e.g. offscreen for tests).
    bool haveDisplay()
    {
#if defined(_WIN32) || defined(__APPLE__)
        return true;
#else
        auto set = [](const char* name) {
            const char* v = std::getenv(name);
            return v != nullptr && *v != '\0';
        };
        return set("DISPLAY") || set("WAYLAND_DISPLAY") || set("QT_QPA_PLATFORM");
#endif
    }

    // Loads the window module next to the program; null (with the reason in why) if it or Qt is not there.
    RunWindowFn loadWindow(const std::filesystem::path& exeDir, std::string& why)
    {
#ifdef _WIN32
        const std::filesystem::path path = exeDir / "openmw-mp-server-gui.dll";
        HMODULE lib = LoadLibraryW(path.c_str());
        if (!lib)
        {
            why = "could not load " + path.string();
            return nullptr;
        }
        auto fn = reinterpret_cast<RunWindowFn>(reinterpret_cast<void*>(GetProcAddress(lib, kRunWindowSymbol)));
#else
        const std::filesystem::path path = exeDir / "libopenmw-mp-server-gui.so";
        void* lib = dlopen(path.c_str(), RTLD_NOW);
        if (!lib)
        {
            const char* e = dlerror();
            why = e ? e : "could not load " + path.string();
            return nullptr;
        }
        auto fn = reinterpret_cast<RunWindowFn>(dlsym(lib, kRunWindowSymbol));
#endif
        if (!fn)
            why = path.string() + " has no window entry point";
        return fn;
    }
#endif

    // The terminal: lines typed by the operator are the console.
    int runTerminal(MpServer::ServerCore& core)
    {
        std::mutex mutex;
        std::deque<std::string> lines;
        std::thread([&] {
            std::string line;
            while (std::getline(std::cin, line))
            {
                const std::lock_guard lock(mutex);
                lines.push_back(line);
            }
        }).detach();

        bool running = true;
        while (!gStop && running)
        {
            running = core.tick();
            std::vector<std::string> typed;
            {
                const std::lock_guard lock(mutex);
                typed.assign(lines.begin(), lines.end());
                lines.clear();
            }
            for (const std::string& line : typed)
            {
                const std::string answer = core.console(line);
                if (!answer.empty())
                    std::printf("%s\n", answer.c_str());
            }
            std::this_thread::sleep_for(std::chrono::milliseconds(20));
        }
        std::puts("[server] stopping");
        core.shutdown();
        std::fflush(stdout);
        // The console thread is blocked reading stdin and holds its lock; the normal exit path would wait for it
        // forever.
        std::_Exit(0);
    }
}

int main(int argc, char** argv)
{
    Options options;
    if (!parseArgs(argc, argv, options))
        return 1;
    {
        const std::filesystem::path exeDir = executableDir(argv[0]);
        if (options.mMod.empty())
        {
            std::vector<std::filesystem::path> tried;
            options.mMod = findMod(exeDir, tried);
            if (options.mMod.empty())
            {
                std::fprintf(stderr,
                    "[server] cannot find the multiplayer mod (a directory containing scripts/mp/relay.lua).\n"
                    "         Looked in:\n");
                for (const auto& t : tried)
                    std::fprintf(stderr, "           %s\n", t.string().c_str());
                std::fprintf(
                    stderr, "         Pass it with --mod <dir>, e.g. --mod /path/to/clockwork-alpha/multiplayer/mod\n");
                return 1;
            }
            std::printf("[server] using the mod in %s\n", options.mMod.string().c_str());
        }
        else if (!isModDir(options.mMod))
        {
            std::fprintf(stderr, "[server] %s is not the multiplayer mod (scripts/mp/relay.lua is missing)\n",
                options.mMod.string().c_str());
            return 1;
        }
        if (options.mClientMods.empty())
        {
            std::error_code mec;
            const std::filesystem::path inSource = (options.mMod / ".." / "clientmods").lexically_normal();
            options.mClientMods = std::filesystem::is_directory(inSource, mec) ? inSource : exeDir / "clientmods";
        }
        {
            std::error_code cec;
            if (std::filesystem::is_directory(options.mClientMods, cec))
                std::printf(
                    "[server] extra client mods are handed out from %s\n", options.mClientMods.string().c_str());
        }
        if (options.mScript.empty())
            options.mScript = options.mMod / ".." / "server" / "server.lua";
        std::error_code ec;
        if (!std::filesystem::exists(options.mScript, ec))
        {
            const std::filesystem::path beside = exeDir / "server.lua";
            if (std::filesystem::exists(beside, ec))
                options.mScript = beside;
            else
            {
                std::fprintf(stderr, "[server] cannot find the server script %s (pass --script <file>)\n",
                    options.mScript.lexically_normal().string().c_str());
                return 1;
            }
        }
    }

    std::setvbuf(stdout, nullptr, _IOLBF, 0);
    std::signal(SIGINT, onSignal);
    std::signal(SIGTERM, onSignal);

    bool window = false;
#ifdef MPSERVER_WITH_GUI
    RunWindowFn runWindow = nullptr;
    window = !gCliOnly && haveDisplay();
    if (!gCliOnly && !window)
        std::puts("[server] no display found: running in the terminal (use --cli-only to skip this check)");
    if (window)
    {
        std::string why;
        runWindow = loadWindow(executableDir(argv[0]), why);
        if (!runWindow)
        {
            std::printf("[server] no window (%s): running in the terminal\n", why.c_str());
            window = false;
        }
    }
#else
    if (!gCliOnly)
        std::puts("[server] this build has no window: running in the terminal");
#endif

    MpServer::ServerCore core(options);
    // In the window the log goes to its log pane; the terminal gets the lines through stdout either way.
    const std::string error = window ? std::string() : core.start();
    if (!error.empty())
    {
        std::fprintf(stderr, "[server] %s\n", error.c_str());
        return 1;
    }
    if (!window)
        return runTerminal(core);
#ifdef MPSERVER_WITH_GUI
    return runWindow(core, argc, argv, gStop);
#else
    return 0;
#endif
}
