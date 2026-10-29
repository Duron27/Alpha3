package org.alpha3.launcher.utils

import kotlin.time.Clock

/** Wall clock in milliseconds since the epoch (System.currentTimeMillis() replacement). */
fun currentTimeMillis(): Long = Clock.System.now().toEpochMilliseconds()
