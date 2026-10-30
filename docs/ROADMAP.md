# Roadmap

Things to add, mostly aimed at FPS and load times on phones.

## To do

Everything still open, in rough order. The sections below have the details.

**Test on a device first** (the numbers decide the tuning):

- [ ] Distant meshes: check lighting on the tree cards, leaf edges after ASTC conversion (try `astc block size = 4x4` if blotchy), the pop at the active grid edge, and FPS with paging in Vivec and the Ascadian Isles.
- [ ] Texture streaming: compare cell load times with a 2K texture pack, with and without streaming.
- [ ] Occlusion culling: check how much is culled and the CPU cost (the verbose log, every 600 frames) in Vivec, Balmora and on open ground. Then tune `occlusion buffer width` and `occluder triangle budget`.
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

**4. Occlusion culling**

- [ ] Too costly as it is: on the phone (Seyda Neen, Bitter Coast) it culled 1–11% of what it tested, for 2–5 ms per frame, and the cost was out of line with the triangles drawn (3.3 ms with 1404). Log the drawing and the tests separately to find where the time goes. Keep it off until then.
- [ ] Draw the depth buffer on a worker thread instead of at the start of the cull.
- [ ] A smaller simplification error or an inward offset for the occluders, if the device test shows early culling.

**Other**

- [ ] Find why the debug APK grew from 150 MB to 200 MB.

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
  - **Card atlas as DXT5** (etcpak's BC3 encoder): 4x smaller than RGBA8 (32 KB instead of 128 KB for a 192×128 atlas with mips). On GPUs without S3TC, the image hooks convert it to ASTC/ETC2 like any other DDS.
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
  2. Once something drawn with it covers more pixels across on the screen than the small texture has, the full image is prepared on a background thread. The scene camera's cull tells, through the occlusion hooks' `drawableAdded`. Far away the texture stays small. A texture uploaded but never drawn by the scene camera (sky, GUI) is made full size about 5 seconds after its upload.
  3. After a later frame, on the draw thread, the full data replaces the small image's data in place, up to `streaming swaps per frame` per frame. OSG sees the change and remakes the texture at full size. Every texture sharing that image changes with it.
- **Tested:** on a desktop GL context, a texture remade at the new size from the same image. In a host test, 210 textures from Morrowind.bsa (start size lowered to 64) read small first (1.1 MB instead of 11 MB), then byte-identical to the full image after the swap, with and without a conversion step. Vanilla textures are 256 px or smaller, so at the default this only affects high-res texture packs.
- **Next:**
  - **Memory budget:** drop textures not drawn for a while back to their small levels, which needs a "drawn this frame" signal (a hook in `Texture::apply`, or the cull visitor).
  - ~~Upgrade by screen size instead of on first upload~~: done. Tested in a cull with a 1024 px texture on a wall: it stays at 256 px from far away (about 40 px on screen), and goes full size up close.
  - **Device check:** load times of a cell with a 2K texture pack, with and without streaming.

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
- **Next:**
  - **Device check:** culled counts and the CPU cost (the verbose log prints both every 600 frames) in Vivec, Balmora and on open ground. Tune the buffer width and triangle budget.
  - **Draw the buffer on a worker thread** while the previous frame is drawn, instead of at the start of the cull.
  - ~~Simplified occluders~~: done. An occluder over 1000 triangles (a building, an object paging chunk) is simplified once on a background thread (positions welded, 1% error, open edges locked) and drawn in its place. It's drawn whole (or skipped if over budget) until the simplified version is ready, and kept only if at least 20% smaller. A 64×64 grid wall goes from 8192 to 818 triangles and culls the same. This is what lets paging chunks fit in the budget.
  - **Conservative simplification:** the simplified surface can be up to 1% of the mesh's size in front of the real one, so something just behind a wall could be culled a little early. Check on the device; a smaller error or an inward offset would fix it.
