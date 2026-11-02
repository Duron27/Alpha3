// The grass fire's flames, sparks and smoke (src/grassfire.cpp), one quad each made by the vertex shader. One source for
// both stages: A3_VERTEX picks the vertex shader. Premultiplied alpha: flames mostly add light, smoke covers.
//   gl_Vertex          where it is (world; drawn with the view matrix as its model view)
//   gl_MultiTexCoord0  xy the corner (-1..1), z kind (0 flame, 1 + lightness smoke, 2 spark), w seed
//   gl_MultiTexCoord1  x width, y height (smoke: its age in seconds), z age (0..1 of its life), w strength

varying vec2 a3Corner;
varying vec4 a3Look; // kind, seed, age, strength
varying vec3 a3ViewPos;

#ifdef A3_VERTEX

uniform mat4 projectionMatrix; // OpenMW's, reversed with reverse-Z (see grass.glsl)
uniform float windSpeed;

void main()
{
    vec4 c0 = gl_MultiTexCoord0;
    vec4 c1 = gl_MultiTexCoord1;
    float kind = floor(c0.z);
    vec3 world = gl_Vertex.xyz;
    // the engine's storms blow toward +y; smoke goes with the wind
    vec3 wind = vec3(0.25, 1.0, 0.0) * windSpeed;
    vec3 pos;
    if (kind > 0.5 && kind < 1.5)
    {
        world += wind * 45.0 * c1.y;
        vec3 centre = (gl_ModelViewMatrix * vec4(world, 1.0)).xyz;
        float spin = c0.w * 6.2831853 + c1.y * (c0.w - 0.5) * 0.4;
        vec2 corner = vec2(c0.x * cos(spin) - c0.y * sin(spin), c0.x * sin(spin) + c0.y * cos(spin));
        pos = centre + vec3(corner * c1.x * 0.5, 0.0);
    }
    else
    {
        // upright, turned about the up axis toward the camera, leaning with the wind toward the tip
        vec3 centre = (gl_ModelViewMatrix * vec4(world, 1.0)).xyz;
        vec3 up = normalize((gl_ModelViewMatrix * vec4(0.0, 0.0, 1.0, 0.0)).xyz);
        vec3 right = cross(up, normalize(-centre));
        right = length(right) > 0.001 ? normalize(right) : vec3(1.0, 0.0, 0.0);
        float y = kind > 1.5 ? c0.y : c0.y * 0.5 + 0.5;
        vec3 lean = (gl_ModelViewMatrix * vec4(wind * 0.35, 0.0)).xyz;
        pos = centre + right * c0.x * c1.x * 0.5 + (up + lean * y) * y * c1.y;
    }
    gl_Position = projectionMatrix * vec4(pos, 1.0);
    gl_ClipVertex = vec4(pos, 1.0);
    a3Corner = c0.xy;
    a3Look = vec4(c0.z, c0.w, c1.z, c1.w);
    a3ViewPos = pos;
}

#else

uniform float osg_SimulationTime;

struct A3Sun
{
    vec4 position;
    vec4 diffuse;
    vec4 ambient;
    vec4 specular;
};
uniform A3Sun sun;

struct A3Fog
{
    vec4 color;
    vec4 underwaterColor;
    float start;
    float underwaterStart;
    float end;
    float underwaterEnd;
    float depth;
};
uniform A3Fog fog;

float a3Hash(vec2 p)
{
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

float a3Noise(vec2 p)
{
    vec2 i = floor(p);
    vec2 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(a3Hash(i), a3Hash(i + vec2(1.0, 0.0)), f.x), mix(a3Hash(i + vec2(0.0, 1.0)), a3Hash(i + vec2(1.0, 1.0)), f.x),
        f.y);
}

float a3Fbm(vec2 p)
{
    float v = 0.0;
    float amplitude = 0.5;
    for (int i = 0; i < 4; ++i)
    {
        v += a3Noise(p) * amplitude;
        p = p * 2.03 + vec2(1.7, -9.2);
        amplitude *= 0.5;
    }
    return v / 0.9375;
}

void main()
{
    float kind = floor(a3Look.x);
    float seed = a3Look.y;
    float age = a3Look.z;
    float strength = a3Look.w;
    vec2 p = a3Corner;
    vec4 colour;
    if (kind < 0.5)
    {
        // a tongue of flame: turbulence rising through a teardrop of heat, its top torn into licks; the heat's colour
        // as fire has it (white-yellow at the core, orange, a dull red at the tips), added as light
        float y = p.y * 0.5 + 0.5;
        float t = osg_SimulationTime * 2.6 + seed * 19.0;
        vec2 q = vec2(p.x * 1.6 + seed * 11.0, y * 2.2 - t);
        float n = a3Fbm(q);
        float n2 = a3Fbm(vec2(p.x * 3.3 - seed * 5.0, y * 4.4 - t * 1.6));
        // swaying more toward its top
        float x = p.x + (n - 0.5) * 0.7 * y;
        float width = 0.62 * pow(1.0 - y, 0.75) + 0.04;
        float body = 1.0 - smoothstep(width * 0.35, width, abs(x));
        float heat = body * (1.15 - 1.25 * y + (n2 - 0.5) * 1.1);
        heat = clamp(heat, 0.0, 1.0) * smoothstep(0.0, 0.15, y);
        // flaring up, then dying from the top down
        heat *= smoothstep(0.0, 0.12, age) * (1.0 - smoothstep(0.45, 1.0, age + y * 0.3));
        heat *= strength;
        vec3 c = vec3(0.85, 0.16, 0.02) * smoothstep(0.02, 0.3, heat);
        c += vec3(0.15, 0.42, 0.06) * smoothstep(0.25, 0.6, heat);
        c += vec3(0.0, 0.32, 0.38) * smoothstep(0.55, 0.95, heat);
        colour = vec4(c * heat * 1.7, heat * 0.04);
    }
    else if (kind < 1.5)
    {
        // a billow of smoke, lit by the sun and the sky, thinning out as it rises and spreads
        float r = length(p);
        float n = a3Noise(p * 1.7 + seed * 13.0 + vec2(0.0, -age * 2.0)) * 0.6 + a3Noise(p * 3.9 - seed * 7.0) * 0.4;
        float density = 1.0 - smoothstep(0.2, 1.0, r + (n - 0.5) * 0.7);
        float alpha = density * strength * smoothstep(0.0, 0.12, age) * (1.0 - smoothstep(0.45, 1.0, age));
        float lightness = fract(a3Look.x);
        vec3 base = mix(vec3(0.07, 0.065, 0.06), vec3(0.66, 0.66, 0.64), lightness);
        vec3 lit = base * (sun.ambient.xyz + sun.diffuse.xyz * (0.45 + 0.35 * n));
        colour = vec4(lit * alpha, alpha);
    }
    else
    {
        // a spark: a hot streak, twinkling as it cools
        float r = length(p * vec2(1.0, 0.4));
        float s = 1.0 - smoothstep(0.1, 1.0, r);
        float twinkle = 0.6 + 0.4 * sin(osg_SimulationTime * 31.0 + seed * 50.0);
        vec3 c = mix(vec3(1.0, 0.85, 0.5), vec3(1.0, 0.3, 0.05), age) * 3.0;
        colour = vec4(c * s * strength * twinkle * (1.0 - age), s * 0.1 * strength);
    }
    float f = clamp((length(a3ViewPos) - fog.start) / max(fog.end - fog.start, 1.0), 0.0, 1.0);
    colour.rgb = mix(colour.rgb, fog.color.rgb * colour.a, f);
    gl_FragData[0] = colour;
    gl_FragData[1] = vec4(0.0); // the normals, where there are: left as they are
}

#endif
