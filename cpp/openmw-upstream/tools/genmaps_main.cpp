// Host test driver for the support library's generatedmaps.cpp:
//   genmaps <in.rgba> <out prefix> <material name> [normal strength]
// in.rgba: width and height as two little endian uint32, then RGBA8 rows top to bottom (tools/genmaps.py makes it from a
// DDS). Writes <prefix>_nh.rgba, <prefix>_spec.rgba, <prefix>_ds.rgba in the same layout and <prefix>_nh.dds.
#include "generatedmaps.hpp"

#include <cstdint>
#include <cstdio>
#include <fstream>
#include <iterator>
#include <string>

namespace
{
    bool readRgba(const char* path, Alpha3::RgbaImage& image)
    {
        std::ifstream in(path, std::ios::binary);
        std::uint32_t size[2];
        if (!in.read(reinterpret_cast<char*>(size), sizeof size))
            return false;
        image.mWidth = static_cast<int>(size[0]);
        image.mHeight = static_cast<int>(size[1]);
        image.mRgba.resize(static_cast<std::size_t>(size[0]) * size[1] * 4);
        return static_cast<bool>(in.read(reinterpret_cast<char*>(image.mRgba.data()), image.mRgba.size()));
    }

    void writeRgba(const std::string& path, const Alpha3::RgbaImage& image)
    {
        std::ofstream out(path, std::ios::binary);
        const std::uint32_t size[2] = { static_cast<std::uint32_t>(image.mWidth), static_cast<std::uint32_t>(image.mHeight) };
        out.write(reinterpret_cast<const char*>(size), sizeof size);
        out.write(reinterpret_cast<const char*>(image.mRgba.data()), image.mRgba.size());
    }
}

int main(int argc, char** argv)
{
    if (argc < 4)
        return 2;
    Alpha3::RgbaImage diffuse;
    if (!readRgba(argv[1], diffuse))
        return 1;
    const std::string prefix = argv[2];
    Alpha3::Material material = Alpha3::Material::None;
    const auto rules = Alpha3::parseMaterialRules(std::string(argv[3]) + ":x");
    if (!rules.empty())
        material = rules.front().mMaterial;
    Alpha3::MapSettings settings;
    if (argc > 4)
        settings.mNormalStrength = std::stof(argv[4]);
    const Alpha3::RgbaImage normalHeight = Alpha3::makeNormalHeight(diffuse, settings);
    writeRgba(prefix + "_nh.rgba", normalHeight);
    writeRgba(prefix + "_spec.rgba", Alpha3::makeSpecular(diffuse, normalHeight, material));
    writeRgba(prefix + "_ds.rgba", Alpha3::makeDiffuseSpecular(diffuse, normalHeight, material));
    std::ofstream(prefix + "_nh.dds", std::ios::binary) << Alpha3::writeDds(normalHeight, true, material, Alpha3::grainAlongU(diffuse));
    std::printf("%s: %dx%d %s, grain along %s\n", argv[1], diffuse.mWidth, diffuse.mHeight, Alpha3::materialName(material),
        Alpha3::grainAlongU(diffuse) ? "u" : "v");
}
