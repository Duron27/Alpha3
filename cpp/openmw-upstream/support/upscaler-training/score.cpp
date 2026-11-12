// score <w> <h> <ref.raw> <dist.raw>...: SSIMULACRA2 of each RGBA picture against the reference
#include "ssimulacra2.hpp"
#include <cstdio>
#include <cstdlib>
#include <vector>
int main(int argc, char** argv)
{
    const int w = atoi(argv[1]), h = atoi(argv[2]);
    auto load = [&](const char* p) { std::vector<uint8_t> v(size_t(w) * h * 4); FILE* f = fopen(p, "rb"); fread(v.data(), 1, v.size(), f); fclose(f); return v; };
    auto ref = load(argv[3]);
    Alpha3::Ssimulacra2Reference R(ref.data(), w, h);
    for (int i = 4; i < argc; ++i) printf("%.2f ", R.score(load(argv[i]).data()));
    printf("\n");
}
