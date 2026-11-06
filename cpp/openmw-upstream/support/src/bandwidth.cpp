// Memory bandwidth savings for tile based GPUs (Adreno, Mali, PowerVR: every phone). A tiler draws the frame a tile at
// a time in on-chip memory. Every time the engine switches the framebuffer it draws into, the tile's contents are
// stored to memory and the next framebuffer's loaded from it, unless the driver is told they are not needed. On a
// low end GPU at 1280x800 that traffic, not the shading, is most of the frame. Each part below is a setting, on by
// default:
//
// - [Water] idle ripples: OpenMW's ripple simulation (mwrender/ripples.cpp) runs two 1024x1024 RGBA16F passes every
//   frame while water is in view (measured: ~3.3 ms, 5 fps, on an Adreno 540), whether or not anything touches the
//   water. Once nothing has for sRipplesIdleAfter seconds the damping has flattened the waves to nothing: the map is
//   cleared once and the simulation waits for the next ripple. While it runs, its targets are discarded before each
//   pass (each pass writes every texel), so the tiler never loads them.
// - [Video] skip unused opaque copy: after the opaque geometry OpenMW copies the scene's colour and depth
//   (mwrender/opaqueblit.cpp, the OpaqueResolve bin) for what is drawn after it to read: water refraction, soft
//   particles, distortion, the post-processing chain's depth, frame generation's. On a tiler the copy is a flush of the
//   frame so far, an 8 MB copy and a reload. It is skipped in the frames where nothing drawn after it reads it.
// - [Video] discard unused buffers: glInvalidateFramebuffer, which nothing in OpenMW or OSG calls, for what is never
//   read again: the window's colour, depth and stencil before the post processor draws the finished scene over all of
//   it (the window's depth and stencil are never used: the scene has its own), and the first person model's depth
//   buffer once it is drawn.
#include "support.hpp"

#include <alpha3/upstream.hpp>

#include <atomic>

#include <glad/gles2.h>

#include <osg/Camera>
#include <osg/FrameStamp>
#include <osg/RenderInfo>
#include <osg/State>
#include <osg/StateSet>
#include <osgUtil/RenderBin>
#include <osgUtil/RenderLeaf>
#include <osgUtil/RenderStage>
#include <osgUtil/StateGraph>

#include <components/debug/debuglog.hpp>
#include <components/misc/constants.hpp>

#ifndef GL_DRAW_FRAMEBUFFER_BINDING
#define GL_DRAW_FRAMEBUFFER_BINDING 0x8CA6
#endif

namespace Alpha3
{
    namespace
    {
        // The waves lose about 70% of their height a second (2% a tick of the 60 Hz simulation): after 6 s a ripple
        // is less than a thousandth of what it was
        constexpr float sRipplesIdleAfter = 6.f;

        bool idleRipplesOn()
        {
            static const bool on = settingBool("Water", "idle ripples", true);
            return on;
        }

        bool skipOpaqueCopyOn()
        {
            static const bool on = settingBool("Video", "skip unused opaque copy", true);
            return on;
        }

        bool discardOn()
        {
            static const bool on = settingBool("Video", "discard unused buffers", true);
            return on;
        }

        // The post-processing chain has a technique of the user's (they may read the depth); set from the main thread
        std::atomic<bool> sChainReadsDepth{ false };
        // The frame whose opaque copy was skipped (draw thread)
        std::atomic<unsigned> sSkippedFrame{ ~0u };

        // What reads the copy declares the sampler uniforms of it in its state: the water ("opaqueColorTex",
        // "opaqueDepthTex"), soft particles ("opaqueDepthTex", "particleSize"), the distortion bin ("opaqueDepthTex")
        bool readsOpaque(const osg::StateSet* stateSet)
        {
            return stateSet != nullptr
                && (stateSet->getUniform("opaqueDepthTex") != nullptr
                    || stateSet->getUniform("opaqueColorTex") != nullptr
                    || stateSet->getUniform("particleSize") != nullptr);
        }

        bool readsOpaque(const osgUtil::StateGraph* graph)
        {
            for (; graph != nullptr; graph = graph->_parent)
                if (readsOpaque(graph->getStateSet()))
                    return true;
            return false;
        }

        bool hasLeaves(const osgUtil::RenderBin& bin)
        {
            if (!bin.getRenderLeafList().empty())
                return true;
            for (const osgUtil::StateGraph* graph : bin.getStateGraphList())
                if (!graph->_leaves.empty())
                    return true;
            for (const auto& [number, child] : bin.getRenderBinList())
                if (hasLeaves(*child))
                    return true;
            return false;
        }

        // Something in the bin (sorted by now: depth sorted bins hold their leaves in the leaf list, the others in
        // their state graphs) reads the copy
        bool binReadsOpaque(const osgUtil::RenderBin& bin)
        {
            if (readsOpaque(bin.getStateSet()) && hasLeaves(bin))
                return true;
            for (const osgUtil::RenderLeaf* leaf : bin.getRenderLeafList())
                if (readsOpaque(leaf->_parent))
                    return true;
            for (const osgUtil::StateGraph* graph : bin.getStateGraphList())
                if (!graph->_leaves.empty() && readsOpaque(graph))
                    return true;
            for (const auto& [number, child] : bin.getRenderBinList())
                if (binReadsOpaque(*child))
                    return true;
            return false;
        }
    }

    float ripplesIdleAfter()
    {
        return idleRipplesOn() ? sRipplesIdleAfter : 0.f;
    }

    void discardDrawBuffers(bool color, bool depthStencil)
    {
        if (!discardOn() || glad_glInvalidateFramebuffer == nullptr)
            return;
        GLint bound = 0;
        glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &bound);
        GLenum attachments[3];
        GLsizei count = 0;
        if (bound == 0)
        {
            // the window's own
            if (color)
                attachments[count++] = GL_COLOR;
            if (depthStencil)
            {
                attachments[count++] = GL_DEPTH;
                attachments[count++] = GL_STENCIL;
            }
        }
        else
        {
            if (color)
                attachments[count++] = GL_COLOR_ATTACHMENT0;
            if (depthStencil)
                attachments[count++] = GL_DEPTH_STENCIL_ATTACHMENT;
        }
        if (count > 0)
            glInvalidateFramebuffer(GL_DRAW_FRAMEBUFFER, count, attachments);
    }

    namespace
    {
        // The post processor's camera draws the finished scene over the whole window (OpenMW draws the scene into
        // buffers of its own), before the GUI: what the window had is not needed
        class DiscardWindow : public osg::Camera::DrawCallback
        {
        public:
            void operator()(osg::RenderInfo&) const override
            {
                GLint bound = -1;
                glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &bound);
                if (bound != 0)
                    return;
                discardDrawBuffers(true, true);
                [[maybe_unused]] static const bool logged = [] {
                    Log(Debug::Info) << "Discard unused buffers: the window's before the scene is drawn onto it";
                    return true;
                }();
            }
        };
    }

    osg::Camera::DrawCallback* discardWindowCallback()
    {
        if (!discardOn())
            return nullptr;
        static const osg::ref_ptr<DiscardWindow> callback = new DiscardWindow;
        return callback.get();
    }

    void setPostProcessingReadsDepth(bool reads)
    {
        sChainReadsDepth = reads;
    }

    bool opaqueCopyWanted(const osgUtil::RenderBin& resolveBin, osg::RenderInfo& renderInfo)
    {
        if (!skipOpaqueCopyOn())
            return true;
        // other cameras' copies (none are made: opaqueblit.cpp copies only the scene camera's) are left to OpenMW
        const osgUtil::RenderStage* stage = resolveBin.getStage();
        const osg::Camera* camera = stage != nullptr ? stage->getCamera() : nullptr;
        const osg::FrameStamp* frameStamp = renderInfo.getState()->getFrameStamp();
        if (camera == nullptr || camera->getName() != Constants::SceneCamera || frameStamp == nullptr)
            return true;
        if (sChainReadsDepth || frameGenWanted())
            return true;
        // the bins drawn after this one
        for (const auto& [number, bin] : stage->getRenderBinList())
            if (number > resolveBin.getBinNum() && binReadsOpaque(*bin))
                return true;
        sSkippedFrame = frameStamp->getFrameNumber();
        [[maybe_unused]] static const bool logged = [] {
            Log(Debug::Info) << "Skip unused opaque copy: a frame without water, soft particles or distortion";
            return true;
        }();
        return false;
    }

    bool opaqueCopySkipped(unsigned frameNumber)
    {
        return sSkippedFrame == frameNumber;
    }
}
