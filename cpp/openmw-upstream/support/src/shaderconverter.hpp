#ifndef ALPHA3_SUPPORT_SHADERCONVERTER_H
#define ALPHA3_SUPPORT_SHADERCONVERTER_H

#include <string>

#include "glesdialect.hpp"

namespace Alpha3
{
    // True unless OPENMW_DISABLE_SPIRV is set: every shader made for OpenGL ES also goes through glslang and
    // SPIRV-Cross (GLSL to SPIR-V to ESSL). glslang accepts what desktop GLSL allows, such as implicit int to float
    // conversions, and SPIRV-Cross writes it out in the strict form a GLES driver wants.
    bool spirvConversionEnabled();

    // Drops the storage buffer blocks no member of which the source uses. A library declares every block of a shared
    // header but reads a few, and a driver may allow fewer blocks per stage (an Adreno 640 has 4) than the header has.
    std::string pruneUnusedStorageBlocks(const std::string& source);

    // Changes whenever this file is rebuilt (see translatorStamp)
    const char* converterStamp();

    // Returns the source converted to "#version <glesVersion> es", or the source itself if conversion fails.
    std::string convertGlslToGles(const std::string& source, GlslStage stage, unsigned int glesVersion = 320);
}

#endif
