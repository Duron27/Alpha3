#ifndef ALPHA3_SUPPORT_VULKAN_CONTEXT_H
#define ALPHA3_SUPPORT_VULKAN_CONTEXT_H

#include "device.hpp"

#include <cstdint>
#include <functional>
#include <map>
#include <tuple>
#include <unordered_map>
#include <vector>

struct ANativeWindow;

namespace Alpha3::Vk
{
    constexpr unsigned sFramesInFlight = 2;

    // How a render pass treats one attachment
    struct AttachmentUse
    {
        VkFormat mFormat = VK_FORMAT_UNDEFINED; // UNDEFINED: none
        VkAttachmentLoadOp mLoad = VK_ATTACHMENT_LOAD_OP_LOAD;
        VkAttachmentStoreOp mStore = VK_ATTACHMENT_STORE_OP_STORE;
        VkImageLayout mInitial = VK_IMAGE_LAYOUT_UNDEFINED;
        VkImageLayout mFinal = VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL;
        auto key() const { return std::make_tuple(mFormat, mLoad, mStore, mInitial, mFinal); }
    };

    // The swap chain on the window and the frames drawn into it. The draw thread's: one frame at a time is recorded into
    // one command buffer, sFramesInFlight of them in flight on the GPU.
    class Context
    {
    public:
        // The window's, once the graphics window made it (null with OpenGL ES)
        static Context* get();
        static Context* create(ANativeWindow* window, int width, int height);

        Device& device() { return mDevice; }
        VkDevice vkDevice() const { return mDevice.mDevice; }

        // Starts the frame if it is not started: waits for the GPU to be done with the frame recorded sFramesInFlight
        // ago, then lets go of what it used. False when there is nothing to draw on (no window).
        bool beginFrame();
        bool frameBegun() const { return mBegun; }
        VkCommandBuffer commands() const { return mFrames[mFrameSlot].mCommands; }
        // Commands that go before the frame's (uploads, an image's first layout): submitted ahead of them, so they may
        // be recorded while a render pass is open on the frame's commands
        VkCommandBuffer uploadCommands();
        // A descriptor set of this frame (the pools are reset when the frame's slot comes round again)
        VkDescriptorSet allocateSet(VkDescriptorSetLayout layout);
        // A set kept from frame to frame (a draw state's, made again only when its buffers or images change); it is
        // freed with freeKeptSetLater, once the frames in flight that may use it are done
        VkDescriptorSet allocateKeptSet(VkDescriptorSetLayout layout);
        void freeKeptSetLater(VkDescriptorSet set);
        bool inWindowPass() const { return mInWindowPass; }
        void endWindowPass();
        unsigned frameSlot() const { return mFrameSlot; }
        uint64_t frameNumber() const { return mFrameNumber; }
        // Runs once the GPU is done with this frame (resources released while it may still read them)
        void releaseLater(std::function<void()> release);
        // Runs when this frame is presented, after the GPU has finished it (present() waits for that, so only for
        // what must not wait longer: a read back the main thread is blocked on). At once when there is no window.
        void afterFrame(std::function<void()> work);
        // What was drawn on the window this frame so far, blitted into a colour image (turned the OpenGL way up, as
        // glCopyTexImage2D gives it) that is in the given layout before and after. False when the window can't be read.
        bool blitWindow(VkImage target, uint32_t width, uint32_t height, VkImageLayout layout);

        // The window as a render target, begun as a render pass: clears what is asked, keeps the rest. The swap chain
        // image is acquired on the first one of the frame. False when there is no image (the window is gone).
        bool beginWindowPass(bool clearColor, const float color[4], bool clearDepth, float depth, uint32_t stencil);
        VkExtent2D windowExtent() const { return mExtent; }
        VkFormat windowFormat() const { return mFormat; }
        VkFormat windowDepthFormat() const { return mDevice.mDepthFormat; }
        // A render pass the window's pipelines are made for (compatible with every window pass)
        VkRenderPass windowCompatiblePass();

        // Ends the frame: whatever was recorded is submitted and the image presented (cleared when nothing was drawn on
        // it). The window follows the size given.
        void present(int width, int height);
        // The window was replaced (the app went to the background and came back)
        void setWindow(ANativeWindow* window);

        VkRenderPass renderPass(const AttachmentUse& color, const AttachmentUse& depth);

    private:
        explicit Context(Device& device);
        bool createSurface();
        bool createSwapchain();
        void destroySwapchain();
        bool acquire();

        struct Frame
        {
            VkCommandPool mPool = VK_NULL_HANDLE;
            VkCommandBuffer mCommands = VK_NULL_HANDLE;
            VkCommandBuffer mUploads = VK_NULL_HANDLE;
            bool mUploadsBegun = false;
            std::vector<VkDescriptorPool> mDescriptorPools;
            size_t mDescriptorPool = 0; // the first that may have room
            VkFence mFence = VK_NULL_HANDLE;
            VkSemaphore mAcquired = VK_NULL_HANDLE;
            VkSemaphore mFinished = VK_NULL_HANDLE;
            std::vector<std::function<void()>> mReleases;
        };

        Device& mDevice;
        ANativeWindow* mWindow = nullptr;
        VkSurfaceKHR mSurface = VK_NULL_HANDLE;
        VkSwapchainKHR mSwapchain = VK_NULL_HANDLE;
        VkFormat mFormat = VK_FORMAT_UNDEFINED;
        VkExtent2D mExtent{ 0, 0 };
        VkExtent2D mWanted{ 0, 0 };
        std::vector<VkImage> mImages;
        std::vector<VkImageView> mViews;
        std::vector<VkFramebuffer> mFramebuffers;
        VkImage mDepthImage = VK_NULL_HANDLE;
        VmaAllocation_T* mDepthMemory = nullptr;
        VkImageView mDepthView = VK_NULL_HANDLE;
        bool mRecreate = false;
        // the window as it was when the swap chain was made: a present that is only suboptimal because the picture is
        // not pre-rotated (Android, with the display turned from its natural way) keeps it, otherwise it was remade each frame
        VkSurfaceTransformFlagBitsKHR mSurfaceTransform = VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR;
        VkExtent2D mSurfaceExtent{};
        bool mWindowReadable = false; // the swap chain's images can be copied from

        std::vector<std::function<void()>> mAfterFrame;
        // the pools of the kept sets (sets freed one at a time), and which pool each set came from
        std::vector<VkDescriptorPool> mKeptPools;
        std::unordered_map<VkDescriptorSet, VkDescriptorPool> mKeptOwners;
        Frame mFrames[sFramesInFlight];
        unsigned mFrameSlot = 0;
        uint64_t mFrameNumber = 0;
        bool mBegun = false;
        bool mAcquired = false;
        bool mWindowDrawn = false; // a window pass ran this frame (the image left in PRESENT_SRC layout)
        bool mInWindowPass = false;
        uint32_t mImage = 0;

        std::map<std::tuple<decltype(AttachmentUse().key()), decltype(AttachmentUse().key())>, VkRenderPass> mPasses;
    };
}

#endif
