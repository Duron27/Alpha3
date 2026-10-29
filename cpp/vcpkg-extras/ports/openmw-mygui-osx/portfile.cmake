vcpkg_from_github(
  OUT_SOURCE_PATH SOURCE_PATH
  REPO MyGUI/mygui
  REF MyGUI3.4.3
  SHA512 88c69ca2e706af364b72d425f95013eb285501881d8094f8d67e31a54c45ca11b0eb5b62c382af0d4c43f69aa8197648259ac306b72efa7ef3e25eecb9b039cb
  PATCHES
    "mygui.patch"
)

set(CMAKE_BUILD_TYPE "Release")

# iOS has no loadable dylibs for this: OpenMW links MyGUI statically into libopenmw
set(MYGUI_EXTRA_OPTIONS "")
if(VCPKG_TARGET_IS_IOS)
  set(MYGUI_EXTRA_OPTIONS -DMYGUI_STATIC=ON)
endif()

vcpkg_cmake_configure(
  SOURCE_PATH "${SOURCE_PATH}"
  OPTIONS
    -DMYGUI_RENDERSYSTEM=1
    -DMYGUI_BUILD_DEMOS=OFF
    -DMYGUI_BUILD_TOOLS=OFF
    -DMYGUI_BUILD_PLUGINS=OFF
    -DMYGUI_DONT_USE_OBSOLETE=ON
    ${MYGUI_EXTRA_OPTIONS}
)

vcpkg_cmake_install()
vcpkg_fixup_pkgconfig()
file(REMOVE_RECURSE
    "${CURRENT_PACKAGES_DIR}/debug/include"
    "${CURRENT_PACKAGES_DIR}/debug/share"
)
vcpkg_install_copyright(FILE_LIST "${SOURCE_PATH}/COPYING.MIT")
