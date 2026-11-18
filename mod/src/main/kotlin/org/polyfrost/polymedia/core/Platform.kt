package org.polyfrost.polymedia.core

import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

object Platform {
    /** Common install locations; GUI-launched Java (Launchpad, launchers) often has a minimal PATH. */
    private val CANDIDATES = listOf(
        "/opt/homebrew/bin/ffmpeg", "/opt/homebrew/opt/ffmpeg/bin/ffmpeg", "/usr/local/bin/ffmpeg",
        "/usr/local/opt/ffmpeg/bin/ffmpeg", "/opt/local/bin/ffmpeg", "/usr/bin/ffmpeg", "/snap/bin/ffmpeg",
    )

    private data class Cached(val value: String, val at: Long)
    private val cache = ConcurrentHashMap<String, Cached>()
    private const val MISS_TTL_MS = 30_000L

    internal fun clearCache() = cache.clear()

    /**
     * Resolves the ffmpeg to run:
     *  - a custom path is used as-is (`~` expanded; a folder gets `/ffmpeg` appended);
     *  - a bare "ffmpeg" is looked up in the common install locations, then via the user's login shell
     *    (which sees Homebrew's PATH), and falls back to plain "ffmpeg" (the system PATH).
     * Called every frame by the settings snapshot, so results are cached (misses only briefly).
     */
    fun resolveFfmpeg(
        configured: String,
        os: Os = Os.current(),
        exists: (String) -> Boolean = { File(it).canExecute() },
        shellLookup: () -> String? = ::loginShellLookup,
    ): String {
        var c = configured.trim().ifEmpty { "ffmpeg" }
        if (c.startsWith("~")) c = System.getProperty("user.home") + c.removePrefix("~")
        if (c != "ffmpeg") {
            val f = File(c)
            return if (f.isDirectory) File(f, "ffmpeg").path else c
        }
        if (os == Os.WINDOWS) return c

        val now = System.currentTimeMillis()
        cache["ffmpeg"]?.let { if (it.value != "ffmpeg" || now - it.at < MISS_TTL_MS) return it.value }
        val found = CANDIDATES.firstOrNull(exists) ?: shellLookup()?.takeIf { exists(it) } ?: "ffmpeg"
        cache["ffmpeg"] = Cached(found, now)
        return found
    }

    private fun loginShellLookup(): String? = try {
        val shell = System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/sh"
        val p = ProcessBuilder(shell, "-l", "-c", "command -v ffmpeg").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText().lines().lastOrNull { it.startsWith("/") }
        if (!p.waitFor(4, TimeUnit.SECONDS)) { p.destroyForcibly(); null } else out?.trim()
    } catch (_: Exception) { null }

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
