set(VCPKG_TARGET_ARCHITECTURE arm64)
set(VCPKG_CRT_LINKAGE dynamic)
set(VCPKG_LIBRARY_LINKAGE static)
set(VCPKG_CMAKE_SYSTEM_NAME iOS)
set(VCPKG_OSX_ARCHITECTURES arm64)
set(VCPKG_OSX_SYSROOT iphonesimulator)
set(VCPKG_OSX_DEPLOYMENT_TARGET 16.4)
set(VCPKG_BUILD_TYPE release)
set(VCPKG_C_FLAGS "-O3 -fPIC -w")
set(VCPKG_CXX_FLAGS "-O3 -fPIC -w -frtti -fexceptions -stdlib=libc++")
set(VCPKG_MAKE_BUILD_TRIPLET "--host=arm-apple-darwin")

# Only these ports are embedded as separate .framework bundles (see iosApp EmbeddedLibs);
# everything else (zlib, bzip2, libjpeg-turbo, libpng, minizip, ...) stays static and is linked into them.
if(PORT MATCHES "^(sdl2|openal-soft|collada-dom)$")
    set(VCPKG_LIBRARY_LINKAGE dynamic)
endif()

# SDL: the launcher looks up internal entry points (SDL_SendKeyboardText, SDL_SendMouseMotion, the virtual
# joystick API, ...) with dlsym, so SDL must not build with -fvisibility=hidden.
if(PORT STREQUAL "sdl2")
    set(VCPKG_CMAKE_CONFIGURE_OPTIONS -DHAVE_GCC_FVISIBILITY=OFF)
endif()
