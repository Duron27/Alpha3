// The glslang/SPIRV-Cross half of the translation (support/src/shaderconverter.cpp) for the host test driver: off, so
// check-shaders.sh validates what the text translation makes
#include "shaderconverter.hpp"

namespace Alpha3
{
    const char* converterStamp()
    {
        return "host";
    }

    bool spirvConversionEnabled()
    {
        return false;
    }

    std::string pruneUnusedStorageBlocks(const std::string& source)
    {
        return source;
    }

    std::string convertGlslToGles(const std::string& source, GlslStage, unsigned int)
    {
        return source;
    }
}
