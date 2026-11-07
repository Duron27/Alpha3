#ifndef ALPHA3_SUPPORT_VULKAN_RESOURCES_H
#define ALPHA3_SUPPORT_VULKAN_RESOURCES_H

// What the translator draws with, made from OSG's objects as they are met: buffers for its arrays and index lists,
// images for its textures (with their samplers) and for the textures and render buffers its cameras draw into. Kept by
// the OSG object they were made from and remade when it changes (its modified count); let go some frames after the object
// is gone. Per frame memory (uniforms, arrays that change every frame, uploads) comes from a ring of mapped buffers.
#include "vk.hpp"

#include <osg/observer_ptr>

#include <array>
#include <cstdint>
#include <functional>
#include <map>
#include <memory>
#include <unordered_map>
#include <vector>

struct VmaAllocation_T;

namespace osg
{
    class BufferData;
    class Image;
    class RenderBuffer;
    class Texture;
}

namespace Alpha3::Vk
{
    class Context;

    struct Buffer
    {
        VkBuffer mBuffer = VK_NULL_HANDLE;
        VmaAllocation_T* mMemory = nullptr;
        uint8_t* mMapped = nullptr;
        VkDeviceSize mSize = 0;
    };

    // A piece of the frame's memory
    struct Span
    {
        VkBuffer mBuffer = VK_NULL_HANDLE;
        VkDeviceSize mOffset = 0;
        uint8_t* mData = nullptr;
    };

    struct Image
    {
        VkImage mImage = VK_NULL_HANDLE;
        VmaAllocation_T* mMemory = nullptr;
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
        // the layout it is in between passes: shader read only (color) or depth stencil read only
        VkImageLayout mLayout = VK_IMAGE_LAYOUT_UNDEFINED;
        std::map<VkImageViewType, VkImageView> mViews; // for sampling, by view type
        VkImageView mAttachmentView = VK_NULL_HANDLE;
        uint64_t mLastUse = 0;

        VkImageLayout readLayout() const
        {
            return mDepthAspect ? VK_IMAGE_LAYOUT_DEPTH_STENCIL_READ_ONLY_OPTIMAL
                                : VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        }
    };

    class Resources
    {
    public:
        uint64_t mStreamedBytes = 0; // what stream() handed out since the translator last reported it
        explicit Resources(Context& context);
        ~Resources();

        // At the start of a frame (its command buffer begun): the ring's part for this frame is free again, and what
        // was let go of long enough ago is destroyed
        void beginFrame();

        // Mapped memory for this frame only
        Span stream(VkDeviceSize size, VkDeviceSize alignment);

        // The buffer that holds the data of an array or index list (made or remade now if needed). Data that changes
        // every frame is copied to the frame's memory instead, hence the offset.
        // streamed: set when the buffer is in this frame's memory (good for this frame only)
        bool buffer(const osg::BufferData& data, VkBuffer& buffer, VkDeviceSize& offset, bool* streamed = nullptr);
        // An index list of GL_UNSIGNED_BYTE, or one that needs another primitive (quads), as 16 or 32 bit indices
        // made once: converter fills the given vector from the data
        bool convertedIndices(const osg::BufferData& data, uint32_t variant,
            const std::function<void(std::vector<uint32_t>&)>& converter, VkBuffer& buffer, VkDeviceSize& offset,
            uint32_t& count);

        // The image of a texture: its osg::Image uploaded (with mip levels), or what a camera drew into it. Null when
        // it has neither yet, or when its upload waits for a later frame (this frame's upload budget is used up).
        Image* texture(const osg::Texture& texture);
        VkSampler sampler(const osg::Texture* texture, bool shadow);
        // A 1x1 image to bind where a shader samples nothing: white, or a depth of 1 for shadow samplers
        Image* dummy(bool depth);
        VkImageView view(Image& image, VkImageViewType type);

        // The image a camera draws into for a texture or a render buffer (made at the size and format they ask for)
        Image* renderTarget(const osg::Texture& texture);
        // the same at a size and format of the caller's (a texture filled by a copy, which has no size of its own)
        Image* renderTarget(const osg::Texture& texture, uint32_t width, uint32_t height, unsigned internalFormat);
        Image* renderTarget(const osg::RenderBuffer& buffer);
        Image* renderTarget(const void* key, uint32_t width, uint32_t height, unsigned internalFormat);

        VkFramebuffer framebuffer(VkRenderPass pass, const std::vector<VkImageView>& views, uint32_t width,
            uint32_t height);

        // Memory the GPU copies into for the CPU to read (cached), good until this frame's slot comes round again;
        // read(): what was copied, once the GPU has finished the frame
        Buffer readbackBuffer(VkDeviceSize size);
        const uint8_t* read(const Buffer& buffer);

        VkSampler nearestSampler();
        VkSampler linearSampler();

    private:
        struct BufferEntry
        {
            // held: the object stays while the entry does (let go when nothing else holds it), so no lock is needed
            osg::ref_ptr<const osg::BufferData> mData;
            Buffer mBuffer;
            unsigned mModified = 0;
            uint64_t mChangedFrame = 0;
            bool mDynamic = false;
            uint64_t mStreamedFrame = ~0ull;
            Span mStreamed;
            uint64_t mLastUse = 0;
            uint64_t mCheckedFrame = ~0ull; // the frame the object was last checked to be the one the entry is for
        };
        struct ConvertedEntry
        {
            // held: the object stays while the entry does (let go when nothing else holds it), so no lock is needed
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
        };
        struct TargetEntry
        {
            std::unique_ptr<Image> mImage;
            unsigned mFormat = 0;
            uint64_t mLastUse = 0;
        };
        struct Chunk
        {
            Buffer mBuffer;
            VkDeviceSize mUsed = 0;
        };

        Buffer createBuffer(VkDeviceSize size, VkBufferUsageFlags usage, bool readback = false);
        void destroyLater(Buffer& buffer);
        void destroyLater(std::unique_ptr<Image>& image);
        std::unique_ptr<Image> createImage(VkFormat format, VkImageViewType type, uint32_t width, uint32_t height,
            uint32_t depth, uint32_t mips, uint32_t layers, VkImageUsageFlags usage);
        std::unique_ptr<Image> upload(const osg::Texture& texture);
        void transition(Image& image, VkImageLayout from, VkImageLayout to, uint32_t baseMip, uint32_t mips);
        void purge();

        Context& mContext;
        std::array<std::vector<Chunk>, 8> mChunks; // by frame slot
        std::unordered_map<const osg::BufferData*, BufferEntry> mBuffers;
        std::map<std::pair<const osg::BufferData*, uint32_t>, ConvertedEntry> mConverted;
        std::unordered_map<const osg::Texture*, TextureEntry> mTextures;
        std::map<const void*, TargetEntry> mTargets;
        std::map<std::array<uint32_t, 12>, VkSampler> mSamplers;
        std::map<std::vector<uint64_t>, VkFramebuffer> mFramebuffers;
        std::unique_ptr<Image> mDummyColor;
        std::unique_ptr<Image> mDummyDepth;
        VkSampler mNearest = VK_NULL_HANDLE;
        VkSampler mLinear = VK_NULL_HANDLE;
        VkDeviceSize mUploadedThisFrame = 0;
        uint64_t mFrame = 0;
    };

    // The Vulkan format for an OpenGL internal format of a render target (UNDEFINED when there is none)
    VkFormat targetFormat(unsigned internalFormat, VkFormat depthStencilFormat);
    bool isDepthFormat(VkFormat format);
    bool hasStencil(VkFormat format);
}

#endif
