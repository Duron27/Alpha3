#ifndef OPENMW_COMPONENTS_MISC_CLIENTMODS_H
#define OPENMW_COMPONENTS_MISC_CLIENTMODS_H

#include <cstdint>
#include <filesystem>
#include <optional>
#include <string>
#include <string_view>
#include <vector>

namespace Misc::ClientMods
{
    // Client mods are folders of Lua scripts (and the textures, sounds and translations they use) that a server hands
    // out. They live in <root>/<id>/..., each folder is added to the game's data directories at startup and its
    // *.omwscripts files are loaded like the built-in ones. Everything here is checked: names, file types and sizes are
    // limited, and a file is only written when its SHA-256 matches the one it was announced with.
    constexpr std::size_t sMaxFileSize = 8 * 1024 * 1024;
    constexpr std::size_t sMaxModSize = 64 * 1024 * 1024;
    constexpr std::size_t sMaxFilesPerMod = 500;
    constexpr std::size_t sMaxMods = 32;

    struct File
    {
        std::string mPath; // relative to the mod folder, '/' separated
        std::uint64_t mSize = 0;
        std::string mHash; // SHA-256, lowercase hex
    };

    struct Mod
    {
        std::string mId;
        std::vector<File> mFiles;
    };

    // A server can also hand out a folder it already has as one more mod (the multiplayer mod's own client scripts, so
    // they are not stored twice): only its scripts, l10n and textures folders are offered, plus the script list
    // scriptList (a file in the folder, e.g. mp.omwscripts). A game that has the same file name built in as a list of
    // its own (the multiplayer mod's mp.omwscripts) uses the synced one instead, so scripts can be added, removed and
    // changed. A folder <root>/<id> wins.
    void setExtraMod(const std::string& id, const std::filesystem::path& dir, const std::string& scriptList);

    // The folder everything lives in. Set once at startup; empty means the feature is off.
    void setRoot(const std::filesystem::path& root);
    const std::filesystem::path& root();

    // [a-z0-9_-], 1 to 40 characters
    bool validId(std::string_view id);
    // A relative path of at most 6 parts made of letters, digits and . _ - (no "..", no hidden files), ending in a file
    // type a client mod may carry
    bool validPath(std::string_view path);

    // What is installed (or, for a server, what it hands out): every mod with its files and their hashes.
    std::vector<Mod> installed();

    // installed() as JSON text: [{"id":"x","files":[{"p":"scripts/a.lua","s":12,"h":"<sha256>"}]}]
    std::string manifestJson();

    // Reads one installed file. Empty if there is no such file.
    std::optional<std::string> read(std::string_view id, std::string_view path);

    // Writes a file after checking the name, the size limits and that its SHA-256 equals expectedHash. Returns an error
    // message, or an empty string on success.
    std::string write(std::string_view id, std::string_view path, std::string_view data, std::string_view expectedHash);

    // Deletes one file of a mod, or the whole mod if path is empty.
    std::string remove(std::string_view id, std::string_view path);

    // base64 (standard alphabet, padded), for carrying binary files over a line based connection
    std::string base64Encode(std::string_view data);
    std::optional<std::string> base64Decode(std::string_view text);

    // The data directories and *.omwscripts files of the installed mods, for the engine to load at startup.
    void startupEntries(std::vector<std::filesystem::path>& dataDirs, std::vector<std::string>& scriptFiles);

    // Whether this file of this mod was there when the game started, so the engine already knows it and reloading the Lua
    // scripts reads its new contents (a file it did not know needs a restart).
    bool wasLoaded(std::string_view id, std::string_view path);

    // Asks the program to start itself again once it has shut down (see relaunch).
    void requestRestart();
    bool restartRequested();
    // Whether relaunch can work on this platform
    bool canRelaunch();
    // Starts the program again with the same arguments. Returns false where that is not possible.
    bool relaunch(int argc, char** argv);
}

#endif
