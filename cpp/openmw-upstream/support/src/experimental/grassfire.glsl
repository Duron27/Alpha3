// The grass fire's rings (experimental graphics, [Experimental] grass fire), read by the grass's vertex shader
// (experimental/grass.glsl) and OpenMW's terrain fragment shader (src/experimentalshaders.cpp). src/grassfire.cpp sets:
//   a3Fire[i]       xyz where fire i started, on the ground; w how far its front has gone (0: no fire)
//   a3FireState[i]  x how hard its front burns (0..1: lower in the rain, 0 once it is out), y the burning band's
//                   width, z how far the grass has grown back (0..1), w its seed (the shape of its front)
//   a3FireReach     how far each fire can go in 16 directions (four vec4 a fire, the first toward -x, going round
//                   counterclockwise): where its grass ends, at a road, a rock, water or a cliff
//   a3FireCount     how many fires there are (0: nothing to do)
//   a3FireTime      seconds, for the flicker
// A front goes out as a ripple, ragged by its seed, and stops at the end of the grass; behind it the ground is burnt.

uniform vec4 a3Fire[4];
uniform vec4 a3FireState[4];
uniform vec4 a3FireReach[16];
uniform float a3FireCount;
uniform float a3FireTime;

float a3FirePick(vec4 v, float i)
{
    return i < 0.5 ? v.x : i < 1.5 ? v.y : i < 2.5 ? v.z : v.w;
}

// fire's reach toward direction bin (0..16, wrapping)
float a3FireReachAt(int fire, float bin)
{
    float b = bin - 16.0 * floor(bin / 16.0);
    float slot = floor(b / 4.0);
    return a3FirePick(a3FireReach[fire * 4 + int(slot)], b - 4.0 * slot);
}

// Of the fires here (jitter -0.5..0.5 makes the edges ragged, a blade's or a speck's own):
//   burn.x  burnt (0..1; in the burning band it goes from 0 at the front to 1 at the band's back)
//   burn.y  glowing: burning in the band, embers dying away behind it
//   burn.z  the flames' light on it
//   burn.w  how far it has grown back (1 where nothing burnt)
//   heat    just ahead of a front: the blades there scorch and writhe
void a3FireAt(vec3 world, float jitter, out vec4 burn, out float heat)
{
    burn = vec4(0.0, 0.0, 0.0, 1.0);
    heat = 0.0;
    if (a3FireCount < 0.5)
        return;
    for (int i = 0; i < 4; ++i)
    {
        vec4 fire = a3Fire[i];
        if (fire.w <= 0.0)
            continue;
        vec2 d = world.xy - fire.xy;
        float dist = length(d);
        if (dist > fire.w * 1.25 + 300.0 || abs(world.z - fire.z) > 900.0)
            continue;
        vec4 state = a3FireState[i];
        float angle = dist > 0.5 ? atan(d.y, d.x) : 0.0;
        float seed = state.w * 6.2831853;
        float wobble = 1.0 + 0.08 * sin(3.0 * angle + seed) + 0.05 * sin(7.0 * angle + 2.3 * seed)
            + 0.03 * sin(13.0 * angle + 4.1 * seed);
        float bin = (angle / 6.2831853 + 0.5) * 16.0;
        float b0 = floor(bin);
        float reach = mix(a3FireReachAt(i, b0), a3FireReachAt(i, b0 + 1.0), bin - b0) + jitter * 30.0;
        float width = max(state.y, 1.0);
        float front = fire.w * wobble + jitter * width * 0.6;
        float edge = min(front, reach);
        // the front dies down as it comes to the end of the grass
        float live = state.x * (1.0 - smoothstep(reach - 2.0 * width, reach, front));
        float behind = edge - dist;
        float flicker = 0.75 + 0.25 * sin(a3FireTime * 9.0 + angle * 5.0 + dist * 0.05)
            * sin(a3FireTime * 5.3 + angle * 3.0);
        if (behind > 0.0)
        {
            float burnt = clamp(behind / max(width * live, 1.0), 0.0, 1.0);
            float band = live * (1.0 - burnt) * smoothstep(0.0, width * 0.15, behind);
            float embers = state.x * exp(-max(behind - width, 0.0) / (width * 2.5)) * 0.6;
            if (burnt >= burn.x)
                burn.w = state.z;
            burn.x = max(burn.x, burnt);
            burn.y = max(burn.y, max(band, embers) * flicker);
        }
        else
            heat = max(heat, live * (1.0 - smoothstep(0.0, 90.0, -behind)));
        burn.z += live * exp(-abs(behind) / 160.0) * flicker;
    }
}

#ifdef A3_FIRE_TERRAIN
float a3FireHash(vec2 p)
{
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float a3FireNoise(vec2 p)
{
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(a3FireHash(i), a3FireHash(i + vec2(1.0, 0.0)), f.x),
        mix(a3FireHash(i + vec2(0.0, 1.0)), a3FireHash(i + vec2(1.0, 1.0)), f.x), f.y);
}

// The ground under the fires: x charred (fading unevenly as the grass grows back), y glowing embers, z the flames'
// light; colour: what the ground turns to, black char streaked with grey ash
vec3 a3FireGround(vec3 world, out vec3 colour)
{
    colour = vec3(0.0);
    vec4 burn;
    float heat;
    float speck = a3FireNoise(world.xy / 9.0);
    a3FireAt(world, speck - 0.5, burn, heat);
    if (burn.x <= 0.0 && burn.z <= 0.0)
        return vec3(0.0);
    float patches = a3FireNoise(world.xy / 47.0) * 0.6 + speck * 0.4;
    colour = mix(vec3(0.028, 0.025, 0.022), vec3(0.24, 0.23, 0.215), smoothstep(0.55, 0.85, patches));
    float charred = burn.x * (1.0 - smoothstep(0.15 + 0.6 * patches, 1.0, burn.w)) * 0.93;
    // embers in specks, each flaring and dimming
    float ember = smoothstep(0.62, 0.9, a3FireNoise(world.xy / 3.5 + 17.0))
        * (0.6 + 0.4 * sin(a3FireTime * 6.0 + speck * 40.0));
    return vec3(charred, burn.y * ember * 2.0 + burn.y * 0.15, burn.z);
}
#endif
