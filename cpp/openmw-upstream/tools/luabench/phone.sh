#!/bin/bash
# LuaJIT on the phone, outside the game: build variants of the engine's LuaJIT with the NDK exactly as
# cpp/openmw-upstream/CMakeLists.txt does, then run bench.lua with each on one core, rounds interleaved so heat and
# background load fall on every variant alike.
#
#   phone.sh build NAME LUAJIT_SRC ["CCOPT"] ["TARGET_FLAGS"]   e.g. build o3 <src> "-O3 -fomit-frame-pointer"
#   phone.sh run [ROUNDS] [CPU_MASK] NAME...                     e.g. run 5 20 base o3      (mask 20 = cpu5)
#
# LUAJIT_SRC is the fetched source, e.g. androidApp/.cxx/<variant>/<hash>/arm64-v8a/openmw-upstream/luajit-prefix/src/
# luajit. CCOPT also reaches the build machine's tools, so CPU flags (-mtune, -mcpu) go in TARGET_FLAGS.
# Builds land in $LUABENCH_OUT (default /tmp/luabench). ADB_SERIAL picks the phone when several are attached.
#
# The in-game side is Alpha3LuaBench/: a mod that times the same workloads inside OpenMW, plus calls through its Lua
# API, and says in openmw.log whether the JIT works there. The gear menu's Benchmark records the Lua thread's time
# per frame (lua_time_taken) next to everything else.
set -euo pipefail
here=$(cd "$(dirname "$0")" && pwd)
out=${LUABENCH_OUT:-/tmp/luabench}
adb=(adb ${ADB_SERIAL:+-s "$ADB_SERIAL"})
remote=/data/local/tmp/luabench

case "${1:-}" in
build)
    name=$2 src=$3 ccopt=${4:--O2 -fomit-frame-pointer} flags=${5:-}
    ndk=${ANDROID_NDK_BIN:-$HOME/Android/Sdk/ndk/29.0.14206865/toolchains/llvm/prebuilt/linux-x86_64/bin}
    cc=$ndk/aarch64-linux-android26-clang
    dir=$out/$name
    rm -rf "$dir" && mkdir -p "$dir"
    cp -a "$src"/. "$dir"/ && make -C "$dir" clean >/dev/null
    make -C "$dir" -j"$(nproc)" amalg HOST_CC=cc CROSS="$ndk/llvm-" "STATIC_CC=$cc -fPIC" "DYNAMIC_CC=$cc -fPIC" \
        TARGET_LD="$cc" "TARGET_AR=$ndk/llvm-ar rcus" TARGET_STRIP="$ndk/llvm-strip" TARGET_SYS=Linux \
        BUILDMODE=static PREFIX="$dir/inst" CCOPT="$ccopt" "TARGET_FLAGS=$flags" >"$dir/build.log" 2>&1
    echo "$dir/src/luajit"
    ;;
run)
    rounds=${2:-5} mask=${3:-20}
    shift 3
    "${adb[@]}" shell mkdir -p $remote
    "${adb[@]}" push -q "$here/bench.lua" $remote/
    for name in "$@"; do
        "${adb[@]}" push -q "$out/$name/src/luajit" $remote/luajit-"$name"
        "${adb[@]}" shell chmod 755 $remote/luajit-"$name"
    done
    log=$out/run-$(date +%Y%m%d-%H%M%S).txt
    for ((r = 1; r <= rounds; r++)); do
        for name in "$@"; do
            echo "## $name round $r temp $("${adb[@]}" shell cat /sys/class/thermal/thermal_zone0/temp | tr -d '\r')" >>"$log"
            "${adb[@]}" shell "cd $remote && taskset $mask ./luajit-$name bench.lua 5" | tr -d '\r' >>"$log"
        done
    done
    # median over the rounds of each workload's best time
    python3 - "$log" "$@" <<'EOF'
import re, statistics, sys
times, variant = {}, None
for line in open(sys.argv[1]):
    m = re.match(r"## (\S+) round", line)
    if m:
        variant = m.group(1)
        continue
    m = re.match(r"(\S+)\s+first\s+[\d.]+\s+best\s+([\d.]+)", line)
    if m:
        times.setdefault(m.group(1), {}).setdefault(variant, []).append(float(m.group(2)))
names = sys.argv[2:]
print("%-18s" % "workload (ms)" + "".join("%10s" % n for n in names))
totals = dict.fromkeys(names, 0.0)
for work, by in times.items():
    row = {n: statistics.median(by[n]) for n in names if n in by}
    for n, v in row.items():
        totals[n] += v
    print("%-18s" % work + "".join("%10.1f" % row.get(n, float("nan")) for n in names))
print("%-18s" % "total" + "".join("%10.1f" % totals[n] for n in names))
EOF
    echo "raw: $log"
    ;;
*)
    sed -n '2,15p' "$0"
    exit 1
    ;;
esac
