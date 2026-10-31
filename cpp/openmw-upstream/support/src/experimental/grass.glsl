// Grass blades of the experimental graphics ([Experimental] grass), drawn by src/grass.cpp over the terrain where its
// grassy textures are painted. One source for both stages: A3_VERTEX picks the vertex shader. Written in the GLSL 1.20
// of OpenMW's own shaders, translated for OpenGL ES like them.
//
// Each blade is five vertices (root pair, middle pair, tip) that all carry the blade's root in gl_Vertex (the terrain
// chunk's space: x, y across, z up, as the world but moved); the shader builds the blade from its parameters:
//   gl_MultiTexCoord0: x side (-1, 0 or 1), y how far up the blade (0 root, 1 tip), z facing angle, w height
//   gl_MultiTexCoord1: x lean angle, y lean, z tone (0..1), w width at the root
// They sway in the wind (OpenMW's windSpeed, faster in storms) and part around the player (playerPos), and shrink into
// the ground toward A3_GRASS_DISTANCE, where they end.

varying vec3 a3Colour;
varying vec3 a3ViewPos;
varying vec3 a3ViewNormal;
varying float a3Up;

#ifdef A3_VERTEX

uniform float osg_SimulationTime;
uniform mat4 osg_ViewMatrixInverse;
uniform float windSpeed;
uniform vec3 playerPos;
uniform vec3 a3GrassRoot; // the colours of the blades at the root and at the tip, from the grass texture
uniform vec3 a3GrassTip;

void main()
{
    vec3 root = gl_Vertex.xyz;
    vec4 shape = gl_MultiTexCoord0;
    vec4 extra = gl_MultiTexCoord1;
    float t = shape.y;

    vec4 viewRoot = gl_ModelViewMatrix * vec4(root, 1.0);
    float dist = length(viewRoot.xyz);
    float fade = 1.0 - smoothstep(A3_GRASS_DISTANCE * 0.65, A3_GRASS_DISTANCE, dist);
    float height = shape.w * fade;

    vec2 facing = vec2(cos(shape.z), sin(shape.z));
    vec2 lean = vec2(cos(extra.x), sin(extra.x)) * extra.y;

    // wind: a slow swell over the field and a quicker flutter, in world space so it rolls across the chunks
    vec3 world = (osg_ViewMatrixInverse * viewRoot).xyz;
    float gust = (0.25 + 0.08 * windSpeed) * A3_GRASS_WIND;
    float time = osg_SimulationTime * (1.0 + 0.06 * windSpeed);
    float swell = sin(time * 1.1 + dot(world.xy, vec2(0.0021, 0.0013)));
    float flutter = sin(time * 3.7 + dot(world.xy, vec2(0.031, 0.027)) + shape.z * 3.0);
    vec2 sway = vec2(0.8, 0.6) * (swell * 0.7 + 0.3) * gust + vec2(-facing.y, facing.x) * flutter * gust * 0.25;

    // parted around the player's feet
    vec2 away = world.xy - playerPos.xy;
    float d = length(away);
    vec2 stomp = vec2(0.0);
    if (d > 0.01 && d < 70.0 && abs(world.z - playerPos.z) < 80.0)
        stomp = away / d * (1.0 - d / 70.0) * 1.4;

    // bent more toward the tip; a bent blade is lower
    vec2 bend = (lean + sway + stomp) * t * t;
    float width = extra.w * (1.0 - t * 0.85) * max(1.0, dist / 900.0); // wider far off, so blades do not flicker
    vec3 pos = root + vec3(facing * shape.x * width + bend * height, height * t * inversesqrt(1.0 + dot(bend, bend)));

    vec4 viewPos = gl_ModelViewMatrix * vec4(pos, 1.0);
    gl_Position = gl_ProjectionMatrix * viewPos;
    gl_ClipVertex = viewPos;
    a3ViewPos = viewPos.xyz;
    // lit mostly as the ground under it, tipped toward the blade's side and its bend
    a3ViewNormal = gl_NormalMatrix * normalize(vec3(vec2(-facing.y, facing.x) * shape.x * 0.35 + bend * 0.5, 1.0));
    a3Up = t;
    float tone = extra.z;
    a3Colour = mix(a3GrassRoot, a3GrassTip, t * t) * (0.8 + 0.4 * tone)
        + vec3(0.04, 0.03, -0.02) * (tone - 0.5) * t; // some blades a little drier
}

#else

struct A3Sun
{
    vec4 position;
    vec4 diffuse;
    vec4 ambient;
    vec4 specular;
};
uniform A3Sun sun; // OpenMW's sun, in view space (SceneUtil::LightManager)

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
    vec3 n = normalize(a3ViewNormal);
    vec3 l = normalize(sun.position.xyz);
    // light through the blades too: the sun from behind still lights them, a little
    float diffuse = max(dot(n, l), 0.0) * 0.75 + 0.25;
    // darker down among the blades
    float shade = mix(0.4, 1.0, a3Up);
    vec3 colour = a3Colour * (sun.ambient.xyz + sun.diffuse.xyz * diffuse) * shade;
    // a sheen at the tips looking toward the sun
    vec3 v = normalize(-a3ViewPos);
    colour += sun.diffuse.xyz * a3Colour * pow(max(dot(-v, l), 0.0), 8.0) * 0.5 * a3Up;

    float f = clamp((length(a3ViewPos) - fog.start) / max(fog.end - fog.start, 1.0), 0.0, 1.0);
    gl_FragData[0] = vec4(mix(colour, fog.color.xyz, f), 1.0);
}

#endif
