#!/bin/bash
# Builds the multiplayer server the way the release job does (Debian 12, LuaJIT and the C++ runtime linked in, so it
# runs on older servers) and puts it on your server over SSH: upload, back up the old folder, unpack over it (files
# you added there stay), restart the systemd service and check that a player can connect.
#
#   ./deploy-server.sh            build and deploy
#   ./deploy-server.sh --no-build deploy the last build again
#   ./deploy-server.sh --setup    change the settings
#
# Settings: ~/.config/alpha3/mpserver-deploy.conf (asked for on the first run). Log in with an SSH key, not a password:
#   ssh-copy-id user@your-server      (once; it asks for the password that one time)
set -euo pipefail

here=$(cd "$(dirname "$0")" && pwd)
repo=$(cd "$here/../../../.." && pwd)
conf=${XDG_CONFIG_HOME:-$HOME/.config}/alpha3/mpserver-deploy.conf
out=${XDG_CACHE_HOME:-$HOME/.cache}/alpha3/mpserver
image=alpha3-mpserver-builder
zip=$out/build/openmw-mp-server-Linux-x86_64.zip

setup() {
    mkdir -p "$(dirname "$conf")"
    local host="" port="" dir="" service=""
    # shellcheck disable=SC1090
    [ -f "$conf" ] && . "$conf" && host=$MP_HOST port=$MP_SSH_PORT dir=$MP_DIR service=$MP_SERVICE
    read -r -p "SSH login, user@address [${host:-root@your-server}]: " v; host=${v:-$host}
    read -r -p "SSH port [${port:-22}]: " v; port=${v:-${port:-22}}
    read -r -p "Server folder on it (the one holding openmw-mp-server) [${dir:-/opt/openmw-mp-server}]: " v
    dir=${v:-${dir:-/opt/openmw-mp-server}}
    read -r -p "systemd service [${service:-openmw-mp-server.service}]: " v; service=${v:-${service:-openmw-mp-server.service}}
    printf 'MP_HOST=%q\nMP_SSH_PORT=%q\nMP_DIR=%q\nMP_SERVICE=%q\n' "$host" "$port" "$dir" "$service" >"$conf"
    echo "Saved in $conf"
}

build() {
    command -v docker >/dev/null || { echo "docker is needed for the build" >&2; exit 1; }
    if ! docker image inspect "$image" >/dev/null 2>&1; then
        echo "== Making the build image (once)"
        docker build -t "$image" - <<'EOF'
FROM debian:12
RUN apt-get update -qq && apt-get install -y -qq build-essential cmake git ca-certificates >/dev/null \
 && git clone --depth 1 --branch v2.1 https://github.com/LuaJIT/LuaJIT.git /tmp/luajit \
 && make -C /tmp/luajit -j"$(nproc)" BUILDMODE=static >/dev/null \
 && make -C /tmp/luajit install PREFIX=/opt/luajit >/dev/null && rm -rf /tmp/luajit
EOF
    fi
    echo "== Building the server"
    mkdir -p "$out"
    docker run --rm --user "$(id -u):$(id -g)" -v "$repo:/src:ro" -v "$out:/out" -w /out "$image" sh -c '
        cmake -S /src/cpp/openmw-upstream/support/multiplayer/tree/apps/mpserver/standalone -B /out/build \
            -DLUAJIT_PREFIX=/opt/luajit -DCMAKE_BUILD_TYPE=Release \
            -DCMAKE_EXE_LINKER_FLAGS="-static-libstdc++ -static-libgcc" -Wno-dev >/dev/null &&
        cmake --build /out/build --target mpserver-bundle -j"$(nproc)" 2>&1 | grep -E "error|warning: unused|\.zip" || true'
    [ -f "$zip" ] || { echo "The build made no zip" >&2; exit 1; }
}

case ${1:-} in
    --setup) setup; exit 0 ;;
    --no-build) ;;
    "") ;;
    *) sed -n '2,12p' "$0"; exit 2 ;;
esac
[ -f "$conf" ] || setup
# shellcheck disable=SC1090
. "$conf"
[ "${1:-}" = --no-build ] || build
[ -f "$zip" ] || { echo "No build yet: run without --no-build" >&2; exit 1; }

ssh=(ssh -p "$MP_SSH_PORT" -o BatchMode=yes "$MP_HOST")
if ! "${ssh[@]}" true 2>/dev/null; then
    echo "Cannot log in to $MP_HOST with a key. Once: ssh-copy-id -p $MP_SSH_PORT $MP_HOST" >&2
    exit 1
fi

stamp=$(date +%Y%m%d-%H%M%S)
echo "== Uploading to $MP_HOST"
scp -q -P "$MP_SSH_PORT" "$zip" "$MP_HOST:/tmp/openmw-mp-server-$stamp.zip"

echo "== Installing in $MP_DIR and restarting $MP_SERVICE"
# shellcheck disable=SC2087
"${ssh[@]}" bash -s -- "$MP_DIR" "$MP_SERVICE" "/tmp/openmw-mp-server-$stamp.zip" "$stamp" <<'REMOTE'
set -euo pipefail
dir=$1 service=$2 zip=$3 stamp=$4
sudo=""; [ "$(id -u)" -eq 0 ] || sudo="sudo -n"
stage=$(mktemp -d)
if command -v unzip >/dev/null; then unzip -q "$zip" -d "$stage"
else python3 -m zipfile -e "$zip" "$stage"; fi
chmod +x "$stage"/openmw-mp-server/openmw-mp-server "$stage"/openmw-mp-server/*.sh "$stage"/openmw-mp-server/alerts/*.sh 2>/dev/null || true
owner=$(stat -c %U:%G "$dir" 2>/dev/null || echo "")
if [ -d "$dir" ]; then
    $sudo cp -a "$dir" "$dir.bak-$stamp"
    echo "backup: $dir.bak-$stamp"
    # keep the five newest backups
    ls -1d "$dir".bak-* 2>/dev/null | sort | head -n -5 | xargs -r $sudo rm -rf
fi
$sudo mkdir -p "$dir"
$sudo cp -a "$stage"/openmw-mp-server/. "$dir"/
[ -n "$owner" ] && $sudo chown -R "$owner" "$dir"
rm -rf "$stage" "$zip"
$sudo systemctl restart "$service"
port=$(systemctl show -p ExecStart --value "$service" | grep -oE -- '--port[ =][0-9]+' | grep -oE '[0-9]+' | head -1)
port=${port:-25565}
for i in 1 2 3 4 5 6 7 8 9 10; do
    sleep 1
    if { exec 3<>"/dev/tcp/127.0.0.1/$port"; } 2>/dev/null && IFS= read -r -t 5 line <&3; then
        exec 3<&- 3>&-
        echo "running: port $port answers"
        $sudo journalctl -u "$service" -n 6 --no-pager -o cat || true
        exit 0
    fi
done
echo "THE SERVER DOES NOT ANSWER on port $port" >&2
systemctl status "$service" --no-pager -n 20 >&2 || true
exit 1
REMOTE
echo "== Done"
