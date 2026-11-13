#ifndef ALPHA3_SUPPORT_METAL_TRANSLATOR_H
#define ALPHA3_SUPPORT_METAL_TRANSLATOR_H

namespace osgUtil
{
    class SceneView;
}

namespace Alpha3::Mtl
{
    class Context;

    // Records the frame OSG culled into the SceneView (its render stages, bins and leaves) as Metal commands into the
    // context's frame; presented at the swap
    void translateFrame(Context& context, osgUtil::SceneView& sceneView);
}

#endif
