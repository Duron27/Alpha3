#!/bin/bash
# Sets up Discord alerts for a multiplayer server that runs under systemd. Run as root on the server:
#
#   sudo ./alerts/install-alerts.sh [service name] [port]
#
# It asks for the Discord webhook (Edit Channel > Integrations > Webhooks > New Webhook > Copy Webhook URL), then:
#   - /etc/openmw-mp-server/alerts.env      the settings (readable by root only: the webhook is a secret)
#   - /usr/local/lib/openmw-mp-server/mp-watch.sh
#   - openmw-mp-watch.service and .timer    a check every 5 minutes: can a player connect, new script errors, the
#                                           daily heartbeat
#   - a drop-in for the server's unit       a message when the server stops other than by a normal stop
# and sends a test message. Run it again to change the settings.
set -e

if [ "$(id -u)" -ne 0 ]; then
    echo "Run this as root (sudo $0)" >&2
    exit 1
fi
here=$(cd "$(dirname "$0")" && pwd)
service=${1:-}
port=${2:-}

if [ -z "$service" ]; then
    echo "Multiplayer server units found:"
    systemctl list-units --type=service --all --no-legend | awk '{print $1}' | grep -iE 'mp|openmw|morrow' | sed 's/^/  /'
    read -r -p "The server's systemd unit [openmw-mp-server.service]: " service
    service=${service:-openmw-mp-server.service}
fi
[[ $service == *.service ]] || service="$service.service"
if ! systemctl cat "$service" >/dev/null 2>&1; then
    echo "There is no unit $service" >&2
    exit 1
fi
if [ -z "$port" ]; then
    guess=$(systemctl show -p ExecStart --value "$service" | grep -oE -- '--port[ =][0-9]+' | grep -oE '[0-9]+' | head -1)
    read -r -p "The port it listens on [${guess:-25565}]: " port
    port=${port:-${guess:-25565}}
fi

env=/etc/openmw-mp-server/alerts.env
old_url=$(grep -s '^DISCORD_WEBHOOK_URL=' "$env" | cut -d= -f2-)
read -r -p "Discord webhook URL${old_url:+ [keep the current one]}: " url
url=${url:-$old_url}
if [[ $url != https://discord.com/api/webhooks/* && $url != https://discordapp.com/api/webhooks/* ]]; then
    echo "That is not a Discord webhook URL (https://discord.com/api/webhooks/...)" >&2
    exit 1
fi
read -r -p "Hour of the daily heartbeat, 0-23 server time, empty for none [12]: " hour
hour=${hour-12}

command -v curl >/dev/null || { echo "curl is needed: apt install curl (or your system's equivalent)" >&2; exit 1; }

install -d -m 700 /etc/openmw-mp-server
umask 077
cat >"$env" <<EOF
DISCORD_WEBHOOK_URL=$url
MP_SERVICE=$service
MP_PORT=$port
HEARTBEAT_HOUR=${hour:-12}
STATE_DIR=/var/lib/openmw-mp-watch
EOF
[ -z "$hour" ] && sed -i 's/^HEARTBEAT_HOUR=.*/HEARTBEAT_HOUR=/' "$env"
umask 022

install -D -m 755 "$here/mp-watch.sh" /usr/local/lib/openmw-mp-server/mp-watch.sh

cat >/etc/systemd/system/openmw-mp-watch.service <<EOF
[Unit]
Description=Checks the OpenMW multiplayer server and posts problems to Discord

[Service]
Type=oneshot
EnvironmentFile=$env
ExecStart=/usr/local/lib/openmw-mp-server/mp-watch.sh check
EOF
cat >/etc/systemd/system/openmw-mp-watch.timer <<EOF
[Unit]
Description=Checks the OpenMW multiplayer server every 5 minutes

[Timer]
OnBootSec=3min
OnUnitActiveSec=5min

[Install]
WantedBy=timers.target
EOF

# The stop message runs as root ('+'), so it can read the journal and the webhook whatever user the server runs as
install -d "/etc/systemd/system/$service.d"
cat >"/etc/systemd/system/$service.d/alerts.conf" <<EOF
[Service]
ExecStopPost=+/bin/sh -c 'set -a; . $env; exec /usr/local/lib/openmw-mp-server/mp-watch.sh stopped'
EOF

systemctl daemon-reload
systemctl enable --now openmw-mp-watch.timer
set -a
# shellcheck disable=SC1090
. "$env"
set +a
/usr/local/lib/openmw-mp-server/mp-watch.sh test
echo
echo "Done: a test message went to the channel. The checks run every 5 minutes (systemctl list-timers openmw-mp-watch)."
echo "The stop message applies from the server's next start (systemctl restart $service)."
