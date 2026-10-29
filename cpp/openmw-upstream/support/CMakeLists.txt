# Alpha3's support library for the Android build of stock OpenMW. It does what the patch series used to do inside the
# engine, mostly through OSG's RuntimeHooks (cpp/vcpkg-extras/ports/openmw-osg/android/runtime-hooks.patch): shader
# translation for OpenGL ES, the program binary cache, frame pacing, texture conversion, Zink, mimalloc, logcat.
#
# Built as part of OpenMW's build: the Android block of apps/openmw/CMakeLists.txt adds this directory (patch
# 0004), after the openmw target exists. It is compiled against OpenMW's components (Settings::Manager, Log,
# Files::AndroidPath) and OSG.
#
# Inputs from cpp/openmw-upstream/CMakeLists.txt: ASTCENC_SOURCE_DIR, ETCPAK_SOURCE_DIR, MIMALLOC_SOURCE_DIR,
# ALPHA3_MULTIPLAYER.
#
# CMake version: the Android SDK's CMake is 3.22, which has no $<LINK_LIBRARY:WHOLE_ARCHIVE,...> (3.24). The objects
# that must be linked although nothing calls them (hooks.cpp, allocator.cpp, logcat.cpp) are therefore an OBJECT library
# (alpha3_support_init) instead of a static library linked with -Wl,--whole-archive. With CMake 3.24 or newer they could
# be one static library linked as $<LINK_LIBRARY:WHOLE_ARCHIVE,alpha3_support>.

find_package(glslang CONFIG REQUIRED)
# the C interface refers to the targets of the other SPIRV-Cross packages, so they are found first
foreach(spirv_cross_package core glsl hlsl msl cpp reflect util c)
    find_package(spirv_cross_${spirv_cross_package} CONFIG REQUIRED)
endforeach()

# ARM's astcenc (Apache 2.0): DXT to ASTC for GPUs without S3TC, see src/textureconversion.cpp
file(GLOB ALPHA3_ASTCENC_SOURCES ${ASTCENC_SOURCE_DIR}/astcenc_*.cpp)
add_library(alpha3_astcenc STATIC ${ALPHA3_ASTCENC_SOURCES})
# astcenc is not tied to ARM: NEON on arm64, SSE4.1 and POPCNT on x86_64 (both part of Android's x86_64 ABI; AVX2 is
# not, so it stays off), and its plain C++ path anywhere else
if (ANDROID_ABI STREQUAL "arm64-v8a" OR CMAKE_SYSTEM_PROCESSOR MATCHES "^(aarch64|arm64)$")
    set(ALPHA3_ASTCENC_ISA ASTCENC_NEON=1 ASTCENC_SSE=0 ASTCENC_POPCNT=0)
elseif (ANDROID_ABI STREQUAL "x86_64" OR CMAKE_SYSTEM_PROCESSOR MATCHES "^(x86_64|AMD64)$")
    set(ALPHA3_ASTCENC_ISA ASTCENC_NEON=0 ASTCENC_SSE=41 ASTCENC_POPCNT=1)
    target_compile_options(alpha3_astcenc PRIVATE -msse4.1 -mpopcnt)
else()
    set(ALPHA3_ASTCENC_ISA ASTCENC_NEON=0 ASTCENC_SSE=0 ASTCENC_POPCNT=0)
endif()
target_compile_definitions(alpha3_astcenc PRIVATE ${ALPHA3_ASTCENC_ISA} ASTCENC_AVX=0 ASTCENC_F16C=0)
target_compile_options(alpha3_astcenc PRIVATE -O3 -fPIC -fvisibility=hidden -Wno-everything)
target_include_directories(alpha3_astcenc PUBLIC ${ASTCENC_SOURCE_DIR})

# wolfpld's etcpak (BSD): the fast DXT to ETC2 option
add_library(alpha3_etcpak STATIC ${ETCPAK_SOURCE_DIR}/ProcessRGB.cpp ${ETCPAK_SOURCE_DIR}/Tables.cpp ${ETCPAK_SOURCE_DIR}/Dither.cpp)
target_compile_options(alpha3_etcpak PRIVATE -O3 -fPIC -fvisibility=hidden -Wno-everything)
target_include_directories(alpha3_etcpak PUBLIC ${ETCPAK_SOURCE_DIR})

# Microsoft's mimalloc (MIT) behind operator new, see src/allocator.cpp
add_library(alpha3_mimalloc STATIC ${MIMALLOC_SOURCE_DIR}/src/static.c)
target_include_directories(alpha3_mimalloc PUBLIC ${MIMALLOC_SOURCE_DIR}/include)
target_include_directories(alpha3_mimalloc PRIVATE ${MIMALLOC_SOURCE_DIR}/src)
target_compile_options(alpha3_mimalloc PRIVATE -O3 -fPIC -Wno-everything)
target_compile_definitions(alpha3_mimalloc PRIVATE NDEBUG)

# OSG's KTX reader, a static plugin that sits next to the DDS one the engine links (src/ktxtextures.cpp registers it)
get_filename_component(ALPHA3_OSG_PLUGIN_DIR "${OSGDB_DDS_LIBRARY_RELEASE}" DIRECTORY)
find_library(ALPHA3_OSGDB_KTX NAMES osgdb_ktx PATHS "${ALPHA3_OSG_PLUGIN_DIR}" NO_DEFAULT_PATH REQUIRED)

# What the engine and the hooks call; linked into whatever needs it
add_library(alpha3_support STATIC
    src/bcdecode.cpp
    src/blobstore.cpp
    src/framegen.cpp
    src/framepacing.cpp
    src/glesdialect.cpp
    src/ktxtextures.cpp
    src/programcache.cpp
    src/settings.cpp
    src/shaderconverter.cpp
    src/shaders.cpp
    src/textureconversion.cpp
    src/texturepreload.cpp
    src/zink.cpp
)
target_include_directories(alpha3_support PUBLIC ${CMAKE_CURRENT_SOURCE_DIR}/include PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src)
target_compile_options(alpha3_support PRIVATE -fPIC)
target_link_libraries(alpha3_support
    components
    alpha3_astcenc
    alpha3_etcpak
    glslang::glslang
    glslang::SPIRV
    glslang::glslang-default-resource-limits
    spirv-cross-c
    # the KTX reader, then what it needs, after it
    ${ALPHA3_OSGDB_KTX}
    ${OSGDB_LIBRARIES}
    ${OSG_LIBRARIES}
    android
    EGL
    log
)

# The engine's sources that call the library (the Zink window, compute shader support) find its header
target_include_directories(components PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include)
target_include_directories(openmw-lib PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/include)
target_link_libraries(components alpha3_support)

# Objects that work by being there (they install themselves when the library loads): always linked into the engine
add_library(alpha3_support_init OBJECT
    src/allocator.cpp
    src/hooks.cpp
    src/logcat.cpp
)
target_include_directories(alpha3_support_init PRIVATE ${CMAKE_CURRENT_SOURCE_DIR}/src)
target_compile_options(alpha3_support_init PRIVATE -fPIC)
target_link_libraries(alpha3_support_init alpha3_support alpha3_mimalloc components)

target_link_libraries(openmw alpha3_support_init alpha3_support alpha3_mimalloc)
# libc++ inside the engine: the shared one frees what std::string and others grew with its own operator delete, which
# cannot take a pointer from our operator new
target_link_options(openmw PRIVATE -static-libstdc++ -Wl,--exclude-libs,libc++_static.a -Wl,--exclude-libs,libc++abi.a)

# The navmesh tool library (loaded by the app's navmesh baking) logs to logcat too, and needs liblog and zlib. Its target
# is made after this directory, so it is linked at the end of the configure.
add_library(alpha3_logcat OBJECT src/logcat.cpp)
target_compile_options(alpha3_logcat PRIVATE -fPIC)
function(alpha3_link_navmeshtool)
    if (TARGET openmw-navmeshtool-lib)
        target_link_libraries(openmw-navmeshtool-lib alpha3_logcat log z)
    endif()
endfunction()
cmake_language(DEFER DIRECTORY ${CMAKE_SOURCE_DIR} CALL alpha3_link_navmeshtool)

# Multiplayer, when the outer build turns it on (its files are copied into the tree then, see multiplayer/CMakeLists.txt)
if (ALPHA3_MULTIPLAYER)
    add_subdirectory(multiplayer)
endif()

# The launcher's texture pre-pass (texconv/texconv.cpp): the game's texture conversion and cache, built a second time as
# libalpha3_texconv.so with a logcat stand-in for OpenMW's log (texconv/shim) and no other part of the engine. It is
# loaded into the same process as the engine, so it keeps its OSG, libc++ and everything else to itself: only its JNI
# functions are exported.
add_library(alpha3_texconv SHARED texconv/texconv.cpp src/textureconversion.cpp src/blobstore.cpp)
target_include_directories(alpha3_texconv BEFORE PRIVATE texconv/shim src)
target_include_directories(alpha3_texconv PRIVATE ${OPENSCENEGRAPH_INCLUDE_DIRS})
target_compile_options(alpha3_texconv PRIVATE -fvisibility=hidden -fvisibility-inlines-hidden)
target_link_libraries(alpha3_texconv PRIVATE
    osgdb_dds
    ${OSGDB_LIBRARIES}
    ${OSG_LIBRARIES}
    ${OPENTHREADS_LIBRARIES}
    alpha3_astcenc
    alpha3_etcpak
    log
    z
)
target_link_options(alpha3_texconv PRIVATE -static-libstdc++ -Wl,--exclude-libs,ALL -Wl,--gc-sections)
set_target_properties(alpha3_texconv PROPERTIES LIBRARY_OUTPUT_DIRECTORY ${CMAKE_BINARY_DIR})
