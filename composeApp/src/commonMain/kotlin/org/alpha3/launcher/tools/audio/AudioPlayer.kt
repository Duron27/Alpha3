package org.alpha3.launcher.tools.audio

/** Plays one sound file at a time (the developer tools' file list), with the platform's own decoder. */
internal expect class AudioPlayer() {
    /** Starts [path] from the beginning, stopping whatever was playing; [onDone] runs when it ends. Returns an error, or null. */
    fun play(path: String, onDone: () -> Unit): String?
    fun stop()
    val positionMs: Int
    val durationMs: Int
    fun release()
}

/** Extensions [AudioPlayer] is offered for (Morrowind's sound effects are WAV, voices and music MP3). */
internal val playableAudio = setOf("wav", "mp3", "ogg")
