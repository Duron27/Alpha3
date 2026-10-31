#!/bin/bash
# Renders the procedural materials on vanilla textures, as the game would draw them (see index.html):
#   tools/procedural/render.sh <Morrowind textures dir> <out dir> <texture>:<material> ...
# Needs g++, python3 with numpy and Pillow, node and google-chrome. Writes <out dir>/<texture>.png.
set -e
here=$(cd "$(dirname "$0")" && pwd)
textures=$1 out=$(mkdir -p "$2" && cd "$2" && pwd); shift 2
work=$out/site
mkdir -p "$work/textures" "$work/maps"
support=$here/../../support/src
etcpak=${ETCPAK_SOURCE_DIR:?set ETCPAK_SOURCE_DIR to the etcpak source}
if [ ! -x "$out/genmaps" ] || [ "$support/generatedmaps.cpp" -nt "$out/genmaps" ]; then
    g++ -std=c++20 -O2 -I"$support" -I"$etcpak" "$here/../genmaps_main.cpp" "$support/generatedmaps.cpp" \
        "$etcpak/ProcessDxtc.cpp" "$etcpak/Tables.cpp" "$etcpak/Dither.cpp" "$etcpak/ProcessRGB.cpp" "$etcpak/bc7enc.cpp" \
        -o "$out/genmaps" -lpthread
fi
cp "$here/index.html" "$support/experimental/procedural.glsl" "$work/"
for item in "$@"; do
    name=${item%%:*} material=${item#*:}
    python3 -I -c "
import struct, sys
from PIL import Image
im = Image.open(sys.argv[1]).convert('RGBA')
im.save(sys.argv[2])
open(sys.argv[3], 'wb').write(struct.pack('<II', *im.size) + im.tobytes())
" "$textures/$name.dds" "$work/textures/$name.png" "$work/$name.rgba"
    "$out/genmaps" "$work/$name.rgba" "$work/maps/$name" "$material" > /dev/null
done
port=$((8700 + RANDOM % 200))
python3 -m http.server $port -d "$work" > /dev/null 2>&1 &
server=$!
trap 'kill $server' EXIT
sleep 0.5
node=$(command -v node || ls ~/emsdk/node/*/bin/node | head -1)
for item in "$@"; do
    name=${item%%:*}
    "$node" "$here/shot.mjs" "http://127.0.0.1:$port/index.html?tex=$name" "$out/$name.png" 1600 1000
    echo "$out/$name.png"
done
