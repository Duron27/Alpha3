package org.alpha3.launcher.cs

import org.alpha3.launcher.cs.model.PluginFile
import org.alpha3.launcher.cs.model.newHeader
import org.alpha3.launcher.cs.model.savePlugin
import org.alpha3.launcher.tools.openmw.OkioToolFs
import org.alpha3.launcher.tools.openmw.TextCodec
import org.alpha3.launcher.tools.openmw.ToolException
import org.alpha3.launcher.tools.openmw.isOfficialContent
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The official Morrowind files are never written, whatever their case or folder. */
class OfficialFilesTest {
    @Test
    fun officialNames() {
        assertTrue(isOfficialContent("/x/Data Files/Morrowind.esm"))
        assertTrue(isOfficialContent("TRIBUNAL.ESM"))
        assertTrue(isOfficialContent("/sdcard/Data Files/Bloodmoon.esm"))
        assertTrue(isOfficialContent("C:\\Games\\Data Files\\Siege at Firemoth.esp"))
        assertFalse(isOfficialContent("Morrowind changes.esp"))
    }

    @Test
    fun saveRefused() {
        val dir = Files.createTempDirectory("cs_official").toFile()
        val codec = TextCodec.forName("win1252")
        for (name in listOf("Morrowind.esm", "Tribunal.esm", "Bloodmoon.esm", "master_index.esp")) {
            val path = File(dir, name).path
            val plugin = PluginFile(path, newHeader("", "", emptyList(), codec), ArrayList())
            assertFailsWith<ToolException> { savePlugin(OkioToolFs(), plugin) }
            assertFalse(File(path).exists())
        }
        dir.deleteRecursively()
    }
}
