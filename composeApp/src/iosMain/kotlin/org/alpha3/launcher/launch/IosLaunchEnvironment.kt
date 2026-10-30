@file:OptIn(ExperimentalForeignApi::class)

package org.alpha3.launcher.launch

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.alpha3.launcher.utils.GameFilesPreferences
import org.alpha3.launcher.utils.dataStore
import platform.posix.getenv
import platform.posix.setenv

/**
 * Puts the Launch options environment (the launcher's baseline plus the user's switched on entries, as on Android)
 * into this process before the engine starts. Called from Swift (OpenMWLauncher.launch) on the main thread.
 */
fun applyLaunchEnvironment() {
    val env = runBlocking { LaunchEnvironment.effective() }
    for ((name, value) in env) setenv(name, value, 1)
    println("Launch environment: ${env.entries.joinToString(" ") { "${it.key}=${it.value}" }}")
    // The launcher's renderer: ANGLE on Metal (read by the engine's window, OSG and the support library). OSG_USE_ANGLE
    // in the Launch options wins over it, as on Android.
    if (getenv("OSG_USE_ANGLE") == null &&
        runBlocking { dataStore.data.first()[GameFilesPreferences.GL_RENDERER_KEY] } == "angle"
    ) setenv("OSG_USE_ANGLE", "1", 1)
}

/** The engine's command line options chosen in the Launch options dialog, as Android passes them. Values stay whole. */
fun launchArguments(): List<String> = runBlocking { LaunchArguments.effective() }
