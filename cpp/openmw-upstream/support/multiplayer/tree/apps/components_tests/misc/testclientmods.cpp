#include <components/misc/clientmods.hpp>
#include <components/misc/sha256.hpp>

#include <gtest/gtest.h>

#include <filesystem>
#include <fstream>

namespace
{
    namespace CM = Misc::ClientMods;

    TEST(MiscSha256, knownValues)
    {
        EXPECT_EQ(Misc::sha256Hex(""), "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855");
        EXPECT_EQ(Misc::sha256Hex("abc"), "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad");
        EXPECT_EQ(Misc::sha256Hex("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq"),
            "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1");
        EXPECT_EQ(Misc::sha256Hex(std::string(1000000, 'a')),
            "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0");
    }

    TEST(MiscClientMods, names)
    {
        EXPECT_TRUE(CM::validId("my-mod_2"));
        EXPECT_FALSE(CM::validId(""));
        EXPECT_FALSE(CM::validId("Upper"));
        EXPECT_FALSE(CM::validId("../x"));
        EXPECT_FALSE(CM::validId(std::string(41, 'a')));

        EXPECT_TRUE(CM::validPath("scripts/mp/a.lua"));
        EXPECT_TRUE(CM::validPath("mymod.omwscripts"));
        EXPECT_TRUE(CM::validPath("textures/Icon.TGA"));
        EXPECT_FALSE(CM::validPath("../a.lua"));
        EXPECT_FALSE(CM::validPath("a/../b.lua"));
        EXPECT_FALSE(CM::validPath("/etc/a.lua"));
        EXPECT_FALSE(CM::validPath("a\\b.lua"));
        EXPECT_FALSE(CM::validPath(".hidden.lua"));
        EXPECT_FALSE(CM::validPath("a/.b/c.lua"));
        EXPECT_FALSE(CM::validPath("run.exe"));
        EXPECT_FALSE(CM::validPath("noextension"));
        EXPECT_FALSE(CM::validPath("a//b.lua"));
        EXPECT_FALSE(CM::validPath("a/b/c/d/e/f/g.lua")); // too deep
    }

    TEST(MiscClientMods, base64)
    {
        EXPECT_EQ(CM::base64Encode(""), "");
        EXPECT_EQ(CM::base64Encode("f"), "Zg==");
        EXPECT_EQ(CM::base64Encode("fo"), "Zm8=");
        EXPECT_EQ(CM::base64Encode("foo"), "Zm9v");
        EXPECT_EQ(CM::base64Encode("foobar"), "Zm9vYmFy");
        EXPECT_EQ(CM::base64Decode("Zm9vYmFy"), "foobar");
        EXPECT_EQ(CM::base64Decode("Zm8="), "fo");
        EXPECT_EQ(CM::base64Decode("Zg=="), "f");
        EXPECT_FALSE(CM::base64Decode("Zg="));
        EXPECT_FALSE(CM::base64Decode("Z!=="));
        std::string binary;
        for (int i = 0; i < 256; ++i)
            binary += static_cast<char>(i);
        EXPECT_EQ(CM::base64Decode(CM::base64Encode(binary)), binary);
    }

    TEST(MiscClientMods, writeReadRemove)
    {
        const std::filesystem::path root = std::filesystem::temp_directory_path() / "omw_clientmods_test";
        std::filesystem::remove_all(root);
        CM::setRoot(root);

        const std::string data = "print('hi')\n";
        const std::string hash = Misc::sha256Hex(data);
        EXPECT_NE(CM::write("m", "scripts/a.lua", data, "0000"), "");
        EXPECT_NE(CM::write("m", "../a.lua", data, hash), "");
        EXPECT_NE(CM::write("m", "a.exe", data, hash), "");
        EXPECT_NE(CM::write("M", "a.lua", data, hash), "");
        EXPECT_EQ(CM::write("m", "scripts/a.lua", data, hash), "");
        EXPECT_EQ(
            CM::write("m", "m.omwscripts", "GLOBAL: scripts/a.lua\n", Misc::sha256Hex("GLOBAL: scripts/a.lua\n")), "");

        EXPECT_EQ(CM::read("m", "scripts/a.lua"), data);
        EXPECT_FALSE(CM::read("m", "scripts/missing.lua"));

        const auto mods = CM::installed();
        ASSERT_EQ(mods.size(), 1u);
        EXPECT_EQ(mods[0].mId, "m");
        ASSERT_EQ(mods[0].mFiles.size(), 2u);
        EXPECT_EQ(mods[0].mFiles[1].mPath, "scripts/a.lua");
        EXPECT_EQ(mods[0].mFiles[1].mHash, hash);

        std::vector<std::filesystem::path> dirs;
        std::vector<std::string> scripts;
        CM::startupEntries(dirs, scripts);
        ASSERT_EQ(dirs.size(), 1u);
        ASSERT_EQ(scripts.size(), 1u);
        EXPECT_EQ(scripts[0], "m.omwscripts");

        EXPECT_EQ(CM::write("m", "big.lua", std::string(CM::sMaxFileSize + 1, 'x'),
                      Misc::sha256Hex(std::string(CM::sMaxFileSize + 1, 'x'))),
            "file too large");

        EXPECT_EQ(CM::remove("m", "scripts/a.lua"), "");
        EXPECT_FALSE(CM::read("m", "scripts/a.lua"));
        EXPECT_EQ(CM::remove("m", ""), "");
        EXPECT_TRUE(CM::installed().empty());
        std::filesystem::remove_all(root);
        CM::setRoot({});
    }

    TEST(MiscClientMods, extraModServedFromAFolder)
    {
        const std::filesystem::path base = std::filesystem::temp_directory_path() / "omw_clientmods_extra";
        std::filesystem::remove_all(base);
        std::filesystem::create_directories(base / "mod" / "scripts" / "mp");
        std::filesystem::create_directories(base / "mod" / "secret");
        std::filesystem::create_directories(base / "root");
        auto put = [](const std::filesystem::path& p, const std::string& text) {
            std::ofstream(p, std::ios::binary) << text;
        };
        put(base / "mod" / "scripts" / "mp" / "a.lua", "print(1)");
        put(base / "mod" / "mp.omwscripts",
            "GLOBAL: scripts/mp/a.lua\n"); // the script list: offered under its own name
        put(base / "mod" / "other.omwscripts", "GLOBAL: scripts/mp/b.lua\n"); // anything else at the top is not
        put(base / "mod" / "secret" / "s.lua", "x");
        CM::setRoot(base / "root");
        CM::setExtraMod("multiplayer", base / "mod", "mp.omwscripts");

        const auto mods = CM::installed();
        ASSERT_EQ(mods.size(), 1u);
        EXPECT_EQ(mods[0].mId, "multiplayer");
        ASSERT_EQ(mods[0].mFiles.size(), 2u);
        EXPECT_EQ(mods[0].mFiles[0].mPath, "mp.omwscripts");
        EXPECT_EQ(mods[0].mFiles[1].mPath, "scripts/mp/a.lua");
        EXPECT_EQ(mods[0].mFiles[1].mHash, Misc::sha256Hex("print(1)"));
        EXPECT_EQ(CM::read("multiplayer", "scripts/mp/a.lua"), "print(1)");
        const auto list = CM::read("multiplayer", "mp.omwscripts");
        ASSERT_TRUE(list);
        EXPECT_EQ(Misc::sha256Hex(*list), mods[0].mFiles[0].mHash);
        EXPECT_EQ(*list, "GLOBAL: scripts/mp/a.lua\n");
        EXPECT_FALSE(CM::read("multiplayer", "other.omwscripts"));
        EXPECT_FALSE(CM::read("multiplayer", "secret/s.lua"));

        // a real folder of the same name takes over
        put(base / "root" / "x", "");
        std::filesystem::remove(base / "root" / "x");
        std::filesystem::create_directories(base / "root" / "multiplayer");
        EXPECT_EQ(CM::write("multiplayer", "b.lua", "y", Misc::sha256Hex("y")), "");
        const auto own = CM::installed();
        ASSERT_EQ(own.size(), 1u);
        ASSERT_EQ(own[0].mFiles.size(), 1u);
        EXPECT_EQ(own[0].mFiles[0].mPath, "b.lua");

        std::filesystem::remove_all(base);
        CM::setExtraMod("", {}, "");
        CM::setRoot({});
    }
}
