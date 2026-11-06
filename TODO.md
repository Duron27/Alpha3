# TODO

## Point light shadows (asked, not started)

Recommended starting with screen-space contact shadows for point lights, which are cheap. Real cube-map shadows are
much heavier. Waiting on the user's go-ahead.

## Not yet seen on the phone

Storm lean of the grass, grass antialiasing, yellow bushels, fireflies at night, the enlarged bugs.

## More fps on tile GPUs (found 2026-11-04 on the SM-T720 test tablet)

Against a ~26.7 ms frame (37.5 fps rendered, 1280x800, Balmora canal, ANGLE on Turnip).

0. **Water ripple simulation: measured +5 fps (37.4 -> 42.6, ~3.3 ms) with it skipped.** `ripples.cpp` runs two full
   1024x1024 RGBA16F passes every frame whenever water is visible (outdoors nearly always), whether or not anything touches
   the water. On GLES it takes the fragment path (compute needs GL 4.4) with no clear, so a tiler loads, samples and stores
   8 MB twice. Fixed cost, not scaled by the window, so it hits every device. Fix: stop simulating once nothing has emitted
   for a few seconds (the damping flattens the field; clear it once and idle), invalidate the target before each pass, and
   consider 512x512 on weak GPUs.

1. **Skip the opaque colour/depth copy when nothing reads it.** `opaqueblit.cpp` (the OpaqueResolve bin) copies the scene's
   colour and depth after the opaque geometry every frame. On a tiler that is a mid-frame flush, a ~8 MB copy and a reload
   (~2.5-3.5 ms at 1280x800). Only water refraction and the first-person depth fix read it. Expect +3-5 fps where no water
   is on screen, ~0 looking at water.
2. **glInvalidateFramebuffer.** Never called by OpenMW, OSG or the support library. Discard the window's unused depth and
   stencil (post-processing draws to its own FBO), the window's colour before the final full-screen copy, and the first-person
   depth renderbuffer after use. +0-1.5 fps; drivers may already skip some of it.
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
