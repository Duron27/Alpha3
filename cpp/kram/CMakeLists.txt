cmake_minimum_required(VERSION 3.22.1)

project(kram-android)

include(ExternalProject)

# kram comes from alecazam/kram. The patches are applied in place once, so a changed patch needs a fresh source and build
# directory: name them after the patch files' hash.
#  * duron27-differences.patch is what the Duron27/kram fork has over this upstream commit (newer ASTC encoder and
#    squish, BC2, 10x10/12x12 ASTC, OpenGL pixel flipping and KTX orientation flags, RGB888, Android build fixes).
#    patches/README.md says how it is made again for a newer upstream commit.
#  * kram-shared-library.patch builds kramc as a shared library exporting kram_main (loaded by the app) instead of an
#    executable, and stops kram's CMake from replacing the NDK compiler with the host's clang.
set(KRAM_COMMIT 03b0252747cf0234cde93f638a35892ea1c238fa)
set(KRAM_DIFFERENCES_PATCH "${CMAKE_CURRENT_SOURCE_DIR}/patches/duron27-differences.patch")
set(KRAM_PATCH "${CMAKE_CURRENT_SOURCE_DIR}/patches/kram-shared-library.patch")
file(SHA256 "${KRAM_DIFFERENCES_PATCH}" KRAM_DIFFERENCES_PATCH_HASH)
file(SHA256 "${KRAM_PATCH}" KRAM_PATCH_HASH)
string(SHA256 KRAM_PATCH_ID "${KRAM_COMMIT}${KRAM_DIFFERENCES_PATCH_HASH}${KRAM_PATCH_HASH}")
string(SUBSTRING "${KRAM_PATCH_ID}" 0 8 KRAM_PATCH_ID)
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${KRAM_DIFFERENCES_PATCH}" "${KRAM_PATCH}")

ExternalProject_Add(kram
    PREFIX kram-prefix-${KRAM_PATCH_ID}
    URL https://github.com/alecazam/kram/archive/${KRAM_COMMIT}.tar.gz
    DOWNLOAD_DIR ${download_dir}

    PATCH_COMMAND patch -d <SOURCE_DIR> -p1 -t -N < ${KRAM_DIFFERENCES_PATCH}
    COMMAND patch -d <SOURCE_DIR> -p1 -t -N < ${KRAM_PATCH}

    CONFIGURE_COMMAND ${wrapper_command} cmake <SOURCE_DIR>
    ${VCPKG_CMAKE_CONFIGURE_OPTIONS}
    -DKRAM_BUILD_SHARED=ON

    BUILD_COMMAND ${wrapper_command} make -j${NPROC}

    INSTALL_COMMAND ${CMAKE_COMMAND} -E make_directory ${prefix}/lib/
    COMMAND ${CMAKE_COMMAND} -DLIB=kramc/libkram.so -P ${CMAKE_CURRENT_SOURCE_DIR}/check_arch.cmake
    COMMAND ${CMAKE_COMMAND} -E copy kramc/libkram.so ${jni_libs_dir}/libkram.so
    USES_TERMINAL_BUILD ON
    USES_TERMINAL_INSTALL ON
)
