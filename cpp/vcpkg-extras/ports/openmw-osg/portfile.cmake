set(VCPKG_POLICY_DLLS_WITHOUT_EXPORTS enabled)

set(OSG_VER 3.6.5)

set(PATCHES "")

# OpenGL ES through glad, and Alpha3's runtime hooks for the support library of the upstream engine
# (cpp/openmw-upstream/support): what the rendering of stock OpenMW on OpenGL ES rests on. Shared by Android and iOS; the
# patches live in android/ because that is where they started. The hooks are no-ops unless the support library installs
# them, so the fork, which links the same OSG on Android, is unaffected.
set(ALPHA3_GLES_PATCHES
    "android/0001-Replace-Atomic-impl-with-std-atomic.patch"
    "android/0002-BufferObject-make-numClients-atomic.patch"
    "android/0004-IncrementalCompileOperation-wrap-some-stuff-in-atomi.patch"
    "android/dae_collada.patch"
    # glad's OpenGL ES 3.2 header instead of the system's (the vcpkg prefix gets glad from cpp/GLAD first)
    "android/glad.patch"
    "android/vertex-only-builtins.patch"
    "android/features.patch"
    "android/textures.patch"
    "android/msaa.patch"
    "android/polygonmode-nv.patch"
    "android/0005-CullSettings-make-inheritanceMask-atomic-to-silence-.patch"
    "android/silence-clipplane-warning.patch"
    "android/silence-alphafunc-warning.patch"
    # OpenGL ES 3.0 (Apple) has no glEnablei; the terrain sets it on every pass, so OSG warned on every draw
    "android/silence-enablei-warning.patch"
    "android/uniform-lookup-by-id.patch"
    "android/runtime-hooks.patch"
    "android/gles-texture-formats.patch"
    "android/gles-fixed-function-shims.patch"
    "android/gles-mode-uniforms.patch"
    "android/frame-and-texture-hooks.patch"
    "android/vao-custom-drawing.patch"
    # the model view projection and normal matrices are only worked out for programs that read them
    "android/lazy-matrix-uniforms.patch"
    # occlusion culling by the support library: the cull visitor asks it about each node and drawable in view
    "android/occlusion-hooks.patch"
    # the experimental graphics of the support library: state for one drawable (trees swaying), defines it adds to
    # shaders, and the state's defines in the source before it is translated (glslang runs the preprocessor)
    "android/experimental-hooks.patch"
    # a program linked again goes to a new GL program object (Adreno crashed after relinks: lighting settings toggled)
    "android/relink-new-program.patch"
)

if(VCPKG_TARGET_IS_IOS)
    list(
      APPEND PATCHES
      "macos-dedicated-image-plugin.patch"
      ${ALPHA3_GLES_PATCHES}
      # glad on iOS too (OpenGLES.framework loaded at runtime), and no OSG window: SDL makes it
      "ios/glad.patch"
      # with OSG_USE_ANGLE the GL functions come from the app's libGLESv2_angle.framework (ANGLE on Metal)
      "ios/angle.patch"
    )
elseif(VCPKG_TARGET_IS_OSX)
    list(
      APPEND PATCHES
      "macos-dedicated-image-plugin.patch"
    )
elseif(VCPKG_TARGET_IS_ANDROID)
    list(
      APPEND PATCHES
      "android/fix-freetype-include-dirs.patch"
      "android/force-add-plugins.patch"
      ${ALPHA3_GLES_PATCHES}
    )
endif()

vcpkg_from_github(
    OUT_SOURCE_PATH SOURCE_PATH
    REPO openmw/osg
    REF 638f0a1e73687633fd99bf110d04226e78ff69c6
    SHA512 9549619551aa42ba65e8dddd977f4a13c73295108f70b3027d74b3b6d93af82866233f33f71877bbdcf6edb316c5c9c1647bda0df0b73230ca06c473a962efae
    HEAD_REF 3.6
    PATCHES ${PATCHES}
)

file(REMOVE
    "${SOURCE_PATH}/CMakeModules/FindFontconfig.cmake"
    "${SOURCE_PATH}/CMakeModules/FindFreetype.cmake"
    "${SOURCE_PATH}/CMakeModules/Findilmbase.cmake"
    "${SOURCE_PATH}/CMakeModules/FindOpenEXR.cmake"
    "${SOURCE_PATH}/CMakeModules/FindSDL2.cmake"
)

string(COMPARE EQUAL "${VCPKG_LIBRARY_LINKAGE}" "dynamic" OSG_DYNAMIC)

set(OPTIONS "")

# Skip try_run checks
if(VCPKG_TARGET_IS_MINGW)
    list(APPEND OPTIONS -D_OPENTHREADS_ATOMIC_USE_WIN32_INTERLOCKED=0 -D_OPENTHREADS_ATOMIC_USE_GCC_BUILTINS=1)
elseif(VCPKG_TARGET_IS_WINDOWS)
  list(APPEND OPTIONS
    -D_OPENTHREADS_ATOMIC_USE_WIN32_INTERLOCKED=1
    -D_OPENTHREADS_ATOMIC_USE_GCC_BUILTINS=0
    -DPKG_CONFIG_USE_CMAKE_PREFIX_PATH=ON
  )
elseif(VCPKG_TARGET_IS_OSX)
  list(APPEND OPTIONS
    -DOSG_WINDOWING_SYSTEM=Cocoa
  )
elseif(VCPKG_TARGET_IS_IOS)
  set(OSG_DYNAMIC OFF)
  # OSG's iOS mode force-sets CMAKE_OSX_SYSROOT to a hardcoded iPhoneOS<10.2>.sdk, which breaks on current
  # Xcode and can't target the simulator. Keep the sysroot the vcpkg toolchain already resolved.
  vcpkg_replace_string("${SOURCE_PATH}/CMakeLists.txt"
    "SET(CMAKE_OSX_SYSROOT \"\${IPHONE_SDKROOT}\" CACHE STRING \"System root for iOS\" FORCE)"
    "# sysroot is provided by the vcpkg toolchain")
  vcpkg_replace_string("${SOURCE_PATH}/CMakeLists.txt"
    "SET(OPENGL_gl_LIBRARY \"\${IPHONE_SDKROOT}/System/Library/Frameworks/OpenGLES.framework\")"
    "SET(OPENGL_gl_LIBRARY \"\${CMAKE_OSX_SYSROOT}/System/Library/Frameworks/OpenGLES.framework\")")
  list(APPEND OPTIONS
    -D_OPENTHREADS_ATOMIC_USE_GCC_BUILTINS=1
    -D_OPENTHREADS_ATOMIC_USE_BSD_ATOMIC=0
    -DOPENGL_PROFILE=GLES3
    -DOSG_BUILD_PLATFORM_IPHONE=ON
    -DOSG_WINDOWING_SYSTEM=None
    -DDYNAMIC_OPENTHREADS=OFF
    -DDYNAMIC_OPENSCENEGRAPH=OFF
    -DBUILD_OSG_PLUGIN_OSG=ON
    -DBUILD_OSG_PLUGIN_DAE=ON
    -DBUILD_OSG_PLUGIN_DDS=ON
    -DBUILD_OSG_PLUGIN_KTX=ON
    -DBUILD_OSG_PLUGIN_TGA=ON
    -DBUILD_OSG_PLUGIN_BMP=ON
    -DBUILD_OSG_PLUGIN_JPEG=ON
    -DBUILD_OSG_PLUGIN_PNG=ON
    -DBUILD_OSG_PLUGIN_FREETYPE=ON
    -DBUILD_OSG_APPLICATIONS=OFF
    -DBUILD_OSG_PLUGINS_BY_DEFAULT=OFF
    -DBUILD_OSG_DEPRECATED_SERIALIZERS=OFF
    -DOSG_CPP_EXCEPTIONS_AVAILABLE=TRUE
    -DOSG_GLU_AVAILABLE=OFF
    -DOSG_GL1_AVAILABLE=OFF
    -DOSG_GL2_AVAILABLE=OFF
    -DOSG_GL3_AVAILABLE=OFF
    -DOSG_GLES1_AVAILABLE=OFF
    -DOSG_GLES2_AVAILABLE=OFF
    -DOSG_GLES3_AVAILABLE=ON
    )
elseif(VCPKG_TARGET_IS_ANDROID)
  set(OSG_DYNAMIC OFF)
  list(APPEND OPTIONS
    -DOSG_CPP_EXCEPTIONS_AVAILABLE=TRUE
    -DOSG_GLU_AVAILABLE=OFF
    -DOSG_GL1_AVAILABLE=OFF
    -DOSG_GL2_AVAILABLE=OFF
    -DOSG_GL3_AVAILABLE=OFF
    -DOSG_GLES1_AVAILABLE=OFF
    -DOSG_GLES2_AVAILABLE=OFF
    -DOSG_GLES3_AVAILABLE=ON
    )
elseif(VCPKG_CROSSCOMPILING)
    message(WARNING "Atomics detection may fail for cross builds. You can set osg cmake variables in a custom triplet.")
endif()

# The package osg can be configured to use different OpenGL profiles via a custom triplet file:
# Possible values are GLCORE, GL2, GL3, GLES1, GLES2, GLES3, and GLES2+GLES3
if(NOT DEFINED osg_OPENGL_PROFILE)
    if(VCPKG_TARGET_IS_ANDROID OR VCPKG_TARGET_IS_IOS)
      set(osg_OPENGL_PROFILE "GLES3")
    else()
      set(osg_OPENGL_PROFILE "GL2")
    endif()
endif()

vcpkg_cmake_configure(
    SOURCE_PATH "${SOURCE_PATH}"
    OPTIONS
        -DDYNAMIC_OPENSCENEGRAPH=${OSG_DYNAMIC}
        -DDYNAMIC_OPENTHREADS=${OSG_DYNAMIC}
        -DOSG_MSVC_VERSIONED_DLL=OFF
        -DOSG_DETERMINE_WIN_VERSION=OFF
        -DOSG_FIND_3RD_PARTY_DEPS=OFF
        -DOSG_DISABLE_MSVC_WARNINGS=OFF
        -DOPENGL_PROFILE=${osg_OPENGL_PROFILE}
        -DBUILD_DASHBOARD_REPORTS=OFF
        -DCMAKE_CXX_STANDARD=11
        -DOSG_TEXT_USE_FONTCONFIG=OFF
        -DBUILD_OSG_PLUGINS_BY_DEFAULT=OFF
        -DBUILD_OSG_PLUGIN_OSG=ON
        -DBUILD_OSG_PLUGIN_DDS=ON
        -DBUILD_OSG_PLUGIN_TGA=ON
        -DBUILD_OSG_PLUGIN_BMP=ON
        -DBUILD_OSG_PLUGIN_JPEG=ON
        -DBUILD_OSG_PLUGIN_PNG=ON
        -DBUILD_OSG_PLUGIN_FREETYPE=ON
        -DBUILD_OSG_PLUGIN_DAE=ON
        -DBUILD_OSG_PLUGIN_KTX=ON
        -DBUILD_OSG_DEPRECATED_SERIALIZERS=OFF
        -DBUILD_OSG_APPLICATIONS=OFF
        -DBUILD_OSG_EXAMPLES=OFF
        -DBUILD_DOCUMENTATION=OFF
        ${OPTIONS}
    MAYBE_UNUSED_VARIABLES
        OSG_DETERMINE_WIN_VERSION
)
vcpkg_cmake_install()
vcpkg_copy_pdbs()

if(VCPKG_LIBRARY_LINKAGE STREQUAL "static")
    file(APPEND "${CURRENT_PACKAGES_DIR}/include/osg/Config" "#ifndef OSG_LIBRARY_STATIC\n#define OSG_LIBRARY_STATIC 1\n#endif\n")
endif()

file(REMOVE_RECURSE
    "${CURRENT_PACKAGES_DIR}/debug/include"
    "${CURRENT_PACKAGES_DIR}/debug/share"
)

vcpkg_replace_string("${CURRENT_PACKAGES_DIR}/lib/pkgconfig/openscenegraph.pc" "\\\n" " ")
if(NOT VCPKG_BUILD_TYPE)
    vcpkg_replace_string("${CURRENT_PACKAGES_DIR}/debug/lib/pkgconfig/openscenegraph.pc" "\\\n" " ")
endif()
vcpkg_fixup_pkgconfig()

file(COPY "${CMAKE_CURRENT_LIST_DIR}/usage" DESTINATION "${CURRENT_PACKAGES_DIR}/share/${PORT}")
file(INSTALL "${SOURCE_PATH}/LICENSE.txt" DESTINATION "${CURRENT_PACKAGES_DIR}/share/${PORT}" RENAME copyright)
