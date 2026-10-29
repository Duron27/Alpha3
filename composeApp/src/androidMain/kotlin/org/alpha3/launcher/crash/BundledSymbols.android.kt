package org.alpha3.launcher.crash

import org.alpha3.launcher.utils.PlatformContext

actual fun readBundledSymbols(context: PlatformContext, buildId: String): String? =
    runCatching { context.assets.open("symbols/$buildId.syms").bufferedReader().use { it.readText() } }.getOrNull()
