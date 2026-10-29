# Multiplayer for the stock OpenMW engine

Built in when `ALPHA3_MULTIPLAYER` is on (the default in `cpp/openmw-upstream/CMakeLists.txt`; `-DALPHA3_MULTIPLAYER=OFF`
builds plain OpenMW). This is the LuaJIT/sol2 port of the fork's multiplayer (`cpp/openmw/patches/multiplayer`, Luau). The Lua
mod is the same in both; the C++ differs (standalone Asio instead of Boost.Asio, sol2 bindings instead of Luau's).

* `tree/` holds every file multiplayer adds to OpenMW, at its path in the OpenMW tree. The patch step copies it into the
  OpenMW sources before the patches are applied.
  * `components/net` (sockets), `components/misc` (client mods, sha256), `apps/openmw/mwlua` (`openmw.net`,
    `openmw.modstore`), `apps/openmw/mwgui/playermarker.hpp`
  * `multiplayer/mod`: the Lua mod, installed into `resources/vfs` with its own built-in script list `mp.omwscripts`
  * `apps/mpserver`: the standalone server, `multiplayer/server`, `multiplayer/test`, `multiplayer/debug`, desktop
    packaging scripts
  * `cmake/Asio.cmake`, `files/lua_api/openmw/{net,modstore}.lua`, `docs/.../openmw_net.rst`, component tests
* `CMakeLists.txt` registers the sources with the engine's targets, links Asio and installs the mod. It replaces the
  edits that the multiplayer patch used to make to OpenMW's CMake files.
* `../../patches/multiplayer/*.patch` holds only the edits to OpenMW's own files: startup (client mods, built-in script
  lists), the main menu button, map player markers, Lua handlers and bindings registration, head pitch, text edit.
* `docs.patch` holds the edits to OpenMW's Lua documentation (`docs/`, `files/lua_api`). The Android build does not
  apply it.

Changing a file in `tree/` re-runs the patch step on the next build.

The server builds from `tree/` alone (Asio, LuaJIT and optionally Qt):

    cmake -S tree/apps/mpserver/standalone -B build-server -DLUAJIT_PREFIX=<LuaJIT install folder>
    cmake --build build-server --target mpserver-bundle
