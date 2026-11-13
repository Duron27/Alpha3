#ifndef ALPHA3_SUPPORT_METAL_CONTEXT_H
#define ALPHA3_SUPPORT_METAL_CONTEXT_H

// The window's CAMetalLayer and the frames drawn into it, as src/vulkan/context.hpp is the swap chain's. The draw
// thread's: one frame at a time is recorded, sFramesInFlight of them in flight on the GPU. A frame is two command
// buffers: what is drawn before the window's first pass (render targets) is committed at once, so the GPU starts on
// it while the drawable is waited for, and the rest with the drawable. Uploads go on command buffers of their own,
// committed just before the part they were recorded for.
#import <Metal/Metal.h>
#import <QuartzCore/CAMetalLayer.h>

#include "device.hpp"

#include <atomic>
#include <cstdint>
#include <functional>
#include <map>
#include <vector>

namespace Alpha3::Mtl
{
    constexpr unsigned sFramesInFlight = 3;

    class Context
    {
    public:
        // The window's, once the graphics window made it (null with OpenGL ES)
        static Context* get();
        static Context* create(CAMetalLayer* layer, int width, int height);

        Device& device() { return mDevice; }
        id<MTLDevice> mtl() const { return mDevice.mDevice; }

        // Starts the frame if it is not started: waits for the GPU to be done with the frame recorded sFramesInFlight
        // ago, then lets go of what it used. False when there is nothing to draw on (no layer).
        bool beginFrame();
        bool frameBegun() const { return mBegun; }
        id<MTLCommandBuffer> commands() const { return mCommands; }
        // Counts the command buffers begun: what was bound on an encoder of an earlier one is gone
        uint64_t commandsGeneration() const { return mCommandsGeneration; }

        // The encoders of the frame's command buffer. One is open at a time: asking for another kind ends it.
        // renderEncoder: the open render pass's (nil outside one)
        id<MTLRenderCommandEncoder> renderEncoder() const { return mRender; }
        id<MTLRenderCommandEncoder> beginPass(MTLRenderPassDescriptor* pass, NSString* label);
        id<MTLBlitCommandEncoder> blitEncoder();
        id<MTLComputeCommandEncoder> computeEncoder();
        // ends whatever encoder is open (a render pass too)
        void endEncoder();
        // Copies that go before the frame's commands (uploads): on a command buffer committed ahead of them, so they
        // may be recorded while a render pass is open
        id<MTLBlitCommandEncoder> uploadEncoder();
        // A render pass that only clears (a new render target, as if drawn into before it is read), ahead of the
        // frame's commands as the uploads are
        void clearAhead(MTLRenderPassDescriptor* pass);

        unsigned frameSlot() const { return mFrameSlot; }
        uint64_t frameNumber() const { return mFrameNumber; }
        // Runs once the GPU is done with this frame (resources released while it may still read them)
        void releaseLater(std::function<void()> release);
        // Runs when this frame is presented, after the GPU has finished it (present() waits for that, so only for
        // what must not wait longer: a read back the main thread is blocked on). At once when there is no layer.
        void afterFrame(std::function<void()> work);

        // The window as a render target, begun as a render pass: clears what is asked, keeps the rest. The drawable
        // is taken on the first one of the frame. False when there is none (the app is in the background).
        bool beginWindowPass(bool clearColor, const float color[4], bool clearDepth, float depth, uint32_t stencil);
        bool inWindowPass() const { return mInWindowPass; }
        void endWindowPass();
        uint32_t windowWidth() const { return mWidth; }
        uint32_t windowHeight() const { return mHeight; }
        MTLPixelFormat windowFormat() const { return MTLPixelFormatBGRA8Unorm; }
        MTLPixelFormat windowDepthFormat() const { return mDevice.mDepthFormat; }
        // What was drawn on the window this frame so far, copied into a colour texture turned the OpenGL way up (as
        // glCopyTexImage2D gives it). False when the window can't be read.
        bool blitWindow(id<MTLTexture> target);

        // A copy of a colour texture into another by a draw (scaled, filtered, optionally turned over), for what a
        // blit encoder cannot do: sizes or formats that differ. Outside a render pass.
        void drawCopy(id<MTLTexture> source, id<MTLTexture> target, bool flipY, bool linear);

        // Ends the frame: what was recorded is committed and the drawable presented (cleared when nothing was drawn
        // on it). The layer follows the size given.
        void present(int width, int height);
        // The view was replaced (the app went to the background and came back)
        void setLayer(CAMetalLayer* layer);

        // The display's refresh period in ms (8.3 on a 120 Hz ProMotion screen), read when the context was made
        double displayPeriodMs() const { return mDisplayPeriodMs; }

        // the GPU's time for the last finished frame, in ms (both command buffers)
        double gpuMilliseconds() const { return mGpuMs.load(std::memory_order_relaxed); }

    private:
        explicit Context(Device& device);
        void resize(uint32_t width, uint32_t height);
        void newCommands();
        // Commits the frame's first part (before the drawable is taken) and begins the second
        void commitFirstPart();
        void commitUploads();
        id<MTLRenderPipelineState> copyPipeline(MTLPixelFormat format);

        Device& mDevice;
        CAMetalLayer* mLayer = nil;
        uint32_t mWidth = 0, mHeight = 0;
        id<MTLTexture> mDepth = nil;
        id<CAMetalDrawable> mDrawable = nil;
        id<MTLCommandBuffer> mCommands = nil;
        id<MTLCommandBuffer> mUploads = nil;
        id<MTLRenderCommandEncoder> mRender = nil;
        id<MTLBlitCommandEncoder> mBlit = nil;
        id<MTLComputeCommandEncoder> mCompute = nil;
        id<MTLBlitCommandEncoder> mUploadBlit = nil;
        dispatch_semaphore_t mInFlight = nullptr;
        std::vector<std::function<void()>> mReleases[sFramesInFlight];
        std::vector<std::function<void()>> mAfterFrame;
        unsigned mFrameSlot = 0;
        uint64_t mFrameNumber = 0;
        uint64_t mCommandsGeneration = 0;
        bool mBegun = false;
        bool mFirstCommitted = false; // the frame's first part went (the drawable is taken)
        bool mWindowDrawn = false;
        bool mInWindowPass = false;
        double mDisplayPeriodMs = 1000.0 / 60.0;
        std::atomic<double> mGpuMs{ 0.0 };
        std::atomic<double> mGpuStart{ 0.0 };
        id<MTLLibrary> mCopyLibrary = nil;
        std::map<MTLPixelFormat, id<MTLRenderPipelineState>> mCopyPipelines;
        id<MTLSamplerState> mCopySamplers[2] = { nil, nil };
    };
}

#endif
