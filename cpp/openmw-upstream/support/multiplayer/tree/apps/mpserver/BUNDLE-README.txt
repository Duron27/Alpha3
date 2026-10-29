OpenMW multiplayer server
=========================

Everything the server needs is in this folder:

  openmw-mp-server          the program
  multiplayer/mod           the multiplayer mod (the server runs its relay)
  multiplayer/server        server.lua, what the program runs
  multiplayer/README.md     how the multiplayer works, the server's commands and settings
  start-server.sh / .bat    starts it from this folder

Start it:

  ./start-server.sh --port 25565 --password secret --admin-password hunter2
  ./start-server.sh --help          every option and every console command

Players join with "Join a server" on the multiplayer screen: your address, the port, the password. The port (TCP, 25565
unless you change it) must be open on this machine's firewall and forwarded on your router if the server is at home.
Type "help" in the server's terminal for the commands; "quit" stops it. Up to 128 players.

A window (players, kick/ban/mute buttons, the sky, a log) opens by default when there is a screen and this build has it;
add --cli-only for a plain terminal. A build with the window needs the Qt 6 libraries installed on this machine; a
terminal-only build needs nothing but the system's C++ runtime.

The mod is found by itself next to the program. To use another copy: --mod <folder holding scripts/mp>.
