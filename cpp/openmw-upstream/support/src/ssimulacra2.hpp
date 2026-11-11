// SSIMULACRA2, a perceptual image quality score (100 = identical, 90 = visually lossless, 70 = high quality, 50 = medium),
// for choosing an ASTC block size per texture (src/textureconversion.cpp). A standalone port of
// https://github.com/cloudinary/ssimulacra2 (BSD-3-Clause, Copyright (c) Cloudinary, by Jon Sneyers; see
// THIRD_PARTY_NOTICES.md) without libjxl or highway: the same XYB space, scales, maps, weights and final mapping. The
// blur is a separable sigma 1.5 Gaussian (radius 5, zero outside the image) where the original uses libjxl's recursive
// Gaussian, so scores can differ from the reference tool by a little.
#ifndef ALPHA3_SSIMULACRA2_HPP
#define ALPHA3_SSIMULACRA2_HPP

#include <cstdint>
#include <memory>

namespace Alpha3
{
    class Ssimulacra2Reference
    {
    public:
        // rgba: width * height pixels of 8-bit sRGB with alpha, tightly packed. greyFromAlpha scores the alpha channel
        // alone, as a grey picture (for alpha-tested and blended textures); otherwise colour is blended over 50% grey by
        // its alpha, as the reference tool does.
        Ssimulacra2Reference(const uint8_t* rgba, int width, int height, bool greyFromAlpha = false);
        ~Ssimulacra2Reference();

        // false when the picture is too small to score (under 8 pixels across)
        bool valid() const;

        // distorted: same size and layout as the reference
        double score(const uint8_t* rgba) const;

    private:
        struct Data;
        std::unique_ptr<Data> mData;
    };
}

#endif
