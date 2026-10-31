// Terrain of the experimental graphics, added to OpenMW's terrain fragment shader by src/experimental.cpp:
//  - triplanar ([Experimental] terrain triplanar): on steep slopes the texture is also projected from the sides, so
//    cliffs show rock instead of a texture smeared down them. a3TerrainPos is the vertex position in the chunk's space
//    (x, y across, z up), passed from terrain.vert; the texture repeats every A3_TERRAIN_REPEAT units (512 in Morrowind).
//  - height blending ([Experimental] terrain height blending): where two textures meet, the one whose texels stand
//    higher (stones over the dirt between them) shows first, in a crisp edge instead of a long smear.

// The side projections' weights for the normal n (model space), the top's in z
vec3 a3TriplanarWeights(vec3 n)
{
    vec3 w = pow(abs(n), vec3(4.0));
    return w / max(w.x + w.y + w.z, 0.0001);
}

// The colour, with the sides blended in where the slope is steep; top: the texture sampled as usual
vec4 a3TriplanarColour(sampler2D map, vec4 top, vec3 pos, vec3 n)
{
    vec3 w = a3TriplanarWeights(normalize(n));
    if (w.z > 0.98)
        return top;
    vec2 uvX = vec2(pos.y * sign(n.x), -pos.z) / A3_TERRAIN_REPEAT;
    vec2 uvY = vec2(-pos.x * sign(n.y), -pos.z) / A3_TERRAIN_REPEAT;
    return top * w.z + texture2D(map, uvX) * w.x + texture2D(map, uvY) * w.y;
}

// The model space normal from the normal map: the top's tangent space normal (tangent along x, bitangent along y) and the
// sides', each laid on the surface normal (whiteout blending)
vec3 a3TriplanarNormal(sampler2D normalMap, vec3 topNormal, vec3 pos, vec3 n)
{
    n = normalize(n);
    vec3 w = a3TriplanarWeights(n);
    vec3 top = normalize(vec3(topNormal.xy + n.xy, abs(topNormal.z) * n.z));
    if (w.z > 0.98)
        return top;
    vec2 uvX = vec2(pos.y * sign(n.x), -pos.z) / A3_TERRAIN_REPEAT;
    vec2 uvY = vec2(-pos.x * sign(n.y), -pos.z) / A3_TERRAIN_REPEAT;
    vec3 tX = texture2D(normalMap, uvX).xyz * 2.0 - 1.0;
    vec3 tY = texture2D(normalMap, uvY).xyz * 2.0 - 1.0;
    tX.x *= sign(n.x);
    tY.x *= -sign(n.y);
    vec3 nX = vec3(tX.xy + n.zy, abs(tX.z) * n.x).zyx;
    vec3 nY = vec3(tY.xy + n.xz, abs(tY.z) * n.y).xzy;
    return normalize(top * w.z + nX * w.x + nY * w.y);
}

// How high the texture stands at uv, from its own brightness against its blurred self (0.5 level): stones are lighter
// than the gaps between them in nearly every terrain texture
float a3TerrainHeight(sampler2D diffuseMap, vec2 uv)
{
    float fine = dot(texture2D(diffuseMap, uv).rgb, vec3(0.333));
    float broad = dot(texture2D(diffuseMap, uv, 4.0).rgb, vec3(0.333));
    return clamp(0.5 + (fine - broad) * 2.0, 0.0, 1.0);
}

// The layer's blend weight, pushed by its height and made crisper. The layers' weights add up to one, and the curve is
// symmetric about one half, so two layers meeting still add up to one but for the small height term.
float a3HeightBlend(float alpha, float height, float sharpness)
{
    float a = clamp(alpha + (height - 0.5) * 2.4 * alpha * (1.0 - alpha), 0.0, 1.0);
    return clamp((a - 0.5) * sharpness + 0.5, 0.0, 1.0);
}
