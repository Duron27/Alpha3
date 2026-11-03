// The render scale's passes ([Experimental] render scale, src/renderscale.cpp): the scene, drawn smaller than the
// window, made window sized (A3_UPSCALE), then sharpened (A3_SHARPEN), or upscaled straight onto the screen without
// sharpening. One source for both stages: A3_VERTEX picks the vertex shader. Written in the GLSL 1.20 of OpenMW's own
// shaders, translated for OpenGL ES like them.
//   A3_UPSCALE  0 bilinear, 1 Lanczos (2 lobes, 4x4 taps), 2 FSR 1 EASU (edge adaptive, 12 taps)
//   A3_SHARPEN  1 RCAS (FSR 1's sharpening), 2 CAS (AMD's contrast adaptive sharpening)
// EASU, RCAS and CAS follow AMD FidelityFX (MIT licence), written again for the fullscreen triangle.

varying vec2 a3Uv;

#ifdef A3_VERTEX

void main()
{
    gl_Position = vec4(gl_Vertex.xy, 0.0, 1.0);
    a3Uv = gl_Position.xy * 0.5 + 0.5;
}

#else

uniform sampler2D a3Source;
uniform vec2 a3SourceSize; // pixels
uniform float a3Sharpness; // 0 to 1

// the texel at whole pixel coordinates (its centre: exact even with linear filtering)
vec3 texel(vec2 p)
{
    return texture2D(a3Source, (p + 0.5) / a3SourceSize).rgb;
}

#ifdef A3_UPSCALE

#if A3_UPSCALE == 1
float lanczos2(float x)
{
    x = abs(x);
    if (x < 1e-4)
        return 1.0;
    if (x >= 2.0)
        return 0.0;
    float px = 3.14159265 * x;
    return 2.0 * sin(px) * sin(px * 0.5) / (px * px);
}

vec3 upscale()
{
    vec2 pp = a3Uv * a3SourceSize - 0.5;
    vec2 fp = floor(pp);
    vec2 f = pp - fp;
    vec4 wx = vec4(lanczos2(-1.0 - f.x), lanczos2(-f.x), lanczos2(1.0 - f.x), lanczos2(2.0 - f.x));
    vec4 wy = vec4(lanczos2(-1.0 - f.y), lanczos2(-f.y), lanczos2(1.0 - f.y), lanczos2(2.0 - f.y));
    vec3 sum = vec3(0.0);
    float total = 0.0;
    vec3 lo = vec3(1e9);
    vec3 hi = vec3(-1e9);
    for (int y = 0; y < 4; ++y)
    {
        for (int x = 0; x < 4; ++x)
        {
            vec3 c = texel(fp + vec2(float(x - 1), float(y - 1)));
            float w = wx[x] * wy[y];
            sum += c * w;
            total += w;
            if ((x == 1 || x == 2) && (y == 1 || y == 2))
            {
                lo = min(lo, c);
                hi = max(hi, c);
            }
        }
    }
    // no ringing past the nearest four
    return clamp(sum / total, lo, hi);
}

#elif A3_UPSCALE == 2
float luma(vec3 c)
{
    return c.g + 0.5 * (c.r + c.b);
}

// one of the four nearest texels' share of the edge's direction and length: A above, B left, C itself, D right, E below
void easuSet(inout vec2 dir, inout float len, float w, float lA, float lB, float lC, float lD, float lE)
{
    float lenX = max(abs(lD - lC), abs(lC - lB));
    float dirX = lD - lB;
    dir.x += dirX * w;
    lenX = clamp(abs(dirX) / max(lenX, 1e-5), 0.0, 1.0);
    len += lenX * lenX * w;
    float lenY = max(abs(lE - lC), abs(lC - lA));
    float dirY = lE - lA;
    dir.y += dirY * w;
    lenY = clamp(abs(dirY) / max(lenY, 1e-5), 0.0, 1.0);
    len += lenY * lenY * w;
}

void easuTap(inout vec3 sum, inout float total, vec2 off, vec2 dir, vec2 len, float lob, float clp, vec3 c)
{
    vec2 v = vec2(dot(off, dir), dot(off, vec2(-dir.y, dir.x))) * len;
    float d2 = min(dot(v, v), clp);
    // the lobe (windowed, approximately Lanczos) along and across the edge
    float wB = 0.4 * d2 - 1.0;
    float wA = lob * d2 - 1.0;
    wB *= wB;
    wA *= wA;
    wB = 1.5625 * wB - 0.5625;
    float w = wB * wA;
    sum += c * w;
    total += w;
}

vec3 upscale()
{
    vec2 pp = a3Uv * a3SourceSize - 0.5;
    vec2 fp = floor(pp);
    vec2 f = pp - fp;
    //     b c
    //   e F G h
    //   i J K l
    //     n o      (F at fp)
    vec3 b = texel(fp + vec2(0.0, -1.0));
    vec3 c = texel(fp + vec2(1.0, -1.0));
    vec3 e = texel(fp + vec2(-1.0, 0.0));
    vec3 F = texel(fp);
    vec3 G = texel(fp + vec2(1.0, 0.0));
    vec3 h = texel(fp + vec2(2.0, 0.0));
    vec3 i = texel(fp + vec2(-1.0, 1.0));
    vec3 J = texel(fp + vec2(0.0, 1.0));
    vec3 K = texel(fp + vec2(1.0, 1.0));
    vec3 l = texel(fp + vec2(2.0, 1.0));
    vec3 n = texel(fp + vec2(0.0, 2.0));
    vec3 o = texel(fp + vec2(1.0, 2.0));
    float bL = luma(b), cL = luma(c), eL = luma(e), fL = luma(F), gL = luma(G), hL = luma(h);
    float iL = luma(i), jL = luma(J), kL = luma(K), lL = luma(l), nL = luma(n), oL = luma(o);

    vec2 dir = vec2(0.0);
    float len = 0.0;
    easuSet(dir, len, (1.0 - f.x) * (1.0 - f.y), bL, eL, fL, gL, jL);
    easuSet(dir, len, f.x * (1.0 - f.y), cL, fL, gL, hL, kL);
    easuSet(dir, len, (1.0 - f.x) * f.y, fL, iL, jL, kL, nL);
    easuSet(dir, len, f.x * f.y, gL, jL, kL, lL, oL);

    float dirR = dot(dir, dir);
    bool isFlat = dirR < 1.0 / 32768.0;
    dir = isFlat ? vec2(1.0, 0.0) : dir * inversesqrt(dirR);
    len = len * 0.5;
    len *= len;
    float stretch = dot(dir, dir) / max(abs(dir.x), abs(dir.y));
    vec2 len2 = vec2(1.0 + (stretch - 1.0) * len, 1.0 - 0.5 * len);
    float lob = 0.5 + (1.0 / 4.0 - 0.04 - 0.5) * len;
    float clp = 1.0 / lob;

    vec3 sum = vec3(0.0);
    float total = 0.0;
    easuTap(sum, total, vec2(0.0, -1.0) - f, dir, len2, lob, clp, b);
    easuTap(sum, total, vec2(1.0, -1.0) - f, dir, len2, lob, clp, c);
    easuTap(sum, total, vec2(-1.0, 1.0) - f, dir, len2, lob, clp, i);
    easuTap(sum, total, vec2(0.0, 1.0) - f, dir, len2, lob, clp, J);
    easuTap(sum, total, vec2(0.0, 0.0) - f, dir, len2, lob, clp, F);
    easuTap(sum, total, vec2(-1.0, 0.0) - f, dir, len2, lob, clp, e);
    easuTap(sum, total, vec2(1.0, 1.0) - f, dir, len2, lob, clp, K);
    easuTap(sum, total, vec2(2.0, 1.0) - f, dir, len2, lob, clp, l);
    easuTap(sum, total, vec2(2.0, 0.0) - f, dir, len2, lob, clp, h);
    easuTap(sum, total, vec2(1.0, 0.0) - f, dir, len2, lob, clp, G);
    easuTap(sum, total, vec2(1.0, 2.0) - f, dir, len2, lob, clp, o);
    easuTap(sum, total, vec2(0.0, 2.0) - f, dir, len2, lob, clp, n);

    // no ringing past the nearest four
    vec3 lo = min(min(F, G), min(J, K));
    vec3 hi = max(max(F, G), max(J, K));
    return clamp(sum / max(total, 1e-5), lo, hi);
}

#else
vec3 upscale()
{
    return texture2D(a3Source, a3Uv).rgb;
}
#endif

void main()
{
    gl_FragColor = vec4(upscale(), 1.0);
}

#else // A3_SHARPEN: at the window's size

vec3 near(float x, float y)
{
    return texture2D(a3Source, a3Uv + vec2(x, y) / a3SourceSize).rgb;
}

#if A3_SHARPEN == 2
vec3 sharpen()
{
    vec3 a = near(-1.0, -1.0), b = near(0.0, -1.0), c = near(1.0, -1.0);
    vec3 d = near(-1.0, 0.0), e = near(0.0, 0.0), f = near(1.0, 0.0);
    vec3 g = near(-1.0, 1.0), h = near(0.0, 1.0), i = near(1.0, 1.0);
    vec3 lo = min(min(min(d, e), min(f, b)), h);
    vec3 hi = max(max(max(d, e), max(f, b)), h);
    lo += min(lo, min(min(a, c), min(g, i)));
    hi += max(hi, max(max(a, c), max(g, i)));
    // less where the contrast is already high (no halos), on a 0..2 scale from the two rings summed
    vec3 amp = sqrt(clamp(min(lo, 2.0 - hi) / max(hi, vec3(1e-5)), 0.0, 1.0));
    float peak = -1.0 / mix(8.0, 5.0, a3Sharpness);
    vec3 w = amp * peak;
    return clamp((b * w + d * w + f * w + h * w + e) / (1.0 + 4.0 * w), 0.0, 1.0);
}
#else
vec3 sharpen()
{
    vec3 b = near(0.0, -1.0), d = near(-1.0, 0.0), e = near(0.0, 0.0), f = near(1.0, 0.0), h = near(0.0, 1.0);
    vec3 lo = min(min(b, d), min(f, h));
    vec3 hi = max(max(b, d), max(f, h));
    // the most negative lobe that keeps every channel within the neighbours' range
    vec3 hitMin = min(lo, e) / max(4.0 * hi, vec3(1e-5));
    vec3 hitMax = (1.0 - max(hi, e)) / min(4.0 * lo - 4.0, vec3(-1e-5));
    vec3 lobes = max(-hitMin, hitMax);
    float lobe = max(-0.1875, min(max(lobes.r, max(lobes.g, lobes.b)), 0.0)) * a3Sharpness;
    return clamp((lobe * (b + d + h + f) + e) / (4.0 * lobe + 1.0), 0.0, 1.0);
}
#endif

void main()
{
    gl_FragColor = vec4(sharpen(), 1.0);
}

#endif
#endif
