set(VCPKG_TARGET_ARCHITECTURE arm64)
set(VCPKG_CMAKE_SYSTEM_NAME Android)

if(DEFINED ENV{VCPKG_ANDROID_API})
    set(VCPKG_CMAKE_SYSTEM_VERSION $ENV{VCPKG_ANDROID_API})
else()
    set(VCPKG_CMAKE_SYSTEM_VERSION 28)
endif()

# ANGLE requires specific handling
if(${PORT} MATCHES "angle")
    set(VCPKG_CRT_LINKAGE dynamic)
    set(VCPKG_LIBRARY_LINKAGE dynamic)
    set(VCPKG_CMAKE_CONFIGURE_OPTIONS
            -DANDROID_ABI=arm64-v8a
            -DBUILD_SHARED_LIBS=ON
            -DANGLE_EXPOSE_NON_CONFORMANT_EXTENSIONS_AND_VERSIONS=ON
    )
    set(VCPKG_LINKER_FLAGS "-llog -landroid -lnativewindow")
elseif(${PORT} MATCHES "openal|sdl2|collada")
    set(VCPKG_CRT_LINKAGE dynamic)
    set(VCPKG_LIBRARY_LINKAGE dynamic)
    set(VCPKG_CMAKE_CONFIGURE_OPTIONS -DANDROID_ABI=arm64-v8a)
else()
    set(VCPKG_CRT_LINKAGE static)
    set(VCPKG_LIBRARY_LINKAGE static)
    set(VCPKG_CMAKE_CONFIGURE_OPTIONS -DANDROID_ABI=arm64-v8a)
endif()

set(VCPKG_MAKE_BUILD_TRIPLET "--host=aarch64-linux-android")
set(VCPKG_BUILD_TYPE release)