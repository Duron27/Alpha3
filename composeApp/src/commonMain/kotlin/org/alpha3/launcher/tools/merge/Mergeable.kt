// SPDX-License-Identifier: GPL-3.0-or-later
// Modelled on the plugin merge in DeltaPlugin (https://gitlab.com/portmod/delta-plugin).
package org.alpha3.launcher.tools.merge

internal class Conflict(message: String) : Exception(message)

/**
 * A record that can be reduced to what one plugin changed relative to its masters and have those
 * changes replayed in load order. Deltas are opaque to the engine: only the record that made one
 * can apply it.
 */
internal interface Mergeable {
    val recordTag: String
    val recordId: String
    fun copyValue(): Mergeable
    fun sameAs(o: Mergeable): Boolean

    /** The delta that turns this (master) version into [newer]. */
    fun diffTo(newer: Mergeable): Any

    /** Applies a delta produced by [diffTo]; throws [Conflict] if it does not fit this version. */
    fun applyDelta(d: Any)
}

/** What one plugin says about one record, once compared with its masters. */
internal sealed class Entry {
    class Full(val value: Mergeable) : Entry()
    class Delta(val delta: Any) : Entry()
    object Delete : Entry()
}

internal fun mergeKey(tag: String, id: String) = "$tag::${id.lowercase()}"
