#ifndef ALPHA3_SUPPORT_VULKAN_TRANSLATOR_H
#define ALPHA3_SUPPORT_VULKAN_TRANSLATOR_H

namespace osgUtil
{
    class SceneView;
}

namespace Alpha3::Vk
{
    class Context;

    // Records the frame OSG culled into the SceneView (its render stages, bins and leaves) as Vulkan commands into the
    // context's frame; presented at the swap
    void translateFrame(Context& context, osgUtil::SceneView& sceneView);
}

#endif
