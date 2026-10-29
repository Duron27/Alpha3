# Vulkan below 1.2 (vulkan-compat)

Goal (2026-10-19): one build for Vulkan 1.0 to 1.4. Vulkan 1.2 and up stay exactly as they are; lower devices may run
with fewer features, as long as the game starts and is playable.

## What is here

* `android-gles-fallback.patch` - on Android a failed Vulkan start (a device below Vulkan 1.2 or a driver that refuses it)
  goes to the phone's own OpenGL ES driver, not to Zink (desktop GL on Vulkan with a CPU copy). The same for the step down
  after a failed live switch. Adds the dev probe `adb shell setprop debug.alpha3.novulkan 1` to test it on a Vulkan phone.
  This is what makes a Vulkan 1.0/1.1 device playable today.

## Why a real Vulkan 1.1/1.0 entry is not a small change

The fork's Vulkan entries (components/vulkan/device.cpp, `sFeatures`) require, on every entry: timeline semaphores, buffer
device address, descriptor indexing (runtimeDescriptorArray, non-uniform sampled image indexing, partially bound, variable
count, update-after-bind, dynamic indexing), shaderDrawParameters, multiDrawIndirect with drawIndirectFirstInstance, and
dynamic rendering, synchronization2, maintenance4 and shaderDemoteToHelperInvocation (core in 1.3; the 1.2 entry takes
them as extensions). The scene is GPU driven (bindless material and instance tables, GPU culling, SPIR-V twins per entry
in components/shader). A 1.1 entry would need the KHR/EXT forms of every one of those (function pointers named with the
suffix, SPIR-V 1.3 plus extensions for the twins, feature structs chained separately), and a 1.0 entry additionally
VK_KHR_get_physical_device_properties2 and more. Devices that report only 1.0/1.1 mostly lack the descriptor indexing and
buffer device address extensions as well, so such an entry would rarely start; the games would be left with a second
renderer path without bindless resources, which is a different renderer. The GLES renderer already is that path.

If a 1.1 entry is still wanted, the work list is: Entry::Vulkan11 in components/settings/rendererentry.hpp and
components/vulkan/instance.cpp (apiVersion 1.1), the extension lists and feature chains in device.cpp, the devicetable.py
function list (KHR names), SpirvTarget::Vulkan11 (EShTargetVulkan_1_1) and a check of every shader for SPIR-V 1.3, and
every `entry == Entry::Vulkan12` site (about 30 in components/vulkan, more under apps/openmw/mwrender/clockwork).

## What the phone's own driver offers (Adreno 640, Qualcomm stock driver, Vulkan 1.1, measured 2026-10-19 with `cmd gpu vkjson`)

Has: buffer_device_address, draw_indirect_count, shader_draw_parameters, create_renderpass2, depth_stencil_resolve,
spirv_1_4, push_descriptor, multiview, scalar_block_layout, host_query_reset.

Missing, and each is a hard requirement of every entry in `sFeatures`: timeline semaphores, descriptor indexing
(runtimeDescriptorArray, partially bound, variable count, update-after-bind), dynamic rendering, synchronization2,
maintenance4, shaderDemoteToHelperInvocation, multiDrawIndirect/drawIndirectFirstInstance is unverified.

So a "Vulkan 1.1 entry" cannot be just KHR spellings of the 1.2 feature set: the stock driver has no bindless descriptors
and no dynamic rendering, which the whole scene path is built on. Making it start there means a second Vulkan renderer
(binary semaphores and fences, render passes and framebuffers, per-draw descriptor sets, no GPU-driven culling). The GLES
fallback in this folder is what these phones run instead.
