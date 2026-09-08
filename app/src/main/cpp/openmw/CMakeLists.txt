cmake_minimum_required(VERSION 3.2.0)

# https://github.com/libjpeg-turbo/libjpeg-turbo/releases
set(LIBJPEG_TURBO_VERSION 3.1.0)
set(LIBJPEG_TURBO_HASH SHA256=9564c72b1dfd1d6fe6274c5f95a8d989b59854575d4bbee44ade7bc17aa9bc93)

set(LIBXML2_VERSION 2.14.3)
set(LIBXML2_HASH SHA256=e282bfeb750be5f4baf98195c6393f9b7beadc6bed4d85e7ca37a66f56394b5f)

# http://www.libpng.org/pub/png/libpng.html
set(LIBPNG_VERSION 1.6.48)
set(LIBPNG_HASH SHA256=46fd06ff37db1db64c0dc288d78a3f5efd23ad9ac41561193f983e20937ece03)

# https://www.freetype.org/index.html#news
set(FREETYPE2_VERSION 2.13.3)
set(FREETYPE2_HASH SHA256=5c3a8e78f7b24c20b25b54ee575d6daa40007a5f4eea2845861c3409b3021747)

# https://github.com/kcat/openal-soft/releases
set(OPENAL_VERSION 1.24.3)
set(OPENAL_HASH SHA256=7e1fecdeb45e7f78722b776c5cf30bd33934b961d7fd2a11e0494e064cc631ce)

# http://www.boost.org/users/download/
set(BOOST_VERSION 1.88.0)
set(BOOST_HASH SHA256=dcea50f40ba1ecfc448fdf886c0165cf3e525fef2c9e3e080b9804e8117b9694)

# https://www.ffmpeg.org/download.html
set(FFMPEG_VERSION 7.1.1)
set(FFMPEG_HASH SHA256=0c8da2f11579a01e014fc007cbacf5bb4da1d06afd0b43c7f8097ec7c0f143ba)

# https://www.libsdl.org/download-2.0.php
set(SDL2_VERSION 2.32.4)
set(SDL2_HASH SHA256=349268f695c02efbc9b9148a70b85e58cefbbf704abd3e91be654db7f1e2c863)

# https://github.com/bulletphysics/bullet3/releases
set(BULLET_VERSION 3.25)
set(BULLET_HASH SHA256=baa642c906576d4d98d041d0acb80d85dd6eff6e3c16a009b1abf1ccd2bc0a61)

# https://github.com/MyGUI/mygui/releases
set(MYGUI_VERSION 3.4.3)
set(MYGUI_HASH SHA256=d1d5f294670ae71f7200ed4b30859018281d8cfd45d6a38d18b97a4aba604c42)

# https://github.com/lz4/lz4/releases
set(LZ4_VERSION 1.10.0)
set(LZ4_HASH SHA256=537512904744b35e232912055ccf8ec66d768639ff3abe5788d90d792ec5f48b)

# https://luajit.org/download.html
set(LUAJIT_VERSION 2.1.ROLLING)
set(LUAJIT_HASH SHA256=1ad2e34b111c802f9d0cdf019e986909123237a28c746b21295b63c9e785d9c3)

set(LIBICU_VERSION 70-1)
set(LIBICU_HASH SHA256=4013d7aff8a34bae410664bdb16554d7eda03654b22627b9284ab55a685492d4)

# https://github.com/openscenegraph/OpenSceneGraph/releases
set(OSG_VERSION 638f0a1e73687633fd99bf110d04226e78ff69c6)
set(OSG_HASH SHA256=aea196550f02974d6d09291c5d83b51ca6a03b3767e234a8c0e21322927d1e12)

# https://github.com/sisah2/openmw/commits/clean
set(OPENMW_VERSION 6ca6d0f127682a02294325eadc498b049093f283)
set(OPENMW_HASH SHA256=44d044ad0a5546216fe611fe92c37065bb07e2efeeeaac4f049dae03df313186)

project(openmw-android)

include(ExternalProject)
include(ProcessorCount)
ProcessorCount(NPROC)
if(NPROC EQUAL 0)
    set(NPROC 2)  # Fallback to 2 cores if detection fails
endif()

# Detect ABI and setup architecture-specific variables for the Gradle environment
if(NOT DEFINED ARCH)
    if(ANDROID_ABI STREQUAL "arm64-v8a")
        set(ARCH "arm64")
        set(GN_CPU "arm64")
        set(NDK_TRIPLET "aarch64-linux-android")
        set(FFMPEG_CPU "armv8-a")
    elseif(ANDROID_ABI STREQUAL "armeabi-v7a")
        set(ARCH "arm")
        set(GN_CPU "arm")
        set(NDK_TRIPLET "arm-linux-androideabi")
        set(FFMPEG_CPU "armv7-a")
    elseif(ANDROID_ABI STREQUAL "x86_64")
        set(ARCH "x86_64")
        set(GN_CPU "x64")
        set(NDK_TRIPLET "x86_64-linux-android")
        set(FFMPEG_CPU "x86_64")
    elseif(ANDROID_ABI STREQUAL "x86")
        set(ARCH "x86")
        set(GN_CPU "x86")
        set(NDK_TRIPLET "i686-linux-android")
        set(FFMPEG_CPU "i686")
    endif()
endif()

if(NOT DEFINED ANDROID_API)
    set(ANDROID_API ${ANDROID_NATIVE_API_LEVEL})
endif()

if(NOT DEFINED LUAJIT_HOST_CC)
    set(LUAJIT_HOST_CC "gcc")
endif()

# Find the host tag for NDK prebuilt tools
if(NOT DEFINED ANDROID_HOST_TAG)
    if(CMAKE_HOST_SYSTEM_NAME STREQUAL "Linux")
        set(ANDROID_HOST_TAG "linux-x86_64")
    elseif(CMAKE_HOST_SYSTEM_NAME STREQUAL "Darwin")
        set(ANDROID_HOST_TAG "darwin-x86_64")
    elseif(CMAKE_HOST_SYSTEM_NAME STREQUAL "Windows")
        set(ANDROID_HOST_TAG "windows-x86_64")
    endif()
endif()

if(CMAKE_HOST_WIN32)
    set(BOOTSTRAP_SCRIPT "bootstrap-vcpkg.bat")
    set(VCPKG_EXE "vcpkg.exe")
else()
    set(BOOTSTRAP_SCRIPT "./bootstrap-vcpkg.sh")
    set(VCPKG_EXE "./vcpkg")
endif()

set(NDK_TOOLCHAIN_BIN "${CMAKE_ANDROID_NDK}/toolchains/llvm/prebuilt/${ANDROID_HOST_TAG}/bin")

find_program(CCACHE_FOUND ccache)
if(CCACHE_FOUND)
    message (STATUS "CCACHE found and is being used.")
    set(CC_WRAPPER "ccache ")
else()
    message (STATUS "CCACHE not found.")
    set(CC_WRAPPER "")
endif()

set(GLOBAL_CFLAGS "-w -fPIC -O3 -flto=thin")
set(GLOBAL_CXXFLAGS "-w -fPIC -O3 -frtti -fexceptions -flto=thin") # -ffast-math
set(GLOBAL_LDFLAGS "-w -fPIC -Wl,--undefined-version -flto=thin -fuse-ld=lld -L${CMAKE_BINARY_DIR}/prefix/lib")

option(USE_VCPKG "Use vcpkg for dependencies" OFF)
if(USE_VCPKG)
    set(prefix "${CMAKE_BINARY_DIR}/openmw/vcpkg-prefix/src/vcpkg/vcpkg_installed/arm64-android/")
else()
    set(prefix "${CMAKE_BINARY_DIR}/prefix/")
endif()

set(ndk_triplet ${NDK_TRIPLET})
set(download_dir ${CMAKE_BINARY_DIR}/downloads/)
set(host ${ndk_triplet})
set(android_platform android-${ANDROID_API})
set(app_abi ${ANDROID_ABI})
set(jni_libs_dir "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")
set(OPENMW_SRC "" CACHE PATH "Path to a local OpenMW source checkout")
set(VCPKG_ROOT_DIR "${CMAKE_BINARY_DIR}/openmw/vcpkg-prefix/src/vcpkg")
file(TO_NATIVE_PATH "${VCPKG_ROOT_DIR}" VCPKG_ROOT_DIR_NATIVE)
set(VCPKG_USR_DIR "${CMAKE_BINARY_DIR}/openmw/openmw_deps-prefix/src/openmw_deps")
get_filename_component(PROJECT_ROOT ${CMAKE_SOURCE_DIR}/../../.. ABSOLUTE)
message(STATUS "PROJECT_ROOT: ${PROJECT_ROOT}")

if(NOT DEFINED GENERATED_ASSETS_DIR)
    message(FATAL_ERROR "GENERATED_ASSETS_DIR not set (should be passed from Gradle)")
endif()

file(MAKE_DIRECTORY "${GENERATED_ASSETS_DIR}")
file(MAKE_DIRECTORY "${jni_libs_dir}")

set(wrapper_command ${CMAKE_BINARY_DIR}/command_wrapper.sh)
file(WRITE ${wrapper_command}
"#!/bin/bash
export PATH=\"${NDK_TOOLCHAIN_BIN}:\$PATH\"
export CC=\"${CC_WRAPPER}${NDK_TRIPLET}${ANDROID_API}-clang\"
export CXX=\"${CC_WRAPPER}${NDK_TRIPLET}${ANDROID_API}-clang++\"
export CFLAGS=\"${GLOBAL_CFLAGS}\"
export CXXFLAGS=\"${GLOBAL_CXXFLAGS}\"
export LDFLAGS=\"${GLOBAL_LDFLAGS}\"
export AR=\"llvm-ar\"
export RANLIB=\"llvm-ranlib\"
export STRIP=\"llvm-strip\"

# --- pkg-config paths ---
export PKG_CONFIG_LIBDIR=\"${prefix}/lib/pkgconfig:${prefix}/share/pkgconfig\"
export PKG_CONFIG_PATH=\"${prefix}/lib/pkgconfig:${prefix}/share/pkgconfig\"

exec \"\$@\"
")
execute_process(COMMAND chmod +x ${wrapper_command})

ExternalProject_Add(openmw_deps
    GIT_REPOSITORY https://github.com/Duron27/openmw-deps-build.git
    GIT_TAG android-setup
    CONFIGURE_COMMAND ""
    BUILD_COMMAND ""
    INSTALL_COMMAND ""
)

ExternalProject_Add(vcpkg
    DEPENDS openmw_deps
    GIT_REPOSITORY https://github.com/microsoft/vcpkg.git
    GIT_TAG master
    GIT_SUBMODULES_RECURSE ON
    GIT_PROGRESS ON

    CONFIGURE_COMMAND ""

    BUILD_COMMAND ${CMAKE_COMMAND} -E copy_directory "${VCPKG_USR_DIR}/ports" "${VCPKG_ROOT_DIR}/ports"
    COMMAND ${CMAKE_COMMAND} -E copy_directory "${VCPKG_USR_DIR}/triplets" "${VCPKG_ROOT_DIR}/triplets"
    COMMAND ${CMAKE_COMMAND} -E copy "${VCPKG_USR_DIR}/vcpkg.json" "${VCPKG_ROOT_DIR}/vcpkg.json"
    COMMAND ${BOOTSTRAP_SCRIPT}

    INSTALL_COMMAND ${CMAKE_COMMAND} -E env "VCPKG_ROOT=${VCPKG_ROOT_DIR_NATIVE}" "ANDROID_NDK_HOME=${CMAKE_ANDROID_NDK}" ${VCPKG_EXE} install
    --triplet=arm64-android
    --overlay-ports=ports
    --overlay-triplets=triplets

    BUILD_IN_SOURCE 1
    USES_TERMINAL_BUILD ON
    USES_TERMINAL_INSTALL ON
)

set(COMMON_CMAKE_ARGS
    -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
    -DANDROID_ABI=${app_abi}
    -DANDROID_PLATFORM=${android_platform}
    -DANDROID_STL=c++_shared
    -DANDROID_LD=deprecated
    -DANDROID_CPP_FEATURES=
    -DCMAKE_C_FLAGS=-I${prefix}/include/\ ${GLOBAL_CFLAGS}
    -DCMAKE_CXX_FLAGS=-I${prefix}/include/\ ${GLOBAL_CXXFLAGS}
    -DCMAKE_SHARED_LINKER_FLAGS=${GLOBAL_LDFLAGS}
    -DCMAKE_BUILD_TYPE=release #${CMAKE_BUILD_TYPE}
    -DCMAKE_DEBUG_POSTFIX=
    -DCMAKE_INSTALL_PREFIX=${prefix}
    -DCMAKE_FIND_ROOT_PATH=${prefix}
    -DHAVE_LD_VERSION_SCRIPT=OFF
)

if(CCACHE_FOUND)
    list(APPEND COMMON_CMAKE_ARGS
        -DCMAKE_C_COMPILER_LAUNCHER=ccache
        -DCMAKE_CXX_COMPILER_LAUNCHER=ccache
    )
endif()

set(COMMON_AUTOCONF_FLAGS
    --host=${host}
    --enable-static
    --disable-shared
    --prefix=${prefix}
)

ExternalProject_Add(zlib
    URL https://github.com/madler/zlib/archive/refs/tags/v1.3.1.tar.gz
    URL_HASH SHA256=17e88863f3600672ab49182f217281b6fc4d3c762bde361935e436a95214d05c
    DOWNLOAD_DIR ${download_dir}

    CONFIGURE_COMMAND ${wrapper_command} cmake <SOURCE_DIR>
    ${COMMON_CMAKE_ARGS}
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5

    BUILD_COMMAND ${wrapper_command} make -j${NPROC}
    INSTALL_COMMAND ${wrapper_command} make install
    USES_TERMINAL_BUILD ON
    USES_TERMINAL_INSTALL ON
)

ExternalProject_Add(libjpeg-turbo
    URL https://github.com/libjpeg-turbo/libjpeg-turbo/releases/download/${LIBJPEG_TURBO_VERSION}/libjpeg-turbo-${LIBJPEG_TURBO_VERSION}.tar.gz
    URL_HASH ${LIBJPEG_TURBO_HASH}
    DOWNLOAD_DIR ${download_dir}

    CONFIGURE_COMMAND ${wrapper_command} cmake <SOURCE_DIR>
    ${COMMON_CMAKE_ARGS}
    -DENABLE_SHARED=false

    BUILD_COMMAND ${wrapper_command} make -j${NPROC} PROGRAMS=
    INSTALL_COMMAND ${wrapper_command} make install
    USES_TERMINAL_BUILD ON
    USES_TERMINAL_INSTALL ON
)

ExternalProject_Add(libpng
    URL http://prdownloads.sourceforge.net/libpng/libpng-${LIBPNG_VERSION}.tar.xz
    URL_HASH ${LIBPNG_HASH}
    DOWNLOAD_DIR ${download_dir}

    CONFIGURE_COMMAND ${wrapper_command} <SOURCE_DIR>/configure
    ${COMMON_AUTOCONF_FLAGS}

    BUILD_COMMAND ${wrapper_command} make check_PROGRAMS= bin_PROGRAMS=
    INSTALL_COMMAND ${wrapper_command} make install check_PROGRAMS= bin_PROGRAMS=
    USES_TERMINAL_BUILD ON
    USES_TERMINAL_INSTALL ON
)

ExternalProject_Add(libicu-host
    URL https://github.com/unicode-org/icu/archive/refs/tags/release-${LIBICU_VERSION}.zip
    URL_HASH ${LIBICU_HASH}
    DOWNLOAD_DIR ${download_dir}
    LOG_CONFIGURE 1
    LOG_BUILD 1
    LOG_INSTALL 1

    CONFIGURE_COMMAND <SOURCE_DIR>/icu4c/source/configure
    --enable-static
    --disable-shared
    --disable-tests
    --disable-samples

    BUILD_COMMAND make -j${NPROC}
    INSTALL_COMMAND ""
)

ExternalProject_Get_Property(libicu-host SOURCE_DIR BINARY_DIR)

ExternalProject_Add(libicu
    DEPENDS libicu-host
    SOURCE_DIR ${SOURCE_DIR}
    DOWNLOAD_COMMAND ""

    CONFIGURE_COMMAND ${wrapper_command} <SOURCE_DIR>/icu4c/source/configure
    ${COMMON_AUTOCONF_FLAGS}
    --disable-tests
    --disable-samples
    --disable-icuio
    --disable-extras
    --with-cross-build=${BINARY_DIR}

    BUILD_COMMAND ${wrapper_command} make -j${NPROC}
    INSTALL_COMMAND ${wrapper_command} make install install >> /dev/null
    USES_TERMINAL_BUILD ON
    USES_TERMINAL_INSTALL ON
)

ExternalProject_Add(freetype2
    URL http://prdownloads.sourceforge.net/freetype/freetype-${FREETYPE2_VERSION}.tar.gz
    URL_HASH ${FREETYPE2_HASH}
    DOWNLOAD_DIR ${download_dir}

    CONFIGURE_COMMAND ${wrapper_command} cmake <SOURCE_DIR>
    ${COMMON_CMAKE_ARGS}
    -DCMAKE_DISABLE_FIND_PACKAGE_ZLIB=TRUE
    -DCMAKE_DISABLE_FIND_PACKAGE_BZip2=TRUE
    -DCMAKE_DISABLE_FIND_PACKAGE_PNG=TRUE

    BUILD_COMMAND ${wrapper_command} make -j${NPROC}
    INSTALL_COMMAND ${wrapper_command} make install
    USES_TERMINAL_BUILD ON
    USES_TERMINAL_INSTALL ON
)

ExternalProject_Add(openal
    URL https://github.com/kcat/openal-soft/archive/${OPENAL_VERSION}.tar.gz
    URL_HASH ${OPENAL_HASH}
    DOWNLOAD_DIR ${download_dir}

    CONFIGURE_COMMAND ${wrapper_command} cmake <SOURCE_DIR>
    ${COMMON_CMAKE_ARGS}
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5
    -DALSOFT_EXAMPLES=OFF
    -DALSOFT_TESTS=OFF
    -DALSOFT_UTILS=OFF
    -DALSOFT_NO_CONFIG_UTIL=ON
    -DALSOFT_BACKEND_OPENSL=ON
    -DALSOFT_BACKEND_WAVE=OFF

    BUILD_COMMAND ${wrapper_command} make -j${NPROC}
    INSTALL_COMMAND ${wrapper_command} make install
    COMMAND ${CMAKE_COMMAND} -E copy_if_different ${prefix}/lib/libopenal.so ${jni_libs_dir}/
    USES_TERMINAL_BUILD ON
    USES_TERMINAL_INSTALL ON
)

ExternalProject_Add(boost
    URL https://github.com/boostorg/boost/releases/download/boost-${BOOST_VERSION}/boost-${BOOST_VERSION}-cmake.tar.gz
    URL_HASH ${BOOST_HASH}
    DOWNLOAD_DIR ${download_dir}

    PATCH_COMMAND patch -d <SOURCE_DIR>/libs/system/ -p1 -t -N < ${CMAKE_SOURCE_DIR}/openmw/patches/boost/system.diff
    COMMAND patch -d <SOURCE_DIR>/libs/regex/ -p1 -t -N < ${CMAKE_SOURCE_DIR}/openmw/patches/boost/regex.diff

    CONFIGURE_COMMAND ${wrapper_command} cmake <SOURCE_DIR>
    ${COMMON_CMAKE_ARGS}
    -DCMAKE_CXX_FLAGS=-I<SOURCE_DIR>/libs/core/include/\ ${GLOBAL_CXXFLAGS}
    -DBOOST_EXCLUDE_LIBRARIES=process

    BUILD_COMMAND ${wrapper_command} make -j${NPROC}
    INSTALL_COMMAND ${wrapper_command} make install install >> /dev/null

    COMMAND ${wrapper_command} llvm-ranlib ${prefix}/lib/libboost_{filesystem,program_options,iostreams}.a
    USES_TERMINAL_BUILD ON
    USES_TERMINAL_INSTALL ON
)

ExternalProject_Add(ffmpeg
    URL http://ffmpeg.org/releases/ffmpeg-${FFMPEG_VERSION}.tar.bz2
    URL_HASH ${FFMPEG_HASH}
    DOWNLOAD_DIR ${download_dir}

    CONFIGURE_COMMAND ${wrapper_command} <SOURCE_DIR>/configure
    --disable-asm
    --disable-optimizations
    --target-os=android
    --enable-cross-compile
    --cross-prefix=llvm-
    --cc=${host}${ANDROID_API}-clang
    --arch=${ARCH}
    --cpu=${FFMPEG_CPU}
    --prefix=${prefix}
    --extra-cflags=${GLOBAL_CFLAGS}\ -fvisibility=hidden
    --extra-cxxflags=${GLOBAL_CXXFLAGS}\ -fvisibility=hidden
    --extra-ldflags=${GLOBAL_LDFLAGS}
    --enable-version3
    --enable-pic
    --disable-everything
    --disable-doc
    --disable-programs
    --disable-autodetect
    --disable-iconv
    --enable-decoder=mp3
    --enable-demuxer=mp3
    --enable-decoder=bink
    --enable-decoder=binkaudio_rdft
    --enable-decoder=binkaudio_dct
    --enable-demuxer=bink
    --enable-demuxer=wav
    --enable-decoder=pcm_*
    --enable-decoder=vp8
    --enable-decoder=vp9
    --enable-decoder=opus
    --enable-decoder=vorbis
    --enable-demuxer=matroska
    --enable-demuxer=ogg

    BUILD_COMMAND ${wrapper_command} make -j${NPROC}
    INSTALL_COMMAND ${wrapper_command} make install
    USES_TERMINAL_BUILD ON
    USES_TERMINAL_INSTALL ON
)

ExternalProject_Add(sdl2
    URL https://www.libsdl.org/release/SDL2-${SDL2_VERSION}.tar.gz
    DOWNLOAD_DIR ${download_dir}
#    BUILD_ALWAYS 1

#    PATCH_COMMAND patch -d <SOURCE_DIR> -p1 -t -N < ${CMAKE_SOURCE_DIR}/openmw/patches/sdl2-add-display-mode.patch

    CONFIGURE_COMMAND ${wrapper_command} cmake <SOURCE_DIR>
    ${COMMON_CMAKE_ARGS}
    -DSDL_STATIC=OFF

    BUILD_COMMAND ${wrapper_command} make -j${NPROC} PROGRAMS=
    INSTALL_COMMAND ${wrapper_command} make install
    COMMAND ${CMAKE_COMMAND} -E copy_if_different ${prefix}/lib/libSDL2.so ${jni_libs_dir}/
    USES_TERMINAL_BUILD ON
    USES_TERMINAL_INSTALL ON
)

ExternalProject_Add(bullet
    URL https://github.com/bulletphysics/bullet3/archive/${BULLET_VERSION}.tar.gz
    DOWNLOAD_DIR ${download_dir}

    PATCH_COMMAND ""

    CONFIGURE_COMMAND ${wrapper_command} cmake <SOURCE_DIR>
    ${COMMON_CMAKE_ARGS}
    -DBUILD_BULLET2_DEMOS=OFF
    -DBUILD_CPU_DEMOS=OFF
    -DBUILD_UNIT_TESTS=OFF
    -DBUILD_EXTRAS=OFF
    -DUSE_DOUBLE_PRECISION=ON
    -DBULLET2_MULTITHREADING=ON
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5

    BUILD_COMMAND ${wrapper_command} make -j${NPROC}
    INSTALL_COMMAND ${wrapper_command} make install
    USES_TERMINAL_BUILD ON
    USES_TERMINAL_INSTALL ON
)

ExternalProject_Add(mygui
    DEPENDS freetype2

    URL https://github.com/MyGUI/mygui/archive/MyGUI${MYGUI_VERSION}.tar.gz
    DOWNLOAD_DIR ${download_dir}

    PATCH_COMMAND sed -i -e "s/unicode_char = uint32/unicode_char = char32_t/g" <SOURCE_DIR>/MyGUIEngine/include/MyGUI_UString.h
    COMMAND sed -i -e "s/code_point = uint16/code_point = char16_t/g" <SOURCE_DIR>/MyGUIEngine/include/MyGUI_UString.h

    CONFIGURE_COMMAND ${wrapper_command} cmake <SOURCE_DIR>
    ${COMMON_CMAKE_ARGS}
    -DMYGUI_RENDERSYSTEM=1
    -DMYGUI_BUILD_DEMOS=OFF
    -DMYGUI_BUILD_TOOLS=OFF
    -DMYGUI_BUILD_PLUGINS=OFF
    -DMYGUI_DONT_USE_OBSOLETE=ON
    -DMYGUI_STATIC=ON

    BUILD_COMMAND ${wrapper_command} make -j${NPROC}
    INSTALL_COMMAND ${wrapper_command} make install
    USES_TERMINAL_BUILD ON
    USES_TERMINAL_INSTALL ON
)

ExternalProject_Add(lz4
    URL https://github.com/lz4/lz4/archive/v${LZ4_VERSION}.tar.gz
    URL_HASH ${LZ4_HASH}
    DOWNLOAD_DIR ${download_dir}

    CONFIGURE_COMMAND ${wrapper_command} cmake <SOURCE_DIR>/build/cmake/
    ${COMMON_CMAKE_ARGS}
    -DBUILD_STATIC_LIBS=ON
    -DBUILD_SHARED_LIBS=OFF

    BUILD_COMMAND ${wrapper_command} make -j${NPROC}
    INSTALL_COMMAND ${wrapper_command} make install
    USES_TERMINAL_BUILD ON
    USES_TERMINAL_INSTALL ON
)

set(LUAJIT_MAKE_FLAGS
    HOST_CC=${LUAJIT_HOST_CC}
    CFLAGS=
    TARGET_CFLAGS=${GLOBAL_CFLAGS}
    CROSS=llvm-
    STATIC_CC=${host}${ANDROID_API}-clang
    DYNAMIC_CC=${host}${ANDROID_API}-clang\ -fPIC
    TARGET_LD=${host}${ANDROID_API}-clang
    PREFIX=${prefix}
    BUILDMODE=static
    XCFLAGS=-DLUAJIT_ENABLE_SANDBOX_BYPASS
)

ExternalProject_Add(luajit
    URL https://github.com/DreamWeave-MP/rubic0n/archive/refs/tags/development.tar.gz
    DOWNLOAD_DIR ${download_dir}

    BUILD_IN_SOURCE 1

    CONFIGURE_COMMAND ""

    BUILD_COMMAND ${wrapper_command} make amalg
    ${LUAJIT_MAKE_FLAGS}

    INSTALL_COMMAND ${wrapper_command} make install
    ${LUAJIT_MAKE_FLAGS}

    USES_TERMINAL_BUILD ON
    USES_TERMINAL_INSTALL ON
)

ExternalProject_Add(libxml
    DEPENDS zlib

    URL https://github.com/GNOME/libxml2/archive/refs/tags/v${LIBXML2_VERSION}.tar.gz
    URL_HASH ${LIBXML2_HASH}
    DOWNLOAD_DIR ${download_dir}

    CONFIGURE_COMMAND ${wrapper_command} cmake <SOURCE_DIR>
    ${COMMON_CMAKE_ARGS}
    -DBUILD_SHARED_LIBS=OFF
    -DLIBXML2_WITH_CATALOG=OFF
    -DLIBXML2_WITH_ICONV=OFF
    -DLIBXML2_WITH_LZMA=OFF
    -DLIBXML2_WITH_PROGRAMS=OFF
    -DLIBXML2_WITH_PYTHON=OFF
    -DLIBXML2_WITH_TESTS=OFF
    -DLIBXML2_WITH_ZLIB=ON

    BUILD_COMMAND ${wrapper_command} make -j${NPROC}
    INSTALL_COMMAND ${wrapper_command} make install
    USES_TERMINAL_BUILD ON
    USES_TERMINAL_INSTALL ON
)

ExternalProject_Add(libogg
    URL https://github.com/xiph/ogg/releases/download/v1.3.5/libogg-1.3.5.tar.gz
    DOWNLOAD_DIR ${download_dir}

    CONFIGURE_COMMAND ${wrapper_command} cmake <SOURCE_DIR>
    ${COMMON_CMAKE_ARGS}
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5

    BUILD_COMMAND ${wrapper_command} make -j${NPROC}
    INSTALL_COMMAND ${wrapper_command} make install
    USES_TERMINAL_BUILD ON
    USES_TERMINAL_INSTALL ON
)

ExternalProject_Add(libvorbis
    DEPENDS libogg
    URL https://github.com/xiph/vorbis/releases/download/v1.3.7/libvorbis-1.3.7.tar.gz
    DOWNLOAD_DIR ${download_dir}

    CONFIGURE_COMMAND ${wrapper_command} cmake <SOURCE_DIR>
    ${COMMON_CMAKE_ARGS}
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5

    BUILD_COMMAND ${wrapper_command} make -j${NPROC}
    INSTALL_COMMAND ${wrapper_command} make install
    USES_TERMINAL_BUILD ON
    USES_TERMINAL_INSTALL ON
)

ExternalProject_Add(uqm
    DEPENDS sdl2 openal libpng zlib libvorbis libogg

    GIT_REPOSITORY https://github.com/JHGuitarFreak/UQM-MegaMod.git
    GIT_TAG master
    DOWNLOAD_DIR ${download_dir}
#    BUILD_ALWAYS 1

    UPDATE_COMMAND ""

    PATCH_COMMAND ${CMAKE_COMMAND} -E copy ${CMAKE_SOURCE_DIR}/openmw/patches/uqm/jni_engine.c <SOURCE_DIR>/src/jni_engine.c
    COMMAND    ${CMAKE_COMMAND} -E copy ${CMAKE_SOURCE_DIR}/openmw/patches/uqm/config_android.h.in <SOURCE_DIR>/src/config_android.h.in
    COMMAND sed -i "/add_library (UrQuanMasters SHARED/i list(APPEND UQM_SOURCES src/jni_engine.c)" <SOURCE_DIR>/CMakeLists.txt

    CONFIGURE_COMMAND ${wrapper_command} cmake -S <SOURCE_DIR> -B <BINARY_DIR>
    ${COMMON_CMAKE_ARGS}

    BUILD_COMMAND ${wrapper_command} make -C <BINARY_DIR> -j${NPROC}
    INSTALL_COMMAND ${CMAKE_COMMAND} -E copy <BINARY_DIR>/libUrQuanMasters.so ${jni_libs_dir}/libuqm.so
    USES_TERMINAL_BUILD ON
    USES_TERMINAL_INSTALL ON
)

ExternalProject_Add(dethrace
    DEPENDS sdl2 openal

    GIT_REPOSITORY https://github.com/Duron27-org/dethrace.git
    GIT_TAG main
    GIT_SUBMODULES_RECURSE ON
    DOWNLOAD_DIR ${download_dir}

    UPDATE_COMMAND ""

    CONFIGURE_COMMAND ${wrapper_command} cmake <SOURCE_DIR>
    ${COMMON_CMAKE_ARGS}
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5
    -DDETHRACE_ANDROID=ON
    -DDETHRACE_PLATFORM_SDL2=ON
    -DSDL2_INCLUDE_DIR=${prefix}/include
    -DSDL2_LIBRARY=${prefix}/lib/libSDL2.so

    BUILD_COMMAND ${wrapper_command} make -C <BINARY_DIR> -j${NPROC}
    INSTALL_COMMAND ${CMAKE_COMMAND} -E copy <BINARY_DIR>/src/DETHRACE/libdethrace.so ${jni_libs_dir}/
    USES_TERMINAL_BUILD ON
    USES_TERMINAL_INSTALL ON
)

ExternalProject_Add(glslang
    GIT_REPOSITORY https://github.com/KhronosGroup/glslang
    GIT_TAG main
    DOWNLOAD_DIR ${download_dir}

    UPDATE_COMMAND ""

    CONFIGURE_COMMAND ${wrapper_command} cmake <SOURCE_DIR>
    ${COMMON_CMAKE_ARGS}
    -DBUILD_EXTERNAL=OFF
    -DENABLE_OPT=OFF
    -DENABLE_PCH=OFF
    -DENABLE_GLSLANG_BINARIES=OFF

    BUILD_COMMAND ${wrapper_command} make -j${NPROC}
    INSTALL_COMMAND ${wrapper_command} make install
    COMMAND ${CMAKE_COMMAND} -E copy_directory <SOURCE_DIR>/glslang/Include ${prefix}/include/glslang/Include
    USES_TERMINAL_BUILD ON
    USES_TERMINAL_INSTALL ON
)

ExternalProject_Add(glad
    SOURCE_DIR ${CMAKE_SOURCE_DIR}/GLAD

    CONFIGURE_COMMAND ${wrapper_command} cmake <SOURCE_DIR>
    ${COMMON_CMAKE_ARGS}

    BUILD_COMMAND ${wrapper_command} cmake --build . -j${NPROC}

    INSTALL_COMMAND ${CMAKE_COMMAND} -E copy <BINARY_DIR>/libglad.so ${prefix}/lib/
    COMMAND ${CMAKE_COMMAND} -E copy_directory <SOURCE_DIR>/include ${prefix}/include/
    COMMAND ${CMAKE_COMMAND} -E copy_if_different <BINARY_DIR>/libglad.so ${jni_libs_dir}/

    USES_TERMINAL_BUILD ON
    USES_TERMINAL_INSTALL ON
)

ExternalProject_Add(spirv-cross
    GIT_REPOSITORY https://github.com/KhronosGroup/SPIRV-Cross
    GIT_TAG main
    DOWNLOAD_DIR ${download_dir}

    UPDATE_COMMAND ""

    CONFIGURE_COMMAND ${wrapper_command} cmake <SOURCE_DIR>
    ${COMMON_CMAKE_ARGS}
    -DSPIRV_CROSS_CLI=OFF
    -DSPIRV_CROSS_ENABLE_CPP=OFF
    -DSPIRV_CROSS_ENABLE_HLSL=OFF
    -DSPIRV_CROSS_ENABLE_MSL=OFF
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5

    BUILD_COMMAND ${wrapper_command} make -j${NPROC}
    INSTALL_COMMAND ${wrapper_command} make install
    USES_TERMINAL_BUILD ON
    USES_TERMINAL_INSTALL ON
)

ExternalProject_Add(kram
    URL https://github.com/Duron27/kram/archive/09bf2e9bd52f20658f032e957a75093c3be08698.tar.gz
    DOWNLOAD_DIR ${download_dir}

    CONFIGURE_COMMAND ${wrapper_command} cmake <SOURCE_DIR>
    ${COMMON_CMAKE_ARGS}

    BUILD_COMMAND ${wrapper_command} make -j${NPROC}

    INSTALL_COMMAND ${CMAKE_COMMAND} -E make_directory ${prefix}/lib/
    COMMAND ${CMAKE_COMMAND} -E copy kramc/kram ${jni_libs_dir}/libkram.so
    USES_TERMINAL_BUILD ON
    USES_TERMINAL_INSTALL ON
)

set(LIBCOLLADA_PATCH
    patch -d <SOURCE_DIR> -p0 -t -N < ${CMAKE_SOURCE_DIR}/openmw/patches/libcollada-minizip-fix.patch
)

ExternalProject_Add(libcollada
    DEPENDS libxml boost

    URL https://github.com/rdiankov/collada-dom/archive/v2.5.0.tar.gz
    URL_HASH SHA256=3be672407a7aef60b64ce4b39704b32816b0b28f61ebffd4fbd02c8012901e0d
    DOWNLOAD_DIR ${download_dir}

    PATCH_COMMAND ${LIBCOLLADA_PATCH}
    COMMAND sed -i -e "s|boost/filesystem/convenience.hpp|boost/filesystem.hpp|g" <SOURCE_DIR>/dom/include/dae.h
    COMMAND sed -i -e "s|boost/filesystem/convenience.hpp|boost/filesystem.hpp|g" <SOURCE_DIR>/dom/src/dae/daeUtils.cpp
    COMMAND sed -i -e "s/branch_path/parent_path/g" <SOURCE_DIR>/dom/src/dae/daeZAEUncompressHandler.cpp

    CONFIGURE_COMMAND ${wrapper_command} cmake <SOURCE_DIR>
    ${COMMON_CMAKE_ARGS}
    -DCMAKE_POLICY_VERSION_MINIMUM=3.5
    -DCMAKE_CXX_FLAGS=-Dauto_ptr=unique_ptr\ ${GLOBAL_CXXFLAGS}

    BUILD_COMMAND ${wrapper_command} make -j${NPROC}
    INSTALL_COMMAND ${wrapper_command} make install
    COMMAND ${CMAKE_COMMAND} -E copy_if_different ${prefix}/lib/libcollada-dom2.5-dp.so ${jni_libs_dir}/
    USES_TERMINAL_BUILD ON
    USES_TERMINAL_INSTALL ON
)

set(OSG_COMMON
    -DOPENGL_PROFILE=GLES3
    -DDYNAMIC_OPENTHREADS=OFF
    -DDYNAMIC_OPENSCENEGRAPH=OFF
    -DBUILD_OSG_PLUGIN_OSG=ON
    -DBUILD_OSG_PLUGIN_DAE=ON
    -DBUILD_OSG_PLUGIN_DDS=ON
    -DBUILD_OSG_PLUGIN_KTX=ON
    -DBUILD_OSG_PLUGIN_TGA=ON
    -DBUILD_OSG_PLUGIN_BMP=ON
    -DBUILD_OSG_PLUGIN_JPEG=ON
    -DBUILD_OSG_PLUGIN_PNG=ON
    -DBUILD_OSG_PLUGIN_FREETYPE=ON
    -DJPEG_INCLUDE_DIR=${prefix}/include/
    -DPNG_INCLUDE_DIR=${prefix}/include/
    -DFREETYPE_DIR=${prefix}
    -DCOLLADA_INCLUDE_DIR=${prefix}/include/collada-dom2.5
    -DOSG_CPP_EXCEPTIONS_AVAILABLE=TRUE
    -DOSG_GLU_AVAILABLE=OFF
    -DOSG_GL1_AVAILABLE=OFF
    -DOSG_GL2_AVAILABLE=OFF
    -DOSG_GL3_AVAILABLE=OFF
    -DOSG_GLES1_AVAILABLE=OFF
    -DOSG_GLES2_AVAILABLE=OFF
    -DOSG_GLES3_AVAILABLE=ON
    -DBUILD_OSG_APPLICATIONS=OFF
    -DBUILD_OSG_PLUGINS_BY_DEFAULT=OFF
    -DBUILD_OSG_DEPRECATED_SERIALIZERS=OFF
)

set(OSG_PATCH
    patch -d <SOURCE_DIR> -p1 -t -N < ${CMAKE_SOURCE_DIR}/openmw/patches/osg/remove-lib-prefix-from-plugins.patch &&
    patch -d <SOURCE_DIR> -p1 -t -N < ${CMAKE_SOURCE_DIR}/openmw/patches/osg/fix-freetype-include-dirs.patch &&
    patch -d <SOURCE_DIR> -p1 -t -N < ${CMAKE_SOURCE_DIR}/openmw/patches/osg/0001-Replace-Atomic-impl-with-std-atomic.patch &&
    patch -d <SOURCE_DIR> -p1 -t -N < ${CMAKE_SOURCE_DIR}/openmw/patches/osg/0002-BufferObject-make-numClients-atomic.patch &&
    patch -d <SOURCE_DIR> -p1 -t -N < ${CMAKE_SOURCE_DIR}/openmw/patches/osg/0004-IncrementalCompileOperation-wrap-some-stuff-in-atomi.patch &&
    patch -d <SOURCE_DIR> -p1 -t -N < ${CMAKE_SOURCE_DIR}/openmw/patches/osg/force-add-plugins.patch &&
    patch -d <SOURCE_DIR> -p1 -t -N < ${CMAKE_SOURCE_DIR}/openmw/patches/osg/dae_collada.patch &&
    patch -d <SOURCE_DIR> -p1 -t -N < ${CMAKE_SOURCE_DIR}/openmw/patches/osg/features.patch &&
    patch -d <SOURCE_DIR> -p1 -t -N < ${CMAKE_SOURCE_DIR}/openmw/patches/osg/textures.patch &&
    patch -d <SOURCE_DIR> -p1 -t -N < ${CMAKE_SOURCE_DIR}/openmw/patches/osg/msaa.patch &&
    patch -d <SOURCE_DIR> -p1 -t -N < ${CMAKE_SOURCE_DIR}/openmw/patches/osg/glad.patch &&
    patch -d <SOURCE_DIR> -p1 -t -N < ${CMAKE_SOURCE_DIR}/openmw/patches/osg/0005-CullSettings-make-inheritanceMask-atomic-to-silence-.patch &&
    patch -d <SOURCE_DIR> -p1 -t -N < ${CMAKE_SOURCE_DIR}/openmw/patches/osg/silence-clipplane-warning.patch
)

ExternalProject_Add(osg
    DEPENDS libjpeg-turbo libpng freetype2 libcollada glad

    GIT_REPOSITORY https://github.com/OpenMW/osg
    GIT_TAG ${OSG_VERSION}
    DOWNLOAD_DIR ${download_dir}

    UPDATE_COMMAND ""

    PATCH_COMMAND ${OSG_PATCH}

    CONFIGURE_COMMAND ${wrapper_command} cmake <SOURCE_DIR>
    ${COMMON_CMAKE_ARGS}
    ${OSG_COMMON}
    -DCMAKE_CXX_FLAGS=-Dauto_ptr=unique_ptr\ -I${prefix}/include\ ${GLOBAL_CXXFLAGS}

    BUILD_COMMAND ${wrapper_command} make -j${NPROC}

    INSTALL_COMMAND ${CMAKE_COMMAND} -E make_directory <SOURCE_DIR>/3rdparty/build/libpng
    COMMAND ${CMAKE_COMMAND} -E make_directory <SOURCE_DIR>/3rdparty/build/libjpeg
    COMMAND ${CMAKE_COMMAND} -E make_directory <SOURCE_DIR>/3rdparty/build/freetype
    COMMAND ${wrapper_command} make install
    USES_TERMINAL_BUILD ON
    USES_TERMINAL_INSTALL ON
)

set(OPENMW_COMMON
    -DBUILD_BSATOOL=1
    -DBUILD_NIFTEST=0
    -DBUILD_ESMTOOL=0
    -DBUILD_LAUNCHER=0
    -DBUILD_MWINIIMPORTER=1
    -DBUILD_ESSIMPORTER=0
    -DBUILD_OPENCS=0
    -DBUILD_NAVMESHTOOL=1
    -DBUILD_WIZARD=0
    -DBUILD_BULLETOBJECTTOOL=0
    -DOPENMW_USE_SYSTEM_SQLITE3=OFF
    -DOPENMW_USE_SYSTEM_YAML_CPP=OFF
    -DOPENMW_USE_SYSTEM_ICU=ON
    -DOPENGL_gl_LIBRARY=${prefix}/lib/libglad.so
    -DOPENGL_INCLUDE_DIR=${prefix}/include/
    -DOPENAL_INCLUDE_DIR=${prefix}/include/AL/
    -DOSG_STATIC=TRUE
    -DCMAKE_CXX_FLAGS=-std=gnu++20\ -I${prefix}/include/\ ${GLOBAL_CXXFLAGS}
    -DMyGUI_LIBRARY=${prefix}/lib/libMyGUIEngineStatic.a
    -Dglslang_DIR=<SOURCE_DIR>/cmake
)

set(OPENMW_PATCH
    patch -d <SOURCE_DIR> -p1 -t -N < ${CMAKE_SOURCE_DIR}/openmw/patches/openmw/0009-windowmanagerimp-always-show-mouse-when-possible-pat.patch &&
    patch -d <SOURCE_DIR> -p1 -t -N < ${CMAKE_SOURCE_DIR}/openmw/patches/openmw/androidPath.patch &&
    patch -d <SOURCE_DIR> -p1 -t -N < ${CMAKE_SOURCE_DIR}/openmw/patches/openmw/makeLibs.patch &&
    patch -d <SOURCE_DIR> -p1 -t -N < ${CMAKE_SOURCE_DIR}/openmw/patches/openmw/ktx.patch &&
    patch -d <SOURCE_DIR> -p1 -t -N < ${CMAKE_SOURCE_DIR}/openmw/patches/openmw/bsatool-extract-dds-only.patch &&
    patch -d <SOURCE_DIR> -p1 -t -N < ${CMAKE_SOURCE_DIR}/openmw/patches/openmw/base-changes.patch &&
    patch -d <SOURCE_DIR> -p1 -t -N < ${CMAKE_SOURCE_DIR}/openmw/patches/openmw/allow-more-es-versions.patch &&
    patch -d <SOURCE_DIR> -p1 -t -N < ${CMAKE_SOURCE_DIR}/openmw/patches/openmw/GLES-3-OMW.patch &&
    patch -d <SOURCE_DIR> -p1 -t -N < ${CMAKE_SOURCE_DIR}/openmw/patches/openmw/textures.patch &&
    patch -d <SOURCE_DIR> -p1 -t -N < ${CMAKE_SOURCE_DIR}/openmw/patches/openmw/features.patch &&
    patch -d <SOURCE_DIR> -p1 -t -N < ${CMAKE_SOURCE_DIR}/openmw/patches/openmw/misc.patch &&
    patch -d <SOURCE_DIR> -p1 -t -N < ${CMAKE_SOURCE_DIR}/openmw/patches/openmw/spirv.patch &&
    patch -d <SOURCE_DIR> -p1 -t -N < ${CMAKE_SOURCE_DIR}/openmw/patches/openmw/glad.patch &&
    patch -d <SOURCE_DIR> -p1 -t -N < ${CMAKE_SOURCE_DIR}/openmw/patches/openmw/bcdec.patch &&
    patch -d <SOURCE_DIR> -p1 -t -N < ${CMAKE_SOURCE_DIR}/openmw/patches/openmw/logcatSTDout.patch &&
    patch -d <SOURCE_DIR> -p1 -t -N < ${CMAKE_SOURCE_DIR}/openmw/patches/openmw/hackyRumble.patch
)

if(OPENMW_SRC)
    set(OPENMW_SOURCE_DIR "${OPENMW_SRC}")
    set(OPENMW_GIT_ARGS "")
else()
    set(OPENMW_SOURCE_DIR "")
    set(OPENMW_GIT_ARGS
        GIT_REPOSITORY https://github.com/OpenMW/openmw
        GIT_TAG ${OPENMW_VERSION}
        DOWNLOAD_DIR ${download_dir}
    )
endif()

set(OPENMW_DEPS)
if(NOT USE_VCPKG)
    list(APPEND OPENMW_DEPS
        boost openal osg mygui ffmpeg sdl2 bullet lz4 luajit libicu glslang spirv-cross
    )
else()
    list(APPEND OPENMW_DEPS vcpkg)
endif()

ExternalProject_Add(openmw
    DEPENDS ${OPENMW_DEPS}

    URL https://github.com/sisah2/openmw/archive/edddcd8ce7b8a505f7f5eb949482eaa44093325b.tar.gz
    DOWNLOAD_DIR ${download_dir}

#    ${OPENMW_GIT_ARGS}
#    SOURCE_DIR ${OPENMW_SOURCE_DIR}

#    UPDATE_COMMAND ""
#    BUILD_ALWAYS 1

    PATCH_COMMAND ${OPENMW_PATCH}
    COMMAND ${CMAKE_COMMAND} -E copy_if_different ${CMAKE_SOURCE_DIR}/openmw/patches/openmw/androidmain.cpp <SOURCE_DIR>/apps/openmw/androidmain.cpp

    CONFIGURE_COMMAND ${wrapper_command} cmake <SOURCE_DIR>
    ${COMMON_CMAKE_ARGS}
    -DCMAKE_SHARED_LINKER_FLAGS=${GLOBAL_LDFLAGS}\ -lglad
    -DCMAKE_EXE_LINKER_FLAGS=${GLOBAL_LDFLAGS}\ -lglad
    ${OPENMW_COMMON}
    BUILD_COMMAND ${wrapper_command} make -j${NPROC}

    INSTALL_COMMAND ${CMAKE_COMMAND} -E make_directory ${GENERATED_ASSETS_DIR}/libopenmw/openmw
    COMMAND ${CMAKE_COMMAND} -E copy_directory resources ${GENERATED_ASSETS_DIR}/libopenmw/resources
    COMMAND ${CMAKE_COMMAND} -E copy defaults.bin gamecontrollerdb.txt ${GENERATED_ASSETS_DIR}/libopenmw/openmw/
    COMMAND ${CMAKE_COMMAND} -E copy libopenmw.so ${jni_libs_dir}/
    COMMAND ${CMAKE_COMMAND} -E copy libbsatool.so ${jni_libs_dir}/
    COMMAND ${CMAKE_COMMAND} -E copy libopenmw-iniimporter.so ${jni_libs_dir}/
    COMMAND ${CMAKE_COMMAND} -E copy libopenmw-navmeshtool.so ${jni_libs_dir}/

    USES_TERMINAL_BUILD ON
    USES_TERMINAL_INSTALL ON
)

add_custom_target(copy_final ALL
    DEPENDS openmw dethrace uqm

    COMMAND ${CMAKE_COMMAND} -E copy_directory ${CMAKE_SOURCE_DIR}/Libraries/ ${jni_libs_dir}/

    # Copy Rust binaries renamed to .so
    COMMAND ${CMAKE_COMMAND} -E copy $<TARGET_FILE:s3lightfixes-shared> ${jni_libs_dir}/libs3lightfixes.so
    COMMAND ${CMAKE_COMMAND} -E copy $<TARGET_FILE:delta_plugin> ${jni_libs_dir}/libdelta_plugin.so

    # Copy ANGLE binaries
#    COMMAND ${CMAKE_COMMAND} -E copy $<TARGET_FILE:libEGL> ${jni_libs_dir}/libEGL.so
#    COMMAND ${CMAKE_COMMAND} -E copy $<TARGET_FILE:libGLESv2> ${jni_libs_dir}/libGLESv2.so

    #Final Copy of assets
    COMMAND ${CMAKE_COMMAND} -E copy ${PROJECT_ROOT}/settings.fallback.cfg ${GENERATED_ASSETS_DIR}/libopenmw/openmw/
    COMMAND ${CMAKE_COMMAND} -E copy ${PROJECT_ROOT}/openmw.cfg ${GENERATED_ASSETS_DIR}/libopenmw/openmw/
    COMMAND ${CMAKE_COMMAND} -E copy_directory ${PROJECT_ROOT}/ui ${GENERATED_ASSETS_DIR}/libopenmw/ui
    COMMAND ${CMAKE_COMMAND} -E copy ${PROJECT_ROOT}/../3rdparty-licenses.txt ${GENERATED_ASSETS_DIR}/libopenmw/
)
