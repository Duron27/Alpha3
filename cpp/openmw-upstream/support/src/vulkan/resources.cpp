#include "resources.hpp"

#include "../support.hpp"

#include "context.hpp"
#include "vma.hpp"

#include <algorithm>
#include <cmath>
#include <cstring>

#include <osg/BufferObject>
#include <osg/Image>
#include <osg/Texture>
#include <osg/FrameBufferObject>

#include <components/debug/debuglog.hpp>

namespace Alpha3::Vk
{
    namespace
    {
        // GL enums the translation reads (OSG's headers do not have all of them on every platform)
        constexpr unsigned glRgb = 0x1907, glRgba = 0x1908, glBgr = 0x80E0, glBgra = 0x80E1, glRed = 0x1903,
                           glRg = 0x8227, glAlpha = 0x1906, glLuminance = 0x1909, glLuminanceAlpha = 0x190A,
                           glUnsignedByte = 0x1401, glFloat = 0x1406, glHalfFloat = 0x140B, glHalfFloatOes = 0x8D61,
                           glUnsignedShort = 0x1403, glDepthComponent = 0x1902, glDepthStencil = 0x84F9,
                           glUnsignedInt248 = 0x84FA, glUnsignedInt = 0x1405, glUnsignedShort565 = 0x8363,
                           glUnsignedShort5551 = 0x8034;

        constexpr VkDeviceSize sChunkSize = 16 << 20;
        constexpr VkDeviceSize sUploadBudget = 48 << 20; // texture bytes uploaded per frame, the rest waits

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

        VkFilter filter(int mode)
        {
            return (mode == 0x2600 || mode == 0x2700 || mode == 0x2702) ? VK_FILTER_NEAREST : VK_FILTER_LINEAR;
        }

        VkSamplerAddressMode addressMode(int wrap)
        {
            switch (wrap)
            {
                case 0x2901: // GL_REPEAT
                    return VK_SAMPLER_ADDRESS_MODE_REPEAT;
                case 0x8370: // GL_MIRRORED_REPEAT
                    return VK_SAMPLER_ADDRESS_MODE_MIRRORED_REPEAT;
                case 0x812D: // GL_CLAMP_TO_BORDER
                    return VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_BORDER;
                default: // GL_CLAMP, GL_CLAMP_TO_EDGE
                    return VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
            }
        }

        VkCompareOp compareOp(unsigned func)
        {
            switch (func)
            {
                case 0x0200:
                    return VK_COMPARE_OP_NEVER;
                case 0x0201:
                    return VK_COMPARE_OP_LESS;
                case 0x0202:
                    return VK_COMPARE_OP_EQUAL;
                case 0x0203:
                    return VK_COMPARE_OP_LESS_OR_EQUAL;
                case 0x0204:
                    return VK_COMPARE_OP_GREATER;
                case 0x0205:
                    return VK_COMPARE_OP_NOT_EQUAL;
                case 0x0206:
                    return VK_COMPARE_OP_GREATER_OR_EQUAL;
                default:
                    return VK_COMPARE_OP_ALWAYS;
            }
        }

        bool formatSupports(VkPhysicalDevice gpu, VkFormat format, VkFormatFeatureFlags features)
        {
            VkFormatProperties properties{};
            vkGetPhysicalDeviceFormatProperties(gpu, format, &properties);
            return (properties.optimalTilingFeatures & features) == features;
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

    Buffer Resources::readbackBuffer(VkDeviceSize size)
    {
        Buffer buffer = createBuffer(size, VK_BUFFER_USAGE_TRANSFER_DST_BIT, true);
        Buffer gone = buffer;
        destroyLater(gone);
        return buffer;
    }

    const uint8_t* Resources::read(const Buffer& buffer)
    {
        if (buffer.mMapped == nullptr)
            return nullptr;
        vmaInvalidateAllocation(mContext.device().mAllocator, buffer.mMemory, 0, VK_WHOLE_SIZE);
        return buffer.mMapped;
    }

    Buffer Resources::createBuffer(VkDeviceSize size, VkBufferUsageFlags usage, bool readback)
    {
        Buffer buffer;
        VkBufferCreateInfo info{ VK_STRUCTURE_TYPE_BUFFER_CREATE_INFO };
        info.size = std::max<VkDeviceSize>(size, 16);
        info.usage = usage;
        info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        // on a phone all memory is the GPU's and the CPU's: written in place, no staging
        VmaAllocationCreateInfo memory{};
        memory.usage = VMA_MEMORY_USAGE_AUTO;
        memory.flags = (readback ? VMA_ALLOCATION_CREATE_HOST_ACCESS_RANDOM_BIT
                                 : VMA_ALLOCATION_CREATE_HOST_ACCESS_SEQUENTIAL_WRITE_BIT)
            | VMA_ALLOCATION_CREATE_MAPPED_BIT;
        VmaAllocationInfo allocated{};
        const VkResult result = vmaCreateBuffer(
            mContext.device().mAllocator, &info, &memory, &buffer.mBuffer, &buffer.mMemory, &allocated);
        if (result != VK_SUCCESS)
        {
            Log(Debug::Error) << "Vulkan: a buffer of " << size << " bytes: " << resultName(result);
            return {};
        }
        buffer.mMapped = static_cast<uint8_t*>(allocated.pMappedData);
        buffer.mSize = info.size;
        return buffer;
    }

    void Resources::destroyLater(Buffer& buffer)
    {
        if (buffer.mBuffer == VK_NULL_HANDLE)
            return;
        VmaAllocator allocator = mContext.device().mAllocator;
        VkBuffer handle = buffer.mBuffer;
        VmaAllocation memory = buffer.mMemory;
        mContext.releaseLater([allocator, handle, memory] { vmaDestroyBuffer(allocator, handle, memory); });
        buffer = {};
    }

    void Resources::destroyLater(std::unique_ptr<Image>& image)
    {
        if (!image)
            return;
        ++mImageGeneration;
        // the framebuffers that hold it go with it
        for (auto it = mFramebuffers.begin(); it != mFramebuffers.end();)
        {
            const bool uses = image->mAttachmentView != VK_NULL_HANDLE
                && std::find(it->first.begin(), it->first.end(), reinterpret_cast<uint64_t>(image->mAttachmentView))
                    != it->first.end();
            if (uses)
            {
                VkDevice device = mContext.vkDevice();
                VkFramebuffer framebuffer = it->second;
                mContext.releaseLater([device, framebuffer] { vkDestroyFramebuffer(device, framebuffer, nullptr); });
                it = mFramebuffers.erase(it);
            }
            else
                ++it;
        }
        std::shared_ptr<Image> held(image.release());
        VkDevice device = mContext.vkDevice();
        VmaAllocator allocator = mContext.device().mAllocator;
        mContext.releaseLater([device, allocator, held] {
            for (const auto& [type, view] : held->mViews)
                vkDestroyImageView(device, view, nullptr);
            if (held->mAttachmentView != VK_NULL_HANDLE)
                vkDestroyImageView(device, held->mAttachmentView, nullptr);
            vmaDestroyImage(allocator, held->mImage, held->mMemory);
        });
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

    Span Resources::stream(VkDeviceSize size, VkDeviceSize alignment)
    {
        mStreamedBytes += size;
        std::vector<Chunk>& chunks = mChunks[mContext.frameSlot()];
        for (Chunk& chunk : chunks)
        {
            const VkDeviceSize at = (chunk.mUsed + alignment - 1) / alignment * alignment;
            if (at + size <= chunk.mBuffer.mSize)
            {
                chunk.mUsed = at + size;
                return { chunk.mBuffer.mBuffer, at, chunk.mBuffer.mMapped + at };
            }
        }
        Chunk chunk;
        chunk.mBuffer = createBuffer(std::max(sChunkSize, size),
            VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT | VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT
                | VK_BUFFER_USAGE_TRANSFER_SRC_BIT);
        if (chunk.mBuffer.mBuffer == VK_NULL_HANDLE)
            return {};
        chunk.mUsed = size;
        chunks.push_back(chunk);
        return { chunk.mBuffer.mBuffer, 0, chunk.mBuffer.mMapped };
    }

    VkBuffer Resources::storage(const osg::BufferData& data, bool copy)
    {
        const unsigned size = data.getTotalDataSize();
        if (size == 0 || data.getDataPointer() == nullptr)
            return VK_NULL_HANDLE;
        StorageEntry& entry = mStorage[&data];
        entry.mLastUse = mFrame;
        const bool same = entry.mBuffer.mBuffer != VK_NULL_HANDLE && entry.mData.get() == &data;
        if (same && entry.mBuffer.mSize >= size)
        {
            if (entry.mModified == data.getModifiedCount() || !copy)
                return entry.mBuffer.mBuffer;
            Span span = stream(size, 16);
            if (span.mData == nullptr)
                return entry.mBuffer.mBuffer;
            std::memcpy(span.mData, data.getDataPointer(), size);
            // with the compute dispatches that use it (Translator::dispatchCompute), ahead of the frame's commands
            VkCommandBuffer commands = mContext.uploadCommands();
            // after the shaders that used it before (earlier in this frame or in frames before), before those after
            VkMemoryBarrier before{ VK_STRUCTURE_TYPE_MEMORY_BARRIER };
            before.srcAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
            before.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            vkCmdPipelineBarrier(commands,
                VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
                    | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, 0, 1, &before, 0, nullptr, 0, nullptr);
            const VkBufferCopy region{ span.mOffset, 0, size };
            vkCmdCopyBuffer(commands, span.mBuffer, entry.mBuffer.mBuffer, 1, &region);
            VkMemoryBarrier after{ VK_STRUCTURE_TYPE_MEMORY_BARRIER };
            after.srcAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
            after.dstAccessMask = VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_SHADER_WRITE_BIT;
            vkCmdPipelineBarrier(commands, VK_PIPELINE_STAGE_TRANSFER_BIT,
                VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
                    | VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                0, 1, &after, 0, nullptr, 0, nullptr);
            entry.mModified = data.getModifiedCount();
            return entry.mBuffer.mBuffer;
        }
        // new, or grown (the point lights' list): a new buffer, room to grow, written in place (no GPU use yet)
        destroyLater(entry.mBuffer);
        entry.mData = &data;
        entry.mBuffer = createBuffer(same ? size + size / 2 : size,
            VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT | VK_BUFFER_USAGE_TRANSFER_DST_BIT);
        if (entry.mBuffer.mBuffer == VK_NULL_HANDLE)
            return VK_NULL_HANDLE;
        std::memcpy(entry.mBuffer.mMapped, data.getDataPointer(), size);
        vmaFlushAllocation(mContext.device().mAllocator, entry.mBuffer.mMemory, 0, VK_WHOLE_SIZE);
        entry.mModified = data.getModifiedCount();
        return entry.mBuffer.mBuffer;
    }

    VkBuffer Resources::dummyStorage()
    {
        if (mDummyStorage.mBuffer == VK_NULL_HANDLE)
        {
            mDummyStorage = createBuffer(4096, VK_BUFFER_USAGE_STORAGE_BUFFER_BIT | VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT);
            if (mDummyStorage.mBuffer == VK_NULL_HANDLE)
                return VK_NULL_HANDLE;
            std::memset(mDummyStorage.mMapped, 0, 4096);
            vmaFlushAllocation(mContext.device().mAllocator, mDummyStorage.mMemory, 0, VK_WHOLE_SIZE);
        }
        return mDummyStorage.mBuffer;
    }

    bool Resources::buffer(const osg::BufferData& data, VkBuffer& buffer, VkDeviceSize& offset, bool* streamed)
    {
        const unsigned size = data.getTotalDataSize();
        if (size == 0 || data.getDataPointer() == nullptr)
            return false;
        BufferEntry& entry = mBuffers[&data];
        // checked already this frame and unchanged: the buffer it has
        if (entry.mCheckedFrame == mFrame && entry.mModified == data.getModifiedCount()
            && entry.mBuffer.mBuffer != VK_NULL_HANDLE)
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
        const bool same = entry.mBuffer.mBuffer != VK_NULL_HANDLE && entry.mData.get() == &data;
        if (same && entry.mModified == data.getModifiedCount())
        {
            if (!entry.mDynamic)
            {
                buffer = entry.mBuffer.mBuffer;
                offset = 0;
                return true;
            }
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
        entry.mBuffer = createBuffer(size, VK_BUFFER_USAGE_VERTEX_BUFFER_BIT | VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
        if (entry.mBuffer.mBuffer == VK_NULL_HANDLE)
            return false;
        std::memcpy(entry.mBuffer.mMapped, data.getDataPointer(), size);
        vmaFlushAllocation(mContext.device().mAllocator, entry.mBuffer.mMemory, 0, VK_WHOLE_SIZE);
        buffer = entry.mBuffer.mBuffer;
        offset = 0;
        return true;
    }

    bool Resources::packedArray(const osg::Array& array, bool direction, VkBuffer& buffer, VkDeviceSize& offset)
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
        entry.mBuffer = createBuffer(packed.size(), VK_BUFFER_USAGE_VERTEX_BUFFER_BIT);
        if (entry.mBuffer.mBuffer == VK_NULL_HANDLE)
            return false;
        std::memcpy(entry.mBuffer.mMapped, packed.data(), packed.size());
        vmaFlushAllocation(mContext.device().mAllocator, entry.mBuffer.mMemory, 0, VK_WHOLE_SIZE);
        entry.mCount = count;
        buffer = entry.mBuffer.mBuffer;
        offset = 0;
        return true;
    }

    bool Resources::convertedIndices(const osg::BufferData& data, uint32_t variant,
        const std::function<void(std::vector<uint32_t>&)>& converter, VkBuffer& buffer, VkDeviceSize& offset,
        uint32_t& count)
    {
        ConvertedEntry& entry = mConverted[{ &data, variant }];
        entry.mLastUse = mFrame;
        const bool same = entry.mBuffer.mBuffer != VK_NULL_HANDLE && entry.mData.get() == &data;
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
            entry.mBuffer = createBuffer(indices.size() * 4, VK_BUFFER_USAGE_INDEX_BUFFER_BIT);
            if (entry.mBuffer.mBuffer == VK_NULL_HANDLE)
                return false;
            std::memcpy(entry.mBuffer.mMapped, indices.data(), indices.size() * 4);
            vmaFlushAllocation(mContext.device().mAllocator, entry.mBuffer.mMemory, 0, VK_WHOLE_SIZE);
        }
        buffer = entry.mBuffer.mBuffer;
        offset = 0;
        count = entry.mCount;
        return count > 0;
    }

    std::unique_ptr<Image> Resources::createImage(VkFormat format, VkImageViewType type, uint32_t width,
        uint32_t height, uint32_t depth, uint32_t mips, uint32_t layers, VkImageUsageFlags usage)
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

        VkImageCreateInfo info{ VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO };
        info.imageType = type == VK_IMAGE_VIEW_TYPE_3D ? VK_IMAGE_TYPE_3D : VK_IMAGE_TYPE_2D;
        info.format = format;
        info.extent = { image->mWidth, image->mHeight, image->mDepth };
        info.mipLevels = image->mMips;
        info.arrayLayers = image->mLayers;
        info.samples = VK_SAMPLE_COUNT_1_BIT;
        info.tiling = VK_IMAGE_TILING_OPTIMAL;
        info.usage = usage;
        info.sharingMode = VK_SHARING_MODE_EXCLUSIVE;
        info.initialLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        if (type == VK_IMAGE_VIEW_TYPE_CUBE)
            info.flags |= VK_IMAGE_CREATE_CUBE_COMPATIBLE_BIT;
        VmaAllocationCreateInfo memory{};
        memory.usage = VMA_MEMORY_USAGE_AUTO_PREFER_DEVICE;
        const VkResult result
            = vmaCreateImage(mContext.device().mAllocator, &info, &memory, &image->mImage, &image->mMemory, nullptr);
        if (result != VK_SUCCESS)
        {
            Log(Debug::Error) << "Vulkan: an image " << width << "x" << height << " format " << format << ": "
                              << resultName(result);
            return nullptr;
        }
        if (usage & (VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT | VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT))
        {
            VkImageViewCreateInfo viewInfo{ VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO };
            viewInfo.image = image->mImage;
            viewInfo.viewType = VK_IMAGE_VIEW_TYPE_2D;
            viewInfo.format = format;
            viewInfo.subresourceRange.aspectMask = image->mDepthAspect
                ? (VK_IMAGE_ASPECT_DEPTH_BIT | (image->mStencilAspect ? VK_IMAGE_ASPECT_STENCIL_BIT : 0))
                : VK_IMAGE_ASPECT_COLOR_BIT;
            viewInfo.subresourceRange.levelCount = 1;
            viewInfo.subresourceRange.layerCount = 1;
            vkCreateImageView(mContext.vkDevice(), &viewInfo, nullptr, &image->mAttachmentView);
        }
        return image;
    }

    VkImageView Resources::view(Image& image, VkImageViewType type)
    {
        // a 2D image is also a one layer array; anything else is seen as it is
        if (!(image.mType == VK_IMAGE_VIEW_TYPE_2D && type == VK_IMAGE_VIEW_TYPE_2D_ARRAY))
            type = image.mType;
        auto found = image.mViews.find(type);
        if (found != image.mViews.end())
            return found->second;
        VkImageViewCreateInfo info{ VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO };
        info.image = image.mImage;
        info.viewType = type;
        info.format = image.mFormat;
        info.components = image.mSwizzle;
        info.subresourceRange.aspectMask = image.mDepthAspect ? VK_IMAGE_ASPECT_DEPTH_BIT : VK_IMAGE_ASPECT_COLOR_BIT;
        info.subresourceRange.levelCount = image.mMips;
        info.subresourceRange.layerCount = image.mLayers;
        VkImageView view = VK_NULL_HANDLE;
        vkCreateImageView(mContext.vkDevice(), &info, nullptr, &view);
        image.mViews[type] = view;
        return view;
    }

    void Resources::transition(Image& image, VkImageLayout from, VkImageLayout to, uint32_t baseMip, uint32_t mips)
    {
        VkImageMemoryBarrier barrier{ VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER };
        barrier.oldLayout = from;
        barrier.newLayout = to;
        barrier.srcQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
        barrier.image = image.mImage;
        barrier.subresourceRange.aspectMask = image.mDepthAspect
            ? (VK_IMAGE_ASPECT_DEPTH_BIT | (image.mStencilAspect ? VK_IMAGE_ASPECT_STENCIL_BIT : 0))
            : VK_IMAGE_ASPECT_COLOR_BIT;
        barrier.subresourceRange.baseMipLevel = baseMip;
        barrier.subresourceRange.levelCount = mips;
        barrier.subresourceRange.layerCount = image.mLayers;
        VkPipelineStageFlags source = VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT;
        VkPipelineStageFlags destination = VK_PIPELINE_STAGE_TRANSFER_BIT;
        if (from == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL || from == VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
        {
            source = VK_PIPELINE_STAGE_TRANSFER_BIT;
            barrier.srcAccessMask = from == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL ? VK_ACCESS_TRANSFER_WRITE_BIT
                                                                                 : VK_ACCESS_TRANSFER_READ_BIT;
        }
        if (to == VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL)
            barrier.dstAccessMask = VK_ACCESS_TRANSFER_WRITE_BIT;
        else if (to == VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL)
            barrier.dstAccessMask = VK_ACCESS_TRANSFER_READ_BIT;
        else
        {
            destination = VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
            barrier.dstAccessMask = VK_ACCESS_SHADER_READ_BIT;
        }
        vkCmdPipelineBarrier(mContext.uploadCommands(), source, destination, 0, 0, nullptr, 0, nullptr, 1, &barrier);
    }

    std::unique_ptr<Image> Resources::upload(const osg::Texture& texture)
    {
        auto& mutableTexture = const_cast<osg::Texture&>(texture);
        const unsigned images = mutableTexture.getNumImages();
        // as OpenGL's upload does (the OSG hook, textureImageLoading): a texture file's pixels let go after an earlier
        // upload are read back, and let go again after this frame (texturerelease.cpp); the preload notes the file and
        // streaming notes the upload (a texture the scene camera never draws is made full size a few seconds after)
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
            const bool supported = (format >= VK_FORMAT_BC1_RGB_UNORM_BLOCK && format <= VK_FORMAT_BC7_SRGB_BLOCK)
                ? device.mBcTextures
                : (format >= VK_FORMAT_ETC2_R8G8B8_UNORM_BLOCK && format <= VK_FORMAT_EAC_R11G11_SNORM_BLOCK)
                ? device.mEtc2Textures
                : device.mAstcTextures;
            if (format == VK_FORMAT_UNDEFINED || !supported)
            {
                static int warned = 0;
                if (warned++ < 10)
                    Log(Debug::Warning) << "Vulkan: compressed texture format 0x" << std::hex
                                        << first->getPixelFormat() << std::dec << " is not supported ("
                                        << texture.getName() << ")";
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
                    Log(Debug::Warning) << "Vulkan: texture pixel format 0x" << std::hex << first->getPixelFormat()
                                        << " type 0x" << first->getDataType() << std::dec << " is not supported";
                return nullptr;
            }
        }

        const uint32_t width = first->s(), height = first->t(), depth = type == VK_IMAGE_VIEW_TYPE_3D ? first->r() : 1;
        const uint32_t storedMips = std::max(1u, first->getNumMipmapLevels());
        const int minFilter = texture.getFilter(osg::Texture::MIN_FILTER);
        const bool wantsMips = minFilter >= 0x2700 && minFilter <= 0x2703;
        const uint32_t fullMips = 1 + static_cast<uint32_t>(std::floor(std::log2(std::max({ width, height, depth }))));
        const bool generate = wantsMips && storedMips == 1 && !compressed && type != VK_IMAGE_VIEW_TYPE_3D
            && formatSupports(device.mPhysical, format,
                VK_FORMAT_FEATURE_BLIT_SRC_BIT | VK_FORMAT_FEATURE_BLIT_DST_BIT
                    | VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT);
        const uint32_t mips = generate ? fullMips : std::min(storedMips, fullMips);

        // the bytes of every level of every layer, as the GPU takes them
        struct Region
        {
            uint32_t mLevel, mLayer, mWidth, mHeight, mDepth;
            const uint8_t* mSource;
            VkDeviceSize mBytes;
            uint32_t mRowBytes, mRowStep, mRows;
        };
        std::vector<Region> regions;
        VkDeviceSize total = 0;
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
                    uint32_t bw, bh, bytes;
                    blockSize(format, bw, bh, bytes);
                    region.mBytes = static_cast<VkDeviceSize>((region.mWidth + bw - 1) / bw)
                        * ((region.mHeight + bh - 1) / bh) * bytes * region.mDepth;
                    region.mRowBytes = region.mRowStep = 0;
                    region.mRows = 0;
                }
                else
                {
                    region.mRowBytes = region.mWidth * layout.mTexelBytes;
                    region.mRows = region.mHeight * region.mDepth;
                    region.mBytes = static_cast<VkDeviceSize>(region.mRowBytes) * region.mRows;
                    // the source's rows: its row length and packing on level 0, tightly packed (as OSG computes
                    // them) on the levels below
                    region.mRowStep = level == 0
                        ? image->getRowStepInBytes()
                        : osg::Image::computeRowWidthInBytes(
                            region.mWidth, image->getPixelFormat(), image->getDataType(), image->getPacking());
                }
                total += (region.mBytes + 15) & ~VkDeviceSize(15);
                regions.push_back(region);
            }
        }
        if (mUploadedThisFrame > 0 && mUploadedThisFrame + total > sUploadBudget)
            return nullptr; // next frame
        mUploadedThisFrame += total;

        Span staging = stream(total, 16);
        if (staging.mData == nullptr)
            return nullptr;
        std::vector<VkBufferImageCopy> copies;
        VkDeviceSize at = 0;
        const unsigned sourceTexelBytes = compressed ? 0 : osg::Image::computePixelSizeInBits(
                                              first->getPixelFormat(), first->getDataType())
                / 8;
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
            VkBufferImageCopy copy{};
            copy.bufferOffset = staging.mOffset + at;
            copy.imageSubresource.aspectMask = VK_IMAGE_ASPECT_COLOR_BIT;
            copy.imageSubresource.mipLevel = region.mLevel;
            copy.imageSubresource.baseArrayLayer = region.mLayer;
            copy.imageSubresource.layerCount = 1;
            copy.imageExtent = { region.mWidth, region.mHeight, region.mDepth };
            copies.push_back(copy);
            at += (region.mBytes + 15) & ~VkDeviceSize(15);
        }

        VkImageUsageFlags usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT;
        if (generate)
            usage |= VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        std::unique_ptr<Image> image = createImage(format, type, width, height, depth, mips, layers, usage);
        if (!image)
            return nullptr;
        if (!compressed)
            image->mSwizzle = layout.mSwizzle;

        VkCommandBuffer commands = mContext.uploadCommands();
        transition(*image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, mips);
        vkCmdCopyBufferToImage(commands, staging.mBuffer, image->mImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            static_cast<uint32_t>(copies.size()), copies.data());
        if (generate)
        {
            for (uint32_t level = 1; level < mips; ++level)
            {
                transition(*image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
                    level - 1, 1);
                VkImageBlit blit{};
                blit.srcSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, level - 1, 0, layers };
                blit.srcOffsets[1] = { static_cast<int32_t>(std::max(width >> (level - 1), 1u)),
                    static_cast<int32_t>(std::max(height >> (level - 1), 1u)), 1 };
                blit.dstSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, level, 0, layers };
                blit.dstOffsets[1] = { static_cast<int32_t>(std::max(width >> level, 1u)),
                    static_cast<int32_t>(std::max(height >> level, 1u)), 1 };
                vkCmdBlitImage(commands, image->mImage, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, image->mImage,
                    VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &blit, VK_FILTER_LINEAR);
                transition(*image, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    level - 1, 1);
            }
            transition(*image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                mips - 1, 1);
        }
        else
            transition(*image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL, 0, mips);
        image->mLayout = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        return image;
    }

    Image* Resources::texture(const osg::Texture& texture, bool shadow, VkSampler& sampler)
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
        VkFormat format = targetFormat(internalFormat, mContext.device().mDepthFormat);
        if (format == VK_FORMAT_UNDEFINED)
        {
            Log(Debug::Warning) << "Vulkan: render target format 0x" << std::hex << internalFormat << std::dec
                                << " is not known, RGBA8 instead";
            format = VK_FORMAT_R8G8B8A8_UNORM;
        }
        const bool depth = isDepthFormat(format);
        // A depth texture is sampled with linear filtering (the shadow maps' comparisons, four texels each): one the
        // device does not filter (D32_SFLOAT on the Adreno 615) gave every comparison as shadowed
        if (depth && !hasStencil(format)
            && !formatSupports(mContext.device().mPhysical, format,
                VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT))
            for (VkFormat other : { VK_FORMAT_X8_D24_UNORM_PACK32, VK_FORMAT_D16_UNORM, mContext.device().mDepthFormat })
                if (formatSupports(mContext.device().mPhysical, other,
                        VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_FORMAT_FEATURE_SAMPLED_IMAGE_FILTER_LINEAR_BIT))
                {
                    format = other;
                    break;
                }
        const VkFormatFeatureFlags needs = depth ? VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT
                                                 : VK_FORMAT_FEATURE_COLOR_ATTACHMENT_BIT;
        if (!formatSupports(mContext.device().mPhysical, format, needs | VK_FORMAT_FEATURE_SAMPLED_IMAGE_BIT))
            format = depth ? mContext.device().mDepthFormat : VK_FORMAT_R16G16B16A16_SFLOAT;
        const VkImageUsageFlags usage = VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT
            | VK_IMAGE_USAGE_TRANSFER_DST_BIT
            | (depth ? VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT : VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT);
        entry.mImage = createImage(format, VK_IMAGE_VIEW_TYPE_2D, width, height, 1, 1, 1, usage);
        entry.mFormat = internalFormat;
        entry.mLastUse = mFrame;
        if (!entry.mImage)
            return nullptr;
        // sampled before anything is drawn into it, it reads as cleared
        transition(*entry.mImage, VK_IMAGE_LAYOUT_UNDEFINED, entry.mImage->readLayout(), 0, 1);
        entry.mImage->mLayout = entry.mImage->readLayout();
        Log(Debug::Verbose) << "Vulkan: render target " << width << "x" << height << " format " << format;
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
        if (depth)
        {
            image = createImage(VK_FORMAT_D16_UNORM, VK_IMAGE_VIEW_TYPE_2D, 1, 1, 1, 1, 1,
                VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
            if (!image)
                return nullptr;
            transition(*image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, 1);
            VkClearDepthStencilValue clear{ 1.f, 0 };
            VkImageSubresourceRange range{ VK_IMAGE_ASPECT_DEPTH_BIT, 0, 1, 0, 1 };
            vkCmdClearDepthStencilImage(
                mContext.uploadCommands(), image->mImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &clear, 1, &range);
            transition(*image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, image->readLayout(), 0, 1);
        }
        else
        {
            image = createImage(VK_FORMAT_R8G8B8A8_UNORM, VK_IMAGE_VIEW_TYPE_2D, 1, 1, 1, 1, 1,
                VK_IMAGE_USAGE_SAMPLED_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT);
            if (!image)
                return nullptr;
            transition(*image, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 0, 1);
            VkClearColorValue clear{ { 1.f, 1.f, 1.f, 1.f } };
            VkImageSubresourceRange range{ VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
            vkCmdClearColorImage(
                mContext.uploadCommands(), image->mImage, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, &clear, 1, &range);
            transition(*image, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, image->readLayout(), 0, 1);
        }
        image->mLayout = image->readLayout();
        return image.get();
    }

    VkSampler Resources::sampler(const osg::Texture* texture, bool shadow)
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

        VkSamplerCreateInfo info{ VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO };
        const int minFilter = static_cast<int>(key[0]);
        info.minFilter = filter(minFilter);
        info.magFilter = filter(static_cast<int>(key[1]));
        info.mipmapMode = (minFilter == 0x2702 || minFilter == 0x2703) ? VK_SAMPLER_MIPMAP_MODE_LINEAR
                                                                       : VK_SAMPLER_MIPMAP_MODE_NEAREST;
        info.addressModeU = addressMode(static_cast<int>(key[2]));
        info.addressModeV = addressMode(static_cast<int>(key[3]));
        info.addressModeW = addressMode(static_cast<int>(key[4]));
        info.mipLodBias = (static_cast<float>(key[9]) - 65536.f) / 256.f;
        const float anisotropy = static_cast<float>(key[5]) / 16.f;
        const Device& device = mContext.device();
        if (anisotropy > 1.f && device.mFeatures.samplerAnisotropy && minFilter >= 0x2700)
        {
            info.anisotropyEnable = VK_TRUE;
            info.maxAnisotropy = std::min(anisotropy, device.mProperties.limits.maxSamplerAnisotropy);
        }
        info.compareEnable = shadow ? VK_TRUE : VK_FALSE;
        info.compareOp = compareOp(key[7]);
        info.minLod = 0.f;
        // without a mipmap filter only the base level is read (as in OpenGL)
        info.maxLod = (minFilter >= 0x2700 && minFilter <= 0x2703) ? std::max(key[10] / 16.f, 0.f) : 0.25f;
        info.borderColor = key[8] == 0 ? VK_BORDER_COLOR_FLOAT_TRANSPARENT_BLACK
            : key[8] == 1             ? VK_BORDER_COLOR_FLOAT_OPAQUE_BLACK
                                      : VK_BORDER_COLOR_FLOAT_OPAQUE_WHITE;
        VkSampler sampler = VK_NULL_HANDLE;
        vkCreateSampler(device.mDevice, &info, nullptr, &sampler);
        mSamplers[key] = sampler;
        return sampler;
    }

    VkSampler Resources::nearestSampler()
    {
        if (mNearest == VK_NULL_HANDLE)
        {
            VkSamplerCreateInfo info{ VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO };
            info.minFilter = info.magFilter = VK_FILTER_NEAREST;
            info.addressModeU = info.addressModeV = info.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
            info.maxLod = 0.25f;
            vkCreateSampler(mContext.vkDevice(), &info, nullptr, &mNearest);
        }
        return mNearest;
    }

    VkSampler Resources::linearSampler()
    {
        if (mLinear == VK_NULL_HANDLE)
        {
            VkSamplerCreateInfo info{ VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO };
            info.minFilter = info.magFilter = VK_FILTER_LINEAR;
            info.addressModeU = info.addressModeV = info.addressModeW = VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE;
            info.maxLod = 0.25f;
            vkCreateSampler(mContext.vkDevice(), &info, nullptr, &mLinear);
        }
        return mLinear;
    }

    VkFramebuffer Resources::framebuffer(
        VkRenderPass pass, const std::vector<VkImageView>& views, uint32_t width, uint32_t height)
    {
        std::vector<uint64_t> key;
        key.push_back(reinterpret_cast<uint64_t>(pass));
        for (VkImageView view : views)
            key.push_back(reinterpret_cast<uint64_t>(view));
        key.push_back(width);
        key.push_back(height);
        auto found = mFramebuffers.find(key);
        if (found != mFramebuffers.end())
            return found->second;
        VkFramebufferCreateInfo info{ VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO };
        info.renderPass = pass;
        info.attachmentCount = static_cast<uint32_t>(views.size());
        info.pAttachments = views.data();
        info.width = width;
        info.height = height;
        info.layers = 1;
        VkFramebuffer framebuffer = VK_NULL_HANDLE;
        const VkResult result = vkCreateFramebuffer(mContext.vkDevice(), &info, nullptr, &framebuffer);
        if (result != VK_SUCCESS)
            Log(Debug::Error) << "Vulkan: vkCreateFramebuffer: " << resultName(result);
        mFramebuffers[key] = framebuffer;
        return framebuffer;
    }

    void Resources::purge()
    {
        for (auto it = mBuffers.begin(); it != mBuffers.end();)
        {
            if (!it->second.mData || it->second.mData->referenceCount() <= 1 || mFrame - it->second.mLastUse > 3600)
            {
                destroyLater(it->second.mBuffer);
                it = mBuffers.erase(it);
            }
            else
                ++it;
        }
        for (auto it = mPacked.begin(); it != mPacked.end();)
        {
            if (!it->second.mData || it->second.mData->referenceCount() <= 1 || mFrame - it->second.mLastUse > 3600)
            {
                destroyLater(it->second.mBuffer);
                it = mPacked.erase(it);
            }
            else
                ++it;
        }
        for (auto it = mStorage.begin(); it != mStorage.end();)
        {
            if (!it->second.mData || it->second.mData->referenceCount() <= 1 || mFrame - it->second.mLastUse > 600)
            {
                destroyLater(it->second.mBuffer);
                it = mStorage.erase(it);
            }
            else
                ++it;
        }
        for (auto it = mConverted.begin(); it != mConverted.end();)
        {
            if (!it->second.mData || it->second.mData->referenceCount() <= 1 || mFrame - it->second.mLastUse > 3600)
            {
                destroyLater(it->second.mBuffer);
                it = mConverted.erase(it);
            }
            else
                ++it;
        }
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
