package org.alpha3.launcher.cs

import org.alpha3.launcher.cs.model.CsDocument
import org.alpha3.launcher.cs.model.MasterIndex
import org.alpha3.launcher.cs.model.PluginFile
import org.alpha3.launcher.cs.model.RECORD_DEFS
import org.alpha3.launcher.cs.model.RecordState
import org.alpha3.launcher.cs.model.newHeader
import org.alpha3.launcher.cs.model.readField
import org.alpha3.launcher.cs.model.readPlugin
import org.alpha3.launcher.cs.model.slots
import org.alpha3.launcher.cs.model.writeField
import org.alpha3.launcher.tools.openmw.OkioToolFs
import org.alpha3.launcher.tools.openmw.Sub
import org.alpha3.launcher.tools.openmw.TextCodec
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Makes a plugin over Morrowind.esm (from CS_TEST_PLUGINS): new records, an edited GMST, a deletion; saves and reads it back. */
class DocumentTest {
    private val dir = System.getenv("CS_TEST_PLUGINS")?.let(::File)

    private fun CsDocument.setField(tag: String, id: String, sub: String, field: String, value: Any) {
        val row = rows(tag).first { it.id == id }
        val rec = recordOf(row)
        val def = RECORD_DEFS.getValue(tag).sub(sub)
        val subs = rec.subs.map { s ->
            if (s.tag != sub) s else {
                val slot = slots(def, s)!!.first { it.def.name == field }
                writeField(s, slot, value, codec)
            }
        }
        edit(row, subs)
    }

    @Test
    fun createEditSave() {
        val mw = dir?.resolve("Morrowind.esm")?.takeIf { it.isFile } ?: return
        val fs = OkioToolFs()
        val codec = TextCodec.forName("win1252")
        val out = File.createTempFile("cstest", ".esp").also { it.delete() }
        val plugin = PluginFile(out.path, newHeader("tester", "a test", listOf("Morrowind.esm" to mw.length()), codec), ArrayList())
        val doc = CsDocument(fs, codec, plugin, listOf(MasterIndex(fs, mw.path, 0)))

        doc.create("WEAP", "a3_sword")
        doc.setField("WEAP", "a3_sword", "FNAM", "Name", "Alpha3 Sword")
        doc.setField("WEAP", "a3_sword", "WPDT", "Slash max", 40L)
        doc.setField("WEAP", "a3_sword", "WPDT", "Weight", 12.5f)
        doc.setField("WEAP", "a3_sword", "MODL", "Model", "w\\w_longsword.nif")
        doc.create("NPC_", "a3_guard")
        val npc = doc.rows("NPC_").first { it.id == "a3_guard" }
        val npco = Sub("NPCO", ByteArray(36).also { it[0] = 1; codec.encode("a3_sword").copyInto(it, 4) })
        doc.edit(npc, doc.recordOf(npc).subs + npco)
        doc.setField("GMST", "fjumpacrobaticsbase", "FLTV", "Float", 400f)
        val statId = doc.rows("STAT").first().id
        doc.delete(doc.rows("STAT").first())

        // undo and redo the last edit
        doc.undo()
        assertEquals(RecordState.Base, doc.rows("STAT").first { it.id == statId }.state)
        doc.redo()
        assertEquals(RecordState.Deleted, doc.rows("STAT").first { it.id == statId }.state)

        doc.save { if (it.equals("Morrowind.esm", true)) mw.length() else null }
        doc.close()

        val back = readPlugin(fs, out.path)
        assertEquals(listOf("Morrowind.esm" to mw.length()), back.masters)
        assertEquals(4, back.records.size)
        val sword = back.records.first { it.id == "a3_sword" }
        val wpdt = sword.subs.first { it.tag == "WPDT" }
        val wslots = slots(RECORD_DEFS.getValue("WEAP").sub("WPDT"), wpdt)!!
        assertEquals(40L, readField(wpdt, wslots.first { it.def.name == "Slash max" }, codec))
        assertEquals(12.5f, readField(wpdt, wslots.first { it.def.name == "Weight" }, codec))
        assertEquals("Alpha3 Sword", readField(sword.subs.first { it.tag == "FNAM" }, slots(RECORD_DEFS.getValue("WEAP").sub("FNAM"), sword.subs.first { it.tag == "FNAM" })!![0], codec))
        val gmst = back.records.first { it.tag == "GMST" }
        assertEquals(400f, Float.fromBits(gmst.subs.first { it.tag == "FLTV" }.data.let { (it[0].toInt() and 255) or ((it[1].toInt() and 255) shl 8) or ((it[2].toInt() and 255) shl 16) or ((it[3].toInt() and 255) shl 24) }))
        assertTrue(back.records.first { it.tag == "STAT" }.deleted)
        val guard = back.records.first { it.id == "a3_guard" }
        println("guard subs: " + guard.subs.joinToString { "${it.tag}(${it.data.size})" })
        println("sword subs: " + sword.subs.joinToString { "${it.tag}(${it.data.size})" })
        println("saved ${out.length()} bytes to $out")
        File(out.path).copyTo(File(dir, "../cs_out_test.esp"), overwrite = true)
    }
}
