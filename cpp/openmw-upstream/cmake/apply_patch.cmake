# Applies one patch of the series to a source tree, unless it is already in. Used as
#   cmake -DSRC=<tree> -DPATCH=<file> -P apply_patch.cmake
# (a local OPENMW_UPSTREAM_SRC checkout is patched in place, so a rebuild meets patches that are already applied)
find_program(GIT git REQUIRED)

execute_process(COMMAND ${GIT} -C ${SRC} apply --reverse --check --whitespace=nowarn ${PATCH}
    RESULT_VARIABLE already OUTPUT_QUIET ERROR_QUIET)
if(already EQUAL 0)
    message(STATUS "already applied: ${PATCH}")
    return()
endif()

execute_process(COMMAND ${GIT} -C ${SRC} apply --whitespace=nowarn ${PATCH}
    RESULT_VARIABLE rc ERROR_VARIABLE err)
if(NOT rc EQUAL 0)
    message(FATAL_ERROR "Could not apply ${PATCH} to ${SRC}:\n${err}")
endif()
message(STATUS "applied: ${PATCH}")
