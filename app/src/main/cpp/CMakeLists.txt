cmake_minimum_required(VERSION 3.22.1)

project(Alpha3)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)
set(jni_libs_dir "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}")

# Fetch Corrosion to build Rust libraries using CMake
FetchContent_Declare(
    Corrosion
    GIT_REPOSITORY https://github.com/corrosion-rs/corrosion.git
    GIT_TAG v0.6.1
)
FetchContent_MakeAvailable(Corrosion)

# Fetch and build S3LightFixes using Corrosion
FetchContent_Declare(
    S3LightFixes
    GIT_REPOSITORY https://github.com/Duron27-org/S3LightFixes.git
    GIT_TAG main
)
FetchContent_MakeAvailable(S3LightFixes)
corrosion_import_crate(MANIFEST_PATH "${s3lightfixes_SOURCE_DIR}/Cargo.toml")

# Fetch and build DeltaPlugin using Corrosion
FetchContent_Declare(
    DeltaPlugin
    GIT_REPOSITORY https://gitlab.com/bmwinger/delta-plugin.git
    GIT_TAG 9c40703c7481635ebfc0933941e88c36a8c8b282
)
FetchContent_MakeAvailable(DeltaPlugin)
corrosion_import_crate(MANIFEST_PATH "${deltaplugin_SOURCE_DIR}/Cargo.toml")

add_subdirectory(Alpha3)
add_subdirectory(openmw)

set(BOOTSTRAP_SCRIPT "./bootstrap-vcpkg.sh")
set(VCPKG_EXE "./vcpkg")

ExternalProject_Add(vcpkgMain
    GIT_REPOSITORY https://github.com/microsoft/vcpkg.git
    GIT_TAG master
    GIT_SUBMODULES_RECURSE ON
    GIT_PROGRESS ON

    CONFIGURE_COMMAND ""

    BUILD_COMMAND ${BOOTSTRAP_SCRIPT}

    # Keep the arguments aligned as part of a single continuous command block
    INSTALL_COMMAND ${CMAKE_COMMAND} -E env "ANDROID_NDK_HOME=${CMAKE_ANDROID_NDK}"
    ${VCPKG_EXE} install angle:arm64-android
    --overlay-triplets=${CMAKE_CURRENT_SOURCE_DIR}/vcpkg-triplets

    # Copy ANGLE binaries (vcpkg generates them with a 'lib' prefix on top of the 'lib' prefix CMake adds by default)
    COMMAND ${CMAKE_COMMAND} -E copy installed/arm64-android/lib/liblibEGL_angle.so ${jni_libs_dir}/libEGL_angle.so
    COMMAND ${CMAKE_COMMAND} -E copy installed/arm64-android/lib/liblibGLESv2_angle.so ${jni_libs_dir}/libGLESv2_angle.so

    BUILD_IN_SOURCE 1
    USES_TERMINAL_BUILD ON
    USES_TERMINAL_INSTALL ON
)

add_dependencies(Alpha3 vcpkgMain s3lightfixes-shared delta_plugin copy_final)
