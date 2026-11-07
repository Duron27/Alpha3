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
        static const bool enabled = settingBool("Cells", "seamless cells", true);
        return enabled;
    }

    bool seamlessDoors()
    {
        static const bool enabled = seamlessCells() && settingBool("Cells", "seamless doors", true);
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
