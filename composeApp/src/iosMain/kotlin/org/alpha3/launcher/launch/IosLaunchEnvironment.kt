@file:OptIn(ExperimentalForeignApi::class)

package org.alpha3.launcher.launch

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.alpha3.launcher.paths.Profiles
import org.alpha3.launcher.utils.GameFilesPreferences
import org.alpha3.launcher.utils.dataStore
import platform.posix.getenv
import platform.posix.setenv
import platform.posix.unsetenv

/**
 * Puts the Launch options environment (the launcher's baseline plus the user's switched on entries, as on Android)
 * into this process before the engine starts. Called from Swift (OpenMWLauncher.launch) on the main thread.
 */
fun applyLaunchEnvironment() {
    val env = runBlocking { LaunchEnvironment.effective() }
    for ((name, value) in env) setenv(name, value, 1)
    println("Launch environment: ${env.entries.joinToString(" ") { "${it.key}=${it.value}" }}")
    // The launcher's renderer (read by the engine's window, OSG and the support library): native Metal unless another
    // was chosen (none chosen yet is Metal, the first of IosPlatformFeatures.glRenderers); ANGLE on Metal, or Apple's
    // OpenGL ES. OSG_USE_ANGLE or OSG_USE_METAL in the Launch options wins over it, as on Android.
    if (getenv("OSG_USE_ANGLE") == null && getenv("OSG_USE_METAL") == null) {
        when (runBlocking { dataStore.data.first()[GameFilesPreferences.GL_RENDERER_KEY] } ?: "metal") {
            "metal" -> setenv("OSG_USE_METAL", "1", 1)
            "angle" -> setenv("OSG_USE_ANGLE", "1", 1)
        }
    }
    // Launcher switch: frame generation (the Metal renderer's), and frames shown per rendered one (2x to 4x) as the
    // generated frames between two rendered ones, as Android's EngineActivity sets them
    val prefs = runBlocking { dataStore.data.first() }
    if (prefs[GameFilesPreferences.FRAME_GENERATION_KEY] == true) {
        setenv("OPENMW_FRAMEGEN", "1", 1)
        val multiplier = prefs[GameFilesPreferences.FRAME_GENERATION_MULTIPLIER_KEY] ?: 2
        setenv("OPENMW_FRAMEGEN_FRAMES", (multiplier.coerceIn(2, 4) - 1).toString(), 1)
    } else {
        // (the engine runs in this process again on the next launch: what the last one set must go)
        unsetenv("OPENMW_FRAMEGEN")
        unsetenv("OPENMW_FRAMEGEN_FRAMES")
    }
}

/** The engine's command line options chosen in the Launch options dialog, as Android passes them. Values stay whole. */
fun launchArguments(): List<String> {
    // The default profile is the engine's own user config; another one is a config folder of its own
    val profile = if (Profiles.isDefault) emptyList() else Profiles.engineArguments()
    return profile + runBlocking { LaunchArguments.effective() }
}
