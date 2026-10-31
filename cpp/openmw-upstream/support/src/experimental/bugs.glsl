// Bugs among the grass of the experimental graphics ([Experimental] grass bugs), drawn by src/grass.cpp with the tiles'
// blades. One source for both stages: A3_VERTEX picks the vertex shader. GLSL 1.20, as OpenMW's own shaders.
//
// Each bug is a quad (four vertices) carrying its home in gl_Vertex (the terrain chunk's space, on the ground); where it
// is now, which way it heads and how it looks are worked out here from the time, so nothing is updated on the CPU:
//   gl_MultiTexCoord0: xy the quad's corner (-1 or 1), z its kind, w a random number of its own
//   gl_MultiTexCoord1: x where along its path (ants in a line, gnats in a swarm share a home), y how far it roams,
//                      z how high it flies, w its size (half its length)
//   gl_MultiTexCoord2: xy the ground's slope there (dz/dx, dz/dy), z its speed
// Kinds: 0 ant, 1 beetle, 2 grasshopper (on the ground); 3 bee, 4 butterfly, 5 dragonfly, 6 gnat, 7 firefly (flying).
// Fliers keep out of the wind and the rain (OpenMW's windSpeed), the day ones fly by day, fireflies glow at night, by
// how bright the sun's light is. Past A3_BUG_DISTANCE nothing is drawn.

varying vec2 a3Uv;
varying vec3 a3ViewPos;
varying float a3Kind;
varying float a3Seed;
varying float a3Flap; // wings: how open (butterflies), a firefly's glow

struct A3Sun
{
    vec4 position;
    vec4 diffuse;
    vec4 ambient;
    vec4 specular;
};
uniform A3Sun sun;

#ifdef A3_VERTEX

uniform float osg_SimulationTime;
uniform mat4 projectionMatrix;
uniform float windSpeed;

float hash(float n)
{
    return fract(sin(n) * 43758.5453);
}

vec2 hash2(float n)
{
    return vec2(hash(n), hash(n + 17.31)) * 2.0 - 1.0;
}

void main()
{
    vec3 home = gl_Vertex.xyz;
    vec2 corner = gl_MultiTexCoord0.xy;
    float kind = gl_MultiTexCoord0.z;
    float seed = gl_MultiTexCoord0.w;
    float along = gl_MultiTexCoord1.x;
    float roam = gl_MultiTexCoord1.y;
    float height = gl_MultiTexCoord1.z;
    float size = gl_MultiTexCoord1.w;
    vec2 slope = gl_MultiTexCoord2.xy;
    float speed = gl_MultiTexCoord2.z;
    float time = osg_SimulationTime + seed * 100.0;

    float daylight = clamp(dot(sun.diffuse.xyz, vec3(0.333)) * 1.6, 0.0, 1.0);
    float shown = 1.0;
    if (kind > 2.5 && kind < 5.5)
        shown = step(0.35, daylight) * step(windSpeed, 0.55);
    else if (kind > 6.5)
        shown = step(daylight, 0.25) * step(windSpeed, 0.5);
    else if (kind > 5.5)
        shown = step(windSpeed, 0.6);

    vec3 pos;
    vec2 heading = vec2(1.0, 0.0);
    float flap = 1.0;
    bool ground = kind < 2.5;
    if (kind < 0.5 || (kind > 0.5 && kind < 1.5))
    {
        // ants follow a winding loop, one after another; beetles the same, slowly, stopping now and then
        float t = kind < 0.5 ? time * speed : (floor(time * speed / 4.0) + smoothstep(0.0, 0.6, fract(time * speed / 4.0))) * 4.0;
        float s = along + t / roam;
        float a = seed * 6.0;
        pos = vec3(roam * vec2(cos(s) + 0.3 * cos(3.0 * s + a), sin(s) + 0.25 * sin(2.0 * s + a)), 0.0);
        heading = vec2(-sin(s) - 0.9 * sin(3.0 * s + a), cos(s) + 0.5 * cos(2.0 * s + a));
        pos.xy += vec2(-heading.y, heading.x) / max(length(heading), 0.001) * sin(time * 15.0 + along * 40.0) * 0.3;
    }
    else if (kind < 2.5)
    {
        // grasshoppers: a hop now and then, from one spot to the next
        float n = floor(time / 2.5);
        float f = fract(time / 2.5);
        float u = clamp(f / 0.22, 0.0, 1.0);
        vec2 from = hash2(n + seed * 91.0) * roam, to = hash2(n + 1.0 + seed * 91.0) * roam;
        pos = vec3(mix(from, to, smoothstep(0.0, 1.0, u)), 4.0 * 16.0 * u * (1.0 - u));
        heading = to - from;
    }
    else if (kind < 3.5)
    {
        // bees: quick figure eights about their patch, bobbing
        float s = along + time * speed / roam;
        float r = seed * 6.28;
        vec2 p = roam * vec2(sin(s), sin(s) * cos(s));
        vec2 d = vec2(cos(s), cos(2.0 * s));
        mat2 turn = mat2(cos(r), sin(r), -sin(r), cos(r));
        pos = vec3(turn * p + vec2(sin(time * 7.0), cos(time * 6.3)) * 2.0, height + 6.0 * sin(time * 2.0));
        heading = turn * d;
    }
    else if (kind < 4.5)
    {
        // butterflies: a wandering path, rising and falling with every beat of the wings
        float s = along + time * speed / roam;
        pos = vec3(roam * vec2(sin(s) + 0.5 * sin(2.3 * s), cos(0.8 * s) + 0.4 * sin(1.7 * s)),
            height + 14.0 * sin(time * 0.9 + seed * 9.0));
        heading = vec2(cos(s) + 1.15 * cos(2.3 * s), -0.8 * sin(0.8 * s) + 0.68 * cos(1.7 * s));
        flap = sin(time * 13.0 + seed * 20.0);
        pos.z += 3.0 * abs(flap);
    }
    else if (kind < 5.5)
    {
        // dragonflies: hover, then dart to another spot
        float n = floor(time / 1.7);
        float f = fract(time / 1.7);
        vec2 from = hash2(n + seed * 53.0) * roam, to = hash2(n + 1.0 + seed * 53.0) * roam;
        pos = vec3(mix(from, to, smoothstep(0.65, 0.85, f)) + vec2(sin(time * 5.0), cos(time * 4.0)) * 0.8,
            height + 4.0 * sin(time * 1.3));
        heading = to - from;
    }
    else if (kind < 6.5)
    {
        // gnats: a cloud hanging in one place, each jittering
        pos = vec3(9.0 * vec2(sin(time * 3.1 + along * 17.0), cos(time * 2.7 + along * 11.0))
                + 2.5 * vec2(sin(time * 13.0 + along * 31.0), cos(time * 11.0 + along * 23.0)),
            height + 7.0 * sin(time * 2.3 + along * 7.0));
        heading = vec2(cos(time * 3.1 + along * 17.0), -sin(time * 2.7 + along * 11.0));
    }
    else
    {
        // fireflies: drifting slowly, glowing on and off
        pos = vec3(roam * vec2(sin(time * 0.13 + along * 7.0) + 0.4 * sin(time * 0.37 + along * 3.0),
                       cos(time * 0.11 + along * 5.0) + 0.4 * cos(time * 0.29 + along * 2.0)),
            height + 18.0 * sin(time * 0.3 + along * 9.0));
        heading = vec2(cos(time * 0.13 + along * 7.0), -sin(time * 0.11 + along * 5.0));
        flap = smoothstep(0.2, 1.0, sin(time * 1.7 + along * 13.0));
    }
    // on the ground's slope (a plane through its home), so walkers keep to it and fliers keep their height above it
    pos.z += dot(slope, pos.xy) + 0.3;
    vec3 centre = home + pos;
    heading = length(heading) > 0.001 ? normalize(heading) : vec2(1.0, 0.0);

    vec4 viewCentre = gl_ModelViewMatrix * vec4(centre, 1.0);
    float dist = length(viewCentre.xyz);
    float far = kind < 0.5 || kind > 5.5 && kind < 6.5 ? A3_BUG_DISTANCE * 0.45 : A3_BUG_DISTANCE;
    size *= shown * step(dist, far);

    // its outline is a quad along the way it heads: lying on the ground for walkers, facing the camera for fliers
    float wide = kind > 2.5 && kind < 4.5 ? 1.0 : kind > 4.5 && kind < 5.5 ? 0.85 : kind > 0.5 && kind < 1.5 ? 0.7 : 0.5;
    vec4 viewPos;
    if (ground)
    {
        vec2 side = vec2(-heading.y, heading.x);
        vec3 offset = vec3(heading * corner.x + side * corner.y * wide, 0.0) * size;
        offset.z += dot(slope, offset.xy);
        viewPos = gl_ModelViewMatrix * vec4(centre + offset, 1.0);
    }
    else
    {
        vec2 screen = (gl_ModelViewMatrix * vec4(heading, 0.0, 0.0)).xy;
        screen = length(screen) > 0.001 ? normalize(screen) : vec2(1.0, 0.0);
        vec2 side = vec2(-screen.y, screen.x);
        viewPos = viewCentre + vec4((screen * corner.x + side * corner.y * wide) * size, 0.0, 0.0);
    }
    gl_Position = projectionMatrix * viewPos;
    gl_ClipVertex = viewPos;
    a3ViewPos = viewPos.xyz;
    a3Uv = corner;
    a3Kind = kind;
    a3Seed = seed;
    a3Flap = flap;
}

#else

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

// inside an ellipse: under 1
float blob(vec2 p, vec2 centre, vec2 radius)
{
    vec2 d = (p - centre) / radius;
    return dot(d, d);
}

void main()
{
    vec2 p = a3Uv; // x along the bug (head at -1), y across
    float kind = a3Kind;
    float seed = a3Seed;
    vec3 colour = vec3(0.0);
    bool inside = false;
    bool wing = false;
    bool glow = false;

    if (kind < 0.5)
    {
        // ant: head, thorax, abdomen; black, or red now and then
        inside = blob(p, vec2(-0.68, 0.0), vec2(0.22, 0.55)) < 1.0 || blob(p, vec2(-0.15, 0.0), vec2(0.24, 0.4)) < 1.0
            || blob(p, vec2(0.5, 0.0), vec2(0.42, 0.75)) < 1.0;
        colour = seed < 0.3 ? vec3(0.32, 0.08, 0.03) : vec3(0.05, 0.04, 0.03);
    }
    else if (kind < 1.5)
    {
        // beetle: a domed shell parted down the middle, a small head; green-black, brown or black
        float shell = blob(p, vec2(0.12, 0.0), vec2(0.85, 0.95));
        inside = shell < 1.0 || blob(p, vec2(-0.82, 0.0), vec2(0.2, 0.4)) < 1.0;
        colour = seed < 0.35 ? vec3(0.05, 0.16, 0.09) : seed < 0.7 ? vec3(0.22, 0.12, 0.05) : vec3(0.04, 0.04, 0.05);
        colour *= abs(p.y) < 0.06 && p.x > -0.6 ? 0.4 : 1.0 + 0.6 * (1.0 - shell);
    }
    else if (kind < 2.5)
    {
        // grasshopper: a long body, the big hind legs folded along it
        inside = blob(p, vec2(0.0, 0.0), vec2(0.95, 0.4)) < 1.0 || blob(p, vec2(0.35, 0.55), vec2(0.55, 0.16)) < 1.0
            || blob(p, vec2(0.35, -0.55), vec2(0.55, 0.16)) < 1.0;
        colour = seed < 0.6 ? vec3(0.25, 0.42, 0.10) : vec3(0.42, 0.33, 0.16);
    }
    else if (kind < 3.5)
    {
        // bee: a striped body, a dark head, wings blurred above
        bool body = blob(p, vec2(0.15, 0.0), vec2(0.72, 0.42)) < 1.0;
        bool head = blob(p, vec2(-0.72, 0.0), vec2(0.22, 0.28)) < 1.0;
        wing = !body && !head
            && (blob(p, vec2(0.0, 0.5), vec2(0.4, 0.32)) < 1.0 || blob(p, vec2(0.0, -0.5), vec2(0.4, 0.32)) < 1.0);
        inside = body || head || wing;
        colour = head ? vec3(0.05) : wing ? vec3(0.85, 0.88, 0.92) : (sin(p.x * 14.0) > 0.0 ? vec3(0.9, 0.68, 0.08) : vec3(0.06, 0.05, 0.03));
    }
    else if (kind < 4.5)
    {
        // butterfly: two pairs of wings opening and closing about a thin body, edged darker
        float open = max(abs(a3Flap), 0.15);
        vec2 q = vec2(p.x, p.y / open);
        float fore = min(blob(q, vec2(-0.2, 0.5), vec2(0.45, 0.5)), blob(q, vec2(-0.2, -0.5), vec2(0.45, 0.5)));
        float hind = min(blob(q, vec2(0.35, 0.4), vec2(0.32, 0.38)), blob(q, vec2(0.35, -0.4), vec2(0.32, 0.38)));
        bool body = blob(p, vec2(0.0, 0.0), vec2(0.7, 0.09)) < 1.0;
        float w = min(fore, hind);
        inside = body || w < 1.0;
        vec3 tint = seed < 0.3 ? vec3(0.95, 0.94, 0.88) : seed < 0.55 ? vec3(0.95, 0.82, 0.18)
            : seed < 0.8 ? vec3(0.95, 0.45, 0.08) : vec3(0.25, 0.42, 0.95);
        colour = body ? vec3(0.08, 0.06, 0.05) : tint * (w > 0.65 ? 0.3 : 1.0);
    }
    else if (kind < 5.5)
    {
        // dragonfly: a long thin body, a round head, four clear wings
        bool body = blob(p, vec2(0.15, 0.0), vec2(0.85, 0.09)) < 1.0;
        bool head = blob(p, vec2(-0.8, 0.0), vec2(0.13, 0.17)) < 1.0;
        wing = !body && !head
            && (blob(vec2(p.x, abs(p.y)), vec2(-0.38, 0.48), vec2(0.13, 0.48)) < 1.0
                || blob(vec2(p.x, abs(p.y)), vec2(-0.08, 0.48), vec2(0.13, 0.48)) < 1.0);
        inside = body || head || wing;
        vec3 tint = seed < 0.5 ? vec3(0.12, 0.35, 0.75) : vec3(0.15, 0.55, 0.3);
        colour = wing ? vec3(0.8, 0.86, 0.9) : tint;
    }
    else if (kind < 6.5)
    {
        inside = blob(p, vec2(0.0), vec2(0.6)) < 1.0;
        colour = vec3(0.05, 0.05, 0.04);
    }
    else
    {
        // firefly: a dark body with a glowing tail
        float tail = blob(p, vec2(0.35, 0.0), vec2(0.5, 0.6));
        inside = tail < 1.0 || blob(p, vec2(-0.4, 0.0), vec2(0.4, 0.4)) < 1.0;
        glow = tail < 1.0;
        colour = glow ? mix(vec3(0.15, 0.13, 0.05), vec3(1.0, 1.0, 0.45) * 1.6, a3Flap) : vec3(0.06, 0.05, 0.04);
    }
    if (!inside)
        discard;
    // wings are seen through: every other pixel
    if (wing && mod(floor(gl_FragCoord.x) + floor(gl_FragCoord.y), 2.0) < 0.5)
        discard;

    vec3 lit = glow ? colour : colour * (sun.ambient.xyz + sun.diffuse.xyz * 0.7);
    float f = clamp((length(a3ViewPos) - fog.start) / max(fog.end - fog.start, 1.0), 0.0, 1.0);
    gl_FragData[0] = vec4(mix(lit, fog.color.xyz, f), 1.0);
}

#endif
