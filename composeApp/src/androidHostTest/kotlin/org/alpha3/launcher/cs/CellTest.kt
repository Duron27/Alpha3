package org.alpha3.launcher.cs

import org.alpha3.launcher.cs.model.CsDocument
import org.alpha3.launcher.cs.model.MasterIndex
import org.alpha3.launcher.cs.model.PluginFile
import org.alpha3.launcher.cs.model.RecordState
import org.alpha3.launcher.cs.model.addRef
import org.alpha3.launcher.cs.model.cellView
import org.alpha3.launcher.cs.model.deleteRef
import org.alpha3.launcher.cs.model.joinCell
import org.alpha3.launcher.cs.model.newHeader
import org.alpha3.launcher.cs.model.readPlugin
import org.alpha3.launcher.cs.model.revertRef
import org.alpha3.launcher.cs.model.savePlugin
import org.alpha3.launcher.cs.model.setRef
import org.alpha3.launcher.cs.model.splitCell
import org.alpha3.launcher.cs.model.withPlacement
import org.alpha3.launcher.tools.openmw.OkioToolFs
import org.alpha3.launcher.tools.openmw.TextCodec
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Cell references over Morrowind.esm and Tribunal.esm (from CS_TEST_PLUGINS): split/join, merge, move, delete, add, save. */
class CellTest {
    private val dir = System.getenv("CS_TEST_PLUGINS")?.let(::File)

    @Test
    fun splitJoinIsExact() {
        val fs = OkioToolFs()
        for (f in listOf("Morrowind.esm", "Tribunal.esm", "MorrowindInteriorsProject.ESP")) {
            val file = dir?.resolve(f)?.takeIf { it.isFile } ?: continue
            var cells = 0
            for (r in readPlugin(fs, file.path).records) {
                if (r.tag != "CELL") continue
                val parts = splitCell(r.subs)
                val back = joinCell(parts.header, parts.refs)
                // NAM0 is rewritten with the count of temporary references; everything else comes back as it was
                val a = r.subs.filter { it.tag != "NAM0" }.map { it.tag to it.data.toList() }
                val b = back.filter { it.tag != "NAM0" }.map { it.tag to it.data.toList() }
                assertEquals(a, b, "$f ${r.displayId}")
                cells++
            }
            println("$f: $cells cells split and joined")
        }
    }

    @Test
    fun editCellSaveReload() {
        val mw = dir?.resolve("Morrowind.esm")?.takeIf { it.isFile } ?: return
        val tr = dir.resolve("Tribunal.esm").takeIf { it.isFile } ?: return
        val fs = OkioToolFs()
        val codec = TextCodec.forName("win1252")
        val out = File.createTempFile("cscell", ".esp").also { it.delete() }
        val masters = listOf("Morrowind.esm" to mw.length(), "Tribunal.esm" to tr.length())
        val doc = CsDocument(fs, codec, PluginFile(out.path, newHeader("t", "cells", masters, codec), ArrayList()), listOf(MasterIndex(fs, mw.path, 0), MasterIndex(fs, tr.path, 1)))

        val row = doc.rows("CELL").first { it.id == "balmora, guild of mages" }
        val before = doc.cellView(row)
        println("Guild of Mages: ${before.refs.size} references")
        assertTrue(before.refs.size > 50)
        assertTrue(before.interior)

        // move one, delete one, add one
        val moved = before.refs[5]
        val p = moved.position.copyOf().also { it[2] += 100f; it[5] = 1.5f }
        doc.setRef(row, moved, withPlacement(moved.subs, p, 1.25f))
        val gone = before.refs[6]
        doc.deleteRef(row, gone)
        val added = doc.addRef(row, "a3_new_thing", floatArrayOf(10f, 20f, 30f, 0f, 0f, 0f), persistent = false)

        val prow = doc.rows("CELL").first { it.id == "balmora, guild of mages" }
        assertEquals(RecordState.Modified, prow.state)
        val after = doc.cellView(prow)
        val m = after.refs.first { it.key == moved.key }
        assertEquals(RecordState.Modified, m.state)
        assertEquals(p.toList(), m.position.toList())
        assertEquals(1.25f, m.scale)
        assertEquals(RecordState.Deleted, after.refs.first { it.key == gone.key }.state)
        assertEquals(RecordState.New, after.refs.first { it.key == added }.state)
        assertEquals(before.refs.size + 1, after.refs.size)

        // the plugin's cell holds only the three changes
        assertEquals(3, splitCell(doc.plugin.records.single { it.tag == "CELL" }.subs).refs.size)

        // revert the move, then redo everything through undo/redo
        doc.revertRef(prow, m)
        assertEquals(RecordState.Base, doc.cellView(prow).refs.first { it.key == moved.key }.state)
        doc.undo()
        assertEquals(RecordState.Modified, doc.cellView(prow).refs.first { it.key == moved.key }.state)

        savePlugin(fs, doc.plugin)
        val again = CsDocument(fs, codec, readPlugin(fs, out.path), listOf(MasterIndex(fs, mw.path, 0), MasterIndex(fs, tr.path, 1)))
        val r2 = again.cellView(again.rows("CELL").first { it.id == "balmora, guild of mages" })
        assertEquals(p.toList(), r2.refs.first { it.key == moved.key }.position.toList())
        assertEquals(RecordState.Deleted, r2.refs.first { it.key == gone.key }.state)
        assertNotNull(r2.refs.firstOrNull { it.objectId == "a3_new_thing" })
        assertNull(r2.refs.firstOrNull { it.objectId == "a3_new_thing" && it.state != RecordState.New })
        File(System.getenv("CS_TEST_OUT") ?: return).let { out.copyTo(it, overwrite = true) }
    }
}
