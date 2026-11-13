package org.polyfrost.polymedia.core

import java.nio.file.Files
import java.nio.file.Path

/** PolyReplay: records gameplay to an MP4 until stopped. */
class ReplayRecorder(private val outputDir: () -> Path) {
    @Volatile private var encoder: FrameEncoder? = null
    @Volatile private var file: Path? = null

    val active: Boolean get() = encoder?.isAlive == true

    /** Starts recording at the given frame size. Throws [FfmpegException] on failure. */
    fun start(settings: CaptureSettings, audio: AudioOptions, w: Int, h: Int): Path {
        check(!active) { "already recording" }
        val dir = outputDir()
        Files.createDirectories(dir)
        val out = dir.resolve("replay-${System.currentTimeMillis() / 1000}.mp4")
        val args = FfmpegCommands.record(settings, audio, w, h, out)
        encoder = FrameEncoder.start(settings.ffmpegPath, args, w, h)
        file = out
        return out
    }

    fun offer(frame: Frame) { encoder?.offer(frame) }

    /** Stops and returns the finished file, or null if nothing was recording. */
    fun stop(): Path? {
        val e = encoder ?: return null
        encoder = null
        e.stop()
        return file.also { file = null }
    }

    fun lastError(): String = encoder?.lastError().orEmpty()
}

/** PolyClip: keeps a rolling buffer; [clip] saves the last N seconds. */
class ClipRecorder(private val bufferDir: Path, private val outputDir: () -> Path) {
    data class Params(val settings: CaptureSettings, val audio: AudioOptions, val length: ClipLength)

    @Volatile private var encoder: FrameEncoder? = null
    @Volatile var params: Params? = null
        private set

    val active: Boolean get() = encoder?.isAlive == true

    fun start(p: Params, w: Int, h: Int) {
        check(!active) { "already buffering" }
        // Drop segments from earlier sessions so they are never stitched in.
        if (Files.exists(bufferDir)) bufferDir.toFile().deleteRecursively()
        Files.createDirectories(bufferDir)
        val args = FfmpegCommands.buffer(p.settings, p.audio, w, h, bufferDir, p.length.seconds)
        encoder = FrameEncoder.start(p.settings.ffmpegPath, args, w, h)
        params = p
    }

    fun offer(frame: Frame) { encoder?.offer(frame) }

    fun stop() {
        val e = encoder ?: return
        encoder = null
        params = null
        e.stop()
        bufferDir.toFile().deleteRecursively()
    }

    /** Saves the last `length` of footage (or whatever exists). Returns null if nothing is buffered yet. */
    fun clip(): Path? {
        val p = params ?: return null
        if (!active) return null
        val out = outputDir().resolve("clip-${System.currentTimeMillis() / 1000}.mp4")
        return if (ClipRing.cut(p.settings.ffmpegPath, bufferDir, p.length.seconds, out)) out else null
    }

    fun lastError(): String = encoder?.lastError().orEmpty()
}
