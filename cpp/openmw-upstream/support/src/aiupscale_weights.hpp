// The AI texture upscaler's weights (src/aiupscale.cpp), written by upscaler-training/export.py. This one is empty:
// no model has been trained yet on data that may be shipped (see upscaler-training/README.md), so
// [Textures] texture upscale method = ai uses EASU instead. Replace this file with export.py's output only for a model
// whose training data allows it to be distributed.
#pragma once
#define AI_UPSCALE_AVAILABLE 0
namespace Alpha3::AiUpscaleWeights
{
    inline constexpr const char* kVersion = "none";
}
