// Point light shadows ([Shadows] point light shadows, src/pointshadows.cpp): the shadow maps of the point lights
// closest to the camera, and their part in OpenMW's lighting. Added by src/experimentalshaders.cpp in front of
// calcPointLighting (lib/light/util.glsl) in the merged object and terrain shaders, with A3_PS_MAX (the most lights) and
// A3_PS_VERTEX or A3_PS_FRAGMENT defined.
//
// Each light has six depth maps, the faces of a cube around it (90 degree views along +X, -X, +Y, -Y, +Z, -Z), tiles
// of one depth texture: a row per light, a column per face. The lights come per camera: xyz the position in its view
// space, w the far plane of the maps (the light's radius). A light of the per-object lists is one of them when its
// position is the same (the same numbers, from the same world position and view matrix).
//
// Lit per vertex, a shadowed light's diffuse and specular light go to the fragment shader on their own
// (a3PassShadowLight<index>, a3PassShadowSpecular), which shadows them per pixel (a3PointShadowLight) and adds them.
// Lit per pixel, calcPointLighting shadows them itself.
//
// A3_PS_ON is the number of shadowed lights in the object's light list (1 to A3_PS_MAX), set with it by the CPU
// (pointShadowListState), and a3PointShadowSlots which maps they have: the shaders carry only those.

#define A3_PS_COUNT A3_PS_ON
uniform vec4 a3PointShadowLights[A3_PS_MAX];
uniform ivec4 a3PointShadowSlots;

// which of the object's shadowed lights (0 to A3_PS_COUNT - 1) this light is, or -1
int a3PointShadowIndex(vec3 viewLightPos)
{
    for (int i = 0; i < A3_PS_COUNT; ++i)
    {
        vec3 d = viewLightPos - a3PointShadowLights[a3PointShadowSlots[i]].xyz;
        if (dot(d, d) < 1.0)
            return i;
    }
    return -1;
}

// Lit per vertex, each shadowed light goes to the fragment shader in one varying of its own (no arrays: a varying array
// written at a dynamic index is spilled to memory on some GPUs, costing many milliseconds): its colour times the
// attenuation (rgb) and lambert (a), and one more holds each light's specular intensity times the attenuation. The
// specular colour is the diffuse one (as OpenMW makes a light's; negative lights cast no shadows). Medium precision:
// they are light, and the GPU carries half as much.
#if defined(A3_PS_VERTEX) || !PER_PIXEL_LIGHTING
varying mediump vec4 a3PassShadowLight0;
#if A3_PS_COUNT > 1
varying mediump vec4 a3PassShadowLight1;
#endif
#if A3_PS_COUNT > 2
varying mediump vec4 a3PassShadowLight2;
#endif
#if A3_PS_COUNT > 3
varying mediump vec4 a3PassShadowLight3;
#endif
#if A3_PS_COUNT > 1
varying mediump vec4 a3PassShadowSpecular;
#else
varying mediump float a3PassShadowSpecular;
#endif
#endif

#ifdef A3_PS_VERTEX
// at the start of pointLighting, which every lit vertex goes through once
void a3PointShadowClear()
{
    a3PassShadowLight0 = vec4(0.0);
#if A3_PS_COUNT > 1
    a3PassShadowLight1 = vec4(0.0);
#endif
#if A3_PS_COUNT > 2
    a3PassShadowLight2 = vec4(0.0);
#endif
#if A3_PS_COUNT > 3
    a3PassShadowLight3 = vec4(0.0);
#endif
#if A3_PS_COUNT > 1
    a3PassShadowSpecular = vec4(0.0);
#else
    a3PassShadowSpecular = 0.0;
#endif
}

void a3PointShadowPassDiffuse(int index, vec3 colour, float lambertTerm)
{
    vec4 value = vec4(colour, lambertTerm);
    if (index == 0) a3PassShadowLight0 = value;
#if A3_PS_COUNT > 1
    if (index == 1) a3PassShadowLight1 = value;
#endif
#if A3_PS_COUNT > 2
    if (index == 2) a3PassShadowLight2 = value;
#endif
#if A3_PS_COUNT > 3
    if (index == 3) a3PassShadowLight3 = value;
#endif
}

void a3PointShadowPassSpecular(int index, float intensity)
{
#if A3_PS_COUNT > 1
    if (index == 0) a3PassShadowSpecular.x = intensity;
    else if (index == 1) a3PassShadowSpecular.y = intensity;
    else if (index == 2) a3PassShadowSpecular.z = intensity;
    else a3PassShadowSpecular.w = intensity;
#else
    a3PassShadowSpecular = intensity;
#endif
}
#endif

#ifdef A3_PS_FRAGMENT
uniform sampler2DShadow a3PointShadowMap;
uniform float a3PointShadowFade[A3_PS_MAX];
// where each face was drawn from (this camera's view space): a few faces are drawn a frame, and a light that moves (a
// torch in a hand) was elsewhere when the others were
uniform vec3 a3PointShadowFaceOrigins[A3_PS_MAX * 6];
// the rotation from this camera's view space to the world's, which the cube faces follow
uniform mat3 a3PointShadowViewToWorld;
// a light whose light here (its colour times the attenuation, summed) is no more than this casts no shadow here: too
// little of the light there is ([Shadows] point light shadow min light, min contrast)
uniform float a3PointShadowMinLight;
// x: near plane, y: one texel of a face (1 / resolution), z: depth bias, w: 1 / the lights (the atlas' rows)
uniform vec4 a3PointShadowParams;
// a3PointShadowOcclusion: declared before main() (src/experimentalshaders.cpp), which the library comes after

// How much of a light's shadow shows where its light here (its colour times the attenuation, summed) is this: none at
// the threshold ([Shadows] point light shadow min light, min contrast), all of it at twice that; a hard edge where the
// light's reach ends otherwise
float a3PointShadowEdge(float lightHere)
{
    float edge = clamp((lightHere - a3PointShadowMinLight) / max(a3PointShadowMinLight, 0.01), 0.0, 1.0);
    return edge * edge * (3.0 - 2.0 * edge);
}

// 1 lit, 0 in shadow
// index: the object's shadowed light (0 to A3_PS_COUNT - 1)
float a3PointShadow(int local, vec3 viewPos, vec3 viewNormal)
{
    int index = a3PointShadowSlots[local];
    float strength = a3PointShadowFade[index];
    if (strength <= 0.0)
        return 1.0;
#if A3_PS_TAPS <= 0
    // testing ([Shadows] point light shadow test level)
    return 1.0;
#endif
    float texel = a3PointShadowParams.y;
    // the face: by the direction from the light where it is now
    vec3 v = a3PointShadowViewToWorld * (viewPos - a3PointShadowLights[index].xyz);
    vec3 a = abs(v);
    int face = a.x >= a.y && a.x >= a.z ? (v.x > 0.0 ? 0 : 1) : (a.y >= a.z ? (v.y > 0.0 ? 2 : 3) : (v.z > 0.0 ? 4 : 5));
    // the point on it, from where it was drawn (as the cameras' lookAt: x right, y up); moved out along the normal by
    // two of the map's texels where it is (a texel there is 2 * distance / resolution wide), more where the light
    // grazes the surface (a wall the lantern hangs on): no acne
    vec3 toFragment = viewPos - a3PointShadowFaceOrigins[index * 6 + face];
    float distance = length(toFragment);
    float grazing = 1.0 - abs(dot(viewNormal, toFragment / max(distance, 1e-4)));
    toFragment += viewNormal * (distance * texel * (4.0 + 4.0 * grazing));
    v = a3PointShadowViewToWorld * toFragment;
    float major;
    vec2 xy;
    if (face == 0) { major = v.x; xy = vec2(-v.y, v.z); }
    else if (face == 1) { major = -v.x; xy = vec2(v.y, v.z); }
    else if (face == 2) { major = v.y; xy = vec2(v.x, v.z); }
    else if (face == 3) { major = -v.y; xy = vec2(-v.x, v.z); }
    else if (face == 4) { major = v.z; xy = vec2(-v.x, v.y); }
    else { major = -v.z; xy = vec2(v.x, v.y); }
    // and nearer the light by a little more than a texel, in depth
    major = max(major - (1.0 + major * texel * 2.0), a3PointShadowParams.x);
    vec2 uv = clamp(xy / major * 0.5 + 0.5, vec2(texel * 1.5), vec2(1.0 - texel * 1.5));
    float near = a3PointShadowParams.x;
    float far = a3PointShadowLights[index].w;
    float depth = ((far + near) / (far - near) - 2.0 * far * near / ((far - near) * major)) * 0.5 + 0.5
        - a3PointShadowParams.z;
    // the face's tile; uv keeps 1.5 texels from its edges, so the taps below stay inside it
    vec2 tile = vec2(float(face), float(index));
    vec2 scale = vec2(1.0 / 6.0, a3PointShadowParams.w);
#if A3_PS_TAPS >= 4
    // four taps half a texel apart, each filtered (2x2 comparisons): a soft edge three texels wide
    float lit = (shadow2D(a3PointShadowMap, vec3((tile + uv + vec2(-0.5, -0.5) * texel) * scale, depth)).r
        + shadow2D(a3PointShadowMap, vec3((tile + uv + vec2(0.5, -0.5) * texel) * scale, depth)).r
        + shadow2D(a3PointShadowMap, vec3((tile + uv + vec2(-0.5, 0.5) * texel) * scale, depth)).r
        + shadow2D(a3PointShadowMap, vec3((tile + uv + vec2(0.5, 0.5) * texel) * scale, depth)).r) * 0.25;
#else
    // one tap, filtered (2x2 comparisons): a soft edge a texel wide, a quarter of the texture reads
    float lit = shadow2D(a3PointShadowMap, vec3((tile + uv) * scale, depth)).r;
#endif
    return mix(1.0, lit, strength);
}

// lit per vertex only: a shader lit per pixel has a vertex shader without these
#if !PER_PIXEL_LIGHTING
// The shadowed lights the vertex shader lit, shadowed here
void a3PointShadowOne(int index, mediump vec4 passed, mediump float specularTerm, vec3 viewPos, vec3 viewNormal,
    inout vec3 diffuseLight, inout vec3 specularLight)
{
    mediump vec3 d = passed.rgb * passed.a;
    mediump vec3 s = passed.rgb * specularTerm;
    // too little of its light here to see its shadow ([Shadows] point light shadow min light, min contrast), and
    // fading in above that
    float edge = a3PointShadowEdge(dot(passed.rgb, vec3(1.0)));
    if (edge <= 0.0)
    {
        diffuseLight += d;
        specularLight += s;
        return;
    }
    float shadow = mix(1.0, a3PointShadow(index, viewPos, viewNormal), edge);
    a3PointShadowOcclusion = max(a3PointShadowOcclusion, 1.0 - shadow);
#ifdef A3_PS_DEBUG
    // [Shadows] point light shadow debug: where the light is shadowed, red
    diffuseLight += vec3(1.0, 0.0, 0.0) * (1.0 - shadow);
#endif
    diffuseLight += d * shadow;
    specularLight += s * shadow;
}

void a3PointShadowLight(vec3 viewPos, vec3 viewNormal, out vec3 diffuseLight, out vec3 specularLight)
{
    diffuseLight = vec3(0.0);
    specularLight = vec3(0.0);
#if A3_PS_TAPS < 0
    // testing: the fragment shader takes nothing of it (the shadowed lights are lost)
    return;
#endif
#if A3_PS_COUNT > 1
    a3PointShadowOne(0, a3PassShadowLight0, a3PassShadowSpecular.x, viewPos, viewNormal, diffuseLight, specularLight);
    a3PointShadowOne(1, a3PassShadowLight1, a3PassShadowSpecular.y, viewPos, viewNormal, diffuseLight, specularLight);
#else
    a3PointShadowOne(0, a3PassShadowLight0, a3PassShadowSpecular, viewPos, viewNormal, diffuseLight, specularLight);
#endif
#if A3_PS_COUNT > 2
    a3PointShadowOne(2, a3PassShadowLight2, a3PassShadowSpecular.z, viewPos, viewNormal, diffuseLight, specularLight);
#endif
#if A3_PS_COUNT > 3
    a3PointShadowOne(3, a3PassShadowLight3, a3PassShadowSpecular.w, viewPos, viewNormal, diffuseLight, specularLight);
#endif
}
#endif
#endif
