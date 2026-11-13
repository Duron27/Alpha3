#include "device.hpp"

#include "../support.hpp"

#include <TargetConditionals.h>

#include <atomic>
#include <memory>
#include <mutex>

#include <components/debug/debuglog.hpp>

namespace Alpha3
{
    namespace
    {
        // set once the device is made, before any texture is read; textures are read on several threads
        std::atomic<unsigned> sTextureFormats{ 0 };
        constexpr unsigned sKnown = 1, sBc = 2, sEtc2 = 4, sAstc = 8;
    }

    // iOS: the Metal device's formats (src/vulkan/device.cpp is Android's). The texture hooks ask this under Metal: the
    // OpenGL ES context beneath the Metal window samples no DXT, whatever the GPU does.
    VulkanTextureFormats vulkanTextureFormats()
    {
        const unsigned bits = sTextureFormats.load(std::memory_order_acquire);
        VulkanTextureFormats formats;
        formats.mKnown = (bits & sKnown) != 0;
        formats.mBc = (bits & sBc) != 0;
        formats.mEtc2 = (bits & sEtc2) != 0;
        formats.mAstc = (bits & sAstc) != 0;
        return formats;
    }
}

namespace Alpha3::Mtl
{
    namespace
    {
        std::mutex sMutex;
        Device* sDevice = nullptr;
        bool sTried = false;

        uint32_t familyOf(id<MTLDevice> device)
        {
            uint32_t family = 0;
            const MTLGPUFamily families[] = { MTLGPUFamilyApple4, MTLGPUFamilyApple5, MTLGPUFamilyApple6,
                MTLGPUFamilyApple7, MTLGPUFamilyApple8, MTLGPUFamilyApple9 };
            for (uint32_t i = 0; i < sizeof(families) / sizeof(families[0]); ++i)
                if ([device supportsFamily:families[i]])
                    family = 4 + i;
            return family;
        }

        Device* create()
        {
            id<MTLDevice> gpu = MTLCreateSystemDefaultDevice();
            if (gpu == nil)
            {
                Log(Debug::Error) << "Metal: no GPU";
                return nullptr;
            }
            auto device = std::make_unique<Device>();
            device->mDevice = gpu;
            device->mFamily = familyOf(gpu);
            // the simulator's GPU answers for a family of its own (a subset of the device's): it is let through
            if (device->mFamily < 4 && !TARGET_OS_SIMULATOR)
            {
                Log(Debug::Error) << "Metal: " << [[gpu name] UTF8String]
                                  << " is older than Apple GPU family 4 (A11); the renderer needs it";
                return nullptr;
            }
            device->mQueue = [gpu newCommandQueueWithMaxCommandBufferCount:16];
            if (device->mQueue == nil)
            {
                Log(Debug::Error) << "Metal: no command queue";
                return nullptr;
            }
            device->mQueue.label = @"Alpha3";
            if (@available(iOS 16.4, *))
                device->mBcTextures = [gpu supportsBCTextureCompression];
            device->mAstcHdr = [gpu supportsFamily:MTLGPUFamilyApple6];

            unsigned bits = sKnown | sEtc2 | sAstc;
            if (device->mBcTextures)
                bits |= sBc;
            sTextureFormats.store(bits, std::memory_order_release);
            Log(Debug::Info) << "Metal: " << [[gpu name] UTF8String] << ", Apple GPU family " << device->mFamily
                             << (device->mBcTextures ? ", samples DXT" : "") << ", ASTC"
                             << (device->mAstcHdr ? " (HDR too)" : "") << ", ETC2";
            return device.release();
        }
    }

    Device* device()
    {
        std::lock_guard<std::mutex> lock(sMutex);
        if (!sTried)
        {
            sTried = true;
            @autoreleasepool
            {
                sDevice = create();
            }
        }
        return sDevice;
    }

    bool probe()
    {
        @autoreleasepool
        {
            id<MTLDevice> gpu = MTLCreateSystemDefaultDevice();
            return gpu != nil && (familyOf(gpu) >= 4 || TARGET_OS_SIMULATOR);
        }
    }
}
