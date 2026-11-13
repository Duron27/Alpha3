// Metal's resources (resources.hpp). The translation of OSG's formats to Vulkan's format enums is
// src/vulkan/resources.cpp's, copied; what is made from them is Metal's.
#include "resources.hpp"

#include "../support.hpp"

#include "context.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>

#include <osg/BufferObject>
#include <osg/FrameBufferObject>
#include <osg/Image>
#include <osg/Texture>

#include <components/debug/debuglog.hpp>

namespace Alpha3::Mtl
{
    namespace
    {
        // GL enums the translation reads (OSG's headers do not have all of them on every platform)
        constexpr unsigned glRgb = 0x1907, glRgba = 0x1908, glBgr = 0x80E0, glBgra = 0x80E1, glRed = 0x1903,
                           glRg = 0x8227, glAlpha = 0x1906, glLuminance = 0x1909, glLuminanceAlpha = 0x190A,
                           glUnsignedByte = 0x1401, glFloat = 0x1406, glHalfFloat = 0x140B, glHalfFloatOes = 0x8D61,
                           glUnsignedShort = 0x1403, glDepthComponent = 0x1902, glDepthStencil = 0x84F9,
                           glUnsignedShort565 = 0x8363,
                           glUnsignedShort5551 = 0x8034;

        constexpr uint64_t sChunkSize = 16 << 20;
        constexpr uint64_t sUploadBudget = 48 << 20; // texture bytes uploaded per frame, the rest waits

        VkFormat compressedFormat(unsigned format)
        {
            switch (format)
            {
                case 0x83F0: // GL_COMPRESSED_RGB_S3TC_DXT1_EXT
                    return VK_FORMAT_BC1_RGB_UNORM_BLOCK;
                case 0x83F1: // GL_COMPRESSED_RGBA_S3TC_DXT1_EXT
                    return VK_FORMAT_BC1_RGBA_UNORM_BLOCK;
                case 0x83F2:
                    return VK_FORMAT_BC2_UNORM_BLOCK;
                case 0x83F3:
                    return VK_FORMAT_BC3_UNORM_BLOCK;
                case 0x8D64: // GL_ETC1_RGB8_OES
                case 0x9274: // GL_COMPRESSED_RGB8_ETC2
                    return VK_FORMAT_ETC2_R8G8B8_UNORM_BLOCK;
                case 0x9276: // GL_COMPRESSED_RGB8_PUNCHTHROUGH_ALPHA1_ETC2
                    return VK_FORMAT_ETC2_R8G8B8A1_UNORM_BLOCK;
                case 0x9278: // GL_COMPRESSED_RGBA8_ETC2_EAC
                    return VK_FORMAT_ETC2_R8G8B8A8_UNORM_BLOCK;
                case 0x9270:
                    return VK_FORMAT_EAC_R11_UNORM_BLOCK;
                case 0x9272:
                    return VK_FORMAT_EAC_R11G11_UNORM_BLOCK;
                case 0x93B0:
                    return VK_FORMAT_ASTC_4x4_UNORM_BLOCK;
                case 0x93B1:
                    return VK_FORMAT_ASTC_5x4_UNORM_BLOCK;
                case 0x93B2:
                    return VK_FORMAT_ASTC_5x5_UNORM_BLOCK;
                case 0x93B3:
                    return VK_FORMAT_ASTC_6x5_UNORM_BLOCK;
                case 0x93B4:
                    return VK_FORMAT_ASTC_6x6_UNORM_BLOCK;
                case 0x93B5:
                    return VK_FORMAT_ASTC_8x5_UNORM_BLOCK;
                case 0x93B6:
                    return VK_FORMAT_ASTC_8x6_UNORM_BLOCK;
                case 0x93B7:
                    return VK_FORMAT_ASTC_8x8_UNORM_BLOCK;
                case 0x93B8:
                    return VK_FORMAT_ASTC_10x5_UNORM_BLOCK;
                case 0x93B9:
                    return VK_FORMAT_ASTC_10x6_UNORM_BLOCK;
                case 0x93BA:
                    return VK_FORMAT_ASTC_10x8_UNORM_BLOCK;
                case 0x93BB:
                    return VK_FORMAT_ASTC_10x10_UNORM_BLOCK;
                case 0x93BC:
                    return VK_FORMAT_ASTC_12x10_UNORM_BLOCK;
                case 0x93BD:
                    return VK_FORMAT_ASTC_12x12_UNORM_BLOCK;
                default:
                    return VK_FORMAT_UNDEFINED;
            }
        }

        // The block size of a compressed format, in texels and bytes
        void blockSize(VkFormat format, uint32_t& width, uint32_t& height, uint32_t& bytes)
        {
            width = height = 4;
            bytes = 16;
            switch (format)
            {
                case VK_FORMAT_BC1_RGB_UNORM_BLOCK:
                case VK_FORMAT_BC1_RGBA_UNORM_BLOCK:
                case VK_FORMAT_ETC2_R8G8B8_UNORM_BLOCK:
                case VK_FORMAT_ETC2_R8G8B8A1_UNORM_BLOCK:
                case VK_FORMAT_EAC_R11_UNORM_BLOCK:
                    bytes = 8;
                    return;
                case VK_FORMAT_ASTC_5x4_UNORM_BLOCK:
                    width = 5;
                    return;
                case VK_FORMAT_ASTC_5x5_UNORM_BLOCK:
                    width = height = 5;
                    return;
                case VK_FORMAT_ASTC_6x5_UNORM_BLOCK:
                    width = 6, height = 5;
                    return;
                case VK_FORMAT_ASTC_6x6_UNORM_BLOCK:
                    width = height = 6;
                    return;
                case VK_FORMAT_ASTC_8x5_UNORM_BLOCK:
                    width = 8, height = 5;
                    return;
                case VK_FORMAT_ASTC_8x6_UNORM_BLOCK:
                    width = 8, height = 6;
                    return;
                case VK_FORMAT_ASTC_8x8_UNORM_BLOCK:
                    width = height = 8;
                    return;
                case VK_FORMAT_ASTC_10x5_UNORM_BLOCK:
                    width = 10, height = 5;
                    return;
                case VK_FORMAT_ASTC_10x6_UNORM_BLOCK:
                    width = 10, height = 6;
                    return;
                case VK_FORMAT_ASTC_10x8_UNORM_BLOCK:
                    width = 10, height = 8;
                    return;
                case VK_FORMAT_ASTC_10x10_UNORM_BLOCK:
                    width = height = 10;
                    return;
                case VK_FORMAT_ASTC_12x10_UNORM_BLOCK:
                    width = 12, height = 10;
                    return;
                case VK_FORMAT_ASTC_12x12_UNORM_BLOCK:
                    width = height = 12;
                    return;
                default:
                    return;
            }
        }

        VkComponentMapping identity()
        {
            return { VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY,
                VK_COMPONENT_SWIZZLE_IDENTITY };
        }

        // How the pixels of an uncompressed image go to the GPU: the Vulkan format, the bytes per texel there, and a
        // conversion of one row (null: as they are)
        struct Layout
        {
            VkFormat mFormat = VK_FORMAT_UNDEFINED;
            uint32_t mTexelBytes = 0;
            VkComponentMapping mSwizzle = identity();
            void (*mConvert)(const uint8_t* source, uint8_t* destination, uint32_t texels) = nullptr;
        };

        Layout uncompressedLayout(unsigned pixelFormat, unsigned dataType)
        {
            Layout layout;
            const VkComponentSwizzle r = VK_COMPONENT_SWIZZLE_R, g = VK_COMPONENT_SWIZZLE_G,
                                     one = VK_COMPONENT_SWIZZLE_ONE, zero = VK_COMPONENT_SWIZZLE_ZERO;
            if (dataType == glUnsignedByte)
            {
                switch (pixelFormat)
                {
                    case glRgba:
                        return { VK_FORMAT_R8G8B8A8_UNORM, 4 };
                    case glBgra:
                        return { VK_FORMAT_B8G8R8A8_UNORM, 4 };
                    case glRgb:
                        layout = { VK_FORMAT_R8G8B8A8_UNORM, 4 };
                        layout.mConvert = [](const uint8_t* s, uint8_t* d, uint32_t n) {
                            for (uint32_t i = 0; i < n; ++i, s += 3, d += 4)
                                d[0] = s[0], d[1] = s[1], d[2] = s[2], d[3] = 255;
                        };
                        return layout;
                    case glBgr:
                        layout = { VK_FORMAT_R8G8B8A8_UNORM, 4 };
                        layout.mConvert = [](const uint8_t* s, uint8_t* d, uint32_t n) {
                            for (uint32_t i = 0; i < n; ++i, s += 3, d += 4)
                                d[0] = s[2], d[1] = s[1], d[2] = s[0], d[3] = 255;
                        };
                        return layout;
                    case glRed:
                        return { VK_FORMAT_R8_UNORM, 1 };
                    case glRg:
                        return { VK_FORMAT_R8G8_UNORM, 2 };
                    case glLuminance:
                        return { VK_FORMAT_R8_UNORM, 1, { r, r, r, one } };
                    case glAlpha:
                        return { VK_FORMAT_R8_UNORM, 1, { zero, zero, zero, r } };
                    case glLuminanceAlpha:
                        return { VK_FORMAT_R8G8_UNORM, 2, { r, r, r, g } };
                    default:
                        return layout;
                }
            }
            if (dataType == glFloat)
            {
                switch (pixelFormat)
                {
                    case glRgba:
                        return { VK_FORMAT_R32G32B32A32_SFLOAT, 16 };
                    case glRed:
                    case glLuminance:
                        return { VK_FORMAT_R32_SFLOAT, 4,
                            pixelFormat == glLuminance ? VkComponentMapping{ r, r, r, one } : identity() };
                    case glRg:
                        return { VK_FORMAT_R32G32_SFLOAT, 8 };
                    case glRgb:
                        layout = { VK_FORMAT_R32G32B32A32_SFLOAT, 16 };
                        layout.mConvert = [](const uint8_t* s, uint8_t* d, uint32_t n) {
                            const float* in = reinterpret_cast<const float*>(s);
                            float* out = reinterpret_cast<float*>(d);
                            for (uint32_t i = 0; i < n; ++i, in += 3, out += 4)
                                out[0] = in[0], out[1] = in[1], out[2] = in[2], out[3] = 1.f;
                        };
                        return layout;
                    default:
                        return layout;
                }
            }
            if (dataType == glHalfFloat || dataType == glHalfFloatOes)
            {
                switch (pixelFormat)
                {
                    case glRgba:
                        return { VK_FORMAT_R16G16B16A16_SFLOAT, 8 };
                    case glRed:
                        return { VK_FORMAT_R16_SFLOAT, 2 };
                    case glRg:
                        return { VK_FORMAT_R16G16_SFLOAT, 4 };
                    default:
                        return layout;
                }
            }
            // DXT1 decoded on the CPU (bcdecode.cpp, a GPU without BC formats) is 16 bit: RGB565 or RGB5_A1, the
            // top bits red. R5G6B5 is a format every Vulkan GPU samples and filters; R5G5B5A1 is not, so it goes as RGBA8.
            if (dataType == glUnsignedShort565 && pixelFormat == glRgb)
                return { VK_FORMAT_R5G6B5_UNORM_PACK16, 2 };
            if (dataType == glUnsignedShort5551 && pixelFormat == glRgba)
            {
                layout = { VK_FORMAT_R8G8B8A8_UNORM, 4 };
                layout.mConvert = [](const uint8_t* s, uint8_t* d, uint32_t n) {
                    for (uint32_t i = 0; i < n; ++i, s += 2, d += 4)
                    {
                        uint16_t v;
                        std::memcpy(&v, s, 2);
                        const uint32_t r = (v >> 11) & 31, g = (v >> 6) & 31, b = (v >> 1) & 31;
                        d[0] = static_cast<uint8_t>((r << 3) | (r >> 2));
                        d[1] = static_cast<uint8_t>((g << 3) | (g >> 2));
                        d[2] = static_cast<uint8_t>((b << 3) | (b >> 2));
                        d[3] = (v & 1) ? 255 : 0;
                    }
                };
                return layout;
            }
            if (dataType == glUnsignedShort)
            {
                switch (pixelFormat)
                {
                    case glRgba:
                        return { VK_FORMAT_R16G16B16A16_UNORM, 8 };
                    case glRed:
                    case glLuminance:
                        return { VK_FORMAT_R16_UNORM, 2,
                            pixelFormat == glLuminance ? VkComponentMapping{ r, r, r, one } : identity() };
                    default:
                        return layout;
                }
            }
            return layout;
        }

        MTLSamplerMinMagFilter filter(int mode)
        {
            return (mode == 0x2600 || mode == 0x2700 || mode == 0x2702) ? MTLSamplerMinMagFilterNearest
                                                                        : MTLSamplerMinMagFilterLinear;
        }

        MTLSamplerAddressMode addressMode(int wrap)
        {
            switch (wrap)
            {
                case 0x2901: // GL_REPEAT
                    return MTLSamplerAddressModeRepeat;
                case 0x8370: // GL_MIRRORED_REPEAT
                    return MTLSamplerAddressModeMirrorRepeat;
                case 0x812D: // GL_CLAMP_TO_BORDER
                    return MTLSamplerAddressModeClampToBorderColor;
                default: // GL_CLAMP, GL_CLAMP_TO_EDGE
                    return MTLSamplerAddressModeClampToEdge;
            }
        }

        MTLCompareFunction compareFunction(unsigned func)
        {
            switch (func)
            {
                case 0x0200:
                    return MTLCompareFunctionNever;
                case 0x0201:
                    return MTLCompareFunctionLess;
                case 0x0202:
                    return MTLCompareFunctionEqual;
                case 0x0203:
                    return MTLCompareFunctionLessEqual;
                case 0x0204:
                    return MTLCompareFunctionGreater;
                case 0x0205:
                    return MTLCompareFunctionNotEqual;
                case 0x0206:
                    return MTLCompareFunctionGreaterEqual;
                default:
                    return MTLCompareFunctionAlways;
            }
        }

        MTLTextureSwizzle swizzleOf(VkComponentSwizzle swizzle, MTLTextureSwizzle identity)
        {
            switch (swizzle)
            {
                case VK_COMPONENT_SWIZZLE_ZERO:
                    return MTLTextureSwizzleZero;
                case VK_COMPONENT_SWIZZLE_ONE:
                    return MTLTextureSwizzleOne;
                case VK_COMPONENT_SWIZZLE_R:
                    return MTLTextureSwizzleRed;
                case VK_COMPONENT_SWIZZLE_G:
                    return MTLTextureSwizzleGreen;
                case VK_COMPONENT_SWIZZLE_B:
                    return MTLTextureSwizzleBlue;
                case VK_COMPONENT_SWIZZLE_A:
                    return MTLTextureSwizzleAlpha;
                default:
                    return identity;
            }
        }

        bool isIdentity(const VkComponentMapping& mapping)
        {
            const auto plain = [](VkComponentSwizzle s, VkComponentSwizzle own) {
                return s == VK_COMPONENT_SWIZZLE_IDENTITY || s == own;
            };
            return plain(mapping.r, VK_COMPONENT_SWIZZLE_R) && plain(mapping.g, VK_COMPONENT_SWIZZLE_G)
                && plain(mapping.b, VK_COMPONENT_SWIZZLE_B) && plain(mapping.a, VK_COMPONENT_SWIZZLE_A);
        }

        MTLTextureType textureType(VkImageViewType type)
        {
            switch (type)
            {
                case VK_IMAGE_VIEW_TYPE_2D_ARRAY:
                    return MTLTextureType2DArray;
                case VK_IMAGE_VIEW_TYPE_CUBE:
                    return MTLTextureTypeCube;
                case VK_IMAGE_VIEW_TYPE_3D:
                    return MTLTextureType3D;
                default:
                    return MTLTextureType2D;
            }
        }
    }

    MTLPixelFormat mtlFormat(VkFormat format)
    {
        switch (format)
        {
            case VK_FORMAT_R8G8B8A8_UNORM:
                return MTLPixelFormatRGBA8Unorm;
            case VK_FORMAT_B8G8R8A8_UNORM:
                return MTLPixelFormatBGRA8Unorm;
            case VK_FORMAT_R8_UNORM:
                return MTLPixelFormatR8Unorm;
            case VK_FORMAT_R8G8_UNORM:
                return MTLPixelFormatRG8Unorm;
            case VK_FORMAT_R16_UNORM:
                return MTLPixelFormatR16Unorm;
            case VK_FORMAT_R16G16B16A16_UNORM:
                return MTLPixelFormatRGBA16Unorm;
            case VK_FORMAT_R16_SFLOAT:
                return MTLPixelFormatR16Float;
            case VK_FORMAT_R16G16_SFLOAT:
                return MTLPixelFormatRG16Float;
            case VK_FORMAT_R16G16B16A16_SFLOAT:
                return MTLPixelFormatRGBA16Float;
            case VK_FORMAT_R32_SFLOAT:
                return MTLPixelFormatR32Float;
            case VK_FORMAT_R32G32_SFLOAT:
                return MTLPixelFormatRG32Float;
            case VK_FORMAT_R32G32B32A32_SFLOAT:
                return MTLPixelFormatRGBA32Float;
            case VK_FORMAT_R5G6B5_UNORM_PACK16:
                return MTLPixelFormatB5G6R5Unorm;
            case VK_FORMAT_A2B10G10R10_UNORM_PACK32:
                return MTLPixelFormatRGB10A2Unorm;
            case VK_FORMAT_B10G11R11_UFLOAT_PACK32:
                return MTLPixelFormatRG11B10Float;
            case VK_FORMAT_D16_UNORM:
                return MTLPixelFormatDepth16Unorm;
            case VK_FORMAT_D32_SFLOAT:
                return MTLPixelFormatDepth32Float;
            case VK_FORMAT_D32_SFLOAT_S8_UINT:
            case VK_FORMAT_D24_UNORM_S8_UINT: // (iOS has no 24 bit depth)
                return MTLPixelFormatDepth32Float_Stencil8;
            case VK_FORMAT_BC1_RGB_UNORM_BLOCK:
            case VK_FORMAT_BC1_RGBA_UNORM_BLOCK:
                return MTLPixelFormatBC1_RGBA;
            case VK_FORMAT_BC2_UNORM_BLOCK:
                return MTLPixelFormatBC2_RGBA;
            case VK_FORMAT_BC3_UNORM_BLOCK:
                return MTLPixelFormatBC3_RGBA;
            case VK_FORMAT_ETC2_R8G8B8_UNORM_BLOCK:
                return MTLPixelFormatETC2_RGB8;
            case VK_FORMAT_ETC2_R8G8B8A1_UNORM_BLOCK:
                return MTLPixelFormatETC2_RGB8A1;
            case VK_FORMAT_ETC2_R8G8B8A8_UNORM_BLOCK:
                return MTLPixelFormatEAC_RGBA8;
            case VK_FORMAT_EAC_R11_UNORM_BLOCK:
                return MTLPixelFormatEAC_R11Unorm;
            case VK_FORMAT_EAC_R11G11_UNORM_BLOCK:
                return MTLPixelFormatEAC_RG11Unorm;
            case VK_FORMAT_ASTC_4x4_UNORM_BLOCK:
                return MTLPixelFormatASTC_4x4_LDR;
            case VK_FORMAT_ASTC_5x4_UNORM_BLOCK:
                return MTLPixelFormatASTC_5x4_LDR;
            case VK_FORMAT_ASTC_5x5_UNORM_BLOCK:
                return MTLPixelFormatASTC_5x5_LDR;
            case VK_FORMAT_ASTC_6x5_UNORM_BLOCK:
                return MTLPixelFormatASTC_6x5_LDR;
            case VK_FORMAT_ASTC_6x6_UNORM_BLOCK:
                return MTLPixelFormatASTC_6x6_LDR;
            case VK_FORMAT_ASTC_8x5_UNORM_BLOCK:
                return MTLPixelFormatASTC_8x5_LDR;
            case VK_FORMAT_ASTC_8x6_UNORM_BLOCK:
                return MTLPixelFormatASTC_8x6_LDR;
            case VK_FORMAT_ASTC_8x8_UNORM_BLOCK:
                return MTLPixelFormatASTC_8x8_LDR;
            case VK_FORMAT_ASTC_10x5_UNORM_BLOCK:
                return MTLPixelFormatASTC_10x5_LDR;
            case VK_FORMAT_ASTC_10x6_UNORM_BLOCK:
                return MTLPixelFormatASTC_10x6_LDR;
            case VK_FORMAT_ASTC_10x8_UNORM_BLOCK:
                return MTLPixelFormatASTC_10x8_LDR;
            case VK_FORMAT_ASTC_10x10_UNORM_BLOCK:
                return MTLPixelFormatASTC_10x10_LDR;
            case VK_FORMAT_ASTC_12x10_UNORM_BLOCK:
                return MTLPixelFormatASTC_12x10_LDR;
            case VK_FORMAT_ASTC_12x12_UNORM_BLOCK:
                return MTLPixelFormatASTC_12x12_LDR;
            default:
                return MTLPixelFormatInvalid;
        }
    }

    MTLVertexFormat mtlVertexFormat(VkFormat format)
    {
        switch (format)
        {
            case VK_FORMAT_R32_SFLOAT:
                return MTLVertexFormatFloat;
            case VK_FORMAT_R32G32_SFLOAT:
                return MTLVertexFormatFloat2;
            case VK_FORMAT_R32G32B32_SFLOAT:
                return MTLVertexFormatFloat3;
            case VK_FORMAT_R32G32B32A32_SFLOAT:
                return MTLVertexFormatFloat4;
            case VK_FORMAT_R32_SINT:
                return MTLVertexFormatInt;
            case VK_FORMAT_R32G32_SINT:
                return MTLVertexFormatInt2;
            case VK_FORMAT_R32G32B32_SINT:
                return MTLVertexFormatInt3;
            case VK_FORMAT_R32G32B32A32_SINT:
                return MTLVertexFormatInt4;
            case VK_FORMAT_R32_UINT:
                return MTLVertexFormatUInt;
            case VK_FORMAT_R32G32_UINT:
                return MTLVertexFormatUInt2;
            case VK_FORMAT_R32G32B32_UINT:
                return MTLVertexFormatUInt3;
            case VK_FORMAT_R32G32B32A32_UINT:
                return MTLVertexFormatUInt4;
            case VK_FORMAT_R8G8B8A8_UNORM:
                return MTLVertexFormatUChar4Normalized;
            case VK_FORMAT_R8G8B8A8_SNORM:
                return MTLVertexFormatChar4Normalized;
            case VK_FORMAT_R8G8B8A8_UINT:
                return MTLVertexFormatUChar4;
            case VK_FORMAT_R8G8B8A8_SINT:
                return MTLVertexFormatChar4;
            case VK_FORMAT_R8G8B8_UNORM:
                return MTLVertexFormatUChar3Normalized;
            case VK_FORMAT_R8G8_UNORM:
                return MTLVertexFormatUChar2Normalized;
            case VK_FORMAT_R8_UNORM:
                return MTLVertexFormatUCharNormalized;
            case VK_FORMAT_R16G16_SFLOAT:
                return MTLVertexFormatHalf2;
            case VK_FORMAT_R16G16B16A16_SFLOAT:
                return MTLVertexFormatHalf4;
            case VK_FORMAT_R16G16_SNORM:
                return MTLVertexFormatShort2Normalized;
            case VK_FORMAT_R16G16B16A16_SNORM:
                return MTLVertexFormatShort4Normalized;
            case VK_FORMAT_R16G16_UNORM:
                return MTLVertexFormatUShort2Normalized;
            case VK_FORMAT_R16G16B16A16_UNORM:
                return MTLVertexFormatUShort4Normalized;
            case VK_FORMAT_R16G16_SINT:
                return MTLVertexFormatShort2;
            case VK_FORMAT_R16G16B16A16_SINT:
                return MTLVertexFormatShort4;
            case VK_FORMAT_R16G16_UINT:
                return MTLVertexFormatUShort2;
            case VK_FORMAT_R16G16B16A16_UINT:
                return MTLVertexFormatUShort4;
            case VK_FORMAT_R16G16B16_SFLOAT:
                return MTLVertexFormatHalf3;
            case VK_FORMAT_R16G16B16_SNORM:
                return MTLVertexFormatShort3Normalized;
            case VK_FORMAT_R16G16B16_UNORM:
                return MTLVertexFormatUShort3Normalized;
            case VK_FORMAT_R8G8B8_SNORM:
                return MTLVertexFormatChar3Normalized;
            default:
                return MTLVertexFormatInvalid;
        }
    }

    bool isDepthFormat(VkFormat format)
    {
        switch (format)
        {
            case VK_FORMAT_D16_UNORM:
            case VK_FORMAT_X8_D24_UNORM_PACK32:
            case VK_FORMAT_D32_SFLOAT:
            case VK_FORMAT_D16_UNORM_S8_UINT:
            case VK_FORMAT_D24_UNORM_S8_UINT:
            case VK_FORMAT_D32_SFLOAT_S8_UINT:
                return true;
            default:
                return false;
        }
    }

    bool hasStencil(VkFormat format)
    {
        return format == VK_FORMAT_D16_UNORM_S8_UINT || format == VK_FORMAT_D24_UNORM_S8_UINT
            || format == VK_FORMAT_D32_SFLOAT_S8_UINT;
    }

    VkFormat targetFormat(unsigned internalFormat, VkFormat depthStencilFormat)
    {
        switch (internalFormat)
        {
            case glRgb:
            case glRgba:
            case 4: // the legacy "4 components"
            case 3:
            case 0x8051: // GL_RGB8
            case 0x8058: // GL_RGBA8
            case glBgra:
                return VK_FORMAT_R8G8B8A8_UNORM;
            case 0x8059: // GL_RGB10_A2
                return VK_FORMAT_A2B10G10R10_UNORM_PACK32;
            case 0x881A: // GL_RGBA16F
            case 0x881B: // GL_RGB16F
                return VK_FORMAT_R16G16B16A16_SFLOAT;
            case 0x8C3A: // GL_R11F_G11F_B10F
                return VK_FORMAT_B10G11R11_UFLOAT_PACK32;
            case 0x8814: // GL_RGBA32F
            case 0x8815: // GL_RGB32F
                return VK_FORMAT_R32G32B32A32_SFLOAT;
            case 0x822D: // GL_R16F
                return VK_FORMAT_R16_SFLOAT;
            case 0x822E: // GL_R32F
                return VK_FORMAT_R32_SFLOAT;
            case 0x822F: // GL_RG16F
                return VK_FORMAT_R16G16_SFLOAT;
            case 0x8230: // GL_RG32F
                return VK_FORMAT_R32G32_SFLOAT;
            case 0x8229: // GL_R8
            case glRed:
            case glLuminance:
                return VK_FORMAT_R8_UNORM;
            case 0x822B: // GL_RG8
            case glRg:
                return VK_FORMAT_R8G8_UNORM;
            case 0x81A5: // GL_DEPTH_COMPONENT16
                return VK_FORMAT_D16_UNORM;
            case 0x81A6: // GL_DEPTH_COMPONENT24
            case 0x81A7: // GL_DEPTH_COMPONENT32
            case 0x8CAC: // GL_DEPTH_COMPONENT32F
            case glDepthComponent:
                return VK_FORMAT_D32_SFLOAT;
            case 0x88F0: // GL_DEPTH24_STENCIL8
            case 0x8CAD: // GL_DEPTH32F_STENCIL8
            case glDepthStencil:
                return depthStencilFormat;
            default:
                return VK_FORMAT_UNDEFINED;
        }
    }

    Resources::Resources(Context& context)
        : mContext(context)
    {
    }

    Resources::~Resources() = default;

    Buffer Resources::readbackBuffer(uint64_t size)
    {
        Buffer buffer = createBuffer(size, true);
        Buffer gone = buffer;
        destroyLater(gone);
        return buffer;
    }

    Buffer Resources::createBuffer(uint64_t size, bool readback)
    {
        Buffer buffer;
        size = std::max<uint64_t>(size, 16);
        // all memory is the GPU's and the CPU's: written in place, no staging
        const MTLResourceOptions options = MTLResourceStorageModeShared
            | (readback ? MTLResourceCPUCacheModeDefaultCache : MTLResourceCPUCacheModeWriteCombined);
        buffer.mBuffer = [mContext.mtl() newBufferWithLength:size options:options];
        if (buffer.mBuffer == nil)
        {
            Log(Debug::Error) << "Metal: a buffer of " << size << " bytes could not be made";
            return {};
        }
        buffer.mMapped = static_cast<uint8_t*>([buffer.mBuffer contents]);
        buffer.mSize = size;
        return buffer;
    }

    void Resources::destroyLater(Buffer& buffer)
    {
        if (buffer.mBuffer == nil)
            return;
        // held by the release until the frames that may read it are done
        id<MTLBuffer> held = buffer.mBuffer;
        mContext.releaseLater([held] { (void)held; });
        buffer = {};
    }

    void Resources::destroyLater(std::unique_ptr<Image>& image)
    {
        if (!image)
            return;
        ++mImageGeneration;
        std::shared_ptr<Image> held(image.release());
        mContext.releaseLater([held] { (void)held; });
    }

    void Resources::beginFrame()
    {
        // the translator's frames: frame generation's frames between them (more of the context's) do not count, or
        // what the translator keeps for a number of its frames would outlive what is kept here
        ++mFrame;
        mUploadedThisFrame = 0;
        resetStream();
        if (mFrame % 300 == 0)
            purge();
    }

    void Resources::resetStream()
    {
        std::vector<Chunk>& chunks = mChunks[mContext.frameSlot()];
        // the chunks beyond the first that this slot did not need last time are let go
        while (chunks.size() > 1 && chunks.back().mUsed == 0)
        {
            destroyLater(chunks.back().mBuffer);
            chunks.pop_back();
        }
        for (Chunk& chunk : chunks)
            chunk.mUsed = 0;
    }

    Span Resources::stream(uint64_t size, uint64_t alignment)
    {
        mStreamedBytes += size;
        std::vector<Chunk>& chunks = mChunks[mContext.frameSlot()];
        for (Chunk& chunk : chunks)
        {
            const uint64_t at = (chunk.mUsed + alignment - 1) / alignment * alignment;
            if (at + size <= chunk.mBuffer.mSize)
            {
                chunk.mUsed = at + size;
                return { chunk.mBuffer.mBuffer, at, chunk.mBuffer.mMapped + at };
            }
        }
        Chunk chunk;
        chunk.mBuffer = createBuffer(std::max(sChunkSize, size));
        if (chunk.mBuffer.mBuffer == nil)
            return {};
        chunk.mBuffer.mBuffer.label = @"Alpha3 frame memory";
        chunk.mUsed = size;
        chunks.push_back(chunk);
        return { chunk.mBuffer.mBuffer, 0, chunk.mBuffer.mMapped };
    }

    id<MTLBuffer> Resources::storage(const osg::BufferData& data, bool copy)
    {
        const unsigned size = data.getTotalDataSize();
        if (size == 0 || data.getDataPointer() == nullptr)
            return nil;
        StorageEntry& entry = mStorage[&data];
        entry.mLastUse = mFrame;
        const bool same = entry.mBuffer.mBuffer != nil && entry.mData.get() == &data;
        if (same && entry.mBuffer.mSize >= size)
        {
            if (entry.mModified == data.getModifiedCount() || !copy)
                return entry.mBuffer.mBuffer;
            Span span = stream(size, 16);
            if (span.mData == nullptr)
                return entry.mBuffer.mBuffer;
            std::memcpy(span.mData, data.getDataPointer(), size);
            // ahead of the frame's commands; Metal orders it after the GPU's earlier use of the buffer
            [mContext.uploadEncoder() copyFromBuffer:span.mBuffer
                                        sourceOffset:span.mOffset
                                            toBuffer:entry.mBuffer.mBuffer
                                   destinationOffset:0
                                                size:size];
            entry.mModified = data.getModifiedCount();
            return entry.mBuffer.mBuffer;
        }
        // new, or grown (the point lights' list): a new buffer, room to grow, written in place (no GPU use yet)
        destroyLater(entry.mBuffer);
        entry.mData = &data;
        entry.mBuffer = createBuffer(same ? size + size / 2 : size);
        if (entry.mBuffer.mBuffer == nil)
            return nil;
        std::memcpy(entry.mBuffer.mMapped, data.getDataPointer(), size);
        entry.mModified = data.getModifiedCount();
        return entry.mBuffer.mBuffer;
    }

    id<MTLBuffer> Resources::dummyStorage()
    {
        if (mDummyStorage.mBuffer == nil)
        {
            mDummyStorage = createBuffer(4096);
            if (mDummyStorage.mBuffer == nil)
                return nil;
            std::memset(mDummyStorage.mMapped, 0, 4096);
        }
        return mDummyStorage.mBuffer;
    }

    bool Resources::buffer(const osg::BufferData& data, __strong id<MTLBuffer>& buffer, uint64_t& offset, bool* streamed)
    {
        const unsigned size = data.getTotalDataSize();
        if (size == 0 || data.getDataPointer() == nullptr)
            return false;
        BufferEntry& entry = mBuffers[&data];
        // checked already this frame and unchanged: the buffer it has
        if (entry.mCheckedFrame == mFrame && entry.mModified == data.getModifiedCount() && entry.mBuffer.mBuffer != nil)
        {
            if (!entry.mDynamic)
            {
                buffer = entry.mBuffer.mBuffer;
                offset = 0;
                return true;
            }
            if (entry.mStreamedFrame == mFrame)
            {
                buffer = entry.mStreamed.mBuffer;
                offset = entry.mStreamed.mOffset;
                if (streamed)
                    *streamed = true;
                return true;
            }
        }
        entry.mLastUse = mFrame;
        entry.mCheckedFrame = mFrame;
        const bool same = entry.mBuffer.mBuffer != nil && entry.mData.get() == &data;
        if (same && entry.mModified == data.getModifiedCount() && !entry.mDynamic)
        {
            buffer = entry.mBuffer.mBuffer;
            offset = 0;
            return true;
        }
        if (same && entry.mModified != data.getModifiedCount())
        {
            // changed again soon after the last change: it changes every frame or so, it goes to the frame's memory
            if (mFrame - entry.mChangedFrame < 8)
                entry.mDynamic = true;
            entry.mChangedFrame = mFrame;
            entry.mModified = data.getModifiedCount();
        }
        if (same && entry.mDynamic)
        {
            if (entry.mStreamedFrame != mFrame)
            {
                entry.mStreamed = stream(size, 16);
                if (entry.mStreamed.mData == nullptr)
                    return false;
                std::memcpy(entry.mStreamed.mData, data.getDataPointer(), size);
                entry.mStreamedFrame = mFrame;
            }
            buffer = entry.mStreamed.mBuffer;
            offset = entry.mStreamed.mOffset;
            if (streamed)
                *streamed = true;
            return true;
        }
        // new (or replaced, or a new object where a released one was)
        if (!same)
        {
            destroyLater(entry.mBuffer);
            entry = {};
            entry.mData = &data;
            entry.mLastUse = mFrame;
            entry.mChangedFrame = mFrame;
            entry.mCheckedFrame = mFrame;
        }
        else
            destroyLater(entry.mBuffer);
        entry.mModified = data.getModifiedCount();
        entry.mBuffer = createBuffer(size);
        if (entry.mBuffer.mBuffer == nil)
            return false;
        std::memcpy(entry.mBuffer.mMapped, data.getDataPointer(), size);
        buffer = entry.mBuffer.mBuffer;
        offset = 0;
        return true;
    }

    bool Resources::packedArray(const osg::Array& array, bool direction, __strong id<MTLBuffer>& buffer, uint64_t& offset)
    {
        const unsigned components = array.getDataSize();
        const unsigned count = array.getNumElements();
        if (array.getDataType() != 0x1406 /* GL_FLOAT */ || (components != 3 && components != 4) || count == 0
            || array.getDataPointer() == nullptr)
            return false;
        ConvertedEntry& entry = mPacked[&array];
        entry.mLastUse = mFrame;
        const bool same = entry.mData.get() == &array;
        if (same && entry.mModified != array.getModifiedCount())
        {
            // it changes (a skinned shape's normals): not worth packing again each time
            destroyLater(entry.mBuffer);
            entry.mBuffer = {};
            entry.mCount = 0;
            entry.mModified = array.getModifiedCount();
        }
        if (same)
        {
            buffer = entry.mBuffer.mBuffer;
            offset = 0;
            return entry.mCount > 0;
        }
        destroyLater(entry.mBuffer);
        entry = {};
        entry.mData = &array;
        entry.mLastUse = mFrame;
        entry.mModified = array.getModifiedCount();
        const float* in = static_cast<const float*>(array.getDataPointer());
        std::vector<uint8_t> packed(size_t(count) * 4);
        for (unsigned i = 0; i < count; ++i, in += components)
        {
            uint8_t* out = &packed[size_t(i) * 4];
            if (direction)
            {
                const float length = std::sqrt(in[0] * in[0] + in[1] * in[1] + in[2] * in[2]);
                const float scale = length > 1e-12f ? 127.f / length : 0.f;
                for (int c = 0; c < 3; ++c)
                    out[c] = static_cast<uint8_t>(static_cast<int8_t>(std::lround(in[c] * scale)));
                out[3] = 0;
            }
            else
            {
                for (unsigned c = 0; c < 4; ++c)
                {
                    const float value = c < components ? in[c] : 1.f;
                    if (!(value >= 0.f && value <= 1.f))
                        return false;
                    out[c] = static_cast<uint8_t>(value * 255.f + 0.5f);
                }
            }
        }
        entry.mBuffer = createBuffer(packed.size());
        if (entry.mBuffer.mBuffer == nil)
            return false;
        std::memcpy(entry.mBuffer.mMapped, packed.data(), packed.size());
        entry.mCount = count;
        buffer = entry.mBuffer.mBuffer;
        offset = 0;
        return true;
    }

    bool Resources::alignedArray(const osg::Array& array, uint32_t stride, __strong id<MTLBuffer>& buffer, uint64_t& offset)
    {
        const unsigned element = array.getElementSize();
        const unsigned count = array.getNumElements();
        if (element == 0 || count == 0 || array.getDataPointer() == nullptr || stride < element)
            return false;
        ConvertedEntry& entry = mAligned[{ &array, stride }];
        entry.mLastUse = mFrame;
        const bool same = entry.mBuffer.mBuffer != nil && entry.mData.get() == &array;
        if (!same || entry.mModified != array.getModifiedCount())
        {
            destroyLater(entry.mBuffer);
            entry.mData = &array;
            entry.mModified = array.getModifiedCount();
            entry.mBuffer = createBuffer(size_t(count) * stride);
            if (entry.mBuffer.mBuffer == nil)
                return false;
            const uint8_t* in = static_cast<const uint8_t*>(array.getDataPointer());
            std::memset(entry.mBuffer.mMapped, 0, size_t(count) * stride);
            for (unsigned i = 0; i < count; ++i)
                std::memcpy(entry.mBuffer.mMapped + size_t(i) * stride, in + size_t(i) * element, element);
            entry.mCount = count;
        }
        buffer = entry.mBuffer.mBuffer;
        offset = 0;
        return true;
    }

    bool Resources::convertedIndices(const osg::BufferData& data, uint32_t variant,
        const std::function<void(std::vector<uint32_t>&)>& converter, __strong id<MTLBuffer>& buffer, uint64_t& offset,
        uint32_t& count)
    {
        ConvertedEntry& entry = mConverted[{ &data, variant }];
        entry.mLastUse = mFrame;
        const bool same = entry.mBuffer.mBuffer != nil && entry.mData.get() == &data;
        if (!same || entry.mModified != data.getModifiedCount())
        {
            destroyLater(entry.mBuffer);
            std::vector<uint32_t> indices;
            converter(indices);
            entry.mData = &data;
            entry.mModified = data.getModifiedCount();
            entry.mCount = static_cast<uint32_t>(indices.size());
            if (indices.empty())
                return false;
            entry.mBuffer = createBuffer(indices.size() * 4);
            if (entry.mBuffer.mBuffer == nil)
                return false;
            std::memcpy(entry.mBuffer.mMapped, indices.data(), indices.size() * 4);
        }
        buffer = entry.mBuffer.mBuffer;
        offset = 0;
        count = entry.mCount;
        return count > 0;
    }

    std::unique_ptr<Image> Resources::createImage(VkFormat format, VkImageViewType type, uint32_t width,
        uint32_t height, uint32_t depth, uint32_t mips, uint32_t layers, bool target)
    {
        auto image = std::make_unique<Image>();
        image->mFormat = format;
        image->mType = type;
        image->mWidth = std::max(width, 1u);
        image->mHeight = std::max(height, 1u);
        image->mDepth = std::max(depth, 1u);
        image->mMips = std::max(mips, 1u);
        image->mLayers = std::max(layers, 1u);
        image->mDepthAspect = isDepthFormat(format);
        image->mStencilAspect = hasStencil(format);
        image->mSwizzle = identity();

        const MTLPixelFormat pixelFormat = mtlFormat(format);
        if (pixelFormat == MTLPixelFormatInvalid)
        {
            Log(Debug::Error) << "Metal: format " << format << " has no Metal format";
            return nullptr;
        }
        MTLTextureDescriptor* info = [[MTLTextureDescriptor alloc] init];
        info.textureType = textureType(type);
        info.pixelFormat = pixelFormat;
        info.width = image->mWidth;
        info.height = image->mHeight;
        info.depth = type == VK_IMAGE_VIEW_TYPE_3D ? image->mDepth : 1;
        info.mipmapLevelCount = image->mMips;
        info.arrayLength = type == VK_IMAGE_VIEW_TYPE_CUBE ? 1 : (type == VK_IMAGE_VIEW_TYPE_2D_ARRAY ? image->mLayers : 1);
        // (the simulator has no shared textures: uploads go through a blit)
        info.storageMode = MTLStorageModePrivate;
        info.usage = MTLTextureUsageShaderRead | MTLTextureUsagePixelFormatView
            | (target ? MTLTextureUsageRenderTarget : 0);
        image->mTexture = [mContext.mtl() newTextureWithDescriptor:info];
        if (image->mTexture == nil)
        {
            Log(Debug::Error) << "Metal: a texture " << width << "x" << height << " format " << format
                              << " could not be made";
            return nullptr;
        }
        return image;
    }

    id<MTLTexture> Resources::view(Image& image, VkImageViewType type)
    {
        // a 2D image is also a one layer array; anything else is seen as it is
        if (!(image.mType == VK_IMAGE_VIEW_TYPE_2D && type == VK_IMAGE_VIEW_TYPE_2D_ARRAY))
            type = image.mType;
        if (type == image.mType && isIdentity(image.mSwizzle))
            return image.mTexture;
        auto found = image.mViews.find(type);
        if (found != image.mViews.end())
            return found->second;
        const MTLTextureSwizzleChannels swizzle = MTLTextureSwizzleChannelsMake(
            swizzleOf(image.mSwizzle.r, MTLTextureSwizzleRed), swizzleOf(image.mSwizzle.g, MTLTextureSwizzleGreen),
            swizzleOf(image.mSwizzle.b, MTLTextureSwizzleBlue), swizzleOf(image.mSwizzle.a, MTLTextureSwizzleAlpha));
        id<MTLTexture> view = [image.mTexture newTextureViewWithPixelFormat:image.mTexture.pixelFormat
                                                                textureType:textureType(type)
                                                                     levels:NSMakeRange(0, image.mMips)
                                                                     slices:NSMakeRange(0, image.mTexture.arrayLength
                                                                                    * (image.mType == VK_IMAGE_VIEW_TYPE_CUBE ? 6 : 1))
                                                                    swizzle:swizzle];
        image.mViews[type] = view;
        return view;
    }

    std::unique_ptr<Image> Resources::upload(const osg::Texture& texture)
    {
        auto& mutableTexture = const_cast<osg::Texture&>(texture);
        const unsigned images = mutableTexture.getNumImages();
        // as OpenGL's upload does (the OSG hook, textureImageLoading): a texture file's pixels let go after an earlier
        // upload are read back, and let go again after this frame (texturerelease.cpp); the preload notes the file and
        // streaming notes the upload
        for (unsigned i = 0; i < images; ++i)
            if (const osg::Image* image = mutableTexture.getImage(i))
            {
                textureReleaseLoading(*image);
                texturePreloadTextureLoading(*image);
                textureStreamingTextureLoading(*image);
            }
        const osg::Image* first = images > 0 ? mutableTexture.getImage(0) : nullptr;
        if (first == nullptr || first->data() == nullptr || first->s() <= 0 || first->t() <= 0)
            return nullptr;

        VkImageViewType type = VK_IMAGE_VIEW_TYPE_2D;
        uint32_t layers = 1;
        switch (texture.getTextureTarget())
        {
            case 0x8C1A: // GL_TEXTURE_2D_ARRAY
                type = VK_IMAGE_VIEW_TYPE_2D_ARRAY;
                layers = images;
                break;
            case 0x8513: // GL_TEXTURE_CUBE_MAP
                type = VK_IMAGE_VIEW_TYPE_CUBE;
                layers = 6;
                break;
            case 0x806F: // GL_TEXTURE_3D
                type = VK_IMAGE_VIEW_TYPE_3D;
                break;
            default:
                break;
        }
        for (uint32_t i = 0; i < layers; ++i)
        {
            const osg::Image* layer = mutableTexture.getImage(std::min(i, images - 1));
            if (layer == nullptr || layer->data() == nullptr || layer->s() != first->s() || layer->t() != first->t())
                return nullptr;
        }

        const Device& device = mContext.device();
        const bool compressed = first->isCompressed();
        VkFormat format = VK_FORMAT_UNDEFINED;
        Layout layout;
        if (compressed)
        {
            format = compressedFormat(first->getPixelFormat());
            const bool supported
                = (format >= VK_FORMAT_BC1_RGB_UNORM_BLOCK && format <= VK_FORMAT_BC7_SRGB_BLOCK) ? device.mBcTextures : true;
            if (format == VK_FORMAT_UNDEFINED || !supported)
            {
                static int warned = 0;
                if (warned++ < 10)
                    Log(Debug::Warning) << "Metal: compressed texture format 0x" << std::hex << first->getPixelFormat()
                                        << std::dec << " is not supported (" << texture.getName() << ")";
                return nullptr;
            }
        }
        else
        {
            layout = uncompressedLayout(first->getPixelFormat(), first->getDataType());
            format = layout.mFormat;
            if (format == VK_FORMAT_UNDEFINED)
            {
                // a depth image (the shadows' 1x1 fallback map, a depth of 1) is drawn as the depth dummy, which is that
                static int warned = 0;
                if (first->getPixelFormat() != 0x1902 /* GL_DEPTH_COMPONENT */ && warned++ < 10)
                    Log(Debug::Warning) << "Metal: texture pixel format 0x" << std::hex << first->getPixelFormat()
                                        << " type 0x" << first->getDataType() << std::dec << " is not supported";
                return nullptr;
            }
        }

        const uint32_t width = first->s(), height = first->t(), depth = type == VK_IMAGE_VIEW_TYPE_3D ? first->r() : 1;
        const uint32_t storedMips = std::max(1u, first->getNumMipmapLevels());
        const int minFilter = texture.getFilter(osg::Texture::MIN_FILTER);
        const bool wantsMips = minFilter >= 0x2700 && minFilter <= 0x2703;
        const uint32_t fullMips = 1 + static_cast<uint32_t>(std::floor(std::log2(std::max({ width, height, depth }))));
        // generateMipmapsForTexture wants a format that is filtered and drawn into (every uncompressed one here but
        // the 32 bit floats)
        const bool generate = wantsMips && storedMips == 1 && !compressed && type != VK_IMAGE_VIEW_TYPE_3D
            && format != VK_FORMAT_R32G32B32A32_SFLOAT && format != VK_FORMAT_R32_SFLOAT
            && format != VK_FORMAT_R32G32_SFLOAT;
        const uint32_t mips = generate ? fullMips : std::min(storedMips, fullMips);

        // the bytes of every level of every layer, as the GPU takes them
        struct Region
        {
            uint32_t mLevel, mLayer, mWidth, mHeight, mDepth;
            const uint8_t* mSource;
            uint64_t mBytes;
            uint32_t mRowBytes, mRowStep, mRows;
            uint32_t mBlockRowBytes; // a row of texels (of blocks, compressed) in the staging memory
        };
        std::vector<Region> regions;
        uint64_t total = 0;
        uint32_t bw = 1, bh = 1, blockBytes = 0;
        if (compressed)
            blockSize(format, bw, bh, blockBytes);
        for (uint32_t layerIndex = 0; layerIndex < layers; ++layerIndex)
        {
            const osg::Image* image = mutableTexture.getImage(std::min(layerIndex, images - 1));
            for (uint32_t level = 0; level < (generate ? 1 : mips); ++level)
            {
                Region region;
                region.mLevel = level;
                region.mLayer = layerIndex;
                region.mWidth = std::max(width >> level, 1u);
                region.mHeight = std::max(height >> level, 1u);
                region.mDepth = std::max(depth >> level, 1u);
                region.mSource = image->data() + image->getMipmapOffset(level);
                if (compressed)
                {
                    region.mBlockRowBytes = (region.mWidth + bw - 1) / bw * blockBytes;
                    region.mBytes = static_cast<uint64_t>(region.mBlockRowBytes) * ((region.mHeight + bh - 1) / bh)
                        * region.mDepth;
                    region.mRowBytes = region.mRowStep = 0;
                    region.mRows = 0;
                }
                else
                {
                    region.mRowBytes = region.mWidth * layout.mTexelBytes;
                    region.mBlockRowBytes = region.mRowBytes;
                    region.mRows = region.mHeight * region.mDepth;
                    region.mBytes = static_cast<uint64_t>(region.mRowBytes) * region.mRows;
                    region.mRowStep = level == 0
                        ? image->getRowStepInBytes()
                        : osg::Image::computeRowWidthInBytes(
                            region.mWidth, image->getPixelFormat(), image->getDataType(), image->getPacking());
                }
                total += (region.mBytes + 15) & ~uint64_t(15);
                regions.push_back(region);
            }
        }
        if (mUploadedThisFrame > 0 && mUploadedThisFrame + total > sUploadBudget)
            return nullptr; // next frame
        mUploadedThisFrame += total;

        std::unique_ptr<Image> image = createImage(format, type, width, height, depth, mips, layers, generate);
        if (!image)
            return nullptr;
        if (!compressed)
            image->mSwizzle = layout.mSwizzle;

        Span staging = stream(total, 16);
        if (staging.mData == nullptr)
            return nullptr;
        const unsigned sourceTexelBytes = compressed
            ? 0
            : osg::Image::computePixelSizeInBits(first->getPixelFormat(), first->getDataType()) / 8;
        id<MTLBlitCommandEncoder> blit = mContext.uploadEncoder();
        uint64_t at = 0;
        for (const Region& region : regions)
        {
            uint8_t* destination = staging.mData + at;
            if (compressed)
                std::memcpy(destination, region.mSource, region.mBytes);
            else
                for (uint32_t row = 0; row < region.mRows; ++row)
                {
                    const uint8_t* source = region.mSource + static_cast<size_t>(row) * region.mRowStep;
                    uint8_t* out = destination + static_cast<size_t>(row) * region.mRowBytes;
                    if (layout.mConvert)
                        layout.mConvert(source, out, region.mWidth);
                    else
                        std::memcpy(out, source, static_cast<size_t>(region.mWidth) * sourceTexelBytes);
                }
            const uint64_t imageBytes = region.mBytes / region.mDepth;
            [blit copyFromBuffer:staging.mBuffer
                       sourceOffset:staging.mOffset + at
                  sourceBytesPerRow:region.mBlockRowBytes
                sourceBytesPerImage:type == VK_IMAGE_VIEW_TYPE_3D ? imageBytes : 0
                         sourceSize:MTLSizeMake(region.mWidth, region.mHeight, region.mDepth)
                          toTexture:image->mTexture
                   destinationSlice:region.mLayer
                   destinationLevel:region.mLevel
                  destinationOrigin:MTLOriginMake(0, 0, 0)];
            at += (region.mBytes + 15) & ~uint64_t(15);
        }
        if (generate)
            [blit generateMipmapsForTexture:image->mTexture];
        return image;
    }

    Image* Resources::texture(const osg::Texture& texture, bool shadow, __strong id<MTLSamplerState>& sampler)
    {
        Image* image = this->texture(texture);
        if (image == nullptr)
            return nullptr;
        TextureEntry& entry = mTextures[&texture];
        const int which = shadow ? 1 : 0;
        if (entry.mSamplerFrame[which] != mFrame)
        {
            entry.mSampler[which] = this->sampler(&texture, shadow);
            entry.mSamplerFrame[which] = mFrame;
        }
        sampler = entry.mSampler[which];
        return image;
    }

    Image* Resources::texture(const osg::Texture& texture)
    {
        TextureEntry& entry = mTextures[&texture];
        // checked already this frame: the scene holds the texture for the frame, so it is the same one
        if (entry.mChecked == mFrame && entry.mImage != nullptr)
            return entry.mImage;
        Image* image = textureChecked(texture, entry);
        entry.mChecked = image != nullptr ? mFrame : ~0ull;
        return image;
    }

    Image* Resources::textureChecked(const osg::Texture& texture, TextureEntry& entry)
    {
        osg::ref_ptr<const osg::Texture> alive;
        const bool same = entry.mImage != nullptr && entry.mTexture.lock(alive) && alive.get() == &texture;
        if (!same)
        {
            destroyLater(entry.mOwned);
            entry.mImage = nullptr;
            entry.mTarget = false;
        }
        if (same && entry.mTarget)
        {
            // sampled: kept, even if nothing draws into it any more (a terrain composite map is drawn once)
            auto target = mTargets.find(&texture);
            if (target != mTargets.end())
                target->second.mLastUse = mFrame;
            entry.mImage->mLastUse = mFrame;
            return entry.mImage;
        }
        auto& mutableTexture = const_cast<osg::Texture&>(texture);
        const osg::Image* source = mutableTexture.getNumImages() > 0 ? mutableTexture.getImage(0) : nullptr;
        if (same && source == entry.mSource && (source == nullptr || source->getModifiedCount() == entry.mSourceModified))
        {
            entry.mImage->mLastUse = mFrame;
            return entry.mImage;
        }
        std::unique_ptr<Image> image = upload(texture);
        if (!image)
            return same ? entry.mImage : nullptr; // the old one until the new one is up
        destroyLater(entry.mOwned);
        entry.mTexture = &texture;
        entry.mOwned = std::move(image);
        entry.mImage = entry.mOwned.get();
        entry.mSource = source;
        entry.mSourceModified = source ? source->getModifiedCount() : 0;
        entry.mTarget = false;
        entry.mImage->mLastUse = mFrame;
        return entry.mImage;
    }

    Image* Resources::renderTarget(const void* key, uint32_t width, uint32_t height, unsigned internalFormat)
    {
        TargetEntry& entry = mTargets[key];
        if (entry.mImage && entry.mImage->mWidth == width && entry.mImage->mHeight == height
            && entry.mFormat == internalFormat)
        {
            entry.mLastUse = mFrame;
            return entry.mImage.get();
        }
        destroyLater(entry.mImage);
        VkFormat format = targetFormat(internalFormat, VK_FORMAT_D32_SFLOAT_S8_UINT);
        if (format == VK_FORMAT_UNDEFINED)
        {
            Log(Debug::Warning) << "Metal: render target format 0x" << std::hex << internalFormat << std::dec
                                << " is not known, RGBA8 instead";
            format = VK_FORMAT_R8G8B8A8_UNORM;
        }
        // (every Apple GPU draws into and filters 16 bit depth and 32 bit float depth, which the shadow maps sample with
        // linear filtering for their comparisons)
        entry.mImage = createImage(format, VK_IMAGE_VIEW_TYPE_2D, width, height, 1, 1, 1, true);
        entry.mFormat = internalFormat;
        entry.mLastUse = mFrame;
        if (!entry.mImage)
            return nullptr;
        entry.mImage->mTexture.label = @"Alpha3 render target";
        // sampled before anything is drawn into it, it reads as cleared
        MTLRenderPassDescriptor* pass = [MTLRenderPassDescriptor renderPassDescriptor];
        if (entry.mImage->mDepthAspect)
        {
            pass.depthAttachment.texture = entry.mImage->mTexture;
            pass.depthAttachment.loadAction = MTLLoadActionClear;
            pass.depthAttachment.clearDepth = 1.0;
            pass.depthAttachment.storeAction = MTLStoreActionStore;
            if (entry.mImage->mStencilAspect)
            {
                pass.stencilAttachment.texture = entry.mImage->mTexture;
                pass.stencilAttachment.loadAction = MTLLoadActionClear;
                pass.stencilAttachment.storeAction = MTLStoreActionStore;
            }
        }
        else
        {
            pass.colorAttachments[0].texture = entry.mImage->mTexture;
            pass.colorAttachments[0].loadAction = MTLLoadActionClear;
            pass.colorAttachments[0].clearColor = MTLClearColorMake(0, 0, 0, 0);
            pass.colorAttachments[0].storeAction = MTLStoreActionStore;
        }
        mContext.clearAhead(pass);
        Log(Debug::Verbose) << "Metal: render target " << width << "x" << height << " format " << format;
        return entry.mImage.get();
    }

    Image* Resources::renderTarget(const osg::Texture& texture)
    {
        return renderTarget(texture, std::max(texture.getTextureWidth(), 1), std::max(texture.getTextureHeight(), 1),
            texture.getInternalFormat());
    }

    Image* Resources::renderTarget(const osg::Texture& texture, uint32_t width, uint32_t height, unsigned internalFormat)
    {
        Image* image = renderTarget(&texture, width, height, internalFormat);
        if (image == nullptr)
            return nullptr;
        // the texture is sampled as this image from now on
        TextureEntry& entry = mTextures[&texture];
        destroyLater(entry.mOwned);
        entry.mImage = image;
        entry.mTexture = &texture;
        entry.mTarget = true;
        entry.mChecked = ~0ull;
        return image;
    }

    Image* Resources::renderTarget(const osg::RenderBuffer& buffer)
    {
        return renderTarget(&buffer, std::max(buffer.getWidth(), 1), std::max(buffer.getHeight(), 1),
            buffer.getInternalFormat());
    }

    Image* Resources::dummy(bool depth)
    {
        std::unique_ptr<Image>& image = depth ? mDummyDepth : mDummyColor;
        if (image)
            return image.get();
        image = createImage(depth ? VK_FORMAT_D16_UNORM : VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_VIEW_TYPE_2D, 1, 1, 1, 1, 1,
            true);
        if (!image)
            return nullptr;
        MTLRenderPassDescriptor* pass = [MTLRenderPassDescriptor renderPassDescriptor];
        if (depth)
        {
            pass.depthAttachment.texture = image->mTexture;
            pass.depthAttachment.loadAction = MTLLoadActionClear;
            pass.depthAttachment.clearDepth = 1.0;
            pass.depthAttachment.storeAction = MTLStoreActionStore;
        }
        else
        {
            pass.colorAttachments[0].texture = image->mTexture;
            pass.colorAttachments[0].loadAction = MTLLoadActionClear;
            pass.colorAttachments[0].clearColor = MTLClearColorMake(1, 1, 1, 1);
            pass.colorAttachments[0].storeAction = MTLStoreActionStore;
        }
        mContext.clearAhead(pass);
        return image.get();
    }

    id<MTLSamplerState> Resources::sampler(const osg::Texture* texture, bool shadow)
    {
        std::array<uint32_t, 12> key{};
        if (texture != nullptr)
        {
            key[0] = texture->getFilter(osg::Texture::MIN_FILTER);
            key[1] = texture->getFilter(osg::Texture::MAG_FILTER);
            key[2] = texture->getWrap(osg::Texture::WRAP_S);
            key[3] = texture->getWrap(osg::Texture::WRAP_T);
            key[4] = texture->getWrap(osg::Texture::WRAP_R);
            key[5] = static_cast<uint32_t>(texture->getMaxAnisotropy() * 16);
            key[6] = shadow ? 1 : 0;
            key[7] = texture->getShadowCompareFunc();
            const osg::Vec4d& border = texture->getBorderColor();
            key[8] = border.a() < 0.5 ? 0 : (border.r() > 0.5 ? 2 : 1);
            key[9] = static_cast<uint32_t>(texture->getLODBias() * 256 + 65536);
            key[10] = static_cast<uint32_t>(texture->getMaxLOD() * 16);
        }
        else
        {
            key = { 0x2601, 0x2601, 0x812F, 0x812F, 0x812F, 16, shadow ? 1u : 0u, 0x0203, 0, 65536, 16000, 0 };
        }
        auto found = mSamplers.find(key);
        if (found != mSamplers.end())
            return found->second;

        MTLSamplerDescriptor* info = [[MTLSamplerDescriptor alloc] init];
        const int minFilter = static_cast<int>(key[0]);
        info.minFilter = filter(minFilter);
        info.magFilter = filter(static_cast<int>(key[1]));
        // without a mipmap filter only the base level is read (as in OpenGL)
        info.mipFilter = (minFilter == 0x2702 || minFilter == 0x2703) ? MTLSamplerMipFilterLinear
            : (minFilter == 0x2700 || minFilter == 0x2701)            ? MTLSamplerMipFilterNearest
                                                                      : MTLSamplerMipFilterNotMipmapped;
        info.sAddressMode = addressMode(static_cast<int>(key[2]));
        info.tAddressMode = addressMode(static_cast<int>(key[3]));
        info.rAddressMode = addressMode(static_cast<int>(key[4]));
        // (Metal's samplers have no LOD bias; the shader's bias argument would be the place)
        const float anisotropy = static_cast<float>(key[5]) / 16.f;
        if (anisotropy > 1.f && minFilter >= 0x2700)
            info.maxAnisotropy = static_cast<NSUInteger>(std::clamp(anisotropy, 1.f, 16.f));
        if (shadow)
            info.compareFunction = compareFunction(key[7]);
        info.lodMinClamp = 0.f;
        info.lodMaxClamp = (minFilter >= 0x2700 && minFilter <= 0x2703) ? std::max(key[10] / 16.f, 0.f) : FLT_MAX;
        info.borderColor = key[8] == 0 ? MTLSamplerBorderColorTransparentBlack
            : key[8] == 1             ? MTLSamplerBorderColorOpaqueBlack
                                      : MTLSamplerBorderColorOpaqueWhite;
        id<MTLSamplerState> sampler = [mContext.mtl() newSamplerStateWithDescriptor:info];
        mSamplers[key] = sampler;
        return sampler;
    }

    id<MTLSamplerState> Resources::nearestSampler()
    {
        if (mNearest == nil)
        {
            MTLSamplerDescriptor* info = [[MTLSamplerDescriptor alloc] init];
            info.minFilter = info.magFilter = MTLSamplerMinMagFilterNearest;
            info.sAddressMode = info.tAddressMode = info.rAddressMode = MTLSamplerAddressModeClampToEdge;
            mNearest = [mContext.mtl() newSamplerStateWithDescriptor:info];
        }
        return mNearest;
    }

    id<MTLSamplerState> Resources::linearSampler()
    {
        if (mLinear == nil)
        {
            MTLSamplerDescriptor* info = [[MTLSamplerDescriptor alloc] init];
            info.minFilter = info.magFilter = MTLSamplerMinMagFilterLinear;
            info.sAddressMode = info.tAddressMode = info.rAddressMode = MTLSamplerAddressModeClampToEdge;
            mLinear = [mContext.mtl() newSamplerStateWithDescriptor:info];
        }
        return mLinear;
    }

    void Resources::purge()
    {
        const auto purgeBuffers = [this](auto& map, uint64_t frames) {
            for (auto it = map.begin(); it != map.end();)
            {
                if (!it->second.mData || it->second.mData->referenceCount() <= 1 || mFrame - it->second.mLastUse > frames)
                {
                    destroyLater(it->second.mBuffer);
                    it = map.erase(it);
                }
                else
                    ++it;
            }
        };
        purgeBuffers(mBuffers, 3600);
        purgeBuffers(mPacked, 3600);
        purgeBuffers(mStorage, 600);
        purgeBuffers(mConverted, 3600);
        purgeBuffers(mAligned, 3600);
        for (auto it = mTextures.begin(); it != mTextures.end();)
        {
            osg::ref_ptr<const osg::Texture> alive;
            if (!it->second.mTexture.lock(alive))
            {
                destroyLater(it->second.mOwned);
                it = mTextures.erase(it);
            }
            else
                ++it;
        }
        for (auto it = mTargets.begin(); it != mTargets.end();)
        {
            if (mFrame - it->second.mLastUse > 600)
            {
                // a texture entry may still point at it
                for (auto& [texture, entry] : mTextures)
                    if (entry.mTarget && entry.mImage == it->second.mImage.get())
                    {
                        entry.mImage = nullptr;
                        entry.mTarget = false;
                        entry.mChecked = ~0ull;
                    }
                destroyLater(it->second.mImage);
                it = mTargets.erase(it);
            }
            else
                ++it;
        }
    }
}
