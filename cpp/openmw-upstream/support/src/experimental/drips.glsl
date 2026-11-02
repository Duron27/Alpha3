// Rain dripping off things (wet ground, src/wetground.cpp): drops falling from the edges of roofs, rocks and tree crowns
// to the ground, splashing there, while it rains and a while after. One source for both stages (A3_VERTEX).
//
// The ground around the player is cut into cells (a grid fixed in the world, A3_DRIP_SPACING units, A3_DRIP_CELLS a
// side); each cell's quad is one drop at a time. Its vertex shader looks at the cell in the map of how high things
// reach (a3RainMap, wetground.glsl) and the ground's height (a3GroundMap): a cell where something stands over part of
// it and not the rest is under an edge. There a drop falls from the edge's height, now and then, to a splash.
//   gl_Vertex          xy the cell (0..A3_DRIP_CELLS), z its seed
//   gl_MultiTexCoord0  xy the corner (-1..1)

varying vec2 a3Corner;
varying float a3Kind; // 0 a drop, 1 a splash
varying float a3Fade; // fading out far off
varying float a3Progress; // a splash's, 0..1
varying vec3 a3ViewPos;

#ifdef A3_VERTEX

uniform mat4 projectionMatrix;
uniform float osg_SimulationTime;
uniform sampler2D a3RainMap;
uniform vec4 a3RainArea;
uniform vec2 a3RainHeight;
uniform sampler2D a3GroundMap;
uniform vec4 a3GroundArea; // corner, size, the height its values are from
uniform vec2 a3DripOrigin; // the first cell's corner in the world
uniform float a3Rain;
uniform float a3Wetness;

float a3DripHash(vec2 p)
{
    return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453);
}

// how high what stands here reaches, or very low
float a3DripTop(vec2 world)
{
    vec2 uv = (world - a3RainArea.xy) / a3RainArea.z;
    float top = texture2D(a3RainMap, uv).r;
    return top > 0.002 ? a3RainHeight.x + a3RainHeight.y * top : -100000.0;
}

void hide()
{
    gl_Position = vec4(2.0, 2.0, 2.0, 1.0);
    a3Corner = vec2(0.0);
    a3Kind = 0.0;
    a3Fade = 0.0;
    a3Progress = 0.0;
    a3ViewPos = vec3(0.0);
}

void main()
{
    vec2 cell = gl_Vertex.xy;
    float seed = gl_Vertex.z;
    vec2 corner = gl_MultiTexCoord0.xy;
    float dripping = clamp(a3Rain * 1.5 + a3Wetness * 0.3, 0.0, 1.0);
    if (a3RainArea.w < 0.5 || dripping < 0.02)
    {
        hide();
        return;
    }
    vec2 base = a3DripOrigin + cell * A3_DRIP_SPACING;
    vec2 groundUV = (base + 0.5 * A3_DRIP_SPACING - a3GroundArea.xy) / a3GroundArea.z;
    float groundValue = texture2D(a3GroundMap, groundUV).r;
    if (groundValue < -9000.0 || groundUV.x < 0.0 || groundUV.y < 0.0 || groundUV.x > 1.0 || groundUV.y > 1.0)
    {
        hide();
        return;
    }
    float ground = a3GroundArea.w + groundValue;

    // nine places in the cell: which have something over them, and how high the highest reaches
    float period = 0.7 + 0.9 * a3DripHash(cell + seed);
    float t = osg_SimulationTime / period + a3DripHash(cell * 1.7 + 3.1);
    float cycle = floor(t);
    float phase = fract(t) * period;
    float pick = floor(a3DripHash(cell + cycle * 0.37) * 9.0);
    float edge = -100000.0;
    vec2 from = base;
    bool open = false;
    for (int i = 0; i < 9; ++i)
    {
        float fi = float(i);
        vec2 at = base + (vec2(fi - 3.0 * floor(fi / 3.0), floor(fi / 3.0)) + 0.5) * (A3_DRIP_SPACING / 3.0);
        float top = a3DripTop(at);
        if (top > ground + 50.0)
            edge = max(edge, top);
        // the drop falls at an open place: the one picked if it is open, else the first
        else if (!open || fi == pick)
        {
            from = at;
            open = true;
        }
    }
    if (edge < -90000.0 || !open || a3DripHash(cell + cycle * 1.3) > dripping * 0.85)
    {
        hide();
        return;
    }
    from += (vec2(a3DripHash(cell + cycle), a3DripHash(cell.yx + cycle)) - 0.5) * 4.0;

    // falling (700 units a second squared), then a splash
    float fall = edge - ground;
    float fallTime = sqrt(2.0 * fall / 700.0);
    vec3 world;
    float width, height;
    if (phase < fallTime)
    {
        float speed = 700.0 * phase;
        world = vec3(from, edge - 0.5 * 700.0 * phase * phase);
        width = 0.45;
        height = clamp(speed * 0.025, 2.0, 16.0); // streaked by its speed
        a3Kind = 0.0;
        a3Progress = 0.0;
    }
    else if (phase < fallTime + 0.2)
    {
        float s = (phase - fallTime) / 0.2;
        world = vec3(from, ground + 0.5);
        width = 3.0 + 7.0 * s;
        height = width * 0.6;
        a3Kind = 1.0;
        a3Progress = s;
    }
    else
    {
        hide();
        return;
    }
    vec3 centre = (gl_ModelViewMatrix * vec4(world, 1.0)).xyz;
    if (length(centre) > A3_DRIP_DISTANCE)
    {
        hide();
        return;
    }
    vec3 up = normalize((gl_ModelViewMatrix * vec4(0.0, 0.0, 1.0, 0.0)).xyz);
    vec3 right = cross(up, normalize(-centre));
    right = length(right) > 0.001 ? normalize(right) : vec3(1.0, 0.0, 0.0);
    vec3 pos = centre + right * corner.x * width * 0.5 + up * (corner.y * 0.5 + 0.5) * height;
    gl_Position = projectionMatrix * vec4(pos, 1.0);
    gl_ClipVertex = vec4(pos, 1.0);
    a3Corner = corner;
    a3ViewPos = pos;
    a3Fade = 1.0 - smoothstep(A3_DRIP_DISTANCE * 0.6, A3_DRIP_DISTANCE, length(centre));
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

void main()
{
    vec2 p = a3Corner;
    // what a drop shows is the sky's light through it
    vec3 light = mix(fog.color.rgb, vec3(1.0), 0.35);
    float alpha;
    if (a3Kind < 0.5)
    {
        float across = 1.0 - abs(p.x);
        alpha = across * across * smoothstep(-1.0, -0.4, p.y) * (1.0 - smoothstep(0.5, 1.0, p.y)) * 0.5;
    }
    else
    {
        // a ring of spray, thrown up and falling back
        float r = length(vec2(p.x, p.y * 1.6 + 0.3));
        alpha = (1.0 - smoothstep(0.0, 0.25, abs(r - 0.35 - 0.5 * a3Progress))) * (1.0 - a3Progress) * 0.45;
    }
    alpha *= a3Fade;
    gl_FragData[0] = vec4(light * alpha, alpha);
    gl_FragData[1] = vec4(0.0);
}

#endif
