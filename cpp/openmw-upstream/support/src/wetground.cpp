// Wet ground of the experimental graphics (F2 menu, a3_wetground: wet ground, wet ground puddles, wet ground dry under
// objects): the terrain darkens and shines while it rains, puddles gather, and it dries slowly after
// (experimental/wetground.glsl, added to the terrain's fragment shader). Rain drips off the edges of things, and
// whatever walks on the wet ground leaves footprints in it.
//
// The rendering manager says how hard it rains every frame (setRain, OpenMW patch 0020). The scene camera, noted as it
// culls, is given the uniforms (and the F2 menu's live values of all the experimental graphics, experimentalLive*, and
// the grass fire's, grassfire.cpp), and while the ground is wet, cameras of its own that draw, from straight above:
//   - how high the things around the player reach (roofs, trees, rocks; not the terrain), the highest of each texel
//     ("dry under objects"): the ground under something higher than itself is dry, and rain drips off its edges
//     (experimental/drips.glsl: drops falling from where something stands over part of a cell and not the rest)
//   - the terrain's height ("ground map"), for where the drops land and for the footprints
//   - the footprints: actors and the player are drawn from above, the parts of them at the ground (by the ground map)
//     marking the time in a map around the player that keeps them (moved along when the player goes far from its
//     middle, two copies). The player in first person has no legs drawn: steps are made for them as they walk.
// The maps are drawn again only while the ground is wet: the height of things when the player has moved or every two
// seconds, the ground's every few, the footprints every frame.
#include "support.hpp"

#include <alpha3/upstream.hpp>

#include "experimentalsources.hpp"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <string>

#include <osg/BlendEquation>
#include <osg/BlendFunc>
#include <osg/Camera>
#include <osg/Geode>
#include <osg/Geometry>
#include <osg/Program>
#include <osg/Shader>
#include <osg/Texture2D>
#include <osg/observer_ptr>
#include <osgUtil/CullVisitor>

#include <components/debug/debuglog.hpp>
#include <components/sceneutil/depth.hpp>
#include <components/settings/shadermanager.hpp>

namespace Alpha3
{
    namespace
    {
        const std::string sSceneCamera = "SceneCam";
        constexpr int sMapSize = 512; // A3_RAIN_MAP_SIZE in experimentalshaders.cpp
        constexpr float sMapArea = 2048.f; // game units across
        constexpr float sMapRange = 6000.f; // from the top of the map down
        constexpr int sMapUnit = 8; // the terrain uses the first few, OpenMW's own global ones are at the top
        constexpr int sPrintUnit = 9;
        constexpr int sGroundSize = 512;
        constexpr float sGroundArea = 1024.f;
        constexpr int sPrintSize = 1024; // A3_PRINT_MAP_SIZE: a unit a texel
        constexpr float sPrintArea = 1024.f;
        constexpr float sPrintRebaseAt = 1200.f, sPrintRebaseBy = 600.f; // its times stay small (16 bit floats)
        constexpr int sDripCells = 40;
        constexpr float sDripSpacing = 20.f;
        constexpr float sDripDistance = 700.f;
        constexpr float sStride = 44.f; // the player's steps in first person
        constexpr int sDepthSortedBin = 10;
        // OpenMW's VisMask: actors, objects, statics (object paging too), and the groups they are under
        constexpr unsigned sMapCullMask = (1u << 3) | (1u << 10) | (1u << 11) | (1u << 14) | (1u << 19);
        constexpr unsigned sGroundCullMask = (1u << 8) | (1u << 14) | (1u << 19); // the terrain
        constexpr unsigned sContactCullMask = (1u << 3) | (1u << 4) | (1u << 14) | (1u << 19); // actors, the player

        constexpr auto sForce = osg::StateAttribute::ON | osg::StateAttribute::OVERRIDE | osg::StateAttribute::PROTECTED;
        constexpr auto sForceOff
            = osg::StateAttribute::OFF | osg::StateAttribute::OVERRIDE | osg::StateAttribute::PROTECTED;

        constexpr const char* sMapVertex = R"(#version 120
varying float a3Height;
void main()
{
    vec4 view = gl_ModelViewMatrix * gl_Vertex;
    // looking straight down from the top of the range: view z 0 at the top, -range at the bottom
    a3Height = view.z;
    gl_Position = gl_ProjectionMatrix * view;
}
)";

        constexpr const char* sMapFragment = R"(#version 120
uniform float a3RainRange;
varying float a3Height;
void main()
{
    float h = clamp(1.0 + a3Height / a3RainRange, 0.004, 1.0);
    gl_FragColor = vec4(h, 0.0, 0.0, 1.0);
}
)";

        // the ground map: the terrain's height less a3GroundArea.w (a3GroundShift: the top less it)
        constexpr const char* sGroundFragment = R"(#version 120
uniform float a3GroundShift;
varying float a3Height;
void main()
{
    gl_FragColor = vec4(a3Height + a3GroundShift, 0.0, 0.0, 1.0);
}
)";

        // footprints: what is drawn at the ground marks the time (a3StampValue)
        constexpr const char* sContactVertex = R"(#version 120
uniform vec3 a3ContactOrigin; // the camera's middle (xy) and top (z)
varying vec3 a3World;
void main()
{
    vec4 view = gl_ModelViewMatrix * gl_Vertex;
    a3World = vec3(a3ContactOrigin.xy + view.xy, a3ContactOrigin.z + view.z);
    gl_Position = gl_ProjectionMatrix * view;
}
)";

        constexpr const char* sContactFragment = R"(#version 120
uniform sampler2D a3GroundMap;
uniform vec4 a3GroundArea;
uniform float a3StampValue;
varying vec3 a3World;
void main()
{
    vec2 uv = (a3World.xy - a3GroundArea.xy) / a3GroundArea.z;
    if (uv.x <= 0.0 || uv.y <= 0.0 || uv.x >= 1.0 || uv.y >= 1.0)
        discard;
    float ground = texture2D(a3GroundMap, uv).r;
    if (ground < -9000.0)
        discard;
    float above = a3World.z - (a3GroundArea.w + ground);
    if (above > 6.0 || above < -30.0)
        discard;
    gl_FragColor = vec4(a3StampValue, 0.0, 0.0, 1.0);
}
)";

        // the footprints moved along with the player (a3CopyOffset), older ones dropped (a3CopyRebase), or cleared
        constexpr const char* sCopyVertex = R"(#version 120
varying vec2 a3Uv;
void main()
{
    a3Uv = gl_Vertex.xy;
    gl_Position = vec4(gl_Vertex.xy * 2.0 - 1.0, 0.0, 1.0);
}
)";

        constexpr const char* sCopyFragment = R"(#version 120
uniform sampler2D a3CopySource;
uniform vec2 a3CopyOffset;
uniform float a3CopyRebase;
uniform float a3CopyReset;
varying vec2 a3Uv;
void main()
{
    vec2 from = a3Uv + a3CopyOffset;
    float v = 0.0;
    if (a3CopyReset < 0.5 && from.x > 0.0 && from.y > 0.0 && from.x < 1.0 && from.y < 1.0)
    {
        v = texture2D(a3CopySource, from).r;
        v = v > a3CopyRebase + 0.5 ? v - a3CopyRebase : 0.0;
    }
    gl_FragColor = vec4(v, 0.0, 0.0, 1.0);
}
)";

        osg::ref_ptr<osg::Program> makeProgram(const char* name, const std::string& vertex, const std::string& fragment)
        {
            osg::ref_ptr<osg::Program> program = new osg::Program;
            program->setName(name);
            osg::ref_ptr<osg::Shader> v = new osg::Shader(osg::Shader::VERTEX, vertex);
            v->setName(std::string(name) + ".vert");
            osg::ref_ptr<osg::Shader> f = new osg::Shader(osg::Shader::FRAGMENT, fragment);
            f->setName(std::string(name) + ".frag");
            program->addShader(v);
            program->addShader(f);
            return program;
        }

        osg::ref_ptr<osg::Texture2D> makeFloatTexture(int size)
        {
            osg::ref_ptr<osg::Texture2D> texture = new osg::Texture2D;
            texture->setTextureSize(size, size);
            texture->setInternalFormat(GL_RGBA16F_ARB);
            texture->setSourceFormat(GL_RGBA);
            texture->setSourceType(GL_HALF_FLOAT);
            texture->setFilter(osg::Texture::MIN_FILTER, osg::Texture::LINEAR);
            texture->setFilter(osg::Texture::MAG_FILTER, osg::Texture::LINEAR);
            texture->setWrap(osg::Texture::WRAP_S, osg::Texture::CLAMP_TO_EDGE);
            texture->setWrap(osg::Texture::WRAP_T, osg::Texture::CLAMP_TO_EDGE);
            return texture;
        }

        // a camera drawing into texture, before the scene
        osg::ref_ptr<osg::Camera> makeCamera(const char* name, osg::Texture2D* texture, int size, int order)
        {
            osg::ref_ptr<osg::Camera> camera = new osg::Camera;
            camera->setName(name);
            camera->setRenderOrder(osg::Camera::PRE_RENDER, order);
            camera->setRenderTargetImplementation(osg::Camera::FRAME_BUFFER_OBJECT);
            camera->attach(osg::Camera::COLOR_BUFFER, texture);
            camera->setViewport(0, 0, size, size);
            camera->setComputeNearFarMode(osg::Camera::DO_NOT_COMPUTE_NEAR_FAR);
            camera->setClearMask(0);
            return camera;
        }

        class WetGround
        {
        public:
            static WetGround& get()
            {
                static WetGround* wet = new WetGround;
                return *wet;
            }

            WetGround()
            {
                mWetness = new osg::Uniform("a3Wetness", 0.f);
                mRain = new osg::Uniform("a3Rain", 0.f);
                mArea = new osg::Uniform("a3RainArea", osg::Vec4f(0.f, 0.f, sMapArea, 0.f));
                mHeight = new osg::Uniform("a3RainHeight", osg::Vec2f(0.f, sMapRange));
                mSampler = new osg::Uniform("a3RainMap", sMapUnit);
                mPrintArea = new osg::Uniform("a3PrintArea", osg::Vec4f(0.f, 0.f, sPrintArea, 0.f));
                mPrintNow = new osg::Uniform("a3PrintNow", 0.f);
                mPrintSampler = new osg::Uniform("a3PrintMap", sPrintUnit);
                mGroundArea = new osg::Uniform("a3GroundArea", osg::Vec4f(0.f, 0.f, sGroundArea, 0.f));
                mGroundShift = new osg::Uniform("a3GroundShift", 0.f);
                mContactOrigin = new osg::Uniform("a3ContactOrigin", osg::Vec3f());
                mStampValue = new osg::Uniform("a3StampValue", 1.f);
                mCopyOffset = new osg::Uniform("a3CopyOffset", osg::Vec2f());
                mCopyReset = new osg::Uniform("a3CopyReset", 0.f);
                mCopyRebase = new osg::Uniform("a3CopyRebase", 0.f);
                mDripOrigin = new osg::Uniform("a3DripOrigin", osg::Vec2f());
                for (osg::Uniform* u : { mWetness.get(), mRain.get(), mArea.get(), mHeight.get(), mPrintArea.get(),
                         mPrintNow.get(), mGroundArea.get(), mGroundShift.get(), mContactOrigin.get(),
                         mStampValue.get(), mCopyOffset.get(), mCopyReset.get(), mCopyRebase.get(), mDripOrigin.get() })
                    u->setDataVariance(osg::Object::DYNAMIC);
            }

            bool installed() const { return mInstalled; }

            void sceneCamera(osg::Camera* camera)
            {
                if (mScene.get() != camera && !mPending.valid())
                    mPending = camera;
            }

            // the update traversal: the graph may change here
            void setRain(float precipitation, float dt)
            {
                const ExperimentalSettings& s = experimentalSettings();
                if (osg::ref_ptr<osg::Camera> pending; mPending.lock(pending))
                {
                    install(*pending, s);
                    mPending = nullptr;
                    mInstalled = true;
                }
                experimentalLiveUpdate();
                grassFireUpdate(precipitation, dt);
                if (!s.mWetGround)
                    return;
                // turned off in the menu: dry at once
                if (Settings::ShaderManager::get().getMode() == Settings::ShaderManager::Mode::Debug)
                    mOn = experimentalOptionOn("wet ground");
                if (!mOn)
                    precipitation = mWet = 0.f;
                // wet in about forty seconds of heavy rain, dry in four minutes
                else if (precipitation > 0.02f)
                    mWet = std::min(1.f, mWet + dt / 40.f * (0.3f + 0.7f * precipitation));
                else
                    mWet = std::max(0.f, mWet - dt / 240.f);
                mWetness->set(mWet);
                mRain->set(precipitation);
                mPrecipitation = precipitation;
                mTime += dt;
            }

            // the scene camera's cull, after the scene: the maps that are due, the drips
            void cull(osgUtil::CullVisitor& cv)
            {
                if (mWet <= 0.003f || !mGroundCamera || mPaused.load(std::memory_order_relaxed))
                    return;
                const osg::Vec3f eye = cv.getEyePoint();
                const bool mapReady = cullMap(cv, eye);
                cullGround(cv, eye);
                cullPrints(cv, eye);
                if (mapReady && mGroundDrawn && (mPrecipitation > 0.02f || mWet > 0.3f))
                {
                    const float spacing = sDripSpacing;
                    const osg::Vec2f origin(std::floor(eye.x() / spacing - sDripCells * 0.5f) * spacing,
                        std::floor(eye.y() / spacing - sDripCells * 0.5f) * spacing);
                    mDripOrigin->set(origin);
                    const float span = sDripCells * spacing;
                    const osg::BoundingBox box(origin.x(), origin.y(), eye.z() - 2000.f, origin.x() + span,
                        origin.y() + span, eye.z() + 2000.f);
                    if (!cv.isCulled(box))
                    {
                        cv.pushStateSet(mDripState.get());
                        cv.addDrawableAndDepth(mDrips.get(), new osg::RefMatrix(cv.getCurrentCamera()->getViewMatrix()),
                            cv.getDistanceFromEyePoint(box.center(), false));
                        cv.popStateSet();
                    }
                }
            }

            osg::Camera* scene() { return mScene.get(); }
            osg::Geode* steps() { return mSteps.get(); }

        private:
            // the height of things, when it is due; true when it is there to use
            bool cullMap(osgUtil::CullVisitor& cv, const osg::Vec3f& eye)
            {
                if (!mMapCamera)
                    return false;
                // turned off in the menu: nothing is sheltered, and the map is drawn afresh when it is turned on
                if (!wetDryUnderOn())
                {
                    if (mDrawn)
                        mArea->set(osg::Vec4f(0.f, 0.f, sMapArea, 0.f));
                    mDrawn = false;
                    return false;
                }
                const float texel = sMapArea / sMapSize;
                const osg::Vec2f centre(std::floor(eye.x() / texel) * texel, std::floor(eye.y() / texel) * texel);
                // every two seconds, or eight while the phone is hot (thermal.cpp)
                const float every = thermalPressure() >= 2 ? 8.f : 2.f;
                if (mDrawn && (centre - mCentre).length() < 128.f && mTime - mDrawnAt < every)
                    return true;
                mDrawn = true;
                mDrawnAt = mTime;
                mCentre = centre;
                const float top = eye.z() + 2000.f;
                const float half = sMapArea * 0.5f;
                mMapCamera->setViewMatrixAsLookAt(osg::Vec3d(centre.x(), centre.y(), top),
                    osg::Vec3d(centre.x(), centre.y(), top - 1.0), osg::Vec3d(0.0, 1.0, 0.0));
                mMapCamera->setProjectionMatrixAsOrtho(-half, half, -half, half, 0.0, sMapRange);
                mArea->set(osg::Vec4f(centre.x() - half, centre.y() - half, sMapArea, 1.f));
                mHeight->set(osg::Vec2f(top - sMapRange, sMapRange));
                mMapCamera->accept(cv);
                return true;
            }

            // the terrain's height around the player, when it has moved or every few seconds
            void cullGround(osgUtil::CullVisitor& cv, const osg::Vec3f& eye)
            {
                const osg::Vec2f centre(std::floor(eye.x() / 16.f) * 16.f, std::floor(eye.y() / 16.f) * 16.f);
                if (mGroundDrawn && (centre - mGroundCentre).length() < 128.f && mTime - mGroundDrawnAt < 4.f)
                    return;
                mGroundDrawn = true;
                mGroundDrawnAt = mTime;
                mGroundCentre = centre;
                const float base = std::round(eye.z());
                const float top = eye.z() + 2000.f;
                const float half = sGroundArea * 0.5f;
                mGroundCamera->setViewMatrixAsLookAt(osg::Vec3d(centre.x(), centre.y(), top),
                    osg::Vec3d(centre.x(), centre.y(), top - 1.0), osg::Vec3d(0.0, 1.0, 0.0));
                mGroundCamera->setProjectionMatrixAsOrtho(-half, half, -half, half, 0.0, sMapRange);
                mGroundShift->set(top - base);
                mGroundArea->set(osg::Vec4f(centre.x() - half, centre.y() - half, sGroundArea, base));
                mGroundCamera->accept(cv);
            }

            // the footprints: moved along when the player is far from their middle, then what stands on the ground now
            void cullPrints(osgUtil::CullVisitor& cv, const osg::Vec3f& eye)
            {
                if (!mGroundDrawn)
                    return;
                const osg::Vec2f centre(std::floor(eye.x() / 16.f) * 16.f, std::floor(eye.y() / 16.f) * 16.f);
                float now = mTime - mPrintBase;
                if (!mPrintsMade)
                {
                    mPrintsMade = true;
                    mPrintCentre = centre;
                    mCopyReset->set(1.f);
                    mCopyBack->accept(cv);
                }
                else if ((centre - mPrintCentre).length() >= 256.f || now > sPrintRebaseAt)
                {
                    const float rebase = now > sPrintRebaseAt ? sPrintRebaseBy : 0.f;
                    mPrintBase += rebase;
                    now -= rebase;
                    mCopyReset->set(0.f);
                    mCopyRebase->set(rebase);
                    mCopyOffset->set((centre - mPrintCentre) / sPrintArea);
                    mPrintCentre = centre;
                    mCopyAway->accept(cv);
                    mCopyBack->accept(cv);
                }
                else
                    mCopyReset->set(0.f);
                const float half = sPrintArea * 0.5f;
                mPrintArea->set(osg::Vec4f(mPrintCentre.x() - half, mPrintCentre.y() - half, sPrintArea, 1.f));
                mPrintNow->set(now + 1.f);
                // the ground has to be wet enough to take them; every other frame while the phone is hot
                if (mWet < 0.08f || (thermalPressure() >= 2 && (cv.getTraversalNumber() & 1)))
                    return;
                firstPersonSteps(cv, eye);
                const float top = eye.z() + 2000.f;
                mContactCamera->setViewMatrixAsLookAt(osg::Vec3d(mPrintCentre.x(), mPrintCentre.y(), top),
                    osg::Vec3d(mPrintCentre.x(), mPrintCentre.y(), top - 1.0), osg::Vec3d(0.0, 1.0, 0.0));
                mContactCamera->setProjectionMatrixAsOrtho(-half, half, -half, half, 0.0, sMapRange);
                mContactOrigin->set(osg::Vec3f(mPrintCentre, top));
                mStampValue->set(now + 1.f);
                mContactCamera->accept(cv);
            }

            // The player in first person has no legs drawn: a step where they walk, every stride, left and right
            void firstPersonSteps(osgUtil::CullVisitor& cv, const osg::Vec3f& eye)
            {
                mStepsGeometry->setVertexArray(new osg::Vec3Array);
                mStepsDraw->setCount(0);
                osg::Vec3f player;
                if (!playerPosition(player))
                    return;
                const osg::Vec2f flat(player.x(), player.y());
                const bool firstPerson = (osg::Vec2f(eye.x(), eye.y()) - flat).length() < 30.f
                    && eye.z() - player.z() > 60.f && eye.z() - player.z() < 160.f;
                const float climb = player.z() - mLastPlayer.z();
                const osg::Vec2f moved = flat - mLastStep;
                mLastPlayer = player;
                if (!firstPerson || std::abs(climb) > 20.f)
                {
                    mLastStep = flat;
                    return;
                }
                if (moved.length() < sStride)
                    return;
                osg::Vec2f forward = moved / moved.length();
                const osg::Vec2f right(forward.y(), -forward.x());
                mLeft = !mLeft;
                const osg::Vec2f at = flat + right * (mLeft ? -5.5f : 5.5f);
                mLastStep = flat;
                // a sole: the heel and the ball of the foot
                osg::ref_ptr<osg::Vec3Array> vertices = new osg::Vec3Array;
                const auto ellipse = [&](float along, float rx, float ry) {
                    const osg::Vec2f c = at + forward * along;
                    constexpr int segments = 10;
                    for (int i = 0; i < segments; ++i)
                    {
                        const float a0 = i * 6.2831853f / segments, a1 = (i + 1) * 6.2831853f / segments;
                        const osg::Vec2f p0 = c + right * (std::cos(a0) * rx) + forward * (std::sin(a0) * ry);
                        const osg::Vec2f p1 = c + right * (std::cos(a1) * rx) + forward * (std::sin(a1) * ry);
                        vertices->push_back(osg::Vec3f(c, player.z()));
                        vertices->push_back(osg::Vec3f(p0, player.z()));
                        vertices->push_back(osg::Vec3f(p1, player.z()));
                    }
                };
                ellipse(-3.4f, 2.3f, 2.6f);
                ellipse(2.4f, 2.8f, 4.0f);
                mStepsDraw->setCount(static_cast<GLsizei>(vertices->size()));
                mStepsGeometry->setVertexArray(vertices);
                mStepsGeometry->dirtyBound();
                (void)cv;
            }

            // OpenMW's playerPos uniform, on the scene's root (its shared uniforms, double buffered)
            bool playerPosition(osg::Vec3f& position)
            {
                osg::Camera* scene = mScene.get();
                if (scene == nullptr)
                    return false;
                for (unsigned i = 0; i < scene->getNumChildren(); ++i)
                {
                    const osg::StateSet* state = scene->getChild(i)->getStateSet();
                    if (const osg::Uniform* u = state ? state->getUniform("playerPos") : nullptr)
                        return u->get(position);
                }
                return false;
            }

            void install(osg::Camera& camera, const ExperimentalSettings& s)
            {
                mScene = &camera;
                osg::StateSet* state = camera.getOrCreateStateSet();
                experimentalLiveInstall(*state);
                grassFireInstall(*state);
                if (!s.mWetGround)
                    return;
                for (osg::Uniform* u : { mWetness.get(), mRain.get(), mArea.get(), mHeight.get(), mSampler.get(),
                         mPrintArea.get(), mPrintNow.get(), mPrintSampler.get() })
                    state->addUniform(u);
                makeGroundAndPrints();
                state->setTextureAttribute(sPrintUnit, mPrints[0].get(), osg::StateAttribute::ON);
                if (s.mWetDryUnder)
                {
                    makeMapCamera();
                    state->setTextureAttribute(sMapUnit, mMap.get(), osg::StateAttribute::ON);
                }
                camera.addCullCallback(new SceneCull);
                Log(Debug::Info) << "Experimental: wet ground on the scene camera (dry under objects "
                                 << s.mWetDryUnder << ", puddles " << s.mWetPuddles << ", drips, footprints)";
            }

            // the state of a camera drawing from above: its program, and no texture it draws into bound to be read
            static osg::StateSet* overState(osg::Camera& camera, osg::Program* program)
            {
                osg::StateSet* state = camera.getOrCreateStateSet();
                state->setAttributeAndModes(program, sForce);
                state->setMode(GL_DEPTH_TEST, sForceOff);
                state->setMode(GL_CULL_FACE, sForceOff);
                state->setTextureAttribute(sMapUnit, new osg::Texture2D, sForce);
                state->setTextureAttribute(sPrintUnit, new osg::Texture2D, sForce);
                return state;
            }

            void makeMapCamera()
            {
                mMap = makeFloatTexture(sMapSize);
                mMapCamera = makeCamera("A3RainCam", mMap.get(), sMapSize, 0);
                // levels of detail and paging chosen from the player's view, as for the shadows
                mMapCamera->setReferenceFrame(osg::Camera::ABSOLUTE_RF_INHERIT_VIEWPOINT);
                mMapCamera->setClearColor(osg::Vec4(0.f, 0.f, 0.f, 0.f));
                mMapCamera->setClearMask(GL_COLOR_BUFFER_BIT);
                mMapCamera->setCullMask(sMapCullMask);
                mMapCamera->setCullingMode(mMapCamera->getCullingMode() & ~osg::CullSettings::SMALL_FEATURE_CULLING);
                mMapCamera->setCullCallback(new SceneChildren);
                osg::StateSet* state
                    = overState(*mMapCamera, makeProgram("a3_rainmap", sMapVertex, sMapFragment));
                state->addUniform(new osg::Uniform("a3RainRange", sMapRange), sForce);
                // the highest of everything drawn into a texel
                state->setAttributeAndModes(new osg::BlendEquation(osg::BlendEquation::RGBA_MAX), sForce);
                state->setAttributeAndModes(new osg::BlendFunc(GL_ONE, GL_ONE), sForce);
                state->setMode(GL_BLEND, sForce);
            }

            void makeGroundAndPrints()
            {
                // the terrain's height
                mGround = makeFloatTexture(sGroundSize);
                mGroundCamera = makeCamera("A3GroundCam", mGround.get(), sGroundSize, -3);
                mGroundCamera->setReferenceFrame(osg::Camera::ABSOLUTE_RF_INHERIT_VIEWPOINT);
                mGroundCamera->setClearColor(osg::Vec4(-10000.f, 0.f, 0.f, 0.f));
                mGroundCamera->setClearMask(GL_COLOR_BUFFER_BIT);
                mGroundCamera->setCullMask(sGroundCullMask);
                mGroundCamera->setCullCallback(new SceneChildren);
                osg::StateSet* ground
                    = overState(*mGroundCamera, makeProgram("a3_groundmap", sMapVertex, sGroundFragment));
                ground->addUniform(mGroundShift, sForce);
                ground->setMode(GL_BLEND, sForceOff);

                // the footprints, and the copy they are moved through
                mPrints[0] = makeFloatTexture(sPrintSize);
                mPrints[1] = makeFloatTexture(sPrintSize);
                osg::ref_ptr<osg::Program> copy = makeProgram("a3_printcopy", sCopyVertex, sCopyFragment);
                const auto copyCamera = [&](const char* name, int from, int to, int order) {
                    osg::ref_ptr<osg::Camera> camera = makeCamera(name, mPrints[to].get(), sPrintSize, order);
                    camera->setReferenceFrame(osg::Camera::ABSOLUTE_RF);
                    camera->setViewMatrix(osg::Matrix::identity());
                    camera->setProjectionMatrix(osg::Matrix::identity());
                    osg::StateSet* state = overState(*camera, copy);
                    state->setMode(GL_BLEND, sForceOff);
                    state->setTextureAttribute(0, mPrints[from].get(), sForce);
                    state->addUniform(new osg::Uniform("a3CopySource", 0), sForce);
                    for (osg::Uniform* u : { mCopyOffset.get(), mCopyReset.get(), mCopyRebase.get() })
                        state->addUniform(u, sForce);
                    osg::ref_ptr<osg::Geometry> quad = new osg::Geometry;
                    quad->setName("Alpha3 print copy");
                    quad->setUseDisplayList(false);
                    quad->setUseVertexBufferObjects(true);
                    osg::ref_ptr<osg::Vec3Array> corners = new osg::Vec3Array;
                    for (const osg::Vec3f& c : { osg::Vec3f(0, 0, 0), osg::Vec3f(1, 0, 0), osg::Vec3f(1, 1, 0),
                             osg::Vec3f(0, 0, 0), osg::Vec3f(1, 1, 0), osg::Vec3f(0, 1, 0) })
                        corners->push_back(c);
                    quad->setVertexArray(corners);
                    quad->addPrimitiveSet(new osg::DrawArrays(GL_TRIANGLES, 0, 6));
                    quad->setCullingActive(false);
                    osg::ref_ptr<osg::Geode> geode = new osg::Geode;
                    geode->addDrawable(quad);
                    geode->setCullingActive(false);
                    camera->addChild(geode);
                    return camera;
                };
                // moved: into the second, then back into the first (which the terrain reads) as it is
                mCopyAway = copyCamera("A3PrintMove", 0, 1, -2);
                mCopyBack = copyCamera("A3PrintBack", 1, 0, -1);
                // (the copy back takes no offset or rebase: its own uniforms)
                osg::StateSet* back = mCopyBack->getStateSet();
                back->addUniform(new osg::Uniform("a3CopyOffset", osg::Vec2f()), sForce);
                back->addUniform(new osg::Uniform("a3CopyRebase", 0.f), sForce);

                mContactCamera = makeCamera("A3PrintCam", mPrints[0].get(), sPrintSize, 1);
                mContactCamera->setReferenceFrame(osg::Camera::ABSOLUTE_RF_INHERIT_VIEWPOINT);
                mContactCamera->setCullMask(sContactCullMask);
                mContactCamera->setCullingMode(
                    mContactCamera->getCullingMode() & ~osg::CullSettings::SMALL_FEATURE_CULLING);
                mContactCamera->setCullCallback(new ContactChildren);
                osg::StateSet* contact
                    = overState(*mContactCamera, makeProgram("a3_footprints", sContactVertex, sContactFragment));
                contact->setTextureAttribute(0, mGround.get(), sForce);
                contact->addUniform(new osg::Uniform("a3GroundMap", 0), sForce);
                for (osg::Uniform* u : { mGroundArea.get(), mContactOrigin.get(), mStampValue.get() })
                    contact->addUniform(u, sForce);
                // the newest time of each texel
                contact->setAttributeAndModes(new osg::BlendEquation(osg::BlendEquation::RGBA_MAX), sForce);
                contact->setAttributeAndModes(new osg::BlendFunc(GL_ONE, GL_ONE), sForce);
                contact->setMode(GL_BLEND, sForce);

                mStepsGeometry = new osg::Geometry;
                mStepsGeometry->setName("Alpha3 steps");
                mStepsGeometry->setDataVariance(osg::Object::DYNAMIC);
                mStepsGeometry->setUseDisplayList(false);
                mStepsGeometry->setUseVertexBufferObjects(true);
                mStepsGeometry->setVertexArray(new osg::Vec3Array);
                mStepsDraw = new osg::DrawArrays(GL_TRIANGLES, 0, 0);
                mStepsGeometry->addPrimitiveSet(mStepsDraw);
                mStepsGeometry->setCullingActive(false);
                mSteps = new osg::Geode;
                mSteps->addDrawable(mStepsGeometry);
                mSteps->setCullingActive(false);

                makeDrips();
            }

            void makeDrips()
            {
                osg::ref_ptr<osg::Vec3Array> cells = new osg::Vec3Array;
                osg::ref_ptr<osg::Vec2Array> corners = new osg::Vec2Array;
                osg::ref_ptr<osg::DrawElementsUShort> quads = new osg::DrawElementsUShort(GL_TRIANGLES);
                const float xs[4] = { -1.f, 1.f, 1.f, -1.f }, ys[4] = { -1.f, -1.f, 1.f, 1.f };
                for (int j = 0; j < sDripCells; ++j)
                    for (int i = 0; i < sDripCells; ++i)
                    {
                        const auto first = static_cast<unsigned short>(cells->size());
                        const float seed = std::fmod(static_cast<float>(i * 7919 + j * 104729) * 0.618034f, 1.f);
                        for (int k = 0; k < 4; ++k)
                        {
                            cells->push_back(osg::Vec3f(static_cast<float>(i), static_cast<float>(j), seed));
                            corners->push_back(osg::Vec2f(xs[k], ys[k]));
                        }
                        for (unsigned short index : { 0, 1, 2, 0, 2, 3 })
                            quads->push_back(first + index);
                    }
                mDrips = new osg::Geometry;
                mDrips->setName("Alpha3 drips");
                mDrips->setUseDisplayList(false);
                mDrips->setUseVertexBufferObjects(true);
                mDrips->setVertexArray(cells);
                mDrips->setTexCoordArray(0, corners, osg::Array::BIND_PER_VERTEX);
                mDrips->addPrimitiveSet(quads);
                mDrips->setCullingActive(false); // where they are is the shader's
                mDrips->setInitialBound(osg::BoundingBox(-1e6f, -1e6f, -1e6f, 1e6f, 1e6f, 1e6f));

                const std::string defines = "#version 120\n#define A3_DRIP_SPACING " + std::to_string(sDripSpacing)
                    + "\n#define A3_DRIP_DISTANCE " + std::to_string(sDripDistance) + "\n";
                const std::string source(ExperimentalSources::drips);
                mDripState = new osg::StateSet;
                mDripState->setAttributeAndModes(
                    makeProgram("a3_drips", defines + "#define A3_VERTEX\n" + source, defines + source),
                    osg::StateAttribute::ON);
                mDripState->setTextureAttribute(0, mGround.get(), osg::StateAttribute::ON);
                mDripState->addUniform(new osg::Uniform("a3GroundMap", 0));
                mDripState->addUniform(mGroundArea);
                mDripState->addUniform(mDripOrigin);
                mDripState->setAttributeAndModes(new osg::BlendFunc(GL_ONE, GL_ONE_MINUS_SRC_ALPHA), osg::StateAttribute::ON);
                mDripState->setAttributeAndModes(
                    new SceneUtil::AutoDepth(osg::Depth::LEQUAL, 0.0, 1.0, false), osg::StateAttribute::ON);
                mDripState->setMode(GL_CULL_FACE, osg::StateAttribute::OFF);
                mDripState->setRenderBinDetails(sDepthSortedBin, "DepthSortedBin");
            }

            // the scene camera's cull callback: the scene, then the maps and the drips
            struct SceneCull : public osg::NodeCallback
            {
                void operator()(osg::Node* node, osg::NodeVisitor* nv) override
                {
                    traverse(node, nv);
                    if (auto* cv = nv->asCullVisitor())
                        WetGround::get().cull(*cv);
                }
            };

            // the map cameras draw the scene camera's children
            struct SceneChildren : public osg::NodeCallback
            {
                void operator()(osg::Node*, osg::NodeVisitor* nv) override
                {
                    if (osg::Camera* scene = WetGround::get().scene())
                        for (unsigned i = 0; i < scene->getNumChildren(); ++i)
                            scene->getChild(i)->accept(*nv);
                }
            };

            // the footprints' camera: the scene camera's children, and the player's steps in first person
            struct ContactChildren : public osg::NodeCallback
            {
                void operator()(osg::Node* node, osg::NodeVisitor* nv) override
                {
                    SceneChildren()(node, nv);
                    if (osg::Geode* steps = WetGround::get().steps())
                        steps->accept(*nv);
                }
            };

            osg::ref_ptr<osg::Uniform> mWetness, mRain, mArea, mHeight, mSampler;
            osg::ref_ptr<osg::Uniform> mPrintArea, mPrintNow, mPrintSampler, mGroundArea, mGroundShift, mContactOrigin,
                mStampValue, mCopyOffset, mCopyReset, mCopyRebase, mDripOrigin;
            osg::observer_ptr<osg::Camera> mPending;
            osg::observer_ptr<osg::Camera> mScene;
            osg::ref_ptr<osg::Camera> mMapCamera, mGroundCamera, mContactCamera, mCopyAway, mCopyBack;
            osg::ref_ptr<osg::Texture2D> mMap, mGround;
            osg::ref_ptr<osg::Texture2D> mPrints[2];
            osg::ref_ptr<osg::Geode> mSteps;
            osg::ref_ptr<osg::Geometry> mStepsGeometry, mDrips;
            osg::ref_ptr<osg::DrawArrays> mStepsDraw;
            osg::ref_ptr<osg::StateSet> mDripState;
            float mWet = 0.f;
            float mPrecipitation = 0.f;
            float mTime = 0.f;
            float mDrawnAt = 0.f, mGroundDrawnAt = 0.f;
            float mPrintBase = 0.f;
            bool mDrawn = false, mGroundDrawn = false, mPrintsMade = false;
            bool mOn = true;
            bool mLeft = false;
            std::atomic<bool> mInstalled = false;
        public:
            std::atomic<bool> mPaused = false; // the benchmark's: no maps, footprints or drips
            float mLastRain = 0.f;
        private:
            osg::Vec2f mCentre, mGroundCentre, mPrintCentre, mLastStep;
            osg::Vec3f mLastPlayer;
        };
    }

    void setRain(float precipitation, float dt)
    {
        WetGround::get().mLastRain = precipitation;
        WetGround::get().setRain(precipitation, dt);
    }

    float lastRain()
    {
        return WetGround::get().mLastRain;
    }

    void wetGroundPause(bool paused)
    {
        WetGround::get().mPaused.store(paused, std::memory_order_relaxed);
    }

    void wetGroundDrawableAdded(osg::NodeVisitor& cullVisitor, osg::Drawable&)
    {
        if (WetGround::get().installed())
            return;
        auto& cv = static_cast<osgUtil::CullVisitor&>(cullVisitor);
        osg::Camera* camera = cv.getCurrentCamera();
        if (camera != nullptr && camera->getName() == sSceneCamera)
            WetGround::get().sceneCamera(camera);
    }
}
