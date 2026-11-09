package org.alpha3.launcher.cs

import org.alpha3.launcher.cs.save.SaveGame
import org.alpha3.launcher.cs.save.SKILLS
import org.alpha3.launcher.tools.openmw.AndroidToolFs
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Needs SAVE_TEST=<an .omwsave>: unchanged it writes back byte for byte, and edits read back from the new file. */
class SaveGameTest {
    private fun source() = System.getenv("SAVE_TEST")?.let(::File)?.takeIf { it.isFile }

    @Test
    fun unchangedIsIdentical() {
        val src = source() ?: return
        val save = SaveGame.open(AndroidToolFs, src.path)
        val out = File.createTempFile("same", ".omwsave").also { it.delete() }
        save.writeTo(out.path)
        assertContentEquals(src.readBytes(), out.readBytes())
        out.delete()
    }

    @Test
    fun editsReadBack() {
        val src = source() ?: return
        val save = SaveGame.open(AndroidToolFs, src.path)
        println("player=${save.playerName} level=${save.level} gold=${save.gold} items=${save.items.size} globals=${save.globals.size} quests=${save.quests.size}")
        println("attrs=" + (0 until 8).map { save.attribute(it)?.base } + " dyn=" + (0 until 3).map { save.dynamic(it)?.let { d -> d.base to d.current } })
        println("skills=" + SKILLS.indices.map { save.skill(it)?.base } + " bounty=${save.bounty} rep=${save.reputation}")
        println("factions=" + save.factions.joinToString { "${it.id}:${it.rank}/${it.reputation}" })
        println("equipped=" + save.items.filter { it.equipped }.joinToString { it.id })
        assertTrue(save.canEditPlayer && save.canEditInventory)

        val removeAt = save.items.indexOfFirst { !it.equipped && !it.id.equals("gold_001", true) }
        val removed = save.items[removeAt].id
        val equippedBefore = save.items.filter { it.equipped }.map { it.id }
        save.setGold(123456)
        save.setAttribute(0, 99f)
        save.setSkill(10, 100f)
        save.setDynamic(0, 900f, 850f)
        save.level = 60
        save.bounty = 500
        save.removeItem(removeAt)
        save.addItem("misc_dwrv_coin00", 7)
        save.setGlobal(save.globals.first { it.type != 'f' }, 3f)
        save.setQuest(save.quests.first(), 100, true)
        save.description = "edited by test"

        val out = File.createTempFile("edited", ".omwsave").also { it.delete() }
        save.writeTo(out.path)
        val back = SaveGame.open(AndroidToolFs, out.path)
        assertEquals(123456, back.gold)
        assertEquals(99f, back.attribute(0)?.base)
        assertEquals(100f, back.skill(10)?.base)
        assertEquals(900f, back.dynamic(0)?.base)
        assertEquals(850f, back.dynamic(0)?.current)
        assertEquals(60, back.level)
        assertEquals(500, back.bounty)
        assertEquals("edited by test", back.description)
        assertTrue(back.items.none { it.id == removed } || save.items.count { it.id == removed } > 0)
        assertEquals(7, back.items.first { it.id == "misc_dwrv_coin00" }.count)
        assertEquals(equippedBefore.sorted(), back.items.filter { it.equipped }.map { it.id }.sorted())
        assertEquals(100, back.quests.first().stage)
        println("edited ok: ${out.length()} bytes")
        out.delete()
    }

    @Test
    fun emptyInventoryAndNewQuest() {
        val src = source() ?: return
        val save = SaveGame.open(AndroidToolFs, src.path)
        while (save.items.isNotEmpty()) save.removeItem(save.items.lastIndex)
        assertTrue(save.canEditInventory)
        save.addItem("gold_001", 250)
        save.startQuest("alpha3_test_quest", 20)
        val out = File.createTempFile("empty", ".omwsave").also { it.delete() }
        save.writeTo(out.path)
        val back = SaveGame.open(AndroidToolFs, out.path)
        assertEquals(1, back.items.size)
        assertEquals(250, back.gold)
        assertTrue(back.items.none { it.equipped })
        assertEquals(20, back.quests.first { it.id == "alpha3_test_quest" }.stage)
        println("empty inventory + new quest ok")
        out.delete()
    }
}
