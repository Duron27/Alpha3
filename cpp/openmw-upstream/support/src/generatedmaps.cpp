// Normal, height and specular maps made from a texture's colours, for the experimental graphics (src/experimental.cpp).
// The height is the luminance with the broad shading removed (a band pass), so a dark painted crack sinks and a bright
// edge rises while a darker half of the texture stays level. Textures repeat, so every filter wraps around.
#include "generatedmaps.hpp"

#include <ProcessDxtc.hpp>

#include <algorithm>
#include <cmath>
#include <cstring>

namespace Alpha3
{
    namespace
    {
        struct MaterialLook
        {
            const char* mName;
            float mSpecular; // strength of the highlight
            float mShininess; // its exponent
            bool mTinted; // highlight in the surface's own colour (metal)
        };

        // by Material value
        constexpr MaterialLook sLooks[] = {
            { "none", 0.08f, 12.f, false },
            { "bark", 0.05f, 8.f, false },
            { "wood", 0.12f, 18.f, false },
            { "stone", 0.16f, 24.f, false },
            { "masonry", 0.14f, 20.f, false },
            { "plaster", 0.07f, 10.f, false },
            { "dirt", 0.04f, 6.f, false },
            { "grass", 0.06f, 10.f, false },
            { "sand", 0.08f, 14.f, false },
            { "metal", 0.6f, 48.f, true },
        };

        const MaterialLook& look(Material material)
        {
            const auto index = static_cast<std::size_t>(material);
            return sLooks[index < std::size(sLooks) ? index : 0];
        }

        float luminance(const std::uint8_t* p)
        {
            return (0.299f * p[0] + 0.587f * p[1] + 0.114f * p[2]) / 255.f;
        }

        // One box blur along rows (step 1) or columns (step width), wrapping around
        void boxPass(std::vector<float>& v, std::vector<float>& tmp, int width, int height, int radius, bool rows)
        {
            const int length = rows ? width : height;
            const int lines = rows ? height : width;
            const int r = std::min(radius, (length - 1) / 2);
            if (r <= 0)
                return;
            const float scale = 1.f / (2 * r + 1);
            tmp.resize(length);
            for (int line = 0; line < lines; ++line)
            {
                auto at = [&](int i) -> float& {
                    i = ((i % length) + length) % length;
                    return rows ? v[static_cast<std::size_t>(line) * width + i] : v[static_cast<std::size_t>(i) * width + line];
                };
                float sum = 0.f;
                for (int i = -r; i <= r; ++i)
                    sum += at(i);
                for (int i = 0; i < length; ++i)
                {
                    tmp[i] = sum * scale;
                    sum += at(i + r + 1) - at(i - r);
                }
                for (int i = 0; i < length; ++i)
                    at(i) = tmp[i];
            }
        }

        // About a gaussian of [sigma] texels: three box blurs each way
        void blur(std::vector<float>& v, int width, int height, float sigma)
        {
            if (sigma < 0.3f)
                return;
            const int radius = std::max(1, static_cast<int>(std::lround(std::sqrt(4.f * sigma * sigma + 1.f) / 2.f)));
            std::vector<float> tmp;
            for (int pass = 0; pass < 3; ++pass)
            {
                boxPass(v, tmp, width, height, radius, true);
                boxPass(v, tmp, width, height, radius, false);
            }
        }

        std::uint8_t toByte(float v)
        {
            return static_cast<std::uint8_t>(std::clamp(v, 0.f, 1.f) * 255.f + 0.5f);
        }

        std::vector<std::string> split(std::string_view text, char separator)
        {
            std::vector<std::string> result;
            std::size_t start = 0;
            while (start <= text.size())
            {
                std::size_t end = text.find(separator, start);
                if (end == std::string_view::npos)
                    end = text.size();
                std::string part(text.substr(start, end - start));
                part.erase(0, part.find_first_not_of(" \t"));
                part.erase(part.find_last_not_of(" \t") + 1);
                if (!part.empty())
                    result.push_back(std::move(part));
                start = end + 1;
            }
            return result;
        }

        // The next mip level of an RGBA8 image (2x2 average, odd sizes keep their last row or column)
        RgbaImage halve(const RgbaImage& src, bool normals)
        {
            RgbaImage dst;
            dst.mWidth = std::max(1, src.mWidth / 2);
            dst.mHeight = std::max(1, src.mHeight / 2);
            dst.mRgba.resize(static_cast<std::size_t>(dst.mWidth) * dst.mHeight * 4);
            for (int y = 0; y < dst.mHeight; ++y)
                for (int x = 0; x < dst.mWidth; ++x)
                {
                    float sum[4] = {};
                    for (int sy = 0; sy < 2; ++sy)
                        for (int sx = 0; sx < 2; ++sx)
                        {
                            const int ox = std::min(src.mWidth - 1, x * 2 + sx), oy = std::min(src.mHeight - 1, y * 2 + sy);
                            const std::uint8_t* s = &src.mRgba[(static_cast<std::size_t>(oy) * src.mWidth + ox) * 4];
                            for (int c = 0; c < 4; ++c)
                                sum[c] += s[c] / 255.f;
                        }
                    std::uint8_t* d = &dst.mRgba[(static_cast<std::size_t>(y) * dst.mWidth + x) * 4];
                    if (normals)
                    {
                        // the average of unit vectors is shorter: made unit again (the bumps flatten with distance)
                        float n[3] = { sum[0] / 2.f - 1.f, sum[1] / 2.f - 1.f, sum[2] / 2.f - 1.f };
                        const float length = std::sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
                        for (int c = 0; c < 3; ++c)
                            d[c] = toByte((length > 1e-4f ? n[c] / length : (c == 2 ? 1.f : 0.f)) * 0.5f + 0.5f);
                    }
                    else
                        for (int c = 0; c < 3; ++c)
                            d[c] = toByte(sum[c] / 4.f);
                    d[3] = toByte(sum[3] / 4.f);
                }
            return dst;
        }

        // One mip level as DXT5, in rows padded to blocks of 4 (etcpak's DXT encoders take RGBA as stored)
        void compressBc3(const RgbaImage& image, std::string& out)
        {
            const int pw = (image.mWidth + 3) & ~3, ph = (image.mHeight + 3) & ~3;
            std::vector<std::uint32_t> padded(static_cast<std::size_t>(pw) * ph);
            for (int y = 0; y < ph; ++y)
                for (int x = 0; x < pw; ++x)
                    std::memcpy(&padded[static_cast<std::size_t>(y) * pw + x],
                        &image.mRgba[(static_cast<std::size_t>(std::min(y, image.mHeight - 1)) * image.mWidth
                                         + std::min(x, image.mWidth - 1))
                            * 4],
                        4);
            const std::size_t blocks = static_cast<std::size_t>(pw / 4) * (ph / 4);
            std::vector<std::uint64_t> compressed(blocks * 2);
            CompressBc3(padded.data(), compressed.data(), static_cast<std::uint32_t>(blocks), static_cast<std::size_t>(pw));
            out.append(reinterpret_cast<const char*>(compressed.data()), compressed.size() * sizeof(std::uint64_t));
        }

        void put32(std::string& out, std::uint32_t v)
        {
            out.append(reinterpret_cast<const char*>(&v), 4);
        }
    }

    const char* defaultMaterialRules()
    {
        // Keywords are matched anywhere in the file's name, the first rule that has one wins. Skipped: what is painted
        // rather than a surface (banners, signs, books, faces), the GUI, effects, clothes, armour and weapons (their own
        // shapes), and what is mostly cut out or see-through (leaves, glass, water).
        return "skip:ripple,flake,blizzard,raindrop,snowflake,spark,smoke,glow,beam,banner,tapestry,sign,rug,book,menu,paper,scroll,map,icon,cursor,glass,window,fabric,cloth,robe,shirt,"
               "pants,skirt,shoe,glove,belt,ring,amulet,tx_a_,tx_w_,tx_c_,tx_b_,creature,face,hair,head,eye,vfx,fx_,"
               "alpha,water,lava,fire,flame,light,lantern,tent,screen,shell,leaves,leaf,flower,plant,fern,kelp,web,potion,"
               "bottle,candle,blood,"
               "sky,cloud,star,moon,sun,birthsign,levelup,splash,compass,target,enviro,magic;"
               "bark:bark,root,tree,trunk,branch,log;"
               "metal:metal,iron,steel,dwrv,dwemer,bronze,copper,brass,silver,gold,pipe,gear;"
               "wood:wood,plank,board,crate,barrel,hull,door,shelf,table,chair,bench,dock,beam,ship,boat,cart;"
               "plaster:plaster,adobe,stucco,hlaalu,clay,render;"
               "masonry:brick,block,cobble,floor,tile,imp_,stronghold,daed,velothi,temple,marble,tomb,ruin,wall,step,stair,"
               "pillar,column,arch;"
               "stone:rock,stone,cliff,boulder,cave,cavern,crag,pebble,gravel,mountain,ice;"
               "dirt:dirt,mud,ground,road,path,soil,ash,trail,swamp,land;"
               "grass:grass,moss,lawn,meadow,field;"
               "sand:sand,beach,dune,shore";
    }

    std::vector<MaterialRule> parseMaterialRules(std::string_view text)
    {
        std::vector<MaterialRule> rules;
        for (const std::string& rule : split(text, ';'))
        {
            const std::size_t colon = rule.find(':');
            if (colon == std::string::npos)
                continue;
            std::string name = rule.substr(0, colon);
            name.erase(name.find_last_not_of(" \t") + 1);
            MaterialRule parsed{ Material::None, name == "skip", split(std::string_view(rule).substr(colon + 1), ',') };
            if (!parsed.mSkip)
            {
                bool known = false;
                for (std::size_t i = 1; i < std::size(sLooks); ++i)
                    if (name == sLooks[i].mName)
                    {
                        parsed.mMaterial = static_cast<Material>(i);
                        known = true;
                    }
                if (!known)
                    continue;
            }
            for (std::string& keyword : parsed.mKeywords)
                std::transform(keyword.begin(), keyword.end(), keyword.begin(),
                    [](unsigned char c) { return static_cast<char>(std::tolower(c)); });
            rules.push_back(std::move(parsed));
        }
        return rules;
    }

    const char* materialName(Material material)
    {
        return look(material).mName;
    }

    bool materialOf(const std::vector<MaterialRule>& rules, std::string_view stem, Material& material)
    {
        material = Material::None;
        for (const MaterialRule& rule : rules)
            for (const std::string& keyword : rule.mKeywords)
                if (stem.find(keyword) != std::string_view::npos)
                {
                    material = rule.mMaterial;
                    return !rule.mSkip;
                }
        return true;
    }

    RgbaImage makeNormalHeight(const RgbaImage& diffuse, const MapSettings& settings)
    {
        const int w = diffuse.mWidth, h = diffuse.mHeight;
        const std::size_t count = static_cast<std::size_t>(w) * h;
        // Sizes in texels of a 256 texture: a larger one has the same features over more texels
        const float scale = std::max(w, h) / 256.f;

        std::vector<float> lum(count);
        double mean = 0;
        std::size_t opaque = 0;
        for (std::size_t i = 0; i < count; ++i)
        {
            const std::uint8_t* p = &diffuse.mRgba[i * 4];
            lum[i] = luminance(p);
            if (p[3] >= 128)
            {
                mean += lum[i];
                ++opaque;
            }
        }
        mean = opaque ? mean / opaque : 0.5;
        // cut out texels are level: they would make a rim around every leaf
        for (std::size_t i = 0; i < count; ++i)
            if (diffuse.mRgba[i * 4 + 3] < 128)
                lum[i] = static_cast<float>(mean);

        // band pass: fine noise softened a little, the painted light and shade across the texture taken away
        std::vector<float> fine = lum, broad = lum;
        blur(fine, w, h, 0.7f * scale);
        blur(broad, w, h, 20.f * scale);
        std::vector<float> height(count);
        double variance = 0;
        for (std::size_t i = 0; i < count; ++i)
        {
            height[i] = fine[i] - broad[i];
            variance += height[i] * height[i];
        }
        const float deviation = static_cast<float>(std::sqrt(variance / std::max<std::size_t>(count, 1)));
        // about +-2 deviations fill the range
        const float toRange = deviation > 1e-5f ? 0.25f / deviation : 0.f;
        for (float& v : height)
            v = std::clamp(0.5f + v * toRange, 0.f, 1.f);

        RgbaImage result;
        result.mWidth = w;
        result.mHeight = h;
        result.mRgba.resize(count * 4);
        // the slope of the height in units of a 256 texture's texel, times the strength: 0.5 of height over 6 texels is
        // about 45 degrees at strength 1
        const float slope = settings.mNormalStrength * 3.f * scale;
        for (int y = 0; y < h; ++y)
            for (int x = 0; x < w; ++x)
            {
                auto at = [&](int ox, int oy) {
                    return height[static_cast<std::size_t>(((y + oy) % h + h) % h) * w + ((x + ox) % w + w) % w];
                };
                // Sobel: the slope along x (columns) and y (rows)
                const float gx = (at(1, -1) + 2.f * at(1, 0) + at(1, 1) - at(-1, -1) - 2.f * at(-1, 0) - at(-1, 1)) / 8.f;
                const float gy = (at(-1, 1) + 2.f * at(0, 1) + at(1, 1) - at(-1, -1) - 2.f * at(0, -1) - at(1, -1)) / 8.f;
                float n[3] = { -gx * slope, -gy * slope, 1.f };
                const float length = std::sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
                std::uint8_t* d = &result.mRgba[(static_cast<std::size_t>(y) * w + x) * 4];
                for (int c = 0; c < 3; ++c)
                    d[c] = toByte(n[c] / length * 0.5f + 0.5f);
                d[3] = toByte(height[static_cast<std::size_t>(y) * w + x]);
            }
        return result;
    }

    RgbaImage forParallax(const RgbaImage& normalHeight, const MapSettings& settings)
    {
        // OpenMW shifts the texture by up to 0.02 of it where the height is used for parallax, made for real height maps.
        // Relief guessed from colours is noisy and full range, which made surfaces swim, so the map's height is softened
        // and keeps only a part of its range, following the strength (about a third at 1, a fifteenth at 0.2).
        const int w = normalHeight.mWidth, h = normalHeight.mHeight;
        const std::size_t count = static_cast<std::size_t>(w) * h;
        std::vector<float> height(count);
        for (std::size_t i = 0; i < count; ++i)
            height[i] = normalHeight.mRgba[i * 4 + 3] / 255.f;
        blur(height, w, h, 1.5f * std::max(w, h) / 256.f);
        const float depth = std::clamp(0.35f * settings.mNormalStrength, 0.f, 1.f);
        RgbaImage result = normalHeight;
        for (std::size_t i = 0; i < count; ++i)
            result.mRgba[i * 4 + 3] = toByte(0.5f + (height[i] - 0.5f) * depth);
        return result;
    }

    RgbaImage makeSpecular(const RgbaImage& diffuse, const RgbaImage& normalHeight, Material material)
    {
        const MaterialLook& l = look(material);
        RgbaImage result;
        result.mWidth = diffuse.mWidth;
        result.mHeight = diffuse.mHeight;
        result.mRgba.resize(diffuse.mRgba.size());
        for (std::size_t i = 0; i < diffuse.mRgba.size() / 4; ++i)
        {
            const std::uint8_t* p = &diffuse.mRgba[i * 4];
            // raised and lighter parts catch more light than cracks, which hold dirt
            const float height = normalHeight.mRgba[i * 4 + 3] / 255.f;
            const float strength = l.mSpecular * (0.4f + 1.2f * height) * (0.6f + 0.8f * luminance(p));
            std::uint8_t* d = &result.mRgba[i * 4];
            for (int c = 0; c < 3; ++c)
                d[c] = toByte(strength * (l.mTinted ? 0.3f + 1.4f * p[c] / 255.f : 1.f));
            d[3] = toByte(l.mShininess / 255.f);
        }
        return result;
    }

    RgbaImage makeDiffuseSpecular(const RgbaImage& diffuse, const RgbaImage& normalHeight, Material material)
    {
        const MaterialLook& l = look(material);
        RgbaImage result = diffuse;
        for (std::size_t i = 0; i < result.mRgba.size() / 4; ++i)
        {
            const float height = normalHeight.mRgba[i * 4 + 3] / 255.f;
            result.mRgba[i * 4 + 3] = toByte(l.mSpecular * 2.f * (0.4f + 1.2f * height));
        }
        return result;
    }

    bool grainAlongU(const RgbaImage& diffuse)
    {
        // the structure tensor of the luminance: streaks along u change little along u, so their gradients point along v
        const int w = diffuse.mWidth, h = diffuse.mHeight;
        double along = 0, across = 0;
        for (int y = 0; y < h; ++y)
            for (int x = 0; x < w; ++x)
            {
                const float l = luminance(&diffuse.mRgba[(static_cast<std::size_t>(y) * w + x) * 4]);
                const float right = luminance(&diffuse.mRgba[(static_cast<std::size_t>(y) * w + (x + 1) % w) * 4]);
                const float below = luminance(&diffuse.mRgba[(static_cast<std::size_t>((y + 1) % h) * w + x) * 4]);
                along += (right - l) * (right - l);
                across += (below - l) * (below - l);
            }
        return across > along * 1.15;
    }

    void materialTag(Material material, bool alongU, std::uint8_t out[4])
    {
        out[0] = static_cast<std::uint8_t>(static_cast<unsigned>(material) * 17);
        out[1] = 0;
        out[2] = 255;
        out[3] = alongU ? 255 : 0;
    }

    std::string writeDds(const RgbaImage& image, bool normals, Material material, bool alongU)
    {
        std::vector<RgbaImage> levels{ image };
        while (levels.back().mWidth > 1 || levels.back().mHeight > 1)
            levels.push_back(halve(levels.back(), normals));
        if (normals)
            materialTag(material, alongU, levels.back().mRgba.data());

        std::string out = "DDS ";
        put32(out, 124);
        put32(out, 0x1 | 0x2 | 0x4 | 0x1000 | 0x20000 | 0x80000); // caps, height, width, format, mips, size
        put32(out, static_cast<std::uint32_t>(image.mHeight));
        put32(out, static_cast<std::uint32_t>(image.mWidth));
        put32(out, static_cast<std::uint32_t>(((image.mWidth + 3) / 4) * ((image.mHeight + 3) / 4) * 16));
        put32(out, 0); // depth
        put32(out, static_cast<std::uint32_t>(levels.size()));
        for (int i = 0; i < 11; ++i)
            put32(out, 0);
        put32(out, 32);
        put32(out, 0x4); // four CC
        out += "DXT5";
        for (int i = 0; i < 5; ++i)
            put32(out, 0);
        put32(out, 0x1000 | 0x8 | 0x400000); // texture, complex, mip map
        for (int i = 0; i < 4; ++i)
            put32(out, 0);
        for (const RgbaImage& level : levels)
            compressBc3(level, out);
        return out;
    }
}
