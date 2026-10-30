# Roadmap

Things to add, mostly aimed at FPS and load times on phones.

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
- **Next:**
  - **Real-device check:** lighting on the cards (their normals point up), the alpha test at low mips, the pop at the active grid edge, and FPS with paging in Vivec and the Ascadian Isles.
  - **More reduction for buildings:** many Morrowind buildings are open pieces, and their locked borders limit the simplifier. Try meshoptimizer's attribute-aware simplifier, welding positions across materials, or a texture-baked proxy mesh for the farthest LOD level (`_dist_1`, `_dist_2`, which paging already asks for).
  - **Compress the card atlas:** use ETC2/ASTC with the encoders already linked, about 4x smaller.

## 2. Low quality while loading, better when close (LOD and streaming)

Load something cheap first, so a cell shows up fast, then upgrade what's near the player.

- Show distant terrain and objects at the lowest LOD (or as sprites, see above) while a cell loads, then load full detail by distance.
- Tie into the existing `viewing distance` and `object paging min size` settings, with mobile presets.

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
  2. The first time that small texture is uploaded, the full image is prepared on a background thread.
  3. After a later frame, on the draw thread, the full data replaces the small image's data in place, up to `streaming swaps per frame` per frame. OSG sees the change and remakes the texture at full size. Every texture sharing that image changes with it.
- **Tested:** on a desktop GL context, a texture remade at the new size from the same image. In a host test, 210 textures from Morrowind.bsa (start size lowered to 64) read small first (1.1 MB instead of 11 MB), then byte-identical to the full image after the swap, with and without a conversion step. Vanilla textures are 256 px or smaller, so at the default this only affects high-res texture packs.
- **Next:**
  - **Memory budget:** drop textures not drawn for a while back to their small levels, which needs a "drawn this frame" signal (a hook in `Texture::apply`, or the cull visitor).
  - **Upgrade by distance or screen size** instead of on first upload: today a texture compiled ahead for a cell becomes full size even if it is only seen far away.
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
  - **Simplified occluder meshes** from the distant mesh simplifier, so a building costs fewer triangles.
  - **Object paging chunks** usually go over the budget and are skipped, because one chunk is a whole cell's merged geometry. Drawing only their large pieces would let distant towns hide what is behind them.
