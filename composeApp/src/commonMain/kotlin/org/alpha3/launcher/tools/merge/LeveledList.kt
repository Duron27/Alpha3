// SPDX-License-Identifier: GPL-3.0-or-later
// Kotlin port of the levelled list handling in DeltaPlugin (https://gitlab.com/portmod/delta-plugin).
package org.alpha3.launcher.tools.merge

enum class ListKind(val tag: String, val entryTag: String) {
    Items("LEVI", "INAM"),
    Creatures("LEVC", "CNAM"),
}

internal data class ItemKey(val id: String, val level: Int)

/** Changes to one list relative to the version in its masters. */
internal class ListDelta(
    /** Count to add per entry; null deletes the entry. */
    val items: LinkedHashMap<ItemKey, Int?>,
    val each: Boolean?,
    val allLevels: Boolean?,
    val noneChance: Int?,
)

/**
 * A LEVI or LEVC record. [items] maps (item, player level) to how many times the entry appears,
 * which is what lets plugins add and remove entries independently of each other.
 */
internal class LeveledList(
    val kind: ListKind,
    val id: String,
    val items: LinkedHashMap<ItemKey, Int> = LinkedHashMap(),
    var each: Boolean = false,
    var allLevels: Boolean = false,
    /** Percent chance that nothing is chosen. */
    var noneChance: Int = 0,
) : Mergeable {
    override val recordTag get() = kind.tag
    override val recordId get() = id
    override fun copyValue(): Mergeable = copy()
    override fun sameAs(o: Mergeable) = o is LeveledList && sameAs(o)
    override fun diffTo(newer: Mergeable): Any = diff(newer as LeveledList)
    override fun applyDelta(d: Any) = apply(d as ListDelta)

    fun copy() = LeveledList(kind, id, LinkedHashMap(items), each, allLevels, noneChance)

    /** Order-insensitive equality, like DeltaPlugin's IndexMap comparison. */
    fun sameAs(o: LeveledList) =
        kind == o.kind && items == o.items && each == o.each && allLevels == o.allLevels && noneChance == o.noneChance

    /** The delta that turns this (master) version into [new]. */
    fun diff(new: LeveledList): ListDelta {
        val d = LinkedHashMap<ItemKey, Int?>()
        for ((k, v) in new.items) {
            val old = items[k]
            if (old == null) d[k] = v else if (v != old) d[k] = v - old
        }
        for (k in items.keys) if (k !in new.items) d[k] = null
        return ListDelta(
            d,
            if (each != new.each) new.each else null,
            if (allLevels != new.allLevels) new.allLevels else null,
            if (noneChance != new.noneChance) new.noneChance else null,
        )
    }

    fun apply(d: ListDelta) {
        for ((k, v) in d.items) {
            if (v != null) {
                items[k] = (items[k] ?: 0) + v
            } else if (items.remove(k) == null) {
                throw Conflict("cannot remove ${k.id} (level ${k.level}) from $id: not in the list")
            }
        }
        d.each?.let { each = it }
        d.allLevels?.let { allLevels = it }
        d.noneChance?.let { noneChance = it }
    }
}
