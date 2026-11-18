#ifndef ALPHA3_SUPPORT_VULKAN_RESHADE_H
#define ALPHA3_SUPPORT_VULKAN_RESHADE_H

// ReShade effects (.fx) on the Vulkan renderer: the scene as the post-processing chain left it, before the GUI, goes
// through the techniques the preset turns on, in its order. The effects are compiled by ReShade's own compiler
// (third_party/reshadefx, BSD 3-clause, crosire/reshade v6.7.3) to SPIR-V; the runtime here is our own, after
// vkShade's (github.com/ralgar/vkShade, BSD 2-clause), on Vulkan 1.0 render passes and inside the frame (no submit or
// wait of its own).
//
// Files: /storage/emulated/0/Alpha3/reshade/ with Shaders/ (the .fx and .fxh, searched with their subfolders),
// Textures/ (the images the effects name) and ReShadePreset.ini (ReShade's own preset format: Techniques=,
// TechniqueSorting=, PreprocessorDefinitions=, a [File.fx] section of values each; plus Alpha3EffectsOn=). A small
// permissively licensed starter set ships with the app (cpp/reshade-starter, copied to Shaders/Alpha3 starter); users
// add any other pack beside it.
//
// The launcher's overlay (org.openmw.SdlEngineBridge omwEffectsReport / omwEffectsCommand) shows what there is and
// changes it while the game runs: values the next frame, techniques turned on or off and #defines recompile that
// file (on a thread of its own, the effects running meanwhile as they were).

#include "vk.hpp"

#include <memory>
#include <string>

namespace Alpha3::Vk
{
    class Context;

    class Effects
    {
    public:
        explicit Effects(Context& context);
        ~Effects();

        // Whether any technique is to be drawn (the window then needs to be an image of the context's own)
        bool active();

        // Draws the techniques on [window] (the context's window image, VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL before
        // and after; outside any render pass). depthView: the scene's depth (sampled, in depthLayout), or null.
        void apply(VkImage window, VkExtent2D extent, VkFormat format, VkImageView depthView, VkImageLayout depthLayout,
            bool depthReversed);

        // For the overlay (any thread): what there is, as JSON, and a change (see reshade.cpp, command())
        static std::string report();
        static void command(const std::string& command);

    private:
        struct Impl;
        std::unique_ptr<Impl> mImpl;
    };
}

#endif
