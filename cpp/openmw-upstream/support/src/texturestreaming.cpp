// Texture streaming ([Textures] streaming): a large texture is read as its smaller mip levels only, so a cell is ready
// sooner (on a GPU without S3TC only those levels are converted to ASTC or ETC2, the slow part of a first load) and its
// textures go to the GPU small. The first time the small texture is uploaded (it is drawn, or compiled ahead for a cell
// about to be drawn) the full image is prepared on a background thread, and after a later frame its data replaces the
// small image's: OSG sees the image change and remakes the texture at full size (osg::Texture2D::textureObjectValid).
// A few are swapped per frame, so the uploads are spread out instead of stalling one frame.
//
// The image object is the one the engine's image manager keeps and every texture of that file shares, so nothing in the
// engine needs to know: the textures that use it change with it.
#include "support.hpp"

#include <algorithm>
#include <atomic>
#include <condition_variable>
#include <cstring>
#include <deque>
#include <mutex>
#include <thread>
#include <unordered_map>
#include <vector>

#include <osg/Image>
#include <osg/observer_ptr>

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
                ++mStreamed;
                return small;
            }

            void textureLoading(const osg::Image& image)
            {
                std::lock_guard lock(mMutex);
                const auto found = mPending.find(&image);
                if (found == mPending.end())
                    return;
                // only once: the swap makes the image change, and its next upload comes here again
                Pending pending = std::move(found->second);
                mPending.erase(found);
                if (!pending.mSmall.valid())
                    return;
                mQueue.push_back(std::move(pending));
                if (!mWorker.joinable())
                    mWorker = std::thread([this] { work(); });
                mWake.notify_one();
            }

            void frame()
            {
                std::vector<Ready> swaps;
                {
                    std::lock_guard lock(mMutex);
                    // small images the engine let go of before they were drawn: their full images are not needed
                    if (++mFrames % 600 == 0)
                        std::erase_if(mPending, [](const auto& entry) { return !entry.second.mSmall.valid(); });
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

            void stop()
            {
                {
                    std::lock_guard lock(mMutex);
                    mStopped = true;
                    mQueue.clear();
                    mReady.clear();
                    mPending.clear();
                }
                mWake.notify_all();
                if (mWorker.joinable())
                    mWorker.join();
            }

            ~TextureStreaming() { stop(); }

        private:
            struct Pending
            {
                osg::observer_ptr<osg::Image> mSmall; // what the engine has; gone when it let the texture go
                osg::ref_ptr<osg::Image> mFull; // as read
                Prepare mPrepare = nullptr;
            };

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
        };

        TextureStreaming& streaming()
        {
            static TextureStreaming instance;
            return instance;
        }
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
