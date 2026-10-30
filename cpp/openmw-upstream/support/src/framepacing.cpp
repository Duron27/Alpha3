// Every swap of the engine's window goes through here (RuntimeHooks::swapBuffers): Swappy (Android Frame Pacing) on the
// OpenGL ES path, and the frame intervals for the launcher's performance overlay. Moved from OpenMW's
// SDLUtil::GraphicsWindowSDL2 (Swappy) and OMW::Engine::go (frame times).
#include "support.hpp"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <limits>
#include <thread>
#include <vector>

#include <dlfcn.h>
#include <time.h>

#include <EGL/egl.h>
#include <EGL/eglext.h>
#include <SDL_syswm.h>
#include <SDL_system.h>
#include <SDL_video.h>
#include <android/choreographer.h>
#include <android/looper.h>
#include <jni.h>
#include <swappy/swappyGL.h>
#include <swappy/swappyGL_extra.h>

#include <osg/GraphicsContext>
#include <osg/State>

#include <components/debug/debuglog.hpp>

extern "C" void omw_record_frame_time(float milliseconds); // apps/openmw/androidmain.cpp

namespace Alpha3
{
    namespace
    {
        using Clock = std::chrono::steady_clock;

        // The interval Swappy holds the frames to (nanoseconds), 0 when it is not pacing: the engine's time step follows it
        std::atomic<uint64_t> sPacedInterval{ 0 };

        ANativeWindow* currentNativeWindow()
        {
            SDL_Window* window = SDL_GL_GetCurrentWindow();
            if (window == nullptr)
                return nullptr;
            SDL_SysWMinfo info;
            SDL_VERSION(&info.version);
            if (!SDL_GetWindowWMInfo(window, &info) || info.subsystem != SDL_SYSWM_ANDROID)
                return nullptr;
            return info.info.android.window;
        }

        struct Pacing
        {
            bool mTried = false;
            bool mSwappy = false;
            ANativeWindow* mWindow = nullptr;

            void init()
            {
                mTried = true;
                // ANGLE brings its own EGL, which Swappy (it talks to the system's) cannot swap; Zink presents by copying
                if (usesZink() || std::getenv("OSG_USE_ANGLE") != nullptr || std::getenv("OPENMW_NO_SWAPPY") != nullptr)
                {
                    if (!usesZink() && frameGenRequested())
                        Log(Debug::Warning) << "Frame generation needs frame pacing (Swappy, OpenGL ES system driver only): off";
                    return;
                }
                auto* env = static_cast<JNIEnv*>(SDL_AndroidGetJNIEnv());
                jobject activity = static_cast<jobject>(SDL_AndroidGetActivity());
                mWindow = currentNativeWindow();
                if (env == nullptr || activity == nullptr || mWindow == nullptr)
                {
                    Log(Debug::Warning) << "Frame pacing: no Java environment or native window, using plain swaps";
                    return;
                }
                const bool started = SwappyGL_init(env, activity);
                env->DeleteLocalRef(activity);
                if (!started)
                {
                    Log(Debug::Warning) << "Frame pacing: Swappy could not start, using plain swaps";
                    return;
                }
                if (!SwappyGL_setWindow(mWindow))
                {
                    Log(Debug::Warning) << "Frame pacing: Swappy rejected the window, using plain swaps";
                    SwappyGL_destroy();
                    return;
                }
                // In automatic mode Swappy rounds the frame time up to a whole number of display periods and paces to
                // that, so a frame that takes 35 ms on a 60 Hz display is held to 50 ms (20 fps). Past two periods it
                // should stop pacing and submit as soon as the frame is done (the swap chain is still triple buffered), so
                // the frame rate follows the real frame time instead of dropping to the next step.
                mPeriod = SwappyGL_getRefreshPeriodNanos();
                if (const char* periods = std::getenv("OPENMW_SWAPPY_MAX_PERIODS"))
                {
                    mMaxPeriods = std::clamp<uint64_t>(std::strtoull(periods, nullptr, 10), 1, 6);
                    mAdaptive = false;
                }
                setMaxPeriods(mMaxPeriods);
                mSwappy = true;
                Log(Debug::Info) << "Frame pacing: Swappy, display period " << mPeriod / 1.0e6 << " ms";
            }

            void setMaxPeriods(uint64_t periods)
            {
                mMaxPeriods = periods;
                SwappyGL_setMaxAutoSwapIntervalNS(mPeriod * periods + mPeriod / 8);
            }

            // Past mMaxPeriods display periods a frame goes out unpaced, at whichever refresh it is done by, so frames of
            // 40 ms show for 33 and 50 ms in turn: judder. When most frames for two seconds take longer than two periods,
            // three are allowed (Swappy then holds them steady, it picks the interval itself and drops back on its own);
            // every ten seconds two are tried again, since nothing shows whether the frames would fit while they are held.
            void adapt(Clock::time_point now)
            {
                if (!mAdaptive)
                    return;
                const float frameMs = std::chrono::duration<float, std::milli>(now - mLastSwap).count();
                mLastSwap = now;
                const float twoPeriodsMs = 2.0f * mPeriod / 1.0e6f;
                ++mWindowFrames;
                if (frameMs > twoPeriodsMs * 1.06f)
                    ++mSlowFrames;
                if (now - mWindowStart < std::chrono::seconds(2))
                    return;
                if (mMaxPeriods == 2 && mSlowFrames * 2 > mWindowFrames)
                {
                    setMaxPeriods(3);
                    mRaisedAt = now;
                    Log(Debug::Verbose) << "Frame pacing: frames over " << twoPeriodsMs << " ms, holding them to three periods";
                }
                else if (mMaxPeriods == 3 && now - mRaisedAt > std::chrono::seconds(10))
                    setMaxPeriods(2);
                mWindowStart = now;
                mWindowFrames = 0;
                mSlowFrames = 0;
            }

            uint64_t mPeriod = 0;
            uint64_t mMaxPeriods = 2;
            bool mThermalHeld = false;
            bool mAdaptive = true;
            Clock::time_point mLastSwap = Clock::now();
            Clock::time_point mWindowStart = mLastSwap;
            Clock::time_point mRaisedAt;
            unsigned mWindowFrames = 0;
            unsigned mSlowFrames = 0;

            // Swaps through Swappy; false when it is not in use
            bool swap(osg::GraphicsContext& gc)
            {
                if (!mTried)
                    init();
                if (!mSwappy || !SwappyGL_isEnabled())
                    return false;
                // the surface is made again when the app comes back to the front
                if (ANativeWindow* window = currentNativeWindow(); window != nullptr && window != mWindow)
                {
                    mWindow = window;
                    if (!SwappyGL_setWindow(window))
                    {
                        Log(Debug::Warning) << "Frame pacing: Swappy rejected the new window, using plain swaps";
                        SwappyGL_destroy();
                        mSwappy = false;
                        sPacedInterval = 0;
                        return false;
                    }
                }
                // Swappy times the swaps itself and needs the driver's vertical sync on, whatever the setting says
                if (SDL_GL_GetSwapInterval() != 1)
                    SDL_GL_SetSwapInterval(1);
                EGLDisplay display = eglGetCurrentDisplay();
                EGLSurface surface = eglGetCurrentSurface(EGL_DRAW);
                EGLint width = 0, height = 0;
                eglQuerySurface(display, surface, EGL_WIDTH, &width);
                eglQuerySurface(display, surface, EGL_HEIGHT, &height);
                // Frame generation swaps without Swappy: the rendered frame goes out as soon as it is done, and the generated
                // ones after it carry the time they are to be shown (EGL_ANDROID_presentation_time), so nothing is held
                // back. The time step is measured.
                if (frameGenSwap(gc, width, height, false, [&](int64_t presentAt) {
                        static const auto presentationTime = reinterpret_cast<PFNEGLPRESENTATIONTIMEANDROIDPROC>(
                            eglGetProcAddress("eglPresentationTimeANDROID"));
                        if (presentAt != 0 && presentationTime != nullptr)
                            presentationTime(display, surface, presentAt);
                        eglSwapBuffers(display, surface);
                    }))
                {
                    sPacedInterval = 0;
                    return true;
                }
                // hot (thermal.cpp): at least two display periods a frame, so the GPU rests before Android clamps every core
                if (const bool hot = thermalPressure() >= 2; hot != mThermalHeld)
                {
                    mThermalHeld = hot;
                    SwappyGL_setSwapIntervalNS(mPeriod * (hot ? 2 : 1));
                    Log(Debug::Info) << "Frame pacing: "
                                     << (hot ? "two display periods at least while the phone is hot" : "the phone has cooled");
                }
                SwappyGL_swap(display, surface);
                adapt(Clock::now());
                sPacedInterval = SwappyGL_getSwapIntervalNS();
                return true;
            }
        };

        int64_t monotonicNow()
        {
            timespec ts;
            clock_gettime(CLOCK_MONOTONIC, &ts);
            return int64_t(ts.tv_sec) * 1000000000 + ts.tv_nsec;
        }

        // The display's vertical syncs, from the Choreographer on a thread of its own (Zink has no EGL swap to pace)
        class VsyncClock
        {
        public:
            static VsyncClock& get()
            {
                static VsyncClock clock;
                return clock;
            }

            // The latest vertical sync (CLOCK_MONOTONIC nanoseconds) and the display period; false until both are known
            bool sample(int64_t& vsync, int64_t& period) const
            {
                vsync = mVsync.load(std::memory_order_acquire);
                period = mPeriod.load(std::memory_order_acquire);
                return vsync != 0 && period != 0;
            }

        private:
            VsyncClock()
            {
                std::thread([this] { run(); }).detach();
            }

            void run()
            {
                ALooper_prepare(0);
                mChoreographer = AChoreographer_getInstance();
                if (mChoreographer == nullptr)
                {
                    Log(Debug::Warning) << "Frame pacing: no Choreographer, Zink frames go out unpaced";
                    return;
                }
                AChoreographer_postFrameCallback(mChoreographer, &VsyncClock::onFrame, this);
                while (true)
                    ALooper_pollOnce(-1, nullptr, nullptr, nullptr);
            }

            static void onFrame(long frameTimeNanos, void* data)
            {
                auto* self = static_cast<VsyncClock*>(data);
                const int64_t previous = self->mVsync.exchange(frameTimeNanos, std::memory_order_acq_rel);
                const int64_t delta = frameTimeNanos - previous;
                const int64_t period = self->mPeriod.load(std::memory_order_relaxed);
                // a missed callback spans several periods: only single ones go into the average
                if (previous != 0 && delta > 4000000 && delta < 50000000 && (period == 0 || delta < period * 3 / 2))
                    self->mPeriod.store(period == 0 ? delta : (period * 7 + delta) / 8, std::memory_order_release);
                AChoreographer_postFrameCallback(self->mChoreographer, &VsyncClock::onFrame, self);
            }

            AChoreographer* mChoreographer = nullptr;
            std::atomic<int64_t> mVsync{ 0 };
            std::atomic<int64_t> mPeriod{ 0 };
        };

        // Zink posts each frame as soon as it is copied, so frames of 30 ms show for one, two or three refreshes in turn.
        // Held to a whole number of display periods instead. The holding is done at the start of the engine's main loop: a
        // draw thread that waited before its post would hold the main thread too (OSG lets it cull the next frame only once
        // the draw thread has started on this one), and the two threads that overlapped would take turns. The draw thread
        // only puts each post just after the right vertical sync, and tells how long it had to wait for it; the main loop
        // starts its frames that much later until the wait is short.
        //
        // The number of periods follows the main loop's own time per frame, with part of each interval left spare (the GPU
        // rests, the phone stays cooler): up when a quarter of the frames over a second need more than the budget
        // (OPENMW_ZINK_FRAME_BUDGET, percent, the launcher's slider, 80 by default), down when nine in ten over two seconds
        // would fit in five sixths of the budget of one period fewer. One more period while the phone is warm (thermal.cpp).
        struct ZinkPacing
        {
            static constexpr int64_t sMargin = 1500000; // after the vertical sync, so the post lands in the right period
            static constexpr int64_t sAimedWait = 2000000; // what the draw thread is left to wait before its post
            static constexpr int sMaxInterval = 4;

            const int64_t mBudget = [] {
                const char* value = std::getenv("OPENMW_ZINK_FRAME_BUDGET");
                return value ? std::clamp<int64_t>(std::strtoll(value, nullptr, 10), 50, 100) : int64_t(80);
            }();

            // decided by the main loop, read by the draw thread
            std::atomic<int> mInterval{ 0 };
            // the draw thread's latest wait before a post
            std::atomic<int64_t> mDrawWait{ 0 };

            // main loop
            int mBase = 1;
            int mThermalExtra = 0;
            int64_t mStart = 0; // when the next frame is to start
            int64_t mLastWake = 0;
            int64_t mWindowStart = 0;
            unsigned mWindowFrames = 0;
            unsigned mMissed = 0;
            unsigned mWouldFitLower = 0;

            // draw thread
            int64_t mLastTarget = 0;

            void adapt(int64_t now, int64_t work, int64_t period)
            {
                ++mWindowFrames;
                if (work * 100 > mBase * period * mBudget)
                    ++mMissed;
                if (mBase > 1 && work * 600 < (mBase - 1) * period * mBudget * 5)
                    ++mWouldFitLower;
                if (mWindowStart == 0)
                    mWindowStart = now;
                const int64_t elapsed = now - mWindowStart;
                if (elapsed >= 1000000000 && mMissed * 4 > mWindowFrames && mBase < sMaxInterval)
                    ++mBase;
                else if (elapsed >= 2000000000 && mWouldFitLower * 10 >= mWindowFrames * 9 && mBase > 1)
                    --mBase;
                else if (elapsed < 2000000000)
                    return;
                mWindowStart = now;
                mWindowFrames = mMissed = mWouldFitLower = 0;
            }

            void checkThermal()
            {
                const int extra = thermalPressure() >= 1 ? 1 : 0;
                if (extra != mThermalExtra)
                    Log(Debug::Info) << "Frame pacing: " << (extra ? "one display period more per frame while the phone is warm" : "the phone has cooled");
                mThermalExtra = extra;
            }

            // main loop, before each frame
            void frameStart()
            {
                int64_t vsync = 0, period = 0;
                if (!VsyncClock::get().sample(vsync, period))
                    return;
                const int64_t now = monotonicNow();
                const int64_t drawWait = mDrawWait.exchange(0, std::memory_order_relaxed);
                // the main loop's own time for the last frame: not the time it slept here, nor what it waited on the draw
                // thread's wait for its vertical sync
                if (mLastWake != 0)
                    adapt(now, std::max<int64_t>(now - mLastWake - drawWait, 0), period);
                checkThermal();
                const int interval = std::min(mBase + mThermalExtra, sMaxInterval);
                if (interval != mInterval.load(std::memory_order_relaxed))
                    Log(Debug::Verbose) << "Frame pacing: " << interval << " display periods per frame";
                mInterval.store(interval, std::memory_order_relaxed);
                const int64_t length = interval * period;

                if (mStart == 0 || now > mStart + length)
                    mStart = now; // a whole frame behind: the schedule starts over
                else
                    mStart += std::clamp<int64_t>((drawWait - sAimedWait) / 2, -period / 2, period / 2);
                if (mStart > now)
                    std::this_thread::sleep_for(std::chrono::nanoseconds(mStart - now));
                mLastWake = monotonicNow();
                mStart += length;
                sPacedInterval.store(uint64_t(length), std::memory_order_relaxed);
            }

            // draw thread, before each post: just after the vertical sync the interval asks for
            void beforePost()
            {
                const int interval = mInterval.load(std::memory_order_relaxed);
                int64_t vsync = 0, period = 0;
                if (interval == 0 || !VsyncClock::get().sample(vsync, period))
                    return;
                int64_t now = monotonicNow();
                // the vertical sync a frame posted now would be shown after: the one ending the period it is posted in
                auto targetOf = [&](int64_t time) {
                    const int64_t phase = time - sMargin - vsync;
                    const int64_t periods = phase >= 0 ? phase / period : -((-phase + period - 1) / period);
                    return vsync + (periods + 1) * period;
                };
                const int64_t wanted = mLastTarget + interval * period;
                int64_t wait = 0;
                if (mLastTarget != 0 && targetOf(now) < wanted)
                {
                    wait = wanted - period + sMargin - now;
                    std::this_thread::sleep_for(std::chrono::nanoseconds(wait));
                    now = monotonicNow();
                }
                mDrawWait.store(wait, std::memory_order_relaxed);
                mLastTarget = targetOf(now);
            }
        };

        ZinkPacing* zinkPacing()
        {
            // frame generation times its own frames; OPENMW_NO_SWAPPY is the launcher's switch for frame pacing
            static ZinkPacing* const pacing
                = usesZink() && !frameGenRequested() && std::getenv("OPENMW_NO_SWAPPY") == nullptr ? new ZinkPacing
                                                                                                   : nullptr;
            return pacing;
        }

        // OPENMW_FRAME_STATS=1 logs the frame interval median, 95th percentile and worst every ten seconds
        struct FrameTimes
        {
            Clock::time_point mLast = Clock::now();
            Clock::time_point mStatsStart = mLast;
            const bool mLogStats = std::getenv("OPENMW_FRAME_STATS") != nullptr;
            std::vector<float> mStats;

            void record()
            {
                const Clock::time_point now = Clock::now();
                const float frameMs = std::chrono::duration<float, std::milli>(now - mLast).count();
                mLast = now;
                omw_record_frame_time(frameMs);
                if (!mLogStats)
                    return;
                mStats.push_back(frameMs);
                if (now - mStatsStart < std::chrono::seconds(10))
                    return;
                std::sort(mStats.begin(), mStats.end());
                Log(Debug::Info) << "Frame stats: " << mStats.size() << " frames, median " << mStats[mStats.size() / 2]
                                 << " ms, p95 " << mStats[mStats.size() * 95 / 100] << " ms, worst " << mStats.back()
                                 << " ms";
                mStats.clear();
                mStatsStart = now;
            }
        };
    }

    static double pacedFrameSeconds(double measured)
    {
        if (ZinkPacing* pacing = zinkPacing())
            pacing->frameStart();
        // The main loop runs one frame ahead of the draw thread and wakes when it is let go, which is not quite in step
        // with the display: a frame measured near the paced interval was shown for exactly that long, so the world moves
        // by that much and not by the wobble of the measurement
        const uint64_t interval = sPacedInterval.load(std::memory_order_relaxed);
        if (interval == 0)
            return measured;
        const double paced = interval / 1.0e9;
        return measured > paced * 0.7 && measured < paced * 1.3 ? paced : measured;
    }

    void swapBuffers(osg::GraphicsContext& gc)
    {
        // OSG reads GL_TIMESTAMP right after this swap (State::frameCompleted) for its GPU statistics only, and Zink
        // answers it by waiting for the GPU to finish the frame: the CPU and GPU then take turns every frame
        if (usesZink() && gc.getState() != nullptr)
            gc.getState()->setTimestampBits(0);

        // one window, swapped on the draw thread
        static Pacing sPacing;
        static FrameTimes sFrameTimes;
        sFrameTimes.record();
        if (sPacing.swap(gc))
            return;
        // Zink: the frame is copied to the window by the graphics window's own swap
        if (usesZink())
        {
            const osg::GraphicsContext::Traits* traits = gc.getTraits();
            if (traits != nullptr
                && frameGenSwap(gc, traits->width, traits->height, true, [&](int64_t) { gc.swapBuffersImplementation(); }))
                return;
        }
        else
        {
            // plain swaps (Swappy off): while the phone is hot (thermal.cpp) every other vertical sync at most, then the
            // swap interval it had
            static int sHeldFrom = -1;
            const bool hot = thermalPressure() >= 2;
            if (hot && sHeldFrom < 0)
            {
                sHeldFrom = SDL_GL_GetSwapInterval();
                SDL_GL_SetSwapInterval(2);
                Log(Debug::Info) << "Frame pacing: every other vertical sync at most while the phone is hot";
            }
            else if (!hot && sHeldFrom >= 0)
            {
                SDL_GL_SetSwapInterval(sHeldFrom);
                sHeldFrom = -1;
                Log(Debug::Info) << "Frame pacing: the phone has cooled";
            }
        }
        gc.swapBuffersImplementation();
    }
}

namespace Alpha3
{
    void paceZinkPost()
    {
        if (ZinkPacing* pacing = zinkPacing())
            pacing->beforePost();
    }
}

// The engine's time step (apps/openmw/engine.cpp): C linkage, so the engine declares it without the library's headers
extern "C" double alpha3PacedFrameSeconds(double measured)
{
    return Alpha3::pacedFrameSeconds(measured);
}
