# Makes <OUT_DIR>/<NAME>.zip: the server program, the multiplayer folder and start scripts, ready to unpack anywhere.
#   cmake -DSERVER=<program> -DMULTIPLAYER_DIR=<folder with mod, server, README.md> -DFILES_DIR=<apps/mpserver>
#         -DOUT_DIR=<folder> -DNAME=<name> [-DSTRIP=<strip tool>] -P bundle.cmake
foreach (required SERVER MULTIPLAYER_DIR FILES_DIR OUT_DIR NAME)
    if (NOT DEFINED ${required})
        message(FATAL_ERROR "bundle.cmake: ${required} is not set")
    endif ()
endforeach ()

set(stage "${OUT_DIR}/${NAME}-staging")
set(dir "${stage}/openmw-mp-server")
file(REMOVE_RECURSE "${stage}")
file(MAKE_DIRECTORY "${dir}/multiplayer")

get_filename_component(exe "${SERVER}" NAME)
file(COPY "${SERVER}" DESTINATION "${dir}" FILE_PERMISSIONS OWNER_READ OWNER_WRITE OWNER_EXECUTE GROUP_READ GROUP_EXECUTE
    WORLD_READ WORLD_EXECUTE)
if (STRIP AND NOT STRIP MATCHES "NOTFOUND" AND NOT WIN32)
    execute_process(COMMAND "${STRIP}" "${dir}/${exe}" RESULT_VARIABLE strip_result ERROR_QUIET)
endif ()

if (GUI_MODULE AND EXISTS "${GUI_MODULE}")
    file(COPY "${GUI_MODULE}" DESTINATION "${dir}")
    get_filename_component(guiname "${GUI_MODULE}" NAME)
    if (STRIP AND NOT STRIP MATCHES "NOTFOUND" AND NOT WIN32)
        execute_process(COMMAND "${STRIP}" "${dir}/${guiname}" RESULT_VARIABLE strip_result ERROR_QUIET)
    endif ()
endif ()

foreach (part mod server README.md)
    if (NOT EXISTS "${MULTIPLAYER_DIR}/${part}")
        message(FATAL_ERROR "bundle.cmake: ${MULTIPLAYER_DIR}/${part} is missing")
    endif ()
    file(COPY "${MULTIPLAYER_DIR}/${part}" DESTINATION "${dir}/multiplayer")
endforeach ()

# The client mods this server hands out: the multiplayer mod's own client scripts are served straight from the mod folder
# (so they are stored once), and any mods kept in multiplayer/clientmods are added.
if (EXISTS "${MULTIPLAYER_DIR}/clientmods")
    file(COPY "${MULTIPLAYER_DIR}/clientmods" DESTINATION "${dir}/multiplayer")
endif ()

file(COPY "${FILES_DIR}/start-server.sh" DESTINATION "${dir}" FILE_PERMISSIONS OWNER_READ OWNER_WRITE OWNER_EXECUTE
    GROUP_READ GROUP_EXECUTE WORLD_READ WORLD_EXECUTE)
# (a batch file wants Windows line endings, whatever git did to the one in the source tree)
file(READ "${FILES_DIR}/start-server.bat" bat)
string(REGEX REPLACE "\r?\n" "\r\n" bat "${bat}")
file(WRITE "${dir}/start-server.bat" "${bat}")
file(COPY "${FILES_DIR}/BUNDLE-README.txt" DESTINATION "${dir}")
file(RENAME "${dir}/BUNDLE-README.txt" "${dir}/README.txt")

file(REMOVE "${OUT_DIR}/${NAME}.zip")
execute_process(COMMAND "${CMAKE_COMMAND}" -E tar cf "${OUT_DIR}/${NAME}.zip" --format=zip openmw-mp-server
    WORKING_DIRECTORY "${stage}" RESULT_VARIABLE zip_result)
if (NOT zip_result EQUAL 0)
    message(FATAL_ERROR "bundle.cmake: could not make the zip")
endif ()
file(REMOVE_RECURSE "${stage}")
file(SIZE "${OUT_DIR}/${NAME}.zip" size)
math(EXPR kilobytes "${size} / 1024")
message(STATUS "${OUT_DIR}/${NAME}.zip (${kilobytes} KB)")
