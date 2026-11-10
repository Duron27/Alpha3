#include "context.hpp"

#include <algorithm>
#include <cmath>
#include <cstdlib>

#include <android/native_window.h>

#define VMA_STATIC_VULKAN_FUNCTIONS 0
#define VMA_DYNAMIC_VULKAN_FUNCTIONS 1
#define VMA_VULKAN_VERSION 1000000
#include <vk_mem_alloc.h>

#include <components/debug/debuglog.hpp>
#include <components/settings/settings.hpp>

namespace Alpha3::Vk
{
    namespace
    {
        Context* sContext = nullptr;

        // Every render pass waits for what earlier passes (and transfers) wrote, and makes what it writes visible to
        // whatever samples it later: the attachments of one pass are often the textures of the next
        void addDependencies(VkRenderPassCreateInfo& info, VkSubpassDependency (&dependencies)[2])
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
            info.dependencyCount = 2;
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
        uint32_t images = std::max(caps.minImageCount, 4u);
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
        Log(Debug::Info) << "Vulkan: swap chain " << mExtent.width << "x" << mExtent.height << ", " << count
                         << " images, format " << mFormat;
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

    VkRenderPass Context::renderPass(const AttachmentUse& color, const AttachmentUse& depth)
    {
        const auto key = std::make_tuple(color.key(), depth.key());
        auto found = mPasses.find(key);
        if (found != mPasses.end())
            return found->second;

        VkAttachmentDescription attachments[2]{};
        VkAttachmentReference colorRef{ 0, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL };
        VkAttachmentReference depthRef{ 0, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL };
        uint32_t count = 0;
        VkSubpassDescription subpass{};
        subpass.pipelineBindPoint = VK_PIPELINE_BIND_POINT_GRAPHICS;
        if (color.mFormat != VK_FORMAT_UNDEFINED)
        {
            VkAttachmentDescription& a = attachments[count];
            a.format = color.mFormat;
            a.samples = VK_SAMPLE_COUNT_1_BIT;
            a.loadOp = color.mLoad;
            a.storeOp = color.mStore;
            a.stencilLoadOp = VK_ATTACHMENT_LOAD_OP_DONT_CARE;
            a.stencilStoreOp = VK_ATTACHMENT_STORE_OP_DONT_CARE;
            a.initialLayout = color.mInitial;
            a.finalLayout = color.mFinal;
            colorRef.attachment = count++;
            subpass.colorAttachmentCount = 1;
            subpass.pColorAttachments = &colorRef;
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
        VkSubpassDependency dependencies[2];
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

    bool Context::beginFrame()
    {
        if (mBegun)
            return true;
        if (mSwapchain == VK_NULL_HANDLE)
            return false;
        Frame& frame = mFrames[mFrameSlot];
        vkWaitForFences(mDevice.mDevice, 1, &frame.mFence, VK_TRUE, UINT64_MAX);
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
        if (mRecreate)
        {
            destroySwapchain();
            if (!createSwapchain())
                return false;
        }
        Frame& frame = mFrames[mFrameSlot];
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
        const VkResult result = vkQueueSubmit(mDevice.mQueue, 1, &submit, VK_NULL_HANDLE);
        if (result != VK_SUCCESS)
            Log(Debug::Error) << "Vulkan: vkQueueSubmit (first part): " << resultName(result);
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
        colorUse.mLoad = clearColor ? VK_ATTACHMENT_LOAD_OP_CLEAR
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
                    { VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER, 8192 } };
                VkDescriptorPoolCreateInfo info{ VK_STRUCTURE_TYPE_DESCRIPTOR_POOL_CREATE_INFO };
                info.maxSets = 1024;
                info.poolSizeCount = 2;
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
        vkResetFences(mDevice.mDevice, 1, &frame.mFence);
        VkResult result = vkQueueSubmit(mDevice.mQueue, 1, &submit, frame.mFence);
        const bool submitted = result == VK_SUCCESS;
        if (result != VK_SUCCESS)
            Log(Debug::Error) << "Vulkan: vkQueueSubmit: " << resultName(result);

        if (mAcquired)
        {
            VkPresentInfoKHR present{ VK_STRUCTURE_TYPE_PRESENT_INFO_KHR };
            present.waitSemaphoreCount = 1;
            present.pWaitSemaphores = &frame.mFinished;
            present.swapchainCount = 1;
            present.pSwapchains = &mSwapchain;
            present.pImageIndices = &mImage;
            result = vkQueuePresentKHR(mDevice.mQueue, &present);
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
                Log(Debug::Error) << "Vulkan: vkQueuePresentKHR: " << resultName(result);
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
        mFrameSlot = (mFrameSlot + 1) % sFramesInFlight;
        ++mFrameNumber;
    }
}
