// iOS stand-ins for the parts of the support library that only exist on Android: Swappy frame pacing and the frame times
// for the launcher (framepacing.cpp), Mesa Zink (zink.cpp) and mimalloc (allocator.cpp); and frame generation's store of
// the engine's cameras (framegen.cpp's on Android; the generated frames are metal/translator.mm's). The swap is SDL's
// own: Core Animation paces it to the display.
#include "support.hpp"

#include <alpha3/upstream.hpp>

#include <algorithm>
#include <array>
#include <chrono>
#include <cstdlib>
#include <cstring>
#include <functional>
#include <mutex>

#include <osg/Camera>
#include <osg/GraphicsContext>

namespace
{
    // The last frame intervals, for the launcher's performance overlay (Android: framepacing.cpp and androidmain.cpp)
    constexpr size_t sFrameHistory = 240;
    std::mutex sFrameMutex;
    float sFrameTimes[sFrameHistory];
    size_t sFrameCount = 0;

    void recordFrame()
    {
        using Clock = std::chrono::steady_clock;
        static Clock::time_point last = Clock::now();
        const Clock::time_point now = Clock::now();
        const float milliseconds = std::chrono::duration<float, std::milli>(now - last).count();
        last = now;
        std::lock_guard<std::mutex> lock(sFrameMutex);
        sFrameTimes[sFrameCount++ % sFrameHistory] = milliseconds;
    }
}

// Copies up to [capacity] of the last frame intervals in milliseconds, oldest first; returns how many. The launcher looks
// it up with dlsym while the overlay's performance hub is on.
extern "C" __attribute__((visibility("default"))) int alpha3_frame_times(float* out, int capacity)
{
    std::lock_guard<std::mutex> lock(sFrameMutex);
    const size_t count = std::min({ sFrameCount, sFrameHistory, static_cast<size_t>(std::max(capacity, 0)) });
    for (size_t i = 0; i < count; ++i)
        out[i] = sFrameTimes[(sFrameCount - count + i) % sFrameHistory];
    return static_cast<int>(count);
}

namespace Alpha3
{
    void swapBuffers(osg::GraphicsContext& gc)
    {
        recordFrame();
        gc.swapBuffersImplementation();
    }

    void paceZinkPost() {}

    // Frame generation (OPENMW_FRAMEGEN, the launcher's switch) is the Metal renderer's (metal/translator.mm), as it is
    // the Vulkan renderer's on Android: the engine's camera and textures for the last few frames are kept here (handed
    // over by iosengine.cpp), the translator draws the generated frames. OpenGL ES and ANGLE have none.
    bool frameGenRequested()
    {
        const char* request = std::getenv("OPENMW_FRAMEGEN");
        return request != nullptr && *request != '\0' && std::strcmp(request, "0") != 0;
    }

    bool frameGenSwap(osg::GraphicsContext&, int, int, bool, int64_t, const std::function<void(int64_t)>&)
    {
        return false;
    }

    bool frameGenWanted()
    {
        static const bool wanted = frameGenRequested() && usesMetal();
        return wanted;
    }

    namespace
    {
        std::mutex sViewsMutex;
        std::array<FrameGenView, 4> sViews;

        // the post processor's HUD camera gets it (the OpenGL ES path copies the window before the GUI there); Metal
        // keeps its own images, so it does nothing
        struct NoCapture : osg::Camera::DrawCallback
        {
            void operator()(osg::RenderInfo&) const override {}
        };
    }

    void frameGenSubmitView(FrameGenView view)
    {
        std::lock_guard lock(sViewsMutex);
        sViews[view.frame % sViews.size()] = std::move(view);
    }

    bool frameGenFindView(unsigned frame, FrameGenView& out)
    {
        std::lock_guard lock(sViewsMutex);
        const FrameGenView& view = sViews[frame % sViews.size()];
        if (view.frame != frame || !view.scene || !view.depth || !view.opaqueDepth)
            return false;
        out = view;
        return true;
    }

    osg::Camera::DrawCallback* frameGenPreGuiCapture()
    {
        static osg::ref_ptr<osg::Camera::DrawCallback> sCapture = new NoCapture;
        return sCapture.get();
    }

    // a generated frame sent to the screen: counted in the launcher's frame times like a rendered one
    void noteShownFrame()
    {
        recordFrame();
    }

    void zinkClipPlane(unsigned int, const double*) {}

    // libc++ is a shared library on iOS, so operator new cannot be replaced for the engine alone (memory libc++ grew
    // would come back to our delete): the system allocator stays
    void applyAllocatorSetting() {}
}

// omw_frame_update (androidmain.cpp's on Android) is iosengine.cpp's here.
