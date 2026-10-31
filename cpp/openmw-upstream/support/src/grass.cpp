// Grass of the experimental graphics ([Experimental] grass, grass density, grass height, grass distance, grass wind):
// blades growing out of the terrain where a grassy texture is painted (the material rules' grass), in clumps, swaying
// in the wind (experimental/grass.glsl).
//
// When the scene camera's cull reaches a terrain chunk (the drawableAdded hook, at its first pass), the chunk is split
// into tiles of about 512 units and the tiles within the grass distance and the view are added to the render graph
// beside it, under the same lights and fog. A tile's blades are made on a thread of their own, from the chunk's
// vertices (the ground's height) and its blendmaps (where the grassy layers are painted, and how much); until then the
// tile has none. Tiles not drawn for a while are let go of.
#include "support.hpp"

#include "experimentalsources.hpp"
#include "generatedmaps.hpp"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <condition_variable>
#include <cstring>
#include <deque>
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
#include <osg/observer_ptr>
#include <osgUtil/CullVisitor>

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
            Queued,
            Ready,
        };

        struct Tile
        {
            std::atomic<TileState> mState{ TileState::None };
            float mU0 = 0, mV0 = 0, mU1 = 0, mV1 = 0; // its part of the chunk
            osg::BoundingBox mBox; // the ground under it, from the chunk's corners; the blades' own is the geometry's
            osg::ref_ptr<osg::Geometry> mGeometry; // when ready; null: no grass on it
            osg::Vec3f mRoot, mTip;
            unsigned mLastDrawn = 0;
        };

        struct Chunk
        {
            osg::observer_ptr<Terrain::TerrainDrawable> mDrawable;
            std::shared_ptr<const ChunkGround> mGround;
            std::vector<std::unique_ptr<Tile>> mTiles;
            unsigned mLastSeen = 0;
        };

        osg::ref_ptr<osg::Geometry> makeBlades(const ChunkGround& ground, Tile& tile)
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

                const float clumpHeight = (0.75f + 0.5f * random.next()) * s.mGrassHeight;
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
                    if (std::abs(normal.z()) < 0.8f || root.z() < 2.f)
                        continue;

                    const float height = (26.f + 30.f * random.next()) * clumpHeight * (0.6f + 0.4f * weight);
                    const float facing = random.next() * 6.2831853f;
                    const float leanAngle = angle + (random.next() - 0.5f) * 1.2f; // out from the clump's middle
                    const float lean = 0.12f + 0.3f * random.next() * radius / 14.f;
                    const float tone = random.next();
                    const float bladeWidth = 1.3f + 1.2f * random.next();

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
        };

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
                    chunk = makeChunk(drawable);
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
                    TileState expected = TileState::None;
                    if (tile->mState.compare_exchange_strong(expected, TileState::Queued))
                    {
                        queue(Job{ chunk, tile.get() });
                        continue;
                    }
                    if (expected != TileState::Ready || !tile->mGeometry)
                        continue;
                    const osg::BoundingBox& blades = tile->mGeometry->getInitialBound();
                    if (cv.isCulled(blades))
                        continue;
                    tile->mLastDrawn = frame;
                    stateSet(*tile);
                    cv.pushStateSet(tile->mGeometry->getStateSet());
                    const float depth = cv.getDistanceFromEyePoint(blades.center(), false);
                    cv.addDrawableAndDepth(tile->mGeometry.get(), cv.getModelViewMatrix(), depth);
                    cv.popStateSet();
                }
                (void)modelView;
            }

        private:
            Grass()
            {
                mWorker = std::thread([this] { work(); });
                mWorker.detach();
            }

            std::shared_ptr<Chunk> makeChunk(Terrain::TerrainDrawable& drawable)
            {
                auto chunk = std::make_shared<Chunk>();
                chunk->mDrawable = &drawable;
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
                        chunk->mTiles.push_back(std::move(tile));
                    }
                return chunk;
            }

            // the program and colours of a tile, made on the cull thread the first time it is drawn
            void stateSet(Tile& tile)
            {
                if (tile.mGeometry->getStateSet() != nullptr)
                    return;
                osg::ref_ptr<osg::StateSet> stateset = new osg::StateSet;
                stateset->setAttributeAndModes(program(), osg::StateAttribute::ON);
                stateset->setMode(GL_BLEND, osg::StateAttribute::OFF);
                stateset->setMode(GL_CULL_FACE, osg::StateAttribute::OFF);
                stateset->setRenderBinDetails(0, "RenderBin");
                stateset->addUniform(new osg::Uniform("a3GrassRoot", tile.mRoot));
                stateset->addUniform(new osg::Uniform("a3GrassTip", tile.mTip));
                tile.mGeometry->setStateSet(stateset);
            }

            osg::Program* program()
            {
                if (!mProgram)
                {
                    const ExperimentalSettings& s = experimentalSettings();
                    const std::string defines = "#version 120\n#define A3_GRASS_DISTANCE "
                        + std::to_string(s.mGrassDistance) + "\n#define A3_GRASS_WIND " + std::to_string(s.mGrassWind)
                        + "\n";
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
                            tile->mState = TileState::None;
                        }
                    }
                    if (frame - chunk.mLastSeen > sForgetAfterFrames || !chunk.mDrawable.valid())
                        it = mChunks.erase(it);
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
                        job.mTile->mGeometry = makeBlades(*job.mChunk->mGround, *job.mTile);
                    }
                    catch (const std::exception& e)
                    {
                        Log(Debug::Warning) << "Grass: " << e.what();
                        job.mTile->mGeometry = nullptr;
                    }
                    job.mTile->mState = TileState::Ready;
                }
            }

            std::mutex mChunksMutex;
            std::unordered_map<const Terrain::TerrainDrawable*, std::shared_ptr<Chunk>> mChunks;
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
        // TerrainDrawable has no META_Object of its own (its className() is osg::Geometry's), so by its type
        auto* terrain = dynamic_cast<Terrain::TerrainDrawable*>(&drawable);
        if (terrain == nullptr)
            return;
        auto& cv = static_cast<osgUtil::CullVisitor&>(cullVisitor);
        const osg::Camera* camera = cv.getCurrentCamera();
        if (camera == nullptr || camera->getName() != sSceneCamera)
            return;
        if (sLastDrawable == &drawable && sLastTraversal == cv.getTraversalNumber())
            return;
        sLastDrawable = &drawable;
        sLastTraversal = cv.getTraversalNumber();
        Grass::get().drawableAdded(cv, *terrain, modelView);
    }
}
