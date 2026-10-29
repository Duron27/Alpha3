# Multiplayer TODO

## Client Lua mods kept on the server
The client Lua mods live in a folder next to the server (`clientmods/<id>/...`, each with an `<id>.omwscripts` file). When a
client joins, the server tells it which mods and files (with SHA-256 hashes) it hands out; the client compares them with
what it has installed in its own `clientmods` folder, and if they differ it asks the player (a confirmation popup),
downloads what is missing or changed over the session connection, checks every file against its hash, removes what the
server no longer has, and restarts the game so the new scripts are loaded. No pack manager, no signing.

Engine side (generic, small): `openmw.modstore` (list/read/write/remove with hash, size and file-type checks, restart),
the `clientmods` folders added to the data directories and their `*.omwscripts` loaded at startup, a relaunch after
shutdown (Linux and Windows; elsewhere the player starts the game again).

To decide: what a player who declines gets (now: they cannot join that server), a way to mark a server's mods as optional.

## Parked / known gaps
- Loot sharing and stand-ins for other worlds are in but only tested with faked engine objects: try them in a real game
  (a chest and a corpse looted by a party member; an NPC killed, then five minutes later its other-world copy).
- Shared loot ignores containers that have no stable id (made at runtime) and the edges of exterior cells (only the cell the
  player stands in is watched).
- NAT traversal for hosted games: UPnP port mapping and/or a public relay.
- State is broadcast to everyone, so cost grows with the square of the player count (127 players max, ~30 comfortable on a
  small VPS): skip positions of players in other cells.
- The server forgets bans and settings on restart; passwords cross the network in plain text.
- Not yet tried in a real game: shared fights, projectiles and spell effects of others, healing a party member, trading made-up
  items, reconnecting. Also: the engine with the mod built in (`OPENMW_BUILTIN_MULTIPLAYER`).
