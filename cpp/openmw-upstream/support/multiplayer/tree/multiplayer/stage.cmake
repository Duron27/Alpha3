# Copies the multiplayer folder (the mod, server/server.lua, the README) into the build folder, once:
#   <build>/multiplayer/mod, <build>/multiplayer/server, <build>/multiplayer/README.md
# Everything that runs from the build folder uses that copy and not the source tree: openmw-mp-server finds it next to
# itself, the engine's built-in multiplayer scripts are copied from it (files/data), and the server bundle zip is made from
# it. Edit the files here as usual; CMake copies them again when it reruns (it reruns by itself when a file changes).
# (This is included by the main CMakeLists.txt, not a subdirectory, so only the real files land in <build>/multiplayer.)
if (NOT DEFINED OPENMW_RESOURCES_ROOT)
    return()
endif ()

file(GLOB_RECURSE MULTIPLAYER_STAGE_FILES CONFIGURE_DEPENDS RELATIVE "${CMAKE_CURRENT_LIST_DIR}"
    "${CMAKE_CURRENT_LIST_DIR}/mod/*" "${CMAKE_CURRENT_LIST_DIR}/server/*")
list(APPEND MULTIPLAYER_STAGE_FILES README.md)
foreach (f ${MULTIPLAYER_STAGE_FILES})
    copy_resource_file("${CMAKE_CURRENT_LIST_DIR}/${f}" "${OPENMW_RESOURCES_ROOT}" "multiplayer/${f}")
endforeach (f)

# With a multi-config generator the copy lives in one folder per configuration: users of the path below then fall back to
# the source tree.)
get_generator_is_multi_config(multi_config)
if (multi_config)
    set(OPENMW_MULTIPLAYER_STAGED "" CACHE INTERNAL "")
else ()
    set(OPENMW_MULTIPLAYER_STAGED "${OPENMW_RESOURCES_ROOT}/multiplayer" CACHE INTERNAL "the multiplayer folder in the build folder")
endif ()
