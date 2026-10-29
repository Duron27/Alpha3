// SPDX-License-Identifier: GPL-3.0-or-later
// Record type names and identifiers follow DeltaPlugin (https://gitlab.com/portmod/delta-plugin).
package org.alpha3.launcher.tools.pluginquery

import org.alpha3.launcher.tools.openmw.*

private val typeNames = mapOf(
    "activator" to "ACTI", "apparatus" to "APPA", "armour" to "ARMO", "armor" to "ARMO", "birthsign" to "BSGN",
    "bodypart" to "BODY", "book" to "BOOK", "cell" to "CELL", "class" to "CLAS", "clothing" to "CLOT",
    "container" to "CONT", "creature" to "CREA", "dialogue" to "DIAL", "dialogueinfo" to "INFO", "door" to "DOOR",
    "enchantmenteffect" to "ENCH", "faction" to "FACT", "gamesetting" to "GMST", "global" to "GLOB",
    "ingredient" to "INGR", "landscape" to "LAND", "landtexture" to "LTEX", "levelledcreatures" to "LEVC",
    "levelleditems" to "LEVI", "light" to "LIGH", "lockpick" to "LOCK", "magiceffect" to "MGEF", "misc" to "MISC",
    "npc" to "NPC_", "pathgrid" to "PGRD", "potion" to "ALCH", "probe" to "PROB", "race" to "RACE",
    "region" to "REGN", "repair" to "REPA", "script" to "SCPT", "skill" to "SKIL", "sound" to "SOUN",
    "soundgenerator" to "SNDG", "spell" to "SPEL", "startup" to "SSCR", "static" to "STAT", "weapon" to "WEAP",
)
private val typeTags = typeNames.values.toSet()

/** Accepts DeltaPlugin's type names (any case) or the four-letter record tag. */
fun recordTag(name: String): String =
    typeNames[name.lowercase()] ?: name.takeIf { it.uppercase() in typeTags }?.uppercase()
        ?: throw ToolException("unknown record type \"$name\"")

internal fun typeName(tag: String): String = typeNames.entries.first { it.value == tag }.key.let { k ->
    // Display names as DeltaPlugin spells them.
    when (k) {
        "armor" -> "Armour"
        "npc" -> "NPC"
        else -> k.replaceFirstChar { it.uppercase() }
    }
}

private val skills = listOf(
    "Block", "Armorer", "MediumArmor", "HeavyArmor", "BluntWeapon", "LongBlade", "Axe", "Spear", "Athletics",
    "Enchant", "Destruction", "Alteration", "Illusion", "Conjuration", "Mysticism", "Restoration", "Alchemy",
    "Unarmored", "Security", "Sneak", "Acrobatics", "LightArmor", "ShortBlade", "Marksman", "Mercantile",
    "Speechcraft", "HandToHand",
)

private val effects = listOf(
    "WaterBreathing", "SwiftSwim", "WaterWalking", "Shield", "FireShield", "LightningShield",
    "FrostShield", "Burden", "Feather", "Jump", "Levitate", "SlowFall",
    "Lock", "Open", "FireDamage", "ShockDamage", "FrostDamage", "DrainAttribute",
    "DrainHealth", "DrainMagicka", "DrainFatigue", "DrainSkill", "DamageAttribute", "DamageHealth",
    "DamageMagicka", "DamageFatigue", "DamageSkill", "Poison", "WeaknessToFire", "WeaknessToFrost",
    "WeaknessToShock", "WeaknessToMagicka", "WeaknessToCommonDisease", "WeaknessToBlightDisease", "WeaknessToCorprusDisease", "WeaknessToPoison",
    "WeaknessToNormalWeapons", "DisintegrateWeapon", "DisintegrateArmor", "Invisibility", "Chameleon", "Light",
    "Sanctuary", "NightEye", "Charm", "Paralyze", "Silence", "Blind",
    "Sound", "CalmHumanoid", "CalmCreature", "FrenzyHumanoid", "FrenzyCreature", "DemoralizeHumanoid",
    "DemoralizeCreature", "RallyHumanoid", "RallyCreature", "Dispel", "Soultrap", "Telekinesis",
    "Mark", "Recall", "DivineIntervention", "AlmsiviIntervention", "DetectAnimal", "DetectEnchantment",
    "DetectKey", "SpellAbsorption", "Reflect", "CureCommonDisease", "CureBlightDisease", "CureCorprusDisease",
    "CurePoison", "CureParalyzation", "RestoreAttribute", "RestoreHealth", "RestoreMagicka", "RestoreFatigue",
    "RestoreSkill", "FortifyAttribute", "FortifyHealth", "FortifyMagicka", "FortifyFatigue", "FortifySkill",
    "FortifyMaximumMagicka", "AbsorbAttribute", "AbsorbHealth", "AbsorbMagicka", "AbsorbFatigue", "AbsorbSkill",
    "ResistFire", "ResistFrost", "ResistShock", "ResistMagicka", "ResistCommonDisease", "ResistBlightDisease",
    "ResistCorprusDisease", "ResistPoison", "ResistNormalWeapons", "ResistParalysis", "RemoveCurse", "TurnUndead",
    "SummonScamp", "SummonClannfear", "SummonDaedroth", "SummonDremora", "SummonAncestralGhost", "SummonSkeletalMinion",
    "SummonBonewalker", "SummonGreaterBonewalker", "SummonBonelord", "SummonWingedTwilight", "SummonHunger", "SummonGoldenSaint",
    "SummonFlameAtronach", "SummonFrostAtronach", "SummonStormAtronach", "FortifyAttack", "CommandCreature", "CommandHumanoid",
    "BoundDagger", "BoundLongsword", "BoundMace", "BoundBattleAxe", "BoundSpear", "BoundLongbow",
    "ExtraSpell", "BoundCuirass", "BoundHelm", "BoundBoots", "BoundShield", "BoundGloves",
    "Corprus", "Vampirism", "SummonCenturionSphere", "SunDamage", "StuntedMagicka", "SummonFabricant", "SummonWolf", "SummonBear", "SummonBonewolf",
)

/** [display] keeps the plugin's casing; [canonical] is what ids are matched and merged by. */
internal class Identity(val display: String, val canonical: String)

private fun first(subs: List<Sub>, tag: String) = subs.firstOrNull { it.tag == tag }?.data

/** The identifier DeltaPlugin gives a record, or null if the record has none. */
internal fun identify(tag: String, subs: List<Sub>, codec: TextCodec): Identity? {
    fun str(t: String) = first(subs, t)?.let { codec.decode(it) }
    fun id(display: String) = Identity(display, display.lowercase())
    return when (tag) {
        "CELL" -> {
            val d = first(subs, "DATA")?.takeIf { it.size == 12 } ?: return null
            if (u32(d) and 1L != 0L) str("NAME")?.let { id(it) } else id("cell::${i32(d, 4)}x${i32(d, 8)}")
        }
        "LAND" -> first(subs, "INTV")?.takeIf { it.size == 8 }?.let { id("landscape::${i32(it)}x${i32(it, 4)}") }
        "PGRD" -> {
            val d = first(subs, "DATA")?.takeIf { it.size >= 8 } ?: return null
            val name = str("NAME")
            // DeltaPlugin cannot tell interior from exterior pathgrids by the record alone; it treats a
            // grid at 0,0 as interior except for the one exterior grid named "Ashlands Region".
            if (i32(d) == 0 && i32(d, 4) == 0 && name != "Ashlands Region") name?.let { id(it) } else id("path_grid::${i32(d)}x${i32(d, 4)}")
        }
        "SKIL" -> first(subs, "INDX")?.takeIf { it.size >= 4 }?.let { id("skill::${skills.getOrNull(i32(it)) ?: i32(it)}") }
        "MGEF" -> first(subs, "INDX")?.takeIf { it.size >= 4 }?.let { id("mgef::${effects.getOrNull(i32(it)) ?: i32(it)}") }
        "SCPT" -> first(subs, "SCHD")?.takeIf { it.size >= 32 }?.let { id(codec.decode(it.copyOf(32))) }
        "INFO" -> str("INAM")?.let { id(it) }
        else -> str("NAME")?.let { id(it) }
    }
}
