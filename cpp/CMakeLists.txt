# =============================================================================
# Alpha3 — unified Android + iOS build
#
# Dependencies come from vcpkg for both platforms:
#   * official vcpkg (cloned and bootstrapped by ExternalProject)
#   * https://github.com/OpenMW/openmw-deps-build as an overlay (its ports/ and
#     triplets/ dirs), for OpenMW's custom ports (openmw-osg, bullet3 features, ...)
#   * your own vcpkg-extras/{ports,triplets,vcpkg.json}
#
# Overlay precedence (vcpkg: FIRST match wins):
#   vcpkg-extras/ports  >  openmw-deps-build/ports  >  official vcpkg ports
#
# Repo layout (this file lives in <repo>/cpp):
#   androidApp/   Android app; gets jniLibs/ and assets/ filled by this build
#   composeApp/   KMP launcher (iOS + Android); Gradle drives this file for iOS
#   iosApp/       Xcode project; gets EmbeddedLibs*/ and OpenMWAssets/ filled by this build
#
# Usage
#   Android : :androidApp Gradle externalNativeBuild with the NDK toolchain
#   iOS     : ./gradlew :composeApp:buildNativeIos   (macOS), or by hand:
#             cmake -S cpp -B build-ios -DCMAKE_SYSTEM_NAME=iOS -DCMAKE_OSX_SYSROOT=iphoneos
#   iOS sim : same with -DCMAKE_OSX_SYSROOT=iphonesimulator
#   Deps only (old iOS script behaviour, no compilers needed, host = macOS):
#             cmake -S . -B build-deps -DALPHA3_DEPS_ONLY=ON -DIOS_PLATFORM=OS64
#             (IOS_PLATFORM = OS64 | SIMULATORARM64, same names as before)
# =============================================================================
cmake_minimum_required(VERSION 3.22.1)

# App directories this build writes into (see the layout above)
get_filename_component(ALPHA3_REPO_DIR "${CMAKE_CURRENT_SOURCE_DIR}/.." ABSOLUTE)
set(ALPHA3_ANDROIDAPP_DIR "${ALPHA3_REPO_DIR}/androidApp")
set(ALPHA3_COMPOSEAPP_DIR "${ALPHA3_REPO_DIR}/composeApp")
set(ALPHA3_IOSAPP_DIR "${ALPHA3_REPO_DIR}/iosApp")

# Set to TRUE to also build the iOS simulator libraries (iosApp/EmbeddedLibsSim)
# whenever the device libraries are built. Plain set() so editing it here always applies.
set(ALPHA3_ALSO_BUILD_SIMULATOR FALSE)

# ------------------------------- user options --------------------------------
set(ALPHA3_PLATFORM "" CACHE STRING
        "Target platform: android | ios | ios-simulator (auto-detected when empty)")
option(ALPHA3_DEPS_ONLY "Only build the vcpkg dependency tree, no app targets" OFF)
option(ALPHA3_OPENMW_UPSTREAM "Android: also build stock OpenMW (gitlab.com/OpenMW/openmw, openmw-upstream/) next to the fork" ON)
option(ALPHA3_OPENMW_FORK "Android: build the clockwork-alpha fork (cpp/openmw, libopenmw.so). OFF ships only stock OpenMW; iOS always builds the fork" ON)
option(ALPHA3_USE_OPENMW_DEPS "Use OpenMW/openmw-deps-build as a vcpkg overlay" ON)
set(ALPHA3_OPENMW_DEPS_TAG "master" CACHE STRING
        "openmw-deps-build git ref (pin to a commit for reproducible builds)")
set(ALPHA3_OPENMW_DEPS_PORTS "ALL" CACHE STRING
        "Which openmw-deps-build ports to overlay: ALL, or a ;-list such as openmw-osg;bullet3")
set(ALPHA3_VCPKG_TAG "master" CACHE STRING
        "Official vcpkg git ref (pin to a release tag or commit for reproducible builds)")
set(ALPHA3_VCPKG_HOST_TRIPLET "" CACHE STRING
        "Optional --host-triplet (empty = let vcpkg detect, e.g. arm64-osx / x64-linux)")
set(ALPHA3_IOS_DEPLOYMENT_TARGET "16.4" CACHE STRING "Minimum iOS version")
set(ALPHA3_IOS_LIBRARY_LINKAGE "static" CACHE STRING "iOS vcpkg libraries: static | dynamic")

if(ALPHA3_DEPS_ONLY)
    project(Alpha3 LANGUAGES NONE)
else()
    project(Alpha3 LANGUAGES C CXX)
endif()

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(ExternalProject)
include(ProcessorCount)
include(FetchContent)
ProcessorCount(NPROC)
if(NPROC EQUAL 0)
    set(NPROC 2)  # Fallback to 2 cores if detection fails
endif()

# --------------------------- platform detection ------------------------------
if(NOT ALPHA3_PLATFORM)
    if(ANDROID)
        set(ALPHA3_PLATFORM android)
    elseif(IOS_PLATFORM STREQUAL "SIMULATORARM64")
        set(ALPHA3_PLATFORM ios-simulator)
    elseif(IOS_PLATFORM STREQUAL "OS64")
        set(ALPHA3_PLATFORM ios)
    elseif(CMAKE_SYSTEM_NAME STREQUAL "iOS")
        if("${CMAKE_OSX_SYSROOT}" MATCHES "[Ss]imulator")
            set(ALPHA3_PLATFORM ios-simulator)
        else()
            set(ALPHA3_PLATFORM ios)
        endif()
    else()
        message(FATAL_ERROR
                "Cannot detect the target platform. Use the Android NDK toolchain, "
                "-DCMAKE_SYSTEM_NAME=iOS, -DIOS_PLATFORM=OS64|SIMULATORARM64, "
                "or set -DALPHA3_PLATFORM=android|ios|ios-simulator.")
    endif()
endif()
if(NOT ALPHA3_PLATFORM MATCHES "^(android|ios|ios-simulator)$")
    message(FATAL_ERROR "ALPHA3_PLATFORM must be android, ios or ios-simulator (got '${ALPHA3_PLATFORM}')")
endif()
if(ALPHA3_PLATFORM STREQUAL "android")
    set(ALPHA3_IS_ANDROID TRUE)
    set(ALPHA3_IS_IOS FALSE)
else()
    set(ALPHA3_IS_ANDROID FALSE)
    set(ALPHA3_IS_IOS TRUE)
endif()
message(STATUS "Alpha3 platform: ${ALPHA3_PLATFORM}")

set(ARCH "arm64")

# Host-side helpers shared by both platforms
if(CMAKE_HOST_WIN32)
    set(BOOTSTRAP_SCRIPT "bootstrap-vcpkg.bat")
    set(VCPKG_EXE "vcpkg.exe")
else()
    set(BOOTSTRAP_SCRIPT "./bootstrap-vcpkg.sh")
    set(VCPKG_EXE "./vcpkg")
endif()

find_program(CCACHE_FOUND ccache)
if(CCACHE_FOUND)
    message(STATUS "CCACHE found and is being used.")
    set(CC_WRAPPER "ccache ")
else()
    message(STATUS "CCACHE not found.")
    set(CC_WRAPPER "")
endif()

set(VCPKG_ROOT_DIR "${CMAKE_BINARY_DIR}/vcpkg-prefix/src/vcpkg")
file(TO_NATIVE_PATH "${VCPKG_ROOT_DIR}" VCPKG_ROOT_DIR_NATIVE)
set(download_dir ${CMAKE_BINARY_DIR}/downloads/)

# =============================================================================
# Platform-specific toolchain / flags / triplet
# Variable names are kept from the two original files so existing
# add_subdirectory() projects keep working.
# =============================================================================
if(ALPHA3_IS_ANDROID)
    # ------------------------------- Android ---------------------------------
    set(NDK_TRIPLET "aarch64-linux-android")
    set(FFMPEG_CPU "armv8-a")
    set(VCPKG_TRIPLET "arm64-android")

    if(NOT DEFINED ANDROID_API OR ANDROID_API LESS 28)
        set(ANDROID_API 28)
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

    set(NDK_TOOLCHAIN_BIN "${CMAKE_ANDROID_NDK}/toolchains/llvm/prebuilt/${ANDROID_HOST_TAG}/bin")
    set(VCPKG_CHAINLOAD_TOOLCHAIN_FILE "$ENV{ANDROID_NDK_HOME}/build/cmake/android.toolchain.cmake")

    # No stack protector or fortify checks: the NDK adds both by default and they cost a little on every call and copy
    # in the render and cull loops (this is a game, not a network service)
    set(VCPKG_C_FLAGS "-w -fPIC -O3 -fno-stack-protector -U_FORTIFY_SOURCE")
    set(VCPKG_CXX_FLAGS "-w -fPIC -O3 -frtti -fexceptions -fno-stack-protector -U_FORTIFY_SOURCE")

    set(prefix "${VCPKG_ROOT_DIR}/vcpkg_installed/${VCPKG_TRIPLET}/")
    set(VCPKG_LINKER_FLAGS "-w -fPIC -Wl,--undefined-version -fuse-ld=lld -L${prefix}/lib")
    # vcpkg's freetype is built with WOFF2 support; its static archive needs brotli, and ffmpeg MediaCodec needs mediandk
    set(ALPHA3_EXTRA_LIBS "-lbrotlidec -lbrotlicommon -lmediandk")

    # Android Frame Pacing (Swappy, Apache 2.0): the static library and headers come out of Google's AAR. The same
    # version is the Gradle dependency that brings its Java half (androidApp/build.gradle.kts).
    set(ALPHA3_SWAPPY_VERSION 2.1.3)
    set(ALPHA3_SWAPPY_AAR "${CMAKE_BINARY_DIR}/swappy/games-frame-pacing-${ALPHA3_SWAPPY_VERSION}.aar")
    if(NOT EXISTS "${ALPHA3_SWAPPY_AAR}")
        file(DOWNLOAD
            "https://dl.google.com/dl/android/maven2/androidx/games/games-frame-pacing/${ALPHA3_SWAPPY_VERSION}/games-frame-pacing-${ALPHA3_SWAPPY_VERSION}.aar"
            "${ALPHA3_SWAPPY_AAR}"
            EXPECTED_HASH SHA256=a9f83e0cf47d694ed2cea0ae995d5cf7411060d9d4b680610430d03d6b20ea27
            STATUS _swappy_status)
        list(GET _swappy_status 0 _swappy_code)
        if(NOT _swappy_code EQUAL 0)
            file(REMOVE "${ALPHA3_SWAPPY_AAR}")
            message(FATAL_ERROR "Could not download Swappy: ${_swappy_status}")
        endif()
    endif()
    file(ARCHIVE_EXTRACT INPUT "${ALPHA3_SWAPPY_AAR}" DESTINATION "${CMAKE_BINARY_DIR}/swappy/aar")
    set(ALPHA3_SWAPPY_DIR "${CMAKE_BINARY_DIR}/swappy/aar/prefab/modules/swappy_static")
    set(ALPHA3_SWAPPY_INCLUDE "-I${ALPHA3_SWAPPY_DIR}/include")
    set(ALPHA3_EXTRA_LIBS "${ALPHA3_EXTRA_LIBS} ${ALPHA3_SWAPPY_DIR}/libs/android.${ANDROID_ABI}/libswappy_static.a -landroid -lEGL")

    set(ndk_triplet ${NDK_TRIPLET})
    set(host ${ndk_triplet})
    set(android_platform android-${ANDROID_API})
    set(app_abi ${ANDROID_ABI})
    set(jni_libs_dir "${ALPHA3_ANDROIDAPP_DIR}/src/main/jniLibs/${ANDROID_ABI}")
    set(assets_dir "${ALPHA3_ANDROIDAPP_DIR}/src/main/assets")
    set(PROJECT_ROOT "${ALPHA3_ANDROIDAPP_DIR}")
    message(STATUS "PROJECT_ROOT: ${PROJECT_ROOT}")

    file(MAKE_DIRECTORY "${jni_libs_dir}")
    file(MAKE_DIRECTORY "${assets_dir}")

    if(CMAKE_HOST_WIN32)
        set(PATH_SEPARATOR "\\;")
        string(REPLACE ";" "\\;" ESCAPED_PATH "$ENV{PATH}")
        set(ENV_PATH_VAR "PATH=${NDK_TOOLCHAIN_BIN}\\;${ESCAPED_PATH}")
    else()
        set(PATH_SEPARATOR ":")
        set(ENV_PATH_VAR "PATH=${NDK_TOOLCHAIN_BIN}:$ENV{PATH}")
    endif()

    set(VCPKG_CMAKE_CONFIGURE_OPTIONS
            -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
            -DANDROID_ABI=${app_abi}
            -DANDROID_PLATFORM=${android_platform}
            -DANDROID_STL=c++_shared
            -DANDROID_LD=deprecated
            -DANDROID_CPP_FEATURES=
            -DCMAKE_C_FLAGS=-I${prefix}/include/\ ${VCPKG_C_FLAGS}
            -DCMAKE_CXX_FLAGS=-I${prefix}/include/\ ${VCPKG_CXX_FLAGS}
            -DCMAKE_SHARED_LINKER_FLAGS=${VCPKG_LINKER_FLAGS}
            -DCMAKE_BUILD_TYPE=release
            -DCMAKE_DEBUG_POSTFIX=
            -DCMAKE_INSTALL_PREFIX=${prefix}
            -DCMAKE_FIND_ROOT_PATH=${prefix}
            -DCMAKE_PREFIX_PATH=${prefix}
    )

    # Environment for autotools-style sub-builds (used by add_subdirectory projects)
    set(wrapper_command
            ${CMAKE_COMMAND} -E env
            "${ENV_PATH_VAR}"
            "CC=${CC_WRAPPER}${NDK_TRIPLET}${ANDROID_API}-clang"
            "CXX=${CC_WRAPPER}${NDK_TRIPLET}${ANDROID_API}-clang++"
            "CFLAGS=${VCPKG_C_FLAGS}"
            "CXXFLAGS=${VCPKG_CXX_FLAGS}"
            "LDFLAGS=${VCPKG_LINKER_FLAGS}"
            "AR=llvm-ar"
            "RANLIB=llvm-ranlib"
            "STRIP=llvm-strip"
            "PKG_CONFIG_LIBDIR=${prefix}/lib/pkgconfig${PATH_SEPARATOR}${prefix}/share/pkgconfig"
            "PKG_CONFIG_PATH=${prefix}/lib/pkgconfig${PATH_SEPARATOR}${prefix}/share/pkgconfig"
    )

    # Environment for the vcpkg install step
    set(ALPHA3_VCPKG_ENV
            "VCPKG_FORCE_SYSTEM_BINARIES=1"
            "ANDROID_NDK_HOME=${CMAKE_ANDROID_NDK}"
            "${ENV_PATH_VAR}"
    )
else()
    # --------------------------------- iOS -----------------------------------
    if(NOT CMAKE_HOST_APPLE)
        message(FATAL_ERROR "iOS dependencies can only be built on a macOS host (Xcode required).")
    endif()

    set(DEPLOYMENT_TARGET "${ALPHA3_IOS_DEPLOYMENT_TARGET}")
    if(ALPHA3_PLATFORM STREQUAL "ios")
        set(IOS_PLATFORM "OS64")
        set(SDK_NAME "iphoneos")
        set(MIN_VERSION_FLAG "-miphoneos-version-min=${DEPLOYMENT_TARGET}")
        set(VCPKG_TRIPLET "arm64-ios")
    else()
        set(IOS_PLATFORM "SIMULATORARM64")
        set(SDK_NAME "iphonesimulator")
        set(MIN_VERSION_FLAG "-mios-simulator-version-min=${DEPLOYMENT_TARGET}")
        set(VCPKG_TRIPLET "arm64-ios-simulator")
    endif()

    foreach(tool IN ITEMS "--show-sdk-path:IOS_SDK_PATH" "-f clang:IOS_CLANG" "-f clang++:IOS_CLANGXX"
            "-f ar:IOS_AR" "-f nm:IOS_NM" "-f ranlib:IOS_RANLIB")
        string(REPLACE ":" ";" tool_pair "${tool}")
        list(GET tool_pair 0 tool_args)
        list(GET tool_pair 1 tool_var)
        separate_arguments(tool_args UNIX_COMMAND "${tool_args}")
        execute_process(COMMAND xcrun --sdk ${SDK_NAME} ${tool_args}
                OUTPUT_VARIABLE ${tool_var}
                OUTPUT_STRIP_TRAILING_WHITESPACE
                RESULT_VARIABLE xcrun_rc)
        if(NOT xcrun_rc EQUAL 0 OR "${${tool_var}}" STREQUAL "")
            message(FATAL_ERROR "xcrun --sdk ${SDK_NAME} ${tool_args} failed; is Xcode installed and selected?")
        endif()
    endforeach()

    set(prefix "${VCPKG_ROOT_DIR}/vcpkg_installed/${VCPKG_TRIPLET}/")
    set(COMMON_FLAGS "-O3 -fPIC -stdlib=libc++ -w")
    set(VCPKG_C_FLAGS "${COMMON_FLAGS} -isysroot ${IOS_SDK_PATH} -arch ${ARCH} ${MIN_VERSION_FLAG}")
    set(VCPKG_CXX_FLAGS "${VCPKG_C_FLAGS} -frtti -fexceptions")
    set(VCPKG_LINKER_FLAGS "-isysroot ${IOS_SDK_PATH} -arch ${ARCH} -L${prefix}/lib")

    set(CONFIGURE_CFLAGS "${VCPKG_C_FLAGS}")
    set(CONFIGURE_CPPFLAGS "-isysroot ${IOS_SDK_PATH} -I${prefix}/include")
    set(CONFIGURE_LDFLAGS "${VCPKG_LINKER_FLAGS}")
    set(CONFIGURE_ENV
            "CC=${CC_WRAPPER}${IOS_CLANG}"
            "CXX=${CC_WRAPPER}${IOS_CLANGXX}"
            "AR=${IOS_AR}"
            "NM=${IOS_NM}"
            "RANLIB=${IOS_RANLIB}"
            "CFLAGS=${CONFIGURE_CFLAGS}"
            "CPPFLAGS=${CONFIGURE_CPPFLAGS}"
            "LDFLAGS=${CONFIGURE_LDFLAGS}"
            "PKG_CONFIG_LIBDIR=${prefix}/lib/pkgconfig:${prefix}/share/pkgconfig"
            "PKG_CONFIG_PATH=${prefix}/lib/pkgconfig:${prefix}/share/pkgconfig"
    )
    set(wrapper_command ${CMAKE_COMMAND} -E env ${CONFIGURE_ENV})   # same name as on Android
    set(ENV_CMAKE ${CMAKE_COMMAND} -E env ${CONFIGURE_ENV} ${CMAKE_COMMAND})

    set(GLES_INCLUDE "${IOS_SDK_PATH}/System/Library/Frameworks")
    set(GLES_LIBRARY "${IOS_SDK_PATH}/System/Library/Frameworks/OpenGLES.framework")

    # Replaces the old ios-cmake toolchain: CMake supports iOS natively.
    set(VCPKG_CMAKE_CONFIGURE_OPTIONS
            -G "Unix Makefiles"
            -DCMAKE_SYSTEM_NAME=iOS
            -DCMAKE_OSX_SYSROOT=${SDK_NAME}
            -DCMAKE_OSX_ARCHITECTURES=${ARCH}
            -DCMAKE_OSX_DEPLOYMENT_TARGET=${DEPLOYMENT_TARGET}
            -DCMAKE_INSTALL_PREFIX=${prefix}
            -DCMAKE_BUILD_TYPE=Release
            -DCMAKE_C_FLAGS=${COMMON_FLAGS}
            -DCMAKE_CXX_FLAGS=${COMMON_FLAGS}
            -DCMAKE_FIND_ROOT_PATH=${prefix}
            -DCMAKE_PREFIX_PATH=${prefix}
            -DCMAKE_FIND_ROOT_PATH_MODE_PROGRAM=NEVER
            -DCMAKE_FIND_ROOT_PATH_MODE_LIBRARY=ONLY
            -DCMAKE_FIND_ROOT_PATH_MODE_INCLUDE=ONLY
            -DCMAKE_FIND_ROOT_PATH_MODE_PACKAGE=ONLY
            -DOPENGL_INCLUDE_DIR=${GLES_INCLUDE}
            -DOPENGL_gl_LIBRARY=${GLES_LIBRARY}
            -DOPENGL_glx_LIBRARY=${GLES_LIBRARY}
            -Wno-author
            -Wno-deprecated
    )

    # Adjust if your iOS layout differs from the Android one
    set(PROJECT_ROOT "${CMAKE_SOURCE_DIR}")
    set(ALPHA3_VCPKG_ENV "")
endif()

if(CCACHE_FOUND)
    list(APPEND VCPKG_CMAKE_CONFIGURE_OPTIONS
            -DCMAKE_C_COMPILER_LAUNCHER=ccache
            -DCMAKE_CXX_COMPILER_LAUNCHER=ccache
    )
endif()

# Sub-projects (openmw, dethrace, ...) pass this to their own cmake invocations
set(COMMON_CMAKE_ARGS ${VCPKG_CMAKE_CONFIGURE_OPTIONS})

# =============================================================================
# MoltenVK (iOS Vulkan implementation), built from source with a small patch
# (patches/moltenvk-tier2-sampler-limit.patch): stock MoltenVK hardcodes
# maxPerStageDescriptorSamplers to 16, below what OpenMW's Vulkan renderer requires (47).
# The patch is for device builds only; the simulator slice is built without it.
# The build happens once at configure time and is reused while the lib exists.
# The static lib and the headers are copied into the vcpkg prefix (lib/, include/) after
# the vcpkg install.
# =============================================================================
if(ALPHA3_IS_IOS)
    # Plain set() (not CACHE) so changing the tag here always applies to existing build dirs
    set(ALPHA3_MOLTENVK_TAG "v1.4.2")
    set(ALPHA3_MOLTENVK_PATCH "${CMAKE_CURRENT_SOURCE_DIR}/patches/moltenvk-tier2-sampler-limit.patch")
    # Shared by the device and simulator build dirs
    get_filename_component(MOLTENVK_DIR "${CMAKE_BINARY_DIR}/../moltenvk/src" ABSOLUTE)

    if(ALPHA3_PLATFORM STREQUAL "ios")
        set(_mvk_slice "ios-arm64")
        set(_mvk_make_target "ios")
    else()
        set(_mvk_slice "ios-arm64_x86_64-simulator")
        set(_mvk_make_target "iossim")
    endif()
    set(_mvk_built_lib "${MOLTENVK_DIR}/Package/Release/MoltenVK/static/MoltenVK.xcframework/${_mvk_slice}/libMoltenVK.a")

    # The patch raises limits that only a device GPU with Tier 2 argument buffers can honor, so only the device
    # slice is built with it; the simulator slice is built from the unpatched sources. The source tree is
    # shared, so the patch is applied or reversed to match the slice before each build. A stamp records what
    # the simulator library was built from (device libraries from before the stamp existed were patched, which
    # is right for them; simulator ones were not stamped and are rebuilt once).
    set(_mvk_rebuild FALSE)
    if(NOT ALPHA3_PLATFORM STREQUAL "ios")
        set(_mvk_stamp "${MOLTENVK_DIR}/Package/.alpha3-${_mvk_slice}-unpatched")
        if(NOT EXISTS "${_mvk_stamp}")
            set(_mvk_rebuild TRUE)
        endif()
    endif()

    if(NOT EXISTS "${_mvk_built_lib}" OR _mvk_rebuild)
        find_program(ALPHA3_GIT_EXE git REQUIRED)
        if(NOT EXISTS "${MOLTENVK_DIR}/.git")
            message(STATUS "MoltenVK: cloning ${ALPHA3_MOLTENVK_TAG}")
            execute_process(COMMAND ${ALPHA3_GIT_EXE} clone --depth 1 --branch ${ALPHA3_MOLTENVK_TAG}
                    https://github.com/KhronosGroup/MoltenVK.git "${MOLTENVK_DIR}"
                    RESULT_VARIABLE _rc)
            if(NOT _rc EQUAL 0)
                message(FATAL_ERROR "MoltenVK: git clone failed")
            endif()
        endif()
        # Device: the patch must be applied. Simulator: it must not be.
        execute_process(COMMAND ${ALPHA3_GIT_EXE} apply --check --reverse "${ALPHA3_MOLTENVK_PATCH}"
                WORKING_DIRECTORY "${MOLTENVK_DIR}" RESULT_VARIABLE _mvk_is_patched OUTPUT_QUIET ERROR_QUIET)
        if(ALPHA3_PLATFORM STREQUAL "ios")
            if(NOT _mvk_is_patched EQUAL 0)
                execute_process(COMMAND ${ALPHA3_GIT_EXE} apply "${ALPHA3_MOLTENVK_PATCH}"
                        WORKING_DIRECTORY "${MOLTENVK_DIR}" RESULT_VARIABLE _rc)
                if(NOT _rc EQUAL 0)
                    message(FATAL_ERROR "MoltenVK: could not apply ${ALPHA3_MOLTENVK_PATCH}")
                endif()
            endif()
        else()
            if(_mvk_is_patched EQUAL 0)
                message(STATUS "MoltenVK: reversing the device patch for the simulator build")
                execute_process(COMMAND ${ALPHA3_GIT_EXE} apply --reverse "${ALPHA3_MOLTENVK_PATCH}"
                        WORKING_DIRECTORY "${MOLTENVK_DIR}" RESULT_VARIABLE _rc)
                if(NOT _rc EQUAL 0)
                    message(FATAL_ERROR "MoltenVK: could not reverse ${ALPHA3_MOLTENVK_PATCH}")
                endif()
            endif()
        endif()
        # Device and simulator builds share External/build; fetch both slices at once so
        # switching between them never needs a refetch (and never overwrites the other).
        if(NOT EXISTS "${MOLTENVK_DIR}/External/build/Latest/SPIRVTools.xcframework/ios-arm64"
                OR NOT EXISTS "${MOLTENVK_DIR}/External/build/Latest/SPIRVTools.xcframework/ios-arm64_x86_64-simulator")
            message(STATUS "MoltenVK: fetching dependencies --ios --iossim (slow, first time only)")
            execute_process(COMMAND ./fetchDependencies --ios --iossim
                    WORKING_DIRECTORY "${MOLTENVK_DIR}" RESULT_VARIABLE _rc)
            if(NOT _rc EQUAL 0)
                message(FATAL_ERROR "MoltenVK: fetchDependencies failed")
            endif()
        endif()
        message(STATUS "MoltenVK: building (make ${_mvk_make_target})")
        execute_process(COMMAND make ${_mvk_make_target}
                WORKING_DIRECTORY "${MOLTENVK_DIR}" RESULT_VARIABLE _rc)
        if(NOT _rc EQUAL 0)
            message(FATAL_ERROR "MoltenVK: make ${_mvk_make_target} failed")
        endif()
        if(NOT ALPHA3_PLATFORM STREQUAL "ios")
            file(WRITE "${_mvk_stamp}" "built without ${ALPHA3_MOLTENVK_PATCH}\n")
        endif()
    endif()

    file(GLOB_RECURSE _mvk_libs "${MOLTENVK_DIR}/Package/Release/MoltenVK/static/MoltenVK.xcframework/${_mvk_slice}/libMoltenVK.a")
    file(GLOB_RECURSE _mvk_headers "${MOLTENVK_DIR}/Package/Release/MoltenVK/include/MoltenVK/mvk_vulkan.h")
    if(NOT _mvk_libs OR NOT _mvk_headers)
        message(FATAL_ERROR "No MoltenVK ${_mvk_slice} slice or headers under ${MOLTENVK_DIR}")
    endif()
    list(GET _mvk_libs 0 _mvk_src_lib)
    list(GET _mvk_headers 0 _mvk_header)
    get_filename_component(_mvk_src_include "${_mvk_header}" DIRECTORY)
    get_filename_component(_mvk_src_include "${_mvk_src_include}/.." ABSOLUTE)

    # Where they end up (installed by the vcpkg moltenvk step below)
    set(MOLTENVK_LIBRARY "${VCPKG_ROOT_DIR}/vcpkg_installed/${VCPKG_TRIPLET}/lib/libMoltenVK.a")
    set(MOLTENVK_INCLUDE_DIR "${VCPKG_ROOT_DIR}/vcpkg_installed/${VCPKG_TRIPLET}/include")
    message(STATUS "MoltenVK lib  : ${_mvk_src_lib} -> ${MOLTENVK_LIBRARY}")
endif()

# =============================================================================
# vcpkg overlays
# =============================================================================
set(ALPHA3_VCPKG_MANIFEST_ROOT "${CMAKE_CURRENT_SOURCE_DIR}/vcpkg-extras")
if(NOT EXISTS "${ALPHA3_VCPKG_MANIFEST_ROOT}/vcpkg.json")
    message(FATAL_ERROR "Missing ${ALPHA3_VCPKG_MANIFEST_ROOT}/vcpkg.json (the vcpkg manifest)")
endif()

# 1) your own overrides, highest priority
set(ALPHA3_OVERLAY_PORTS "${ALPHA3_VCPKG_MANIFEST_ROOT}/ports")
set(ALPHA3_OVERLAY_TRIPLETS "${ALPHA3_VCPKG_MANIFEST_ROOT}/triplets")

# 2) OpenMW's custom ports/triplets
if(ALPHA3_USE_OPENMW_DEPS)
    set(FETCHCONTENT_UPDATES_DISCONNECTED_OPENMW_DEPS_BUILD ON)  # no network on every re-configure
    FetchContent_Declare(openmw_deps_build
            GIT_REPOSITORY https://github.com/OpenMW/openmw-deps-build.git
            GIT_TAG ${ALPHA3_OPENMW_DEPS_TAG}
    )
    FetchContent_MakeAvailable(openmw_deps_build)
    set(OPENMW_DEPS_DIR "${openmw_deps_build_SOURCE_DIR}")

    if(ALPHA3_OPENMW_DEPS_PORTS STREQUAL "ALL")
        list(APPEND ALPHA3_OVERLAY_PORTS "${OPENMW_DEPS_DIR}/ports")
    else()
        foreach(port IN LISTS ALPHA3_OPENMW_DEPS_PORTS)
            if(NOT EXISTS "${OPENMW_DEPS_DIR}/ports/${port}/portfile.cmake")
                message(FATAL_ERROR "openmw-deps-build has no port '${port}' (checked ${OPENMW_DEPS_DIR}/ports)")
            endif()
            list(APPEND ALPHA3_OVERLAY_PORTS "${OPENMW_DEPS_DIR}/ports/${port}")
        endforeach()
    endif()
    list(APPEND ALPHA3_OVERLAY_TRIPLETS "${OPENMW_DEPS_DIR}/triplets")
endif()

set(ALPHA3_VCPKG_OVERLAY_ARGS "")
foreach(dir IN LISTS ALPHA3_OVERLAY_PORTS)
    list(APPEND ALPHA3_VCPKG_OVERLAY_ARGS "--overlay-ports=${dir}")
endforeach()
foreach(dir IN LISTS ALPHA3_OVERLAY_TRIPLETS)
    list(APPEND ALPHA3_VCPKG_OVERLAY_ARGS "--overlay-triplets=${dir}")
endforeach()

set(ALPHA3_VCPKG_HOST_ARGS "")
if(ALPHA3_VCPKG_HOST_TRIPLET)
    set(ALPHA3_VCPKG_HOST_ARGS "--host-triplet=${ALPHA3_VCPKG_HOST_TRIPLET}")
endif()

# =============================================================================
# vcpkg (official) — bootstrap and install the manifest for this platform
# =============================================================================
list(APPEND ALPHA3_VCPKG_ENV
        "VCPKG_ROOT=${VCPKG_ROOT_DIR_NATIVE}"
        "VCPKG_DISABLE_METRICS=1"
)
if(DEFINED ENV{VCPKG_DEFAULT_BINARY_CACHE})
    list(APPEND ALPHA3_VCPKG_ENV "VCPKG_DEFAULT_BINARY_CACHE=$ENV{VCPKG_DEFAULT_BINARY_CACHE}")
endif()

ExternalProject_Add(vcpkg
        GIT_REPOSITORY https://github.com/microsoft/vcpkg.git
        GIT_TAG ${ALPHA3_VCPKG_TAG}
        GIT_SUBMODULES_RECURSE ON
        GIT_PROGRESS ON
        CONFIGURE_COMMAND ""
        BUILD_COMMAND ${BOOTSTRAP_SCRIPT} -disableMetrics

        INSTALL_COMMAND ${CMAKE_COMMAND} -E env ${ALPHA3_VCPKG_ENV}
        ${VCPKG_EXE} install
        "--triplet=${VCPKG_TRIPLET}"
        ${ALPHA3_VCPKG_HOST_ARGS}
        ${ALPHA3_VCPKG_OVERLAY_ARGS}
        "--x-manifest-root=${ALPHA3_VCPKG_MANIFEST_ROOT}"
        "--x-install-root=${VCPKG_ROOT_DIR}/vcpkg_installed"

        BUILD_IN_SOURCE 1
        USES_TERMINAL_BUILD ON
        USES_TERMINAL_INSTALL ON
)

# Android: build GLAD and install it into the vcpkg prefix before vcpkg builds any port, so that
# openmw-osg (android/glad.patch) finds glad/gles2.h and libglad in ${VCPKG_INSTALLED_DIR}/${VCPKG_TARGET_TRIPLET}.
# Runs after the vcpkg clone/bootstrap (a re-clone would wipe the prefix) and before `vcpkg install`.
if(ALPHA3_IS_ANDROID)
    set(ALPHA3_GLAD_BUILD_DIR "${CMAKE_BINARY_DIR}/glad")
    ExternalProject_Add_Step(vcpkg glad
            COMMAND ${CMAKE_COMMAND} -S ${CMAKE_CURRENT_SOURCE_DIR}/GLAD -B ${ALPHA3_GLAD_BUILD_DIR}
                    -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
                    -DANDROID_ABI=${app_abi}
                    -DANDROID_PLATFORM=${android_platform}
                    -DCMAKE_BUILD_TYPE=Release
                    -DCMAKE_INSTALL_PREFIX=${prefix}
            COMMAND ${CMAKE_COMMAND} --build ${ALPHA3_GLAD_BUILD_DIR}
            COMMAND ${CMAKE_COMMAND} --install ${ALPHA3_GLAD_BUILD_DIR}
            DEPENDEES build
            DEPENDERS install
            ALWAYS TRUE
            COMMENT "Building GLAD into ${prefix}"
    )
endif()

# Android: ship the shared libraries inside the APK
if(ALPHA3_IS_ANDROID)
    set(ALPHA3_ANDROID_JNI_LIBS
            ${prefix}/lib/libcollada-dom2.5-dp.so
            ${prefix}/lib/libSDL2.so
            ${prefix}/lib/libopenal.so
            ${prefix}/lib/libglad.so
    )
    ExternalProject_Add_Step(vcpkg android_jni_libs
            COMMAND ${CMAKE_COMMAND} -E copy_if_different ${ALPHA3_ANDROID_JNI_LIBS} ${jni_libs_dir}/
            DEPENDEES install
    )
endif()

# iOS: wrap the dynamic vcpkg libraries into .framework bundles inside iosApp
if(ALPHA3_IS_IOS)
    if(ALPHA3_PLATFORM STREQUAL "ios")
        set(ALPHA3_IOS_EMBED_DIR "${ALPHA3_IOSAPP_DIR}/EmbeddedLibsDevice")
    else()
        set(ALPHA3_IOS_EMBED_DIR "${ALPHA3_IOSAPP_DIR}/EmbeddedLibsSim")
    endif()
    if(ALPHA3_PLATFORM STREQUAL "ios")
        set(_fw_supported iPhoneOS)
    else()
        set(_fw_supported iPhoneSimulator)
    endif()

    # install name of a vcpkg dylib (as referenced by the other libs) -> framework it becomes
    set(ALPHA3_IOS_FRAMEWORK_RENAMES
        libSDL2-2.0.0.dylib=libSDL2
        libcollada-dom2.5-dp.0.dylib=libcollada
        libopenal.1.dylib=libopenal)

    # alpha3_ios_framework(<out var> <framework name> <dylib>)
    # Sets <out var> to build-step COMMANDs that wrap <dylib> into <embed dir>/<name>.framework, give it
    # @rpath/<name>.framework/<name> as install name, and point its references to the other libs at
    # their frameworks.
    function(alpha3_ios_framework out name dylib)
        set(fw "${ALPHA3_IOS_EMBED_DIR}/${name}.framework")
        string(REGEX REPLACE "[^A-Za-z0-9]" "-" bundle_id "${name}")
        set(plist "${CMAKE_BINARY_DIR}/ios-framework-plists/${name}/Info.plist")
        file(WRITE "${plist}" "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">
<plist version=\"1.0\"><dict>
 <key>CFBundleExecutable</key><string>${name}</string>
 <key>CFBundleIdentifier</key><string>org.alpha3.lib.${bundle_id}</string>
 <key>CFBundleName</key><string>${name}</string>
 <key>CFBundlePackageType</key><string>FMWK</string>
 <key>CFBundleShortVersionString</key><string>1.0</string>
 <key>CFBundleVersion</key><string>1</string>
 <key>CFBundleSupportedPlatforms</key><array><string>${_fw_supported}</string></array>
 <key>MinimumOSVersion</key><string>${ALPHA3_IOS_DEPLOYMENT_TARGET}</string>
</dict></plist>
")
        set(cmds
            COMMAND ${CMAKE_COMMAND} -E remove_directory ${fw}
            COMMAND ${CMAKE_COMMAND} -E make_directory ${fw}
            COMMAND ${CMAKE_COMMAND} -E copy ${dylib} ${fw}/${name}
            COMMAND ${CMAKE_COMMAND} -E copy ${plist} ${fw}/Info.plist
            COMMAND install_name_tool -id @rpath/${name}.framework/${name} ${fw}/${name})
        foreach(pair IN LISTS ALPHA3_IOS_FRAMEWORK_RENAMES)
            string(REPLACE "=" ";" pair "${pair}")
            list(GET pair 0 old_name)
            list(GET pair 1 new_name)
            list(APPEND cmds
                COMMAND install_name_tool -change @rpath/${old_name}
                        @rpath/${new_name}.framework/${new_name} ${fw}/${name})
        endforeach()
        set(${out} ${cmds} PARENT_SCOPE)
    endfunction()

    alpha3_ios_framework(_fw_sdl2 libSDL2 ${prefix}/lib/libSDL2.dylib)
    alpha3_ios_framework(_fw_collada libcollada ${prefix}/lib/libcollada-dom2.5-dp.dylib)
    alpha3_ios_framework(_fw_openal libopenal ${prefix}/lib/libopenal.dylib)
    ExternalProject_Add_Step(vcpkg ios_frameworks
            ${_fw_sdl2}
            ${_fw_collada}
            ${_fw_openal}
            DEPENDEES install
            COMMENT "Packaging vcpkg dylibs as frameworks into ${ALPHA3_IOS_EMBED_DIR}"
    )
    ExternalProject_Add_Step(vcpkg moltenvk
            COMMAND ${CMAKE_COMMAND} -E make_directory ${prefix}/lib
            COMMAND ${CMAKE_COMMAND} -E copy_if_different ${_mvk_src_lib} ${prefix}/lib/
            COMMAND ${CMAKE_COMMAND} -E copy_directory ${_mvk_src_include}/vulkan ${prefix}/include/vulkan
            COMMAND ${CMAKE_COMMAND} -E copy_directory ${_mvk_src_include}/vk_video ${prefix}/include/vk_video
            COMMAND ${CMAKE_COMMAND} -E copy_directory ${_mvk_src_include}/MoltenVK ${prefix}/include/MoltenVK
            DEPENDEES install
            COMMENT "Installing MoltenVK (${_mvk_slice}) into ${prefix}"
    )
endif()

message(STATUS "vcpkg triplet : ${VCPKG_TRIPLET}")
message(STATUS "vcpkg prefix  : ${prefix}")
message(STATUS "overlay ports : ${ALPHA3_OVERLAY_PORTS}")

# =============================================================================
# App targets (skipped with -DALPHA3_DEPS_ONLY=ON)
# =============================================================================
if(NOT ALPHA3_DEPS_ONLY)
    if(NOT ALPHA3_IS_ANDROID OR ALPHA3_OPENMW_FORK)
        add_subdirectory(openmw)
    endif()
    if(ALPHA3_IS_ANDROID)  # Android-only for now
        if(NOT ALPHA3_OPENMW_FORK)
            if(NOT ALPHA3_OPENMW_UPSTREAM)
                message(FATAL_ERROR "ALPHA3_OPENMW_FORK and ALPHA3_OPENMW_UPSTREAM are both OFF: no OpenMW engine would be built")
            endif()
            # The launcher's UI layouts (the fork's install step provides them under libopenmw/); everything else the app
            # reads lives in libopenmw-upstream/, so the fork's asset folder and libraries are removed if an earlier build left them
            add_custom_target(openmw_shared_assets ALL
                COMMAND ${CMAKE_COMMAND} -E remove -f ${jni_libs_dir}/libopenmw.so ${jni_libs_dir}/libkram.so
                COMMAND ${CMAKE_COMMAND} -E remove_directory ${assets_dir}/libopenmw
                COMMAND ${CMAKE_COMMAND} -E copy_directory ${ALPHA3_COMPOSEAPP_DIR}/UI ${assets_dir}/libopenmw-upstream/ui
                COMMENT "Shared launcher assets (ALPHA3_OPENMW_FORK=OFF)")
        endif()
        if(ALPHA3_OPENMW_UPSTREAM)
            add_subdirectory(openmw-upstream)
        endif()
        add_subdirectory(Alpha3)
        add_subdirectory(Zink)  # custom Turnip Vulkan driver, loaded by libadrenotools
        add_subdirectory(Angle)  # OpenGL ES on Vulkan: libEGL_angle, libGLESv2_angle, libGLESv1_CM_angle, libfeature_support_angle
        add_subdirectory(dethrace)
        add_subdirectory(MegaMod)
        add_dependencies(Alpha3 dethrace uqm copy_zink copy_angle)
        if(ALPHA3_OPENMW_FORK)
            # kram makes the .ktx files the fork loads in place of DXT; stock OpenMW converts DXT itself (and ahead of the
            # game with the launcher's texture pre-pass, openmw-upstream/support/texconv), so it is only built with the fork
            add_subdirectory(kram)
            add_dependencies(Alpha3 openmw kram)
        else()
            add_dependencies(Alpha3 openmw_shared_assets)
        endif()
        if(TARGET openmw_upstream)
            add_dependencies(Alpha3 openmw_upstream)
        endif()
        if(TARGET copy_mesa_zink)  # Mesa Zink is built unless -DALPHA3_BUILD_MESA_ZINK=OFF
            add_dependencies(Alpha3 copy_mesa_zink)
        endif()
    endif()
endif()

# Device build with ALPHA3_ALSO_BUILD_SIMULATOR: build this same project again for the simulator
# into <build dir>-sim (the dir Gradle's buildNativeIosSimulator uses), after the device build.
if(ALPHA3_ALSO_BUILD_SIMULATOR AND ALPHA3_PLATFORM STREQUAL "ios" AND NOT ALPHA3_DEPS_ONLY)
    ExternalProject_Add(alpha3-simulator
            DEPENDS openmw
            SOURCE_DIR ${CMAKE_SOURCE_DIR}
            BINARY_DIR ${CMAKE_BINARY_DIR}-sim
            CMAKE_ARGS
                -DCMAKE_SYSTEM_NAME=iOS
                -DCMAKE_OSX_SYSROOT=iphonesimulator
                -DCMAKE_OSX_ARCHITECTURES=arm64
                -Wno-author
            BUILD_COMMAND ${CMAKE_COMMAND} --build <BINARY_DIR> -j
            INSTALL_COMMAND ""
            BUILD_ALWAYS 1
            USES_TERMINAL_BUILD ON
    )
endif()
