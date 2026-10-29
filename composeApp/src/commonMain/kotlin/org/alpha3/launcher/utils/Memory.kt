package org.alpha3.launcher.utils

/** Bytes the app can still allocate before it runs out of memory (the Java heap on Android). */
expect fun freeMemoryBytes(): Long

/** The most memory the app can ever use. */
expect fun maxMemoryBytes(): Long
