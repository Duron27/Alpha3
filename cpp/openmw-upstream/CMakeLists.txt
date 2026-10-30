cmake_minimum_required(VERSION 3.22.1)

project(openmw-upstream)

# Stock OpenMW (https://gitlab.com/OpenMW/openmw) for Android (next to the fork in ../openmw) and iOS (in place of it).
# The changes to OpenMW are git format-patch series (see patches/README.md): patches/common for both platforms (Android-only
# hunks are inside #ifdef ANDROID / if (ANDROID)), then patches/ios on iOS. None of it is shared with the fork's patch set,
# so either can move on its own.
#
# Differences from the fork's build:
#   * no Vulkan renderer: OpenGL only. Android: renderer = "opengl" is desktop GL through Mesa Zink (libOSMesa_8.so),
#     "opengl es 3.2" is the phone's own GLES driver. iOS: Apple's OpenGL ES 3.0 (OpenGLES.framework), through glad
#   * Lua is LuaJIT (upstream's default), cross compiled here; the fork uses Luau. iOS forbids generated code, so LuaJIT
#     runs as an interpreter there
#   * Android: the library is libopenmw_upstream.so and the assets live in assets/libopenmw-upstream. iOS: the library is
#     libopenmw.framework and the assets are iosApp/OpenMWAssets, where the fork's build put its own

set(OPENMW_UPSTREAM_SRC "" CACHE PATH "Path to a local checkout of stock OpenMW")

# Pinned so the patch series always applies. To move it: bump the commit, run
# rebase the series onto it as described in patches/README.md and fix what conflicts.
set(OPENMW_UPSTREAM_REPO https://gitlab.com/OpenMW/openmw.git)
set(OPENMW_UPSTREAM_COMMIT 33184cd19434203e408ecedf135b2094706b38d7)

# ----------------------------------------------------------------------------------------------------------------------
# LuaJIT, cross compiled with the NDK or Xcode's iOS clang. Its build tools (minilua, buildvm) run on the build machine, so
# HOST_CC must be a compiler for the host, not the target one. The target is 64 bit, so the host compiler needs no -m32.
# ----------------------------------------------------------------------------------------------------------------------
# rubic0n: a LuaJIT 2.1 build tuned for speed. "development" is a moving tag, so the hash has to be updated with it.
set(OPENMW_UPSTREAM_LUAJIT_URL https://github.com/DreamWeave-MP/rubic0n/archive/refs/tags/development.tar.gz)
set(OPENMW_UPSTREAM_LUAJIT_SHA256 9919657540888fe6cb425f606e718b3f1ba2d6c76e132422cb07906f1db6fce1) # 2026-10-23
set(luajit_prefix ${CMAKE_CURRENT_BINARY_DIR}/luajit-install)

find_program(OPENMW_UPSTREAM_HOST_CC NAMES cc gcc clang HINTS ENV HOST_CC
    NO_CMAKE_FIND_ROOT_PATH NO_CMAKE_PATH NO_CMAKE_ENVIRONMENT_PATH NO_CMAKE_SYSTEM_PATH)
if(NOT OPENMW_UPSTREAM_HOST_CC)
    message(FATAL_ERROR "LuaJIT needs a host C compiler (cc/gcc/clang) to build its code generators")
endif()

if(ALPHA3_IS_ANDROID)
    set(ndk_cc ${NDK_TOOLCHAIN_BIN}/${NDK_TRIPLET}${ANDROID_API}-clang)
    set(luajit_make_args
        HOST_CC=${OPENMW_UPSTREAM_HOST_CC}
        CROSS=${NDK_TOOLCHAIN_BIN}/llvm-
        "STATIC_CC=${ndk_cc} -fPIC"
        "DYNAMIC_CC=${ndk_cc} -fPIC"
        TARGET_LD=${ndk_cc}
        "TARGET_AR=${NDK_TOOLCHAIN_BIN}/llvm-ar rcus"
        TARGET_STRIP=${NDK_TOOLCHAIN_BIN}/llvm-strip
        TARGET_SYS=Linux
        BUILDMODE=static
        PREFIX=${luajit_prefix}
    )
    set(luajit_env "")
else()
    # TARGET_SYS=iOS: LuaJIT turns its JIT compiler off (no executable memory for apps), the interpreter stays
    execute_process(COMMAND xcrun --sdk ${SDK_NAME} -f strip OUTPUT_VARIABLE ios_strip OUTPUT_STRIP_TRAILING_WHITESPACE)
    set(luajit_make_args
        HOST_CC=${OPENMW_UPSTREAM_HOST_CC}
        "STATIC_CC=${IOS_CLANG} -fPIC"
        "DYNAMIC_CC=${IOS_CLANG} -fPIC"
        TARGET_LD=${IOS_CLANG}
        "TARGET_AR=${IOS_AR} rcus"
        TARGET_STRIP=${ios_strip}
        "TARGET_FLAGS=-arch ${ARCH} -isysroot ${IOS_SDK_PATH} ${MIN_VERSION_FLAG}"
        TARGET_SYS=iOS
        BUILDMODE=static
        PREFIX=${luajit_prefix}
    )
    # the host tools are built for macOS: an SDKROOT for iOS left by Xcode would send them to the iOS SDK
    set(luajit_env ${CMAKE_COMMAND} -E env --unset=SDKROOT --unset=IPHONEOS_DEPLOYMENT_TARGET)
endif()

ExternalProject_Add(luajit
    URL ${OPENMW_UPSTREAM_LUAJIT_URL}
    URL_HASH SHA256=${OPENMW_UPSTREAM_LUAJIT_SHA256}
    DOWNLOAD_DIR ${download_dir}
    UPDATE_COMMAND ""
    CONFIGURE_COMMAND ""
    BUILD_IN_SOURCE 1
    BUILD_COMMAND ${luajit_env} make -j${NPROC} amalg ${luajit_make_args}
    INSTALL_COMMAND ${luajit_env} make install ${luajit_make_args}
    BUILD_BYPRODUCTS ${luajit_prefix}/lib/libluajit-5.1.a
    USES_TERMINAL_BUILD ON
)

# ----------------------------------------------------------------------------------------------------------------------
# OpenMW
# ----------------------------------------------------------------------------------------------------------------------
set(assets_upstream_dir ${assets_dir}/libopenmw-upstream)

# patches/common on every platform, then the platform's own series (patches/ios)
file(GLOB OPENMW_UPSTREAM_PATCHES CONFIGURE_DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/patches/common/*.patch)
list(SORT OPENMW_UPSTREAM_PATCHES)
if(ALPHA3_IS_IOS)
    file(GLOB OPENMW_UPSTREAM_IOS_PATCHES CONFIGURE_DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/patches/ios/*.patch)
    list(SORT OPENMW_UPSTREAM_IOS_PATCHES)
    list(APPEND OPENMW_UPSTREAM_PATCHES ${OPENMW_UPSTREAM_IOS_PATCHES})
endif()
# Editing a patch re-runs the patch step
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${OPENMW_UPSTREAM_PATCHES})

# Multiplayer (Lua networking, avatars, host/join menu): the fork's multiplayer, ported to stock OpenMW (LuaJIT, sol2). The
# new files live in support/multiplayer/tree and are copied into the OpenMW sources; patches/multiplayer holds only the
# edits to OpenMW's own files, applied after the common and platform ones; support/multiplayer/CMakeLists.txt adds the sources to the
# engine and puts the Lua mod into resources/vfs (mp.omwscripts is loaded as a built-in script list).
# -DALPHA3_MULTIPLAYER=OFF builds plain OpenMW.
option(ALPHA3_MULTIPLAYER "Build OpenMW with multiplayer (support/multiplayer)" ON)
set(OPENMW_UPSTREAM_PATCH_COMMANDS "")
set(OPENMW_UPSTREAM_PATCH_DEPENDS ${OPENMW_UPSTREAM_PATCHES})
if(ALPHA3_MULTIPLAYER)
    file(GLOB OPENMW_UPSTREAM_MP_PATCHES CONFIGURE_DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/patches/multiplayer/*.patch)
    list(SORT OPENMW_UPSTREAM_MP_PATCHES)
    set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${OPENMW_UPSTREAM_MP_PATCHES})
    list(APPEND OPENMW_UPSTREAM_PATCHES ${OPENMW_UPSTREAM_MP_PATCHES})
    # the new files first; the patches only change files OpenMW already has
    list(APPEND OPENMW_UPSTREAM_PATCH_COMMANDS
        COMMAND ${CMAKE_COMMAND} -E copy_directory ${CMAKE_CURRENT_SOURCE_DIR}/support/multiplayer/tree <SOURCE_DIR>)
    # and an edited file copies them again
    file(GLOB_RECURSE OPENMW_UPSTREAM_MP_TREE CONFIGURE_DEPENDS ${CMAKE_CURRENT_SOURCE_DIR}/support/multiplayer/tree/*)
    list(APPEND OPENMW_UPSTREAM_PATCH_DEPENDS ${OPENMW_UPSTREAM_MP_PATCHES} ${OPENMW_UPSTREAM_MP_TREE})
endif()

foreach(upstream_patch IN LISTS OPENMW_UPSTREAM_PATCHES)
    # git apply, not patch: the series is git format-patch output and applies all or nothing per patch
    list(APPEND OPENMW_UPSTREAM_PATCH_COMMANDS
        COMMAND ${CMAKE_COMMAND} -DSRC=<SOURCE_DIR> -DPATCH=${upstream_patch}
                -P ${CMAKE_CURRENT_SOURCE_DIR}/cmake/apply_patch.cmake)
endforeach()

if(OPENMW_UPSTREAM_SRC)
    set(OPENMW_UPSTREAM_SOURCE_ARGS SOURCE_DIR ${OPENMW_UPSTREAM_SRC})
    # A local checkout is patched in place and is the user's to reset
    set(OPENMW_UPSTREAM_PATCH_STEP ${OPENMW_UPSTREAM_PATCH_COMMANDS})
else()
    set(OPENMW_UPSTREAM_SOURCE_ARGS
        GIT_REPOSITORY ${OPENMW_UPSTREAM_REPO}
        GIT_TAG ${OPENMW_UPSTREAM_COMMIT}
        DOWNLOAD_DIR ${download_dir}
    )
    # A previous (possibly failed) patch run leaves the clone half-patched; start from a clean tree.
    set(OPENMW_UPSTREAM_PATCH_STEP
        COMMAND git -C <SOURCE_DIR> checkout -- .
        COMMAND git -C <SOURCE_DIR> clean -fdq
        ${OPENMW_UPSTREAM_PATCH_COMMANDS}
    )
endif()
# ExternalProject wants the first command without the COMMAND keyword
list(POP_FRONT OPENMW_UPSTREAM_PATCH_STEP)

# ARM's astcenc, compiled into the support library (support/, DXT to ASTC on GPUs without S3TC)
set(ASTCENC_VERSION 4.8.0)
set(ASTCENC_ARCHIVE ${download_dir}/astc-encoder-${ASTCENC_VERSION}.tar.gz)
if(NOT EXISTS ${ASTCENC_ARCHIVE})
    file(DOWNLOAD https://github.com/ARM-software/astc-encoder/archive/refs/tags/${ASTCENC_VERSION}.tar.gz
        ${ASTCENC_ARCHIVE}
        EXPECTED_HASH SHA256=6c12f4656be21a69cbacd9f2c817283405decb514072dc1dcf51fd9a0b659852)
endif()
set(ASTCENC_EXTRACT_DIR ${CMAKE_CURRENT_BINARY_DIR}/astcenc)
if(NOT EXISTS ${ASTCENC_EXTRACT_DIR}/astc-encoder-${ASTCENC_VERSION}/Source/astcenc.h)
    file(ARCHIVE_EXTRACT INPUT ${ASTCENC_ARCHIVE} DESTINATION ${ASTCENC_EXTRACT_DIR})
endif()

# wolfpld's etcpak (BSD), compiled into the support library: the fast DXT to ETC2 conversion option
set(ETCPAK_VERSION 2.1)
set(ETCPAK_ARCHIVE ${download_dir}/etcpak-${ETCPAK_VERSION}.tar.gz)
if(NOT EXISTS ${ETCPAK_ARCHIVE})
    file(DOWNLOAD https://github.com/wolfpld/etcpak/archive/refs/tags/${ETCPAK_VERSION}.tar.gz ${ETCPAK_ARCHIVE}
        EXPECTED_HASH SHA512=be64eb61a2a0119858ed4548b2ed187c5bad572317b82ebf1090b9ff91b62728a6aa086c6fabc6d6b43f08eb90ced92b4985c6b558a1afa5607c20668906dec3)
endif()
set(ETCPAK_EXTRACT_DIR ${CMAKE_CURRENT_BINARY_DIR}/etcpak)
if(NOT EXISTS ${ETCPAK_EXTRACT_DIR}/etcpak-${ETCPAK_VERSION}/ProcessRGB.cpp)
    file(ARCHIVE_EXTRACT INPUT ${ETCPAK_ARCHIVE} DESTINATION ${ETCPAK_EXTRACT_DIR})
endif()

# Microsoft's mimalloc (MIT): operator new of the engine, see support/src/allocator.cpp
set(MIMALLOC_VERSION 2.1.7)
set(MIMALLOC_ARCHIVE ${download_dir}/mimalloc-${MIMALLOC_VERSION}.tar.gz)
if(NOT EXISTS ${MIMALLOC_ARCHIVE})
    file(DOWNLOAD https://github.com/microsoft/mimalloc/archive/refs/tags/v${MIMALLOC_VERSION}.tar.gz ${MIMALLOC_ARCHIVE}
        EXPECTED_HASH SHA512=4e30976758015c76a146acc1bfc8501e2e5c61b81db77d253de0d58a8edef987669243f232210667b32ef8da3a33286642acb56ba526fd24c4ba925b44403730)
endif()
set(MIMALLOC_EXTRACT_DIR ${CMAKE_CURRENT_BINARY_DIR}/mimalloc)
if(NOT EXISTS ${MIMALLOC_EXTRACT_DIR}/mimalloc-${MIMALLOC_VERSION}/src/static.c)
    file(ARCHIVE_EXTRACT INPUT ${MIMALLOC_ARCHIVE} DESTINATION ${MIMALLOC_EXTRACT_DIR})
endif()

# Standalone Asio for the multiplayer sockets: downloaded here and handed to support/multiplayer's cmake/Asio.cmake as its source
# (FETCHCONTENT_SOURCE_DIR_ASIO), so the engine's own configure needs no network
set(ASIO_VERSION 1.30.2)
set(ASIO_ARCHIVE ${download_dir}/asio-${ASIO_VERSION}.tar.gz)
if(ALPHA3_MULTIPLAYER AND NOT EXISTS ${ASIO_ARCHIVE})
    file(DOWNLOAD https://github.com/chriskohlhoff/asio/archive/refs/tags/asio-1-30-2.tar.gz ${ASIO_ARCHIVE}
        EXPECTED_HASH SHA512=cfba1998d4f666156e751c4cab1e504441b368efbf344e22de30f8b983670a88d045d3ca82f197b95522a2026262274f93bc3061210781ce30c35c71a386ce6e)
endif()
set(ASIO_EXTRACT_DIR ${CMAKE_CURRENT_BINARY_DIR}/asio)
if(ALPHA3_MULTIPLAYER AND NOT EXISTS ${ASIO_EXTRACT_DIR}/asio-asio-1-30-2/asio/include/asio.hpp)
    file(ARCHIVE_EXTRACT INPUT ${ASIO_ARCHIVE} DESTINATION ${ASIO_EXTRACT_DIR})
endif()

set(OPENMW_UPSTREAM_COMMON
    -DFETCHCONTENT_SOURCE_DIR_ASIO=${ASIO_EXTRACT_DIR}/asio-asio-1-30-2
    # support/multiplayer is added to the engine's build (sources, Asio, the Lua mod in resources/vfs)
    -DALPHA3_MULTIPLAYER=${ALPHA3_MULTIPLAYER}
    -DASTCENC_SOURCE_DIR=${ASTCENC_EXTRACT_DIR}/astc-encoder-${ASTCENC_VERSION}/Source
    -DETCPAK_SOURCE_DIR=${ETCPAK_EXTRACT_DIR}/etcpak-${ETCPAK_VERSION}
    -DMIMALLOC_SOURCE_DIR=${MIMALLOC_EXTRACT_DIR}/mimalloc-${MIMALLOC_VERSION}
    # Alpha3's support library, added to the engine's build by patch 0004 (OSG RuntimeHooks and what the engine calls)
    -DALPHA3_SUPPORT_DIR=${CMAKE_CURRENT_SOURCE_DIR}/support
    -DBUILD_OPENMW=ON
    -DBUILD_BSATOOL=0
    -DBUILD_NIFTEST=0
    -DBUILD_ESMTOOL=0
    -DBUILD_LAUNCHER=0
    -DBUILD_MWINIIMPORTER=0
    -DBUILD_ESSIMPORTER=0
    -DBUILD_OPENCS=0
    -DBUILD_WIZARD=0
    -DBUILD_BULLETOBJECTTOOL=0
    -DOPENMW_USE_SYSTEM_SQLITE3=OFF
    -DOPENMW_USE_SYSTEM_YAML_CPP=OFF
    -DOPENMW_USE_SYSTEM_ICU=ON
    -DOPENAL_INCLUDE_DIR=${prefix}/include/AL/
    -DOSG_STATIC=TRUE
    # OSG_GLES_FIXED_FUNCTION_SHIMS: <osg/GL> maps the client array calls of the MyGUI renderer to vertex attributes
    "-DCMAKE_CXX_FLAGS=-std=gnu++20 -I${prefix}/include/ ${ALPHA3_SWAPPY_INCLUDE} ${VCPKG_CXX_FLAGS} -DOSG_GLES_FIXED_FUNCTION_SHIMS"
    -DMyGUI_LIBRARY=${prefix}/lib/libMyGUIEngineStatic.a
    -Dcollada_dom_DIR=${prefix}/share/collada-dom
    -DOPENGL_INCLUDE_DIR=${prefix}/include
    # LuaJIT, built above
    -DUSE_LUAJIT=ON
    -DLuaJit_INCLUDE_DIR=${luajit_prefix}/include/luajit-2.1
    -DLuaJit_LIBRARY=${luajit_prefix}/lib/libluajit-5.1.a
)
if(ALPHA3_IS_ANDROID)
    list(APPEND OPENMW_UPSTREAM_COMMON
        # libopenmw-navmeshtool.so: the app's navmesh baking loads it and calls its SDL_main
        -DBUILD_NAVMESHTOOL=1
        # GL comes from glad (built by the vcpkg step in the top-level CMakeLists), which loads the system GLES driver or
        # Zink's OSMesa at runtime, instead of linking libGLESv3 directly
        -DOPENGL_gl_LIBRARY=${prefix}/lib/libglad.so
        # -Bsymbolic-functions: calls between the engine's own functions (OSG and Bullet are linked in statically) are
        # bound at link time instead of going through the PLT, which showed up as 5% of the main thread
        "-DCMAKE_SHARED_LINKER_FLAGS=${VCPKG_LINKER_FLAGS} ${ALPHA3_EXTRA_LIBS} -Wl,-Bsymbolic-functions"
        "-DCMAKE_EXE_LINKER_FLAGS=${VCPKG_LINKER_FLAGS} ${ALPHA3_EXTRA_LIBS}"
    )
else()
    # iOS: glad is a static library that loads OpenGLES.framework's functions at runtime; the Apple frameworks SDL, OpenAL
    # and FFmpeg need are linked straight into libopenmw. vcpkg's static freetype pulls in brotli for WOFF2 fonts.
    set(OPENMW_UPSTREAM_IOS_FRAMEWORKS
        OpenGLES Metal QuartzCore IOSurface UIKit Foundation CoreGraphics CoreFoundation AudioToolbox AVFoundation
        CoreMedia CoreVideo VideoToolbox CoreAudio CoreHaptics GameController CoreMotion CoreBluetooth Security)
    set(OPENMW_UPSTREAM_IOS_LINK_FLAGS "${VCPKG_LINKER_FLAGS} -lc++ -lbrotlidec -lbrotlicommon")
    foreach(fw IN LISTS OPENMW_UPSTREAM_IOS_FRAMEWORKS)
        string(APPEND OPENMW_UPSTREAM_IOS_LINK_FLAGS " -framework ${fw}")
    endforeach()
    list(APPEND OPENMW_UPSTREAM_COMMON
        # no navmesh tool process on iOS: the engine builds the navmesh while it plays
        -DBUILD_NAVMESHTOOL=0
        -DOPENGL_gl_LIBRARY=${prefix}/lib/libglad.a
        -DBUILD_MYGUI_PLUGIN=0
        # cmake/CheckLuaCustomAllocator.cmake runs a test program, which an iOS build cannot (OpenMW skips it on Android).
        # LuaJIT on arm64 is GC64 by default, which is what the check is about: the allocator works.
        -DRUN_RESULT_VAR=0
        -DRUN_RESULT_VAR__TRYRUN_OUTPUT=
        "-DCMAKE_SHARED_LINKER_FLAGS=${OPENMW_UPSTREAM_IOS_LINK_FLAGS}"
        "-DCMAKE_EXE_LINKER_FLAGS=${OPENMW_UPSTREAM_IOS_LINK_FLAGS}"
    )
endif()

if(ALPHA3_IS_ANDROID)
    set(OPENMW_UPSTREAM_INSTALL
        INSTALL_COMMAND ${CMAKE_COMMAND} -E make_directory ${assets_upstream_dir}/openmw
        COMMAND ${CMAKE_COMMAND} -E remove_directory ${assets_upstream_dir}/resources
        COMMAND ${CMAKE_COMMAND} -E copy_directory resources ${assets_upstream_dir}/resources
        COMMAND ${CMAKE_COMMAND} -E copy defaults.bin gamecontrollerdb.txt ${assets_upstream_dir}/openmw/
        COMMAND ${CMAKE_COMMAND} -E copy libopenmw_upstream.so libopenmw-navmeshtool.so libalpha3_texconv.so ${jni_libs_dir}/
        COMMAND ${CMAKE_COMMAND} -E copy ${CMAKE_CURRENT_SOURCE_DIR}/settings.fallback.cfg ${assets_upstream_dir}/openmw/
        # upstream's own global config: it carries the fallback= values (FontColor_*, ...) that stock OpenMW reads from
        # openmw.cfg and aborts without; the fork's copy has none. Paths in it are fixed up at install time in the app.
        COMMAND ${CMAKE_COMMAND} -E copy openmw.cfg ${assets_upstream_dir}/openmw/
        COMMAND ${CMAKE_COMMAND} -E copy ${ALPHA3_REPO_DIR}/3rdparty-licenses.txt ${assets_upstream_dir}/
        COMMAND ${CMAKE_COMMAND} -E copy ${ALPHA3_REPO_DIR}/LICENSE ${ALPHA3_REPO_DIR}/THIRD_PARTY_NOTICES.md ${assets_upstream_dir}/
    )
else()
    # iOS: libopenmw.dylib (built into the macOS bundle layout, OpenMW.app/Contents/MacOS) becomes
    # iosApp/EmbeddedLibs*/libopenmw.framework, the data files go to iosApp/OpenMWAssets; the same names the fork's build
    # used, so the Xcode project and OpenMWLauncher.swift take either engine.
    set(ios_assets_dir "${ALPHA3_IOSAPP_DIR}/OpenMWAssets")
    alpha3_ios_framework(ios_openmw_framework libopenmw <BINARY_DIR>/OpenMW.app/Contents/MacOS/libopenmw.dylib)
    # openmw.cfg is the macOS one (resources=../Resources/resources); on iOS the resources sit next to it
    set(ios_cfg_fixup "${CMAKE_CURRENT_BINARY_DIR}/fix_openmw_cfg.cmake")
    file(WRITE ${ios_cfg_fixup} [=[
file(READ "${CFG}" content)
string(REPLACE "../Resources/resources" "resources" content "${content}")
file(WRITE "${CFG}" "${content}")
]=])
    set(OPENMW_UPSTREAM_INSTALL
        INSTALL_COMMAND ${CMAKE_COMMAND} -E make_directory ${ios_assets_dir}
        COMMAND ${CMAKE_COMMAND} -E remove_directory ${ios_assets_dir}/resources
        COMMAND ${CMAKE_COMMAND} -E copy_directory resources ${ios_assets_dir}/resources
        COMMAND ${CMAKE_COMMAND} -E copy defaults.bin gamecontrollerdb.txt openmw.cfg ${ios_assets_dir}/
        COMMAND ${CMAKE_COMMAND} -E copy ${CMAKE_CURRENT_SOURCE_DIR}/settings.fallback.cfg ${ios_assets_dir}/
        COMMAND ${CMAKE_COMMAND} -E copy ${ALPHA3_COMPOSEAPP_DIR}/settings.cfg ${ios_assets_dir}/
        # GPL text and third-party notices travel with the app
        COMMAND ${CMAKE_COMMAND} -E copy ${ALPHA3_REPO_DIR}/LICENSE ${ALPHA3_REPO_DIR}/THIRD_PARTY_NOTICES.md
                ${ALPHA3_REPO_DIR}/3rdparty-licenses.txt ${ios_assets_dir}/
        COMMAND ${CMAKE_COMMAND} -DCFG=${ios_assets_dir}/openmw.cfg -P ${ios_cfg_fixup}
        ${ios_openmw_framework}
    )
endif()

ExternalProject_Add(openmw_upstream
    DEPENDS vcpkg luajit

    ${OPENMW_UPSTREAM_SOURCE_ARGS}

    UPDATE_COMMAND ""
    BUILD_ALWAYS 1

    PATCH_COMMAND ${OPENMW_UPSTREAM_PATCH_STEP}

    CONFIGURE_COMMAND ${wrapper_command} cmake <SOURCE_DIR>
    ${VCPKG_CMAKE_CONFIGURE_OPTIONS}
    ${OPENMW_UPSTREAM_COMMON}
    BUILD_COMMAND ${wrapper_command} make -j${NPROC}

    ${OPENMW_UPSTREAM_INSTALL}

    USES_TERMINAL_BUILD ON
    USES_TERMINAL_INSTALL ON
)
# The patch step's command line names the patch files, not their contents: an edited patch (or multiplayer file) re-runs
# it only through this
ExternalProject_Add_StepDependencies(openmw_upstream patch ${OPENMW_UPSTREAM_PATCH_DEPENDS})
