# OpenMW multiplayer (a Lua mod)

Everything here is a mod. The engine only offers generic pieces: TCP sockets (`openmw.net`), animation head/spine tilt
(`animation.setHeadPitch`), map markers (`ui.setMapMarkers`) and a main-menu button hook. The networking itself (client,
server, JSON, internet-address lookup), the relay, parties, trade, chat, avatars and all UI are Lua.

## Try it
1. **Build** this branch as usual. The mod is built into the engine on desktop (`-DOPENMW_BUILTIN_MULTIPLAYER=ON`, the
   default; phone builds package it themselves and turn it off), so there is nothing to add to `openmw.cfg`: the
   **Multiplayer** button on the main menu is all you need. It does nothing until you start a game from that screen.
   For a dedicated server add `-DBUILD_MP_SERVER=ON` and build the target `openmw-mp-server`.
2. **Host:** main menu -> Multiplayer -> *Host a game* (port, optional password), then start or load a character. Your game
   shows the address friends need (the port must be forwarded on your router), or put the dedicated server on a machine
   with an open port. **Join:** Multiplayer -> *Join a server* (address, port, password).
3. **Two players on one machine:** `multiplayer/run-two.sh <openmw> <save.omwsave>`. **One player:** *Test: load a save with
   a test player* on the multiplayer screen gives you a fake player to try invites, chat, trades and the party panel with.
4. **Please report** what breaks together with the log (`openmw.log`; for the server its console output) and what you did.
   Not tested in a real game yet: shared fights, projectiles and spell effects of other players, healing a party member,
   trading made-up items and reconnecting - those are the interesting ones.

Without `OPENMW_BUILTIN_MULTIPLAYER` (or with another engine) the mod can still be added the old way: put
`data="<repo>/multiplayer/mod"` and `content=mp.omwscripts` in `openmw.cfg`. Do not do both: the scripts would load twice.

```
mod/mp.omwscripts        what the mod loads (the build appends it to builtin.omwscripts)
mod/l10n/Multiplayer/    the words of the settings page
mod/scripts/mp/
  netlib.lua             client + server + JSON + public-address lookup on top of openmw.net sockets
  relay.lua              the server logic that runs inside the host's game
  bot.lua                a fake player living inside the relay (for testing the UI with one game)
  global.lua             the client: sync, avatars, parties, commands, time/weather
  player.lua             UI: chat, party frames, invites, avatar menu; drives hud.lua and observe.lua
  hud.lua                nameplates, floating numbers, target frame, connection line
  observe.lua            what others see of us: animations, readied spell, visible magic, damage/healing/skills
  interp.lua             timed playback of other players' positions
  menu.lua               main-menu screen (host/join, port, password, test player) and settings renderers
  remote.lua             runs on avatars/stand-ins: movement, attacks, head tilt, death pose, reading pose
  shots.lua              arrows, thrown weapons and spell bolts of other players (visuals, glow, sounds, bursts)
  proxies.lua            stand-ins for party members' actors, shared kills, no duplicate NPCs
  items.lua              describes, keys and rebuilds items made while playing (enchanted gear, potions, scrolls)
  trade.lua, damage.lua  trade window, damage/kill delivery to real actors
server/server.lua        what the dedicated server runs (apps/mpserver loads it)
test/server_test.py      starts the real dedicated server (terminal and, offscreen, the window) and plays clients against it
test/relay_test.lua      runs the relay logic without the game:  luajit multiplayer/test/relay_test.lua
test/items_test.lua      the same for item descriptions:  luajit multiplayer/test/items_test.lua
run-two.sh               two real instances on one machine (host + joiner)
```

## Sharing a build with testers
`cmake --build . --target client-package` (after the build) makes two zips in the build folder, with every OpenMW program that was
built (the game, launcher, wizard, OpenMW-CS and the tools), their resources with this mod built in, a README and start scripts:
- `openmw-multiplayer-client-<system>-<cpu>-portable.zip`: the libraries they need are included (about 150 MB). Nothing else to
  install.
- `...-light.zip`: without the libraries (about 40 MB); its README lists the packages to install.

The rest of this section is about Linux (Windows is below). A package built straight from your own build tree needs a C library as new as yours, so for something
you hand to others on different distributions, build it in the container instead:

    multiplayer/container/build-client-package.sh [source-dir] [output-dir]

This builds everything (static OpenSceneGraph/MyGUI/Bullet, own SDL2, as upstream's CI does) on Ubuntu 24.04 in Docker and
packages it there. The result needs glibc 2.38 or newer (Ubuntu 24.04+, Debian 13+, Fedora 40+, current Arch and so on) and
carries its own libstdc++, Qt, FFmpeg, OpenAL, SDL2 and the rest. From the system it expects only what any graphical desktop
has: the GPU driver's OpenGL/EGL, X11 or Wayland, libxkbcommon, D-Bus. Tested on clean Ubuntu 24.04, Debian 13 and Fedora 44.
Older distributions (Ubuntu 22.04, Debian 12) are not covered. The script behind it is `multiplayer/package-client.py`
(`python3 multiplayer/package-client.py --build-dir <build>` does the same by hand). A dedicated server is a separate zip:
`--target mpserver-bundle`.

A single-file version: `multiplayer/make-appimage.sh <portable zip> [output.AppImage]` wraps the portable zip into an AppImage
(downloads `appimagetool` if needed). Running it starts the launcher; `./OpenMW-Multiplayer-x86_64.AppImage openmw-cs` (or
`openmw`, `openmw-wizard`, `esmtool`, ...) starts another program. It has the same system requirements as the zip. Make it executable
(`chmod +x`) before the first run. If the system has no FUSE, run it with `--appimage-extract-and-run`.

### Windows
`cmake --build . --config Release --target client-package` makes `openmw-multiplayer-client-Windows-x86_64.zip` (about 65 MB):
every program, the DLLs, the Qt and OpenSceneGraph plugins, `resources/` with this mod built in, the Visual C++ runtime DLLs
(from Visual Studio's redistributable folder, so a clean machine needs nothing), `LICENSE.txt` and a README. The script behind it
is `multiplayer/package-client-windows.py` (needs Python 3). `--target mpserver-bundle` makes the server zip as on Linux.

How the build was made (Visual Studio 2022 or its Build Tools with the C++ workload, CMake, Ninja, 7-Zip, Git Bash):

    # from Git Bash, with vswhere, 7z, cmake and ninja on PATH; the build folder is created inside the source tree
    mkdir MSVC2022_64_Ninja && cd MSVC2022_64_Ninja
    bash ../CI/before_script.msvc.sh -c Release -p Win64 -v 2022 -k -V -N     # downloads the prebuilt dependencies, configures
    source ./activate_msvc.sh
    cmake -DBUILD_MP_SERVER=ON .
    cmake --build . --config Release

The sockets use standalone Asio (header-only), which CMake downloads at configure time (`cmake/Asio.cmake`, pinned and checked by its hash; `-DOPENMW_USE_SYSTEM_ASIO=ON` uses an installed one), so no extra Boost is needed. The zip was tested by unpacking it into a
fresh folder and running `openmw.exe`, `esmtool.exe`, `bsatool.exe` and `openmw-navmeshtool.exe` with only `C:\Windows` on the PATH,
and by checking that the built-in script list holds the mod. It was not tested on a machine without Visual Studio, and the game
itself, the launcher window and the Multiplayer button were not started.

## Hosting and joining
Main menu → **Multiplayer**: choose **Join a server** (address, port, password) or **Host a game** (port, optional
password), then start or load a character. Hosting starts with your game: the relay runs inside it and you play through a
loopback connection, so host and joiner share the same client code. You are connected to exactly one host at a time and
while hosting you cannot join another server; to switch, return to the main menu and load again.

When hosting the game looks up your internet address (one request to `api.ipify.org`) and shows it in chat, in the status
line and on *Options → Scripts → Multiplayer*, together with your LAN address. Friends outside your network need that address
and the TCP port forwarded on your router to your computer. Joiners also get a list of servers they joined before.

Reconnecting: if your connection drops (or goes silent), the host keeps your name, party and place for 90 seconds. The game
reconnects by itself and gives the token it was handed at joining; you get your old id and party back and the others see
"is back". After 90 seconds the place is given up. Joining again under the same name in the meantime gets "(2)".

The host's relay checks what it passes on: messages have to fit their type (finite positions, bounded lists, sane damage), are
rate limited per type (positions, shots, rosters...), and a trade is only forwarded between two players who agreed to it
(request, accept, then deals) - an outsider or a stray message cannot inject into one.

Safety (hosted games and servers): protocol version check (both sides must run the same mod), optional password, player limit (default 64, at most 128; a game hosted from inside OpenMW counts the host as one of them; parties hold 3), unique
names (a second "Alice" becomes "Alice (2)"), a message of the day, and a 10 second limit to say who you are.

## Dedicated server
`openmw-mp-server` runs the same relay as a hosted game, on its own, with nobody playing on it: put it on a machine with an
open TCP port and everybody uses **Join a server**. Nobody has to forward a port except whoever runs the server.

```
cmake -DBUILD_MP_SERVER=ON ... && cmake --build . --target openmw-mp-server   # the window needs Qt6 Widgets; -DBUILD_MP_SERVER_GUI=OFF leaves it out
openmw-mp-server --mod multiplayer/mod --port 25565 --password secret --admin-password hunter2 --motd "Welcome"
```
By default it opens a **window**: the players (name, latency, address, location, status) with Kick, Ban, Mute/Unmute and
Copy address buttons for the selected one, the sky (weather box and time of day), an announcement box, the banned names with
an Unban button, the log, and a command line for everything else (Help lists the commands). Closing the window asks first if
players are connected. `--cli-only` runs it in the terminal instead, and so does a machine with no display (a VPS) by itself.
The build copies the whole `multiplayer/` folder into the build folder (`<build>/multiplayer`); the server finds it next to its
program and the engine's built-in scripts are copied from it, so a build folder does not need the source tree. `cmake --build .
--target mpserver-bundle` makes `openmw-mp-server-<system>-<cpu>.zip` with the program, that folder, start scripts and a short
README, ready to unpack on a server (use `-DBUILD_MP_SERVER_GUI=OFF` for a bundle that needs nothing but the C++ runtime).
`--help` lists the rest (player limit, weather, how fast time runs...). The passwords can also come from `MP_PASSWORD` and
`MP_ADMIN_PASSWORD`. The program embeds LuaJIT, the same Lua as the game and the engine's own sockets (`components/net`), and
loads `relay.lua` unchanged, so a server behaves exactly like a hosted game (resume tokens, rate limits, trade checks).

- **Sky:** the server owns time of day and weather. Time advances on its own clock (`--time-scale`, `--start-hour`), the
  weather changes by itself every ~15 minutes (or stays fixed with `--weather rain`) and applies wherever each player is.
  The date is never synced: everybody keeps their own day.
- **Bans** are kept in `bans.txt` in the folder the server runs in (one name a line; delete a line to unban), so they
  outlive a restart.
- **Admins:** players type `/login <admin password>`; admins can `/kick /ban /unban /mute /unmute /announce`, `/who`
  (name, latency, address and location of everyone), `/weather [id|cycle]` and `/time <hour>`.
- **Console:** type into the server's terminal: `players` (or `who`), `kick|ban|unban|mute|unmute <name>`,
  `announce <text>`, `weather [id|cycle]`, `time <hour>`, `quit`. Latency is measured by the server's pings; the address is
  where the player connects from; the location is their cell (or exterior grid square) and position.
- Addresses go only to admins and the console, never into the player list everybody receives.
- **Latency:** the server relays a message the moment it arrives (it waits on its sockets, it does not poll on a timer),
  well under a millisecond on the machine itself; positions, animations and shots go only to the players who can see the
  sender, the others get a position once a second for their map.
- **Log:** the console prints a timestamped line for connections (with the address), joins, refusals (wrong password,
  version, full, banned), leaving, losing connection and coming back, global chat (not party chat, and whispers only as who
  wrote to whom), parties, trades, admin logins and commands, weather changes, and once a minute how many messages a player
  had thrown away for flooding or being malformed. Type `help` for the commands.

## Client mods kept on the server
A server (or a game that hosts) can hand out its own client Lua mods, so every player runs the same scripts without
installing anything by hand.

- **On the server:** put the mods in a folder, one subfolder per mod: `clientmods/<name>/...`, with a `<name>.omwscripts` file
  at the top of each (the same file you would list as `content=` in `openmw.cfg`), its scripts under `scripts/`, and any
  textures, sounds or translations it uses. `<name>` is lowercase letters, digits, `_` and `-`. The standalone server uses
  `multiplayer/clientmods` or `clientmods` next to the program, or the folder given with `--client-mods <dir>`. A hosted game
  hands out the `clientmods` folder of its own user data directory.
- **When a player joins:** the server sends the list of its mods with a SHA-256 hash for every file. The game compares it
  with what is installed (in `<user data>/clientmods`). **If nothing differs, nothing is asked.** Only differences count: a
  file whose hash is different (updated), a file the player does not have (new) and a file the player has but the server no
  longer has (deleted); a whole mod that the server dropped is removed too.
- **The question:** the player sees which mods change and how many files are new, updated and removed, with the download
  size, and chooses to install and restart or to decline (declining leaves the server).
- **The install:** only the files that differ are downloaded, over the session connection. The engine writes a file only if
  its SHA-256 is the announced one and its name and size are allowed (types: lua, omwscripts, yaml, txt, tga, dds, png,
  jpg, nif, kf, wav, mp3, ogg; at most 8 MB a file, 64 MB and 500 files a mod, 32 mods). Then the game restarts (Linux and
  Windows; elsewhere the player starts it again) and the new scripts load at startup. The player joins again.

The multiplayer mod's own client scripts (`multiplayer/mod/scripts`, `l10n`, `textures` and its script list `mp.omwscripts`)
  are always handed out too, as the mod `multiplayer`. The game keeps `mp.omwscripts` as a built-in list of its own, and the
  synced copy replaces it, so you can change scripts, add them to the list, remove them or change their kind on the server and
  the players' game follows without a new build.

A player trusts the server's owner with this: the scripts run in their game like any mod. They are only ever asked first.

## Playing together
Every player keeps their **own** world; nothing a group member does changes yours, except the shared things below.
- **Presence:** everyone appears as a stand-in NPC (look and gear) when you share a cell, with a **nameplate** (name,
  level, health bar) that shows through walls but not through magic (invisible or mostly chameleon players have none,
  except for their party), and a marker on the map, the minimap and (outdoors) the global map. **Orange is your group, green
  is everyone else**, for the nameplate and the marker alike. Point your crosshair at someone for a **target frame** (level,
  race, class, health, latency). `/markers` and `/arrows` (or the settings page) switch the map markers and the nameplates
  on and off. Activate someone for a menu: invite to party, trade, ...
- **Movement:** their position messages carry the time they were taken and are played back a little behind (about one
  message apart plus the jitter of the connection, typically 50-80 ms), on a smooth curve between two messages
  (`interp.lua`), so a late message changes nothing on screen and a stop, a turn or a jump happens where it did. The avatar
  walks with their velocity plus a pull toward where they are (its legs move as theirs, at their speed), a small gap is
  pulled in a little every frame and only a big one is jumped. With the engine hook `animation.setMovementOverride` (the
  multiplayer patch) the avatar moves at exactly that velocity, its legs paced to match; without it the walk animation
  moves it at about that speed. Standing still, an avatar that cannot get any closer (something in the way) stands
  instead of walking on the spot. 30 messages a second while moving, two a second standing still; a change of stance,
  sneaking, a jump or a menu goes at once.
- **Animation:** every animation their engine plays that is not walking or idling is played on the avatar exactly as it
  was played (the same keys, speed, start point and bone priorities; `observe.lua` hears it through
  `I.AnimationController`), at the moment the shown position reaches it: attacks with their wind-up and charge, spell casts
  of each kind (self, touch, target), bows, crossbows and throwing, hits taken, knock-downs. Plus head and body tilt
  following their aim, a death pose, and a "reading a book" pose while they have a menu open (their game is paused).
  Attacks are animation only: an avatar never damages anything in your world.
- **Magic you can see:** a readied spell lights the avatar's hands and arms in the spell's colour (a fireball in hand is
  fire), as the engine does for a caster. A cast shows its cast effects and sound on the avatar, a spell on themselves its
  hit effect. Spell bolts fly with their model, glow in the spell's colour and hum their sound, and burst where they really
  hit in the caster's world (the caster sends the impact point; the area burst is as big as the spell's area). Lasting magic
  on them shows: shields, sanctuary, reflect and the like loop their effect, and light, chameleon and invisibility are put on
  the avatar for real, so it glows, fades or vanishes. Arrows and thrown weapons fly as visuals too.
- **Numbers over heads:** damage a player takes (red), healing (green) and skills that rise (gold, and a line in party
  chat) float up over them for everybody who can see them.
- **Parties (max 3):** shared party chat, vitals panel (with their poison, diseases and other harmful effects, so a healer
  knows what to cure; cast a helpful spell such as a heal or cure at a member's avatar and it takes effect on them), and stand-ins for actors that are alive in a member's world but dead
  or missing in yours (one per actor, never next to a living twin). **Kills are shared** (setting, on by default): if a member
  kills an NPC or creature it dies for everybody in the group (essential NPCs excepted). **Loot is shared** (setting, on by
  default): what a member takes from a container or a corpse is taken from your copy of it too, so the group does not loot
  the same chest twice. Only ordinary loot: gold, ingredients, potions, lockpicks, probes, repair tools and ammunition
  (a second setting adds plain weapons and armor). Anything with a script or an enchantment, keys, books and every other
  kind of item is never touched, so nothing a quest needs can vanish from your world. It only ever removes.
- **Other worlds:** players outside your group share nothing of this, and an NPC you kill stays dead for you. But after it
  has been gone for five minutes, a copy from another player's world (where it is alive) can stand in for it, labelled
  "[Name's world]" (setting *Show characters that are alive in other players' worlds*). Hitting or killing it changes only your
  world; the owner never hears of it. At most eight at a time, only for players in your cell.
- **Time and weather:** the host's clock and the weather in the host's region.
- **Chat:** Enter or Y opens the input (the game keeps running); Tab switches Global/Party.
- **Party frames:** one per member: name, level, how far, health/magicka/fatigue bars with their numbers, and every effect
  on them as icons with the seconds left (bordered by kind: poison green, disease brown, burning/freezing/shock/bleeding
  orange, other harm red, good blue), with what ails them named underneath for a healer.
- **Idle camera:** in multiplayer the camera stays still while you stand idle (no slow turn around your character);
  Settings > Multiplayer > Camera turns the usual one back on. Single-player games are not affected.
- **Players window:** F2 or `/players`: everyone online with level, where they are and latency; whisper, invite, trade
  and (party) go to from there.

### Commands
`/players`, `/markers [on|off]`, `/arrows [on|off]`, `/me text`, `/w name text`, `/r text`, `/g text`, `/p text`, `/goto name`, `/ip` (look up your address again),
`/accept [name]`, `/decline [name]`, `/leave`, `/pkick name` (party leader).
Host only: `/kick name`, `/ban name`, `/unban name`, `/mute name`, `/unmute name`, `/announce text`.

### Testing
`run-two.sh <openmw> <save.omwsave>` starts a host and a joiner (own user-data dirs, logs in `$TMPDIR/mp-two/`). The
**Test: load a save with a test player** button on the multiplayer screen (or **Add a test player** in a running hosted game)
puts a fake player next to you that accepts invites, chats, trades and shows vitals. `debug/` has helper scripts for the
automated runs (auto-host, auto-join with a walking character, auto-bot, kill test).

## Not done
Stand-ins for actors that are alive but unloaded in your world. Trades of items made while playing
(enchanted weapons/armor/clothing, your own potions and scrolls) rebuild them from a description; traded items always arrive
with full condition and charge. Shared fights are approximate: damage to an actor alive in several worlds is synced as "lowest health wins"
(setting *Damage to actors is shared*), so simultaneous hits by two players are not added up. Projectiles do not collide with
anything in your world (they fly to where they ended in the shooter's world).
