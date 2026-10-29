package org.alpha3.launcher.utils

import platform.Foundation.NSProcessInfo

// iOS has no heap limit to ask for; half of the physical memory is a safe budget
actual fun maxMemoryBytes(): Long = (NSProcessInfo.processInfo.physicalMemory / 2uL).toLong()

actual fun freeMemoryBytes(): Long = maxMemoryBytes()
