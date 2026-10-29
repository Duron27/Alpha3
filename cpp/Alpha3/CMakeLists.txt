project(Alpha3)

# Loads a custom Vulkan driver (Turnip, built in ../Zink) in place of the system one.
# Also builds the hook libraries (libhook_impl/libmain_hook/...) that must sit in nativeLibraryDir.
FetchContent_Declare(
    libadrenotools
    GIT_REPOSITORY https://github.com/bylaws/libadrenotools.git
    GIT_TAG 8fae8ce254dfc1344527e05301e43f37dea2df80
    GIT_SUBMODULES_RECURSE ON
)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
FetchContent_MakeAvailable(libadrenotools)

file(GLOB INCLUDE_FILES
        *.h
)

file(GLOB SOURCE_FILES
        *.cpp
)

add_library(${PROJECT_NAME} SHARED
        ${INCLUDE_FILES}
        ${SOURCE_FILES})

target_link_libraries(${PROJECT_NAME} PRIVATE
        android
        z
        log
        Memory
        adrenotools
        spdlog::spdlog
)

target_compile_options(${PROJECT_NAME} PRIVATE
        -w
        -O3
        -fexceptions)

add_subdirectory(deps)

set_property(TARGET ${PROJECT_NAME} PROPERTY OUTPUT_NAME Alpha3)
set_property(TARGET ${PROJECT_NAME} PROPERTY ENABLE_EXPORTS ON)
