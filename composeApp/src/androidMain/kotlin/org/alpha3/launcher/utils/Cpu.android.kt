package org.alpha3.launcher.utils

actual fun availableCpuCores(): Int = Runtime.getRuntime().availableProcessors().coerceAtLeast(1)
