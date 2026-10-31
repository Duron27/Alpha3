// The server without a user interface: embeds the LuaJIT VM and the engine's own sockets (components/net), supplies the
// three engine modules the relay uses (openmw.net sockets, openmw.core a clock, openmw.markup a JSON decoder) and
// runs multiplayer/server/server.lua, which runs the relay of the mod unchanged.
#include "servercore.hpp"
#include <components/misc/clientmods.hpp>
#include <components/misc/sha256.hpp>

// engine modules the relay uses: openmw.net (sockets), openmw.core (a clock) and openmw.markup (a JSON decoder).
#include <atomic>
#include <cctype>
#include <chrono>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <deque>
#include <filesystem>
#include <fstream>
#include <iostream>
#include <memory>
#include <mutex>
#include <sstream>
#include <stdexcept>
#include <string>
#include <thread>
#include <vector>

#include <lua.hpp>

#include <components/net/sockets.hpp>

namespace
{
    struct Host
    {
        Net::Sockets mSockets;
        std::filesystem::path mModDir;
        std::chrono::steady_clock::time_point mStart = std::chrono::steady_clock::now();
        std::function<void(const std::string&)> mSink;
    };
    Host* gHost = nullptr;

    constexpr const char* sLoadedKey = "mp_loaded";
    constexpr std::size_t sMaxJsonDepth = 64;

    ///////////////////////////////////////////////////////////////// JSON -> Lua

    class JsonReader
    {
    public:
        JsonReader(lua_State* l, std::string_view text)
            : mL(l)
            , mText(text)
        {
        }

        // Pushes the decoded value. Throws std::runtime_error on bad input.
        void parse()
        {
            skipSpace();
            value(0);
            skipSpace();
            if (mPos != mText.size())
                fail("unexpected data after the value");
        }

    private:
        [[noreturn]] void fail(const char* what) const
        {
            throw std::runtime_error(std::string("bad JSON at ") + std::to_string(mPos) + ": " + what);
        }
        void skipSpace()
        {
            while (mPos < mText.size()
                && (mText[mPos] == ' ' || mText[mPos] == '\t' || mText[mPos] == '\n' || mText[mPos] == '\r'))
                ++mPos;
        }
        bool consume(std::string_view word)
        {
            if (mText.substr(mPos, word.size()) != word)
                return false;
            mPos += word.size();
            return true;
        }
        void value(std::size_t depth)
        {
            if (depth > sMaxJsonDepth)
                fail("nested too deeply");
            if (mPos >= mText.size())
                fail("unexpected end");
            if (!lua_checkstack(mL, 4))
                fail("too deep for the stack");
            const char c = mText[mPos];
            if (c == '{')
                object(depth);
            else if (c == '[')
                array(depth);
            else if (c == '"')
            {
                const std::string s = string();
                lua_pushlstring(mL, s.data(), s.size());
            }
            else if (consume("true"))
                lua_pushboolean(mL, 1);
            else if (consume("false"))
                lua_pushboolean(mL, 0);
            else if (consume("null"))
                lua_pushnil(mL);
            else
                number();
        }
        void object(std::size_t depth)
        {
            ++mPos;
            lua_createtable(mL, 0, 4);
            skipSpace();
            if (mPos < mText.size() && mText[mPos] == '}')
            {
                ++mPos;
                return;
            }
            for (;;)
            {
                skipSpace();
                if (mPos >= mText.size() || mText[mPos] != '"')
                    fail("expected a key");
                const std::string key = string();
                skipSpace();
                if (mPos >= mText.size() || mText[mPos] != ':')
                    fail("expected ':'");
                ++mPos;
                skipSpace();
                value(depth + 1);
                lua_setfield(mL, -2, key.c_str()); // (a nil value leaves the key out)
                skipSpace();
                if (mPos >= mText.size())
                    fail("unexpected end");
                if (mText[mPos] == ',')
                {
                    ++mPos;
                    continue;
                }
                if (mText[mPos] == '}')
                {
                    ++mPos;
                    return;
                }
                fail("expected ',' or '}'");
            }
        }
        void array(std::size_t depth)
        {
            ++mPos;
            lua_createtable(mL, 4, 0);
            skipSpace();
            if (mPos < mText.size() && mText[mPos] == ']')
            {
                ++mPos;
                return;
            }
            int index = 1;
            for (;;)
            {
                skipSpace();
                value(depth + 1);
                lua_rawseti(mL, -2, index++);
                skipSpace();
                if (mPos >= mText.size())
                    fail("unexpected end");
                if (mText[mPos] == ',')
                {
                    ++mPos;
                    continue;
                }
                if (mText[mPos] == ']')
                {
                    ++mPos;
                    return;
                }
                fail("expected ',' or ']'");
            }
        }
        static void appendUtf8(std::string& out, unsigned code)
        {
            if (code < 0x80)
                out.push_back(static_cast<char>(code));
            else if (code < 0x800)
            {
                out.push_back(static_cast<char>(0xC0 | (code >> 6)));
                out.push_back(static_cast<char>(0x80 | (code & 0x3F)));
            }
            else if (code < 0x10000)
            {
                out.push_back(static_cast<char>(0xE0 | (code >> 12)));
                out.push_back(static_cast<char>(0x80 | ((code >> 6) & 0x3F)));
                out.push_back(static_cast<char>(0x80 | (code & 0x3F)));
            }
            else
            {
                out.push_back(static_cast<char>(0xF0 | (code >> 18)));
                out.push_back(static_cast<char>(0x80 | ((code >> 12) & 0x3F)));
                out.push_back(static_cast<char>(0x80 | ((code >> 6) & 0x3F)));
                out.push_back(static_cast<char>(0x80 | (code & 0x3F)));
            }
        }
        unsigned hex4()
        {
            if (mPos + 4 > mText.size())
                fail("bad \\u escape");
            unsigned code = 0;
            for (int i = 0; i < 4; ++i)
            {
                const char h = mText[mPos++];
                code <<= 4;
                if (h >= '0' && h <= '9')
                    code |= static_cast<unsigned>(h - '0');
                else if (h >= 'a' && h <= 'f')
                    code |= static_cast<unsigned>(h - 'a' + 10);
                else if (h >= 'A' && h <= 'F')
                    code |= static_cast<unsigned>(h - 'A' + 10);
                else
                    fail("bad \\u escape");
            }
            return code;
        }
        std::string string()
        {
            ++mPos; // opening quote
            std::string out;
            for (;;)
            {
                if (mPos >= mText.size())
                    fail("unterminated string");
                const char c = mText[mPos++];
                if (c == '"')
                    return out;
                if (c != '\\')
                {
                    out.push_back(c);
                    continue;
                }
                if (mPos >= mText.size())
                    fail("unterminated string");
                const char e = mText[mPos++];
                switch (e)
                {
                    case '"':
                        out.push_back('"');
                        break;
                    case '\\':
                        out.push_back('\\');
                        break;
                    case '/':
                        out.push_back('/');
                        break;
                    case 'b':
                        out.push_back('\b');
                        break;
                    case 'f':
                        out.push_back('\f');
                        break;
                    case 'n':
                        out.push_back('\n');
                        break;
                    case 'r':
                        out.push_back('\r');
                        break;
                    case 't':
                        out.push_back('\t');
                        break;
                    case 'u':
                    {
                        unsigned code = hex4();
                        if (code >= 0xD800 && code < 0xDC00 && consume("\\u"))
                        {
                            const unsigned low = hex4();
                            if (low >= 0xDC00 && low < 0xE000)
                                code = 0x10000 + ((code - 0xD800) << 10) + (low - 0xDC00);
                            else
                                code = 0xFFFD;
                        }
                        else if (code >= 0xD800 && code < 0xE000)
                            code = 0xFFFD;
                        appendUtf8(out, code);
                        break;
                    }
                    default:
                        fail("bad escape");
                }
            }
        }
        void number()
        {
            const std::size_t start = mPos;
            while (mPos < mText.size() && std::strchr("+-0123456789.eE", mText[mPos]) != nullptr && mText[mPos] != '\0')
                ++mPos;
            if (mPos == start)
                fail("unexpected character");
            const std::string token(mText.substr(start, mPos - start));
            char* end = nullptr;
            const double number = std::strtod(token.c_str(), &end);
            if (end == nullptr || *end != '\0')
                fail("bad number");
            lua_pushnumber(mL, number);
        }

        lua_State* mL;
        std::string_view mText;
        std::size_t mPos = 0;
    };

    ///////////////////////////////////////////////////////////////// openmw.* modules

    std::uint32_t socketId(lua_State* l, int index)
    {
        const double n = luaL_checknumber(l, index);
        if (n < 0 || n > 4294967295.0)
            return 0;
        return static_cast<std::uint32_t>(n);
    }

    int netConnect(lua_State* l)
    {
        std::size_t len = 0;
        const char* host = luaL_checklstring(l, 1, &len);
        const double port = luaL_checknumber(l, 2);
        if (port < 1 || port > 65535)
            luaL_error(l, "invalid port");
        const std::uint32_t id = gHost->mSockets.connect(std::string(host, len), static_cast<std::uint16_t>(port));
        if (id == 0)
            lua_pushnil(l);
        else
            lua_pushnumber(l, id);
        return 1;
    }

    int netListen(lua_State* l)
    {
        const double port = luaL_checknumber(l, 1);
        if (port < 1 || port > 65535)
            luaL_error(l, "invalid port");
        std::string error;
        const std::uint32_t id = gHost->mSockets.listen(static_cast<std::uint16_t>(port), error);
        if (id == 0)
        {
            lua_pushnil(l);
            lua_pushlstring(l, error.data(), error.size());
            return 2;
        }
        lua_pushnumber(l, id);
        return 1;
    }

    int netSend(lua_State* l)
    {
        const std::uint32_t id = socketId(l, 1);
        std::size_t len = 0;
        const char* data = luaL_checklstring(l, 2, &len);
        const bool raw = lua_toboolean(l, 3) != 0;
        lua_pushboolean(l, gHost->mSockets.send(id, std::string_view(data, len), raw));
        return 1;
    }

    int netClose(lua_State* l)
    {
        gHost->mSockets.close(socketId(l, 1));
        return 0;
    }

    int pushAddress(lua_State* l, const std::string& address)
    {
        if (address.empty())
            lua_pushnil(l);
        else
            lua_pushlstring(l, address.data(), address.size());
        return 1;
    }

    int netLocalAddress(lua_State* l)
    {
        return pushAddress(l, gHost->mSockets.localAddress(socketId(l, 1)));
    }
    int netRemoteAddress(lua_State* l)
    {
        return pushAddress(l, gHost->mSockets.remoteAddress(socketId(l, 1)));
    }

    int coreGetRealTime(lua_State* l)
    {
        lua_pushnumber(l, std::chrono::duration<double>(std::chrono::steady_clock::now() - gHost->mStart).count());
        return 1;
    }

    int markupDecode(lua_State* l)
    {
        std::size_t len = 0;
        const char* text = luaL_checklstring(l, 1, &len);
        std::string error;
        const int top = lua_gettop(l);
        try
        {
            JsonReader(l, std::string_view(text, len)).parse();
        }
        catch (const std::exception& e)
        {
            lua_settop(l, top);
            error = e.what();
        }
        if (!error.empty())
            luaL_error(l, "%s", error.c_str());
        return 1;
    }

    int modstoreManifest(lua_State* l)
    {
        const std::string json = Misc::ClientMods::manifestJson();
        lua_pushlstring(l, json.data(), json.size());
        return 1;
    }

    int modstoreRead(lua_State* l)
    {
        std::size_t idLen = 0, pathLen = 0;
        const char* id = luaL_checklstring(l, 1, &idLen);
        const char* path = luaL_checklstring(l, 2, &pathLen);
        const auto data = Misc::ClientMods::read(std::string_view(id, idLen), std::string_view(path, pathLen));
        if (!data)
            lua_pushnil(l);
        else
            lua_pushlstring(l, data->data(), data->size());
        return 1;
    }

    int modstoreSha256(lua_State* l)
    {
        std::size_t len = 0;
        const char* data = luaL_checklstring(l, 1, &len);
        const std::string hex = Misc::sha256Hex(std::string_view(data, len));
        lua_pushlstring(l, hex.data(), hex.size());
        return 1;
    }

    int modstoreBase64(lua_State* l)
    {
        std::size_t len = 0;
        const char* data = luaL_checklstring(l, 1, &len);
        const std::string text = Misc::ClientMods::base64Encode(std::string_view(data, len));
        lua_pushlstring(l, text.data(), text.size());
        return 1;
    }

    void registerFunction(lua_State* l, const char* name, lua_CFunction f)
    {
        lua_pushcfunction(l, f);
        lua_setfield(l, -2, name);
    }

    bool buildBuiltin(lua_State* l, const std::string& name)
    {
        if (name == "openmw.net")
        {
            lua_createtable(l, 0, 8);
            registerFunction(l, "connect", netConnect);
            registerFunction(l, "listen", netListen);
            registerFunction(l, "send", netSend);
            registerFunction(l, "close", netClose);
            registerFunction(l, "localAddress", netLocalAddress);
            registerFunction(l, "remoteAddress", netRemoteAddress);
            return true;
        }
        if (name == "openmw.core")
        {
            lua_createtable(l, 0, 2);
            registerFunction(l, "getRealTime", coreGetRealTime);
            return true;
        }
        if (name == "openmw.modstore")
        {
            // The client mods this server hands out (a folder of <id>/... with their hashes), read-only
            lua_createtable(l, 0, 4);
            registerFunction(l, "manifest", modstoreManifest);
            registerFunction(l, "read", modstoreRead);
            registerFunction(l, "sha256", modstoreSha256);
            registerFunction(l, "base64Encode", modstoreBase64);
            return true;
        }
        if (name == "openmw.markup")
        {
            lua_createtable(l, 0, 2);
            registerFunction(l, "decodeYaml", markupDecode);
            return true;
        }
        return false;
    }

    ///////////////////////////////////////////////////////////////// running Lua files

    bool readFile(const std::filesystem::path& path, std::string& out)
    {
        std::ifstream in(path, std::ios::binary);
        if (!in)
            return false;
        std::ostringstream ss;
        ss << in.rdbuf();
        out = ss.str();
        return true;
    }

    // Compiles a file and leaves the resulting function on the stack. Returns an error message, or an empty string.
    std::string loadFile(lua_State* l, const std::filesystem::path& path)
    {
        std::string source;
        if (!readFile(path, source))
            return "cannot read " + path.string();
        const std::string chunk = "=" + path.filename().string();
        const int result = luaL_loadbuffer(l, source.data(), source.size(), chunk.c_str());
        if (result != 0)
        {
            std::string message = lua_tostring(l, -1) ? lua_tostring(l, -1) : "load error";
            lua_pop(l, 1);
            return message;
        }
        return {};
    }

    bool safeModuleName(const std::string& name)
    {
        if (name.empty() || name.size() > 100 || name.find("..") != std::string::npos)
            return false;
        for (const char c : name)
        {
            if (!(std::isalnum(static_cast<unsigned char>(c)) || c == '_' || c == '/'))
                return false;
        }
        return true;
    }

    // require(name): the three openmw.* modules are built in, scripts/mp/* come from the mod directory.
    int luaRequire(lua_State* l)
    {
        const std::string name = luaL_checkstring(l, 1);
        lua_getfield(l, LUA_REGISTRYINDEX, sLoadedKey);
        lua_getfield(l, -1, name.c_str());
        if (!lua_isnil(l, -1))
            return 1;
        lua_pop(l, 1);

        std::string error;
        if (!buildBuiltin(l, name))
        {
            if (name.rfind("scripts/mp/", 0) != 0 || !safeModuleName(name))
                luaL_error(l, "module '%s' is not available in the server", name.c_str());
            error = loadFile(l, gHost->mModDir / (name + ".lua"));
            if (error.empty() && lua_pcall(l, 0, 1, 0) != 0)
            {
                error = lua_tostring(l, -1) ? lua_tostring(l, -1) : "error";
                lua_pop(l, 1);
            }
            if (!error.empty())
                luaL_error(l, "module '%s': %s", name.c_str(), error.c_str());
            if (lua_isnil(l, -1))
            {
                lua_pop(l, 1);
                lua_pushboolean(l, 1);
            }
        }
        lua_pushvalue(l, -1);
        lua_setfield(l, 2, name.c_str()); // loaded[name] = module (the table is at index 2)
        return 1;
    }

    void setField(lua_State* l, const char* key, const std::string& value)
    {
        lua_pushlstring(l, value.data(), value.size());
        lua_setfield(l, -2, key);
    }
    void setField(lua_State* l, const char* key, double value)
    {
        lua_pushnumber(l, value);
        lua_setfield(l, -2, key);
    }

    // print(...): to stdout and to whoever listens (the window's log).
    int luaPrint(lua_State* l)
    {
        const int n = lua_gettop(l);
        std::string line;
        for (int i = 1; i <= n; ++i)
        {
            // (LuaJIT has no luaL_tolstring: call the global tostring)
            std::size_t len = 0;
            lua_getglobal(l, "tostring");
            lua_pushvalue(l, i);
            lua_call(l, 1, 1);
            const char* s = lua_tolstring(l, -1, &len);
            if (!s)
            {
                s = "";
                len = 0;
            }
            if (i > 1)
                line.push_back('\t');
            line.append(s, len);
            lua_pop(l, 1);
        }
        std::printf("%s\n", line.c_str());
        std::fflush(stdout);
        if (gHost && gHost->mSink)
            gHost->mSink(line);
        return 0;
    }

    const char* eventName(Net::SocketEvent::Type type)
    {
        switch (type)
        {
            case Net::SocketEvent::Type::Connected:
                return "connected";
            case Net::SocketEvent::Type::Accepted:
                return "accepted";
            case Net::SocketEvent::Type::Line:
                return "line";
            case Net::SocketEvent::Type::Closed:
                return "closed";
        }
        return "";
    }

    std::string getString(lua_State* l, const char* key)
    {
        lua_getfield(l, -1, key);
        std::string out;
        if (lua_isstring(l, -1))
        {
            std::size_t len = 0;
            const char* s = lua_tolstring(l, -1, &len);
            out.assign(s, len);
        }
        lua_pop(l, 1);
        return out;
    }
    double getNumber(lua_State* l, const char* key, double fallback)
    {
        lua_getfield(l, -1, key);
        const double out = lua_isnumber(l, -1) ? lua_tonumber(l, -1) : fallback;
        lua_pop(l, 1);
        return out;
    }
    bool getBool(lua_State* l, const char* key)
    {
        lua_getfield(l, -1, key);
        const bool out = lua_toboolean(l, -1) != 0;
        lua_pop(l, 1);
        return out;
    }
}

namespace MpServer
{
    struct ServerCore::Impl
    {
        Options mOptions;
        Host mHost;
        std::unique_ptr<lua_State, decltype(&lua_close)> mState{ nullptr, &lua_close };
        std::chrono::steady_clock::time_point mLast = std::chrono::steady_clock::now();
        bool mStop = false;

        // Calls a function of the table the server script returned. The arguments are on the stack; the function is
        // inserted below them. Errors are reported, never fatal.
        bool call(const char* name, int nargs, int nresults)
        {
            lua_State* l = mState.get();
            lua_getfield(l, LUA_REGISTRYINDEX, "mp_script");
            lua_getfield(l, -1, name);
            lua_remove(l, -2);
            lua_insert(l, -(nargs + 1));
            if (lua_pcall(l, nargs, nresults, 0) != 0)
            {
                std::fprintf(
                    stderr, "[server] error in %s: %s\n", name, lua_tostring(l, -1) ? lua_tostring(l, -1) : "?");
                lua_pop(l, 1);
                return false;
            }
            return true;
        }
    };

    ServerCore::ServerCore(const Options& options)
        : mImpl(std::make_unique<Impl>())
    {
        mImpl->mOptions = options;
        mImpl->mHost.mModDir = options.mMod;
        Misc::ClientMods::setRoot(options.mClientMods);
        Misc::ClientMods::setExtraMod("multiplayer", options.mMod,
            "mp.omwscripts"); // the mod's own client scripts, served from where the relay reads them
        gHost = &mImpl->mHost;
    }

    ServerCore::~ServerCore()
    {
        shutdown();
        if (gHost == &mImpl->mHost)
            gHost = nullptr;
    }

    const Options& ServerCore::options() const
    {
        return mImpl->mOptions;
    }

    void ServerCore::setLogSink(std::function<void(const std::string&)> sink)
    {
        mImpl->mHost.mSink = std::move(sink);
    }

    std::string ServerCore::start()
    {
        const Options& o = mImpl->mOptions;
        mImpl->mState.reset(luaL_newstate());
        lua_State* l = mImpl->mState.get();
        luaL_openlibs(l);
        // (LuaJIT only has the global unpack; the game's sandbox adds table.unpack, and the mod's scripts use it)
        luaL_dostring(l, "if not table.unpack then table.unpack = unpack end");

        lua_newtable(l);
        lua_setfield(l, LUA_REGISTRYINDEX, sLoadedKey);
        lua_pushcfunction(l, luaRequire);
        lua_setglobal(l, "require");
        lua_pushcfunction(l, luaPrint);
        lua_setglobal(l, "print");

        lua_createtable(l, 0, 12);
        setField(l, "port", o.mPort);
        setField(l, "password", o.mPassword);
        setField(l, "adminPassword", o.mAdminPassword);
        setField(l, "maxPlayers", o.mMaxPlayers);
        setField(l, "motd", o.mMotd);
        setField(l, "weather", o.mWeather);
        setField(l, "weatherMinutes", o.mWeatherMinutes);
        setField(l, "timeScale", o.mTimeScale);
        setField(l, "startHour", o.mStartHour);
        lua_setglobal(l, "OPTIONS");

        std::string error = loadFile(l, o.mScript);
        if (error.empty() && lua_pcall(l, 0, 1, 0) != 0)
        {
            error = lua_tostring(l, -1) ? lua_tostring(l, -1) : "error";
            lua_pop(l, 1);
        }
        if (!error.empty())
            return error;
        if (!lua_istable(l, -1))
            return o.mScript.string() + " must return a table with onEvent, update, console and snapshot";
        lua_setfield(l, LUA_REGISTRYINDEX, "mp_script");
        mImpl->mLast = std::chrono::steady_clock::now();
        return {};
    }

    bool ServerCore::tick()
    {
        lua_State* l = mImpl->mState.get();
        if (!l || mImpl->mStop)
            return false;
        for (const Net::SocketEvent& e : mImpl->mHost.mSockets.poll())
        {
            lua_pushstring(l, eventName(e.mType));
            lua_pushnumber(l, e.mId);
            lua_pushlstring(l, e.mData.data(), e.mData.size());
            lua_pushnumber(l, e.mListener);
            mImpl->call("onEvent", 4, 0);
        }
        const auto now = std::chrono::steady_clock::now();
        const double dt = std::chrono::duration<double>(now - mImpl->mLast).count();
        mImpl->mLast = now;
        lua_pushnumber(l, dt);
        mImpl->call("update", 1, 0);
        return !mImpl->mStop;
    }

    void ServerCore::waitForEvents(int milliseconds)
    {
        mImpl->mHost.mSockets.waitForEvents(std::chrono::milliseconds(milliseconds));
    }

    std::string ServerCore::console(const std::string& line)
    {
        lua_State* l = mImpl->mState.get();
        if (!l)
            return {};
        const std::size_t first = line.find_first_not_of(" \t");
        const std::string trimmed = first == std::string::npos ? std::string() : line.substr(first);
        if (trimmed == "quit" || trimmed == "exit" || trimmed == "stop")
        {
            mImpl->mStop = true;
            return {};
        }
        lua_pushlstring(l, line.data(), line.size());
        std::string answer;
        if (mImpl->call("console", 1, 1))
        {
            if (lua_isstring(l, -1))
                answer = lua_tostring(l, -1);
            lua_pop(l, 1);
        }
        return answer;
    }

    Snapshot ServerCore::snapshot()
    {
        Snapshot out;
        lua_State* l = mImpl->mState.get();
        if (!l)
            return out;
        if (!mImpl->call("snapshot", 0, 1))
            return out;
        if (lua_istable(l, -1))
        {
            out.mValid = true;
            out.mMaxPlayers = static_cast<int>(getNumber(l, "maxPlayers", 0));
            out.mPasswordProtected = getBool(l, "passworded");
            out.mHour = getNumber(l, "hour", 0);
            out.mWeather = getString(l, "weather");
            out.mWeatherFixed = getBool(l, "weatherFixed");
            lua_getfield(l, -1, "players");
            if (lua_istable(l, -1))
            {
                for (int i = 1;; ++i)
                {
                    lua_rawgeti(l, -1, i);
                    if (!lua_istable(l, -1))
                    {
                        lua_pop(l, 1);
                        break;
                    }
                    PlayerInfo p;
                    p.mId = static_cast<std::uint32_t>(getNumber(l, "id", 0));
                    p.mName = getString(l, "name");
                    p.mAddress = getString(l, "ip");
                    p.mLocation = getString(l, "location");
                    p.mAdmin = getBool(l, "admin");
                    p.mMuted = getBool(l, "muted");
                    p.mAway = getBool(l, "away");
                    p.mHost = getBool(l, "host");
                    p.mBot = getBool(l, "bot");
                    p.mPing = static_cast<int>(getNumber(l, "ping", -1));
                    out.mPlayers.push_back(std::move(p));
                    lua_pop(l, 1);
                }
            }
            lua_pop(l, 1);
            lua_getfield(l, -1, "bans");
            if (lua_istable(l, -1))
            {
                for (int i = 1;; ++i)
                {
                    lua_rawgeti(l, -1, i);
                    if (!lua_isstring(l, -1))
                    {
                        lua_pop(l, 1);
                        break;
                    }
                    out.mBans.emplace_back(lua_tostring(l, -1));
                    lua_pop(l, 1);
                }
            }
            lua_pop(l, 1);
        }
        lua_pop(l, 1);
        return out;
    }

    void ServerCore::requestStop()
    {
        mImpl->mStop = true;
    }

    void ServerCore::shutdown()
    {
        if (mImpl->mState)
        {
            mImpl->mHost.mSockets.closeAll();
            mImpl->mState.reset();
        }
    }
}
