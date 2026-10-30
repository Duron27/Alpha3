// iOS: [Video] resolution as a render scale. The window is made at the screen's size in points (patch ios/0004) and keeps
// it; a resolution sets the contentScaleFactor of SDL's view, so the layer under it has that many pixels. Apple's OpenGL
// ES view (SDL_uikitopenglview) then needs its renderbuffers made again, with the context current: that happens on the
// draw thread, as a graphics operation. The Metal view (ANGLE) takes the size from its layer by itself. Either way a
// window size change event follows, which the engine handles as after any resize.
#include <alpha3/upstream.hpp>

#import <UIKit/UIKit.h>

#include <algorithm>
#include <atomic>

#include <osg/GraphicsContext>
#include <osg/GraphicsThread>

#include <SDL_events.h>
#include <SDL_syswm.h>
#include <SDL_video.h>

#include <components/debug/debuglog.hpp>

namespace Alpha3
{
    namespace
    {
        std::atomic<float> sRenderScale{ 1.f };

        // The view SDL renders into: its view controller's view (the OpenGL ES one, or the Metal one ANGLE made)
        UIView* sdlView(SDL_Window* window)
        {
            SDL_SysWMinfo info;
            SDL_VERSION(&info.version);
            if (!SDL_GetWindowWMInfo(window, &info) || info.subsystem != SDL_SYSWM_UIKIT)
                return nil;
            return info.info.uikit.window.rootViewController.view;
        }

        class ApplyRenderScale : public osg::GraphicsOperation
        {
        public:
            ApplyRenderScale(UIView* view, SDL_Window* window)
                : osg::GraphicsOperation("Alpha3RenderScale", false)
                , mView(view)
                , mWindowId(SDL_GetWindowID(window))
            {
            }

            void operator()(osg::GraphicsContext*) override
            {
                // SDL_uikitopenglview: storage for the layer's new size, with the context that is current here
                SEL updateFrame = NSSelectorFromString(@"updateFrame");
                if ([mView respondsToSelector:updateFrame])
                {
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Warc-performSelector-leaks"
                    [mView performSelector:updateFrame];
#pragma clang diagnostic pop
                }

                SDL_Event event{};
                event.type = SDL_WINDOWEVENT;
                event.window.event = SDL_WINDOWEVENT_SIZE_CHANGED;
                event.window.windowID = mWindowId;
                SDL_PushEvent(&event);
            }

        private:
            UIView* mView;
            Uint32 mWindowId;
        };
    }

    float nativeScreenScale()
    {
        return static_cast<float>(UIScreen.mainScreen.nativeScale);
    }

    float renderScale()
    {
        return sRenderScale;
    }

    void setRenderResolution(SDL_Window* window, osg::GraphicsContext* context, int width, int height)
    {
        UIView* view = sdlView(window);
        if (view == nil || context == nullptr)
            return;

        // the window's long side in points against the resolution's long side, from a quarter of the screen's pixels
        // up to all of them
        int pointsWidth = 0;
        int pointsHeight = 0;
        SDL_GetWindowSize(window, &pointsWidth, &pointsHeight);
        const int points = std::max(pointsWidth, pointsHeight);
        if (points <= 0)
            return;
        const float native = nativeScreenScale();
        const float scale
            = std::clamp(static_cast<float>(std::max(width, height)) / points, native * 0.25f, native);

        if (scale == static_cast<float>(view.contentScaleFactor) && scale == sRenderScale)
            return;
        Log(Debug::Info) << "Render scale " << scale << ": " << static_cast<int>(pointsWidth * scale) << "x"
                         << static_cast<int>(pointsHeight * scale) << " (" << native << " is the screen's)";

        sRenderScale = scale;
        view.contentScaleFactor = scale;
        // The Metal view sizes its layer's drawable in layoutSubviews; the OpenGL ES view would make its renderbuffers
        // there, on this thread, so that is left to the draw thread
        if (![view respondsToSelector:NSSelectorFromString(@"updateFrame")])
        {
            [view setNeedsLayout];
            [view layoutIfNeeded];
        }
        context->add(new ApplyRenderScale(view, window));
    }
}
