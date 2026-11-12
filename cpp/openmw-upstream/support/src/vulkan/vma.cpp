// The Vulkan Memory Allocator (third_party/vma, MIT), built for Vulkan up to 1.2 (the version the device uses is given at run time) with the functions the loader found
#include "vk.hpp"

#define VMA_IMPLEMENTATION
#define VMA_STATIC_VULKAN_FUNCTIONS 0
#define VMA_DYNAMIC_VULKAN_FUNCTIONS 1
#define VMA_VULKAN_VERSION 1002000
#include <vk_mem_alloc.h>
