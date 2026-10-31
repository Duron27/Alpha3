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
