# Third-party software, content and trademarks

Alpha3 (Android and iOS) is made of this project's own code plus a lot of other people's software. This file says
what each part is, which license applies, and how it reaches the app. It is shared by the Android app (APK) and the
iOS app (IPA).

**Status of this file.** It was written from the build files and the source trees used for the build, not from an
inspection of a finished APK or IPA, and it is not legal advice. The `Check` column says how sure each entry is:

* **Seen** - the license text or file header was read in the source used for the build.
* **Known** - the license is the one the project is publicly known to use, but the text was not read here.
* **OPEN** - the license or origin could not be confirmed. These need an answer before a release (see the list at
  the end).

Every release must be checked against the real binary, see `RELEASE_COMPLIANCE.md`.

## 1. This project

The original Kotlin, Swift and C++ code of Alpha3 and the patches in `cpp/*/patches/` are released under the
**GNU General Public License v3** (`LICENSE`). The copyright holders are the Alpha3 authors and contributors;
copyright and change notices of the projects below are kept in the files that came from them.

How the other licenses fit with GPLv3:

* MIT, BSD, zlib, Apache-2.0, Boost, public domain and similar permissive licenses can be combined with GPLv3
  (Apache-2.0 is GPLv3-compatible, not GPLv2-only-compatible). Their notices must still ship with the binaries.
* LGPL libraries (FFmpeg, OpenAL Soft, OpenSceneGraph, libiconv, libsmacker, 7-Zip/7-Zip-JBinding) have their own
  conditions about replacing the library and about notices, see section 7.
* GPLv2-or-later code (Ur-Quan Masters) may be used under GPLv3 and is also shipped as its own library.

## 2. Game engines and games

| Component | License | Check | Notes |
| --- | --- | --- | --- |
| [OpenMW](https://gitlab.com/OpenMW/openmw), built from the fork [OursCodeur/openmw](https://gitlab.com/OursCodeur/openmw) (`clockwork-alpha`) for both Android and iOS | GPLv3 | Known | Patched here: `cpp/openmw/patches/android/`, `cpp/openmw/patches/ios/` and, when enabled, `cpp/openmw/patches/multiplayer/`. The patches are the modifications and are part of the corresponding source. Earlier notes of this project also name [sisah2/openmw](https://github.com/sisah2/openmw); check the fork's history for upstream forks whose notices must be kept. |
| [CaveBros openmw-android](https://gitlab.com/cavebros/openmw-android) | GPLv3 | Known | Source lineage of the Android port. Keep its attribution for code actually copied. |
| ...bundled in OpenMW's `extern/`: sol3 (MIT), smhasher (MurmurHash, public domain), Base64, oics, osg-ffmpeg-videoplayer | Per directory | Known | Keep each directory's license file. Check the exact list in the OpenMW revision you build. |
| ...fonts in OpenMW's `resources/`: DejaVuLGCSansMono (DejaVu/Bitstream Vera license), DemonicLetters and MysticCards (SIL Open Font License 1.1) | As named | Seen | Their license texts are `DejaVuFontLicense.txt`, `DemonicLettersFontLicense.txt` and `MysticCardsFontLicense.txt` and must stay next to the fonts in the app. |
| [dethrace](https://github.com/dethrace-labs/dethrace), built from the fork `Duron27-org/dethrace` | GPLv3 (fork's `LICENSE`) | Seen | Reimplementation of Carmageddon. Needs the user's own game data, which is not included. |
| ...bundled in dethrace: BRender 1.3.2 | MIT (Argonaut Software) | Seen | |
| ...bundled in dethrace: inih | BSD-3-Clause | Seen | |
| ...bundled in dethrace: libsmacker | LGPL-2.1 | Seen | See section 7. |
| ...bundled in dethrace: miniaudio | Public domain or MIT-0 (your choice) | Seen | |
| ...bundled in dethrace: stb | Public domain or MIT (your choice) | Known | |
| ...bundled in dethrace: Dear ImGui, Dear Bindings | MIT | Seen | Listed in dethrace's `NOTICE`. |
| [UQM-MegaMod](https://github.com/JHGuitarFreak/UQM-MegaMod), the Ur-Quan Masters engine (Android) | GPLv2 or later (source headers: "either version 2 of the License, or any later version") | Seen | Used under its "or later" terms, built as a separate library. |
| Ur-Quan Masters / MegaMod game **content** (graphics, sound, music, voice) | Creative Commons BY-NC-SA 2.5 | Seen | **Non-commercial.** Not included in the app. The user downloads it from [UQM-MegaMod-Content](https://github.com/JHGuitarFreak/UQM-MegaMod-Content) from inside the app. Do not bundle it, and see the open items about artwork. |

## 3. Native libraries used on both platforms

These come from the vcpkg manifest `cpp/vcpkg-extras/vcpkg.json`, the OpenMW overlay ports
(`OpenMW/openmw-deps-build`) and the ports in `cpp/vcpkg-extras/ports/`. The list below is what the manifest asks
for; transitive dependencies (for example libpng, libjpeg-turbo, libxml2, libtiff, zlib, bzip2, liblzma, collada-dom
and libiconv from OpenSceneGraph, LuaJIT and others from the OpenMW overlay) must be listed from the real build, see
`RELEASE_COMPLIANCE.md`.

| Component | License | Check | How it is used |
| --- | --- | --- | --- |
| [FFmpeg](https://ffmpeg.org/legal.html) (avcodec, avformat, swresample, swscale) | LGPL-2.1-or-later; no GPL or nonfree options | Known | See section 7 for the linking conditions. |
| [OpenAL Soft](https://github.com/kcat/openal-soft) | LGPL-2.0-or-later | Known | Shared library. |
| [OpenSceneGraph](https://github.com/openscenegraph/OpenSceneGraph/blob/master/LICENSE.txt) as `openmw-osg` ([OpenMW/osg](https://github.com/openmw/osg)) | OpenSceneGraph Public License (LGPL-2.1 with an exception for linking) | Known | Patched in `cpp/vcpkg-extras/ports/openmw-osg/`. |
| [MyGUI](https://github.com/MyGUI/mygui) (`openmw-mygui-osx` port on iOS) | MIT | Known | Patched in `cpp/vcpkg-extras/ports/openmw-mygui-osx/` and `cpp/openmw/patches/*/MyGui350.diff`. |
| [SDL2](https://github.com/libsdl-org/SDL) | zlib | Known | |
| [Boost](https://www.boost.org) (geometry, iostreams, program_options) | Boost Software License 1.0 | Known | |
| [Bullet](https://github.com/bulletphysics/bullet3) | zlib | Known | |
| [FreeType](https://github.com/freetype/freetype/blob/master/LICENSE.TXT) | FreeType License or GPLv2-or-later (your choice) | Known | Record the option chosen: the FreeType License. |
| [ICU](https://icu.unicode.org) | Unicode License | Known | |
| [LZ4](https://github.com/lz4/lz4) | BSD-2-Clause (library) | Known | |
| [glslang](https://github.com/KhronosGroup/glslang), [SPIRV-Cross](https://github.com/KhronosGroup/SPIRV-Cross) | BSD-3-Clause and other permissive licenses (glslang); Apache-2.0 (SPIRV-Cross) | Known | Both carry a license file listing the details. |
| libvorbis, libogg (Android) | BSD-3-Clause | Known | |
| [Vulkan-Headers](https://github.com/KhronosGroup/Vulkan-Headers) (Android) | Apache-2.0 or MIT (per file) | Seen | Headers only. |
| [MoltenVK](https://github.com/KhronosGroup/MoltenVK) (iOS) | Apache-2.0 | Known | Built from source with `cpp/patches/moltenvk-tier2-sampler-limit.patch`. |
| LuaJIT (iOS, via the ports in `cpp/vcpkg-extras/ports-ios/`; Android via the OpenMW overlay) | MIT | Known | |

## 4. Native code that only Android builds

| Component | License | Check | How it is used |
| --- | --- | --- | --- |
| [Mesa](https://www.mesa3d.org) (Turnip Vulkan driver `libvulkan_freedreno.so`; Zink and EGL/GLES `libEGL_mesa.so`, `libGLESv2_mesa.so`, `libgallium_dri.so`) and the libdrm it builds | MIT for the core; some files carry other licenses, see Mesa's `docs/license.rst` and `licenses/` | Seen | Built in `cpp/Zink/`. |
| [Android Frame Pacing / Swappy](https://developer.android.com/games/sdk/frame-pacing) (`androidx.games:games-frame-pacing`) | Apache-2.0 | Seen | Static library and headers fetched from Google Maven by `cpp/CMakeLists.txt`; Java half through Gradle. |
| [bcdec](https://github.com/iOrange/bcdec) (v0.98, `components/resource/bcdec.h` in the stock OpenMW patch series) | Unlicense (public domain) | Seen | Vendored by `cpp/openmw-upstream/patches/android/0036-*.patch`; decodes DXT textures on the CPU for GPUs without S3TC. |
| [libadrenotools](https://github.com/bylaws/libadrenotools) | BSD-2-Clause (its submodules have their own licenses) | Seen | Loads the custom Vulkan driver (`cpp/Alpha3/adrenotools.cpp`). |
| [ANGLE](https://chromium.googlesource.com/angle/angle) (`libEGL_angle.so`, `libGLESv2_angle.so`, `libGLESv1_CM_angle.so`, `libfeature_support_angle.so`) | BSD-3-Clause (TransGaming, Google, 3Dlabs) | Seen | Built in `cpp/Angle/` with `Force_esVersion.patch`. Parts of ANGLE have their own notices in its source tree. |
| ...built into ANGLE: abseil-cpp | Apache-2.0 | Seen | |
| ...built into ANGLE: Chromium's zlib | zlib | Seen | |
| ...built into ANGLE: SPIRV-Headers, SPIRV-Tools headers | MIT-style (Khronos); Apache-2.0 | Seen | |
| ...built into ANGLE: VulkanMemoryAllocator | MIT | Seen | |
| ...built into ANGLE: volk | MIT | Seen | |
| ...built into ANGLE: cpu_features | Apache-2.0 | Seen | |
| ...built into ANGLE: jsoncpp | Public domain / MIT | Seen | |
| ...built into ANGLE: xxHash | BSD-2-Clause | Seen | |
| [kram](https://github.com/alecazam/kram) (`libkram.so`, texture conversion) via the fork `Duron27/kram` | MIT | Seen | Patched in `cpp/kram/patches/`. |
| ...inside kram: Arm astc-encoder | Apache-2.0 | Seen | |
| ...inside kram: bc7enc | MIT; `bc7e.ispc` is Apache-2.0 | Seen | |
| ...inside kram: Squish, fmt, lodepng, cgltf, fastl, json11, miniz, EASTL, simdjson, zstd, Etc2comp, Compressonator (AMD) and others | MIT, zlib, BSD or Apache-2.0 | OPEN | kram's README lists Etc2comp as MIT; upstream Etc2comp is Apache-2.0. Read each license file in `libkram/` for the exact source revision, and keep their notices. |
| [spdlog](https://github.com/gabime/spdlog) | MIT | Seen | `cpp/Alpha3/deps/spdlog/LICENSE`. |
| [GLAD](https://github.com/Dav1dde/glad) generated OpenGL ES 2 loader (`cpp/GLAD/`, `gles2.c` and `include/glad`, `include/KHR`) | `(WTFPL OR CC0-1.0) AND Apache-2.0` for the generated code, as stated in the file header; the Khronos headers carry their own Apache-2.0/MIT notices | Seen | Loads GL entry points for the Zink/Mesa path. |
| Kotlin port of `openmw-navmeshtool` and its [Recast](https://github.com/recastnavigation/recastnavigation) code (`composeApp/.../tools/navmesh/`) | Port of OpenMW code: GPL-3.0 like OpenMW; Recast: zlib | Seen | Opt-in alternative to the native navmesh tool. Keep Recast's zlib notice with the source. |
| GlossHook (`cpp/Alpha3/deps/memory/lib/arm64-v8a/libGlossHook.a`, header `Gloss.h`) | Not stated in the repository | **OPEN** | A prebuilt static library without source or license file, linked into `libAlpha3.so`. Its header refers to xDL (MIT). |
| String obfuscation header (`cpp/Alpha3/deps/Obfuscator/obfuscator.h`) | Not stated in the file | **OPEN** | Looks like the single-header "Obfuscate" by Adam Yaxley (Unlicense). Confirm and add the attribution. |

Drivers that users download inside the app (the **GPU driver** section of the settings) are not part of this
project and are not redistributed by it: the app only links to them and downloads them to the user's device when the
user asks. They are Turnip/Mesa builds (MIT) by their own authors. The catalog is in
`composeApp/.../utils/GpuDrivers.kt`. Each package's own `meta.json` names its author.

## 5. Kotlin, Java and Swift libraries

The direct versions are in `gradle/libs.versions.toml`, `androidApp/build.gradle.kts` and
`composeApp/build.gradle.kts`. Transitive libraries differ between Android and iOS, resolve the graph of each
release (`./gradlew :androidApp:dependencies`, `:composeApp:dependencies`).

| Library (group) | License | Check |
| --- | --- | --- |
| Kotlin and kotlinx (stdlib, coroutines, serialization), Compose Multiplatform, AndroidX (core, appcompat, activity, lifecycle, navigation, datastore, room, webkit, profileinstaller, documentfile, window), Material Icons, Hilt/Dagger, Ktor, OkHttp and Okio, Coil, Guava, Gson, protobuf-javalite (BSD-3-Clause), ReLinker, Accompanist, Reorderable, FileKit (MIT), colorpicker-compose | Apache-2.0 unless noted | Known |
| `desugar_jdk_libs` (Java API desugaring) | Apache-2.0 for the tooling; the library code taken from the OpenJDK is GPLv2 with the Classpath Exception | Known |
| [jsoup](https://jsoup.org) | MIT | Known |
| [Bouncy Castle](https://www.bouncycastle.org/licence.html) (`bcprov-jdk18on`) | Bouncy Castle License (MIT-style) | Known |
| [JGit](https://www.eclipse.org/jgit/) | Eclipse Distribution License 1.0 (BSD-3-Clause) | Known |
| [7-Zip-JBinding](https://sevenzipjbind.sourceforge.net) (`sevenzipjbinding`, used to unpack `.7z`/`.rar` mods) | LGPL-2.1 for the wrapper; 7-Zip is LGPL with the **unRAR restriction** (the unRAR code may not be used to recreate the RAR compression algorithm) | Known |
| [ML Kit Translate](https://developers.google.com/ml-kit/terms) (`com.google.mlkit:translate`) | Google's ML Kit terms of service (proprietary, not open source) | **OPEN** |
| Google Material Symbols icons (the `*_24dp_e8eaed_*` drawables) | Apache-2.0 | Known |

## 6. Artwork, fonts and other assets

* `3rdparty-licenses.txt` lists the icons carried with the OpenMW launcher UI: Font Awesome Free icons (CC BY 4.0),
  Noun Project icons (CC0 and CC BY 3.0) and the notices for libjpeg-turbo, libpng and others. It ships inside the
  app and must stay with it.
* OpenMW's resource directory carries its own font and image license files. The packaging step copies the whole
  directory; check that they are in the finished app.
* **OPEN - provenance not documented in the repository:** `composeApp/src/commonMain/composeResources/drawable/`
  `dreadnought_big_004.png`, `starmap.jpg` (look like Star Control II / Ur-Quan Masters artwork, which would fall
  under the non-commercial CC BY-NC-SA 2.5 content license), `backgroundbouncebw.jpg`, `ic_launcher_foreground.webp`,
  and the launcher icons in `androidApp/src/main/res/mipmap-*` and `iosApp/iosApp/Assets.xcassets`. Record who
  made each one and under what license, or replace it.

## 7. LGPL libraries and replacing them

The conditions of the LGPL are about letting a user replace the library with a modified version:

* **Android:** FFmpeg, OpenAL Soft, OpenSceneGraph and 7-Zip-JBinding are loaded as separate shared libraries
  (`.so` in the APK), which is the straightforward way to meet this. Keep it that way. Ship the license texts and
  offer the source of the exact versions (see `RELEASE_COMPLIANCE.md`).
* **iOS:** FFmpeg and OpenSceneGraph are built as static libraries by default and OpenAL Soft is packaged as a
  framework. For a static LGPL library provide the library source and the application's object files (or source)
  needed to relink a changed library, with instructions, or use the GPL-conversion route in LGPL-2.1 section 3 for
  that copy and document it. Do not assume the conversion happened just because the app is GPL.
* libsmacker (LGPL-2.1, in dethrace) is compiled into dethrace's library: the same rule applies, so that library
  has to be replaceable or relinkable.

## 8. Online services and what they allow

The app can talk to these services on the user's behalf. None of their content is bundled, and the user needs
their own accounts and rights:

* Nexus Mods (API key entered by the user; follow the Nexus API terms and each mod's own permissions).
* MEGA, Internet Archive (`web.archive.org`), GitHub and GitLab (mod, content and driver downloads), the
  modding-openmw.com and UESP websites (links).
* Google ML Kit translation (the on-device translation models are downloaded at run time by ML Kit).

Mods, textures and other files the user downloads keep their authors' licenses. The app's texture converter
(kram) and other tools change files on the user's device only.

## 9. Game data and trademarks

* No game data is included. The user must own and supply The Elder Scrolls III: Morrowind (and the Tribunal and
  Bloodmoon expansions), Carmageddon, or other game files themselves. Alpha3 does not circumvent copy protection
  and does not contain Bethesda, ZeniMax, Stainless Games or Toys for Bob assets other than as listed above.
* "The Elder Scrolls", "Morrowind", "Tribunal" and "Bloodmoon" are trademarks of ZeniMax Media Inc.; "Carmageddon"
  belongs to its rights holders; "Star Control" and "The Ur-Quan Masters" belong to their rights holders;
  "OpenMW" is the name of the OpenMW project. "Vulkan", "OpenGL ES" and "EGL" are trademarks of the Khronos Group;
  "Android" and "ML Kit" belong to Google; "iOS", "Metal" and "MoltenVK" belong to Apple and their authors.
* Alpha3 is an independent project. It is not made, approved or endorsed by any of the above.

## 10. Open items before a release

1. GlossHook: find the license and the source, or remove the library.
2. Obfuscator header: confirm the origin and add attribution.
3. ML Kit Translate: read Google's terms for the distribution you use (store listing, data collection) or remove it.
4. 7-Zip-JBinding: keep the LGPL notice and the unRAR note with the app.
5. UQM artwork (`dreadnought_big_004.png`, `starmap.jpg`) and every other image in section 6: document the author
   and license or replace.
6. The kram library licenses marked OPEN in section 4, and the bundled third-party code in the OpenMW tree.
7. Pin floating build inputs for a release (`vcpkg` and `openmw-deps-build` default to `master`, dethrace and
   UQM-MegaMod to `main`/`master`), so the source offered matches the binary. See `RELEASE_COMPLIANCE.md`.
8. Confirm the copyright holder names for the GPLv3 notice of this project.
