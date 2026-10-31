#include "modstorebindings.hpp"

#include <optional>
#include <string>

#include <components/lua/luastate.hpp>
#include <components/misc/clientmods.hpp>
#include <components/misc/sha256.hpp>

#include "../mwbase/environment.hpp"
#include "../mwbase/luamanager.hpp"
#include "../mwbase/statemanager.hpp"

#include "context.hpp"

namespace MWLua
{
    sol::table initModstorePackage(const Context& context)
    {
        sol::state_view lua = context.sol();
        sol::table api(lua, sol::create);

        // The installed mods and their files, with SHA-256 hashes, as JSON text
        api["manifest"] = []() { return Misc::ClientMods::manifestJson(); };
        // One installed file, or nil
        api["read"] = [](std::string_view id, std::string_view path) -> sol::optional<std::string> {
            if (auto data = Misc::ClientMods::read(id, path))
                return std::move(*data);
            return sol::nullopt;
        };
        // Stores a file; fails (returns the reason) unless the name, size and SHA-256 are right. "" when it worked.
        api["write"] = [](std::string_view id, std::string_view path, std::string_view data, std::string_view hash) {
            return Misc::ClientMods::write(id, path, data, hash);
        };
        // Deletes a file of a mod, or the whole mod when the path is empty. "" when it worked.
        api["remove"] = [](std::string_view id, std::string_view path) { return Misc::ClientMods::remove(id, path); };
        api["sha256"] = [](std::string_view data) { return Misc::sha256Hex(data); };
        api["base64Encode"] = [](std::string_view data) { return Misc::ClientMods::base64Encode(data); };
        api["base64Decode"] = [](std::string_view text) -> sol::optional<std::string> {
            if (auto data = Misc::ClientMods::base64Decode(text))
                return std::move(*data);
            return sol::nullopt;
        };
        // Whether the game loaded this file of this mod at startup: changes to such files need only a Lua reload
        api["wasLoaded"]
            = [](std::string_view id, std::string_view path) { return Misc::ClientMods::wasLoaded(id, path); };
        // Reloads every Lua script (at the end of the frame), reading the installed mods' files again. Scripts keep
        // their saved data, as when a game is saved and loaded.
        api["reloadScripts"] = []() { MWBase::Environment::get().getLuaManager()->reloadAllScripts(); };
        // Quits and starts the game again, so the installed mods are loaded. Returns false (and does nothing) where the
        // game cannot start itself again.
        api["restart"] = []() {
            if (!Misc::ClientMods::canRelaunch())
                return false;
            Misc::ClientMods::requestRestart();
            MWBase::Environment::get().getStateManager()->requestQuit();
            return true;
        };

        return LuaUtil::makeReadOnly(api);
    }
}
