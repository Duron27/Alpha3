cmake_minimum_required(VERSION 3.22.1)

project(Alpha3)

set(CMAKE_C_STANDARD 11)
set(CMAKE_CXX_STANDARD 23)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

include(FetchContent)

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
#add_subdirectory(angle)
add_subdirectory(openmw)

add_dependencies(Alpha3 s3lightfixes-shared delta_plugin copy_final) #libANGLE libGLESv2 libEGL
