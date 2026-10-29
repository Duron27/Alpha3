package org.alpha3.launcher.utils

import platform.Foundation.NSProcessInfo

actual fun availableCpuCores(): Int = NSProcessInfo.processInfo.activeProcessorCount.toInt().coerceAtLeast(1)
