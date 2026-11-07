// The Vulkan Memory Allocator (third_party/vma, MIT), built for Vulkan 1.0 with the functions the loader found
#include "vk.hpp"

#define VMA_IMPLEMENTATION
#define VMA_STATIC_VULKAN_FUNCTIONS 0
#define VMA_DYNAMIC_VULKAN_FUNCTIONS 1
#define VMA_VULKAN_VERSION 1000000
#include <vk_mem_alloc.h>
