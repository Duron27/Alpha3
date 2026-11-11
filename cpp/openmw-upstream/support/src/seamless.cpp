// Seamless cells ([Cells] seamless cells): going through a door, or across the exterior's cell borders, in a game under
// way changes the cells without the loading screen and without fading the screen out and in (OpenMW's scene.cpp asks
// seamlessCells()). A save being loaded still shows the loading screen.
//
// Cells loaded over frames ([Cells] load cells over frames): the settings OpenMW's scene.cpp asks for.
//
// Seamless doors ([Cells] seamless doors): the doorway the player walks through (doorwayPassable, asked by OpenMW's
// mwphysics) and the weather outside for the view out of an interior.
#include "support.hpp"

#include <alpha3/upstream.hpp>

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <mutex>

namespace Alpha3
{
    namespace
    {
        std::mutex sDoorwayMutex;
        std::atomic<std::size_t> sDoorwayCount{ 0 };
        std::array<Doorway, 4> sDoorways;

        bool inDoorway(const Doorway& doorway, const osg::Vec3f& point, const osg::Vec3f& normal)
        {
            const osg::Vec3f relative = point - doorway.mCentre;
            // The doorway's walls, its door and a lintel lower than the door (a low doorway on one side of a door
            // between two cells) do not stop an actor. A floor holds them up as anywhere else, but not what is in the
            // wall around the door there (a sill, a step up into the wall): they walk through at the door's floor
            const float out = relative * doorway.mNormal;
            const bool floor = normal.z() > 0.7f && (relative.z() < 12.f || std::abs(out) > 40.f);
            return !floor && std::abs(relative * doorway.mAcross) < doorway.mHalfWidth
                && std::abs(out) < doorway.mDepth && relative.z() > -8.f && relative.z() < doorway.mHeight;
        }

        std::mutex sOutsideMutex;
        bool sOutsideKnown = false;
        osg::Vec4f sOutsideFog;
        osg::Vec4f sOutsideSky;
        osg::Vec4f sOutsideAmbient;
        osg::Vec4f sOutsideSun;
        std::string sClouds;
        std::string sNextClouds;
        float sCloudBlend = 0.f;
        float sCloudOffset = 0.f;
    }

    bool seamlessCells()
    {
        static const bool enabled = settingBool("Cells", "seamless cells", false);
        return enabled;
    }

    bool seamlessDoors()
    {
        static const bool enabled = seamlessCells() && settingBool("Cells", "seamless doors", false);
        return enabled;
    }

    bool cellsOverFrames()
    {
        static const bool enabled = seamlessCells() && settingBool("Cells", "load cells over frames", true);
        return enabled;
    }

    float cellLoadNearRadius()
    {
        static const float radius = std::max(0.f, settingFloat("Cells", "cell load near radius", 1024.f));
        return radius;
    }

    float cellLoadBudget()
    {
        static const float budget = std::max(0.5f, settingFloat("Cells", "cell load budget", 3.f));
        return budget;
    }

    void setDoorway(const void* actor, const Doorway* doorway)
    {
        setDoorways(doorway, actor && doorway ? 1 : 0);
    }

    void setDoorways(const Doorway* doorways, std::size_t count)
    {
        std::lock_guard<std::mutex> lock(sDoorwayMutex);
        count = std::min(count, sDoorways.size());
        for (std::size_t i = 0; i < count; ++i)
            sDoorways[i] = doorways[i];
        sDoorwayCount = count;
    }

    bool doorwayPassable(const void* actor, const osg::Vec3f& point, const osg::Vec3f& normal)
    {
        // most calls: no doorway
        if (!actor || sDoorwayCount.load(std::memory_order_relaxed) == 0)
            return false;
        std::lock_guard<std::mutex> lock(sDoorwayMutex);
        for (std::size_t i = 0; i < sDoorwayCount.load(std::memory_order_relaxed); ++i)
            if (inDoorway(sDoorways[i], point, normal))
                return true;
        return false;
    }

    void setOutsideLight(const osg::Vec4f& fog, const osg::Vec4f& sky, const osg::Vec4f& ambient, const osg::Vec4f& sun)
    {
        std::lock_guard<std::mutex> lock(sOutsideMutex);
        sOutsideKnown = true;
        sOutsideFog = fog;
        sOutsideSky = sky;
        sOutsideAmbient = ambient;
        sOutsideSun = sun;
    }

    namespace
    {
        bool sOutsideSunKnown = false;
        osg::Vec3f sOutsideSunDirection;

        // the light going from one side of a door to the other (startLightBlend)
        struct LightBlend
        {
            std::mutex mMutex;
            bool mActive = false;
            std::chrono::steady_clock::time_point mStart;
            float mSeconds = 0.f;
            // sunlight: position, diffuse, ambient, specular; fog: colour, (start, end)
            bool mHaveSun = false, mHaveFog = false;
            osg::Vec4f mSun[4], mSunFrom[4];
            osg::Vec4f mFog[2], mFogFrom[2];

            // how far along (1: done); smooth at both ends
            float amount()
            {
                if (!mActive)
                    return 1.f;
                const float t = std::chrono::duration<float>(std::chrono::steady_clock::now() - mStart).count() / mSeconds;
                if (t >= 1.f)
                {
                    mActive = false;
                    return 1.f;
                }
                return t * t * (3.f - 2.f * t);
            }
        };
        LightBlend sLightBlend;
    }

    void setOutsideSunDirection(const osg::Vec3f& direction)
    {
        std::lock_guard<std::mutex> lock(sOutsideMutex);
        sOutsideSunKnown = true;
        sOutsideSunDirection = direction;
    }

    bool outsideSunDirection(osg::Vec3f& direction)
    {
        std::lock_guard<std::mutex> lock(sOutsideMutex);
        if (sOutsideSunKnown)
            direction = sOutsideSunDirection;
        return sOutsideSunKnown;
    }

    void startLightBlend()
    {
        static const float seconds = std::clamp(settingFloat("Cells", "seamless light blend", 1.f), 0.f, 10.f);
        LightBlend& blend = sLightBlend;
        std::lock_guard<std::mutex> lock(blend.mMutex);
        if (seconds <= 0.f || !blend.mHaveSun)
            return;
        // from what is shown now (part way, when the last one has not finished)
        for (int i = 0; i < 4; ++i)
            blend.mSunFrom[i] = blend.mSun[i];
        for (int i = 0; i < 2; ++i)
            blend.mFogFrom[i] = blend.mFog[i];
        blend.mActive = true;
        blend.mSeconds = seconds;
        blend.mStart = std::chrono::steady_clock::now();
    }

    void blendSunlight(osg::Vec4f& position, osg::Vec4f& diffuse, osg::Vec4f& ambient, osg::Vec4f& specular)
    {
        LightBlend& blend = sLightBlend;
        std::lock_guard<std::mutex> lock(blend.mMutex);
        osg::Vec4f* const values[4] = { &position, &diffuse, &ambient, &specular };
        const float amount = blend.amount();
        if (amount < 1.f)
            for (int i = 0; i < 4; ++i)
                *values[i] = blend.mSunFrom[i] + (*values[i] - blend.mSunFrom[i]) * amount;
        // what is shown, for the next blend to start from
        for (int i = 0; i < 4; ++i)
            blend.mSun[i] = *values[i];
        blend.mHaveSun = true;
    }

    void blendFog(osg::Vec4f& color, float& start, float& end)
    {
        LightBlend& blend = sLightBlend;
        std::lock_guard<std::mutex> lock(blend.mMutex);
        const float amount = blend.amount();
        if (amount < 1.f && blend.mHaveFog)
        {
            color = blend.mFogFrom[0] + (color - blend.mFogFrom[0]) * amount;
            start = blend.mFogFrom[1].x() + (start - blend.mFogFrom[1].x()) * amount;
            end = blend.mFogFrom[1].y() + (end - blend.mFogFrom[1].y()) * amount;
        }
        blend.mFog[0] = color;
        blend.mFog[1] = osg::Vec4f(start, end, 0.f, 0.f);
        blend.mHaveFog = true;
    }

    bool outsideLight(osg::Vec4f& fog, osg::Vec4f& sky, osg::Vec4f& ambient, osg::Vec4f& sun)
    {
        std::lock_guard<std::mutex> lock(sOutsideMutex);
        if (!sOutsideKnown)
            return false;
        fog = sOutsideFog;
        sky = sOutsideSky;
        ambient = sOutsideAmbient;
        sun = sOutsideSun;
        return true;
    }

    void setOutsideClouds(const std::string& texture, const std::string& next, float blend, float speed, float duration)
    {
        std::lock_guard<std::mutex> lock(sOutsideMutex);
        sClouds = texture;
        sNextClouds = next;
        sCloudBlend = std::clamp(blend, 0.f, 1.f);
        // as OpenMW's sky scrolls them (mwrender/sky.cpp), a texture's length every 400 / speed seconds
        sCloudOffset = std::fmod(sCloudOffset + duration * speed / 400.f, 4.f);
    }

    void outsideClouds(DoorPortalLight& light)
    {
        std::lock_guard<std::mutex> lock(sOutsideMutex);
        light.mClouds = sClouds;
        light.mNextClouds = sNextClouds;
        light.mCloudBlend = sCloudBlend;
        light.mCloudOffset = sCloudOffset;
    }
}
