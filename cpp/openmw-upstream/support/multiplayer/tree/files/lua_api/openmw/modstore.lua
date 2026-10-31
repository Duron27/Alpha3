---
-- `openmw.modstore` is available in global scripts. It manages the client mods folder (`<user data>/clientmods/<id>/`):
-- folders of Lua scripts and their assets that a multiplayer server hands out. At startup every mod folder that holds
-- an `.omwscripts` file is added to the data directories and its `.omwscripts` files are loaded like the built-in ones, so
-- a new file takes effect after a restart (see @{#modstore.restart}); a changed one after @{#modstore.reloadScripts}.
--
-- Everything is checked: mod names are `[a-z0-9_-]` (at most 40), file paths are relative, at most 6 parts deep, with
-- letters, digits and `. _ -` and an allowed type (lua, omwscripts, yaml, txt, tga, dds, png, jpg, nif, kf, wav, mp3,
-- ogg); a file is at most 8 MB, a mod at most 64 MB and 500 files, and at most 32 mods are kept.
-- @module modstore
-- @usage local modstore = require('openmw.modstore')

---
-- The installed mods and their files as JSON text: `[{"id":"x","files":[{"p":"scripts/a.lua","s":12,"h":"<sha256>"}]}]`
-- (`p` the path, `s` the size in bytes, `h` the SHA-256 as 64 lowercase hex digits). Decode it with `openmw.markup`.
-- @function [parent=#modstore] manifest
-- @return #string

---
-- The contents of an installed file.
-- @function [parent=#modstore] read
-- @param #string id
-- @param #string path
-- @return #string The data, or nil if there is no such file

---
-- Stores a file. It is only written if the names and sizes are allowed and the SHA-256 of `data` is `hash`.
-- @function [parent=#modstore] write
-- @param #string id
-- @param #string path
-- @param #string data
-- @param #string hash The SHA-256 the file is expected to have
-- @return #string The reason it failed, or an empty string on success

---
-- Deletes one file of a mod, or the whole mod if `path` is empty.
-- @function [parent=#modstore] remove
-- @param #string id
-- @param #string path
-- @return #string The reason it failed, or an empty string on success

---
-- @function [parent=#modstore] sha256
-- @param #string data
-- @return #string The SHA-256 of the data as 64 lowercase hex digits

---
-- @function [parent=#modstore] base64Encode
-- @param #string data
-- @return #string

---
-- @function [parent=#modstore] base64Decode
-- @param #string text
-- @return #string The data, or nil if the text is not valid base64

---
-- Whether the game loaded this file of this mod at startup. Such files are read again by @{#modstore.reloadScripts};
-- a file the game did not know needs a restart.
-- @function [parent=#modstore] wasLoaded
-- @param #string id The mod
-- @param #string path The file, inside the mod
-- @return #boolean

---
-- Reloads every Lua script at the end of the frame, reading the installed mods' files again. Scripts keep their saved
-- data, as when a game is saved and loaded.
-- @function [parent=#modstore] reloadScripts

---
-- Quits the game and starts it again with the same arguments, so that newly installed mods are loaded. Only possible on
-- Linux and Windows: elsewhere it does nothing and returns false (ask the player to start the game again).
-- @function [parent=#modstore] restart
-- @return #boolean Whether the game is going to restart

return nil
