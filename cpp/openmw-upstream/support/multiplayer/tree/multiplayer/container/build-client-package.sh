#!/bin/bash
# Build the OpenMW programs inside an Ubuntu 24.04 container and produce the
# client package zips, so they run on other Linux distributions.
#
#   multiplayer/container/build-client-package.sh [SOURCE_DIR] [OUT_DIR]
#
# SOURCE_DIR defaults to the repository containing this script.
# OUT_DIR defaults to ./client-package-out. The build tree is kept in
# ~/.cache/openmw-mp-container-build so rebuilds are incremental.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
SRC="$(cd "${1:-$HERE/../..}" && pwd)"
OUT="$(mkdir -p "${2:-./client-package-out}" && cd "${2:-./client-package-out}" && pwd)"
CACHE="${OPENMW_MP_BUILD_CACHE:-$HOME/.cache/openmw-mp-container-build}"
mkdir -p "$CACHE/build" "$CACHE/ccache"
IMAGE=openmw-mp-client-builder

docker build -t "$IMAGE" "$HERE"
docker run --rm \
  -v "$SRC":/src:ro -v "$CACHE/build":/build -v "$CACHE/ccache":/ccache -v "$OUT":/out \
  -e CCACHE_DIR=/ccache -e HOST_UID="$(id -u)" -e HOST_GID="$(id -g)" \
  "$IMAGE" bash -euxo pipefail -c '
    cmake -S /src -B /build -G Ninja -DCMAKE_BUILD_TYPE=Release \
      -DCMAKE_C_COMPILER_LAUNCHER=ccache -DCMAKE_CXX_COMPILER_LAUNCHER=ccache \
      -DOPENMW_USE_SYSTEM_OSG=OFF -DOSG_STATIC=ON -DBUILD_SHARED_LIBS=OFF \
      -DUSE_SYSTEM_TINYXML=ON -DCMAKE_PREFIX_PATH=/opt/sdl2 -DSDL2_DIR=/opt/sdl2/lib/cmake/SDL2 \
      -DOPENMW_USE_SYSTEM_MYGUI=OFF -DOPENMW_USE_SYSTEM_BULLET=OFF \
      -DOPENMW_USE_SYSTEM_SQLITE3=OFF -DOPENMW_USE_SYSTEM_RECASTNAVIGATION=OFF \
      -DOPENMW_USE_VULKAN=ON -DOPENMW_USE_SYSTEM_GLSLANG=OFF \
      -DOPENMW_USE_SYSTEM_VULKAN_HEADERS=OFF -DOPENMW_USE_SYSTEM_VMA=OFF \
      -DOPENMW_BUILTIN_MULTIPLAYER=ON
    cmake --build /build --target client-package -j"$(nproc)"
    cp /build/openmw-multiplayer-client-*.zip /out/
    chown "$HOST_UID:$HOST_GID" /out/*.zip
  '
echo "Packages written to $OUT"
