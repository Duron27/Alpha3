#ifndef ALPHA3_SUPPORT_GENERATEDMAPS_H
#define ALPHA3_SUPPORT_GENERATEDMAPS_H

// The maps the experimental graphics make from a texture's own colours (src/experimental.cpp lists them in the VFS): a
// normal map with the height in alpha, a specular map, and a terrain "diffuse specular" map. Plain C++ on RGBA8 rows (no
// OSG), so it is tested on the host.

#include <cstdint>
#include <string>
#include <string_view>
#include <vector>

namespace Alpha3
{
    // What a texture shows, from its name (materialOf); the shaders draw a procedural surface for each. The value is
    // stored in the normal map's 1x1 mip level (see writeNormalHeightDds), so it must stay below 16.
    enum class Material : std::uint8_t
    {
        None = 0,
        Bark = 1,
        Wood = 2,
        Stone = 3,
        Masonry = 4,
        Plaster = 5,
        Dirt = 6,
        Grass = 7,
        Sand = 8,
        Metal = 9,
    };

    struct MaterialRule
    {
        Material mMaterial;
        bool mSkip; // no generated maps at all
        std::vector<std::string> mKeywords;
    };

    // "skip:banner,sign;bark:bark,root;..." in order, the first rule with a keyword in the name wins
    std::vector<MaterialRule> parseMaterialRules(std::string_view text);
    const char* defaultMaterialRules();
    const char* materialName(Material material);
    // false: the texture gets no generated maps (a skip rule matched); material is None when nothing matched
    bool materialOf(const std::vector<MaterialRule>& rules, std::string_view stem, Material& material);

    struct RgbaImage
    {
        int mWidth = 0;
        int mHeight = 0;
        std::vector<std::uint8_t> mRgba; // rows top to bottom, as the DDS stores them
    };

    struct MapSettings
    {
        float mNormalStrength = 1.f;
    };

    // Height from the colours (band-passed luminance, 0.5 on average) and its normals, as the engine reads a normal map:
    // xyz in rgb (z up, out of the surface; y along the rows), height in alpha. Mip levels are made from it; the last (1x1)
    // carries the material (see materialTag).
    RgbaImage makeNormalHeight(const RgbaImage& diffuse, const MapSettings& settings);
    // OpenMW's object specular map: colour in rgb, shininess / 255 in alpha
    // The normal map with its height (alpha) made gentle enough for OpenMW's parallax
    RgbaImage forParallax(const RgbaImage& normalHeight, const MapSettings& settings);

    RgbaImage makeSpecular(const RgbaImage& diffuse, const RgbaImage& normalHeight, Material material);
    // The terrain's "diffuse specular" map: the diffuse colour, the specular strength in alpha
    RgbaImage makeDiffuseSpecular(const RgbaImage& diffuse, const RgbaImage& normalHeight, Material material);

    // Whether the texture's streaks (grain, fibres, furrows) run along u (its rows) rather than along v: the shaders turn
    // their patterns to match
    bool grainAlongU(const RgbaImage& diffuse);

    // The 1x1 texel that names the material: red = material / 15, green = 0, blue = 1, alpha = 1 when the grain runs
    // along u. A real normal map's 1x1 level is about (0.5, 0.5, 1), so green tells them apart.
    void materialTag(Material material, bool alongU, std::uint8_t out[4]);

    // DXT5 DDS with every mip level down to 1x1. normals: the levels are renormalized and the last is the material tag.
    std::string writeDds(const RgbaImage& image, bool normals, Material material, bool alongU);
}

#endif
