# Multiplayer for the Android build of stock OpenMW, chosen at build time with ALPHA3_MULTIPLAYER (on by default in
# cpp/openmw-upstream/CMakeLists.txt). Added by ../CMakeLists.txt.
#
# tree/ holds every file multiplayer adds to OpenMW, at its place in the OpenMW tree: the sockets (components/net), client
# mods and sha256 (components/misc), the openmw.net and openmw.modstore bindings (apps/openmw/mwlua), the map's player
# marker, the Lua mod (multiplayer/mod), the standalone server (apps/mpserver), tests and Lua API files. The patch step of
# cpp/openmw-upstream/CMakeLists.txt copies tree/ into the OpenMW sources before it applies
# patches/multiplayer/*.patch, which holds only the edits to OpenMW's own files. This file does what the edits to OpenMW's
# CMakeLists.txt files used to do: it adds the new sources to the engine's targets and installs the mod.
#
# docs.patch has the edits to OpenMW's Lua documentation (docs/, files/lua_api); the Android build does not apply it.
#
# The server needs nothing else from OpenMW and builds from tree/ itself:
#   cmake -S tree/apps/mpserver/standalone -B build-server -DLUAJIT_PREFIX=<LuaJIT install folder>

set(ALPHA3_MP_TREE ${CMAKE_CURRENT_SOURCE_DIR}/tree)

# Standalone Asio (header-only) as the interface target openmw-asio; the outer build hands it the unpacked release through
# FETCHCONTENT_SOURCE_DIR_ASIO, so no download happens here
include(${ALPHA3_MP_TREE}/cmake/Asio.cmake)

# The sources are compiled from their copies in the OpenMW tree, so the engine's own includes of them ("netbindings.hpp",
# <components/net/sockets.hpp>, ...) find them where they always were
target_sources(components PRIVATE
    ${OpenMW_SOURCE_DIR}/components/misc/clientmods.cpp
    ${OpenMW_SOURCE_DIR}/components/misc/sha256.cpp
    ${OpenMW_SOURCE_DIR}/components/net/sockets.cpp
)
target_link_libraries(components openmw-asio)

target_sources(openmw-lib PRIVATE
    ${OpenMW_SOURCE_DIR}/apps/openmw/mwlua/modstorebindings.cpp
    ${OpenMW_SOURCE_DIR}/apps/openmw/mwlua/netbindings.cpp
)

# The Lua mod ships with the engine's resources: its script list is a built-in list of its own (resources/vfs/mp.omwscripts,
# loaded after builtin.omwscripts by the patched main.cpp), and does nothing until a game is started from the Multiplayer
# screen. A client mod a server hands out replaces it by name.
set(ALPHA3_MP_MOD ${ALPHA3_MP_TREE}/multiplayer/mod)
file(GLOB ALPHA3_MP_MOD_FILES CONFIGURE_DEPENDS RELATIVE ${ALPHA3_MP_MOD}
    ${ALPHA3_MP_MOD}/mp.omwscripts
    ${ALPHA3_MP_MOD}/scripts/mp/*.lua
    ${ALPHA3_MP_MOD}/l10n/*/*.yaml
    ${ALPHA3_MP_MOD}/textures/*
)
foreach (f ${ALPHA3_MP_MOD_FILES})
    copy_resource_file(${ALPHA3_MP_MOD}/${f} ${OPENMW_RESOURCES_ROOT} resources/vfs/${f})
endforeach ()
list(LENGTH ALPHA3_MP_MOD_FILES ALPHA3_MP_MOD_COUNT)
message(STATUS "Multiplayer: on (${ALPHA3_MP_MOD_COUNT} mod files in resources/vfs)")
