# ANGLE for iOS (OpenGL ES 3.0 on top of Metal), included by CMakeLists.txt when building for iOS.
#
# One library, libGLESv2_angle.dylib, carries both the OpenGL ES and the EGL entry points: libEGL's sources are built
# without ANGLE_USE_EGL_LOADER into it, so eglXxx call libGLESv2's EGL_Xxx directly. The source lists are
# angle_sources_ios.cmake (tools/gen_sources_apple.py, from ANGLE's .gni files). The Metal back end's internal shaders
# are compiled at runtime from the source ANGLE carries in mtl_internal_shaders_src_autogen.h, which is what ANGLE
# does on iOS too, so the build needs no Metal toolchain.
#
# The engine does not link it: it is opened at runtime when the launcher picks ANGLE as renderer.

enable_language(OBJC OBJCXX)
if(DEFINED ALPHA3_IOS_DEPLOYMENT_TARGET)
    set(CMAKE_OSX_DEPLOYMENT_TARGET "${ALPHA3_IOS_DEPLOYMENT_TARGET}")
endif()

angle_fetch(angle angle/angle ${ANGLE_COMMIT})
set(ANGLE_ROOT "${angle_SOURCE_DIR}")
angle_fetch(zlib chromium/src/third_party/zlib ${ANGLE_ZLIB_COMMIT} "${ANGLE_ROOT}/third_party/zlib")
angle_fetch(abseil chromium/src/third_party/abseil-cpp ${ANGLE_ABSEIL_COMMIT})

include(${CMAKE_CURRENT_LIST_DIR}/angle_sources_ios.cmake)
include(${CMAKE_CURRENT_LIST_DIR}/angle_sources.cmake)    # ANGLE_PROGRAM_VERSION_FILES

# ---------------------------------------------------------------------------------------------------------------
# Generated headers, as on Android
# ---------------------------------------------------------------------------------------------------------------
set(ANGLE_GEN "${CMAKE_CURRENT_BINARY_DIR}/gen/angle")
file(MAKE_DIRECTORY "${ANGLE_GEN}")

string(SUBSTRING "${ANGLE_COMMIT}" 0 12 ANGLE_COMMIT_SHORT)
file(WRITE "${ANGLE_GEN}/angle_commit.h.tmp"
    "#define ANGLE_COMMIT_HASH \"${ANGLE_COMMIT_SHORT}\"\n"
    "#define ANGLE_COMMIT_HASH_SIZE 12\n"
    "#define ANGLE_COMMIT_DATE \"unknown date\"\n"
    "#define ANGLE_COMMIT_POSITION ${ANGLE_COMMIT_POSITION}\n")
file(COPY_FILE "${ANGLE_GEN}/angle_commit.h.tmp" "${ANGLE_GEN}/angle_commit.h" ONLY_IF_DIFFERENT)
file(REMOVE "${ANGLE_GEN}/angle_commit.h.tmp")

set(ANGLE_PROGRAM_VERSION_DEPENDS "")
set(program_version_list "")
foreach(f IN LISTS ANGLE_PROGRAM_VERSION_FILES)
    list(APPEND ANGLE_PROGRAM_VERSION_DEPENDS "${ANGLE_ROOT}/${f}")
    string(APPEND program_version_list "${f}\n")
endforeach()
file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/program_version_files.rsp" "${program_version_list}")
add_custom_command(
    OUTPUT "${ANGLE_GEN}/ANGLEShaderProgramVersion.h"
    COMMAND ${Python3_EXECUTABLE} "${ANGLE_ROOT}/src/program_serialize_data_version.py"
            "${ANGLE_GEN}/ANGLEShaderProgramVersion.h" "${CMAKE_CURRENT_BINARY_DIR}/program_version_files.rsp"
    WORKING_DIRECTORY "${ANGLE_ROOT}"
    DEPENDS ${ANGLE_PROGRAM_VERSION_DEPENDS} "${ANGLE_ROOT}/src/program_serialize_data_version.py"
    COMMENT "Generating ANGLEShaderProgramVersion.h"
    VERBATIM
)
add_custom_target(angle_generated_headers DEPENDS "${ANGLE_GEN}/ANGLEShaderProgramVersion.h")

# ---------------------------------------------------------------------------------------------------------------
# abseil (flat_hash_map and flat_hash_set), no exceptions, no RTTI
# ---------------------------------------------------------------------------------------------------------------
set(ABSL_PROPAGATE_CXX_STD ON)
set(ABSL_BUILD_TESTING OFF)
set(ABSL_ENABLE_INSTALL OFF)
set(ABSL_USE_EXTERNAL_GOOGLETEST OFF)
set(BUILD_TESTING OFF)
set(CMAKE_CXX_STANDARD 20)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)

set(angle_saved_cxx_flags "${CMAKE_CXX_FLAGS}")
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -O2 -fno-exceptions -fno-rtti -fvisibility=hidden -fvisibility-inlines-hidden -DABSL_ALLOCATOR_NOTHROW=1 -w")
add_subdirectory("${abseil_SOURCE_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/abseil" EXCLUDE_FROM_ALL)
set(CMAKE_CXX_FLAGS "${angle_saved_cxx_flags}")

# ---------------------------------------------------------------------------------------------------------------
# Compile settings (BUILD.gn's configs for target_os="ios" with only the Metal back end)
# ---------------------------------------------------------------------------------------------------------------
add_library(angle_flags INTERFACE)

target_include_directories(angle_flags INTERFACE
    "${ANGLE_ROOT}"
    "${ANGLE_ROOT}/include"
    "${ANGLE_ROOT}/src"
    "${ANGLE_ROOT}/src/common/base"
    "${ANGLE_ROOT}/src/common/third_party/xxhash"
    "${abseil_SOURCE_DIR}"
    "${ANGLE_ROOT}/third_party/zlib/google"
    "${ANGLE_ROOT}/third_party/zlib"
    "${ANGLE_GEN}"
)

target_compile_definitions(angle_flags INTERFACE
    __STDC_CONSTANT_MACROS __STDC_FORMAT_MACROS NDEBUG _LIBCPP_HARDENING_MODE=_LIBCPP_HARDENING_MODE_NONE
    ANGLE_ENABLE_SHARE_CONTEXT_LOCK=1 ANGLE_ENABLE_CONTEXT_MUTEX=1
    ANGLE_OUTSIDE_WEBKIT ANGLE_USE_ABSEIL ABSL_ALLOCATOR_NOTHROW=1
    ANGLE_CAPTURE_ENABLED=0 ANGLE_EXPOSE_NON_CONFORMANT_EXTENSIONS_AND_VERSIONS ANGLE_PLATFORM_EXPORT=
    ANGLE_ENABLE_METAL
    GL_GLES_PROTOTYPES=1 GL_GLEXT_PROTOTYPES EGL_EGL_PROTOTYPES=1 EGL_EGLEXT_PROTOTYPES
)

target_compile_options(angle_flags INTERFACE
    -O2 -g0 -fPIC -w
    -fvisibility=hidden
    -fno-strict-aliasing -fno-math-errno -ffp-contract=off -fmerge-all-constants -ffunction-sections
    $<$<COMPILE_LANGUAGE:CXX,OBJCXX>:-std=c++20>
    $<$<COMPILE_LANGUAGE:CXX,OBJCXX>:-fno-exceptions>
    $<$<COMPILE_LANGUAGE:CXX,OBJCXX>:-fno-rtti>
    $<$<COMPILE_LANGUAGE:CXX,OBJCXX>:-fvisibility-inlines-hidden>
)

# One OBJECT library per group of angle_sources_ios.cmake: angle_obj_<group>
function(angle_ios_objects group)
    set(sources "")
    foreach(f IN LISTS ANGLE_IOS_SRC_${group})
        list(APPEND sources "${ANGLE_ROOT}/${f}")
    endforeach()
    add_library(angle_obj_${group} OBJECT ${sources})
    target_link_libraries(angle_obj_${group} PRIVATE angle_flags)
    add_dependencies(angle_obj_${group} angle_generated_headers)
endfunction()

set(ANGLE_IOS_GROUPS
    xxhash common common_shader_state image_util gpu_info_util preprocessor translator version_info
    libangle metal_backend frame_capture_mock compression libGLESv2 libEGL)
foreach(group IN LISTS ANGLE_IOS_GROUPS)
    angle_ios_objects(${group})
endforeach()

# Chromium builds Objective-C with ARC; the Metal back end does its own reference counting
foreach(group IN LISTS ANGLE_IOS_GROUPS)
    if(group STREQUAL "metal_backend")
        target_compile_options(angle_obj_${group} PRIVATE $<$<COMPILE_LANGUAGE:OBJC,OBJCXX>:-fno-objc-arc>)
    else()
        target_compile_options(angle_obj_${group} PRIVATE $<$<COMPILE_LANGUAGE:OBJC,OBJCXX>:-fobjc-arc>)
    endif()
endforeach()

foreach(group libangle metal_backend frame_capture_mock libGLESv2)
    target_compile_definitions(angle_obj_${group} PRIVATE LIBANGLE_IMPLEMENTATION)
endforeach()
target_compile_definitions(angle_obj_libGLESv2 PRIVATE
    LIBGLESV2_IMPLEMENTATION
    "GL_APICALL=__attribute__((visibility(\"default\")))" "GL_API=__attribute__((visibility(\"default\")))"
    "EGLAPI=__attribute__((visibility(\"default\")))")
target_compile_definitions(angle_obj_libEGL PRIVATE
    LIBEGL_IMPLEMENTATION "EGLAPI=__attribute__((visibility(\"default\")))")

# Chromium's zlib (symbols renamed Cr_z_*), only used for the program binary cache: the plain C paths
set(zlib_sources adler32.c compress.c cpu_features.c crc32.c deflate.c gzclose.c gzlib.c gzread.c gzwrite.c
    infback.c inffast.c inflate.c inftrees.c trees.c uncompr.c zutil.c)
list(TRANSFORM zlib_sources PREPEND "${zlib_SOURCE_DIR}/")
add_library(angle_obj_zlib OBJECT ${zlib_sources})
target_compile_definitions(angle_obj_zlib PRIVATE ZLIB_IMPLEMENTATION)
target_include_directories(angle_obj_zlib PRIVATE "${zlib_SOURCE_DIR}")
target_compile_options(angle_obj_zlib PRIVATE -O3)
target_link_libraries(angle_obj_zlib PRIVATE angle_flags)

# ---------------------------------------------------------------------------------------------------------------
# The library
# ---------------------------------------------------------------------------------------------------------------
set(objects "")
foreach(group IN LISTS ANGLE_IOS_GROUPS ITEMS zlib)
    list(APPEND objects "$<TARGET_OBJECTS:angle_obj_${group}>")
endforeach()
add_library(angle_libGLESv2 SHARED ${objects})
set_target_properties(angle_libGLESv2 PROPERTIES OUTPUT_NAME GLESv2_angle LINKER_LANGUAGE CXX)
target_link_options(angle_libGLESv2 PRIVATE -Wl,-dead_strip -Wl,-x -weak_framework Metal)
target_link_libraries(angle_libGLESv2 PRIVATE
    absl::flat_hash_map absl::flat_hash_set
    "-framework IOSurface" "-framework QuartzCore" "-framework CoreGraphics" "-framework CoreFoundation"
    "-framework Foundation" "-framework UIKit")
