package org.alpha3.launcher.cs.ui

import org.alpha3.launcher.cs.model.CsDocument
import org.alpha3.launcher.cs.model.CsRecord
import org.alpha3.launcher.cs.model.RecordState
import org.alpha3.launcher.cs.model.zstringOf
import org.alpha3.launcher.tools.openmw.Sub
import org.alpha3.launcher.tools.render.GuardActor.Companion.NpcPart

/*
 * How the game dresses an NPC (OpenMW's NpcAnimation, simplified): the race's skin body parts for the NPC's sex, its
 * own head and hair, and over them the body parts of the clothing and armour it carries (the first item of each
 * equipment slot, as auto-equip would mostly pick). Every part goes on a "part reference" slot; the highest priority
 * one on a slot is shown.
 */

/** The skeleton node each part reference slot (ESM PartReferenceType, 0 to 26) hangs on. */
private val PART_BONE = arrayOf(
    "Head", "Head", "Neck", "Chest", "Groin", "Groin", "Right Hand", "Left Hand", "Right Wrist", "Left Wrist",
    "Shield Bone", "Right Forearm", "Left Forearm", "Right Upper Arm", "Left Upper Arm", "Right Foot", "Left Foot",
    "Right Ankle", "Left Ankle", "Right Knee", "Left Knee", "Right Upper Leg", "Left Upper Leg", "Right Clavicle",
    "Left Clavicle", "Weapon Bone", "Tail",
)

private const val PRT_HEAD = 0
private const val PRT_HAIR = 1
private const val PRT_SHIELD = 10
private const val PRT_WEAPON = 25

/** The slots a race skin body part (BODY BYDT mesh part 2 to 14) covers; head and hair come from the NPC. */
private val SKIN_SLOTS = mapOf(
    2 to intArrayOf(2), 3 to intArrayOf(3), 4 to intArrayOf(4), 5 to intArrayOf(6, 7), 6 to intArrayOf(8, 9),
    7 to intArrayOf(11, 12), 8 to intArrayOf(13, 14), 9 to intArrayOf(15, 16), 10 to intArrayOf(17, 18),
    11 to intArrayOf(19, 20), 12 to intArrayOf(21, 22), 13 to intArrayOf(23, 24), 14 to intArrayOf(26),
)

private fun i32(d: ByteArray, o: Int) =
    (d[o].toInt() and 255) or ((d[o + 1].toInt() and 255) shl 8) or ((d[o + 2].toInt() and 255) shl 16) or (d[o + 3].toInt() shl 24)

private fun f32(d: ByteArray, o: Int) = Float.fromBits(i32(d, o))

private fun List<Sub>.z(tag: String) = firstOrNull { it.tag == tag }?.let { zstringOf(it.data).trim() }?.takeIf { it.isNotEmpty() }

private fun meshPath(model: String) =
    model.replace('\\', '/').trimStart('/').let { if (it.startsWith("meshes/", true)) it else "meshes/$it" }

/** The race skin body parts of every race, from the BODY records (not vampire ones), read once. */
internal class RaceBodies(doc: CsDocument) {
    private val skin = HashMap<String, String>()

    init {
        for (row in doc.rows("BODY")) {
            if (row.state == RecordState.Deleted) continue
            val subs = runCatching { doc.recordOf(row).subs }.getOrNull() ?: continue
            val bydt = subs.firstOrNull { it.tag == "BYDT" }?.data?.takeIf { it.size >= 4 } ?: continue
            val part = bydt[0].toInt() and 255
            val vampire = bydt[1].toInt() != 0
            val female = bydt[2].toInt() and 1 != 0
            val type = bydt[3].toInt() and 255
            if (type != 0 || vampire || part !in SKIN_SLOTS) continue
            val race = subs.z("FNAM")?.lowercase() ?: continue
            val model = subs.z("MODL") ?: continue
            skin.putIfAbsent("$race|$part|$female", meshPath(model))
        }
    }

    /** The race's model for skin [part], the male one where the race has none for women (as the game does). */
    fun skin(race: String, part: Int, female: Boolean): String? =
        skin["${race.lowercase()}|$part|$female"] ?: skin["${race.lowercase()}|$part|false"]
}

/** What [loadNpc][org.alpha3.launcher.tools.render.GuardActor.Companion.loadNpc] needs for an NPC, and its race's size. */
internal class NpcBuild(
    /** Skeleton candidates, the first that is in the data folders used, each with its animation files (overriding first). */
    val skeletons: List<Pair<String, List<String>>>,
    val parts: List<NpcPart>,
    /** The race's weight (across) and height (up) for the NPC's sex. */
    val scaleXY: Float,
    val scaleZ: Float,
    /** Its AI Wander idle chances for Idle2 to Idle9 (AI_W), how often it fidgets with each; all 0 without wander. */
    val idleChances: IntArray = IntArray(8),
)

/** The parts [npc] is drawn with. */
internal fun npcBuild(doc: CsDocument, bodies: RaceBodies, npc: CsRecord): NpcBuild {
    val subs = npc.subs
    val female = subs.firstOrNull { it.tag == "FLAG" }?.data?.takeIf { it.size >= 4 }?.let { i32(it, 0) and 1 != 0 } ?: false
    val raceId = subs.z("RNAM") ?: ""
    val race = runCatching { doc.find("RACE", raceId) }.getOrNull()
    val radt = race?.subs?.firstOrNull { it.tag == "RADT" }?.data?.takeIf { it.size >= 140 }
    val beast = radt?.let { i32(it, 136) and 2 != 0 } ?: false
    val height = radt?.let { f32(it, if (female) 124 else 120) }?.takeIf { it in 0.5f..2f } ?: 1f
    val weight = radt?.let { f32(it, if (female) 132 else 128) }?.takeIf { it in 0.5f..2f } ?: 1f

    // slot -> (priority, model)
    val shown = arrayOfNulls<Pair<Int, String>>(PART_BONE.size)
    fun put(slot: Int, priority: Int, model: String) {
        if (slot !in shown.indices) return
        val cur = shown[slot]
        if (cur == null || priority > cur.first) shown[slot] = priority to model
    }
    fun bodyModel(id: String?) = id?.let { runCatching { doc.find("BODY", it) }.getOrNull() }?.subs?.z("MODL")?.let(::meshPath)

    for ((part, slots) in SKIN_SLOTS) bodies.skin(raceId, part, female)?.let { m -> for (s in slots) put(s, 0, m) }
    bodyModel(subs.z("BNAM"))?.let { put(PRT_HEAD, 0, it) }
    bodyModel(subs.z("KNAM"))?.let { put(PRT_HAIR, 0, it) }

    // what it wears: the first item of each equipment slot, in inventory order
    val taken = HashSet<String>()
    val extras = ArrayList<NpcPart>()  // a helmet goes over the head, not in its place
    var helmet = false
    for (item in subs.filter { it.tag == "NPCO" && it.data.size >= 36 }) {
        val id = zstringOf(item.data.copyOfRange(4, 36)).trim()
        val rec = runCatching { doc.find("ARMO", id) ?: doc.find("CLOT", id) }.getOrNull() ?: continue
        val data = rec.subs.firstOrNull { it.tag == if (rec.tag == "ARMO") "AODT" else "CTDT" }?.data?.takeIf { it.size >= 4 } ?: continue
        val type = i32(data, 0)
        val slot = (if (rec.tag == "ARMO") when (type) {
            0 -> "helmet"; 1 -> "cuirass"; 2 -> "lpauldron"; 3 -> "rpauldron"; 4 -> "greaves"; 5 -> "boots"
            6, 9 -> "lhand"; 7, 10 -> "rhand"; else -> null  // 8 shield: only out with a weapon drawn
        } else when (type) {
            0 -> "pants"; 1 -> "boots"; 2 -> "shirt"; 3 -> "belt"; 4 -> "robe"; 5 -> "rhand"; 6 -> "lhand"; 7 -> "skirt"
            else -> null  // rings and amulets have no body parts
        }) ?: continue
        if (beast && (slot == "boots" || slot == "helmet")) continue  // beast races can't wear them
        if (!taken.add(slot)) continue
        val priority = when (slot) { "robe" -> 11; "skirt" -> 3; else -> 1 }
        // INDX (slot) followed by BNAM (male part) and CNAM (female part)
        var index = -1; var male: String? = null; var fem: String? = null
        fun flush() {
            if (index < 0) return
            val model = bodyModel(if (female) fem ?: male else male) ?: return
            if (index == PRT_HEAD) { extras += NpcPart(model, PART_BONE[PRT_HEAD]); helmet = true }
            else if (index != PRT_SHIELD && index != PRT_WEAPON) put(index, priority, model)
        }
        for (s in rec.subs) when (s.tag) {
            "INDX" -> { flush(); index = s.data.firstOrNull()?.toInt()?.and(255) ?: -1; male = null; fem = null }
            "BNAM" -> male = zstringOf(s.data).trim().takeIf { it.isNotEmpty() }
            "CNAM" -> fem = zstringOf(s.data).trim().takeIf { it.isNotEmpty() }
        }
        flush()
    }
    if (helmet) shown[PRT_HAIR] = null

    val parts = ArrayList<NpcPart>()
    for (slot in shown.indices) shown[slot]?.let { parts += NpcPart(it.second, PART_BONE[slot]) }
    parts += extras

    // as OpenMW stacks them: the base animations, the beast or female ones over them
    val base = "meshes/xbase_anim.kf"
    val skeletons = buildList {
        if (beast) add("meshes/xbase_animkna.nif" to listOf("meshes/xbase_animkna.kf", base))
        if (female && !beast) add("meshes/xbase_anim_female.nif" to listOf("meshes/xbase_anim_female.kf", base))
        add("meshes/xbase_anim.nif" to listOf(base))
    }
    // AI_W: distance, duration (shorts), time of day (byte), then the 8 idle chances
    val idles = subs.firstOrNull { it.tag == "AI_W" }?.data?.takeIf { it.size >= 13 }
        ?.let { d -> IntArray(8) { d[5 + it].toInt() and 255 } } ?: IntArray(8)
    return NpcBuild(skeletons, parts, weight, height, idles)
}
