// Mesh splitting for occlusion culling ([Camera] occlusion mesh splitting, off by default). Culling hides whole
// drawables, and some are huge: a Vivec canton is a dozen shapes each thousands of units across, and object paging
// merges a cell's statics into a few more. Their bounds reach past the camera, so they are never hidden, even when a wall
// in front covers nearly all of them. This cuts each large static shape into pieces on a grid ([Camera] occlusion split
// size units a side, at most [Camera] occlusion split max pieces), every triangle going to the piece holding its centre,
// so the pieces behind a wall can be culled one by one.
//
// It runs once per mesh when the scene manager loads it, and on object paging's active grid chunks after they are merged.
// Only plain osg::Geometry is split (not skinned, morphed or particle geometry), with no callbacks of its own, drawn as
// triangles; each piece gets its own compacted arrays, so nothing is shared with the original. Pieces keep the shape's
// state, name and user data. With it off, nothing is touched.
#include "support.hpp"

#include <alpha3/upstream.hpp>

#include <algorithm>
#include <cmath>
#include <map>
#include <vector>

#include <osg/Geometry>
#include <osg/Group>
#include <osg/NodeVisitor>
#include <osg/TriangleIndexFunctor>
#include <osg/UserDataContainer>

#include <components/debug/debuglog.hpp>

namespace Alpha3
{
    namespace
    {
        struct SplitSettings
        {
            bool mEnabled = false;
            float mSize = 2048.f;
            int mMaxPieces = 16;
        };

        const SplitSettings& splitSettings()
        {
            static const SplitSettings settings = [] {
                SplitSettings s;
                s.mEnabled = settingBool("Camera", "occlusion culling", false)
                    && settingBool("Camera", "occlusion mesh splitting", false);
                s.mSize = std::max(256.f, settingFloat("Camera", "occlusion split size", 2048.f));
                s.mMaxPieces = std::clamp(settingInt("Camera", "occlusion split max pieces", 16), 2, 256);
                if (s.mEnabled)
                    Log(Debug::Info) << "Occlusion mesh splitting on: pieces of " << s.mSize << " units, at most "
                                     << s.mMaxPieces << " a shape";
                return s;
            }();
            return settings;
        }

        struct CollectTriangles
        {
            std::vector<unsigned>* mIndices = nullptr;
            void operator()(unsigned a, unsigned b, unsigned c)
            {
                mIndices->push_back(a);
                mIndices->push_back(b);
                mIndices->push_back(c);
            }
        };

        template <class A>
        osg::ref_ptr<osg::Array> gatherAs(const osg::Array& in, const std::vector<unsigned>& kept)
        {
            const A* source = dynamic_cast<const A*>(&in);
            if (source == nullptr)
                return nullptr;
            osg::ref_ptr<A> out = new A(in.getBinding());
            out->reserve(kept.size());
            for (unsigned index : kept)
                out->push_back((*source)[index]);
            out->setNormalize(in.getNormalize());
            return out;
        }

        // The elements at kept of an array, as a new array of its type; null for a type not handled
        osg::ref_ptr<osg::Array> gather(const osg::Array& in, const std::vector<unsigned>& kept)
        {
            osg::ref_ptr<osg::Array> out;
            if (!(out = gatherAs<osg::Vec3Array>(in, kept)) && !(out = gatherAs<osg::Vec2Array>(in, kept))
                && !(out = gatherAs<osg::Vec4Array>(in, kept)) && !(out = gatherAs<osg::Vec4ubArray>(in, kept))
                && !(out = gatherAs<osg::FloatArray>(in, kept)) && !(out = gatherAs<osg::Vec4usArray>(in, kept)))
                return nullptr;
            return out;
        }

        // Per vertex arrays are gathered, overall ones shared; anything else can't be split
        enum class ArrayUse
        {
            None,
            Share,
            Gather,
            Unsplittable
        };

        ArrayUse arrayUse(const osg::Array* array, unsigned vertices)
        {
            if (array == nullptr)
                return ArrayUse::None;
            if (array->getBinding() == osg::Array::BIND_OVERALL)
                return ArrayUse::Share;
            if (array->getNumElements() == vertices
                && (array->getBinding() == osg::Array::BIND_PER_VERTEX
                    || array->getBinding() == osg::Array::BIND_UNDEFINED))
                return ArrayUse::Gather;
            return ArrayUse::Unsplittable;
        }

        bool splittable(const osg::Geometry& geometry)
        {
            if (std::string_view(geometry.className()) != "Geometry" || std::string_view(geometry.libraryName()) != "osg")
                return false;
            if (geometry.getUpdateCallback() || geometry.getEventCallback() || geometry.getCullCallback()
                || geometry.getDrawCallback() || geometry.getComputeBoundingBoxCallback()
                || geometry.getInitialBound().valid() || geometry.getNumParents() == 0)
                return false;
            const osg::Vec3Array* vertices = dynamic_cast<const osg::Vec3Array*>(geometry.getVertexArray());
            if (vertices == nullptr || vertices->empty())
                return false;
            for (unsigned i = 0; i < geometry.getNumParents(); ++i)
                if (geometry.getParent(i)->asGroup() == nullptr)
                    return false;
            for (const auto& primitives : geometry.getPrimitiveSetList())
            {
                const GLenum mode = primitives->getMode();
                if (mode != GL_TRIANGLES && mode != GL_TRIANGLE_STRIP && mode != GL_TRIANGLE_FAN)
                    return false;
            }
            const unsigned count = vertices->size();
            const osg::Array* arrays[] = { geometry.getNormalArray(), geometry.getColorArray(),
                geometry.getSecondaryColorArray(), geometry.getFogCoordArray() };
            for (const osg::Array* array : arrays)
                if (arrayUse(array, count) == ArrayUse::Unsplittable)
                    return false;
            for (const auto& array : geometry.getTexCoordArrayList())
                if (arrayUse(array.get(), count) == ArrayUse::Unsplittable)
                    return false;
            for (const auto& array : geometry.getVertexAttribArrayList())
                if (arrayUse(array.get(), count) == ArrayUse::Unsplittable)
                    return false;
            return true;
        }

        osg::ref_ptr<osg::Array> pieceArray(const osg::Array* array, const std::vector<unsigned>& kept, unsigned vertices,
            bool& failed)
        {
            switch (arrayUse(array, vertices))
            {
                case ArrayUse::None:
                    return nullptr;
                case ArrayUse::Share:
                    return const_cast<osg::Array*>(array);
                case ArrayUse::Gather:
                {
                    osg::ref_ptr<osg::Array> out = gather(*array, kept);
                    if (!out)
                        failed = true;
                    return out;
                }
                case ArrayUse::Unsplittable:
                    break;
            }
            failed = true;
            return nullptr;
        }

        // The pieces of a shape, or none when it is small enough, or can't be split
        std::vector<osg::ref_ptr<osg::Geometry>> split(osg::Geometry& geometry, const SplitPieceCallback& callback)
        {
            const SplitSettings& settings = splitSettings();
            std::vector<osg::ref_ptr<osg::Geometry>> pieces;
            if (!splittable(geometry))
                return pieces;
            const osg::Vec3Array& vertices = static_cast<const osg::Vec3Array&>(*geometry.getVertexArray());
            osg::BoundingBox box;
            for (const osg::Vec3f& v : vertices)
                box.expandBy(v);
            const osg::Vec3f extent = box._max - box._min;
            if (std::max({ extent.x(), extent.y(), extent.z() }) < settings.mSize * 1.5f)
                return pieces;

            std::vector<unsigned> triangles;
            osg::TriangleIndexFunctor<CollectTriangles> functor;
            functor.mIndices = &triangles;
            geometry.accept(functor);
            if (triangles.size() < 3 * 64)
                return pieces; // too few to be worth the draw calls

            // the grid: grown until the pieces fit in the most allowed
            float size = settings.mSize;
            std::map<unsigned, std::vector<unsigned>> cells; // cell -> its triangles (first index of each)
            for (;;)
            {
                cells.clear();
                for (std::size_t t = 0; t + 2 < triangles.size(); t += 3)
                {
                    const unsigned a = triangles[t], b = triangles[t + 1], c = triangles[t + 2];
                    if (a >= vertices.size() || b >= vertices.size() || c >= vertices.size())
                        continue;
                    const osg::Vec3f centre = (vertices[a] + vertices[b] + vertices[c]) / 3.f - box._min;
                    const unsigned x = static_cast<unsigned>(std::max(0.f, centre.x() / size));
                    const unsigned y = static_cast<unsigned>(std::max(0.f, centre.y() / size));
                    const unsigned z = static_cast<unsigned>(std::max(0.f, centre.z() / size));
                    cells[(std::min(x, 1023u) << 20) | (std::min(y, 1023u) << 10) | std::min(z, 1023u)].push_back(
                        static_cast<unsigned>(t));
                }
                if (static_cast<int>(cells.size()) <= settings.mMaxPieces)
                    break;
                size *= 1.25f;
            }
            if (cells.size() < 2)
                return pieces;

            const unsigned count = vertices.size();
            std::vector<int> remap(count, -1);
            for (const auto& [cell, cellTriangles] : cells)
            {
                // the piece's vertices in their original order (callbacks map ranges of them)
                std::vector<unsigned> kept;
                for (unsigned t : cellTriangles)
                    for (int k = 0; k < 3; ++k)
                        if (remap[triangles[t + k]] < 0)
                        {
                            remap[triangles[t + k]] = 0;
                            kept.push_back(triangles[t + k]);
                        }
                std::sort(kept.begin(), kept.end());
                for (std::size_t i = 0; i < kept.size(); ++i)
                    remap[kept[i]] = static_cast<int>(i);

                osg::ref_ptr<osg::Geometry> piece = new osg::Geometry;
                piece->setName(geometry.getName());
                piece->setStateSet(geometry.getStateSet());
                piece->setNodeMask(geometry.getNodeMask());
                piece->setDataVariance(geometry.getDataVariance());
                piece->setCullingActive(geometry.getCullingActive());
                if (const osg::UserDataContainer* data = geometry.getUserDataContainer())
                    piece->setUserDataContainer(osg::clone(data, osg::CopyOp::DEEP_COPY_USERDATA));
                piece->setUseDisplayList(false);

                bool failed = false;
                piece->setVertexArray(pieceArray(geometry.getVertexArray(), kept, count, failed));
                piece->setNormalArray(pieceArray(geometry.getNormalArray(), kept, count, failed));
                piece->setColorArray(pieceArray(geometry.getColorArray(), kept, count, failed));
                piece->setSecondaryColorArray(pieceArray(geometry.getSecondaryColorArray(), kept, count, failed));
                piece->setFogCoordArray(pieceArray(geometry.getFogCoordArray(), kept, count, failed));
                for (unsigned unit = 0; unit < geometry.getNumTexCoordArrays(); ++unit)
                    if (const osg::Array* array = geometry.getTexCoordArray(unit))
                        piece->setTexCoordArray(unit, pieceArray(array, kept, count, failed));
                for (unsigned index = 0; index < geometry.getNumVertexAttribArrays(); ++index)
                    if (const osg::Array* array = geometry.getVertexAttribArray(index))
                        piece->setVertexAttribArray(index, pieceArray(array, kept, count, failed));
                if (failed)
                {
                    pieces.clear();
                    return pieces;
                }

                if (kept.size() <= 0xffff)
                {
                    osg::ref_ptr<osg::DrawElementsUShort> elements = new osg::DrawElementsUShort(GL_TRIANGLES);
                    elements->reserve(cellTriangles.size() * 3);
                    for (unsigned t : cellTriangles)
                        for (int k = 0; k < 3; ++k)
                            elements->push_back(static_cast<unsigned short>(remap[triangles[t + k]]));
                    piece->addPrimitiveSet(elements);
                }
                else
                {
                    osg::ref_ptr<osg::DrawElementsUInt> elements = new osg::DrawElementsUInt(GL_TRIANGLES);
                    elements->reserve(cellTriangles.size() * 3);
                    for (unsigned t : cellTriangles)
                        for (int k = 0; k < 3; ++k)
                            elements->push_back(static_cast<unsigned>(remap[triangles[t + k]]));
                    piece->addPrimitiveSet(elements);
                }
                piece->setUseVertexBufferObjects(geometry.getUseVertexBufferObjects());

                for (unsigned index : kept)
                    remap[index] = -1;
                if (callback)
                    callback(geometry, *piece, kept);
                pieces.push_back(piece);
            }
            return pieces;
        }

        class FindGeometry : public osg::NodeVisitor
        {
        public:
            FindGeometry()
                : osg::NodeVisitor(TRAVERSE_ALL_CHILDREN)
            {
            }
            void apply(osg::Geometry& geometry) override { mFound.push_back(&geometry); }
            std::vector<osg::ref_ptr<osg::Geometry>> mFound;
        };

        unsigned splitNode(osg::Node& node, const SplitPieceCallback& callback)
        {
            if (!splitSettings().mEnabled)
                return 0;
            FindGeometry finder;
            node.accept(finder);
            unsigned made = 0;
            for (const osg::ref_ptr<osg::Geometry>& geometry : finder.mFound)
            {
                std::vector<osg::ref_ptr<osg::Geometry>> pieces = split(*geometry, callback);
                if (pieces.empty())
                    continue;
                const osg::Node::ParentList parents = geometry->getParents();
                for (osg::Group* parent : parents)
                {
                    parent->removeChild(geometry.get());
                    for (const auto& piece : pieces)
                        parent->addChild(piece);
                }
                made += static_cast<unsigned>(pieces.size());
            }
            return made;
        }
    }

    bool meshSplittingOn()
    {
        return splitSettings().mEnabled;
    }

    void splitMeshForCulling(osg::Node& node, std::string_view path)
    {
        // distant versions (x_dist.nif, x_dist_1.nif, ...) are only drawn merged into object paging's distant chunks,
        // which would merge the pieces again
        if (path.find("_dist") != std::string_view::npos)
            return;
        const unsigned made = splitNode(node, {});
        if (made > 0)
            Log(Debug::Verbose) << "Occlusion mesh splitting: " << path << " in " << made << " pieces";
    }

    void splitChunkForCulling(osg::Node& node, const SplitPieceCallback& callback)
    {
        splitNode(node, callback);
    }
}
