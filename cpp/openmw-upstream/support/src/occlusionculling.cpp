// Occlusion culling ([Camera] occlusion culling): objects hidden behind terrain and large opaque meshes (buildings,
// rocks, Vivec's cantons) are not drawn. It works on the CPU, so nothing is read back from the GPU:
//
// 1. While the scene camera is culled, the large opaque drawables it keeps are remembered as occluders, with their
//    world matrices.
// 2. At the first test of the next frame, those occluders are drawn with that frame's camera into a small depth buffer
//    (a few hundred pixels wide), nearest first, up to a triangle budget. Each pixel keeps the farthest depth its
//    triangle has anywhere in the pixel, so the buffer is never nearer than what is really drawn there.
// 3. A node or drawable that passed the view frustum test is culled when every pixel of its bounds' screen rectangle
//    (widened by a pixel) holds a surface nearer than the nearest point of its bounds.
//
// Only the scene camera's traversal is tested (not shadows, water reflections or the sky). An occluder that was not in
// view last frame is simply missing, which only means less is culled; one that moved is a frame late.
//
// It pays only where much is hidden (towns, interiors): elsewhere (open land, or the active cells merged into object
// paging chunks, which are tested whole) it would cost the drawing and the tests for nothing. So when it has culled
// under 2% of what it tested for a while, it rests (no drawing, no tests, no occluders recorded), and tries one frame
// again after a rest that doubles each time it still finds nothing, up to 8 seconds. Where it does cull, it measures
// now and then (frames in turn without and with it) whether frames are really faster with it, and rests where not.
//
// The nearest occluders are drawn on the cull thread first; one hidden behind them is skipped; the rest are shared
// with a few helper threads, each drawing into a buffer of its own, merged at the end.
//
// An occluder of many triangles (a building, an object paging chunk: a cell's merged meshes) is simplified once on a
// background thread (meshoptimizer, 1% of its size, open edges kept where they are), and that is drawn in its place, so
// it fits in the budget. Until it is ready the occluder is drawn as it is, or skipped when it does not fit.
#include "support.hpp"

#include <algorithm>
#include <atomic>
#include <chrono>
#include <cmath>
#include <condition_variable>
#include <deque>
#include <functional>
#include <limits>
#include <memory>
#include <mutex>
#include <thread>
#include <unordered_map>
#include <unordered_set>
#include <vector>

#include <osg/AlphaFunc>
#include <osg/CullFace>
#include <osg/CullStack>
#include <osg/Depth>
#include <osg/Geometry>
#include <osg/TriangleIndexFunctor>
#include <osgUtil/CullVisitor>
#include <osgUtil/RenderBin>
#include <osgUtil/StateGraph>

#include <components/debug/debuglog.hpp>

#include <meshoptimizer.h>

#ifdef __linux__
#include <sys/resource.h>
#include <sys/syscall.h>
#include <unistd.h>
#endif

namespace Alpha3
{
    namespace
    {
        struct OcclusionSettings
        {
            bool mEnabled;
            int mWidth; // of the depth buffer; its height is half of it
            float mOccluderMinSize; // bounding radius, in world units
            int mTriangleBudget; // occluder triangles drawn per frame
        };

        const OcclusionSettings& occlusionSettings()
        {
            static const OcclusionSettings settings{ settingBool("Camera", "occlusion culling", false),
                std::clamp(settingInt("Camera", "occlusion buffer width", 2048), 64, 4096) & ~7,
                std::max(0.f, settingFloat("Camera", "occluder min size", 300.f)),
                std::clamp(settingInt("Camera", "occluder triangle budget", 80000), 1000, 2000000) };
            return settings;
        }

        constexpr unsigned sSimplifyAbove = 1000; // triangles: an occluder with more is simplified
        constexpr float sSimplifyError = 0.01f; // of the mesh's size
        constexpr float sNear = 1.f; // view depth where triangles are clipped and bounds count as touching the camera
        constexpr int sTile = 8;
        const std::string sSceneCamera = "SceneCam"; // OpenMW's Constants::SceneCamera

        float maxScale(const osg::Matrix& m)
        {
            const auto length2 = [&](int c) { return m(c, 0) * m(c, 0) + m(c, 1) * m(c, 1) + m(c, 2) * m(c, 2); };
            return static_cast<float>(std::sqrt(std::max({ length2(0), length2(1), length2(2) })));
        }

        unsigned triangleCount(const osg::Geometry& geometry)
        {
            unsigned count = 0;
            for (unsigned i = 0; i < geometry.getNumPrimitiveSets(); ++i)
            {
                const osg::PrimitiveSet& set = *geometry.getPrimitiveSet(i);
                if (set.getNumInstances() > 1)
                    return 0; // instanced (groundcover): the vertices are not where they are drawn
                const unsigned n = set.getNumIndices();
                switch (set.getMode())
                {
                    case GL_TRIANGLES:
                        count += n / 3;
                        break;
                    case GL_TRIANGLE_STRIP:
                    case GL_TRIANGLE_FAN:
                        count += n > 2 ? n - 2 : 0;
                        break;
                    default:
                        break;
                }
            }
            return count;
        }

        // How the state of a render graph leaf draws, as far as occlusion goes
        struct StateVerdict
        {
            bool mOccluder = false;
            bool mCullBack = false;
            bool mCullFront = false;
        };

        // The state the draw will have, from the root of the state graph to the leaf: a value set further down wins,
        // unless one above is OVERRIDE and it is not PROTECTED
        StateVerdict judgeState(const osgUtil::StateGraph* leaf)
        {
            std::vector<const osg::StateSet*> chain;
            for (const osgUtil::StateGraph* graph = leaf; graph != nullptr; graph = graph->_parent)
                if (graph->getStateSet() != nullptr)
                    chain.push_back(graph->getStateSet());

            const auto wins = [](unsigned current, unsigned candidate) {
                return !(current & osg::StateAttribute::OVERRIDE) || (candidate & osg::StateAttribute::PROTECTED);
            };
            unsigned blend = osg::StateAttribute::OFF, cullFace = osg::StateAttribute::OFF;
            unsigned alphaValue = 0, depthValue = 0, cullModeValue = 0;
            const osg::AlphaFunc* alpha = nullptr;
            const osg::Depth* depth = nullptr;
            const osg::CullFace* cullMode = nullptr;
            for (auto it = chain.rbegin(); it != chain.rend(); ++it)
            {
                const osg::StateSet& set = **it;
                const osg::StateSet::ModeList& modes = set.getModeList();
                if (const auto found = modes.find(GL_BLEND); found != modes.end() && wins(blend, found->second))
                    blend = found->second;
                if (const auto found = modes.find(GL_CULL_FACE); found != modes.end() && wins(cullFace, found->second))
                    cullFace = found->second;
                if (const auto* pair = set.getAttributePair(osg::StateAttribute::ALPHAFUNC);
                    pair != nullptr && wins(alphaValue, pair->second))
                {
                    alpha = static_cast<const osg::AlphaFunc*>(pair->first.get());
                    alphaValue = pair->second;
                }
                if (const auto* pair = set.getAttributePair(osg::StateAttribute::DEPTH);
                    pair != nullptr && wins(depthValue, pair->second))
                {
                    depth = static_cast<const osg::Depth*>(pair->first.get());
                    depthValue = pair->second;
                }
                if (const auto* pair = set.getAttributePair(osg::StateAttribute::CULLFACE);
                    pair != nullptr && wins(cullModeValue, pair->second))
                {
                    cullMode = static_cast<const osg::CullFace*>(pair->first.get());
                    cullModeValue = pair->second;
                }
            }

            StateVerdict verdict;
            // OpenMW keeps the alpha test as an attribute (Shader::RemovedAlphaFunc) and does it in the shader, whether
            // GL_ALPHA_TEST is on or not (an attribute's value only holds OVERRIDE and PROTECTED)
            const bool alphaTested = alpha != nullptr && alpha->getFunction() != osg::AlphaFunc::ALWAYS;
            const bool writesDepth = depth == nullptr || depth->getWriteMask();
            verdict.mOccluder = !(blend & osg::StateAttribute::ON) && !alphaTested && writesDepth;
            if (cullFace & osg::StateAttribute::ON)
            {
                const osg::CullFace::Mode mode = cullMode != nullptr ? cullMode->getMode() : osg::CullFace::BACK;
                verdict.mCullBack = mode == osg::CullFace::BACK || mode == osg::CullFace::FRONT_AND_BACK;
                verdict.mCullFront = mode == osg::CullFace::FRONT || mode == osg::CullFace::FRONT_AND_BACK;
            }
            return verdict;
        }

        // An occluder's triangles, simplified: positions only, welded
        struct SimplifiedOccluder
        {
            std::vector<osg::Vec3f> mPositions;
            std::vector<unsigned> mIndices;
        };

        struct Occluder
        {
            osg::ref_ptr<const osg::Geometry> mGeometry;
            std::shared_ptr<const SimplifiedOccluder> mSimplified; // drawn in place of the geometry when there is one
            osg::Matrix mWorld;
            osg::BoundingBox mBox; // the geometry's own
            bool mCullBack;
            bool mCullFront;
            float mPriority; // radius over distance: how much of the view it can cover
            unsigned mTriangles;
        };

        struct ScreenPoint
        {
            float mX, mY, mInvDepth;
        };

        // The occluders' depth, as 1 / view depth (0: nothing there): each pixel keeps the nearest occluder, at the
        // farthest it is anywhere in the pixel, so the buffer is never nearer than what is drawn there
        class DepthBuffer
        {
        public:
            void reset(int width, int height)
            {
                mWidth = width;
                mHeight = height;
                mInvDepth.assign(static_cast<std::size_t>(width) * height, 0.f);
                mTilesX = (width + sTile - 1) / sTile;
                mTilesY = (height + sTile - 1) / sTile;
                mTileLeast.assign(static_cast<std::size_t>(mTilesX) * mTilesY, 0.f);
            }

            void setCamera(const osg::Matrix& projection)
            {
                for (int row = 0; row < 4; ++row)
                {
                    mX[row] = static_cast<float>(projection(row, 0) * 0.5 * mWidth + projection(row, 3) * 0.5 * mWidth);
                    mY[row] = static_cast<float>(projection(row, 1) * 0.5 * mHeight + projection(row, 3) * 0.5 * mHeight);
                    mW[row] = static_cast<float>(projection(row, 3));
                }
            }

            // false: behind the camera
            bool project(const osg::Vec3f& v, ScreenPoint& out) const
            {
                const float w = v.x() * mW[0] + v.y() * mW[1] + v.z() * mW[2] + mW[3];
                if (!(w > 1e-6f))
                    return false;
                const float inv = 1.f / w;
                out.mX = (v.x() * mX[0] + v.y() * mX[1] + v.z() * mX[2] + mX[3]) * inv;
                out.mY = (v.x() * mY[0] + v.y() * mY[1] + v.z() * mY[2] + mY[3]) * inv;
                out.mInvDepth = 1.f / -v.z();
                return std::isfinite(out.mX) && std::isfinite(out.mY);
            }

            // A mesh in view space: its vertices are projected once, its triangles drawn (the ones crossing the near
            // plane clipped)
            template <class Triangles>
            void drawMesh(const std::vector<osg::Vec3f>& view, bool cullBack, bool cullFront, Triangles&& triangles)
            {
                mScreen.resize(view.size());
                mInFront.resize(view.size());
                for (std::size_t i = 0; i < view.size(); ++i)
                    mInFront[i] = -view[i].z() >= sNear && project(view[i], mScreen[i]);
                triangles([&](unsigned a, unsigned b, unsigned c) {
                    if (a >= view.size() || b >= view.size() || c >= view.size())
                        return;
                    const int inFront = mInFront[a] + mInFront[b] + mInFront[c];
                    if (inFront == 3)
                    {
                        // counter-clockwise on the screen (y up) is the front
                        const ScreenPoint &p = mScreen[a], &q = mScreen[b], &r = mScreen[c];
                        const float area = (q.mX - p.mX) * (r.mY - p.mY) - (r.mX - p.mX) * (q.mY - p.mY);
                        if ((area > 0 && cullFront) || (area < 0 && cullBack))
                            return;
                        rasterize(p, q, r, area);
                    }
                    else if (inFront > 0 || -view[a].z() >= sNear || -view[b].z() >= sNear || -view[c].z() >= sNear)
                    {
                        const osg::Vec3f &p = view[a], &q = view[b], &r = view[c];
                        if (cullBack || cullFront)
                        {
                            // counter-clockwise seen from the camera (at the origin) is the front, as on the screen
                            const bool front = ((q - p) ^ (r - p)) * p < 0;
                            if ((front && cullFront) || (!front && cullBack))
                                return;
                        }
                        drawTriangle(p, q, r);
                    }
                });
            }

            // A triangle in view space (depth is -z), clipped at the near plane
            void drawTriangle(const osg::Vec3f& a, const osg::Vec3f& b, const osg::Vec3f& c)
            {
                const float da = -a.z(), db = -b.z(), dc = -c.z();
                if (da < sNear && db < sNear && dc < sNear)
                    return;
                if (da >= sNear && db >= sNear && dc >= sNear)
                {
                    drawProjected(a, b, c);
                    return;
                }
                // Sutherland-Hodgman against the near plane: three or four vertices
                const osg::Vec3f in[3] = { a, b, c };
                osg::Vec3f out[4];
                int n = 0;
                for (int i = 0; i < 3; ++i)
                {
                    const osg::Vec3f& p = in[i];
                    const osg::Vec3f& q = in[(i + 1) % 3];
                    const float dp = -p.z() - sNear, dq = -q.z() - sNear;
                    if (dp >= 0)
                        out[n++] = p;
                    if ((dp >= 0) != (dq >= 0))
                        out[n++] = p + (q - p) * (dp / (dp - dq));
                }
                if (n >= 3)
                    drawProjected(out[0], out[1], out[2]);
                if (n == 4)
                    drawProjected(out[0], out[2], out[3]);
            }

            void finish()
            {
                for (int ty = 0; ty < mTilesY; ++ty)
                    for (int tx = 0; tx < mTilesX; ++tx)
                    {
                        float least = std::numeric_limits<float>::infinity();
                        for (int y = ty * sTile; y < std::min(mHeight, (ty + 1) * sTile); ++y)
                            for (int x = tx * sTile; x < std::min(mWidth, (tx + 1) * sTile); ++x)
                                least = std::min(least, mInvDepth[y * mWidth + x]);
                        mTileLeast[ty * mTilesX + tx] = least;
                    }
            }

            // Bounds in view space, as corners; true when they are behind what is in the buffer everywhere. Without
            // tiles: while the buffer is still being drawn (their least values are only known after finish())
            bool hidden(const osg::Vec3f* corners, int count, bool tiles = true) const
            {
                float nearest = std::numeric_limits<float>::infinity();
                float minX = std::numeric_limits<float>::infinity(), minY = minX;
                float maxX = -minX, maxY = -minX;
                for (int i = 0; i < count; ++i)
                {
                    const float depth = -corners[i].z();
                    if (depth < sNear)
                        return false;
                    nearest = std::min(nearest, depth);
                    ScreenPoint p;
                    if (!project(corners[i], p))
                        return false;
                    minX = std::min(minX, p.mX);
                    maxX = std::max(maxX, p.mX);
                    minY = std::min(minY, p.mY);
                    maxY = std::max(maxY, p.mY);
                }
                // every pixel the rectangle touches, and one more around it
                const int x0 = std::max(0, static_cast<int>(std::floor(minX)) - 1);
                const int x1 = std::min(mWidth - 1, static_cast<int>(std::ceil(maxX)));
                const int y0 = std::max(0, static_cast<int>(std::floor(minY)) - 1);
                const int y1 = std::min(mHeight - 1, static_cast<int>(std::ceil(maxY)));
                if (x0 > x1 || y0 > y1)
                    return false;
                // hidden where an occluder is strictly nearer: 1 / depth greater
                const float bound = 1.f / nearest;
                for (int ty = y0 / sTile; ty <= y1 / sTile; ++ty)
                    for (int tx = x0 / sTile; tx <= x1 / sTile; ++tx)
                    {
                        if (tiles && mTileLeast[ty * mTilesX + tx] > bound)
                            continue;
                        for (int y = std::max(y0, ty * sTile); y <= std::min(y1, ty * sTile + sTile - 1); ++y)
                        {
                            const float* row = &mInvDepth[y * mWidth];
                            for (int x = std::max(x0, tx * sTile); x <= std::min(x1, tx * sTile + sTile - 1); ++x)
                                if (!(row[x] > bound))
                                    return false;
                        }
                    }
                return true;
            }

            // What another buffer of the same size holds, kept where it is nearer
            void merge(const DepthBuffer& other)
            {
                for (std::size_t i = 0; i < mInvDepth.size(); ++i)
                    mInvDepth[i] = std::max(mInvDepth[i], other.mInvDepth[i]);
            }

            int width() const { return mWidth; }
            int height() const { return mHeight; }
            // view depth per pixel (infinity: nothing)
            float depth(int x, int y) const
            {
                const float inv = mInvDepth[y * mWidth + x];
                return inv > 0 ? 1.f / inv : std::numeric_limits<float>::infinity();
            }

        private:
            void drawProjected(const osg::Vec3f& a, const osg::Vec3f& b, const osg::Vec3f& c)
            {
                ScreenPoint p, q, r;
                if (!project(a, p) || !project(b, q) || !project(c, r))
                    return;
                rasterize(p, q, r, (q.mX - p.mX) * (r.mY - p.mY) - (r.mX - p.mX) * (q.mY - p.mY));
            }

            void rasterize(ScreenPoint p0, ScreenPoint p1, ScreenPoint p2, float area)
            {
                if (std::abs(area) < 1e-8f)
                    return;
                if (area < 0)
                    std::swap(p1, p2);
                const float invArea = 1.f / std::abs(area);

                const int x0 = std::max(0, static_cast<int>(std::ceil(std::min({ p0.mX, p1.mX, p2.mX }) - 0.5f)));
                const int x1 = std::min(mWidth - 1, static_cast<int>(std::floor(std::max({ p0.mX, p1.mX, p2.mX }) - 0.5f)));
                const int y0 = std::max(0, static_cast<int>(std::ceil(std::min({ p0.mY, p1.mY, p2.mY }) - 0.5f)));
                const int y1 = std::min(mHeight - 1, static_cast<int>(std::floor(std::max({ p0.mY, p1.mY, p2.mY }) - 0.5f)));
                if (x0 > x1 || y0 > y1)
                    return; // between pixel centres, or off the screen

                // 1/depth is linear on the screen: a plane, whose least value over a pixel is at one of its corners
                const float e1x = p1.mX - p0.mX, e1y = p1.mY - p0.mY;
                const float e2x = p2.mX - p0.mX, e2y = p2.mY - p0.mY;
                const float d1 = p1.mInvDepth - p0.mInvDepth, d2 = p2.mInvDepth - p0.mInvDepth;
                const float da = (d1 * e2y - d2 * e1y) * invArea;
                const float db = (d2 * e1x - d1 * e2x) * invArea;
                const float least = std::min({ p0.mInvDepth, p1.mInvDepth, p2.mInvDepth });
                const float startX = x0 + 0.5f, startY = y0 + 0.5f;
                float rowInv = p0.mInvDepth + da * (startX - p0.mX) + db * (startY - p0.mY)
                    - 0.5f * (std::abs(da) + std::abs(db));

                // edge functions (positive inside: counter-clockwise after the swap), stepped along rows and columns
                const auto setup = [&](const ScreenPoint& s, const ScreenPoint& t, float& w, float& stepX, float& stepY) {
                    stepX = -(t.mY - s.mY);
                    stepY = t.mX - s.mX;
                    w = (t.mX - s.mX) * (startY - s.mY) - (t.mY - s.mY) * (startX - s.mX);
                };
                float w0, w1, w2, ax0, ax1, ax2, ay0, ay1, ay2;
                setup(p0, p1, w0, ax0, ay0);
                setup(p1, p2, w1, ax1, ay1);
                setup(p2, p0, w2, ax2, ay2);
                for (int y = y0; y <= y1; ++y)
                {
                    // the row's span of pixel centres inside all three edges, from where each edge crosses the row;
                    // then trimmed where rounding let in a pixel that fails the edge test itself
                    const float w[3] = { w0, w1, w2 }, ax[3] = { ax0, ax1, ax2 };
                    float lo = static_cast<float>(x0), hi = static_cast<float>(x1);
                    for (int k = 0; k < 3; ++k)
                    {
                        if (ax[k] > 0)
                            lo = std::max(lo, x0 + std::ceil(-w[k] / ax[k]));
                        else if (ax[k] < 0)
                            hi = std::min(hi, x0 + std::floor(w[k] / -ax[k]));
                        else if (w[k] < 0)
                            hi = lo - 1;
                    }
                    w0 += ay0;
                    w1 += ay1;
                    w2 += ay2;
                    const float rowStart = rowInv;
                    rowInv += db;
                    if (!(lo <= hi))
                        continue;
                    const auto inside = [&](int x) {
                        const float dx = static_cast<float>(x - x0);
                        return w[0] + ax[0] * dx >= 0 && w[1] + ax[1] * dx >= 0 && w[2] + ax[2] * dx >= 0;
                    };
                    int xs = static_cast<int>(lo), xe = static_cast<int>(hi);
                    while (xs <= xe && !inside(xs))
                        ++xs;
                    while (xe >= xs && !inside(xe))
                        --xe;
                    // no branches: vectorised
                    float* row = &mInvDepth[y * mWidth];
                    const float start = rowStart + da * static_cast<float>(xs - x0);
                    for (int x = xs; x <= xe; ++x)
                        row[x] = std::max(row[x], std::max(least, start + da * static_cast<float>(x - xs)));
                }
            }

            int mWidth = 0, mHeight = 0, mTilesX = 0, mTilesY = 0;
            std::vector<float> mInvDepth;
            std::vector<float> mTileLeast; // the least of each tile's pixels
            float mX[4] = {}, mY[4] = {}, mW[4] = {}; // the projection, to pixels
            std::vector<ScreenPoint> mScreen;
            std::vector<unsigned char> mInFront;
        };

        struct IndexCollector
        {
            std::vector<unsigned>* mIndices = nullptr;
            std::size_t mVertices = 0;
            void operator()(unsigned a, unsigned b, unsigned c)
            {
                if (a < mVertices && b < mVertices && c < mVertices)
                    mIndices->insert(mIndices->end(), { a, b, c });
            }
        };

        SimplifiedOccluder simplifyOccluder(const osg::Geometry& geometry)
        {
            const auto& vertices = static_cast<const osg::Vec3Array&>(*geometry.getVertexArray());
            std::vector<unsigned> indices;
            osg::TriangleIndexFunctor<IndexCollector> collector;
            collector.mIndices = &indices;
            collector.mVertices = vertices.size();
            geometry.accept(collector);
            if (indices.empty())
                return {};

            // one vertex per position: the copies for other normals or texture coordinates would be borders
            SimplifiedOccluder result;
            std::vector<unsigned> remap(vertices.size());
            const std::size_t unique = meshopt_generateVertexRemap(remap.data(), indices.data(), indices.size(),
                &vertices.front(), vertices.size(), sizeof(osg::Vec3f));
            result.mPositions.resize(unique);
            meshopt_remapVertexBuffer(
                result.mPositions.data(), &vertices.front(), vertices.size(), sizeof(osg::Vec3f), remap.data());
            std::vector<unsigned> welded(indices.size());
            meshopt_remapIndexBuffer(welded.data(), indices.data(), indices.size(), remap.data());

            result.mIndices.resize(welded.size());
            const std::size_t target = std::max<std::size_t>(welded.size() / 3 / 10, 64) * 3;
            result.mIndices.resize(meshopt_simplify(result.mIndices.data(), welded.data(), welded.size(),
                &result.mPositions[0].x(), result.mPositions.size(), sizeof(osg::Vec3f), target, sSimplifyError,
                meshopt_SimplifyLockBorder, nullptr));
            return result;
        }

        // Simplified versions of the large occluders, made on a background thread, kept while they are used
        class OccluderSimplifier
        {
        public:
            ~OccluderSimplifier()
            {
                {
                    std::lock_guard lock(mMutex);
                    mStopped = true;
                    mQueue.clear();
                }
                mWake.notify_all();
                if (mWorker.joinable())
                    mWorker.join();
            }

            // The simplified version, null while it is being made or when simplifying does not make it smaller
            std::shared_ptr<const SimplifiedOccluder> get(const osg::Geometry& geometry, unsigned frame)
            {
                std::lock_guard lock(mMutex);
                Entry& entry = mEntries[&geometry];
                if (!entry.mGeometry.valid())
                {
                    // new, or a geometry freed whose address is used again (not while the worker reads it: it holds it)
                    entry = Entry();
                    entry.mGeometry = &geometry;
                    mQueue.emplace_back(&geometry);
                    if (!mWorker.joinable())
                        mWorker = std::thread([this] { work(); });
                    mWake.notify_one();
                }
                entry.mLastUsed = frame;
                return entry.mSimplified;
            }

            // Lets go of what has not been used for a while
            void sweep(unsigned frame)
            {
                std::lock_guard lock(mMutex);
                std::erase_if(mEntries, [&](const auto& item) {
                    const Entry& entry = item.second;
                    return !entry.mGeometry.valid() || (!entry.mBusy && frame - entry.mLastUsed > 1200);
                });
            }

        private:
            struct Entry
            {
                osg::observer_ptr<const osg::Geometry> mGeometry;
                std::shared_ptr<const SimplifiedOccluder> mSimplified;
                unsigned mLastUsed = 0;
                bool mBusy = false;
            };

            void work()
            {
                while (true)
                {
                    osg::ref_ptr<const osg::Geometry> geometry;
                    {
                        std::unique_lock lock(mMutex);
                        mWake.wait(lock, [this] { return mStopped || !mQueue.empty(); });
                        if (mStopped)
                            return;
                        const bool alive = mQueue.front().lock(geometry);
                        mQueue.pop_front();
                        if (!alive)
                            continue;
                        const auto found = mEntries.find(geometry.get());
                        if (found == mEntries.end())
                            continue;
                        found->second.mBusy = true;
                    }
                    // the engine does not change a static mesh's arrays once it is drawn: read here as they are
                    auto simplified = std::make_shared<SimplifiedOccluder>(simplifyOccluder(*geometry));
                    const unsigned before = triangleCount(*geometry);
                    std::lock_guard lock(mMutex);
                    const auto found = mEntries.find(geometry.get());
                    if (found == mEntries.end())
                        continue;
                    found->second.mBusy = false;
                    // a fifth smaller at least, or the geometry is drawn as it is
                    if (simplified->mIndices.size() / 3 * 5 <= before * 4 && !simplified->mIndices.empty())
                        found->second.mSimplified = std::move(simplified);
                }
            }

            std::mutex mMutex;
            std::condition_variable mWake;
            std::unordered_map<const osg::Geometry*, Entry> mEntries;
            std::deque<osg::observer_ptr<const osg::Geometry>> mQueue;
            std::thread mWorker;
            bool mStopped = false;
        };

        // Hands a geometry's triangles (as OSG's index functor finds them) to a callable
        template <class F>
        struct IndexSink
        {
            F* mF = nullptr;
            void operator()(unsigned a, unsigned b, unsigned c) { (*mF)(a, b, c); }
        };

        // An occluder into a buffer, its vertices taken to view space in [scratch]
        void drawOccluder(DepthBuffer& buffer, std::vector<osg::Vec3f>& scratch, const Occluder& occluder,
            const osg::Matrixf& toView)
        {
            if (occluder.mSimplified)
            {
                const SimplifiedOccluder& mesh = *occluder.mSimplified;
                scratch.resize(mesh.mPositions.size());
                for (std::size_t i = 0; i < mesh.mPositions.size(); ++i)
                    scratch[i] = mesh.mPositions[i] * toView;
                buffer.drawMesh(scratch, occluder.mCullBack, occluder.mCullFront, [&](auto&& triangle) {
                    for (std::size_t i = 0; i + 2 < mesh.mIndices.size(); i += 3)
                        triangle(mesh.mIndices[i], mesh.mIndices[i + 1], mesh.mIndices[i + 2]);
                });
            }
            else
            {
                const auto& vertices = static_cast<const osg::Vec3Array&>(*occluder.mGeometry->getVertexArray());
                scratch.resize(vertices.size());
                for (std::size_t i = 0; i < vertices.size(); ++i)
                    scratch[i] = vertices[i] * toView;
                buffer.drawMesh(scratch, occluder.mCullBack, occluder.mCullFront, [&](auto&& triangle) {
                    osg::TriangleIndexFunctor<IndexSink<std::remove_reference_t<decltype(triangle)>>> sink;
                    sink.mF = &triangle;
                    occluder.mGeometry->accept(sink);
                });
            }
        }

        // Threads that draw occluders next to the cull thread, each into a buffer of its own
        class Helpers
        {
        public:
            explicit Helpers(unsigned count)
                : mBuffers(count)
            {
                for (unsigned i = 0; i < count; ++i)
                    mThreads.emplace_back([this, i] { loop(i); });
            }

            ~Helpers()
            {
                {
                    std::lock_guard lock(mMutex);
                    mStop = true;
                }
                mWake.notify_all();
                for (std::thread& thread : mThreads)
                    thread.join();
            }

            unsigned count() const { return static_cast<unsigned>(mThreads.size()); }

            // Has each helper run [job] with its buffer and scratch; wait() returns once all are done
            void start(std::function<void(DepthBuffer&, std::vector<osg::Vec3f>&)> job)
            {
                {
                    std::lock_guard lock(mMutex);
                    mJob = std::move(job);
                    ++mGeneration;
                    mRunning = count();
                }
                mWake.notify_all();
            }

            void wait()
            {
                std::unique_lock lock(mMutex);
                mDone.wait(lock, [&] { return mRunning == 0; });
            }

            const DepthBuffer& buffer(unsigned index) const { return mBuffers[index].mBuffer; }

        private:
            void loop(unsigned index)
            {
#ifdef __linux__
                // below the cull thread that wakes them (the engine's threads run at nice -4 on Android), so a helper
                // woken on its core moves to another one rather than taking it from the cull thread
                setpriority(PRIO_PROCESS, static_cast<id_t>(syscall(SYS_gettid)), 0);
#endif
                unsigned seen = 0;
                while (true)
                {
                    std::function<void(DepthBuffer&, std::vector<osg::Vec3f>&)> job;
                    {
                        std::unique_lock lock(mMutex);
                        mWake.wait(lock, [&] { return mStop || mGeneration != seen; });
                        if (mStop)
                            return;
                        seen = mGeneration;
                        job = mJob;
                    }
                    job(mBuffers[index].mBuffer, mBuffers[index].mScratch);
                    {
                        std::lock_guard lock(mMutex);
                        if (--mRunning == 0)
                            mDone.notify_one();
                    }
                }
            }

            struct Own
            {
                DepthBuffer mBuffer;
                std::vector<osg::Vec3f> mScratch;
            };
            std::vector<Own> mBuffers;
            std::vector<std::thread> mThreads;
            std::mutex mMutex;
            std::condition_variable mWake, mDone;
            std::function<void(DepthBuffer&, std::vector<osg::Vec3f>&)> mJob;
            unsigned mGeneration = 0, mRunning = 0;
            bool mStop = false;
        };

        class OcclusionCulling
        {
        public:
            // The cull visitor when this is the scene camera's traversal, ready for tests (the buffer drawn)
            osgUtil::CullVisitor* sceneCull(osg::CullStack& cullStack)
            {
                thread_local const osg::CullStack* lastStack = nullptr;
                thread_local osgUtil::CullVisitor* lastVisitor = nullptr;
                if (&cullStack != lastStack)
                {
                    lastStack = &cullStack;
                    lastVisitor = dynamic_cast<osgUtil::CullVisitor*>(&cullStack);
                }
                return lastVisitor != nullptr ? sceneCull(*lastVisitor) : nullptr;
            }

            osgUtil::CullVisitor* sceneCull(osgUtil::CullVisitor& cv)
            {
                thread_local const osg::Camera* lastCamera = nullptr;
                thread_local bool lastIsScene = false;
                thread_local unsigned lastFrame = 0;
                const osg::Camera* camera = cv.getCurrentCamera();
                const osg::FrameStamp* stamp = cv.getFrameStamp();
                if (camera == nullptr || stamp == nullptr || cv.getCurrentRenderStage() == nullptr)
                    return nullptr;
                const unsigned frame = stamp->getFrameNumber();
                if (camera != lastCamera || frame != lastFrame)
                {
                    lastCamera = camera;
                    lastFrame = frame;
                    lastIsScene = camera->getName() == sSceneCamera;
                }
                if (!lastIsScene)
                    return nullptr;
                if (frame != mFrame)
                    newFrame(frame, stamp->getReferenceTime());
                if (!mViewKnown)
                {
                    const osg::RefMatrix* view = cv.getCurrentRenderStage()->getInitialViewMatrix();
                    if (view == nullptr)
                        return nullptr;
                    mView = *view;
                    mInverseView = osg::Matrix::inverse(mView);
                    mViewKnown = true;
                }
                return &cv;
            }

            bool nodeOccluded(osg::CullStack& cullStack, const osg::Node& node)
            {
                osgUtil::CullVisitor* cv = sceneCull(cullStack);
                if (cv == nullptr || !ready(*cv))
                    return false;
                const osg::BoundingSphere& bound = node.getBound();
                const osg::Matrix& modelView = *cv->getModelViewMatrix();
                const osg::Vec3f center = bound.center() * modelView;
                const float radius = bound.radius() * maxScale(modelView);
                osg::Vec3f corners[8];
                for (int i = 0; i < 8; ++i)
                    corners[i] = center
                        + osg::Vec3f(i & 1 ? radius : -radius, i & 2 ? radius : -radius, i & 4 ? radius : -radius);
                return test(corners);
            }

            bool boxOccluded(osg::CullStack& cullStack, const osg::BoundingBox& box)
            {
                osgUtil::CullVisitor* cv = sceneCull(cullStack);
                if (cv == nullptr || !ready(*cv))
                    return false;
                const osg::Matrix& modelView = *cv->getModelViewMatrix();
                osg::Vec3f corners[8];
                for (int i = 0; i < 8; ++i)
                    corners[i] = box.corner(i) * modelView;
                return test(corners);
            }

            void drawableAdded(osgUtil::CullVisitor& cv, osg::Drawable& drawable, const osg::Matrix& modelView)
            {
                if (sceneCull(cv) == nullptr || (mRestFrames > 1))
                    return; // resting, and not drawing next frame
                const osg::Geometry* geometry = drawable.asGeometry();
                if (geometry == nullptr || dynamic_cast<const osg::Vec3Array*>(geometry->getVertexArray()) == nullptr)
                    return;
                const osg::BoundingBox& box = drawable.getBoundingBox();
                if (!box.valid())
                    return;
                const float radius = box.radius() * maxScale(modelView);
                if (radius < occlusionSettings().mOccluderMinSize)
                    return;
                if (cv.getCurrentRenderBin() == nullptr || cv.getCurrentRenderBin()->getBinNum() != 0)
                    return; // sorted (transparent), first person, water, ...
                if (!mRecordedDrawables.insert(&drawable).second)
                    return; // the terrain adds a drawable once per layer
                const osgUtil::StateGraph* graph = cv.getCurrentStateGraph();
                auto found = mVerdicts.find(graph);
                if (found == mVerdicts.end())
                    found = mVerdicts.emplace(graph, judgeState(graph)).first;
                const StateVerdict& verdict = found->second;
                if (!verdict.mOccluder || (verdict.mCullBack && verdict.mCullFront))
                    return;
                unsigned triangles = triangleCount(*geometry);
                if (triangles == 0)
                    return;
                std::shared_ptr<const SimplifiedOccluder> simplified;
                if (triangles > sSimplifyAbove)
                {
                    simplified = mSimplifier.get(*geometry, mFrame);
                    if (simplified)
                        triangles = static_cast<unsigned>(simplified->mIndices.size() / 3);
                }
                const float distance = std::max(1.f, (box.center() * modelView).length() - radius);
                mRecording.push_back({ geometry, std::move(simplified), modelView * mInverseView, box, verdict.mCullBack,
                    verdict.mCullFront, radius / distance, triangles });
            }

            unsigned drawnTriangles() const { return mDrawnTriangles; }
            unsigned restFrames() const { return mRestFrames; }
            unsigned trials() const { return mTrials; }

        private:
            void newFrame(unsigned frame, double time)
            {
                // how long the frame that just ended took (a loading screen's is cut short)
                const double frameTime = mLastTime >= 0 ? std::clamp(time - mLastTime, 0.0, 0.25) : 0.0;
                mLastTime = time;
                logStatistics();
                mFrame = frame;
                mOccluders.swap(mRecording);
                mRecording.clear();
                mRecordedDrawables.clear();
                mVerdicts.clear(); // the state graphs are rebuilt (and may be freed) every frame
                mDrawn = false;
                mViewKnown = false;
                adapt(frameTime);
                mTested = mCulled = 0;
            }

            // Rests while it culls next to nothing. Where it culls something it still costs CPU time on the cull
            // thread, and saves time there and on the GPU only as far as what it culls was worth drawing; so now and
            // then it measures whether frames are faster with it than without, and rests where they are not
            void adapt(double frameTime)
            {
                if (mTrialFrame >= 0)
                {
                    trialStep(frameTime);
                    return;
                }
                if (mRestFrames > 0)
                {
                    if (--mRestFrames == 0 && mTrialAfterRest)
                    {
                        mTrialAfterRest = false;
                        mProbing = false;
                        startTrial();
                    }
                    return;
                }
                if (!mUsable)
                    return;
                if (++mSinceTrial >= sTrialEvery)
                {
                    startTrial();
                    return;
                }
                mWindowTested += mTested;
                mWindowCulled += mCulled;
                const bool probe = mProbing;
                if (!probe && ++mWindowFrames < sWindow)
                    return;
                const bool worthIt = mWindowCulled * 50 >= mWindowTested && mWindowCulled > 0;
                mWindowFrames = 0;
                mWindowTested = mWindowCulled = 0;
                if (worthIt)
                {
                    mRest = sFirstRest;
                    mProbing = false;
                    return;
                }
                mRestFrames = mRest;
                mRestedFrames += mRest;
                mRest = std::min(mRest * 2, sLongestRest);
                mProbing = true; // after the rest, one frame decides
                mOccluders.clear();
            }

            // Frames in turn without and with culling, a few of each, the first of each turn not counted (the draw
            // thread is a frame behind); the average frame time decides
            void startTrial()
            {
                mTrialFrame = 0;
                mSinceTrial = 0;
                mTrialTime[0] = mTrialTime[1] = 0;
                mTrialFrames[0] = mTrialFrames[1] = 0;
                mMeasureLast = false;
                trialStep(0);
            }

            void trialStep(double frameTime)
            {
                if (mMeasureLast)
                {
                    mTrialTime[mTrialOff ? 0 : 1] += frameTime;
                    ++mTrialFrames[mTrialOff ? 0 : 1];
                }
                if (mTrialFrame >= sTrialTurn * sTrialTurns)
                {
                    mTrialFrame = -1;
                    mTrialOff = false;
                    mMeasureLast = false;
                    const double off = mTrialTime[0] / std::max(1u, mTrialFrames[0]);
                    const double on = mTrialTime[1] / std::max(1u, mTrialFrames[1]);
                    // equal is off: the CPU time is better left to the engine's other threads
                    const bool keep = on < off * 0.99;
                    ++mTrials;
                    Log(Debug::Verbose) << "Occlusion culling: frames take " << on * 1000 << " ms with it, " << off * 1000
                                        << " ms without; " << (keep ? "kept on" : "resting");
                    if (!keep)
                    {
                        mRestFrames = sTrialEvery;
                        mRestedFrames += sTrialEvery;
                        mTrialAfterRest = true;
                        mOccluders.clear();
                    }
                    return;
                }
                const int turn = mTrialFrame / sTrialTurn;
                mTrialOff = turn % 2 == 0;
                mMeasureLast = mTrialFrame % sTrialTurn >= sTrialSettle;
                ++mTrialFrame;
            }

            // Draws the buffer at the first test of the frame; false when it cannot be used
            bool ready(osgUtil::CullVisitor& cv)
            {
                if (!mDrawn)
                {
                    mDrawn = true;
                    if (mRestFrames > 0 || mTrialOff)
                    {
                        mUsable = false;
                        return false;
                    }
                    const osg::Matrix& projection = cv.getCurrentCamera()->getProjectionMatrix();
                    // perspective only: w is the view depth
                    mUsable = !mOccluders.empty() && projection(2, 3) != 0 && projection(3, 3) == 0;
                    if (mUsable)
                        draw(projection);
                }
                return mUsable;
            }

            void draw(const osg::Matrix& projection)
            {
                const auto start = std::chrono::steady_clock::now();
                const OcclusionSettings& settings = occlusionSettings();
                mBuffer.reset(settings.mWidth, settings.mWidth / 2);
                mBuffer.setCamera(projection);
                std::sort(mOccluders.begin(), mOccluders.end(),
                    [](const Occluder& a, const Occluder& b) { return a.mPriority > b.mPriority; });

                // The nearest and largest first, on this thread, so the rest can be skipped when hidden behind them
                // (facing a wall, the wall is drawn and the terrain and buildings behind it are not); then the others
                // shared with the helpers, each taking the next one left
                int budget = settings.mTriangleBudget;
                const int alone = budget / 4;
                mDrawnOccluders = 0;
                mDrawnTriangles = 0;
                mToDraw.clear();
                for (const Occluder& occluder : mOccluders)
                {
                    if (static_cast<int>(occluder.mTriangles) > budget)
                        continue;
                    // in float: the vertices are float, and the buffer is a few hundred pixels wide
                    const osg::Matrixf toView(occluder.mWorld * mView);
                    if (mDrawnOccluders > 0)
                    {
                        osg::Vec3f corners[8];
                        for (int i = 0; i < 8; ++i)
                            corners[i] = occluder.mBox.corner(i) * toView;
                        if (mBuffer.hidden(corners, 8, false))
                        {
                            ++mHiddenOccluders;
                            continue;
                        }
                    }
                    budget -= occluder.mTriangles;
                    ++mDrawnOccluders;
                    mDrawnTriangles += occluder.mTriangles;
                    if (static_cast<int>(mDrawnTriangles) <= alone || mDrawnOccluders == 1)
                        drawOccluder(mBuffer, mViewVertices, occluder, toView);
                    else
                        mToDraw.push_back({ &occluder, toView });
                }
                if (!mToDraw.empty())
                {
                    std::atomic<std::size_t> next{ 0 };
                    const auto work = [&](DepthBuffer& buffer, std::vector<osg::Vec3f>& scratch) {
                        for (std::size_t i = next++; i < mToDraw.size(); i = next++)
                            drawOccluder(buffer, scratch, *mToDraw[i].first, mToDraw[i].second);
                    };
                    Helpers& helpers = this->helpers();
                    // hot: the cull thread alone, the frames are held longer then anyway (thermal.cpp)
                    const unsigned count = mToDraw.size() > 1 && thermalPressure() < 2 ? helpers.count() : 0;
                    if (count > 0)
                        helpers.start([&](DepthBuffer& buffer, std::vector<osg::Vec3f>& scratch) {
                            buffer.reset(mBuffer.width(), mBuffer.height());
                            buffer.setCamera(projection);
                            work(buffer, scratch);
                        });
                    work(mBuffer, mViewVertices);
                    if (count > 0)
                    {
                        helpers.wait();
                        for (unsigned i = 0; i < count; ++i)
                            mBuffer.merge(helpers.buffer(i));
                    }
                }
                mBuffer.finish();
                mOccluders.clear(); // let go of the geometry; next frame's are being recorded
                mDrawTime += std::chrono::duration<double>(std::chrono::steady_clock::now() - start).count();
            }

            bool test(const osg::Vec3f* corners)
            {
                // timed one frame in 8, the clock costing about as much as a small test
                const bool timed = (mFrame & 7) == 0;
                const auto start = timed ? std::chrono::steady_clock::now() : std::chrono::steady_clock::time_point();
                const bool hidden = mBuffer.hidden(corners, 8);
                if (timed)
                    mTestTime += std::chrono::duration<double>(std::chrono::steady_clock::now() - start).count() * 8;
                ++mTested;
                if (hidden)
                    ++mCulled;
                return hidden;
            }

            void logStatistics()
            {
                if (mFrames % 600 == 0)
                    mSimplifier.sweep(mFrame);
                mSumTested += mTested;
                mSumCulled += mCulled;
                if (++mFrames % 600 != 0)
                    return;
                Log(Debug::Verbose) << "Occlusion culling: last drawn " << mDrawnOccluders << " occluders ("
                                    << mDrawnTriangles << " triangles, " << mHiddenOccluders / 600
                                    << " a frame skipped as hidden by nearer ones); per frame " << mSumCulled / 600 << " of "
                                    << mSumTested / 600 << " tested culled, drawing " << mDrawTime / 600 * 1000
                                    << " ms, tests " << mTestTime / 600 * 1000 << " ms; rested "
                                    << std::min(mRestedFrames, 600u) * 100 / 600 << "% of frames";
                mSumTested = mSumCulled = 0;
                mDrawTime = mTestTime = 0;
                mRestedFrames = 0;
                mHiddenOccluders = 0;
            }

            unsigned mFrame = ~0u;
            std::vector<Occluder> mRecording; // this frame's, drawn next frame
            std::vector<Occluder> mOccluders; // last frame's
            std::unordered_set<const osg::Drawable*> mRecordedDrawables;
            std::unordered_map<const osgUtil::StateGraph*, StateVerdict> mVerdicts;
            osg::Matrix mView, mInverseView;
            bool mViewKnown = false, mDrawn = false, mUsable = false;
            Helpers& helpers()
            {
                // half the cores, less this one: the draw thread and the engine's own threads need the rest
                if (!mHelpers)
                    mHelpers = std::make_unique<Helpers>(std::clamp(std::thread::hardware_concurrency() / 2, 1u, 4u) - 1);
                return *mHelpers;
            }

            DepthBuffer mBuffer;
            std::vector<osg::Vec3f> mViewVertices;
            std::vector<std::pair<const Occluder*, osg::Matrixf>> mToDraw;
            std::unique_ptr<Helpers> mHelpers;
            OccluderSimplifier mSimplifier;
            unsigned mTested = 0, mCulled = 0, mDrawnOccluders = 0, mDrawnTriangles = 0, mFrames = 0;
            unsigned mHiddenOccluders = 0;
            std::size_t mSumTested = 0, mSumCulled = 0;
            double mDrawTime = 0, mTestTime = 0;

            static constexpr unsigned sWindow = 30, sFirstRest = 30, sLongestRest = 480;
            unsigned mRestFrames = 0, mRest = sFirstRest, mRestedFrames = 0;
            unsigned mWindowFrames = 0;
            std::size_t mWindowTested = 0, mWindowCulled = 0;
            bool mProbing = false;

            // about 30 seconds at 40 FPS between trials, and resting after one that finds it slower
            static constexpr int sTrialEvery = 1200, sTrialTurn = 24, sTrialTurns = 8, sTrialSettle = 6;
            double mLastTime = -1;
            int mTrialFrame = -1, mSinceTrial = sTrialEvery - 300; // the first soon after the start
            bool mTrialOff = false, mMeasureLast = false, mTrialAfterRest = false;
            double mTrialTime[2] = {}; // without, with
            unsigned mTrialFrames[2] = {};
            unsigned mTrials = 0;
        };

        OcclusionCulling& occlusion()
        {
            static OcclusionCulling instance;
            return instance;
        }

        bool occlusionEnabled()
        {
            static const bool enabled = [] {
                const bool on = occlusionSettings().mEnabled;
                if (on)
                    Log(Debug::Info) << "Occlusion culling on: " << occlusionSettings().mWidth << " px buffer, occluders "
                                     << occlusionSettings().mOccluderMinSize << " units and larger, "
                                     << occlusionSettings().mTriangleBudget << " triangles per frame";
                return on;
            }();
            return enabled;
        }
    }

    bool occlusionNodeOccluded(osg::CullStack& cullStack, const osg::Node& node)
    {
        return occlusionEnabled() && occlusion().nodeOccluded(cullStack, node);
    }

    bool occlusionBoxOccluded(osg::CullStack& cullStack, const osg::BoundingBox& box)
    {
        return occlusionEnabled() && occlusion().boxOccluded(cullStack, box);
    }

    void occlusionDrawableAdded(osg::NodeVisitor& cullVisitor, osg::Drawable& drawable, const osg::Matrix& modelView)
    {
        if (occlusionEnabled())
            occlusion().drawableAdded(static_cast<osgUtil::CullVisitor&>(cullVisitor), drawable, modelView);
    }
}
