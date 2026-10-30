// iOS stand-ins for the parts of the support library that only exist on Android: Swappy frame pacing and the frame times
// for the launcher (framepacing.cpp), frame generation (framegen.cpp), Mesa Zink (zink.cpp), mimalloc (allocator.cpp) and
// the player state androidmain.cpp hands to the app. The swap is SDL's own: Core Animation paces it to the display.
#include "support.hpp"

#include <alpha3/upstream.hpp>

#include <algorithm>
#include <chrono>
#include <functional>
#include <mutex>

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

    bool frameGenRequested()
    {
        return false;
    }

    bool frameGenSwap(osg::GraphicsContext&, int, int, bool, const std::function<void(int64_t)>&)
    {
        return false;
    }

    bool frameGenWanted()
    {
        return false;
    }

    void frameGenSubmitView(FrameGenView) {}

    osg::Camera::DrawCallback* frameGenPreGuiCapture()
    {
        return nullptr;
    }

    void zinkClipPlane(unsigned int, const double*) {}

    // libc++ is a shared library on iOS, so operator new cannot be replaced for the engine alone (memory libc++ grew
    // would come back to our delete): the system allocator stays
    void applyAllocatorSetting() {}
}

// apps/openmw/androidmain.cpp on Android: the player state the launcher polls. The iOS launcher does not read it.
extern "C" void omw_frame_update() {}
