# Release licensing checks

Run these checks for every Android APK (or AAB) and every iOS device IPA or simulator ZIP before publishing it.
The build recipe alone does not show which optional or transitive dependencies reached a particular binary, and
this list is a gate to pass, not a claim that an untested package is compliant. It is not legal advice.

Keep the finished manifest and the evidence with each published build (steps 1 and 9).

## 1. Fix the inputs of the build

The source you offer has to match the binary, so nothing in the build may move between the build and the release.
Record the exact revision of every input in the manifest:

* This repository: a git tag on a clean, committed checkout.
* Inputs that currently follow a branch and **must be pinned for a release** (set a commit hash, or record the
  commit that was fetched): `ALPHA3_VCPKG_TAG` (default `master`) and `ALPHA3_OPENMW_DEPS_TAG` (default `master`) in
  `cpp/CMakeLists.txt`, OpenMW `clockwork-alpha` in `cpp/openmw/CMakeLists.txt`, Mesa `main` in
  `cpp/Zink/CMakeLists.txt`, dethrace `main` in `cpp/dethrace/CMakeLists.txt`, UQM-MegaMod `master` in
  `cpp/MegaMod/CMakeLists.txt`.
* Inputs that are already pinned: ANGLE and its dependencies (`cpp/Angle/CMakeLists.txt`), kram
  (`cpp/kram/CMakeLists.txt`), libadrenotools (`cpp/Alpha3/CMakeLists.txt`).
* Tool versions: NDK, Xcode, CMake, Gradle, Kotlin.

## 2. Corresponding source (GPLv3 section 6)

* Publish, together with each binary, a source archive of what was built: this repository at the release tag, the
  patches in `cpp/*/patches/`, and the pinned revisions or source archives of every dependency in step 1, plus the
  scripts and instructions to build. Or give a written offer for that source, valid for at least three years and
  for as long as you ship that version, and keep the offer's promise.
* Keep the source available for as long as people can still get the binary.
* Check that the archive contains the upstream license files, the patched upstream sources and this port's
  sources, and that the build instructions in `README.md` reproduce the binary.

## 3. Look inside the finished binary

* **Android:** list the APK (`unzip -l`) and the dynamic dependencies of each library (`llvm-readelf -d`, the
  `NEEDED` entries). Expected native libraries include OpenMW and its dependencies, `libAlpha3.so`, kram, dethrace,
  UQM-MegaMod, the Turnip driver and, when `ALPHA3_BUILD_MESA_ZINK` is on, the Mesa EGL/GLES/Zink libraries, and the
  four ANGLE libraries. Resolve the dependency graph that ships
  (`./gradlew :androidApp:dependencies --configuration releaseRuntimeClasspath`, and the same for `:composeApp`),
  including transitive libraries.
* **iOS:** inspect the finished `.app` with `otool -L` and `nm`, and record the dynamic frameworks, static libraries
  and resource files.
* Update `THIRD_PARTY_NOTICES.md` from this evidence. Every library that is in the binary needs an entry; remove
  entries for things that are not.

## 4. Notices that must ship with the app

* The APK must contain `LICENSE`, `THIRD_PARTY_NOTICES.md` and `3rdparty-licenses.txt` (under
  `assets/libopenmw/`); the iOS app must have the same three in `OpenMWAssets`. The build copies them
  (`cpp/openmw/CMakeLists.txt`); check that they are really in the package.
* Check the OpenMW resources for the font license files (`DejaVuFontLicense.txt`, `DemonicLettersFontLicense.txt`,
  `MysticCardsFontLicense.txt`) and for the license files of the other bundled code.
* Make the notices reachable from inside the app and from the store page (an About or Licenses screen with the GPL
  text, the third-party notices and the link to the source). GPLv3 section 5(d) asks interactive programs to show
  their legal notices. The app does not have such a screen yet.
* Include the license and copyright texts that the permissive licenses ask for (BSD, MIT, Apache-2.0, zlib, libpng,
  ICU, OFL fonts and others). `3rdparty-licenses.txt` and `THIRD_PARTY_NOTICES.md` must stay complete for this.

## 5. LGPL and other replace-the-library rules

* FFmpeg: keep the exact `configure` options and the source. It must be LGPL only, with no GPL or nonfree options.
  If it is **statically** linked (the iOS default), provide the library source and the application's source or
  object files needed to relink a changed FFmpeg, plus instructions. If you rely on LGPL-2.1 section 3 to convert
  that copy to GPL, document the choice and check the notices and terms of every FFmpeg file included. Do not
  assume the conversion happened automatically.
* Same for OpenSceneGraph (static on iOS), libsmacker (compiled into dethrace), libiconv and any other LGPL library
  that is linked statically. On Android they are separate `.so` files, which keeps replacing them simple.
* 7-Zip-JBinding: keep its LGPL notice and the unRAR restriction statement. Do not use it to create RAR archives.

## 6. Things that must not be in the package or the repository

* No game data: no Morrowind (`.esm`, `.esp`, `.bsa`) or Carmageddon files, no Ur-Quan Masters content package. Check
  the APK, the IPA and `git ls-files`.
* No mod files that the project has no right to redistribute.
* No downloaded GPU driver binaries: drivers are downloaded by the user at run time. The catalog only links to
  them.
* The Ur-Quan Masters content is licensed non-commercially (CC BY-NC-SA 2.5). It must only be downloaded by the
  user from upstream, and if the app is sold or earns money, get advice on whether the app's own artwork taken from
  that content, if any, can stay.
* Check the provenance of every image listed in section 6 of `THIRD_PARTY_NOTICES.md`: app icons,
  `backgroundbouncebw.jpg`, `dreadnought_big_004.png`, `starmap.jpg`, `ic_launcher_foreground.webp`,
  `pointer_arrow.png`. Do not copy CaveBros' older icon attributions unless those particular assets are present.

## 7. Open items

Go through "Open items before a release" at the end of `THIRD_PARTY_NOTICES.md`. Each item is either resolved (and
the entry updated) or consciously accepted and recorded in the manifest. Today that list includes the prebuilt
GlossHook library without a license, the obfuscation header without attribution, and ML Kit Translate's terms.

## 8. Online features and privacy

* Nexus Mods: users supply their own API key; follow the Nexus API terms and each mod's permissions.
* MEGA, Internet Archive, GitHub and GitLab downloads, and ML Kit translation (models downloaded at run time):
  check that the privacy policy and the store forms (Google Play Data safety, App Store privacy labels) describe the
  network access and the data used. The app stores API keys and settings only on the device.

## 9. Distribution channel

* Check that the store's actual user terms and installation conditions allow the rights that the licenses give
  recipients. The GPL does not allow extra restrictions on recipients. Some store terms (Apple's App Store in
  particular) are widely considered hard to square with GPLv3 unless every copyright holder allows it. Decide this
  before publishing there; sideloading, TestFlight and Android stores with open terms are separate questions.
* Confirm that the repository's GPLv3 declaration covers the original Swift, Kotlin and C++ code and the patches,
  and that copied OpenMW, CaveBros, SDL, OSG and other contributors' copyright and change notices are kept.
* Create the release manifest: version, git tag, input revisions (step 1), the file list and sha256 of the
  APK/IPA and the source archive, the dependency lists (step 3), and the sign-off for each open item.
