package org.alpha3.launcher.cs.ui

/** A plain blocking lock, so the cell's objects can be placed on several threads around one shared cache. */
internal expect class CacheLock() {
    fun <T> locked(block: () -> T): T
}
