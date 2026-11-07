# TODO

## Point light shadows (asked, not started)

Recommended starting with screen-space contact shadows for point lights, which are cheap. Real cube-map shadows are
much heavier. Waiting on the user's go-ahead.

## Not yet seen on the phone

Storm lean of the grass, grass antialiasing, yellow bushels, fireflies at night, the enlarged bugs.

## More fps on tile GPUs (found 2026-11-04 on the SM-T720 test tablet)

Against a ~26.7 ms frame (37.5 fps rendered, 1280x800, Balmora canal, ANGLE on Turnip).

Items 0-2 are done (patch 0025, `support/src/bandwidth.cpp`), each a setting on by default. Measured 2026-11-06 on the
tablet, 1280x800, frame generation and point light shadows off (two runs each, game restarted per run):
- Balmora canal: all off 38.8 / 41.8 fps, all on 50.1 / 45.3 fps. Idle ripples alone 49.4 / 45.7: it is nearly all
  of it. The opaque copy is needed there (water in view); discards alone 40.5.
- South Wall Cornerclub (no water, 60 fps cap): skipping the opaque copy takes GPU busy from 73% to 63% at the same
  clock (~1.7 ms a frame). Discards: no change (63% either way); ANGLE on Turnip probably skips those loads already.
- With frame generation on the rendered rate is pinned at 30.1 either way (paced to half of 60 Hz), and with point
  light shadows (4 lights, 8 faces a frame) the canal renders ~15 fps either way: those dominate the frame now.
- 0: `[Water] idle ripples`: the simulation stops (map cleared once) 6 s after the last ripple; its targets are
  discarded before each pass. Not done: a 512x512 map on weak GPUs (`sRTTSize` is a constant the water shader shares).
- 1: `[Video] skip unused opaque copy`: the copy is made only when a later bin reads it (water, soft particles,
  distortion: found by their `opaqueDepthTex`/`opaqueColorTex`/`particleSize` uniforms), the post-processing chain has
  a user technique, or frame generation is on. **Frame generation always needs it** (first person mask), so the
  tablet's best setup (frame gen) gains nothing here; frame gen could use the scene depth when no first person model
  is drawn. Without the copy the first person depth pass into it is skipped too (one fewer framebuffer switch).
- 2: `[Video] discard unused buffers`: the window's colour/depth/stencil before the post processor's final draw, the
  first person depth after use, the ripple targets.

3. **First-person arms without framebuffer switches.** `DepthClearCallback` (npcanimation.cpp) switches render targets three
   times a frame while hands or a weapon are out, each a full tile flush. Draw them in a squeezed depth range instead.
4. **mediump in the hot shaders.** The shader converter forces highp everywhere; Adreno 6xx runs mediump at twice the rate.
   Water, terrain and lighting colour maths, carefully (banding risk).
5. **Rain ripple detail 0 measured +2.5 fps** (40.2 vs ~37.7) at the canal. The water shader skips ripples when it is not
   raining, so check whether it was raining or whether the shader conversion flattens that branch.

Ruled out by measurement: water reflection detail, groundcover, anisotropy, sunlight scattering and post-processing off
are all within noise (~2 fps) at 1280x800. Qualcomm's own GLES driver is slower than ANGLE on Turnip (30.3 vs ~37.7) and
has no DXT.
6. **Depth/stencil format.** With reverse depth the scene uses GL_DEPTH32F_STENCIL8 (~5 bytes a pixel). Where nothing
   reads stencil, use DEPTH32F alone, and invalidate depth before resolve (item 2) so it is never stored at all.
   The scene colour is already RGBA8 (4 bytes), so a smaller colour format buys nothing there; the 16-bit float targets
   are the ripple maps (item 0), the post-processing normals (RGB16F, only with normals on) and frame generation's
   motion/luma textures (512x256 and 1024x1024 RGBA16F, small but checked against R11G11B10F/RG16F).
7. **Fewer full-screen passes.** Merge the F2 passes (water, clouds, air, mist, sharpen) and the post-processing chain
   where they can share one read/write of the screen; run blur/bloom-type passes at quarter resolution.
8. **Textures.** Every texture mipmapped (a missing chain samples full size at distance), a distance mip bias, and
   compressed formats for generated textures too.
9. **Packed vertex data.** Half-float positions/UVs and 32-bit packed normals for terrain and groundcover.

## Profiling and inspection tools

- **CPU flamegraph:** `<profileable android:shell="true"/>` in the manifest lets the NDK's simpleperf profile the
  release build (view in Firefox Profiler or as SVG). Perfetto adds GPU counters and the frame timeline.
- **RenderDoc:** runs on the PC and captures over adb; attaches only to a debuggable build (or a rooted device). A
  debuggable build variant just for captures would show every pass, texture and render target of a frame.
- **What is in memory:** a support-library dump of every live GL texture and render target (name, size, format,
  estimated bytes), OSG cache sizes and `dumpsys meminfo` totals, to a file or on the performance HUD.
