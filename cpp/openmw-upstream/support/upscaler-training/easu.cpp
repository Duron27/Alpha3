#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdio>
#include <cstdlib>
#include <thread>
#include <vector>
namespace Alpha3 { namespace {
#include "upscale_part.inc"
} }
int main(int argc, char** argv)
{
    const int w = atoi(argv[2]), h = atoi(argv[3]);
    std::vector<uint8_t> in(size_t(w) * h * 4), out;
    FILE* f = fopen(argv[1], "rb"); fread(in.data(), 1, in.size(), f); fclose(f);
    Alpha3::easu2x(in, w, h, out, 4); Alpha3::rcas(out, w * 2, h * 2, atof(argv[5]), 4);
    f = fopen(argv[4], "wb"); fwrite(out.data(), 1, out.size(), f); fclose(f);
}
