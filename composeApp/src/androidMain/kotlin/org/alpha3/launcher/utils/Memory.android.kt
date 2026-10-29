package org.alpha3.launcher.utils

actual fun maxMemoryBytes(): Long = Runtime.getRuntime().maxMemory()

actual fun freeMemoryBytes(): Long {
    val rt = Runtime.getRuntime()
    return rt.maxMemory() - (rt.totalMemory() - rt.freeMemory())
}
