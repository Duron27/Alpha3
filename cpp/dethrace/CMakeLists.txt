cmake_minimum_required(VERSION 3.22.1)

project(dethrace-android)

include(ExternalProject)

# dethrace comes from dethrace-labs/dethrace at DETHRACE_COMMIT, with our Android changes as a patch. The patch is applied
# in place once, so a changed patch needs a fresh source and build directory: name them after its hash.
# patches/README.md says how the patch is made again for a newer upstream commit.
set(DETHRACE_COMMIT 166dd93cf3dd985d027095c29d236e38bd9ae5b8)
set(DETHRACE_PATCH "${CMAKE_CURRENT_SOURCE_DIR}/patches/duron27-android.patch")
file(SHA256 "${DETHRACE_PATCH}" DETHRACE_PATCH_HASH)
string(SHA256 DETHRACE_PATCH_ID "${DETHRACE_COMMIT}${DETHRACE_PATCH_HASH}")
string(SUBSTRING "${DETHRACE_PATCH_ID}" 0 8 DETHRACE_PATCH_ID)
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${DETHRACE_PATCH}")

ExternalProject_Add(dethrace
    PREFIX dethrace-prefix-${DETHRACE_PATCH_ID}
    DEPENDS vcpkg

    GIT_REPOSITORY https://github.com/dethrace-labs/dethrace.git
    GIT_TAG ${DETHRACE_COMMIT}
    GIT_SUBMODULES_RECURSE ON
    DOWNLOAD_DIR ${download_dir}

    UPDATE_COMMAND ""
    PATCH_COMMAND patch -d <SOURCE_DIR> -p1 -t -N < ${DETHRACE_PATCH}

    CONFIGURE_COMMAND ${wrapper_command} cmake <SOURCE_DIR>
    ${VCPKG_CMAKE_CONFIGURE_OPTIONS}
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5
    -DDETHRACE_PLATFORM_SDL2=ON
    -DSDL2_INCLUDE_DIR=${prefix}/include/SDL2
    -DSDL2_LIBRARY=${prefix}/lib/libSDL2.so
    -DSDL2_SDLMAIN_LIBRARY=${prefix}/lib/libSDL2main.a

    BUILD_COMMAND ${wrapper_command} make -C <BINARY_DIR> -j${NPROC}
    INSTALL_COMMAND ${CMAKE_COMMAND} -E copy <BINARY_DIR>/src/DETHRACE/libdethrace.so ${jni_libs_dir}/
    USES_TERMINAL_BUILD ON
    USES_TERMINAL_INSTALL ON
)
