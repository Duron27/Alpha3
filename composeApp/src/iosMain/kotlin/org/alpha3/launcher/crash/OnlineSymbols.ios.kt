package org.alpha3.launcher.crash

// No native symbol tables are published for iOS yet
actual fun readOnlineSymbols(buildId: String): String? = null
