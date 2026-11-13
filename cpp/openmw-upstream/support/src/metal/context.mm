#include "context.hpp"

#import <UIKit/UIKit.h>

#include <components/debug/debuglog.hpp>

namespace Alpha3::Mtl
{
    namespace
    {
        Context* sContext = nullptr;

        // a triangle that covers the target, and its colour from the source (turned over when asked)
        NSString* const sCopySource = @R"(#include <metal_stdlib>
using namespace metal;
struct Out { float4 position [[position]]; float2 uv; };
vertex Out copyVertex(uint id [[vertex_id]], constant uint& flip [[buffer(0)]])
{
    float2 p = float2(float((id << 1) & 2), float(id & 2));
    Out out;
    out.position = float4(p * 2.0 - 1.0, 0.0, 1.0);
    out.uv = float2(p.x, flip != 0 ? p.y : 1.0 - p.y);
    return out;
}
fragment float4 copyFragment(Out in [[stage_in]], texture2d<float> source [[texture(0)]], sampler s [[sampler(0)]])
{
    return source.sample(s, in.uv);
}
)";
    }

    Context* Context::get()
    {
        return sContext;
    }

    Context::Context(Device& device)
        : mDevice(device)
    {
        mInFlight = dispatch_semaphore_create(sFramesInFlight);
    }

    Context* Context::create(CAMetalLayer* layer, int width, int height)
    {
        Device* device = Mtl::device();
        if (device == nullptr || layer == nil)
            return nullptr;
        auto* context = new Context(*device);
        context->mLayer = layer;
        layer.device = device->mDevice;
        layer.pixelFormat = MTLPixelFormatBGRA8Unorm;
        // read back for the loading screen's background and saved games' screenshots (blitWindow)
        layer.framebufferOnly = NO;
        layer.maximumDrawableCount = 3;
        layer.opaque = YES;
        context->resize(static_cast<uint32_t>(std::max(width, 1)), static_cast<uint32_t>(std::max(height, 1)));
        // (made with the window, on the main thread, where UIKit may be asked; ProMotion screens give 120 with
        // CADisableMinimumFrameDurationOnPhone in Info.plist)
        const NSInteger refresh = [UIScreen mainScreen].maximumFramesPerSecond;
        if (refresh > 0)
            context->mDisplayPeriodMs = 1000.0 / static_cast<double>(refresh);
        sContext = context;
        Log(Debug::Info) << "Metal: window " << width << "x" << height << ", " << sFramesInFlight
                         << " frames in flight, display " << 1000.0 / context->mDisplayPeriodMs << " Hz";
        return context;
    }

    void Context::resize(uint32_t width, uint32_t height)
    {
        if (width == mWidth && height == mHeight && mDepth != nil)
            return;
        if (mDepth != nil)
            Log(Debug::Info) << "Metal: window " << width << "x" << height;
        mWidth = width;
        mHeight = height;
        mLayer.drawableSize = CGSizeMake(width, height);
        MTLTextureDescriptor* depth = [MTLTextureDescriptor texture2DDescriptorWithPixelFormat:mDevice.mDepthFormat
                                                                                         width:width
                                                                                        height:height
                                                                                     mipmapped:NO];
        depth.usage = MTLTextureUsageRenderTarget;
        depth.storageMode = MTLStorageModePrivate;
        // the old one may still be drawn into by the frames in flight: it goes with them
        id<MTLTexture> old = mDepth;
        if (old != nil)
            releaseLater([old] { (void)old; });
        mDepth = [mDevice.mDevice newTextureWithDescriptor:depth];
        mDepth.label = @"window depth";
    }

    void Context::setLayer(CAMetalLayer* layer)
    {
        if (layer == mLayer || layer == nil)
            return;
        mLayer = layer;
        layer.device = mDevice.mDevice;
        layer.pixelFormat = MTLPixelFormatBGRA8Unorm;
        layer.framebufferOnly = NO;
        layer.maximumDrawableCount = 3;
        layer.drawableSize = CGSizeMake(mWidth, mHeight);
    }

    void Context::releaseLater(std::function<void()> release)
    {
        mReleases[mFrameSlot].push_back(std::move(release));
    }

    void Context::afterFrame(std::function<void()> work)
    {
        mAfterFrame.push_back(std::move(work));
    }

    void Context::newCommands()
    {
        mCommands = [mDevice.mQueue commandBuffer];
        mCommands.label = mFirstCommitted ? @"frame (window)" : @"frame (render targets)";
        ++mCommandsGeneration;
    }

    bool Context::beginFrame()
    {
        if (mBegun)
            return true;
        if (mLayer == nil)
            return false;
        dispatch_semaphore_wait(mInFlight, DISPATCH_TIME_FOREVER);
        mFrameSlot = static_cast<unsigned>(mFrameNumber % sFramesInFlight);
        std::vector<std::function<void()>> releases;
        releases.swap(mReleases[mFrameSlot]);
        for (auto& release : releases)
            release();
        mFirstCommitted = false;
        mWindowDrawn = false;
        mInWindowPass = false;
        mDrawable = nil;
        newCommands();
        // the GPU's time of the frame: from the first part's start to the last part's end
        __block std::atomic<double>* start = &mGpuStart;
        [mCommands addCompletedHandler:^(id<MTLCommandBuffer> buffer) {
            start->store(buffer.GPUStartTime, std::memory_order_relaxed);
        }];
        mBegun = true;
        return true;
    }

    void Context::endEncoder()
    {
        if (mRender != nil)
        {
            [mRender endEncoding];
            mRender = nil;
            mInWindowPass = false;
        }
        if (mBlit != nil)
        {
            [mBlit endEncoding];
            mBlit = nil;
        }
        if (mCompute != nil)
        {
            [mCompute endEncoding];
            mCompute = nil;
        }
    }

    id<MTLRenderCommandEncoder> Context::beginPass(MTLRenderPassDescriptor* pass, NSString* label)
    {
        endEncoder();
        mRender = [mCommands renderCommandEncoderWithDescriptor:pass];
        if (label != nil)
            mRender.label = label;
        return mRender;
    }

    id<MTLBlitCommandEncoder> Context::blitEncoder()
    {
        if (mBlit != nil)
            return mBlit;
        endEncoder();
        mBlit = [mCommands blitCommandEncoder];
        return mBlit;
    }

    id<MTLComputeCommandEncoder> Context::computeEncoder()
    {
        if (mCompute != nil)
            return mCompute;
        endEncoder();
        mCompute = [mCommands computeCommandEncoder];
        return mCompute;
    }

    id<MTLBlitCommandEncoder> Context::uploadEncoder()
    {
        if (mUploadBlit != nil)
            return mUploadBlit;
        if (mUploads == nil)
        {
            mUploads = [mDevice.mQueue commandBuffer];
            mUploads.label = @"uploads";
        }
        mUploadBlit = [mUploads blitCommandEncoder];
        return mUploadBlit;
    }

    void Context::clearAhead(MTLRenderPassDescriptor* pass)
    {
        if (mUploadBlit != nil)
        {
            [mUploadBlit endEncoding];
            mUploadBlit = nil;
        }
        if (mUploads == nil)
        {
            mUploads = [mDevice.mQueue commandBuffer];
            mUploads.label = @"uploads";
        }
        id<MTLRenderCommandEncoder> encoder = [mUploads renderCommandEncoderWithDescriptor:pass];
        encoder.label = @"clear";
        [encoder endEncoding];
    }

    void Context::commitUploads()
    {
        if (mUploads == nil)
            return;
        if (mUploadBlit != nil)
        {
            [mUploadBlit endEncoding];
            mUploadBlit = nil;
        }
        [mUploads commit];
        mUploads = nil;
    }

    void Context::commitFirstPart()
    {
        if (mFirstCommitted)
            return;
        endEncoder();
        commitUploads();
        [mCommands commit];
        mFirstCommitted = true;
        newCommands();
    }

    bool Context::beginWindowPass(bool clearColor, const float color[4], bool clearDepth, float depth, uint32_t stencil)
    {
        if (!beginFrame())
            return false;
        // the render targets drawn so far go to the GPU before the drawable is waited for
        if (!mFirstCommitted)
            commitFirstPart();
        if (mDrawable == nil)
        {
            if (mLayer.drawableSize.width != mWidth || mLayer.drawableSize.height != mHeight)
                mLayer.drawableSize = CGSizeMake(mWidth, mHeight);
            mDrawable = [mLayer nextDrawable];
            if (mDrawable == nil)
                return false;
        }
        MTLRenderPassDescriptor* pass = [MTLRenderPassDescriptor renderPassDescriptor];
        pass.colorAttachments[0].texture = mDrawable.texture;
        // the first pass of the frame on the drawable: what was there before is not needed unless it is kept
        pass.colorAttachments[0].loadAction
            = clearColor ? MTLLoadActionClear : (mWindowDrawn ? MTLLoadActionLoad : MTLLoadActionDontCare);
        pass.colorAttachments[0].storeAction = MTLStoreActionStore;
        pass.colorAttachments[0].clearColor = MTLClearColorMake(color[0], color[1], color[2], color[3]);
        pass.depthAttachment.texture = mDepth;
        pass.depthAttachment.loadAction
            = clearDepth ? MTLLoadActionClear : (mWindowDrawn ? MTLLoadActionLoad : MTLLoadActionDontCare);
        pass.depthAttachment.storeAction = MTLStoreActionStore;
        pass.depthAttachment.clearDepth = depth;
        pass.stencilAttachment.texture = mDepth;
        pass.stencilAttachment.loadAction = pass.depthAttachment.loadAction;
        pass.stencilAttachment.storeAction = MTLStoreActionStore;
        pass.stencilAttachment.clearStencil = stencil;
        if (beginPass(pass, @"window") == nil)
            return false;
        mWindowDrawn = true;
        mInWindowPass = true;
        return true;
    }

    void Context::endWindowPass()
    {
        if (!mInWindowPass)
            return;
        endEncoder();
    }

    id<MTLRenderPipelineState> Context::copyPipeline(MTLPixelFormat format)
    {
        auto found = mCopyPipelines.find(format);
        if (found != mCopyPipelines.end())
            return found->second;
        if (mCopyLibrary == nil)
        {
            NSError* error = nil;
            mCopyLibrary = [mDevice.mDevice newLibraryWithSource:sCopySource options:nil error:&error];
            if (mCopyLibrary == nil)
            {
                Log(Debug::Error) << "Metal: the copy shaders: " << [[error localizedDescription] UTF8String];
                return nil;
            }
            for (int linear = 0; linear < 2; ++linear)
            {
                MTLSamplerDescriptor* sampler = [MTLSamplerDescriptor new];
                sampler.minFilter = sampler.magFilter = linear ? MTLSamplerMinMagFilterLinear : MTLSamplerMinMagFilterNearest;
                sampler.sAddressMode = sampler.tAddressMode = MTLSamplerAddressModeClampToEdge;
                mCopySamplers[linear] = [mDevice.mDevice newSamplerStateWithDescriptor:sampler];
            }
        }
        MTLRenderPipelineDescriptor* descriptor = [MTLRenderPipelineDescriptor new];
        descriptor.vertexFunction = [mCopyLibrary newFunctionWithName:@"copyVertex"];
        descriptor.fragmentFunction = [mCopyLibrary newFunctionWithName:@"copyFragment"];
        descriptor.colorAttachments[0].pixelFormat = format;
        NSError* error = nil;
        id<MTLRenderPipelineState> pipeline = [mDevice.mDevice newRenderPipelineStateWithDescriptor:descriptor
                                                                                              error:&error];
        if (pipeline == nil)
            Log(Debug::Error) << "Metal: the copy pipeline: " << [[error localizedDescription] UTF8String];
        mCopyPipelines[format] = pipeline;
        return pipeline;
    }

    void Context::drawCopy(id<MTLTexture> source, id<MTLTexture> target, bool flipY, bool linear)
    {
        if (source == nil || target == nil)
            return;
        id<MTLRenderPipelineState> pipeline = copyPipeline(target.pixelFormat);
        if (pipeline == nil)
            return;
        MTLRenderPassDescriptor* pass = [MTLRenderPassDescriptor renderPassDescriptor];
        pass.colorAttachments[0].texture = target;
        pass.colorAttachments[0].loadAction = MTLLoadActionDontCare;
        pass.colorAttachments[0].storeAction = MTLStoreActionStore;
        id<MTLRenderCommandEncoder> encoder = beginPass(pass, @"copy");
        [encoder setRenderPipelineState:pipeline];
        const uint32_t flip = flipY ? 1 : 0;
        [encoder setVertexBytes:&flip length:sizeof(flip) atIndex:0];
        [encoder setFragmentTexture:source atIndex:0];
        [encoder setFragmentSamplerState:mCopySamplers[linear ? 1 : 0] atIndex:0];
        [encoder drawPrimitives:MTLPrimitiveTypeTriangle vertexStart:0 vertexCount:3];
        endEncoder();
    }

    bool Context::blitWindow(id<MTLTexture> target)
    {
        if (!mWindowDrawn || mDrawable == nil || mInWindowPass || target == nil)
            return false;
        // the window is top row first, a texture the OpenGL way (bottom row first)
        drawCopy(mDrawable.texture, target, true, true);
        return true;
    }

    void Context::present(int width, int height)
    {
        if (width > 0 && height > 0)
            resize(static_cast<uint32_t>(width), static_cast<uint32_t>(height));
        std::vector<std::function<void()>> after;
        after.swap(mAfterFrame);
        if (!beginFrame())
        {
            for (auto& work : after)
                work();
            return;
        }
        // nothing drawn on the window this frame: it is cleared, so that there is something to present
        if (!mWindowDrawn)
        {
            const float black[4] = { 0.f, 0.f, 0.f, 1.f };
            if (beginWindowPass(true, black, true, 1.f, 0))
                endWindowPass();
        }
        endEncoder();
        commitUploads();
        if (mDrawable != nil)
            [mCommands presentDrawable:mDrawable];
        dispatch_semaphore_t inFlight = mInFlight;
        __block std::atomic<double>* gpuMs = &mGpuMs;
        __block std::atomic<double>* start = &mGpuStart;
        const bool split = mFirstCommitted;
        [mCommands addCompletedHandler:^(id<MTLCommandBuffer> buffer) {
            if (buffer.status == MTLCommandBufferStatusError)
            {
                static std::atomic<int> logged{ 0 };
                if (logged.fetch_add(1) < 5)
                    Log(Debug::Error) << "Metal: a frame failed on the GPU: "
                                      << [[buffer.error localizedDescription] UTF8String];
            }
            const double begin = split ? start->load(std::memory_order_relaxed) : buffer.GPUStartTime;
            if (begin > 0.0 && buffer.GPUEndTime > begin)
                gpuMs->store((buffer.GPUEndTime - begin) * 1000.0, std::memory_order_relaxed);
            dispatch_semaphore_signal(inFlight);
        }];
        id<MTLCommandBuffer> last = mCommands;
        [mCommands commit];
        if (!after.empty())
        {
            [last waitUntilCompleted];
            for (auto& work : after)
                work();
        }
        mCommands = nil;
        mDrawable = nil;
        mBegun = false;
        ++mFrameNumber;
    }
}
