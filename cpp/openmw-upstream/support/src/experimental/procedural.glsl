// Procedural surfaces of the experimental graphics ([Experimental] procedural materials). Added to OpenMW's object and
// terrain fragment shaders by src/experimental.cpp, and rendered by tools/procedural/ for checking how they look.
//
// The large shapes stay the texture's own: the generated normal map (made from its colours, src/generatedmaps.cpp) keeps
// where its bricks, planks and cracks are painted, and the colours come from a blurred mip level of the texture. On top of
// that each material adds what a 256 texel texture cannot hold up close: grain, pores, chips, fibres, pebbles. The
// material is the drawable's (A3_MATERIAL, an OSG state define from its texture's name, src/experimentalstate.cpp);
// the normal map's 1x1 mip level is tagged with it (materialTag in generatedmaps.cpp), which says the map is a generated
// one and which way the grain runs.
//
// Written in the GLSL that OpenGL 2.1 and OpenGL ES 3.0 share: float literals, no integer or bit operations. Every
// pattern repeats once per texture repeat (whole numbers of cells over one unit of uv), so a seam where the uv wraps
// shows nothing.

// Dave Hoskins' hash without sine: stable in highp on phones
float a3Hash(vec2 p)
{
    vec3 p3 = fract(vec3(p.xyx) * 0.1031);
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.x + p3.y) * p3.z);
}

vec2 a3Hash2(vec2 p)
{
    vec3 p3 = fract(vec3(p.xyx) * vec3(0.1031, 0.1030, 0.0973));
    p3 += dot(p3, p3.yzx + 33.33);
    return fract((p3.xx + p3.yz) * p3.zy);
}

// Value noise with [cells] cells over one unit, repeating with it
float a3Value(vec2 p, vec2 cells)
{
    vec2 x = p * cells;
    vec2 i = floor(x);
    vec2 f = x - i;
    vec2 s = f * f * (3.0 - 2.0 * f);
    float a = a3Hash(mod(i, cells));
    float b = a3Hash(mod(i + vec2(1.0, 0.0), cells));
    float c = a3Hash(mod(i + vec2(0.0, 1.0), cells));
    float d = a3Hash(mod(i + vec2(1.0, 1.0), cells));
    return mix(mix(a, b, s.x), mix(c, d, s.x), s.y);
}

// Octaves of value noise, each fading out once its cells get smaller than a pixel ([footprint]: uv per pixel)
float a3Fbm(vec2 p, vec2 cells, float footprint, float octaves)
{
    float sum = 0.0;
    float amp = 0.5;
    float norm = 0.0;
    for (float o = 0.0; o < 6.0; o += 1.0)
    {
        if (o >= octaves)
            break;
        float fade = clamp(1.5 - footprint * max(cells.x, cells.y) * 2.0, 0.0, 1.0);
        sum += amp * fade * (a3Value(p, cells) - 0.5);
        norm += amp;
        amp *= 0.5;
        cells *= 2.0;
    }
    return 0.5 + sum / max(norm, 0.001);
}

// How much of a pattern with [cells] cells per unit is left at [fp] uv per pixel: gone before its cells are two pixels
float a3Fade(float fp, float cells)
{
    return clamp(1.5 - fp * cells * 2.0, 0.0, 1.0);
}

// Distance to the nearest and second nearest of one jittered point per cell (cellular noise), in cells, repeating with
// [cells]. Fewer cells along one axis make long cells along it.
vec2 a3Cells(vec2 p, vec2 cells, float seed)
{
    vec2 x = p * cells;
    vec2 i = floor(x);
    vec2 f = x - i;
    float f1 = 8.0;
    float f2 = 8.0;
    for (float dy = -1.0; dy <= 1.0; dy += 1.0)
        for (float dx = -1.0; dx <= 1.0; dx += 1.0)
        {
            vec2 c = vec2(dx, dy);
            vec2 o = c + a3Hash2(mod(i + c, cells) + seed) - f;
            float d = length(o);
            f2 = d < f1 ? f1 : min(f2, d);
            f1 = min(f1, d);
        }
    return vec2(f1, f2);
}

// Chipped facets: every cell is a low tilted cap, the surface is the highest cap, so it is continuous with sharp creases
// where two caps meet (0 to about 1). The caps fall off fast enough that one from outside the 3x3 cells searched never
// wins, which would leave straight seams.
float a3Facets(vec2 p, vec2 cells, float seed)
{
    vec2 x = p * cells;
    vec2 i = floor(x);
    vec2 f = x - i;
    float h = -8.0;
    for (float dy = -1.0; dy <= 1.0; dy += 1.0)
        for (float dx = -1.0; dx <= 1.0; dx += 1.0)
        {
            vec2 c = vec2(dx, dy);
            vec2 id = mod(i + c, cells) + seed;
            vec2 o = f - c - a3Hash2(id);
            float cap = a3Hash(id + 5.0) * 0.3 + dot(a3Hash2(id + 17.0) - 0.5, o) * 0.6 - dot(o, o) * 1.2;
            h = max(h, cap);
        }
    return clamp(h + 0.6, 0.0, 1.0);
}

// Irregular blobs where fbm is above [threshold]: chips, rust scale, worn spots
float a3Blobs(vec2 p, vec2 cells, float fp, float threshold)
{
    return smoothstep(threshold - 0.04, threshold + 0.1, a3Fbm(p, cells, fp, 3.0)) * a3Fade(fp, cells.x * 2.0);
}

// Rounded pebbles, one in about half the cells (0..1, 1 at a pebble's middle)
float a3Pebbles(vec2 p, vec2 cells, float seed)
{
    vec2 x = p * cells;
    vec2 i = floor(x);
    vec2 f = x - i;
    float best = 0.0;
    for (float dy = -1.0; dy <= 1.0; dy += 1.0)
        for (float dx = -1.0; dx <= 1.0; dx += 1.0)
        {
            vec2 c = vec2(dx, dy);
            vec2 id = mod(i + c, cells) + seed;
            vec2 o = c + 0.2 + 0.6 * a3Hash2(id) - f;
            float size = 0.18 + 0.14 * a3Hash(id + 9.0);
            float pebble = step(0.45, a3Hash(id + 4.0)) * (1.0 - smoothstep(size * 0.4, size, length(o)));
            best = max(best, pebble);
        }
    return best;
}

// Scattered blades: in every cell one short stroke at its own angle; the brightest of the strokes around (0..1, 1 at the
// middle of a blade, its tip lighter than its base in y)
vec2 a3Blades(vec2 p, vec2 cells, float seed, float length, float width)
{
    vec2 x = p * cells;
    vec2 i = floor(x);
    vec2 f = x - i;
    vec2 best = vec2(0.0);
    for (float dy = -1.0; dy <= 1.0; dy += 1.0)
        for (float dx = -1.0; dx <= 1.0; dx += 1.0)
        {
            vec2 c = vec2(dx, dy);
            vec2 id = mod(i + c, cells) + seed;
            vec2 r = a3Hash2(id);
            vec2 o = f - c - r;
            // mostly upright, leaning either way
            float angle = (a3Hash(id + 3.0) - 0.5) * 2.2;
            vec2 along = vec2(sin(angle), cos(angle));
            float t = dot(o, along);
            float s = dot(o, vec2(along.y, -along.x));
            float taper = clamp(1.0 - abs(t) / length, 0.0, 1.0);
            float blade = taper * clamp(1.0 - abs(s) / (width * (0.3 + taper)), 0.0, 1.0);
            if (blade > best.x)
                best = vec2(blade, clamp(0.5 - t / length * 0.5, 0.0, 1.0));
        }
    return best;
}

// ---- the materials: height (0..1, 0.5 level) in w, a colour multiplier in rgb (about 1)

// Furrowed bark: lens-shaped plates along the trunk (v) that split and join, deep V furrows between them, stringy fibres
// and a flaky surface
vec4 a3Bark(vec2 p, float fp, out float detail)
{
    // the furrows wander: a slow sideways warp, and a faster small one
    vec2 w = p;
    w.x += 0.045 * (a3Value(p, vec2(6.0, 3.0)) - 0.5) + 0.012 * (a3Value(p, vec2(24.0, 12.0)) - 0.5);
    w.y += 0.12 * (a3Value(p + 0.5, vec2(4.0, 2.0)) - 0.5);
    vec2 c = a3Cells(w, vec2(16.0, 2.0), 1.0);
    float edge = c.y - c.x;
    float rag = a3Fbm(p, vec2(48.0, 24.0), fp, 3.0);
    // 0 at the bottom of a furrow, 1 on a plate; V shaped, with ragged lips
    float plate = smoothstep(0.06, 0.5, edge - 0.08 * (rag - 0.5));
    float fibres = mix(0.5, a3Value(w, vec2(180.0, 12.0)), a3Fade(fp, 180.0));
    float flakes = a3Fbm(p, vec2(40.0, 30.0), fp, 5.0);
    float relief = a3Fade(fp, 14.0);
    float h = mix(0.5, 0.15 + 0.6 * plate, relief) + 0.12 * (fibres - 0.5) + 0.22 * (flakes - 0.5);
    float shade = mix(1.0, mix(0.6, 1.25, plate), relief) * (0.9 + 0.2 * fibres) * (0.85 + 0.3 * flakes);
    // moss and lichen of the texture stay in the furrows
    detail = 0.55 * (1.0 - plate) * relief;
    return vec4(vec3(shade), h);
}

// Sawn wood, its grain along v: growth lines that waver and run out, and fine fibres between them. replace: there is
// no painted layout, so the boards are laid here, four across a unit, each with its own tone and grain offset.
vec4 a3Wood(vec2 p, float fp, bool replace, out float detail)
{
    detail = 0.0;
    float seam = 0.0;
    float tone = 0.5;
    if (replace)
    {
        float board = floor(p.x * 4.0);
        float id = mod(board, 4.0);
        tone = a3Hash(vec2(id, 31.0));
        float f = fract(p.x * 4.0);
        seam = 1.0 - smoothstep(0.0, 0.025, min(f, 1.0 - f));
        p.y += a3Hash(vec2(id, 47.0)); // whole units: the grain still repeats with the texture
        p.y = p.y - floor(p.y);
    }
    float wave = a3Fbm(p, vec2(6.0, 2.0), fp, 3.0);
    vec2 q = p + vec2(wave * 0.06, 0.0);
    float lines = mix(0.5, a3Value(q, vec2(70.0, 3.0)), a3Fade(fp, 70.0));
    float grain = smoothstep(0.3, 0.8, lines);
    float fibres = mix(0.5, a3Value(q, vec2(300.0, 8.0)), a3Fade(fp, 300.0));
    float h = 0.5 + 0.2 * (grain - 0.5) + 0.1 * (fibres - 0.5) - 0.4 * seam;
    float shade = 0.92 + 0.16 * grain + 0.06 * (fibres - 0.5) + 0.16 * (tone - 0.5) - 0.3 * seam;
    return vec4(vec3(shade), h);
}

// Rock broken into chipped facets at two sizes, rough between them, with fine crystals; the painted cracks of the
// texture are kept
vec4 a3Stone(vec2 p, float fp, out float detail)
{
    detail = 0.15;
    float big = mix(0.5, a3Facets(p, vec2(6.0, 6.0), 3.0), a3Fade(fp, 6.0));
    float small = mix(0.5, a3Facets(p + 0.31, vec2(19.0, 19.0), 9.0), a3Fade(fp, 19.0));
    float rough = a3Fbm(p, vec2(32.0, 32.0), fp, 4.0);
    float grit = mix(0.5, a3Value(p, vec2(256.0, 256.0)), a3Fade(fp, 256.0));
    float h = 0.4 * big + 0.3 * small + 0.25 * rough + 0.02 * grit;
    float shade = 0.86 + 0.14 * big + 0.12 * small + 0.15 * (rough - 0.5) + 0.12 * (grit - 0.5);
    return vec4(vec3(shade), h);
}

// Dressed stone: the blocks and their edges are the texture's (the generated normals); their faces get a gentle
// tooled undulation, sandstone grain and a few chipped spots, the mortar gets grit
// Laid blocks for when the texture is not used (no painted layout to follow): four courses per unit, three blocks
// each, every course shifted; 0.3 in the mortar to 0.7 on a block's face like the generated height. tone: the block's own.
float a3Blocks(vec2 p, out float tone)
{
    float row = floor(p.y * 4.0);
    float rowId = mod(row, 4.0);
    float x = p.x * 3.0 + a3Hash(vec2(rowId, 7.0));
    float column = mod(floor(x), 3.0);
    tone = a3Hash(vec2(column, rowId) + 21.0);
    vec2 f = vec2(fract(x), fract(p.y * 4.0));
    float edge = min(min(f.x, 1.0 - f.x) / 3.0, min(f.y, 1.0 - f.y) / 4.0); // uv to the nearest joint
    return 0.3 + 0.4 * smoothstep(0.004, 0.012, edge);
}

// macro: the generated height (its painted blocks); below 0 when the texture is not used, and the blocks are laid here
vec4 a3Masonry(vec2 p, float fp, float macro, out float detail)
{
    detail = 0.0;
    float tone = 0.5;
    if (macro < 0.0)
        macro = a3Blocks(p, tone);
    float tooled = a3Fbm(p, vec2(12.0, 12.0), fp, 3.0);
    float rough = a3Fbm(p, vec2(64.0, 64.0), fp, 3.0);
    float grit = mix(0.5, a3Value(p, vec2(320.0, 320.0)), a3Fade(fp, 320.0));
    float chips = a3Blobs(p, vec2(16.0, 16.0), fp, 0.72);
    float mortar = 1.0 - smoothstep(0.3, 0.45, macro);
    float face = 0.5 + 0.25 * (tooled - 0.5) + 0.25 * (rough - 0.5) + 0.08 * (grit - 0.5) - 0.15 * chips;
    float sand = 0.5 + 0.35 * (grit - 0.5);
    float h = mix(face, sand, mortar);
    float shade = mix(1.0 + 0.12 * (tooled - 0.5) + 0.15 * (rough - 0.5) + 0.1 * (grit - 0.5) - 0.1 * chips
            + 0.2 * (tone - 0.5), 0.8 + 0.2 * (grit - 0.5), mortar);
    return vec4(vec3(shade), h);
}

// Lime render: soft trowel undulation, sandy speckle, worn patches
vec4 a3Plaster(vec2 p, float fp, out float detail)
{
    detail = 0.0;
    float trowel = a3Fbm(p, vec2(5.0, 5.0), fp, 3.0);
    float speckle = mix(0.5, a3Value(p, vec2(220.0, 220.0)), a3Fade(fp, 220.0));
    float worn = a3Blobs(p, vec2(9.0, 9.0), fp, 0.66);
    float h = 0.5 + 0.25 * (trowel - 0.5) + 0.12 * (speckle - 0.5) - 0.1 * worn;
    float shade = 0.98 + 0.1 * (trowel - 0.5) + 0.1 * (speckle - 0.5) - 0.06 * worn;
    return vec4(vec3(shade), h);
}

// Packed earth: clods, scattered pebbles, fine grains
vec4 a3Dirt(vec2 p, float fp, out float detail)
{
    detail = 0.0;
    float clods = a3Fbm(p, vec2(10.0, 10.0), fp, 5.0);
    float pebble = a3Pebbles(p, vec2(28.0, 28.0), 13.0) * a3Fade(fp, 56.0);
    float grain = a3Value(p, vec2(300.0, 300.0));
    float h = 0.4 + 0.35 * clods + 0.15 * pebble + 0.1 * grain;
    float shade = 0.86 + 0.25 * (clods - 0.5) + 0.12 * pebble + 0.12 * (grain - 0.5);
    return vec4(vec3(shade), h);
}

// Grass seen from above: two layers of scattered blades over a darker mat of stems, in clumps
vec4 a3Grass(vec2 p, float fp, out float detail)
{
    detail = 0.0;
    float clumps = a3Fbm(p, vec2(8.0, 8.0), fp, 3.0);
    vec2 b1 = a3Blades(p, vec2(64.0, 64.0), 3.0, 0.9, 0.18) * a3Fade(fp, 128.0);
    vec2 b2 = a3Blades(p + 0.37, vec2(110.0, 110.0), 7.0, 0.8, 0.18) * a3Fade(fp, 220.0);
    // rounded blades blended softly, so where they cross there is no hard step
    float blade = 1.0 - (1.0 - smoothstep(0.0, 1.0, b1.x)) * (1.0 - 0.8 * smoothstep(0.0, 1.0, b2.x));
    float tip = b1.x > b2.x * 0.8 ? b1.y : b2.y;
    float h = 0.3 + 0.35 * blade + 0.35 * clumps;
    float shade = mix(0.88, 1.06 + 0.1 * tip, blade) + 0.18 * (clumps - 0.5);
    return vec4(vec3(shade), h);
}

// Wind ripples and fine grains
vec4 a3Sand(vec2 p, float fp, out float detail)
{
    detail = 0.0;
    float warp = a3Fbm(p, vec2(3.0, 3.0), fp, 3.0);
    float ripple = sin((p.x * 2.0 + p.y + warp * 0.6) * 6.2831853 * 9.0) * 0.5 + 0.5;
    float grain = a3Value(p, vec2(400.0, 400.0));
    float h = 0.5 + 0.25 * (ripple - 0.5) + 0.15 * (grain - 0.5);
    float shade = 0.95 + 0.08 * (ripple - 0.5) + 0.12 * (grain - 0.5);
    return vec4(vec3(shade), h);
}

// Worked iron: shallow hammer dents, faint brushing along v, and raised blistered rust scale in patches
vec4 a3Metal(vec2 p, float fp, out float detail)
{
    detail = 0.0;
    vec2 dents = a3Cells(p, vec2(9.0, 9.0), 17.0);
    float dent = smoothstep(0.0, 0.7, dents.x) * a3Fade(fp, 9.0);
    float brush = mix(0.5, a3Value(p, vec2(400.0, 8.0)), a3Fade(fp, 400.0));
    float rust = a3Blobs(p, vec2(12.0, 12.0), fp, 0.55);
    float scale = a3Fbm(p, vec2(90.0, 90.0), fp, 4.0);
    float h = 0.4 + 0.25 * dent + 0.06 * (brush - 0.5) * (1.0 - rust) + rust * (0.1 + 0.5 * (scale - 0.3));
    float shade = 0.97 + 0.1 * (brush - 0.5) * (1.0 - rust) + rust * (0.45 * (scale - 0.5) - 0.06);
    return vec4(vec3(shade), h);
}

// Material by the tag in the normal map's last mip level; 0 when the map is not a generated one
float a3MaterialOf(vec4 tag)
{
    if (tag.g > 0.12 || tag.b < 0.6)
        return 0.0;
    return floor(tag.r * 15.0 + 0.5);
}

// The drawable's material is its variant's A3_MATERIAL (src/experimentalstate.cpp, from its texture's name), so only
// that material's pattern is compiled in: one shader with all of them, picked at run time, was several times slower
vec4 a3Surface(vec2 p, float fp, float macro, out float detail)
{
#if A3_MATERIAL == 1
    return a3Bark(p, fp, detail);
#elif A3_MATERIAL == 2
    return a3Wood(p, fp, macro < 0.0, detail);
#elif A3_MATERIAL == 3
    return a3Stone(p, fp, detail);
#elif A3_MATERIAL == 4
    return a3Masonry(p, fp, macro, detail);
#elif A3_MATERIAL == 5
    return a3Plaster(p, fp, detail);
#elif A3_MATERIAL == 6
    return a3Dirt(p, fp, detail);
#elif A3_MATERIAL == 7
    return a3Grass(p, fp, detail);
#elif A3_MATERIAL == 8
    return a3Sand(p, fp, detail);
#else
    return a3Metal(p, fp, detail);
#endif
}

// By material: x how much of the texture's own fine detail is kept, y how much the generated (painted) shapes still
// shape the normal, z the depth of the procedural relief in texels of a 256 texture. Bark is mostly procedural, masonry
// keeps its blocks.
vec3 a3Look(float material)
{
    if (material < 1.5)
        return vec3(0.35, 0.4, 6.0); // bark
    if (material < 2.5)
        return vec3(0.5, 0.9, 1.5); // wood
    if (material < 3.5)
        return vec3(0.6, 0.6, 8.0); // stone
    if (material < 4.5)
        return vec3(0.55, 1.0, 2.0); // masonry
    if (material < 5.5)
        return vec3(0.55, 0.8, 5.0); // plaster
    if (material < 6.5)
        return vec3(0.45, 0.7, 3.0); // dirt
    if (material < 7.5)
        return vec3(0.5, 0.6, 0.8); // grass
    if (material < 8.5)
        return vec3(0.5, 0.6, 1.5); // sand
    return vec3(0.6, 0.9, 1.0); // metal
}

// How far the pattern's shading is stretched when it is all there is (the texture not used): the patterns are made
// to sit on a painted texture, so their own range is small
float a3ReplaceContrast(float material)
{
    if (material < 1.5)
        return 2.5; // bark
    if (material < 2.5)
        return 3.5; // wood
    if (material < 4.5)
        return 2.5; // stone, masonry
    if (material < 5.5)
        return 2.0; // plaster
    if (material < 6.5)
        return 3.0; // dirt
    if (material < 7.5)
        return 2.0; // grass
    if (material < 8.5)
        return 2.0; // sand
    return 3.0; // metal
}

// The procedural surface on a texel: [colour] is the texture's colour, [normal] the tangent space normal from the
// generated map; both are replaced (in part, or whole). uv: the diffuse uv; viewPos for the fade with distance.
// replace: the texture's painted detail is not used at all, only its average colour; the surface is the material's
// alone, at every distance (its finest patterns still fade out as they get smaller than a pixel). Otherwise the
// texture's layout stays and the material is added up close.
void a3Procedural(sampler2D diffuseMap, sampler2D normalMap, vec2 uv, vec3 viewPos, float strength, float range,
    bool replace, inout vec3 colour, inout vec3 normal)
{
    // the tag in the normal map's last mip level: that it is a generated map (a mod's own has none), and which way the
    // texture's grain runs
    vec4 tag = texture2D(normalMap, uv, 16.0);
    if (a3MaterialOf(tag) < 0.5)
        return;
    const float material = float(A3_MATERIAL);
    // the patterns have their grain along v; a texture whose grain runs along u has them turned
    bool turned = tag.a > 0.5;
    // within the range the patterns are drawn; beyond it only their tint (replacing) or nothing (over the texture)
    float near = 1.0 - smoothstep(range * 0.6, range, length(viewPos));
    float fade = replace ? 1.0 : strength * near;
    if (fade <= 0.0)
        return;

    vec2 duvx = dFdx(uv);
    vec2 duvy = dFdy(uv);
    float fp = max(length(duvx), length(duvy)); // uv per pixel
    vec2 p = turned ? uv.yx : uv;
    if (replace && near <= 0.0)
    {
        // far off: the tint in broad patches, the texture's shading left out
        vec3 tint = texture2D(diffuseMap, uv, 16.0).rgb;
        colour = tint * (0.8 + 0.4 * a3Fbm(p, vec2(3.0, 3.0), fp, 2.0));
        return;
    }
    float macro = replace ? -1.0 : texture2D(normalMap, uv).a;
    float detail;
    vec4 s = a3Surface(p, fp, macro, detail);
    // the slope of the procedural height along u and v, from its change across the pixel (screen derivatives) and the
    // change of uv across it: one evaluation of the pattern rather than three
    vec2 dh = vec2(dFdx(s.w), dFdy(s.w));
    float det = duvx.x * duvy.y - duvx.y * duvy.x;
    det = det < 0.0 ? min(det, -1e-12) : max(det, 1e-12);
    // (along uv itself, so a turned pattern needs no turning back)
    vec2 slopeUv = vec2(duvy.y * dh.x - duvx.y * dh.y, duvx.x * dh.y - duvy.x * dh.x) / det;
    vec3 keep = a3Look(material);
    vec2 slope = slopeUv * (keep.z / 256.0);

    if (replace)
    {
        // the texture's average colour (its last mip level), from dark to light by the pattern's shading, broad
        // patches and a finer mottle, the darks a little more saturated
        vec3 tint = texture2D(diffuseMap, uv, 16.0).rgb;
        float patches = a3Fbm(p, vec2(3.0, 3.0), fp, 3.0);
        float mottle = a3Fbm(p + 0.5, vec2(24.0, 24.0), fp, 3.0);
        float value = clamp(0.5 + (dot(s.rgb, vec3(0.333)) - 1.0) * a3ReplaceContrast(material)
            + 0.45 * (patches - 0.5) + 0.25 * (mottle - 0.5), 0.0, 1.0);
        vec3 saturated = tint * tint / max(dot(tint, vec3(0.333)), 0.01);
        vec3 dark = mix(tint, saturated, 0.5) * 0.5;
        vec3 light = mix(tint, vec3(dot(tint, vec3(0.333))), 0.15) * 1.45;
        vec3 far = tint * (0.8 + 0.4 * patches);
        colour = mix(far, mix(dark, light, value), near);
        // grass is mostly colour: its blades lit as relief look like crumpled foil
        float relief = material > 6.5 && material < 7.5 ? 0.35 : 1.0;
        normal = normalize(vec3(-slope * strength * relief * near, 1.0));
        return;
    }

    vec3 n = normalize(vec3(normal.xy * mix(1.0, keep.y, fade) - slope * fade, normal.z));
    // colours: the texture's own blurred (its painted layout), shaded by the pattern, with some of its fine detail
    vec3 broad = texture2D(diffuseMap, uv, 2.5).rgb;
    vec3 procedural = broad * s.rgb;
    colour = mix(colour, mix(procedural, colour, clamp(keep.x + detail, 0.0, 1.0)), fade);
    normal = n;
}
