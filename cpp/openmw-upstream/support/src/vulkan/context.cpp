#include "context.hpp"

#include <algorithm>
#include <chrono>
#include <condition_variable>
#include <cstdio>
#include <deque>
#include <mutex>
#include <sstream>
#include <thread>
#include <cmath>
#include <cstdlib>

#include <android/native_window.h>

#define VMA_STATIC_VULKAN_FUNCTIONS 0
#define VMA_DYNAMIC_VULKAN_FUNCTIONS 1
#define VMA_VULKAN_VERSION 1002000
#include <vk_mem_alloc.h>

#include <alpha3/upstream.hpp>
#include <components/debug/debuglog.hpp>
#include <components/settings/settings.hpp>

namespace
{
    // A lost device does not come back: every later call fails, and the swap chain cannot be made again (the window is
    // still held by the dead one). Stop with the reason in the log rather than crash later on a missing swap chain.
    void stopIfLost(VkResult result, const char* where)
    {
        if (result != VK_ERROR_DEVICE_LOST)
            return;
        Log(Debug::Error) << "Vulkan: the GPU driver lost the device (" << where
                          << "): the GPU hung or faulted on the work sent; the game cannot go on";
        Alpha3::Vk::logDeviceFault();
        std::abort();
    }
}

namespace Alpha3::Vk
{
    namespace
    {
        Context* sContext = nullptr;

        // Every render pass waits for what earlier passes (and transfers) wrote, and makes what it writes visible to
        // whatever samples it later: the attachments of one pass are often the textures of the next
        void addDependencies(VkRenderPassCreateInfo& info, VkSubpassDependency (&dependencies)[3])
        {
            const VkPipelineStageFlags attachmentStages = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT
                | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT | VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT;
            const VkAccessFlags attachmentAccess = VK_ACCESS_COLOR_ATTACHMENT_READ_BIT
                | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_READ_BIT
                | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
            dependencies[0] = {};
            dependencies[0].srcSubpass = VK_SUBPASS_EXTERNAL;
            dependencies[0].dstSubpass = 0;
            dependencies[0].srcStageMask = attachmentStages | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
                | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT;
            dependencies[0].dstStageMask = attachmentStages | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
            dependencies[0].srcAccessMask = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT
                | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT | VK_ACCESS_TRANSFER_WRITE_BIT;
            dependencies[0].dstAccessMask = attachmentAccess | VK_ACCESS_SHADER_READ_BIT;
            dependencies[1] = {};
            dependencies[1].srcSubpass = 0;
            dependencies[1].dstSubpass = VK_SUBPASS_EXTERNAL;
            dependencies[1].srcStageMask = attachmentStages;
            dependencies[1].dstStageMask = attachmentStages | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT
                | VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_TRANSFER_BIT;
            dependencies[1].srcAccessMask
                = VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT;
            dependencies[1].dstAccessMask = attachmentAccess | VK_ACCESS_SHADER_READ_BIT | VK_ACCESS_TRANSFER_READ_BIT;
            // the subpass on itself, from the vertex stage to the vertex stage: what Context::drawDirect's barrier needs.
            // Every pass has it (unused it costs nothing), so that the passes stay compatible with one another and a
            // pipeline made for one serves them all
            dependencies[2] = {};
            dependencies[2].srcSubpass = 0;
            dependencies[2].dstSubpass = 0;
            dependencies[2].srcStageMask = VK_PIPELINE_STAGE_VERTEX_SHADER_BIT;
            dependencies[2].dstStageMask = VK_PIPELINE_STAGE_VERTEX_SHADER_BIT;
            info.dependencyCount = 3;
            info.pDependencies = dependencies;
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
    }

    Context* Context::get()
    {
        return sContext;
    }

    Context::Context(Device& device)
        : mDevice(device)
    {
    }

    Context* Context::create(ANativeWindow* window, int width, int height)
    {
        Device* device = Vk::device();
        if (device == nullptr)
            return nullptr;
        auto* context = new Context(*device);
        context->mWindow = window;
        context->mWanted = { static_cast<uint32_t>(std::max(width, 1)), static_cast<uint32_t>(std::max(height, 1)) };
        // with frame generation, two rendered frames in flight as without it: each is followed by its generated ones
        if (frameGenWanted())
        {
            const char* count = std::getenv("OPENMW_FRAMEGEN_FRAMES");
            const unsigned generated = count ? static_cast<unsigned>(std::clamp(std::atoi(count), 1, 3)) : 1;
            context->mInFlight = std::min(sFramesInFlight, 2 * (1 + generated));
        }
        if (!context->createSurface() || !context->createSwapchain())
        {
            delete context;
            return nullptr;
        }
        for (Frame& frame : context->mFrames)
        {
            VkCommandPoolCreateInfo pool{ VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO };
            pool.flags = VK_COMMAND_POOL_CREATE_TRANSIENT_BIT;
            pool.queueFamilyIndex = device->mQueueFamily;
            vkCreateCommandPool(device->mDevice, &pool, nullptr, &frame.mPool);
            VkCommandBufferAllocateInfo allocate{ VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO };
            allocate.commandPool = frame.mPool;
            allocate.level = VK_COMMAND_BUFFER_LEVEL_PRIMARY;
            allocate.commandBufferCount = 1;
            allocate.commandBufferCount = 2;
            vkAllocateCommandBuffers(device->mDevice, &allocate, frame.mCommands);
            vkAllocateCommandBuffers(device->mDevice, &allocate, frame.mUploads);
            VkFenceCreateInfo fence{ VK_STRUCTURE_TYPE_FENCE_CREATE_INFO };
            fence.flags = VK_FENCE_CREATE_SIGNALED_BIT;
            vkCreateFence(device->mDevice, &fence, nullptr, &frame.mFence);
            VkSemaphoreCreateInfo semaphore{ VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO };
            vkCreateSemaphore(device->mDevice, &semaphore, nullptr, &frame.mAcquired);
            vkCreateSemaphore(device->mDevice, &semaphore, nullptr, &frame.mFinished);
        }
        sContext = context;
        return context;
    }

    bool Context::createSurface()
    {
        VkAndroidSurfaceCreateInfoKHR info{ VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR };
        info.window = mWindow;
        const VkResult result = vkCreateAndroidSurfaceKHR(mDevice.mInstance, &info, nullptr, &mSurface);
        if (result != VK_SUCCESS)
        {
            Log(Debug::Error) << "Vulkan: vkCreateAndroidSurfaceKHR: " << resultName(result);
            return false;
        }
        VkBool32 supported = VK_FALSE;
        vkGetPhysicalDeviceSurfaceSupportKHR(mDevice.mPhysical, mDevice.mQueueFamily, mSurface, &supported);
        if (!supported)
        {
            Log(Debug::Error) << "Vulkan: the queue cannot present to the window";
            return false;
        }
        return true;
    }

    bool Context::createSwapchain()
    {
        VkSurfaceCapabilitiesKHR caps{};
        vkGetPhysicalDeviceSurfaceCapabilitiesKHR(mDevice.mPhysical, mSurface, &caps);
        uint32_t count = 0;
        vkGetPhysicalDeviceSurfaceFormatsKHR(mDevice.mPhysical, mSurface, &count, nullptr);
        std::vector<VkSurfaceFormatKHR> formats(count);
        vkGetPhysicalDeviceSurfaceFormatsKHR(mDevice.mPhysical, mSurface, &count, formats.data());
        if (formats.empty())
        {
            Log(Debug::Error) << "Vulkan: the window offers no formats";
            return false;
        }
        // linear 8 bit RGBA, as the OpenGL ES window is (the engine does its own gamma)
        VkSurfaceFormatKHR chosen = formats[0];
        for (VkFormat wanted : { VK_FORMAT_R8G8B8A8_UNORM, VK_FORMAT_B8G8R8A8_UNORM })
        {
            auto found = std::find_if(formats.begin(), formats.end(),
                [&](const VkSurfaceFormatKHR& f) { return f.format == wanted; });
            if (found != formats.end())
            {
                chosen = *found;
                break;
            }
        }
        mFormat = chosen.format;

        // the size the engine draws at (the compositor scales it to the screen, as with OpenGL ES)
        mExtent.width = std::clamp(mWanted.width, caps.minImageExtent.width, std::max(caps.maxImageExtent.width, 1u));
        mExtent.height = std::clamp(mWanted.height, caps.minImageExtent.height, std::max(caps.maxImageExtent.height, 1u));
        // the present modes, logged; FIFO is used
        int vsyncMode = 1;
        try
        {
            vsyncMode = Settings::Manager::getInt("vsync mode", "Video");
        }
        catch (const std::exception&)
        {
        }
        VkPresentModeKHR presentMode = VK_PRESENT_MODE_FIFO_KHR;
        {
            uint32_t count = 0;
            vkGetPhysicalDeviceSurfacePresentModesKHR(mDevice.mPhysical, mSurface, &count, nullptr);
            std::vector<VkPresentModeKHR> modes(count);
            vkGetPhysicalDeviceSurfacePresentModesKHR(mDevice.mPhysical, mSurface, &count, modes.data());
            std::string names;
            for (VkPresentModeKHR mode : modes)
                names += " " + std::to_string(static_cast<int>(mode));
            // mailbox only on asking: on Android it lets frames replace one another before they are shown (at 51
            // frames a second drawn, 39 reached the screen on the SM-T720, against 51 with FIFO and four images)
            if (vsyncMode == 0 && std::getenv("A3_VK_MAILBOX") != nullptr)
                for (VkPresentModeKHR wanted : { VK_PRESENT_MODE_MAILBOX_KHR, VK_PRESENT_MODE_IMMEDIATE_KHR })
                    if (std::find(modes.begin(), modes.end(), wanted) != modes.end())
                    {
                        presentMode = wanted;
                        break;
                    }
            Log(Debug::Info) << "Vulkan: present modes" << names << "; vsync mode " << vsyncMode << ", using "
                             << static_cast<int>(presentMode);
        }
        // four: one shown, one waiting to be, one the GPU draws and one the next frame is recorded for (two frames are
        // in flight). With three the next frame's acquire waited on the display, and the GPU with it.
        // With frame generation, the generated frames queued after a rendered one take an image each too: without
        // more, the next frame's recording waited for the display to let go of one.
        uint32_t images = std::max(caps.minImageCount, 2u + mInFlight);
        if (caps.maxImageCount > 0)
            images = std::min(images, caps.maxImageCount);

        VkSwapchainCreateInfoKHR info{ VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR };
        info.surface = mSurface;
        info.minImageCount = images;
        info.imageFormat = chosen.format;
        info.imageColorSpace = chosen.colorSpace;
        info.imageExtent = mExtent;
        info.imageArrayLayers = 1;
        info.imageUsage = VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT
            | (caps.supportedUsageFlags & (VK_IMAGE_USAGE_TRANSFER_DST_BIT | VK_IMAGE_USAGE_TRANSFER_SRC_BIT));
        mWindowReadable = (info.imageUsage & VK_IMAGE_USAGE_TRANSFER_SRC_BIT) != 0;
        info.imageSharingMode = VK_SHARING_MODE_EXCLUSIVE;
        // the compositor turns the picture when the display is turned: simplest, and the game is held to landscape
        info.preTransform = (caps.supportedTransforms & VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR)
            ? VK_SURFACE_TRANSFORM_IDENTITY_BIT_KHR
            : caps.currentTransform;
        info.compositeAlpha = (caps.supportedCompositeAlpha & VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR)
            ? VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR
            : VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR;
        info.presentMode = presentMode;
        info.clipped = VK_TRUE;
        VkSwapchainKHR old = mSwapchain;
        info.oldSwapchain = old;
        const VkResult result = vkCreateSwapchainKHR(mDevice.mDevice, &info, nullptr, &mSwapchain);
        if (old != VK_NULL_HANDLE)
            vkDestroySwapchainKHR(mDevice.mDevice, old, nullptr);
        if (result != VK_SUCCESS)
        {
            mSwapchain = VK_NULL_HANDLE;
            Log(Debug::Error) << "Vulkan: vkCreateSwapchainKHR: " << resultName(result);
            return false;
        }
        vkGetSwapchainImagesKHR(mDevice.mDevice, mSwapchain, &count, nullptr);
        mImages.resize(count);
        vkGetSwapchainImagesKHR(mDevice.mDevice, mSwapchain, &count, mImages.data());

        // the window's depth and stencil, which the engine never reads back
        VkImageCreateInfo depth{ VK_STRUCTURE_TYPE_IMAGE_CREATE_INFO };
        depth.imageType = VK_IMAGE_TYPE_2D;
        depth.format = mDevice.mDepthFormat;
        depth.extent = { mExtent.width, mExtent.height, 1 };
        depth.mipLevels = 1;
        depth.arrayLayers = 1;
        depth.samples = VK_SAMPLE_COUNT_1_BIT;
        depth.tiling = VK_IMAGE_TILING_OPTIMAL;
        depth.usage = VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_TRANSIENT_ATTACHMENT_BIT;
        VmaAllocationCreateInfo depthMemory{};
        depthMemory.usage = VMA_MEMORY_USAGE_GPU_LAZILY_ALLOCATED;
        if (vmaCreateImage(mDevice.mAllocator, &depth, &depthMemory, &mDepthImage, &mDepthMemory, nullptr) != VK_SUCCESS)
        {
            depthMemory.usage = VMA_MEMORY_USAGE_GPU_ONLY;
            vmaCreateImage(mDevice.mAllocator, &depth, &depthMemory, &mDepthImage, &mDepthMemory, nullptr);
        }
        VkImageViewCreateInfo view{ VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO };
        view.viewType = VK_IMAGE_VIEW_TYPE_2D;
        view.subresourceRange = { VK_IMAGE_ASPECT_DEPTH_BIT | VK_IMAGE_ASPECT_STENCIL_BIT, 0, 1, 0, 1 };
        view.image = mDepthImage;
        view.format = mDevice.mDepthFormat;
        vkCreateImageView(mDevice.mDevice, &view, nullptr, &mDepthView);

        VkRenderPass compatible = windowCompatiblePass();
        for (VkImage image : mImages)
        {
            view.image = image;
            view.format = mFormat;
            view.subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
            VkImageView colorView = VK_NULL_HANDLE;
            vkCreateImageView(mDevice.mDevice, &view, nullptr, &colorView);
            mViews.push_back(colorView);
            VkImageView attachments[] = { colorView, mDepthView };
            VkFramebufferCreateInfo framebuffer{ VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO };
            framebuffer.renderPass = compatible;
            framebuffer.attachmentCount = 2;
            framebuffer.pAttachments = attachments;
            framebuffer.width = mExtent.width;
            framebuffer.height = mExtent.height;
            framebuffer.layers = 1;
            VkFramebuffer handle = VK_NULL_HANDLE;
            vkCreateFramebuffer(mDevice.mDevice, &framebuffer, nullptr, &handle);
            mFramebuffers.push_back(handle);
        }
        mSurfaceTransform = caps.currentTransform;
        mSurfaceExtent = caps.currentExtent;
        {
            // a window that is not pre-rotated is turned by the compositor (or the display hardware): on some phones
            // that costs a full-screen pass a frame. currentTransform other than 1 (identity) while preTransform is 1
            // means that.
            std::ostringstream formatsText;
            for (const VkSurfaceFormatKHR& f : formats)
                formatsText << " " << f.format << "/" << f.colorSpace;
            Log(Debug::Info) << "Vulkan: swap chain " << mExtent.width << "x" << mExtent.height << " (asked "
                             << mWanted.width << "x" << mWanted.height << ", window " << caps.currentExtent.width
                             << "x" << caps.currentExtent.height << "), " << count << " images (asked " << images
                             << ", driver min " << caps.minImageCount << " max " << caps.maxImageCount << "), format "
                             << mFormat << " colour space " << chosen.colorSpace << " (offered" << formatsText.str()
                             << "), present mode " << static_cast<int>(presentMode) << ", transform: current 0x"
                             << std::hex << caps.currentTransform << " supported 0x" << caps.supportedTransforms
                             << " used 0x" << info.preTransform << ", composite alpha used 0x" << info.compositeAlpha
                             << " supported 0x" << caps.supportedCompositeAlpha << ", usage 0x" << info.imageUsage
                             << std::dec << ", " << mInFlight << " frames in flight";
            if (caps.currentTransform != info.preTransform)
                Log(Debug::Info) << "Vulkan: the window is not pre-rotated: the system turns each frame (can cost "
                                    "speed on some GPUs)";
        }
        mRecreate = false;
        return true;
    }

    void Context::destroySwapchain()
    {
        vkDeviceWaitIdle(mDevice.mDevice);
        for (VkFramebuffer framebuffer : mFramebuffers)
            vkDestroyFramebuffer(mDevice.mDevice, framebuffer, nullptr);
        for (VkImageView view : mViews)
            vkDestroyImageView(mDevice.mDevice, view, nullptr);
        mFramebuffers.clear();
        mViews.clear();
        mImages.clear();
        if (mDepthView != VK_NULL_HANDLE)
            vkDestroyImageView(mDevice.mDevice, mDepthView, nullptr);
        if (mDepthImage != VK_NULL_HANDLE)
            vmaDestroyImage(mDevice.mAllocator, mDepthImage, mDepthMemory);
        mDepthView = VK_NULL_HANDLE;
        mDepthImage = VK_NULL_HANDLE;
        mDepthMemory = nullptr;
    }

    void Context::setWindow(ANativeWindow* window)
    {
        if (window == mWindow)
            return;
        destroySwapchain();
        if (mSwapchain != VK_NULL_HANDLE)
            vkDestroySwapchainKHR(mDevice.mDevice, mSwapchain, nullptr);
        mSwapchain = VK_NULL_HANDLE;
        if (mSurface != VK_NULL_HANDLE)
            vkDestroySurfaceKHR(mDevice.mInstance, mSurface, nullptr);
        mSurface = VK_NULL_HANDLE;
        mWindow = window;
        if (mWindow != nullptr && createSurface())
            createSwapchain();
    }

    VkRenderPass Context::renderPass(const AttachmentUse& color, const AttachmentUse& depth, const AttachmentUse& color1)
    {
        const auto key = std::make_tuple(color.key(), depth.key(), color1.key());
        auto found = mPasses.find(key);
        if (found != mPasses.end())
            return found->second;

        VkAttachmentDescription attachments[3]{};
        VkAttachmentReference colorRefs[2]{ { 0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL },
            { 0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL } };
        VkAttachmentReference depthRef{ 0, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL };
        uint32_t count = 0;
        VkSubpassDescription subpass{};
        subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
        for (const AttachmentUse* use : { &color, &color1 })
        {
            if (use->mFormat == VK_FORMAT_UNDEFINED || (use == &color1 && color.mFormat == VK_FORMAT_UNDEFINED))
                continue;
            VkAttachmentDescription& a = attachments[count];
            a.format = use->mFormat;
            a.samples = VK_SAMPLE_COUNT_1_BIT;
            a.loadOp = use->mLoad;
            a.storeOp = use->mStore;
            a.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
            a.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
            a.initialLayout = use->mInitial;
            a.finalLayout = use->mFinal;
            colorRefs[subpass.colorAttachmentCount++].attachment = count++;
            subpass.pColorAttachments = colorRefs;
        }
        if (depth.mFormat != VK_FORMAT_UNDEFINED)
        {
            VkAttachmentDescription& a = attachments[count];
            a.format = depth.mFormat;
            a.samples = VK_SAMPLE_COUNT_1_BIT;
            a.loadOp = depth.mLoad;
            a.storeOp = depth.mStore;
            const bool stencil = depth.mFormat == VK_FORMAT_D24_UNORM_S8_UINT
                || depth.mFormat == VK_FORMAT_D32_SFLOAT_S8_UINT || depth.mFormat == VK_FORMAT_D16_UNORM_S8_UINT;
            a.stencilLoadOp = stencil ? depth.mLoad : VK_ATTACHMENT_LOAD_OP_DONT_CARE;
            a.stencilStoreOp = stencil ? depth.mStore : VK_ATTACHMENT_STORE_OP_DONT_CARE;
            a.initialLayout = depth.mInitial;
            a.finalLayout = depth.mFinal;
            depthRef.attachment = count++;
            subpass.pDepthStencilAttachment = &depthRef;
        }
        VkRenderPassCreateInfo info{ VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO };
        info.attachmentCount = count;
        info.pAttachments = attachments;
        info.subpassCount = 1;
        info.pSubpasses = &subpass;
        VkSubpassDependency dependencies[3];
        addDependencies(info, dependencies);
        VkRenderPass pass = VK_NULL_HANDLE;
        const VkResult result = vkCreateRenderPass(mDevice.mDevice, &info, nullptr, &pass);
        if (result != VK_SUCCESS)
            Log(Debug::Error) << "Vulkan: vkCreateRenderPass: " << resultName(result);
        mPasses[key] = pass;
        (void)isDepthFormat;
        return pass;
    }

    VkRenderPass Context::windowCompatiblePass()
    {
        AttachmentUse color;
        color.mFormat = mFormat;
        color.mInitial = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
        color.mFinal = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
        AttachmentUse depth;
        depth.mFormat = mDevice.mDepthFormat;
        depth.mInitial = VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
        depth.mFinal = VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;
        return renderPass(color, depth);
    }

    void Context::releaseLater(std::function<void()> release)
    {
        mFrames[mFrameSlot].mReleases.push_back(std::move(release));
    }

    // A thread that waits for each frame's fence and notes when it signalled. The GPU's time for a frame: from when
    // it could start on it (submitted, or the previous frame done, whichever is later) to its fence. Includes the
    // GPU waiting for a window image (the swap chain image ms in the frame line says how long the CPU waited for one).
    struct Context::FenceTimer
    {
        using Clock = std::chrono::steady_clock;
        struct Pending
        {
            VkFence mFence;
            Clock::time_point mSubmitted;
        };
        VkDevice mDevice;
        std::mutex mMutex;
        std::condition_variable mChanged;
        std::deque<Pending> mPending;
        VkFence mWaiting = VK_NULL_HANDLE; // the fence the thread is waiting on now
        Clock::time_point mLastDone{};
        std::vector<double> mTimes; // since the last report, ms


        explicit FenceTimer(VkDevice device)
            : mDevice(device)
        {
        }

        void run()
        {
            for (;;)
            {
                Pending next;
                {
                    std::unique_lock lock(mMutex);
                    mChanged.wait(lock, [&] { return !mPending.empty(); });
                    next = mPending.front();
                    mPending.pop_front();
                    mWaiting = next.mFence;
                }
                VkResult result = VK_TIMEOUT;
                for (int tries = 0; tries < 10 && result == VK_TIMEOUT; ++tries)
                    result = vkWaitForFences(mDevice, 1, &next.mFence, VK_TRUE, 500'000'000);
                const Clock::time_point done = Clock::now();
                std::lock_guard lock(mMutex);
                mWaiting = VK_NULL_HANDLE;
                if (result == VK_SUCCESS)
                {
                    const Clock::time_point start = std::max(next.mSubmitted, mLastDone);
                    const double ms = std::chrono::duration<double, std::milli>(done - start).count();
                    mLastDone = done;
                    mTimes.push_back(ms);
                }
                mChanged.notify_all();
            }
        }

        void submitted(VkFence fence, Clock::time_point when)
        {
            std::lock_guard lock(mMutex);
            mPending.push_back({ fence, when });
            mChanged.notify_all();
        }

        // before the fence is reset: the thread is done with it (it has signalled already, so this is short)
        void release(VkFence fence)
        {
            std::unique_lock lock(mMutex);
            mChanged.wait(lock, [&] {
                if (mWaiting == fence)
                    return false;
                for (const Pending& p : mPending)
                    if (p.mFence == fence)
                        return false;
                return true;
            });
        }
    };

    std::string Context::gpuDiagnostics()
    {
        std::ostringstream out;
        out << "Vulkan GPU: ";
        if (mFenceTimer)
        {
            std::lock_guard lock(mFenceTimer->mMutex);
            std::vector<double>& times = mFenceTimer->mTimes;
            if (!times.empty())
            {
                // frames over a second are loading screens and the like: counted apart, not in the average
                double sum = 0.0;
                unsigned counted = 0, longFrames = 0;
                for (double ms : times)
                    if (ms > 1000.0)
                        ++longFrames;
                    else
                        sum += ms, ++counted;
                std::sort(times.begin(), times.end());
                out << "per frame (fences) median " << times[times.size() / 2] << " ms, average "
                    << (counted ? sum / counted : 0.0) << " ms, 90% under " << times[times.size() * 9 / 10]
                    << " ms, over " << times.size() << " frames (" << longFrames << " over 1 s left out of the average)";
            }
            else
                out << "no frame timed by fences";
            times.clear();
        }
        else
            out << "fence timing off";

        // the kernel's view, where SELinux lets an app read it: Adreno (kgsl), Samsung's Exynos/Mali node, Mali's own
        auto readNumber = [](const char* path, double& value) {
            FILE* file = std::fopen(path, "r");
            if (file == nullptr)
                return false;
            const bool read = std::fscanf(file, "%lf", &value) == 1;
            std::fclose(file);
            return read;
        };
        double busy = 0, clock = 0;
        const char* busyFrom = nullptr;
        for (const char* path : { "/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage", "/sys/kernel/gpu/gpu_busy",
                 "/sys/class/misc/mali0/device/utilization", "/sys/devices/platform/mali.0/utilization" })
            if (readNumber(path, busy))
            {
                busyFrom = path;
                break;
            }
        const char* clockFrom = nullptr;
        for (const char* path : { "/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq", "/sys/kernel/gpu/gpu_clock",
                 "/sys/class/misc/mali0/device/cur_freq", "/sys/class/misc/mali0/device/clock" })
            if (readNumber(path, clock))
            {
                clockFrom = path;
                break;
            }
        if (clock > 1e5)
            clock /= 1e6; // Hz to MHz (kgsl, some Mali kernels); Samsung's gpu_clock is MHz already
        out << "; kernel: busy ";
        if (busyFrom)
            out << busy << "% (" << busyFrom << ")";
        else
            out << "not readable";
        out << ", clock ";
        if (clockFrom)
            out << clock << " MHz (" << clockFrom << ")";
        else
            out << "not readable";
        return out.str();
    }

    bool Context::beginFrame()
    {
        if (mBegun)
            return true;
        if (mSwapchain == VK_NULL_HANDLE)
            return false;
        Frame& frame = mFrames[mFrameSlot];
        const auto fenceBegun = std::chrono::steady_clock::now();
        vkWaitForFences(mDevice.mDevice, 1, &frame.mFence, VK_TRUE, UINT64_MAX);
        mFenceSeconds += std::chrono::duration<double>(std::chrono::steady_clock::now() - fenceBegun).count();
        for (auto& release : frame.mReleases)
            release();
        frame.mReleases.clear();
        vkResetCommandPool(mDevice.mDevice, frame.mPool, 0);
        frame.mUploadsBegun[0] = frame.mUploadsBegun[1] = false;
        for (VkDescriptorPool pool : frame.mDescriptorPools)
            vkResetDescriptorPool(mDevice.mDevice, pool, 0);
        frame.mDescriptorPool = 0;
        VkCommandBufferBeginInfo begin{ VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO };
        begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        vkBeginCommandBuffer(frame.mCommands[0], &begin);
        mPart = 0;
        ++mCommandsGeneration;
        mBegun = true;
        mAcquired = false;
        mWindowDrawn = false;
        mInWindowPass = false;
        return true;
    }

    bool Context::acquire()
    {
        if (mAcquired)
            return true;
        if (mRecreate || mSwapchain == VK_NULL_HANDLE)
        {
            destroySwapchain();
            if (!createSwapchain())
                return false;
        }
        Frame& frame = mFrames[mFrameSlot];
        const auto acquireBegun = std::chrono::steady_clock::now();
        struct Timed
        {
            double& mSum;
            std::chrono::steady_clock::time_point mBegun;
            ~Timed() { mSum += std::chrono::duration<double>(std::chrono::steady_clock::now() - mBegun).count(); }
        } timed{ mAcquireSeconds, acquireBegun };
        VkResult result
            = vkAcquireNextImageKHR(mDevice.mDevice, mSwapchain, UINT64_MAX, frame.mAcquired, VK_NULL_HANDLE, &mImage);
        if (result == VK_ERROR_OUT_OF_DATE_KHR)
        {
            destroySwapchain();
            if (!createSwapchain())
                return false;
            result = vkAcquireNextImageKHR(
                mDevice.mDevice, mSwapchain, UINT64_MAX, frame.mAcquired, VK_NULL_HANDLE, &mImage);
        }
        if (result != VK_SUCCESS && result != VK_SUBOPTIMAL_KHR)
        {
            Log(Debug::Error) << "Vulkan: vkAcquireNextImageKHR: " << resultName(result);
            stopIfLost(result, "vkAcquireNextImageKHR");
            return false;
        }
        mAcquired = true;
        return true;
    }

    void Context::submitFirstPart()
    {
        Frame& frame = mFrames[mFrameSlot];
        if (mPart != 0 || mInWindowPass)
            return;
        vkEndCommandBuffer(frame.mCommands[0]);
        VkCommandBuffer buffers[2];
        uint32_t bufferCount = 0;
        if (frame.mUploadsBegun[0])
        {
            vkEndCommandBuffer(frame.mUploads[0]);
            buffers[bufferCount++] = frame.mUploads[0];
        }
        buffers[bufferCount++] = frame.mCommands[0];
        VkSubmitInfo submit{ VK_STRUCTURE_TYPE_SUBMIT_INFO };
        submit.commandBufferCount = bufferCount;
        submit.pCommandBuffers = buffers;
        if (!mFirstSubmitted)
        {
            mFirstSubmit = std::chrono::steady_clock::now();
            mFirstSubmitted = true;
        }
        const VkResult result = vkQueueSubmit(mDevice.mQueue, 1, &submit, VK_NULL_HANDLE);
        if (result != VK_SUCCESS)
        {
            Log(Debug::Error) << "Vulkan: vkQueueSubmit (first part): " << resultName(result);
            stopIfLost(result, "vkQueueSubmit (first part)");
        }
        mPart = 1;
        ++mCommandsGeneration;
        VkCommandBufferBeginInfo begin{ VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO };
        begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
        vkBeginCommandBuffer(frame.mCommands[1], &begin);
    }

    bool Context::beginWindowPass(bool clearColor, const float color[4], bool clearDepth, float depth, uint32_t stencil)
    {
        if (!beginFrame())
            return false;
        // the render targets drawn so far go to the GPU before the image is waited for
        if (!mAcquired)
            submitFirstPart();
        if (!acquire())
            return false;
        AttachmentUse colorUse;
        colorUse.mFormat = mFormat;
        // the first pass of the frame on the image: what was there before is not needed unless it is kept
        // (DONT_CARE keeps, on many drivers, what the image showed frames ago: a frame that did not cover the window
        // then showed it under the GUI, the place left behind just after a loading screen. Cleared for those frames.)
        const bool forceClear = !clearColor && !mWindowDrawn && mClearWindow;
        static const float black[4] = { 0.f, 0.f, 0.f, 1.f };
        if (forceClear)
            color = black;
        colorUse.mLoad = clearColor || forceClear
            ? VK_ATTACHMENT_LOAD_OP_CLEAR
            : (mWindowDrawn ? VK_ATTACHMENT_LOAD_OP_LOAD : VK_ATTACHMENT_LOAD_OP_DONT_CARE);
        colorUse.mInitial = mWindowDrawn ? VK_IMAGE_LAYOUT_PRESENT_SRC_KHR : VK_IMAGE_LAYOUT_UNDEFINED;
        colorUse.mFinal = VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
        AttachmentUse depthUse;
        depthUse.mFormat = mDevice.mDepthFormat;
        depthUse.mLoad = clearDepth ? VK_ATTACHMENT_LOAD_OP_CLEAR
                                    : (mWindowDrawn ? VK_ATTACHMENT_LOAD_OP_LOAD : VK_ATTACHMENT_LOAD_OP_DONT_CARE);
        depthUse.mStore = VK_ATTACHMENT_STORE_OP_STORE;
        depthUse.mInitial = mWindowDrawn ? VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL : VK_IMAGE_LAYOUT_UNDEFINED;
        depthUse.mFinal = VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL;

        VkClearValue clears[2]{};
        clears[0].color = { { color[0], color[1], color[2], color[3] } };
        clears[1].depthStencil = { depth, stencil };
        VkRenderPassBeginInfo begin{ VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO };
        begin.renderPass = renderPass(colorUse, depthUse);
        begin.framebuffer = mFramebuffers[mImage];
        begin.renderArea = { { 0, 0 }, mExtent };
        begin.clearValueCount = 2;
        begin.pClearValues = clears;
        vkCmdBeginRenderPass(commands(), &begin, VK_SUBPASS_CONTENTS_INLINE);
        mWindowDrawn = true;
        mClearWindow = false;
        mInWindowPass = true;
        return true;
    }

    VkCommandBuffer Context::uploadCommands()
    {
        Frame& frame = mFrames[mFrameSlot];
        if (!frame.mUploadsBegun[mPart])
        {
            VkCommandBufferBeginInfo begin{ VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO };
            begin.flags = VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT;
            vkBeginCommandBuffer(frame.mUploads[mPart], &begin);
            frame.mUploadsBegun[mPart] = true;
        }
        return frame.mUploads[mPart];
    }

    VkDescriptorSet Context::allocateSet(VkDescriptorSetLayout layout)
    {
        Frame& frame = mFrames[mFrameSlot];
        for (;;)
        {
            if (frame.mDescriptorPool == frame.mDescriptorPools.size())
            {
                const VkDescriptorPoolSize sizes[] = { { VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC, 3072 },
                    { VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 8192 }, { VK_DESCRIPTOR_TYPE_STORAGE_BUFFER, 512 },
                    { VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER, 64 } };
                VkDescriptorPoolCreateInfo info{ VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO };
                info.maxSets = 1024;
                info.poolSizeCount = 4;
                info.pPoolSizes = sizes;
                VkDescriptorPool pool = VK_NULL_HANDLE;
                if (vkCreateDescriptorPool(mDevice.mDevice, &info, nullptr, &pool) != VK_SUCCESS)
                    return VK_NULL_HANDLE;
                frame.mDescriptorPools.push_back(pool);
            }
            VkDescriptorSetAllocateInfo allocate{ VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO };
            allocate.descriptorPool = frame.mDescriptorPools[frame.mDescriptorPool];
            allocate.descriptorSetCount = 1;
            allocate.pSetLayouts = &layout;
            VkDescriptorSet set = VK_NULL_HANDLE;
            const VkResult result = vkAllocateDescriptorSets(mDevice.mDevice, &allocate, &set);
            if (result == VK_SUCCESS)
                return set;
            if (result != VK_ERROR_OUT_OF_POOL_MEMORY && result != VK_ERROR_FRAGMENTED_POOL)
            {
                Log(Debug::Error) << "Vulkan: vkAllocateDescriptorSets: " << resultName(result);
                return VK_NULL_HANDLE;
            }
            ++frame.mDescriptorPool; // full: the next one
        }
    }

    VkDescriptorSet Context::allocateKeptSet(VkDescriptorSetLayout layout)
    {
        VkDescriptorSetAllocateInfo allocate{ VK_STRUCTURE_TYPE_DESCRIPTOR_SET_ALLOCATE_INFO };
        allocate.descriptorSetCount = 1;
        allocate.pSetLayouts = &layout;
        // the newest pool first: the older ones are full, but for the sets freed in them
        for (size_t i = mKeptPools.size(); i-- > 0;)
        {
            allocate.descriptorPool = mKeptPools[i];
            VkDescriptorSet set = VK_NULL_HANDLE;
            const VkResult result = vkAllocateDescriptorSets(mDevice.mDevice, &allocate, &set);
            if (result == VK_SUCCESS)
            {
                mKeptOwners[set] = mKeptPools[i];
                return set;
            }
            if (result != VK_ERROR_OUT_OF_POOL_MEMORY && result != VK_ERROR_FRAGMENTED_POOL)
            {
                Log(Debug::Error) << "Vulkan: vkAllocateDescriptorSets (kept): " << resultName(result);
                return VK_NULL_HANDLE;
            }
        }
        const VkDescriptorPoolSize sizes[] = { { VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC, 3072 },
            { VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 8192 } };
        VkDescriptorPoolCreateInfo info{ VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO };
        info.flags = VK_DESCRIPTOR_POOL_CREATE_FREE_DESCRIPTOR_SET_BIT;
        info.maxSets = 1024;
        info.poolSizeCount = 2;
        info.pPoolSizes = sizes;
        VkDescriptorPool pool = VK_NULL_HANDLE;
        if (vkCreateDescriptorPool(mDevice.mDevice, &info, nullptr, &pool) != VK_SUCCESS)
            return VK_NULL_HANDLE;
        mKeptPools.push_back(pool);
        allocate.descriptorPool = pool;
        VkDescriptorSet set = VK_NULL_HANDLE;
        if (vkAllocateDescriptorSets(mDevice.mDevice, &allocate, &set) != VK_SUCCESS)
            return VK_NULL_HANDLE;
        mKeptOwners[set] = pool;
        return set;
    }

    void Context::freeKeptSetLater(VkDescriptorSet set)
    {
        if (set == VK_NULL_HANDLE)
            return;
        releaseLater([this, set] {
            auto found = mKeptOwners.find(set);
            if (found == mKeptOwners.end())
                return;
            vkFreeDescriptorSets(mDevice.mDevice, found->second, 1, &set);
            mKeptOwners.erase(found);
        });
    }

    void Context::endWindowPass()
    {
        if (!mInWindowPass)
            return;
        vkCmdEndRenderPass(commands());
        mInWindowPass = false;
    }

    bool Context::blitWindow(VkImage target, uint32_t width, uint32_t height, VkImageLayout layout)
    {
        if (!mWindowReadable || !mWindowDrawn || !mAcquired || mInWindowPass || mImage >= mImages.size())
            return false;
        VkCommandBuffer commandBuffer = commands();
        auto barrier = [&](VkImage image, VkImageLayout from, VkImageLayout to, VkAccessFlags fromAccess,
                           VkAccessFlags toAccess, VkPipelineStageFlags fromStage, VkPipelineStageFlags toStage) {
            VkImageMemoryBarrier b{ VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER };
            b.oldLayout = from;
            b.newLayout = to;
            b.srcAccessMask = fromAccess;
            b.dstAccessMask = toAccess;
            b.srcQueueFamilyIndex = b.dstQueueFamilyIndex = VK_QUEUE_FAMILY_IGNORED;
            b.image = image;
            b.subresourceRange = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 1, 0, 1 };
            vkCmdPipelineBarrier(commandBuffer, fromStage, toStage, 0, 0, nullptr, 0, nullptr, 1, &b);
        };
        const VkPipelineStageFlags shaders = VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT;
        VkImage window = mImages[mImage];
        barrier(window, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL,
            VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT, VK_ACCESS_TRANSFER_READ_BIT,
            VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT);
        barrier(target, layout, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, VK_ACCESS_SHADER_READ_BIT,
            VK_ACCESS_TRANSFER_WRITE_BIT, shaders, VK_PIPELINE_STAGE_TRANSFER_BIT);
        VkImageBlit region{};
        region.srcSubresource = { VK_IMAGE_ASPECT_COLOR_BIT, 0, 0, 1 };
        region.dstSubresource = region.srcSubresource;
        region.srcOffsets[1] = { int32_t(mExtent.width), int32_t(mExtent.height), 1 };
        // the window is top row first, a texture the OpenGL way (bottom row first)
        region.dstOffsets[0] = { 0, int32_t(height), 0 };
        region.dstOffsets[1] = { int32_t(width), 0, 1 };
        vkCmdBlitImage(commandBuffer, window, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, target,
            VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, 1, &region, VK_FILTER_LINEAR);
        barrier(window, VK_IMAGE_LAYOUT_TRANSFER_SRC_OPTIMAL, VK_IMAGE_LAYOUT_PRESENT_SRC_KHR,
            VK_ACCESS_TRANSFER_READ_BIT, 0, VK_PIPELINE_STAGE_TRANSFER_BIT, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT);
        barrier(target, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, layout, VK_ACCESS_TRANSFER_WRITE_BIT,
            VK_ACCESS_SHADER_READ_BIT, VK_PIPELINE_STAGE_TRANSFER_BIT, shaders);
        return true;
    }

    void Context::drawDirect()
    {
        // a barrier on the vertex stage in the pass (the subpass's dependency on itself): Turnip then draws the pass
        // straight to memory rather than in tiles. Other drivers only wait on the vertex stage, which nothing before it
        // in the pass uses
        vkCmdPipelineBarrier(commands(), VK_PIPELINE_STAGE_VERTEX_SHADER_BIT, VK_PIPELINE_STAGE_VERTEX_SHADER_BIT, 0, 0,
            nullptr, 0, nullptr, 0, nullptr);
    }

    void Context::afterFrame(std::function<void()> work)
    {
        mAfterFrame.push_back(std::move(work));
    }

    void Context::present(int width, int height)
    {
        if (width > 0 && height > 0
            && (static_cast<uint32_t>(width) != mWanted.width || static_cast<uint32_t>(height) != mWanted.height))
        {
            mWanted = { static_cast<uint32_t>(width), static_cast<uint32_t>(height) };
            mRecreate = true;
        }
        std::vector<std::function<void()>> after;
        after.swap(mAfterFrame);
        if (!beginFrame())
        {
            for (auto& work : after)
                work();
            return;
        }
        Frame& frame = mFrames[mFrameSlot];
        // nothing drawn on the window this frame: it is cleared, so that there is something to present
        if (!mWindowDrawn)
        {
            const float black[4] = { 0.f, 0.f, 0.f, 1.f };
            if (beginWindowPass(true, black, true, 1.f, 0))
                endWindowPass();
        }
        endWindowPass();
        vkEndCommandBuffer(frame.mCommands[mPart]);
        VkCommandBuffer buffers[2];
        uint32_t bufferCount = 0;
        if (frame.mUploadsBegun[mPart])
        {
            vkEndCommandBuffer(frame.mUploads[mPart]);
            buffers[bufferCount++] = frame.mUploads[mPart];
        }
        buffers[bufferCount++] = frame.mCommands[mPart];

        const VkPipelineStageFlags waitStage = VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT;
        VkSubmitInfo submit{ VK_STRUCTURE_TYPE_SUBMIT_INFO };
        submit.commandBufferCount = bufferCount;
        submit.pCommandBuffers = buffers;
        if (mAcquired)
        {
            submit.waitSemaphoreCount = 1;
            submit.pWaitSemaphores = &frame.mAcquired;
            submit.pWaitDstStageMask = &waitStage;
            submit.signalSemaphoreCount = 1;
            submit.pSignalSemaphores = &frame.mFinished;
        }
        if (diagnostics() && !mFenceTimer)
        {
            mFenceTimer = std::make_shared<FenceTimer>(mDevice.mDevice);
            std::thread([timer = mFenceTimer] { timer->run(); }).detach();
        }
        if (mFenceTimer)
            mFenceTimer->release(frame.mFence);
        const auto submittedAt = mFirstSubmitted ? mFirstSubmit : std::chrono::steady_clock::now();
        mFirstSubmitted = false;
        vkResetFences(mDevice.mDevice, 1, &frame.mFence);
        VkResult result = vkQueueSubmit(mDevice.mQueue, 1, &submit, frame.mFence);
        const bool submitted = result == VK_SUCCESS;
        if (submitted && mFenceTimer)
            mFenceTimer->submitted(frame.mFence, submittedAt);
        if (result != VK_SUCCESS)
        {
            Log(Debug::Error) << "Vulkan: vkQueueSubmit: " << resultName(result);
            stopIfLost(result, "vkQueueSubmit");
        }

        if (mAcquired)
        {
            VkPresentInfoKHR present{ VK_STRUCTURE_TYPE_PRESENT_INFO_KHR };
            present.waitSemaphoreCount = 1;
            present.pWaitSemaphores = &frame.mFinished;
            present.swapchainCount = 1;
            present.pSwapchains = &mSwapchain;
            present.pImageIndices = &mImage;
            const auto presentBegun = std::chrono::steady_clock::now();
            result = vkQueuePresentKHR(mDevice.mQueue, &present);
            mPresentSeconds
                += std::chrono::duration<double>(std::chrono::steady_clock::now() - presentBegun).count();
            if (result == VK_ERROR_OUT_OF_DATE_KHR)
                mRecreate = true;
            else if (result == VK_SUBOPTIMAL_KHR)
            {
                // Android says suboptimal for every frame that is not pre-rotated; only a changed window is remade
                VkSurfaceCapabilitiesKHR caps{};
                vkGetPhysicalDeviceSurfaceCapabilitiesKHR(mDevice.mPhysical, mSurface, &caps);
                mRecreate = caps.currentTransform != mSurfaceTransform
                    || caps.currentExtent.width != mSurfaceExtent.width
                    || caps.currentExtent.height != mSurfaceExtent.height;
            }
            else if (result != VK_SUCCESS)
            {
                Log(Debug::Error) << "Vulkan: vkQueuePresentKHR: " << resultName(result);
                stopIfLost(result, "vkQueuePresentKHR");
            }
        }

        if (!after.empty())
        {
            if (submitted)
                vkWaitForFences(mDevice.mDevice, 1, &frame.mFence, VK_TRUE, UINT64_MAX);
            for (auto& work : after)
                work();
        }

        mBegun = false;
        mAcquired = false;
        mFrameSlot = (mFrameSlot + 1) % mInFlight;
        ++mFrameNumber;
    }
}
