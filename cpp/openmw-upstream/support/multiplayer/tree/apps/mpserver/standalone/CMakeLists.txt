# The multiplayer server on its own, for CI and for people who only want a server: no OpenMW dependencies (no OSG, SDL, Qt
# ...), just a C++20 compiler, CMake and LuaJIT. Nothing here needs the rest of the tree to configure.
#
#   cmake -S apps/mpserver/standalone -B build-server -DLUAJIT_PREFIX=<where LuaJIT was installed>
#   cmake --build build-server --target mpserver-bundle      # -> build-server/openmw-mp-server-<system>-<cpu>.zip
#
# LuaJIT is linked statically, so the server is one program that runs on any machine of the same kind (build LuaJIT with
# `make BUILDMODE=static` or `msvcbuild.bat static`, see CI/mp_server/).
cmake_minimum_required(VERSION 3.16)
project(openmw-mp-server LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
if (NOT CMAKE_BUILD_TYPE AND NOT CMAKE_CONFIGURATION_TYPES)
    set(CMAKE_BUILD_TYPE Release)
endif ()

get_filename_component(MPSERVER_REPO_ROOT "${CMAKE_CURRENT_LIST_DIR}/../../.." ABSOLUTE)

set(LUAJIT_PREFIX "" CACHE PATH "Where LuaJIT is installed (include/luajit-2.1 or include, and lib)")
find_path(LUA_INCLUDE_DIR NAMES lua.hpp
    HINTS "${LUAJIT_PREFIX}" PATH_SUFFIXES include/luajit-2.1 include)
find_library(LUA_LIBRARIES NAMES luajit-5.1 lua51 libluajit-5.1
    HINTS "${LUAJIT_PREFIX}" PATH_SUFFIXES lib)
if (NOT LUA_INCLUDE_DIR OR NOT LUA_LIBRARIES)
    message(FATAL_ERROR "LuaJIT not found: pass -DLUAJIT_PREFIX=<install folder> (found: '${LUA_INCLUDE_DIR}' '${LUA_LIBRARIES}')")
endif ()
message(STATUS "LuaJIT: ${LUA_INCLUDE_DIR} ${LUA_LIBRARIES}")
if (LUA_LIBRARIES MATCHES "\\.a$" AND UNIX)
    list(APPEND LUA_LIBRARIES m)
endif ()

# The program has no window here (that needs Qt)
set(BUILD_MP_SERVER_GUI OFF CACHE BOOL "" FORCE)

# Asio, downloaded at configure time (or the installed one with -DOPENMW_USE_SYSTEM_ASIO=ON)
include("${MPSERVER_REPO_ROOT}/cmake/Asio.cmake")

# The multiplayer folder (mod, server.lua, README) is read from the source tree
set(OPENMW_MULTIPLAYER_STAGED "")
add_subdirectory("${MPSERVER_REPO_ROOT}/apps/mpserver" mpserver)
