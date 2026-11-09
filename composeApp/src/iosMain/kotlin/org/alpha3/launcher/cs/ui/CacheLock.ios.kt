package org.alpha3.launcher.cs.ui

import platform.Foundation.NSRecursiveLock

internal actual class CacheLock actual constructor() {
    private val lock = NSRecursiveLock()
    actual fun <T> locked(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }
}
