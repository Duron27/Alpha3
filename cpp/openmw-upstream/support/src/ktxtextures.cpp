// .ktx textures (ASTC and others, made ahead of time by kram) in place of the .dds files of the same name. The engine
// asks for textures/foo.dds; when a textures/foo.ktx exists too, the .dds name opens the .ktx instead (the last archive
// the virtual file system lists, so it wins over the .dds of any data directory or BSA), and the DDS reader of the
// image manager recognises KTX data and hands it to OSG's KTX reader. The engine only has to ask for the archive.
#include <alpha3/upstream.hpp>

#include "support.hpp"

#include <cstring>
#include <filesystem>
#include <map>
#include <memory>
#include <mutex>
#include <string>
#include <string_view>
#include <vector>

#include <osg/Image>
#include <osg/Texture>
#include <osgDB/ReaderWriter>
#include <osgDB/Registry>

#include <components/debug/debuglog.hpp>
#include <components/vfs/archive.hpp>
#include <components/vfs/file.hpp>
#include <components/vfs/manager.hpp>
#include <components/vfs/pathutil.hpp>

USE_OSGPLUGIN(ktx)

namespace Alpha3
{
    namespace
    {
        constexpr std::string_view sKtxExtension = ".ktx";
        constexpr std::string_view sDdsExtension = ".dds";

        // KTX 1: "\xABKTX 11\xBB\r\n\x1A\n"
        constexpr unsigned char sKtxIdentifier[12] = { 0xAB, 'K', 'T', 'X', ' ', '1', '1', 0xBB, '\r', '\n', 0x1A, '\n' };

        bool isS3tc(const osg::Image& image)
        {
            switch (image.getPixelFormat())
            {
                case GL_COMPRESSED_RGB_S3TC_DXT1_EXT:
                case GL_COMPRESSED_RGBA_S3TC_DXT1_EXT:
                case GL_COMPRESSED_RGBA_S3TC_DXT3_EXT:
                case GL_COMPRESSED_RGBA_S3TC_DXT5_EXT:
                    return true;
                default:
                    return false;
            }
        }

        // The image manager asks the reader of the path's extension, which is "dds" for what it is given as foo.dds
        class DdsOrKtxReader final : public osgDB::ReaderWriter
        {
        public:
            DdsOrKtxReader(osgDB::ReaderWriter* dds, osgDB::ReaderWriter* ktx)
                : mDds(dds)
                , mKtx(ktx)
            {
                supportsExtension("dds", "DDS image format, or KTX in its place");
            }

            const char* className() const override { return "DDS or KTX image reader"; }

            ReadResult readImage(std::istream& stream, const osgDB::Options* options) const override
            {
                const std::istream::pos_type start = stream.tellg();
                unsigned char head[sizeof sKtxIdentifier] = {};
                stream.read(reinterpret_cast<char*>(head), sizeof head);
                const bool ktx = static_cast<std::size_t>(stream.gcount()) == sizeof head
                    && std::memcmp(head, sKtxIdentifier, sizeof head) == 0;
                stream.clear();
                stream.seekg(start);
                if (!ktx)
                    return mDds->readImage(stream, options);

                ReadResult result = mKtx->readImage(stream, options);
                osg::Image* image = result.success() ? result.getImage() : nullptr;
                // The engine takes what it read as a .dds to be stored top down and does not look at the origin. A
                // KTX file stored bottom up is turned over here, which only works for what OSG can flip.
                if (image != nullptr && image->getOrigin() == osg::Image::BOTTOM_LEFT)
                {
                    if (image->isCompressed() && !isS3tc(*image))
                        return ReadResult("cannot flip a compressed KTX texture stored bottom up");
                    image->flipVertical();
                    image->setOrigin(osg::Image::TOP_LEFT);
                }
                return result;
            }

            ReadResult readImage(const std::string& file, const osgDB::Options* options) const override
            {
                return mDds->readImage(file, options);
            }

            ReadResult readObject(std::istream& stream, const osgDB::Options* options) const override
            {
                return readImage(stream, options);
            }

            ReadResult readObject(const std::string& file, const osgDB::Options* options) const override
            {
                return mDds->readObject(file, options);
            }

        private:
            osg::ref_ptr<osgDB::ReaderWriter> mDds;
            osg::ref_ptr<osgDB::ReaderWriter> mKtx;
        };

        void installReader()
        {
            static std::once_flag installed;
            std::call_once(installed, [] {
                osgDB::Registry* registry = osgDB::Registry::instance();
                osg::ref_ptr<osgDB::ReaderWriter> dds = registry->getReaderWriterForExtension("dds");
                osg::ref_ptr<osgDB::ReaderWriter> ktx = registry->getReaderWriterForExtension("ktx");
                if (!dds || !ktx)
                {
                    Log(Debug::Warning) << "KTX textures: no " << (dds ? "KTX" : "DDS") << " reader, the .ktx files are not used";
                    return;
                }
                registry->removeReaderWriter(dds.get());
                registry->addReaderWriter(new DdsOrKtxReader(dds.get(), ktx.get()));
            });
        }

        // The .dds name of a .ktx file, opening that file
        class AliasFile final : public VFS::File
        {
        public:
            explicit AliasFile(VFS::File* target)
                : mTarget(target)
            {
            }

            Files::IStreamPtr open() override { return mTarget->open(); }
            std::filesystem::file_time_type getLastModified() const override { return mTarget->getLastModified(); }
            std::string getStem() const override { return mTarget->getStem(); }

        private:
            VFS::File* mTarget; // belongs to the archive listed before this one
        };

        class KtxArchive final : public VFS::Archive
        {
        public:
            void listResources(VFS::FileMap& out) override
            {
                mAliases.clear();
                std::vector<std::pair<VFS::Path::Normalized, VFS::File*>> found;
                for (const auto& [path, file] : out)
                {
                    const std::string& name = path.value();
                    if (name.size() > sKtxExtension.size()
                        && std::string_view(name).substr(name.size() - sKtxExtension.size()) == sKtxExtension)
                        found.emplace_back(
                            VFS::Path::Normalized(name.substr(0, name.size() - sKtxExtension.size()) + std::string(sDdsExtension)),
                            file);
                }
                for (auto& [alias, target] : found)
                {
                    std::unique_ptr<AliasFile>& file = mAliases[alias];
                    file = std::make_unique<AliasFile>(target);
                    out[alias] = file.get();
                }
                if (mAliases.empty())
                    return;
                Log(Debug::Info) << "KTX textures: " << mAliases.size() << " .ktx files are used in place of the .dds of the same name";
                installReader();
            }

            bool contains(VFS::Path::NormalizedView file) const override { return mAliases.find(file) != mAliases.end(); }

            std::string getDescription() const override { return "KTX textures"; }

        private:
            std::map<VFS::Path::Normalized, std::unique_ptr<AliasFile>, std::less<>> mAliases;
        };
    }

    void addKtxTextures(VFS::Manager& vfs)
    {
        noteMainThread(); // the engine sets up its VFS on its main thread
        vfs.addArchive(std::make_unique<KtxArchive>());
        addDistantMeshes(vfs);
    }
}
