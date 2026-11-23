package org.alpha3.launcher.tools

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What the Morrowind.ini import does to the fallback= lines of the config it merges into. */
class IniFallbackTest {
    private val cfgText = "resources=r\nfallback=Water_Map_Alpha,0.4\nfallback=Weather_Sunrise_Time,6\n"

    private fun import(ini: String): List<String> {
        val dir = File.createTempFile("ini", "").apply { delete(); mkdirs() }
        try {
            File(dir, "Morrowind.ini").writeBytes(ini.toByteArray(Charsets.ISO_8859_1))
            File(dir, "Data Files").mkdirs()
            val cfg = File(dir, "openmw.cfg").apply { writeText(cfgText) }
            assertEquals(0, IniImportRunner.run(dir.path, cfg.path, importContent = false, importArchives = false))
            return cfg.readLines().filter { it.startsWith("fallback=") }
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun placeholderIniKeepsTheConfigsFallbacks() {
        // what the iOS folder picker writes when only "Data Files" was picked and no Morrowind.ini could be copied
        val placeholder = "; Made by Alpha3: only the Data Files folder was selected.\r\n[General]\r\n"
        assertEquals(listOf("fallback=Water_Map_Alpha,0.4", "fallback=Weather_Sunrise_Time,6"), import(placeholder))
    }

    @Test
    fun realIniReplacesThem() {
        val fallbacks = import("[General]\r\n[Water]\r\nMap Alpha=0.5\r\n")
        assertEquals(listOf("fallback=Water_Map_Alpha,0.5"), fallbacks)
        assertTrue(fallbacks.none { it.startsWith("fallback=Weather") })
    }
}
