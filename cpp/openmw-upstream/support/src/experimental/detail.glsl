// Detail up close of the experimental graphics ([Experimental] detail up close): where a texture is magnified (one texel
// over several pixels, a wall at arm's length) its own fine detail is laid over it again, smaller and turned, so the
// surface does not go soft. Works on any texture, procedural material or not. Added to OpenMW's object and terrain
// fragment shaders by src/experimental.cpp.

// colour: the texture's colour at uv, made more detailed; strength 1 is the full effect, range the distance (game units)
// it fades out by
void a3Detail(sampler2D diffuseMap, vec2 uv, vec3 viewPos, float strength, float range, inout vec3 colour)
{
    float fade = strength * (1.0 - smoothstep(range * 0.5, range, length(viewPos)));
    if (fade <= 0.0)
        return;
    // only while the detail layer itself is larger than a pixel: 4.37 repeats of a texture of some 256 texels
    float fp = max(length(dFdx(uv)), length(dFdy(uv)));
    fade *= clamp(1.5 - fp * 4.37 * 256.0, 0.0, 1.0);
    if (fade <= 0.0)
        return;
    // the texture 4.37 times smaller and turned by 37 degrees, so it does not line up with itself; high-passed against
    // its own mip level three steps down, as a ratio, so it only brightens and darkens
    vec2 q = mat2(0.8, -0.6, 0.6, 0.8) * uv * 4.37;
    float fine = dot(texture2D(diffuseMap, q).rgb, vec3(0.333));
    float broad = dot(texture2D(diffuseMap, q, 3.0).rgb, vec3(0.333));
    float ratio = clamp((fine - broad) / max(broad, 0.05), -0.5, 0.5);
    colour *= 1.0 + ratio * 0.6 * fade;
}
