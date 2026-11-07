// The main camera's renderer under Vulkan: OSG's osgViewer::Renderer, culling as always, with the draw of the culled
// frame (SceneView::draw, OpenGL) replaced by the translator recording it as Vulkan commands (translator.cpp). The
// rest is OSG's own draw() and cull_draw(), so the viewer's threading (the SceneViews passed between the cull and the
// draw thread) and statistics stay as they are.
#include "../support.hpp"

#include "context.hpp"
#include "translator.hpp"

#include <alpha3/upstream.hpp>

#include <osg/Camera>
#include <osg/State>
#include <osg/Stats>
#include <osgUtil/SceneView>
#include <osgViewer/Renderer>

#include <components/debug/debuglog.hpp>

namespace Alpha3
{
    namespace
    {
        class VulkanRenderer : public osgViewer::Renderer
        {
        public:
            explicit VulkanRenderer(osg::Camera* camera)
                : osgViewer::Renderer(camera)
            {
            }

            void draw() override
            {
                osgUtil::SceneView* sceneView = _drawQueue.takeFront();
                if (sceneView == nullptr || _done)
                    return;
                sceneView->collateReferencesToDependentCameras();
                if (_compileOnNextDraw)
                    compile();
                if (_done || _graphicsThreadDoesCull)
                    return;

                osg::Stats* stats = sceneView->getCamera()->getStats();
                osg::State* state = sceneView->getState();
                const unsigned int frameNumber = sceneView->getFrameStamp()->getFrameNumber();
                if (!_initialized)
                    initialize(state);
                state->setDynamicObjectCount(sceneView->getDynamicObjectCount());

                const osg::Timer_t beforeDraw = osg::Timer::instance()->tick();
                drawSceneView(*sceneView);
                _availableQueue.add(sceneView);
                const osg::Timer_t afterDraw = osg::Timer::instance()->tick();

                if (stats && stats->collectStats("rendering"))
                {
                    stats->setAttribute(frameNumber, "Draw traversal begin time",
                        osg::Timer::instance()->delta_s(_startTick, beforeDraw));
                    stats->setAttribute(
                        frameNumber, "Draw traversal end time", osg::Timer::instance()->delta_s(_startTick, afterDraw));
                    stats->setAttribute(frameNumber, "Draw traversal time taken",
                        osg::Timer::instance()->delta_s(beforeDraw, afterDraw));
                }
                sceneView->clearReferencesToDependentCameras();
            }

            void cull_draw() override
            {
                osgUtil::SceneView* sceneView = _sceneView[0].get();
                if (sceneView == nullptr || _done)
                    return;
                updateSceneView(sceneView);
                if (_compileOnNextDraw)
                    compile();
                osg::Stats* stats = sceneView->getCamera()->getStats();
                osg::State* state = sceneView->getState();
                const osg::FrameStamp* frameStamp = sceneView->getFrameStamp();
                const unsigned int frameNumber = frameStamp ? frameStamp->getFrameNumber() : 0;
                if (!_initialized)
                    initialize(state);

                const osg::Timer_t beforeCull = osg::Timer::instance()->tick();
                sceneView->inheritCullSettings(*(sceneView->getCamera()));
                sceneView->cull();
                const osg::Timer_t afterCull = osg::Timer::instance()->tick();
                state->setDynamicObjectCount(sceneView->getDynamicObjectCount());
                drawSceneView(*sceneView);
                const osg::Timer_t afterDraw = osg::Timer::instance()->tick();

                if (stats && stats->collectStats("rendering"))
                {
                    osg::Timer* timer = osg::Timer::instance();
                    stats->setAttribute(frameNumber, "Cull traversal begin time", timer->delta_s(_startTick, beforeCull));
                    stats->setAttribute(frameNumber, "Cull traversal end time", timer->delta_s(_startTick, afterCull));
                    stats->setAttribute(frameNumber, "Cull traversal time taken", timer->delta_s(beforeCull, afterCull));
                    stats->setAttribute(frameNumber, "Draw traversal begin time", timer->delta_s(_startTick, afterCull));
                    stats->setAttribute(frameNumber, "Draw traversal end time", timer->delta_s(_startTick, afterDraw));
                    stats->setAttribute(frameNumber, "Draw traversal time taken", timer->delta_s(afterCull, afterDraw));
                }
            }

        private:
            void drawSceneView(osgUtil::SceneView& sceneView)
            {
                osg::State* state = sceneView.getState();
                // as osgViewer::Renderer::draw: with no dynamic objects the main thread goes on at once
                if (state->getDynamicObjectCount() == 0 && state->getDynamicObjectRenderingCompletedCallback())
                    state->getDynamicObjectRenderingCompletedCallback()->completed(state);
                if (Vk::Context* context = Vk::Context::get())
                    Vk::translateFrame(*context, sceneView);
                // OSG's draw tells the main thread when the last dynamic object is drawn (it waits for that before it
                // changes the scene graph again): the translator does as each is drawn, and the ones it left out are
                // done now
                if (state->getDynamicObjectCount() > 0)
                {
                    state->setDynamicObjectCount(0);
                    if (state->getDynamicObjectRenderingCompletedCallback())
                        state->getDynamicObjectRenderingCompletedCallback()->completed(state);
                }
            }
        };
    }

    void installVulkanRenderer(osg::Camera& camera)
    {
        if (!usesVulkan())
            return;
        camera.setRenderer(new VulkanRenderer(&camera));
        Log(Debug::Info) << "Vulkan: the main camera draws through the Vulkan translator";
    }
}

// Called from OMW::Engine::createWindow (declared there with C linkage, so the engine needs no include of the library)
extern "C" void alpha3InstallVulkanRenderer(osg::Camera* camera)
{
    if (camera != nullptr)
        Alpha3::installVulkanRenderer(*camera);
}
