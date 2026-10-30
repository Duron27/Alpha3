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

## 4. Occlusion culling

Skip drawing things hidden behind other things, like buildings behind a wall in Vivec or Balmora.

- **Options:**
  - GPU occlusion queries that use the previous frame's results, so the CPU never waits on the GPU.
  - A small CPU software rasterizer that draws big occluders (terrain, large buildings) into a low-res depth buffer and tests object bounding boxes against it. This avoids GPU readbacks, which are slow on mobile and on ANGLE/Metal.
- Exteriors in towns gain the most. Interiors already get most of the benefit from cell boundaries.
- Measure first: profile a few heavy spots (Vivec, Balmora, Ald'ruhn) to see whether the draw calls are the bottleneck.
