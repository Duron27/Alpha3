// Point light shadows ([Shadows] point light shadows): the few point lights closest to the camera cast shadows. Each
// has a cube of depth maps around it (six 90 degree views, tiles of one depth texture) drawn every frame from the
// scene's objects, statics and actors, and the object and terrain shaders (src/experimental/pointshadows.glsl, added
// by injectPointShadowSource) shadow those lights' light with them. OpenGL ES and the per-object light lists only:
// the shaders are merged there, the lighting library is part of the shader the parts are added to.
//
// The lights are picked in the scene camera's cull (the closest in front of the camera, with some hysteresis so they
// do not swap places each frame, and faded in), each face's camera is culled right after the scene when its view can
// reach what the camera sees, and every camera that draws the scene gets the lights in its own view space.
#include "support.hpp"

#include "experimentalshaders.hpp"
#include "experimentalsources.hpp"
#include "glesdialect.hpp"

#include <alpha3/upstream.hpp>

#include <algorithm>
#include <array>
#include <atomic>
#include <cstdint>
#include <map>
#include <mutex>
#include <set>
#include <vector>

#include <osg/ClipControl>
#include <osg/CullFace>
#include <osg/Depth>
#include <osg/PolygonOffset>
#include <osg/Program>
#include <osg/Texture2D>
#include <osg/observer_ptr>
#include <osgUtil/CullVisitor>
#include <osgUtil/RenderStage>

#include <components/debug/debuglog.hpp>
#include <components/misc/constants.hpp>
#include <components/sceneutil/glextensions.hpp>
#include <components/sceneutil/lightmanager.hpp>
#include <components/sceneutil/positionattitudetransform.hpp>
#include <components/sceneutil/shadowsbin.hpp>
#include <components/shader/shadermanager.hpp>

namespace Alpha3
{
    namespace
    {
        // the most lights the shaders take; the setting is clamped to it
        constexpr int sMaxLights = 4;
        constexpr int sFaces = 6;

        struct Config
        {
            int mLights;
            int mResolution;
            int mFacesPerFrame;
            bool mDebug;
            bool mOwnObject;
            float mDistance;
            float mNear;
            // shadow map taps per shaded pixel (each a 2x2 filtered comparison): 1 or 4
            int mTaps;
            // the reflection, refraction and other cameras that draw the scene read the shadows too
            bool mOtherCameras;
            // light (the sum of its colour) below which a pixel does not look its shadow up
            float mMinLight;
            // the maps' depth: 16 (half the memory and bandwidth; a light's range is short) or 24
            int mDepthBits;
            // the scene reads the maps (false: they are still drawn, for measuring what that costs)
            bool mReceive;
            // a light is shadowed only where its light is at least this part of the sun's and ambient light (0: as far
            // as it reaches): the faces see less, the pixels beyond look nothing up
            float mContrast;
            // testing: the shaders skip the map lookups (all lit), keeping the rest
            int mNoLookup;
        };

        const Config& config()
        {
            static const Config c = [] {
                Config out;
                out.mLights = std::clamp(settingInt("Shadows", "point light shadows", 2), 0, sMaxLights);
                out.mResolution = std::clamp(settingInt("Shadows", "point light shadow resolution", 256), 64, 1024);
                out.mFacesPerFrame = std::clamp(
                    settingInt("Shadows", "point light shadow faces per frame", 2), 1, sMaxLights * sFaces);
                out.mOwnObject = settingBool("Shadows", "point light shadow from own object", true);
                out.mDebug = settingBool("Shadows", "point light shadow debug", false);
                out.mDistance = std::max(settingFloat("Shadows", "point light shadow distance", 2048.f), 1.f);
                out.mNear = std::max(settingFloat("Shadows", "point light shadow near", 16.f), 1.f);
                out.mTaps = settingInt("Shadows", "point light shadow taps", 1) >= 4 ? 4 : 1;
                out.mOtherCameras = settingBool("Shadows", "point light shadows in reflections", false);
                out.mMinLight = std::clamp(settingFloat("Shadows", "point light shadow min light", 0.01f), 0.f, 1.f);
                out.mDepthBits = settingInt("Shadows", "point light shadow depth bits", 16) >= 24 ? 24 : 16;
                out.mReceive = settingBool("Shadows", "point light shadow receive", true);
                out.mNoLookup = std::clamp(settingInt("Shadows", "point light shadow test level", 0), 0, 2);
                out.mContrast = std::clamp(settingFloat("Shadows", "point light shadow min contrast", 0.15f), 0.f, 4.f);
                return out;
            }();
            return c;
        }

        std::uint64_t sourceHash(std::string_view text)
        {
            std::uint64_t hash = 0xcbf29ce484222325ull;
            for (unsigned char ch : text)
                hash = (hash ^ ch) * 0x100000001b3ull;
            return hash;
        }

        // the lights the shaders were made for: set once the feature is installed, before anything is compiled
        std::atomic<int> sShaderLights{ 0 };

        // This frame's shadowed lights (their ids) and the views (the render stages' initial view matrices) that read
        // them, for pointShadowsReach. Cull thread only.
        // the shadowed lights: their ids and slots (rows of the atlas)
        std::vector<std::pair<int, int>> sShadowedIds;
        std::vector<const osg::RefMatrix*> sReceivingViews;
        unsigned int sReachFrame = ~0u;
        // light list state sets made, and those that read the shadows, since the last report
        unsigned int sReachAsked = 0;
        unsigned int sReachHits = 0;
        std::atomic<int> sTextureUnit{ -1 };

        // the faces: the direction each looks along and its up, as pointshadows.glsl picks them
        struct Face
        {
            osg::Vec3f mDirection;
            osg::Vec3f mUp;
        };
        const std::array<Face, sFaces> sFaceTable{ {
            { { 1, 0, 0 }, { 0, 0, 1 } },
            { { -1, 0, 0 }, { 0, 0, 1 } },
            { { 0, 1, 0 }, { 0, 0, 1 } },
            { { 0, -1, 0 }, { 0, 0, 1 } },
            { { 0, 0, 1 }, { 0, 1, 0 } },
            { { 0, 0, -1 }, { 0, 1, 0 } },
        } };

        float colourSum(const osg::Vec4f& colour)
        {
            return colour.x() + colour.y() + colour.z();
        }

        // How far the light's diffuse light (the sum of its colour, times OpenMW's attenuation: lib/light/util.glsl
        // calcAttenuation) stays above the threshold, up to its radius. The attenuation only falls with the distance.
        float lightReach(const SceneUtil::Light& light, float radius, float threshold)
        {
            const float colour = colourSum(light.getDiffuse());
            const auto lit = [&](float d) {
                const float divisor = light.getConstantAttenuation() + light.getLinearAttenuation() * d
                    + light.getQuadraticAttenuation() * d * d;
                float x = std::clamp((d / radius - 0.75f) / 0.25f, 0.f, 1.f);
                x = 1.f - x * x;
                const float fade = 1.f - x * x;
                return colour * (1.f - fade) / std::max(divisor, 1e-6f);
            };
            if (radius <= 0.f || lit(0.f) <= threshold)
                return 0.f;
            if (lit(radius) > threshold)
                return radius;
            float low = 0.f, high = radius;
            for (int i = 0; i < 16; ++i)
            {
                const float mid = (low + high) * 0.5f;
                (lit(mid) > threshold ? low : high) = mid;
            }
            return high;
        }

        // What a light's faces traverse: the scene's children, with the casting state. Inside the camera's cull, so
        // the render bin (ShadowsBin) is the face's own render stage's.
        class FaceCullCallback : public osg::NodeCallback
        {
        public:
            FaceCullCallback(osg::Group* scene, osg::StateSet* casting)
                : mScene(scene)
                , mCasting(casting)
            {
            }

            void operator()(osg::Node*, osg::NodeVisitor* nv) override
            {
                auto* cv = static_cast<osgUtil::CullVisitor*>(nv);
                osg::ref_ptr<osg::Group> scene;
                if (!mScene.lock(scene))
                    return;
                cv->pushStateSet(mCasting);
                scene->osg::Group::traverse(*nv);
                cv->popStateSet();
            }

        private:
            osg::observer_ptr<osg::Group> mScene;
            osg::ref_ptr<osg::StateSet> mCasting;
        };

        // The lights' uniforms for one camera, one set per frame parity (the draw thread may still be using the
        // previous frame's)
        struct ReceiveState
        {
            osg::ref_ptr<osg::StateSet> mStateSet;
            osg::ref_ptr<osg::Uniform> mCount;
            osg::ref_ptr<osg::Uniform> mLights;
            osg::ref_ptr<osg::Uniform> mFade;
            osg::ref_ptr<osg::Uniform> mFaceOrigins;
            osg::ref_ptr<osg::Uniform> mViewToWorld;
            osg::ref_ptr<osg::Uniform> mMinLight;
        };

        struct CameraState
        {
            std::array<ReceiveState, 2> mFrames;
            unsigned int mLastFrame = 0;
        };

        // a light's shadow slot: its row of the atlas, its six cameras
        struct Slot
        {
            osg::observer_ptr<SceneUtil::LightSource> mLight;
            // the object the light is part of (a lantern, a torch, the actor holding it), left out of its maps with
            // [Shadows] point light shadow from own object = false: a lantern's frame throws its large shadows
            osg::observer_ptr<osg::Node> mOwner;
            double mStart = 0;
            float mFade = 0;
            // world space position and the maps' far plane, this frame
            osg::Vec3f mPosition;
            float mFar = 0;
            // the threshold mFar was found with: the faces are drawn again when it changes much (dusk, a cell)
            float mFarThreshold = -1;
            std::array<osg::ref_ptr<osg::Camera>, sFaces> mCameras;
            // when each face was last drawn for this light (0: not yet) and where the light was then
            std::array<unsigned int, sFaces> mDrawn{};
            std::array<osg::Vec3f, sFaces> mDrawnAt{};
        };

        class PointShadows : public osg::NodeCallback
        {
        public:
            PointShadows(SceneUtil::LightManager& scene, Shader::ShaderManager& shaderManager, int unit,
                unsigned int castMask)
                : mScene(&scene)
                , mUnit(unit)
            {
                const Config& c = config();
                mSlots.resize(c.mLights);

                // one depth texture, a row of six faces per light (OSG has texture arrays on OpenGL ES 3 off)
                mTexture = new osg::Texture2D;
                mTexture->setTextureSize(c.mResolution * sFaces, c.mResolution * c.mLights);
                mTexture->setInternalFormat(c.mDepthBits >= 24 ? GL_DEPTH_COMPONENT24 : GL_DEPTH_COMPONENT16);
                mTexture->setSourceFormat(GL_DEPTH_COMPONENT);
                mTexture->setSourceType(c.mDepthBits >= 24 ? GL_UNSIGNED_INT : GL_UNSIGNED_SHORT);
                mTexture->setFilter(osg::Texture::MIN_FILTER, osg::Texture::LINEAR);
                mTexture->setFilter(osg::Texture::MAG_FILTER, osg::Texture::LINEAR);
                mTexture->setWrap(osg::Texture::WRAP_S, osg::Texture::CLAMP_TO_EDGE);
                mTexture->setWrap(osg::Texture::WRAP_T, osg::Texture::CLAMP_TO_EDGE);
                mTexture->setShadowComparison(true);
                mTexture->setShadowCompareFunc(osg::Texture::LEQUAL);
                mTexture->setUseHardwareMipMapGeneration(false);

                osg::ref_ptr<osg::StateSet> casting = makeCastingState(shaderManager);
                osg::ref_ptr<FaceCullCallback> faceCull = new FaceCullCallback(&scene, casting);
                for (int s = 0; s < c.mLights; ++s)
                {
                    for (int f = 0; f < sFaces; ++f)
                    {
                        osg::ref_ptr<osg::Camera> camera = new osg::Camera;
                        camera->setName("PointShadowCamera");
                        camera->setReferenceFrame(osg::Camera::ABSOLUTE_RF_INHERIT_VIEWPOINT);
                        camera->setRenderOrder(osg::Camera::PRE_RENDER, -1000);
                        camera->setRenderTargetImplementation(osg::Camera::FRAME_BUFFER_OBJECT);
                        camera->setImplicitBufferAttachmentMask(0, 0);
                        camera->attach(osg::Camera::DEPTH_BUFFER, mTexture.get());
                        // its tile: the clear is held to the viewport (OSG's scissor)
                        camera->setViewport(f * c.mResolution, s * c.mResolution, c.mResolution, c.mResolution);
                        camera->setClearMask(GL_DEPTH_BUFFER_BIT);
                        camera->setClearDepth(1.0);
                        // its own near and far, culling and mask; the rest (LOD scale, ...) from the scene camera
                        camera->setInheritanceMask(osg::CullSettings::ALL_VARIABLES
                            & ~(osg::CullSettings::COMPUTE_NEAR_FAR_MODE | osg::CullSettings::CULLING_MODE
                                | osg::CullSettings::CULL_MASK));
                        camera->setComputeNearFarMode(osg::Camera::DO_NOT_COMPUTE_NEAR_FAR);
                        camera->setCullingMode(camera->getCullingMode() & ~osg::CullSettings::SMALL_FEATURE_CULLING);
                        camera->setCullMask(castMask);
                        camera->setCullCallback(faceCull);
                        mSlots[s].mCameras[f] = camera;
                    }
                }

                // the cameras that draw the scene without the lights read none (the maps are bound only with them: the
                // faces draw into them inside the scene's state)
                mParams = new osg::Uniform("a3PointShadowParams",
                    osg::Vec4f(c.mNear, 1.f / c.mResolution, 0.0002f, 1.f / c.mLights));
                osg::StateSet* root = scene.getOrCreateStateSet();
                root->addUniform(new osg::Uniform("a3PointShadowCount", 0));
                root->addUniform(new osg::Uniform("a3PointShadowMap", unit));
            }

            void operator()(osg::Node* node, osg::NodeVisitor* nv) override
            {
                auto* cv = static_cast<osgUtil::CullVisitor*>(nv);
                osg::Camera* camera = cv->getCurrentCamera();
                const unsigned int frame = cv->getTraversalNumber();
                const osg::RefMatrix* viewMatrix = cv->getCurrentRenderStage()->getInitialViewMatrix();
                if (!camera || !viewMatrix || !(cv->getTraversalMask() & mScene->getLightingMask()))
                {
                    traverse(node, nv);
                    return;
                }

                const bool sceneCamera = camera->getName() == Constants::SceneCamera;
                if (sceneCamera)
                {
                    pickLights(*cv, *viewMatrix, frame);
                    report(*cv);
                }

                // the scene camera's own: the water's reflection and the like draw without them (a3PointShadowCount
                // is 0 at the root), unless [Shadows] point light shadows in reflections
                if (!mAnyLight || (!sceneCamera && !config().mOtherCameras))
                {
                    traverse(node, nv);
                    return;
                }

                if (sReachFrame != frame)
                {
                    sReachFrame = frame;
                    sReceivingViews.clear();
                }
                sReceivingViews.push_back(viewMatrix);
                ReceiveState& receive = receiveState(camera, frame);
                fillReceiveState(receive, *viewMatrix);
                cv->pushStateSet(receive.mStateSet);
                traverse(node, nv);
                cv->popStateSet();

                // after the scene: the faces must not draw while their texture is bound for reading
                if (sceneCamera)
                {
                    cullFaces(*cv, *viewMatrix, frame);
                    // again, with where this frame's faces were drawn from (read when the scene draws)
                    fillReceiveState(receive, *viewMatrix);
                }
            }

        private:
            osg::ref_ptr<osg::StateSet> makeCastingState(Shader::ShaderManager& shaderManager)
            {
                // OpenMW's casting programs, with a vertex shader of another name: the sun's get depth clamping
                // (src/shaders.cpp), which would flatten what is closer than the near plane onto it
                osg::ref_ptr<osg::Shader> vertex = shaderManager.getShader("shadowcasting.vert");
                osg::ref_ptr<osg::Shader> renamed = new osg::Shader(*vertex, osg::CopyOp::SHALLOW_COPY);
                renamed->setName("a3_pointshadowcasting.vert");
                const std::string gpuShader4 = SceneUtil::getGLExtensions().isGpuShader4Supported ? "1" : "0";
                SceneUtil::ShadowsBin::CastingPrograms programs;
                for (int alphaFunc = GL_NEVER; alphaFunc <= GL_ALWAYS; ++alphaFunc)
                {
                    osg::ref_ptr<osg::Program> original = shaderManager.getProgram(vertex,
                        shaderManager.getShader("shadowcasting.frag",
                            { { "alphaFunc", std::to_string(alphaFunc) }, { "alphaToCoverage", "0" },
                                { "adjustCoverage", "1" }, { "useGPUShader4", gpuShader4 } }));
                    osg::ref_ptr<osg::Program> program = shaderManager.cloneProgram(original);
                    program->removeShader(vertex);
                    program->addShader(renamed);
                    programs[alphaFunc - GL_NEVER] = program;
                }

                static const std::string binName = "A3PointShadowsBin";
                osgUtil::RenderBin::addRenderBinPrototype(binName, new SceneUtil::ShadowsBin(programs));

                osg::ref_ptr<osg::Texture2D> fallback = new osg::Texture2D;
                osg::ref_ptr<osg::Image> white = new osg::Image;
                white->allocateImage(1, 1, 1, GL_RGBA, GL_UNSIGNED_BYTE);
                *reinterpret_cast<unsigned int*>(white->data()) = 0xffffffffu;
                fallback->setImage(white);

                osg::ref_ptr<osg::StateSet> state = new osg::StateSet;
                state->setRenderBinDetails(
                    osg::StateSet::OPAQUE_BIN, binName, osg::StateSet::OVERRIDE_PROTECTED_RENDERBIN_DETAILS);
                state->setAttributeAndModes(programs[GL_ALWAYS - GL_NEVER],
                    osg::StateAttribute::ON | osg::StateAttribute::OVERRIDE);
                state->setTextureAttribute(0, fallback, osg::StateAttribute::ON);
                state->addUniform(new osg::Uniform("useDiffuseMapForShadowAlpha", true));
                state->addUniform(new osg::Uniform("alphaTestShadows", false));
                osg::ref_ptr<osg::Depth> depth = new osg::Depth;
                depth->setWriteMask(true);
                state->setAttribute(depth, osg::StateAttribute::ON | osg::StateAttribute::OVERRIDE);
                state->setAttribute(new osg::ClipControl(osg::ClipControl::LOWER_LEFT,
                                        osg::ClipControl::NEGATIVE_ONE_TO_ONE),
                    osg::StateAttribute::ON | osg::StateAttribute::OVERRIDE);
                state->setAttributeAndModes(
                    new osg::PolygonOffset(1.5f, 4.f), osg::StateAttribute::ON | osg::StateAttribute::OVERRIDE);
                // Morrowind's meshes are often one-sided
                state->setMode(GL_CULL_FACE, osg::StateAttribute::OFF | osg::StateAttribute::OVERRIDE);
                return state;
            }

            // the object's base node (OpenMW's PositionAttitudeTransform under the cell) the light hangs under
            static osg::Node* ownerOf(osg::Node* light)
            {
                osg::Node* node = light;
                for (int depth = 0; node && depth < 32; ++depth)
                {
                    if (dynamic_cast<SceneUtil::PositionAttitudeTransform*>(node))
                        return node;
                    node = node->getNumParents() > 0 ? node->getParent(0) : nullptr;
                }
                return nullptr;
            }

            // every 10 seconds: what was shadowed
            void report(osgUtil::CullVisitor& cv)
            {
                const double now = cv.getFrameStamp() ? cv.getFrameStamp()->getReferenceTime() : 0.0;
                ++mFrames;
                for (const Slot& slot : mSlots)
                    if (slot.mLight.valid() && slot.mFade > 0.f)
                        ++mLightFrames;
                if (now - mReportStart < 10.0)
                    return;
                if (mReportStart > 0.0 && mFrames > 0)
                    Log(Debug::Verbose) << "Point light shadows: " << static_cast<float>(mLightFrames) / mFrames
                                        << " lights shadowing, " << static_cast<float>(mFacesDrawn) / mFrames
                                        << " faces drawn a frame (" << mFrames << " frames), " << sReachHits << " of "
                                        << sReachAsked << " light lists read them";
                mReportStart = now;
                mFrames = mLightFrames = mFacesDrawn = 0;
                sReachAsked = sReachHits = 0;
            }

            void pickLights(osgUtil::CullVisitor& cv, const osg::RefMatrix& viewMatrix, unsigned int frame)
            {
                const Config& c = config();
                const double now = cv.getFrameStamp() ? cv.getFrameStamp()->getReferenceTime() : 0.0;
                const float multiplier = mScene->getPointLightRadiusMultiplier();
                // a copy: the culling stack grows (and moves) as cameras are culled
                osg::Polytope frustum = cv.getModelViewCullingStack().front().getFrustum();

                struct Candidate
                {
                    SceneUtil::LightSource* mLight;
                    float mScore;
                    float mDistance;
                };
                // the light a point light stands out against: the sun's (or the cell's) and the ambient
                float level = 0.f;
                if (const std::shared_ptr<SceneUtil::Light>& sun = mScene->getSunlight())
                    level = colourSum(sun->getDiffuse()) + colourSum(sun->getAmbient());
                mThreshold = std::max(c.mMinLight, c.mContrast * level);

                std::vector<Candidate> candidates;
                for (const auto& bound : mScene->getLightsInViewSpace(&cv, &viewMatrix, frame))
                {
                    SceneUtil::LightSource* source = bound.mLightSource;
                    const SceneUtil::Light* light = source->getLight(frame);
                    if (source->getEmpty() || light->getDiffuse().x() < 0.f)
                        continue;
                    const float radius = lightReach(*light, source->getRadius() * multiplier, mThreshold);
                    const float distance = bound.mViewBound.center().length();
                    if (radius <= c.mNear * 2.f || distance > c.mDistance)
                        continue;
                    if (!frustum.contains(osg::BoundingSphere(bound.mViewBound.center(), radius)))
                        continue;
                    float score = distance;
                    for (const Slot& slot : mSlots)
                        if (slot.mLight == source)
                            score *= 0.8f;
                    candidates.push_back({ source, score, distance });
                }
                const std::size_t keep = std::min<std::size_t>(candidates.size(), mSlots.size());
                std::partial_sort(candidates.begin(), candidates.begin() + keep, candidates.end(),
                    [](const Candidate& a, const Candidate& b) { return a.mScore < b.mScore; });
                candidates.resize(keep);

                // the lights that stay keep their slots, so their faces and fades carry on
                std::vector<bool> taken(mSlots.size(), false);
                std::vector<const Candidate*> newcomers;
                for (const Candidate& candidate : candidates)
                {
                    bool found = false;
                    for (std::size_t s = 0; s < mSlots.size(); ++s)
                    {
                        if (mSlots[s].mLight == candidate.mLight)
                        {
                            taken[s] = true;
                            found = true;
                            break;
                        }
                    }
                    if (!found)
                        newcomers.push_back(&candidate);
                }
                for (std::size_t s = 0; s < mSlots.size(); ++s)
                {
                    if (!taken[s])
                    {
                        mSlots[s].mLight = nullptr;
                        if (!newcomers.empty())
                        {
                            mSlots[s].mLight = newcomers.back()->mLight;
                            mSlots[s].mStart = now;
                            mSlots[s].mDrawn.fill(0);
                            mSlots[s].mFarThreshold = -1.f;
                            mSlots[s].mOwner = config().mOwnObject ? nullptr : ownerOf(mSlots[s].mLight.get());
                            newcomers.pop_back();
                        }
                    }
                }

                mAnyLight = false;
                sShadowedIds.clear();
                for (std::size_t s = 0; s < mSlots.size(); ++s)
                {
                    Slot& slot = mSlots[s];
                    osg::ref_ptr<SceneUtil::LightSource> source;
                    if (!slot.mLight.lock(source))
                    {
                        slot.mFade = 0;
                        continue;
                    }
                    const SceneUtil::Light* light = source->getLight(frame);
                    const osg::Vec4f position = light->getPosition();
                    slot.mPosition = osg::Vec3f(position.x(), position.y(), position.z());
                    // the far plane the faces were drawn with stays until the threshold moves by a quarter
                    if (slot.mFarThreshold < 0.f || std::abs(mThreshold - slot.mFarThreshold) > slot.mFarThreshold * 0.25f)
                    {
                        slot.mFar = std::max(lightReach(*light, source->getRadius() * multiplier, mThreshold),
                            c.mNear * 2.f);
                        slot.mFarThreshold = mThreshold;
                        slot.mDrawn.fill(0);
                    }
                    const float distance = (slot.mPosition * viewMatrix).length();
                    const float fadeIn = std::clamp(static_cast<float>((now - slot.mStart) / 0.3), 0.f, 1.f);
                    const float fadeOut
                        = std::clamp((c.mDistance - distance) / (c.mDistance * 0.2f), 0.f, 1.f);
                    slot.mFade = fadeIn * fadeOut;
                    mAnyLight = true;
                    if (slot.mFade > 0.f)
                        sShadowedIds.emplace_back(source->getId(), static_cast<int>(s));
                }
            }

            // Draws a few faces a frame ([Shadows] point light shadow faces per frame): each face draws the scene
            // again, and on a phone that is the draw calls' cost more than the pixels'. The rest keep what they
            // last drew. First the faces not drawn yet for their light (a light just picked, or seen from a side not
            // seen before), then those of lights that moved (a torch in a hand), then the longest waiting: lights
            // that stay put still show the actors moving around them, a few times a second.
            void cullFaces(osgUtil::CullVisitor& cv, const osg::RefMatrix& viewMatrix, unsigned int frame)
            {
                const Config& c = config();
                // a copy: accepting a camera pushes onto the culling stack, which may move it
                osg::Polytope frustum = cv.getModelViewCullingStack().front().getFrustum();
                struct Pick
                {
                    Slot* mSlot;
                    int mFace;
                    int mRank;
                    unsigned int mAge;
                };
                std::vector<Pick> picks;
                for (Slot& slot : mSlots)
                {
                    if (!slot.mLight.valid() || slot.mFade <= 0.f)
                        continue;
                    for (int f = 0; f < sFaces; ++f)
                    {
                        const Face& face = sFaceTable[f];
                        // a sphere around the face's view: when the camera cannot see into it, nothing the camera
                        // sees reads this face
                        const osg::Vec3f centre = (slot.mPosition + face.mDirection * (slot.mFar * 0.5f)) * viewMatrix;
                        if (!frustum.contains(osg::BoundingSphere(centre, slot.mFar * 1.5f)))
                            continue;
                        int rank = 2;
                        if (slot.mDrawn[f] == 0)
                            rank = 0;
                        else if ((slot.mDrawnAt[f] - slot.mPosition).length2() > 1.f)
                            rank = 1;
                        picks.push_back({ &slot, f, rank, frame - slot.mDrawn[f] });
                    }
                }
                const std::size_t count = std::min<std::size_t>(picks.size(), c.mFacesPerFrame);
                std::partial_sort(picks.begin(), picks.begin() + count, picks.end(), [](const Pick& a, const Pick& b) {
                    return a.mRank != b.mRank ? a.mRank < b.mRank : a.mAge > b.mAge;
                });
                for (std::size_t i = 0; i < count; ++i)
                {
                    Slot& slot = *picks[i].mSlot;
                    osg::ref_ptr<osg::Node> owner;
                    slot.mOwner.lock(owner);
                    const osg::Node::NodeMask ownerMask = owner ? owner->getNodeMask() : 0;
                    if (owner)
                        owner->setNodeMask(0);
                    const int f = picks[i].mFace;
                    const Face& face = sFaceTable[f];
                    osg::Camera* camera = slot.mCameras[f];
                    camera->setProjectionMatrix(
                        osg::Matrixd::perspective(90.0, 1.0, c.mNear, std::max(slot.mFar, c.mNear * 2.f)));
                    camera->setViewMatrixAsLookAt(slot.mPosition, slot.mPosition + face.mDirection, face.mUp);
                    camera->accept(cv);
                    if (owner)
                        owner->setNodeMask(ownerMask);
                    ++mFacesDrawn;
                    slot.mDrawn[f] = std::max(frame, 1u);
                    slot.mDrawnAt[f] = slot.mPosition;
                }
            }

            ReceiveState& receiveState(osg::Camera* camera, unsigned int frame)
            {
                // cameras gone or not drawing for a while leave
                if (frame != mLastPrune)
                {
                    mLastPrune = frame;
                    for (auto it = mCameras.begin(); it != mCameras.end();)
                    {
                        if (!it->first.valid() || frame - it->second.mLastFrame > 3)
                            it = mCameras.erase(it);
                        else
                            ++it;
                    }
                }
                CameraState& state = mCameras[osg::observer_ptr<osg::Camera>(camera)];
                state.mLastFrame = frame;
                ReceiveState& receive = state.mFrames[frame % 2];
                if (!receive.mStateSet)
                {
                    const int lights = static_cast<int>(mSlots.size());
                    receive.mStateSet = new osg::StateSet;
                    receive.mStateSet->setTextureAttribute(mUnit, mTexture, osg::StateAttribute::ON);
                    receive.mCount = new osg::Uniform("a3PointShadowCount", lights);
                    receive.mLights = new osg::Uniform(osg::Uniform::FLOAT_VEC4, "a3PointShadowLights", lights);
                    receive.mFade = new osg::Uniform(osg::Uniform::FLOAT, "a3PointShadowFade", lights);
                    receive.mFaceOrigins
                        = new osg::Uniform(osg::Uniform::FLOAT_VEC3, "a3PointShadowFaceOrigins", lights * sFaces);
                    receive.mViewToWorld = new osg::Uniform(osg::Uniform::FLOAT_MAT3, "a3PointShadowViewToWorld");
                    receive.mMinLight = new osg::Uniform("a3PointShadowMinLight", 0.f);
                    for (osg::Uniform* uniform :
                        { receive.mCount.get(), receive.mLights.get(), receive.mFade.get(), receive.mFaceOrigins.get(),
                            receive.mViewToWorld.get(), receive.mMinLight.get(), mParams.get() })
                        receive.mStateSet->addUniform(uniform);
                }
                return receive;
            }

            void fillReceiveState(ReceiveState& receive, const osg::RefMatrix& viewMatrix)
            {
                for (std::size_t s = 0; s < mSlots.size(); ++s)
                {
                    const Slot& slot = mSlots[s];
                    if (slot.mLight.valid() && slot.mFade > 0.f)
                    {
                        // as OpenMW puts it in the per-object lists, so the shaders find it by its position
                        const osg::Vec4f view = osg::Vec4f(slot.mPosition, 1.f) * osg::Matrixf(viewMatrix);
                        receive.mLights->setElement(s, osg::Vec4f(view.x(), view.y(), view.z(), slot.mFar));
                        receive.mFade->setElement(s, slot.mFade);
                        for (int f = 0; f < sFaces; ++f)
                        {
                            const osg::Vec3f origin = slot.mDrawn[f] != 0 ? slot.mDrawnAt[f] : slot.mPosition;
                            receive.mFaceOrigins->setElement(
                                static_cast<unsigned int>(s * sFaces + f), origin * osg::Matrixf(viewMatrix));
                        }
                    }
                    else
                    {
                        receive.mLights->setElement(s, osg::Vec4f(1e9f, 1e9f, 1e9f, 1.f));
                        receive.mFade->setElement(s, 0.f);
                    }
                }
                receive.mMinLight->set(mThreshold);
                const osg::Matrixd inverse = osg::Matrixd::inverse(viewMatrix);
                // GLSL's m * v is OSG's v * m: the inverse view's rotation, as it is
                receive.mViewToWorld->set(osg::Matrix3(inverse(0, 0), inverse(0, 1), inverse(0, 2), inverse(1, 0),
                    inverse(1, 1), inverse(1, 2), inverse(2, 0), inverse(2, 1), inverse(2, 2)));
            }

            osg::observer_ptr<SceneUtil::LightManager> mScene;
            int mUnit;
            osg::ref_ptr<osg::Texture2D> mTexture;
            osg::ref_ptr<osg::Uniform> mParams;
            std::vector<Slot> mSlots;
            bool mAnyLight = false;
            float mThreshold = 0.f;
            double mReportStart = 0.0;
            unsigned int mFrames = 0;
            unsigned int mLightFrames = 0;
            unsigned int mFacesDrawn = 0;
            std::map<osg::observer_ptr<osg::Camera>, CameraState> mCameras;
            unsigned int mLastPrune = ~0u;
        };
    }

    void installPointShadows(
        SceneUtil::LightManager& sceneRoot, Shader::ShaderManager& shaderManager, int textureUnit, unsigned int castMask)
    {
        const Config& c = config();
        if (c.mLights <= 0)
            return;
        if (glslTarget() != GlslTarget::Gles)
        {
            Log(Debug::Info) << "Point light shadows: OpenGL ES only, off";
            return;
        }
        if (sceneRoot.getClusteredLighting())
        {
            Log(Debug::Info) << "Point light shadows: not with clustered lighting, off";
            return;
        }
        sTextureUnit = textureUnit;
        sShaderLights = c.mLights;
        sceneRoot.addCullCallback(new PointShadows(sceneRoot, shaderManager, textureUnit, castMask));
        Log(Debug::Info) << "Point light shadows: " << c.mLights << " lights, " << c.mResolution << "x"
                         << c.mResolution << " maps (" << c.mDepthBits << " bit depth, "
                         << c.mResolution * sFaces * c.mResolution * c.mLights * (c.mDepthBits >= 24 ? 4 : 2) / 1024
                         << " KiB), " << c.mTaps << (c.mTaps == 1 ? " tap" : " taps") << ", out to " << c.mDistance
                         << " units, texture unit " << textureUnit << (c.mOtherCameras ? ", in reflections" : "");
    }

    int pointShadowLights()
    {
        return config().mLights;
    }

    void pointShadowListState(const osg::RefMatrix* viewMatrix, const std::vector<int>& lightIds, osg::StateSet& state)
    {
        ++sReachAsked;
        if (sShaderLights <= 0 || sShadowedIds.empty() || !config().mReceive
            || std::find(sReceivingViews.begin(), sReceivingViews.end(), viewMatrix) == sReceivingViews.end())
            return;
        // the list's shadowed lights, in its order (the closest first): their slots
        int slots[sMaxLights] = { 0, 0, 0, 0 };
        int count = 0;
        for (std::size_t i = 0; i < lightIds.size(); ++i)
        {
            for (const auto& [shadowedId, slot] : sShadowedIds)
            {
                if (shadowedId == lightIds[i] && count < sMaxLights)
                    slots[count++] = slot;
            }
        }
        if (count == 0)
            return;
        ++sReachHits;
        state.setDefine("A3_PS_ON", std::to_string(count));
        osg::ref_ptr<osg::Uniform> uniform = new osg::Uniform(osg::Uniform::INT_VEC4, "a3PointShadowSlots");
        uniform->set(slots[0], slots[1], slots[2], slots[3]);
        state.addUniform(uniform);
    }

    bool injectPointShadows(const osg::Shader& shader, std::string& source)
    {
        const int lights = sShaderLights;
        if (lights <= 0 || (shader.getType() != osg::Shader::VERTEX && shader.getType() != osg::Shader::FRAGMENT))
            return false;
        static const MissingAnchor missing = [](std::string_view name, std::string_view anchor) {
            static std::mutex mutex;
            static std::set<std::string> logged;
            std::lock_guard lock(mutex);
            if (logged.insert(std::string(name) + "|" + std::string(anchor)).second)
                Log(Debug::Warning) << "Point light shadows: '" << anchor << "' not found in " << name
                                    << ", left out of it";
        };
        const Config& c = config();
        return injectPointShadowSource(shader.getName(), shader.getType() == osg::Shader::VERTEX, lights,
            ExperimentalSources::pointShadows, source, missing, c.mDebug, c.mNoLookup ? -c.mNoLookup + 1 : c.mTaps, c.mMinLight);
    }

    int pointShadowTextureUnit()
    {
        return sShaderLights > 0 ? sTextureUnit.load() : -1;
    }

    std::string pointShadowsShaderStamp()
    {
        const int lights = sShaderLights;
        if (lights <= 0)
            return "ps:0";
        const Config& c = config();
        return "ps:" + std::to_string(lights) + (c.mDebug ? "d:" : ":") + "t" + std::to_string(c.mNoLookup ? -c.mNoLookup + 1 : c.mTaps) + ":m"
            + std::to_string(c.mMinLight) + ":" + std::to_string(sourceHash(ExperimentalSources::pointShadows));
    }
}
