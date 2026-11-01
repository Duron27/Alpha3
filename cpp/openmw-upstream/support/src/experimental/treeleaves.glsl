// Fuller leaves of the experimental graphics (F2 menu, a3_experimental: tree fuller leaves), added to OpenMW's objects.frag by
// src/experimentalshaders.cpp, compiled in only for the leaves tree sway moves (A3_TREE_SWAY 1):
//  - the leaf cards' soft alpha margins count as leaf, so the canopy is thicker and less see-through
//  - the leaves' colour varies from cluster to cluster (by where they are in the world), some yellower, some darker
//  - the sun shines through them when it is behind (translucency), and their shaded side is not as dark
// The world position comes from the view position (osg_ViewMatrixInverse and the sun, declared by fog.glsl).

uniform float a3LeafOn; // from the F2 menu while the game runs: 0 the leaves as they were

float a3LeafHash(vec3 p)
{
    p = fract(p * 0.3183099 + 0.1);
    p *= 17.0;
    return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
}

float a3LeafNoise(vec3 p)
{
    vec3 i = floor(p);
    vec3 f = fract(p);
    f = f * f * (3.0 - 2.0 * f);
    return mix(mix(mix(a3LeafHash(i), a3LeafHash(i + vec3(1.0, 0.0, 0.0)), f.x),
                   mix(a3LeafHash(i + vec3(0.0, 1.0, 0.0)), a3LeafHash(i + vec3(1.0, 1.0, 0.0)), f.x), f.y),
               mix(mix(a3LeafHash(i + vec3(0.0, 0.0, 1.0)), a3LeafHash(i + vec3(1.0, 0.0, 1.0)), f.x),
                   mix(a3LeafHash(i + vec3(0.0, 1.0, 1.0)), a3LeafHash(i + vec3(1.0, 1.0, 1.0)), f.x), f.y), f.z);
}

// the alpha with the soft margins grown into leaf
float a3LeafAlpha(float alpha)
{
    return mix(alpha, clamp(alpha * 1.7 - 0.05, 0.0, 1.0), a3LeafOn);
}

void a3LeafColour(inout vec3 colour, vec3 viewPos)
{
    vec3 world = (osg_ViewMatrixInverse * vec4(viewPos, 1.0)).xyz;
    float cluster = a3LeafNoise(world / 180.0);
    float fine = a3LeafNoise(world / 41.0);
    // darker and bluer, or lighter and yellower
    vec3 tint = mix(vec3(0.82, 0.9, 0.86), vec3(1.12, 1.08, 0.86), cluster);
    tint *= 0.92 + 0.16 * fine;
    colour *= mix(vec3(1.0), tint, A3_LEAF_VARIATION * a3LeafOn);
}

void a3LeafLight(inout vec3 colour, vec3 albedo, vec3 viewPos, vec3 viewNormal)
{
    vec3 toEye = normalize(-viewPos);
    vec3 toSun = normalize(sun.position.xyz);
    // the sun behind the leaves, seen through them
    float behind = pow(max(dot(-toEye, toSun), 0.0), 3.0);
    // either side of the card lets the light through
    float through = behind * (0.5 + 0.5 * abs(dot(viewNormal, toSun))) * a3LeafOn;
    colour += albedo * sun.diffuse.xyz * through * 0.75 * A3_LEAF_TRANSLUCENCY;
    // the shaded side lifted a little: light scattered inside the canopy
    colour += albedo * sun.ambient.xyz * 0.18 * A3_LEAF_TRANSLUCENCY * a3LeafOn;
}
