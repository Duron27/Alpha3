package org.alpha3.launcher.cs

import org.alpha3.launcher.cs.model.RECORD_DEFS
import org.alpha3.launcher.cs.model.pluginBytes
import org.alpha3.launcher.cs.model.readField
import org.alpha3.launcher.cs.model.readPlugin
import org.alpha3.launcher.cs.model.slots
import org.alpha3.launcher.cs.model.writeField
import org.alpha3.launcher.tools.openmw.OkioToolFs
import org.alpha3.launcher.tools.openmw.TextCodec
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Opens real plugins and checks the Construction Set saves them back unchanged: the file as a whole, and every field
 * read and written again. The plugins come from the folder in CS_TEST_PLUGINS (skipped when it is not set).
 */
class RoundTripTest {
    private val dir = System.getenv("CS_TEST_PLUGINS")?.let(::File)

    private fun plugins(): List<File> =
        dir?.listFiles { f -> f.extension.lowercase() in setOf("esm", "esp", "omwaddon") }?.sortedBy { it.name } ?: emptyList()

    @Test
    fun filesSaveUnchanged() {
        val fs = OkioToolFs()
        for (f in plugins()) {
            val plugin = readPlugin(fs, f.path)
            val bytes = pluginBytes(plugin)
            assertTrue(bytes.contentEquals(f.readBytes()), "${f.name}: saved bytes differ")
            println("${f.name}: ${plugin.records.size} records, saved unchanged")
        }
    }

    @Test
    fun fieldsRewriteUnchanged() {
        val fs = OkioToolFs()
        val codec = TextCodec.forName("win1252")
        for (f in plugins()) {
            val plugin = readPlugin(fs, f.path)
            var fields = 0
            var raw = 0
            val bad = HashMap<String, Int>()
            for (r in listOf(plugin.header) + plugin.records) {
                val def = RECORD_DEFS[r.tag] ?: continue
                for (s in r.subs) {
                    val sd = def.sub(s.tag)
                    val sl = slots(sd, s)
                    if (sl == null) { if (sd != null && sd.layouts.isNotEmpty()) raw++; continue }
                    for (slot in sl) {
                        val v = readField(s, slot, codec)
                        val back = writeField(s, slot, v, codec)
                        fields++
                        if (!back.data.contentEquals(s.data)) bad.merge("${r.tag}.${s.tag}.${slot.def.name}", 1, Int::plus)
                    }
                }
            }
            println("${f.name}: $fields fields rewritten, $raw subrecords without a layout for their size")
            bad.entries.sortedByDescending { it.value }.take(20).forEach { println("  differs: ${it.key} x${it.value}") }
            assertEquals(emptyMap(), bad, "${f.name}: fields that do not write back as read")
        }
    }
}
