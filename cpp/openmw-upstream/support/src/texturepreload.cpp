// The textures of the previous session, loaded on background threads while the game starts, so that the loading screen
// finds them in the image manager's cache instead of decoding them one at a time on the main thread. The list is what
// the game drew (in the order it first did) and is kept in the cache directory.
#include "support.hpp"

#include <alpha3/upstream.hpp>

#include <algorithm>
#include <atomic>
#include <chrono>
#include <condition_variable>
#include <fstream>
#include <memory>
#include <mutex>
#include <string>
#include <system_error>
#include <thread>
#include <unordered_set>
#include <vector>

#include <osg/Image>

#include <components/debug/debuglog.hpp>
#include <components/resource/imagemanager.hpp>
#include <components/vfs/manager.hpp>
#include <components/vfs/pathutil.hpp>

namespace Alpha3
{
    namespace
    {
        constexpr std::size_t sMaxListedImages = 6000;
        // What the preload holds on to (the images the game then asks for are shared with it), and for how long
        constexpr std::size_t sMaxPreloadBytes = std::size_t(768) << 20;
        constexpr std::chrono::seconds sPreloadHold(150);
        // the game can be closed without a clean shutdown, so the list is written as it grows
        constexpr std::chrono::seconds sSaveInterval(15);

        class TexturePreload
        {
        public:
            void setSource(Resource::ImageManager* (*source)())
            {
                std::lock_guard lock(mStateMutex);
                mSource = source;
            }

            // On the first DDS image the engine reads: its image manager exists by then
            void imageRead()
            {
                if (mStarted.load(std::memory_order_acquire))
                    return;
                std::lock_guard lock(mStateMutex);
                if (mStarted.load(std::memory_order_relaxed) || mSource == nullptr)
                    return;
                Resource::ImageManager* manager = mSource();
                if (manager == nullptr)
                    return;
                mManager = manager;
                mStarted.store(true, std::memory_order_release);
                start();
            }

            void textureLoading(const osg::Image& image)
            {
                const std::string& name = image.getFileName();
                if (name.empty())
                    return;
                std::lock_guard lock(mRecordMutex);
                if (!mRecording || mRecordedSet.count(name))
                    return;
                // only what is there to load (not images made in memory, nor files outside the VFS)
                if (!mManager->getVFS()->exists(VFS::Path::Normalized(name)))
                    return;
                mRecordedSet.insert(name);
                mRecorded.push_back(name);
                mRecordDirty = true;
            }

            // Before the engine's image manager goes away (its viewer closes the window's context first)
            void stop()
            {
                {
                    std::lock_guard lock(mStateMutex);
                    if (mStopping)
                        return;
                    mStopping = true;
                }
                mWake.notify_all();
                // a preload thread finishes the image it is reading and sees mStopping before the next
                for (std::thread& thread : mThreads)
                    thread.join();
                mThreads.clear();
                bool recording;
                {
                    std::lock_guard lock(mRecordMutex);
                    recording = mRecording;
                }
                if (recording)
                    saveList();
                std::lock_guard lock(mRecordMutex);
                mRecording = false;
                mPreloaded.clear();
            }

        private:
            void start()
            {
                const unsigned threads = static_cast<unsigned>(std::clamp(settingInt("Textures", "preload threads", 3), 0, 16));
                mListFile = cacheDirectory() / "texture-preload.list";
                {
                    std::ifstream in(mListFile, std::ios::binary);
                    std::string line;
                    while (std::getline(in, line))
                        if (!line.empty() && mManager->getVFS()->exists(VFS::Path::Normalized(line))) // not what has gone since
                            mPrevious.push_back(line);
                }
                {
                    std::lock_guard lock(mRecordMutex);
                    mRecording = true;
                }
                Log(Debug::Info) << "Texture preload: " << mPrevious.size() << " textures listed, " << threads << " threads";

                mThreads.emplace_back([this] {
                    std::unique_lock lock(mStateMutex);
                    while (!mWake.wait_for(lock, sSaveInterval, [this] { return mStopping; }))
                    {
                        lock.unlock();
                        bool dirty;
                        {
                            std::lock_guard recordLock(mRecordMutex);
                            dirty = mRecordDirty;
                        }
                        if (dirty)
                            saveList();
                        lock.lock();
                    }
                });
                if (mPrevious.empty() || threads == 0)
                    return;

                auto next = std::make_shared<std::atomic<std::size_t>>(0);
                auto bytes = std::make_shared<std::atomic<std::size_t>>(0);
                for (unsigned i = 0; i < threads; ++i)
                {
                    mThreads.emplace_back([this, next, bytes] {
                        while (true)
                        {
                            const std::size_t index = next->fetch_add(1);
                            if (index >= mPrevious.size() || bytes->load() > sMaxPreloadBytes)
                                return;
                            {
                                std::lock_guard lock(mStateMutex);
                                if (mStopping)
                                    return;
                            }
                            try
                            {
                                osg::ref_ptr<osg::Image> image
                                    = mManager->getImage(VFS::Path::Normalized(mPrevious[index]));
                                if (image == nullptr || image == mManager->getWarningImage())
                                    continue;
                                bytes->fetch_add(image->getTotalSizeInBytesIncludingMipmaps());
                                std::lock_guard lock(mRecordMutex);
                                mPreloaded.push_back(std::move(image));
                            }
                            catch (const std::exception&)
                            {
                            }
                        }
                    });
                }
                // lets go of the images once the game has had its chance to ask for them
                const auto deadline = std::chrono::steady_clock::now() + sPreloadHold;
                mThreads.emplace_back([this, deadline] {
                    std::unique_lock lock(mStateMutex);
                    mWake.wait_until(lock, deadline, [this] { return mStopping; });
                    lock.unlock();
                    std::vector<osg::ref_ptr<osg::Image>> released;
                    std::lock_guard recordLock(mRecordMutex);
                    released.swap(mPreloaded);
                });
            }

            void saveList()
            {
                std::vector<std::string> lines;
                {
                    std::lock_guard lock(mRecordMutex);
                    mRecordDirty = false;
                    lines = mRecorded;
                    // what the last run listed and this one has not drawn (yet) goes after it
                    for (const std::string& path : mPrevious)
                    {
                        if (lines.size() >= sMaxListedImages)
                            break;
                        if (!mRecordedSet.count(path))
                            lines.push_back(path);
                    }
                }
                if (lines.size() > sMaxListedImages)
                    lines.resize(sMaxListedImages);
                std::filesystem::path tmp = mListFile;
                tmp += ".tmp";
                {
                    std::ofstream out(tmp, std::ios::binary | std::ios::trunc);
                    for (const std::string& line : lines)
                        out << line << '\n';
                    if (!out)
                        return;
                }
                std::error_code ec;
                std::filesystem::rename(tmp, mListFile, ec);
            }

            std::mutex mStateMutex;
            std::condition_variable mWake;
            Resource::ImageManager* (*mSource)() = nullptr;
            std::atomic<bool> mStarted{ false };
            bool mStopping = false;
            Resource::ImageManager* mManager = nullptr;
            std::vector<std::thread> mThreads;
            std::filesystem::path mListFile;
            // set before the threads start, read only after
            std::vector<std::string> mPrevious;

            std::mutex mRecordMutex;
            std::vector<std::string> mRecorded;
            std::unordered_set<std::string> mRecordedSet;
            bool mRecordDirty = false;
            bool mRecording = false;
            std::vector<osg::ref_ptr<osg::Image>> mPreloaded;
        };

        TexturePreload& preload()
        {
            static TexturePreload instance;
            return instance;
        }
    }

    void setImageManagerSource(Resource::ImageManager* (*source)())
    {
        noteMainThread(); // the engine's setup calls this on its main thread
        preload().setSource(source);
    }

    void texturePreloadImageRead()
    {
        preload().imageRead();
    }

    void texturePreloadTextureLoading(const osg::Image& image)
    {
        preload().textureLoading(image);
    }

    void texturePreloadStop()
    {
        preload().stop();
    }
}
