#ifndef ALPHA3_SUPPORT_FRAMEGENLOCK_H
#define ALPHA3_SUPPORT_FRAMEGENLOCK_H

// How many generated frames go between rendered ones, from how fast the rendered frames come: shared by the OpenGL
// ES frame generation (framegen.cpp) and Vulkan's (vulkan/translator.cpp)
#include <chrono>
#include <iomanip>
#include <string>

#include <components/debug/debuglog.hpp>

namespace Alpha3
{
    using FrameGenClock = std::chrono::steady_clock;

    // With the display's period known (Android), generated frames fill the refreshes the rendered frames cannot: the
    // rendered frames are held to a pattern of refreshes they keep up with, and generated frames fill the rest, so every
    // refresh shows a new frame. On a 60 Hz display:
    //   frames under 16.7 ms   every refresh rendered, none generated ("resting")
    //   up to 25 ms            two rendered then one generated every three refreshes (40 + 20)
    //   up to 33 ms            one rendered then one generated (30 + 30)
    //   up to 50 ms            one rendered then two generated (20 + 40), when the launcher's multiplier allows
    //   up to 67 ms            one rendered then three generated (15 + 45), likewise
    // Every so often the pattern one step lighter is tried for half a second, since nothing shows whether the rendered
    // frames would fit while they are held.
    struct FrameGenLock
    {
        struct Step
        {
            int mRendered; // rendered frames in the pattern, the generated ones after the last
            int mRefreshes; // refreshes it fills
        };
        static constexpr Step sSteps[] = { { 1, 1 }, { 2, 3 }, { 1, 2 }, { 1, 3 }, { 1, 4 } };
        static constexpr int sStepCount = 5;

        int mStep = 0;
        FrameGenClock::time_point mWindowStart = FrameGenClock::now();
        FrameGenClock::time_point mChangedAt = mWindowStart;
        std::chrono::seconds mProbeWait{ 10 };
        int mWindowFrames = 0;
        bool mProbing = false;

        const Step& step() const { return sSteps[mStep]; }
        int rendered() const { return step().mRendered; }
        int generated() const { return step().mRefreshes - step().mRendered; }
        /// Refreshes between the last two rendered frames before the generated ones (what one rendered frame's motion
        /// spans)
        int lastGap() const { return step().mRendered == 1 ? step().mRefreshes : 1; }

        void set(int index, FrameGenClock::time_point now, double intervalMs, const char* why)
        {
            mStep = index;
            mChangedAt = now;
            Log(Debug::Info) << "Frame generation: " << why << " (rendered frames every " << std::fixed
                             << std::setprecision(1) << intervalMs << " ms), "
                             << (generated() == 0 ? std::string("resting")
                                                  : std::to_string(rendered()) + " rendered then "
                                         + std::to_string(generated()) + " generated every "
                                         + std::to_string(step().mRefreshes) + " refreshes");
        }

        /// At a rendered frame. most: the last step allowed.
        void frame(FrameGenClock::time_point now, double periodMs, int most)
        {
            ++mWindowFrames;
            const double windowMs = std::chrono::duration<double, std::milli>(now - mWindowStart).count();
            if (windowMs < (mProbing ? 500.0 : 1000.0))
                return;
            const double interval = windowMs / mWindowFrames;
            mWindowStart = now;
            mWindowFrames = 0;
            const double budget = periodMs * step().mRefreshes / step().mRendered;
            const bool holds = interval <= budget * 1.08;
            if (mProbing)
            {
                mProbing = false;
                if (holds)
                {
                    mProbeWait = std::chrono::seconds(10);
                    set(mStep, now, interval, "a lighter pattern holds");
                }
                else
                {
                    set(mStep + 1, now, interval, "the lighter pattern did not hold");
                    mProbeWait = std::min(mProbeWait * 2, std::chrono::seconds(80));
                }
                return;
            }
            if (!holds && mStep < most)
            {
                mProbeWait = std::chrono::seconds(10);
                set(mStep + 1, now, interval, "rendered frames are slower than the pattern");
            }
            else if (mStep > 0 && now - mChangedAt > mProbeWait)
            {
                mStep -= 1;
                mProbing = true;
                mChangedAt = now;
            }
        }
    };

}

#endif
