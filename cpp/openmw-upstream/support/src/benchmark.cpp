// The benchmark (the launcher's in-game gear menu, "Benchmark", through benchmarkRequest()): with the view held still, measures the frame as it is, then with
// one part of it taken away at a time, and writes all of it to one file (Alpha3/benchmarks/ on shared storage), to be
// set next to another build's or phone's with tools/benchcompare.py. What a part costs is the baseline less the frame
// without it: a build that got slower shows which part got dearer.
//
// Each phase settles for a moment (the culling and the caches catch up) and is then measured: the frame interval (as
// the swap paces it), OSG's stats of every frame (the engine's own timings, each camera's cull, draw and GPU time,
// what it drew), and once at its end the phone's temperatures, clocks and how busy the CPU and GPU were. The file also
// has the build, the phone, the GPU's driver, where the camera stood and looked, and every setting (with the F2
// menu's), so two runs that differ in anything but the code show it.
#include "support.hpp"

#include <alpha3/upstream.hpp>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <ctime>
#include <filesystem>
#include <fstream>
#include <map>
#include <mutex>
#include <optional>
#include <sstream>
#include <string>
#include <vector>

#ifdef __ANDROID__
#include <sys/system_properties.h>
#endif
#include <unistd.h>

#include <SDL_events.h>

#include <MyGUI_Gui.h>
#include <MyGUI_RenderManager.h>
#include <MyGUI_TextBox.h>

#include <osg/Camera>
#include <osg/GLExtensions>
#include <osg/Stats>
#include <osgShadow/ShadowedScene>
#include <osgUtil/CullVisitor>
#include <osgViewer/View>
#include <osgViewer/ViewerBase>

#include <components/debug/debuglog.hpp>
#include <components/sceneutil/mwshadowtechnique.hpp>
#include <components/settings/settings.hpp>
#include <components/settings/shadermanager.hpp>

#include "buildid.hpp"

#ifdef __ANDROID__
#include "vulkan/device.hpp"
#endif

namespace Alpha3
{
    namespace
    {
        using Clock = std::chrono::steady_clock;

        const std::string sSceneCamera = "SceneCam";

        constexpr float sWarmup = 5.f; // seconds before the first phase: the menu's closing, the game unpausing
        constexpr float sSettle = 2.5f;
        constexpr int sCountdown = 5; // seconds from the request to the start: to close the launcher's menu
        constexpr float sMeasure = 5.f;
        constexpr unsigned sStatsDelay = 3; // frames: a frame's GPU time is known a few frames after it is drawn

        // OpenMW's visibility masks (apps/openmw/mwrender/vismask.hpp)
        constexpr unsigned sEffect = 1u << 1, sActor = 1u << 3, sSky = 1u << 5, sWater = 1u << 6,
                           sSimpleWater = 1u << 7, sTerrain = 1u << 8, sObject = 1u << 10, sStatic = 1u << 11,
                           sSun = 1u << 12, sWeatherParticles = 1u << 13, sScene = 1u << 14, sParticles = 1u << 16,
                           sGroundcover = 1u << 20;

        enum class Extra
        {
            None,
            Shadows,
            Grass,
            Occlusion,
            OcclusionTune, // the occlusion culling with another buffer width or triangle budget
        };

        struct Phase
        {
            const char* mName;
            const char* mWhat;
            unsigned mHide; // taken out of the scene camera's cull mask
            Extra mExtra; // or this turned off
            int mOcclusionWidth = 0, mOcclusionBudget = 0; // Extra::OcclusionTune's (0: as set)
        };

        constexpr Phase sPhases[] = {
            { "baseline", "everything as it is", 0, Extra::None },
            { "no shadows", "shadow maps not drawn", 0, Extra::Shadows },
            { "no terrain", "the ground, and the grass growing on it", sTerrain, Extra::None },
            { "no grass", "the experimental grass blades (and their critters)", 0, Extra::Grass },
            { "no groundcover", "OpenMW's groundcover", sGroundcover, Extra::None },
            { "no statics", "buildings, rocks, trees (paged statics)", sStatic, Extra::None },
            { "no objects", "movable objects: containers, doors, items", sObject, Extra::None },
            { "no actors", "NPCs and creatures", sActor, Extra::None },
            { "no water", "water, its reflections and refraction", sWater | sSimpleWater, Extra::None },
            { "no sky", "sky, clouds, sun, moons", sSky | sSun, Extra::None },
            { "no effects", "particles, spell effects, rain and snow", sEffect | sParticles | sWeatherParticles,
                Extra::None },
            { "no occlusion culling", "the CPU occlusion culling (more is drawn)", 0, Extra::Occlusion },
            // The occlusion culling's own settings: the baseline has them as set ([Camera] occlusion buffer width, 256 by
            // default; occluder triangle budget); these try around them: a finer and a coarser buffer, smaller budgets
            { "occlusion 512 px", "occlusion buffer 512 pixels wide", 0, Extra::OcclusionTune, 512, 0 },
            { "occlusion 128 px", "occlusion buffer 128 pixels wide", 0, Extra::OcclusionTune, 128, 0 },
            { "occlusion 256 px 20k", "256 pixels, 20000 occluder triangles a frame", 0, Extra::OcclusionTune, 256,
                20000 },
            { "occlusion 256 px 10k", "256 pixels, 10000 occluder triangles a frame", 0, Extra::OcclusionTune, 256,
                10000 },
            { "no scene", "nothing 3D: what is left is post-processing, the GUI and the swap", sScene, Extra::None },
            { "baseline again", "everything as it is, to see the phone heat up over the run", 0, Extra::None },
        };
        constexpr int sPhaseCount = static_cast<int>(std::size(sPhases));

        // ---- The fly-through (OPENMW_BENCHMARK_FLY, set by the launcher's Benchmarks button, which also starts the
        // game with the benchmark's own settings): the player is held at a route's start until the world around it has
        // loaded, then moved along it at a fixed speed with collision off, and the frame is measured in tenths of the
        // route. The speed is the same on every build and phone, so a slower frame shows as fewer frames, not as a
        // different route.
        struct FlyRoute
        {
            const char* mName;
            float mFrom[3], mTo[3];
            float mPitch, mYaw; // degrees, as the console's GetAngle X and Z
            float mSeconds;
        };
        constexpr FlyRoute sRoutes[] = {
            // Vivec from the north, about 6200 units up, south over the cantons and the water (31,900 units)
            { "vivec", { 33055.8f, -70769.0f, 6185.4f }, { 32815.6f, -102628.4f, 6310.2f }, -0.22f, 179.94f, 90.f },
        };
        constexpr float sFlyHold = 15.f; // s at the start, not measured: the cells round it load, the programs build
        constexpr int sFlySegments = 10;
        constexpr Phase sFlyPhases[sFlySegments] = {
            { "0-10%", "the route's first tenth", 0, Extra::None },
            { "10-20%", "the route's second tenth", 0, Extra::None },
            { "20-30%", "the route's third tenth", 0, Extra::None },
            { "30-40%", "the route's fourth tenth", 0, Extra::None },
            { "40-50%", "the route's fifth tenth", 0, Extra::None },
            { "50-60%", "the route's sixth tenth", 0, Extra::None },
            { "60-70%", "the route's seventh tenth", 0, Extra::None },
            { "70-80%", "the route's eighth tenth", 0, Extra::None },
            { "80-90%", "the route's ninth tenth", 0, Extra::None },
            { "90-100%", "the route's last tenth", 0, Extra::None },
        };

        std::string trimmed(std::string s)
        {
            while (!s.empty() && (s.back() == '\n' || s.back() == '\r' || s.back() == ' ' || s.back() == '\0'))
                s.pop_back();
            return s;
        }

        std::string readFile(const std::string& path)
        {
            std::ifstream in(path);
            if (!in)
                return {};
            std::stringstream s;
            s << in.rdbuf();
            return trimmed(s.str());
        }

        double readNumber(const std::string& path)
        {
            const std::string text = readFile(path);
            if (text.empty())
                return std::nan("");
            char* end = nullptr;
            const double v = std::strtod(text.c_str(), &end);
            return end == text.c_str() ? std::nan("") : v;
        }

        std::string property(const char* name)
        {
#ifdef __ANDROID__
            char value[PROP_VALUE_MAX] = {};
            __system_property_get(name, value);
            return value;
#else
            (void)name;
            return {};
#endif
        }

        // ---- JSON, written by hand
        std::string jsonString(std::string_view s)
        {
            std::string out = "\"";
            for (char c : s)
            {
                switch (c)
                {
                    case '"':
                        out += "\\\"";
                        break;
                    case '\\':
                        out += "\\\\";
                        break;
                    case '\n':
                        out += "\\n";
                        break;
                    case '\r':
                        out += "\\r";
                        break;
                    case '\t':
                        out += "\\t";
                        break;
                    default:
                        if (static_cast<unsigned char>(c) < 0x20)
                        {
                            char buf[8];
                            std::snprintf(buf, sizeof buf, "\\u%04x", c);
                            out += buf;
                        }
                        else
                            out += c;
                }
            }
            return out + "\"";
        }

        std::string number(double v)
        {
            if (!std::isfinite(v))
                return "null";
            char buf[32];
            std::snprintf(buf, sizeof buf, "%.6g", v);
            return buf;
        }

        // ---- the phone: temperatures, clocks, how busy
        struct CpuTimes
        {
            unsigned long long mBusy = 0, mTotal = 0;
            double mProcess = 0; // this process's CPU seconds
        };

        CpuTimes cpuTimes()
        {
            CpuTimes t;
            std::ifstream stat("/proc/stat");
            std::string cpu;
            unsigned long long v[8] = {};
            if (stat >> cpu >> v[0] >> v[1] >> v[2] >> v[3] >> v[4] >> v[5] >> v[6] >> v[7])
            {
                for (unsigned long long x : v)
                    t.mTotal += x;
                t.mBusy = t.mTotal - v[3] - v[4]; // less idle and iowait
            }
            std::ifstream self("/proc/self/stat");
            std::string line;
            std::getline(self, line);
            const auto close = line.rfind(')');
            if (close != std::string::npos)
            {
                std::istringstream rest(line.substr(close + 2));
                std::string field;
                unsigned long long utime = 0, stime = 0;
                for (int i = 3; i <= 15 && rest >> field; ++i)
                {
                    if (i == 14)
                        utime = std::strtoull(field.c_str(), nullptr, 10);
                    if (i == 15)
                        stime = std::strtoull(field.c_str(), nullptr, 10);
                }
                t.mProcess = static_cast<double>(utime + stime) / static_cast<double>(sysconf(_SC_CLK_TCK));
            }
            return t;
        }

        struct Machine
        {
            std::map<std::string, double> mValues;
            std::vector<double> mCpuMhz;
        };

        Machine machine()
        {
            Machine m;
            // the hottest zone of each kind (the zones' names vary by phone; not every one can be read by an app)
            double cpu = std::nan(""), gpu = std::nan(""), skin = std::nan(""), any = std::nan("");
            for (int i = 0; i < 160; ++i)
            {
                const std::string zone = "/sys/class/thermal/thermal_zone" + std::to_string(i) + "/";
                std::string type = readFile(zone + "type");
                if (type.empty())
                {
                    if (i > 8 && !std::filesystem::exists(zone))
                        break;
                    continue;
                }
                double t = readNumber(zone + "temp");
                if (!std::isfinite(t) || t <= -40000 || t == 0)
                    continue;
                t = t > 1000 ? t / 1000.0 : t;
                for (char& c : type)
                    c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
                auto hottest = [&](double& slot) { slot = std::isnan(slot) ? t : std::max(slot, t); };
                hottest(any);
                if (type.find("gpu") != std::string::npos || type.find("g3d") != std::string::npos)
                    hottest(gpu);
                else if (type.find("cpu") != std::string::npos || type.find("big") != std::string::npos
                    || type.find("little") != std::string::npos || type.find("mid") != std::string::npos)
                    hottest(cpu);
                else if (type.find("skin") != std::string::npos || type.find("ap_therm") != std::string::npos)
                    hottest(skin);
            }
            m.mValues["temp cpu c"] = cpu;
            m.mValues["temp gpu c"] = gpu;
            m.mValues["temp skin c"] = skin;
            m.mValues["temp hottest c"] = any;
            const double battery = readNumber("/sys/class/power_supply/battery/temp");
            m.mValues["temp battery c"] = std::isfinite(battery) ? battery / 10.0 : battery;

            for (int i = 0; i < 16; ++i)
            {
                const double khz
                    = readNumber("/sys/devices/system/cpu/cpu" + std::to_string(i) + "/cpufreq/scaling_cur_freq");
                if (!std::isfinite(khz))
                {
                    if (!std::filesystem::exists("/sys/devices/system/cpu/cpu" + std::to_string(i)))
                        break;
                    m.mCpuMhz.push_back(khz);
                    continue;
                }
                m.mCpuMhz.push_back(khz / 1000.0);
            }

            // the GPU: Adreno (kgsl), Exynos/Mali (Samsung's /sys/kernel/gpu), generic devfreq
            double gpuMhz = readNumber("/sys/class/kgsl/kgsl-3d0/devfreq/cur_freq");
            if (std::isfinite(gpuMhz))
                gpuMhz /= 1e6;
            if (!std::isfinite(gpuMhz))
                gpuMhz = readNumber("/sys/kernel/gpu/gpu_clock");
            if (!std::isfinite(gpuMhz))
            {
                gpuMhz = readNumber("/sys/class/misc/mali0/device/cur_freq");
                if (std::isfinite(gpuMhz) && gpuMhz > 1e5)
                    gpuMhz /= 1e6;
            }
            m.mValues["gpu mhz"] = gpuMhz;
            double busy = readNumber("/sys/class/kgsl/kgsl-3d0/gpu_busy_percentage");
            if (!std::isfinite(busy))
                busy = readNumber("/sys/kernel/gpu/gpu_busy");
            if (!std::isfinite(busy))
                busy = readNumber("/sys/class/misc/mali0/device/utilization");
            m.mValues["gpu busy %"] = busy;
            return m;
        }

        // the shadows' switch (it has no getter): read through a pointer to the member, which a subclass may take
        struct ShadowPeek : SceneUtil::MWShadowTechnique
        {
            static bool on(const SceneUtil::MWShadowTechnique& t) { return t.*(&ShadowPeek::_enableShadows); }
        };

        SceneUtil::MWShadowTechnique* findShadows(osg::Node& node, int depth)
        {
            if (auto* scene = dynamic_cast<osgShadow::ShadowedScene*>(&node))
                return dynamic_cast<SceneUtil::MWShadowTechnique*>(scene->getShadowTechnique());
            osg::Group* group = node.asGroup();
            if (group == nullptr || depth > 4)
                return nullptr;
            for (unsigned i = 0; i < group->getNumChildren(); ++i)
                if (auto* found = findShadows(*group->getChild(i), depth + 1))
                    return found;
            return nullptr;
        }

        struct Sums
        {
            std::map<std::string, std::pair<double, unsigned>> mValues;

            // a frame's stats, read through report(), which holds the stats' lock (the draw thread may still be adding
            // the frame's GPU time)
            void add(const osg::Stats& stats, unsigned frame)
            {
                std::ostringstream text;
                stats.report(text, frame);
                std::istringstream lines(text.str());
                std::string line;
                std::getline(lines, line); // "Stats <name> FrameNumber <n>"
                while (std::getline(lines, line))
                {
                    const auto tab = line.rfind('\t');
                    if (tab == std::string::npos)
                        continue;
                    const auto from = line.find_first_not_of(' ');
                    const std::string key = line.substr(from, tab - from);
                    const double value = std::strtod(line.c_str() + tab + 1, nullptr);
                    // when a traversal began and ended (times since the start) say nothing averaged
                    if (key.find("begin") != std::string::npos || key.find("end time") != std::string::npos
                        || key.find("Reference") != std::string::npos || key == "FrameNumber")
                        continue;
                    // a timer query across a disjoint event (Adreno's GLES: a clock change) reads back garbage, such
                    // as 1.6e11 s; one such frame would make the whole part's average meaningless
                    if (key.find("GPU") != std::string::npos && !(value >= 0 && value < 10.0))
                        continue;
                    auto& [sum, count] = mValues[key];
                    sum += value;
                    ++count;
                }
            }

            std::string json() const
            {
                std::string out = "{";
                bool first = true;
                for (const auto& [key, v] : mValues)
                {
                    out += (first ? "" : ", ") + jsonString(key) + ": " + number(v.first / std::max(1u, v.second));
                    first = false;
                }
                return out + "}";
            }
        };

        struct PhaseResult
        {
            const Phase* mPhase = nullptr;
            bool mSkipped = false;
            std::string mSkipReason;
            std::vector<double> mIntervals; // ms
            Sums mViewer;
            std::map<std::string, Sums> mCameras;
            Machine mMachine;
            double mCpuBusy = std::nan(""), mProcessCores = std::nan("");
            int mThermalPressure = 0;
            unsigned mDrawnFrames = 0;
        };

        class Benchmark
        {
        public:
            static Benchmark& get()
            {
                static Benchmark b;
                return b;
            }

            void glInfo(const osg::GLExtensions& extensions, std::string vendor, std::string renderer, std::string version)
            {
                std::lock_guard lock(mGlMutex);
                mGlVersion = version;
                mGl = "{\"vendor\": " + jsonString(vendor) + ", \"renderer\": " + jsonString(renderer)
                    + ", \"version\": " + jsonString(version) + ", \"gles\": " + (extensions.isOpenGLES ? "true" : "false")
                    + ", \"timer queries\": "
                    + (extensions.isTimerQuerySupported || extensions.isARBTimerQuerySupported ? "true" : "false")
                    + "}";
            }

            void camera(osg::Camera* camera)
            {
                if (camera->getName() != sSceneCamera)
                    return;
                std::lock_guard lock(mCameraMutex);
                mScene = camera;
                mCameraSeen.store(true, std::memory_order_release);
            }

            bool cameraSeen() const { return mCameraSeen.load(std::memory_order_acquire); }

            // any thread (the launcher's UI thread, through JNI): taken up by the next frame
            void request() { mAsk.store(true, std::memory_order_relaxed); }

            // OpenMW pans the camera round the player after 30 s without input (vanity mode, the camera script's
            // idle timer): a mouse move of nothing, now and then, keeps the view where it is
            void holdView(Clock::time_point now)
            {
                if (now - mLastNudge < std::chrono::milliseconds(500))
                    return;
                mLastNudge = now;
                SDL_Event event{};
                event.type = SDL_MOUSEMOTION;
                event.motion.which = 0;
                event.motion.xrel = 0;
                event.motion.yrel = 0;
                SDL_PushEvent(&event);
            }

            // the main thread, each frame before it is drawn
            void frame()
            {
                const Clock::time_point now = Clock::now();
                if (flyFrame(now))
                    return;
                if (mResultsShown
                    && (now > mResultsUntil || viewMoved()
                        || Settings::ShaderManager::get().getMode() == Settings::ShaderManager::Mode::Debug))
                    hideResults();
                // asked for: a few seconds first, to close the launcher's menu and let go of the screen
                if (mAsk.exchange(false, std::memory_order_relaxed) && !mRunning && !mRequested)
                {
                    hideResults();
                    mRequested = true;
                    mStartAt = now + std::chrono::seconds(sCountdown);
                }
                if (!mRunning && !mRequested && !mStatusUntil)
                    return;
                if (mStatusUntil && !mRunning && now > *mStatusUntil)
                {
                    hideStatus();
                    mStatusUntil.reset();
                }
                if (!mRunning)
                {
                    if (!mRequested || !cameraSeen())
                        return;
                    if (Settings::ShaderManager::get().getMode() == Settings::ShaderManager::Mode::Debug)
                        return; // the F2 window is open
                    if (now < mStartAt)
                    {
                        const auto left = std::chrono::ceil<std::chrono::seconds>(mStartAt - now).count();
                        status("Benchmark in " + std::to_string(left)
                            + " s: close the menu, stand still, look at the spot, do not touch");
                        return;
                    }
                    hideResults();
                    start(now);
                    return;
                }
                holdView(now);
                osg::ref_ptr<osg::Camera> scene;
                {
                    std::lock_guard lock(mCameraMutex);
                    mScene.lock(scene);
                }
                osgViewer::ViewerBase* viewer = scene ? viewerOf(*scene) : nullptr;
                if (viewer == nullptr)
                {
                    mCameraSeen.store(false, std::memory_order_release);
                    finish("the scene camera went away");
                    return;
                }
                // OpenMW's F3 profiler sets "engine" back to whether its overlay is shown on every event traversal,
                // which comes before this hook: turned on again each frame, the engine's own timings (Lua,
                // mechanics, physics, mwscript) are recorded for the frames measured
                viewer->getViewerStats()->collectStats("engine", true);
                const float t = std::chrono::duration<float>(now - mPhaseStart).count();
                if (mPhase < 0)
                {
                    if (t >= sWarmup)
                        beginPhase(0, *scene, now);
                    mLast = now;
                    return;
                }
                PhaseResult& r = mResults[mPhase];
                if (!r.mSkipped && t >= sSettle)
                {
                    if (!mMeasuring)
                    {
                        mMeasuring = true;
                        mMeasureFrame = frameNumber(*viewer);
                        mCpuStart = cpuTimes();
                        mMeasureStart = now;
                    }
                    else
                        r.mIntervals.push_back(std::chrono::duration<double, std::milli>(now - mLast).count());
                    sample(*viewer, r);
                }
                mLast = now;
                if ((r.mSkipped && t >= 0.f) || t >= sSettle + sMeasure)
                {
                    if (!r.mSkipped)
                        endMeasure(r, now);
                    if (mPhase + 1 < sPhaseCount)
                        beginPhase(mPhase + 1, *scene, now);
                    else
                        finish({});
                }
            }

            // ---- the fly-through

            // the main thread, for androidmain.cpp: where the player is to be this frame
            bool flyPose(BenchmarkPose& pose, bool& done)
            {
                done = false;
                if (mFlyReleased)
                {
                    mFlyReleased = false;
                    done = true;
                    return false;
                }
                if (mRoute == nullptr || (mFly != Fly::Holding && mFly != Fly::Flying))
                    return false;
                const FlyRoute& r = *mRoute;
                const float t = mFly == Fly::Holding
                    ? 0.f
                    : std::clamp(std::chrono::duration<float>(Clock::now() - mFlyStart).count() / r.mSeconds, 0.f, 1.f);
                pose.position = osg::Vec3f(r.mFrom[0], r.mFrom[1], r.mFrom[2]) * (1.f - t)
                    + osg::Vec3f(r.mTo[0], r.mTo[1], r.mTo[2]) * t;
                pose.pitch = osg::DegreesToRadians(r.mPitch);
                pose.yaw = osg::DegreesToRadians(r.mYaw);
                return true;
            }

        private:
            enum class Fly
            {
                Off,
                Waiting, // for the game: the scene camera drawing the world
                Holding, // at the start, not measured
                Flying,
            };

            // true while the fly-through has the frame (the still benchmark is not run then)
            bool flyFrame(Clock::time_point now)
            {
                if (!mFlyChecked)
                {
                    mFlyChecked = true;
                    if (const char* name = std::getenv("OPENMW_BENCHMARK_FLY"); name != nullptr && *name != '\0')
                    {
                        for (const FlyRoute& r : sRoutes)
                            if (std::string_view(r.mName) == name)
                                mRoute = &r;
                        if (mRoute == nullptr)
                            mRoute = &sRoutes[0];
                        mFly = Fly::Waiting;
                        Log(Debug::Info) << "Fly-through: route " << mRoute->mName << ", waiting for the game";
                    }
                }
                if (mFly == Fly::Off)
                    return false;
                if (!cameraSeen())
                    return true;
                osg::ref_ptr<osg::Camera> scene;
                {
                    std::lock_guard lock(mCameraMutex);
                    mScene.lock(scene);
                }
                osgViewer::ViewerBase* viewer = scene ? viewerOf(*scene) : nullptr;
                if (viewer == nullptr)
                {
                    mCameraSeen.store(false, std::memory_order_release);
                    return true;
                }
                holdView(now);
                const FlyRoute& route = *mRoute;
                if (mFly == Fly::Waiting)
                {
                    hideResults();
                    mFly = Fly::Holding;
                    mFlyStart = now;
                    Log(Debug::Info) << "Fly-through: holding at the start";
                }
                if (mFly == Fly::Holding)
                {
                    const float t = std::chrono::duration<float>(now - mFlyStart).count();
                    const int left = static_cast<int>(std::ceil(sFlyHold - t));
                    if (left != mFlyShown)
                    {
                        mFlyShown = left;
                        status("Fly-through benchmark (" + std::string(route.mName) + ") in " + std::to_string(left)
                            + " s: do not touch the screen");
                    }
                    if (t < sFlyHold)
                        return true;
                    // what the header says about the view, as the still benchmark has it
                    mCullMask = scene->getCullMask();
                    mShadows = findShadows(*scene, 0);
                    mShadowsOn = mShadows && ShadowPeek::on(*mShadows);
                    mGrassOn = grassOn();
                    mOcclusionOn = occlusionOn();
                    mHeader = header(*scene, *viewer);
                    collectStats(*viewer);
                    mResults.assign(sFlySegments, {});
                    for (int i = 0; i < sFlySegments; ++i)
                        mResults[i].mPhase = &sFlyPhases[i];
                    mFly = Fly::Flying;
                    mFlyStart = mLast = now;
                    beginSegment(0, *viewer, now);
                    Log(Debug::Info) << "Fly-through: flying (" << route.mSeconds << " s)";
                    return true;
                }
                const float t = std::chrono::duration<float>(now - mFlyStart).count() / route.mSeconds;
                const int segment = std::min(sFlySegments - 1, static_cast<int>(t * sFlySegments));
                if (segment != mPhase)
                {
                    endMeasure(mResults[mPhase], now);
                    beginSegment(segment, *viewer, now);
                }
                PhaseResult& r = mResults[mPhase];
                r.mIntervals.push_back(std::chrono::duration<double, std::milli>(now - mLast).count());
                sample(*viewer, r);
                mLast = now;
                const int percent = std::min(100, static_cast<int>(t * 100.f));
                if (percent / 5 != mFlyShown)
                {
                    mFlyShown = percent / 5;
                    status("Fly-through " + std::to_string(percent) + "%");
                }
                if (t < 1.f)
                    return true;
                endMeasure(r, now);
                uncollectStats();
                mFly = Fly::Off;
                mFlyReleased = true;
                finishFly();
                return true;
            }

            void beginSegment(int index, osgViewer::ViewerBase& viewer, Clock::time_point now)
            {
                mPhase = index;
                mMeasureFrame = frameNumber(viewer);
                mCpuStart = cpuTimes();
                mMeasureStart = now;
            }

            static double overall(const std::vector<PhaseResult>& results, double p)
            {
                std::vector<double> v;
                for (const PhaseResult& r : results)
                    v.insert(v.end(), r.mIntervals.begin(), r.mIntervals.end());
                if (v.empty())
                    return std::nan("");
                std::sort(v.begin(), v.end());
                if (p < 0)
                {
                    double sum = 0;
                    for (double x : v)
                        sum += x;
                    return sum / v.size();
                }
                return v[std::min(v.size() - 1, static_cast<std::size_t>(p * v.size()))];
            }

            void finishFly()
            {
                const FlyRoute& route = *mRoute;
                const std::filesystem::path dir = cacheDirectory().parent_path() / "benchmarks";
                std::error_code ec;
                std::filesystem::create_directories(dir, ec);
                const std::string prefix = std::string("flythrough-") + route.mName + "-";
                const std::filesystem::path path = dir / (prefix + mStamp + "-" + ALPHA3_BUILD_COMMIT + ".json");
                std::ofstream out(path);
                out << "{\n  \"alpha3 flythrough\": 1,\n  \"route\": {\"name\": " << jsonString(route.mName)
                    << ", \"from\": [" << number(route.mFrom[0]) << ", " << number(route.mFrom[1]) << ", "
                    << number(route.mFrom[2]) << "], \"to\": [" << number(route.mTo[0]) << ", "
                    << number(route.mTo[1]) << ", " << number(route.mTo[2]) << "], \"pitch\": " << number(route.mPitch)
                    << ", \"yaw\": " << number(route.mYaw) << ", \"seconds\": " << number(route.mSeconds)
                    << ", \"hold\": " << number(sFlyHold) << "},\n"
                    << mHeader << "  \"overall\": {\"fps\": " << number(1000.0 / overall(mResults, -1))
                    << ", \"ms\": {\"mean\": " << number(overall(mResults, -1)) << ", \"p50\": "
                    << number(overall(mResults, 0.5)) << ", \"p99\": " << number(overall(mResults, 0.99))
                    << ", \"max\": " << number(overall(mResults, 1.0)) << "}},\n  \"segments\": [";
                for (int i = 0; i < sFlySegments; ++i)
                    out << (i ? ",\n" : "\n") << phaseJson(mResults[i]);
                out << "\n  ]\n}\n";
                out.close();
                mStatusUntil = Clock::now() + std::chrono::seconds(12);
                if (!out)
                {
                    Log(Debug::Error) << "Fly-through: cannot write " << path.string();
                    status("Fly-through: could not write the file");
                    return;
                }
                Log(Debug::Info) << "Fly-through: written to " << path.string();

                // the newest earlier run of the same route, to set this one beside
                std::filesystem::path txt = path;
                txt.replace_extension(".txt");
                std::filesystem::path newest;
                for (const auto& entry : std::filesystem::directory_iterator(dir, ec))
                {
                    const std::filesystem::path& f = entry.path();
                    if (f.extension() == ".txt" && f.filename().string().rfind(prefix, 0) == 0 && f != txt
                        && (newest.empty() || f.filename() > newest.filename()))
                        newest = f;
                }
                std::map<std::string, double> then; // segment (or "overall") -> fps
                std::string thenBuild;
                if (!newest.empty())
                {
                    std::ifstream in(newest);
                    std::string line;
                    while (std::getline(in, line))
                    {
                        if (line.rfind("build ", 0) == 0 && thenBuild.empty())
                            thenBuild = line.substr(6, line.find(' ', 6) - 6);
                        if (line.rfind("data\t", 0) != 0)
                            continue;
                        std::istringstream fields(line.substr(5));
                        std::string name, fps;
                        std::getline(fields, name, '\t');
                        std::getline(fields, fps, '\t');
                        then[name] = fps == "null" ? std::nan("") : std::atof(fps.c_str());
                    }
                }
                const bool compare = !then.empty();
                const auto cell = [](double v, const char* format) {
                    char b[32];
                    if (std::isfinite(v))
                        std::snprintf(b, sizeof b, format, v);
                    else
                        std::snprintf(b, sizeof b, "%7s", "-");
                    return std::string(b);
                };
                std::string table;
                char line[256];
                std::snprintf(line, sizeof line, "fly-through %s  build %s  %s %s\n", route.mName, ALPHA3_BUILD_COMMIT,
                    property("ro.product.manufacturer").c_str(), property("ro.product.model").c_str());
                table += line;
                table += "renderer: " + rendererName() + "\n";
                table += compare ? "             fps   p50 ms  p99 ms  max ms  GPU ms |  then  change\n"
                                 : "             fps   p50 ms  p99 ms  max ms  GPU ms\n";
                const auto row = [&](const std::string& name, double mean, double p50, double p99, double max, double gpu) {
                    const double fps = 1000.0 / mean;
                    std::snprintf(line, sizeof line, "%-10s", name.c_str());
                    std::string text = line + cell(fps, "%6.1f") + cell(p50, "%8.1f") + cell(p99, "%8.1f")
                        + cell(max, "%8.1f") + cell(gpu, "%8.1f");
                    if (compare)
                    {
                        const auto it = then.find(name);
                        const double was = it == then.end() ? std::nan("") : it->second;
                        text += " |" + cell(was, "%6.1f") + cell(fps - was, "%+7.1f");
                    }
                    return text + "\n";
                };
                std::string data;
                for (const PhaseResult& r : mResults)
                {
                    std::vector<double> v = r.mIntervals;
                    std::sort(v.begin(), v.end());
                    const auto pct = [&](double p) {
                        return v.empty() ? std::nan("") : v[std::min(v.size() - 1, static_cast<std::size_t>(p * v.size()))];
                    };
                    table += row(r.mPhase->mName, meanMs(r), pct(0.5), pct(0.99), v.empty() ? std::nan("") : v.back(),
                        gpuMs(r));
                    data += std::string("data\t") + r.mPhase->mName + "\t" + number(1000.0 / meanMs(r)) + "\n";
                }
                const double mean = overall(mResults, -1);
                table += row("overall", mean, overall(mResults, 0.5), overall(mResults, 0.99), overall(mResults, 1.0),
                    std::nan(""));
                data += "data\toverall\t" + number(1000.0 / mean) + "\n";
                const auto get = [&](const char* key) {
                    const auto it = mResults.back().mMachine.mValues.find(key);
                    return it == mResults.back().mMachine.mValues.end() ? std::nan("") : it->second;
                };
                std::snprintf(line, sizeof line, "at the end: hottest %.0f C, GPU %.0f MHz %.0f%% busy, thermal %d\n",
                    get("temp hottest c"), get("gpu mhz"), get("gpu busy %"), mResults.back().mThermalPressure);
                table += line;
                if (compare)
                    table += "then: " + newest.filename().string() + " (build " + thenBuild + ")\n";
                table += "saved: Alpha3/benchmarks/" + path.filename().string() + " (and .txt)";
                std::ofstream text(txt);
                text << table << "\n" << data;
                hideStatus();
                mStatusUntil.reset();
                showResults(table);
            }
            static osgViewer::ViewerBase* viewerOf(osg::Camera& camera)
            {
                auto* view = dynamic_cast<osgViewer::View*>(camera.getView());
                return view ? view->getViewerBase() : nullptr;
            }

            static unsigned frameNumber(osgViewer::ViewerBase& viewer)
            {
                const osg::FrameStamp* stamp = viewer.getViewerFrameStamp();
                return stamp ? stamp->getFrameNumber() : 0;
            }

            void start(Clock::time_point now)
            {
                mRequested = false;
                osg::ref_ptr<osg::Camera> scene;
                {
                    std::lock_guard lock(mCameraMutex);
                    mScene.lock(scene);
                }
                osgViewer::ViewerBase* viewer = scene ? viewerOf(*scene) : nullptr;
                if (viewer == nullptr)
                {
                    Log(Debug::Warning) << "Benchmark: no scene camera";
                    mCameraSeen.store(false, std::memory_order_release);
                    return;
                }
                mRunning = true;
                mResults.assign(sPhaseCount, {});
                mPhase = -1;
                mPhaseStart = mLast = now;
                mMeasuring = false;
                mCullMask = scene->getCullMask();
                mShadows = findShadows(*scene, 0);
                mShadowsOn = mShadows && ShadowPeek::on(*mShadows);
                mGrassOn = grassOn();
                mOcclusionOn = occlusionOn();
                // measured as it is when it works, not resting or trying itself out in the middle of a phase
                occlusionForce(mOcclusionOn);
                mHeader = header(*scene, *viewer);
                collectStats(*viewer);
                Log(Debug::Info) << "Benchmark: started (" << sPhaseCount << " phases, about "
                                 << static_cast<int>(sWarmup + sPhaseCount * (sSettle + sMeasure)) << " s)";
                status("Benchmark starting: hold still, do not touch the screen");
            }

            // OSG's stats, as OpenMW's profiler turns them on (F3), kept as they were for after. OpenMW's own CPU
            // work is timed only while its category is collected: lua (the Lua worker's update, which overlaps the
            // draw), luasyncupdate (the part the frame waits for), mechanics, physics and mwscript.
            void collectStats(osgViewer::ViewerBase& viewer)
            {
                mCollect.clear();
                osg::Stats* stats = viewer.getViewerStats();
                for (const char* c : { "frame_rate", "event", "update", "engine", "resource", "scene", "lua",
                         "luasyncupdate", "mechanics", "physics", "physicsworker", "script" })
                {
                    mCollect.push_back({ stats, c, stats->collectStats(c) });
                    stats->collectStats(c, true);
                }
                osgViewer::ViewerBase::Cameras cameras;
                viewer.getCameras(cameras);
                for (osg::Camera* camera : cameras)
                    if (osg::Stats* s = camera->getStats())
                        for (const char* c : { "rendering", "gpu", "scene" })
                        {
                            mCollect.push_back({ s, c, s->collectStats(c) });
                            s->collectStats(c, true);
                        }
            }

            void uncollectStats()
            {
                for (const Collect& c : mCollect)
                    if (osg::ref_ptr<osg::Stats> s; c.mStats.lock(s))
                        s->collectStats(c.mName, c.mWas);
                mCollect.clear();
            }

            void restore(osg::Camera& scene)
            {
                scene.setCullMask(mCullMask);
                if (mShadows && mShadowsOn)
                    mShadows->enableShadows();
                setGrassOn(mGrassOn);
                occlusionPause(false);
                occlusionTune(0, 0);
            }

            void beginPhase(int index, osg::Camera& scene, Clock::time_point now)
            {
                restore(scene);
                mPhase = index;
                mPhaseStart = now;
                mMeasuring = false;
                const Phase& p = sPhases[index];
                PhaseResult& r = mResults[index];
                r.mPhase = &p;
                const auto skip = [&](const char* why) {
                    r.mSkipped = true;
                    r.mSkipReason = why;
                };
                switch (p.mExtra)
                {
                    case Extra::Shadows:
                        if (!mShadowsOn)
                            skip("shadows are off here");
                        else
                            mShadows->disableShadows(true);
                        break;
                    case Extra::Grass:
                        if (!mGrassOn)
                            skip("grass is off");
                        else
                            setGrassOn(false);
                        break;
                    case Extra::Occlusion:
                        if (!mOcclusionOn)
                            skip("occlusion culling is off");
                        else
                            occlusionPause(true);
                        break;
                    case Extra::OcclusionTune:
                        if (!mOcclusionOn)
                            skip("occlusion culling is off");
                        else
                            occlusionTune(p.mOcclusionWidth, p.mOcclusionBudget);
                        break;
                    case Extra::None:
                        break;
                }
                if (p.mHide != 0 && !r.mSkipped)
                {
                    if ((mCullMask & p.mHide) == 0)
                        skip("not drawn anyway");
                    else
                        scene.setCullMask(mCullMask & ~p.mHide);
                }
                status("Benchmark " + std::to_string(index + 1) + "/" + std::to_string(sPhaseCount) + ": " + p.mName
                    + "  (do not touch)");
            }

            void sample(osgViewer::ViewerBase& viewer, PhaseResult& r)
            {
                const unsigned now = frameNumber(viewer);
                if (now < sStatsDelay)
                    return;
                const unsigned f = now - sStatsDelay;
                if (f < mMeasureFrame)
                    return; // drawn before the phase settled
                ++r.mDrawnFrames;
                const auto take = [&](osg::Stats& stats, Sums& sums) {
                    if (f < stats.getEarliestFrameNumber() || f > stats.getLatestFrameNumber())
                        return;
                    sums.add(stats, f);
                };
                take(*viewer.getViewerStats(), r.mViewer);
                osgViewer::ViewerBase::Cameras cameras;
                viewer.getCameras(cameras);
                for (std::size_t i = 0; i < cameras.size(); ++i)
                    if (osg::Stats* s = cameras[i]->getStats())
                    {
                        const std::string& name = cameras[i]->getName();
                        take(*s, r.mCameras[name.empty() ? "camera " + std::to_string(i) : name]);
                    }
            }

            void endMeasure(PhaseResult& r, Clock::time_point now)
            {
                const CpuTimes end = cpuTimes();
                const double wall = std::chrono::duration<double>(now - mMeasureStart).count();
                if (end.mTotal > mCpuStart.mTotal)
                    r.mCpuBusy = 100.0 * static_cast<double>(end.mBusy - mCpuStart.mBusy)
                        / static_cast<double>(end.mTotal - mCpuStart.mTotal);
                if (wall > 0)
                    r.mProcessCores = (end.mProcess - mCpuStart.mProcess) / wall;
                r.mMachine = machine();
                r.mThermalPressure = thermalPressure();
                if (!r.mIntervals.empty())
                {
                    double sum = 0;
                    for (double v : r.mIntervals)
                        sum += v;
                    Log(Debug::Info) << "Benchmark: " << r.mPhase->mName << " " << sum / r.mIntervals.size()
                                     << " ms a frame";
                }
            }

            std::string header(osg::Camera& scene, osgViewer::ViewerBase& viewer)
            {
                std::ostringstream h;
                const auto now = std::chrono::system_clock::to_time_t(std::chrono::system_clock::now());
                char date[32];
                std::strftime(date, sizeof date, "%Y-%m-%d %H:%M:%S", std::localtime(&now));
                mStamp.resize(32);
                mStamp.resize(std::strftime(mStamp.data(), mStamp.size(), "%Y%m%d-%H%M%S", std::localtime(&now)));
                h << "  \"date\": " << jsonString(date) << ",\n";
                h << "  \"build\": {\"commit\": " << jsonString(ALPHA3_BUILD_COMMIT) << ", \"built\": "
                  << jsonString(ALPHA3_BUILD_TIME) << "},\n";
                h << "  \"device\": {\"manufacturer\": " << jsonString(property("ro.product.manufacturer"))
                  << ", \"model\": " << jsonString(property("ro.product.model")) << ", \"soc\": "
                  << jsonString(property("ro.soc.model")) << ", \"hardware\": " << jsonString(property("ro.hardware"))
                  << ", \"android\": " << jsonString(property("ro.build.version.release")) << ", \"sdk\": "
                  << jsonString(property("ro.build.version.sdk")) << ", \"cores\": " << sysconf(_SC_NPROCESSORS_CONF)
                  << "},\n";
                {
                    std::lock_guard lock(mGlMutex);
                    h << "  \"gl\": " << (mGl.empty() ? "{}" : mGl) << ",\n";
                }
                const char* envs[] = { "OSG_USE_ZINK", "OPENMW_FRAMEGEN", "OPENMW_GLES_VERSION", "OPENMW_DISABLE_SPIRV",
                    "OSG_USE_ANGLE", "OSG_USE_VULKAN" };
                h << "  \"renderer\": " << jsonString(rendererName()) << ",\n";
                h << "  \"environment\": {";
                bool first = true;
                for (const char* e : envs)
                    if (const char* v = std::getenv(e))
                    {
                        h << (first ? "" : ", ") << jsonString(e) << ": " << jsonString(v);
                        first = false;
                    }
                h << "},\n";
                const osg::Viewport* vp = scene.getViewport();
                const osg::Matrixd inverse = scene.getInverseViewMatrix();
                const osg::Vec3d eye = inverse.getTrans();
                osg::Vec3d look = osg::Vec3d(0, 0, -1) * inverse - eye;
                look.normalize();
                double fovy = 0, aspect = 0, zNear = 0, zFar = 0;
                scene.getProjectionMatrixAsPerspective(fovy, aspect, zNear, zFar);
                h << "  \"view\": {\"eye\": [" << number(eye.x()) << ", " << number(eye.y()) << ", " << number(eye.z())
                  << "], \"look\": [" << number(look.x()) << ", " << number(look.y()) << ", " << number(look.z())
                  << "], \"width\": " << (vp ? vp->width() : 0) << ", \"height\": " << (vp ? vp->height() : 0)
                  << ", \"fov\": " << number(std::abs(fovy)) << ", \"threading\": "
                  << static_cast<int>(viewer.getThreadingModel()) << ", \"cull mask\": " << mCullMask
                  << ", \"shadows\": " << (mShadowsOn ? "true" : "false") << ", \"grass\": "
                  << (mGrassOn ? "true" : "false") << ", \"occlusion culling\": " << (mOcclusionOn ? "true" : "false")
                  << ", \"thermal pressure\": " << thermalPressure() << "},\n";

                // every setting as the game has it (its defaults with the user's on top), and the F2 menu's
                std::map<std::string, std::string> settings;
                for (const auto& [key, value] : Settings::Manager::mDefaultSettings)
                    settings[key.first + "/" + key.second] = value;
                for (const auto& [key, value] : Settings::Manager::mUserSettings)
                    settings[key.first + "/" + key.second] = value;
                h << "  \"settings\": {";
                first = true;
                for (const auto& [key, value] : settings)
                {
                    h << (first ? "\n    " : ",\n    ") << jsonString(key) << ": " << jsonString(value);
                    first = false;
                }
                h << "\n  },\n";
                std::string menu;
                if (!Settings::ShaderManager::get().getRoot().IsNull())
                    menu = YAML::Dump(Settings::ShaderManager::get().getRoot());
                h << "  \"menu\": " << jsonString(menu) << ",\n";
                return h.str();
            }

            void finish(const std::string& error)
            {
                osg::ref_ptr<osg::Camera> scene;
                {
                    std::lock_guard lock(mCameraMutex);
                    mScene.lock(scene);
                }
                if (scene)
                    restore(*scene);
                occlusionForce(false);
                occlusionTune(0, 0);
                uncollectStats();
                mRunning = false;
                mStatusUntil = Clock::now() + std::chrono::seconds(12);
                if (!error.empty())
                {
                    Log(Debug::Warning) << "Benchmark: stopped, " << error;
                    status("Benchmark stopped: " + error);
                    return;
                }
                const std::filesystem::path dir = cacheDirectory().parent_path() / "benchmarks";
                std::error_code ec;
                std::filesystem::create_directories(dir, ec);
                std::string commit = ALPHA3_BUILD_COMMIT;
                const std::filesystem::path path = dir / ("benchmark-" + mStamp + "-" + commit + ".json");
                std::ofstream out(path);
                out << "{\n  \"alpha3 benchmark\": 1,\n" << mHeader << "  \"phases\": [";
                for (int i = 0; i < sPhaseCount; ++i)
                    out << (i ? ",\n" : "\n") << phaseJson(mResults[i]);
                out << "\n  ]\n}\n";
                out.close();
                if (!out)
                {
                    Log(Debug::Error) << "Benchmark: cannot write " << path.string();
                    status("Benchmark: could not write the file");
                    return;
                }
                Log(Debug::Info) << "Benchmark: written to " << path.string();

                // the same as a table to read on the phone: on the screen until the F2 menu opens, and next to the file
                // (with lines for the next run to compare itself with)
                std::filesystem::path txt = path;
                txt.replace_extension(".txt");
                const std::string table = summary(previousRun(dir, txt), path.filename().string());
                std::ofstream text(txt);
                text << table << "\n";
                for (const PhaseResult& r : mResults)
                    if (r.mPhase && !r.mSkipped)
                        text << "data\t" << r.mPhase->mName << "\t" << number(meanMs(r)) << "\t" << number(gpuMs(r))
                             << "\n";
                hideStatus();
                mStatusUntil.reset();
                showResults(table);
            }

            static double meanMs(const PhaseResult& r)
            {
                if (r.mSkipped || r.mIntervals.empty())
                    return std::nan("");
                double sum = 0;
                for (double v : r.mIntervals)
                    sum += v;
                return sum / r.mIntervals.size();
            }

            // the scene camera's GPU time, ms (NaN without timer queries)
            // A value the scene camera's stats hold, averaged over the frames that have it (nan: none)
            static double sceneValue(const PhaseResult& r, const char* key)
            {
                for (const auto& [name, sums] : r.mCameras)
                {
                    if (name != sSceneCamera && r.mCameras.size() > 1)
                        continue;
                    const auto it = sums.mValues.find(key);
                    if (it != sums.mValues.end() && it->second.second > 0)
                        return it->second.first / it->second.second;
                }
                return std::nan("");
            }

            static double gpuMs(const PhaseResult& r)
            {
                for (const auto& [name, sums] : r.mCameras)
                {
                    if (name != sSceneCamera && r.mCameras.size() > 1)
                        continue;
                    const auto it = sums.mValues.find("GPU draw time taken");
                    if (it != sums.mValues.end() && it->second.second > 0)
                        return it->second.first / it->second.second * 1000.0;
                }
                return std::nan("");
            }

            struct Previous
            {
                std::string mFile, mBuild;
                std::map<std::string, std::pair<double, double>> mPhases; // ms, GPU ms
            };

            // the newest run before this one (its .txt)
            static Previous previousRun(const std::filesystem::path& dir, const std::filesystem::path& self)
            {
                Previous p;
                std::filesystem::path newest;
                std::error_code ec;
                for (const auto& entry : std::filesystem::directory_iterator(dir, ec))
                {
                    const std::filesystem::path& f = entry.path();
                    if (f.extension() == ".txt" && f.filename().string().rfind("benchmark-", 0) == 0 && f != self
                        && (newest.empty() || f.filename() > newest.filename()))
                        newest = f;
                }
                if (newest.empty())
                    return p;
                std::ifstream in(newest);
                std::string line;
                while (std::getline(in, line))
                {
                    if (line.rfind("build ", 0) == 0 && p.mBuild.empty())
                        p.mBuild = line.substr(6, line.find(' ', 6) - 6);
                    if (line.rfind("data\t", 0) != 0)
                        continue;
                    std::istringstream fields(line.substr(5));
                    std::string name, ms, gpu;
                    std::getline(fields, name, '\t');
                    std::getline(fields, ms, '\t');
                    std::getline(fields, gpu, '\t');
                    const auto value = [](const std::string& s) { return s == "null" ? std::nan("") : std::atof(s.c_str()); };
                    p.mPhases[name] = { value(ms), value(gpu) };
                }
                if (!p.mPhases.empty())
                    p.mFile = newest.filename().string();
                return p;
            }

            std::string summary(const Previous& previous, const std::string& file) const
            {
                std::string out;
                char line[256];
                const auto cell = [](double v, const char* format) {
                    char b[32];
                    if (std::isfinite(v))
                        std::snprintf(b, sizeof b, format, v);
                    else
                        std::snprintf(b, sizeof b, "%7s", "-");
                    return std::string(b);
                };
                std::snprintf(line, sizeof line, "build %s  %s %s\n", ALPHA3_BUILD_COMMIT,
                    property("ro.product.manufacturer").c_str(), property("ro.product.model").c_str());
                out += line;
                const bool compare = !previous.mPhases.empty();
                // occl: the occlusion buffer's drawing (cull thread) and the share of what was tested that it culled
                out += compare ? "                         fps      ms     GPU   cost   occl culled |  then ms  cost  change\n"
                               : "                         fps      ms     GPU   cost   occl culled\n";
                const double baseMs = meanMs(mResults[0]);
                const double thenBase
                    = compare && previous.mPhases.count("baseline") ? previous.mPhases.at("baseline").first : std::nan("");
                for (const PhaseResult& r : mResults)
                {
                    if (r.mPhase == nullptr)
                        continue;
                    const std::string name = r.mPhase->mName;
                    if (r.mSkipped)
                    {
                        std::snprintf(line, sizeof line, "%-22s (skipped: %s)\n", name.c_str(), r.mSkipReason.c_str());
                        out += line;
                        continue;
                    }
                    const double ms = meanMs(r);
                    const bool isBase = &r == &mResults[0] || name == "baseline again";
                    const double cost = isBase ? std::nan("") : baseMs - ms;
                    std::snprintf(line, sizeof line, "%-22s", name.c_str());
                    const double tested = sceneValue(r, "Occlusion tested");
                    const double culled = tested > 0 ? 100.0 * sceneValue(r, "Occlusion culled") / tested : std::nan("");
                    out += line + cell(1000.0 / ms, "%7.1f") + " " + cell(ms, "%7.2f") + " " + cell(gpuMs(r), "%7.2f")
                        + cell(cost, "%7.2f") + cell(sceneValue(r, "Occlusion draw time taken") * 1000.0, "%7.2f")
                        + cell(culled, "%6.0f%%");
                    if (compare)
                    {
                        const auto it = previous.mPhases.find(name);
                        const double then = it == previous.mPhases.end() ? std::nan("") : it->second.first;
                        const double thenCost = isBase ? std::nan("") : thenBase - then;
                        // what grew: the frame for the baselines, the part's cost for the rest
                        const double change = isBase ? ms - then : cost - thenCost;
                        out += " |" + cell(then, "%8.2f") + cell(thenCost, "%7.2f") + cell(change, "%+7.2f");
                    }
                    out += "\n";
                }
                const PhaseResult& base = mResults[0];
                const double gpu = gpuMs(base);
                const auto get = [&](const char* key) {
                    const auto it = base.mMachine.mValues.find(key);
                    return it == base.mMachine.mValues.end() ? std::nan("") : it->second;
                };
                std::snprintf(line, sizeof line,
                    "limited by: %s   hottest %.0f C, GPU %.0f MHz %.0f%% busy, CPU %.0f%% busy, thermal %d\n",
                    std::isfinite(gpu) && gpu > 0.85 * baseMs ? "GPU" : "CPU or pacing", get("temp hottest c"),
                    get("gpu mhz"), get("gpu busy %"), base.mCpuBusy, base.mThermalPressure);
                out += line;
                out += "cost: how much faster the frame is without that part\n";
                if (compare)
                    out += "then: " + previous.mFile + "\n";
                out += "saved: Alpha3/benchmarks/" + file + " (and .txt)";
                return out;
            }

            // The scene camera's eye and the way it looks, false when there is no camera
            bool sceneView(osg::Vec3d& eye, osg::Vec3d& look)
            {
                osg::ref_ptr<osg::Camera> scene;
                {
                    std::lock_guard lock(mCameraMutex);
                    mScene.lock(scene);
                }
                if (!scene)
                    return false;
                const osg::Matrixd inverse = scene->getInverseViewMatrix();
                eye = inverse.getTrans();
                look = osg::Vec3d(0, 0, -1) * inverse - eye;
                look.normalize();
                return true;
            }

            // Whether the player moved or turned since the results were shown (a touch on Android goes to the
            // launcher's controls, not to MyGUI, so moving is how the results are put away)
            bool viewMoved()
            {
                osg::Vec3d eye, look;
                if (!mResultsViewKnown || !sceneView(eye, look))
                    return false;
                return (eye - mResultsEye).length() > 32.0 || look * mResultsLook < 0.996; // about 5 degrees
            }

            // the results over the game until the player moves or turns, for two minutes at most, or until the F2
            // window opens
            void showResults(const std::string& table)
            {
                MyGUI::Gui* gui = MyGUI::Gui::getInstancePtr();
                if (gui == nullptr)
                    return;
                hideResults();
                const MyGUI::IntSize size = MyGUI::RenderManager::getInstance().getViewSize();
                const int w = std::max(200, size.width - 40), h = std::max(200, size.height - 40);
                mPanel = gui->createWidget<MyGUI::Widget>(
                    "DialogBG", MyGUI::IntCoord(20, 20, w, h), MyGUI::Align::Default, "Notification");
                mPanel->setNeedMouseFocus(false);
                auto* text = mPanel->createWidget<MyGUI::TextBox>(
                    "SandText", MyGUI::IntCoord(12, 10, w - 24, h - 20), MyGUI::Align::Stretch);
                text->setFontName("MonoFont");
                text->setTextAlign(MyGUI::Align::Left | MyGUI::Align::Top);
                text->setNeedMouseFocus(false);
                text->setCaption(MyGUI::UString("Benchmark results (move or turn to close)\n\n" + table));
                mResultsShown = true;
                mResultsUntil = Clock::now() + std::chrono::minutes(2);
                mResultsViewKnown = sceneView(mResultsEye, mResultsLook);
            }

            void hideResults()
            {
                if (mPanel != nullptr && MyGUI::Gui::getInstancePtr() != nullptr)
                    MyGUI::Gui::getInstance().destroyWidget(mPanel);
                mPanel = nullptr;
                mResultsShown = false;
            }

            static std::string phaseJson(const PhaseResult& r)
            {
                std::ostringstream o;
                o << "    {\"name\": " << jsonString(r.mPhase ? r.mPhase->mName : "?") << ", \"what\": "
                  << jsonString(r.mPhase ? r.mPhase->mWhat : "");
                if (r.mSkipped)
                {
                    o << ", \"skipped\": " << jsonString(r.mSkipReason) << "}";
                    return o.str();
                }
                std::vector<double> v = r.mIntervals;
                std::sort(v.begin(), v.end());
                const auto pct = [&](double p) {
                    return v.empty() ? std::nan("") : v[std::min(v.size() - 1, static_cast<std::size_t>(p * v.size()))];
                };
                double sum = 0;
                for (double x : v)
                    sum += x;
                const double mean = v.empty() ? std::nan("") : sum / v.size();
                o << ", \"frames\": " << v.size() << ", \"fps\": " << number(1000.0 / mean) << ",\n     \"ms\": {\"mean\": "
                  << number(mean) << ", \"p50\": " << number(pct(0.5)) << ", \"p90\": " << number(pct(0.9))
                  << ", \"p99\": " << number(pct(0.99)) << ", \"max\": " << number(v.empty() ? std::nan("") : v.back())
                  << "},\n     \"cpu busy %\": " << number(r.mCpuBusy) << ", \"process cores\": "
                  << number(r.mProcessCores) << ", \"thermal pressure\": " << r.mThermalPressure << ",\n     \"machine\": {";
                bool first = true;
                for (const auto& [key, value] : r.mMachine.mValues)
                {
                    o << (first ? "" : ", ") << jsonString(key) << ": " << number(value);
                    first = false;
                }
                o << ", \"cpu mhz\": [";
                for (std::size_t i = 0; i < r.mMachine.mCpuMhz.size(); ++i)
                    o << (i ? ", " : "") << number(r.mMachine.mCpuMhz[i]);
                o << "]},\n     \"stats frames\": " << r.mDrawnFrames << ",\n     \"viewer\": " << r.mViewer.json()
                  << ",\n     \"cameras\": {";
                first = true;
                for (const auto& [name, sums] : r.mCameras)
                {
                    o << (first ? "" : ", ") << jsonString(name) << ": " << sums.json();
                    first = false;
                }
                o << "}}";
                return o.str();
            }

            // a line at the top of the screen
            void status(const std::string& text)
            {
                MyGUI::Gui* gui = MyGUI::Gui::getInstancePtr();
                if (gui == nullptr)
                    return;
                if (mLabel == nullptr)
                {
                    const MyGUI::IntSize size = MyGUI::RenderManager::getInstance().getViewSize();
                    mLabel = gui->createWidget<MyGUI::TextBox>("SandText",
                        MyGUI::IntCoord(16, 12, std::max(100, size.width - 32), 28), MyGUI::Align::Default,
                        "Notification");
                    mLabel->setTextShadow(true);
                    mLabel->setNeedMouseFocus(false);
                }
                mLabel->setCaption(text);
            }

            void hideStatus()
            {
                if (mLabel != nullptr && MyGUI::Gui::getInstancePtr() != nullptr)
                    MyGUI::Gui::getInstance().destroyWidget(mLabel);
                mLabel = nullptr;
            }

            struct Collect
            {
                osg::observer_ptr<osg::Stats> mStats;
                std::string mName;
                bool mWas;
            };

            std::mutex mGlMutex, mCameraMutex;
            std::string mGl;
            std::string mGlVersion; // GL_VERSION, as the driver gave it

            // What drew the run, for the results' "renderer:" line: "Vulkan 1.1 (Adreno (TM) 640)" (a custom driver
            // names itself in the device name, "Turnip Adreno (TM) 640"), "OpenGL ES 3.2", "OpenGL on Vulkan (Zink)"
            std::string rendererName()
            {
#ifdef __ANDROID__
                if (usesVulkan())
                {
                    std::string name = "Vulkan";
                    if (const Vk::Device* device = Vk::device())
                        name += " " + std::to_string(VK_API_VERSION_MAJOR(device->mApiVersion)) + "."
                            + std::to_string(VK_API_VERSION_MINOR(device->mApiVersion)) + " ("
                            + device->mProperties.deviceName + ")";
                    return name;
                }
                if (usesZink())
                    return "OpenGL on Vulkan (Zink)";
#endif
                std::lock_guard lock(mGlMutex);
                // "OpenGL ES 3.2 V@0502.0 (GIT@...)": the API and its version, without the driver's build
                std::string version = mGlVersion;
                const std::size_t es = version.find("OpenGL ES ");
                if (es != std::string::npos)
                {
                    const std::size_t end = version.find(' ', es + 10);
                    version = version.substr(es, end == std::string::npos ? std::string::npos : end - es);
                }
                return version.empty() ? std::string("OpenGL") : version;
            }
            osg::observer_ptr<osg::Camera> mScene;
            std::atomic<bool> mCameraSeen{ false };
            std::atomic<bool> mAsk{ false };
            bool mRequested = false, mRunning = false, mMeasuring = false;
            Clock::time_point mStartAt, mResultsUntil;
            int mPhase = -1;
            Clock::time_point mPhaseStart, mLast, mMeasureStart, mLastNudge;
            std::optional<Clock::time_point> mStatusUntil;
            unsigned mMeasureFrame = 0;
            CpuTimes mCpuStart;
            std::vector<PhaseResult> mResults;
            std::vector<Collect> mCollect;
            std::string mHeader, mStamp;
            unsigned mCullMask = ~0u;
            SceneUtil::MWShadowTechnique* mShadows = nullptr;
            bool mShadowsOn = false, mGrassOn = false, mOcclusionOn = false;
            MyGUI::TextBox* mLabel = nullptr;
            MyGUI::Widget* mPanel = nullptr;
            bool mResultsShown = false;
            bool mResultsViewKnown = false;
            osg::Vec3d mResultsEye, mResultsLook;
            bool mFlyChecked = false, mFlyReleased = false;
            Fly mFly = Fly::Off;
            const FlyRoute* mRoute = nullptr;
            Clock::time_point mFlyStart;
            int mFlyShown = -1;
        };
    }

    void benchmarkGlInfo(const osg::GLExtensions& extensions, const char* vendor, const char* renderer, const char* version)
    {
        Benchmark::get().glInfo(extensions, vendor ? vendor : "", renderer ? renderer : "", version ? version : "");
    }

    void benchmarkDrawableAdded(osg::NodeVisitor& cullVisitor)
    {
        Benchmark& b = Benchmark::get();
        if (b.cameraSeen())
            return;
        if (osg::Camera* camera = static_cast<osgUtil::CullVisitor&>(cullVisitor).getCurrentCamera())
            b.camera(camera);
    }

    void benchmarkRequest()
    {
        Benchmark::get().request();
    }

    void benchmarkFrame()
    {
        Benchmark::get().frame();
    }

    bool benchmarkFlyPose(BenchmarkPose& pose, bool& done)
    {
        return Benchmark::get().flyPose(pose, done);
    }
}

#ifdef __ANDROID__
#include <jni.h>

// the launcher's in-game gear menu, "Benchmark" (androidApp SdlEngineBridge.omwBenchmarkStart)
extern "C" JNIEXPORT void JNICALL Java_org_openmw_SdlEngineBridge_omwBenchmarkStart(JNIEnv*, jobject)
{
    Alpha3::benchmarkRequest();
}
#endif
