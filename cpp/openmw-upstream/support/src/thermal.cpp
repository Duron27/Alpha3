// [General] thermal mode: how close the phone is to throttling, for the parts that can do their work more slowly while it
// heats up. Throttled, Android clamps every core and the GPU at once, so everything is slower than if a little had been
// given up before. full (the default) never backs off; auto follows Android's forecast (AThermal_getThermalHeadroom,
// Android 11+: 1 is where it starts to clamp).
//
// Warm (forecast 0.85 or more): background texture conversion on half its threads, Zink one display period more per
// frame. Hot (0.95 or more): conversion on one thread, frames held to two display periods at least (Swappy, or the swap
// interval of plain swaps), occlusion culling without its helper threads. Each level is left once the forecast is 0.1
// below where it starts.
#include "support.hpp"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cctype>
#include <cmath>
#include <cstdint>
#include <limits>
#include <mutex>
#include <string>

#ifdef __ANDROID__
#include <dlfcn.h>
#endif

#include <components/debug/debuglog.hpp>

namespace Alpha3
{
    namespace
    {
        // Looked up at run time, since the app runs on older Android too; NaN where it is not known
        float headroomForecast(int seconds)
        {
#ifdef __ANDROID__
            static void* manager = nullptr;
            static float (*get)(void*, int) = nullptr;
            static std::once_flag once;
            std::call_once(once, [] {
                void* lib = dlopen("libandroid.so", RTLD_NOW);
                auto acquire = lib ? reinterpret_cast<void* (*)()>(dlsym(lib, "AThermal_acquireManager")) : nullptr;
                get = lib ? reinterpret_cast<float (*)(void*, int)>(dlsym(lib, "AThermal_getThermalHeadroom")) : nullptr;
                manager = acquire && get ? acquire() : nullptr;
            });
            if (manager)
                return get(manager, seconds);
#endif
            return std::numeric_limits<float>::quiet_NaN();
        }

        class Thermal
        {
        public:
            int pressure()
            {
                if (!mAuto)
                    return 0;
                // asked more often than every second or so, Android answers NaN; whoever asks first after two seconds checks
                const int64_t now = std::chrono::duration_cast<std::chrono::nanoseconds>(
                    std::chrono::steady_clock::now().time_since_epoch())
                                        .count();
                int64_t next = mNextCheck.load(std::memory_order_relaxed);
                if (now >= next && mNextCheck.compare_exchange_strong(next, now + 2000000000))
                    check();
                return mPressure.load(std::memory_order_relaxed);
            }

        private:
            void check()
            {
                const float headroom = headroomForecast(10);
                if (std::isnan(headroom))
                    return;
                const int level = mPressure.load(std::memory_order_relaxed);
                const int up = headroom >= 0.95f ? 2 : headroom >= 0.85f ? 1 : 0;
                const int keep = headroom >= 0.85f ? 2 : headroom >= 0.75f ? 1 : 0;
                const int wanted = std::max(up, std::min(level, keep));
                if (wanted == level)
                    return;
                mPressure.store(wanted, std::memory_order_relaxed);
                static const char* const names[] = { "cool", "warm", "hot" };
                Log(Debug::Info) << "Thermal: " << names[wanted] << " (headroom forecast " << headroom << ")"
                                 << (wanted > level ? ", backing off" : "");
            }

            const bool mAuto = [] {
                std::string mode = setting("General", "thermal mode", "full");
                for (char& c : mode)
                    c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
                Log(Debug::Info) << "Thermal mode: " << (mode == "auto" ? "auto" : "full");
                return mode == "auto";
            }();
            std::atomic<int64_t> mNextCheck{ 0 };
            std::atomic<int> mPressure{ 0 };
        };
    }

    int thermalPressure()
    {
        static Thermal thermal;
        return thermal.pressure();
    }

    unsigned thermalThreads(unsigned threads)
    {
        switch (thermalPressure())
        {
            case 0:
                return threads;
            case 1:
                return std::max(1u, threads / 2);
            default:
                return 1;
        }
    }
}
