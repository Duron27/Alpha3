// Grass blades of the experimental graphics ([Experimental] grass), drawn by src/grass.cpp over the terrain where its
// grassy textures are painted. One source for both stages: A3_VERTEX picks the vertex shader. Written in the GLSL 1.20
// of OpenMW's own shaders, translated for OpenGL ES like them.
//
// Each blade is five vertices (root pair, middle pair, tip) that all carry the blade's root in gl_Vertex (the terrain
// chunk's space: x, y across, z up, as the world but moved); the shader builds the blade from its parameters:
//   gl_MultiTexCoord0: x side (-1, 0 or 1), y how far up the blade (0 root, 1 tip), z facing angle, w height
//   gl_MultiTexCoord1: x lean angle, y lean, z tone (0..1; 2 more for a seed stalk; 4, 8 or 12 more for a clump that
//                      is darker, lighter or yellow), w width at the root
// They sway in the wind (OpenMW's windSpeed, faster in storms) and part around the player (playerPos), and shrink into
// the ground toward A3_GRASS_DISTANCE, where they end. Settings of grass.cpp as defines:
//   A3_GRASS_STORM      ten times how hard they lean with a storm (blizzards, ash and blight storms: windSpeed 0.8 and more)
//   A3_GRASS_VARIETY    1: seed stalks and drier patches
//   A3_GRASS_AA         1: no blade thinner than a pixel (A3_SCREEN_HEIGHT pixels high), widened ones faded to the root

varying vec3 a3Colour;
varying float a3Coverage;
varying vec3 a3ViewPos;
varying vec3 a3ViewNormal;
varying float a3Up;

#ifdef A3_VERTEX

uniform float osg_SimulationTime;
uniform mat4 osg_ViewMatrixInverse;
// OpenMW's projection: with reverse-Z (clip control) the camera keeps the ordinary one in gl_ProjectionMatrix and the
// reversed one is only here, so projecting by gl_ProjectionMatrix put the blades' depth backwards (seen through walls)
uniform mat4 projectionMatrix;
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
#if A3_GRASS_STORM > 0
    // A storm lays it down the way it blows (the engine's storms all blow toward +y) in gusts that roll across the
    // field one after another, the blades whipping as each one passes
    float storm = smoothstep(0.6, 0.85, windSpeed) * float(A3_GRASS_STORM) / 10.0;
    if (storm > 0.0)
    {
        float front = world.y * 0.0035 + sin(world.x * 0.0021) * 1.7 - osg_SimulationTime * 2.6;
        float wave = 0.5 + 0.5 * sin(front);
        wave *= wave * wave;
        float whip = sin(osg_SimulationTime * 11.0 + shape.z * 7.0 + world.y * 0.05) * (0.15 + 0.35 * wave);
        sway = mix(sway, vec2(0.0), min(storm, 1.0) * 0.6)
            + vec2(0.0, 1.0) * storm * (0.7 + 1.6 * wave) + vec2(-facing.y, facing.x) * whip * storm;
    }
#endif

    // parted around the player's feet
    vec2 away = world.xy - playerPos.xy;
    float d = length(away);
    vec2 stomp = vec2(0.0);
    if (d > 0.01 && d < 70.0 && abs(world.z - playerPos.z) < 80.0)
        stomp = away / d * (1.0 - d / 70.0) * 1.4;

    // bent more toward the tip; a bent blade is lower
    vec2 bend = (lean + sway + stomp) * t * t;
#if A3_GRASS_AA
    // no thinner than a pixel there (a blade narrower than that only sparkles), and the wider it had to be made the
    // more it takes the colour of its root, the ground's, so the field keeps its look
    float pixel = 2.0 * dist / (abs(projectionMatrix[1][1]) * float(A3_SCREEN_HEIGHT));
    float rootWidth = max(extra.w, pixel * 1.2);
    a3Coverage = clamp(extra.w / rootWidth, 0.2, 1.0);
    float width = rootWidth * (1.0 - t * 0.85);
#else
    a3Coverage = 1.0;
    float width = extra.w * (1.0 - t * 0.85) * max(1.0, dist / 900.0); // wider far off, so blades do not flicker
#endif
    vec3 pos = root + vec3(facing * shape.x * width + bend * height, height * t * inversesqrt(1.0 + dot(bend, bend)));

    vec4 viewPos = gl_ModelViewMatrix * vec4(pos, 1.0);
    gl_Position = projectionMatrix * viewPos;
    gl_ClipVertex = viewPos;
    a3ViewPos = viewPos.xyz;
    // lit mostly as the ground under it, tipped toward the blade's side and its bend
    a3ViewNormal = gl_NormalMatrix * normalize(vec3(vec2(-facing.y, facing.x) * shape.x * 0.35 + bend * 0.5, 1.0));
    a3Up = t;
    float shade = floor(extra.z / 4.0);
    float seed = step(1.5, extra.z - 4.0 * shade);
    float tone = extra.z - 4.0 * shade - 2.0 * seed;
    a3Colour = mix(a3GrassRoot, a3GrassTip, t * t) * (0.8 + 0.4 * tone)
        + vec3(0.04, 0.03, -0.02) * (tone - 0.5) * t; // some blades a little drier
#if A3_GRASS_VARIETY
    // drier patches across the field, and the straw coloured heads of the seed stalks
    float patches = sin(world.x * 0.0013 + sin(world.y * 0.0021) * 2.0) + sin(world.y * 0.0017 - world.x * 0.0007);
    float dry = smoothstep(0.6, 1.5, patches);
    a3Colour = mix(a3Colour, a3Colour * vec3(1.2, 1.05, 0.55) + vec3(0.07, 0.05, 0.0), dry * 0.75);
    a3Colour = mix(a3Colour, vec3(0.63, 0.56, 0.37) * (0.8 + 0.4 * tone), seed * smoothstep(0.65, 0.95, t));
    // clumps in shades of their colour, darker and deeper or lighter and warmer, and a rare yellow bushel
    if (shade > 2.5)
        a3Colour = mix(a3Colour, vec3(0.80, 0.68, 0.22) * (0.75 + 0.5 * tone), 0.35 + 0.6 * t);
    else if (shade > 1.5)
        a3Colour = a3Colour * vec3(1.22, 1.28, 1.05) + vec3(0.03, 0.04, 0.0) * t;
    else if (shade > 0.5)
        a3Colour *= vec3(0.62, 0.70, 0.66);
#endif
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
uniform vec3 a3GrassRoot;

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
    // a blade drawn wider than it is (antialiasing) is mostly the ground showing between blades
    colour = mix(a3GrassRoot * (sun.ambient.xyz + sun.diffuse.xyz * 0.6) * 0.55, colour, a3Coverage);

    float f = clamp((length(a3ViewPos) - fog.start) / max(fog.end - fog.start, 1.0), 0.0, 1.0);
    gl_FragData[0] = vec4(mix(colour, fog.color.xyz, f), 1.0);
}

#endif
