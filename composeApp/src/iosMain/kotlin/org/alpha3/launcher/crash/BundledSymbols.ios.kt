package org.alpha3.launcher.crash

import org.alpha3.launcher.utils.PlatformContext

// No native symbol tables are bundled on iOS yet
actual fun readBundledSymbols(context: PlatformContext, buildId: String): String? = null
