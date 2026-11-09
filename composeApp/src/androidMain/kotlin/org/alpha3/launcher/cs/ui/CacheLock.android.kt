package org.alpha3.launcher.cs.ui

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

internal actual class CacheLock actual constructor() {
    private val lock = ReentrantLock()
    actual fun <T> locked(block: () -> T): T = lock.withLock(block)
}
