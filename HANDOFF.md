# Handoff: Alpha3 (2026-11-02)

Alpha3 is an Android launcher plus a build of stock OpenMW. Repo: `/home/arinmal/AndroidStudioProjects/alpha3`, branch `main`. Nothing from this session is pushed.

## Rules the user has set (follow them)
- **Commit only when asked.** Commit messages end with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. The user likes small separate commits so they can revert one feature during testing.
- **Before installing on the phone**, run `adb -s RF8N124YRQX shell pidof com.alpha3.launcher`. It must print nothing (the game is closed). Install with `adb -s RF8N124YRQX install -r <apk>`.
- **Keep engine features in the support library** (`cpp/openmw-upstream/support/`, wired in through OSG RuntimeHooks) and the openmw-osg vcpkg port. OpenMW patch hunks (`cpp/openmw-upstream/patches/common/00NN-*.patch`, git format-patch style) stay minimal: just a call into support.
- **Trees:** the tree generator in the openmw-oblivion project is `treegrower.*`. Never call it SpeedTree, and never use the leaked SDK.
- **Shaders:** GLSL 1.20 style, checked with `glslangValidator`. Texture units 11–15 are reserved on the phone (8 is the rain map, 9 the footprints).

## Build
- `./gradlew :androidApp:assembleRelease -q` builds `androidApp/build/outputs/apk/release/androidApp-release.apk`.
- OpenMW is patched and built as an ExternalProject in `androidApp/.cxx/RelWithDebInfo/3p164l22/arm64-v8a/openmw-upstream/openmw_upstream-prefix/src/openmw_upstream`. That tree is the place to read engine source.
- Engine log on the phone: `adb logcat` (OpenMW logs through `logcat.cpp`).

## Commits made this session (on top of `3a2203d`)
| Commit | Content |
|---|---|
| `5219973` | F2 menu: ground mist pass, cloud tuning, live-gated options |
| `e6b5b22` | Occlusion buffer default 768 wide (was 2048) |
| `8c1abbc` | Thermal: view, grass and shadow distances follow phone temperature (patch 0023) |
| `a4aa2ec` | TODO updates |
| `20e9dad` | Grass fire: fire and lightning spells set grass alight (patch 0024, `grassfire.cpp`, `grassfire.glsl`, `fireparticles.glsl`) |
| `0ab5399` | Wet ground: rain drips off things (`drips.glsl`), footprints |

## The open problem: 60 → 40 fps
- A tester got 60 fps at a fixed spot in a save on `8cbbd0a`, and 40 fps with the newest APK, using the same settings. The cause is unknown.
- Suspects, all changed since `8cbbd0a`:
  - everything in `3a2203d`: F2 passes for water, clouds, air, mist and sharpen; wet ground; fuller leaves; birds
  - occlusion buffer 2048 → 768
  - thermal backoff
  - grass fire shader injection into the terrain
  - wet ground maps, footprint cameras and drips
- The benchmark below was built to find it. **Next step: get the two benchmark `.json` files from the tester and run `benchcompare.py` on them.**

## Benchmark (UNCOMMITTED, in the working tree)
**Files:**
- `support/src/benchmark.cpp` (new)
- `support/buildid.cmake` (new): writes `generated/buildid.hpp` with `ALPHA3_BUILD_COMMIT` and `ALPHA3_BUILD_TIME`
- `tools/benchcompare.py` (new)
- small hooks:
  - `experimental.cpp`: the "benchmark" option on the Testing page (`a3_testing`), `setGrassOn`, `benchmarkMenu()` in `experimentalLiveUpdate`
  - `occlusionculling.cpp`: `occlusionOn`/`occlusionPause`
  - `wetground.cpp`: `wetGroundPause`, `lastRain`
  - `hooks.cpp`:
    - `benchmarkFrame` in `renderingTraversals`
    - `benchmarkDrawableAdded` in `drawableAdded`
    - `benchmarkGlInfo` in `extensionsRead`
  - `support.hpp`: declarations
  - `support/CMakeLists.txt`: the source file and the `alpha3_build_id` target

**What it does:**
- **Trigger:** in the F2 post-processing window, enable the `a3_testing` technique, tick "benchmark" and close the window. It turns itself off afterwards.
- **Run:** 5 s warmup, then 15 phases of 2.5 s settle plus 5 s measure:
  - baseline
  - no shadows (`MWShadowTechnique::disableShadows(true)`, current state read through a member pointer)
  - removed with the scene camera's cull mask: terrain, groundcover, statics, objects, actors, water, sky, effects, the whole scene
  - support-library toggles: no grass, no occlusion culling, no wet ground extras
  - baseline again (shows heat drift)
- **Measured:**
  - frame intervals (mean and percentiles)
  - all OSG viewer and camera stats per frame, read 3 frames late through the locked `osg::Stats::report()`, with "rendering", "gpu" and "scene" collection switched on; GPU time comes from timer queries
  - per phase: `/proc/stat` CPU busy, process cores, thermal zones, battery temperature, CPU and GPU clocks, GPU busy
- **Header:** build, device properties, GL strings, environment, eye position and look direction, rain, all settings, and the F2 values (`shaders.yaml`).
- **Holds the view:** OpenMW's idle vanity camera starts after 30 s without input (`files/data/scripts/omw/camera/camera.lua`). The benchmark pushes an SDL mouse motion with zero movement every 0.5 s to reset that timer (`holdView`).
- **Output:** `/storage/emulated/0/Alpha3/benchmarks/benchmark-<date>-<commit>.json` plus a `.txt` table with `data\t` lines that the next run reads to compare against. An on-screen MyGUI panel (`DialogBG` + `MonoFont`, layer "Notification") shows the table until F2 opens, with "then" columns comparing against the previous run.
- **PC side:** `python3 cpp/openmw-upstream/tools/benchcompare.py A.json B.json` prints a fairness check, the baseline, per-part costs ranked by growth, and the stats that changed most. With one file it prints a summary.
- **Status:** builds. Its output has not been seen from a real run yet, so the first real run is the test: the panel layout, the stat key names (the script expects "GPU draw time taken", "Cull traversal time taken" and similar on camera `SceneCam`), and whether the zero mouse motion stops the idle pan.
- **Commit:** when the user asks, commit it as one commit, e.g. "Benchmark: the frame measured part by part, saved and compared (F2 Testing page)".

## Test APKs on the Desktop
- `~/Desktop/Alpha3-NEW-0ab5399-40fps-benchmark.apk`: the current tree with the benchmark. Installed on the user's phone. Started from F2.
- `~/Desktop/Alpha3-OLD-8cbbd0a-60fps-benchmark.apk`: `8cbbd0a` with the benchmark backported.
  - That commit has no F2 pages, wet ground or forced weather, so those are stubbed.
  - It starts by itself once the world has been drawn for 20 s after a load.
- `handoff/build_both.sh` rebuilds both:
  1. builds NEW
  2. stashes `cpp/`
  3. checks out `8cbbd0a`
  4. brings the benchmark files over from the stash
  5. applies `handoff/adapt_old.py`
  6. builds OLD
  7. restores `main` and pops the stash (in a trap)
- Each switch rebuilds OpenMW, about 5–10 minutes. Check `git stash list` afterwards; it should be empty.

## Phone test setup (user's phone, adb RF8N124YRQX)
- **Test mod:** `/storage/emulated/0/Alpha3/OpenMW/Mods/Alpha3Test/`
  - `alpha3-test.omwscripts` → `PLAYER: scripts/alpha3test/player.lua`
  - effects: 20000 magicka kept topped up, magic skills at 100, 17 fire and shock spells
  - It is enabled in the profile `openmw.cfg` (`data=` and `content=` lines). The backup is `openmw.cfg.before-alpha3-test`; restore it to remove the mod.
- `thermal mode = auto` is set in the profile's `settings.cfg`.

## Other pending items
- **Grass fire:**
  - Last tuning, from user feedback:
    - spreads at 1/4 the speed and 1/3 the size of the first version
    - regrows in half the time (30 s setting, about 15 s regrowth)
    - flames redone as fbm turbulence teardrops
  - The user has not confirmed it now looks realistic.
  - Tuning lives in `support/src/grassfire.cpp` (`struck()`, `update()`) and `experimental/fireparticles.glsl`.
- **Footprints and drips** need `[Experimental] wet ground = true`. Drips also need "dry under objects".
- Untracked `HANDOFF.md` and `handoff/` are this note and its scripts. They aren't meant to be committed unless the user wants them.

## Seamless cells and doors (2026-11-05, UNCOMMITTED, waiting for the user to try it)
**Off by default since 2026-11-08** (user's request): `seamless cells`, `seamless doors` (and so live cells, patch 0027)
are `false` in `alpha3.cfg` and in `seamless.cpp`'s fallbacks, and `[Shadows] point light shadows` is `0` (`alpha3.cfg`,
`pointshadows.cpp`). Profiles made before keep whatever their settings.cfg says.
Files: `support/src/seamless.cpp` (settings), `support/src/doorportal.cpp` (the stencil portal),
`support/include/alpha3/upstream.hpp`, `support/CMakeLists.txt`, `cpp/settings/alpha3.cfg` (`[Cells]`), and patch
`0024-Seamless-cells-no-loading-screen-and-doors-open-onto.patch` (scene.cpp quiet listener; new
`mwworld/seamlessdoors.cpp/.hpp`; hooks in `mwclass/door.cpp`, `worldimp.cpp`, `apps/openmw/CMakeLists.txt`).
- `[Cells] seamless cells`: no fade or loading screen on any cell change after the first. Loading still happens
  synchronously, so there is a hitch while the cell loads.
- `[Cells] seamless doors` (`mwworld/seamlessdoors.cpp`): a door between an exterior and an interior (or two
  interiors) works both ways. From outside, the real door is hidden and the interior's door is drawn in the portal
  swinging into the room. From inside, the real door swings into the room and the portal shows outside: objects within
  7000 units, terrain chunks, a sky gradient from the weather's fog and sky colours with the weather's clouds over it (support
  `doorportal.cpp` clear pass; `weather.cpp` passes them through `Alpha3::setOutsideClouds`), and fog. The interior's and the
  exterior's NPCs and creatures (up to 16) idle in the portal. Both sides' content is built 4 ms a frame while the door
  swings (1.1 s), so crossing builds nothing.
- Walking through: `Alpha3::doorwayPassable` (asked by `actorconvexcallback.cpp` and `movementsolver.cpp`'s contact
  callback) lets the player through walls, the door, and a lintel in a box around the doorway (depth 100). Floors still
  hold them, except upward faces more than 12 above the door's floor within 40 of the plane: interior doorways have a
  sill and a low lintel (141 high at the Cornerclub) that used to stop the player going out. Crossing happens when the
  player's centre passes the door plane: `changeToCell(..., false)`, then `moveObject`/`rotateObject` to the same place
  and heading, then the other door opens behind them.
- Tested on the tablet at Balmora, South Wall Cornerclub: in and back out by walking, NPCs seen through the door both
  ways. Crossing costs OpenMW's cell change, about 70 ms in and 110–160 ms out. `preload cell cache max = 25` (in
  `alpha3.cfg`) stops the preloader warning and helped a little.
- Test: `scratchpad`-style `setup.sh` loads the save, teleports out of the Cornerclub and faces the door from 175
  units. Activate with the button at (685,1338), walk with the left stick (swipe from 455,1190), turn with the right
  stick (swipe from 2053,1265). (An earlier "input freeze" was a turn swipe that missed the stick; not a bug.)
- Crossing hitch profiled (simpleperf, 2026-11-05): of ~140 ms going out, ~90 ms CPU is OpenMW's `Scene::insertCell`
  for the 9 exterior cells: model cloning 24%, NPC animation build 22%, physics addObject 20%. Removing it means
  loading the cell over several frames (an engine change, not started).

## Texture memory: every texture is held twice (found in openmw-xenon, 2026-11-08, NOT applied here)
Found while porting Alpha3's Vulkan renderer to the Xbox 360 (`~/AndroidStudioProjects/openmw-xenon`, which copies
`cpp/openmw-upstream/support/src/vulkan/` at `15ef7f8` into `renderer/from-alpha3/`). It applies to Alpha3 as it
stands; nothing in this repo has been changed.

- **How textures load:** OpenMW reads a DDS straight out of the BSA through a stream over that file's slice of the
  archive (`Files::openConstrainedFileStream`, an 8 KB buffer; the archive is never unpacked or held). OSG's DDS
  reader keeps the DXT1/3/5 blocks and mipmaps compressed in an `osg::Image`, and the Vulkan renderer maps them to
  BC1-3 (`resources.cpp`, the `GL_COMPRESSED_*_S3TC_*` cases) and uploads the blocks as they are. Kept compressed, a
  512x512 texture with mips is ~171 KB (DXT1) or ~341 KB (DXT5) against ~1.33 MB as RGBA8. Only uncompressed sources
  (TGA/BMP, generated maps) go up as RGBA8, RGB padded to 4 bytes.
- **The waste:** after the upload the `osg::Image` stays in the heap, so each texture sits once in CPU memory and once
  in GPU memory. OSG would drop it only for textures with `setUnRefImageDataAfterApply(true)`, and OpenMW never sets
  that on NIF textures: `engine.cpp` calls `SceneManager::setUnRefImageDataAfterApply(false)` ("keep to Off for now to
  allow better state sharing"), and `SceneManager::mUnRefImageDataAfterApply` is set but never read anywhere. Only
  MyGUI's static textures set it. Alpha3's Vulkan renderer doesn't look at the flag at all. This likely holds for the
  GL path too, since NIF textures never get the flag (not checked on Alpha3).
- **The fix in openmw-xenon** (`Resources::releaseImages` in `renderer/from-alpha3/src/vulkan/resources.cpp`, under
  `__XSDK__`; here it would go in `cpp/openmw-upstream/support/src/vulkan/resources.cpp`): after a successful upload in `Resources::texture()`, if every image of the texture has a file name and
  isn't `DYNAMIC`, call `texture.setImage(i, nullptr)` for each and set the entry's `mSource` to null, so the next
  frame's "same source" check keeps the upload instead of retrying. Images the game writes into (fog of war, the
  global map overlay, terrain blend maps) have no file name and are left alone. OpenMW's image cache keeps the image
  a few seconds longer (`cache expiry delay`), so models loading meanwhile still find it. The GPU copy lives as long
  as the `osg::Texture` (it's never evicted while the texture is alive), so the pixels are never needed again.
- **Reads of `getImage(0)` checked:** `shadervisitor.cpp` (normal and specular map names: runs when a model loads,
  before upload, and null-checks), `terrain/material.cpp` (normal map format, null-checked), `texturereport.cpp`
  (debug). All tolerate a null image.
- **Risk:** the draw thread clears `texture->_image` while a loading thread might read it (the SharedStateManager
  comparing textures). The image cache still holds the image, so a stale read isn't a freed one, but it is a data race.
  Shared-state merging also no longer matches an uploaded texture with a new one of the same image, so that image can
  end up on the GPU twice.
- **Why it matters:** on the console (512 MB shared) Seyda Neen had 206 MB of heap in use with 591 cached images, and
  16 MB free. How much the release frees there isn't measured yet: the build reports textures released and MB of
  pixels in `user/stats.log`. On Alpha3, measure before and after in the same save, e.g. heap and RSS from
  `dumpsys meminfo com.alpha3.launcher`, if phones run short of memory.

## Touch cursor movement and the cursor magnifier (2026-11-08, committed)
**Cursor smoothness** (`androidApp/.../SdlEngineBridge.kt`; every relative mouse move from the right thumbstick,
dynamic buttons and touch areas goes through `onNativeMouse` there):
- Cause, measured on the SM-T720 (screenrecord + frame-by-frame cursor tracking, `getevent -lt`): the panel reports a
  slow drag in 1-px steps, x and y in separate reports, a slow diagonal being runs of 3-5 steps on one axis then the
  other. The controls apply a fixed ~2 game px per screen px (4 screen px at the 1280x800 window), so the finger's
  wobble showed as stairs ("etch a sketch"). SDL2's `Android_OnMouse` also casts motion to int, dropping fractions.
- Fix, in order: pointer acceleration while OpenMW's cursor is shown (`GAIN_SLOW` 0.45 under `ACCEL_SLOW` 150 game
  px/s, `GAIN_FAST` 1.4 over `ACCEL_FAST` 1500, smoothstep between; look mode keeps plain gain), a 1€ filter run per
  display frame on Choreographer (`MIN_CUTOFF_HZ` 1.0, `BETA` 0.02), then the int remainder carried (`carryX/Y`, as
  `IosEngineBridge` already did). Clicks flush the waiting motion first. `MouseIcon` draws at `getMouseX() +
  Engine.mouseFractionX()` so the arrow moves between game pixels.
- What did not work: the fraction carry alone, and a fixed 20 ms smoothing (the user found that worse). The user called
  the acceleration build "perfect, or at least better". iOS has only the carry.

**Cursor magnifier** (`[Experimental] cursor magnifier = false`, `cursor magnifier zoom = 2.0`, `cursor magnifier size =
140` dp): Android's `android.widget.Magnifier` on SDL's SurfaceView (`AndroidSystemServices.showMagnifier`, Android
10+), driven each frame by `CursorMagnifier` in `ui/controls/mouse.kt`. With the cursor shown the lens is centred on
OpenMW's cursor point (sub-pixel), and `pointer_arrow.png` is drawn over it as the Magnifier's overlay at MouseIcon's size
and offset, tip on the centre: the magnified spot is where the click lands. In look mode it sits on the screen centre
(crosshair), no arrow. The SurfaceView's buffer is the [Video] resolution stretched over the view (1280x800 on the
2560x1600 tablet); Magnifier reads the buffer as if view sized, so the source point is scaled by buffer/view and the zoom
divided by it (without that it sampled twice as far out at 1x). Clipping is off so the lens is not pushed in at the
screen edge. The switch is a small zoom button on the right thumbstick's top-right (`UIStateManager.magnifierOn`),
there while the setting is on. Checked on the tablet's main menu 2026-11-08. The settings editor only lists keys already
in the profile's settings.cfg, and existing profiles do not get new keys from `alpha3.cfg`; they were added to the
tablet's by hand.

## Lua mod creator (2026-11-08) — compiles, host tests pass, NOT yet tried on a device

Entry: Construction Set start screen → "Lua mods". Code in `composeApp/src/commonMain/kotlin/org/alpha3/launcher/cs/lua/`:
- `LuaApi.kt` — loads `composeResources/files/lua_api.json`, the API catalog (46 modules, 293 types, 793 functions,
  38 handlers, 22 built-in events). The catalog is generated by `cpp/openmw-upstream/tools/lua_api_catalog.py` (re-run it
  after an engine update). It reads:
  - the engine's LuaDoc (`assets/libopenmw-upstream/resources/lua_api`, vfs interfaces, openmw_aux)
  - multiplayer `net`/`modstore` + `docs.patch` (applied in a temp dir)
  - `tools/lua_api_extra/` (undocumented multiplayer bindings `setMovementOverride`, `addMainMenuButton`)
  - handlers found in the engine source (`mwlua/*scripts.hpp`, `_`-prefixed ones skipped)

  The docs come from the built engine tree under `androidApp/.cxx/.../openmw_upstream`. `contexts()` reads per-function
  limits from the doc text ("Can be called only from a global script").
- `LuaModel.kt` — the mod: scripts by kind, handlers (`Trigger`), blocks, values (`Expr`) and settings. It is saved as
  `alpha3-luamod.json` in the mod folder.
- `LuaGen.kt` — generates readable Lua, `.omwscripts` and the l10n settings page. A call the script kind can't make is
  routed as an event to the first world or player script, which is created if missing. Saved variables get
  `onSave`/`onLoad`.
- `LuaParser.kt` + `LuaCheck.kt` — a LuaJIT parser and a checker for:
  - syntax
  - requires and functions that aren't allowed in the script kind
  - unknown members, with suggestions
  - argument counts
  - handler names
  - unhandled events
  - unset names

  Problems map back to blocks through the line map.
- `ModTemplates.kt` — 13 templates (HUD, settings, events world↔player, console commands, saved state…).
- `LuaModStore.kt` — mods live in `<alpha3_cs>/lua/<id>/`. "Save & enable" adds `data=` + `content=<id>.omwscripts`.
- `ui/` — `ChainMenu.kt` (radial step-by-step picker plus search), `Editors.kt` (block, value, trigger and settings
  dialogs), `LuaModCreator.kt` (mod list, template gallery, editor with scripts | blocks | Lua/Problems/Help, undo).

Tests: `./gradlew :composeApp:testAndroidHostTest --tests '*LuaCreatorTest*'`. They cover:
- the parser reading all of multiplayer and the engine scripts
- the checker on the multiplayer mod
- every template compiling with `luajit -bl`
- routing

Next for the creator:
- try it on the tablet; tune the layout on the phone
- record id pickers from the CS Document
- live `reloadlua` after saving
- show Lua errors from the log
- drag to reorder

## Vulkan: where to pick up

- Fixed 2026-11-08 (3dfdc44): on phones held landscape, `VK_SUBOPTIMAL_KHR` (no pre-rotation) rebuilt the swap chain
  every frame. That also made the cursor magnifier's PixelCopy fail and flicker. It now rebuilds only when the surface's
  transform or extent changes (`support/src/vulkan/context.cpp`).
- Next ("keep going with interaction"): continue the Vulkan renderer's interaction work. See the plan in
  `~/.claude/plans/expressive-riding-neumann.md` and the memory note "Vulkan 1.0 renderer plan" (to-do and tablet
  restore list in it). Open items there: the view out from inside through seamless doors (grey, dark sun direction);
  pre-rotation (`preTransform` = currentTransform) is optional now.

### 2026-11-09: point light shadows fixed (f51b08a), CPU recording work (4544076)

- **Committed f51b08a:** with point light shadows on, many pipelines failed (VK_ERROR_UNKNOWN on Adreno), so objects and terrain lit by a shadowed light vanished about 1 s after loading.
  - Cause: a program's defines were applied per stage. OSG applies the union of all the program's shaders' defines to every stage, so `A3_PS_ON` differed between vertex and fragment and the stage interfaces mismatched.
  - Also in that commit: glslang preprocessing before varying planning, a varying-limit warning, and failed shaders dumped to `/storage/emulated/0/Alpha3/dump_shaders`.
- **Committed 4544076** (all in `support/src/vulkan/`, builds and runs, 0 pipeline failures, scene correct):
  - `programs.cpp/hpp`:
    - `blockLights()` moves `LightBuffer`, `PointLightCount` and `a3PointShadowSlots` into their own UBO `a3LightUniforms` (set 0, binding 3).
    - Samplers now start at binding 4.
    - `Variant::mLightUniforms`/`mLightStages` hold the light block's layout and stages.
  - `translator.cpp`:
    - Three state blocks (vertex, fragment, lights). Blocks with volatile uniforms (OpenMW's per-frame light-list state sets) are streamed once per frame per key and shared through `mSharedBlocks`.
    - The key is variant + stage + the chain state sets the volatile uniforms come from (+ the template, unless the block holds only volatile uniforms). The old per-template `mCopies` is gone.
    - `refresh()` rewrites only uniforms whose modified count changed and skips volatile ones. It used to copy and rewrite every block every frame.
    - Kept descriptor sets per frame slot (`DrawTemplate::Kept`) are reused while buffers, images and `Resources::mImageGeneration` don't change.
    - A float fast path in `writeUniform`.
    - A single-pass `fingerprint()` for `step()`.
  - `context.cpp/hpp`: `allocateKeptSet`/`freeKeptSetLater` (pools with FREE_DESCRIPTOR_SET_BIT).
  - `resources.cpp/hpp`: `mImageGeneration`.
  - `vk.hpp`: `vkFreeDescriptorSets`.
- **Not measured yet:** the last change (keying shared blocks on state set pointers instead of a per-draw `getUniform` lookup) is built and installed on the tablet. Its measurement was stopped. Next: run `measure.sh vulkan`, check the screenshot, then profile.
- **Numbers so far** (SM-T720, Balmora autosave at the door, idle camera off; point light shadows, seamless doors and seamless cells off):
  - Vulkan: 40.2 fps, about 23 ms CPU recording per frame, GPU about 19.3 ms (SceneCam 13.3, Reflection 5.1), 559 draws.
  - GLES: 43.3 fps.
  - The changes above cut `templateFor` from 25% to 16% of the render thread, but fps has not moved yet.
- **Remaining hotspots** (inclusive, render thread):
  - `bindUniforms` 24%: before the last change, 5% of this was `StateSet::getUniform` string lookups.
  - `templateFor` 16%
  - `accumulate` 12% (`step` 7.5%)
  - `vkCmdBindDescriptorSets` 7%
  - `derive` 6%
  - `Resources::texture` 6%
  - `vkCmdBindVertexBuffers` 5%
  - malloc/new 4.5%
- **Tablet state to restore later:** `point light shadows` (was 4), seamless doors and seamless cells were turned off in the tablet's settings.cfg for these tests. The Lua position mod was removed from the tablet.
- **Test scripts** are in the session scratchpad (they won't survive). They did the following:
  - `vkrun.sh`: set `renderer =` in the device settings.cfg, launch with `--load-savegame saves/Duron/Autosave.omwsave`, take timestats, a screenshot and logcat.
  - `measure.sh`: settle 50 s, then `--script-run novan.txt` (DisableVanityMode) and print the Vulkan counters.
  - `prof.sh`: simpleperf `--call-graph dwarf` on the render thread, using the unstripped `libopenmw_upstream.so` from `.cxx` with `binary_cache_builder.py`.

### 2026-11-09 (later): GPU and CPU work, UNCOMMITTED (all in `support/src/`)

**Test view:** use `saves/Duron/Quicksave.omwsave` from now on. It shows the canal: water, bridges, town, about 1270 draws and 375k triangles.

**Two tablets are attached now. Always pass `-s R52M40E147K` (the SM-T720).**
- R52NA12J8FW is an SM-T867U Galaxy Tab S6 (Snapdragon 855, Adreno 640, 6 GB, Vulkan 1.1.128). It is not tested yet.

**Results on the Quicksave view (SM-T720, system driver, 1280x800):**
- GLES: 31.2–31.8 fps, CPU-bound. Vulkan before: 28.9 fps. **Vulkan now: 33.0 fps.**
- Vulkan GPU now: SceneCam 22.7 ms (was 27), Reflection 6.56 ms (was 6.75), opaque copy 1.0 ms.
- GL timer queries (the inspector) for the same view: Reflection 2.95 ms, SceneCam 12.4 + 6.2 ms.

**Changes:**
- `vulkan/programs.cpp`:
  - `centroid` is stripped. Every pass is single-sampled, and the Adreno 615 Vulkan driver charged ~3.2 ms of SceneCam for it.
  - Clip distances are written only by the clip variants (`variant(..., clip)`). Writing them on every draw cost 1.2 ms of scene time.
- `vulkan/context.cpp`:
  - Two-part submit: render-target work goes before acquire.
  - FIFO with 4 images. Mailbox only with `vsync mode = 0` and the env var `A3_VK_MAILBOX`.
  - Most of the old "23 ms recording" was time blocked in `vkAcquireNextImageKHR`.
- `vulkan/translator.cpp` and `vulkan/resources.cpp`: CPU caches:
  - kind per typeid; static_cast in `derive`
  - accumulated state and shared blocks kept across frames
  - textures and samplers checked once per frame
  - cofactor normal matrix; memcpy for uniform arrays
  - recording now takes 11–15 ms
- `vulkan/translator.cpp`:
  - nested GPU timestamps
  - more counters in the 300-frame log line
- `inspector.cpp`: create `/storage/emulated/0/Alpha3/cache/inspect.request` to start the GL inspector. It writes `cache/inspector.json`.

**Measuring switches, debug only (drop or keep before committing):** `/storage/emulated/0/Alpha3/vkexp.txt` holds a bit mask, read once.
- 1: skip descriptor rebinds
- 2: draw everything twice
- 4: no point lights
- 16: flat objects/terrain fragment
- 32: same, running the real main
- 64: diffuse only
- 128: keep `centroid`
- 256: flat water too

**GPU breakdown of SceneCam (before the centroid fix, 26 ms):**
- flat objects/terrain fragment: 14.7 ms
- with the diffuse texture: 17.4 ms
- with water flat too: 9.8 ms (water is about 5 ms)

**Ruled out:**
- uniform block size: objects' fragment block is 336 bytes, and pruning unread uniforms changed nothing
- varying count (objects 11 locations, terrain 13)
- light block size, light loop, spirv-opt, mipmaps, samplers, ASTC, SurfaceFlinger, GPU clock, render target formats (RGBA8 + D24S8, same as GL)

**Open:**
- Reflection is still 2.2× GL's time. It is vertex or per-draw bound (drawing everything twice adds 5.4 ms).
- Ideas: mediump/RelaxedPrecision; per-draw descriptor cost (4 dynamic UBOs); the vertex attribute formats.
- Descriptor binds cost the GPU nothing measurable: binding every set a second time, with a draw in between, changed no pass time. So push constants for the matrices are not worth doing.
- The S6 (R52NA12J8FW) was unplugged when its test was due, so it is still untested.

### 2026-11-10 (overnight, UNCOMMITTED for review): shadows, frame generation, hitches, local map
The user asked for no commits. Everything below is in the working tree. Test saves: `saves/Duron/Autosave.omwsave` (sunny forest path) and `Quicksave.omwsave`.

**Shadows on Vulkan (they match GLES now):**
- `vulkan/translator.cpp`: the draw block's normal matrix was transposed. It is now `normal[c*4+r] = inverse[r][c]`, because OSG builds its Matrix3 from columns of the inverse. This was why the sun's diffuse light was missing.
- `vulkan/programs.cpp` `fixShortCircuits()`: SPIRV-Cross writes `a && b` as `bool t; if (a) t = b; else t = a;`. The Adreno 615 Vulkan driver gets `t` wrong when `a` is a uniform-block bool, and leaves cast solid shadows because of it. The else branch is rewritten to `false` (and the then branch of `||` to `true`).
- `PipelineState::mDepthClamp` follows GL_DEPTH_CLAMP.
- Depth-only targets fall back to a format that filters linearly.
- Color, SecondaryColor and Normal inputs are always normalized.

**Frame generation on Vulkan (the "glitchy when looking around quickly" report):**
- **Main bug:** the warp moved nothing behind the first-person model.
  - The translator clears the scene depth for the first-person bin (`DepthClearCallback`). GL draws that bin against a proxy depth instead, so in GL the scene depth stays world-only.
  - On Vulkan the scene depth therefore held only the bow, and every world pixel failed the "opaque nearer than scene" test, so it got zero motion. Generated frames repeated the last frame (shown with the tint: shift 0 every time).
  - **Fix:** `mFirstPersonCleared` → uniform `uFirstPerson`. When it is set, the world depth comes from the opaque copy, and a scene depth below 1 marks the first-person model (no motion).
  - The opaque copy is now forced on frames that generate.
- **Placement:** generated frames are placed where they are shown. `FrameGenLock::shownAt(i, period)` = i × refresh ÷ measured interval (capped at the even split). At ~21 fps rendered the 1:1 pattern runs as real = 1 refresh, generated = 2. Before, the motion went 0.5 in 16.7 ms and then 0.5 in 33 ms; now its speed is even.
- **Screen edges:** what comes into view past the edge is mirrored instead of clamped (no streaks). This is in both the Vulkan warp and GLES `framegen.cpp`.
- **Program compiles off the draw thread:** `Programs::prepare(Job&)` (merge stages, uniform initializers, cache key, cache read) now runs on the worker too. Before, it cost the draw thread 90–180 ms per new program mid-turn.
  - Worker threads run at nice 10.
- **Periodic hitch:** a 70–95 ms frame every 300 frames came from the `mAccumulated` sweep (~40k entries). `sweepSlice()` now does 1/300 of the buckets each frame.
- **Measured** with `turnrec.sh` (5 s screenrecord while swiping the look joystick):
  - largest gap mid-turn: 0.23 s → 0.10 s cold, 0.07–0.1 warm
  - generated frames sit between their real frames in proportion to time
  - screenrecord drops frames shown for only one refresh, so judge by SurfaceFlinger timestats: 42 fps shown = 21 + 21
- **Answers to the user's frame-gen questions:**
  - At ~21 fps rendered, 1:1 is the only pattern that reaches 60 shown frames. A 2-rendered/1-generated pattern needs at least 40 fps rendered.
  - "the lighter pattern did not hold" means the periodic half-second try of fewer generated frames failed, so it went back.

**Features checked under Vulkan:**
- **Texture conversion (ASTC 6x6):** on. The GL pbuffer answers the capability check, and Vulkan reports BC no / ETC2 yes / ASTC yes.
- **Impostors and distant meshes:** listed and drawn as ordinary NIFs.
- **Texture streaming:** `Resources::upload` now also calls `texturePreloadTextureLoading` and `textureStreamingTextureLoading`, as GL's upload hook does. Before, textures the scene camera never draws were not made full size, and the preload list was not kept.
  - By design, streaming does nothing for what the main thread loads (save load, coc). It shows while walking into new cells.
- **Occlusion culling:** works. It culls 70–90 of ~700 tested objects and keeps itself on (45.4 ms with vs 46.0 ms without).
  - The tablet's settings.cfg had it off, so it was turned back on. Backup: `settings.cfg.before-occl2`.
- **Local map (minimap) was black on Vulkan (fixed):** its camera draws once per cell, and its programs were still compiling then, so its draws were left out for good.
  - **Fix:** an unnamed render-to-texture camera that was not drawn the frame before waits for its programs and pipelines (`mCamerasDrawn`), as the composite maps do.
- The "drawn as the dummy" log line now includes the texture's own size.

**Performance at Autosave (SM-T720, 1280x800, shadows on, frame gen on):**
- Vulkan: 41.9 fps shown (21 rendered). GLES: 30.1 shown (15 rendered).
- GPU per frame:

  | Pass | Vulkan | GLES (inspector) |
  |---|---|---|
  | SceneCam | 28.2 ms | 38.7 ms |
  | Shadows | 7.5–9 ms | 5.3 ms |
  | Reflection | 6.3 ms | 3.6 ms |
  | **Total** | **44 ms** | **53 ms** |

- Vulkan's remaining gap is in the draw-heavy small passes (shadow cascades, reflection).
- GLES frame generation holds the draw thread ~17.6 ms per generated swap ("generated swaps 17.6" in its timing log). That is a likely cause of GLES rendering only ~15 fps here, and is worth a look.

**Tablet state:**
- `renderer = vulkan`, shadows on, occlusion culling on.
- Still to restore: point light shadows 4, seamless doors and cells.
- `dump_shaders` was removed.
- **Tried and reverted:** pipelines without a fragment stage for shadow casters that cannot discard (`shadowcasting.frag` with alpha function ALWAYS and `alphaTestShadows` false). They were used, but the shadow pass time did not change (8.1–8.4 ms), so the cascades are not limited by fragment work.

### 2026-11-10 (day): cell crossing, bow string ghost, HUD — committed 2026-11-11
Commits: ee7a434 (ASTC block size per texture), 9d83e2b (cell borders), 9db8567 (Vulkan).

**Already done this day (also uncommitted):**
- Reverse-z, clustered lighting, post-processing with the normals MRT, and the source-change rebuild.
- The purple screen at startup is fixed: the GUI waits for its programs.
- F3 stats overlay through osgText.
- Perf check at the Balmora autosave with frame gen off: Vulkan 26 fps vs GLES 20.7 fps.

**Cell-crossing stutter:** the user confirmed it happens on PC too, so it is not a Vulkan bug.
- New patch `patches/common/0030-Cell-borders-chunks-built-in-the-background-levelled.patch` plus `support/src/cellborders.cpp`. Each part has its own setting in `[Cells]` of `cpp/settings/alpha3.cfg`:
  - Object paging chunks are built on the work queue. While a chunk builds, the old chunk stands in, or nothing does if the chunk is more than 3072 units from the player.
  - Levelled creatures' models are preloaded (`getModelsToPreload`).
  - Physics object removal is spread over frames (`cell unload budget`).
  - The fog-of-war PNG save is deferred.
- **A/B on the tablet:** worst crossing frame is 66–83 ms with these on, 100–166 ms with them off.
- **What remains** is mostly `Scene::insertCell`. "Load cells over frames" exists but only with seamless cells.
- The patch README's contents list stops at 17, so it was not updated for 0030.

**Performance HUD:** it did not count Vulkan's generated frames.
- `Alpha3::noteShownFrame()` (in `support.hpp` and `framepacing.cpp`) is now called after each generated present in `translator.cpp` `drawGenerated`.
- Built, but not yet checked on screen.

**Frame generation ghosts, first and third person (Vulkan):** fixed, the user confirmed both (2026-11-11). Committed in 9db8567.
- **Root cause of the first-person ghost:** not the mask, and not depth from another frame. The warp looked the motion up twice (at the pixel, then where the pixel comes from). When the second lookup landed on the bow or string, the motion became 0 and the pixel was left unwarped. That left a bow- and string-shaped strip of stale world beside the real bow, which reads as a dark second string. A diagnostic tint (fill blue, static world yellow, model green) showed it at once.
- **What is in the code now** (`support/src/vulkan/translator.cpp`, `sWarpFragment`):
  1. `modelAt()` says which pixels go with the camera. First person: the scene depth after the first-person clear (handles reversed depth too). Third person: inside a cylinder around the player.
  2. A model pixel stays put. A world pixel takes the world's motion; if its source is behind the model, it keeps the motion found at the pixel itself and goes to the fill.
  3. The fill takes the world at the model's edge: the edge along the motion first (what is past it is drawn beside these pixels), the other way only if none is found. 8-texel steps, then 3 halvings to about a texel.
  4. `drawGeometry`: in the first-person bin with frame gen on, draws that write no depth write it (the bow string), so they are in the mask.
  5. `captureForFrameGen` uses the depth images the scene was drawn with (`mSceneDepth`, `mSceneOpaqueDepth`).
- **Third person ("very jerky player"):** the player was warped as part of the world, so it jumped each generated frame.
  - `FrameGenView` (`upstream.hpp`) gained `player`, `playerFeet`, `playerHeight`, `playerRadius`. `androidmain.cpp` `submitFrameGenView` fills them when not in first person (base node position, physics half extents). Patch 0003 has the same lines; applying 0003 and 0019 reproduces the tree's file.
  - `drawGenerated` passes the cylinder in view space: from 6% of the height above the feet (the ground stays world) to 115%, radius 2.5 × the half extent.
  - GLES `framegen.cpp` has no such handling yet.
- **Tried, didn't work or wasn't the cause:**
  - Swapping to the depth of the "other pair": never fired, removed.
  - Widening `nearModel` from ±1 to ±2 texels: no change (kept at ±2).
  - Depth from another frame: the log showed it always matched (960/960).
  - Sharpening `none` instead of rcas: removed only faint GUI pass-through ghosts.
  - A fill that took the nearest world pixel in 8-texel steps: it removed the bow copies but left comb-like repeated slices behind the bow and hand. Replaced by the edge fill.
  - Mirroring the world past the nearer edge: a large mirrored wedge with a hard edge beside the bow. The user called it worse than before. Replaced by the edge fill.
- **Capturing:** `screenrecord --size 1280x800 --bit-rate 24000000` keeps more frames than full size. TAB (`input keyevent 61`) toggles third person. The scratchpad scripts (`turn.sh`, `run3.sh`, `sheet3.py`) are lost after the session.

**Tablet state to restore:**
- `/storage/emulated/0/Alpha3/config/settings.cfg.before-test` → `settings.cfg`. The test copy has shadows off, point light shadows 0 and sharpening none.
- Delete `/storage/emulated/0/Alpha3/sunny.txt`.
- The installed build matches the source (no diagnostic code).
