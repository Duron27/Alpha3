// Linked shader programs kept on disk (glGetProgramBinary), so the driver does not compile them again on the next start.
// OSG asks for a program's binary before it compiles the shaders (RuntimeHooks), so a hit costs no shader compile and
// no translation. Moved from OpenMW's components/shader/programbinarycache.cpp.
#include "support.hpp"

#include "blobstore.hpp"
#include "glesdialect.hpp"
#include "shaderconverter.hpp"

#include <atomic>
#include <chrono>
#include <condition_variable>
#include <cstdint>
#include <cstdlib>
#include <deque>
#include <memory>
#include <mutex>
#include <system_error>
#include <thread>
#include <vector>

#include <osg/GL>

#include <components/debug/debuglog.hpp>

namespace Alpha3
{
    namespace
    {
        // 2: keyed by the sources OpenMW gives (before translation) and the build of the translator
        constexpr std::uint64_t sVersion = 2;
        constexpr std::uint32_t sStoreTag = 0x42503341; // "A3PB"
        // 2: started again, the entries of version 1 (keyed by the translated sources) are no longer found
        constexpr std::uint32_t sStoreVersion = 2;
        constexpr GLenum sNumProgramBinaryFormats = 0x87FE;

        struct Config
        {
            bool enabled = false;
            std::filesystem::path dir;
            std::string gpu;
            // All programs in one file: shader_cache.bin
            std::unique_ptr<BlobStore> store;
        };

        // Read on the first program, which is linked on the draw thread with the context current
        const Config& config()
        {
            static const Config result = [] {
                Config r;
                if (!settingBool("Shaders", "program binary cache", true))
                    return r;
                GLint formats = 0;
                glGetIntegerv(sNumProgramBinaryFormats, &formats);
                if (glGetError() != GL_NO_ERROR || formats <= 0)
                {
                    Log(Debug::Info) << "Shader program binary cache: the driver cannot save programs";
                    return r;
                }
                const std::string dir = setting("Shaders", "program binary cache path", "");
                r.dir = dir.empty() ? cacheDirectory() / "shader-programs" : std::filesystem::path(dir);
                std::error_code ec;
                std::filesystem::create_directories(r.dir, ec);
                if (ec)
                {
                    Log(Debug::Warning) << "Shader program binary cache: cannot create " << r.dir.string() << ": "
                                        << ec.message();
                    return r;
                }
                for (GLenum name : { GL_VENDOR, GL_RENDERER, GL_VERSION })
                {
                    const GLubyte* text = glGetString(name);
                    r.gpu += text ? reinterpret_cast<const char*>(text) : "";
                    r.gpu += '\n';
                }
                // Programs are looked up on the draw thread as they are first drawn: the file is read once, here, rather
                // than twice per program while the game runs (up to 64 MB; a bigger one is read per program)
                r.store = std::make_unique<BlobStore>(
                    r.dir / "shader_cache.bin", sStoreTag, sStoreVersion, std::size_t(64) << 20);
                if (!r.store->valid())
                    return r;
                r.enabled = true;
                Log(Debug::Info) << "Shader program binary cache: " << r.dir.string() << ", " << r.store->entries()
                                 << " programs";
                return r;
            }();
            return result;
        }

        struct Hash
        {
            std::uint64_t a = 0xcbf29ce484222325ull;
            std::uint64_t b = 0x84222325cbf29ce4ull;

            void add(const void* data, std::size_t size)
            {
                const auto* bytes = static_cast<const unsigned char*>(data);
                for (std::size_t i = 0; i < size; ++i)
                {
                    a = (a ^ bytes[i]) * 0x100000001b3ull;
                    b = (b ^ (bytes[i] + 0x9eu)) * 0x100000001b3ull;
                }
            }

            void add(const std::string& text)
            {
                const std::uint64_t size = text.size();
                add(&size, sizeof(size));
                add(text.data(), text.size());
            }

            void add(std::uint64_t value) { add(&value, sizeof(value)); }
        };

        BlobKey blobKeyOf(const std::string& key)
        {
            BlobKey result;
            if (key.size() == 32)
            {
                result.mLow = std::strtoull(key.substr(0, 16).c_str(), nullptr, 16);
                result.mHigh = std::strtoull(key.substr(16).c_str(), nullptr, 16);
            }
            return result;
        }

        // The draw thread hands the binaries over and goes on; one thread writes them
        class Writer
        {
        public:
            void push(BlobStore& store, const BlobKey& key, std::uint32_t format, std::vector<unsigned char> data)
            {
                std::lock_guard lock(mMutex);
                mQueue.push_back({ &store, key, format, std::move(data) });
                if (!mThread.joinable())
                    mThread = std::thread([this] { run(); });
                mWake.notify_one();
            }

            ~Writer()
            {
                {
                    std::lock_guard lock(mMutex);
                    mStop = true;
                }
                mWake.notify_one();
                if (mThread.joinable())
                    mThread.join();
            }

        private:
            struct Item
            {
                BlobStore* mStore;
                BlobKey mKey;
                std::uint32_t mFormat;
                std::vector<unsigned char> mData;
            };

            void run()
            {
                std::unique_lock lock(mMutex);
                while (true)
                {
                    mWake.wait(lock, [this] { return mStop || !mQueue.empty(); });
                    if (mQueue.empty())
                        return;
                    Item item = std::move(mQueue.front());
                    mQueue.pop_front();
                    lock.unlock();
                    item.mStore->append(item.mKey, &item.mFormat, sizeof(item.mFormat), item.mData.data(), item.mData.size());
                    lock.lock();
                }
            }

            std::mutex mMutex;
            std::condition_variable mWake;
            std::deque<Item> mQueue;
            std::thread mThread;
            bool mStop = false;
        };

        Writer& writer()
        {
            static Writer sWriter;
            return sWriter;
        }

        // Programs found in the cache, and programs compiled and saved (missing, or rejected by the driver), logged at
        // most every 5 seconds while they change
        std::atomic<unsigned> sLoaded{ 0 };
        std::atomic<unsigned> sSaved{ 0 };

        void logProgress()
        {
            using Clock = std::chrono::steady_clock;
            static std::mutex mutex;
            static Clock::time_point last;
            static unsigned lastTotal = 0;
            std::lock_guard lock(mutex);
            const unsigned loaded = sLoaded, saved = sSaved;
            const Clock::time_point now = Clock::now();
            if (loaded + saved == lastTotal || now - last < std::chrono::seconds(5))
                return;
            last = now;
            lastTotal = loaded + saved;
            Log(Debug::Info) << "Shader program binary cache: " << loaded << " programs loaded, " << saved
                             << " compiled and saved";
        }
    }

    std::string programBinaryKey(const osg::Program& program, const std::string& defines)
    {
        const Config& cfg = config();
        if (!cfg.enabled)
            return {};
        Hash hash;
        hash.add(sVersion);
        hash.add(cfg.gpu);
        hash.add(std::string(translatorStamp()));
        hash.add(std::string(converterStamp()));
        hash.add(experimentalShaderStamp());
        hash.add(std::string(std::getenv("OPENMW_DISABLE_SPIRV") ? "nospirv" : "spirv"));
        hash.add(defines);
        for (unsigned i = 0; i < program.getNumShaders(); ++i)
        {
            const osg::Shader* shader = program.getShader(i);
            hash.add(static_cast<std::uint64_t>(shader->getType()));
            hash.add(shader->getShaderSource());
        }
        for (const auto& [name, index] : program.getAttribBindingList())
        {
            hash.add(name);
            hash.add(index);
        }
        for (const auto& [name, index] : program.getFragDataBindingList())
        {
            hash.add(name);
            hash.add(index);
        }
        for (const auto& [name, index] : program.getUniformBlockBindingList())
        {
            hash.add(name);
            hash.add(index);
        }
        static const char digits[] = "0123456789abcdef";
        std::string out;
        for (std::uint64_t value : { hash.a, hash.b })
            for (int shift = 60; shift >= 0; shift -= 4)
                out += digits[(value >> shift) & 0xf];
        return out;
    }

    osg::ref_ptr<osg::Program::ProgramBinary> loadProgramBinary(const std::string& key)
    {
        const Config& cfg = config();
        if (!cfg.enabled)
            return nullptr;
        const BlobKey blobKey = blobKeyOf(key);
        const std::size_t size = cfg.store->size(blobKey);
        if (size <= sizeof(std::uint32_t))
            return nullptr;
        std::uint32_t format = 0;
        osg::ref_ptr<osg::Program::ProgramBinary> binary = new osg::Program::ProgramBinary;
        binary->allocate(static_cast<unsigned>(size - sizeof(format)));
        if (!cfg.store->read(blobKey, 0, &format, sizeof(format))
            || !cfg.store->read(blobKey, sizeof(format), binary->getData(), size - sizeof(format)))
            return nullptr;
        binary->setFormat(format);
        ++sLoaded;
        logProgress();
        return binary;
    }

    void storeProgramBinary(const std::string& key, const osg::Program::ProgramBinary& binary)
    {
        const Config& cfg = config();
        if (!cfg.enabled || binary.getSize() == 0)
            return;
        std::vector<unsigned char> data(binary.getData(), binary.getData() + binary.getSize());
        writer().push(*cfg.store, blobKeyOf(key), binary.getFormat(), std::move(data));
        ++sSaved;
        logProgress();
    }
}
