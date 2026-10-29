# Build environment for the shareable client package.
# Ubuntu 24.04 (glibc 2.39) is the baseline: binaries built here run on any
# Linux distribution with glibc >= 2.39 (Ubuntu 24.04+, Debian 13+, Fedora 40+,
# Arch, openSUSE Tumbleweed, ...). Dependencies follow upstream CI.
FROM ubuntu:24.04
ENV DEBIAN_FRONTEND=noninteractive
RUN apt-get update && apt-get install -y --no-install-recommends \
    binutils gcc g++ build-essential cmake ninja-build ccache curl unzip git \
    pkg-config patchelf python3 ca-certificates file xz-utils \
    libboost-program-options-dev libboost-system-dev libboost-iostreams-dev \
    libboost-filesystem-dev \
    libavcodec-dev libavformat-dev libavutil-dev libswscale-dev libswresample-dev \
    libsdl2-dev libqt6opengl6-dev qt6-tools-dev qt6-tools-dev-tools \
    qt6-l10n-tools libqt6svg6 libqt6svg6-dev qt6-qpa-plugins qt6-wayland \
    libopenal-dev libunshield-dev libtinyxml-dev liblz4-dev libpng-dev \
    libjpeg-dev libicu-dev libyaml-cpp-dev librecast-dev libsqlite3-dev \
    libcollada-dom-dev libfreetype6-dev libgl-dev libvulkan-dev \
    libx11-dev libxrandr-dev libxi-dev libxext-dev libxcursor-dev \
    libwayland-dev wayland-protocols libxkbcommon-dev libasound2-dev libpulse-dev \
    libdecor-0-dev libsndio-dev libdbus-1-dev libudev-dev libxss-dev libxfixes-dev \
    libxinerama-dev libxcb1-dev libegl-dev libgbm-dev libdrm-dev libpipewire-0.3-dev \
    && rm -rf /var/lib/apt/lists/*

# SDL2 of our own: the distribution's links audio/window-system libraries directly
# (libsndio, libdecor, libXss, ...), so a fresh install without them could not start.
# Built from source, SDL loads them only when it needs them, as upstream's package does.
RUN curl -fsSL https://github.com/libsdl-org/SDL/releases/download/release-2.32.8/SDL2-2.32.8.tar.gz | tar xz -C /tmp \
    && cmake -S /tmp/SDL2-2.32.8 -B /tmp/sdl-build -G Ninja -DCMAKE_BUILD_TYPE=Release \
       -DCMAKE_INSTALL_PREFIX=/opt/sdl2 -DSDL_STATIC=OFF -DSDL_TEST=OFF \
    && cmake --build /tmp/sdl-build && cmake --install /tmp/sdl-build && rm -rf /tmp/SDL2-2.32.8 /tmp/sdl-build
ENV PKG_CONFIG_PATH=/opt/sdl2/lib/pkgconfig SDL2DIR=/opt/sdl2
WORKDIR /build
