// DXT (S3TC) textures converted to ASTC or ETC2 when they are read, for GPUs without S3TC (moved from OpenMW's
// components/resource/astccache.cpp). The converted textures are kept in one cache file, keyed by their texels.
#include "support.hpp"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <cstring>
#include <filesystem>
#include <fstream>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <vector>

#include <osg/Texture>

#include <components/debug/debuglog.hpp>

#include "blobstore.hpp"

#include <astcenc.h>
#include <ProcessRGB.hpp>

namespace Alpha3
{
    namespace
    {
        constexpr GLenum DXT1_RGB = 0x83F0, DXT1_RGBA = 0x83F1, DXT3_RGBA = 0x83F2, DXT5_RGBA = 0x83F3;
        constexpr GLenum ETC2_RGB8 = 0x9274, ETC2_RGBA8_EAC = 0x9278;

        struct Settings
        {
            bool enabled = true;
            bool etc2 = false;
            unsigned blockX = 6, blockY = 6;
            float quality = ASTCENC_PRE_FAST;
            int maxSize = 4096;
            unsigned threads = 1;
            GLenum internalFormat = GL_COMPRESSED_RGBA_ASTC_6x6_KHR;
            std::filesystem::path dir;
            std::string tag;
            // Every converted texture in one file, astc_cache.bin (ASTC and ETC2 entries are told apart by the key)
            std::shared_ptr<BlobStore> store;
        };

        GLenum astcFormat(unsigned x, unsigned y)
        {
            struct Entry { unsigned x, y; GLenum format; };
            static const Entry table[] = {
                { 4, 4, GL_COMPRESSED_RGBA_ASTC_4x4_KHR }, { 5, 4, GL_COMPRESSED_RGBA_ASTC_5x4_KHR },
                { 5, 5, GL_COMPRESSED_RGBA_ASTC_5x5_KHR }, { 6, 5, GL_COMPRESSED_RGBA_ASTC_6x5_KHR },
                { 6, 6, GL_COMPRESSED_RGBA_ASTC_6x6_KHR }, { 8, 5, GL_COMPRESSED_RGBA_ASTC_8x5_KHR },
                { 8, 6, GL_COMPRESSED_RGBA_ASTC_8x6_KHR }, { 8, 8, GL_COMPRESSED_RGBA_ASTC_8x8_KHR },
                { 10, 5, GL_COMPRESSED_RGBA_ASTC_10x5_KHR }, { 10, 6, GL_COMPRESSED_RGBA_ASTC_10x6_KHR },
                { 10, 8, GL_COMPRESSED_RGBA_ASTC_10x8_KHR }, { 10, 10, GL_COMPRESSED_RGBA_ASTC_10x10_KHR },
                { 12, 10, GL_COMPRESSED_RGBA_ASTC_12x10_KHR }, { 12, 12, GL_COMPRESSED_RGBA_ASTC_12x12_KHR },
            };
            for (const Entry& e : table)
                if (e.x == x && e.y == y)
                    return e.format;
            return 0;
        }

        const Settings& settings()
        {
            static const Settings s = [] {
                Settings r;
                r.enabled = settingBool("Textures", "enable texture conversion", false);
                r.etc2 = setting("Textures", "texture conversion format", "astc") == "etc2";
                unsigned x = 0, y = 0;
                if (sscanf(setting("Textures", "astc block size", "6x6").c_str(), "%ux%u", &x, &y) == 2
                    && astcFormat(x, y) != 0)
                {
                    r.blockX = x;
                    r.blockY = y;
                }
                r.internalFormat = astcFormat(r.blockX, r.blockY);
                r.quality = std::clamp(settingFloat("Textures", "astc quality", 10.f), 0.f, 100.f);
                r.maxSize = std::clamp(settingInt("Textures", "astc max size", 4096), 64, 16384);
                const unsigned cores = std::max(1u, std::thread::hardware_concurrency());
                const int wanted = std::clamp(settingInt("Textures", "astc threads", 0), 0, 64);
                r.threads = wanted > 0 ? static_cast<unsigned>(wanted) : cores;
                const std::string dir = setting("Textures", "astc cache path", "");
                r.dir = dir.empty() ? cacheDirectory() / "astc" : std::filesystem::path(dir);
                r.tag = r.etc2 ? std::string("etc2bgra") : std::to_string(r.blockX) + "x" + std::to_string(r.blockY) + "q"
                    + std::to_string(static_cast<int>(r.quality)) + "m" + std::to_string(r.maxSize);
                if (r.enabled)
                {
                    std::error_code ec;
                    if (r.dir.empty())
                        r.enabled = false;
                    else
                        std::filesystem::create_directories(r.dir, ec);
                    if (ec)
                        r.enabled = false;
                    if (r.enabled)
                    {
                        auto store = std::make_shared<BlobStore>(r.dir / "astc_cache.bin", 0x53413341, 1); // "A3AS"
                        if (store->valid())
                            r.store = std::move(store);
                    }
                }
                if (r.etc2)
                    Log(Debug::Info) << "Texture conversion " << (r.enabled ? "on" : "off") << ": ETC2, " << r.threads
                                     << " threads, textures larger than " << r.maxSize
                                     << " px start at a smaller mip level, cache " << r.dir.string();
                else
                    Log(Debug::Info) << "Texture conversion " << (r.enabled ? "on" : "off") << ": ASTC block "
                                     << r.blockX << "x" << r.blockY << ", quality " << r.quality << ", " << r.threads
                                     << " threads, textures larger than " << r.maxSize
                                     << " px start at a smaller mip level, cache " << r.dir.string();
                return r;
            }();
            return s;
        }

        // ---- DXT decoding --------------------------------------------------------------------------------------

        inline void unpack565(uint16_t c, uint8_t* rgb)
        {
            rgb[0] = static_cast<uint8_t>(((c >> 11) & 31) * 255 / 31);
            rgb[1] = static_cast<uint8_t>(((c >> 5) & 63) * 255 / 63);
            rgb[2] = static_cast<uint8_t>((c & 31) * 255 / 31);
        }

        // 8 bytes of colour endpoints and indices; opaque4 forces the four-colour mode (DXT3/5 always use it)
        void decodeColour(const uint8_t* b, bool opaque4, bool rgbOnly, uint8_t out[16][4])
        {
            const uint16_t c0 = static_cast<uint16_t>(b[0] | (b[1] << 8));
            const uint16_t c1 = static_cast<uint16_t>(b[2] | (b[3] << 8));
            uint8_t pal[4][4];
            unpack565(c0, pal[0]);
            unpack565(c1, pal[1]);
            pal[0][3] = pal[1][3] = 255;
            if (c0 > c1 || opaque4)
            {
                for (int i = 0; i < 3; ++i)
                {
                    pal[2][i] = static_cast<uint8_t>((2 * pal[0][i] + pal[1][i]) / 3);
                    pal[3][i] = static_cast<uint8_t>((pal[0][i] + 2 * pal[1][i]) / 3);
                }
                pal[2][3] = pal[3][3] = 255;
            }
            else
            {
                for (int i = 0; i < 3; ++i)
                {
                    pal[2][i] = static_cast<uint8_t>((pal[0][i] + pal[1][i]) / 2);
                    pal[3][i] = 0;
                }
                pal[2][3] = 255;
                pal[3][3] = rgbOnly ? 255 : 0;
            }
            uint32_t idx = b[4] | (b[5] << 8) | (b[6] << 16) | (static_cast<uint32_t>(b[7]) << 24);
            for (int i = 0; i < 16; ++i, idx >>= 2)
                std::memcpy(out[i], pal[idx & 3], 4);
        }

        void decodeAlpha5(const uint8_t* b, uint8_t out[16])
        {
            uint8_t a[8];
            a[0] = b[0];
            a[1] = b[1];
            if (a[0] > a[1])
                for (int i = 1; i < 7; ++i)
                    a[1 + i] = static_cast<uint8_t>(((7 - i) * a[0] + i * a[1]) / 7);
            else
            {
                for (int i = 1; i < 5; ++i)
                    a[1 + i] = static_cast<uint8_t>(((5 - i) * a[0] + i * a[1]) / 5);
                a[6] = 0;
                a[7] = 255;
            }
            uint64_t bits = 0;
            for (int i = 0; i < 6; ++i)
                bits |= static_cast<uint64_t>(b[2 + i]) << (8 * i);
            for (int i = 0; i < 16; ++i, bits >>= 3)
                out[i] = a[bits & 7];
        }

        // One mip level of DXT data to tightly packed RGBA8
        void decodeLevel(GLenum format, const uint8_t* src, int w, int h, std::vector<uint8_t>& rgba)
        {
            const bool dxt1 = format == DXT1_RGB || format == DXT1_RGBA;
            const size_t blockBytes = dxt1 ? 8 : 16;
            const int bw = (w + 3) / 4, bh = (h + 3) / 4;
            rgba.assign(static_cast<size_t>(w) * h * 4, 255);
            uint8_t px[16][4];
            uint8_t alpha[16];
            for (int by = 0; by < bh; ++by)
                for (int bx = 0; bx < bw; ++bx)
                {
                    const uint8_t* b = src + (static_cast<size_t>(by) * bw + bx) * blockBytes;
                    if (dxt1)
                        decodeColour(b, false, format == DXT1_RGB, px);
                    else
                    {
                        decodeColour(b + 8, true, false, px);
                        if (format == DXT3_RGBA)
                            for (int i = 0; i < 16; ++i)
                            {
                                const uint8_t n = (b[i / 2] >> ((i & 1) * 4)) & 15;
                                px[i][3] = static_cast<uint8_t>(n * 17);
                            }
                        else
                        {
                            decodeAlpha5(b, alpha);
                            for (int i = 0; i < 16; ++i)
                                px[i][3] = alpha[i];
                        }
                    }
                    for (int y = 0; y < 4 && by * 4 + y < h; ++y)
                        for (int x = 0; x < 4 && bx * 4 + x < w; ++x)
                            std::memcpy(&rgba[((static_cast<size_t>(by) * 4 + y) * w + bx * 4 + x) * 4], px[y * 4 + x], 4);
                }
        }

        // ---- encoding ------------------------------------------------------------------------------------------

        struct Encoder
        {
            astcenc_context* context = nullptr;
            ~Encoder()
            {
                if (context)
                    astcenc_context_free(context);
            }
        };

        astcenc_context* makeContext(const Settings& s, unsigned threads)
        {
            astcenc_config config;
            if (astcenc_config_init(ASTCENC_PRF_LDR, s.blockX, s.blockY, 1, s.quality, 0, &config) != ASTCENC_SUCCESS)
                return nullptr;
            astcenc_context* context = nullptr;
            return astcenc_context_alloc(&config, threads, &context) == ASTCENC_SUCCESS ? context : nullptr;
        }

        // Small images are not worth waking the other cores for, so each loader thread has its own single-thread context
        astcenc_context* threadContext(const Settings& s)
        {
            thread_local Encoder encoder;
            if (!encoder.context)
                encoder.context = makeContext(s, 1);
            return encoder.context;
        }

        // One image at a time is split between all the threads: the cores work on the same texture, so it is done as
        // soon as possible whichever loader thread asked for it
        bool compressParallel(const Settings& s, astcenc_image& image, const astcenc_swizzle& swizzle, uint8_t* out,
            size_t size)
        {
            static std::mutex sMutex;
            static Encoder sEncoder;
            std::lock_guard lock(sMutex);
            if (!sEncoder.context)
                sEncoder.context = makeContext(s, s.threads);
            astcenc_context* context = sEncoder.context;
            if (!context)
                return false;
            astcenc_compress_reset(context);
            std::atomic<bool> ok{ true };
            auto work = [&](unsigned index) {
                if (astcenc_compress_image(context, &image, &swizzle, out, size, index) != ASTCENC_SUCCESS)
                    ok = false;
            };
            std::vector<std::thread> helpers;
            for (unsigned i = 1; i < s.threads; ++i)
                helpers.emplace_back(work, i);
            work(0);
            for (std::thread& helper : helpers)
                helper.join();
            return ok;
        }

        size_t astcBytes(const Settings& s, int w, int h)
        {
            return static_cast<size_t>((w + s.blockX - 1) / s.blockX) * ((h + s.blockY - 1) / s.blockY) * 16;
        }

        size_t etc2Bytes(int w, int h, bool alpha)
        {
            return static_cast<size_t>((w + 3) / 4) * ((h + 3) / 4) * (alpha ? 16 : 8);
        }

        // etcpak (ETC2 RGB8, or RGBA8 with the EAC alpha block first, the layout OpenGL expects) takes BGRA pixels in
        // blocks of 4x4, so red and blue are swapped on the way in and the image is padded to a multiple of 4 by
        // repeating its edge. A big level is cut into rows of blocks that the threads encode side by side.
        void encodeEtc2(const uint8_t* rgba, int w, int h, bool alpha, uint8_t* out, unsigned threads)
        {
            const int pw = (w + 3) & ~3, ph = (h + 3) & ~3;
            std::vector<uint32_t> padded(static_cast<size_t>(pw) * ph);
            for (int y = 0; y < ph; ++y)
                for (int x = 0; x < pw; ++x)
                {
                    const uint8_t* in = rgba + (static_cast<size_t>(std::min(y, h - 1)) * w + std::min(x, w - 1)) * 4;
                    const uint8_t bgra[4] = { in[2], in[1], in[0], in[3] };
                    std::memcpy(&padded[static_cast<size_t>(y) * pw + x], bgra, 4);
                }
            const uint32_t blockColumns = static_cast<uint32_t>(pw / 4), blockRows = static_cast<uint32_t>(ph / 4);
            const size_t blockBytes = alpha ? 16 : 8;
            auto encodeRows = [&](uint32_t firstRow, uint32_t rows) {
                const uint32_t* src = padded.data() + static_cast<size_t>(firstRow) * 4 * pw;
                uint64_t* dst = reinterpret_cast<uint64_t*>(out + static_cast<size_t>(firstRow) * blockColumns * blockBytes);
                if (alpha)
                    CompressEtc2Rgba(src, dst, blockColumns * rows, static_cast<size_t>(pw), true);
                else
                    CompressEtc2Rgb(src, dst, blockColumns * rows, static_cast<size_t>(pw), true);
            };
            const unsigned chunks = (threads > 1 && static_cast<size_t>(w) * h >= 256 * 256)
                ? std::min<unsigned>(threads, blockRows) : 1;
            std::vector<std::thread> helpers;
            for (unsigned i = 1; i < chunks; ++i)
            {
                const uint32_t first = blockRows * i / chunks, last = blockRows * (i + 1) / chunks;
                helpers.emplace_back(encodeRows, first, last - first);
            }
            encodeRows(0, blockRows / chunks);
            for (std::thread& helper : helpers)
                helper.join();
        }

        uint64_t fnv(const uint8_t* data, size_t size, uint64_t h = 1469598103934665603ull)
        {
            for (size_t i = 0; i < size; ++i)
                h = (h ^ data[i]) * 1099511628211ull;
            return h;
        }

        // The key of a texture is a hash of its first level, so it is worked out on every load, cached or not. A byte at a
        // time (fnv above) that is slow enough to show up on a profile: this one reads 8 bytes per step on four
        // independent lanes. Keys made by the byte-wise hash are still found and renamed (see transcodeDxt).
        uint64_t fastHash(const uint8_t* data, size_t size, uint64_t seed)
        {
            constexpr uint64_t k1 = 0x9E3779B185EBCA87ull, k2 = 0xC2B2AE3D27D4EB4Full;
            auto rotl = [](uint64_t v, int r) { return (v << r) | (v >> (64 - r)); };
            uint64_t lane[4] = { seed + k1 + k2, seed + k2, seed, seed - k1 };
            size_t i = 0;
            for (; i + 32 <= size; i += 32)
                for (int l = 0; l < 4; ++l)
                {
                    uint64_t word;
                    std::memcpy(&word, data + i + l * 8, 8);
                    lane[l] = rotl(lane[l] + word * k2, 31) * k1;
                }
            uint64_t h = rotl(lane[0], 1) + rotl(lane[1], 7) + rotl(lane[2], 12) + rotl(lane[3], 18) + size;
            for (; i + 8 <= size; i += 8)
            {
                uint64_t word;
                std::memcpy(&word, data + i, 8);
                h = rotl(h ^ (rotl(word * k2, 31) * k1), 27) * k1 + k2;
            }
            for (; i < size; ++i)
                h = rotl(h ^ (data[i] * 0x9E3779B1ull), 11) * k1;
            h ^= h >> 33;
            h *= k2;
            h ^= h >> 29;
            return h;
        }

        struct CacheHeader
        {
            char magic[4];
            uint32_t version, width, height, levels, format, total;
        };

        std::atomic<unsigned> sHits{ 0 }, sEncoded{ 0 }, sFailed{ 0 };
        std::atomic<uint64_t> sEncodeMicros{ 0 }, sSourceBytes{ 0 }, sAstcBytes{ 0 };

        std::atomic<int64_t> sLastReportMs{ 0 };
        std::atomic<unsigned> sSlow{ 0 };

        int64_t nowMs()
        {
            return std::chrono::duration_cast<std::chrono::milliseconds>(
                std::chrono::steady_clock::now().time_since_epoch()).count();
        }

        void report(const char* what)
        {
            const unsigned encoded = sEncoded, hits = sHits;
            const uint64_t src = sSourceBytes, out = sAstcBytes;
            const char* format = settings().etc2 ? "ETC2" : "ASTC";
            Log(Debug::Info) << format << " " << what << ": " << encoded << " converted (" << sEncodeMicros / 1000
                             << " ms of encoding, " << (encoded ? sEncodeMicros / 1000 / encoded : 0) << " ms each, "
                             << sSlow << " over 150 ms), " << hits << " loaded from the cache, " << sFailed
                             << " failed; " << src / (1024 * 1024) << " MB of DXT -> " << out / (1024 * 1024) << " MB of "
                             << format;
        }

        // A progress line every 5 seconds while textures are being converted or read back
        void maybeReport()
        {
            const int64_t now = nowMs();
            int64_t last = sLastReportMs;
            if (now - last >= 5000 && sLastReportMs.compare_exchange_strong(last, now))
                report("progress");
        }

        // the image takes over buffer, which was allocated with new[]
        osg::ref_ptr<osg::Image> fromOwnedBuffer(GLenum internalFormat, int w, int h, const std::vector<uint32_t>& sizes,
            unsigned char* buffer)
        {
            osg::ref_ptr<osg::Image> out = new osg::Image;
            out->setImage(w, h, 1, internalFormat, internalFormat, GL_UNSIGNED_BYTE, buffer,
                osg::Image::USE_NEW_DELETE);
            if (sizes.size() > 1)
            {
                osg::Image::MipmapDataType offsets;
                size_t offset = 0;
                for (size_t i = 0; i + 1 < sizes.size(); ++i)
                {
                    offset += sizes[i];
                    offsets.push_back(static_cast<unsigned>(offset));
                }
                out->setMipmapLevels(offsets);
            }
            out->setOrigin(osg::Image::TOP_LEFT);
            return out;
        }

        osg::ref_ptr<osg::Image> fromBuffer(GLenum internalFormat, int w, int h, const std::vector<uint32_t>& sizes,
            const uint8_t* data, size_t total)
        {
            unsigned char* buffer = new unsigned char[total];
            std::memcpy(buffer, data, total);
            return fromOwnedBuffer(internalFormat, w, h, sizes, buffer);
        }
    }

    bool textureConversionUsesEtc2()
    {
        return settings().etc2;
    }

    bool textureConversionEnabled()
    {
        return settings().enabled;
    }

    TextureConversionCounts textureConversionCounts()
    {
        return { sEncoded, sHits, sFailed };
    }

    osg::ref_ptr<osg::Image> transcodeDxt(const osg::Image& image, std::string_view path)
    {
        const GLenum format = image.getPixelFormat();
        if (format != DXT1_RGB && format != DXT1_RGBA && format != DXT3_RGBA && format != DXT5_RGBA)
            return nullptr;
        const Settings& s = settings();
        if (!s.enabled || image.r() != 1)
            return nullptr;

        // Very large textures are encoded smaller: 8192x8192 would take minutes and a quarter gigabyte of RAM. They start
        // at a smaller mip level, or, with no mip levels (or too few), are halved after decoding.
        const int w0 = image.s(), h0 = image.t();
        const unsigned sourceLevels = std::max(1u, image.getNumMipmapLevels());
        unsigned skip = 0;
        while (skip + 1 < sourceLevels && std::max(w0 >> skip, h0 >> skip) > s.maxSize)
            ++skip;
        const int srcW = std::max(1, w0 >> skip), srcH = std::max(1, h0 >> skip);
        unsigned halvings = 0;
        while (std::max(srcW >> halvings, srcH >> halvings) > s.maxSize)
            ++halvings;
        const int w = std::max(1, srcW >> halvings), h = std::max(1, srcH >> halvings);
        // Always the whole chain down to 1x1. The levels the DDS lacks (it has one level, or its mip data could not be
        // read, or the first level was halved) are made by halving the level above: OSG would otherwise ask for
        // glGenerateMipmap, which OpenGL ES refuses on compressed textures and leaves the texture incomplete.
        unsigned levels = 1;
        while (std::max(w >> levels, h >> levels) > 0)
            ++levels;
        // the source levels that can be decoded as they are
        const unsigned direct = halvings > 0 ? 0 : sourceLevels - skip;
        const size_t dxtBlock = (format == DXT1_RGB || format == DXT1_RGBA) ? 8 : 16;
        const bool alpha = format != DXT1_RGB;
        const GLenum internalFormat = s.etc2 ? (alpha ? ETC2_RGBA8_EAC : ETC2_RGB8) : s.internalFormat;
        std::vector<uint32_t> sizes;
        size_t total = 0;
        for (unsigned l = 0; l < levels; ++l)
        {
            const size_t bytes = s.etc2 ? etc2Bytes(std::max(1, w >> l), std::max(1, h >> l), alpha)
                                        : astcBytes(s, std::max(1, w >> l), std::max(1, h >> l));
            sizes.push_back(static_cast<uint32_t>(bytes));
            total += bytes;
        }

        // key: the texels of the first level used (a later mod can replace a file under the same path) and how they are
        // encoded. Only that level is hashed, from a size worked out here rather than what the image reports.
        const size_t keyBytes = static_cast<size_t>((srcW + 3) / 4) * ((srcH + 3) / 4) * dxtBlock;
        const uint8_t* texels = image.getMipmapData(skip);
        auto finishKey = [&](uint64_t key) {
            key = fnv(reinterpret_cast<const uint8_t*>(s.tag.data()), s.tag.size(), key);
            key = (key ^ (static_cast<uint64_t>(w) << 32 | static_cast<uint64_t>(h) << 8 | format)) * 1099511628211ull;
            key ^= levels * 0x9E3779B97F4A7C15ull;
            return key;
        };
        auto cacheFile = [&](uint64_t key) {
            char name[40];
            snprintf(name, sizeof name, s.etc2 ? "%016llx.etc2" : "%016llx.astc", static_cast<unsigned long long>(key));
            return s.dir / std::string(name, 2) / name;
        };
        const uint64_t cacheKey = finishKey(fastHash(texels, keyBytes, 0x6f6d77ull));
        const std::filesystem::path file = cacheFile(cacheKey);
        const BlobKey blobKey{ cacheKey, s.etc2 ? 2u : 1u };

        // the texture straight from a cache file into the buffer the image keeps
        auto readCached = [&](const std::filesystem::path& path) -> osg::ref_ptr<osg::Image> {
            std::ifstream in(path, std::ios::binary);
            CacheHeader header;
            if (in && in.read(reinterpret_cast<char*>(&header), sizeof header) && !std::memcmp(header.magic, s.etc2 ? "OETC" : "OAST", 4)
                && header.version == 1 && header.width == static_cast<uint32_t>(w) && header.height == static_cast<uint32_t>(h)
                && header.levels == levels && header.format == internalFormat && header.total == total)
            {
                std::unique_ptr<unsigned char[]> buffer(new unsigned char[total]);
                if (in.read(reinterpret_cast<char*>(buffer.get()), static_cast<std::streamsize>(total)))
                    return fromOwnedBuffer(internalFormat, w, h, sizes, buffer.release());
            }
            return nullptr;
        };

        // the texture straight from the single cache file into the buffer the image keeps
        auto readBlob = [&]() -> osg::ref_ptr<osg::Image> {
            CacheHeader header;
            if (!s.store || s.store->size(blobKey) != sizeof header + total || !s.store->read(blobKey, 0, &header, sizeof header)
                || std::memcmp(header.magic, s.etc2 ? "OETC" : "OAST", 4) || header.version != 1
                || header.width != static_cast<uint32_t>(w) || header.height != static_cast<uint32_t>(h)
                || header.levels != levels || header.format != internalFormat || header.total != total)
                return nullptr;
            std::unique_ptr<unsigned char[]> buffer(new unsigned char[total]);
            if (!s.store->read(blobKey, sizeof header, buffer.get(), total))
                return nullptr;
            return fromOwnedBuffer(internalFormat, w, h, sizes, buffer.release());
        };

        auto appendBlob = [&](const unsigned char* data) {
            if (!s.store)
                return false;
            const CacheHeader header{ { 'O', s.etc2 ? 'E' : 'A', s.etc2 ? 'T' : 'S', s.etc2 ? 'C' : 'T' }, 1,
                static_cast<uint32_t>(w), static_cast<uint32_t>(h), levels, internalFormat, static_cast<uint32_t>(total) };
            return s.store->append(blobKey, &header, sizeof header, data, total);
        };

        // A file of the one-file-per-texture layout of earlier builds: read once, moved into the single file
        auto importLoose = [&](const std::filesystem::path& path) -> osg::ref_ptr<osg::Image> {
            osg::ref_ptr<osg::Image> image = readCached(path);
            if (image && appendBlob(image->data()))
            {
                std::error_code ignored;
                std::filesystem::remove(path, ignored);
            }
            return image;
        };

        if (osg::ref_ptr<osg::Image> cached = readBlob())
        {
            ++sHits;
            maybeReport();
            return cached;
        }

        if (osg::ref_ptr<osg::Image> cached = importLoose(file))
        {
            ++sHits;
            maybeReport();
            return cached;
        }

        // Files named by the earlier, byte-wise hash: renamed to the new name, so each is only hashed the slow way once
        {
            const std::filesystem::path legacy = cacheFile(finishKey(fnv(texels, keyBytes)));
            std::error_code ec;
            if (std::filesystem::exists(legacy, ec))
            {
                std::filesystem::create_directories(file.parent_path(), ec);
                std::filesystem::rename(legacy, file, ec);
                if (osg::ref_ptr<osg::Image> cached = importLoose(ec ? legacy : file))
                {
                    ++sHits;
                    maybeReport();
                    return cached;
                }
            }
        }

        astcenc_context* context = s.etc2 ? nullptr : threadContext(s);
        if (!s.etc2 && !context)
            return nullptr;
        const auto start = std::chrono::steady_clock::now();
        std::vector<uint8_t> data(total), rgba;
        // a 2x2 box filter in place, from cw x ch to half that
        auto halve = [&rgba](int& cw, int& ch) {
            const int nw = std::max(1, cw / 2), nh = std::max(1, ch / 2);
            for (int y = 0; y < nh; ++y)
                for (int x = 0; x < nw; ++x)
                    for (int c = 0; c < 4; ++c)
                    {
                        const int x0 = std::min(cw - 1, x * 2), y0 = std::min(ch - 1, y * 2);
                        const int x1 = std::min(cw - 1, x * 2 + 1), y1 = std::min(ch - 1, y * 2 + 1);
                        const unsigned sum = rgba[(y0 * cw + x0) * 4 + c] + rgba[(y0 * cw + x1) * 4 + c]
                            + rgba[(y1 * cw + x0) * 4 + c] + rgba[(y1 * cw + x1) * 4 + c];
                        rgba[(y * nw + x) * 4 + c] = static_cast<uint8_t>((sum + 2) / 4);
                    }
            cw = nw;
            ch = nh;
            rgba.resize(static_cast<size_t>(cw) * ch * 4);
        };
        size_t offset = 0;
        for (unsigned l = 0; l < levels; ++l)
        {
            const int lw = std::max(1, w >> l), lh = std::max(1, h >> l);
            if (l < direct)
                decodeLevel(format, image.getMipmapData(skip + l), lw, lh, rgba);
            else if (l == 0)
            {
                decodeLevel(format, image.getMipmapData(skip), srcW, srcH, rgba);
                int cw = srcW, ch = srcH;
                for (unsigned i = 0; i < halvings; ++i)
                    halve(cw, ch);
            }
            else
            {
                // rgba still holds the level above
                int cw = std::max(1, w >> (l - 1)), ch = std::max(1, h >> (l - 1));
                halve(cw, ch);
            }
            if (s.etc2)
            {
                encodeEtc2(rgba.data(), lw, lh, alpha, data.data() + offset, s.threads);
                offset += sizes[l];
                continue;
            }
            void* slice = rgba.data();
            astcenc_image input{ static_cast<unsigned>(lw), static_cast<unsigned>(lh), 1, ASTCENC_TYPE_U8, &slice };
            const astcenc_swizzle swizzle{ ASTCENC_SWZ_R, ASTCENC_SWZ_G, ASTCENC_SWZ_B, ASTCENC_SWZ_A };
            // the first levels hold nearly all the work
            bool done;
            if (s.threads > 1 && static_cast<size_t>(lw) * lh >= 256 * 256)
                done = compressParallel(s, input, swizzle, data.data() + offset, sizes[l]);
            else
            {
                astcenc_compress_reset(context);
                done = astcenc_compress_image(context, &input, &swizzle, data.data() + offset, sizes[l], 0)
                    == ASTCENC_SUCCESS;
            }
            if (!done)
            {
                ++sFailed;
                return nullptr;
            }
            offset += sizes[l];
        }
        const auto micros
            = std::chrono::duration_cast<std::chrono::microseconds>(std::chrono::steady_clock::now() - start).count();
        sEncodeMicros += static_cast<uint64_t>(micros);
        sSourceBytes += keyBytes * 4 / 3;
        sAstcBytes += total;
        ++sEncoded;
        if (micros > 150000)
        {
            ++sSlow;
            Log(Debug::Info) << (s.etc2 ? "ETC2" : "ASTC") << " converted " << path << " " << w << "x" << h << " (" << levels << " levels, "
                             << keyBytes / 1024 << " KB of the first level DXT -> " << total / 1024
                             << " KB) in " << micros / 1000 << " ms";
        }
        maybeReport();

        if (!appendBlob(data.data()))
        {
            // no single file (it could not be opened or written): one file per texture, written under a temporary name and
            // renamed so a half-written file is never read
            std::error_code ec;
            std::filesystem::create_directories(file.parent_path(), ec);
            const std::filesystem::path tmp = file.string() + "." + std::to_string(reinterpret_cast<uintptr_t>(&data)) + ".tmp";
            {
                std::ofstream out(tmp, std::ios::binary);
                CacheHeader header{ { 'O', s.etc2 ? 'E' : 'A', s.etc2 ? 'T' : 'S', s.etc2 ? 'C' : 'T' }, 1,
                    static_cast<uint32_t>(w), static_cast<uint32_t>(h), levels, internalFormat,
                    static_cast<uint32_t>(total) };
                out.write(reinterpret_cast<const char*>(&header), sizeof header);
                out.write(reinterpret_cast<const char*>(data.data()), static_cast<std::streamsize>(total));
            }
            std::filesystem::rename(tmp, file, ec);
            if (ec)
                std::filesystem::remove(tmp, ec);
        }
        return fromBuffer(internalFormat, w, h, sizes, data.data(), total);
    }
}
