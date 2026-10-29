# A standalone multiplayer server: the Lua relay of the multiplayer mod on the engine's own sockets, without a game.
# By default it has a window (Qt); --cli-only runs it in the terminal. Without Qt (or with BUILD_MP_SERVER_GUI=OFF) it is
# terminal-only, which is also what a headless machine needs.
find_package(Threads REQUIRED)

# The root of the source tree. Set by the server-only project (apps/mpserver/standalone), which builds this server and its
# bundle without the rest of OpenMW; inside the main project it is the main project's source folder.
if (NOT MPSERVER_REPO_ROOT)
    set(MPSERVER_REPO_ROOT "${CMAKE_SOURCE_DIR}")
endif ()

option(BUILD_MP_SERVER_GUI "Give openmw-mp-server a window (needs Qt Widgets)" ON)

# The server itself never links Qt: the window is a separate module (openmw-mp-server-gui) that the program loads only
# when it opens one, so --cli-only and headless machines run without any Qt libraries installed.
add_library(mpserver-core STATIC
    servercore.cpp
    ${MPSERVER_REPO_ROOT}/components/net/sockets.cpp
    ${MPSERVER_REPO_ROOT}/components/misc/clientmods.cpp
    ${MPSERVER_REPO_ROOT}/components/misc/sha256.cpp
)
target_include_directories(mpserver-core PRIVATE ${MPSERVER_REPO_ROOT} ${LUA_INCLUDE_DIR})
target_link_libraries(mpserver-core PUBLIC openmw-asio ${LUA_LIBRARIES} Threads::Threads)
if (WIN32)
    target_link_libraries(mpserver-core PUBLIC ws2_32 mswsock)
endif()

set(MPSERVER_QT_FOUND OFF)
if (BUILD_MP_SERVER_GUI)
    find_package(Qt6 QUIET COMPONENTS Widgets)
    if (Qt6_FOUND)
        set(MPSERVER_QT_FOUND ON)
    else()
        message(STATUS "openmw-mp-server: Qt6 Widgets not found, it will have no window")
    endif()
endif()

add_executable(openmw-mp-server main.cpp)
target_include_directories(openmw-mp-server PRIVATE ${MPSERVER_REPO_ROOT})
target_link_libraries(openmw-mp-server mpserver-core ${CMAKE_DL_LIBS})

if (MPSERVER_QT_FOUND)
    add_library(openmw-mp-server-gui MODULE gui.cpp)
    set_target_properties(openmw-mp-server-gui PROPERTIES AUTOMOC OFF) # (no signals or slots are declared)
    target_include_directories(openmw-mp-server-gui PRIVATE ${MPSERVER_REPO_ROOT})
    # (the module uses the server's classes from the program that loads it, so the program exports its symbols)
    set_target_properties(openmw-mp-server PROPERTIES ENABLE_EXPORTS ON)
    target_link_libraries(openmw-mp-server-gui Qt6::Widgets)
    if (WIN32)
        target_link_libraries(openmw-mp-server-gui openmw-mp-server)
    endif()
    add_dependencies(openmw-mp-server openmw-mp-server-gui)
    target_compile_definitions(openmw-mp-server PRIVATE MPSERVER_WITH_GUI)
    install(TARGETS openmw-mp-server-gui DESTINATION ${CMAKE_INSTALL_LIBDIR}/openmw-mp)
endif()

install(TARGETS openmw-mp-server)

# mpserver-bundle: openmw-mp-server-<system>-<cpu>.zip with the program, the multiplayer folder (mod, server.lua, README) and
# start scripts, ready to unpack anywhere:  cmake --build . --target mpserver-bundle
# The multiplayer folder comes from the copy in the build folder (multiplayer/stage.cmake).
if (OPENMW_MULTIPLAYER_STAGED AND EXISTS "${OPENMW_MULTIPLAYER_STAGED}/mod/mp.omwscripts")
    set(MPSERVER_BUNDLE_FOLDER "${OPENMW_MULTIPLAYER_STAGED}")
else ()
    set(MPSERVER_BUNDLE_FOLDER "${MPSERVER_REPO_ROOT}/multiplayer")
endif ()
set(MPSERVER_BUNDLE_NAME "openmw-mp-server-${CMAKE_SYSTEM_NAME}-${CMAKE_SYSTEM_PROCESSOR}")
add_custom_target(mpserver-bundle
    COMMAND ${CMAKE_COMMAND}
        "-DSERVER=$<TARGET_FILE:openmw-mp-server>" "-DGUI_MODULE=$<$<BOOL:${MPSERVER_QT_FOUND}>:$<TARGET_FILE:openmw-mp-server-gui>>" "-DMULTIPLAYER_DIR=${MPSERVER_BUNDLE_FOLDER}"
        "-DFILES_DIR=${CMAKE_CURRENT_SOURCE_DIR}" "-DOUT_DIR=${CMAKE_BINARY_DIR}" "-DNAME=${MPSERVER_BUNDLE_NAME}"
        "-DSTRIP=${CMAKE_STRIP}" -P "${CMAKE_CURRENT_SOURCE_DIR}/bundle.cmake"
    DEPENDS openmw-mp-server $<$<BOOL:${MPSERVER_QT_FOUND}>:openmw-mp-server-gui>
    COMMENT "Making ${MPSERVER_BUNDLE_NAME}.zip"
    VERBATIM)
if (MPSERVER_QT_FOUND)
    message(STATUS "openmw-mp-server has a window: its bundle holds the window module, which needs the Qt 6 libraries "
        "(without them the server falls back to the terminal)")
endif ()

# Where the server looks for the mod when it is installed (<prefix>/share/openmw-mp/mod and .../server/server.lua)
install(DIRECTORY ${MPSERVER_REPO_ROOT}/multiplayer/mod DESTINATION share/openmw-mp)
install(FILES ${MPSERVER_REPO_ROOT}/multiplayer/server/server.lua DESTINATION share/openmw-mp/server)
