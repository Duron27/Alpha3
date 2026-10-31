# Roadmap

Things to add, mostly aimed at FPS and load times on phones.

## To do

Everything still open, in rough order. The sections below have the details.

**Test on a device first** (the numbers decide the tuning):

- [ ] Distant meshes: check lighting on the tree cards, leaf edges after ASTC conversion (try `astc block size = 4x4` if blotchy), the pop at the active grid edge, and FPS with paging in Vivec and the Ascadian Isles.
- [ ] Texture streaming: compare cell load times with a 2K texture pack, with and without streaming.
- [ ] Occlusion culling: check how much is culled and the CPU cost (the verbose log, every 600 frames) in Vivec, Balmora and on open ground. Then tune `occlusion buffer width` and `occluder triangle budget`. First device run (before the faster rasterizer and the rests): 1–11% culled for 2–5 ms a frame, and lower FPS facing a wall. Recheck with the new build.
- [ ] Occlusion culling: watch for objects just behind walls disappearing early (the simplified occluders can sit up to 1% of a mesh's size in front of the real surface).
- [ ] Profile the paging thread on a phone (silt strider ride), before deciding on 2b.

**1. Distant meshes**

- [ ] More reduction for buildings: their locked open edges stop the simplifier, so most get no farther levels. Options: unlock borders at the farther levels, weld positions across materials, meshoptimizer's attribute-aware simplifier, or a texture-baked proxy mesh for the farthest level.
- [ ] If the cards look soft: keep the atlas uncompressed so the conversion encodes ASTC from the original pixels, not from DXT5.

**2. LOD while loading**

- [ ] Show the low levels first while a cell loads, then swap in detail. This needs a hook in OpenMW's cell loading or paging (an OpenMW patch), so it needs an OK first.
- [ ] Mobile presets for `viewing distance` and `object paging min size`.
- [ ] 2b: pre-built object paging chunks (only if profiling shows paging is the bottleneck).

**3. Texture streaming**

- [ ] Memory budget: drop textures not drawn for a while back to their small levels.
- [ ] Maybe: a texture upscaler (see "Texture upscaling" under section 3). First, judge the look with no code: upscale a few textures on a PC and load them as `.ktx`.

**4. Occlusion culling**

- [ ] Too costly as it is: on the phone (Seyda Neen, Bitter Coast) it culled 1–11% of what it tested, for 2–5 ms per frame, and the cost was out of line with the triangles drawn (3.3 ms with 1404). Log the drawing and the tests separately to find where the time goes. Keep it off until then.
- [ ] Draw the depth buffer on a worker thread instead of at the start of the cull.
- [ ] A smaller simplification error or an inward offset for the occluders, if the device test shows early culling.

**5. Better looking vanilla (experimental, `[Experimental]` switches, all off by default)**

- [x] Generated normal and specular maps. Tested on the device: works, about the same memory.
- [x] Grass: 3D blades on grassy terrain, density, height, distance and wind sliders. Tested: about +0.3 GB.
- [x] Trees swaying in the wind, trunks bending a little. Tested.
- [x] Procedural materials (built, too slow: 8 fps against 20 without, in the Bitter Coast).
- [x] Detail up close, terrain triplanar and height blending, ambient occlusion and look (`.omwfx`): built, not yet tested on the device one by one.
- [ ] Procedural: bake the replace mode into generated textures (it only depends on the uv), so it costs nothing at runtime. Port the patterns to C++; run it in "Build Alpha3 cache".
- [ ] Generate the maps in "Build Alpha3 cache": the first visit to an area converts about 100 new maps to ASTC at about 600 ms each.
- [ ] Test the others one by one on the device (memory, frame rate, screenshots).
- [ ] Clip planes without `GL_EXT_clip_cull_distance` (Mali, ANGLE): fragment discard fallback built, test on such a device.
- [ ] Clustered lighting makes the Adreno GPU fault and the game get killed (near Vivec, also before this work).

**Other**

- [ ] Find why the debug APK grew from 150 MB to 200 MB.
- [ ] "Build Alpha3 cache" (Mod tools): built. The texture pre-pass, then every distant mesh and card in a `:cache` process (CacheBuildService, `support/src/cachebuild.cpp`), both on every core whatever the thermal mode. Not the navmesh (navmeshtool takes too long; `write to navmeshdb = true` caches each area as it is visited). The texture half is tested on the phone; the distant mesh half still needs a full run.
- [ ] Kotlin navmesh generator: 229 ms per tile of 18 triangles in the last run (rasterize 55, compact+erode 89), far from native Recast. Profile it on the phone (interpreter in the debug APK?) before replacing libopenmw-navmeshtool.so.
- [ ] Thermal mode: check the thresholds over a long session (the Note 10+ forecasts headroom from its skin temperature, since its thermal HAL gives no thresholds).

## Thermal mode

- `[General] thermal mode = full` (default) never backs off. `auto` follows Android's thermal headroom forecast (Android 11+, checked every 2 s). Warm (0.85 or more): background texture conversion on half its threads, and Zink one display period more per frame. Hot (0.95 or more): conversion on one thread, frames held to two display periods at least (Swappy's minimum interval, or the swap interval of plain swaps), occlusion culling without its helper threads. Each level is left 0.1 below where it starts.
- On the Note 10+ while charging after a long test run, the forecast was 1.22 (skin 48.9 °C), so it started hot.

## 1. Distant objects as sprites (impostors)

Far away, draw a flat 2D picture of an object (a tree, a rock, a house) instead of the 3D mesh. The picture always turns to face the camera, like the enemies in Doom 2. Up close, the real mesh comes back.

- **Background:** Cmaugust tried this with sprites for distant land objects and got good gains. The sprites were made by hand, though, so it only worked for the objects someone drew.
- **What we need:** a tool that makes the sprites on its own for any mesh, including mod content:
  1. Render each candidate mesh offscreen from a ring of angles (8 or 16 around, maybe one from above), with color plus alpha, and optionally normals so lighting still works.
  2. Pack the views into an atlas texture and store it in the engine cache (the same place the `[Textures]` pre-pass writes to), so this happens once per mesh.
  3. At runtime, past a set distance, swap the mesh for a camera-facing quad and pick the view closest to the camera angle. Fade between views or between sprite and mesh to hide the switch.
- **Which objects:** trees, flora and rocks first, since they are the most common and look fine as cards. Buildings could come later, or use more views.
- **Later:** octahedral impostors (views spread over a half-sphere) look better from higher camera angles.
- **Ties into:** OpenMW object paging (`object paging active grid`, `distant terrain`). The sprites should probably be a new LOD level inside paging, not a separate system.
- **Status: done for trees, rocks and buildings, no OpenMW patch.** See `cpp/openmw-upstream/support/src/distantmeshes.cpp`. Object paging already asks for `<mesh>_dist.nif` when it builds a chunk outside the active grid, so a support-library archive answers for it. Active cells never see these meshes. Each distant mesh is made the first time it is needed and cached in `cache/impostors/`. Both kinds are off by default:
  - **Cards** (`[Terrain] impostors`, the trees in `impostor meshes`): the mesh is drawn on the CPU from 6 sides into one texture, written as 3 crossed two-sided quads. All 72 vanilla trees, about 7 ms each.
  - **Simplified meshes** (`[Terrain] distant mesh simplification`, every other mesh by default, `simplified meshes = *`): shapes are gathered by material and simplified with meshoptimizer, with borders locked so the pieces still meet. They keep their own textures and properties, so paging merges them with everything else. On the vanilla and expansion BSAs at the 2% default: 2387 meshes simplified (431 `ex_` buildings, 91 rocks), 2.46M → 1.00M triangles, about 0.5 ms each on a PC. Examples: Vivec canton 9214 → 5768, Ald'ruhn building 4226 → 1802, Bitter Coast rock 916 → 228. They look the same at a distance.
  - **Left as they are:** animated meshes, particles, billboards, effects, skinned meshes, meshes under 200 triangles, and meshes that don't get at least 20% smaller. The cache remembers them, so from the next start they are not listed and the engine doesn't keep them twice.
  - **Farther levels** (`[Terrain] simplified mesh levels`, 3): paging asks for `<mesh>_dist_<n>.nif` for its chunks of 2^n cells, so each simplified mesh is also listed as `_dist_1` and `_dist_2`, with 2x and 4x the error. A level is only kept if it is at least 20% smaller than the one before; otherwise paging falls back to the level before. On the vanilla and expansion BSAs, 1280 meshes get a level 1 (236k triangles) and 818 get a level 2 (73k). Level 0 is unchanged, so existing caches stay valid.
  - **Card atlas as DXT5** (etcpak's BC3 encoder, which takes RGBA as stored; the first build swapped red and blue, so trees looked blue): 4x smaller than RGBA8. The atlas is padded to a power of two (256×128 for the usual 192×128), since GLES can't use mipmapped compressed textures of other sizes. On GPUs without S3TC, the image hooks convert it to ASTC/ETC2 like any other DDS. The host test checks the atlas colours.
  - **Cache hits without reading the mesh:** each entry is also linked from a key of the mesh's name, file time and settings, so a mesh made before is found without reading its file. Before, every hit read the whole NIF (one character at a time) and hashed it twice, about 7.5 ms each: 4.4 s of an 8.5 s `coc` from Molag Mar to Balmora. Now about 1.5 ms. The first run after the change still reads each mesh once, to add its link; caches already on disk stay valid.
  - **Startup:** a mesh that was not made is remembered by its name and settings, and only those few get their file time read (a `stat`, slow on Android's shared storage). Before, every mesh and level was stat'ed while listing, about 1.8 s on the phone. The log line gives the listing time.
- **Next:**
  - **Real-device check:** lighting on the cards (their normals point up), the alpha test at low mips, the pop at the active grid edge, and FPS with paging in Vivec and the Ascadian Isles.
  - **More reduction for buildings:** many Morrowind buildings are open pieces, and their locked borders limit the simplifier, so the farther levels gain little on them (Vivec canton: 5768 at level 0, 5742 at level 1, so no level 1 is made). Try meshoptimizer's attribute-aware simplifier, welding positions across materials, unlocked borders at the farther levels, or a texture-baked proxy mesh.

## 2. Low quality while loading, better when close (LOD and streaming)

Load something cheap first, so a cell shows up fast, then upgrade what's near the player.

- Show distant terrain and objects at the lowest LOD (or as sprites, see above) while a cell loads, then load full detail by distance.
- Tie into the existing `viewing distance` and `object paging min size` settings, with mobile presets.
- **Status: started.** Distant objects now get coarser the farther away they are (the distant mesh levels, item 1), and texture streaming keeps far textures small (item 3).
- **Not done yet:** showing the low levels first while a cell loads, then swapping in detail. That needs a hook in OpenMW's paging or cell loading, not just the VFS. Also not done: mobile presets for `viewing distance` and `object paging min size`.

## 2b. Pre-built object paging chunks

OpenMW builds every paging chunk in game: it collects the references, loads the meshes, then copies and merges the geometry on a worker thread. On phones this may be the bottleneck while you travel.

- Bake the distant chunks offline (per cell and per LOD level), store the merged geometry, and load it instead of building it.
- **Problems:** chunks depend on the viewpoint (the min size cut uses the distance to each object) and on the save (disabled and moved references are blacklisted). A baked chunk would need a fixed cut per LOD level and must still respect the blacklist.
- Unlike the impostors, this needs a hook inside `ObjectPaging::createChunk` (OpenMW code), so keep the patch to a single call into the support library.
- **Measure first:** profile the paging thread on a phone while riding the silt strider. Impostors already make chunks cheaper to build, because a card has 24 vertices instead of thousands.

## 3. Texture streaming

- Load the small mip levels of a texture first, then the full size only when the object gets close or covers enough of the screen.
- Unload high mips for things far away to stay inside a memory budget, which matters most on phones with little RAM.
- Builds on the `[Textures]` conversion cache: converted textures already store their mips, so loading part of a file should be cheap.
- **Status: first version done, no OpenMW or OSG patch.** See `cpp/openmw-upstream/support/src/texturestreaming.cpp`, off by default (`[Textures] streaming`).
  1. A mipmapped DDS larger than `streaming start size` (256) is read as its smaller levels only. With texture conversion on, only those levels are converted while the cell loads.
  - Pictures shown flat on the screen are never streamed: everything read on the main thread (the GUI and loading screens), paths outside `textures/`, menu art (`textures/menu*`, `levelup`, `scroll`, `compass`, `target`, `tx_menubook`), and textures that had to be made full size without being seen drawn, which are remembered in `texture-streaming-full.list` in the cache folder. The main menu background was blurry until it loaded before this.
  2. Once something drawn with it covers more pixels across on the screen than the small texture has, the full image is prepared on a background thread. The scene camera's cull tells, through the occlusion hooks' `drawableAdded`. Far away the texture stays small. A texture uploaded but never drawn by the scene camera (sky, GUI) is made full size about 5 seconds after its upload.
  3. After a later frame, on the draw thread, the full data replaces the small image's data in place, up to `streaming swaps per frame` per frame. OSG sees the change and remakes the texture at full size. Every texture sharing that image changes with it.
- **Tested:** on a desktop GL context, a texture remade at the new size from the same image. In a host test, 210 textures from Morrowind.bsa (start size lowered to 64) read small first (1.1 MB instead of 11 MB), then byte-identical to the full image after the swap, with and without a conversion step. Vanilla textures are 256 px or smaller, so at the default this only affects high-res texture packs.
- **Next:**
  - **Memory budget:** drop textures not drawn for a while back to their small levels, which needs a "drawn this frame" signal (a hook in `Texture::apply`, or the cull visitor).
  - ~~Upgrade by screen size instead of on first upload~~: done. Tested in a cull with a 1024 px texture on a wall: it stays at 256 px from far away (about 40 px on screen), and goes full size up close.
  - **Device check:** load times of a cell with a 2K texture pack, with and without streaming.

### Texture upscaling (possible option, not started)

Make vanilla textures larger and sharper. Only an idea for now; nothing is built.

- **Where it fits:** on GPUs without S3TC, every DDS already goes DXT → RGBA (`decodeLevel`) → ASTC/ETC2 in `transcodeDxt` (`support/src/textureconversion.cpp`), and the launcher's pre-pass (`texconv`) runs the same code. An upscaler is one more step on the RGBA, between decode and encode. Everything stays in `support`, with no OpenMW or OSG patch.
- **What already helps:**
  - **Cache:** each texture is upscaled once and then read from `astc_cache.bin`. The upscaler and its settings must go into the cache key (`s.tag`).
  - **Streaming:** only textures drawn large on screen are made full size, so most of the memory cost lands on nearby objects. The background thread that prepares full images could run a cheap upscaler.
  - **Pre-pass and "Build Alpha3 cache":** the place for a slow one.
- **Options:**
  1. **AI upscaling (Real-ESRGAN style) in the pre-pass and cache builder.** It runs with ncnn on Vulkan. A small model such as `realesr-animevideov3` is a few MB; x4plus is about 17 MB. The only option that adds detail. Expect under a second to a few seconds per texture: minutes to tens of minutes once, too slow while playing. New dependency: ncnn and the model file.
  2. **Classic upscaling** (Lanczos plus sharpening, or an FSR1-style EASU and RCAS on the CPU). Cheap enough for the streaming thread, with no dependency and about 100–200 lines. It sharpens edges but adds no detail. A runtime fallback when no pre-pass was run.
  3. **No code:** upscale on a PC and convert with kram to `.ktx`, which `ktxtextures.cpp` already loads in place of the `.dds`. Try this on about 10 textures first, to see if it's worth building 1.
- **Problems to handle:**
  - **Tiling textures:** wrap-pad before upscaling and crop after, or seams appear.
  - **Normal maps (`_n.dds`):** skip them, or upscale simply and re-normalize. AI models break the vectors.
  - **Alpha:** upscale alpha-tested leaves and fences separately and keep their edges crisp.
  - **DXT artifacts:** many models amplify the 4×4 blocks; prefer one trained on compression artifacts.
  - **Size:** a 2× upscale is 4× the texels, so the cache size and ASTC encode time grow 4×. Default to 2× within `maxSize`.
  - **Exclusions:** GUI, fonts and menu art (the `FullQualityImages` list) probably shouldn't be upscaled, or need a different model.
- **Effort:** option 2 is about a day; option 1 is a few days, mostly integrating ncnn, the padding and comparing models.

## 4. Occlusion culling

Skip drawing things hidden behind other things, like buildings behind a wall in Vivec or Balmora.

- **Options:**
  - GPU occlusion queries that use the previous frame's results, so the CPU never waits on the GPU.
  - A small CPU software rasterizer that draws big occluders (terrain, large buildings) into a low-res depth buffer and tests object bounding boxes against it. This avoids GPU readbacks, which are slow on mobile and on ANGLE/Metal.
- Exteriors in towns gain the most. Interiors already get most of the benefit from cell boundaries.
- Measure first: profile a few heavy spots (Vivec, Balmora, Ald'ruhn) to see whether the draw calls are the bottleneck.
- **Status: first version done, the CPU rasterizer.** See `cpp/openmw-upstream/support/src/occlusionculling.cpp`, off by default (`[Camera] occlusion culling`). The only OSG change is `occlusion-hooks.patch` in the openmw-osg port: three `RuntimeHooks` calls in the cull visitor. There is no OpenMW patch.
  1. While the scene camera is culled, large opaque drawables are remembered as occluders, with their world matrices. These are terrain chunks, buildings and rocks over `occluder min size` (300 units). Drawables that are blended, alpha tested, don't write depth, or are outside the default render bin are skipped.
  2. At the first test of the next frame, those occluders are drawn with that frame's camera into a 256×128 depth buffer, largest on screen first, up to `occluder triangle budget` (20000). Back faces are skipped where the mesh culls them. Each pixel keeps the farthest depth its triangle has anywhere in the pixel.
  3. A node or drawable that passed the frustum test is culled when every pixel under its bounds, plus one pixel around them, holds a nearer surface.
  - Only the scene camera's traversal is touched; shadows, water reflections and the sky are not. Lights are collected in the update traversal, so culling a hidden node doesn't turn its light off.
  - An occluder that was off screen last frame is missing (less is culled). One that moved is a frame late, so a swinging door could pop for one frame.
- **Tested:** a host test (a wall and boxes through a real `osgUtil::SceneView` cull) checks these cases: behind is culled; in front, above, and past the end are not; back faces hide nothing; alpha-tested and blended walls hide nothing; `AlphaFunc ALWAYS` still hides; the camera inside the wall culls nothing; small meshes and other cameras are left alone. The buffer is checked to never be nearer than the surface. Speed: 60000 terrain triangles in about 3 ms on a desktop CPU.
- **Second version (after the first device run, which culled 1–11% for 2–5 ms a frame and lost FPS facing a wall):**
  - With `object paging active grid`, the objects of the active cells are merged into paging chunks, so a wall rarely hides a whole chunk and little can be culled per object. The cost was paid every frame anyway.
  - **Rests:** every 30 frames it checks what it culled. Under 1 in 50 of what it tested, it stops (no occluders recorded, no buffer drawn, no tests) for 30 frames, then doubles that up to 480, trying one frame after each rest. The first frame that culls enough brings it back. The log line shows how much of the time it rested.
  - **Faster rasterizer:** each vertex of a mesh is transformed and projected once (not once per triangle). Triangles fully in front of the near plane are rasterized with incremental edge functions, and only those crossing it are clipped. The buffer stores 1/depth, so the per-pixel value is linear on screen. A tile minimum lets a test skip whole tiles.
  - **Measured trials:** counting what is culled doesn't say whether frames got faster. So about every 1200 frames (and 300 frames after the start) it runs 8 turns of 24 frames, alternately without and with culling. The first 6 frames of each turn are not counted, because the draw thread is a frame behind. It stays on only if frames are at least 1% faster with it; otherwise it rests 1200 frames and tries again. The log shows each verdict. Seyda Neen facing the town wall: 24.8 ms with it, 26.9 ms without, so it stays on. Vivec: 20.2–21.8 ms with it, 33.3–33.5 ms without (about 35–40% faster), culling about a third of what it tests for 2.1 ms of drawing and 0.45 ms of tests a frame.
  - **Occluders hidden by nearer ones** are skipped. The nearest are drawn first on the cull thread, and every later one whose box is already hidden isn't drawn, which keeps its triangles in the budget.
  - **Helper threads:** the remaining occluders are shared with up to 3 helper threads (half the cores, less one), each taking the next one left and drawing into its own buffer. The buffers are merged by the per-pixel max. Helpers run at nice 0, below the engine's -4. Otherwise, once woken, they preempted the cull thread on its own core and nothing ran in parallel (seen on the Note 10+).
  - **Span rasterizer:** each row's span inside the triangle is found from where the three edges cross it, trimmed by the exact edge test, and then filled without branches, which vectorizes. Big near triangles (the terrain under the player) were the slowest part: one 532-triangle chunk took 0.6 ms. Vertices go to view space with a float matrix.
  - Device, Seyda Neen facing the wall, before the helpers and spans: 2.3–2.9 ms drawing and 0.8 ms of tests per frame, for 20000 triangles; 11–15% of tests culled.
  - **Terrain chunk tests:** a bounding box test hook (`boxOccluded` in `CullStack::isCulled(BoundingBox)`) also tests drawables by their box, which is tighter than their sphere.
- **Next:**
  - **Device check:** culled counts and the CPU cost (the verbose log prints both every 600 frames) in Vivec, Balmora and on open ground. Tune the buffer width and triangle budget.
  - **Draw the buffer on a worker thread** while the previous frame is drawn, instead of at the start of the cull.
  - ~~Simplified occluders~~: done. An occluder over 1000 triangles (a building, an object paging chunk) is simplified once on a background thread (positions welded, 1% error, open edges locked) and drawn in its place. It's drawn whole (or skipped if over budget) until the simplified version is ready, and kept only if at least 20% smaller. A 64×64 grid wall goes from 8192 to 818 triangles and culls the same. This is what lets paging chunks fit in the budget.
  - **Conservative simplification:** the simplified surface can be up to 1% of the mesh's size in front of the real one, so something just behind a wall could be culled a little early. Check on the device; a smaller error or an inward offset would fix it.

## 5. Better looking vanilla

Make the 20 year old textures and meshes look better on the fly, without replacing the data files.

- **Status: built as experimental switches** (`[Experimental]` in `settings.fallback.cfg`, each its own setting, all off by default). Code: `support/src/experimental.cpp` (settings, generated maps in the VFS), `experimentalshaders.cpp` (the parts added to OpenMW's shaders at anchors), `experimentalstate.cpp` (per drawable variants), `grass.cpp`, `generatedmaps.cpp`, shader parts in `support/src/experimental/`. The only OSG change is `experimental-hooks.patch`: state pushed for one drawable, defines a shader imports, and a state's defines put in the source before it is translated (glslang runs the preprocessor; OpenMW's `FORCE_OPAQUE` and `DISTORTION` were lost on the phone before). There is no OpenMW patch.
  - **Generated maps:** `_n`, `_nh`, `_spec`, `_diffusespec` for `.dds` textures, made on first use and cached (`generatedmaps/generatedmaps.bin`), capped at 512 (1024 for terrain). Which textures get them, and their material, by name (`material rules`). The 1x1 mip level of a normal map carries the material and the grain's direction.
  - **Procedural materials:** bark, wood, stone, masonry, plaster, dirt, grass, sand, metal. Each drawable (and terrain layer pass) gets a variant with only its material (`A3_MATERIAL`); one shader with all of them was 2.5 fps. Still about 70 ms a frame on the phone: to be baked into textures.
  - **Grass:** blades built on a worker thread per 512 unit tile from the terrain chunk's vertices and blendmaps, drawn beside the chunk, swaying and parting around the player.
  - **Tree sway:** leaves (by texture name) get `A3_TREE_SWAY 1`, trunks (bark) `2` with the world height of their foot; the swell is shared so leaves and trunk lean together.
  - **Clip planes on GPUs without clip distances:** the vertex shaders pass the clip position, the fragment shaders discard behind the planes (`glesdialect.cpp`).
- **Ideas as first written:** Seen in screenshots from the phone: walls are flat painted surfaces with no relief and a uniform dark smear on the shaded side; terrain is a big blurry stretched texture up close, with a soft smudge where grass meets dirt; nothing darkens where rocks, roots and walls meet the ground, so objects look pasted on. Water and shadows already look fine.

- **What makes it possible, with no OpenMW patch:**
  - **Shader hook:** every shader OpenMW compiles passes through `translateShaderSource` (`support/src/shaders.cpp`), so the object, terrain and water shaders can be rewritten as they compile.
  - **Generated files:** the support library already makes files that aren't on disk (`_dist.nif` in `distantmeshes.cpp`, `.ktx` in `ktxtextures.cpp`), so it can make textures and meshes the same way.
  - **Post-processing:** OpenMW's `.omwfx` post-processing is already shipped (`bloomlinear`, `adjustments`), so screen effects need no C++.
- **Ideas, best value first:**
  1. **Generated bump and specular maps.**
     - When the engine asks for `foo_n.dds`, the support library makes it from `foo.dds`: brightness becomes height, a Sobel filter turns that into normals, and the height goes in alpha for parallax. `foo_spec.dds` is made the same way.
     - Cached like the ASTC conversions; OpenMW already uses these maps (`auto use object normal maps`, `auto use terrain normal maps`). At runtime it's one more texture read per pixel.
     - Stone, brick, plaster, wood and dirt gain relief. Painted textures (banners, faces, GUI) come out wrong as heights, so they need a skip list by name.
  2. **Detail up close** (shader rewrite). Within a few metres of the camera, a fine tiling detail texture (or shader noise, which costs more) blended on top, so close surfaces stop looking blurry. For terrain also:
     - triplanar mapping on steep slopes, so textures stop stretching;
     - height-based blending, so grass gives way to dirt along the bumps instead of a smudge.
  3. **Ambient occlusion**:
     - **Baked into vertex colours:** computed once per mesh, served like the distant meshes, and free at runtime. Many Morrowind meshes already bake lighting into their vertex colours, so multiply into them, don't replace them.
     - **SSAO:** an `.omwfx` pass at half resolution. Quicker to try, but about 1.5–3 ms a frame on a phone.
  4. **Colour and atmosphere** (`.omwfx`, no code): colour grading, a little bloom, fog tinted by the sky.
  5. **Procedural replacements for whole textures** (maybe). The texture is computed in the shader instead of sampled, with its colours taken from the original so it keeps the Morrowind look.
     - **Bark mock-up** (`tx_bc_bark_02`, Bitter Coast trees; rendered in numpy as a stand-in for the shader):
       - long ridges from cell noise stretched along the trunk, with the furrows where two cells meet;
       - warped coordinates so ridges wander and split, and ragged furrow edges;
       - fibres along the grain, flaky roughness, loose paler flakes;
       - dark, mid and light colours picked from the original texture;
       - moss in the furrows (patchy, more on one side) and lichen spots;
       - the height gives bump lighting, darker cavities and a faint sheen.
     - **Result:** it stays sharp up close where the 256 px original blurs, and needs no texture file.
     - **Concerns:**
       - It can clash with the hand-painted textures around it. Bark, water and lava are the safest; it's a per-texture opt-in.
       - Cell noise is several texture reads' worth of maths per pixel on a phone, so fade to the original texture with distance.
       - The coordinates need a sensible UV layout: tree trunk UVs usually wrap around once.
- **Cost on phones:** each idea is its own setting, and fades out with distance where possible.
- **GLES:** shader rewrites must survive the GLES translation: hook in after it, or write them for both.
