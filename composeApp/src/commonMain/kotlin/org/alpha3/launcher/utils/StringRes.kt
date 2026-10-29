package org.alpha3.launcher.utils

import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.getString

/** Non-composable string lookup, for state holders and callbacks that cannot call stringResource(). */
fun stringRes(res: StringResource): String = runBlocking { getString(res) }

/** Same with format arguments (%1$s style placeholders). */
fun stringRes(res: StringResource, vararg args: Any): String = runBlocking { getString(res, *args) }
