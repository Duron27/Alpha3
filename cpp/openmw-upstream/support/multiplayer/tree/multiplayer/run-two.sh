#!/usr/bin/env bash
# Two real game instances on this machine: one hosts, the other joins it (fake second player).
# usage: multiplayer/run-two.sh <path/to/openmw> <savegame.omwsave> [-- extra openmw args]
# Each instance gets its own user-data dir (settings, saves), under $TMPDIR/mp-two.
set -e
HERE="$(cd "$(dirname "$0")" && pwd)"
OPENMW="${1:?path to openmw}"; SAVE="${2:?path to a .omwsave}"; shift 2
[ "${1:-}" = "--" ] && shift
OUT="${TMPDIR:-/tmp}/mp-two"; mkdir -p "$OUT/host" "$OUT/join"
common=(--data "$HERE/mod" --data "$HERE/debug" --load-savegame "$SAVE")
"$OPENMW" "${common[@]}" --content mp.omwscripts --content autohost.omwscripts --user-data "$OUT/host" "$@" > "$OUT/host.log" 2>&1 &
HOST=$!
trap 'kill $HOST ${JOIN:-} 2>/dev/null' EXIT
sleep 25   # let the host load and start listening
"$OPENMW" "${common[@]}" --content mp.omwscripts --content autojoin.omwscripts --content autoread.omwscripts --user-data "$OUT/join" "$@" > "$OUT/join.log" 2>&1 &
JOIN=$!
echo "host log: $OUT/host.log   join log: $OUT/join.log"
wait
