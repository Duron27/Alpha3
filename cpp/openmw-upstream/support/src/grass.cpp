// Grass of the experimental graphics ([Experimental] grass, grass density, grass height, grass distance, grass wind):
// blades growing out of the terrain where a grassy texture is painted (the material rules' grass), in clumps, swaying
// in the wind (experimental/grass.glsl).
//
// When the scene camera's cull reaches a terrain chunk (the drawableAdded hook, at its first pass), the chunk is split
// into tiles of about 512 units and the tiles within the grass distance and the view are added to the render graph
// beside it, under the same lights and fog. A tile's blades are made on a thread of their own, from the chunk's
// vertices (the ground's height) and its blendmaps (where the grassy layers are painted, and how much); until then the
// tile has none. Tiles not drawn for a while are let go of.
//
// No blade grows through what stands on the ground: the solid things the scene camera draws (rocks, walls, roots;
// object paging's merged chunks too) are noted as they are culled, and a tile's blades leave out the places where one
// of their surfaces is just above the ground, lower than a blade's tip. A tile made before such a thing was first seen
// is made again (drawn as it was until then).
#include "support.hpp"

#include "experimentalsources.hpp"
#include "generatedmaps.hpp"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <condition_variable>
#include <cstring>
#include <deque>
#include <limits>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <thread>
#include <unordered_map>
#include <vector>

#include <osg/BlendFunc>
#include <osg/Geometry>
#include <osg/Program>
#include <osg/Shader>
#include <osg/Texture2D>
#include <osg/Viewport>
#include <osg/TriangleIndexFunctor>
#include <osg/observer_ptr>
#include <osgUtil/CullVisitor>
#include <osgUtil/RenderBin>

#include <components/debug/debuglog.hpp>
#include <components/terrain/compositemaprenderer.hpp>
#include <components/terrain/terraindrawable.hpp>
#include <components/vfs/manager.hpp>
#include <components/vfs/pathutil.hpp>

namespace Alpha3
{
    namespace
    {
        constexpr float sTileSize = 512.f;
        constexpr float sAreaPerBlade = 140.f; // square units at density 1
        constexpr int sBladesPerClump = 8;
        constexpr unsigned sForgetAfterFrames = 300;
        constexpr unsigned sForgetObstaclesAfterFrames = 3000;
        constexpr float sBlockedCell = 8.f; // the grid of where things stand on a tile
        const std::string sSceneCamera = "SceneCam";

        const VFS::Manager* sVfs = nullptr;

        // ---- random numbers that are the same every run for the same place
        struct Random
        {
            std::uint32_t mState;
            explicit Random(std::uint32_t seed)
                : mState(seed * 747796405u + 2891336453u)
            {
            }
            float next()
            {
                mState = mState * 747796405u + 2891336453u;
                std::uint32_t word = ((mState >> ((mState >> 28u) + 4u)) ^ mState) * 277803737u;
                word = (word >> 22u) ^ word;
                return static_cast<float>(word >> 8) / 16777216.f;
            }
        };

        std::uint32_t hashPosition(float x, float y)
        {
            const auto ix = static_cast<std::int32_t>(std::floor(x));
            const auto iy = static_cast<std::int32_t>(std::floor(y));
            std::uint32_t h = static_cast<std::uint32_t>(ix) * 73856093u ^ static_cast<std::uint32_t>(iy) * 19349663u;
            return h ^ (h >> 13);
        }

        // ---- what a grassy layer looks like, from its texture

        struct Layer
        {
            bool mGrass = false;
            osg::Vec3f mRoot, mTip; // blade colours
        };

        std::string stemOf(const std::string& path)
        {
            std::string stem = path.substr(path.find_last_of("/\\") + 1);
            stem = stem.substr(0, stem.rfind('.'));
            for (std::string_view suffix : { "_diffusespec" })
                if (stem.size() > suffix.size() && stem.compare(stem.size() - suffix.size(), suffix.size(), suffix) == 0)
                    stem.resize(stem.size() - suffix.size());
            for (char& c : stem)
                c = static_cast<char>(std::tolower(static_cast<unsigned char>(c)));
            return stem;
        }

        // On the worker thread: whether the texture is grass by the material rules, and its blade colours from its
        // average, cached by file name
        Layer layerOf(const std::string& file)
        {
            static std::mutex mutex;
            static std::map<std::string, Layer> layers;
            {
                std::lock_guard lock(mutex);
                if (const auto found = layers.find(file); found != layers.end())
                    return found->second;
            }
            Layer layer;
            const ExperimentalSettings& s = experimentalSettings();
            static const std::vector<MaterialRule> rules
                = parseMaterialRules(s.mRules.empty() ? defaultMaterialRules() : s.mRules);
            Material material = Material::None;
            layer.mGrass = materialOf(rules, stemOf(file), material) && material == Material::Grass;
            if (layer.mGrass)
            {
                osg::Vec3f average(0.25f, 0.33f, 0.12f);
                try
                {
                    int width = 0, height = 0;
                    std::vector<unsigned char> rgba;
                    if (sVfs != nullptr)
                    {
                        Files::IStreamPtr stream = sVfs->get(VFS::Path::Normalized(file));
                        const std::string extension = file.substr(file.rfind('.') + 1);
                        if (readTextureForCpu(*stream, extension, width, height, rgba) && width > 0)
                        {
                            double sum[3] = {};
                            const std::size_t texels = static_cast<std::size_t>(width) * height;
                            for (std::size_t i = 0; i < texels; ++i)
                                for (int c = 0; c < 3; ++c)
                                    sum[c] += rgba[i * 4 + c];
                            average = osg::Vec3f(sum[0], sum[1], sum[2]) / (255.f * texels);
                        }
                    }
                }
                catch (const std::exception& e)
                {
                    Log(Debug::Warning) << "Grass: " << file << ": " << e.what();
                }
                // the texture is the ground seen from above, mostly shadow between the blades: the blades are lighter
                // and more saturated at the tips, darker at the root
                const float grey = (average.x() + average.y() + average.z()) / 3.f;
                const osg::Vec3f saturated = average + (average - osg::Vec3f(grey, grey, grey)) * 0.6f;
                layer.mTip = osg::Vec3f(std::min(saturated.x() * 1.7f, 1.f), std::min(saturated.y() * 1.7f, 1.f),
                    std::min(saturated.z() * 1.5f, 1.f));
                layer.mRoot = saturated * 0.45f;
                Log(Debug::Info) << "Grass: " << file << " grows grass";
            }
            std::lock_guard lock(mutex);
            layers.emplace(file, layer);
            return layer;
        }

        // ---- a chunk's ground and where grass grows on it

        struct ChunkGround
        {
            osg::ref_ptr<const osg::Vec3Array> mVertices;
            int mGridSize = 0; // vertices along a side; vertex col * n + row is at u = col / (n - 1), v = row / (n - 1)
            struct Pass
            {
                std::string mTexture;
                osg::ref_ptr<const osg::Image> mBlendmap; // null: the only layer, everywhere
                osg::Matrixf mBlendmapMatrix;
            };
            std::vector<Pass> mPasses;

            osg::Vec3f position(float u, float v) const
            {
                const float fc = std::clamp(u, 0.f, 1.f) * (mGridSize - 1);
                const float fr = std::clamp(v, 0.f, 1.f) * (mGridSize - 1);
                const int c = std::min(static_cast<int>(fc), mGridSize - 2);
                const int r = std::min(static_cast<int>(fr), mGridSize - 2);
                const float a = fc - c, b = fr - r;
                const auto at = [&](int cc, int rr) { return (*mVertices)[static_cast<std::size_t>(cc) * mGridSize + rr]; };
                return (at(c, r) * (1 - a) + at(c + 1, r) * a) * (1 - b) + (at(c, r + 1) * (1 - a) + at(c + 1, r + 1) * a) * b;
            }

            static float blend(const osg::Image& image, const osg::Matrixf& matrix, float u, float v)
            {
                const osg::Vec3f uv = osg::Vec3f(u, v, 0.f) * matrix;
                const int width = image.s(), height = image.t();
                const float fx = std::clamp(uv.x(), 0.f, 1.f) * width - 0.5f;
                const float fy = std::clamp(uv.y(), 0.f, 1.f) * height - 0.5f;
                const int x = std::clamp(static_cast<int>(std::floor(fx)), 0, width - 1);
                const int y = std::clamp(static_cast<int>(std::floor(fy)), 0, height - 1);
                const int x1 = std::min(x + 1, width - 1), y1 = std::min(y + 1, height - 1);
                const float a = std::clamp(fx - x, 0.f, 1.f), b = std::clamp(fy - y, 0.f, 1.f);
                const auto texel = [&](int xx, int yy) { return *image.data(xx, yy) / 255.f; };
                return (texel(x, y) * (1 - a) + texel(x1, y) * a) * (1 - b) + (texel(x, y1) * (1 - a) + texel(x1, y1) * a) * b;
            }
        };

        // ---- the tiles

        enum class TileState
        {
            None,
            Queued, // its first blades are being made
            Made, // the worker is done: mMade is to be drawn
            Ready,
            Remaking, // drawn as it is while new blades are made
        };

        struct Tile
        {
            std::atomic<TileState> mState{ TileState::None };
            float mU0 = 0, mV0 = 0, mU1 = 0, mV1 = 0; // its part of the chunk
            osg::BoundingBox mBox; // the ground under it, from the chunk's corners; the blades' own is the geometry's
            osg::BoundingBox mWorldBox; // the same in the world
            osg::ref_ptr<osg::Geometry> mGeometry; // drawn (cull thread's); null: no grass on it
            osg::ref_ptr<osg::Geometry> mMade; // handed over by the worker
            osg::Vec3f mRoot, mTip;
            unsigned mLastDrawn = 0;
            bool mStale = false; // something new stands on it: make its blades again
        };

        struct Chunk
        {
            osg::observer_ptr<Terrain::TerrainDrawable> mDrawable;
            std::shared_ptr<const ChunkGround> mGround;
            std::vector<std::unique_ptr<Tile>> mTiles;
            osg::Matrix mToWorld;
            unsigned mLastSeen = 0;
        };

        // ---- what stands on the ground

        struct Obstacle
        {
            osg::ref_ptr<const osg::Geometry> mGeometry;
            osg::Matrix mToChunk; // its vertices into the chunk's space
        };

        // Where on a tile a thing's surface is just above the ground and below the tallest blade's tip: blades there
        // would come out through it. Its triangles are sampled (every few units, so walls and steep rock count) onto a
        // grid over the tile, which is then grown by a cell.
        class Blocked
        {
        public:
            Blocked(const ChunkGround& ground, const Tile& tile, const std::vector<Obstacle>& obstacles, float top)
                : mTop(top)
            {
                if (obstacles.empty())
                    return;
                const float margin = 2 * sBlockedCell + 16.f; // blades of a clump spread past the tile's edge
                mX0 = tile.mBox.xMin() - margin;
                mY0 = tile.mBox.yMin() - margin;
                mWidth = static_cast<int>(std::ceil((tile.mBox.xMax() + margin - mX0) / sBlockedCell));
                mHeight = static_cast<int>(std::ceil((tile.mBox.yMax() + margin - mY0) / sBlockedCell));
                mGround.resize(static_cast<std::size_t>(mWidth) * mHeight);
                mCells.assign(mGround.size(), 0);
                const osg::Vec3f c0 = ground.position(0.f, 0.f), c1 = ground.position(1.f, 1.f);
                const float spanX = c1.x() - c0.x(), spanY = c1.y() - c0.y();
                if (std::abs(spanX) < 1.f || std::abs(spanY) < 1.f)
                    return;
                mLow = std::numeric_limits<float>::max();
                mHigh = -mLow;
                for (int j = 0; j < mHeight; ++j)
                    for (int i = 0; i < mWidth; ++i)
                    {
                        const float x = mX0 + (i + 0.5f) * sBlockedCell, y = mY0 + (j + 0.5f) * sBlockedCell;
                        const float z = ground.position((x - c0.x()) / spanX, (y - c0.y()) / spanY).z();
                        mGround[static_cast<std::size_t>(j) * mWidth + i] = z;
                        mLow = std::min(mLow, z);
                        mHigh = std::max(mHigh, z);
                    }
                for (const Obstacle& obstacle : obstacles)
                    add(obstacle);
                // grown by a cell: a sample can fall just beside a blade's root
                std::vector<unsigned char> grown = mCells;
                for (int j = 0; j < mHeight; ++j)
                    for (int i = 0; i < mWidth; ++i)
                        if (mCells[static_cast<std::size_t>(j) * mWidth + i])
                            for (int dj = -1; dj <= 1; ++dj)
                                for (int di = -1; di <= 1; ++di)
                                {
                                    const int ii = i + di, jj = j + dj;
                                    if (ii >= 0 && jj >= 0 && ii < mWidth && jj < mHeight)
                                        grown[static_cast<std::size_t>(jj) * mWidth + ii] = 1;
                                }
                mCells = std::move(grown);
            }

            bool at(float x, float y) const
            {
                if (mCells.empty())
                    return false;
                const int i = static_cast<int>(std::floor((x - mX0) / sBlockedCell));
                const int j = static_cast<int>(std::floor((y - mY0) / sBlockedCell));
                return i >= 0 && j >= 0 && i < mWidth && j < mHeight && mCells[static_cast<std::size_t>(j) * mWidth + i];
            }

        private:
            struct Triangles
            {
                Blocked* mBlocked = nullptr;
                const osg::Vec3Array* mVertices = nullptr;
                const osg::Matrix* mToChunk = nullptr;
                void operator()(unsigned a, unsigned b, unsigned c)
                {
                    if (a < mVertices->size() && b < mVertices->size() && c < mVertices->size())
                        mBlocked->triangle((*mVertices)[a] * *mToChunk, (*mVertices)[b] * *mToChunk,
                            (*mVertices)[c] * *mToChunk);
                }
            };

            void add(const Obstacle& obstacle)
            {
                const auto* vertices = dynamic_cast<const osg::Vec3Array*>(obstacle.mGeometry->getVertexArray());
                if (vertices == nullptr)
                    return;
                osg::TriangleIndexFunctor<Triangles> triangles;
                triangles.mBlocked = this;
                triangles.mVertices = vertices;
                triangles.mToChunk = &obstacle.mToChunk;
                obstacle.mGeometry->accept(triangles);
            }

            void triangle(const osg::Vec3f& a, const osg::Vec3f& b, const osg::Vec3f& c)
            {
                const float x1 = mX0 + mWidth * sBlockedCell, y1 = mY0 + mHeight * sBlockedCell;
                if (std::max({ a.x(), b.x(), c.x() }) < mX0 || std::min({ a.x(), b.x(), c.x() }) > x1
                    || std::max({ a.y(), b.y(), c.y() }) < mY0 || std::min({ a.y(), b.y(), c.y() }) > y1
                    || std::max({ a.z(), b.z(), c.z() }) < mLow || std::min({ a.z(), b.z(), c.z() }) > mHigh + mTop)
                    return;
                // samples no more than half a cell apart along both sides from a (a long one is cut short: such a
                // triangle is a floor or a wall far bigger than a tile, and its samples spread out)
                const float longest = std::max({ (b - a).length(), (c - a).length(), (c - b).length() });
                const int steps = std::clamp(static_cast<int>(std::ceil(longest / (sBlockedCell * 0.5f))), 1, 160);
                for (int i = 0; i <= steps; ++i)
                    for (int j = 0; i + j <= steps; ++j)
                    {
                        const float s = static_cast<float>(i) / steps, t = static_cast<float>(j) / steps;
                        mark(a + (b - a) * s + (c - a) * t);
                    }
            }

            void mark(const osg::Vec3f& p)
            {
                const int i = static_cast<int>(std::floor((p.x() - mX0) / sBlockedCell));
                const int j = static_cast<int>(std::floor((p.y() - mY0) / sBlockedCell));
                if (i < 0 || j < 0 || i >= mWidth || j >= mHeight)
                    return;
                const std::size_t cell = static_cast<std::size_t>(j) * mWidth + i;
                const float above = p.z() - mGround[cell];
                if (above > 0.5f && above < mTop)
                    mCells[cell] = 1;
            }

            float mTop;
            float mX0 = 0, mY0 = 0, mLow = 0, mHigh = 0;
            int mWidth = 0, mHeight = 0;
            std::vector<float> mGround; // the ground's height at each cell's middle
            std::vector<unsigned char> mCells;
        };

        osg::ref_ptr<osg::Geometry> makeBlades(const ChunkGround& ground, Tile& tile, const std::vector<Obstacle>& obstacles)
        {
            std::vector<std::pair<Layer, const ChunkGround::Pass*>> grassy;
            for (const ChunkGround::Pass& pass : ground.mPasses)
            {
                const Layer layer = layerOf(pass.mTexture);
                if (layer.mGrass)
                    grassy.emplace_back(layer, &pass);
            }
            if (grassy.empty())
                return nullptr;
            tile.mRoot = grassy.front().first.mRoot;
            tile.mTip = grassy.front().first.mTip;

            const ExperimentalSettings& s = experimentalSettings();
            const osg::Vec3f corner0 = ground.position(tile.mU0, tile.mV0);
            const osg::Vec3f corner1 = ground.position(tile.mU1, tile.mV1);
            const float width = std::abs(corner1.x() - corner0.x()), depth = std::abs(corner1.y() - corner0.y());
            const float area = std::max(width * depth, 1.f);
            const int clumps = static_cast<int>(area / sAreaPerBlade * s.mGrassDensity / sBladesPerClump);
            const float uPerUnit = (tile.mU1 - tile.mU0) / std::max(width, 1.f);
            const float vPerUnit = (tile.mV1 - tile.mV0) / std::max(depth, 1.f);
            const Blocked blocked(ground, tile, obstacles, (s.mGrassVariety ? 120.f : 80.f) * s.mGrassHeight);

            osg::ref_ptr<osg::Vec3Array> roots = new osg::Vec3Array;
            osg::ref_ptr<osg::Vec4Array> shape = new osg::Vec4Array;
            osg::ref_ptr<osg::Vec4Array> extra = new osg::Vec4Array;
            osg::ref_ptr<osg::DrawElementsUShort> triangles = new osg::DrawElementsUShort(GL_TRIANGLES);
            Random random(hashPosition(corner0.x(), corner0.y()));
            osg::BoundingBox box;
            for (int clump = 0; clump < clumps && roots->size() + sBladesPerClump * 5 < 65535; ++clump)
            {
                const float cu = tile.mU0 + (tile.mU1 - tile.mU0) * random.next();
                const float cv = tile.mV0 + (tile.mV1 - tile.mV0) * random.next();
                // how much of this place is grassy, by the blendmaps; a clump grows where a random number is under it
                float weight = 0.f;
                std::size_t which = 0;
                float strongest = -1.f;
                for (std::size_t i = 0; i < grassy.size(); ++i)
                {
                    const ChunkGround::Pass& pass = *grassy[i].second;
                    const float w = pass.mBlendmap
                        ? ChunkGround::blend(*pass.mBlendmap, pass.mBlendmapMatrix, cu, cv)
                        : 1.f;
                    weight += w;
                    if (w > strongest)
                    {
                        strongest = w;
                        which = i;
                    }
                }
                if (random.next() > std::min(weight, 1.f) * 1.2f - 0.1f)
                    continue;
                const Layer& layer = grassy[which].first;
                tile.mRoot = layer.mRoot;
                tile.mTip = layer.mTip;

                float clumpHeight = (0.75f + 0.5f * random.next()) * s.mGrassHeight;
                // some clumps are tall thin seed stalks, some short and broad
                const float kind = random.next();
                float kindWidth = 1.f, kindLean = 0.f, seed = 0.f;
                // and each clump a shade (added to the tone, 4 apart): normal, darker, lighter, or now and then yellow
                const float shadeRoll = random.next();
                float shade = 0.f;
                if (s.mGrassVariety)
                    shade = shadeRoll < 0.015f ? 12.f : shadeRoll < 0.3f ? 4.f : shadeRoll < 0.55f ? 8.f : 0.f;
                if (s.mGrassVariety && kind < 0.12f)
                {
                    clumpHeight *= 1.5f;
                    kindWidth = 0.55f;
                    seed = 2.f;
                }
                else if (s.mGrassVariety && kind < 0.27f)
                {
                    clumpHeight *= 0.55f;
                    kindWidth = 1.9f;
                    kindLean = 0.15f;
                }
                for (int blade = 0; blade < sBladesPerClump; ++blade)
                {
                    // around the clump's middle, closer in more often
                    const float angle = random.next() * 6.2831853f;
                    const float radius = 14.f * std::sqrt(random.next());
                    const float u = cu + std::cos(angle) * radius * uPerUnit;
                    const float v = cv + std::sin(angle) * radius * vPerUnit;
                    const osg::Vec3f root = ground.position(u, v);
                    // not on steep ground (the slope from the neighbours), not under the sea
                    const osg::Vec3f across = ground.position(u + 4.f * uPerUnit, v) - root;
                    const osg::Vec3f along = ground.position(u, v + 4.f * vPerUnit) - root;
                    osg::Vec3f normal = across ^ along;
                    normal.normalize();
                    if (std::abs(normal.z()) < 0.8f || root.z() < 2.f || blocked.at(root.x(), root.y()))
                        continue;

                    const float height = (26.f + 30.f * random.next()) * clumpHeight * (0.6f + 0.4f * weight);
                    const float facing = random.next() * 6.2831853f;
                    const float leanAngle = angle + (random.next() - 0.5f) * 1.2f; // out from the clump's middle
                    const float lean = 0.12f + kindLean + 0.3f * random.next() * radius / 14.f;
                    const float tone = random.next() * 0.999f + seed + shade;
                    const float bladeWidth = (1.3f + 1.2f * random.next()) * kindWidth;

                    const auto first = static_cast<unsigned short>(roots->size());
                    const float sides[5] = { -1.f, 1.f, -1.f, 1.f, 0.f };
                    const float ups[5] = { 0.f, 0.f, 0.5f, 0.5f, 1.f };
                    for (int i = 0; i < 5; ++i)
                    {
                        roots->push_back(root);
                        shape->push_back(osg::Vec4f(sides[i], ups[i], facing, height));
                        extra->push_back(osg::Vec4f(leanAngle, lean, tone, bladeWidth));
                    }
                    for (unsigned short index : { 0, 1, 2, 1, 3, 2, 2, 3, 4 })
                        triangles->push_back(first + index);
                    box.expandBy(root);
                    box.expandBy(root + osg::Vec3f(0.f, 0.f, height * 1.1f));
                }
            }
            if (roots->empty())
                return nullptr;
            box.xMin() -= 30.f;
            box.yMin() -= 30.f;
            box.xMax() += 30.f;
            box.yMax() += 30.f;

            osg::ref_ptr<osg::Geometry> geometry = new osg::Geometry;
            geometry->setName("Alpha3 grass");
            geometry->setUseDisplayList(false);
            geometry->setUseVertexBufferObjects(true);
            geometry->setVertexArray(roots);
            geometry->setTexCoordArray(0, shape, osg::Array::BIND_PER_VERTEX);
            geometry->setTexCoordArray(1, extra, osg::Array::BIND_PER_VERTEX);
            geometry->addPrimitiveSet(triangles);
            geometry->setInitialBound(box);
            geometry->setCullingActive(false); // culled here, by the tile
            return geometry;
        }

        // ---- the worker

        struct Job
        {
            std::shared_ptr<Chunk> mChunk; // kept while the job waits or runs, whatever the cull does with it
            Tile* mTile = nullptr; // the chunk's
            std::vector<Obstacle> mObstacles; // what stands on it
        };

        struct ObstacleEntry
        {
            osg::observer_ptr<const osg::Geometry> mGeometry;
            osg::Matrix mToWorld;
            osg::BoundingBox mBox; // in the world
            unsigned mLastSeen = 0;
        };

        bool overlaps(const osg::BoundingBox& a, const osg::BoundingBox& b)
        {
            return a.xMin() <= b.xMax() && b.xMin() <= a.xMax() && a.yMin() <= b.yMax() && b.yMin() <= a.yMax()
                && a.zMin() <= b.zMax() && b.zMin() <= a.zMax();
        }

        class Grass
        {
        public:
            static Grass& get()
            {
                // never destroyed: the worker may still be running when the process ends
                static Grass* grass = new Grass;
                return *grass;
            }

            void drawableAdded(osgUtil::CullVisitor& cv, Terrain::TerrainDrawable& drawable, const osg::Matrix& modelView)
            {
                const unsigned frame = cv.getTraversalNumber();
                std::lock_guard lock(mChunksMutex);
                if (frame != mFrame)
                {
                    mFrame = frame;
                    if (frame % 120 == 0)
                        forget(frame);
                }

                std::shared_ptr<Chunk>& chunk = mChunks[&drawable];
                if (chunk && chunk->mDrawable.get() != &drawable)
                    chunk.reset(); // another drawable at the address of a deleted one
                if (!chunk)
                {
                    chunk = makeChunk(drawable, modelView * cv.getCurrentCamera()->getInverseViewMatrix());
                    if (!chunk)
                        return;
                }
                chunk->mLastSeen = frame;
                if (!chunk->mGround)
                    return; // no grassy layer

                const ExperimentalSettings& s = experimentalSettings();
                const osg::Vec3f eye = cv.getEyeLocal();
                for (const std::unique_ptr<Tile>& tile : chunk->mTiles)
                {
                    const osg::BoundingBox& box = tile->mBox;
                    const osg::Vec3f nearest(std::clamp(eye.x(), box.xMin(), box.xMax()),
                        std::clamp(eye.y(), box.yMin(), box.yMax()), std::clamp(eye.z(), box.zMin(), box.zMax()));
                    if ((nearest - eye).length() > s.mGrassDistance)
                        continue;
                    TileState state = tile->mState;
                    if (state == TileState::None)
                    {
                        tile->mStale = false;
                        tile->mState = TileState::Queued;
                        queue(Job{ chunk, tile.get(), obstaclesOn(*chunk, *tile) });
                        continue;
                    }
                    if (state == TileState::Made)
                    {
                        tile->mGeometry = std::move(tile->mMade);
                        tile->mState = state = TileState::Ready;
                    }
                    if (state == TileState::Ready && tile->mStale)
                    {
                        tile->mStale = false;
                        tile->mState = state = TileState::Remaking;
                        queue(Job{ chunk, tile.get(), obstaclesOn(*chunk, *tile) });
                    }
                    if ((state != TileState::Ready && state != TileState::Remaking) || !tile->mGeometry)
                        continue;
                    const osg::BoundingBox& blades = tile->mGeometry->getInitialBound();
                    if (cv.isCulled(blades))
                        continue;
                    tile->mLastDrawn = frame;
                    stateSet(*tile, cv.getViewport() != nullptr ? static_cast<int>(cv.getViewport()->height()) : 1080);
                    cv.pushStateSet(tile->mGeometry->getStateSet());
                    const float depth = cv.getDistanceFromEyePoint(blades.center(), false);
                    cv.addDrawableAndDepth(tile->mGeometry.get(), cv.getModelViewMatrix(), depth);
                    cv.popStateSet();
                }
            }

            // Something drawn by the scene camera that may stand on the ground: noted the first time, and the tiles under
            // it already made are made again
            void obstacleAdded(osgUtil::CullVisitor& cv, const osg::Geometry& geometry, const osg::Matrix& modelView)
            {
                const unsigned frame = cv.getTraversalNumber();
                std::lock_guard lock(mChunksMutex);
                ObstacleEntry& entry = mObstacles[&geometry];
                if (entry.mGeometry.get() == &geometry)
                {
                    entry.mLastSeen = frame;
                    return;
                }
                entry.mGeometry = &geometry;
                entry.mLastSeen = frame;
                entry.mToWorld = modelView * cv.getCurrentCamera()->getInverseViewMatrix();
                entry.mBox.init();
                const osg::BoundingBox& box = geometry.getBoundingBox();
                for (unsigned i = 0; i < 8; ++i)
                    entry.mBox.expandBy(box.corner(i) * entry.mToWorld);
                for (auto& [key, chunk] : mChunks)
                    for (const std::unique_ptr<Tile>& tile : chunk->mTiles)
                        if (tile->mState != TileState::None && overlaps(tile->mWorldBox, entry.mBox))
                            tile->mStale = true;
            }

        private:
            // what stands on a tile, for its job (with mChunksMutex held)
            std::vector<Obstacle> obstaclesOn(const Chunk& chunk, const Tile& tile)
            {
                std::vector<Obstacle> found;
                const osg::Matrix toChunk = osg::Matrix::inverse(chunk.mToWorld);
                for (const auto& [key, entry] : mObstacles)
                {
                    if (!overlaps(tile.mWorldBox, entry.mBox))
                        continue;
                    osg::ref_ptr<const osg::Geometry> geometry;
                    if (entry.mGeometry.lock(geometry))
                        found.push_back(Obstacle{ geometry, entry.mToWorld * toChunk });
                }
                return found;
            }

            Grass()
            {
                mWorker = std::thread([this] { work(); });
                mWorker.detach();
            }

            std::shared_ptr<Chunk> makeChunk(Terrain::TerrainDrawable& drawable, const osg::Matrix& toWorld)
            {
                auto chunk = std::make_shared<Chunk>();
                chunk->mDrawable = &drawable;
                chunk->mToWorld = toWorld;
                const auto* vertices = dynamic_cast<const osg::Vec3Array*>(drawable.getVertexArray());
                if (vertices == nullptr || vertices->size() < 4)
                    return chunk;
                const int gridSize = static_cast<int>(std::lround(std::sqrt(static_cast<double>(vertices->size()))));
                if (gridSize * gridSize != static_cast<int>(vertices->size()))
                    return chunk;
                auto ground = std::make_shared<ChunkGround>();
                ground->mVertices = vertices;
                ground->mGridSize = gridSize;
                for (const osg::ref_ptr<osg::StateSet>& pass : drawable.getPasses())
                {
                    ChunkGround::Pass p;
                    const auto* texture = dynamic_cast<const osg::Texture2D*>(
                        pass->getTextureAttribute(0, osg::StateAttribute::TEXTURE));
                    if (texture == nullptr || texture->getImage() == nullptr)
                        continue;
                    p.mTexture = texture->getImage()->getFileName();
                    const auto* blendmap = dynamic_cast<const osg::Texture2D*>(
                        pass->getTextureAttribute(1, osg::StateAttribute::TEXTURE));
                    if (blendmap != nullptr && blendmap->getImage() != nullptr)
                    {
                        if (blendmap->getImage()->data() == nullptr || blendmap->getImage()->getPixelFormat() != GL_ALPHA)
                            continue;
                        p.mBlendmap = blendmap->getImage();
                        if (const osg::Uniform* matrix = pass->getUniform("texMat1"))
                            matrix->get(p.mBlendmapMatrix);
                    }
                    ground->mPasses.push_back(std::move(p));
                }
                if (ground->mPasses.empty())
                    return chunk;
                chunk->mGround = ground;

                // tiles of about sTileSize units
                const osg::Vec3f corner0 = ground->position(0.f, 0.f), corner1 = ground->position(1.f, 1.f);
                const int across = std::max(1, static_cast<int>(std::lround(std::abs(corner1.x() - corner0.x()) / sTileSize)));
                const int along = std::max(1, static_cast<int>(std::lround(std::abs(corner1.y() - corner0.y()) / sTileSize)));
                const osg::BoundingBox& chunkBox = drawable.getBoundingBox();
                for (int i = 0; i < across; ++i)
                    for (int j = 0; j < along; ++j)
                    {
                        auto tile = std::make_unique<Tile>();
                        tile->mU0 = static_cast<float>(i) / across;
                        tile->mU1 = static_cast<float>(i + 1) / across;
                        tile->mV0 = static_cast<float>(j) / along;
                        tile->mV1 = static_cast<float>(j + 1) / along;
                        const osg::Vec3f a = ground->position(tile->mU0, tile->mV0);
                        const osg::Vec3f b = ground->position(tile->mU1, tile->mV1);
                        tile->mBox = osg::BoundingBox(std::min(a.x(), b.x()), std::min(a.y(), b.y()), chunkBox.zMin(),
                            std::max(a.x(), b.x()), std::max(a.y(), b.y()), chunkBox.zMax() + 80.f);
                        for (unsigned k = 0; k < 8; ++k)
                            tile->mWorldBox.expandBy(tile->mBox.corner(k) * toWorld);
                        chunk->mTiles.push_back(std::move(tile));
                    }
                return chunk;
            }

            // the program and colours of a tile, made on the cull thread the first time it is drawn
            void stateSet(Tile& tile, int screenHeight)
            {
                if (tile.mGeometry->getStateSet() != nullptr)
                    return;
                osg::ref_ptr<osg::StateSet> stateset = new osg::StateSet;
                stateset->setAttributeAndModes(program(screenHeight), osg::StateAttribute::ON);
                stateset->setMode(GL_BLEND, osg::StateAttribute::OFF);
                stateset->setMode(GL_CULL_FACE, osg::StateAttribute::OFF);
                stateset->setRenderBinDetails(0, "RenderBin");
                stateset->addUniform(new osg::Uniform("a3GrassRoot", tile.mRoot));
                stateset->addUniform(new osg::Uniform("a3GrassTip", tile.mTip));
                tile.mGeometry->setStateSet(stateset);
            }

            // (the screen's height for the antialiasing: the scene camera's viewport when the first tile is drawn)
            osg::Program* program(int screenHeight)
            {
                if (!mProgram)
                {
                    const ExperimentalSettings& s = experimentalSettings();
                    const std::string defines = "#version 120\n#define A3_GRASS_DISTANCE "
                        + std::to_string(s.mGrassDistance) + "\n#define A3_GRASS_WIND " + std::to_string(s.mGrassWind)
                        + "\n#define A3_GRASS_STORM " + std::to_string(static_cast<int>(std::lround(s.mGrassStorm * 10.f)))
                        + "\n#define A3_GRASS_VARIETY " + (s.mGrassVariety ? "1" : "0")
                        + "\n#define A3_GRASS_AA " + (s.mGrassAntialiasing ? "1" : "0")
                        + "\n#define A3_SCREEN_HEIGHT " + std::to_string(std::max(screenHeight, 1)) + "\n";
                    const std::string source(ExperimentalSources::grass);
                    mProgram = new osg::Program;
                    mProgram->setName("Alpha3 grass");
                    osg::ref_ptr<osg::Shader> vertex
                        = new osg::Shader(osg::Shader::VERTEX, defines + "#define A3_VERTEX\n" + source);
                    vertex->setName("a3_grass.vert");
                    osg::ref_ptr<osg::Shader> fragment = new osg::Shader(osg::Shader::FRAGMENT, defines + source);
                    fragment->setName("a3_grass.frag");
                    mProgram->addShader(vertex);
                    mProgram->addShader(fragment);
                }
                return mProgram.get();
            }

            // chunks no longer culled, and tiles not drawn for a while (their blades are made again when needed)
            void forget(unsigned frame)
            {
                for (auto it = mChunks.begin(); it != mChunks.end();)
                {
                    Chunk& chunk = *it->second;
                    for (const std::unique_ptr<Tile>& tile : chunk.mTiles)
                    {
                        if (tile->mState == TileState::Ready && frame - tile->mLastDrawn > sForgetAfterFrames
                            && frame - chunk.mLastSeen < sForgetAfterFrames)
                        {
                            tile->mGeometry = nullptr;
                            tile->mStale = false;
                            tile->mState = TileState::None;
                        }
                    }
                    if (frame - chunk.mLastSeen > sForgetAfterFrames || !chunk.mDrawable.valid())
                        it = mChunks.erase(it);
                    else
                        ++it;
                }
                for (auto it = mObstacles.begin(); it != mObstacles.end();)
                {
                    if (frame - it->second.mLastSeen > sForgetObstaclesAfterFrames || !it->second.mGeometry.valid())
                        it = mObstacles.erase(it);
                    else
                        ++it;
                }
            }

            void queue(Job job)
            {
                {
                    std::lock_guard lock(mJobsMutex);
                    mJobs.push_back(std::move(job));
                }
                mJobsReady.notify_one();
            }

            void work()
            {
                for (;;)
                {
                    Job job;
                    {
                        std::unique_lock lock(mJobsMutex);
                        mJobsReady.wait(lock, [this] { return !mJobs.empty(); });
                        // the newest first: the camera is where it was queued last
                        job = std::move(mJobs.back());
                        mJobs.pop_back();
                    }
                    try
                    {
                        job.mTile->mMade = makeBlades(*job.mChunk->mGround, *job.mTile, job.mObstacles);
                    }
                    catch (const std::exception& e)
                    {
                        Log(Debug::Warning) << "Grass: " << e.what();
                        job.mTile->mMade = nullptr;
                    }
                    job.mObstacles.clear(); // (let go of here, not on the cull thread's watch)
                    job.mTile->mState = TileState::Made;
                }
            }

            std::mutex mChunksMutex;
            std::unordered_map<const Terrain::TerrainDrawable*, std::shared_ptr<Chunk>> mChunks;
            std::unordered_map<const osg::Drawable*, ObstacleEntry> mObstacles;
            unsigned mFrame = 0;
            osg::ref_ptr<osg::Program> mProgram;

            std::mutex mJobsMutex;
            std::condition_variable mJobsReady;
            std::deque<Job> mJobs;
            std::thread mWorker;
        };

        // the terrain drawable culled last on this thread, and in which traversal: its first pass is where the grass goes
        thread_local const osg::Drawable* sLastDrawable = nullptr;
        thread_local unsigned sLastTraversal = 0;
    }

    void setGrassVfs(const VFS::Manager& vfs)
    {
        sVfs = &vfs;
    }

    void grassDrawableAdded(osg::NodeVisitor& cullVisitor, osg::Drawable& drawable, const osg::Matrix& modelView)
    {
        if (!experimentalSettings().mGrass)
            return;
        auto& cv = static_cast<osgUtil::CullVisitor&>(cullVisitor);
        const osg::Camera* camera = cv.getCurrentCamera();
        if (camera == nullptr || camera->getName() != sSceneCamera)
            return;
        // TerrainDrawable has no META_Object of its own (its className() is osg::Geometry's), so by its type
        auto* terrain = dynamic_cast<Terrain::TerrainDrawable*>(&drawable);
        if (terrain == nullptr)
        {
            // solid things only: not the sky, water, what is see-through or the first person view (other bins), not
            // actors (skinned: no osg::Geometry), not the blades themselves
            if (!experimentalSettings().mGrassClearance)
                return;
            const osg::Geometry* geometry = drawable.asGeometry();
            if (geometry == nullptr || cv.getCurrentRenderBin() == nullptr || cv.getCurrentRenderBin()->getBinNum() != 0
                || !geometry->getBoundingBox().valid() || geometry->getName() == "Alpha3 grass")
                return;
            Grass::get().obstacleAdded(cv, *geometry, modelView);
            return;
        }
        if (sLastDrawable == &drawable && sLastTraversal == cv.getTraversalNumber())
            return;
        sLastDrawable = &drawable;
        sLastTraversal = cv.getTraversalNumber();
        Grass::get().drawableAdded(cv, *terrain, modelView);
    }
}
