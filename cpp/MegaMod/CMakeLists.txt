cmake_minimum_required(VERSION 3.22.1)

project(uqm-android)

include(ExternalProject)

find_package(Git REQUIRED)
execute_process(
    COMMAND ${GIT_EXECUTABLE} ls-remote https://github.com/JHGuitarFreak/UQM-MegaMod-Content.git HEAD
    OUTPUT_VARIABLE UQM_CONTENT_COMMIT_OUTPUT
    OUTPUT_STRIP_TRAILING_WHITESPACE
)
string(REGEX MATCH "^[0-9a-fA-F]+" UQM_CONTENT_COMMIT "${UQM_CONTENT_COMMIT_OUTPUT}")
if(UQM_CONTENT_COMMIT)
    message(STATUS "UQM Content build-time commit: ${UQM_CONTENT_COMMIT}")
    file(WRITE "${assets_dir}/uqm_content_commit.txt" "${UQM_CONTENT_COMMIT}")
else()
    message(WARNING "Could not fetch UQM Content commit hash, using master")
    file(WRITE "${assets_dir}/uqm_content_commit.txt" "master")
endif()

# Bundle the core game content (root files + base/, not addons/) into the APK assets as the engine's base content
# package (a .uqm is a plain zip). The app copies it into content/packages once and the engine mounts it from there,
# so the ~12,000 files are never unpacked; only the few root files (version, uqm.rmp, ...) are extracted.
# Sparse partial fetch of the pinned commit, so only ~27MB is transferred, not the whole repo.
set(uqm_content_ref "${UQM_CONTENT_COMMIT}")
if(NOT uqm_content_ref)
    set(uqm_content_ref "master")
endif()
set(uqm_content_zip "${assets_dir}/mm-content.uqm")
set(uqm_content_stamp "${assets_dir}/mm-content.commit")
set(uqm_content_have "")
if(EXISTS "${uqm_content_stamp}")
    file(READ "${uqm_content_stamp}" uqm_content_have)
endif()
if(NOT EXISTS "${uqm_content_zip}" OR NOT "${uqm_content_have}" STREQUAL "${uqm_content_ref}")
    set(uqm_content_src "${download_dir}/uqm-content-src")
    file(REMOVE_RECURSE "${uqm_content_src}")
    file(MAKE_DIRECTORY "${uqm_content_src}")
    set(uqm_git ${GIT_EXECUTABLE} -C "${uqm_content_src}")
    execute_process(COMMAND ${uqm_git} init -q RESULT_VARIABLE r1)
    execute_process(COMMAND ${uqm_git} remote add origin https://github.com/JHGuitarFreak/UQM-MegaMod-Content.git RESULT_VARIABLE r2)
    execute_process(COMMAND ${uqm_git} sparse-checkout set --no-cone "/*" "!/addons/" RESULT_VARIABLE r3)
    execute_process(COMMAND ${uqm_git} fetch -q --depth 1 --filter=blob:none origin ${uqm_content_ref} RESULT_VARIABLE r4)
    execute_process(COMMAND ${uqm_git} checkout -q FETCH_HEAD RESULT_VARIABLE r5)
    if(r1 OR r2 OR r3 OR r4 OR r5)
        message(WARNING "Could not fetch UQM base content; the app will have no bundled content")
    else()
        file(GLOB uqm_content_entries RELATIVE "${uqm_content_src}" "${uqm_content_src}/*")
        list(REMOVE_ITEM uqm_content_entries .git)
        file(REMOVE "${uqm_content_zip}")
        execute_process(
            COMMAND ${CMAKE_COMMAND} -E tar cf "${uqm_content_zip}" --format=zip -- ${uqm_content_entries}
            WORKING_DIRECTORY "${uqm_content_src}"
            RESULT_VARIABLE r6
        )
        if(r6)
            message(WARNING "Could not zip UQM base content")
        else()
            file(WRITE "${uqm_content_stamp}" "${uqm_content_ref}")
        endif()
    endif()
    file(REMOVE_RECURSE "${uqm_content_src}")
endif()

ExternalProject_Add(uqm
    DEPENDS vcpkg

    GIT_REPOSITORY https://github.com/JHGuitarFreak/UQM-MegaMod.git
    GIT_TAG master
    DOWNLOAD_DIR ${download_dir}
#    BUILD_ALWAYS 1

    UPDATE_COMMAND ""

    PATCH_COMMAND ${CMAKE_COMMAND} -E copy ${CMAKE_SOURCE_DIR}/MegaMod/jni_engine.c <SOURCE_DIR>/src/jni_engine.c
    # the engine only mounts the base package under its exact name, which carries the engine version; use a fixed one
    # so the bundled package (mm-content.uqm, see above) is found whatever version master is at
    COMMAND sed -i "s/^#define BASE_CONTENT_NAME .*/#define BASE_CONTENT_NAME \"mm-content.uqm\"/" <SOURCE_DIR>/src/options.h
    COMMAND ${CMAKE_COMMAND} -DFILE=<SOURCE_DIR>/src/options.c -P ${CMAKE_SOURCE_DIR}/MegaMod/patch_content_env.cmake
    COMMAND ${CMAKE_COMMAND} -DFILE=<SOURCE_DIR>/src/uqm/imgui/uqm_imgui.c -P ${CMAKE_SOURCE_DIR}/MegaMod/patch_imgui_mouse.cmake
    # the log file is lost when Android kills the process after a failed start; write it through
    COMMAND sed -i "s/#ifdef UNBUFFERED_LOGFILE/#if 1/" <SOURCE_DIR>/src/uqm.c
    COMMAND sh -c "grep -q 'src/jni_engine.c' <SOURCE_DIR>/CMakeLists.txt || sed -i '/add_library (UrQuanMasters SHARED/i list(APPEND UQM_SOURCES src/jni_engine.c)' <SOURCE_DIR>/CMakeLists.txt"

    CONFIGURE_COMMAND ${wrapper_command} cmake -S <SOURCE_DIR> -B <BINARY_DIR>
    ${VCPKG_CMAKE_CONFIGURE_OPTIONS}

    BUILD_COMMAND ${wrapper_command} make -C <BINARY_DIR> -j${NPROC}
    INSTALL_COMMAND ${CMAKE_COMMAND} -E make_directory ${jni_libs_dir}
    COMMAND ${CMAKE_COMMAND} -E copy <BINARY_DIR>/libUrQuanMasters.so ${jni_libs_dir}/libuqm.so
    USES_TERMINAL_BUILD ON
    USES_TERMINAL_INSTALL ON
)
