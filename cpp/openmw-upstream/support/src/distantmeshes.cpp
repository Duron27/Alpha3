// Distant meshes: lighter versions of meshes for object paging's distant chunks. Paging asks the virtual file system for
// a distant version of a mesh (meshes/x_dist.nif for Morrowind content) when it builds a chunk outside the active grid,
// and only there; the active cells keep the real meshes. This archive lists such a file for the meshes the settings name
// that have none of their own, and makes it the first time it is opened, in one of two ways:
//
// * Cards ([Terrain] impostors, for trees): the mesh is drawn on the CPU from six directions into one texture
//   (textures/alpha3_impostors/...) and written as a NIF of three crossed quads, two sided, each side showing the view
//   from that side. A forest far away is a few triangles per tree.
// * Simplified meshes ([Terrain] distant mesh simplification, for rocks, buildings and the rest): the mesh's shapes are
//   gathered by material (textures, alpha, material colours, vertex colours, stencil, z buffer, specular, as the NIF's
//   properties set them), each gathered shape is simplified (meshoptimizer, its borders locked so the pieces still meet),
//   and written as a NIF that uses the mesh's own textures, so paging merges it with everything else drawn with them.
//   Coarser versions go under the names of paging's farther levels (x_dist_1.nif for chunks of 2 cells, x_dist_2.nif for
//   4, ...), each with twice the error of the one before, so the farther a chunk is, the fewer triangles it has.
//
// Both go to a cache in the cache directory, keyed by the mesh's contents and the settings, so a mesh is only made once.
// A mesh that cannot be made (animated, particles, billboards, effects, skinned, not a Morrowind NIF, too small to be
// worth it) gets its own NIF under the distant name, which is what paging would have used without the archive; the cache
// remembers it, and from the next start it is no longer listed, so the engine does not keep it twice.
#include "bcdecode.hpp"
#include "blobstore.hpp"
#include "support.hpp"

#include <alpha3/upstream.hpp>

#include <ProcessDxtc.hpp>
#include <meshoptimizer.h>

#include <algorithm>
#include <array>
#include <atomic>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <iterator>
#include <limits>
#include <map>
#include <memory>
#include <mutex>
#include <sstream>
#include <string>
#include <string_view>
#include <tuple>
#include <vector>

#include <osg/BoundingBox>
#include <osg/Image>
#include <osg/Matrixf>
#include <osgDB/Options>
#include <osgDB/ReaderWriter>
#include <osgDB/Registry>

#include <components/debug/debuglog.hpp>
#include <components/misc/pathhelpers.hpp>
#include <components/misc/resourcehelpers.hpp>
#include <components/misc/strings/algorithm.hpp>
#include <components/misc/strings/lower.hpp>
#include <components/nif/data.hpp>
#include <components/nif/extra.hpp>
#include <components/nif/niffile.hpp>
#include <components/nif/node.hpp>
#include <components/nif/property.hpp>
#include <components/nif/texture.hpp>
#include <components/vfs/archive.hpp>
#include <components/vfs/file.hpp>
#include <components/vfs/manager.hpp>
#include <components/vfs/pathutil.hpp>

namespace Alpha3
{
    namespace
    {
        thread_local bool sBypassImageHooks = false;

        // Images read here are for the CPU: the hooks must not convert them for the GPU or count them for the preload
        struct BypassImageHooks
        {
            BypassImageHooks() { sBypassImageHooks = true; }
            ~BypassImageHooks() { sBypassImageHooks = false; }
        };

        constexpr int sViews = 6; // every 60 degrees: three planes, both sides
        constexpr int sGutter = 2; // transparent texels around each view, so mip levels do not mix neighbouring views
        constexpr int sSupersample = 2;
        constexpr std::uint32_t sCacheVersion = 2;
        constexpr std::string_view sDistantSuffix = "_dist";
        constexpr std::string_view sTextureDirectory = "textures/alpha3_impostors/";
        constexpr std::string_view sCardRootName = "Alpha3 impostor";
        constexpr std::string_view sSimplifiedRootName = "Alpha3 distant mesh";

        enum class Kind
        {
            Card,
            Simplified,
        };

        std::vector<std::string> splitPatterns(std::string patterns)
        {
            Misc::StringUtils::lowerCaseInPlace(patterns);
            std::vector<std::string> result;
            std::size_t start = 0;
            while (start <= patterns.size())
            {
                std::size_t end = patterns.find(',', start);
                if (end == std::string::npos)
                    end = patterns.size();
                std::string pattern = patterns.substr(start, end - start);
                pattern.erase(0, pattern.find_first_not_of(" \t"));
                pattern.erase(pattern.find_last_not_of(" \t") + 1);
                if (!pattern.empty())
                    result.push_back(std::move(pattern));
                start = end + 1;
            }
            return result;
        }

        // "*" matches every name, anything else a part of it
        bool matches(const std::vector<std::string>& patterns, std::string_view stem)
        {
            return std::any_of(patterns.begin(), patterns.end(),
                [&](const std::string& p) { return p == "*" || stem.find(p) != std::string_view::npos; });
        }

        struct Settings
        {
            bool mCards = false;
            std::vector<std::string> mCardPatterns;
            int mCardSize = 64; // texels per view, each side

            bool mSimplify = false;
            std::vector<std::string> mSimplifyPatterns;
            float mSimplifyError = 0.02f; // of the mesh's size, at level 0
            int mSimplifyMinTriangles = 200;
            int mSimplifyLevels = 3; // _dist, _dist_1, _dist_2

            // the error of a level: twice the one before
            float simplifyError(int level) const { return std::min(0.5f, mSimplifyError * static_cast<float>(1 << level)); }
        };

        Settings readSettings()
        {
            Settings s;
            s.mCards = settingBool("Terrain", "impostors", false);
            s.mCardSize = std::clamp(settingInt("Terrain", "impostor size", 64), 16, 512);
            s.mCardPatterns = splitPatterns(
                setting("Terrain", "impostor meshes", "flora_tree,flora_bc_tree,flora_ashtree,flora_emp_parasol"));
            s.mSimplify = settingBool("Terrain", "distant mesh simplification", false);
            s.mSimplifyPatterns = splitPatterns(setting("Terrain", "simplified meshes", "*"));
            s.mSimplifyError = std::clamp(settingFloat("Terrain", "simplified mesh error", 0.02f), 0.0001f, 0.5f);
            s.mSimplifyMinTriangles = std::max(0, settingInt("Terrain", "simplified mesh min triangles", 200));
            s.mSimplifyLevels = std::clamp(settingInt("Terrain", "simplified mesh levels", 3), 1, 6);
            return s;
        }

        std::uint64_t fnv1a(std::string_view data, std::uint64_t hash)
        {
            for (unsigned char c : data)
            {
                hash ^= c;
                hash *= 0x100000001b3ull;
            }
            return hash;
        }

        std::string readAll(std::istream& stream)
        {
            return std::string(std::istreambuf_iterator<char>(stream), std::istreambuf_iterator<char>());
        }

        // ---- the files

        class Writer
        {
        public:
            template <class T>
            void put(T value)
            {
                char bytes[sizeof(T)];
                std::memcpy(bytes, &value, sizeof(T));
                mData.append(bytes, sizeof(T));
            }
            void putBool(bool value) { put<std::int32_t>(value ? 1 : 0); } // 4.0.0.2 booleans are 32 bit
            void putString(std::string_view value)
            {
                put<std::uint32_t>(static_cast<std::uint32_t>(value.size()));
                mData.append(value);
            }
            void putVec(const osg::Vec3f& v)
            {
                put(v.x());
                put(v.y());
                put(v.z());
            }
            void putRefs(const std::vector<std::int32_t>& refs)
            {
                put<std::uint32_t>(static_cast<std::uint32_t>(refs.size()));
                for (std::int32_t ref : refs)
                    put(ref);
            }

            std::string mData;
        };

        // NiObjectNET, then NiAVObject, as Nif::NiAVObject::read reads them in 4.0.0.2
        void putObject(Writer& w, std::string_view name)
        {
            w.putString(name);
            w.put<std::int32_t>(-1); // extra data
            w.put<std::int32_t>(-1); // controller
        }

        void putAVObject(Writer& w, std::string_view name, const std::vector<std::int32_t>& properties)
        {
            putObject(w, name);
            w.put<std::uint16_t>(0); // flags
            w.putVec(osg::Vec3f()); // translation
            for (int i = 0; i < 9; ++i)
                w.put<float>(i % 4 == 0 ? 1.f : 0.f); // rotation
            w.put<float>(1.f); // scale
            w.putVec(osg::Vec3f()); // velocity
            w.putRefs(properties);
            w.putBool(false); // bounding volume
        }

        // What a shape is drawn with: the properties of the NIF that apply to it, kept as they were read (the bump map,
        // texture effects and controllers are not kept; a mesh with controllers or effects is not made at all)
        struct Material
        {
            struct Slot
            {
                std::string mFile; // as the NIF names it, empty when the slot is off
                std::uint32_t mClamp = 3, mFilter = 2;
            };

            bool mHasTexturing = false;
            std::uint16_t mTexturingFlags = 0;
            std::uint32_t mApplyMode = 2;
            std::array<Slot, 7> mSlots;

            bool mHasAlpha = false;
            std::uint16_t mAlphaFlags = 0;
            std::uint8_t mAlphaThreshold = 0;

            bool mHasMaterial = false;
            std::uint16_t mMaterialFlags = 0;
            osg::Vec3f mAmbient{ 1, 1, 1 }, mDiffuse{ 1, 1, 1 }, mSpecular, mEmissive;
            float mGlossiness = 0.f, mAlpha = 1.f;

            bool mHasVertexColor = false;
            std::uint16_t mVertexColorFlags = 0;
            std::uint32_t mVertexMode = 0, mLightingMode = 0;

            bool mHasStencil = false;
            std::uint16_t mStencilFlags = 0;
            std::uint8_t mStencilEnabled = 0;
            std::uint32_t mStencil[7] = {}; // test, ref, mask, fail, z fail, pass, draw mode

            bool mHasZBuffer = false;
            std::uint16_t mZBufferFlags = 0;

            bool mHasSpecularProperty = false;
            std::uint16_t mSpecularFlags = 0;

            // Each property as a record body (after its type name); empty when the shape has none of it
            std::string texturing(const std::array<std::int32_t, 7>& sources) const
            {
                if (!mHasTexturing)
                    return {};
                Writer w;
                putObject(w, "");
                w.put(mTexturingFlags);
                w.put(mApplyMode);
                w.put<std::uint32_t>(7);
                for (int slot = 0; slot < 7; ++slot)
                {
                    const bool on = !mSlots[slot].mFile.empty();
                    w.putBool(on);
                    if (!on)
                        continue;
                    w.put<std::int32_t>(sources[slot]);
                    w.put(mSlots[slot].mClamp);
                    w.put(mSlots[slot].mFilter);
                    w.put<std::uint32_t>(0); // UV set
                    w.put<std::uint32_t>(0); // PS2 L, K
                    w.put<std::uint16_t>(0); // unknown
                }
                return w.mData;
            }

            std::string alpha() const
            {
                if (!mHasAlpha)
                    return {};
                Writer w;
                putObject(w, "");
                w.put(mAlphaFlags);
                w.put(mAlphaThreshold);
                return w.mData;
            }

            std::string material() const
            {
                if (!mHasMaterial)
                    return {};
                Writer w;
                putObject(w, "");
                w.put(mMaterialFlags);
                w.putVec(mAmbient);
                w.putVec(mDiffuse);
                w.putVec(mSpecular);
                w.putVec(mEmissive);
                w.put(mGlossiness);
                w.put(mAlpha);
                return w.mData;
            }

            std::string vertexColor() const
            {
                if (!mHasVertexColor)
                    return {};
                Writer w;
                putObject(w, "");
                w.put(mVertexColorFlags);
                w.put(mVertexMode);
                w.put(mLightingMode);
                return w.mData;
            }

            std::string stencil() const
            {
                if (!mHasStencil)
                    return {};
                Writer w;
                putObject(w, "");
                w.put(mStencilFlags);
                w.put(mStencilEnabled);
                for (std::uint32_t value : mStencil)
                    w.put(value);
                return w.mData;
            }

            std::string zBuffer() const
            {
                if (!mHasZBuffer)
                    return {};
                Writer w;
                putObject(w, "");
                w.put(mZBufferFlags);
                return w.mData;
            }

            std::string specular() const
            {
                if (!mHasSpecularProperty)
                    return {};
                Writer w;
                putObject(w, "");
                w.put(mSpecularFlags);
                return w.mData;
            }

            // Shapes with equal keys are drawn the same way
            std::string key() const
            {
                std::string k = texturing({}) + '|' + alpha() + '|' + material() + '|' + vertexColor() + '|'
                    + stencil() + '|' + zBuffer() + '|' + specular();
                for (const Slot& slot : mSlots)
                    k += '|' + slot.mFile;
                return k;
            }
        };

        struct Vertex
        {
            float mPosition[3];
            float mNormal[3];
            float mUv[2];
            float mColor[4];
        };
        static_assert(sizeof(Vertex) == 12 * sizeof(float));

        // Shapes of one material, in the mesh's coordinates
        struct Group
        {
            Material mMaterial;
            bool mHasNormals = false, mHasColors = false, mHasUv = false;
            std::vector<Vertex> mVertices;
            std::vector<std::uint32_t> mIndices;
        };

        struct Shape
        {
            Group mGroup;
            std::string mKey;
        };

        // A NIF of the groups under one root node; the texture files are the slots' names
        std::string writeNif(std::string_view rootName, const std::vector<Group>& groups)
        {
            // record numbers first: root, then for each group its shape, data and properties
            struct Plan
            {
                std::int32_t mShape, mData, mTexturing = -1, mAlpha = -1, mMaterial = -1, mVertexColor = -1,
                                                 mStencil = -1, mZBuffer = -1, mSpecular = -1;
                std::array<std::int32_t, 7> mSources{ -1, -1, -1, -1, -1, -1, -1 };
            };
            std::vector<Plan> plans;
            std::int32_t next = 1;
            for (const Group& group : groups)
            {
                const Material& m = group.mMaterial;
                Plan plan;
                plan.mShape = next++;
                plan.mData = next++;
                if (m.mHasTexturing)
                {
                    plan.mTexturing = next++;
                    for (int slot = 0; slot < 7; ++slot)
                        if (!m.mSlots[slot].mFile.empty())
                            plan.mSources[slot] = next++;
                }
                if (m.mHasAlpha)
                    plan.mAlpha = next++;
                if (m.mHasMaterial)
                    plan.mMaterial = next++;
                if (m.mHasVertexColor)
                    plan.mVertexColor = next++;
                if (m.mHasStencil)
                    plan.mStencil = next++;
                if (m.mHasZBuffer)
                    plan.mZBuffer = next++;
                if (m.mHasSpecularProperty)
                    plan.mSpecular = next++;
                plans.push_back(plan);
            }

            Writer w;
            w.mData = "NetImmerse File Format, Version 4.0.0.2\n";
            w.put<std::uint32_t>(0x04000002);
            w.put<std::uint32_t>(static_cast<std::uint32_t>(next));

            std::vector<std::int32_t> children;
            for (const Plan& plan : plans)
                children.push_back(plan.mShape);
            w.putString("NiNode");
            putAVObject(w, rootName, {});
            w.putRefs(children);
            w.putRefs({}); // effects

            for (std::size_t g = 0; g < groups.size(); ++g)
            {
                const Group& group = groups[g];
                const Material& m = group.mMaterial;
                const Plan& plan = plans[g];

                std::vector<std::int32_t> properties;
                for (std::int32_t p : { plan.mTexturing, plan.mAlpha, plan.mMaterial, plan.mVertexColor, plan.mStencil,
                         plan.mZBuffer, plan.mSpecular })
                    if (p >= 0)
                        properties.push_back(p);
                w.putString("NiTriShape");
                putAVObject(w, "Distant", properties);
                w.put<std::int32_t>(plan.mData);
                w.put<std::int32_t>(-1); // skin

                w.putString("NiTriShapeData");
                w.put<std::uint16_t>(static_cast<std::uint16_t>(group.mVertices.size()));
                w.putBool(true);
                for (const Vertex& v : group.mVertices)
                    w.putVec(osg::Vec3f(v.mPosition[0], v.mPosition[1], v.mPosition[2]));
                w.putBool(group.mHasNormals);
                if (group.mHasNormals)
                    for (const Vertex& v : group.mVertices)
                        w.putVec(osg::Vec3f(v.mNormal[0], v.mNormal[1], v.mNormal[2]));
                osg::BoundingSpheref sphere;
                for (const Vertex& v : group.mVertices)
                    sphere.expandBy(osg::Vec3f(v.mPosition[0], v.mPosition[1], v.mPosition[2]));
                w.putVec(sphere.center());
                w.put<float>(sphere.radius());
                w.putBool(group.mHasColors);
                if (group.mHasColors)
                    for (const Vertex& v : group.mVertices)
                        for (float c : v.mColor)
                            w.put(c);
                w.put<std::uint16_t>(group.mHasUv ? 1 : 0); // UV sets
                w.putBool(group.mHasUv);
                if (group.mHasUv)
                    for (const Vertex& v : group.mVertices)
                    {
                        w.put(v.mUv[0]);
                        w.put(v.mUv[1]);
                    }
                w.put<std::uint16_t>(static_cast<std::uint16_t>(group.mIndices.size() / 3));
                w.put<std::uint32_t>(static_cast<std::uint32_t>(group.mIndices.size()));
                for (std::uint32_t index : group.mIndices)
                    w.put(static_cast<std::uint16_t>(index));
                w.put<std::uint16_t>(0); // match groups

                if (plan.mTexturing >= 0)
                {
                    w.putString("NiTexturingProperty");
                    w.mData += m.texturing(plan.mSources);
                    for (int slot = 0; slot < 7; ++slot)
                    {
                        if (plan.mSources[slot] < 0)
                            continue;
                        w.putString("NiSourceTexture");
                        putObject(w, "");
                        w.put<std::uint8_t>(1); // external
                        w.putString(m.mSlots[slot].mFile);
                        w.put<std::uint32_t>(5); // pixel layout: default
                        w.put<std::uint32_t>(2); // mip maps: default
                        w.put<std::uint32_t>(3); // alpha: default
                        w.put<std::uint8_t>(1); // static
                    }
                }
                if (plan.mAlpha >= 0)
                {
                    w.putString("NiAlphaProperty");
                    w.mData += m.alpha();
                }
                if (plan.mMaterial >= 0)
                {
                    w.putString("NiMaterialProperty");
                    w.mData += m.material();
                }
                if (plan.mVertexColor >= 0)
                {
                    w.putString("NiVertexColorProperty");
                    w.mData += m.vertexColor();
                }
                if (plan.mStencil >= 0)
                {
                    w.putString("NiStencilProperty");
                    w.mData += m.stencil();
                }
                if (plan.mZBuffer >= 0)
                {
                    w.putString("NiZBufferProperty");
                    w.mData += m.zBuffer();
                }
                if (plan.mSpecular >= 0)
                {
                    w.putString("NiSpecularProperty");
                    w.mData += m.specular();
                }
            }

            w.putRefs({ 0 }); // roots
            return std::move(w.mData);
        }

        // Uncompressed RGBA DDS with its mip levels (alpha weighted box filter)
        // One mip level as DXT5 (etcpak takes BGRA, in rows padded to blocks of 4)
        void compressBc3(const std::vector<unsigned char>& rgba, int width, int height, std::string& out)
        {
            const int pw = (width + 3) & ~3, ph = (height + 3) & ~3;
            std::vector<std::uint32_t> padded(static_cast<std::size_t>(pw) * ph);
            for (int y = 0; y < ph; ++y)
                for (int x = 0; x < pw; ++x)
                {
                    const unsigned char* in
                        = &rgba[(static_cast<std::size_t>(std::min(y, height - 1)) * width + std::min(x, width - 1)) * 4];
                    const unsigned char bgra[4] = { in[2], in[1], in[0], in[3] };
                    std::memcpy(&padded[static_cast<std::size_t>(y) * pw + x], bgra, 4);
                }
            const std::size_t blocks = static_cast<std::size_t>(pw / 4) * (ph / 4);
            std::vector<std::uint64_t> compressed(blocks * 2);
            CompressBc3(padded.data(), compressed.data(), static_cast<std::uint32_t>(blocks), static_cast<std::size_t>(pw));
            out.append(reinterpret_cast<const char*>(compressed.data()), compressed.size() * sizeof(std::uint64_t));
        }

        // The card's texture with its mip levels, as DXT5: a quarter of RGBA8. Where the GPU has no S3TC, the image hooks
        // convert it like any other DDS.
        std::string writeDds(const std::vector<unsigned char>& rgba, int width, int height)
        {
            std::vector<std::vector<unsigned char>> levels{ rgba };
            std::vector<std::pair<int, int>> sizes{ { width, height } };
            while (sizes.back().first > 1 || sizes.back().second > 1)
            {
                const auto [w, h] = sizes.back();
                const int nw = std::max(1, w / 2), nh = std::max(1, h / 2);
                const std::vector<unsigned char>& src = levels.back();
                std::vector<unsigned char> dst(static_cast<std::size_t>(nw) * nh * 4);
                for (int y = 0; y < nh; ++y)
                    for (int x = 0; x < nw; ++x)
                    {
                        float sum[4] = {}, weight = 0.f;
                        for (int sy = 0; sy < 2; ++sy)
                            for (int sx = 0; sx < 2; ++sx)
                            {
                                const int ox = std::min(w - 1, x * 2 + sx), oy = std::min(h - 1, y * 2 + sy);
                                const unsigned char* s = &src[(static_cast<std::size_t>(oy) * w + ox) * 4];
                                const float a = s[3] / 255.f;
                                // transparent texels still carry the dilated colour, at a small weight
                                const float wgt = a + 0.01f;
                                for (int c = 0; c < 3; ++c)
                                    sum[c] += s[c] * wgt;
                                weight += wgt;
                                sum[3] += s[3];
                            }
                        unsigned char* d = &dst[(static_cast<std::size_t>(y) * nw + x) * 4];
                        for (int c = 0; c < 3; ++c)
                            d[c] = static_cast<unsigned char>(sum[c] / weight + 0.5f);
                        d[3] = static_cast<unsigned char>(sum[3] / 4.f + 0.5f);
                    }
                levels.push_back(std::move(dst));
                sizes.emplace_back(nw, nh);
            }

            Writer w;
            w.mData = "DDS ";
            w.put<std::uint32_t>(124);
            w.put<std::uint32_t>(0x1 | 0x2 | 0x4 | 0x1000 | 0x20000 | 0x80000); // caps, height, width, format, mips, size
            w.put<std::uint32_t>(static_cast<std::uint32_t>(height));
            w.put<std::uint32_t>(static_cast<std::uint32_t>(width));
            w.put<std::uint32_t>(static_cast<std::uint32_t>(((width + 3) / 4) * ((height + 3) / 4) * 16));
            w.put<std::uint32_t>(0); // depth
            w.put<std::uint32_t>(static_cast<std::uint32_t>(levels.size()));
            for (int i = 0; i < 11; ++i)
                w.put<std::uint32_t>(0);
            w.put<std::uint32_t>(32);
            w.put<std::uint32_t>(0x4); // four CC
            w.mData += "DXT5";
            for (int i = 0; i < 5; ++i)
                w.put<std::uint32_t>(0);
            w.put<std::uint32_t>(0x1000 | 0x8 | 0x400000); // texture, complex, mip map
            for (int i = 0; i < 4; ++i)
                w.put<std::uint32_t>(0);
            for (std::size_t i = 0; i < levels.size(); ++i)
                compressBc3(levels[i], sizes[i].first, sizes[i].second, w.mData);
            return std::move(w.mData);
        }

        // ---- reading the mesh

        struct NotMade
        {
            std::string mReason;
        };

        // The visible shapes of a NIF, with what they are drawn with, in the mesh's coordinates; throws NotMade for what
        // a distant version cannot show the same way
        class MeshReader
        {
        public:
            std::vector<Shape> read(const Nif::FileView& file)
            {
                if (file.getVersion() != Nif::NIFFile::VER_MW)
                    throw NotMade{ "not a Morrowind NIF" };
                for (std::size_t i = 0; i < file.numRoots(); ++i)
                    if (const auto* root = dynamic_cast<const Nif::NiAVObject*>(file.getRoot(i)))
                    {
                        mHasMarkers = false;
                        for (const auto& extra : root->getExtraList())
                            if (!extra.empty() && extra->mRecordType == Nif::RC_NiStringExtraData
                                && static_cast<const Nif::NiStringExtraData*>(extra.getPtr())->mData == "MRK")
                                mHasMarkers = true;
                        // the engine drops the root node's own transform (Nif::NiNode::read)
                        readObject(root, osg::Matrixf(), Material(), true);
                    }
                return std::move(mShapes);
            }

        private:
            void readObject(const Nif::NiAVObject* object, const osg::Matrixf& parent, Material material, bool root)
            {
                if (object == nullptr || object->isHidden() || object->mRecordType == Nif::RC_RootCollisionNode)
                    return;
                // what the engine's loader skips (NifOsg::Loader)
                const std::string& name = object->mName;
                if ((root && Misc::StringUtils::ciEqual(name, "Bounding Box"))
                    || Misc::StringUtils::ciStartsWith(name, "shadow")
                    || Misc::StringUtils::ciStartsWith(name, "tri shadow")
                    || Misc::StringUtils::ciStartsWith(name, "EditorMarker")
                    || Misc::StringUtils::ciStartsWith(name, "VisibilityEditorMarker")
                    || (mHasMarkers && Misc::StringUtils::ciStartsWith(name, "tri editormarker")))
                    return;
                if (!object->mController.empty())
                    throw NotMade{ "animated (" + name + ")" };
                if (object->mRecordType == Nif::RC_NiBillboardNode)
                    throw NotMade{ "billboard (" + name + ")" };
                const osg::Matrixf matrix = object->mTransform.toMatrix() * parent;

                for (const auto& property : object->mProperties)
                    if (!property.empty())
                        applyProperty(*property.getPtr(), material);

                if (object->mRecordType == Nif::RC_NiTriShape || object->mRecordType == Nif::RC_NiTriStrips)
                {
                    readGeometry(static_cast<const Nif::NiGeometry&>(*object), matrix, material);
                    return;
                }
                if (dynamic_cast<const Nif::NiGeometry*>(object) != nullptr)
                    throw NotMade{ "has " + object->mRecordName };

                if (const auto* node = dynamic_cast<const Nif::NiNode*>(object))
                {
                    for (const auto& effect : node->mEffects)
                        if (!effect.empty())
                            throw NotMade{ "has an effect (" + effect->mRecordName + ")" };
                    // a switch or LOD node shows one child: the first (the most detailed level)
                    const bool oneChild
                        = object->mRecordType == Nif::RC_NiSwitchNode || object->mRecordType == Nif::RC_NiLODNode;
                    for (const auto& child : node->mChildren)
                    {
                        if (child.empty())
                            continue;
                        readObject(child.getPtr(), matrix, material, false);
                        if (oneChild)
                            break;
                    }
                }
            }

            static void applyProperty(const Nif::NiProperty& property, Material& m)
            {
                if (!property.mController.empty())
                    throw NotMade{ "animated " + property.mRecordName };
                switch (property.mRecordType)
                {
                    case Nif::RC_NiTexturingProperty:
                    {
                        const auto& p = static_cast<const Nif::NiTexturingProperty&>(property);
                        m.mHasTexturing = true;
                        m.mTexturingFlags = p.mFlags;
                        m.mApplyMode = static_cast<std::uint32_t>(p.mApplyMode);
                        for (std::size_t slot = 0; slot < m.mSlots.size(); ++slot)
                        {
                            m.mSlots[slot] = Material::Slot();
                            // the bump map needs more than the slot (luma, matrix) and does nothing far away
                            if (slot >= p.mTextures.size() || slot == Nif::NiTexturingProperty::BumpTexture)
                                continue;
                            const auto& texture = p.mTextures[slot];
                            if (!texture.mEnabled || texture.mSourceTexture.empty())
                                continue;
                            if (!texture.mSourceTexture->mExternal)
                                throw NotMade{ "embedded texture" };
                            if (texture.mUVSet != 0)
                                throw NotMade{ "second UV set" };
                            m.mSlots[slot].mFile = texture.mSourceTexture->mFile;
                            m.mSlots[slot].mClamp = texture.mClamp;
                            m.mSlots[slot].mFilter = texture.mFilter;
                        }
                        break;
                    }
                    case Nif::RC_NiAlphaProperty:
                    {
                        const auto& p = static_cast<const Nif::NiAlphaProperty&>(property);
                        m.mHasAlpha = true;
                        m.mAlphaFlags = p.mFlags;
                        m.mAlphaThreshold = p.mThreshold;
                        break;
                    }
                    case Nif::RC_NiMaterialProperty:
                    {
                        const auto& p = static_cast<const Nif::NiMaterialProperty&>(property);
                        m.mHasMaterial = true;
                        m.mMaterialFlags = p.mFlags;
                        m.mAmbient = p.mAmbient;
                        m.mDiffuse = p.mDiffuse;
                        m.mSpecular = p.mSpecular;
                        m.mEmissive = p.mEmissive;
                        m.mGlossiness = p.mGlossiness;
                        m.mAlpha = p.mAlpha;
                        break;
                    }
                    case Nif::RC_NiVertexColorProperty:
                    {
                        const auto& p = static_cast<const Nif::NiVertexColorProperty&>(property);
                        m.mHasVertexColor = true;
                        m.mVertexColorFlags = p.mFlags;
                        m.mVertexMode = static_cast<std::uint32_t>(p.mVertexMode);
                        m.mLightingMode = static_cast<std::uint32_t>(p.mLightingMode);
                        break;
                    }
                    case Nif::RC_NiStencilProperty:
                    {
                        const auto& p = static_cast<const Nif::NiStencilProperty&>(property);
                        m.mHasStencil = true;
                        m.mStencilFlags = p.mFlags;
                        m.mStencilEnabled = p.mEnabled ? 1 : 0;
                        m.mStencil[0] = static_cast<std::uint32_t>(p.mTestFunction);
                        m.mStencil[1] = p.mStencilRef;
                        m.mStencil[2] = p.mStencilMask;
                        m.mStencil[3] = static_cast<std::uint32_t>(p.mFailAction);
                        m.mStencil[4] = static_cast<std::uint32_t>(p.mZFailAction);
                        m.mStencil[5] = static_cast<std::uint32_t>(p.mPassAction);
                        m.mStencil[6] = static_cast<std::uint32_t>(p.mDrawMode);
                        break;
                    }
                    case Nif::RC_NiZBufferProperty:
                        m.mHasZBuffer = true;
                        m.mZBufferFlags = static_cast<const Nif::NiZBufferProperty&>(property).mFlags;
                        break;
                    case Nif::RC_NiSpecularProperty:
                        m.mHasSpecularProperty = true;
                        m.mSpecularFlags = static_cast<const Nif::NiSpecularProperty&>(property).mEnable ? 1 : 0;
                        break;
                    default:
                        // wireframe, dither, shade, fog: nothing a distant mesh shows
                        break;
                }
            }

            void readGeometry(const Nif::NiGeometry& geometry, const osg::Matrixf& matrix, const Material& material)
            {
                if (!geometry.mSkin.empty())
                    throw NotMade{ "skinned" };
                if (geometry.mData.empty())
                    return;
                const Nif::NiGeometryData& data = *geometry.mData.getPtr();

                std::vector<std::uint32_t> indices;
                if (data.mRecordType == Nif::RC_NiTriShapeData)
                {
                    const auto& triangles = static_cast<const Nif::NiTriShapeData&>(data).mTriangles;
                    indices.assign(triangles.begin(), triangles.end());
                }
                else if (data.mRecordType == Nif::RC_NiTriStripsData)
                {
                    for (const auto& strip : static_cast<const Nif::NiTriStripsData&>(data).mStrips)
                        for (std::size_t i = 2; i < strip.size(); ++i)
                        {
                            if (strip[i - 2] == strip[i - 1] || strip[i - 1] == strip[i] || strip[i - 2] == strip[i])
                                continue;
                            // every other triangle of a strip is wound the other way
                            if (i % 2 == 0)
                                indices.insert(indices.end(), { strip[i - 2], strip[i - 1], strip[i] });
                            else
                                indices.insert(indices.end(), { strip[i - 2], strip[i], strip[i - 1] });
                        }
                }
                else
                    return;

                const std::size_t count = data.mVertices.size();
                for (std::size_t i = 0; i + 2 < indices.size(); i += 3)
                    if (indices[i] >= count || indices[i + 1] >= count || indices[i + 2] >= count)
                        throw NotMade{ "bad triangle" };

                Shape shape;
                Group& group = shape.mGroup;
                group.mMaterial = material;
                group.mHasNormals = data.mNormals.size() == count;
                group.mHasColors = data.mColors.size() == count;
                const std::vector<osg::Vec2f>* uvs
                    = !data.mUVList.empty() && data.mUVList[0].size() == count ? &data.mUVList[0] : nullptr;
                group.mHasUv = uvs != nullptr;
                group.mVertices.resize(count);
                for (std::size_t i = 0; i < count; ++i)
                {
                    Vertex& v = group.mVertices[i];
                    // row vectors times the matrix, positions and normals alike
                    const osg::Vec3f p = data.mVertices[i] * matrix;
                    osg::Vec3f n = group.mHasNormals ? osg::Matrixf::transform3x3(data.mNormals[i], matrix)
                                                     : osg::Vec3f(0, 0, 1);
                    n.normalize();
                    const osg::Vec2f uv = uvs != nullptr ? (*uvs)[i] : osg::Vec2f();
                    const osg::Vec4f c = group.mHasColors ? data.mColors[i] : osg::Vec4f(1, 1, 1, 1);
                    v = Vertex{ { p.x(), p.y(), p.z() }, { n.x(), n.y(), n.z() }, { uv.x(), uv.y() },
                        { c.r(), c.g(), c.b(), c.a() } };
                }
                group.mIndices = std::move(indices);
                shape.mKey = material.key() + (group.mHasNormals ? "n" : "") + (group.mHasColors ? "c" : "")
                    + (group.mHasUv ? "t" : "");
                mShapes.push_back(std::move(shape));
            }

            std::vector<Shape> mShapes;
            bool mHasMarkers = false;
        };

        // Shapes drawn the same way, one group each
        std::vector<Group> gather(std::vector<Shape> shapes)
        {
            std::map<std::string, std::size_t> byKey;
            std::vector<Group> groups;
            for (Shape& shape : shapes)
            {
                const auto [found, added] = byKey.emplace(shape.mKey, groups.size());
                if (added)
                {
                    groups.push_back(std::move(shape.mGroup));
                    continue;
                }
                Group& group = groups[found->second];
                const auto base = static_cast<std::uint32_t>(group.mVertices.size());
                group.mVertices.insert(group.mVertices.end(), shape.mGroup.mVertices.begin(),
                    shape.mGroup.mVertices.end());
                for (std::uint32_t index : shape.mGroup.mIndices)
                    group.mIndices.push_back(base + index);
            }
            return groups;
        }

        std::size_t triangleCount(const std::vector<Group>& groups)
        {
            std::size_t n = 0;
            for (const Group& group : groups)
                n += group.mIndices.size() / 3;
            return n;
        }

        // ---- simplified meshes

        std::vector<Group> simplifyGroups(const std::vector<Group>& input, const Settings& settings, int level)
        {
            const float error = settings.simplifyError(level);
            std::vector<Group> output;
            for (const Group& source : input)
            {
                if (source.mIndices.empty())
                    continue;
                // the same vertex in every attribute once, so the simplifier sees the shape's surface
                std::vector<unsigned int> remap(source.mVertices.size());
                const std::size_t unique = meshopt_generateVertexRemap(remap.data(), source.mIndices.data(),
                    source.mIndices.size(), source.mVertices.data(), source.mVertices.size(), sizeof(Vertex));
                Group group;
                group.mMaterial = source.mMaterial;
                group.mHasNormals = source.mHasNormals;
                group.mHasColors = source.mHasColors;
                group.mHasUv = source.mHasUv;
                group.mVertices.resize(unique);
                meshopt_remapVertexBuffer(group.mVertices.data(), source.mVertices.data(), source.mVertices.size(),
                    sizeof(Vertex), remap.data());
                std::vector<unsigned int> indices(source.mIndices.size());
                meshopt_remapIndexBuffer(indices.data(), source.mIndices.data(), source.mIndices.size(), remap.data());

                // locked borders: where this group meets another, or a mesh's open edge, stays where it is
                std::vector<unsigned int> simplified(indices.size());
                // a quarter of the triangles at level 0, half of that at each level after
                const std::size_t target = std::max<std::size_t>(1, indices.size() / 3 / (4u << level)) * 3;
                const std::size_t count = meshopt_simplify(simplified.data(), indices.data(), indices.size(),
                    group.mVertices[0].mPosition, group.mVertices.size(), sizeof(Vertex), target, error,
                    meshopt_SimplifyLockBorder, nullptr);
                simplified.resize(count);
                if (simplified.empty())
                    continue;
                // only the vertices still used, in the order they are drawn
                const std::size_t used = meshopt_optimizeVertexFetch(group.mVertices.data(), simplified.data(),
                    simplified.size(), group.mVertices.data(), group.mVertices.size(), sizeof(Vertex));
                group.mVertices.resize(used);
                if (used > std::numeric_limits<std::uint16_t>::max())
                    throw NotMade{ "more than 65535 vertices in one material" };
                group.mIndices.assign(simplified.begin(), simplified.end());
                output.push_back(std::move(group));
            }
            return output;
        }

        std::string makeSimplified(const std::vector<Group>& input, const Settings& settings, int level)
        {
            const std::size_t original = triangleCount(input);
            if (original < static_cast<std::size_t>(settings.mSimplifyMinTriangles))
                throw NotMade{ std::to_string(original) + " triangles, small enough already" };
            // a level is worth keeping only when it is a fifth smaller than the one paging would use without it
            const std::size_t before = level == 0 ? original : triangleCount(simplifyGroups(input, settings, level - 1));
            const std::vector<Group> output = simplifyGroups(input, settings, level);
            const std::size_t after = triangleCount(output);
            if (after == 0 || after * 5 > before * 4)
                throw NotMade{ std::to_string(before) + " triangles" + (level == 0 ? "" : " at the level before")
                    + ", simplified only to " + std::to_string(after) };
            return writeNif(sSimplifiedRootName, output);
        }

        // ---- cards

        struct Texture
        {
            int mWidth = 0, mHeight = 0;
            std::vector<unsigned char> mRgba; // row 0 is v = 0, as the engine uploads it

            osg::Vec4f sample(osg::Vec2f uv) const
            {
                // wrapped, nearest: the views are small, and supersampled
                const float u = uv.x() - std::floor(uv.x()), v = uv.y() - std::floor(uv.y());
                const int x = std::min(mWidth - 1, static_cast<int>(u * mWidth));
                const int y = std::min(mHeight - 1, static_cast<int>(v * mHeight));
                const unsigned char* p = &mRgba[(static_cast<std::size_t>(y) * mWidth + x) * 4];
                return osg::Vec4f(p[0], p[1], p[2], p[3]) / 255.f;
            }
        };

        // The texels of an image as RGBA8, rows in the engine's order (see Resource::ImageManager: DDS data as it is,
        // anything else flipped to top left)
        bool toRgba8(const osg::Image& image, bool dds, Texture& out)
        {
            out.mWidth = image.s();
            out.mHeight = image.t();
            if (out.mWidth <= 0 || out.mHeight <= 0 || image.r() != 1 || image.data() == nullptr)
                return false;
            if (image.isCompressed())
            {
                if (!decodeS3tcRgba8(image, out.mRgba))
                    return false;
            }
            else
            {
                if (image.getDataType() != GL_UNSIGNED_BYTE)
                    return false;
                const GLenum format = image.getPixelFormat();
                int channels;
                switch (format)
                {
                    case GL_RGBA:
                    case GL_BGRA:
                        channels = 4;
                        break;
                    case GL_RGB:
                    case GL_BGR:
                        channels = 3;
                        break;
                    case GL_LUMINANCE_ALPHA:
                        channels = 2;
                        break;
                    case GL_LUMINANCE:
                    case GL_ALPHA:
                        channels = 1;
                        break;
                    default:
                        return false;
                }
                const bool bgr = format == GL_BGRA || format == GL_BGR;
                out.mRgba.resize(static_cast<std::size_t>(out.mWidth) * out.mHeight * 4);
                for (int y = 0; y < out.mHeight; ++y)
                {
                    const unsigned char* row = image.data(0, y);
                    for (int x = 0; x < out.mWidth; ++x)
                    {
                        const unsigned char* p = row + x * channels;
                        unsigned char* d = &out.mRgba[(static_cast<std::size_t>(y) * out.mWidth + x) * 4];
                        if (channels >= 3)
                        {
                            d[0] = p[bgr ? 2 : 0];
                            d[1] = p[1];
                            d[2] = p[bgr ? 0 : 2];
                            d[3] = channels == 4 ? p[3] : 255;
                        }
                        else if (format == GL_ALPHA)
                        {
                            d[0] = d[1] = d[2] = 255;
                            d[3] = p[0];
                        }
                        else
                        {
                            d[0] = d[1] = d[2] = p[0];
                            d[3] = channels == 2 ? p[1] : 255;
                        }
                    }
                }
            }
            if (!dds && image.getOrigin() == osg::Image::BOTTOM_LEFT)
            {
                const std::size_t rowBytes = static_cast<std::size_t>(out.mWidth) * 4;
                for (int y = 0; y < out.mHeight / 2; ++y)
                    std::swap_ranges(out.mRgba.begin() + y * rowBytes, out.mRgba.begin() + (y + 1) * rowBytes,
                        out.mRgba.begin() + (out.mHeight - 1 - y) * rowBytes);
            }
            return true;
        }

        // The base texture of a group, read for the CPU
        Texture loadTexture(const VFS::Manager& vfs, const std::string& file)
        {
            const VFS::Path::Normalized path
                = Misc::ResourceHelpers::correctTexturePath(VFS::Path::toNormalized(file), vfs);
            std::string extension(Misc::getFileExtension(path.value()));
            if (extension == "targa")
                extension = "tga";
            osgDB::ReaderWriter* reader = osgDB::Registry::instance()->getReaderWriterForExtension(extension);
            if (reader == nullptr)
                throw NotMade{ "no reader for " + extension };
            static const osg::ref_ptr<osgDB::Options> options
                = new osgDB::Options("dds_dxt1_detect_rgba ignoreTga2Fields");
            Files::IStreamPtr stream = vfs.get(path);
            BypassImageHooks bypass;
            osgDB::ReaderWriter::ReadResult result = reader->readImage(*stream, options);
            Texture texture;
            if (!result.success() || !toRgba8(*result.getImage(), extension == "dds", texture))
                throw NotMade{ "texture " + file + " cannot be read" };
            return texture;
        }

        struct View
        {
            osg::Vec3f mToCamera, mRight;
            float mHalfWidth;
        };

        struct Card
        {
            int mSize; // texels per view
            int mAtlasWidth, mAtlasHeight; // 3 x 2 views, in the top left of a power of two
            std::vector<unsigned char> mRgba;
            osg::Vec3f mCenter; // on the ground plane through the middle of the bounds
            float mBottom, mTop;
            std::array<View, sViews> mViews;
        };

        struct DrawGroup
        {
            const Group* mGroup;
            const Texture* mTexture; // null: vertex colours only
        };

        void drawView(const std::vector<DrawGroup>& groups, const Card& card, const View& view, std::vector<float>& rgba)
        {
            const int inner = (card.mSize - 2 * sGutter) * sSupersample;
            const int size = card.mSize * sSupersample;
            const int gutter = sGutter * sSupersample;
            std::vector<float> depth(static_cast<std::size_t>(size) * size, -std::numeric_limits<float>::max());
            rgba.assign(static_cast<std::size_t>(size) * size * 4, 0.f);

            const float height = card.mTop - card.mBottom;
            auto project = [&](const Vertex& v) {
                const osg::Vec3f p(v.mPosition[0], v.mPosition[1], v.mPosition[2]);
                const osg::Vec3f d = p - card.mCenter;
                return osg::Vec3f(gutter + (d * view.mRight + view.mHalfWidth) / (2 * view.mHalfWidth) * inner,
                    gutter + (card.mTop - p.z()) / height * inner, d * view.mToCamera);
            };

            for (const DrawGroup& draw : groups)
            {
                const Group& group = *draw.mGroup;
                for (std::size_t i = 0; i + 2 < group.mIndices.size(); i += 3)
                {
                    const Vertex& va = group.mVertices[group.mIndices[i]];
                    const Vertex& vb = group.mVertices[group.mIndices[i + 1]];
                    const Vertex& vc = group.mVertices[group.mIndices[i + 2]];
                    const osg::Vec3f a = project(va), b = project(vb), c = project(vc);
                    const float area = (b.x() - a.x()) * (c.y() - a.y()) - (b.y() - a.y()) * (c.x() - a.x());
                    if (std::abs(area) < 1e-8f)
                        continue;
                    const int x0 = std::max(0, static_cast<int>(std::floor(std::min({ a.x(), b.x(), c.x() }))));
                    const int x1 = std::min(size - 1, static_cast<int>(std::ceil(std::max({ a.x(), b.x(), c.x() }))));
                    const int y0 = std::max(0, static_cast<int>(std::floor(std::min({ a.y(), b.y(), c.y() }))));
                    const int y1 = std::min(size - 1, static_cast<int>(std::ceil(std::max({ a.y(), b.y(), c.y() }))));
                    for (int y = y0; y <= y1; ++y)
                    {
                        for (int x = x0; x <= x1; ++x)
                        {
                            const float px = x + 0.5f, py = y + 0.5f;
                            // barycentric weights, either winding: both sides of every triangle are drawn
                            const float w0 = ((b.x() - px) * (c.y() - py) - (b.y() - py) * (c.x() - px)) / area;
                            const float w1 = ((c.x() - px) * (a.y() - py) - (c.y() - py) * (a.x() - px)) / area;
                            const float w2 = 1.f - w0 - w1;
                            if (w0 < 0 || w1 < 0 || w2 < 0)
                                continue;
                            const float z = w0 * a.z() + w1 * b.z() + w2 * c.z();
                            float& nearest = depth[static_cast<std::size_t>(y) * size + x];
                            if (z <= nearest)
                                continue;
                            osg::Vec4f color(1, 1, 1, 1);
                            if (group.mHasColors)
                                for (int k = 0; k < 4; ++k)
                                    color[k] = va.mColor[k] * w0 + vb.mColor[k] * w1 + vc.mColor[k] * w2;
                            if (draw.mTexture != nullptr)
                            {
                                const osg::Vec2f uv(va.mUv[0] * w0 + vb.mUv[0] * w1 + vc.mUv[0] * w2,
                                    va.mUv[1] * w0 + vb.mUv[1] * w1 + vc.mUv[1] * w2);
                                color = osg::componentMultiply(color, draw.mTexture->sample(uv));
                            }
                            // cut out like an alpha tested leaf
                            if (color.a() < 0.5f)
                                continue;
                            nearest = z;
                            float* out = &rgba[(static_cast<std::size_t>(y) * size + x) * 4];
                            out[0] = color.r();
                            out[1] = color.g();
                            out[2] = color.b();
                            out[3] = 1.f;
                        }
                    }
                }
            }
        }

        // Colour of the transparent texels from their opaque neighbours, so filtering does not darken the edges
        void dilate(std::vector<unsigned char>& rgba, int width, int x0, int y0, int w, int h, int passes)
        {
            for (int pass = 0; pass < passes; ++pass)
            {
                std::vector<unsigned char> next = rgba;
                bool changed = false;
                for (int y = y0; y < y0 + h; ++y)
                    for (int x = x0; x < x0 + w; ++x)
                    {
                        unsigned char* p = &next[(static_cast<std::size_t>(y) * width + x) * 4];
                        if (p[3] != 0 || (p[0] | p[1] | p[2]) != 0)
                            continue;
                        int sum[3] = {}, n = 0;
                        for (int dy = -1; dy <= 1; ++dy)
                            for (int dx = -1; dx <= 1; ++dx)
                            {
                                const int nx = x + dx, ny = y + dy;
                                if (nx < x0 || ny < y0 || nx >= x0 + w || ny >= y0 + h)
                                    continue;
                                const unsigned char* q = &rgba[(static_cast<std::size_t>(ny) * width + nx) * 4];
                                if (q[3] == 0 && (q[0] | q[1] | q[2]) == 0)
                                    continue;
                                for (int c = 0; c < 3; ++c)
                                    sum[c] += q[c];
                                ++n;
                            }
                        if (n == 0)
                            continue;
                        for (int c = 0; c < 3; ++c)
                            p[c] = static_cast<unsigned char>(std::max(1, sum[c] / n));
                        changed = true;
                    }
                rgba.swap(next);
                if (!changed)
                    break;
            }
        }

        void drawCard(const std::vector<DrawGroup>& groups, int viewSize, Card& card)
        {
            osg::BoundingBoxf bounds;
            for (const DrawGroup& draw : groups)
                for (std::uint32_t index : draw.mGroup->mIndices)
                {
                    const Vertex& v = draw.mGroup->mVertices[index];
                    bounds.expandBy(osg::Vec3f(v.mPosition[0], v.mPosition[1], v.mPosition[2]));
                }
            if (!bounds.valid())
                throw NotMade{ "nothing to draw" };
            card.mSize = viewSize;
            // powers of two: OSG cannot use a mipmapped compressed texture of another size on OpenGL ES (it can only
            // rescale uncompressed ones), so the space right of and below the 3 x 2 views is left empty
            const auto powerOfTwo = [](int n) {
                int p = 1;
                while (p < n)
                    p *= 2;
                return p;
            };
            card.mAtlasWidth = powerOfTwo(viewSize * 3);
            card.mAtlasHeight = powerOfTwo(viewSize * 2);
            card.mCenter = osg::Vec3f(bounds.center().x(), bounds.center().y(), 0.f);
            card.mBottom = bounds.zMin();
            card.mTop = bounds.zMax();
            if (card.mTop - card.mBottom < 1.f)
                throw NotMade{ "flat" };

            card.mRgba.assign(static_cast<std::size_t>(card.mAtlasWidth) * card.mAtlasHeight * 4, 0);
            std::vector<float> supersampled;
            for (int i = 0; i < sViews; ++i)
            {
                const float angle = static_cast<float>(i) * static_cast<float>(osg::PI) / 3.f;
                View& view = card.mViews[i];
                view.mToCamera = osg::Vec3f(std::cos(angle), std::sin(angle), 0.f);
                view.mRight = osg::Vec3f(-std::sin(angle), std::cos(angle), 0.f);
                view.mHalfWidth = 0.f;
                for (const DrawGroup& draw : groups)
                    for (std::uint32_t index : draw.mGroup->mIndices)
                    {
                        const Vertex& v = draw.mGroup->mVertices[index];
                        const osg::Vec3f p(v.mPosition[0], v.mPosition[1], v.mPosition[2]);
                        view.mHalfWidth = std::max(view.mHalfWidth, std::abs((p - card.mCenter) * view.mRight));
                    }
                if (view.mHalfWidth < 0.5f)
                    throw NotMade{ "too thin" };

                drawView(groups, card, view, supersampled);
                const int size = viewSize * sSupersample;
                const int tileX = (i % 3) * viewSize, tileY = (i / 3) * viewSize;
                for (int y = 0; y < viewSize; ++y)
                    for (int x = 0; x < viewSize; ++x)
                    {
                        float sum[4] = {};
                        for (int sy = 0; sy < sSupersample; ++sy)
                            for (int sx = 0; sx < sSupersample; ++sx)
                            {
                                const float* s = &supersampled[(static_cast<std::size_t>(y * sSupersample + sy) * size
                                                                   + x * sSupersample + sx)
                                    * 4];
                                for (int c = 0; c < 3; ++c)
                                    sum[c] += s[c] * s[3];
                                sum[3] += s[3];
                            }
                        unsigned char* d
                            = &card.mRgba[(static_cast<std::size_t>(tileY + y) * card.mAtlasWidth + tileX + x) * 4];
                        if (sum[3] <= 0.f)
                            continue;
                        for (int c = 0; c < 3; ++c)
                            d[c] = static_cast<unsigned char>(std::clamp(sum[c] / sum[3] * 255.f + 0.5f, 0.f, 255.f));
                        // coverage, kept hard for the alpha test
                        d[3] = sum[3] / (sSupersample * sSupersample) >= 0.5f ? 255 : 0;
                    }
                dilate(card.mRgba, card.mAtlasWidth, tileX, tileY, viewSize, viewSize, 4);
            }
        }

        // Three crossed quads, both sides, each side textured with the view from its side
        Group cardGroup(const Card& card, std::string_view texture)
        {
            Group group;
            group.mHasNormals = true;
            group.mHasUv = true;
            Material& m = group.mMaterial;
            m.mHasTexturing = true;
            m.mSlots[Nif::NiTexturingProperty::BaseTexture].mFile = texture;
            m.mSlots[Nif::NiTexturingProperty::BaseTexture].mClamp = 0; // the atlas must not wrap into the next view
            m.mHasAlpha = true;
            m.mAlphaFlags = 0x0200 | (6 << 10); // alpha test, greater or equal
            m.mAlphaThreshold = 128;
            m.mHasMaterial = true;

            for (int i = 0; i < sViews; ++i)
            {
                const View& view = card.mViews[i];
                const float u0 = static_cast<float>((i % 3) * card.mSize + sGutter) / card.mAtlasWidth;
                const float u1 = static_cast<float>((i % 3 + 1) * card.mSize - sGutter) / card.mAtlasWidth;
                const float v0 = static_cast<float>((i / 3) * card.mSize + sGutter) / card.mAtlasHeight;
                const float v1 = static_cast<float>((i / 3 + 1) * card.mSize - sGutter) / card.mAtlasHeight;
                const osg::Vec3f left = card.mCenter - view.mRight * view.mHalfWidth;
                const osg::Vec3f right = card.mCenter + view.mRight * view.mHalfWidth;
                const auto base = static_cast<std::uint32_t>(group.mVertices.size());
                // counter-clockwise seen from the side the view was drawn from; lit like foliage, from above
                auto add = [&](const osg::Vec3f& p, float u, float v) {
                    group.mVertices.push_back(Vertex{ { p.x(), p.y(), p.z() }, { 0, 0, 1 }, { u, v }, { 1, 1, 1, 1 } });
                };
                add(left + osg::Vec3f(0, 0, card.mBottom), u0, v1);
                add(right + osg::Vec3f(0, 0, card.mBottom), u1, v1);
                add(right + osg::Vec3f(0, 0, card.mTop), u1, v0);
                add(left + osg::Vec3f(0, 0, card.mTop), u0, v0);
                group.mIndices.insert(group.mIndices.end(), { base, base + 1, base + 2, base, base + 2, base + 3 });
            }
            return group;
        }

        // The card's NIF and its texture
        std::pair<std::string, std::string> makeCard(
            const VFS::Manager& vfs, const std::vector<Group>& groups, int viewSize, const std::string& texturePath)
        {
            std::map<std::string, Texture> textures;
            std::vector<DrawGroup> draws;
            for (const Group& group : groups)
            {
                const std::string& file = group.mMaterial.mSlots[Nif::NiTexturingProperty::BaseTexture].mFile;
                const Texture* texture = nullptr;
                if (!file.empty() && group.mHasUv)
                {
                    auto found = textures.find(file);
                    if (found == textures.end())
                        found = textures.emplace(file, loadTexture(vfs, file)).first;
                    texture = &found->second;
                }
                draws.push_back({ &group, texture });
            }
            Card card;
            drawCard(draws, viewSize, card);
            // the NIF names textures relative to textures/
            const std::string_view name = std::string_view(texturePath).substr(std::string_view("textures/").size());
            return { writeNif(sCardRootName, { cardGroup(card, name) }),
                writeDds(card.mRgba, card.mAtlasWidth, card.mAtlasHeight) };
        }

        // ---- the archive

        class Cache
        {
        public:
            static Cache& get()
            {
                static Cache cache;
                return cache;
            }

            // An entry of NIF and texture; a NIF of no bytes is a mesh that was not made
            bool load(const BlobKey& key, std::string& nif, std::string& dds) const
            {
                if (!mStore)
                    return false;
                const std::size_t size = mStore->size(key);
                if (size < 4)
                    return false;
                std::string data(size, '\0');
                if (!mStore->read(key, 0, data.data(), size))
                    return false;
                std::uint32_t nifSize;
                std::memcpy(&nifSize, data.data(), 4);
                if (nifSize > size - 4)
                    return false;
                nif = data.substr(4, nifSize);
                dds = data.substr(4 + nifSize);
                return true;
            }

            bool contains(const BlobKey& key) const { return mStore && mStore->size(key) != 0; }

            void store(const BlobKey& key, const std::string& nif, const std::string& dds)
            {
                if (!mStore)
                    return;
                std::string data(4, '\0');
                const auto nifSize = static_cast<std::uint32_t>(nif.size());
                std::memcpy(data.data(), &nifSize, 4);
                data += nif;
                mStore->append(key, data.data(), data.size(), dds.data(), dds.size());
            }

        private:
            Cache()
            {
                const std::filesystem::path dir = cacheDirectory() / "impostors";
                std::error_code ec;
                std::filesystem::create_directories(dir, ec);
                if (ec)
                    return;
                auto store = std::make_unique<BlobStore>(dir / "impostors.bin", 0x50493341, sCacheVersion); // "A3IP"
                if (store->valid())
                    mStore = std::move(store);
            }

            std::unique_ptr<BlobStore> mStore;
        };

        // What the settings of a kind change in what is made
        std::uint64_t settingsHash(Kind kind, const Settings& settings, int level)
        {
            if (kind == Kind::Card)
                return 1 + (static_cast<std::uint64_t>(settings.mCardSize) << 8) + (2ull << 40); // 2: DXT5, power of two
            // level 0 has the hash it had before there were levels, so cached meshes stay valid
            const float levelError = settings.simplifyError(level);
            std::uint32_t error;
            std::memcpy(&error, &levelError, 4);
            return 2 + (static_cast<std::uint64_t>(error) << 8)
                + (static_cast<std::uint64_t>(settings.mSimplifyMinTriangles) << 40);
        }

        // A mesh that was not made, by its name and time: looked up without reading it
        BlobKey failureKey(std::string_view path, const VFS::File& file, std::uint64_t settings)
        {
            const auto time = static_cast<std::uint64_t>(file.getLastModified().time_since_epoch().count());
            return BlobKey{ fnv1a(path, 0xcbf29ce484222325ull) ^ time, fnv1a(path, 0x84222325cbf29ce4ull) ^ settings
                    ^ 0xfa11ed0000000000ull };
        }

        class DistantMesh
        {
        public:
            DistantMesh(const VFS::Manager& vfs, Kind kind, const Settings& settings, int level, VFS::File* source,
                VFS::Path::Normalized sourcePath, std::string texturePath)
                : mVfs(vfs)
                , mKind(kind)
                , mSettings(settings)
                , mLevel(level)
                , mSource(source)
                , mSourcePath(std::move(sourcePath))
                , mTexturePath(std::move(texturePath))
            {
            }

            Files::IStreamPtr openNif()
            {
                make();
                if (mNif.empty())
                    return mSource->open();
                return std::make_unique<std::istringstream>(mNif);
            }

            Files::IStreamPtr openTexture()
            {
                make();
                return std::make_unique<std::istringstream>(mDds);
            }

            VFS::File* source() const { return mSource; }

            static std::atomic<unsigned> sMade, sNotMade;

        private:
            void make()
            {
                std::call_once(mMade, [this] {
                    std::string sourceBytes;
                    try
                    {
                        sourceBytes = readAll(*mSource->open());
                    }
                    catch (const std::exception& e)
                    {
                        Log(Debug::Warning) << "Distant mesh of " << mSourcePath << ": " << e.what();
                        if (mKind == Kind::Card)
                            mDds = writeDds(std::vector<unsigned char>(4, 0), 1, 1);
                        return;
                    }
                    const std::uint64_t settings = settingsHash(mKind, mSettings, mLevel);
                    const BlobKey key{ fnv1a(sourceBytes, 0xcbf29ce484222325ull),
                        fnv1a(sourceBytes, 0x84222325cbf29ce4ull) ^ settings };
                    Cache& cache = Cache::get();
                    if (cache.load(key, mNif, mDds) && !mNif.empty())
                        return;
                    if (!build(sourceBytes))
                    {
                        mNif.clear();
                        // a card's texture name is listed whatever happens; nothing reads it
                        mDds = mKind == Kind::Card ? writeDds(std::vector<unsigned char>(4, 0), 1, 1) : std::string();
                        cache.store(failureKey(mSourcePath.value(), *mSource, settings), std::string(), std::string());
                        ++sNotMade;
                        return;
                    }
                    cache.store(key, mNif, mDds);
                    ++sMade;
                });
            }

            bool build(const std::string& sourceBytes)
            {
                try
                {
                    Nif::NIFFile file(mSourcePath);
                    Nif::Reader reader(file, nullptr);
                    reader.parse(std::make_unique<std::istringstream>(sourceBytes));
                    const std::vector<Group> groups = gather(MeshReader().read(Nif::FileView(file)));
                    if (groups.empty())
                        throw NotMade{ "nothing to draw" };
                    if (mKind == Kind::Card)
                        std::tie(mNif, mDds) = makeCard(mVfs, groups, mSettings.mCardSize, mTexturePath);
                    else
                        mNif = makeSimplified(groups, mSettings, mLevel);
                    return true;
                }
                catch (const NotMade& e)
                {
                    Log(Debug::Verbose) << "Distant mesh of " << mSourcePath << " not made: " << e.mReason;
                }
                catch (const std::exception& e)
                {
                    Log(Debug::Warning) << "Distant mesh of " << mSourcePath << " not made: " << e.what();
                }
                return false;
            }

            const VFS::Manager& mVfs;
            Kind mKind;
            const Settings& mSettings;
            int mLevel; // simplified meshes: 0 for _dist, n for _dist_n
            VFS::File* mSource; // belongs to an archive listed before this one
            VFS::Path::Normalized mSourcePath;
            std::string mTexturePath; // cards only
            std::once_flag mMade;
            std::string mNif, mDds; // no NIF: not made, the source is opened in its place
        };

        std::atomic<unsigned> DistantMesh::sMade{ 0 }, DistantMesh::sNotMade{ 0 };

        class DistantMeshFile final : public VFS::File
        {
        public:
            DistantMeshFile(std::shared_ptr<DistantMesh> mesh, bool texture, std::string stem)
                : mMesh(std::move(mesh))
                , mTexture(texture)
                , mStem(std::move(stem))
            {
            }

            Files::IStreamPtr open() override { return mTexture ? mMesh->openTexture() : mMesh->openNif(); }

            std::filesystem::file_time_type getLastModified() const override
            {
                return mMesh->source()->getLastModified();
            }

            std::string getStem() const override { return mStem; }

        private:
            std::shared_ptr<DistantMesh> mMesh;
            bool mTexture;
            std::string mStem;
        };

        class DistantMeshArchive final : public VFS::Archive
        {
        public:
            explicit DistantMeshArchive(const VFS::Manager& vfs)
                : mVfs(vfs)
            {
            }

            void listResources(VFS::FileMap& out) override
            {
                mFiles.clear();
                mSettings = readSettings();
                if (!mSettings.mCards && !mSettings.mSimplify)
                    return;

                constexpr std::string_view meshes = "meshes/", nif = ".nif";
                struct Found
                {
                    VFS::Path::Normalized mPath;
                    VFS::File* mFile;
                    Kind mKind;
                };
                std::vector<Found> found;
                for (const auto& [path, file] : out)
                {
                    const std::string_view name = path.value();
                    if (!name.starts_with(meshes) || !name.ends_with(nif))
                        continue;
                    const std::string_view stem = name.substr(name.rfind('/') + 1);
                    // already a distant version, or an actor's animation and skeleton files
                    if (stem.find(sDistantSuffix) != std::string_view::npos || stem.starts_with("x"))
                        continue;
                    if (mSettings.mCards && matches(mSettings.mCardPatterns, stem))
                        found.push_back({ path, file, Kind::Card });
                    else if (mSettings.mSimplify && matches(mSettings.mSimplifyPatterns, stem))
                        found.push_back({ path, file, Kind::Simplified });
                }

                unsigned cards = 0, simplified = 0, levels = 0, skipped = 0;
                for (Found& f : found)
                {
                    const std::string_view name = f.mPath.value();
                    const std::string_view base = name.substr(0, name.size() - nif.size());
                    const auto distantName = [&](int level) {
                        std::string result = std::string(base) + std::string(sDistantSuffix);
                        if (level > 0)
                            result += "_" + std::to_string(level);
                        return VFS::Path::Normalized(result + std::string(nif));
                    };
                    // a mesh with distant versions of its own keeps them (paging also looks for _dist_0 ...)
                    if (out.contains(distantName(0))
                        || out.contains(VFS::Path::Normalized(std::string(base) + std::string(sDistantSuffix) + "_0.nif")))
                        continue;

                    std::string flat(base.substr(meshes.size()));
                    std::replace(flat.begin(), flat.end(), '/', '_');
                    const std::string texturePath
                        = f.mKind == Kind::Card ? std::string(sTextureDirectory) + flat + ".dds" : std::string();
                    const std::string stem = std::string(base.substr(base.rfind('/') + 1)) + std::string(sDistantSuffix);
                    // paging asks for _dist_<n> for its chunks of 2^n cells, and takes the nearest level below that
                    // exists: a level that was not made is not listed, and the one before it is used
                    const int levelCount = f.mKind == Kind::Card ? 1 : mSettings.mSimplifyLevels;
                    for (int level = 0; level < levelCount; ++level)
                    {
                        if (out.contains(distantName(level)))
                            continue; // a mod's own
                        // not made before: listing it would only have the engine keep the mesh twice
                        if (Cache::get().contains(
                                failureKey(name, *f.mFile, settingsHash(f.mKind, mSettings, level))))
                        {
                            if (level == 0)
                                ++skipped;
                            continue;
                        }
                        auto mesh = std::make_shared<DistantMesh>(
                            mVfs, f.mKind, mSettings, level, f.mFile, f.mPath, texturePath);
                        add(out, distantName(level),
                            std::make_unique<DistantMeshFile>(
                                mesh, false, level == 0 ? stem : stem + "_" + std::to_string(level)));
                        if (f.mKind == Kind::Card)
                        {
                            add(out, VFS::Path::Normalized(texturePath),
                                std::make_unique<DistantMeshFile>(mesh, true, flat));
                            ++cards;
                        }
                        else if (level == 0)
                            ++simplified;
                        else
                            ++levels;
                    }
                }
                if (cards + simplified > 0)
                    Log(Debug::Info) << "Distant meshes: " << cards << " drawn as cards, " << simplified
                                     << " simplified past the active grid (" << levels << " farther levels), " << skipped
                                     << " left as they are (cache " << (cacheDirectory() / "impostors").string()
                                     << ")";
            }

            bool contains(VFS::Path::NormalizedView file) const override { return mFiles.find(file) != mFiles.end(); }

            std::string getDescription() const override { return "Distant meshes"; }

        private:
            void add(VFS::FileMap& out, VFS::Path::Normalized path, std::unique_ptr<DistantMeshFile> file)
            {
                out[path] = file.get();
                mFiles[std::move(path)] = std::move(file);
            }

            const VFS::Manager& mVfs;
            Settings mSettings; // the meshes keep a reference: the archive outlives them
            std::map<VFS::Path::Normalized, std::unique_ptr<DistantMeshFile>, std::less<>> mFiles;
        };
    }

    bool imageHooksBypassed()
    {
        return sBypassImageHooks;
    }

    void addDistantMeshes(VFS::Manager& vfs)
    {
        vfs.addArchive(std::make_unique<DistantMeshArchive>(vfs));
    }
}
