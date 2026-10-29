# The target client-package: zips of the OpenMW programs of this build, with the multiplayer mod built in, to share.
#   cmake --build . --target client-package
# Makes <build>/openmw-multiplayer-client-<system>-<cpu>-portable.zip (the libraries are included) and ...-light.zip (they are
# not). Whatever programs were built are in it (openmw is required). The work is done by multiplayer/package-client.py, which
# can also be run by hand:  python3 multiplayer/package-client.py --build-dir <build>. On Windows it is package-client-windows.py (makes
# openmw-multiplayer-client-Windows-<cpu>.zip).
if (NOT (WIN32 OR (UNIX AND NOT APPLE)))
    return()
endif ()

find_package(Python3 QUIET COMPONENTS Interpreter)
if (Python3_Interpreter_FOUND)
    set(MP_PACKAGE_PYTHON "${Python3_EXECUTABLE}")
else ()
    find_program(MP_PACKAGE_PYTHON NAMES python3 python)
endif ()
if (NOT MP_PACKAGE_PYTHON)
    message(STATUS "client-package: no Python found, the target is not made (run multiplayer/package-client.py by hand)")
    return()
endif ()

if (WIN32)
    set(MP_PACKAGE_SCRIPT package-client-windows.py)
else ()
    set(MP_PACKAGE_SCRIPT package-client.py)
endif ()
set(MP_PACKAGE_DEPENDS)
foreach (program openmw openmw-launcher openmw-wizard openmw-cs bsatool esmtool niftest openmw-iniimporter openmw-essimporter
        openmw-navmeshtool openmw-bulletobjecttool qm-files)
    if (TARGET ${program})
        list(APPEND MP_PACKAGE_DEPENDS ${program})
    endif ()
endforeach ()
set(MP_PACKAGE_STRIP)
if (CMAKE_STRIP AND NOT WIN32)
    set(MP_PACKAGE_STRIP --strip "${CMAKE_STRIP}")
endif ()

add_custom_target(client-package
    COMMAND "${MP_PACKAGE_PYTHON}" "${CMAKE_CURRENT_LIST_DIR}/${MP_PACKAGE_SCRIPT}"
        --build-dir "${CMAKE_BINARY_DIR}" --source-dir "${CMAKE_SOURCE_DIR}" ${MP_PACKAGE_STRIP}
    DEPENDS ${MP_PACKAGE_DEPENDS}
    COMMENT "Packaging the OpenMW programs for sharing"
    VERBATIM)
