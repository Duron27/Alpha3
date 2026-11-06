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
