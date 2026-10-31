// Trees swaying in the wind ([Experimental] tree sway): leaves, branches, fronds and hanging moss move, and the trunks
// bend a little ([Experimental] tree trunk sway). Added to OpenMW's objects.vert (src/experimentalshaders.cpp); the sway
// is only compiled in where A3_TREE_SWAY is defined, which src/experimentalstate.cpp switches on for drawables with a
// leafy (1) or a trunk's (2) texture (OSG's state defines), so everything else keeps the shader it had.
//
// A slow swell rolls across the land, the same for the leaves and the trunk of a tree so they lean together; the leaves
// have a quicker flutter on top. In world space, stronger in storms (OpenMW's windSpeed). Works on the view space
// position, before the projection.

#ifdef A3_TREE_SWAY

uniform float osg_SimulationTime;
uniform float windSpeed;
#if !A3_HAS_VIEW_INVERSE
uniform mat4 osg_ViewMatrixInverse;
#endif
#if A3_TREE_SWAY == 2
uniform float a3SwayBase; // the world height of the trunk's foot, which stays where it is
#endif

vec4 a3TreeSway(vec4 viewPos)
{
    mat3 viewToWorld = mat3(osg_ViewMatrixInverse);
    vec3 world = (osg_ViewMatrixInverse * viewPos).xyz;
    float time = osg_SimulationTime;
    float wind = (0.4 + 0.6 * clamp(windSpeed, 0.0, 3.0)) * A3_TREE_SWAY_STRENGTH;

    // the swell: the whole tree leaning with the gusts, 0.3 to 1 toward the wind's way
    float swell = sin(time * 0.8 + dot(world.xy, vec2(0.0017, 0.0011))) * 0.65
        + sin(time * 1.9 + dot(world.xy, vec2(0.0023, -0.0019))) * 0.35;
    vec2 lean = vec2(0.8, 0.6) * (swell * 0.7 + 0.3);

#if A3_TREE_SWAY == 2
    // the trunk bends from its foot, more toward the top: 3 units at 600 up, at strength 1
    float up = clamp((world.z - a3SwayBase) / 600.0, 0.0, 1.6);
    vec3 offset = vec3(lean * 3.0 * A3_TRUNK_SWAY * up * sqrt(up), 0.0);
#else
    // the flutter: each leaf on its own, quicker
    float flutter = sin(time * 5.3 + dot(world, vec3(0.11, 0.13, 0.17)));
    float flutter2 = sin(time * 7.1 + dot(world, vec3(-0.07, 0.15, 0.09)));
    vec3 offset = vec3(lean * 5.0, 0.0) + vec3(flutter * 0.9, flutter2 * 0.9, (flutter + flutter2) * 0.4);
#endif
    // world to view: the inverse of the view's rotation is its transpose
    return viewPos + vec4(transpose(viewToWorld) * (offset * wind), 0.0);
}

#endif
