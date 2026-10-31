// Wet ground of the experimental graphics ([Experimental] wet ground), added to OpenMW's terrain fragment shader by
// src/experimentalshaders.cpp. src/wetground.cpp sets:
//   a3Wetness   how wet the ground is: rises while it rains, dries slowly after (0..1)
//   a3Rain      how hard it rains now (0..1): drops ringing on the puddles
//   a3RainMap   from straight above, how high the things standing around the camera reach (red, a3RainHeight.x plus
//               a3RainHeight.y times it); the ground under them stays dry. a3RainArea: its corner (xy) and size (z) in
//               the world, w 1 once it is drawn
// The ground darkens and shines; in hollows on flat ground puddles gather (A3_PUDDLES), mirror-like, rings spreading
// where the drops fall. The world position comes from the view position (osg_ViewMatrixInverse, declared by fog.glsl).

uniform float a3Wetness;
uniform float a3Rain;
uniform float osg_SimulationTime;
uniform sampler2D a3RainMap;
uniform vec4 a3RainArea;
uniform vec2 a3RainHeight;

float a3WetHash(vec2 p)
{
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float a3WetNoise(vec2 p)
{
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(a3WetHash(i), a3WetHash(i + vec2(1.0, 0.0)), f.x),
        mix(a3WetHash(i + vec2(0.0, 1.0)), a3WetHash(i + vec2(1.0, 1.0)), f.x), f.y);
}

// 1 where something stands over this spot (higher than a little above the ground), softened at its outline
float a3Sheltered(vec3 world)
{
    if (a3RainArea.w < 0.5)
        return 0.0;
    vec2 uv = (world.xy - a3RainArea.xy) / a3RainArea.z;
    if (uv.x <= 0.0 || uv.y <= 0.0 || uv.x >= 1.0 || uv.y >= 1.0)
        return 0.0;
    float texel = 1.0 / A3_RAIN_MAP_SIZE;
    float covered = 0.0;
    for (int i = 0; i < 4; ++i)
    {
        vec2 offset = vec2(i == 1 || i == 3 ? texel : -texel, i >= 2 ? texel : -texel) * 0.75;
        float top = texture2D(a3RainMap, uv + offset).r;
        float height = a3RainHeight.x + a3RainHeight.y * top;
        covered += top > 0.002 ? smoothstep(world.z + 10.0, world.z + 30.0, height) : 0.0;
    }
    return covered * 0.25;
}

// How wet here (x) and how much puddle (y): wetter on flat ground, puddles in the hollows of a slow noise
vec2 a3WetAmount(vec3 world, vec3 groundNormal)
{
    if (a3Wetness < 0.003)
        return vec2(0.0);
    float flatness = smoothstep(0.55, 0.9, groundNormal.z);
    float wet = a3Wetness * flatness * (1.0 - a3Sheltered(world));
    float puddle = 0.0;
#if A3_PUDDLES
    float hollows = a3WetNoise(world.xy / 230.0) * 0.65 + a3WetNoise(world.xy / 70.0) * 0.35;
    // more of them the longer it rains
    float level = 0.78 - 0.16 * smoothstep(0.3, 1.0, a3Wetness);
    puddle = smoothstep(level, level + 0.04, hollows) * smoothstep(0.96, 0.99, groundNormal.z) * wet
        * smoothstep(0.25, 0.6, a3Wetness);
#endif
    return vec2(wet, puddle);
}

// Rings where drops land on a puddle: a grid of cells, one drop at a time in each, at its own place and time;
// returns how the surface tilts (xy, world)
vec2 a3Ripples(vec2 p, float rain)
{
    vec2 tilt = vec2(0.0);
    for (int layer = 0; layer < 2; ++layer)
    {
        vec2 q = p / (layer == 0 ? 36.0 : 23.0) + float(layer) * 7.31;
        vec2 cell = floor(q);
        for (int j = -1; j <= 1; ++j)
            for (int i = -1; i <= 1; ++i)
            {
                vec2 c = cell + vec2(float(i), float(j));
                float h = a3WetHash(c);
                float t = fract(osg_SimulationTime * (0.9 + 0.6 * h) + h * 13.0);
                if (h > rain * 1.2)
                    continue; // fewer drops in light rain
                vec2 centre = c + vec2(a3WetHash(c + 3.7), a3WetHash(c + 9.1));
                vec2 d = q - centre;
                float r = length(d);
                float ring = r - t * 0.9;
                float wave = sin(ring * 40.0) * smoothstep(0.08, 0.0, abs(ring)) * (1.0 - t);
                tilt += (r > 0.001 ? d / r : vec2(0.0)) * wave;
            }
    }
    return tilt * 0.35;
}

// The surface's view normal made wet: puddles lie flat, with the drops' rings on them
vec3 a3WetNormal(vec3 viewNormal, vec2 amount, vec3 world)
{
    if (amount.y <= 0.0)
        return viewNormal;
    vec3 up = normalize(gl_NormalMatrix * vec3(0.0, 0.0, 1.0));
    vec3 n = normalize(mix(viewNormal, up, amount.y * 0.85));
    if (a3Rain > 0.02)
    {
        vec2 tilt = a3Ripples(world.xy, a3Rain) * amount.y;
        n = normalize(n + (gl_NormalMatrix * vec3(tilt, 0.0)));
    }
    return n;
}

// The light a wet surface throws back: the sky's colour (the fog's, at the horizon) at a glancing look, and the sun
void a3WetShine(inout vec3 colour, vec2 amount, vec3 viewPos, vec3 n, vec3 skyColour, vec3 sunDir, vec3 sunColour)
{
    if (amount.x <= 0.0)
        return;
    vec3 v = normalize(-viewPos);
    float facing = max(dot(v, n), 0.0);
    float fresnel = 0.03 + 0.97 * pow(1.0 - facing, 5.0);
    float mirror = 0.25 * amount.x + 0.75 * amount.y;
    float glint = pow(max(dot(reflect(-normalize(sunDir), n), v), 0.0), mix(24.0, 220.0, amount.y));
    colour = mix(colour, skyColour, fresnel * mirror) + sunColour * glint * (0.25 * amount.x + 1.6 * amount.y);
}
