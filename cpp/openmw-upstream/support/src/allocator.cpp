// Replaces operator new and delete of the Android build by mimalloc (moved from OpenMW's apps/openmw/allocator*.cpp).
//
// Android's allocator (Scudo) keeps every size class in a region of fixed size (256 MB) and aborts with "Scudo OOM: The
// process has exhausted 256M for size class N" when one fills up, however much memory the phone has left. The engine
// makes millions of equally small objects (records, scene graph nodes, strings), so a big mod list fills the class.
// mimalloc has no such limit. Whatever else is linked (libc++ itself, the C libraries) still uses malloc, so delete
// asks mimalloc whether it owns a pointer and passes the others to free().
#include "support.hpp"

#include <mimalloc.h>

#include <atomic>
#include <cstdlib>
#include <new>

#include <components/debug/debuglog.hpp>

namespace
{
    // mimalloc until the settings say otherwise: memory is always given back to the allocator it came from, so switching
    // at any time is safe
    std::atomic<bool> sUseMimalloc{ true };
}

namespace Alpha3
{
    void applyAllocatorSetting()
    {
        const bool mimalloc = setting("General", "allocator", "mimalloc") != "system";
        sUseMimalloc = mimalloc;
        Log(Debug::Info) << "Allocator: " << (mimalloc ? "mimalloc" : "system");
    }
}

namespace
{
    void* allocate(std::size_t size, bool nothrow)
    {
        void* p = sUseMimalloc.load(std::memory_order_relaxed) ? mi_malloc(size) : std::malloc(size ? size : 1);
        if (p == nullptr && !nothrow)
            throw std::bad_alloc();
        return p;
    }

    void* allocateAligned(std::size_t size, std::size_t alignment, bool nothrow)
    {
        void* p = nullptr;
        if (sUseMimalloc.load(std::memory_order_relaxed))
            p = mi_malloc_aligned(size, alignment);
        else if (posix_memalign(&p, alignment < sizeof(void*) ? sizeof(void*) : alignment, size ? size : 1) != 0)
            p = nullptr;
        if (p == nullptr && !nothrow)
            throw std::bad_alloc();
        return p;
    }

    void release(void* p) noexcept
    {
        if (p == nullptr)
            return;
        if (mi_is_in_heap_region(p))
            mi_free(p);
        else
            std::free(p);
    }
}

void* operator new(std::size_t size) { return allocate(size, false); }
void* operator new[](std::size_t size) { return allocate(size, false); }
void* operator new(std::size_t size, const std::nothrow_t&) noexcept { return allocate(size, true); }
void* operator new[](std::size_t size, const std::nothrow_t&) noexcept { return allocate(size, true); }
void* operator new(std::size_t size, std::align_val_t a) { return allocateAligned(size, static_cast<std::size_t>(a), false); }
void* operator new[](std::size_t size, std::align_val_t a) { return allocateAligned(size, static_cast<std::size_t>(a), false); }
void* operator new(std::size_t size, std::align_val_t a, const std::nothrow_t&) noexcept
{
    return allocateAligned(size, static_cast<std::size_t>(a), true);
}
void* operator new[](std::size_t size, std::align_val_t a, const std::nothrow_t&) noexcept
{
    return allocateAligned(size, static_cast<std::size_t>(a), true);
}

void operator delete(void* p) noexcept { release(p); }
void operator delete[](void* p) noexcept { release(p); }
void operator delete(void* p, const std::nothrow_t&) noexcept { release(p); }
void operator delete[](void* p, const std::nothrow_t&) noexcept { release(p); }
void operator delete(void* p, std::size_t) noexcept { release(p); }
void operator delete[](void* p, std::size_t) noexcept { release(p); }
void operator delete(void* p, std::align_val_t) noexcept { release(p); }
void operator delete[](void* p, std::align_val_t) noexcept { release(p); }
void operator delete(void* p, std::size_t, std::align_val_t) noexcept { release(p); }
void operator delete[](void* p, std::size_t, std::align_val_t) noexcept { release(p); }
void operator delete(void* p, std::align_val_t, const std::nothrow_t&) noexcept { release(p); }
void operator delete[](void* p, std::align_val_t, const std::nothrow_t&) noexcept { release(p); }
