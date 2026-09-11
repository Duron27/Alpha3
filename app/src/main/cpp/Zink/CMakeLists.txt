cmake_minimum_required(VERSION 3.22.1)

project(Zink)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(ExternalProject)
set(jni_libs_dir "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")

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
  "[binaries]
c = '${CMAKE_C_COMPILER}'
cpp = '${CMAKE_CXX_COMPILER}'
ar = '${CMAKE_AR}'
strip = '${CMAKE_STRIP}'
pkgconfig = 'false'

[built-in options]
c_args = ['--target=aarch64-linux-android29', '-D__INTRODUCED_IN(x)=']
cpp_args = ['--target=aarch64-linux-android29', '-D__INTRODUCED_IN(x)=']
c_link_args = ['--target=aarch64-linux-android29', '-Wl,--undefined-version']
cpp_link_args = ['--target=aarch64-linux-android29', '-Wl,--undefined-version']

[host_machine]
system = 'android'
cpu_family = 'aarch64'
cpu = 'armv8-a'
endian = 'little'
")

set(MESA_INSTALL_DIR "${CMAKE_CURRENT_BINARY_DIR}/mesa-install")

ExternalProject_Add(
  MesaZinkTurnip
  GIT_REPOSITORY https://gitlab.freedesktop.org/mesa/mesa.git
  GIT_TAG main
  GIT_SHALLOW ON
  PREFIX mesa
  CONFIGURE_COMMAND
    ${MESON_EXECUTABLE} setup <BINARY_DIR> <SOURCE_DIR>
    --prefix=${MESA_INSTALL_DIR} --cross-file ${MESON_CROSS_FILE}
    -Dplatforms=android -Dplatform-sdk-version=29 -Dandroid-stub=true
    -Dgallium-drivers=zink -Dvulkan-drivers=freedreno -Dfreedreno-kmds=kgsl
    -Dbuildtype=release -Dllvm=disabled -Dshared-glapi=disabled -Degl=enabled
    -Dgles1=enabled -Dgles2=enabled -Dglx=disabled -Dopengl=true -Dzstd=disabled
    -Degl-lib-suffix=_zink -Dgles-lib-suffix=_zink
  BUILD_COMMAND ${MESON_EXECUTABLE} compile -C <BINARY_DIR>
  INSTALL_COMMAND ${MESON_EXECUTABLE} install -C <BINARY_DIR>
  BUILD_IN_SOURCE 0
  USES_TERMINAL_BUILD ON
  USES_TERMINAL_INSTALL ON)

# Copy the compiled libraries (libvulkan_freedreno.so, libEGL_zink.so, libGLESv2_zink.so,
# etc.) to jniLibs
add_custom_target(copy_zink ALL
        DEPENDS MesaZinkTurnip

        COMMAND ${CMAKE_COMMAND} -E make_directory ${jni_libs_dir}
        COMMAND ${CMAKE_COMMAND} -E copy_directory ${MESA_INSTALL_DIR}/lib
        ${jni_libs_dir}
)
