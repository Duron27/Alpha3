// Grass fire of the experimental graphics ([Experimental] grass fire, with the grass): fire and lightning striking
// grassy ground (OpenMW patch 0024: groundStruck) set it alight. A ring of flame goes out from there like a ripple,
// ragged, slowing as it goes; the blades burn down to a black stubble in it and the ground behind it is charred, embers
// glowing (experimental/grassfire.glsl, in the grass's and the terrain's shaders). It stops where the grass ends (a
// road, a rock, water, a cliff) or when it has gone its distance, then smokes a while; the grass grows back within
// [Experimental] grass fire regrow seconds, and at once when the player goes far from it.
//
// In the rain, and on ground still wet from it, everything is slower and smaller: the front creeps, the flames are low
// and few, and it smokes more, white with steam.
//
// The flames, sparks and smoke are quads made here each frame (experimental/fireparticles.glsl), on the ground where
// the grass is, which the grass's chunks tell (grassGround): looked up once when a fire starts, along 16 directions.
#include "support.hpp"

#include <alpha3/upstream.hpp>

#include "experimentalsources.hpp"

#include <algorithm>
#include <array>
#include <atomic>
#include <chrono>
#include <cmath>
#include <limits>
#include <memory>
#include <mutex>
#include <numeric>
#include <vector>

#include <osg/BlendFunc>
#include <osg/Geometry>
#include <osg/io_utils>
#include <osg/Program>
#include <osg/Shader>
#include <osgUtil/CullVisitor>

#include <components/debug/debuglog.hpp>
#include <components/sceneutil/depth.hpp>

namespace Alpha3
{
    namespace
    {
        constexpr int sMaxFires = 4; // a3Fire[4] in grassfire.glsl
        constexpr int sDirections = 16; // a3FireReach: four vec4 a fire
        constexpr int sSteps = 64; // how the ground is looked up along each direction
        constexpr float sStep = 32.f;
        constexpr int sMaxFlames = 420, sMaxSparks = 90, sMaxSmoke = 150;
        constexpr float sTwoPi = 6.2831853f;
        constexpr int sDepthSortedBin = 10; // OpenMW's RenderBin_DepthSorted

        double seconds()
        {
            return std::chrono::duration<double>(std::chrono::steady_clock::now().time_since_epoch()).count();
        }

        // ---- random numbers (the update thread's)
        std::uint32_t sRandomState = 0x9e3779b9u;
        float random01()
        {
            sRandomState ^= sRandomState << 13;
            sRandomState ^= sRandomState >> 17;
            sRandomState ^= sRandomState << 5;
            return static_cast<float>(sRandomState >> 8) / 16777216.f;
        }
        float between(float a, float b)
        {
            return a + (b - a) * random01();
        }

        // the front's ragged shape, as grassfire.glsl has it
        float wobble(float angle, float seed)
        {
            const float s = seed * sTwoPi;
            return 1.f + 0.08f * std::sin(3.f * angle + s) + 0.05f * std::sin(7.f * angle + 2.3f * s)
                + 0.03f * std::sin(13.f * angle + 4.1f * s);
        }

        enum class Kind
        {
            Flame = 0,
            Smoke = 1,
            Spark = 2,
        };

        struct Particle
        {
            osg::Vec3f mPosition, mVelocity;
            float mAge = 0.f, mLife = 1.f;
            float mWidth = 1.f, mHeight = 1.f;
            float mSeed = 0.f;
            float mStrength = 1.f;
            float mLightness = 0.f; // smoke: 0 black, 1 white steam
            float mGrowth = 0.f; // smoke: how much wider it gets over its life
            Kind mKind = Kind::Flame;
        };

        struct Fire
        {
            bool mActive = false;
            osg::Vec3f mCentre;
            float mRadius = 0.f; // where the front is
            float mEnd = 0.f; // where it stops: its distance, or the farthest grass
            float mSpeed = 100.f;
            float mWidth = 55.f; // the burning band
            float mWet = 0.f; // 0 dry .. 1 soaked, when it started
            float mSeed = 0.f;
            float mAge = 0.f;
            float mOutAt = -1.f; // its age when the front went out
            float mLive = 0.f; // how hard the front burns
            float mRegrow = 0.f;
            float mReach[sDirections] = {};
            // the ground along each direction: height and how grassy, every sStep
            float mZ[sDirections][sSteps] = {};
            float mGrassy[sDirections][sSteps] = {};
            float mFlameDue = 0.f, mSparkDue = 0.f, mSmokeDue = 0.f;

            float reachAt(float angle) const
            {
                const float bin = (angle / sTwoPi + 0.5f) * sDirections;
                const float b0 = std::floor(bin);
                const auto at = [&](float b) {
                    int i = static_cast<int>(b) % sDirections;
                    return mReach[i < 0 ? i + sDirections : i];
                };
                return at(b0) + (at(b0 + 1.f) - at(b0)) * (bin - b0);
            }

            // the ground's height and grassiness at distance along angle
            std::pair<float, float> groundAt(float angle, float distance) const
            {
                int d = static_cast<int>(std::lround((angle / sTwoPi + 0.5f) * sDirections)) % sDirections;
                d = d < 0 ? d + sDirections : d;
                const float f = std::clamp(distance / sStep - 1.f, 0.f, sSteps - 1.001f);
                const int k = static_cast<int>(f);
                const float a = f - k;
                const int k1 = std::min(k + 1, sSteps - 1);
                const float z = mZ[d][k] + (mZ[d][k1] - mZ[d][k]) * a;
                const float grassy = mGrassy[d][k] + (mGrassy[d][k1] - mGrassy[d][k]) * a;
                // nearer than the first step: the centre's
                const float near = std::clamp(distance / sStep, 0.f, 1.f);
                return { mCentre.z() + (z - mCentre.z()) * near, grassy };
            }
        };

        class GrassFire
        {
        public:
            static GrassFire& get()
            {
                static GrassFire* fire = new GrassFire;
                return *fire;
            }

            GrassFire()
            {
                mFireUniform = new osg::Uniform(osg::Uniform::FLOAT_VEC4, "a3Fire", sMaxFires);
                mStateUniform = new osg::Uniform(osg::Uniform::FLOAT_VEC4, "a3FireState", sMaxFires);
                mReachUniform = new osg::Uniform(osg::Uniform::FLOAT_VEC4, "a3FireReach", sMaxFires * 4);
                mCountUniform = new osg::Uniform("a3FireCount", 0.f);
                mTimeUniform = new osg::Uniform("a3FireTime", 0.f);
                for (osg::Uniform* u : { mFireUniform.get(), mStateUniform.get(), mReachUniform.get(),
                         mCountUniform.get(), mTimeUniform.get() })
                    u->setDataVariance(osg::Object::DYNAMIC);
                for (int i = 0; i < sMaxFires; ++i)
                {
                    mFireUniform->setElement(i, osg::Vec4f());
                    mStateUniform->setElement(i, osg::Vec4f());
                }
                for (int i = 0; i < sMaxFires * 4; ++i)
                    mReachUniform->setElement(i, osg::Vec4f());
                mParticles.reserve(sMaxFlames + sMaxSparks + sMaxSmoke);
            }

            void install(osg::StateSet& state)
            {
                for (osg::Uniform* u : { mFireUniform.get(), mStateUniform.get(), mReachUniform.get(),
                         mCountUniform.get(), mTimeUniform.get() })
                    state.addUniform(u);
            }

            // The main thread, as a spell explodes
            void struck(const osg::Vec3f& at, bool shock, float area, float magnitude)
            {
                const ExperimentalSettings& s = experimentalSettings();
                if (!s.mGrass || !s.mGrassFire || !grassOn() || seconds() - mLastCull.load() > 1.0)
                {
                    Log(Debug::Info) << "Grass fire: struck at " << at << ", no grass drawn here";
                    return;
                }
                // on grassy ground, not far above it (one striking a roof or a cliff top sets nothing alight)
                std::array<osg::Vec2f, 5> here;
                const osg::Vec2f centre(at.x(), at.y());
                here[0] = centre;
                for (int i = 0; i < 4; ++i)
                    here[i + 1] = centre + osg::Vec2f(std::cos(i * 1.5708f), std::sin(i * 1.5708f)) * 40.f;
                std::array<osg::Vec2f, 5> found;
                grassGround(here.data(), here.size(), found.data());
                const float groundZ = found[0].x();
                if (std::isnan(groundZ) || at.z() - groundZ > 180.f || groundZ - at.z() > 80.f)
                {
                    Log(Debug::Info) << "Grass fire: struck at " << at << ", not on the ground (" << groundZ << ")";
                    return;
                }
                float grassy = 0.f;
                for (const osg::Vec2f& f : found)
                    grassy = std::max(grassy, std::isnan(f.x()) ? 0.f : f.y());
                if (grassy < 0.25f)
                {
                    Log(Debug::Info) << "Grass fire: struck at " << at << ", no grass (" << grassy << ")";
                    return;
                }

                auto fire = std::make_unique<Fire>();
                fire->mCentre = osg::Vec3f(centre, groundZ);
                fire->mWet = mWetness;
                const float wet = fire->mWet;
                const float distance = std::clamp(140.f + area * 0.3f + magnitude * 1.7f, 130.f, 600.f)
                    * s.mGrassFireSize * (1.f - 0.45f * wet) * (shock ? 0.75f : 1.f);

                // the ground along each direction, out to where the grass ends: two steps without it, a cliff, water
                std::vector<osg::Vec2f> places;
                const int steps = std::min(sSteps, static_cast<int>(distance / sStep) + 2);
                for (int d = 0; d < sDirections; ++d)
                {
                    const float angle = (static_cast<float>(d) / sDirections - 0.5f) * sTwoPi;
                    for (int k = 1; k <= steps; ++k)
                        places.push_back(centre + osg::Vec2f(std::cos(angle), std::sin(angle)) * (k * sStep));
                }
                std::vector<osg::Vec2f> ground(places.size());
                grassGround(places.data(), places.size(), ground.data());
                float farthest = 0.f;
                for (int d = 0; d < sDirections; ++d)
                {
                    float reach = sStep, lastZ = groundZ;
                    int bare = 0;
                    bool stopped = false;
                    for (int k = 0; k < sSteps; ++k)
                    {
                        const osg::Vec2f g = k < steps ? ground[static_cast<std::size_t>(d * steps + k)]
                                                       : osg::Vec2f(std::numeric_limits<float>::quiet_NaN(), 0.f);
                        const bool known = !std::isnan(g.x());
                        fire->mZ[d][k] = known ? g.x() : lastZ;
                        fire->mGrassy[d][k] = known ? g.y() : 0.f;
                        if (stopped)
                            continue;
                        const float dist = (k + 1) * sStep;
                        if (!known || dist > distance || std::abs(g.x() - lastZ) > 70.f || g.x() < 0.f)
                        {
                            stopped = true;
                            continue;
                        }
                        bare = g.y() < 0.2f ? bare + 1 : 0;
                        if (bare >= 2)
                        {
                            stopped = true;
                            continue;
                        }
                        lastZ = g.x();
                        if (bare == 0)
                            reach = dist;
                    }
                    fire->mReach[d] = std::min(reach + sStep * 0.5f, distance);
                    farthest = std::max(farthest, fire->mReach[d]);
                }
                fire->mEnd = std::min(distance, farthest);
                fire->mSpeed = (shock ? 33.f : 26.f) * (1.f - 0.7f * wet);
                fire->mWidth = 35.f * (1.f - 0.35f * wet);
                fire->mSeed = random01();
                fire->mRadius = shock ? 35.f : 15.f; // the strike's own burst
                fire->mActive = true;

                std::lock_guard lock(mMutex);
                // a free place, or the oldest fire's
                int slot = 0;
                for (int i = 0; i < sMaxFires; ++i)
                {
                    if (!mFires[i] || !mFires[i]->mActive)
                    {
                        slot = i;
                        break;
                    }
                    if (mFires[i]->mAge > mFires[slot]->mAge)
                        slot = i;
                }
                // lightning: a burst of sparks where it struck
                if (shock)
                    for (int i = 0; i < 30; ++i)
                        spark(fire->mCentre + osg::Vec3f(between(-20.f, 20.f), between(-20.f, 20.f), 2.f), 1.4f);
                Log(Debug::Info) << "Grass fire at " << centre.x() << ", " << centre.y() << ", going " << fire->mEnd
                                    << (wet > 0.05f ? " (wet)" : "");
                mFires[slot] = std::move(fire);
            }

            // The update traversal: the fires burn on, the particles move, the uniforms and the quads are made afresh
            void update(float precipitation, float dt)
            {
                const ExperimentalSettings& s = experimentalSettings();
                if (!s.mGrass || !s.mGrassFire)
                    return;
                // wet in about forty seconds of heavy rain, dry in four minutes (as the wet ground)
                if (precipitation > 0.02f)
                    mWetness = std::min(1.f, mWetness + dt / 40.f * (0.3f + 0.7f * precipitation));
                else
                    mWetness = std::max(0.f, mWetness - dt / 240.f);
                const float wetNow = std::max(mWetness, precipitation);
                if (dt <= 0.f)
                    return;
                mTime += dt;
                mTimeUniform->set(mTime);

                std::lock_guard lock(mMutex);
                const bool culled = seconds() - mLastCull.load() < 1.0; // outside, with grass
                int count = 0;
                for (int i = 0; i < sMaxFires; ++i)
                {
                    Fire* fire = mFires[i].get();
                    if (fire != nullptr && fire->mActive)
                    {
                        const osg::Vec2f away(mEye.x() - fire->mCentre.x(), mEye.y() - fire->mCentre.y());
                        // the player has gone: it grows back at once
                        if (!culled || away.length() > fire->mEnd + 2500.f)
                            fire->mActive = false;
                        else
                            burn(*fire, dt, std::max(wetNow, fire->mWet), s.mGrassFireRegrow);
                    }
                    if (fire == nullptr || !fire->mActive)
                    {
                        mFireUniform->setElement(i, osg::Vec4f());
                        mStateUniform->setElement(i, osg::Vec4f());
                        continue;
                    }
                    ++count;
                    mFireUniform->setElement(i, osg::Vec4f(fire->mCentre, fire->mRadius));
                    mStateUniform->setElement(i, osg::Vec4f(fire->mLive, fire->mWidth, fire->mRegrow, fire->mSeed));
                    for (int j = 0; j < 4; ++j)
                        mReachUniform->setElement(i * 4 + j,
                            osg::Vec4f(fire->mReach[j * 4], fire->mReach[j * 4 + 1], fire->mReach[j * 4 + 2],
                                fire->mReach[j * 4 + 3]));
                }
                mCountUniform->set(static_cast<float>(count));
                if (!culled)
                    mParticles.clear();
                move(dt);
                build();
            }

            // The scene camera's cull, once a frame as the grass is culled
            void cull(osgUtil::CullVisitor& cv)
            {
                const osg::Camera* camera = cv.getCurrentCamera();
                mLastCull = seconds();
                std::lock_guard lock(mMutex);
                mEye = camera->getInverseViewMatrix().getTrans();
                if (!mGeometry || mDrawn == 0 || !mBound.valid() || cv.isCulled(mBound))
                    return;
                cv.pushStateSet(stateSet());
                cv.addDrawableAndDepth(mGeometry.get(), new osg::RefMatrix(camera->getViewMatrix()),
                    cv.getDistanceFromEyePoint(mBound.center(), false));
                cv.popStateSet();
            }

        private:
            void burn(Fire& fire, float dt, float wet, float regrowTime)
            {
                fire.mAge += dt;
                const float base = 1.f - 0.45f * wet;
                if (fire.mOutAt < 0.f)
                {
                    // slowing as it goes: a ripple dying away
                    const float left = std::max(0.f, 1.f - fire.mRadius / std::max(fire.mEnd, 1.f));
                    fire.mRadius += dt * fire.mSpeed * std::max(0.1f, std::sqrt(left));
                    fire.mLive = base * std::min(1.f, fire.mAge / 0.4f);
                    if (fire.mRadius >= fire.mEnd * 0.995f)
                    {
                        fire.mRadius = fire.mEnd;
                        fire.mOutAt = fire.mAge;
                    }
                }
                else
                    fire.mLive = base * std::max(0.f, 1.f - (fire.mAge - fire.mOutAt) / 2.5f);
                const float smoulder = 10.f * (1.f + wet);
                if (fire.mOutAt >= 0.f)
                {
                    const float start = std::max(regrowTime * 0.5f, fire.mOutAt + 4.f);
                    fire.mRegrow = std::clamp((fire.mAge - start) / (regrowTime * 0.5f), 0.f, 1.f);
                    if (fire.mRegrow >= 1.f)
                    {
                        fire.mActive = false;
                        return;
                    }
                }

                // flames along the front, where it still burns and there is grass
                const float flames = 1.f - 0.6f * wet;
                const float circumference = sTwoPi * fire.mRadius;
                fire.mFlameDue += dt * (circumference / 4.f + 20.f) * fire.mLive * flames;
                fire.mSparkDue += dt * 14.f * fire.mLive * (1.f - 0.7f * wet);
                if (fire.mOutAt < 0.f)
                    fire.mSmokeDue += dt * (4.f + fire.mRadius / 150.f) * (1.f + 1.5f * wet);
                else
                    fire.mSmokeDue += dt * 6.f * (1.f + wet) * std::max(0.f, 1.f - (fire.mAge - fire.mOutAt) / smoulder);
                for (; fire.mFlameDue >= 1.f; fire.mFlameDue -= 1.f)
                {
                    osg::Vec3f at;
                    float strength;
                    if (!onFront(fire, at, strength))
                        continue;
                    if (count(Kind::Flame) >= sMaxFlames)
                        continue;
                    const float size = (0.45f + 0.55f * strength) * (1.f - 0.5f * wet);
                    Particle p;
                    p.mKind = Kind::Flame;
                    if (random01() < 0.7f)
                    {
                        // a flame rooted in the burning grass, flickering where it stands
                        p.mPosition = at - osg::Vec3f(0.f, 0.f, 2.f);
                        p.mVelocity = osg::Vec3f(between(-3.f, 3.f), between(-3.f, 3.f), between(0.f, 8.f));
                        p.mLife = between(0.5f, 1.1f);
                        p.mWidth = between(12.f, 22.f) * size;
                        p.mHeight = between(24.f, 48.f) * size;
                    }
                    else
                    {
                        // a lick torn off the top, rising and gone
                        p.mPosition = at + osg::Vec3f(0.f, 0.f, between(12.f, 28.f) * size);
                        p.mVelocity = osg::Vec3f(between(-6.f, 6.f), between(-6.f, 6.f), between(70.f, 110.f));
                        p.mLife = between(0.22f, 0.45f);
                        p.mWidth = between(6.f, 11.f) * size;
                        p.mHeight = between(12.f, 22.f) * size;
                    }
                    p.mStrength = strength * (0.6f + 0.4f * flames);
                    p.mSeed = random01();
                    mParticles.push_back(p);
                }
                for (; fire.mSparkDue >= 1.f; fire.mSparkDue -= 1.f)
                {
                    osg::Vec3f at;
                    float strength;
                    if (onFront(fire, at, strength))
                        spark(at + osg::Vec3f(0.f, 0.f, 8.f), strength);
                }
                for (; fire.mSmokeDue >= 1.f; fire.mSmokeDue -= 1.f)
                {
                    if (count(Kind::Smoke) >= sMaxSmoke)
                        continue;
                    // from just behind the front while it goes, from all the burnt ground after
                    const float angle = between(-3.14159f, 3.14159f);
                    const float edge
                        = std::min(fire.mRadius * wobble(angle, fire.mSeed), fire.reachAt(angle));
                    const float dist = fire.mOutAt < 0.f ? std::max(0.f, edge - fire.mWidth - between(0.f, 110.f))
                                                         : edge * std::sqrt(random01());
                    const auto [z, grassy] = fire.groundAt(angle, dist);
                    Particle p;
                    p.mKind = Kind::Smoke;
                    p.mPosition = fire.mCentre
                        + osg::Vec3f(std::cos(angle) * dist, std::sin(angle) * dist, z - fire.mCentre.z() + 30.f);
                    p.mVelocity = osg::Vec3f(between(-6.f, 6.f), between(-6.f, 6.f), between(30.f, 55.f));
                    p.mLife = between(5.f, 8.f) * (fire.mOutAt < 0.f ? 1.f : 1.2f);
                    p.mWidth = between(45.f, 75.f);
                    p.mGrowth = between(2.5f, 3.5f);
                    // dark from the flames, greyer as it smoulders, white with steam in the rain
                    p.mLightness = std::min(0.95f, (fire.mOutAt < 0.f ? 0.12f : 0.4f) + 0.6f * wet);
                    p.mStrength = (fire.mOutAt < 0.f ? 0.32f : 0.2f) + 0.12f * wet;
                    p.mSeed = random01();
                    mParticles.push_back(p);
                }
            }

            // a place on the front where it burns now (false: where it is out, or there is no grass)
            bool onFront(const Fire& fire, osg::Vec3f& at, float& strength)
            {
                if (fire.mLive <= 0.01f)
                    return false;
                const float angle = between(-3.14159f, 3.14159f);
                const float front = fire.mRadius * wobble(angle, fire.mSeed);
                const float reach = fire.reachAt(angle);
                if (front > reach)
                    return false;
                const float dist = std::max(0.f, front - random01() * random01() * fire.mWidth);
                const auto [z, grassy] = fire.groundAt(angle, dist);
                if (random01() > grassy * 1.5f)
                    return false;
                // dying down toward the end of the grass
                strength = fire.mLive * std::clamp((reach - front) / (2.f * fire.mWidth), 0.f, 1.f);
                at = fire.mCentre + osg::Vec3f(std::cos(angle) * dist, std::sin(angle) * dist, z - fire.mCentre.z());
                return strength > 0.02f;
            }

            void spark(const osg::Vec3f& at, float strength)
            {
                if (count(Kind::Spark) >= sMaxSparks)
                    return;
                Particle p;
                p.mKind = Kind::Spark;
                p.mPosition = at;
                p.mVelocity = osg::Vec3f(between(-50.f, 50.f), between(-50.f, 50.f), between(140.f, 260.f));
                p.mLife = between(0.6f, 1.6f);
                p.mWidth = between(1.2f, 2.2f);
                p.mHeight = between(4.f, 7.f);
                p.mStrength = std::min(strength, 1.5f);
                p.mSeed = random01();
                mParticles.push_back(p);
            }

            int count(Kind kind) const
            {
                return mCounts[static_cast<int>(kind)];
            }

            void move(float dt)
            {
                mCounts.fill(0);
                std::size_t kept = 0;
                for (Particle& p : mParticles)
                {
                    p.mAge += dt;
                    if (p.mAge >= p.mLife)
                        continue;
                    switch (p.mKind)
                    {
                        case Kind::Flame:
                            p.mVelocity.z() += 40.f * dt;
                            break;
                        case Kind::Spark:
                            p.mVelocity.z() -= 110.f * dt;
                            p.mVelocity *= 1.f - 0.8f * dt;
                            p.mVelocity += osg::Vec3f(between(-1.f, 1.f), between(-1.f, 1.f), 0.f) * (300.f * dt);
                            break;
                        case Kind::Smoke:
                            p.mVelocity *= 1.f - 0.12f * dt;
                            break;
                    }
                    p.mPosition += p.mVelocity * dt;
                    ++mCounts[static_cast<int>(p.mKind)];
                    mParticles[kept++] = p;
                }
                mParticles.resize(kept);
            }

            // the quads, farthest first
            void build()
            {
                if (!mGeometry)
                    makeGeometry();
                const std::size_t n = std::min<std::size_t>(mParticles.size(), sMaxFlames + sMaxSparks + sMaxSmoke);
                mOrder.resize(n);
                std::iota(mOrder.begin(), mOrder.end(), 0);
                std::sort(mOrder.begin(), mOrder.end(), [&](std::size_t a, std::size_t b) {
                    return (mParticles[a].mPosition - mEye).length2() > (mParticles[b].mPosition - mEye).length2();
                });
                static constexpr float xs[6] = { -1.f, 1.f, 1.f, -1.f, 1.f, -1.f };
                static constexpr float ys[6] = { -1.f, -1.f, 1.f, -1.f, 1.f, 1.f };
                mBound.init();
                for (std::size_t q = 0; q < n; ++q)
                {
                    const Particle& p = mParticles[mOrder[q]];
                    const float age = p.mAge / p.mLife;
                    float kind = static_cast<float>(p.mKind), width = p.mWidth, height = p.mHeight;
                    if (p.mKind == Kind::Smoke)
                    {
                        kind = 1.f + p.mLightness * 0.99f;
                        width = p.mWidth * (1.f + p.mGrowth * std::sqrt(age));
                        height = p.mAge;
                    }
                    for (int v = 0; v < 6; ++v)
                    {
                        const std::size_t i = q * 6 + v;
                        (*mVertices)[i] = p.mPosition;
                        (*mLooks)[i] = osg::Vec4f(xs[v], ys[v], kind, p.mSeed);
                        (*mSizes)[i] = osg::Vec4f(width, height, age, p.mStrength);
                    }
                    const float r = std::max(width, height) + (p.mKind == Kind::Smoke ? 60.f * p.mAge : 0.f);
                    mBound.expandBy(p.mPosition - osg::Vec3f(r, r, r));
                    mBound.expandBy(p.mPosition + osg::Vec3f(r, r, r + height));
                }
                mDrawn = n;
                mDraw->setCount(static_cast<GLsizei>(n * 6));
                mVertices->dirty();
                mLooks->dirty();
                mSizes->dirty();
                mDraw->dirty();
                if (mBound.valid())
                    mGeometry->setInitialBound(mBound);
            }

            void makeGeometry()
            {
                const std::size_t vertices = static_cast<std::size_t>(sMaxFlames + sMaxSparks + sMaxSmoke) * 6;
                mVertices = new osg::Vec3Array(vertices);
                mLooks = new osg::Vec4Array(vertices);
                mSizes = new osg::Vec4Array(vertices);
                mDraw = new osg::DrawArrays(GL_TRIANGLES, 0, 0);
                mGeometry = new osg::Geometry;
                mGeometry->setName("Alpha3 fire");
                mGeometry->setDataVariance(osg::Object::DYNAMIC);
                mGeometry->setUseDisplayList(false);
                mGeometry->setUseVertexBufferObjects(true);
                mGeometry->setVertexArray(mVertices);
                mGeometry->setTexCoordArray(0, mLooks, osg::Array::BIND_PER_VERTEX);
                mGeometry->setTexCoordArray(1, mSizes, osg::Array::BIND_PER_VERTEX);
                mGeometry->addPrimitiveSet(mDraw);
                mGeometry->setCullingActive(false); // culled here, by its bound
            }

            osg::StateSet* stateSet()
            {
                if (!mState)
                {
                    const std::string defines = "#version 120\n";
                    const std::string source(ExperimentalSources::fireParticles);
                    osg::ref_ptr<osg::Program> program = new osg::Program;
                    program->setName("Alpha3 fire");
                    osg::ref_ptr<osg::Shader> vertex
                        = new osg::Shader(osg::Shader::VERTEX, defines + "#define A3_VERTEX\n" + source);
                    vertex->setName("a3_fire.vert");
                    osg::ref_ptr<osg::Shader> fragment = new osg::Shader(osg::Shader::FRAGMENT, defines + source);
                    fragment->setName("a3_fire.frag");
                    program->addShader(vertex);
                    program->addShader(fragment);
                    mState = new osg::StateSet;
                    mState->setAttributeAndModes(program, osg::StateAttribute::ON);
                    // premultiplied: the flames add their light, the smoke covers
                    mState->setAttributeAndModes(new osg::BlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA), osg::StateAttribute::ON);
                    mState->setAttributeAndModes(new SceneUtil::AutoDepth(osg::Depth::LEQUAL, 0.0, 1.0, false),
                        osg::StateAttribute::ON);
                    mState->setMode(GL_CULL_FACE, osg::StateAttribute::OFF);
                    mState->setRenderBinDetails(sDepthSortedBin, "DepthSortedBin");
                }
                return mState.get();
            }

            std::mutex mMutex;
            std::array<std::unique_ptr<Fire>, sMaxFires> mFires;
            std::vector<Particle> mParticles;
            std::array<int, 3> mCounts{};
            std::vector<std::size_t> mOrder;
            osg::Vec3f mEye;
            std::atomic<double> mLastCull{ 0.0 };
            float mWetness = 0.f;
            float mTime = 0.f;
            std::size_t mDrawn = 0;
            osg::BoundingBox mBound;

            osg::ref_ptr<osg::Uniform> mFireUniform, mStateUniform, mReachUniform, mCountUniform, mTimeUniform;
            osg::ref_ptr<osg::Geometry> mGeometry;
            osg::ref_ptr<osg::Vec3Array> mVertices;
            osg::ref_ptr<osg::Vec4Array> mLooks, mSizes;
            osg::ref_ptr<osg::DrawArrays> mDraw;
            osg::ref_ptr<osg::StateSet> mState;
        };
    }

    void groundStruck(const osg::Vec3f& at, bool lightning, float area, float magnitude)
    {
        GrassFire::get().struck(at, lightning, area, magnitude);
    }

    void grassFireInstall(osg::StateSet& state)
    {
        const ExperimentalSettings& s = experimentalSettings();
        if (s.mGrass && s.mGrassFire)
            GrassFire::get().install(state);
    }

    void grassFireCull(osg::NodeVisitor& cullVisitor)
    {
        const ExperimentalSettings& s = experimentalSettings();
        if (s.mGrass && s.mGrassFire)
            GrassFire::get().cull(static_cast<osgUtil::CullVisitor&>(cullVisitor));
    }

    void grassFireUpdate(float precipitation, float dt)
    {
        GrassFire::get().update(precipitation, dt);
    }
}
