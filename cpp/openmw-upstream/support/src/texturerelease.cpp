// Texture release ([Textures] release uploaded images, on in alpha3.cfg). The engine keeps the pixels of every texture file
// it read (the image manager's osg::Image, which every texture of that file shares) after they are on the GPU, and on a
// phone the GPU's memory is the same RAM, so each texture is held twice. With this on, once a texture file's image has
// gone to the GPU its pixels are let go after the frame: the image keeps its size, format and change count, so the
// textures already made from it go on as they are, and the image manager's cache and OpenMW's state sharing still find
// the same image object.
//
// A texture that needs the pixels again (another texture object made from the same image, one whose GL object was
// released) asks for them when it uploads: the OSG hook comes before OSG's test for data (frame-and-texture-hooks.patch),
// and the file is read back then, as the image manager reads it, at full size. Texture streaming swaps in new pixels
// itself (its full size image); those are uploaded and let go the same way.
//
// Only files under textures/ read as DDS (or KTX in their place) are let go; images made in memory (blend maps, the maps'
// overlays, fog of war) and others keep their pixels.
#include "support.hpp"

#include <algorithm>
#include <atomic>
#include <cstring>
#include <mutex>
#include <string>
#include <vector>

#include <osg/Image>
#include <osg/ValueObject>
#include <osgDB/Options>
#include <osgDB/ReaderWriter>
#include <osgDB/Registry>

#include <alpha3/upstream.hpp>
#include <components/debug/debuglog.hpp>
#include <components/files/istreamptr.hpp>
#include <components/resource/imagemanager.hpp>
#include <components/vfs/manager.hpp>
#include <components/vfs/pathutil.hpp>

namespace Alpha3
{
    namespace
    {
        constexpr const char* sReleasedKey = "alpha3 released";

        bool enabled()
        {
            static const bool on = [] {
                const bool value = settingBool("Textures", "release uploaded images", false);
                if (value)
                    Log(Debug::Info) << "Texture release on: texture files' pixels are let go once on the GPU";
                return value;
            }();
            return on;
        }

        bool releasable(const osg::Image& image)
        {
            if (image.data() == nullptr || image.r() != 1 || image.getDataVariance() == osg::Object::DYNAMIC)
                return false;
            const std::string& name = image.getFileName();
            return name.size() > 13 && name.compare(0, 9, "textures/") == 0
                && name.compare(name.size() - 4, 4, ".dds") == 0;
        }

        bool released(const osg::Image& image)
        {
            bool value = false;
            return image.data() == nullptr && image.getUserValue(sReleasedKey, value) && value;
        }

        std::mutex sMutex;
        std::vector<osg::ref_ptr<osg::Image>> sUploaded; // this frame, to let go after it
        std::atomic<unsigned long long> sReleasedBytes{ 0 }, sReleasedCount{ 0 }, sReadBack{ 0 };

        // Gives [image] the pixels of [from], keeping its change count (the textures made from it stay as they are)
        void takePixels(osg::Image& image, const osg::Image& from)
        {
            const unsigned count = image.getModifiedCount();
            const std::size_t size = from.getTotalSizeInBytesIncludingMipmaps();
            unsigned char* data = new unsigned char[size];
            std::memcpy(data, from.data(), size);
            image.setImage(from.s(), from.t(), from.r(), from.getInternalTextureFormat(), from.getPixelFormat(),
                from.getDataType(), data, osg::Image::USE_NEW_DELETE, from.getPacking());
            image.setMipmapLevels(from.getMipmapLevels());
            image.setOrigin(from.getOrigin());
            image.setModifiedCount(count);
            image.setUserValue(sReleasedKey, false);
        }

        void letGo(osg::Image& image)
        {
            const unsigned count = image.getModifiedCount();
            const osg::Image::MipmapDataType levels = image.getMipmapLevels();
            const std::size_t bytes = image.getTotalSizeInBytesIncludingMipmaps();
            image.setImage(image.s(), image.t(), image.r(), image.getInternalTextureFormat(), image.getPixelFormat(),
                image.getDataType(), nullptr, osg::Image::NO_DELETE, image.getPacking());
            image.setMipmapLevels(levels);
            image.setModifiedCount(count);
            image.setUserValue(sReleasedKey, true);
            sReleasedBytes += bytes;
            ++sReleasedCount;
        }
    }

    bool textureReleaseOn()
    {
        return enabled();
    }

    osg::ref_ptr<osg::Image> readTextureFile(const std::string& path)
    {
        Resource::ImageManager* manager = engineImageManager();
        osgDB::ReaderWriter* reader = osgDB::Registry::instance()->getReaderWriterForExtension("dds");
        if (manager == nullptr || reader == nullptr)
            return nullptr;
        // what the image manager reads with (components/resource/imagemanager.cpp)
        static const osg::ref_ptr<osgDB::Options> options = new osgDB::Options("dds_dxt1_detect_rgba ignoreTga2Fields");
        try
        {
            Files::IStreamPtr stream = manager->getVFS()->get(VFS::Path::Normalized(path));
            readingImage(path);
            textureStreamingBypass(true);
            osgDB::ReaderWriter::ReadResult result = reader->readImage(*stream, options);
            textureStreamingBypass(false);
            readingImage({});
            if (!result.success())
                return nullptr;
            osg::ref_ptr<osg::Image> image = result.getImage();
            image->setFileName(path);
            image->setOrigin(osg::Image::TOP_LEFT);
            return image;
        }
        catch (const std::exception& e)
        {
            textureStreamingBypass(false);
            readingImage({});
            Log(Debug::Error) << "Texture release: cannot read " << path << " again: " << e.what();
            return nullptr;
        }
    }

    void textureReleaseLoading(const osg::Image& constImage)
    {
        if (!enabled())
            return;
        osg::Image& image = const_cast<osg::Image&>(constImage);
        if (released(image))
        {
            // a texture that needs the pixels again
            if (osg::ref_ptr<osg::Image> fresh = readTextureFile(image.getFileName()))
            {
                takePixels(image, *fresh);
                ++sReadBack;
            }
            else
                return;
        }
        if (!releasable(image))
            return;
        std::lock_guard lock(sMutex);
        sUploaded.emplace_back(&image);
    }

    void textureReleaseFrame()
    {
        if (!enabled())
            return;
        std::vector<osg::ref_ptr<osg::Image>> uploaded;
        {
            std::lock_guard lock(sMutex);
            uploaded.swap(sUploaded);
        }
        if (uploaded.empty())
            return;
        std::sort(uploaded.begin(), uploaded.end());
        uploaded.erase(std::unique(uploaded.begin(), uploaded.end()), uploaded.end());
        const unsigned long long before = sReleasedCount.load();
        for (const osg::ref_ptr<osg::Image>& image : uploaded)
            if (releasable(*image))
                letGo(*image);
        // now and then, how much was let go
        if (sReleasedCount.load() / 250 != before / 250)
            Log(Debug::Verbose) << "Texture release: " << sReleasedCount.load() << " images let go ("
                                << sReleasedBytes.load() / (1024 * 1024) << " MB), " << sReadBack.load()
                                << " read back";
    }
}
