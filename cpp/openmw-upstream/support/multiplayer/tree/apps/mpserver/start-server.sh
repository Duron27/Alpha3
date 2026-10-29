#!/bin/sh
# Starts the OpenMW multiplayer server from this folder. Anything you add is passed on: ./start-server.sh --help
cd "$(dirname "$0")" || exit 1
exec ./openmw-mp-server "$@"
