// The texture upscaler's network ([Textures] texture upscale method = ai, src/textureconversion.cpp): a small
// convolutional network trained to make a texture twice as large with the detail a high resolution version has (the
// training pairs: a low resolution texture and its high resolution one, scaled to twice the size). Its shape is that of
// Real-ESRGAN's compact network (SRVGGNetCompact, BSD-3): a 3x3 convolution from RGB to kFeatures channels, kConvs more
// of kFeatures, each followed by PReLU, a last one to 12 channels that a pixel shuffle makes into twice the size, added
// to the texture upscaled bilinearly. The weights come from aiupscale_weights.hpp, which the training writes.
//
// Run on the CPU, once per texture while it is converted (the result is cached with the rest). The texture is cut into
// tiles that the threads take in turn; each tile is read with a border as wide as the network sees around a pixel, and
// every convolution is "valid" (no padding), so tiles join without seams. Textures repeat, so the border wraps around
// the texture's edges. Alpha is upscaled bilinearly.
#include "support.hpp"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <thread>
#include <vector>

#include "aiupscale_weights.hpp"

#if AI_UPSCALE_AVAILABLE
namespace Alpha3
{
    namespace
    {
        using namespace AiUpscaleWeights;

        constexpr int kLayers = kConvs + 2; // 3x3 convolutions in all
        constexpr int kBorder = kLayers; // each takes one pixel off every side
        constexpr int kTile = 64; // input pixels across a tile, before its border

        struct Layer
        {
            const float* weights; // [ky][kx][in][out]
            const float* bias;
            const float* slopes; // PReLU, null on the last
            int in, out;
        };

        const std::vector<Layer>& layers()
        {
            static const std::vector<Layer> list = [] {
                std::vector<Layer> l;
                const float* w[] = { AI_UPSCALE_WEIGHTS };
                const float* b[] = { AI_UPSCALE_BIASES };
                const float* a[] = { AI_UPSCALE_SLOPES };
                for (int i = 0; i < kLayers; ++i)
                    l.push_back({ w[i], b[i], i + 1 < kLayers ? a[i] : nullptr, i == 0 ? 3 : kFeatures,
                        i + 1 < kLayers ? kFeatures : 12 });
                return l;
            }();
            return list;
        }

        // One valid 3x3 convolution of an (w x h x in) picture, channels last, to ((w-2) x (h-2) x out)
        template <int Out>
        void convolve(const Layer& layer, const float* src, int w, int h, float* dst)
        {
            const int ow = w - 2, oh = h - 2, in = layer.in;
            for (int y = 0; y < oh; ++y)
                for (int x = 0; x < ow; ++x)
                {
                    float acc[Out];
                    for (int o = 0; o < Out; ++o)
                        acc[o] = layer.bias[o];
                    for (int ky = 0; ky < 3; ++ky)
                        for (int kx = 0; kx < 3; ++kx)
                        {
                            const float* p = src + (static_cast<size_t>(y + ky) * w + (x + kx)) * in;
                            const float* k = layer.weights + (ky * 3 + kx) * in * Out;
                            for (int i = 0; i < in; ++i, k += Out)
                            {
                                const float v = p[i];
                                for (int o = 0; o < Out; ++o)
                                    acc[o] += v * k[o];
                            }
                        }
                    float* q = dst + (static_cast<size_t>(y) * ow + x) * Out;
                    if (layer.slopes)
                        for (int o = 0; o < Out; ++o)
                            q[o] = acc[o] >= 0.f ? acc[o] : acc[o] * layer.slopes[o];
                    else
                        for (int o = 0; o < Out; ++o)
                            q[o] = acc[o];
                }
        }

        inline int wrap(int v, int size)
        {
            v %= size;
            return v < 0 ? v + size : v;
        }

        // the input picture's channel c (0..3) at (x, y) bilinearly at twice the size, wrapping
        inline float bilinear(const std::vector<uint8_t>& src, int w, int h, int ox, int oy, int c)
        {
            const float px = ox * 0.5f - 0.25f, py = oy * 0.5f - 0.25f;
            const int fx = static_cast<int>(std::floor(px)), fy = static_cast<int>(std::floor(py));
            const float tx = px - fx, ty = py - fy;
            auto at = [&](int x, int y) { return static_cast<float>(src[(static_cast<size_t>(wrap(y, h)) * w + wrap(x, w)) * 4 + c]); };
            return (at(fx, fy) * (1.f - tx) + at(fx + 1, fy) * tx) * (1.f - ty)
                + (at(fx, fy + 1) * (1.f - tx) + at(fx + 1, fy + 1) * tx) * ty;
        }

        void upscaleTile(const std::vector<uint8_t>& src, int w, int h, int tx0, int ty0, std::vector<uint8_t>& dst,
            std::vector<float>& a, std::vector<float>& b)
        {
            const int tw = std::min(kTile, w - tx0), th = std::min(kTile, h - ty0);
            int cw = tw + 2 * kBorder, ch = th + 2 * kBorder;
            a.resize(static_cast<size_t>(cw) * ch * kFeatures);
            b.resize(static_cast<size_t>(cw) * ch * kFeatures);
            for (int y = 0; y < ch; ++y)
                for (int x = 0; x < cw; ++x)
                {
                    const uint8_t* p = &src[(static_cast<size_t>(wrap(ty0 + y - kBorder, h)) * w + wrap(tx0 + x - kBorder, w)) * 4];
                    float* q = &a[(static_cast<size_t>(y) * cw + x) * 3];
                    for (int c = 0; c < 3; ++c)
                        q[c] = p[c] * (1.f / 255.f);
                }
            const std::vector<Layer>& net = layers();
            for (size_t i = 0; i < net.size(); ++i)
            {
                if (net[i].out == kFeatures)
                    convolve<kFeatures>(net[i], a.data(), cw, ch, b.data());
                else
                    convolve<12>(net[i], a.data(), cw, ch, b.data());
                cw -= 2;
                ch -= 2;
                std::swap(a, b);
            }
            // a: th x tw x 12; channel c * 4 + dy * 2 + dx is colour c of the pixel (2x + dx, 2y + dy) (a pixel shuffle)
            const int ow = w * 2;
            for (int y = 0; y < th; ++y)
                for (int x = 0; x < tw; ++x)
                {
                    const float* r = &a[(static_cast<size_t>(y) * tw + x) * 12];
                    for (int dy = 0; dy < 2; ++dy)
                        for (int dx = 0; dx < 2; ++dx)
                        {
                            const int ox = (tx0 + x) * 2 + dx, oy = (ty0 + y) * 2 + dy;
                            uint8_t* out = &dst[(static_cast<size_t>(oy) * ow + ox) * 4];
                            for (int c = 0; c < 3; ++c)
                            {
                                const float v = bilinear(src, w, h, ox, oy, c) + r[c * 4 + dy * 2 + dx] * 255.f;
                                out[c] = static_cast<uint8_t>(std::clamp(v, 0.f, 255.f) + 0.5f);
                            }
                            out[3] = static_cast<uint8_t>(std::clamp(bilinear(src, w, h, ox, oy, 3), 0.f, 255.f) + 0.5f);
                        }
                }
        }
    }

    void aiUpscale2x(const std::vector<uint8_t>& src, int w, int h, std::vector<uint8_t>& dst, unsigned threads)
    {
        dst.resize(static_cast<size_t>(w) * 2 * h * 2 * 4);
        const int tilesX = (w + kTile - 1) / kTile, tilesY = (h + kTile - 1) / kTile, tiles = tilesX * tilesY;
        std::atomic<int> next{ 0 };
        auto work = [&] {
            std::vector<float> a, b;
            for (int t; (t = next++) < tiles;)
                upscaleTile(src, w, h, (t % tilesX) * kTile, (t / tilesX) * kTile, dst, a, b);
        };
        const unsigned helpers = std::min<unsigned>(std::max(1u, threads), static_cast<unsigned>(tiles)) - 1;
        std::vector<std::thread> pool;
        for (unsigned i = 0; i < helpers; ++i)
            pool.emplace_back(work);
        work();
        for (std::thread& thread : pool)
            thread.join();
    }
}
#endif

namespace Alpha3
{
    bool aiUpscaleAvailable()
    {
        return AI_UPSCALE_AVAILABLE != 0;
    }

    const char* aiUpscaleVersion()
    {
        return AiUpscaleWeights::kVersion;
    }

#if !AI_UPSCALE_AVAILABLE
    void aiUpscale2x(const std::vector<uint8_t>&, int, int, std::vector<uint8_t>& dst, unsigned)
    {
        dst.clear();
    }
#endif
}
