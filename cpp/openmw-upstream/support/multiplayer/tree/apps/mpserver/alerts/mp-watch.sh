#!/bin/bash
# Discord alerts for the OpenMW multiplayer server under systemd (set up by install-alerts.sh).
#
#   mp-watch.sh check     every few minutes (openmw-mp-watch.timer): can a player connect, new script errors,
#                         the daily heartbeat
#   mp-watch.sh stopped   the server's ExecStopPost: tells when the server stopped other than by a normal stop
#   mp-watch.sh test      sends a test message
#
# Settings come from the environment (/etc/openmw-mp-server/alerts.env):
#   DISCORD_WEBHOOK_URL   the channel's webhook (Edit Channel > Integrations > Webhooks)
#   MP_SERVICE            the server's systemd unit, e.g. openmw-mp-server.service
#   MP_PORT               the port it listens on (25565)
#   HEARTBEAT_HOUR        the hour (0-23, server time) of the daily "still running" message; empty for none
#   STATE_DIR             where the watcher remembers what it last saw (/var/lib/openmw-mp-watch)

MP_PORT=${MP_PORT:-25565}
MP_SERVICE=${MP_SERVICE:-openmw-mp-server.service}
HEARTBEAT_HOUR=${HEARTBEAT_HOUR-12}
STATE_DIR=${STATE_DIR:-/var/lib/openmw-mp-watch}
HOST=$(hostname)

# Posts $1 (a title) and optional details to the webhook. Discord takes at most 2000 characters a message.
send() {
    local title=$1 details=$2 text
    if [ -z "$DISCORD_WEBHOOK_URL" ]; then
        echo "mp-watch: no DISCORD_WEBHOOK_URL set: $title" >&2
        return 1
    fi
    text="**[$HOST] $title**"
    if [ -n "$details" ]; then
        text+=$'\n```\n'"$(printf '%s' "$details" | tail -c 1700)"$'\n```'
    fi
    local json
    json=$(printf '%s' "$text" | awk 'BEGIN { ORS = "" } {
        gsub(/\\/, "\\\\"); gsub(/"/, "\\\""); gsub(/\t/, "\\t"); gsub(/\r/, "")
        if (NR > 1) print "\\n"
        print
    }')
    curl -fsS -m 15 -H 'Content-Type: application/json' -d "{\"content\":\"$json\"}" "$DISCORD_WEBHOOK_URL" >/dev/null ||
        echo "mp-watch: could not post to Discord: $title" >&2
}

journal() { journalctl -u "$MP_SERVICE" --no-pager -o cat "$@" 2>/dev/null; }

# ok: a player can connect (the server sends its first line at once); refused: nothing listens on the port;
# closed: the connection is dropped at once (no one can join); timeout: accepted but the server does not answer (stuck)
probe() {
    local line status
    if ! { exec 3<>"/dev/tcp/127.0.0.1/$MP_PORT"; } 2>/dev/null; then
        echo refused
        return
    fi
    if IFS= read -r -t 8 line <&3; then
        echo ok
    else
        status=$?
        if [ -n "$line" ]; then echo ok
        elif [ "$status" -gt 128 ]; then echo timeout
        else echo closed
        fi
    fi
    exec 3<&- 3>&-
}

describe() {
    case $1 in
        refused) echo "nothing is listening on port $MP_PORT" ;;
        closed) echo "connections to port $MP_PORT are dropped at once: nobody can join" ;;
        timeout) echo "port $MP_PORT accepts but the server never answers: it looks stuck" ;;
        inactive) echo "the service $MP_SERVICE is not running" ;;
        ok) echo "port $MP_PORT answers" ;;
        *) echo "$1" ;;
    esac
}

players() { ss -Htn state established "( sport = :$MP_PORT )" 2>/dev/null | wc -l; }

check() {
    mkdir -p "$STATE_DIR"
    local state="$STATE_DIR/state" now last_check=0 last_status=ok failures=0 last_heartbeat=""
    # shellcheck disable=SC1090
    [ -f "$state" ] && . "$state"
    now=$(date +%s)

    # 1. can a player get in? (two failed checks in a row before an alert, so a restart does not cause one)
    local status
    if systemctl is-active --quiet "$MP_SERVICE"; then status=$(probe); else status=inactive; fi
    if [ "$status" = ok ]; then
        if [ "$last_status" != ok ] && [ "$failures" -ge 2 ]; then
            send "Server is reachable again" "port $MP_PORT answers; $(players) connected"
        fi
        failures=0
        last_status=ok
    else
        failures=$((failures + 1))
        if [ "$failures" -eq 2 ]; then
            send "Players cannot join: $(describe "$status")" "$(journal -n 15)"
        fi
        last_status=$status
    fi

    # 2. script errors since the last check (one message for all of them)
    if [ "$last_check" -gt 0 ]; then
        local errors count
        errors=$(journal --since "@$last_check" | grep -F '[server] error in')
        if [ -n "$errors" ]; then
            count=$(printf '%s\n' "$errors" | wc -l)
            send "$count script error(s) in the last $(((now - last_check + 30) / 60)) minutes" "$(printf '%s\n' "$errors" | head -n 8)"
        fi
    fi

    # 3. the daily heartbeat, so silence means the server or the alerts are down
    local today
    today=$(date +%F)
    if [ -n "$HEARTBEAT_HOUR" ] && [ "$(date +%-H)" -ge "$HEARTBEAT_HOUR" ] && [ "$last_heartbeat" != "$today" ]; then
        local since connections dayerrors
        since=$(systemctl show -p ActiveEnterTimestamp --value "$MP_SERVICE")
        connections=$(journal --since "$since" | grep -c 'connection [0-9]* from')
        dayerrors=$(journal --since "-24h" | grep -cF '[server] error in')
        send "Daily check: $(if [ "$last_status" = ok ]; then echo running; else describe "$last_status"; fi)" \
            "running since $since
players connected now: $(players)
connections since start: $connections
script errors in the last 24 h: $dayerrors"
        last_heartbeat=$today
    fi

    printf 'last_check=%s\nlast_status=%s\nfailures=%s\nlast_heartbeat=%s\n' \
        "$now" "$last_status" "$failures" "$last_heartbeat" >"$state"
}

# ExecStopPost: systemd tells how the server ended in SERVICE_RESULT, EXIT_CODE and EXIT_STATUS
stopped() {
    if [ "$SERVICE_RESULT" = success ]; then
        return 0 # stopped normally (systemctl stop/restart, or quit)
    fi
    send "Server stopped: ${SERVICE_RESULT:-unknown} (${EXIT_CODE:-?} ${EXIT_STATUS:-?})" "$(journal -n 25)"
}

case $1 in
    check) check ;;
    stopped) stopped ;;
    test) send "Test message from the multiplayer server's alerts" "service $MP_SERVICE, port $MP_PORT: $(describe "$(probe)")" ;;
    *)
        echo "usage: $0 check|stopped|test" >&2
        exit 2
        ;;
esac
