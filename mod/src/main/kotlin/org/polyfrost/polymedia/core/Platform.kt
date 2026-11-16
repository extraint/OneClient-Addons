package org.polyfrost.polymedia.core

import java.io.File

object Platform {
    /** Where GUI-launched apps commonly can't see ffmpeg on macOS (Homebrew isn't on their PATH). */
    private val MAC_CANDIDATES = listOf("/opt/homebrew/bin/ffmpeg", "/usr/local/bin/ffmpeg", "/opt/local/bin/ffmpeg")

    /**
     * Resolves the ffmpeg to run. A custom path is used as-is; a bare "ffmpeg" falls back to the usual
     * macOS install locations when it can't be found, because Launchpad-started Java has a minimal PATH.
     */
    fun resolveFfmpeg(configured: String, os: Os = Os.current(), exists: (String) -> Boolean = { File(it).canExecute() }): String {
        val c = configured.trim().ifEmpty { "ffmpeg" }
        if (c != "ffmpeg" || os != Os.MAC) return c
        return MAC_CANDIDATES.firstOrNull(exists) ?: c
    }

    /**
     * macOS has no built-in loopback device, so game audio can't work until the user sets one up.
     * Rather than failing the whole recording, drop it and say so. Returns the usable options plus a warning.
     */
    fun usableAudio(audio: AudioOptions, s: CaptureSettings, os: Os = Os.current()): Pair<AudioOptions, String?> =
        if (os == Os.MAC && audio.game && s.gameAudioDevice.isBlank())
            audio.copy(game = false) to
                "No game-audio device set on macOS (needs a loopback like BlackHole), so recording without game audio."
        else audio to null
}
