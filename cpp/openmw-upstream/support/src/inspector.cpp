// The performance HUD's info dump (the launcher's "Inspect" button): one report, as JSON for the launcher to show in
// tabs, of what the game has in memory, what it draws and where the GPU's time and memory bandwidth go.
//
// Asked for from the UI thread (omwInspectorRequest), made over the next frames on the draw thread:
//  1. a few frames with OSG's and OpenMW's statistics collected (turned on from the main thread, as F3 does)
//  2. one frame captured: from the end of one swap to the end of the next (frame generation's work included), every
//     framebuffer the frame draws into is a pass. OSG, OpenMW and this library call OpenGL through glad's function
//     pointers (and OSG's GLExtensions for a few), which are pointed at recorders for that frame: binds, draws, clears,
//     blits, discards and dispatches are counted per pass, and each pass is timed with GL_EXT_disjoint_timer_query.
//     The scene camera's render stages (cameras: shadows, reflections, the scene, the post processor's) and their
//     drawables are walked from the OpaqueResolve bin (bandwidth.cpp), which every frame draws.
//  3. after the frame: every texture and buffer object OSG has on the GPU (from its object managers), the context's
//     strings, limits and extensions, the statistics, the process's memory and every setting
//  4. once the timer queries have their results, the report (omwInspectorReport hands it over once).
//
// Memory traffic per pass is an estimate from what a tile GPU does: an attachment is loaded unless it was cleared or
// discarded before the first draw, and stored unless discarded after the last; blits read and write their area. Drivers
// may do better (ANGLE on Turnip skips some loads by itself), so it is an upper bound.
#include "support.hpp"

#include <alpha3/upstream.hpp>

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdio>
#include <cstdlib>
#include <fstream>
#include <map>
#include <mutex>
#include <sstream>
#include <string>
#include <string_view>
#include <tuple>
#include <vector>

#include <glad/gles2.h>

#ifndef GL_APIENTRY
#define GL_APIENTRY GLAD_API_PTR
#endif

#include <osg/BufferObject>
#include <osg/Camera>
#include <osg/ContextData>
#include <osg/FrameBufferObject>
#include <osg/GLExtensions>
#include <osg/Geometry>
#include <osg/GraphicsContext>
#include <osg/Image>
#include <osg/State>
#include <osg/Stats>
#include <osg/Texture>
#include <osgUtil/RenderBin>
#include <osgUtil/RenderLeaf>
#include <osgUtil/RenderStage>
#include <osgUtil/StateGraph>
#include <osgViewer/View>
#include <osgViewer/ViewerBase>

#include <components/debug/debuglog.hpp>
#include <components/settings/settings.hpp>

namespace Alpha3
{
    namespace
    {
        constexpr GLenum sTimeElapsed = 0x88BF; // GL_TIME_ELAPSED_EXT
        constexpr GLenum sQueryResult = 0x8866;
        constexpr GLenum sQueryResultAvailable = 0x8867;
        constexpr GLenum sGpuDisjoint = 0x8FBB;
        constexpr unsigned sWarmupFrames = 4;
        constexpr unsigned sQueryWaitFrames = 60;

        // ---- the report: tabs of sections, each a list of key/value lines or a table

        struct Cell
        {
            bool mNumber = false;
            double mValue = 0;
            std::string mText;
            Cell(double value)
                : mNumber(true)
                , mValue(value)
            {
            }
            Cell(std::string text)
                : mText(std::move(text))
            {
            }
            Cell(const char* text)
                : mText(text)
            {
            }
        };

        struct Section
        {
            std::string mTitle;
            std::string mNote;
            std::vector<std::pair<std::string, std::string>> mLines;
            std::vector<std::string> mColumns;
            std::vector<std::vector<Cell>> mRows;
            int mSort = -1; // the column it opens sorted by (descending)
        };

        struct Tab
        {
            std::string mTitle;
            std::vector<Section> mSections;
        };

        void jsonString(std::string& out, std::string_view text)
        {
            out += '"';
            for (unsigned char c : text)
            {
                switch (c)
                {
                    case '"':
                        out += "\\\"";
                        break;
                    case '\\':
                        out += "\\\\";
                        break;
                    case '\n':
                        out += "\\n";
                        break;
                    case '\t':
                        out += "\\t";
                        break;
                    case '\r':
                        break;
                    default:
                        if (c < 0x20)
                        {
                            char buffer[8];
                            std::snprintf(buffer, sizeof(buffer), "\\u%04x", c);
                            out += buffer;
                        }
                        else
                            out += static_cast<char>(c);
                }
            }
            out += '"';
        }

        void jsonNumber(std::string& out, double value)
        {
            if (!std::isfinite(value))
            {
                out += "0";
                return;
            }
            char buffer[32];
            if (value == std::floor(value) && std::abs(value) < 1e15)
                std::snprintf(buffer, sizeof(buffer), "%.0f", value);
            else
                std::snprintf(buffer, sizeof(buffer), "%.4g", value);
            out += buffer;
        }

        std::string toJson(const std::vector<Tab>& tabs)
        {
            std::string out = "{\"tabs\":[";
            for (std::size_t t = 0; t < tabs.size(); ++t)
            {
                if (t > 0)
                    out += ',';
                out += "{\"title\":";
                jsonString(out, tabs[t].mTitle);
                out += ",\"sections\":[";
                for (std::size_t s = 0; s < tabs[t].mSections.size(); ++s)
                {
                    const Section& section = tabs[t].mSections[s];
                    if (s > 0)
                        out += ',';
                    out += "{\"title\":";
                    jsonString(out, section.mTitle);
                    if (!section.mNote.empty())
                    {
                        out += ",\"note\":";
                        jsonString(out, section.mNote);
                    }
                    if (!section.mColumns.empty())
                    {
                        out += ",\"columns\":[";
                        for (std::size_t c = 0; c < section.mColumns.size(); ++c)
                        {
                            if (c > 0)
                                out += ',';
                            jsonString(out, section.mColumns[c]);
                        }
                        out += "],\"sort\":";
                        jsonNumber(out, section.mSort);
                        out += ",\"rows\":[";
                        for (std::size_t r = 0; r < section.mRows.size(); ++r)
                        {
                            if (r > 0)
                                out += ',';
                            out += '[';
                            for (std::size_t c = 0; c < section.mRows[r].size(); ++c)
                            {
                                if (c > 0)
                                    out += ',';
                                const Cell& cell = section.mRows[r][c];
                                if (cell.mNumber)
                                    jsonNumber(out, cell.mValue);
                                else
                                    jsonString(out, cell.mText);
                            }
                            out += ']';
                        }
                        out += ']';
                    }
                    else
                    {
                        out += ",\"lines\":[";
                        for (std::size_t l = 0; l < section.mLines.size(); ++l)
                        {
                            if (l > 0)
                                out += ',';
                            out += '[';
                            jsonString(out, section.mLines[l].first);
                            out += ',';
                            jsonString(out, section.mLines[l].second);
                            out += ']';
                        }
                        out += ']';
                    }
                    out += '}';
                }
                out += "]}";
            }
            out += "]}";
            return out;
        }

        std::string fixed(double value, int decimals = 1)
        {
            char buffer[32];
            std::snprintf(buffer, sizeof(buffer), "%.*f", decimals, value);
            return buffer;
        }

        std::string megabytes(double bytes)
        {
            return fixed(bytes / (1024.0 * 1024.0), 1) + " MB";
        }

        // ---- formats

        std::string formatName(GLenum format)
        {
            switch (format)
            {
                case 0x8058:
                    return "RGBA8";
                case 0x8051:
                    return "RGB8";
                case 0x881A:
                    return "RGBA16F";
                case 0x881B:
                    return "RGB16F";
                case 0x8814:
                    return "RGBA32F";
                case 0x8815:
                    return "RGB32F";
                case 0x8C3A:
                    return "R11F_G11F_B10F";
                case 0x822F:
                    return "RG16F";
                case 0x822D:
                    return "R16F";
                case 0x822E:
                    return "R32F";
                case 0x8230:
                    return "RG32F";
                case 0x8229:
                    return "R8";
                case 0x822B:
                    return "RG8";
                case 0x8C43:
                    return "SRGB8_ALPHA8";
                case 0x8059:
                    return "RGB10_A2";
                case 0x8D62:
                    return "RGB565";
                case 0x8056:
                    return "RGBA4";
                case 0x8057:
                    return "RGB5_A1";
                case 0x1908:
                    return "RGBA";
                case 0x1907:
                    return "RGB";
                case 0x1909:
                    return "LUMINANCE";
                case 0x190A:
                    return "LUMINANCE_ALPHA";
                case 0x1906:
                    return "ALPHA";
                case 0x1903:
                    return "RED";
                case 0x81A5:
                    return "DEPTH16";
                case 0x81A6:
                    return "DEPTH24";
                case 0x8CAC:
                    return "DEPTH32F";
                case 0x1902:
                    return "DEPTH";
                case 0x88F0:
                    return "DEPTH24_STENCIL8";
                case 0x8CAD:
                    return "DEPTH32F_STENCIL8";
                case 0x84F9:
                    return "DEPTH_STENCIL";
                case 0x8D48:
                    return "STENCIL8";
                case 0x83F0:
                    return "DXT1";
                case 0x83F1:
                    return "DXT1A";
                case 0x83F2:
                    return "DXT3";
                case 0x83F3:
                    return "DXT5";
                case 0x8E8C:
                    return "BC7";
                case 0x9274:
                    return "ETC2 RGB";
                case 0x9275:
                    return "ETC2 sRGB";
                case 0x9276:
                    return "ETC2 RGB A1";
                case 0x9278:
                    return "ETC2 RGBA";
                case 0x9279:
                    return "ETC2 sRGBA";
                case 0x8D64:
                    return "ETC1";
                case 0x8C00:
                case 0x8C01:
                case 0x8C02:
                case 0x8C03:
                    return "PVRTC";
                case 0x8C92:
                case 0x8C93:
                case 0x87EE:
                    return "ATC";
                case 0x9271:
                    return "EAC R11 signed";
                case 0x9273:
                    return "EAC RG11 signed";
                case 0x9277:
                    return "ETC2 sRGB A1";
                case 0x9270:
                    return "EAC R11";
                case 0x9272:
                    return "EAC RG11";
                default:
                    break;
            }
            if (format >= 0x93B0 && format <= 0x93BD)
            {
                static const char* const blocks[] = { "4x4", "5x4", "5x5", "6x5", "6x6", "8x5", "8x6", "8x8", "10x5",
                    "10x6", "10x8", "10x10", "12x10", "12x12" };
                return std::string("ASTC ") + blocks[format - 0x93B0];
            }
            if (format >= 0x93D0 && format <= 0x93DD)
                return "ASTC sRGB";
            if (format >= 0x8B90 && format <= 0x8B99)
                return "paletted";
            char buffer[16];
            std::snprintf(buffer, sizeof(buffer), "0x%04X", format);
            return buffer;
        }

        // bytes a pixel of a render target takes in memory
        double bytesPerPixel(GLenum format)
        {
            switch (format)
            {
                case 0x881A: // RGBA16F
                case 0x881B: // RGB16F (padded)
                case 0x8230: // RG32F
                case 0x8CAD: // DEPTH32F_STENCIL8 (two planes)
                    return 8;
                case 0x8814:
                case 0x8815:
                    return 16;
                case 0x822D:
                case 0x822B:
                case 0x81A5:
                case 0x8D62:
                case 0x8056:
                case 0x8057:
                    return 2;
                case 0x8229:
                case 0x8D48:
                    return 1;
                default:
                    return 4;
            }
        }

        // ---- OSG's object managers keep their lists protected: reached through member pointers formed in a subclass

        struct TextureManagerAccess : osg::TextureObjectManager
        {
            static const auto& sets(const osg::TextureObjectManager& manager)
            {
                return manager.*(&TextureManagerAccess::_textureSetMap);
            }
        };

        struct TextureSetAccess : osg::TextureObjectSet
        {
            static osg::Texture::TextureObject* head(const osg::TextureObjectSet& set)
            {
                return set.*(&TextureSetAccess::_head);
            }
        };

        struct BufferManagerAccess : osg::GLBufferObjectManager
        {
            static const auto& sets(const osg::GLBufferObjectManager& manager)
            {
                return manager.*(&BufferManagerAccess::_glBufferObjectSetMap);
            }
        };

        struct TextureInfo
        {
            std::string mName;
            std::string mKind;
            int mWidth = 0, mHeight = 0, mDepth = 0, mMips = 0;
            GLenum mFormat = 0, mTarget = 0;
            GLuint mId = 0;
            double mBytes = 0;
        };

        std::string textureName(const osg::Texture* texture)
        {
            if (texture == nullptr)
                return "";
            if (!texture->getName().empty())
                return texture->getName();
            if (texture->getNumImages() > 0)
                if (const osg::Image* image = texture->getImage(0))
                    if (!image->getFileName().empty())
                        return image->getFileName();
            return "";
        }

        std::string targetName(GLenum target)
        {
            switch (target)
            {
                case GL_TEXTURE_2D:
                    return "2D";
                case GL_TEXTURE_2D_ARRAY:
                    return "2D array";
                case GL_TEXTURE_3D:
                    return "3D";
                case GL_TEXTURE_CUBE_MAP:
                    return "cube";
                default:
                    return "other";
            }
        }

        // ---- the frame's passes

        enum : unsigned
        {
            Color = 1,
            DepthStencil = 2
        };

        struct Attachment
        {
            std::string mName;
            GLuint mTexture = 0; // its GL texture, when it is one
            int mWidth = 0, mHeight = 0;
            GLenum mFormat = 0;
            unsigned mKind = Color;
            bool mStored = true; // the window's depth and stencil are not kept after the frame
            double bytes() const { return double(mWidth) * mHeight * bytesPerPixel(mFormat); }
        };

        struct FboInfo
        {
            std::vector<Attachment> mAttachments;
            std::string mNames; // of its attachments' textures
        };

        struct Pass
        {
            GLuint mFbo = 0;
            unsigned mDraws = 0, mComputes = 0, mClears = 0, mBlits = 0, mDiscards = 0;
            double mPrimitives = 0, mBlitBytes = 0;
            double mViewportArea = 0; // the largest viewport set in it: a tiler loads and stores only that area
            bool mWrote = false;
            unsigned mClearedBefore = 0, mDiscardedBefore = 0, mDiscardedAfter = 0;
            std::vector<GLuint> mQueries;
            double mGpuMs = 0;
        };

        struct StageInfo
        {
            std::string mLabel, mOrder, mTarget;
            int mWidth = 0, mHeight = 0;
            unsigned mLeaves = 0;
            double mVertices = 0, mTriangles = 0;
        };

        struct Totals
        {
            unsigned mDrawables = 0;
            double mVertices = 0, mTriangles = 0;
        };

        double primitivesOf(GLenum mode, double count)
        {
            switch (mode)
            {
                case GL_TRIANGLES:
                    return count / 3;
                case GL_TRIANGLE_STRIP:
                case GL_TRIANGLE_FAN:
                    return std::max(0.0, count - 2);
                case GL_LINES:
                    return count / 2;
                case GL_POINTS:
                    return count;
                default:
                    return count / 3;
            }
        }

        class Inspector
        {
        public:
            enum class State
            {
                Idle,
                Requested,
                Warmup,
                Capturing,
                Waiting,
                Done
            };

            static Inspector& get()
            {
                static Inspector inspector;
                return inspector;
            }

            std::atomic<State> mState{ State::Idle };

            bool request()
            {
                State idle = State::Idle;
                if (mState.compare_exchange_strong(idle, State::Requested))
                    mRequestedFrames = 0;
                return true; // or already under way
            }

            std::string take()
            {
                std::lock_guard lock(mReportMutex);
                std::string report = std::move(mReport);
                mReport.clear();
                return report;
            }

            // main thread, before the frame's traversals
            void mainThread()
            {
                const State state = mState.load();
                if (state == State::Requested)
                {
                    osgViewer::ViewerBase* viewer = mSceneViewer.load();
                    if (viewer == nullptr && ++mRequestedFrames < 120)
                        return;
                    mViewer = viewer;
                    if (viewer != nullptr)
                        collectStats(*viewer, true);
                    mWarmup = sWarmupFrames;
                    mState = State::Warmup;
                }
                else if ((state == State::Done || state == State::Idle) && mStatsOn)
                {
                    if (mViewer != nullptr)
                        collectStats(*mViewer, false);
                    mStatsOn = false;
                }
            }

            // draw thread, at the end of each swap
            void swapped(osg::GraphicsContext& gc)
            {
                switch (mState.load())
                {
                    case State::Warmup:
                        if (mWarmup > 0)
                        {
                            --mWarmup;
                            return;
                        }
                        begin(gc);
                        mState = State::Capturing;
                        return;
                    case State::Capturing:
                        end(gc);
                        mWaitFrames = 0;
                        mState = State::Waiting;
                        return;
                    case State::Waiting:
                        if (!queriesReady() && ++mWaitFrames < sQueryWaitFrames)
                            return;
                        readQueries();
                        publish();
                        mState = State::Done;
                        return;
                    default:
                        return;
                }
            }

            // draw thread: the scene camera's stage, sorted, as the OpaqueResolve bin draws
            void sceneStage(const osgUtil::RenderStage& stage, osg::RenderInfo& renderInfo)
            {
                const State state = mState.load();
                if (state == State::Requested && stage.getCamera() != nullptr && mSceneViewer.load() == nullptr)
                    mSceneViewer = viewerOf(*const_cast<osg::Camera*>(stage.getCamera()));
                if (state != State::Capturing || mStageWalked)
                    return;
                mStageWalked = true;
                mContextId = renderInfo.getState()->getContextID();
                walkStages(const_cast<osgUtil::RenderStage&>(stage), "scene", 0);
                walkSceneBins(stage);
            }

        private:
            // ---- statistics (main thread)

            static osgViewer::ViewerBase* viewerOf(osg::Camera& camera)
            {
                auto* view = dynamic_cast<osgViewer::View*>(camera.getView());
                return view != nullptr ? view->getViewerBase() : nullptr;
            }

            void collectStats(osgViewer::ViewerBase& viewer, bool on)
            {
                static const char* const sViewer[] = { "frame_rate", "event", "update", "engine", "resource", "scene",
                    "lua", "mechanics", "physics", "script" };
                // not "gpu": OSG's own timer queries around each camera would pause the inspector's for the whole camera
                static const char* const sCamera[] = { "rendering", "scene" };
                osgViewer::ViewerBase::Cameras cameras;
                viewer.getCameras(cameras);
                if (on)
                {
                    mWasCollected.clear();
                    for (const char* c : sViewer)
                    {
                        mWasCollected.emplace_back(viewer.getViewerStats(), c, viewer.getViewerStats()->collectStats(c));
                        viewer.getViewerStats()->collectStats(c, true);
                    }
                    for (osg::Camera* camera : cameras)
                        if (osg::Stats* stats = camera->getStats())
                            for (const char* c : sCamera)
                            {
                                mWasCollected.emplace_back(stats, c, stats->collectStats(c));
                                stats->collectStats(c, true);
                            }
                    mStatsOn = true;
                }
                else
                {
                    for (auto& [stats, name, was] : mWasCollected)
                        if (osg::ref_ptr<osg::Stats> s; stats.lock(s))
                            s->collectStats(name, was);
                    mWasCollected.clear();
                }
            }

            // ---- the capture (draw thread)

            void begin(osg::GraphicsContext& gc)
            {
                mPasses.clear();
                mFbos.clear();
                mStages.clear();
                mBins.clear();
                mObjects.clear();
                mFboLabels.clear();
                mTargetNames.clear();
                mStageWalked = false;
                mWindowWidth = gc.getTraits() ? gc.getTraits()->width : 0;
                mWindowHeight = gc.getTraits() ? gc.getTraits()->height : 0;
                mContextId = gc.getState() ? gc.getState()->getContextID() : 0;
                mFrame = gc.getState() && gc.getState()->getFrameStamp()
                    ? gc.getState()->getFrameStamp()->getFrameNumber() + 1
                    : 0;
                listTextures();
                mTiming = GLAD_GL_EXT_disjoint_timer_query != 0 && glad_glGenQueriesEXT != nullptr
                    && glad_glBeginQueryEXT != nullptr && glad_glGetQueryObjectui64vEXT != nullptr;
                mOurQuery = false;
                mForeignQuery = false;
                mExt = gc.getState() ? gc.getState()->get<osg::GLExtensions>() : nullptr;
                install();
                GLint bound = 0;
                glad_glGetIntegerv(GL_DRAW_FRAMEBUFFER_BINDING, &bound);
                startPass(static_cast<GLuint>(bound));
            }

            void end(osg::GraphicsContext& gc)
            {
                stopTimer();
                uninstall();
                mGl = glInfo();
                mBuffers = listBuffers();
                if (mViewer != nullptr)
                    mStats = readStats(*mViewer);
                (void)gc;
            }

            bool queriesReady()
            {
                if (!mTiming)
                    return true;
                for (auto it = mPasses.rbegin(); it != mPasses.rend(); ++it)
                    if (!it->mQueries.empty())
                    {
                        GLuint available = 0;
                        glad_glGetQueryObjectuivEXT(it->mQueries.back(), sQueryResultAvailable, &available);
                        return available != 0;
                    }
                return true;
            }

            void readQueries()
            {
                mTimesValid = mTiming;
                if (!mTiming)
                    return;
                GLint disjoint = 0;
                glad_glGetIntegerv(sGpuDisjoint, &disjoint);
                if (disjoint != 0)
                    mTimesValid = false;
                for (Pass& pass : mPasses)
                {
                    for (GLuint query : pass.mQueries)
                    {
                        GLuint available = 0;
                        glad_glGetQueryObjectuivEXT(query, sQueryResultAvailable, &available);
                        if (available == 0)
                        {
                            mTimesValid = false;
                            continue;
                        }
                        GLuint64 ns = 0;
                        glad_glGetQueryObjectui64vEXT(query, sQueryResult, &ns);
                        pass.mGpuMs += ns / 1e6;
                    }
                    if (!pass.mQueries.empty())
                        glad_glDeleteQueriesEXT(static_cast<GLsizei>(pass.mQueries.size()), pass.mQueries.data());
                    pass.mQueries.clear();
                }
            }

            // -- timer queries: one TIME_ELAPSED at a time; another's (OSG's GPU statistics, frame generation's) pauses ours

            void startTimer()
            {
                if (!mTiming || mOurQuery || mForeignQuery || mPasses.empty())
                    return;
                GLuint query = 0;
                glad_glGenQueriesEXT(1, &query);
                if (query == 0)
                    return;
                sBeginQueryEXT(sTimeElapsed, query);
                mPasses.back().mQueries.push_back(query);
                mOurQuery = true;
            }

            void stopTimer()
            {
                if (!mOurQuery)
                    return;
                sEndQueryEXT(sTimeElapsed);
                mOurQuery = false;
            }

            void foreignBegin(GLenum target)
            {
                if (target != sTimeElapsed)
                    return;
                stopTimer();
                mForeignQuery = true;
            }

            void foreignEnd(GLenum target)
            {
                if (target != sTimeElapsed)
                    return;
                mForeignQuery = false;
                startTimer();
            }

            // -- recording

            void startPass(GLuint fbo)
            {
                stopTimer();
                Pass pass;
                pass.mFbo = fbo;
                mPasses.push_back(std::move(pass));
                if (mFbos.find(fbo) == mFbos.end())
                    mFbos[fbo] = describeBound(fbo);
                startTimer();
            }

            void bound(GLenum target, GLuint fbo)
            {
                if (target != GL_FRAMEBUFFER && target != GL_DRAW_FRAMEBUFFER)
                    return;
                if (!mPasses.empty() && mPasses.back().mFbo == fbo)
                    return;
                startPass(fbo);
            }

            static unsigned bitsOfMask(GLbitfield mask)
            {
                return ((mask & GL_COLOR_BUFFER_BIT) ? Color : 0u)
                    | ((mask & (GL_DEPTH_BUFFER_BIT | GL_STENCIL_BUFFER_BIT)) ? DepthStencil : 0u);
            }

            void cleared(GLbitfield mask)
            {
                Pass& pass = mPasses.back();
                ++pass.mClears;
                if (pass.mDraws == 0)
                    pass.mClearedBefore |= bitsOfMask(mask);
                pass.mWrote = true;
                pass.mDiscardedAfter &= ~bitsOfMask(mask);
            }

            void drew(GLenum mode, double count, double instances)
            {
                Pass& pass = mPasses.back();
                ++pass.mDraws;
                pass.mPrimitives += primitivesOf(mode, count) * std::max(1.0, instances);
                pass.mWrote = true;
                pass.mDiscardedAfter = 0;
            }

            void viewportSet(GLsizei width, GLsizei height)
            {
                Pass& pass = mPasses.back();
                pass.mViewportArea = std::max(pass.mViewportArea, double(width) * height);
            }

            void dispatched()
            {
                ++mPasses.back().mComputes;
            }

            void blitted(GLint srcX0, GLint srcY0, GLint srcX1, GLint srcY1, GLint dstX0, GLint dstY0, GLint dstX1,
                GLint dstY1, GLbitfield mask)
            {
                Pass& pass = mPasses.back();
                ++pass.mBlits;
                const double src = std::abs(double(srcX1 - srcX0) * (srcY1 - srcY0));
                const double dst = std::abs(double(dstX1 - dstX0) * (dstY1 - dstY0));
                const double perPixel
                    = ((mask & GL_COLOR_BUFFER_BIT) ? 4.0 : 0.0) + ((mask & GL_DEPTH_BUFFER_BIT) ? 4.0 : 0.0);
                pass.mBlitBytes += (src + dst) * perPixel;
                pass.mWrote = true;
                pass.mDiscardedAfter = 0;
            }

            void discarded(GLenum target, GLsizei count, const GLenum* attachments)
            {
                if (target != GL_FRAMEBUFFER && target != GL_DRAW_FRAMEBUFFER)
                    return;
                Pass& pass = mPasses.back();
                ++pass.mDiscards;
                unsigned bits = 0;
                for (GLsizei i = 0; i < count; ++i)
                {
                    const GLenum a = attachments[i];
                    if (a == GL_COLOR || (a >= GL_COLOR_ATTACHMENT0 && a <= GL_COLOR_ATTACHMENT0 + 15))
                        bits |= Color;
                    else
                        bits |= DepthStencil;
                }
                if (pass.mDraws == 0)
                    pass.mDiscardedBefore |= bits;
                else
                    pass.mDiscardedAfter |= bits;
            }

            // what the framebuffer just bound for drawing has attached (queried once per framebuffer and capture)
            FboInfo describeBound(GLuint fbo)
            {
                FboInfo info;
                if (fbo == 0)
                {
                    Attachment color;
                    color.mName = "window";
                    color.mWidth = mWindowWidth;
                    color.mHeight = mWindowHeight;
                    color.mFormat = 0x8058;
                    info.mAttachments.push_back(color);
                    Attachment depth = color;
                    depth.mFormat = 0x88F0;
                    depth.mKind = DepthStencil;
                    depth.mStored = false;
                    info.mAttachments.push_back(depth);
                    info.mNames = "window";
                    return info;
                }
                const GLenum points[] = { GL_COLOR_ATTACHMENT0, GL_COLOR_ATTACHMENT1, GL_COLOR_ATTACHMENT2,
                    GL_COLOR_ATTACHMENT3, GL_DEPTH_ATTACHMENT, GL_STENCIL_ATTACHMENT };
                GLint depthName = -1;
                for (GLenum point : points)
                {
                    GLint type = GL_NONE;
                    glad_glGetFramebufferAttachmentParameteriv(
                        GL_DRAW_FRAMEBUFFER, point, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_TYPE, &type);
                    if (type == GL_NONE)
                        continue;
                    GLint name = 0;
                    glad_glGetFramebufferAttachmentParameteriv(
                        GL_DRAW_FRAMEBUFFER, point, GL_FRAMEBUFFER_ATTACHMENT_OBJECT_NAME, &name);
                    if (point == GL_STENCIL_ATTACHMENT && name == depthName)
                        continue; // packed depth stencil, counted once
                    if (point == GL_DEPTH_ATTACHMENT)
                        depthName = name;
                    Attachment a;
                    a.mKind = (point == GL_DEPTH_ATTACHMENT || point == GL_STENCIL_ATTACHMENT) ? DepthStencil : Color;
                    if (type == GL_TEXTURE)
                    {
                        auto found = mTextureById.find(static_cast<GLuint>(name));
                        if (found != mTextureById.end())
                        {
                            const TextureInfo& t = mTextures[found->second];
                            a.mName = t.mName;
                            a.mTexture = static_cast<GLuint>(name);
                            a.mWidth = t.mWidth;
                            a.mHeight = t.mHeight;
                            a.mFormat = t.mFormat;
                        }
                        else
                            a.mName = "texture " + std::to_string(name);
                    }
                    else if (type == GL_RENDERBUFFER)
                    {
                        GLint previous = 0, width = 0, height = 0, format = 0, samples = 0;
                        glad_glGetIntegerv(GL_RENDERBUFFER_BINDING, &previous);
                        glad_glBindRenderbuffer(GL_RENDERBUFFER, static_cast<GLuint>(name));
                        glad_glGetRenderbufferParameteriv(GL_RENDERBUFFER, GL_RENDERBUFFER_WIDTH, &width);
                        glad_glGetRenderbufferParameteriv(GL_RENDERBUFFER, GL_RENDERBUFFER_HEIGHT, &height);
                        glad_glGetRenderbufferParameteriv(GL_RENDERBUFFER, GL_RENDERBUFFER_INTERNAL_FORMAT, &format);
                        glad_glGetRenderbufferParameteriv(GL_RENDERBUFFER, GL_RENDERBUFFER_SAMPLES, &samples);
                        glad_glBindRenderbuffer(GL_RENDERBUFFER, static_cast<GLuint>(previous));
                        a.mName = samples > 1 ? "renderbuffer x" + std::to_string(samples) : "renderbuffer";
                        a.mWidth = width * std::max(1, samples); // a sample costs as much as a pixel
                        a.mHeight = height;
                        a.mFormat = static_cast<GLenum>(format);
                    }
                    info.mAttachments.push_back(a);
                }
                for (const Attachment& a : info.mAttachments)
                    if (!a.mName.empty() && a.mName.rfind("texture ", 0) != 0 && a.mName.rfind("renderbuffer", 0) != 0)
                    {
                        if (!info.mNames.empty())
                            info.mNames += ", ";
                        info.mNames += a.mName;
                    }
                return info;
            }

            // ---- the scene

            static std::string targetOf(const osgUtil::RenderStage& stage, int& width, int& height)
            {
                const osg::FrameBufferObject* fbo = stage.getFrameBufferObject();
                if (fbo == nullptr)
                    return "window";
                std::string out;
                for (const auto& [component, attachment] : fbo->getAttachmentMap())
                {
                    std::string one;
                    if (const osg::Texture* texture = attachment.getTexture())
                    {
                        width = std::max(width, texture->getTextureWidth());
                        height = std::max(height, texture->getTextureHeight());
                        one = formatName(texture->getInternalFormat());
                    }
                    else if (const osg::RenderBuffer* rb = attachment.getRenderBuffer())
                    {
                        width = std::max(width, rb->getWidth());
                        height = std::max(height, rb->getHeight());
                        one = formatName(rb->getInternalFormat());
                        if (rb->getSamples() > 1)
                            one += " x" + std::to_string(rb->getSamples());
                    }
                    if (!one.empty())
                        out += (out.empty() ? "" : " + ") + one;
                }
                return out;
            }

            static void geometryOf(const osg::Drawable& drawable, double& vertices, double& triangles)
            {
                const osg::Geometry* geometry = drawable.asGeometry();
                if (geometry == nullptr)
                    return;
                if (const osg::Array* array = geometry->getVertexArray())
                    vertices += array->getNumElements();
                for (const auto& set : geometry->getPrimitiveSetList())
                    if (set != nullptr)
                        triangles += primitivesOf(set->getMode(), set->getNumIndices())
                            * std::max(1, set->getNumInstances());
            }

            template <class F>
            static void forLeaves(const osgUtil::RenderBin& bin, F&& f)
            {
                for (const osgUtil::RenderLeaf* leaf : bin.getRenderLeafList())
                    f(*leaf, bin.getBinNum());
                for (const osgUtil::StateGraph* graph : bin.getStateGraphList())
                    for (const auto& leaf : graph->_leaves)
                        f(*leaf, bin.getBinNum());
                for (const auto& [number, child] : bin.getRenderBinList())
                    forLeaves(*child, f);
            }

            void walkStages(osgUtil::RenderStage& stage, const std::string& order, int depth)
            {
                if (depth > 8)
                    return;
                for (auto& [number, pre] : stage.getPreRenderList())
                    walkStages(*pre, "before", depth + 1);

                StageInfo info;
                const osg::Camera* camera = stage.getCamera();
                info.mLabel = camera && !camera->getName().empty() ? camera->getName() : "(unnamed camera)";
                info.mOrder = order;
                if (const osg::Viewport* viewport = stage.getViewport())
                {
                    info.mWidth = static_cast<int>(viewport->width());
                    info.mHeight = static_cast<int>(viewport->height());
                }
                int w = 0, h = 0;
                info.mTarget = targetOf(stage, w, h);
                forLeaves(stage, [&](const osgUtil::RenderLeaf& leaf, int) {
                    ++info.mLeaves;
                    if (leaf._drawable)
                        geometryOf(*leaf._drawable, info.mVertices, info.mTriangles);
                });
                if (const osg::FrameBufferObject* fbo = stage.getFrameBufferObject())
                {
                    if (GLuint handle = fbo->getHandle(mContextId))
                        mFboLabels[handle] = info.mLabel;
                    for (const auto& [component, attachment] : fbo->getAttachmentMap())
                        if (const osg::Texture* texture = attachment.getTexture())
                            if (const osg::Texture::TextureObject* object = texture->getTextureObject(mContextId))
                                mTargetNames[object->id()] = info.mLabel;
                }
                if (const osg::FrameBufferObject* fbo = stage.getMultisampleResolveFramebufferObject())
                    if (GLuint handle = fbo->getHandle(mContextId))
                        mFboLabels[handle] = info.mLabel + " (resolve)";
                mStages.push_back(std::move(info));

                for (auto& [number, post] : stage.getPostRenderList())
                    walkStages(*post, "after", depth + 1);
            }

            static std::string objectOf(const osg::Drawable& drawable)
            {
                // the nearest named node above it that names a file, else the nearest named one
                std::string named;
                const osg::Node* node = &drawable;
                for (int depth = 0; depth < 24 && node != nullptr; ++depth)
                {
                    const std::string& name = node->getName();
                    if (!name.empty())
                    {
                        if (name.find(".nif") != std::string::npos || name.find(".osg") != std::string::npos
                            || name.find('/') != std::string::npos || name.find('\\') != std::string::npos)
                            return name;
                        if (named.empty())
                            named = name;
                    }
                    node = node->getNumParents() > 0 ? node->getParent(0) : nullptr;
                }
                return named.empty() ? std::string("(") + drawable.className() + ")" : named;
            }

            void walkSceneBins(const osgUtil::RenderStage& stage)
            {
                forLeaves(stage, [&](const osgUtil::RenderLeaf& leaf, int bin) {
                    if (!leaf._drawable)
                        return;
                    double vertices = 0, triangles = 0;
                    geometryOf(*leaf._drawable, vertices, triangles);
                    Totals& b = mBins[bin];
                    ++b.mDrawables;
                    b.mVertices += vertices;
                    b.mTriangles += triangles;
                    Totals& o = mObjects[objectOf(*leaf._drawable)];
                    ++o.mDrawables;
                    o.mVertices += vertices;
                    o.mTriangles += triangles;
                });
            }

            // ---- memory on the GPU

            void listTextures()
            {
                mTextures.clear();
                mTextureById.clear();
                mOrphanBytes = 0;
                mOrphans = 0;
                auto* manager = osg::get<osg::TextureObjectManager>(mContextId);
                if (manager == nullptr)
                    return;
                for (const auto& [profile, set] : TextureManagerAccess::sets(*manager))
                {
                    if (!set)
                        continue;
                    mOrphans += set->getNumOrphans();
                    mOrphanBytes += double(set->getNumOrphans()) * profile._size;
                    for (osg::Texture::TextureObject* object = TextureSetAccess::head(*set); object != nullptr;
                         object = object->_next)
                    {
                        TextureInfo info;
                        const osg::Texture* texture = object->getTexture();
                        info.mName = textureName(texture);
                        const bool hasImage = texture != nullptr && texture->getNumImages() > 0
                            && texture->getImage(0) != nullptr;
                        info.mKind = texture == nullptr ? "detached" : hasImage ? "file" : "render target / made";
                        if (info.mName.empty())
                            info.mName = texture ? std::string("(") + texture->className() + ")" : "(none)";
                        info.mWidth = object->_profile._width;
                        info.mHeight = object->_profile._height;
                        info.mDepth = object->_profile._depth;
                        info.mMips = object->_profile._numMipmapLevels;
                        info.mFormat = object->_profile._internalFormat;
                        info.mTarget = object->_profile._target;
                        info.mBytes = object->_profile._size;
                        info.mId = object->id();
                        mTextureById[object->id()] = mTextures.size();
                        mTextures.push_back(std::move(info));
                    }
                }
            }

            struct BufferRow
            {
                GLenum mTarget = 0, mUsage = 0;
                double mSize = 0;
                unsigned mCount = 0, mOrphans = 0;
            };

            std::vector<BufferRow> listBuffers()
            {
                std::vector<BufferRow> rows;
                auto* manager = osg::get<osg::GLBufferObjectManager>(mContextId);
                if (manager == nullptr)
                    return rows;
                for (const auto& [profile, set] : BufferManagerAccess::sets(*manager))
                {
                    if (!set)
                        continue;
                    BufferRow row;
                    row.mTarget = profile._target;
                    row.mUsage = profile._usage;
                    row.mSize = profile._size;
                    row.mCount = set->getNumOfGLBufferObjects();
                    row.mOrphans = set->getNumOrphans();
                    if (row.mCount > 0 || row.mOrphans > 0)
                        rows.push_back(row);
                }
                return rows;
            }

            // ---- the context

            struct GlInfo
            {
                std::vector<std::pair<std::string, std::string>> mStrings, mLimits;
                std::vector<std::string> mExtensions, mCompressed;
            };

            static GlInfo glInfo()
            {
                GlInfo gl;
                const auto str = [](GLenum name) {
                    const GLubyte* s = glad_glGetString(name);
                    return s ? std::string(reinterpret_cast<const char*>(s)) : std::string();
                };
                gl.mStrings = { { "Vendor", str(GL_VENDOR) }, { "Renderer", str(GL_RENDERER) },
                    { "Version", str(GL_VERSION) }, { "Shading language", str(GL_SHADING_LANGUAGE_VERSION) } };
                const auto integer = [&](const char* label, GLenum name) {
                    GLint values[4] = { 0, 0, 0, 0 };
                    glad_glGetIntegerv(name, values);
                    if (glad_glGetError() == GL_NO_ERROR)
                        gl.mLimits.emplace_back(label, std::to_string(values[0]));
                };
                integer("Max texture size", GL_MAX_TEXTURE_SIZE);
                integer("Max 3D texture size", GL_MAX_3D_TEXTURE_SIZE);
                integer("Max array texture layers", GL_MAX_ARRAY_TEXTURE_LAYERS);
                integer("Max cube map size", GL_MAX_CUBE_MAP_TEXTURE_SIZE);
                integer("Max renderbuffer size", GL_MAX_RENDERBUFFER_SIZE);
                integer("Max samples (MSAA)", GL_MAX_SAMPLES);
                integer("Max colour attachments", GL_MAX_COLOR_ATTACHMENTS);
                integer("Max draw buffers", GL_MAX_DRAW_BUFFERS);
                integer("Max texture units (fragment)", GL_MAX_TEXTURE_IMAGE_UNITS);
                integer("Max texture units (vertex)", GL_MAX_VERTEX_TEXTURE_IMAGE_UNITS);
                integer("Max texture units (all)", GL_MAX_COMBINED_TEXTURE_IMAGE_UNITS);
                integer("Max vertex attributes", GL_MAX_VERTEX_ATTRIBS);
                integer("Max vertex uniform vectors", GL_MAX_VERTEX_UNIFORM_VECTORS);
                integer("Max fragment uniform vectors", GL_MAX_FRAGMENT_UNIFORM_VECTORS);
                integer("Max varying vectors", GL_MAX_VARYING_VECTORS);
                integer("Max uniform block size", GL_MAX_UNIFORM_BLOCK_SIZE);
                integer("Max uniform buffer bindings", GL_MAX_UNIFORM_BUFFER_BINDINGS);
                integer("Max shader storage bindings", 0x90DD);
                integer("Max compute invocations", 0x90EB);
                integer("Max element indices", GL_MAX_ELEMENTS_INDICES);
                integer("Max element vertices", GL_MAX_ELEMENTS_VERTICES);
                {
                    GLfloat anisotropy = 0;
                    glad_glGetFloatv(0x84FF, &anisotropy); // GL_MAX_TEXTURE_MAX_ANISOTROPY_EXT
                    if (glad_glGetError() == GL_NO_ERROR)
                        gl.mLimits.emplace_back("Max anisotropy", fixed(anisotropy, 0));
                }
                {
                    GLint dims[2] = { 0, 0 };
                    glad_glGetIntegerv(GL_MAX_VIEWPORT_DIMS, dims);
                    gl.mLimits.emplace_back("Max viewport", std::to_string(dims[0]) + " x " + std::to_string(dims[1]));
                }
                GLint count = 0;
                glad_glGetIntegerv(GL_NUM_EXTENSIONS, &count);
                for (GLint i = 0; i < count && glad_glGetStringi != nullptr; ++i)
                    if (const GLubyte* e = glad_glGetStringi(GL_EXTENSIONS, static_cast<GLuint>(i)))
                        gl.mExtensions.emplace_back(reinterpret_cast<const char*>(e));
                std::sort(gl.mExtensions.begin(), gl.mExtensions.end());
                GLint formats = 0;
                glad_glGetIntegerv(GL_NUM_COMPRESSED_TEXTURE_FORMATS, &formats);
                if (formats > 0 && formats < 1024)
                {
                    std::vector<GLint> list(formats);
                    glad_glGetIntegerv(GL_COMPRESSED_TEXTURE_FORMATS, list.data());
                    for (GLint f : list)
                        gl.mCompressed.push_back(formatName(static_cast<GLenum>(f)));
                }
                while (glad_glGetError() != GL_NO_ERROR)
                {
                }
                return gl;
            }

            // a frame's statistics a few frames back (the GPU's times arrive late), every value OSG and OpenMW put there
            static std::vector<std::pair<std::string, std::string>> statsOf(osg::Stats& stats)
            {
                std::vector<std::pair<std::string, std::string>> out;
                const unsigned latest = stats.getLatestFrameNumber();
                const unsigned frame = latest > 3 ? latest - 3 : latest;
                std::ostringstream text;
                stats.report(text, frame);
                std::istringstream lines(text.str());
                std::string line;
                std::getline(lines, line);
                while (std::getline(lines, line))
                {
                    const auto tab = line.rfind('\t');
                    if (tab == std::string::npos)
                        continue;
                    const auto from = line.find_first_not_of(' ');
                    if (from == std::string::npos || from >= tab)
                        continue;
                    std::string key = line.substr(from, tab - from);
                    while (!key.empty() && key.back() == ' ')
                        key.pop_back();
                    out.emplace_back(key, line.substr(tab + 1));
                }
                return out;
            }

            std::vector<std::pair<std::string, std::vector<std::pair<std::string, std::string>>>> readStats(
                osgViewer::ViewerBase& viewer)
            {
                std::vector<std::pair<std::string, std::vector<std::pair<std::string, std::string>>>> out;
                out.emplace_back("Viewer (OpenMW's engine and resources)", statsOf(*viewer.getViewerStats()));
                osgViewer::ViewerBase::Cameras cameras;
                viewer.getCameras(cameras);
                for (osg::Camera* camera : cameras)
                    if (osg::Stats* stats = camera->getStats())
                        out.emplace_back("Camera " + (camera->getName().empty() ? std::string("(unnamed)") : camera->getName()),
                            statsOf(*stats));
                return out;
            }

            // ---- the report

            std::string passLabel(const Pass& pass) const
            {
                if (pass.mFbo == 0)
                    return "window";
                auto label = mFboLabels.find(pass.mFbo);
                const FboInfo& fbo = mFbos.at(pass.mFbo);
                std::string out = label != mFboLabels.end() ? label->second : fbo.mNames;
                if (out.empty())
                {
                    out = "framebuffer " + std::to_string(pass.mFbo);
                    if (!fbo.mAttachments.empty())
                        out += " " + std::to_string(fbo.mAttachments[0].mWidth) + "x"
                            + std::to_string(fbo.mAttachments[0].mHeight);
                }
                return out;
            }

            static std::string attachmentsText(const FboInfo& fbo)
            {
                std::string out;
                for (const Attachment& a : fbo.mAttachments)
                {
                    if (!out.empty())
                        out += " + ";
                    out += std::to_string(a.mWidth) + "x" + std::to_string(a.mHeight) + " " + formatName(a.mFormat);
                }
                return out;
            }

            // estimated bytes the pass moves between the GPU's tile memory and RAM: loads, stores and blits
            double passBytes(const Pass& pass, double& loads, double& stores) const
            {
                loads = stores = 0;
                const FboInfo& fbo = mFbos.at(pass.mFbo);
                for (const Attachment& a : fbo.mAttachments)
                {
                    const double area = double(a.mWidth) * a.mHeight;
                    const double share
                        = pass.mViewportArea > 0 && area > 0 ? std::min(1.0, pass.mViewportArea / area) : 1.0;
                    if (pass.mDraws > 0 && !((pass.mClearedBefore | pass.mDiscardedBefore) & a.mKind))
                        loads += a.bytes() * share;
                    if (pass.mWrote && a.mStored && !(pass.mDiscardedAfter & a.mKind))
                        stores += a.bytes() * share;
                }
                return loads + stores + pass.mBlitBytes;
            }

            static std::string processFile(const char* path, const std::vector<std::string>& keys,
                std::vector<std::pair<std::string, std::string>>& out)
            {
                std::ifstream file(path);
                std::string line;
                while (std::getline(file, line))
                    for (const std::string& key : keys)
                        if (line.rfind(key + ":", 0) == 0)
                        {
                            std::string value = line.substr(key.size() + 1);
                            value.erase(0, value.find_first_not_of(" \t"));
                            out.emplace_back(key, value);
                        }
                return {};
            }

            void publish()
            {
                std::vector<Tab> tabs;
                const double fps = mStatsFps();
                for (TextureInfo& t : mTextures)
                    if (!t.mName.empty() && t.mName[0] == '(')
                        if (auto found = mTargetNames.find(t.mId); found != mTargetNames.end())
                            t.mName = found->second + " target " + formatName(t.mFormat);
                for (auto& [handle, fbo] : mFbos)
                {
                    if (handle == 0)
                        continue;
                    fbo.mNames.clear();
                    for (Attachment& a : fbo.mAttachments)
                    {
                        if (a.mTexture != 0)
                            if (auto found = mTextureById.find(a.mTexture); found != mTextureById.end())
                                a.mName = mTextures[found->second].mName;
                        if (a.mName.empty() || a.mName.rfind("texture ", 0) == 0 || a.mName.rfind("renderbuffer", 0) == 0)
                            continue;
                        fbo.mNames += (fbo.mNames.empty() ? "" : ", ") + a.mName;
                    }
                }

                // -- bandwidth
                Tab bandwidth{ "Bandwidth", {} };
                {
                    Section passes;
                    passes.mTitle = "Render passes of one frame";
                    passes.mNote = "Every switch of the framebuffer drawn into, in order. Traffic is an estimate of what a "
                                   "tile GPU loads and stores (an upper bound: drivers skip some). GPU time is from timer "
                                   "queries"
                        + std::string(mTiming ? (mTimesValid ? "." : " (some were lost: treat as rough).")
                                              : " (not available on this driver).");
                    passes.mColumns = { "#", "Pass", "Target", "Draws", "Primitives", "Clears", "Discards", "Blits",
                        "Load MB", "Store MB", "Traffic MB", "GPU ms" };
                    passes.mSort = mTiming ? 11 : 10;
                    std::map<std::string, std::array<double, 4>> byLabel; // passes, traffic, gpu, draws
                    double totalBytes = 0, totalGpu = 0;
                    unsigned totalDraws = 0;
                    for (std::size_t i = 0; i < mPasses.size(); ++i)
                    {
                        const Pass& pass = mPasses[i];
                        if (pass.mDraws == 0 && pass.mClears == 0 && pass.mBlits == 0 && pass.mComputes == 0
                            && pass.mGpuMs < 0.01)
                            continue; // bound and left
                        double loads = 0, stores = 0;
                        const double bytes = passBytes(pass, loads, stores);
                        const std::string label = passLabel(pass);
                        passes.mRows.push_back({ double(i), label, attachmentsText(mFbos.at(pass.mFbo)),
                            double(pass.mDraws + pass.mComputes), pass.mPrimitives, double(pass.mClears),
                            double(pass.mDiscards), double(pass.mBlits), loads / 1048576.0, stores / 1048576.0,
                            bytes / 1048576.0, pass.mGpuMs });
                        auto& sum = byLabel[label];
                        sum[0] += 1;
                        sum[1] += bytes;
                        sum[2] += pass.mGpuMs;
                        sum[3] += pass.mDraws;
                        totalBytes += bytes;
                        totalGpu += pass.mGpuMs;
                        totalDraws += pass.mDraws;
                    }
                    Section ranking;
                    ranking.mTitle = "What uses the bandwidth, most first";
                    ranking.mNote = "The passes above added up by what they draw into.";
                    ranking.mColumns = { "Drawn into", "Passes", "Draws", "Traffic MB/frame", "MB/s at this fps",
                        "Share %", "GPU ms" };
                    ranking.mSort = 3;
                    for (const auto& [label, sum] : byLabel)
                        ranking.mRows.push_back({ label, sum[0], sum[3], sum[1] / 1048576.0,
                            sum[1] / 1048576.0 * fps, totalBytes > 0 ? sum[1] / totalBytes * 100 : 0.0, sum[2] });
                    Section summary;
                    summary.mTitle = "Frame";
                    summary.mLines = { { "Frame captured", std::to_string(mFrame) },
                        { "Framebuffer switches", std::to_string(mPasses.size()) },
                        { "Draw calls", std::to_string(totalDraws) },
                        { "Estimated traffic a frame", megabytes(totalBytes) },
                        { "At " + fixed(fps, 1) + " fps", fixed(totalBytes * fps / 1048576.0 / 1024.0, 2) + " GB/s" },
                        { "GPU time in passes", mTiming ? fixed(totalGpu, 2) + " ms" : "not measured" },
                        { "Opaque copy this frame", opaqueCopySkipped(mFrame) ? "skipped" : "made" } };
                    bandwidth.mSections = { summary, ranking, passes };
                }

                // -- memory
                Tab memory{ "Memory", {} };
                {
                    double fileBytes = 0, madeBytes = 0, bufferBytes = 0;
                    unsigned fileCount = 0, madeCount = 0, bufferCount = 0;
                    std::map<std::string, std::array<double, 2>> byFormat;
                    for (const TextureInfo& t : mTextures)
                    {
                        if (t.mKind == "file")
                        {
                            fileBytes += t.mBytes;
                            ++fileCount;
                        }
                        else
                        {
                            madeBytes += t.mBytes;
                            ++madeCount;
                        }
                        auto& f = byFormat[formatName(t.mFormat)];
                        f[0] += 1;
                        f[1] += t.mBytes;
                    }
                    for (const BufferRow& b : mBuffers)
                    {
                        bufferBytes += b.mSize * b.mCount;
                        bufferCount += b.mCount;
                    }
                    Section gpu;
                    gpu.mTitle = "On the GPU (OSG's objects; sizes estimated from their formats)";
                    gpu.mLines = { { "Textures from files", std::to_string(fileCount) + ", " + megabytes(fileBytes) },
                        { "Render targets and made textures", std::to_string(madeCount) + ", " + megabytes(madeBytes) },
                        { "Unused textures kept for reuse", std::to_string(mOrphans) + ", " + megabytes(mOrphanBytes) },
                        { "Vertex and index buffers", std::to_string(bufferCount) + ", " + megabytes(bufferBytes) },
                        { "Total", megabytes(fileBytes + madeBytes + mOrphanBytes + bufferBytes) } };
                    Section process;
                    process.mTitle = "The process (/proc)";
                    processFile("/proc/self/status",
                        { "VmRSS", "VmHWM", "VmSwap", "VmData", "RssAnon", "RssFile", "Threads" }, process.mLines);
                    processFile("/proc/self/smaps_rollup", { "Pss", "Pss_Anon", "Pss_File", "Swap", "SwapPss" },
                        process.mLines);
                    processFile("/proc/meminfo", { "MemTotal", "MemAvailable", "SwapTotal", "SwapFree" }, process.mLines);
                    Section formats;
                    formats.mTitle = "Textures by format";
                    formats.mColumns = { "Format", "Textures", "MB" };
                    formats.mSort = 2;
                    for (const auto& [name, f] : byFormat)
                        formats.mRows.push_back({ name, f[0], f[1] / 1048576.0 });
                    Section buffers;
                    buffers.mTitle = "Buffers by size";
                    buffers.mColumns = { "Kind", "Usage", "Bytes each", "Count", "Kept for reuse", "MB" };
                    buffers.mSort = 5;
                    for (const BufferRow& b : mBuffers)
                        buffers.mRows.push_back({ b.mTarget == GL_ELEMENT_ARRAY_BUFFER ? "indices"
                                : b.mTarget == GL_ARRAY_BUFFER                         ? "vertices"
                                                                                       : "other",
                            b.mUsage == GL_STATIC_DRAW ? "static" : b.mUsage == GL_DYNAMIC_DRAW ? "dynamic" : "stream",
                            b.mSize, double(b.mCount), double(b.mOrphans), b.mSize * b.mCount / 1048576.0 });
                    memory.mSections = { gpu, process, formats, buffers };
                }

                Tab textures{ "Textures", {} };
                {
                    Section list;
                    list.mTitle = "Every texture on the GPU (" + std::to_string(mTextures.size()) + ")";
                    list.mColumns = { "Name", "Kind", "Type", "Width", "Height", "Layers", "Mips", "Format", "KB" };
                    list.mSort = 8;
                    for (const TextureInfo& t : mTextures)
                        list.mRows.push_back({ t.mName, t.mKind, targetName(t.mTarget), double(t.mWidth),
                            double(t.mHeight), double(std::max(1, t.mDepth)), double(t.mMips), formatName(t.mFormat),
                            t.mBytes / 1024.0 });
                    textures.mSections = { list };
                }

                // -- scene
                Tab scene{ "Scene", {} };
                {
                    Section stages;
                    stages.mTitle = "Cameras drawn this frame";
                    stages.mNote = "The scene camera's render stages: before it (shadow maps, reflections, ripples, ...), "
                                   "itself, and after it (post processing).";
                    stages.mNote += " Draw calls, primitives and GPU time are what the frame's capture recorded in "
                                    "their framebuffers; drawables and triangles are from the scene graph's sorted lists "
                                    "(empty for cameras whose lists are gone by the time the scene is drawn).";
                    stages.mColumns = { "Camera", "When", "Cameras", "Size", "Target", "Draw calls", "Primitives",
                        "GPU ms", "Drawables", "Triangles" };
                    stages.mSort = mTiming ? 7 : 6;
                    struct Gl
                    {
                        double mDraws = 0, mPrimitives = 0, mGpu = 0;
                    };
                    std::map<std::string, Gl> byCamera;
                    for (const Pass& pass : mPasses)
                        if (auto label = mFboLabels.find(pass.mFbo); label != mFboLabels.end())
                        {
                            Gl& gl = byCamera[label->second];
                            gl.mDraws += pass.mDraws;
                            gl.mPrimitives += pass.mPrimitives;
                            gl.mGpu += pass.mGpuMs;
                        }
                    std::map<std::pair<std::string, std::string>, std::vector<const StageInfo*>> grouped;
                    for (const StageInfo& s : mStages)
                        grouped[{ s.mLabel, s.mOrder }].push_back(&s);
                    for (const auto& [key, list] : grouped)
                    {
                        const StageInfo& first = *list.front();
                        double leaves = 0, triangles = 0;
                        for (const StageInfo* s : list)
                        {
                            leaves += s->mLeaves;
                            triangles += s->mTriangles;
                        }
                        const auto gl = byCamera.find(key.first);
                        const bool known = gl != byCamera.end() && key.first != "(unnamed camera)";
                        stages.mRows.push_back({ key.first, key.second, double(list.size()),
                            std::to_string(first.mWidth) + "x" + std::to_string(first.mHeight), first.mTarget,
                            known ? gl->second.mDraws : 0.0, known ? gl->second.mPrimitives : 0.0,
                            known ? gl->second.mGpu : 0.0, leaves, triangles });
                    }
                    Section bins;
                    bins.mTitle = "Scene camera by render bin";
                    bins.mNote = "-1 sky, 0 opaque, 9 opaque copy, 10 transparent (and water), 12 first person, 13 sun "
                                 "glare, 14 distortion.";
                    bins.mColumns = { "Bin", "Drawables", "Vertices", "Triangles" };
                    bins.mSort = 3;
                    for (const auto& [bin, t] : mBins)
                        bins.mRows.push_back({ double(bin), double(t.mDrawables), t.mVertices, t.mTriangles });
                    Section objects;
                    objects.mTitle = "What the scene camera draws, by model (" + std::to_string(mObjects.size()) + ")";
                    objects.mColumns = { "Model or node", "Drawables", "Vertices", "Triangles" };
                    objects.mSort = 3;
                    for (const auto& [name, t] : mObjects)
                        objects.mRows.push_back({ name, double(t.mDrawables), t.mVertices, t.mTriangles });
                    scene.mSections = { stages, bins, objects };
                }

                Tab stats{ "Engine stats", {} };
                for (const auto& [title, values] : mStats)
                {
                    Section s;
                    s.mTitle = title;
                    s.mLines = values;
                    stats.mSections.push_back(std::move(s));
                }
                if (stats.mSections.empty())
                    stats.mSections.push_back({ "No statistics", "The scene camera was not found.", {}, {}, {}, -1 });

                Tab gl{ "OpenGL", {} };
                {
                    Section strings{ "Context", "", mGl.mStrings, {}, {}, -1 };
                    Section limits{ "Limits", "", mGl.mLimits, {}, {}, -1 };
                    Section extensions;
                    extensions.mTitle = "Extensions (" + std::to_string(mGl.mExtensions.size()) + ")";
                    extensions.mColumns = { "Extension" };
                    for (const std::string& e : mGl.mExtensions)
                        extensions.mRows.push_back({ e });
                    Section compressed;
                    compressed.mTitle = "Compressed texture formats (" + std::to_string(mGl.mCompressed.size()) + ")";
                    compressed.mColumns = { "Format" };
                    for (const std::string& c : mGl.mCompressed)
                        compressed.mRows.push_back({ c });
                    gl.mSections = { strings, limits, extensions, compressed };
                }

                Tab settings{ "Settings", {} };
                {
                    std::map<std::pair<std::string, std::string>, std::pair<std::string, bool>> all;
                    for (const auto& [key, value] : Settings::Manager::mDefaultSettings)
                        all[{ key.first, key.second }] = { value, false };
                    for (const auto& [key, value] : Settings::Manager::mUserSettings)
                        all[{ key.first, key.second }] = { value, true };
                    Section list;
                    list.mTitle = "Every setting (" + std::to_string(all.size()) + ")";
                    list.mColumns = { "Section", "Setting", "Value", "From" };
                    for (const auto& [key, value] : all)
                        list.mRows.push_back(
                            { key.first, key.second, value.first, value.second ? "settings.cfg" : "default" });
                    settings.mSections = { list };
                }

                tabs = { bandwidth, memory, textures, scene, stats, gl, settings };
                std::string json = toJson(tabs);
                Log(Debug::Info) << "Inspector: report of " << mPasses.size() << " passes, " << mTextures.size()
                                 << " textures, " << mStages.size() << " cameras (" << json.size() / 1024 << " KB)";
                // kept beside the cache, to look at off the device or share
                {
                    std::ofstream file(cacheDirectory() / "inspector.json", std::ios::binary);
                    file << json;
                }
                std::lock_guard lock(mReportMutex);
                mReport = std::move(json);
            }

            double mStatsFps() const
            {
                for (const auto& [title, values] : mStats)
                    for (const auto& [key, value] : values)
                        if (key == "Frame rate")
                            return std::strtod(value.c_str(), nullptr);
                return 0;
            }

            // ---- the recorders: glad's pointers and OSG's GLExtensions for the captured frame

            void install();
            void uninstall();

            unsigned mRequestedFrames = 0, mWarmup = 0, mWaitFrames = 0, mFrame = 0, mContextId = 0;
            int mWindowWidth = 0, mWindowHeight = 0;
            bool mStatsOn = false, mStageWalked = false, mTiming = false, mTimesValid = false;
            bool mOurQuery = false, mForeignQuery = false;
            std::atomic<osgViewer::ViewerBase*> mSceneViewer{ nullptr };
            osgViewer::ViewerBase* mViewer = nullptr;
            std::vector<std::tuple<osg::observer_ptr<osg::Stats>, std::string, bool>> mWasCollected;
            osg::GLExtensions* mExt = nullptr;

            std::vector<Pass> mPasses;
            std::map<GLuint, FboInfo> mFbos;
            std::map<GLuint, std::string> mFboLabels;
            std::map<GLuint, std::string> mTargetNames; // render targets' textures: the camera drawing into them
            std::vector<StageInfo> mStages;
            std::map<int, Totals> mBins;
            std::map<std::string, Totals> mObjects;
            std::vector<TextureInfo> mTextures;
            std::map<GLuint, std::size_t> mTextureById;
            double mOrphanBytes = 0;
            unsigned mOrphans = 0;
            std::vector<BufferRow> mBuffers;
            GlInfo mGl;
            std::vector<std::pair<std::string, std::vector<std::pair<std::string, std::string>>>> mStats;

            std::mutex mReportMutex;
            std::string mReport;

        public:
            // the originals of what install() replaces
            static inline PFNGLBINDFRAMEBUFFERPROC sBindFramebuffer = nullptr;
            static inline PFNGLBLITFRAMEBUFFERPROC sBlitFramebuffer = nullptr;
            static inline PFNGLCLEARPROC sClear = nullptr;
            static inline PFNGLVIEWPORTPROC sViewport = nullptr;
            static inline PFNGLINVALIDATEFRAMEBUFFERPROC sInvalidateFramebuffer = nullptr;
            static inline PFNGLDRAWARRAYSPROC sDrawArrays = nullptr;
            static inline PFNGLDRAWELEMENTSPROC sDrawElements = nullptr;
            static inline PFNGLDRAWRANGEELEMENTSPROC sDrawRangeElements = nullptr;
            static inline PFNGLDRAWARRAYSINSTANCEDPROC sDrawArraysInstanced = nullptr;
            static inline PFNGLDRAWELEMENTSINSTANCEDPROC sDrawElementsInstanced = nullptr;
            static inline PFNGLDISPATCHCOMPUTEPROC sDispatchCompute = nullptr;
            static inline PFNGLBEGINQUERYPROC sBeginQuery = nullptr;
            static inline PFNGLENDQUERYPROC sEndQuery = nullptr;
            static inline PFNGLBEGINQUERYEXTPROC sBeginQueryEXT = nullptr;
            static inline PFNGLENDQUERYEXTPROC sEndQueryEXT = nullptr;
            // OSG's own pointers (loaded on their own by GLExtensions)
            static inline void(GL_APIENTRY* sExtBindFramebuffer)(GLenum, GLuint) = nullptr;
            static inline void(GL_APIENTRY* sExtBlitFramebuffer)(
                GLint, GLint, GLint, GLint, GLint, GLint, GLint, GLint, GLbitfield, GLenum)
                = nullptr;
            static inline void(GL_APIENTRY* sExtDrawArraysInstanced)(GLenum, GLint, GLsizei, GLsizei) = nullptr;
            static inline void(GL_APIENTRY* sExtDrawElementsInstanced)(GLenum, GLsizei, GLenum, const GLvoid*, GLsizei)
                = nullptr;
            static inline void(GL_APIENTRY* sExtDrawRangeElements)(GLenum, GLuint, GLuint, GLsizei, GLenum, const GLvoid*)
                = nullptr;
            static inline void(GL_APIENTRY* sExtDispatchCompute)(GLuint, GLuint, GLuint) = nullptr;
            static inline void(GL_APIENTRY* sExtBeginQuery)(GLenum, GLuint) = nullptr;
            static inline void(GL_APIENTRY* sExtEndQuery)(GLenum) = nullptr;

            static Inspector& i() { return get(); }

            static void GL_APIENTRY bindFramebuffer(GLenum target, GLuint fbo)
            {
                sBindFramebuffer(target, fbo);
                i().bound(target, fbo);
            }
            static void GL_APIENTRY extBindFramebuffer(GLenum target, GLuint fbo)
            {
                sExtBindFramebuffer(target, fbo);
                i().bound(target, fbo);
            }
            static void GL_APIENTRY blitFramebuffer(GLint a, GLint b, GLint c, GLint d, GLint e, GLint f, GLint g,
                GLint h, GLbitfield mask, GLenum filter)
            {
                sBlitFramebuffer(a, b, c, d, e, f, g, h, mask, filter);
                i().blitted(a, b, c, d, e, f, g, h, mask);
            }
            static void GL_APIENTRY extBlitFramebuffer(GLint a, GLint b, GLint c, GLint d, GLint e, GLint f, GLint g,
                GLint h, GLbitfield mask, GLenum filter)
            {
                sExtBlitFramebuffer(a, b, c, d, e, f, g, h, mask, filter);
                i().blitted(a, b, c, d, e, f, g, h, mask);
            }
            static void GL_APIENTRY viewport(GLint x, GLint y, GLsizei width, GLsizei height)
            {
                sViewport(x, y, width, height);
                i().viewportSet(width, height);
            }
            static void GL_APIENTRY clear(GLbitfield mask)
            {
                sClear(mask);
                i().cleared(mask);
            }
            static void GL_APIENTRY invalidateFramebuffer(GLenum target, GLsizei count, const GLenum* attachments)
            {
                sInvalidateFramebuffer(target, count, attachments);
                i().discarded(target, count, attachments);
            }
            static void GL_APIENTRY drawArrays(GLenum mode, GLint first, GLsizei count)
            {
                sDrawArrays(mode, first, count);
                i().drew(mode, count, 1);
            }
            static void GL_APIENTRY drawElements(GLenum mode, GLsizei count, GLenum type, const void* indices)
            {
                sDrawElements(mode, count, type, indices);
                i().drew(mode, count, 1);
            }
            static void GL_APIENTRY drawRangeElements(
                GLenum mode, GLuint start, GLuint end, GLsizei count, GLenum type, const void* indices)
            {
                sDrawRangeElements(mode, start, end, count, type, indices);
                i().drew(mode, count, 1);
            }
            static void GL_APIENTRY extDrawRangeElements(
                GLenum mode, GLuint start, GLuint end, GLsizei count, GLenum type, const GLvoid* indices)
            {
                sExtDrawRangeElements(mode, start, end, count, type, indices);
                i().drew(mode, count, 1);
            }
            static void GL_APIENTRY drawArraysInstanced(GLenum mode, GLint first, GLsizei count, GLsizei instances)
            {
                sDrawArraysInstanced(mode, first, count, instances);
                i().drew(mode, count, instances);
            }
            static void GL_APIENTRY extDrawArraysInstanced(GLenum mode, GLint first, GLsizei count, GLsizei instances)
            {
                sExtDrawArraysInstanced(mode, first, count, instances);
                i().drew(mode, count, instances);
            }
            static void GL_APIENTRY drawElementsInstanced(
                GLenum mode, GLsizei count, GLenum type, const void* indices, GLsizei instances)
            {
                sDrawElementsInstanced(mode, count, type, indices, instances);
                i().drew(mode, count, instances);
            }
            static void GL_APIENTRY extDrawElementsInstanced(
                GLenum mode, GLsizei count, GLenum type, const GLvoid* indices, GLsizei instances)
            {
                sExtDrawElementsInstanced(mode, count, type, indices, instances);
                i().drew(mode, count, instances);
            }
            static void GL_APIENTRY dispatchCompute(GLuint x, GLuint y, GLuint z)
            {
                sDispatchCompute(x, y, z);
                i().dispatched();
            }
            static void GL_APIENTRY extDispatchCompute(GLuint x, GLuint y, GLuint z)
            {
                sExtDispatchCompute(x, y, z);
                i().dispatched();
            }
            static void GL_APIENTRY beginQuery(GLenum target, GLuint id)
            {
                i().foreignBegin(target);
                sBeginQuery(target, id);
            }
            static void GL_APIENTRY endQuery(GLenum target)
            {
                sEndQuery(target);
                i().foreignEnd(target);
            }
            static void GL_APIENTRY beginQueryEXT(GLenum target, GLuint id)
            {
                i().foreignBegin(target);
                sBeginQueryEXT(target, id);
            }
            static void GL_APIENTRY endQueryEXT(GLenum target)
            {
                sEndQueryEXT(target);
                i().foreignEnd(target);
            }
            static void GL_APIENTRY extBeginQuery(GLenum target, GLuint id)
            {
                i().foreignBegin(target);
                sExtBeginQuery(target, id);
            }
            static void GL_APIENTRY extEndQuery(GLenum target)
            {
                sExtEndQuery(target);
                i().foreignEnd(target);
            }
        };

        // Points a function pointer at a recorder, keeping the original; a null pointer (not loaded) stays null
        template <class P, class W>
        void hook(P& pointer, P& original, W wrapper)
        {
            original = pointer;
            if (pointer != nullptr)
                pointer = reinterpret_cast<P>(wrapper);
        }

        template <class P>
        void unhook(P& pointer, P& original)
        {
            if (original != nullptr)
                pointer = original;
        }

        void Inspector::install()
        {
            using I = Inspector;
            hook(glad_glBindFramebuffer, I::sBindFramebuffer, &I::bindFramebuffer);
            hook(glad_glBlitFramebuffer, I::sBlitFramebuffer, &I::blitFramebuffer);
            hook(glad_glClear, I::sClear, &I::clear);
            hook(glad_glViewport, I::sViewport, &I::viewport);
            hook(glad_glInvalidateFramebuffer, I::sInvalidateFramebuffer, &I::invalidateFramebuffer);
            hook(glad_glDrawArrays, I::sDrawArrays, &I::drawArrays);
            hook(glad_glDrawElements, I::sDrawElements, &I::drawElements);
            hook(glad_glDrawRangeElements, I::sDrawRangeElements, &I::drawRangeElements);
            hook(glad_glDrawArraysInstanced, I::sDrawArraysInstanced, &I::drawArraysInstanced);
            hook(glad_glDrawElementsInstanced, I::sDrawElementsInstanced, &I::drawElementsInstanced);
            hook(glad_glDispatchCompute, I::sDispatchCompute, &I::dispatchCompute);
            hook(glad_glBeginQuery, I::sBeginQuery, &I::beginQuery);
            hook(glad_glEndQuery, I::sEndQuery, &I::endQuery);
            hook(glad_glBeginQueryEXT, I::sBeginQueryEXT, &I::beginQueryEXT);
            hook(glad_glEndQueryEXT, I::sEndQueryEXT, &I::endQueryEXT);
            if (mExt != nullptr)
            {
                hook(mExt->glBindFramebuffer, I::sExtBindFramebuffer, &I::extBindFramebuffer);
                hook(mExt->glBlitFramebuffer, I::sExtBlitFramebuffer, &I::extBlitFramebuffer);
                hook(mExt->glDrawArraysInstanced, I::sExtDrawArraysInstanced, &I::extDrawArraysInstanced);
                hook(mExt->glDrawElementsInstanced, I::sExtDrawElementsInstanced, &I::extDrawElementsInstanced);
                hook(mExt->glDrawRangeElements, I::sExtDrawRangeElements, &I::extDrawRangeElements);
                hook(mExt->glDispatchCompute, I::sExtDispatchCompute, &I::extDispatchCompute);
                hook(mExt->glBeginQuery, I::sExtBeginQuery, &I::extBeginQuery);
                hook(mExt->glEndQuery, I::sExtEndQuery, &I::extEndQuery);
            }
            // our own timer queries go to the originals
            if (sBeginQueryEXT == nullptr || sEndQueryEXT == nullptr)
                mTiming = false;
        }

        void Inspector::uninstall()
        {
            using I = Inspector;
            unhook(glad_glBindFramebuffer, I::sBindFramebuffer);
            unhook(glad_glBlitFramebuffer, I::sBlitFramebuffer);
            unhook(glad_glClear, I::sClear);
            unhook(glad_glViewport, I::sViewport);
            unhook(glad_glInvalidateFramebuffer, I::sInvalidateFramebuffer);
            unhook(glad_glDrawArrays, I::sDrawArrays);
            unhook(glad_glDrawElements, I::sDrawElements);
            unhook(glad_glDrawRangeElements, I::sDrawRangeElements);
            unhook(glad_glDrawArraysInstanced, I::sDrawArraysInstanced);
            unhook(glad_glDrawElementsInstanced, I::sDrawElementsInstanced);
            unhook(glad_glDispatchCompute, I::sDispatchCompute);
            unhook(glad_glBeginQuery, I::sBeginQuery);
            unhook(glad_glEndQuery, I::sEndQuery);
            unhook(glad_glBeginQueryEXT, I::sBeginQueryEXT);
            unhook(glad_glEndQueryEXT, I::sEndQueryEXT);
            if (mExt != nullptr)
            {
                unhook(mExt->glBindFramebuffer, I::sExtBindFramebuffer);
                unhook(mExt->glBlitFramebuffer, I::sExtBlitFramebuffer);
                unhook(mExt->glDrawArraysInstanced, I::sExtDrawArraysInstanced);
                unhook(mExt->glDrawElementsInstanced, I::sExtDrawElementsInstanced);
                unhook(mExt->glDrawRangeElements, I::sExtDrawRangeElements);
                unhook(mExt->glDispatchCompute, I::sExtDispatchCompute);
                unhook(mExt->glBeginQuery, I::sExtBeginQuery);
                unhook(mExt->glEndQuery, I::sExtEndQuery);
            }
        }
    }

    bool inspectorRequest()
    {
        return Inspector::get().request();
    }

    std::string inspectorTakeReport()
    {
        return Inspector::get().take();
    }

    void inspectorMainThread()
    {
        Inspector::get().mainThread();
    }

    void inspectorSwapped(osg::GraphicsContext& gc)
    {
        const auto state = Inspector::get().mState.load();
        if (state != Inspector::State::Idle && state != Inspector::State::Done && state != Inspector::State::Requested)
            Inspector::get().swapped(gc);
    }

    void inspectorSceneStage(const osgUtil::RenderStage& stage, osg::RenderInfo& renderInfo)
    {
        const auto state = Inspector::get().mState.load();
        if (state == Inspector::State::Requested || state == Inspector::State::Capturing)
            Inspector::get().sceneStage(stage, renderInfo);
    }

    bool inspectorTaken()
    {
        // the report was handed over: ready for the next request
        auto& inspector = Inspector::get();
        auto done = Inspector::State::Done;
        return inspector.mState.compare_exchange_strong(done, Inspector::State::Idle);
    }
}

#ifdef __ANDROID__
#include <jni.h>

// the launcher's performance HUD, "Inspect" (androidApp SdlEngineBridge)
extern "C" JNIEXPORT jboolean JNICALL Java_org_openmw_SdlEngineBridge_omwInspectorRequest(JNIEnv*, jobject)
{
    return Alpha3::inspectorRequest() ? JNI_TRUE : JNI_FALSE;
}

// the report as UTF-8 JSON once it is made (null until then); handed over once
extern "C" JNIEXPORT jbyteArray JNICALL Java_org_openmw_SdlEngineBridge_omwInspectorReport(JNIEnv* env, jobject)
{
    std::string report = Alpha3::inspectorTakeReport();
    if (report.empty())
        return nullptr;
    Alpha3::inspectorTaken();
    jbyteArray bytes = env->NewByteArray(static_cast<jsize>(report.size()));
    env->SetByteArrayRegion(bytes, 0, static_cast<jsize>(report.size()), reinterpret_cast<const jbyte*>(report.data()));
    return bytes;
}
#endif
