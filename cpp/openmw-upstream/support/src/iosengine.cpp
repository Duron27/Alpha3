// iOS: what apps/openmw/androidmain.cpp gives the support library on Android, which needs the engine's own objects.
// That file is Android's (JNI) and is not built here, so this one is compiled into the engine's library (openmw-lib, see
// ../CMakeLists.txt) instead:
// * the engine's image manager (texture release and texture streaming read texture files again through it, the texture
//   preload fills it, the door portals read through it), handed over when the library loads, as nativeInit does;
// * the Lua profiler's figures for the performance HUD's Inspect;
// * omw_frame_update (the renderingTraversals hook calls it on the main thread before each frame is drawn): this frame's
//   camera and the post processor's textures for the Metal renderer's frame generation, as androidmain.cpp's
//   submitFrameGenView. The player state androidmain.cpp also gathers there is for Android's launcher only.
#include "support.hpp"

#include <alpha3/upstream.hpp>

#include <algorithm>
#include <string>

#include <osg/Camera>
#include <osg/GraphicsContext>
#include <osg/View>

#include <components/resource/resourcesystem.hpp>
#include <components/sceneutil/depth.hpp>
#include <components/sceneutil/positionattitudetransform.hpp>

#include "apps/openmw/mwbase/environment.hpp"
#include "apps/openmw/mwbase/luamanager.hpp"
#include "apps/openmw/mwbase/statemanager.hpp"
#include "apps/openmw/mwbase/world.hpp"
#include "apps/openmw/mwrender/postprocessor.hpp"
#include "apps/openmw/mwworld/class.hpp"
#include "apps/openmw/mwworld/ptr.hpp"

namespace
{
    struct Sources
    {
        Sources()
        {
            Alpha3::setImageManagerSource([]() -> Resource::ImageManager* {
                if (!MWBase::Environment::exists())
                    return nullptr;
                Resource::ResourceSystem* resources = MWBase::Environment::get().getResourceSystem();
                return resources != nullptr ? resources->getImageManager() : nullptr;
            });
            Alpha3::setLuaProfileSource([]() -> std::string {
                if (!MWBase::Environment::exists() || MWBase::Environment::get().getLuaManager() == nullptr)
                    return {};
                return MWBase::Environment::get().getLuaManager()->formatResourceUsageStats();
            });
        }
    };
    const Sources sSources;

    // The viewer's own camera (the one the window's context draws for), which the engine has placed for this frame
    osg::Camera* viewerCamera()
    {
        for (osg::GraphicsContext* gc : osg::GraphicsContext::getAllRegisteredGraphicsContexts())
            for (osg::Camera* camera : gc->getCameras())
                if (camera->getView() != nullptr && camera->getView()->getCamera() == camera)
                    return camera;
        return nullptr;
    }

    void submitFrameGenView()
    {
        if (!Alpha3::frameGenWanted() || !MWBase::Environment::exists())
            return;
        try
        {
            const MWBase::Environment& env = MWBase::Environment::get();
            MWBase::StateManager* stateMgr = env.getStateManager();
            MWBase::World* world = env.getWorld();
            if (stateMgr == nullptr || stateMgr->getState() != MWBase::StateManager::State_Running || world == nullptr)
                return;
            MWRender::PostProcessor* postProcessor = world->getPostProcessor();
            osg::Camera* camera = viewerCamera();
            if (postProcessor == nullptr || camera == nullptr || camera->getView()->getFrameStamp() == nullptr)
                return;
            Alpha3::FrameGenView view;
            view.frame = camera->getView()->getFrameStamp()->getFrameNumber();
            view.view = camera->getViewMatrix();
            view.projection = camera->getProjectionMatrix();
            view.reversedDepth = SceneUtil::AutoDepth::isReversed();
            view.postProcessing = postProcessor->isEnabled();
            const unsigned frameId = view.frame % 2;
            view.scene = postProcessor->getTexture(MWRender::PostProcessor::Tex_Scene, frameId);
            view.depth = postProcessor->getTexture(MWRender::PostProcessor::Tex_Depth, frameId);
            view.opaqueDepth = postProcessor->getTexture(MWRender::PostProcessor::Tex_OpaqueDepth, frameId);
            // third person: the player's model goes with the camera, not with the world the camera passes
            if (!world->isFirstPerson())
            {
                const MWWorld::Ptr player = world->getPlayerPtr();
                if (!player.isEmpty() && player.getRefData().getBaseNode() != nullptr)
                {
                    const osg::Vec3f halfExtents = world->getHalfExtents(player, true);
                    view.player = halfExtents.z() > 0.f;
                    view.playerFeet = player.getRefData().getBaseNode()->getPosition();
                    view.playerHeight = halfExtents.z() * 2.0;
                    view.playerRadius = std::max(halfExtents.x(), halfExtents.y());
                }
            }
            Alpha3::frameGenSubmitView(std::move(view));
        }
        catch (...)
        {
        }
    }
}

extern "C" void omw_frame_update()
{
    submitFrameGenView();
}
