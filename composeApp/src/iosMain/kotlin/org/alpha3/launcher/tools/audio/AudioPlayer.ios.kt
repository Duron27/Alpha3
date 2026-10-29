package org.alpha3.launcher.tools.audio

internal actual class AudioPlayer actual constructor() {
    actual fun play(path: String, onDone: () -> Unit): String? = "Playing sounds is not available on iOS yet"
    actual fun stop() {}
    actual val positionMs: Int get() = 0
    actual val durationMs: Int get() = 0
    actual fun release() {}
}
