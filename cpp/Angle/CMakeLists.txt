cmake_minimum_required(VERSION 3.22.1)

project(Angle C CXX)

# ANGLE (OpenGL ES on top of Vulkan) for Android, built with CMake only (no gn, no depot_tools).
# For iOS (OpenGL ES on top of Metal) ios.cmake builds it instead.
#
# It builds the same four libraries as
#     gn gen out --args='target_os="android" target_cpu="arm64" angle_enable_vulkan=true angle_enable_gl=false
#                        angle_libs_suffix="_angle" android_ndk_api_level=26 ...'     (full list in tools/gen_sources.py)
#   libEGL_angle.so  libGLESv2_angle.so  libGLESv1_CM_angle.so  libfeature_support_angle.so
# and copies them to ${jni_libs_dir}. The source lists in angle_sources.cmake and the compile definitions below
# come from `gn desc` for that configuration; tools/gen_sources.py makes the lists again for a newer ANGLE.
#
# Sources are downloaded from chromium.googlesource.com at the commits ANGLE's own DEPS file pins.
# Force_esVersion.patch is applied to ANGLE: it stops the Vulkan back end from lowering the OpenGL ES version a
# device is offered, so the highest version (3.2) is exposed even when a driver misses an optional feature.

# ---------------------------------------------------------------------------------------------------------------
# Versions
# ---------------------------------------------------------------------------------------------------------------
set(ANGLE_COMMIT "9bbe9ce189115e468aea2d06001b1eddbb772958" CACHE STRING "ANGLE commit to build")
# Only used for the version string (2.1.<position>); the sources are plain archives without git history.
set(ANGLE_COMMIT_POSITION "27377" CACHE STRING "git commit count of ANGLE_COMMIT")
option(ANGLE_THIN_LTO "Link with ThinLTO like the gn build does (slower to build, smaller and faster)" ON)

# Pinned in ANGLE's DEPS for ANGLE_COMMIT
set(ANGLE_ABSEIL_COMMIT "2a7d49fc392cad55159d68d98aa3648bc89795d3")
set(ANGLE_ZLIB_COMMIT "b80f1d1e5256ac25f6aea3f31f13d458981cb1f9")
set(ANGLE_SPIRV_HEADERS_COMMIT "465055f6c9128772e20082e893d974146acf7a02")
set(ANGLE_SPIRV_TOOLS_COMMIT "8a13595dd4ae5049ef42d0f30297d0c427db54b5")
set(ANGLE_VULKAN_HEADERS_COMMIT "387259ecf4b0fe0cfac161b1d0b0a74e42796710")
set(ANGLE_VMA_COMMIT "cb0597213b0fcb999caa9ed08c2f88dc45eb7d50")
set(ANGLE_JSONCPP_COMMIT "42e892d96e47b1f6e29844cc705e148ec4856448")
set(ANGLE_CPU_FEATURES_COMMIT "936b9ab5515dead115606559502e3864958f7f6e")

if(NOT ANDROID AND NOT CMAKE_SYSTEM_NAME STREQUAL "iOS")
    message(FATAL_ERROR "This builds ANGLE for Android and iOS only")
endif()
find_package(Python3 REQUIRED COMPONENTS Interpreter)

# ---------------------------------------------------------------------------------------------------------------
# Sources
# ---------------------------------------------------------------------------------------------------------------
include(FetchContent)
if(POLICY CMP0169)
    cmake_policy(SET CMP0169 OLD)   # FetchContent_Populate on its own is fine here
endif()
if(NOT DEFINED download_dir)
    set(download_dir ${CMAKE_BINARY_DIR}/downloads)
endif()

# A commit of a chromium.googlesource.com repository, as the archive googlesource builds for it.
# Sets <name>_SOURCE_DIR. The FetchContent names are prefixed so they cannot clash with other users of FetchContent.
function(angle_fetch name repo commit)
    set(destination "")
    if(ARGC GREATER 3)
        set(destination SOURCE_DIR "${ARGV3}")
    endif()
    set(content angle_dep_${name})
    # CMake before 3.24 (the SDK's 3.22.1) does not know DOWNLOAD_EXTRACT_TIMESTAMP and would take it as part of the
    # previous value; newer CMake needs it (or CMP0135 set below) to avoid a warning.
    set(timestamp "")
    if(CMAKE_VERSION VERSION_GREATER_EQUAL 3.24)
        set(timestamp DOWNLOAD_EXTRACT_TIMESTAMP TRUE)
    endif()
    FetchContent_Declare(${content}
        URL "https://chromium.googlesource.com/${repo}/+archive/${commit}.tar.gz"
        DOWNLOAD_NAME "${name}-${commit}.tar.gz"
        DOWNLOAD_DIR "${download_dir}"
        ${timestamp}
        ${destination}
    )
    FetchContent_GetProperties(${content})
    if(NOT ${content}_POPULATED)
        FetchContent_Populate(${content})
    endif()
    set(${name}_SOURCE_DIR "${${content}_SOURCE_DIR}" PARENT_SCOPE)
endfunction()

if(CMAKE_SYSTEM_NAME STREQUAL "iOS")
    include(${CMAKE_CURRENT_LIST_DIR}/ios.cmake)
    return()
endif()

angle_fetch(angle angle/angle ${ANGLE_COMMIT})
set(ANGLE_ROOT "${angle_SOURCE_DIR}")

# zlib's headers are included as third_party/zlib/..., relative to ANGLE's root
angle_fetch(zlib chromium/src/third_party/zlib ${ANGLE_ZLIB_COMMIT} "${ANGLE_ROOT}/third_party/zlib")
angle_fetch(abseil chromium/src/third_party/abseil-cpp ${ANGLE_ABSEIL_COMMIT})
angle_fetch(spirv_headers external/github.com/KhronosGroup/SPIRV-Headers ${ANGLE_SPIRV_HEADERS_COMMIT})
angle_fetch(spirv_tools external/github.com/KhronosGroup/SPIRV-Tools ${ANGLE_SPIRV_TOOLS_COMMIT})
angle_fetch(vulkan_headers external/github.com/KhronosGroup/Vulkan-Headers ${ANGLE_VULKAN_HEADERS_COMMIT})
angle_fetch(vma external/github.com/GPUOpen-LibrariesAndSDKs/VulkanMemoryAllocator ${ANGLE_VMA_COMMIT})
angle_fetch(jsoncpp external/github.com/open-source-parsers/jsoncpp ${ANGLE_JSONCPP_COMMIT})
angle_fetch(cpu_features external/github.com/google/cpu_features ${ANGLE_CPU_FEATURES_COMMIT})

# Our patch, once
set(ANGLE_PATCH "${CMAKE_CURRENT_LIST_DIR}/Force_esVersion.patch")
set(ANGLE_PATCH_STAMP "${ANGLE_ROOT}/.alpha3_patched")
if(NOT EXISTS "${ANGLE_PATCH_STAMP}")
    execute_process(
        COMMAND patch -p1 -N -i "${ANGLE_PATCH}"
        WORKING_DIRECTORY "${ANGLE_ROOT}"
        RESULT_VARIABLE patch_result
    )
    if(NOT patch_result EQUAL 0)
        message(FATAL_ERROR "Force_esVersion.patch does not apply to ANGLE ${ANGLE_COMMIT}")
    endif()
    file(WRITE "${ANGLE_PATCH_STAMP}" "${ANGLE_PATCH}\n")
endif()

include(${CMAKE_CURRENT_LIST_DIR}/angle_sources.cmake)

# ---------------------------------------------------------------------------------------------------------------
# Generated headers: angle_commit.h and ANGLEShaderProgramVersion.h (what the gn actions make)
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

# The shader cache version is a hash of the source files that decide how programs are serialized
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
    WORKING_DIRECTORY "${ANGLE_ROOT}"      # the file names in the list are relative to ANGLE's root
    DEPENDS ${ANGLE_PROGRAM_VERSION_DEPENDS} "${ANGLE_ROOT}/src/program_serialize_data_version.py"
    COMMENT "Generating ANGLEShaderProgramVersion.h"
    VERBATIM
)
add_custom_target(angle_generated_headers DEPENDS "${ANGLE_GEN}/ANGLEShaderProgramVersion.h")

# ---------------------------------------------------------------------------------------------------------------
# abseil (ANGLE only uses flat_hash_map and flat_hash_set), built like the rest: no exceptions, no RTTI
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
set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fno-exceptions -fno-rtti -fvisibility=hidden -fvisibility-inlines-hidden -DABSL_ALLOCATOR_NOTHROW=1 -w")
if(ANGLE_THIN_LTO)
    string(APPEND CMAKE_CXX_FLAGS " -flto=thin")
endif()
add_subdirectory("${abseil_SOURCE_DIR}" "${CMAKE_CURRENT_BINARY_DIR}/abseil" EXCLUDE_FROM_ALL)
set(CMAKE_CXX_FLAGS "${angle_saved_cxx_flags}")

# ---------------------------------------------------------------------------------------------------------------
# Compile settings shared by everything below (the common part of what `gn desc` reports for each ANGLE target)
# ---------------------------------------------------------------------------------------------------------------
add_library(angle_flags INTERFACE)

target_include_directories(angle_flags INTERFACE
    "${ANGLE_ROOT}"                    # third_party/zlib/... style includes
    "${ANGLE_ROOT}/include"
    "${ANGLE_ROOT}/src"
    "${ANGLE_ROOT}/src/common/base"
    "${ANGLE_ROOT}/src/common/third_party/xxhash"
    "${abseil_SOURCE_DIR}"
    "${ANGLE_ROOT}/src/third_party/volk"
    "${vulkan_headers_SOURCE_DIR}/include"
    "${ANGLE_ROOT}/third_party/zlib/google"
    "${ANGLE_ROOT}/third_party/zlib"
    "${spirv_headers_SOURCE_DIR}/include"
    "${spirv_tools_SOURCE_DIR}/include"
    "${vma_SOURCE_DIR}/include"
    "${jsoncpp_SOURCE_DIR}/include"
    "${ANGLE_GEN}"
)

target_compile_definitions(angle_flags INTERFACE
    # Android / libc++
    __STDC_CONSTANT_MACROS __STDC_FORMAT_MACROS _GNU_SOURCE ANDROID HAVE_SYS_UIO_H
    __ANDROID_UNAVAILABLE_SYMBOLS_ARE_WEAK__ _FORTIFY_SOURCE=3 NDEBUG NVALGRIND DYNAMIC_ANNOTATIONS_ENABLED=0
    _LIBCPP_HARDENING_MODE=_LIBCPP_HARDENING_MODE_NONE
    # ANGLE
    ANGLE_ENABLE_SHARE_CONTEXT_LOCK=1 ANGLE_USE_ANDROID_TLS_SLOT=1
    ANGLE_ENABLE_GLOBAL_MUTEX_RECURSION=1 ANGLE_ENABLE_GLOBAL_MUTEX_LOAD_TIME_ALLOCATE=1
    ANGLE_ENABLE_CONTEXT_MUTEX=1 ANGLE_ENABLE_CONTEXT_MUTEX_RECURSION=1
    ANGLE_OUTSIDE_WEBKIT ANGLE_TRACE_EVENTS_IGNORE_TIMESTAMP ANGLE_USE_ABSEIL ABSL_ALLOCATOR_NOTHROW=1
    ANGLE_CAPTURE_ENABLED=0 ANGLE_EXPOSE_NON_CONFORMANT_EXTENSIONS_AND_VERSIONS
    # back ends: Vulkan (talking to the device's libvulkan) and the null one that ANGLE links in anyway
    ANGLE_ENABLE_VULKAN ANGLE_ENABLE_NULL ANGLE_ENABLE_CRC_FOR_PIPELINE_CACHE ANGLE_SHARED_LIBVULKAN=1
    ANGLE_USE_CUSTOM_VULKAN_OUTSIDE_RENDER_PASS_CMD_BUFFERS=1 ANGLE_USE_CUSTOM_VULKAN_RENDER_PASS_CMD_BUFFERS=1
    VK_USE_PLATFORM_ANDROID_KHR
    # entry points
    GL_GLES_PROTOTYPES=1 GL_GLEXT_PROTOTYPES EGL_EGL_PROTOTYPES=1 EGL_EGLEXT_PROTOTYPES
)

target_compile_options(angle_flags INTERFACE
    -O2 -g0 -fPIC -w
    -fvisibility=hidden
    -fno-delete-null-pointer-checks -fno-strict-overflow -fno-math-errno -fno-strict-aliasing -ffp-contract=off
    -fmerge-all-constants -ffunction-sections -funwind-tables -fno-short-enums -fstack-protector -mno-outline
    $<$<COMPILE_LANGUAGE:CXX>:-std=c++20>
    $<$<COMPILE_LANGUAGE:CXX>:-fno-exceptions>
    $<$<COMPILE_LANGUAGE:CXX>:-fno-rtti>
    $<$<COMPILE_LANGUAGE:CXX>:-fvisibility-inlines-hidden>
    $<$<BOOL:${ANGLE_THIN_LTO}>:-flto=thin>
)

# Shared library link settings: 16 KB pages (Android 15), drop what is unused, keep static libraries' symbols private
# The libraries carry their own C++ runtime (like the gn build's), so they do not need libc++_shared.so and stay
# independent of the STL the rest of the app uses.
set(ANGLE_LINK_OPTIONS
    -static-libstdc++
    -Wl,-z,max-page-size=16384 -Wl,--gc-sections -Wl,--icf=all -Wl,--exclude-libs,ALL -Wl,--as-needed
    -Wl,-z,defs -Wl,-z,noexecstack -Wl,-z,relro -Wl,-z,now -Wl,--build-id=sha1
    -Wl,--strip-all
    $<$<BOOL:${ANGLE_THIN_LTO}>:-flto=thin>
)

# One OBJECT library per group of sources in angle_sources.cmake: angle_obj_<group>
function(angle_objects group)
    set(sources "")
    foreach(f IN LISTS ANGLE_SRC_${group})
        list(APPEND sources "${ANGLE_ROOT}/${f}")
    endforeach()
    if(NOT sources)
        return()    # a group of headers only
    endif()
    add_library(angle_obj_${group} OBJECT ${sources})
    target_link_libraries(angle_obj_${group} PRIVATE angle_flags)
    add_dependencies(angle_obj_${group} angle_generated_headers)
    set_target_properties(angle_obj_${group} PROPERTIES POSITION_INDEPENDENT_CODE ON)
endfunction()

foreach(group
        xxhash common common_shader_state image_util gpu_info_util preprocessor translator version version_info
        spirv_base spirv_builder spirv_parser vulkan_loader vulkan_icd vk_mem_alloc volk
        null_backend vulkan_backend libangle frame_capture_mock compression
        egl_loader libEGL libGLESv1_CM libGLESv2 feature_support)
    angle_objects(${group})
endforeach()

# What the individual targets add on top of the shared settings
foreach(group libangle vulkan_backend null_backend frame_capture_mock libGLESv2)
    target_compile_definitions(angle_obj_${group} PRIVATE LIBANGLE_IMPLEMENTATION)
endforeach()
target_compile_definitions(angle_obj_vk_mem_alloc PRIVATE VMA_DYNAMIC_VULKAN_FUNCTIONS=0 VMA_STATIC_VULKAN_FUNCTIONS=0 VMA_USE_STL_CONTAINERS=1)
target_compile_definitions(angle_obj_vulkan_icd PRIVATE ANGLE_VK_LAYERS_DIR="angledata" ANGLE_VK_MOCK_ICD_JSON="angledata/VkICD_mock_icd.json")
target_compile_definitions(angle_obj_libGLESv2 PRIVATE
    LIBGLESV2_IMPLEMENTATION
    "GL_APICALL=__attribute__((visibility(\"default\")))" "GL_API=__attribute__((visibility(\"default\")))")
target_compile_definitions(angle_obj_libGLESv1_CM PRIVATE
    "GL_APICALL=__attribute__((visibility(\"default\")))" "GL_API=__attribute__((visibility(\"default\")))")
target_compile_definitions(angle_obj_libEGL PRIVATE
    LIBEGL_IMPLEMENTATION ANGLE_USE_EGL_LOADER
    "EGLAPI=__attribute__((visibility(\"default\")))"
    "ANGLE_DISPATCH_LIBRARY=\"libGLESv2_angle\"" "ANGLE_EGL_LIBRARY_NAME=\"libEGL_angle\""
    "ANGLE_GLESV2_LIBRARY_NAME=\"libGLESv2_angle\""
    "ANGLE_MESA_EGL_LIBRARY_NAME=\"mesa/src/egl/libEGL\"" "ANGLE_MESA_GLESV2_LIBRARY_NAME=\"mesa/src/mapi/es2api/libGLESv2\""
    "ANGLE_VULKAN_SECONDARIES_EGL_LIBRARY_NAME=\"libEGL_vulkan_secondaries_angle\""
    "ANGLE_VULKAN_SECONDARIES_GLESV2_LIBRARY_NAME=\"libGLESv2_vulkan_secondaries_angle\"")
target_compile_definitions(angle_obj_egl_loader PRIVATE ANGLE_USE_EGL_LOADER)
target_compile_definitions(angle_obj_feature_support PRIVATE LIBFEATURE_SUPPORT_IMPLEMENTATION)

# jsoncpp: only libfeature_support uses it
add_library(angle_obj_jsoncpp OBJECT
    "${jsoncpp_SOURCE_DIR}/src/lib_json/json_reader.cpp"
    "${jsoncpp_SOURCE_DIR}/src/lib_json/json_value.cpp"
    "${jsoncpp_SOURCE_DIR}/src/lib_json/json_writer.cpp"
)
target_include_directories(angle_obj_jsoncpp PRIVATE "${jsoncpp_SOURCE_DIR}/include" "${jsoncpp_SOURCE_DIR}/src/lib_json")
target_compile_definitions(angle_obj_jsoncpp PRIVATE JSON_USE_EXCEPTION=0 JSON_USE_NULLREF=0)
target_link_libraries(angle_obj_jsoncpp PRIVATE angle_flags)
set_target_properties(angle_obj_jsoncpp PROPERTIES POSITION_INDEPENDENT_CODE ON)

# VMA's implementation (ANGLE's own vma.cpp in the VMA checkout)
add_library(angle_obj_vma OBJECT "${vma_SOURCE_DIR}/vma.cpp")
target_compile_definitions(angle_obj_vma PRIVATE VMA_DYNAMIC_VULKAN_FUNCTIONS=0 VMA_STATIC_VULKAN_FUNCTIONS=0 VMA_USE_STL_CONTAINERS=1)
target_link_libraries(angle_obj_vma PRIVATE angle_flags)
set_target_properties(angle_obj_vma PROPERTIES POSITION_INDEPENDENT_CODE ON)

# Chromium's zlib (its symbols are renamed Cr_z_*, so the system libz cannot stand in), with the NEON paths
set(zlib_sources adler32.c compress.c cpu_features.c crc32.c deflate.c gzclose.c gzlib.c gzread.c gzwrite.c
    infback.c inffast.c inftrees.c trees.c uncompr.c zutil.c adler32_simd.c crc32_simd.c
    contrib/optimizations/inflate.c contrib/optimizations/inffast_chunk.c)
list(TRANSFORM zlib_sources PREPEND "${zlib_SOURCE_DIR}/")
add_library(angle_obj_zlib OBJECT ${zlib_sources})
target_compile_definitions(angle_obj_zlib PRIVATE
    ZLIB_IMPLEMENTATION ADLER32_SIMD_NEON INFLATE_CHUNK_SIMD_NEON INFLATE_CHUNK_READ_64LE DEFLATE_CHUNK_WRITE_64LE
    DEFLATE_SLIDE_HASH_NEON CRC32_ARMV8_CRC32 ARMV8_OS_ANDROID)
target_include_directories(angle_obj_zlib PRIVATE "${zlib_SOURCE_DIR}" "${cpu_features_SOURCE_DIR}/ndk_compat")
target_compile_options(angle_obj_zlib PRIVATE -O3)
target_link_libraries(angle_obj_zlib PRIVATE angle_flags)
set_target_properties(angle_obj_zlib PROPERTIES POSITION_INDEPENDENT_CODE ON)

# cpu_features (zlib asks it which ARM extensions the device has)
set(cpu_features_sources src/filesystem.c src/hwcaps.c src/stack_line_reader.c src/string_view.c
    src/impl_aarch64_linux_or_android.c ndk_compat/cpu-features.c)
list(TRANSFORM cpu_features_sources PREPEND "${cpu_features_SOURCE_DIR}/")
add_library(angle_obj_cpu_features OBJECT ${cpu_features_sources})
target_compile_definitions(angle_obj_cpu_features PRIVATE STACK_LINE_READER_BUFFER_SIZE=1024 HAVE_STRONG_GETAUXVAL)
target_include_directories(angle_obj_cpu_features PRIVATE "${cpu_features_SOURCE_DIR}/include" "${cpu_features_SOURCE_DIR}/ndk_compat")
target_link_libraries(angle_obj_cpu_features PRIVATE angle_flags)
set_target_properties(angle_obj_cpu_features PROPERTIES POSITION_INDEPENDENT_CODE ON)

# ---------------------------------------------------------------------------------------------------------------
# The libraries
# ---------------------------------------------------------------------------------------------------------------
function(angle_shared_library target output_name)
    cmake_parse_arguments(L "" "" "OBJECTS;LIBS" ${ARGN})
    set(objects "")
    foreach(o IN LISTS L_OBJECTS)
        if(TARGET angle_obj_${o})
            list(APPEND objects "$<TARGET_OBJECTS:angle_obj_${o}>")
        endif()
    endforeach()
    add_library(${target} SHARED ${objects})
    set_target_properties(${target} PROPERTIES OUTPUT_NAME "${output_name}" LINKER_LANGUAGE CXX)
    target_link_options(${target} PRIVATE ${ANGLE_LINK_OPTIONS})
    target_link_libraries(${target} PRIVATE ${L_LIBS})
    foreach(o IN LISTS L_OBJECTS)
        if(TARGET angle_obj_${o})
            add_dependencies(${target} angle_obj_${o})
        endif()
    endforeach()
endfunction()

# Everything libANGLE is made of
set(ANGLE_CORE_OBJECTS
    libGLESv2 libangle vulkan_backend null_backend frame_capture_mock
    common common_shader_state image_util gpu_info_util preprocessor translator
    spirv_base spirv_builder spirv_parser vulkan_loader vulkan_icd vk_mem_alloc volk
    version version_info xxhash compression vma zlib cpu_features)

angle_shared_library(angle_libGLESv2 GLESv2_angle
    OBJECTS ${ANGLE_CORE_OBJECTS}
    LIBS absl::flat_hash_map absl::flat_hash_set dl m log nativewindow)

angle_shared_library(angle_libEGL EGL_angle
    OBJECTS libEGL egl_loader common xxhash
    LIBS absl::flat_hash_map absl::flat_hash_set dl m log nativewindow)

angle_shared_library(angle_libGLESv1_CM GLESv1_CM_angle
    OBJECTS libGLESv1_CM
    LIBS angle_libGLESv2 dl m)

angle_shared_library(angle_libfeature_support feature_support_angle
    OBJECTS feature_support jsoncpp gpu_info_util common xxhash vulkan_loader vulkan_icd volk
    LIBS absl::flat_hash_map absl::flat_hash_set dl m log nativewindow)

# ---------------------------------------------------------------------------------------------------------------
# Copy to the app
# ---------------------------------------------------------------------------------------------------------------
add_custom_target(copy_angle ALL
    DEPENDS angle_libGLESv2 angle_libEGL angle_libGLESv1_CM angle_libfeature_support
    COMMAND ${CMAKE_COMMAND} -E make_directory "${jni_libs_dir}"
    COMMAND ${CMAKE_COMMAND} -E copy_if_different
        $<TARGET_FILE:angle_libGLESv2> $<TARGET_FILE:angle_libEGL>
        $<TARGET_FILE:angle_libGLESv1_CM> $<TARGET_FILE:angle_libfeature_support>
        "${jni_libs_dir}/"
    COMMENT "Copying the ANGLE libraries to jniLibs"
)
