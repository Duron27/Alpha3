// Impostors: distant trees as flat cards instead of their meshes. Object paging asks the virtual file system for a
// distant version of a mesh (meshes/x_dist.nif for Morrowind content) when it builds a chunk outside the active grid,
// and only there; the active cells keep the real meshes. This archive lists such a file for every mesh whose name
// matches [Terrain] impostor meshes and that has none of its own. Opening it the first time makes the card: the mesh's
// NIF is read, drawn on the CPU from six directions around it into one texture (textures/alpha3_impostors/...), and
// written as a NIF of three crossed quads, two sided, each side showing the view from that side. Paging merges the cards
// like any other mesh, so a forest far away is a few draw calls of a few triangles per tree. Both files go to a cache in
// the cache directory, keyed by the mesh's contents, so a mesh is only drawn once.
//
// A mesh that cannot be drawn (skinned, no readable texture, not a Morrowind NIF) gets its own NIF back under the
// distant name, which is what paging would have used without the archive.
#include "bcdecode.hpp"
#include "blobstore.hpp"
#include "support.hpp"

#include <alpha3/upstream.hpp>

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
        constexpr std::uint32_t sCacheVersion = 1;
        constexpr std::string_view sDistantSuffix = "_dist";
        constexpr std::string_view sTextureDirectory = "textures/alpha3_impostors/";

        struct Settings
        {
            bool mEnabled = false;
            std::vector<std::string> mPatterns;
            int mSize = 64; // texels per view, each side
        };

        Settings readSettings()
        {
            Settings s;
            s.mEnabled = settingBool("Terrain", "impostors", false);
            s.mSize = std::clamp(settingInt("Terrain", "impostor size", 64), 16, 512);
            std::string patterns
                = setting("Terrain", "impostor meshes", "flora_tree,flora_bc_tree,flora_ashtree,flora_emp_parasol");
            Misc::StringUtils::lowerCaseInPlace(patterns);
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
                    s.mPatterns.push_back(std::move(pattern));
                start = end + 1;
            }
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

        // ---- reading the mesh

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

        struct Triangle
        {
            osg::Vec3f mPosition[3];
            osg::Vec2f mUv[3];
            osg::Vec4f mColor[3];
            int mTexture;
        };

        struct Mesh
        {
            std::vector<Triangle> mTriangles;
            std::vector<Texture> mTextures;
            osg::BoundingBoxf mBounds;
        };

        class MeshReader
        {
        public:
            MeshReader(const VFS::Manager& vfs, Mesh& mesh)
                : mVfs(vfs)
                , mMesh(mesh)
            {
            }

            // False when the mesh cannot be drawn as a card
            bool read(const Nif::NiAVObject* object, const osg::Matrixf& parent, int texture)
            {
                if (object == nullptr || object->isHidden() || object->mRecordType == Nif::RC_RootCollisionNode)
                    return true;
                if (Misc::StringUtils::ciEqual(object->mName, "collision"))
                    return true;
                const osg::Matrixf matrix = object->mTransform.toMatrix() * parent;

                for (const auto& property : object->mProperties)
                {
                    if (property.empty() || property->mRecordType != Nif::RC_NiTexturingProperty)
                        continue;
                    const auto* texturing = static_cast<const Nif::NiTexturingProperty*>(property.getPtr());
                    if (texturing->mTextures.empty())
                        continue;
                    const auto& base = texturing->mTextures[Nif::NiTexturingProperty::BaseTexture];
                    if (!base.mEnabled || base.mSourceTexture.empty())
                        continue;
                    texture = loadTexture(*base.mSourceTexture.getPtr());
                    if (texture < 0)
                    {
                        mError = "texture " + base.mSourceTexture->mFile + " cannot be read";
                        return false;
                    }
                }

                if (object->mRecordType == Nif::RC_NiTriShape || object->mRecordType == Nif::RC_NiTriStrips)
                    return readGeometry(static_cast<const Nif::NiGeometry&>(*object), matrix, texture);

                if (const auto* node = dynamic_cast<const Nif::NiNode*>(object))
                {
                    // a switch or LOD node shows one child: the first (the most detailed level)
                    const bool oneChild
                        = object->mRecordType == Nif::RC_NiSwitchNode || object->mRecordType == Nif::RC_NiLODNode;
                    for (const auto& child : node->mChildren)
                    {
                        if (child.empty())
                            continue;
                        if (!read(child.getPtr(), matrix, texture))
                            return false;
                        if (oneChild)
                            break;
                    }
                }
                return true;
            }

            const std::string& error() const { return mError; }

        private:
            int loadTexture(const Nif::NiSourceTexture& source)
            {
                if (!source.mExternal)
                    return -1;
                const VFS::Path::Normalized path
                    = Misc::ResourceHelpers::correctTexturePath(VFS::Path::toNormalized(source.mFile), mVfs);
                const auto found = mLoaded.find(path.value());
                if (found != mLoaded.end())
                    return found->second;

                int index = -1;
                try
                {
                    std::string extension(Misc::getFileExtension(path.value()));
                    if (extension == "targa")
                        extension = "tga";
                    osgDB::ReaderWriter* reader = osgDB::Registry::instance()->getReaderWriterForExtension(extension);
                    if (reader != nullptr)
                    {
                        static const osg::ref_ptr<osgDB::Options> options
                            = new osgDB::Options("dds_dxt1_detect_rgba ignoreTga2Fields");
                        Files::IStreamPtr stream = mVfs.get(path);
                        BypassImageHooks bypass;
                        osgDB::ReaderWriter::ReadResult result = reader->readImage(*stream, options);
                        Texture texture;
                        if (result.success() && toRgba8(*result.getImage(), extension == "dds", texture))
                        {
                            index = static_cast<int>(mMesh.mTextures.size());
                            mMesh.mTextures.push_back(std::move(texture));
                        }
                    }
                }
                catch (const std::exception&)
                {
                }
                mLoaded.emplace(path.value(), index);
                return index;
            }

            bool readGeometry(const Nif::NiGeometry& geometry, const osg::Matrixf& matrix, int texture)
            {
                if (!geometry.mSkin.empty())
                {
                    mError = "skinned";
                    return false;
                }
                if (geometry.mData.empty())
                    return true;
                const Nif::NiGeometryData& data = *geometry.mData.getPtr();

                std::vector<unsigned short> indices;
                if (data.mRecordType == Nif::RC_NiTriShapeData)
                    indices = static_cast<const Nif::NiTriShapeData&>(data).mTriangles;
                else if (data.mRecordType == Nif::RC_NiTriStripsData)
                {
                    for (const auto& strip : static_cast<const Nif::NiTriStripsData&>(data).mStrips)
                        for (std::size_t i = 2; i < strip.size(); ++i)
                        {
                            if (strip[i - 2] == strip[i - 1] || strip[i - 1] == strip[i] || strip[i - 2] == strip[i])
                                continue;
                            indices.insert(indices.end(), { strip[i - 2], strip[i - 1], strip[i] });
                        }
                }
                else
                    return true;

                const std::vector<osg::Vec2f>* uvs = data.mUVList.empty() ? nullptr : &data.mUVList[0];
                const std::size_t count = data.mVertices.size();
                for (std::size_t i = 0; i + 2 < indices.size(); i += 3)
                {
                    Triangle triangle;
                    triangle.mTexture = texture;
                    bool valid = true;
                    for (int k = 0; k < 3; ++k)
                    {
                        const std::size_t v = indices[i + k];
                        if (v >= count)
                        {
                            valid = false;
                            break;
                        }
                        triangle.mPosition[k] = data.mVertices[v] * matrix;
                        triangle.mUv[k] = uvs != nullptr && v < uvs->size() ? (*uvs)[v] : osg::Vec2f();
                        triangle.mColor[k] = v < data.mColors.size() ? data.mColors[v] : osg::Vec4f(1, 1, 1, 1);
                        mMesh.mBounds.expandBy(triangle.mPosition[k]);
                    }
                    if (valid)
                        mMesh.mTriangles.push_back(triangle);
                }
                return true;
            }

            const VFS::Manager& mVfs;
            Mesh& mMesh;
            std::map<std::string, int> mLoaded;
            std::string mError;
        };

        // ---- drawing the views

        struct View
        {
            osg::Vec3f mToCamera, mRight;
            float mHalfWidth;
        };

        struct Card
        {
            int mSize; // texels per view
            int mAtlasWidth, mAtlasHeight; // 3 x 2 views
            std::vector<unsigned char> mRgba;
            osg::Vec3f mCenter; // on the ground plane through the middle of the bounds
            float mBottom, mTop;
            std::array<View, sViews> mViews;
        };

        void drawView(const Mesh& mesh, const Card& card, const View& view, std::vector<float>& rgba)
        {
            const int inner = (card.mSize - 2 * sGutter) * sSupersample;
            const int size = card.mSize * sSupersample;
            const int gutter = sGutter * sSupersample;
            std::vector<float> depth(static_cast<std::size_t>(size) * size, -std::numeric_limits<float>::max());
            rgba.assign(static_cast<std::size_t>(size) * size * 4, 0.f);

            const float height = card.mTop - card.mBottom;
            auto project = [&](const osg::Vec3f& p) {
                const osg::Vec3f d = p - card.mCenter;
                return osg::Vec3f(gutter + (d * view.mRight + view.mHalfWidth) / (2 * view.mHalfWidth) * inner,
                    gutter + (card.mTop - p.z()) / height * inner, d * view.mToCamera);
            };

            for (const Triangle& triangle : mesh.mTriangles)
            {
                const osg::Vec3f a = project(triangle.mPosition[0]), b = project(triangle.mPosition[1]),
                                 c = project(triangle.mPosition[2]);
                const float area = (b.x() - a.x()) * (c.y() - a.y()) - (b.y() - a.y()) * (c.x() - a.x());
                if (std::abs(area) < 1e-8f)
                    continue;
                const int x0 = std::max(0, static_cast<int>(std::floor(std::min({ a.x(), b.x(), c.x() }))));
                const int x1 = std::min(size - 1, static_cast<int>(std::ceil(std::max({ a.x(), b.x(), c.x() }))));
                const int y0 = std::max(0, static_cast<int>(std::floor(std::min({ a.y(), b.y(), c.y() }))));
                const int y1 = std::min(size - 1, static_cast<int>(std::ceil(std::max({ a.y(), b.y(), c.y() }))));
                const Texture* texture = triangle.mTexture >= 0 ? &mesh.mTextures[triangle.mTexture] : nullptr;
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
                        osg::Vec4f color = triangle.mColor[0] * w0 + triangle.mColor[1] * w1 + triangle.mColor[2] * w2;
                        if (texture != nullptr)
                        {
                            const osg::Vec4f texel = texture->sample(
                                triangle.mUv[0] * w0 + triangle.mUv[1] * w1 + triangle.mUv[2] * w2);
                            color = osg::componentMultiply(color, texel);
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

        bool drawCard(const Mesh& mesh, int viewSize, Card& card)
        {
            if (mesh.mTriangles.empty() || !mesh.mBounds.valid())
                return false;
            card.mSize = viewSize;
            card.mAtlasWidth = viewSize * 3;
            card.mAtlasHeight = viewSize * 2;
            card.mCenter = osg::Vec3f(mesh.mBounds.center().x(), mesh.mBounds.center().y(), 0.f);
            card.mBottom = mesh.mBounds.zMin();
            card.mTop = mesh.mBounds.zMax();
            if (card.mTop - card.mBottom < 1.f)
                return false;

            card.mRgba.assign(static_cast<std::size_t>(card.mAtlasWidth) * card.mAtlasHeight * 4, 0);
            std::vector<float> supersampled;
            for (int i = 0; i < sViews; ++i)
            {
                const float angle = static_cast<float>(i) * static_cast<float>(osg::PI) / 3.f;
                View& view = card.mViews[i];
                view.mToCamera = osg::Vec3f(std::cos(angle), std::sin(angle), 0.f);
                view.mRight = osg::Vec3f(-std::sin(angle), std::cos(angle), 0.f);
                view.mHalfWidth = 0.f;
                for (const Triangle& triangle : mesh.mTriangles)
                    for (const osg::Vec3f& p : triangle.mPosition)
                        view.mHalfWidth = std::max(view.mHalfWidth, std::abs((p - card.mCenter) * view.mRight));
                if (view.mHalfWidth < 0.5f)
                    return false;

                drawView(mesh, card, view, supersampled);
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
            return true;
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
            void putRefs(std::initializer_list<std::int32_t> refs)
            {
                put<std::uint32_t>(static_cast<std::uint32_t>(refs.size()));
                for (std::int32_t ref : refs)
                    put(ref);
            }

            std::string mData;
        };

        // NiObjectNET then NiAVObject, as Nif::NiAVObject::read reads them in 4.0.0.2
        void putObject(Writer& w, std::string_view name)
        {
            w.putString(name);
            w.put<std::int32_t>(-1); // extra data
            w.put<std::int32_t>(-1); // controller
        }

        void putAVObject(Writer& w, std::string_view name, std::initializer_list<std::int32_t> properties)
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

        // Three crossed quads, both sides, each side textured with the view from its side
        std::string writeNif(const Card& card, std::string_view texturePath)
        {
            std::vector<osg::Vec3f> vertices, normals;
            std::vector<osg::Vec2f> uvs;
            std::vector<std::uint16_t> triangles;
            for (int i = 0; i < sViews; ++i)
            {
                const View& view = card.mViews[i];
                const float u0 = static_cast<float>((i % 3) * card.mSize + sGutter) / card.mAtlasWidth;
                const float u1 = static_cast<float>((i % 3 + 1) * card.mSize - sGutter) / card.mAtlasWidth;
                const float v0 = static_cast<float>((i / 3) * card.mSize + sGutter) / card.mAtlasHeight;
                const float v1 = static_cast<float>((i / 3 + 1) * card.mSize - sGutter) / card.mAtlasHeight;
                const osg::Vec3f left = card.mCenter - view.mRight * view.mHalfWidth;
                const osg::Vec3f right = card.mCenter + view.mRight * view.mHalfWidth;
                const auto base = static_cast<std::uint16_t>(vertices.size());
                // counter-clockwise seen from the side the view was drawn from
                vertices.push_back(left + osg::Vec3f(0, 0, card.mBottom));
                vertices.push_back(right + osg::Vec3f(0, 0, card.mBottom));
                vertices.push_back(right + osg::Vec3f(0, 0, card.mTop));
                vertices.push_back(left + osg::Vec3f(0, 0, card.mTop));
                uvs.insert(uvs.end(), { { u0, v1 }, { u1, v1 }, { u1, v0 }, { u0, v0 } });
                // lit like foliage, from above, whatever side is seen
                normals.insert(normals.end(), 4, osg::Vec3f(0, 0, 1));
                triangles.insert(triangles.end(),
                    { base, static_cast<std::uint16_t>(base + 1), static_cast<std::uint16_t>(base + 2), base,
                        static_cast<std::uint16_t>(base + 2), static_cast<std::uint16_t>(base + 3) });
            }

            osg::BoundingSpheref sphere;
            for (const osg::Vec3f& v : vertices)
                sphere.expandBy(v);

            Writer w;
            w.mData = "NetImmerse File Format, Version 4.0.0.2\n";
            w.put<std::uint32_t>(0x04000002);
            w.put<std::uint32_t>(7); // records

            // 0: the root
            w.putString("NiNode");
            putAVObject(w, "Alpha3 impostor", {});
            w.putRefs({ 1 }); // children
            w.putRefs({}); // effects

            // 1: the quads
            w.putString("NiTriShape");
            putAVObject(w, "Impostor", { 3, 5, 6 });
            w.put<std::int32_t>(2); // data
            w.put<std::int32_t>(-1); // skin

            // 2
            w.putString("NiTriShapeData");
            w.put<std::uint16_t>(static_cast<std::uint16_t>(vertices.size()));
            w.putBool(true);
            for (const osg::Vec3f& v : vertices)
                w.putVec(v);
            w.putBool(true);
            for (const osg::Vec3f& n : normals)
                w.putVec(n);
            w.putVec(sphere.center());
            w.put<float>(sphere.radius());
            w.putBool(false); // vertex colours
            w.put<std::uint16_t>(1); // UV sets
            w.putBool(true); // has UVs
            for (const osg::Vec2f& uv : uvs)
            {
                w.put(uv.x());
                w.put(uv.y());
            }
            w.put<std::uint16_t>(static_cast<std::uint16_t>(triangles.size() / 3));
            w.put<std::uint32_t>(static_cast<std::uint32_t>(triangles.size()));
            for (std::uint16_t index : triangles)
                w.put(index);
            w.put<std::uint16_t>(0); // match groups

            // 3
            w.putString("NiTexturingProperty");
            putObject(w, "");
            w.put<std::uint16_t>(0); // flags
            w.put<std::uint32_t>(2); // apply mode: modulate
            w.put<std::uint32_t>(7); // texture slots
            for (int slot = 0; slot < 7; ++slot)
            {
                w.putBool(slot == 0);
                if (slot != 0)
                    continue;
                w.put<std::int32_t>(4); // source
                w.put<std::uint32_t>(0); // clamp both: the atlas must not wrap into the next view
                w.put<std::uint32_t>(2); // trilinear
                w.put<std::uint32_t>(0); // UV set
                w.put<std::uint32_t>(0); // PS2 L, K
                w.put<std::uint16_t>(0); // unknown
            }

            // 4
            w.putString("NiSourceTexture");
            putObject(w, "");
            w.put<std::uint8_t>(1); // external
            w.putString(texturePath);
            w.put<std::uint32_t>(5); // pixel layout: default
            w.put<std::uint32_t>(1); // mip maps: yes
            w.put<std::uint32_t>(3); // alpha: default
            w.put<std::uint8_t>(1); // static

            // 5: alpha test, greater or equal 128
            w.putString("NiAlphaProperty");
            putObject(w, "");
            w.put<std::uint16_t>(0x0200 | (6 << 10));
            w.put<std::uint8_t>(128);

            // 6
            w.putString("NiMaterialProperty");
            putObject(w, "");
            w.put<std::uint16_t>(0);
            w.putVec(osg::Vec3f(1, 1, 1)); // ambient
            w.putVec(osg::Vec3f(1, 1, 1)); // diffuse
            w.putVec(osg::Vec3f()); // specular
            w.putVec(osg::Vec3f()); // emissive
            w.put<float>(0.f); // glossiness
            w.put<float>(1.f); // alpha

            w.putRefs({ 0 }); // roots
            return std::move(w.mData);
        }

        // Uncompressed RGBA DDS with its mip levels (alpha weighted box filter)
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
            w.put<std::uint32_t>(0x1 | 0x2 | 0x4 | 0x8 | 0x1000 | 0x20000); // caps, height, width, pitch, format, mips
            w.put<std::uint32_t>(static_cast<std::uint32_t>(height));
            w.put<std::uint32_t>(static_cast<std::uint32_t>(width));
            w.put<std::uint32_t>(static_cast<std::uint32_t>(width * 4));
            w.put<std::uint32_t>(0); // depth
            w.put<std::uint32_t>(static_cast<std::uint32_t>(levels.size()));
            for (int i = 0; i < 11; ++i)
                w.put<std::uint32_t>(0);
            w.put<std::uint32_t>(32);
            w.put<std::uint32_t>(0x40 | 0x1); // RGB, alpha
            w.put<std::uint32_t>(0); // no four CC
            w.put<std::uint32_t>(32);
            w.put<std::uint32_t>(0x000000ff);
            w.put<std::uint32_t>(0x0000ff00);
            w.put<std::uint32_t>(0x00ff0000);
            w.put<std::uint32_t>(0xff000000);
            w.put<std::uint32_t>(0x1000 | 0x8 | 0x400000); // texture, complex, mip map
            for (int i = 0; i < 4; ++i)
                w.put<std::uint32_t>(0);
            for (const auto& level : levels)
                w.mData.append(reinterpret_cast<const char*>(level.data()), level.size());
            return std::move(w.mData);
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

            bool load(const BlobKey& key, std::string& nif, std::string& dds)
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

        class Impostor
        {
        public:
            Impostor(const VFS::Manager& vfs, VFS::File* source, VFS::Path::Normalized sourcePath,
                std::string texturePath, int viewSize)
                : mVfs(vfs)
                , mSource(source)
                , mSourcePath(std::move(sourcePath))
                , mTexturePath(std::move(texturePath))
                , mViewSize(viewSize)
            {
            }

            const std::string& nif()
            {
                make();
                return mNif;
            }

            const std::string& dds()
            {
                make();
                return mDds;
            }

            VFS::File* source() const { return mSource; }

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
                        Log(Debug::Warning) << "Impostor of " << mSourcePath << ": " << e.what();
                    }
                    const BlobKey key{ fnv1a(sourceBytes, 0xcbf29ce484222325ull),
                        fnv1a(sourceBytes, 0x84222325cbf29ce4ull) ^ (std::uint64_t(mViewSize) << 32) };
                    // an entry with no NIF: the mesh was tried before and could not be drawn
                    const bool cached = Cache::get().load(key, mNif, mDds);
                    if (cached && !mNif.empty())
                        return;
                    if (!cached)
                    {
                        if (build(sourceBytes))
                        {
                            Cache::get().store(key, mNif, mDds);
                            ++sMade;
                            return;
                        }
                        Cache::get().store(key, std::string(), std::string());
                        ++sFailed;
                    }
                    // the mesh itself, as if there were no archive, and a texture nothing reads
                    mNif = std::move(sourceBytes);
                    const std::vector<unsigned char> clear(4, 0);
                    mDds = writeDds(clear, 1, 1);
                });
            }

            bool build(const std::string& sourceBytes)
            {
                try
                {
                    Nif::NIFFile file(mSourcePath);
                    Nif::Reader reader(file, nullptr);
                    reader.parse(std::make_unique<std::istringstream>(sourceBytes));
                    const Nif::FileView view(file);
                    if (view.getVersion() != Nif::NIFFile::VER_MW)
                    {
                        Log(Debug::Verbose) << "Impostor of " << mSourcePath << " not made: not a Morrowind NIF";
                        return false;
                    }

                    Mesh mesh;
                    MeshReader meshReader(mVfs, mesh);
                    for (std::size_t i = 0; i < view.numRoots(); ++i)
                    {
                        const auto* root = dynamic_cast<const Nif::NiAVObject*>(view.getRoot(i));
                        // the engine drops the root node's own transform (Nif::NiNode::read)
                        if (root != nullptr && !meshReader.read(root, osg::Matrixf(), -1))
                        {
                            Log(Debug::Verbose) << "Impostor of " << mSourcePath << " not made: " << meshReader.error();
                            return false;
                        }
                    }

                    Card card;
                    if (!drawCard(mesh, mViewSize, card))
                    {
                        Log(Debug::Verbose) << "Impostor of " << mSourcePath << " not made: nothing to draw";
                        return false;
                    }
                    // the NIF names textures relative to textures/
                    mNif = writeNif(card, std::string_view(mTexturePath).substr(std::string_view("textures/").size()));
                    mDds = writeDds(card.mRgba, card.mAtlasWidth, card.mAtlasHeight);
                    return true;
                }
                catch (const std::exception& e)
                {
                    Log(Debug::Warning) << "Impostor of " << mSourcePath << " not made: " << e.what();
                    return false;
                }
            }

        public:
            static std::atomic<unsigned> sMade, sFailed;

        private:
            const VFS::Manager& mVfs;
            VFS::File* mSource; // belongs to an archive listed before this one
            VFS::Path::Normalized mSourcePath;
            std::string mTexturePath;
            int mViewSize;
            std::once_flag mMade;
            std::string mNif, mDds;
        };

        std::atomic<unsigned> Impostor::sMade{ 0 }, Impostor::sFailed{ 0 };

        class ImpostorFile final : public VFS::File
        {
        public:
            ImpostorFile(std::shared_ptr<Impostor> impostor, bool texture, std::string stem)
                : mImpostor(std::move(impostor))
                , mTexture(texture)
                , mStem(std::move(stem))
            {
            }

            Files::IStreamPtr open() override
            {
                return std::make_unique<std::istringstream>(mTexture ? mImpostor->dds() : mImpostor->nif());
            }

            std::filesystem::file_time_type getLastModified() const override
            {
                return mImpostor->source()->getLastModified();
            }

            std::string getStem() const override { return mStem; }

        private:
            std::shared_ptr<Impostor> mImpostor;
            bool mTexture;
            std::string mStem;
        };

        class ImpostorArchive final : public VFS::Archive
        {
        public:
            explicit ImpostorArchive(const VFS::Manager& vfs)
                : mVfs(vfs)
            {
            }

            void listResources(VFS::FileMap& out) override
            {
                mFiles.clear();
                const Settings settings = readSettings();
                if (!settings.mEnabled || settings.mPatterns.empty())
                    return;

                constexpr std::string_view meshes = "meshes/", nif = ".nif";
                std::vector<std::pair<VFS::Path::Normalized, VFS::File*>> found;
                for (const auto& [path, file] : out)
                {
                    const std::string_view name = path.value();
                    if (!name.starts_with(meshes) || !name.ends_with(nif))
                        continue;
                    const std::string_view stem = name.substr(name.rfind('/') + 1);
                    // already a distant version, or an actor's animation and skeleton files
                    if (stem.find(sDistantSuffix) != std::string_view::npos || stem.starts_with("x"))
                        continue;
                    if (std::none_of(settings.mPatterns.begin(), settings.mPatterns.end(),
                            [&](const std::string& p) { return stem.find(p) != std::string_view::npos; }))
                        continue;
                    found.emplace_back(path, file);
                }

                for (auto& [path, file] : found)
                {
                    const std::string_view name = path.value();
                    const std::string_view base = name.substr(0, name.size() - nif.size());
                    VFS::Path::Normalized distant(std::string(base) + std::string(sDistantSuffix) + std::string(nif));
                    // a mesh with distant versions of its own keeps them (paging also looks for _dist_0 ...)
                    if (out.contains(distant)
                        || out.contains(VFS::Path::Normalized(std::string(base) + std::string(sDistantSuffix) + "_0.nif")))
                        continue;

                    std::string flat(base.substr(meshes.size()));
                    std::replace(flat.begin(), flat.end(), '/', '_');
                    std::string texturePath = std::string(sTextureDirectory) + flat + ".dds";
                    auto impostor = std::make_shared<Impostor>(mVfs, file, path, texturePath, settings.mSize);

                    const std::string stem = std::string(base.substr(base.rfind('/') + 1)) + std::string(sDistantSuffix);
                    add(out, std::move(distant), std::make_unique<ImpostorFile>(impostor, false, stem));
                    add(out, VFS::Path::Normalized(texturePath), std::make_unique<ImpostorFile>(impostor, true, flat));
                }
                if (!mFiles.empty())
                    Log(Debug::Info) << "Impostors: " << mFiles.size() / 2 << " meshes are drawn as cards past the active "
                                     << "grid (" << settings.mSize << " texels per view, cache "
                                     << (cacheDirectory() / "impostors").string() << ")";
            }

            bool contains(VFS::Path::NormalizedView file) const override { return mFiles.find(file) != mFiles.end(); }

            std::string getDescription() const override { return "Impostors"; }

        private:
            void add(VFS::FileMap& out, VFS::Path::Normalized path, std::unique_ptr<ImpostorFile> file)
            {
                out[path] = file.get();
                mFiles[std::move(path)] = std::move(file);
            }

            const VFS::Manager& mVfs;
            std::map<VFS::Path::Normalized, std::unique_ptr<ImpostorFile>, std::less<>> mFiles;
        };
    }

    bool imageHooksBypassed()
    {
        return sBypassImageHooks;
    }

    void addImpostors(VFS::Manager& vfs)
    {
        vfs.addArchive(std::make_unique<ImpostorArchive>(vfs));
    }
}
