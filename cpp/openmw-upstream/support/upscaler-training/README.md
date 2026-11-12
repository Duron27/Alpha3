# Texture upscaler training

Trains the small model that `[Textures] texture upscale method = ai` runs on the phone (`src/aiupscale.cpp`). Training
happens on a PC with an NVIDIA GPU (PyTorch with CUDA); the phone only runs the finished model, once per texture, while
the texture is converted to ASTC (`src/textureconversion.cpp`).

**Only ship weights trained on data that may be distributed.** The repo's `src/aiupscale_weights.hpp` is the empty
model (`AI_UPSCALE_AVAILABLE 0`, and `ai` falls back to EASU) until such a model exists. A model trained on a texture
pack's files is derived from them, so it may only be committed or shipped when the pack's licence allows that (most
texture packs are all rights reserved). Check before training, not after.

## The model

`model.py`: the shape of Real-ESRGAN's compact network (SRVGGNetCompact, BSD-3). A 3x3 convolution from RGB to `feat`
channels, `convs` more, each with PReLU, a last one to 12 channels, a pixel shuffle to twice the size, added to the
input upscaled bilinearly. 32 channels and 8 convolutions is about 79,000 numbers: roughly 200 ms per 256 px texture on
a Snapdragon 855's CPU, all cores.

## Steps

1. `prep.py <hd textures dir> <out dir> <bsa>...`: pairs of (vanilla texture, the HD texture scaled to twice the vanilla
   size) from the game's BSAs (read only). Normal maps and textures over 1024 px are left out. Write `holdout.txt` in the
   out dir: the names (one per line; some have spaces) kept out of training for scoring, about 10%.
2. `train.py <data> <out .pt> <feat> <convs> <steps>`: L1 loss, 48 px crops (small textures tiled), flips and turns,
   Adam with a cosine schedule. 40,000 steps of the 32x8 model take about 15 minutes on an RTX 2070 Super.
3. `eval.py <data> <model.pt>...`: SSIMULACRA2 of each held-out texture against the HD one, for bilinear, EASU and each
   model. Build the helpers first: `g++ -O2 -std=c++20 -I../src score.cpp ../src/ssimulacra2.cpp -o score` and
   `easu.cpp` (it includes the EASU and RCAS code cut from `src/textureconversion.cpp` into `upscale_part.inc`).
4. `export.py <model.pt> ../src/aiupscale_weights.hpp`: the weights as C++ arrays, with a version hash that keys the
   ASTC cache (textures convert again when the model changes).

## Next

Train on shippable data: CC0 textures (Poly Haven, ambientCG), made smaller and DXT compressed to look like
Morrowind's, with the originals as targets, plus a perceptual loss so the model adds fine grain instead of smoothing
it. A first L1-only model beat both bilinear and EASU on about 86% of the held-out textures.
