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
#include "support.hpp"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <limits>
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
                std::clamp(settingInt("Camera", "occlusion buffer width", 256), 64, 1024) & ~7,
                std::max(0.f, settingFloat("Camera", "occluder min size", 300.f)),
                std::clamp(settingInt("Camera", "occluder triangle budget", 20000), 1000, 2000000) };
            return settings;
        }

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

        struct Occluder
        {
            osg::ref_ptr<const osg::Geometry> mGeometry;
            osg::Matrix mWorld;
            bool mCullBack;
            bool mCullFront;
            float mPriority; // radius over distance: how much of the view it can cover
            unsigned mTriangles;
        };

        struct ScreenPoint
        {
            float mX, mY, mInvDepth;
        };

        class DepthBuffer
        {
        public:
            void reset(int width, int height)
            {
                mWidth = width;
                mHeight = height;
                mDepth.assign(static_cast<std::size_t>(width) * height, std::numeric_limits<float>::infinity());
                mTilesX = (width + sTile - 1) / sTile;
                mTilesY = (height + sTile - 1) / sTile;
                mTileMax.assign(static_cast<std::size_t>(mTilesX) * mTilesY, std::numeric_limits<float>::infinity());
            }

            void setCamera(const osg::Matrix& projection)
            {
                mProjection = projection;
            }

            // false: behind the camera, or the projection is not a perspective one
            bool project(const osg::Vec3f& view, float& x, float& y) const
            {
                const osg::Matrix& p = mProjection;
                const double w = view.x() * p(0, 3) + view.y() * p(1, 3) + view.z() * p(2, 3) + p(3, 3);
                if (!(w > 1e-6))
                    return false;
                const double cx = view.x() * p(0, 0) + view.y() * p(1, 0) + view.z() * p(2, 0) + p(3, 0);
                const double cy = view.x() * p(0, 1) + view.y() * p(1, 1) + view.z() * p(2, 1) + p(3, 1);
                x = static_cast<float>((cx / w * 0.5 + 0.5) * mWidth);
                y = static_cast<float>((cy / w * 0.5 + 0.5) * mHeight);
                return std::isfinite(x) && std::isfinite(y);
            }

            // A triangle in view space (depth is -z), clipped at the near plane
            void drawTriangle(const osg::Vec3f& a, const osg::Vec3f& b, const osg::Vec3f& c)
            {
                const float da = -a.z(), db = -b.z(), dc = -c.z();
                if (da < sNear && db < sNear && dc < sNear)
                    return;
                if (da >= sNear && db >= sNear && dc >= sNear)
                {
                    drawClipped(a, b, c);
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
                    drawClipped(out[0], out[1], out[2]);
                if (n == 4)
                    drawClipped(out[0], out[2], out[3]);
            }

            void finish()
            {
                for (int ty = 0; ty < mTilesY; ++ty)
                    for (int tx = 0; tx < mTilesX; ++tx)
                    {
                        float farthest = 0;
                        for (int y = ty * sTile; y < std::min(mHeight, (ty + 1) * sTile); ++y)
                            for (int x = tx * sTile; x < std::min(mWidth, (tx + 1) * sTile); ++x)
                                farthest = std::max(farthest, mDepth[y * mWidth + x]);
                        mTileMax[ty * mTilesX + tx] = farthest;
                    }
            }

            // Bounds in view space, as corners; true when they are behind what is in the buffer everywhere
            bool hidden(const osg::Vec3f* corners, int count) const
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
                    float x, y;
                    if (!project(corners[i], x, y))
                        return false;
                    minX = std::min(minX, x);
                    maxX = std::max(maxX, x);
                    minY = std::min(minY, y);
                    maxY = std::max(maxY, y);
                }
                // every pixel the rectangle touches, and one more around it
                const int x0 = std::max(0, static_cast<int>(std::floor(minX)) - 1);
                const int x1 = std::min(mWidth - 1, static_cast<int>(std::ceil(maxX)));
                const int y0 = std::max(0, static_cast<int>(std::floor(minY)) - 1);
                const int y1 = std::min(mHeight - 1, static_cast<int>(std::ceil(maxY)));
                if (x0 > x1 || y0 > y1)
                    return false;
                for (int ty = y0 / sTile; ty <= y1 / sTile; ++ty)
                    for (int tx = x0 / sTile; tx <= x1 / sTile; ++tx)
                    {
                        if (mTileMax[ty * mTilesX + tx] < nearest)
                            continue;
                        for (int y = std::max(y0, ty * sTile); y <= std::min(y1, ty * sTile + sTile - 1); ++y)
                            for (int x = std::max(x0, tx * sTile); x <= std::min(x1, tx * sTile + sTile - 1); ++x)
                                if (!(mDepth[y * mWidth + x] < nearest))
                                    return false;
                    }
                return true;
            }

            int width() const { return mWidth; }
            int height() const { return mHeight; }
            const std::vector<float>& depths() const { return mDepth; }

        private:
            void drawClipped(const osg::Vec3f& a, const osg::Vec3f& b, const osg::Vec3f& c)
            {
                ScreenPoint p[3];
                const osg::Vec3f* v[3] = { &a, &b, &c };
                for (int i = 0; i < 3; ++i)
                {
                    if (!project(*v[i], p[i].mX, p[i].mY))
                        return;
                    p[i].mInvDepth = 1.f / -v[i]->z();
                }
                const float area = (p[1].mX - p[0].mX) * (p[2].mY - p[0].mY) - (p[2].mX - p[0].mX) * (p[1].mY - p[0].mY);
                if (std::abs(area) < 1e-8f)
                    return;
                if (area < 0)
                    std::swap(p[1], p[2]);
                const float invArea = 1.f / std::abs(area);

                const int x0 = std::max(0, static_cast<int>(std::ceil(std::min({ p[0].mX, p[1].mX, p[2].mX }) - 0.5f)));
                const int x1
                    = std::min(mWidth - 1, static_cast<int>(std::floor(std::max({ p[0].mX, p[1].mX, p[2].mX }) - 0.5f)));
                const int y0 = std::max(0, static_cast<int>(std::ceil(std::min({ p[0].mY, p[1].mY, p[2].mY }) - 0.5f)));
                const int y1
                    = std::min(mHeight - 1, static_cast<int>(std::floor(std::max({ p[0].mY, p[1].mY, p[2].mY }) - 0.5f)));
                if (x0 > x1 || y0 > y1)
                    return;

                // 1/depth is linear on the screen: a plane, whose least value over a pixel is at one of its corners
                const float e1x = p[1].mX - p[0].mX, e1y = p[1].mY - p[0].mY;
                const float e2x = p[2].mX - p[0].mX, e2y = p[2].mY - p[0].mY;
                const float d1 = p[1].mInvDepth - p[0].mInvDepth, d2 = p[2].mInvDepth - p[0].mInvDepth;
                const float da = (d1 * e2y - d2 * e1y) * invArea;
                const float db = (d2 * e1x - d1 * e2x) * invArea;
                const float slack = 0.5f * (std::abs(da) + std::abs(db));
                const float leastInvDepth = std::min({ p[0].mInvDepth, p[1].mInvDepth, p[2].mInvDepth });

                // edge functions, positive inside (counter-clockwise on the screen after the swap)
                const auto edge = [](const ScreenPoint& s, const ScreenPoint& t, float x, float y) {
                    return (t.mX - s.mX) * (y - s.mY) - (t.mY - s.mY) * (x - s.mX);
                };
                for (int y = y0; y <= y1; ++y)
                {
                    const float cy = y + 0.5f;
                    for (int x = x0; x <= x1; ++x)
                    {
                        const float cx = x + 0.5f;
                        if (edge(p[0], p[1], cx, cy) < 0 || edge(p[1], p[2], cx, cy) < 0
                            || edge(p[2], p[0], cx, cy) < 0)
                            continue;
                        const float invDepth
                            = std::max(leastInvDepth, p[0].mInvDepth + da * (cx - p[0].mX) + db * (cy - p[0].mY) - slack);
                        if (!(invDepth > 0))
                            continue;
                        float& stored = mDepth[y * mWidth + x];
                        stored = std::min(stored, 1.f / invDepth);
                    }
                }
            }

            int mWidth = 0, mHeight = 0, mTilesX = 0, mTilesY = 0;
            std::vector<float> mDepth; // view depth of the nearest occluder, farthest within the pixel
            std::vector<float> mTileMax;
            osg::Matrix mProjection;
        };

        // Draws the triangles of an occluder, with vertices already in view space
        struct TriangleDrawer
        {
            DepthBuffer* mBuffer = nullptr;
            const std::vector<osg::Vec3f>* mView = nullptr;
            bool mCullBack = false, mCullFront = false;

            void operator()(unsigned i1, unsigned i2, unsigned i3)
            {
                const std::vector<osg::Vec3f>& v = *mView;
                if (i1 >= v.size() || i2 >= v.size() || i3 >= v.size())
                    return;
                const osg::Vec3f& a = v[i1];
                const osg::Vec3f& b = v[i2];
                const osg::Vec3f& c = v[i3];
                if (mCullBack || mCullFront)
                {
                    // counter-clockwise seen from the camera (at the origin) is the front, as on the screen, so a
                    // mirroring transform is already accounted for
                    const bool front = ((b - a) ^ (c - a)) * a < 0;
                    if ((front && mCullFront) || (!front && mCullBack))
                        return;
                }
                mBuffer->drawTriangle(a, b, c);
            }
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
                    newFrame(frame);
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
                return count(mBuffer.hidden(corners, 8));
            }

            bool drawableOccluded(osg::CullStack& cullStack, const osg::BoundingBox& box)
            {
                osgUtil::CullVisitor* cv = sceneCull(cullStack);
                if (cv == nullptr || !ready(*cv))
                    return false;
                const osg::Matrix& modelView = *cv->getModelViewMatrix();
                osg::Vec3f corners[8];
                for (int i = 0; i < 8; ++i)
                    corners[i] = box.corner(i) * modelView;
                return count(mBuffer.hidden(corners, 8));
            }

            void drawableAdded(osgUtil::CullVisitor& cv, osg::Drawable& drawable, const osg::Matrix& modelView)
            {
                if (sceneCull(cv) == nullptr)
                    return;
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
                const unsigned triangles = triangleCount(*geometry);
                if (triangles == 0)
                    return;
                const float distance = std::max(1.f, (box.center() * modelView).length() - radius);
                mRecording.push_back({ geometry, modelView * mInverseView, verdict.mCullBack, verdict.mCullFront,
                    radius / distance, triangles });
            }

        private:
            void newFrame(unsigned frame)
            {
                logStatistics();
                mFrame = frame;
                mOccluders.swap(mRecording);
                mRecording.clear();
                mRecordedDrawables.clear();
                mVerdicts.clear(); // the state graphs are rebuilt (and may be freed) every frame
                mDrawn = false;
                mTested = mCulled = 0;
                mViewKnown = false;
            }

            // Draws the buffer at the first test of the frame; false when it cannot be used
            bool ready(osgUtil::CullVisitor& cv)
            {
                if (!mDrawn)
                {
                    mDrawn = true;
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

                int budget = settings.mTriangleBudget;
                mDrawnOccluders = 0;
                mDrawnTriangles = 0;
                osg::TriangleIndexFunctor<TriangleDrawer> drawer;
                drawer.mBuffer = &mBuffer;
                drawer.mView = &mViewVertices;
                for (const Occluder& occluder : mOccluders)
                {
                    if (static_cast<int>(occluder.mTriangles) > budget)
                        continue;
                    budget -= occluder.mTriangles;
                    const osg::Matrix toView = occluder.mWorld * mView;
                    const auto& vertices = static_cast<const osg::Vec3Array&>(*occluder.mGeometry->getVertexArray());
                    mViewVertices.resize(vertices.size());
                    for (std::size_t i = 0; i < vertices.size(); ++i)
                        mViewVertices[i] = vertices[i] * toView;
                    drawer.mCullBack = occluder.mCullBack;
                    drawer.mCullFront = occluder.mCullFront;
                    occluder.mGeometry->accept(drawer);
                    ++mDrawnOccluders;
                    mDrawnTriangles += occluder.mTriangles;
                }
                mBuffer.finish();
                mOccluders.clear(); // let go of the geometry; next frame's are being recorded
                mDrawTime += std::chrono::duration<double>(std::chrono::steady_clock::now() - start).count();
            }

            bool count(bool hidden)
            {
                ++mTested;
                if (hidden)
                    ++mCulled;
                return hidden;
            }

            void logStatistics()
            {
                mSumTested += mTested;
                mSumCulled += mCulled;
                if (++mFrames % 600 != 0)
                    return;
                Log(Debug::Verbose) << "Occlusion culling: " << mDrawnOccluders << " occluders (" << mDrawnTriangles
                                    << " triangles), " << mSumCulled / 600 << " of " << mSumTested / 600
                                    << " tested culled per frame, " << mDrawTime / 600 * 1000 << " ms per frame";
                mSumTested = mSumCulled = 0;
                mDrawTime = 0;
            }

            unsigned mFrame = ~0u;
            std::vector<Occluder> mRecording; // this frame's, drawn next frame
            std::vector<Occluder> mOccluders; // last frame's
            std::unordered_set<const osg::Drawable*> mRecordedDrawables;
            std::unordered_map<const osgUtil::StateGraph*, StateVerdict> mVerdicts;
            osg::Matrix mView, mInverseView;
            bool mViewKnown = false, mDrawn = false, mUsable = false;
            DepthBuffer mBuffer;
            std::vector<osg::Vec3f> mViewVertices;
            unsigned mTested = 0, mCulled = 0, mDrawnOccluders = 0, mDrawnTriangles = 0, mFrames = 0;
            std::size_t mSumTested = 0, mSumCulled = 0;
            double mDrawTime = 0;
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

    bool occlusionDrawableOccluded(osg::CullStack& cullStack, const osg::BoundingBox& box)
    {
        return occlusionEnabled() && occlusion().drawableOccluded(cullStack, box);
    }

    void occlusionDrawableAdded(osg::NodeVisitor& cullVisitor, osg::Drawable& drawable, const osg::Matrix& modelView)
    {
        if (occlusionEnabled())
            occlusion().drawableAdded(static_cast<osgUtil::CullVisitor&>(cullVisitor), drawable, modelView);
    }
}
