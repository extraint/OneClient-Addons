package org.polyfrost.polymedia.core

import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Runs `ffmpeg` and feeds it frames on a background thread. The game thread only ever does a
 * non-blocking [offer]; if ffmpeg falls behind, frames are dropped instead of stalling the game.
 */
class FrameEncoder private constructor(
    private val process: Process,
    val width: Int,
    val height: Int,
    queueSize: Int,
) {
    private val queue = ArrayBlockingQueue<Frame>(queueSize)
    private val closing = AtomicBoolean(false)
    private val stderrTail = ArrayDeque<String>()
    val dropped = AtomicLong()
    val written = AtomicLong()

    private val writer = Thread(::writeLoop, "PolyMedia-encoder").apply { isDaemon = true }

    val isAlive: Boolean get() = process.isAlive && !closing.get()

    private fun writeLoop() {
        val out: OutputStream = process.outputStream.buffered(1 shl 20)
        var scaled: ByteArray? = null
        try {
            while (true) {
                val f = queue.poll(100, TimeUnit.MILLISECONDS)
                if (f == null) {
                    if (closing.get()) break else continue
                }
                try {
                    if (f.width == width && f.height == height) {
                        out.write(f.data, 0, width * height * 4)
                    } else {
                        val buf = scaled ?: ByteArray(width * height * 4).also { scaled = it }
                        FrameScaler.nearest(f.data, f.width, f.height, buf, width, height)
                        out.write(buf, 0, buf.size)
                    }
                    written.incrementAndGet()
                } finally {
                    f.release()
                }
            }
            out.flush()
        } catch (_: java.io.IOException) {
            // ffmpeg exited (e.g. device vanished); isAlive turns false and callers tidy up.
        } finally {
            runCatching { out.close() }
            queue.forEach { it.release() }
            queue.clear()
        }
    }

    /** Non-blocking. Returns false (and counts a drop) if the encoder can't keep up. */
    fun offer(frame: Frame): Boolean {
        if (!isAlive) return false
        frame.retain()
        if (queue.offer(frame)) return true
        frame.release()
        dropped.incrementAndGet()
        return false
    }

    /** Finishes the queue, closes ffmpeg's stdin so it finalizes files, and waits for it. */
    fun stop(timeoutSecs: Long = 15) {
        closing.set(true)
        writer.join(TimeUnit.SECONDS.toMillis(timeoutSecs))
        if (!process.waitFor(timeoutSecs, TimeUnit.SECONDS)) {
            process.destroy()
            if (!process.waitFor(3, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }

    fun lastError(): String = synchronized(stderrTail) { stderrTail.joinToString("\n") }

    companion object {
        /** Starts ffmpeg; throws [FfmpegException] if it can't start or dies immediately (bad device, etc.). */
        fun start(binary: String, args: List<String>, width: Int, height: Int, queueSize: Int = 4): FrameEncoder {
            val pb = ProcessBuilder(listOf(binary, "-hide_banner", "-loglevel", "error") + args)
                .redirectErrorStream(true)
            val p = try {
                pb.start()
            } catch (e: java.io.IOException) {
                throw FfmpegException(
                    "Couldn't run ffmpeg ('$binary'). Install it (macOS: 'brew install ffmpeg'), then either restart the game " +
                        "or paste the output of 'which ffmpeg' into PolyMedia > Capture > ffmpeg path.",
                )
            }
            val enc = FrameEncoder(p, width, height, queueSize)
            Thread({
                p.inputStream.bufferedReader().forEachLine { line ->
                    synchronized(enc.stderrTail) {
                        if (enc.stderrTail.size >= 12) enc.stderrTail.removeFirst()
                        enc.stderrTail.addLast(line)
                    }
                }
            }, "PolyMedia-ffmpeg-log").apply { isDaemon = true }.start()

            if (p.waitFor(400, TimeUnit.MILLISECONDS)) {
                throw FfmpegException("ffmpeg exited immediately: ${enc.lastError().ifEmpty { "exit code ${p.exitValue()}" }}")
            }
            enc.writer.start()
            return enc
        }
    }
}
