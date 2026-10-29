#include "bcdecode.hpp"

#include <algorithm>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <vector>

#include <osg/GL>

#ifndef GL_RGB565
#define GL_RGB565 0x8D62
#endif
#ifndef GL_RGB5_A1
#define GL_RGB5_A1 0x8057
#endif
#ifndef GL_UNSIGNED_SHORT_5_6_5
#define GL_UNSIGNED_SHORT_5_6_5 0x8363
#endif
#ifndef GL_UNSIGNED_SHORT_5_5_5_1
#define GL_UNSIGNED_SHORT_5_5_5_1 0x8034
#endif

#define BCDEC_STATIC
#define BCDEC_IMPLEMENTATION
#include "bcdec.h"

namespace Alpha3
{
    namespace
    {
        constexpr GLenum sDxt1Rgb = 0x83F0, sDxt1Rgba = 0x83F1, sDxt3Rgba = 0x83F2, sDxt5Rgba = 0x83F3;
    }

    osg::ref_ptr<osg::Image> decodeS3tc(const osg::Image& image)
    {
        const GLenum format = image.getPixelFormat();
        if (format != sDxt1Rgb && format != sDxt1Rgba && format != sDxt3Rgba && format != sDxt5Rgba)
            return nullptr;
        if (image.r() != 1 || image.s() <= 0 || image.t() <= 0 || !image.data())
            return nullptr;

        const bool opaque = format == sDxt1Rgb;
        const bool dxt1 = opaque || format == sDxt1Rgba;
        // DXT1 has 5 and 6 bit colours, so 16 bit texels (RGB565, RGB5_A1) lose nothing but the 1 bit alpha's colour bits
        // and take half (a third less than RGB8) of the memory. OPENMW_TEXTURES_32BIT keeps 8 bit components.
        static const bool wide = std::getenv("OPENMW_TEXTURES_32BIT") != nullptr;
        const bool packed = dxt1 && !wide;
        const unsigned components = packed ? 2 : opaque ? 3 : 4; // bytes per texel
        const std::size_t blockBytes = (format == sDxt1Rgb || format == sDxt1Rgba) ? 8 : 16;
        const unsigned levels = std::max(1u, image.getNumMipmapLevels());

        // the source of every level and its size, checked against what the image holds
        struct Level
        {
            int mWidth, mHeight;
            const unsigned char* mData;
            std::size_t mOffset;
        };
        std::vector<Level> plan;
        std::size_t total = 0;
        for (unsigned level = 0; level < levels; ++level)
        {
            const int width = std::max(1, image.s() >> level), height = std::max(1, image.t() >> level);
            const unsigned char* data = image.getMipmapData(level);
            const std::size_t needed = static_cast<std::size_t>((width + 3) / 4) * ((height + 3) / 4) * blockBytes;
            const std::ptrdiff_t available = (data + needed) - image.data();
            if (!data || available < 0 || static_cast<std::size_t>(available) > image.getTotalSizeInBytesIncludingMipmaps())
                return nullptr;
            plan.push_back({ width, height, data, total });
            total += static_cast<std::size_t>(width) * height * components;
        }

        unsigned char* buffer = new unsigned char[total];
        for (const Level& level : plan)
        {
            unsigned char* destination = buffer + level.mOffset;
            const int blocksX = (level.mWidth + 3) / 4, blocksY = (level.mHeight + 3) / 4;
            for (int by = 0; by < blocksY; ++by)
            {
                for (int bx = 0; bx < blocksX; ++bx)
                {
                    const unsigned char* block = level.mData + (static_cast<std::size_t>(by) * blocksX + bx) * blockBytes;
                    unsigned char decoded[4 * 4 * 4];
                    if (format == sDxt1Rgb || format == sDxt1Rgba)
                        bcdec_bc1(block, decoded, 4 * 4);
                    else if (format == sDxt3Rgba)
                        bcdec_bc2(block, decoded, 4 * 4);
                    else
                        bcdec_bc3(block, decoded, 4 * 4);

                    // the block's pixels that lie inside the image (the edge blocks of a size that is no multiple of 4 stick out)
                    const int columns = std::min(4, level.mWidth - bx * 4), rows = std::min(4, level.mHeight - by * 4);
                    for (int y = 0; y < rows; ++y)
                    {
                        unsigned char* row = destination
                            + (static_cast<std::size_t>(by * 4 + y) * level.mWidth + bx * 4) * components;
                        const unsigned char* source = decoded + y * 4 * 4;
                        if (packed)
                        {
                            for (int x = 0; x < columns; ++x)
                            {
                                const unsigned char* texel = source + x * 4;
                                const unsigned r = texel[0], g = texel[1], b = texel[2];
                                const unsigned short value = opaque
                                    ? static_cast<unsigned short>(((r >> 3) << 11) | ((g >> 2) << 5) | (b >> 3))
                                    : static_cast<unsigned short>(((r >> 3) << 11) | ((g >> 3) << 6) | ((b >> 3) << 1) | (texel[3] >> 7));
                                std::memcpy(row + x * 2, &value, 2);
                            }
                        }
                        else if (opaque)
                            for (int x = 0; x < columns; ++x)
                                std::memcpy(row + x * 3, source + x * 4, 3);
                        else
                            std::memcpy(row, source, static_cast<std::size_t>(columns) * 4);
                    }
                }
            }
        }

        osg::ref_ptr<osg::Image> result = new osg::Image;
        const GLenum pixelFormat = opaque ? GL_RGB : GL_RGBA;
        const GLenum internalFormat = packed ? (opaque ? GL_RGB565 : GL_RGB5_A1) : pixelFormat;
        const GLenum dataType = packed ? (opaque ? GL_UNSIGNED_SHORT_5_6_5 : GL_UNSIGNED_SHORT_5_5_5_1) : GL_UNSIGNED_BYTE;
        // rows are tight (packing 1), which is what the offsets below assume
        result->setImage(plan[0].mWidth, plan[0].mHeight, 1, internalFormat, pixelFormat, dataType, buffer,
            osg::Image::USE_NEW_DELETE, 1);
        if (plan.size() > 1)
        {
            osg::Image::MipmapDataType offsets;
            for (std::size_t i = 1; i < plan.size(); ++i)
                offsets.push_back(static_cast<unsigned>(plan[i].mOffset));
            result->setMipmapLevels(offsets);
        }
        result->setOrigin(image.getOrigin());
        result->setFileName(image.getFileName());
        return result;
    }
}
