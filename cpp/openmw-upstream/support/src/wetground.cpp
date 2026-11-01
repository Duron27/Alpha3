// Wet ground of the experimental graphics (F2 menu, a3_wetground: wet ground, wet ground puddles, wet ground dry under
// objects): the terrain darkens and shines while it rains, puddles gather, and it dries slowly after
// (experimental/wetground.glsl, added to the terrain's fragment shader).
//
// The rendering manager says how hard it rains every frame (setRain, OpenMW patch 0020). The scene camera, noted as it
// culls, is given the uniforms (and the F2 menu's live values of all the experimental graphics, experimentalLive*), and a camera of its own that draws, from straight above, how high the things around
// the player reach (roofs, trees, rocks; not the terrain) into a small float texture, the highest of each texel kept.
// The ground under something higher than itself is dry. That map is drawn again only while the ground is wet, when the
// player has moved or every two seconds.
#include "support.hpp"

#include <alpha3/upstream.hpp>

#include <algorithm>
#include <atomic>
#include <cmath>
#include <string>

#include <osg/BlendEquation>
#include <osg/BlendFunc>
#include <osg/Camera>
#include <osg/Program>
#include <osg/Shader>
#include <osg/Texture2D>
#include <osg/observer_ptr>
#include <osgUtil/CullVisitor>

#include <components/debug/debuglog.hpp>
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
        // OpenMW's VisMask: actors, objects, statics (object paging too), and the groups they are under
        constexpr unsigned sMapCullMask = (1u << 3) | (1u << 10) | (1u << 11) | (1u << 14) | (1u << 19);

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
                for (osg::Uniform* u : { mWetness.get(), mRain.get(), mArea.get(), mHeight.get() })
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
                mTime += dt;
            }

            // the scene camera's cull, after the scene: the map, when it is due
            void cullMap(osgUtil::CullVisitor& cv)
            {
                if (!mMapCamera || mWet <= 0.003f)
                    return;
                // turned off in the menu: nothing is sheltered, and the map is drawn afresh when it is turned on
                if (!wetDryUnderOn())
                {
                    if (mDrawn)
                        mArea->set(osg::Vec4f(0.f, 0.f, sMapArea, 0.f));
                    mDrawn = false;
                    return;
                }
                const osg::Vec3f eye = cv.getEyePoint();
                const float texel = sMapArea / sMapSize;
                const osg::Vec2f centre(std::floor(eye.x() / texel) * texel, std::floor(eye.y() / texel) * texel);
                if (mDrawn && (centre - mCentre).length() < 128.f && mTime - mDrawnAt < 2.f)
                    return;
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
            }

            osg::Camera* scene() { return mScene.get(); }

        private:
            void install(osg::Camera& camera, const ExperimentalSettings& s)
            {
                mScene = &camera;
                osg::StateSet* state = camera.getOrCreateStateSet();
                experimentalLiveInstall(*state);
                if (!s.mWetGround)
                    return;
                for (osg::Uniform* u : { mWetness.get(), mRain.get(), mArea.get(), mHeight.get(), mSampler.get() })
                    state->addUniform(u);
                if (s.mWetDryUnder)
                {
                    makeMapCamera();
                    state->setTextureAttribute(sMapUnit, mMap.get(), osg::StateAttribute::ON);
                    camera.addCullCallback(new MapCull);
                }
                Log(Debug::Info) << "Experimental: wet ground on the scene camera (dry under objects "
                                 << s.mWetDryUnder << ", puddles " << s.mWetPuddles << ")";
            }

            void makeMapCamera()
            {
                mMap = new osg::Texture2D;
                mMap->setTextureSize(sMapSize, sMapSize);
                mMap->setInternalFormat(GL_RGBA16F_ARB);
                mMap->setSourceFormat(GL_RGBA);
                mMap->setSourceType(GL_HALF_FLOAT);
                mMap->setFilter(osg::Texture::MIN_FILTER, osg::Texture::LINEAR);
                mMap->setFilter(osg::Texture::MAG_FILTER, osg::Texture::LINEAR);
                mMap->setWrap(osg::Texture::WRAP_S, osg::Texture::CLAMP_TO_EDGE);
                mMap->setWrap(osg::Texture::WRAP_T, osg::Texture::CLAMP_TO_EDGE);

                mMapCamera = new osg::Camera;
                mMapCamera->setName("A3RainCam");
                mMapCamera->setRenderOrder(osg::Camera::PRE_RENDER);
                mMapCamera->setRenderTargetImplementation(osg::Camera::FRAME_BUFFER_OBJECT);
                mMapCamera->attach(osg::Camera::COLOR_BUFFER, mMap.get());
                // levels of detail and paging chosen from the player's view, as for the shadows
                mMapCamera->setReferenceFrame(osg::Camera::ABSOLUTE_RF_INHERIT_VIEWPOINT);
                mMapCamera->setViewport(0, 0, sMapSize, sMapSize);
                mMapCamera->setClearColor(osg::Vec4(0.f, 0.f, 0.f, 0.f));
                mMapCamera->setClearMask(GL_COLOR_BUFFER_BIT);
                mMapCamera->setComputeNearFarMode(osg::Camera::DO_NOT_COMPUTE_NEAR_FAR);
                mMapCamera->setCullMask(sMapCullMask);
                mMapCamera->setCullingMode(mMapCamera->getCullingMode() & ~osg::CullSettings::SMALL_FEATURE_CULLING);
                mMapCamera->setCullCallback(new MapChildren);

                constexpr auto force = osg::StateAttribute::ON | osg::StateAttribute::OVERRIDE | osg::StateAttribute::PROTECTED;
                constexpr auto forceOff = osg::StateAttribute::OFF | osg::StateAttribute::OVERRIDE | osg::StateAttribute::PROTECTED;
                osg::ref_ptr<osg::Program> program = new osg::Program;
                program->setName("Alpha3 rain map");
                osg::ref_ptr<osg::Shader> vertex = new osg::Shader(osg::Shader::VERTEX, sMapVertex);
                vertex->setName("a3_rainmap.vert");
                osg::ref_ptr<osg::Shader> fragment = new osg::Shader(osg::Shader::FRAGMENT, sMapFragment);
                fragment->setName("a3_rainmap.frag");
                program->addShader(vertex);
                program->addShader(fragment);
                osg::StateSet* state = mMapCamera->getOrCreateStateSet();
                state->setAttributeAndModes(program, force);
                state->addUniform(new osg::Uniform("a3RainRange", sMapRange), force);
                // the highest of everything drawn into a texel
                state->setAttributeAndModes(new osg::BlendEquation(osg::BlendEquation::RGBA_MAX), force);
                state->setAttributeAndModes(new osg::BlendFunc(GL_ONE, GL_ONE), force);
                state->setMode(GL_BLEND, force);
                state->setMode(GL_DEPTH_TEST, forceOff);
                state->setMode(GL_CULL_FACE, forceOff);
                // not the scene camera's own map, being drawn into
                state->setTextureAttribute(sMapUnit, new osg::Texture2D, force);
            }

            // the scene camera's cull callback: the scene, then the map
            struct MapCull : public osg::NodeCallback
            {
                void operator()(osg::Node* node, osg::NodeVisitor* nv) override
                {
                    traverse(node, nv);
                    if (auto* cv = nv->asCullVisitor())
                        WetGround::get().cullMap(*cv);
                }
            };

            // the map camera draws the scene camera's children
            struct MapChildren : public osg::NodeCallback
            {
                void operator()(osg::Node*, osg::NodeVisitor* nv) override
                {
                    if (osg::Camera* scene = WetGround::get().scene())
                        for (unsigned i = 0; i < scene->getNumChildren(); ++i)
                            scene->getChild(i)->accept(*nv);
                }
            };

            osg::ref_ptr<osg::Uniform> mWetness, mRain, mArea, mHeight, mSampler;
            osg::observer_ptr<osg::Camera> mPending;
            osg::observer_ptr<osg::Camera> mScene;
            osg::ref_ptr<osg::Camera> mMapCamera;
            osg::ref_ptr<osg::Texture2D> mMap;
            float mWet = 0.f;
            float mTime = 0.f;
            float mDrawnAt = 0.f;
            bool mDrawn = false;
            bool mOn = true;
            std::atomic<bool> mInstalled = false;
            osg::Vec2f mCentre;
        };
    }

    void setRain(float precipitation, float dt)
    {
        WetGround::get().setRain(precipitation, dt);
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
