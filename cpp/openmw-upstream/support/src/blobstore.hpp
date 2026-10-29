#ifndef ALPHA3_SUPPORT_BLOBSTORE_H
#define ALPHA3_SUPPORT_BLOBSTORE_H

#include <cstddef>
#include <cstdint>
#include <filesystem>
#include <map>
#include <mutex>
#include <shared_mutex>
#include <unordered_map>
#include <vector>

namespace Alpha3
{
    struct BlobKey
    {
        std::uint64_t mLow = 0;
        std::uint64_t mHigh = 0;
        bool operator==(const BlobKey& other) const { return mLow == other.mLow && mHigh == other.mHigh; }
    };

    struct BlobKeyHash
    {
        std::size_t operator()(const BlobKey& key) const { return static_cast<std::size_t>(key.mLow ^ (key.mHigh * 0x9e3779b97f4a7c15ull)); }
    };

    /// A cache kept in one file instead of one file per entry: the file is opened once, the entries found by one
    /// scan of the record headers, and a lookup is a single pread. Entries are only appended, never changed, so a
    /// reader never sees a half-written one; a record becomes valid when its magic number is written, after the rest of it,
    /// and an unfinished record at the end (the app was killed while writing) is cut off the next time the file is opened.
    /// Safe to use from several threads.
    class BlobStore
    {
    public:
        /// [tag] and [version] identify the file's purpose and layout; a file with others is started again. A file no larger
        /// than [keepInMemory] bytes is read whole when it is opened, in one read, and lookups of what it held are served
        /// from memory (for a cache read on the draw thread, where every read of the file is a wait on the storage).
        BlobStore(const std::filesystem::path& path, std::uint32_t tag, std::uint32_t version, std::size_t keepInMemory = 0);
        ~BlobStore();
        BlobStore(const BlobStore&) = delete;
        BlobStore& operator=(const BlobStore&) = delete;

        bool valid() const { return mFd >= 0; }
        std::size_t entries() const;

        /// Size of the stored payload, 0 if there is none.
        std::size_t size(const BlobKey& key) const;

        /// Reads [length] bytes of the payload starting at [offset]; false if there is no such entry or it is too short.
        bool read(const BlobKey& key, std::size_t offset, void* destination, std::size_t length) const;

        /// Stores the payload (two parts, so a caller need not join its header and data); false if the file cannot be
        /// written. An entry that is already there is kept.
        bool append(const BlobKey& key, const void* first, std::size_t firstSize, const void* second = nullptr,
            std::size_t secondSize = 0);

    private:
        struct Location
        {
            std::uint64_t mOffset;
            std::uint32_t mSize;
        };

        int mFd = -1;
        // What writers lock: the file itself, or a lock file standing in for it where the file system has no flock
        int mLockFd = -1;
        bool mOwnsLockFd = false;
        std::uint64_t mEnd = 0;
        // The file as it was opened, when it was kept in memory; never changed afterwards
        std::vector<unsigned char> mMemory;
        mutable std::shared_mutex mIndexMutex;
        std::mutex mWriteMutex;
        std::unordered_map<BlobKey, Location, BlobKeyHash> mIndex;
    };
}

#endif
