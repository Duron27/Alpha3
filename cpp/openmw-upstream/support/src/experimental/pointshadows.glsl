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
// (a3PassShadowDiffuse, a3PassShadowSpecular, by its index), which shadows them per pixel (a3PointShadowLight) and adds
// them. Lit per pixel, calcPointLighting shadows them itself.

uniform int a3PointShadowCount;
uniform vec4 a3PointShadowLights[A3_PS_MAX];

int a3PointShadowIndex(vec3 viewLightPos)
{
    for (int i = 0; i < A3_PS_MAX; ++i)
    {
        if (i >= a3PointShadowCount)
            break;
        vec3 d = viewLightPos - a3PointShadowLights[i].xyz;
        if (dot(d, d) < 1.0)
            return i;
    }
    return -1;
}

#ifdef A3_PS_VERTEX
varying vec3 a3PassShadowDiffuse[A3_PS_MAX];
varying vec3 a3PassShadowSpecular[A3_PS_MAX];

// at the start of pointLighting, which every lit vertex goes through once
void a3PointShadowClear()
{
    for (int i = 0; i < A3_PS_MAX; ++i)
    {
        a3PassShadowDiffuse[i] = vec3(0.0);
        a3PassShadowSpecular[i] = vec3(0.0);
    }
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
// x: near plane, y: one texel of a face (1 / resolution), z: depth bias, w: 1 / the lights (the atlas' rows)
uniform vec4 a3PointShadowParams;

// 1 lit, 0 in shadow
float a3PointShadow(int index, vec3 viewPos, vec3 viewNormal)
{
    float strength = a3PointShadowFade[index];
    if (strength <= 0.0)
        return 1.0;
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
    // four taps half a texel apart, each filtered (2x2 comparisons): a soft edge three texels wide
    float lit = shadow2D(a3PointShadowMap, vec3((tile + uv + vec2(-0.5, -0.5) * texel) * scale, depth)).r
        + shadow2D(a3PointShadowMap, vec3((tile + uv + vec2(0.5, -0.5) * texel) * scale, depth)).r
        + shadow2D(a3PointShadowMap, vec3((tile + uv + vec2(-0.5, 0.5) * texel) * scale, depth)).r
        + shadow2D(a3PointShadowMap, vec3((tile + uv + vec2(0.5, 0.5) * texel) * scale, depth)).r;
    return mix(1.0, lit * 0.25, strength);
}

// lit per vertex only: a shader lit per pixel has a vertex shader without these
#if !PER_PIXEL_LIGHTING
varying vec3 a3PassShadowDiffuse[A3_PS_MAX];
varying vec3 a3PassShadowSpecular[A3_PS_MAX];

// The shadowed lights the vertex shader lit, shadowed here
void a3PointShadowLight(vec3 viewPos, vec3 viewNormal, out vec3 diffuseLight, out vec3 specularLight)
{
    diffuseLight = vec3(0.0);
    specularLight = vec3(0.0);
    for (int i = 0; i < A3_PS_MAX; ++i)
    {
        if (i >= a3PointShadowCount)
            break;
        vec3 d = a3PassShadowDiffuse[i];
        vec3 s = a3PassShadowSpecular[i];
        if (dot(d + s, vec3(1.0)) <= 0.0)
            continue;
        float shadow = a3PointShadow(i, viewPos, viewNormal);
#ifdef A3_PS_DEBUG
        // [Shadows] point light shadow debug: where the light is shadowed, red
        diffuseLight += vec3(1.0, 0.0, 0.0) * (1.0 - shadow);
#endif
        diffuseLight += d * shadow;
        specularLight += s * shadow;
    }
}
#endif
#endif
