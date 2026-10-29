cmake_minimum_required(VERSION 3.22.1)

project(openmw-android)

set(OPENMW_SRC "" CACHE PATH "Path to a local OpenMW source checkout")

if(ALPHA3_IS_ANDROID)
    string(APPEND CMAKE_SHARED_LINKER_FLAGS " -Wl,-Bsymbolic")
    string(APPEND CMAKE_MODULE_LINKER_FLAGS " -Wl,-Bsymbolic")
    string(APPEND VCPKG_LINKER_FLAGS " -Wl,-Bsymbolic")
endif()

# Multiplayer (Lua networking, avatars, host/join menu). ON applies patches/multiplayer and ships the Lua mod
# (multiplayer/mod, from the OpenMW source) as resources/vfs-mp; the launcher then adds it to the global openmw.cfg.
option(ALPHA3_MULTIPLAYER "Apply the multiplayer patches to OpenMW" ON)

# Both platforms build the same OpenMW source (clockwork-alpha); each has its own patch set (patches/android,
# patches/ios) and install step.
if(ALPHA3_IS_ANDROID)
    set(OPENMW_BUILD_TOOLS 1)
else()
    set(OPENMW_BUILD_TOOLS 0)
endif()

if(ALPHA3_MULTIPLAYER)
    set(OPENMW_MP_MOD_IOS COMMAND ${CMAKE_COMMAND} -E copy_directory <SOURCE_DIR>/multiplayer/mod ${ALPHA3_IOSAPP_DIR}/OpenMWAssets/resources/vfs-mp)
else()
    set(OPENMW_MP_MOD_IOS "")
endif()
if(ALPHA3_MULTIPLAYER)
    set(OPENMW_MP_MOD_ANDROID COMMAND ${CMAKE_COMMAND} -E copy_directory <SOURCE_DIR>/multiplayer/mod ${assets_dir}/libopenmw/resources/vfs-mp)
else()
    set(OPENMW_MP_MOD_ANDROID "")
endif()

if(OPENMW_SRC)
    set(OPENMW_SOURCE_ARGS SOURCE_DIR ${OPENMW_SRC})
else()
    set(OPENMW_SOURCE_ARGS
            GIT_REPOSITORY https://gitlab.com/OursCodeur/openmw.git
            GIT_TAG clockwork-alpha
            DOWNLOAD_DIR ${download_dir}
    )
endif()

set(OPENMW_COMMON
    -DBUILD_BSATOOL=0
    -DBUILD_NIFTEST=0
    -DBUILD_ESMTOOL=0
    -DBUILD_LAUNCHER=0
    -DBUILD_MWINIIMPORTER=0
    -DBUILD_ESSIMPORTER=0
    -DBUILD_OPENCS=0
    -DBUILD_NAVMESHTOOL=${OPENMW_BUILD_TOOLS}
    -DBUILD_WIZARD=0
    -DBUILD_BULLETOBJECTTOOL=0
    -DOPENMW_USE_SYSTEM_SQLITE3=OFF
    -DOPENMW_USE_SYSTEM_YAML_CPP=OFF
    -DOPENMW_USE_SYSTEM_ICU=ON
    -DOPENAL_INCLUDE_DIR=${prefix}/include/AL/
    -DOSG_STATIC=TRUE
    -DCMAKE_CXX_FLAGS=-std=gnu++20\ -I${prefix}/include/\ ${ALPHA3_SWAPPY_INCLUDE}\ ${VCPKG_CXX_FLAGS}
    -DMyGUI_LIBRARY=${prefix}/lib/libMyGUIEngineStatic.a
    -Dcollada_dom_DIR=${prefix}/share/collada-dom
)

set(OPENMW_INSTALL_COMMAND "")
if(ALPHA3_IS_ANDROID)
    set(OPENMW_PATCH_DIR ${CMAKE_CURRENT_SOURCE_DIR}/patches/android)

    list(APPEND OPENMW_COMMON
        # GL comes from glad (built by the vcpkg step in the top-level CMakeLists), which loads the system GLES
        # driver or the ANGLE one at runtime, instead of linking libGLESv3 directly
        -DOPENGL_gl_LIBRARY=${prefix}/lib/libglad.so
        -DOPENGL_INCLUDE_DIR=${prefix}/include
        # The NDK's Vulkan headers (1.3.x) are older than the Vulkan renderer needs, use vcpkg's
        -DOPENMW_USE_VULKAN=ON
        # FindVulkan builds "sysroot/usr/lib/<arch>/<api>/libvulkan.so" from CMAKE_LIBRARY_ARCHITECTURE, which is empty
        # in this configure ("usr/lib//34/"), so name the NDK's stub library directly
        -DVulkan_LIBRARY=${NDK_TOOLCHAIN_BIN}/../sysroot/usr/lib/${NDK_TRIPLET}/${ANDROID_API}/libvulkan.so
        # The Vulkan renderer needs the SPIRV-Tools optimizer inside glslang (it prunes unused vertex inputs); vcpkg's
        # glslang has none, so build the glslang and SPIRV-Tools the fork pins.
        -DOPENMW_USE_SYSTEM_GLSLANG=OFF
        -DVulkan_INCLUDE_DIR=${prefix}/include
        "-DCMAKE_SHARED_LINKER_FLAGS=${VCPKG_LINKER_FLAGS} ${ALPHA3_EXTRA_LIBS}"
        "-DCMAKE_EXE_LINKER_FLAGS=${VCPKG_LINKER_FLAGS} ${ALPHA3_EXTRA_LIBS}"
    )

    set(OPENMW_PATCH
        patch -d <SOURCE_DIR> -p1 -t -N < ${OPENMW_PATCH_DIR}/androidPath.patch &&
        patch -d <SOURCE_DIR> -p1 -t -N < ${OPENMW_PATCH_DIR}/makeLibs.patch &&
        patch -d <SOURCE_DIR> -p1 -t -N < ${OPENMW_PATCH_DIR}/ktx.patch &&
        patch -d <SOURCE_DIR> -p1 -t -N < ${OPENMW_PATCH_DIR}/vulkanExtras.patch &&
        patch -d <SOURCE_DIR> -p1 -t -N < ${OPENMW_PATCH_DIR}/SDL_opengles2_gl2ext.patch &&
        patch -d <SOURCE_DIR> -p1 -t -N < ${OPENMW_PATCH_DIR}/MyGui350.diff &&
        patch -d <SOURCE_DIR> -p1 -t -N < ${OPENMW_PATCH_DIR}/GLES-3-OMW.patch &&
        patch -d <SOURCE_DIR> -p1 -t -N < ${OPENMW_PATCH_DIR}/glad.patch &&
        patch -d <SOURCE_DIR> -p1 -t -N < ${OPENMW_PATCH_DIR}/osmesa.patch &&
        patch -d <SOURCE_DIR> -p1 -t -N < ${OPENMW_PATCH_DIR}/fragment-matrices.patch &&
        patch -d <SOURCE_DIR> -p1 -t -N < ${OPENMW_PATCH_DIR}/resolution-picker.patch &&
        patch -d <SOURCE_DIR> -p1 -t -N < ${OPENMW_PATCH_DIR}/gles-renderer.patch &&
        patch -d <SOURCE_DIR> -p1 -t -N < ${OPENMW_PATCH_DIR}/gles-live-switch.patch &&
        patch -d <SOURCE_DIR> -p1 -t -N < ${OPENMW_PATCH_DIR}/swappy.patch &&
        patch -d <SOURCE_DIR> -p1 -t -N < ${OPENMW_PATCH_DIR}/s3tc-software-decode.patch &&
        patch -d <SOURCE_DIR> -p1 -t -N < ${OPENMW_PATCH_DIR}/gles-effects-fixes.patch &&
        patch -d <SOURCE_DIR> -p1 -t -N < ${OPENMW_PATCH_DIR}/framegen.patch &&
        patch -d <SOURCE_DIR> -p1 -t -N < ${OPENMW_PATCH_DIR}/gles-clip-distance.patch &&
        patch -d <SOURCE_DIR> -p1 -t -N < ${OPENMW_PATCH_DIR}/framegen-ratio-software.patch &&
        patch -d <SOURCE_DIR> -p1 -t -N < ${OPENMW_PATCH_DIR}/gles-depth-clamp.patch &&
        patch -d <SOURCE_DIR> -p1 -t -N < ${OPENMW_PATCH_DIR}/gles-clip-enable.patch &&
        patch -d <SOURCE_DIR> -p1 -t -N < ${OPENMW_PATCH_DIR}/logcatSTDout.patch &&
        patch -d <SOURCE_DIR> -p1 -t -N < ${OPENMW_PATCH_DIR}/logFatalError.patch &&
        patch -d <SOURCE_DIR> -p1 -t -N < ${OPENMW_PATCH_DIR}/android-discrete-shadows.patch &&
        patch -d <SOURCE_DIR> -p1 -t -N < ${OPENMW_PATCH_DIR}/live-renderer-switch-fixes.patch &&
        patch -d <SOURCE_DIR> -p1 -t -N < ${OPENMW_PATCH_DIR}/android-no-normals-blend.patch &&
        # Vulkan below 1.2: kept apart from the rest, see patches/vulkan-compat/README.md
        patch -d <SOURCE_DIR> -p1 -t -N < ${CMAKE_CURRENT_SOURCE_DIR}/patches/vulkan-compat/android-gles-fallback.patch
        COMMAND ${CMAKE_COMMAND} -E copy_if_different ${OPENMW_PATCH_DIR}/androidmain.cpp <SOURCE_DIR>/apps/openmw/androidmain.cpp
    )

    set(OPENMW_INSTALL_COMMAND
        INSTALL_COMMAND ${CMAKE_COMMAND} -E make_directory ${assets_dir}/libopenmw/openmw
        # a vfs-mp left by an earlier multiplayer build must not outlive it
        COMMAND ${CMAKE_COMMAND} -E remove_directory ${assets_dir}/libopenmw/resources/vfs-mp
        COMMAND ${CMAKE_COMMAND} -E copy_directory resources ${assets_dir}/libopenmw/resources
        ${OPENMW_MP_MOD_ANDROID}
        COMMAND ${CMAKE_COMMAND} -E copy defaults.bin gamecontrollerdb.txt ${assets_dir}/libopenmw/openmw/
        COMMAND ${CMAKE_COMMAND} -E copy libopenmw.so ${jni_libs_dir}/
        COMMAND ${CMAKE_COMMAND} -E copy libopenmw-navmeshtool.so ${jni_libs_dir}/
        COMMAND ${CMAKE_COMMAND} -E copy ${PROJECT_ROOT}/settings.fallback.cfg ${assets_dir}/libopenmw/openmw/
        COMMAND ${CMAKE_COMMAND} -E copy ${PROJECT_ROOT}/openmw.cfg ${assets_dir}/libopenmw/openmw/
        COMMAND ${CMAKE_COMMAND} -E copy_directory ${ALPHA3_COMPOSEAPP_DIR}/UI ${assets_dir}/libopenmw/ui
        COMMAND ${CMAKE_COMMAND} -E copy ${ALPHA3_REPO_DIR}/3rdparty-licenses.txt ${assets_dir}/libopenmw/
        COMMAND ${CMAKE_COMMAND} -E copy ${ALPHA3_REPO_DIR}/LICENSE ${ALPHA3_REPO_DIR}/THIRD_PARTY_NOTICES.md ${assets_dir}/libopenmw/
    )
else()
    set(OPENMW_PATCH_DIR ${CMAKE_CURRENT_SOURCE_DIR}/patches/ios)

    set(OPENMW_PATCH
        # MyGui350.diff disabled: MyGUI now comes from the openmw-mygui-osx port (3.4.3)
        # patch -d <SOURCE_DIR> -p1 -t -N < ${OPENMW_PATCH_DIR}/MyGui350.diff
        patch -d <SOURCE_DIR> -p1 -t -N < ${OPENMW_PATCH_DIR}/OpenMW_iOS_2.patch
        COMMAND patch -d <SOURCE_DIR> -p1 -t -N < ${OPENMW_PATCH_DIR}/SDL_opengles2_gl2ext.patch
        COMMAND patch -d <SOURCE_DIR> -p1 -t -N < ${OPENMW_PATCH_DIR}/vulkanExtras.patch
        COMMAND patch -d <SOURCE_DIR> -p1 -t -N < ${OPENMW_PATCH_DIR}/metal-atomic-hit.patch
    )

    # iOS: GLES comes from the SDK (no glad), Vulkan from the static MoltenVK, and the
    # Apple frameworks are linked straight into libopenmw. Results go into iosApp.
    # The SDK only has GLES3, so the fixed-function GL paths need converting.
    list(APPEND OPENMW_PATCH
        COMMAND patch -d <SOURCE_DIR> -p1 -t -N < ${OPENMW_PATCH_DIR}/GLES-3-OMW.patch
    )
    set(OPENMW_IOS_FRAMEWORKS
        OpenGLES Metal QuartzCore IOSurface UIKit Foundation CoreGraphics CoreFoundation
        AudioToolbox AVFoundation CoreMedia CoreVideo CoreAudio CoreHaptics GameController
        CoreMotion CoreBluetooth Security QuartzCore)
    # vcpkg's static freetype pulls in brotli for WOFF2 fonts
    set(OPENMW_IOS_LINK_FLAGS "${VCPKG_LINKER_FLAGS} -lc++ -lbrotlidec -lbrotlicommon ${MOLTENVK_LIBRARY}")
    foreach(fw IN LISTS OPENMW_IOS_FRAMEWORKS)
        string(APPEND OPENMW_IOS_LINK_FLAGS " -framework ${fw}")
    endforeach()
    list(APPEND OPENMW_COMMON
        -DOPENGL_gl_LIBRARY=${GLES_LIBRARY}
        -DOPENGL_INCLUDE_DIR=${GLES_INCLUDE}
        -DMOLTENVK_LIBRARY=${MOLTENVK_LIBRARY}
        -DVulkan_LIBRARY=${MOLTENVK_LIBRARY}
        -DVulkan_INCLUDE_DIR=${MOLTENVK_INCLUDE_DIR}
        -DCMAKE_SHARED_LINKER_FLAGS=${OPENMW_IOS_LINK_FLAGS}
        -DCMAKE_EXE_LINKER_FLAGS=${OPENMW_IOS_LINK_FLAGS}
        -DBUILD_MYGUI_PLUGIN=0
        -DLUA_HAS_CUSTOM_ALLOCATOR=ON
    )
    set(ios_assets_dir "${ALPHA3_IOSAPP_DIR}/OpenMWAssets")
    # openmw.cfg points at ../Resources/resources (macOS bundle layout); the iOS bundle keeps everything
    # next to it in OpenMWAssets, so rewrite those paths during install.
    alpha3_ios_framework(ios_openmw_framework libopenmw <BINARY_DIR>/OpenMW.app/Contents/MacOS/libopenmw.dylib)
    set(ios_cfg_fixup "${CMAKE_CURRENT_BINARY_DIR}/fix_openmw_cfg.cmake")
    file(WRITE ${ios_cfg_fixup} [=[
file(READ "${CFG}" content)
string(REPLACE "../Resources/resources" "resources" content "${content}")
file(WRITE "${CFG}" "${content}")
]=])
    # libopenmw.dylib -> libopenmw.framework (+ data files) in iosApp
    set(OPENMW_INSTALL_COMMAND
        INSTALL_COMMAND ${CMAKE_COMMAND} -E make_directory ${ios_assets_dir}
        COMMAND ${CMAKE_COMMAND} -E remove_directory ${ios_assets_dir}/resources
        COMMAND ${CMAKE_COMMAND} -E copy_directory resources ${ios_assets_dir}/resources
        ${OPENMW_MP_MOD_IOS}
        # Lua libs (util.lua, content.lua) are generated into the app bundle's Resources on Apple, not ./resources
        COMMAND ${CMAKE_COMMAND} -E copy_directory OpenMW.app/Contents/Resources/resources/lua_libs ${ios_assets_dir}/resources/lua_libs
        COMMAND ${CMAKE_COMMAND} -E copy defaults.bin gamecontrollerdb.txt openmw.cfg ${ios_assets_dir}/
        COMMAND ${CMAKE_COMMAND} -E copy ${ALPHA3_COMPOSEAPP_DIR}/settings.cfg ${ios_assets_dir}/
        # GPL text and third-party notices travel with the app
        COMMAND ${CMAKE_COMMAND} -E copy ${ALPHA3_REPO_DIR}/LICENSE ${ALPHA3_REPO_DIR}/THIRD_PARTY_NOTICES.md ${ALPHA3_REPO_DIR}/3rdparty-licenses.txt ${ios_assets_dir}/
        COMMAND ${CMAKE_COMMAND} -DCFG=${ios_assets_dir}/openmw.cfg -P ${ios_cfg_fixup}
        ${ios_openmw_framework}
    )

endif()

# Multiplayer (Lua networking, avatars, host/join menu): numbered git-format patches, applied last so they
# sit on top of the platform patches. Same set for Android and iOS.
if(ALPHA3_MULTIPLAYER)
    file(GLOB OPENMW_MULTIPLAYER_PATCHES ${CMAKE_CURRENT_SOURCE_DIR}/patches/multiplayer/*.patch)
    list(SORT OPENMW_MULTIPLAYER_PATCHES)
    set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS ${OPENMW_MULTIPLAYER_PATCHES})
    foreach(mp_patch IN LISTS OPENMW_MULTIPLAYER_PATCHES)
        # git apply, not patch: the series has binary files (textures) and new files
        list(APPEND OPENMW_PATCH COMMAND git -C <SOURCE_DIR> apply --whitespace=nowarn ${mp_patch})
    endforeach()
endif()

if(NOT OPENMW_SRC)
    # A previous (possibly failed) patch run leaves the clone half-patched; start from a clean tree.
    set(OPENMW_PATCH
        git -C <SOURCE_DIR> checkout -- .
        COMMAND git -C <SOURCE_DIR> clean -fdq
        COMMAND ${OPENMW_PATCH}
    )
endif()

ExternalProject_Add(openmw
    DEPENDS vcpkg

    ${OPENMW_SOURCE_ARGS}

    UPDATE_COMMAND ""
    BUILD_ALWAYS 1

    PATCH_COMMAND ${OPENMW_PATCH}

    CONFIGURE_COMMAND ${wrapper_command} cmake <SOURCE_DIR>
    ${VCPKG_CMAKE_CONFIGURE_OPTIONS}
    ${OPENMW_COMMON}
    BUILD_COMMAND ${wrapper_command} make -j${NPROC}

    ${OPENMW_INSTALL_COMMAND}

    USES_TERMINAL_BUILD ON
    USES_TERMINAL_INSTALL ON
)

# iOS device: archive and export a signed .ipa
#   cmake --build <build dir> --target device_ipa
# Signing comes from iosApp.xcodeproj's Release configuration (manual signing, distribution identity);
# the export step re-signs with the provisioning profile named below. Run the native build first
# (buildNativeIosDevice), the Xcode build phase would otherwise start it from inside this target.
if(ALPHA3_IS_IOS AND ALPHA3_PLATFORM STREQUAL "ios")
    set(ALPHA3_IPA_TEAM_ID "J7W2R8H393" CACHE STRING "Apple developer team used to sign the .ipa")
    set(ALPHA3_IPA_BUNDLE_ID "org.alpha3.launcher.Alpha3" CACHE STRING "Bundle identifier of the app")
    set(ALPHA3_IPA_PROFILE "Alpha3 App Store Profile" CACHE STRING "Provisioning profile name used for export")
    set(ALPHA3_IPA_EXPORT_METHOD "app-store-connect" CACHE STRING
        "xcodebuild export method: app-store-connect, release-testing, enterprise or debugging")

    set(ipa_dir "${CMAKE_BINARY_DIR}/ipa")
    set(ipa_archive "${ipa_dir}/Alpha3.xcarchive")
    set(ipa_export "${ipa_dir}/export")
    set(ipa_options "${ipa_dir}/ExportOptions.plist")
    file(WRITE ${ipa_options} "<?xml version=\"1.0\" encoding=\"UTF-8\"?>
<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">
<plist version=\"1.0\"><dict>
 <key>method</key><string>${ALPHA3_IPA_EXPORT_METHOD}</string>
 <key>teamID</key><string>${ALPHA3_IPA_TEAM_ID}</string>
 <key>signingStyle</key><string>manual</string>
 <key>provisioningProfiles</key>
 <dict><key>${ALPHA3_IPA_BUNDLE_ID}</key><string>${ALPHA3_IPA_PROFILE}</string></dict>
 <key>compileBitcode</key><false/>
 <key>stripSwiftSymbols</key><true/>
</dict></plist>
")

    add_custom_target(device_ipa
        COMMAND ${CMAKE_COMMAND} -E remove_directory ${ipa_archive} ${ipa_export}
        COMMAND xcodebuild archive
                -project ${ALPHA3_IOSAPP_DIR}/iosApp.xcodeproj
                -scheme iosApp
                -configuration Release
                -destination generic/platform=iOS
                -archivePath ${ipa_archive}
        COMMAND xcodebuild -exportArchive
                -archivePath ${ipa_archive}
                -exportPath ${ipa_export}
                -exportOptionsPlist ${ipa_options}
        COMMAND ${CMAKE_COMMAND} -E echo "Signed IPA: ${ipa_export}/Alpha3.ipa"
        COMMENT "Archiving and exporting the signed device .ipa"
        USES_TERMINAL
        VERBATIM
    )
endif()
