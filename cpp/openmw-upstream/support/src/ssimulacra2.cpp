// SSIMULACRA2 (see ssimulacra2.hpp). Ported from cloudinary/ssimulacra2 src/ssimulacra2.cc and libjxl's XYB transform;
// BSD-3-Clause, Copyright (c) Cloudinary (license text in 3rdparty-licenses.txt).
#include "ssimulacra2.hpp"

#include <algorithm>
#include <array>
#include <cmath>
#include <vector>

namespace Alpha3
{
    namespace
    {
        constexpr int kNumScales = 6;
        constexpr float kC2 = 0.0009f;

        // three float planes
        struct Image3
        {
            int w = 0, h = 0;
            std::array<std::vector<float>, 3> p;

            void resize(int width, int height)
            {
                w = width;
                h = height;
                for (auto& plane : p)
                    plane.assign(static_cast<size_t>(w) * h, 0.f);
            }
        };

        float srgbToLinear(float v)
        {
            return v <= 0.04045f ? v / 12.92f : std::pow((v + 0.055f) / 1.055f, 2.4f);
        }

        const std::array<float, 256>& linearTable()
        {
            static const std::array<float, 256> table = [] {
                std::array<float, 256> t{};
                for (int i = 0; i < 256; ++i)
                    t[i] = srgbToLinear(i / 255.f);
                return t;
            }();
            return table;
        }

        // 8-bit sRGB to linear RGB. Colour is blended over 0.5 by alpha in the encoded values, before linearising, as
        // the reference tool's AlphaBlend does.
        void toLinear(const uint8_t* rgba, int w, int h, bool greyFromAlpha, Image3& out)
        {
            out.resize(w, h);
            const auto& table = linearTable();
            const size_t n = static_cast<size_t>(w) * h;
            for (size_t i = 0; i < n; ++i)
            {
                const uint8_t* px = rgba + i * 4;
                if (greyFromAlpha)
                {
                    const float v = table[px[3]];
                    out.p[0][i] = out.p[1][i] = out.p[2][i] = v;
                    continue;
                }
                const float a = px[3] / 255.f;
                for (int c = 0; c < 3; ++c)
                    out.p[c][i] = srgbToLinear(a * (px[c] / 255.f) + (1.f - a) * 0.5f);
            }
        }

        // libjxl's opsin absorbance (lib/jxl/opsin_params.h) and the rescaling of MakePositiveXYB
        void linearToPositiveXyb(const Image3& in, Image3& out)
        {
            constexpr float m00 = 0.30f, m02 = 0.078f, m01 = 1.f - m02 - m00;
            constexpr float m10 = 0.23f, m12 = 0.078f, m11 = 1.f - m12 - m10;
            constexpr float m20 = 0.24342268924547819f, m21 = 0.20476744424496821f, m22 = 1.f - m20 - m21;
            constexpr float bias = 0.0037930732552754493f;
            static const float biasCbrt = std::cbrt(bias);
            out.resize(in.w, in.h);
            const size_t n = static_cast<size_t>(in.w) * in.h;
            for (size_t i = 0; i < n; ++i)
            {
                const float r = in.p[0][i], g = in.p[1][i], b = in.p[2][i];
                const float mix0 = std::cbrt(std::max(0.f, m00 * r + m01 * g + m02 * b + bias)) - biasCbrt;
                const float mix1 = std::cbrt(std::max(0.f, m10 * r + m11 * g + m12 * b + bias)) - biasCbrt;
                const float mix2 = std::cbrt(std::max(0.f, m20 * r + m21 * g + m22 * b + bias)) - biasCbrt;
                const float x = 0.5f * (mix0 - mix1), y = 0.5f * (mix0 + mix1), bb = mix2;
                out.p[0][i] = x * 14.f + 0.42f;
                out.p[1][i] = y + 0.01f;
                out.p[2][i] = (bb - y) + 0.55f;
            }
        }

        // a 2x2 box, the last row/column repeated for odd sizes (the reference's Downsample)
        void downsample(const Image3& in, Image3& out)
        {
            const int ow = (in.w + 1) / 2, oh = (in.h + 1) / 2;
            out.resize(ow, oh);
            for (int c = 0; c < 3; ++c)
                for (int y = 0; y < oh; ++y)
                    for (int x = 0; x < ow; ++x)
                    {
                        float sum = 0.f;
                        for (int iy = 0; iy < 2; ++iy)
                            for (int ix = 0; ix < 2; ++ix)
                            {
                                const int sx = std::min(x * 2 + ix, in.w - 1), sy = std::min(y * 2 + iy, in.h - 1);
                                sum += in.p[c][static_cast<size_t>(sy) * in.w + sx];
                            }
                        out.p[c][static_cast<size_t>(y) * ow + x] = sum * 0.25f;
                    }
        }

        constexpr int kRadius = 5;

        const std::array<float, 2 * kRadius + 1>& kernel()
        {
            static const std::array<float, 2 * kRadius + 1> k = [] {
                std::array<float, 2 * kRadius + 1> r{};
                float sum = 0.f;
                for (int i = -kRadius; i <= kRadius; ++i)
                    sum += r[i + kRadius] = std::exp(-0.5f * i * i / (1.5f * 1.5f));
                for (float& v : r)
                    v /= sum;
                return r;
            }();
            return k;
        }

        // separable Gaussian, sigma 1.5; outside the image counts as 0 (as libjxl's recursive Gaussian does)
        void blurPlane(const std::vector<float>& in, int w, int h, std::vector<float>& temp, std::vector<float>& out)
        {
            const auto& k = kernel();
            temp.resize(static_cast<size_t>(w) * h);
            out.resize(static_cast<size_t>(w) * h);
            for (int y = 0; y < h; ++y)
            {
                const float* row = in.data() + static_cast<size_t>(y) * w;
                float* dst = temp.data() + static_cast<size_t>(y) * w;
                for (int x = 0; x < w; ++x)
                {
                    const int lo = std::max(-kRadius, -x), hi = std::min(kRadius, w - 1 - x);
                    float sum = 0.f;
                    for (int i = lo; i <= hi; ++i)
                        sum += k[i + kRadius] * row[x + i];
                    dst[x] = sum;
                }
            }
            for (int y = 0; y < h; ++y)
            {
                float* dst = out.data() + static_cast<size_t>(y) * w;
                const int lo = std::max(-kRadius, -y), hi = std::min(kRadius, h - 1 - y);
                std::fill(dst, dst + w, 0.f);
                for (int i = lo; i <= hi; ++i)
                {
                    const float weight = k[i + kRadius];
                    const float* src = temp.data() + static_cast<size_t>(y + i) * w;
                    for (int x = 0; x < w; ++x)
                        dst[x] += weight * src[x];
                }
            }
        }

        void blur(const Image3& in, std::vector<float>& temp, Image3& out)
        {
            out.w = in.w;
            out.h = in.h;
            for (int c = 0; c < 3; ++c)
                blurPlane(in.p[c], in.w, in.h, temp, out.p[c]);
        }

        void multiply(const Image3& a, const Image3& b, Image3& out)
        {
            out.resize(a.w, a.h);
            for (int c = 0; c < 3; ++c)
                for (size_t i = 0; i < a.p[c].size(); ++i)
                    out.p[c][i] = a.p[c][i] * b.p[c][i];
        }

        double tothe4th(double x)
        {
            x *= x;
            return x * x;
        }

        struct ScaleScores
        {
            double ssim[6] = {};
            double edge[12] = {};
        };

        // the reference's Msssim::Score
        double finalScore(const std::vector<ScaleScores>& scales)
        {
            static constexpr double weight[108] = { 0.0, 0.0007376606707406586, 0.0, 0.0, 0.0007793481682867309, 0.0,
                0.0, 0.0004371155730107379, 0.0, 1.1041726426657346, 0.00066284834129271, 0.00015231632783718752, 0.0,
                0.0016406437456599754, 0.0, 1.8422455520539298, 11.441172603757666, 0.0, 0.0007989109436015163,
                0.000176816438078653, 0.0, 1.8787594979546387, 10.94906990605142, 0.0, 0.0007289346991508072,
                0.9677937080626833, 0.0, 0.00014003424285435884, 0.9981766977854967, 0.00031949755934435053,
                0.0004550992113792063, 0.0, 0.0, 0.0013648766163243398, 0.0, 0.0, 0.0, 0.0, 0.0, 7.466890328078848, 0.0,
                17.445833984131262, 0.0006235601634041466, 0.0, 0.0, 6.683678146179332, 0.00037724407979611296,
                1.027889937768264, 225.20515300849274, 0.0, 0.0, 19.213238186143016, 0.0011401524586618361,
                0.001237755635509985, 176.39317598450694, 0.0, 0.0, 24.43300999870476, 0.28520802612117757,
                0.0004485436923833408, 0.0, 0.0, 0.0, 34.77906344483772, 44.835625328877896, 0.0, 0.0, 0.0, 0.0, 0.0,
                0.0, 0.0, 0.0, 0.0008680556573291698, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0005313191874358747, 0.0,
                0.00016533814161379112, 0.0, 0.0, 0.0, 0.0, 0.0, 0.0004179171803251336, 0.0017290828234722833, 0.0,
                0.0020827005846636437, 0.0, 0.0, 8.826982764996862, 23.19243343998926, 0.0, 95.1080498811086,
                0.9863978034400682, 0.9834382792465353, 0.0012286405048278493, 171.2667255897307, 0.9807858872435379,
                0.0, 0.0, 0.0, 0.0005130064588990679, 0.0, 0.00010854057858411537 };
            double ssim = 0.0;
            size_t i = 0;
            for (int c = 0; c < 3; ++c)
                for (const ScaleScores& s : scales)
                    for (int n = 0; n < 2; ++n)
                    {
                        ssim += weight[i++] * std::abs(s.ssim[c * 2 + n]);
                        ssim += weight[i++] * std::abs(s.edge[c * 4 + n]);
                        ssim += weight[i++] * std::abs(s.edge[c * 4 + n + 2]);
                    }
            ssim *= 0.9562382616834844;
            ssim = 2.326765642916932 * ssim - 0.020884521182843837 * ssim * ssim
                + 6.248496625763138e-05 * ssim * ssim * ssim;
            return ssim > 0 ? 100.0 - 10.0 * std::pow(ssim, 0.6276336467831387) : 100.0;
        }
    }

    // what is kept of the reference picture per scale: its XYB picture, blurred picture and blurred square
    struct Ssimulacra2Reference::Data
    {
        bool greyFromAlpha = false;
        int width = 0, height = 0;
        std::vector<Image3> xyb, mu, sigmaSq;
    };

    Ssimulacra2Reference::Ssimulacra2Reference(const uint8_t* rgba, int width, int height, bool greyFromAlpha)
        : mData(std::make_unique<Data>())
    {
        Data& d = *mData;
        d.greyFromAlpha = greyFromAlpha;
        d.width = width;
        d.height = height;
        Image3 linear, next, xyb, mul, out;
        std::vector<float> temp;
        toLinear(rgba, width, height, greyFromAlpha, linear);
        for (int scale = 0; scale < kNumScales; ++scale)
        {
            if (scale)
            {
                downsample(linear, next);
                std::swap(linear, next);
            }
            if (linear.w < 8 || linear.h < 8)
                break;
            linearToPositiveXyb(linear, xyb);
            multiply(xyb, xyb, mul);
            blur(mul, temp, out);
            d.sigmaSq.push_back(out);
            blur(xyb, temp, out);
            d.mu.push_back(out);
            d.xyb.push_back(xyb);
        }
    }

    Ssimulacra2Reference::~Ssimulacra2Reference() = default;

    bool Ssimulacra2Reference::valid() const
    {
        return !mData->xyb.empty();
    }

    double Ssimulacra2Reference::score(const uint8_t* rgba) const
    {
        const Data& d = *mData;
        if (d.xyb.empty())
            return 100.0;
        Image3 linear, next, xyb2, mul, sigma22, sigma12, mu2;
        std::vector<float> temp;
        toLinear(rgba, d.width, d.height, d.greyFromAlpha, linear);
        std::vector<ScaleScores> scales;
        for (size_t scale = 0; scale < d.xyb.size(); ++scale)
        {
            if (scale)
            {
                downsample(linear, next);
                std::swap(linear, next);
            }
            const Image3& xyb1 = d.xyb[scale];
            const Image3& mu1 = d.mu[scale];
            const Image3& sigma11 = d.sigmaSq[scale];
            linearToPositiveXyb(linear, xyb2);
            multiply(xyb2, xyb2, mul);
            blur(mul, temp, sigma22);
            multiply(xyb1, xyb2, mul);
            blur(mul, temp, sigma12);
            blur(xyb2, temp, mu2);

            ScaleScores s;
            const double onePerPixels = 1.0 / (static_cast<double>(xyb1.w) * xyb1.h);
            for (int c = 0; c < 3; ++c)
            {
                double ssimSum[2] = {}, edgeSum[4] = {};
                for (size_t i = 0; i < xyb1.p[c].size(); ++i)
                {
                    const float m1 = mu1.p[c][i], m2 = mu2.p[c][i];
                    const float numM = 1.f - (m1 - m2) * (m1 - m2);
                    const float numS = 2 * (sigma12.p[c][i] - m1 * m2) + kC2;
                    const float denomS = (sigma11.p[c][i] - m1 * m1) + (sigma22.p[c][i] - m2 * m2) + kC2;
                    const double dSsim = std::max(1.0 - (numM * numS / denomS), 0.0);
                    ssimSum[0] += dSsim;
                    ssimSum[1] += tothe4th(dSsim);

                    const double d1 = (1.0 + std::abs(xyb2.p[c][i] - m2)) / (1.0 + std::abs(xyb1.p[c][i] - m1)) - 1.0;
                    const double artifact = std::max(d1, 0.0), detailLost = std::max(-d1, 0.0);
                    edgeSum[0] += artifact;
                    edgeSum[1] += tothe4th(artifact);
                    edgeSum[2] += detailLost;
                    edgeSum[3] += tothe4th(detailLost);
                }
                s.ssim[c * 2] = onePerPixels * ssimSum[0];
                s.ssim[c * 2 + 1] = std::sqrt(std::sqrt(onePerPixels * ssimSum[1]));
                s.edge[c * 4] = onePerPixels * edgeSum[0];
                s.edge[c * 4 + 1] = std::sqrt(std::sqrt(onePerPixels * edgeSum[1]));
                s.edge[c * 4 + 2] = onePerPixels * edgeSum[2];
                s.edge[c * 4 + 3] = std::sqrt(std::sqrt(onePerPixels * edgeSum[3]));
            }
            scales.push_back(s);
        }
        return finalScore(scales);
    }
}
