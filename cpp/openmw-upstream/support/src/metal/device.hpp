#ifndef ALPHA3_SUPPORT_METAL_DEVICE_H
#define ALPHA3_SUPPORT_METAL_DEVICE_H

// iOS, [Video] renderer = metal (OSG_USE_METAL): the GPU, as Metal has it. Apple GPU family 4 (A11) and up, which the
// app's iOS 16.4 asks for anyway: Metal 2 with the Metal Shading Language 2.1, about what Vulkan 1.0 is on Android.
// Objective-C++ only (the files of src/metal/ are .mm, compiled with ARC).
#import <Metal/Metal.h>

#include "probe.hpp"

#include <cstdint>

namespace Alpha3::Mtl
{
    struct Device
    {
        id<MTLDevice> mDevice = nil;
        id<MTLCommandQueue> mQueue = nil;
        // what the GPU samples beyond what every Apple GPU does (ASTC LDR and ETC2 are on all of them)
        bool mBcTextures = false; // DXT1/3/5: iPads with an M chip, iOS 16.4 on
        bool mAstcHdr = false;
        bool mDepthClamp = true; // setDepthClipMode
        // a depth and stencil format that is sampled too: 32 bit float depth with stencil (iOS has no 24 bit depth)
        MTLPixelFormat mDepthFormat = MTLPixelFormatDepth32Float_Stencil8;
        uint32_t mFamily = 0; // the highest MTLGPUFamilyApple<n>
        uint32_t mMaxBufferArgument = 31; // vertex buffers and other buffers a stage binds
    };

    // The device, made on the first call (null when Metal cannot start; the reason is logged)
    Device* device();

}

#endif
