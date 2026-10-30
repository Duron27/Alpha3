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
- **Status: first version done (trees), no OpenMW patch.** `cpp/openmw-upstream/support/src/impostors.cpp`. Object paging already asks for `<mesh>_dist.nif` when it builds a chunk outside the active grid, so a support-library archive answers for the meshes in `[Terrain] impostor meshes`. Active cells never see them. The first time a card is needed, the mesh is drawn on the CPU from 6 sides into one texture, and a NIF of 3 crossed two-sided quads is written. Both are cached in `cache/impostors/`. Off by default (`[Terrain] impostors = true` turns it on). Tested on the vanilla BSAs: all 72 tree meshes, about 7 ms each, about 130 KB each.
- **Next:**
  - **Rocks and buildings:** crossed cards look wrong for boxy or solid shapes (you see the planes). These need generated low-poly meshes instead: simplify the mesh (meshoptimizer), bake the textures into one atlas, and serve it under the same `_dist.nif` name. Small rocks may work as cards; try them by adding their names to `impostor meshes`.
  - **Pick by use, not by name:** count how often each static is placed in the loaded ESMs and convert the most used ones.
  - **Compress the atlas:** use ETC2/ASTC with the encoders already linked, about 4x smaller.
  - **Real-device check:** lighting (the card normals point up), the alpha test at low mips, and what the pop at the active grid edge looks like.

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
