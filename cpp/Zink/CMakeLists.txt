cmake_minimum_required(VERSION 3.22.1)

project(Zink)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(ExternalProject)
set(prefix "${CMAKE_BINARY_DIR}/prefix/")
set(NDK_TOOLCHAIN_BIN "${CMAKE_ANDROID_NDK}/toolchains/llvm/prebuilt/${ANDROID_HOST_TAG}/bin")

find_program(MESON_EXECUTABLE meson)
if(NOT MESON_EXECUTABLE)
  message(
          WARNING
          "Meson not found! Please install it (e.g., 'pip install meson') to build Mesa."
  )
endif()

set(MESON_CROSS_FILE "${CMAKE_CURRENT_BINARY_DIR}/android-aarch64-cross.txt")

# Generate the cross-file dynamically based on the current NDK toolchain
file(
    WRITE ${MESON_CROSS_FILE}
    "
[constants]
ndk_path = '${NDK_TOOLCHAIN_BIN}'

[binaries]
c = '${CMAKE_C_COMPILER}'
cpp = '${CMAKE_CXX_COMPILER}'
ar = '${CMAKE_AR}'
strip = '${CMAKE_STRIP}'
pkgconfig = ['env', 'PKG_CONFIG_LIBDIR=${CMAKE_ANDROID_NDK}/toolchains/llvm/prebuilt/${ANDROID_HOST_TAG}/sysroot/usr/lib/aarch64-linux-android/29:${CMAKE_ANDROID_NDK}/toolchains/llvm/prebuilt/${ANDROID_HOST_TAG}/sysroot/usr/share/pkgconfig']

[built-in options]
c_args = ['--target=aarch64-linux-android29', '-O3', '-DVK_USE_PLATFORM_ANDROID_KHR', '-fPIC']
cpp_args = ['--target=aarch64-linux-android29', '-O3', '-DVK_USE_PLATFORM_ANDROID_KHR', '-fPIC', '-fno-exceptions', '-fno-unwind-tables', '-fno-asynchronous-unwind-tables', '-static-libstdc++', '-Wno-c++11-narrowing']
c_link_args = ['--target=aarch64-linux-android29', '-Wl,--undefined-version']
cpp_link_args = ['--target=aarch64-linux-android29', '-Wl,--undefined-version']

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'armv8'
endian = 'little'
")

set(MESA_INSTALL_DIR "${CMAKE_CURRENT_BINARY_DIR}/mesa-install")

# Turnip follows Mesa's main branch, so every build asks gitlab.freedesktop.org for news. With ALPHA3_MESA_UPDATE=OFF in the
# environment of the build (ALPHA3_MESA_UPDATE=OFF ./gradlew ...) it builds what is already checked out, which keeps working
# while that server is down. An environment variable, not a CMake argument: arguments change the build's configuration hash
# and Gradle then starts a whole new build directory.
if("$ENV{ALPHA3_MESA_UPDATE}" STREQUAL "OFF")
    set(ALPHA3_MESA_UPDATE OFF)
else()
    set(ALPHA3_MESA_UPDATE ON)
endif()

ExternalProject_Add(
        MesaTurnip
        GIT_REPOSITORY https://gitlab.freedesktop.org/mesa/mesa.git
        GIT_TAG main
        GIT_SHALLOW ON
        GIT_PROGRESS ON
        UPDATE_DISCONNECTED $<NOT:$<BOOL:${ALPHA3_MESA_UPDATE}>>
        PREFIX mesa
        CONFIGURE_COMMAND
        ${MESON_EXECUTABLE} setup <BINARY_DIR> <SOURCE_DIR>
        --prefix=${MESA_INSTALL_DIR}
        --cross-file ${MESON_CROSS_FILE}
        -Dplatforms=android
        -Dplatform-sdk-version=29
        -Dandroid-stub=true
        -Dvulkan-drivers=freedreno
        -Dfreedreno-kmds=kgsl
        -Dgallium-drivers=
        -Dopengl=false
        -Degl=disabled
        -Dgles1=disabled
        -Dgles2=disabled
        -Dglx=disabled
        -Dbuildtype=release
        -Dllvm=disabled
        -Dshared-glapi=disabled
        -Dzstd=disabled
        BUILD_COMMAND ${MESON_EXECUTABLE} compile -C <BINARY_DIR>
        INSTALL_COMMAND ${MESON_EXECUTABLE} install -C <BINARY_DIR>
        BUILD_IN_SOURCE 0
        USES_TERMINAL_BUILD ON
        USES_TERMINAL_INSTALL ON
)

# Copy the Turnip driver to jniLibs. libadrenotools loads it from nativeLibraryDir
# (see Alpha3/adrenotools.cpp).
add_custom_target(copy_turnip
        DEPENDS MesaTurnip
        COMMAND ${CMAKE_COMMAND} -E make_directory ${jni_libs_dir}
        COMMAND ${CMAKE_COMMAND} -E copy_if_different
        ${MESA_INSTALL_DIR}/lib/libvulkan_freedreno.so
        ${jni_libs_dir}/
        COMMENT "Copying Turnip Vulkan driver to jniLibs"
)

# ---------------------------------------------------------------------------------------------------------
# Zink: Mesa's OpenGL ES on top of Vulkan. A second Mesa build from the same checkout (so it runs after
# Turnip), into its own build and install directories. It provides an EGL and GLES library that render
# through whatever Vulkan driver is loaded.
#
# The libraries get a "_mesa" suffix so they cannot be mistaken for the system's libEGL.so / libGLESv2.so
# (the app's own copies would otherwise shadow, or be shadowed by, the system ones).
#
# Known limits, not yet tried on a device:
#  - libEGL_mesa.so links libhardware.so (hw_get_module, for gralloc). That library is not part of the NDK's
#    public set, so Android may refuse to load libEGL_mesa.so inside an app.
#  - libnativewindow.so and libsync.so are public system libraries and are used as they are. The stubs that
#    Mesa builds for them (android-stub) must NOT be shipped, they would shadow the real ones.
# ---------------------------------------------------------------------------------------------------------
option(ALPHA3_BUILD_MESA_ZINK "Also build Mesa Zink (OpenGL ES on Vulkan)" ON)

if(ALPHA3_BUILD_MESA_ZINK)
    ExternalProject_Get_Property(MesaTurnip SOURCE_DIR)
    set(MESA_SOURCE_DIR "${SOURCE_DIR}")
    set(MESA_ZINK_INSTALL_DIR "${CMAKE_CURRENT_BINARY_DIR}/mesa-zink-install")

    ExternalProject_Add(
            MesaZink
            DEPENDS MesaTurnip
            SOURCE_DIR ${MESA_SOURCE_DIR}
            PREFIX mesa-zink
            DOWNLOAD_COMMAND ""
            UPDATE_COMMAND ""
            CONFIGURE_COMMAND
            ${MESON_EXECUTABLE} setup <BINARY_DIR> <SOURCE_DIR>
            --prefix=${MESA_ZINK_INSTALL_DIR}
            --cross-file ${MESON_CROSS_FILE}
            -Dplatforms=android
            -Dplatform-sdk-version=29
            -Dandroid-stub=true
            -Dvulkan-drivers=
            -Dvulkan-layers=
            -Dgallium-drivers=zink
            -Dopengl=true
            -Degl=enabled
            -Dgles1=disabled
            -Dgles2=enabled
            -Dglx=disabled
            -Dgbm=disabled
            -Dshared-glapi=enabled
            -Degl-lib-suffix=_mesa
            -Dgles-lib-suffix=_mesa
            # Mesa's EGL needs libdrm, which the NDK does not have: build the copy Mesa ships a wrap for
            -Dallow-fallback-for=libdrm
            -Dbuildtype=release
            -Dllvm=disabled
            -Dzstd=disabled
            -Dtools=
            BUILD_COMMAND ${MESON_EXECUTABLE} compile -C <BINARY_DIR>
            INSTALL_COMMAND ${MESON_EXECUTABLE} install -C <BINARY_DIR>
            BUILD_IN_SOURCE 0
            USES_TERMINAL_BUILD ON
            USES_TERMINAL_INSTALL ON
    )

    # Only these go into the app: EGL, GLES, the Zink driver they load, and the libdrm it needs.
    add_custom_target(copy_mesa_zink
            DEPENDS MesaZink
            COMMAND ${CMAKE_COMMAND} -E make_directory ${jni_libs_dir}
            COMMAND ${CMAKE_COMMAND} -E copy_if_different ${MESA_ZINK_INSTALL_DIR}/lib/libEGL_mesa.so ${jni_libs_dir}/
            COMMAND ${CMAKE_COMMAND} -E copy_if_different ${MESA_ZINK_INSTALL_DIR}/lib/libGLESv2_mesa.so ${jni_libs_dir}/
            COMMAND ${CMAKE_COMMAND} -E copy_if_different ${MESA_ZINK_INSTALL_DIR}/lib/libgallium_dri.so ${jni_libs_dir}/
            COMMAND ${CMAKE_COMMAND} -E copy_if_different ${MESA_ZINK_INSTALL_DIR}/lib/libdrm.so ${jni_libs_dir}/
            COMMENT "Copying Mesa Zink (EGL, GLES, driver) to jniLibs"
    )
    set(ZINK_COPY_TARGETS copy_turnip copy_mesa_zink)
else()
    set(ZINK_COPY_TARGETS copy_turnip)
endif()

# ---------------------------------------------------------------------------------------------------------
# OSMesa + Zink: desktop OpenGL for OpenMW's `renderer = opengl`. Mesa 25.0.1 is the last release that still has
# the OSMesa target; main (above) does not. OpenMW renders into an OSMesa context (sdlgraphicswindow.cpp, see
# openmw/patches/android/osmesa.patch) and copies each frame to the window. Zink takes the Vulkan driver the
# app already loaded (Turnip through libadrenotools) from ALPHA3_VULKAN_HANDLE, see
# patches/mesa-25.0.1-alpha3.patch. The library is shipped as libOSMesa_8.so (loaded by that name).
# ---------------------------------------------------------------------------------------------------------
option(ALPHA3_BUILD_MESA_OSMESA "Also build Mesa OSMesa with Zink (desktop OpenGL on Vulkan)" ON)

if(ALPHA3_BUILD_MESA_OSMESA)
    set(MESA_OSMESA_PATCH "${CMAKE_CURRENT_SOURCE_DIR}/patches/mesa-25.0.1-alpha3.patch")
    set(MESA_OSMESA_INSTALL_DIR "${CMAKE_CURRENT_BINARY_DIR}/mesa-osmesa-install")

    # Same cross file without VK_USE_PLATFORM_ANDROID_KHR: that pulls in Android's private cutils headers, and OSMesa
    # has no window system anyway. Built with no platform, so libOSMesa depends on public NDK libraries only.
    file(READ ${MESON_CROSS_FILE} _cross_text)
    string(REPLACE "'-DVK_USE_PLATFORM_ANDROID_KHR', " "" _cross_text "${_cross_text}")
    set(MESON_OSMESA_CROSS_FILE "${CMAKE_CURRENT_BINARY_DIR}/android-aarch64-osmesa-cross.txt")
    file(WRITE ${MESON_OSMESA_CROSS_FILE} "${_cross_text}")

    ExternalProject_Add(
            MesaOSMesa
            GIT_REPOSITORY https://gitlab.freedesktop.org/mesa/mesa.git
            GIT_TAG mesa-25.0.1
            GIT_SHALLOW ON
            GIT_PROGRESS ON
            PREFIX mesa-osmesa
            UPDATE_COMMAND ""
            # starts from the clean tag every time, so an edited patch applies cleanly
            PATCH_COMMAND sh -c "git checkout -- . && git apply '${MESA_OSMESA_PATCH}'"
            CONFIGURE_COMMAND
            ${MESON_EXECUTABLE} setup <BINARY_DIR> <SOURCE_DIR>
            --prefix=${MESA_OSMESA_INSTALL_DIR}
            --cross-file ${MESON_OSMESA_CROSS_FILE}
            -Dplatforms=
            -Dexpat:default_library=static
            -Dvulkan-drivers=
            -Dvulkan-layers=
            -Dgallium-drivers=softpipe,zink
            -Dosmesa=true
            -Dopengl=true
            -Degl=disabled
            -Dgles1=disabled
            -Dgles2=disabled
            -Dglx=disabled
            -Dgbm=disabled
            -Dshared-glapi=disabled
            -Dbuildtype=release
            -Dllvm=disabled
            -Dzstd=disabled
            -Dtools=
            BUILD_COMMAND ${MESON_EXECUTABLE} compile -C <BINARY_DIR>
            INSTALL_COMMAND ${MESON_EXECUTABLE} install -C <BINARY_DIR>
            BUILD_IN_SOURCE 0
            USES_TERMINAL_BUILD ON
            USES_TERMINAL_INSTALL ON
    )

    # re-apply the patch whenever it changes
    ExternalProject_Add_StepDependencies(MesaOSMesa patch ${MESA_OSMESA_PATCH})

    add_custom_target(copy_mesa_osmesa
            DEPENDS MesaOSMesa
            COMMAND ${CMAKE_COMMAND} -E make_directory ${jni_libs_dir}
            COMMAND ${CMAKE_COMMAND} -E copy_if_different ${MESA_OSMESA_INSTALL_DIR}/lib/libOSMesa.so ${jni_libs_dir}/libOSMesa_8.so
            COMMENT "Copying Mesa OSMesa (Zink) to jniLibs"
    )
    list(APPEND ZINK_COPY_TARGETS copy_mesa_osmesa)
endif()

# What the rest of the build depends on (Turnip first, then Zink)
add_custom_target(copy_zink ALL DEPENDS ${ZINK_COPY_TARGETS})
