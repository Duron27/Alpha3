package org.alpha3.launcher.tools.audio

import android.media.MediaPlayer

internal actual class AudioPlayer actual constructor() {
    private var player: MediaPlayer? = null

    actual fun play(path: String, onDone: () -> Unit): String? {
        stop()
        val p = MediaPlayer()
        return try {
            p.setDataSource(path)
            p.setOnCompletionListener { stop(); onDone() }
            p.prepare()
            p.start()
            player = p
            null
        } catch (e: Exception) {
            p.release()
            // MediaPlayer reads PCM and IMA ADPCM WAV, MP3 and Ogg Vorbis; anything else ends up here
            "Cannot play ${path.substringAfterLast('/')}: ${e.message ?: e.javaClass.simpleName}"
        }
    }

    actual fun stop() {
        player?.let { runCatching { it.stop() }; it.release() }
        player = null
    }

    actual val positionMs: Int get() = runCatching { player?.currentPosition }.getOrNull() ?: 0
    actual val durationMs: Int get() = runCatching { player?.duration }.getOrNull()?.coerceAtLeast(0) ?: 0

    actual fun release() = stop()
}
