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
        constexpr float sBugDistance = 1000.f; // bugs are drawn only this close
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
            float mLush = 0.75f; // 0 ash grey or brown ground, 1 green
            bool mBarren = false; // bare ash or soil (not a road): a little lichen, moss and short grass, sparse
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
            const std::string stem = stemOf(file);
            layer.mGrass = materialOf(rules, stem, material) && material == Material::Grass;
            if (!layer.mGrass && material == Material::Dirt && s.mGrassSoil && s.mGrassShapes)
            {
                const auto has = [&](std::string_view word) { return stem.find(word) != std::string::npos; };
                layer.mBarren = (has("ash") || has("soil") || has("ground") || has("land"))
                    && !has("road") && !has("path") && !has("trail") && !has("mud");
                layer.mGrass = layer.mBarren;
            }
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
                const float greenness
                    = (average.y() - std::max(average.x(), average.z())) / std::max(average.y(), 0.05f);
                layer.mLush = layer.mBarren ? 0.f : std::clamp(0.35f + greenness * 6.f, 0.f, 1.f);
                Log(Debug::Info) << "Grass: " << file << (layer.mBarren ? " is bare ground: lichen, moss" : " grows grass")
                                 << " (lush " << layer.mLush << ")";
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
            osg::ref_ptr<osg::Geometry> mBugs, mMadeBugs; // the same for its bugs (experimental/bugs.glsl)
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

        // ---- what grows: blades of grass and a few other small things, all from the same few vertices

        enum class BladeKind
        {
            Blade = 0,
            SeedStalk = 1, // tall and thin, a straw coloured head
            Broad = 2, // short broad blades
            Sedge = 3, // stiff, dark, near the water
            Leaf = 4, // a rosette of leaves lying flat, as clover or plantain
            Moss = 5, // low cushions of moss, or pale lichen on ash
            Mushroom = 6, // tiny, a stem and a cap
        };

        // (the shader's numbering: kind 16 apart in the tone)
        struct ClumpShape
        {
            int mCount = sBladesPerClump;
            float mRadius = 14.f;
            float mHeight = 26.f, mHeightRange = 30.f;
            float mWidth = 1.3f, mWidthRange = 1.2f;
            float mLean = 0.12f, mLeanRange = 0.3f;
            bool mSmall = false; // its own size, not the clump's (only the grass height setting)
        };

        ClumpShape clumpShape(BladeKind kind, Random& random)
        {
            ClumpShape c;
            switch (kind)
            {
                case BladeKind::SeedStalk:
                    c.mHeight *= 1.5f;
                    c.mHeightRange *= 1.5f;
                    c.mWidth *= 0.55f;
                    c.mWidthRange *= 0.55f;
                    break;
                case BladeKind::Broad:
                    c.mHeight *= 0.55f;
                    c.mHeightRange *= 0.55f;
                    c.mWidth *= 1.9f;
                    c.mWidthRange *= 1.9f;
                    c.mLean += 0.15f;
                    break;
                case BladeKind::Sedge:
                    c.mCount = 10;
                    c.mRadius = 9.f;
                    c.mHeight = 34.f;
                    c.mHeightRange = 22.f;
                    c.mWidth = 1.f;
                    c.mWidthRange = 0.6f;
                    c.mLean = 0.04f;
                    c.mLeanRange = 0.08f;
                    break;
                case BladeKind::Leaf:
                    c.mCount = 5 + static_cast<int>(random.next() * 3.f);
                    c.mHeight = 7.f;
                    c.mHeightRange = 4.f;
                    c.mWidth = 2.6f;
                    c.mWidthRange = 1.f;
                    c.mLean = 1.2f;
                    c.mLeanRange = 0.6f;
                    c.mSmall = true;
                    break;
                case BladeKind::Moss:
                    c.mCount = 10;
                    c.mRadius = 7.f;
                    c.mHeight = 3.f;
                    c.mHeightRange = 3.f;
                    c.mWidth = 3.f;
                    c.mWidthRange = 2.f;
                    c.mLean = 0.3f;
                    c.mLeanRange = 0.5f;
                    c.mSmall = true;
                    break;
                case BladeKind::Mushroom:
                    c.mCount = 1 + static_cast<int>(random.next() * 3.f);
                    c.mRadius = 5.f;
                    c.mHeight = 6.f;
                    c.mHeightRange = 6.f;
                    c.mWidth = 3.f;
                    c.mWidthRange = 2.f;
                    c.mLean = 0.02f;
                    c.mLeanRange = 0.04f;
                    c.mSmall = true;
                    break;
                default:
                    break;
            }
            return c;
        }

        // A blade's vertices: x side (times its width), y how far up, and its triangles
        struct Outline
        {
            int mCount;
            float mSide[7];
            float mUp[7];
            std::vector<unsigned short> mTriangles;
        };
        constexpr std::size_t sMaxVerticesPerClump = 12 * 7;

        const Outline& outlineOf(BladeKind kind)
        {
            // a blade (the shader tapers it), a leaf (narrow at the root, an oval with a rounded tip), and a
            // mushroom (a thin stem, a cap wide at its rim)
            static const Outline blade{ 5, { -1.f, 1.f, -1.f, 1.f, 0.f }, { 0.f, 0.f, 0.5f, 0.5f, 1.f },
                { 0, 1, 2, 1, 3, 2, 2, 3, 4 } };
            static const Outline leaf{ 7, { -0.25f, 0.25f, -1.f, 1.f, -0.75f, 0.75f, 0.f },
                { 0.f, 0.f, 0.4f, 0.4f, 0.78f, 0.78f, 1.f }, { 0, 1, 2, 1, 3, 2, 2, 3, 4, 3, 5, 4, 4, 5, 6 } };
            static const Outline mushroom{ 7, { -0.22f, 0.22f, -0.22f, 0.22f, -1.f, 1.f, 0.f },
                { 0.f, 0.f, 0.62f, 0.62f, 0.64f, 0.64f, 1.f }, { 0, 1, 2, 1, 3, 2, 4, 5, 6 } };
            return kind == BladeKind::Leaf ? leaf : kind == BladeKind::Mushroom ? mushroom : blade;
        }

        using Grassy = std::vector<std::pair<Layer, const ChunkGround::Pass*>>;

        // how grassy a place is by the blendmaps, and its strongest grassy layer
        float grassyAt(const Grassy& grassy, float u, float v, std::size_t& which)
        {
            float weight = 0.f, strongest = -1.f;
            which = 0;
            for (std::size_t i = 0; i < grassy.size(); ++i)
            {
                const ChunkGround::Pass& pass = *grassy[i].second;
                const float w = pass.mBlendmap ? ChunkGround::blend(*pass.mBlendmap, pass.mBlendmapMatrix, u, v) : 1.f;
                weight += w;
                if (w > strongest)
                {
                    strongest = w;
                    which = i;
                }
            }
            return weight;
        }

        enum class BugKind
        {
            Ant = 0,
            Beetle = 1,
            Grasshopper = 2,
            Bee = 3,
            Butterfly = 4,
            Dragonfly = 5,
            Gnat = 6,
            Firefly = 7,
        };

        // A tile's bugs: each a quad the shader moves (experimental/bugs.glsl), in groups by where they live
        osg::ref_ptr<osg::Geometry> makeBugs(
            const ChunkGround& ground, const Tile& tile, const Grassy& grassy, const Blocked& blocked, Random& random)
        {
            const ExperimentalSettings& s = experimentalSettings();
            osg::ref_ptr<osg::Vec3Array> homes = new osg::Vec3Array;
            osg::ref_ptr<osg::Vec4Array> looks = new osg::Vec4Array;
            osg::ref_ptr<osg::Vec4Array> paths = new osg::Vec4Array;
            osg::ref_ptr<osg::Vec4Array> grounds = new osg::Vec4Array;
            osg::ref_ptr<osg::DrawElementsUShort> quads = new osg::DrawElementsUShort(GL_TRIANGLES);
            osg::BoundingBox box;
            const auto add = [&](const osg::Vec3f& home, BugKind kind, float along, float roam, float height, float size,
                                 const osg::Vec2f& slope, float speed) {
                const auto first = static_cast<unsigned short>(homes->size());
                const float seed = random.next();
                const float xs[4] = { -1.f, 1.f, 1.f, -1.f }, ys[4] = { -1.f, -1.f, 1.f, 1.f };
                for (int i = 0; i < 4; ++i)
                {
                    homes->push_back(home);
                    looks->push_back(osg::Vec4f(xs[i], ys[i], static_cast<float>(kind), seed));
                    paths->push_back(osg::Vec4f(along, roam, height, size));
                    grounds->push_back(osg::Vec4f(slope.x(), slope.y(), speed, 0.f));
                }
                for (unsigned short index : { 0, 1, 2, 0, 2, 3 })
                    quads->push_back(first + index);
                const float reach = roam * 1.5f + 20.f;
                const float rise = (std::abs(slope.x()) + std::abs(slope.y())) * reach;
                box.expandBy(home + osg::Vec3f(-reach, -reach, -rise - 5.f));
                box.expandBy(home + osg::Vec3f(reach, reach, height + rise + 40.f));
            };
            const auto between = [&](float a, float b) { return a + (b - a) * random.next(); };

            const osg::Vec3f corner0 = ground.position(tile.mU0, tile.mV0);
            const osg::Vec3f corner1 = ground.position(tile.mU1, tile.mV1);
            const float width = std::max(std::abs(corner1.x() - corner0.x()), 1.f);
            const float depth = std::max(std::abs(corner1.y() - corner0.y()), 1.f);
            const float uPerUnit = (tile.mU1 - tile.mU0) / width, vPerUnit = (tile.mV1 - tile.mV0) / depth;
            const int tries = static_cast<int>(width * depth / (512.f * 512.f) * 14.f * s.mGrassBugDensity + 0.5f);
            for (int attempt = 0; attempt < tries && homes->size() < 60000; ++attempt)
            {
                const float u = tile.mU0 + (tile.mU1 - tile.mU0) * random.next();
                const float v = tile.mV0 + (tile.mV1 - tile.mV0) * random.next();
                std::size_t which = 0;
                const float weight = grassyAt(grassy, u, v, which);
                const float groupRoll = random.next();
                if (random.next() > std::min(weight, 1.f))
                    continue;
                const osg::Vec3f home = ground.position(u, v);
                const osg::Vec3f across = ground.position(u + 4.f * uPerUnit, v) - home;
                const osg::Vec3f along = ground.position(u, v + 4.f * vPerUnit) - home;
                osg::Vec3f normal = across ^ along;
                normal.normalize();
                if (normal.z() < 0.8f || home.z() < 2.f || blocked.at(home.x(), home.y()))
                    continue;
                const osg::Vec2f slope(-normal.x() / normal.z(), -normal.y() / normal.z());
                const Layer& layer = grassy[which].first;
                const float lush = layer.mLush;
                const float nearWater = std::clamp(1.f - (home.z() - 2.f) / 150.f, 0.f, 1.f);

                // which group lives here: chances by the ground (more beetles on ash, fliers over green ground)
                const float chances[8] = { 0.25f, layer.mBarren ? 0.35f : 0.08f, lush > 0.4f ? 0.12f : 0.f,
                    lush > 0.5f ? 0.15f : 0.f, lush > 0.5f ? 0.1f : 0.f, 0.25f * nearWater, 0.08f,
                    lush > 0.35f ? 0.12f : 0.f };
                float total = 0.f;
                for (float c : chances)
                    total += c;
                float pick = groupRoll * total;
                int group = 0;
                while (group < 7 && pick >= chances[group])
                    pick -= chances[group++];

                switch (static_cast<BugKind>(group))
                {
                    case BugKind::Ant:
                    {
                        const float roam = between(15.f, 35.f), speed = between(7.f, 11.f), start = between(0.f, 6.28f);
                        const int count = 5 + static_cast<int>(random.next() * 4.f);
                        for (int i = 0; i < count; ++i)
                            add(home, BugKind::Ant, start - i * 2.8f / roam, roam, 0.f, between(1.1f, 1.5f), slope, speed);
                        break;
                    }
                    case BugKind::Beetle:
                        add(home, BugKind::Beetle, between(0.f, 6.28f), between(10.f, 25.f), 0.f, between(2.f, 3.f),
                            slope, between(2.5f, 4.f));
                        break;
                    case BugKind::Grasshopper:
                        add(home, BugKind::Grasshopper, 0.f, 50.f, 0.f, between(3.2f, 4.2f), slope, 0.f);
                        break;
                    case BugKind::Bee:
                        for (int i = 0, n = 2 + static_cast<int>(random.next() * 2.f); i < n; ++i)
                            add(home, BugKind::Bee, between(0.f, 6.28f), between(30.f, 60.f), between(18.f, 45.f),
                                between(1.8f, 2.3f), slope, between(90.f, 130.f));
                        break;
                    case BugKind::Butterfly:
                        for (int i = 0, n = 1 + static_cast<int>(random.next() * 2.f); i < n; ++i)
                            add(home, BugKind::Butterfly, between(0.f, 6.28f), between(60.f, 140.f), between(25.f, 80.f),
                                between(4.5f, 6.5f), slope, between(40.f, 70.f));
                        break;
                    case BugKind::Dragonfly:
                        for (int i = 0, n = 1 + static_cast<int>(random.next() * 2.f); i < n; ++i)
                            add(home, BugKind::Dragonfly, 0.f, 120.f, between(30.f, 80.f), between(5.5f, 7.f), slope, 0.f);
                        break;
                    case BugKind::Gnat:
                    {
                        const float height = between(45.f, 85.f);
                        for (int i = 0; i < 10; ++i)
                            add(home, BugKind::Gnat, between(0.f, 6.28f), 9.f, height, 0.5f, slope, 0.f);
                        break;
                    }
                    case BugKind::Firefly:
                        for (int i = 0, n = 4 + static_cast<int>(random.next() * 3.f); i < n; ++i)
                            add(home, BugKind::Firefly, between(0.f, 6.28f), 80.f, between(15.f, 70.f), between(1.f, 1.3f),
                                slope, 0.f);
                        break;
                }
            }
            if (homes->empty())
                return nullptr;
            osg::ref_ptr<osg::Geometry> geometry = new osg::Geometry;
            geometry->setName("Alpha3 bugs");
            geometry->setUseDisplayList(false);
            geometry->setUseVertexBufferObjects(true);
            geometry->setVertexArray(homes);
            geometry->setTexCoordArray(0, looks, osg::Array::BIND_PER_VERTEX);
            geometry->setTexCoordArray(1, paths, osg::Array::BIND_PER_VERTEX);
            geometry->setTexCoordArray(2, grounds, osg::Array::BIND_PER_VERTEX);
            geometry->addPrimitiveSet(quads);
            geometry->setInitialBound(box);
            geometry->setCullingActive(false); // culled here, by the tile
            return geometry;
        }

        osg::ref_ptr<osg::Geometry> makeBlades(const ChunkGround& ground, Tile& tile, const std::vector<Obstacle>& obstacles)
        {
            tile.mMadeBugs = nullptr;
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
            const Blocked blocked(ground, tile, obstacles, 150.f * s.mGrassHeight);

            osg::ref_ptr<osg::Vec3Array> roots = new osg::Vec3Array;
            osg::ref_ptr<osg::Vec4Array> shape = new osg::Vec4Array;
            osg::ref_ptr<osg::Vec4Array> extra = new osg::Vec4Array;
            osg::ref_ptr<osg::DrawElementsUShort> triangles = new osg::DrawElementsUShort(GL_TRIANGLES);
            Random random(hashPosition(corner0.x(), corner0.y()));
            osg::BoundingBox box;
            for (int clump = 0; clump < clumps && roots->size() + sMaxVerticesPerClump < 65535; ++clump)
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
                const Layer& layer = grassy[which].first;
                const osg::Vec3f middle = ground.position(cu, cv);

                // what grows here: how lush the ground is (its texture's colour: green or ash grey and brown) and how
                // close to the water picks among the kinds; the same random numbers are drawn whatever is switched on
                const float lush = s.mGrassSoil ? layer.mLush : 0.75f;
                const float nearWater = std::clamp(1.f - (middle.z() - 2.f) / 150.f, 0.f, 1.f);
                const float kindRoll = random.next();
                const float sizeRoll = random.next();
                const float shadeRoll = random.next();
                BladeKind kind = BladeKind::Blade;
                {
                    float p = 0.f;
                    const auto pick = [&](bool on, float chance, BladeKind k) {
                        if (on && kind == BladeKind::Blade && kindRoll < (p += chance))
                            kind = k;
                    };
                    pick(s.mGrassShapes, 0.04f + 0.5f * std::pow(1.f - lush, 1.5f), BladeKind::Moss);
                    pick(s.mGrassShapes, lush > 0.7f ? 0.03f : 0.012f, BladeKind::Mushroom);
                    pick(s.mGrassShapes, 0.1f * lush, BladeKind::Leaf);
                    pick(s.mGrassShapes, 0.04f + 0.3f * nearWater * lush, BladeKind::Sedge);
                    pick(s.mGrassVariety, 0.12f * lush, BladeKind::SeedStalk);
                    pick(s.mGrassVariety, 0.15f, BladeKind::Broad);
                }
                // moss and lichen take to bare ash; grass thins out there
                float density = kind == BladeKind::Moss || kind == BladeKind::Mushroom ? 1.f : 0.45f + 0.55f * lush;
                if (layer.mBarren)
                    density *= kind == BladeKind::Moss ? 0.35f : 0.12f; // here and there on bare ground
                if (random.next() > (std::min(weight, 1.f) * 1.2f - 0.1f) * density)
                    continue;
                tile.mRoot = layer.mRoot;
                tile.mTip = layer.mTip;

                // taller where the ground is lush, in meadow patches of taller and shorter
                float clumpHeight = (0.75f + 0.5f * sizeRoll) * s.mGrassHeight;
                if (s.mGrassSoil)
                {
                    const float patches = std::sin(middle.x() * 0.0011f + std::sin(middle.y() * 0.0019f) * 2.f)
                        + std::sin(middle.y() * 0.0013f - middle.x() * 0.0006f);
                    clumpHeight *= (0.45f + 0.9f * lush) * (1.f + 0.22f * patches);
                }
                // and each clump a shade (added to the tone, 4 apart): normal, darker, lighter, or now and then yellow
                float shade = 0.f;
                if (s.mGrassVariety)
                    shade = shadeRoll < 0.015f ? 12.f : shadeRoll < 0.3f ? 4.f : shadeRoll < 0.55f ? 8.f : 0.f;

                const ClumpShape shapeOf = clumpShape(kind, random);
                for (int blade = 0; blade < shapeOf.mCount; ++blade)
                {
                    float angle, radius;
                    if (kind == BladeKind::Leaf)
                    {
                        // a rosette: leaves evenly round its middle, lying out from it
                        angle = (blade + 0.3f * random.next()) * 6.2831853f / shapeOf.mCount;
                        radius = 1.5f * random.next();
                    }
                    else
                    {
                        // around the clump's middle, closer in more often
                        angle = random.next() * 6.2831853f;
                        radius = shapeOf.mRadius * std::sqrt(random.next());
                    }
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

                    const float size = shapeOf.mSmall ? s.mGrassHeight * (0.85f + 0.3f * random.next())
                                                      : clumpHeight * (0.6f + 0.4f * weight);
                    const float height = (shapeOf.mHeight + shapeOf.mHeightRange * random.next()) * size;
                    float facing = random.next() * 6.2831853f;
                    float leanAngle = angle + (random.next() - 0.5f) * 1.2f; // out from the clump's middle
                    if (kind == BladeKind::Leaf)
                    {
                        leanAngle = angle;
                        facing = angle + 1.5707963f; // its width across the way it lies: face up
                    }
                    const float lean = shapeOf.mLean + shapeOf.mLeanRange * random.next() * (kind == BladeKind::Leaf ? 1.f : radius / std::max(shapeOf.mRadius, 1.f));
                    const float tone = random.next() * 0.999f + shade + 16.f * static_cast<float>(kind);
                    const float bladeWidth = (shapeOf.mWidth + shapeOf.mWidthRange * random.next());

                    // its outline: how far up each pair of vertices is and how wide (the shader tapers only the
                    // blades of grass; leaves and mushrooms are drawn as given)
                    const auto first = static_cast<unsigned short>(roots->size());
                    const Outline& outline = outlineOf(kind);
                    for (int i = 0; i < outline.mCount; ++i)
                    {
                        roots->push_back(root);
                        shape->push_back(osg::Vec4f(outline.mSide[i], outline.mUp[i], facing, height));
                        extra->push_back(osg::Vec4f(leanAngle, lean, tone, bladeWidth));
                    }
                    for (unsigned short index : outline.mTriangles)
                        triangles->push_back(first + index);
                    box.expandBy(root);
                    box.expandBy(root + osg::Vec3f(0.f, 0.f, height * 1.1f));
                }
            }
            // (a random sequence of their own: the blades stay where they are whatever the bugs' settings)
            Random bugRandom(hashPosition(corner0.x() + 0.5f, corner0.y() + 0.5f) ^ 0x5bd1e995u);
            tile.mMadeBugs = s.mGrassBugs && s.mGrassBugDensity > 0.f
                ? makeBugs(ground, tile, grassy, blocked, bugRandom)
                : nullptr;
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
                        tile->mBugs = std::move(tile->mMadeBugs);
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
                    // its bugs, only close by
                    if (tile->mBugs && (nearest - eye).length() < sBugDistance
                        && !cv.isCulled(tile->mBugs->getInitialBound()))
                    {
                        cv.pushStateSet(bugState());
                        cv.addDrawableAndDepth(tile->mBugs.get(), cv.getModelViewMatrix(),
                            cv.getDistanceFromEyePoint(tile->mBugs->getInitialBound().center(), false));
                        cv.popStateSet();
                    }
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

            // the bugs' program and state, the same for every tile
            const osg::StateSet* bugState()
            {
                if (!mBugState)
                {
                    const std::string defines
                        = "#version 120\n#define A3_BUG_DISTANCE " + std::to_string(sBugDistance) + "\n";
                    const std::string source(ExperimentalSources::bugs);
                    osg::ref_ptr<osg::Program> program = new osg::Program;
                    program->setName("Alpha3 bugs");
                    osg::ref_ptr<osg::Shader> vertex
                        = new osg::Shader(osg::Shader::VERTEX, defines + "#define A3_VERTEX\n" + source);
                    vertex->setName("a3_bugs.vert");
                    osg::ref_ptr<osg::Shader> fragment = new osg::Shader(osg::Shader::FRAGMENT, defines + source);
                    fragment->setName("a3_bugs.frag");
                    program->addShader(vertex);
                    program->addShader(fragment);
                    mBugState = new osg::StateSet;
                    mBugState->setAttributeAndModes(program, osg::StateAttribute::ON);
                    mBugState->setMode(GL_BLEND, osg::StateAttribute::OFF);
                    mBugState->setMode(GL_CULL_FACE, osg::StateAttribute::OFF);
                    mBugState->setRenderBinDetails(0, "RenderBin");
                }
                return mBugState.get();
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
                            tile->mBugs = nullptr;
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
                        job.mTile->mMadeBugs = nullptr;
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
            osg::ref_ptr<osg::StateSet> mBugState;

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
                || !geometry->getBoundingBox().valid() || geometry->getName().rfind("Alpha3 ", 0) == 0)
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
