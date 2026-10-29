#include "blobstore.hpp"

#include <algorithm>
#include <cerrno>
#include <cstdlib>
#include <cstring>
#include <functional>
#include <mutex>

#include <fcntl.h>
#include <sys/file.h>
#include <sys/stat.h>
#include <unistd.h>

#include <components/debug/debuglog.hpp>

namespace Alpha3
{
    namespace
    {
        constexpr std::uint32_t sFileMagic = 0x424c4f42; // "BLOB"
        constexpr std::uint32_t sRecordMagic = 0x52434431; // "RCD1"
        constexpr std::uint32_t sMaxPayload = 1u << 30;

        struct FileHeader
        {
            std::uint32_t mMagic, mTag, mVersion, mReserved;
        };

        // Followed by the payload. mMagic is written last, so a record that was cut short is not taken for a valid one.
        struct RecordHeader
        {
            std::uint32_t mMagic;
            std::uint32_t mSize;
            std::uint64_t mLow;
            std::uint64_t mHigh;
        };

        bool writeAll(int fd, const void* data, std::size_t size, std::uint64_t offset)
        {
            const char* bytes = static_cast<const char*>(data);
            while (size > 0)
            {
                const ssize_t written = pwrite(fd, bytes, size, static_cast<off_t>(offset));
                if (written <= 0)
                    return false;
                bytes += written;
                size -= static_cast<std::size_t>(written);
                offset += static_cast<std::uint64_t>(written);
            }
            return true;
        }

        bool readAll(int fd, void* data, std::size_t size, std::uint64_t offset)
        {
            char* bytes = static_cast<char*>(data);
            while (size > 0)
            {
                const ssize_t got = pread(fd, bytes, size, static_cast<off_t>(offset));
                if (got <= 0)
                    return false;
                bytes += got;
                size -= static_cast<std::size_t>(got);
                offset += static_cast<std::uint64_t>(got);
            }
            return true;
        }

        // Another BlobStore on the same file (the launcher's texture pre-pass and the game live in one process, each with
        // its own copy of this code) may append too: writers take the file lock, and the end of the file is what counts
        struct FileLock
        {
            int mFd;
            explicit FileLock(int fd)
                : mFd(fd)
            {
                while (flock(mFd, LOCK_EX) != 0 && errno == EINTR)
                {
                }
            }
            ~FileLock() { flock(mFd, LOCK_UN); }
        };

        // Shared storage (/storage/emulated, FUSE) has no flock. The lock is then taken on a file named after the store in
        // ALPHA3_LOCK_DIR, which the app sets to a folder of its own storage; without it, writers do not lock.
        int openLockFile(int fd, const std::filesystem::path& path)
        {
            if (flock(fd, LOCK_SH | LOCK_NB) == 0 || errno == EWOULDBLOCK)
            {
                flock(fd, LOCK_UN);
                return fd;
            }
            const char* dir = std::getenv("ALPHA3_LOCK_DIR");
            if (dir == nullptr || *dir == '\0')
            {
                Log(Debug::Warning) << path.string() << ": no file locks there and no ALPHA3_LOCK_DIR";
                return -1;
            }
            const std::filesystem::path lockPath = std::filesystem::path(dir)
                / (path.filename().string() + "." + std::to_string(std::hash<std::string>()(path.string())) + ".lock");
            const int lockFd = open(lockPath.c_str(), O_RDWR | O_CREAT | O_CLOEXEC, 0644);
            if (lockFd < 0)
                Log(Debug::Warning) << "Cannot open " << lockPath.string();
            return lockFd;
        }
    }

    BlobStore::BlobStore(
        const std::filesystem::path& path, std::uint32_t tag, std::uint32_t version, std::size_t keepInMemory)
    {
        mFd = open(path.c_str(), O_RDWR | O_CREAT | O_CLOEXEC, 0644);
        if (mFd < 0)
        {
            Log(Debug::Warning) << "Cannot open " << path.string();
            return;
        }
        mLockFd = openLockFile(mFd, path);
        mOwnsLockFd = mLockFd >= 0 && mLockFd != mFd;
        const FileLock fileLock(mLockFd);
        struct stat info;
        if (fstat(mFd, &info) != 0)
        {
            close(mFd);
            mFd = -1;
            return;
        }
        const std::uint64_t fileSize = static_cast<std::uint64_t>(info.st_size);
        const FileHeader wanted{ sFileMagic, tag, version, 0 };
        FileHeader found{};
        if (fileSize < sizeof(found) || !readAll(mFd, &found, sizeof(found), 0)
            || std::memcmp(&found, &wanted, sizeof(found)) != 0)
        {
            // new, or written with another layout: start again
            if (fileSize > 0)
                Log(Debug::Info) << path.string() << " has another layout, starting it again";
            if (ftruncate(mFd, 0) != 0 || !writeAll(mFd, &wanted, sizeof(wanted), 0))
            {
                close(mFd);
                mFd = -1;
                return;
            }
            mEnd = sizeof(wanted);
            return;
        }

        if (fileSize <= keepInMemory)
        {
            mMemory.resize(static_cast<std::size_t>(fileSize));
            if (!readAll(mFd, mMemory.data(), mMemory.size(), 0))
                mMemory.clear();
        }

        // One walk over the record headers; the payloads are not read
        std::uint64_t position = sizeof(found);
        while (position + sizeof(RecordHeader) <= fileSize)
        {
            RecordHeader header;
            const bool got = !mMemory.empty()
                ? (std::memcpy(&header, mMemory.data() + position, sizeof(header)), true)
                : readAll(mFd, &header, sizeof(header), position);
            if (!got || header.mMagic != sRecordMagic
                || header.mSize > sMaxPayload || position + sizeof(header) + header.mSize > fileSize)
                break;
            mIndex.emplace(BlobKey{ header.mLow, header.mHigh }, Location{ position + sizeof(header), header.mSize });
            position += sizeof(header) + header.mSize;
        }
        if (position < fileSize)
        {
            Log(Debug::Info) << path.string() << ": dropping " << (fileSize - position) << " bytes of an unfinished entry";
            if (ftruncate(mFd, static_cast<off_t>(position)) != 0)
            {
                close(mFd);
                mFd = -1;
                return;
            }
        }
        // new entries go where the cut-off one was, and are read from the file
        if (mMemory.size() > position)
            mMemory.resize(static_cast<std::size_t>(position));
        mEnd = position;
        Log(Debug::Info) << path.string() << ": " << mIndex.size() << " entries, " << (mEnd >> 20) << " MB";
    }

    BlobStore::~BlobStore()
    {
        if (mFd >= 0)
            close(mFd);
        if (mOwnsLockFd)
            close(mLockFd);
    }

    std::size_t BlobStore::entries() const
    {
        std::shared_lock lock(mIndexMutex);
        return mIndex.size();
    }

    std::size_t BlobStore::size(const BlobKey& key) const
    {
        std::shared_lock lock(mIndexMutex);
        const auto it = mIndex.find(key);
        return it == mIndex.end() ? 0 : it->second.mSize;
    }

    bool BlobStore::read(const BlobKey& key, std::size_t offset, void* destination, std::size_t length) const
    {
        Location location;
        {
            std::shared_lock lock(mIndexMutex);
            const auto it = mIndex.find(key);
            if (it == mIndex.end())
                return false;
            location = it->second;
        }
        if (offset > location.mSize || length > location.mSize - offset)
            return false;
        const std::uint64_t start = location.mOffset + offset;
        if (start + length <= mMemory.size())
        {
            std::memcpy(destination, mMemory.data() + start, length);
            return true;
        }
        return readAll(mFd, destination, length, start);
    }

    bool BlobStore::append(
        const BlobKey& key, const void* first, std::size_t firstSize, const void* second, std::size_t secondSize)
    {
        const std::size_t payload = firstSize + secondSize;
        if (mFd < 0 || payload == 0 || payload > sMaxPayload)
            return false;
        std::lock_guard writeLock(mWriteMutex);
        {
            std::shared_lock lock(mIndexMutex);
            if (mIndex.count(key))
                return true;
        }
        RecordHeader header{ 0, static_cast<std::uint32_t>(payload), key.mLow, key.mHigh };
        const FileLock fileLock(mLockFd);
        struct stat info;
        const std::uint64_t start = fstat(mFd, &info) == 0 ? std::max<std::uint64_t>(mEnd, info.st_size) : mEnd;
        const bool written = writeAll(mFd, &header, sizeof(header), start)
            && writeAll(mFd, first, firstSize, start + sizeof(header))
            && (secondSize == 0 || writeAll(mFd, second, secondSize, start + sizeof(header) + firstSize));
        // The record only counts once its magic number is in place
        header.mMagic = sRecordMagic;
        if (!written || !writeAll(mFd, &header.mMagic, sizeof(header.mMagic), start))
        {
            (void)ftruncate(mFd, static_cast<off_t>(start));
            return false;
        }
        mEnd = start + sizeof(header) + payload;
        std::unique_lock lock(mIndexMutex);
        mIndex.emplace(key, Location{ start + sizeof(header), header.mSize });
        return true;
    }
}
