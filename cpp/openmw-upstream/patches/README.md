# Patches for stock OpenMW (Android and iOS)

`common/*.patch` is a `git format-patch` series on top of the commit pinned as `OPENMW_UPSTREAM_COMMIT` in
`../CMakeLists.txt`, applied on every platform. It started as the Android series: what only Android needs is inside
`#ifdef ANDROID` / `if (ANDROID)`, so it builds unchanged for iOS. `ios/*.patch` is a second series on top of it, applied
on iOS only. Both are applied in order by `cmake/apply_patch.cmake` (`git apply`, skipped when already applied), and the
multiplayer patch after them. Nothing here is shared with the fork's patches in `cpp/openmw`.

The series is kept small on purpose, because OpenMW changes every day and every hunk can stop applying. What does not
have to be in OpenMW's files lives elsewhere:

* `../support/` is Alpha3's support library, compiled into the engine (patch 0004 adds the directory to the build).
  It does the OpenGL ES shader translation and merging, the shader program binary cache, Swappy frame pacing and frame
  times, frame generation (`OPENMW_FRAMEGEN`, ported from the fork's framegen patches, in the swap hook), DXT to ASTC/ETC2 conversion and the CPU DXT decoder, the Zink (OSMesa) context, mimalloc behind `operator new`,
  and logcat output. Most of it works through OSG's `RuntimeHooks` and needs no call from the engine. Its settings are
  still read from `settings.cfg` (`[Textures]`, `[Shaders] program binary cache`, `[General] allocator`,
  `[Video] renderer`), through `Settings::Manager`.
* `cpp/vcpkg-extras/ports/openmw-osg/android/` holds the OSG side: `runtime-hooks.patch` (the hooks; no-ops unless the
  support library installs them, so the fork, which links the same OSG, is unaffected), `gles-texture-formats.patch`
  (sized depth formats, float source types, no mipmapped depth textures, depth for shadow comparison on OpenGL ES,
  replacing the engine's own workarounds), `gles-fixed-function-shims.patch` (MyGUI's client array calls on OpenGL ES,
  switched on by `-DOSG_GLES_FIXED_FUNCTION_SHIMS` in `../CMakeLists.txt`) and `gles-mode-uniforms.patch` (clip planes as
  the eye-space uniforms `osg_ClipPlane<n>`, which shaders that do not declare them never see; the `programLinked`
  hook; the 32 bit float depth formats of OpenGL ES 3 named `GL_ARB_depth_buffer_float`) and `frame-and-texture-hooks.patch`
  (`textureImageLoading`, `contextClosing`, `renderingTraversals`), and
  `vao-custom-drawing.patch` (with `OSG_VERTEX_BUFFER_HINT=VAO`, what draws outside Drawable::draw, the MyGUI renderer
  and the terrain composite maps, runs on VAO 0; arrays custom drawing hands a VAO, like the particles' single normal,
  become an attribute value or get a buffer) and `lazy-matrix-uniforms.patch` (`osg_ModelViewProjectionMatrix` and
  `osg_NormalMatrix` are worked out when a program that reads them draws, the normal matrix from cofactors).
  OSG changes about once a year.
* `apps/openmw/androidmain.cpp` is ours and upstream does not change it, so JNI and Android-only engine glue goes there.
* Multiplayer (`ALPHA3_MULTIPLAYER`): its new files and their build registration are in `../support/multiplayer` (copied
  into the tree by the patch step); `multiplayer/*.patch` is one patch with only the edits to OpenMW's own files. See
  `../support/multiplayer/README.md`.

## Moving to a newer OpenMW

    git clone https://gitlab.com/OpenMW/openmw.git && cd openmw
    git checkout <old pinned commit>
    git am /path/to/alpha3_aio/cpp/openmw-upstream/patches/common/*.patch
    git tag alpha3-common
    git am /path/to/alpha3_aio/cpp/openmw-upstream/patches/ios/*.patch
    git rebase <new commit>            # fix conflicts
    rm /path/to/.../patches/common/*.patch /path/to/.../patches/ios/*.patch
    git format-patch --no-signature --zero-commit <new commit>..alpha3-common -o /path/to/.../patches/common
    git format-patch --no-signature --zero-commit alpha3-common -o /path/to/.../patches/ios

(after the rebase, move the tag to the rebased last common commit before the second format-patch)

Then set `OPENMW_UPSTREAM_COMMIT` to the new commit, and check that the multiplayer patch (`multiplayer/*.patch`, applied
after these, with `../support/multiplayer/tree` copied in first) still applies; rebase it the same way. The support library is compiled against OpenMW's `Settings::Manager`, `Log` and
`Files::AndroidPath` and against OSG; a rebase that changes those shows up as a compile error there, not as a patch that
does not apply. `tools/check-shaders.sh <patched tree>` validates the GLSL text translation (`../support/src/glesdialect.cpp`)
offline with glslangValidator; it needs glslang and g++ on the host.

## Contents

1. Android paths and the `getPathToJni` JNI entry (`OPENMW_ANDROID_CACHE_PATH` overrides the cache directory)
2. MyGUI 3.5 compatibility
3. `androidmain.cpp` (player state JNI, copied from the game on the engine's main thread each frame through the support
   library's `renderingTraversals` hook; surface hooks, frame times for the launcher, the image manager for the
   texture preload) and the `libopenmw_upstream.so` output name
4. Alpha3's support library in the engine's build
5. Zink: the window uses the support library's OSMesa context (`Alpha3::ZinkContext`)
6. `[Video] renderer` (`opengl` = Zink, anything else = GLES 3.2), chosen by the support library before the window is made
7. List the data directories side by side when starting (and keep the listings in the cache directory)
8. Resolution list offers fractions of the native size and resizes the surface in place
9. Allow the pass normals buffer of the post processor
10. Shadow casters smaller than a few texels are culled
11. Groundcover vertex shader skips instances past the fade distance and distant wind
12. Shaders: the terrain composite quad, and the Adreno compiler workaround in lib/light/util.glsl for clustered lighting
13. KTX files stand in for DDS files of the same name (one call in `registerArchives`; the rest is `../support/src/ktxtextures.cpp`)
14. Archive and content file reads come from a memory mapping (`ConstrainedFileStreamBuf`; content files use it too):
    on FUSE storage every 8 KiB read and every position query was a round trip to another process
15. The ESM readers cache keeps up to a quarter of RLIMIT_NOFILE content files open (100 to 2048), not 100
16. Skinning (`RigGeometry::cull`): no allocation per frame, a branchless weighted sum, no perspective divide per vertex
17. The time step follows the interval Swappy shows the frames at (`alpha3PacedFrameSeconds`, `../support/src/framepacing.cpp`)

`ios/`, from the fork's iOS patches (`cpp/openmw/patches/ios/upstream`), rebased onto the common series:

1. OpenMW is a shared library on iOS (`libopenmw.dylib`, wrapped into `libopenmw.framework` for the app, which calls its
   `main`), with UIKit instead of Cocoa and no macOS bundling
2. No crash catcher on iOS (ptrace and fork are not allowed), and no modal fatal error box
3. `OPENMW_LOCAL_CONFIG_DIR` points the engine at the app's global config
4. Point resolution (no HiDPI drawable) and no `USE_GRAPHICSWINDOW` (OSG has no window system on iOS; SDL makes the window)
5. Render stages return to the context's default framebuffer object (iOS has no framebuffer 0)
6. No `-noall_load` when linking for iOS
7. An OpenGL ES 3.0 context (Apple's OpenGL ES stops at 3.0), OpenGLES.framework linked, and the support library built
   into the engine as on Android. Its iOS half translates the shaders to `#version 300 es` (`OPENMW_GLES_VERSION=30`,
   set by `OpenMWLauncher.swift`) and reports the context to the engine as GLSL 3.3 without compute shaders;
   `../support/src/ios.cpp` stands in for Swappy, frame generation, Zink and mimalloc. OSG is built with the same GLES
   patches as on Android plus `vcpkg-extras/ports/openmw-osg/ios/glad.patch`.

## Where the earlier patches went

The series had 36 patches until 2026-10-25. Logcat output, glad loading, the OSG windowing system warning, Swappy, frame
times, the shader translation, merging, dump and glslang/SPIRV-Cross conversion, the program binary cache, texture
conversion (ASTC, ETC2, bcdec, the cache store), the BGR swap of DDS images (both on OSG's DDS reader hook), mimalloc
and the navmesh tool's link libraries moved to `../support`; the depth and float texture formats, the depth clamp mode,
the empty debug line draw and MyGUI's client arrays moved to OSG (see above).

OpenMW MR 5502 (clip planes through clip distances and uniforms) was dropped on 2026-10-26: it will not be merged
upstream. Stock OpenMW's clipping (osg::ClipPlane, gl_ClipVertex) works as it is: OSG keeps each plane in eye
coordinates as the uniform `osg_ClipPlane<n>` (`vcpkg-extras/ports/openmw-osg/android/gles-mode-uniforms.patch`), the
GLSL ES translation turns the gl_ClipVertex assignment into gl_ClipDistance writes against it, and on Zink the plane goes
to glClipPlane.

Also on 2026-10-26: the .ktx preference went (kram is built for the fork only; stock OpenMW converts DXT itself), the
GLSL ES literal fixes went (glslang takes the desktop dialect, which allows them), the depth clamp of the shadow casters
moved to the GLSL ES translation (shadowcasting.vert's main is wrapped to clamp gl_Position.z; OpenMW enables
GL_DEPTH_CLAMP for its shadow casters only, and an OSG uniform following the mode never turned on in the shadow pass),
and reverse-z's depth format to OSG.

The .ktx preference came back on 2026-10-27, as one hunk: the fork's patch (`cpp/openmw/patches/android/ktx.patch`: the
`osgdb_ktx` plugin, a priority table in resourcehelpers, a variant lookup in the image manager) is
`support/src/ktxtextures.cpp` here. It adds an archive, listed last, that gives each `x.ktx` the name `x.dds` (so it
wins over any `.dds` of the data directories and BSAs, and the engine's own lookups find it), and installs a DDS reader
for the image manager that passes KTX data (recognised by its identifier) to OSG's KTX reader, flipping a bottom up image
OSG can flip and refusing one it cannot, as the fork's patch did. The launcher's texture pre-pass skips a DDS that has a
.ktx.

Later on 2026-10-26 the support library took over the version checks: its `extensionsRead` hook reports OpenGL ES 3.2 to
the engine as GL/GLSL 4.6 (3.3 without the compute shader conversion or the storage block and work group limits), so
stock OpenMW turns on compute shader ripples, clustered lighting and GLSL 330+ post processing techniques (with uniform
blocks) by itself; the ripples, light manager and post processor version hunks went. The ripples' `scaling` patch went
too: uniform initializers, which the GLES translation drops, are set on each program after it links (OSG's
`programLinked` hook, in `gles-mode-uniforms.patch`).

The texture preload went to `../support/src/texturepreload.cpp` the same day: the first DDS image read starts it (the
image manager comes from `androidmain.cpp`), the list is what OSG uploads into textures (`textureImageLoading` hook),
and it stops when the viewer closes its context (`contextClosing` hook), before the image manager is destroyed. Both
hooks are in `vcpkg-extras/ports/openmw-osg/android/frame-and-texture-hooks.patch`. The cache directory moved to shared
storage (`/storage/emulated/0/Alpha3/cache`, set by the app through `OPENMW_ANDROID_CACHE_PATH`) so a reinstall keeps
it; shared storage has no flock, so the ASTC and program stores lock a file in `ALPHA3_LOCK_DIR` instead.
