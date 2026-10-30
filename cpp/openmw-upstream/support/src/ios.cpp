// iOS stand-ins for the parts of the support library that only exist on Android: Swappy frame pacing and the frame times
// for the launcher (framepacing.cpp), frame generation (framegen.cpp), Mesa Zink (zink.cpp), mimalloc (allocator.cpp) and
// the player state androidmain.cpp hands to the app. The swap is SDL's own: Core Animation paces it to the display.
#include "support.hpp"

#include <alpha3/upstream.hpp>

#include <functional>

#include <osg/GraphicsContext>

namespace Alpha3
{
    void swapBuffers(osg::GraphicsContext& gc)
    {
        gc.swapBuffersImplementation();
    }

    void paceZinkPost() {}

    bool frameGenRequested()
    {
        return false;
    }

    bool frameGenSwap(osg::GraphicsContext&, int, int, bool, const std::function<void(int64_t)>&)
    {
        return false;
    }

    bool frameGenWanted()
    {
        return false;
    }

    void frameGenSubmitView(FrameGenView) {}

    osg::Camera::DrawCallback* frameGenPreGuiCapture()
    {
        return nullptr;
    }

    void zinkClipPlane(unsigned int, const double*) {}

    // libc++ is a shared library on iOS, so operator new cannot be replaced for the engine alone (memory libc++ grew
    // would come back to our delete): the system allocator stays
    void applyAllocatorSetting() {}
}

// apps/openmw/androidmain.cpp on Android: the player state the launcher polls. The iOS launcher does not read it.
extern "C" void omw_frame_update() {}
