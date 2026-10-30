// Texture streaming ([Textures] streaming): a large texture is read as its smaller mip levels only, so a cell is ready
// sooner (on a GPU without S3TC only those levels are converted to ASTC or ETC2, the slow part of a first load) and its
// textures go to the GPU small. It is made full size once something drawn with it covers more pixels on the screen than
// the small texture has across: the scene camera's cull tells (the occlusion hooks' drawableAdded). Then the full image
// is prepared on a background thread, and after a later frame its data replaces the small image's: OSG sees the image
// change and remakes the texture at full size (osg::Texture2D::textureObjectValid). A few are swapped per frame, so the
// uploads are spread out instead of stalling one frame. A texture uploaded that the scene camera never draws (the sky,
// the GUI) is made full size a few seconds after its upload, and its name is remembered so it is not streamed again.
//
// Images shown in full on the screen must never start blurry, so these are read at full size:
// * whatever the engine's main thread reads: the GUI, splash screens, and what a cell needs right away;
// * images the texture preload reads (it names them) that are not textures of the world (outside textures/, or menu
//   pictures), or that an earlier session found the scene camera never draws.
//
// The image object is the one the engine's image manager keeps and every texture of that file shares, so nothing in the
// engine needs to know: the textures that use it change with it.
#include "support.hpp"

#include <algorithm>
#include <atomic>
#include <cmath>
#include <condition_variable>
#include <cstring>
#include <deque>
#include <fstream>
#include <unordered_set>
#include <limits>
#include <mutex>
#include <thread>
#include <unordered_map>
#include <vector>

#include <osg/Image>
#include <osg/Texture>
#include <osg/observer_ptr>
#include <osgUtil/CullVisitor>
#include <osgUtil/StateGraph>

#include <components/debug/debuglog.hpp>

namespace Alpha3
{
    namespace
    {
        struct StreamingSettings
        {
            bool mEnabled;
            int mStartSize; // the largest side of what is read first
            int mSwapsPerFrame;
        };

        const StreamingSettings& streamingSettings()
        {
            static const StreamingSettings settings{ settingBool("Textures", "streaming", false),
                std::clamp(settingInt("Textures", "streaming start size", 256), 16, 4096),
                std::clamp(settingInt("Textures", "streaming swaps per frame", 8), 1, 1000) };
            return settings;
        }

        std::atomic<std::thread::id> sMainThread{};
        thread_local const std::string* tlPath = nullptr;

        bool onMainThread()
        {
            return std::this_thread::get_id() == sMainThread.load(std::memory_order_relaxed);
        }

        // Images not to stream, by name: shown in full on the screen
        class FullQualityImages
        {
        public:
            bool contains(const std::string& name)
            {
                if (!name.starts_with("textures/"))
                    return true; // splash/, bookart/, icons/, ...
                for (std::string_view prefix : { "textures/menu", "textures/levelup", "textures/tx_menubook",
                         "textures/scroll", "textures/compass", "textures/target" })
                    if (name.starts_with(prefix))
                        return true;
                std::lock_guard lock(mMutex);
                load();
                return mNames.count(name) != 0;
            }

            void add(const std::string& name)
            {
                if (name.empty())
                    return;
                std::lock_guard lock(mMutex);
                load();
                if (!mNames.insert(name).second)
                    return;
                std::ofstream(file(), std::ios::app) << name << '\n';
            }

        private:
            static std::filesystem::path file() { return cacheDirectory() / "texture-streaming-full.list"; }

            void load()
            {
                if (mLoaded)
                    return;
                mLoaded = true;
                std::ifstream in(file());
                std::string line;
                while (std::getline(in, line))
                    if (!line.empty())
                        mNames.insert(line);
            }

            std::mutex mMutex;
            bool mLoaded = false;
            std::unordered_set<std::string> mNames;
        };

        FullQualityImages& fullQualityImages()
        {
            static FullQualityImages instance;
            return instance;
        }

        // The image's mip levels from the first one no larger than maxSize, as an image of their own (same format);
        // null when it has no such smaller level
        osg::ref_ptr<osg::Image> withoutTopLevels(const osg::Image& image, int maxSize)
        {
            const unsigned levels = image.getNumMipmapLevels();
            if (levels < 2 || image.r() != 1 || image.data() == nullptr)
                return nullptr;
            unsigned first = 0;
            while (first + 1 < levels && std::max(image.s() >> first, image.t() >> first) > maxSize)
                ++first;
            if (first == 0)
                return nullptr;

            const unsigned char* start = image.getMipmapData(first);
            const std::size_t total = image.getTotalSizeInBytesIncludingMipmaps();
            const std::ptrdiff_t offset = start - image.data();
            if (start == nullptr || offset <= 0 || static_cast<std::size_t>(offset) >= total)
                return nullptr;
            const std::size_t size = total - static_cast<std::size_t>(offset);
            unsigned char* data = new unsigned char[size];
            std::memcpy(data, start, size);

            osg::ref_ptr<osg::Image> result = new osg::Image;
            result->setImage(std::max(1, image.s() >> first), std::max(1, image.t() >> first), 1,
                image.getInternalTextureFormat(), image.getPixelFormat(), image.getDataType(), data,
                osg::Image::USE_NEW_DELETE, image.getPacking());
            osg::Image::MipmapDataType offsets;
            for (unsigned level = first + 1; level < levels; ++level)
                offsets.push_back(static_cast<unsigned>(image.getMipmapData(level) - start));
            result->setMipmapLevels(offsets);
            result->setOrigin(image.getOrigin());
            result->setFileName(image.getFileName());
            return result;
        }

        // Makes [target] hold what [source] holds (data, size, format, mip levels) and marks it changed
        void replaceImage(osg::Image& target, const osg::Image& source)
        {
            const std::size_t size = source.getTotalSizeInBytesIncludingMipmaps();
            unsigned char* data = new unsigned char[size];
            std::memcpy(data, source.data(), size);
            target.setImage(source.s(), source.t(), source.r(), source.getInternalTextureFormat(),
                source.getPixelFormat(), source.getDataType(), data, osg::Image::USE_NEW_DELETE, source.getPacking());
            target.setMipmapLevels(source.getMipmapLevels());
            target.setOrigin(source.getOrigin());
            target.dirty();
        }

        class TextureStreaming
        {
        public:
            using Prepare = osg::ref_ptr<osg::Image> (*)(osg::Image&);

            osg::ref_ptr<osg::Image> read(osg::Image& image, Prepare prepare)
            {
                if (onMainThread() || (tlPath != nullptr && fullQualityImages().contains(*tlPath)))
                    return nullptr;
                osg::ref_ptr<osg::Image> small = withoutTopLevels(image, streamingSettings().mStartSize);
                if (!small)
                    return nullptr;
                if (osg::ref_ptr<osg::Image> prepared = prepare(*small))
                    small = prepared;

                std::lock_guard lock(mMutex);
                if (mStopped)
                    return nullptr;
                Pending& pending = mPending[small.get()];
                pending.mSmall = small;
                pending.mFull = &image;
                pending.mPrepare = prepare;
                mPendingCount.store(mPending.size(), std::memory_order_relaxed);
                ++mStreamed;
                return small;
            }

            void textureLoading(const osg::Image& image)
            {
                std::lock_guard lock(mMutex);
                const auto found = mPending.find(&image);
                if (found != mPending.end() && !found->second.mUploaded)
                {
                    found->second.mUploaded = true;
                    found->second.mUploadFrame = mFrames;
                }
            }

            bool anyPending() const { return mPendingCount.load(std::memory_order_relaxed) != 0; }

            // Of these images, the ones still small, with the largest side of each
            void pendingOf(const std::vector<const osg::Image*>& images, std::vector<std::pair<const osg::Image*, int>>& out)
            {
                std::lock_guard lock(mMutex);
                for (const osg::Image* image : images)
                    if (mPending.find(image) != mPending.end())
                        out.emplace_back(image, std::max(image->s(), image->t()));
            }

            // Drawn over this many pixels: the images smaller than that are made full size
            void drawn(const std::vector<std::pair<const osg::Image*, int>>& images, float pixels)
            {
                std::lock_guard lock(mMutex);
                for (const auto& [image, size] : images)
                    if (pixels > static_cast<float>(size))
                        upgrade(image);
            }

            void frame()
            {
                std::vector<Ready> swaps;
                {
                    std::lock_guard lock(mMutex);
                    // small images the engine let go of before they were drawn: their full images are not needed
                    if (++mFrames % 600 == 0)
                        std::erase_if(mPending, [](const auto& entry) { return !entry.second.mSmall.valid(); });
                    // uploaded, and never drawn by the scene camera since: not something it decides for
                    if (mFrames % 60 == 0)
                    {
                        std::vector<const osg::Image*> unseen;
                        for (const auto& [image, pending] : mPending)
                        {
                            if (!pending.mUploaded || pending.mSeen || mFrames - pending.mUploadFrame <= sUnseenFrames)
                                continue;
                            unseen.push_back(image);
                            // shown some other way than in the world: read at full size from now on
                            osg::ref_ptr<osg::Image> alive;
                            if (pending.mSmall.lock(alive))
                                fullQualityImages().add(alive->getFileName());
                        }
                        for (const osg::Image* image : unseen)
                            upgrade(image);
                    }
                    mPendingCount.store(mPending.size(), std::memory_order_relaxed);
                    const std::size_t n = std::min<std::size_t>(mReady.size(), streamingSettings().mSwapsPerFrame);
                    swaps.assign(std::make_move_iterator(mReady.begin()), std::make_move_iterator(mReady.begin() + n));
                    mReady.erase(mReady.begin(), mReady.begin() + n);
                }
                for (Ready& ready : swaps)
                {
                    osg::ref_ptr<osg::Image> small;
                    if (ready.mSmall.lock(small))
                    {
                        replaceImage(*small, *ready.mFull);
                        ++mSwapped;
                    }
                }
                if (!swaps.empty() && mSwapped.load() % 500 < swaps.size())
                    Log(Debug::Verbose) << "Texture streaming: " << mSwapped.load() << " of " << mStreamed.load()
                                        << " textures at full size";
            }

            // The scene camera drew it (small or not): it decides when the image is made full size
            void seen(const std::vector<std::pair<const osg::Image*, int>>& images)
            {
                std::lock_guard lock(mMutex);
                for (const auto& entry : images)
                    if (const auto found = mPending.find(entry.first); found != mPending.end())
                        found->second.mSeen = true;
            }

            void stop()
            {
                {
                    std::lock_guard lock(mMutex);
                    mStopped = true;
                    mQueue.clear();
                    mReady.clear();
                    mPending.clear();
                    mPendingCount = 0;
                }
                mWake.notify_all();
                if (mWorker.joinable())
                    mWorker.join();
            }

            ~TextureStreaming() { stop(); }

        private:
            static constexpr unsigned sUnseenFrames = 300;

            struct Pending
            {
                osg::observer_ptr<osg::Image> mSmall; // what the engine has; gone when it let the texture go
                osg::ref_ptr<osg::Image> mFull; // as read
                Prepare mPrepare = nullptr;
                bool mUploaded = false;
                unsigned mUploadFrame = 0;
                bool mSeen = false; // drawn by the scene camera
            };

            // Queues the full image of a small one, once: the swap makes the image change, and it is no longer pending
            // (with the mutex held)
            void upgrade(const osg::Image* image)
            {
                const auto found = mPending.find(image);
                if (found == mPending.end())
                    return;
                Pending pending = std::move(found->second);
                mPending.erase(found);
                mPendingCount.store(mPending.size(), std::memory_order_relaxed);
                if (!pending.mSmall.valid())
                    return;
                mQueue.push_back(std::move(pending));
                if (!mWorker.joinable())
                    mWorker = std::thread([this] { work(); });
                mWake.notify_one();
            }

            struct Ready
            {
                osg::observer_ptr<osg::Image> mSmall;
                osg::ref_ptr<osg::Image> mFull; // prepared for the GPU
            };

            void work()
            {
                while (true)
                {
                    Pending job;
                    {
                        std::unique_lock lock(mMutex);
                        mWake.wait(lock, [this] { return mStopped || !mQueue.empty(); });
                        if (mStopped)
                            return;
                        job = std::move(mQueue.front());
                        mQueue.pop_front();
                    }
                    if (!job.mSmall.valid())
                        continue;
                    osg::ref_ptr<osg::Image> full = job.mFull;
                    if (osg::ref_ptr<osg::Image> prepared = job.mPrepare(*full))
                        full = prepared;
                    std::lock_guard lock(mMutex);
                    if (mStopped)
                        return;
                    mReady.push_back({ job.mSmall, full });
                }
            }

            std::mutex mMutex;
            std::condition_variable mWake;
            std::unordered_map<const osg::Image*, Pending> mPending; // read small, not uploaded yet
            std::deque<Pending> mQueue; // uploaded small, to prepare at full size
            std::deque<Ready> mReady; // to swap in
            std::thread mWorker;
            bool mStopped = false;
            unsigned mFrames = 0;
            std::atomic<unsigned> mStreamed{ 0 }, mSwapped{ 0 };
            std::atomic<std::size_t> mPendingCount{ 0 };
        };

        TextureStreaming& streaming()
        {
            static TextureStreaming instance;
            return instance;
        }

        // The images of the textures a state graph leaf draws with, from its root down
        void imagesOf(const osgUtil::StateGraph* leaf, std::vector<const osg::Image*>& out)
        {
            for (const osgUtil::StateGraph* graph = leaf; graph != nullptr; graph = graph->_parent)
            {
                const osg::StateSet* set = graph->getStateSet();
                if (set == nullptr)
                    continue;
                const osg::StateSet::TextureAttributeList& units = set->getTextureAttributeList();
                for (unsigned unit = 0; unit < units.size(); ++unit)
                {
                    const auto* texture
                        = static_cast<const osg::Texture*>(set->getTextureAttribute(unit, osg::StateAttribute::TEXTURE));
                    if (texture == nullptr)
                        continue;
                    for (unsigned i = 0; i < texture->getNumImages(); ++i)
                        if (const osg::Image* image = texture->getImage(i))
                            out.push_back(image);
                }
            }
        }

        // What the scene camera's cull has found this frame, per state graph: the small images it draws with
        struct CullCache
        {
            unsigned mFrame = ~0u;
            std::unordered_map<const osgUtil::StateGraph*, std::vector<std::pair<const osg::Image*, int>>> mPending;
            std::vector<const osg::Image*> mScratch;
        };
    }

    osg::ref_ptr<osg::Image> textureStreamingRead(osg::Image& image, osg::ref_ptr<osg::Image> (*prepare)(osg::Image&))
    {
        if (!streamingSettings().mEnabled)
            return nullptr;
        static std::once_flag logged;
        std::call_once(logged, [] {
            Log(Debug::Info) << "Texture streaming on: textures start at " << streamingSettings().mStartSize
                             << " px, " << streamingSettings().mSwapsPerFrame << " made full size per frame";
        });
        return streaming().read(image, prepare);
    }

    void textureStreamingTextureLoading(const osg::Image& image)
    {
        if (streamingSettings().mEnabled)
            streaming().textureLoading(image);
    }

    void noteMainThread()
    {
        sMainThread.store(std::this_thread::get_id(), std::memory_order_relaxed);
    }

    void setStreamingPath(const std::string* path)
    {
        tlPath = path;
    }

    void textureStreamingDrawableAdded(osg::NodeVisitor& cullVisitor, osg::Drawable& drawable, const osg::Matrix& modelView)
    {
        if (!streamingSettings().mEnabled || !streaming().anyPending())
            return;
        auto& cv = static_cast<osgUtil::CullVisitor&>(cullVisitor);
        const osg::Camera* camera = cv.getCurrentCamera();
        const osg::FrameStamp* stamp = cv.getFrameStamp();
        const osg::Viewport* viewport = cv.getViewport();
        if (camera == nullptr || stamp == nullptr || viewport == nullptr || camera->getName() != "SceneCam")
            return;

        thread_local CullCache cache;
        if (stamp->getFrameNumber() != cache.mFrame)
        {
            cache.mFrame = stamp->getFrameNumber();
            cache.mPending.clear(); // the state graphs are rebuilt (and may be freed) every frame
        }
        const osgUtil::StateGraph* graph = cv.getCurrentStateGraph();
        auto found = cache.mPending.find(graph);
        if (found == cache.mPending.end())
        {
            cache.mScratch.clear();
            imagesOf(graph, cache.mScratch);
            found = cache.mPending.emplace(graph, std::vector<std::pair<const osg::Image*, int>>()).first;
            streaming().pendingOf(cache.mScratch, found->second);
            streaming().seen(found->second);
        }
        if (found->second.empty())
            return;

        // how many pixels the drawable covers across, at its nearest
        const osg::BoundingBox& box = drawable.getBoundingBox();
        if (!box.valid())
            return;
        const double scale = std::sqrt(std::max({ modelView(0, 0) * modelView(0, 0) + modelView(0, 1) * modelView(0, 1)
                + modelView(0, 2) * modelView(0, 2),
            modelView(1, 0) * modelView(1, 0) + modelView(1, 1) * modelView(1, 1) + modelView(1, 2) * modelView(1, 2),
            modelView(2, 0) * modelView(2, 0) + modelView(2, 1) * modelView(2, 1) + modelView(2, 2) * modelView(2, 2) }));
        const double radius = box.radius() * scale;
        const double distance = (box.center() * modelView).length() - radius;
        const osg::Matrix& projection = *cv.getProjectionMatrix();
        const float pixels = distance <= 1.0
            ? std::numeric_limits<float>::infinity()
            : static_cast<float>(radius / distance * std::abs(projection(1, 1)) * viewport->height());
        streaming().drawn(found->second, pixels);
    }

    void textureStreamingFrame()
    {
        if (streamingSettings().mEnabled)
            streaming().frame();
    }

    void textureStreamingStop()
    {
        if (streamingSettings().mEnabled)
            streaming().stop();
    }
}
