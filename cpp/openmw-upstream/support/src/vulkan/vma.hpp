#ifndef ALPHA3_SUPPORT_VULKAN_VMA_H
#define ALPHA3_SUPPORT_VULKAN_VMA_H

// The Vulkan Memory Allocator as the renderer uses it (vma.cpp has its implementation): functions through the loader
#include "vk.hpp"

#define VMA_STATIC_VULKAN_FUNCTIONS 0
#define VMA_DYNAMIC_VULKAN_FUNCTIONS 1
#define VMA_VULKAN_VERSION 1002000
#include <vk_mem_alloc.h>

#endif
