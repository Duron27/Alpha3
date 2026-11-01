# TODO

## Wet ground in rain ([Experimental] wet ground) — in progress

The ground darkens and shines while it rains, puddles gather in hollows with rings where drops land, and the ground under
objects stays dry in their outline. Shader written: `cpp/openmw-upstream/support/src/experimental/wetground.glsl`
(not yet wired in or validated with glslangValidator).

Remaining:

1. Add `wetground wetground.glsl` to `ALPHA3_EXPERIMENTAL_SOURCES` in `support/CMakeLists.txt`. Add `mWet` to
   `ExperimentalShaderParts` and fill it in `experimental.cpp`. Add it to the shader stamp hash.
2. Settings, each with its own entry in `settings.fallback.cfg`, `ExperimentalSettings` and the parsing:
   `wet ground = true`, `wet ground puddles = true`, `wet ground dry under objects = true`.
3. `injectTerrainFragment` (`experimentalshaders.cpp`): add `s.mWetGround` to the guard. Add the library
   (`#define A3_RAIN_MAP_SIZE 512.0`, `#define A3_PUDDLES 0/1`, the part). Then insert:
   - before `    vec4 diffuseColor = getDiffuseColor(material, passColor);`: compute `a3World` from `passViewPos`
     with `osg_ViewMatrixInverse`, compute `a3Wet = a3WetAmount(a3World, normalize(passNormal))`, and darken
     `gl_FragData[0].xyz`.
   - before `    float shadowing = unshadowedLightRatio(linearDepth);`:
     `viewNormal = a3WetNormal(viewNormal, a3Wet, a3World);`
   - after `    gl_FragData[0].xyz = gl_FragData[0].xyz * lighting + specular;`: call `a3WetShine(...)` with
     `fog.color` and `sun.position`/`sun.diffuse`.
4. OpenMW patch `0020` (`renderingmanager.cpp`): after `mWater->setRainIntensity(...)`, call
   `Alpha3::setRain(precipitation, dt)`. Declare it in `support/include/alpha3/upstream.hpp`. Generate the patch from
   the pinned omw clone, in the format of 0018.
5. `support/src/wetground.cpp`:
   - Wetness: rises about dt/40 × rain intensity while it rains, dries about dt/240.
   - Uniforms on the SceneCam's stateset: `a3Wetness`, `a3Rain`, `a3RainArea`, `a3RainHeight`, and the `a3RainMap`
     sampler on unit 15.
   - Rain map: a "A3RainCam" camera.
     - PRE_RENDER, ortho, 2048 units around the camera, snapped to texels, looking straight down.
     - 512² GL_RGBA16F target (8 bits are too coarse for height). GL_MAX blend, no depth test.
     - An OVERRIDE|PROTECTED program writes `clamp((worldZ - base)/range, 0.004, 1)`.
     - Cull mask Object|Static|Actor (paged statics are Mask_Static; terrain is excluded).
     - Use `ABSOLUTE_RF_INHERIT_VIEWPOINT` so QuadTreeWorld/object paging pick LODs from the player's viewpoint.
   - Hook it in with a cull callback added once (`addCullCallback`) to the SceneCam, not inside drawableAdded, so the
     state graph is at the root. The callback traverses the scene, then `rainCam->accept(cv)`. The rain cam's own cull
     callback traverses the SceneCam's children, so the graph is not changed.
   - Redraw only when the camera has moved more than 128 units or about every 2 s, and only while wetness > 0.
6. Build, install, and test on the phone in rain: in the console, `ChangeWeather "Ascadian Isles Region" 4`.

## Point light shadows (asked, not started)

Recommended starting with screen-space contact shadows for point lights, which are cheap. Real cube-map shadows are
much heavier. Waiting on the user's go-ahead.

## Not yet seen on the phone

Storm lean of the grass, grass antialiasing, yellow bushels, fireflies at night, the enlarged bugs.

## Requested 2026-11-01 (in this order)

1. Wet ground in rain (above): finish it.
2. Water reflections: OpenMW's planar reflection is slow. Add a post-processing pass with screen-space reflections,
   and fill what is off screen from a cheap cube map (the sky, a probe). Reference:
   https://github.com/wareya/OpenMW-Shaders/blob/main/shaders/waterNew2.omwfx
3. Shoreline: foam and lighter shallows where the water meets the ground.
4. Steam or mist over water (cold, dawn, rain).
5. Wind, air and dust: dust motes drifting in sunbeams, dust and ash blown along in storms, light shafts.
6. Volumetric clouds with lots of options: coverage, height, thickness, speed, quality, colour by weather.
7. Trees where there are leaf textures: look fuller. Thicker, softer canopy edges (alpha to coverage or dithered
   fringe), light through the leaves from the sun, wrap lighting, colour variation per cluster.

Every feature gets its own entries in settings.fallback.cfg ([Experimental]) and on the phone's settings.cfg.
Post-processing ones are .omwfx passes put in the chain (like a3_look) with their uniforms in the in-game menu.
8. Sharp textures (asked 2026-11-01): the generated/procedural maps were softened toward vanilla (forParallax blur and
   compression). Make them very sharp, detailed and upscaled looking instead: no blur, stronger relief, a sharpening
   pass on the colour, finer procedural detail.
9. More critters (asked 2026-11-01): birds flying overhead (flocks circling, gliding, flapping), more kinds of
   small animals among the grass.
10. Every [Experimental] setting in the F2 post-processing menu, changeable live (asked 2026-11-01). The
    post-processing passes (water, clouds, air, sharpen, look, AO) already have their strengths there. For the rest
    (grass, bugs, tree sway, fuller leaves, wet ground, procedural, detail, ...):
    - an "a3_experimental" technique whose uniforms are those settings;
    - the support library reads them each frame and feeds the shaders uniforms instead of compile-time #defines;
    - on/off switches are a uniform branch, or a program variant picked by a state define.
11. VFS browser (asked 2026-11-01), WRITTEN, NOT YET BUILT: support/lua/scripts/alpha3/vfsbrowser.lua (Options,
    Scripts, Alpha3, VFS browser: Open), shipped as resources/vfs/alpha3.omwscripts by support/CMakeLists.txt. Folders and
    files a page at a time, search, size, source (alpha3/vfs-sources.txt made by experimental.cpp), texture/text/hex
    preview. Still to do: mesh previews.

## DONE, TO CHECK ON THE PHONE (2026-11-01): every experimental setting in the F2 menu
- settings.cfg [Experimental] is gone (moved once to shaders.yaml; material rules and tree sway/trunk textures are still
  read from it if given). Settings-only pages (generated by experimental.cpp, never drawn, need not be in the chain): a3_grass, a3_trees, a3_wetground, a3_terrain, a3_materials, a3_maps, a3_testing (test weather), holding
  grass, critters, trees, wet ground, terrain, procedural and generated maps; the passes hold their own toggles and
  values and are on while in the chain.
- Live (while F2 is open): procedural/detail strength and distance, height blend sharpness, tree sway, trunk sway, leaf
  translucency and variation, grass wind and storm, wet ground off; every pass value. The rest after a restart (marked).
- Outside F2 OpenMW compiles the passes' static uniforms in as constants, so a toggle that is off costs nothing.
- The F2 window clamped onto the screen at start (the phone's was h 1.11, y -0.007).
- Next: grass density/height/distance live by rebuilding the tiles; a pass toggle for the clouds' resolution.
