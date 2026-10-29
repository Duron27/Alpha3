#ifndef MPSERVER_SERVERCORE_H
#define MPSERVER_SERVERCORE_H

#include <cstdint>
#include <filesystem>
#include <functional>
#include <memory>
#include <string>
#include <vector>

namespace MpServer
{
    struct Options
    {
        std::filesystem::path mMod; // the directory holding scripts/mp
        std::filesystem::path mScript; // server.lua
        std::filesystem::path mClientMods; // the client mods handed out to players: <dir>/<id>/...
        int mPort = 25565;
        std::string mPassword, mAdminPassword, mMotd, mWeather = "cycle";
        int mMaxPlayers = 64;
        double mTimeScale = 30, mStartHour = 9, mWeatherMinutes = 15;
    };

    struct PlayerInfo
    {
        std::uint32_t mId = 0;
        std::string mName, mAddress, mLocation;
        bool mAdmin = false, mMuted = false, mAway = false, mHost = false, mBot = false;
        int mPing = -1; // milliseconds, -1 if unknown
    };

    // What a window needs to show, taken from the relay.
    struct Snapshot
    {
        bool mValid = false;
        std::vector<PlayerInfo> mPlayers;
        std::vector<std::string> mBans;
        int mMaxPlayers = 0;
        bool mPasswordProtected = false;
        double mHour = 0;
        std::string mWeather;
        bool mWeatherFixed = false;
    };

    // The server without any user interface: the Lua relay on the engine's sockets. Everything is used from one
    // thread: call tick() often (every ~20 ms).
    class ServerCore
    {
    public:
        explicit ServerCore(const Options& options);
        ~ServerCore();
        ServerCore(const ServerCore&) = delete;
        ServerCore& operator=(const ServerCore&) = delete;

        // Starts listening. Returns an error message, or an empty string.
        std::string start();

        // Handles network events and time. Returns false once the server was asked to stop.
        bool tick();

        // One line of the server console; returns the answer (possibly several lines). quit/exit/stop ends tick().
        std::string console(const std::string& line);

        Snapshot snapshot();

        // Every line the server prints (it is also written to stdout).
        void setLogSink(std::function<void(const std::string&)> sink);

        void requestStop();
        void shutdown();

        const Options& options() const;

    private:
        struct Impl;
        std::unique_ptr<Impl> mImpl;
    };
}

#endif
