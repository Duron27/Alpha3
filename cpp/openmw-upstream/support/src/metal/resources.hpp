#ifndef ALPHA3_SUPPORT_METAL_RESOURCES_H
#define ALPHA3_SUPPORT_METAL_RESOURCES_H

// What the translator draws with, made from OSG's objects as they are met (src/vulkan/resources.hpp is Android's):
// buffers for its arrays and index lists, textures for its textures (with their samplers) and for the textures and
// render buffers its cameras draw into. Kept by the OSG object they were made from and remade when it changes (its
// modified count); let go some frames after the object is gone. Per frame memory (uniforms, arrays that change every
// frame, uploads) comes from a ring of shared buffers.
//
// Formats are named by Vulkan's enums (types only, from the Vulkan headers: nothing of Vulkan is called), so that the
// translation of OSG's formats is the Vulkan renderer's; mtlFormat gives Metal's.
#import <Metal/Metal.h>

#include "vkenums.hpp"

#include <osg/Array>
#include <osg/observer_ptr>

#include <array>
#include <cstdint>
#include <functional>
#include <map>
#include <memory>
#include <unordered_map>
#include <vector>

namespace osg
{
    class BufferData;
    class Image;
    class RenderBuffer;
    class Texture;
}

namespace Alpha3::Mtl
{
    class Context;

    struct Buffer
    {
        id<MTLBuffer> mBuffer = nil;
        uint8_t* mMapped = nullptr;
        uint64_t mSize = 0;
    };

    // A piece of the frame's memory
    struct Span
    {
        id<MTLBuffer> mBuffer = nil;
        uint64_t mOffset = 0;
        uint8_t* mData = nullptr;
    };

    struct Image
    {
        id<MTLTexture> mTexture = nil;
        VkFormat mFormat = VK_FORMAT_UNDEFINED;
        VkImageViewType mType = VK_IMAGE_VIEW_TYPE_2D;
        uint32_t mWidth = 1;
        uint32_t mHeight = 1;
        uint32_t mDepth = 1;
        uint32_t mMips = 1;
        uint32_t mLayers = 1;
        bool mDepthAspect = false;
        bool mStencilAspect = false;
        VkComponentMapping mSwizzle{};
        std::map<VkImageViewType, id<MTLTexture>> mViews; // for sampling, by view type (with the swizzle)
        uint64_t mLastUse = 0;
    };

    class Resources
    {
    public:
        uint64_t mStreamedBytes = 0; // what stream() handed out since the translator last reported it
        // goes up each time an image is let go
        uint64_t mImageGeneration = 0;
        explicit Resources(Context& context);
        ~Resources();

        // At the start of a frame: the ring's part for this frame is free again, and what was let go of long enough
        // ago is destroyed
        void beginFrame();
        // At the start of a frame of the context's that is not the translator's (frame generation): only the ring's
        // part for it is free again
        void resetStream();

        // Shared memory for this frame only
        Span stream(uint64_t size, uint64_t alignment);

        // The buffer that holds the data of an array or index list (made or remade now if needed). Data that changes
        // every frame is copied to the frame's memory instead, hence the offset.
        // streamed: set when the buffer is in this frame's memory (good for this frame only)
        bool buffer(const osg::BufferData& data, __strong id<MTLBuffer>& buffer, uint64_t& offset, bool* streamed = nullptr);
        // Float normals (each a unit vector, as 4 signed bytes) or colours (as 4 unsigned bytes), made once; false for
        // what cannot be (colours outside 0 to 1) and for an array that changes: the caller uses buffer()
        bool packedArray(const osg::Array& array, bool direction, __strong id<MTLBuffer>& buffer, uint64_t& offset);
        // An index list of GL_UNSIGNED_BYTE, or one that needs another primitive (quads, fans), as 32 bit indices
        // made once: converter fills the given vector from the data
        bool convertedIndices(const osg::BufferData& data, uint32_t variant,
            const std::function<void(std::vector<uint32_t>&)>& converter, __strong id<MTLBuffer>& buffer, uint64_t& offset,
            uint32_t& count);
        // An array whose elements are not 4 byte aligned (Metal's vertex strides are multiples of 4), its elements
        // spread to a stride of the given size, made once
        bool alignedArray(const osg::Array& array, uint32_t stride, __strong id<MTLBuffer>& buffer, uint64_t& offset);

        // The buffer of a shader storage or uniform buffer binding's data: kept from frame to frame, as compute shaders
        // write into it. Its data is copied in when its modified count changes, by a copy ahead of the frame's commands,
        // only when copy is set; else the buffer keeps what it had (a new one gets the data at once). Nil without data.
        id<MTLBuffer> storage(const osg::BufferData& data, bool copy);
        // A small zeroed buffer for a binding with nothing bound
        id<MTLBuffer> dummyStorage();

        // The image of a texture: its osg::Image uploaded (with mip levels), or what a camera drew into it. Null when
        // it has neither yet, or when its upload waits for a later frame (this frame's upload budget is used up).
        Image* texture(const osg::Texture& texture);
        // texture() and its sampler, both looked up once a frame
        Image* texture(const osg::Texture& texture, bool shadow, __strong id<MTLSamplerState>& sampler);
        id<MTLSamplerState> sampler(const osg::Texture* texture, bool shadow);
        // A 1x1 image to bind where a shader samples nothing: white, or a depth of 1 for shadow samplers
        Image* dummy(bool depth);
        // The texture as a shader of this type samples it (a 2D image is a one layer array too), with its swizzle
        id<MTLTexture> view(Image& image, VkImageViewType type);

        // The image a camera draws into for a texture or a render buffer (made at the size and format they ask for)
        Image* renderTarget(const osg::Texture& texture);
        // the same at a size and format of the caller's (a texture filled by a copy, which has no size of its own)
        Image* renderTarget(const osg::Texture& texture, uint32_t width, uint32_t height, unsigned internalFormat);
        Image* renderTarget(const osg::RenderBuffer& buffer);
        Image* renderTarget(const void* key, uint32_t width, uint32_t height, unsigned internalFormat);

        // Memory the GPU copies into for the CPU to read, good until this frame's slot comes round again
        Buffer readbackBuffer(uint64_t size);

        id<MTLSamplerState> nearestSampler();
        id<MTLSamplerState> linearSampler();

    private:
        struct BufferEntry
        {
            osg::ref_ptr<const osg::BufferData> mData;
            Buffer mBuffer;
            unsigned mModified = 0;
            uint64_t mChangedFrame = 0;
            bool mDynamic = false;
            uint64_t mStreamedFrame = ~0ull;
            Span mStreamed;
            uint64_t mLastUse = 0;
            uint64_t mCheckedFrame = ~0ull;
        };
        struct StorageEntry
        {
            osg::ref_ptr<const osg::BufferData> mData;
            Buffer mBuffer;
            unsigned mModified = 0;
            uint64_t mLastUse = 0;
        };
        struct ConvertedEntry
        {
            osg::ref_ptr<const osg::BufferData> mData;
            Buffer mBuffer;
            unsigned mModified = 0;
            uint32_t mCount = 0;
            uint64_t mLastUse = 0;
        };
        struct TextureEntry
        {
            osg::observer_ptr<const osg::Texture> mTexture;
            std::unique_ptr<Image> mOwned; // its upload; a render target's image is the target entry's
            Image* mImage = nullptr;
            const osg::Image* mSource = nullptr;
            unsigned mSourceModified = 0;
            bool mTarget = false;
            uint64_t mChecked = ~0ull;
            id<MTLSamplerState> mSampler[2] = { nil, nil };
            uint64_t mSamplerFrame[2]{ ~0ull, ~0ull };
        };
        Image* textureChecked(const osg::Texture& texture, TextureEntry& entry);
        struct TargetEntry
        {
            std::unique_ptr<Image> mImage;
            unsigned mFormat = 0;
            uint64_t mLastUse = 0;
        };
        struct Chunk
        {
            Buffer mBuffer;
            uint64_t mUsed = 0;
        };

        Buffer createBuffer(uint64_t size, bool readback = false);
        void destroyLater(Buffer& buffer);
        void destroyLater(std::unique_ptr<Image>& image);
        std::unique_ptr<Image> createImage(VkFormat format, VkImageViewType type, uint32_t width, uint32_t height,
            uint32_t depth, uint32_t mips, uint32_t layers, bool target);
        std::unique_ptr<Image> upload(const osg::Texture& texture);
        void purge();

        Context& mContext;
        std::array<std::vector<Chunk>, 8> mChunks; // by frame slot
        std::unordered_map<const osg::BufferData*, BufferEntry> mBuffers;
        std::map<std::pair<const osg::BufferData*, uint32_t>, ConvertedEntry> mConverted;
        std::unordered_map<const osg::BufferData*, ConvertedEntry> mPacked; // (mCount 0: not one to pack)
        std::map<std::pair<const osg::BufferData*, uint32_t>, ConvertedEntry> mAligned;
        std::unordered_map<const osg::BufferData*, StorageEntry> mStorage;
        Buffer mDummyStorage;
        std::unordered_map<const osg::Texture*, TextureEntry> mTextures;
        std::map<const void*, TargetEntry> mTargets;
        std::map<std::array<uint32_t, 12>, id<MTLSamplerState>> mSamplers;
        std::unique_ptr<Image> mDummyColor;
        std::unique_ptr<Image> mDummyDepth;
        id<MTLSamplerState> mNearest = nil;
        id<MTLSamplerState> mLinear = nil;
        uint64_t mUploadedThisFrame = 0;
        uint64_t mFrame = 0;
    };

    // The Vulkan format for an OpenGL internal format of a render target (UNDEFINED when there is none)
    VkFormat targetFormat(unsigned internalFormat, VkFormat depthStencilFormat);
    bool isDepthFormat(VkFormat format);
    bool hasStencil(VkFormat format);
    // Metal's format for one of these (Invalid when it has none)
    MTLPixelFormat mtlFormat(VkFormat format);
    MTLVertexFormat mtlVertexFormat(VkFormat format);
}

#endif
