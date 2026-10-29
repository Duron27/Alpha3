// Host test driver for the support library's glesdialect.cpp: translate <vert|frag> <desktop|compat|gles> [clamp] < in > out
// (clamp: the shader is drawn with GL_DEPTH_CLAMP, as the shadow casters are)
#include "glesdialect.hpp"
#include <iostream>
#include <iterator>
#include <string>
int main(int argc, char** argv)
{
    if (argc < 3) return 2;
    // as on a context with GL_EXT_clip_cull_distance (the phones Alpha3 targets)
    Alpha3::setClipDistancesAvailable(true);
    std::string src((std::istreambuf_iterator<char>(std::cin)), std::istreambuf_iterator<char>());
    const Alpha3::GlslStage stage = std::string(argv[1]) == "vert" ? Alpha3::GlslStage::Vertex : Alpha3::GlslStage::Fragment;
    const std::string t = argv[2];
    const Alpha3::GlslTarget target = t == "gles" ? Alpha3::GlslTarget::Gles32 : t == "compat" ? Alpha3::GlslTarget::DesktopCompat : Alpha3::GlslTarget::Desktop;
    std::cout << Alpha3::translateGlsl(src, stage, target, argc > 3 && std::string(argv[3]) == "clamp");
}
