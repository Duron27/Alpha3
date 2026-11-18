#ifndef ALPHA3_SUPPORT_VULKAN_CONTEXT_H
#define ALPHA3_SUPPORT_VULKAN_CONTEXT_H

#include "device.hpp"

#include <chrono>
#include <cstdint>
#include <memory>
#include <string>
#include <functional>
#include <map>
#include <tuple>
#include <unordered_map>
#include <vector>

struct ANativeWindow;

namespace Alpha3::Vk
{
    // the most frames in flight (frame generation's frames count too: 2 rendered frames and the generated ones after
    // each); 2 otherwise
    constexpr unsigned sFramesInFlight = 8;

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
        VkCommandBuffer commands() const { return mFrames[mFrameSlot].mCommands[mPart]; }
        // Counts the command buffers begun: state bound on an earlier one (pipelines, buffers) is gone
        uint64_t commandsGeneration() const { return mCommandsGeneration; }
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
        // In a render pass just begun: Turnip draws it straight to memory instead of in tiles (for a pass whose geometry
        // is most of its cost, as the water's reflection: in tiles it is drawn again for each)
        void drawDirect();
        // the frame's first window pass clears the image (black) rather than leaving what it held undefined
        void clearWindowThisFrame() { mClearWindow = true; }
        unsigned frameSlot() const { return mFrameSlot; }
        uint64_t frameNumber() const { return mFrameNumber; }
        // CPU seconds spent waiting since the translator last reported them (its periodic log line): for a swap chain
        // image (acquire: the display holds them all), in vkQueuePresentKHR, and for a frame slot's fence (the GPU)
        double mAcquireSeconds = 0.0, mPresentSeconds = 0.0, mFenceSeconds = 0.0;
        // With diagnostics(): the GPU's time per frame from when each frame's fence signalled (any driver, no
        // timestamps needed), and the kernel's GPU load and clock where an app may read them. One line; resets.
        std::string gpuDiagnostics();
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

        // The window passes to draw into an image of the context's own, which windowImage() gives (from the next
        // swap chain made; asking for it again changes nothing). For the effects (reshade.cpp), which read the window.
        void wantOwnWindow(bool want)
        {
            if (want != mWantOwnWindow)
            {
                mWantOwnWindow = want;
                mRecreate = true;
            }
        }
        // This frame's window image when the window passes draw into one of the context's own (in
        // VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL between passes), else null
        VkImage windowImage() const
        {
            return mOwnWindow && mAcquired && mFrameSlot < mWindowImages.size() ? mWindowImages[mFrameSlot].mImage
                                                                                  : VK_NULL_HANDLE;
        }

        // color1: a second colour attachment (the scene's normals), after the first
        VkRenderPass renderPass(
            const AttachmentUse& color, const AttachmentUse& depth, const AttachmentUse& color1 = AttachmentUse{});

    private:
        explicit Context(Device& device);
        bool createSurface();
        bool createSwapchain();
        void destroySwapchain();
        // Pre-rotation (Android, the display turned from its natural way): the swap chain is made in the display's
        // own orientation, so the system shows it as it is (no composition on the GPU each frame), and the window
        // passes draw a landscape image of their own (one a frame slot), copied turned into the swap chain image at
        // the end of the frame. Everything that draws on the window sees mExtent, the landscape size, either way.
        bool createRotation();
        void destroyRotation();
        void rotateToWindow();
        VkImageLayout windowLayout() const
        {
            return mOwnWindow ? VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL : VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
        }
        bool acquire();
        // Submits the frame's first part (before the swap chain image is acquired) and begins the second
        void submitFirstPart();

        struct Frame
        {
            VkCommandPool mPool = VK_NULL_HANDLE;
            // a frame is submitted in two parts: what is drawn before the window's first pass (render targets) at
            // once, so the GPU starts on it while the swap chain image is waited for, and the rest with the image
            VkCommandBuffer mCommands[2]{};
            VkCommandBuffer mUploads[2]{};
            bool mUploadsBegun[2]{};
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

        // pre-rotation (createRotation)
        bool mRotated = false;
        // the window passes draw into images of the context's own (mWindowImages), copied (turned when mRotated) into
        // the swap chain image at the end of the frame: when pre-rotated, or when asked (wantOwnWindow: the ReShade
        // effects read and write the window)
        bool mOwnWindow = false;
        bool mWantOwnWindow = false;
        bool mRotationFailed = false; // set up once and failed: not tried again
        int mRotateMode = 0; // 1 a quarter turn, 2 a half, 3 three quarters (the shader's mode)
        VkExtent2D mSwapExtent{ 0, 0 }; // the swap chain images' size (mExtent turned)
        struct WindowImage
        {
            VkImage mImage = VK_NULL_HANDLE;
            VmaAllocation_T* mMemory = nullptr;
            VkImageView mView = VK_NULL_HANDLE;
            VkFramebuffer mFramebuffer = VK_NULL_HANDLE; // with the window's depth, for the window passes
            VkDescriptorSet mSet = VK_NULL_HANDLE; // the rotation pass reads it through this
        };
        std::vector<WindowImage> mWindowImages; // one a frame slot
        std::vector<VkFramebuffer> mRotateFramebuffers; // one a swap chain image
        VkRenderPass mRotatePass = VK_NULL_HANDLE;
        VkFormat mRotateFormat = VK_FORMAT_UNDEFINED; // the swap chain format the pass and pipeline were made for
        VkPipeline mRotatePipeline = VK_NULL_HANDLE;
        VkPipelineLayout mRotateLayout = VK_NULL_HANDLE;
        VkDescriptorSetLayout mRotateSetLayout = VK_NULL_HANDLE;
        VkDescriptorPool mRotatePool = VK_NULL_HANDLE;
        VkSampler mRotateSampler = VK_NULL_HANDLE;

        std::vector<std::function<void()>> mAfterFrame;
        // the pools of the kept sets (sets freed one at a time), and which pool each set came from
        std::vector<VkDescriptorPool> mKeptPools;
        std::unordered_map<VkDescriptorSet, VkDescriptorPool> mKeptOwners;
        Frame mFrames[sFramesInFlight];
        struct FenceTimer;
        std::shared_ptr<FenceTimer> mFenceTimer; // diagnostics() only
        std::chrono::steady_clock::time_point mFirstSubmit{}; // the frame's first submit (its first part's)
        bool mFirstSubmitted = false;
        unsigned mFrameSlot = 0;
        unsigned mInFlight = 2;
        unsigned mPart = 0; // which of the frame's command buffers is being recorded
        uint64_t mCommandsGeneration = 0;
        uint64_t mFrameNumber = 0;
        bool mBegun = false;
        bool mAcquired = false;
        bool mWindowDrawn = false; // a window pass ran this frame (the image left in PRESENT_SRC layout)
        bool mInWindowPass = false;
        bool mClearWindow = false; // clearWindowThisFrame
        uint32_t mImage = 0;

        std::map<std::tuple<decltype(AttachmentUse().key()), decltype(AttachmentUse().key()),
                     decltype(AttachmentUse().key())>,
            VkRenderPass>
            mPasses;
    };
}

#endif
