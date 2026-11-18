package org.polyfrost.polymedia.core

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit
import kotlin.io.path.extension
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.fileSize

object ClipRing {
    /**
     * The newest `ceil(len/segment) + 1` segments (the extra one is the segment still being
     * written), oldest first. Ring slots wrap, so file names are not chronological: order by mtime.
     */
    fun select(entries: List<Pair<Path, FileTime>>, lengthSecs: Int): List<Path> {
        val want = (lengthSecs + FfmpegCommands.SEGMENT_SECS - 1) / FfmpegCommands.SEGMENT_SECS + 1
        return entries.sortedBy { it.second }.takeLast(want).map { it.first }
    }

    fun list(dir: Path): List<Pair<Path, FileTime>> =
        dir.listDirectoryEntries().filter { it.extension == "ts" && it.fileSize() > 0 }
            .map { it to it.getLastModifiedTime() }

    /** ffmpeg concat-demuxer list body. */
    fun listFile(segments: List<Path>): String =
        segments.joinToString("") { "file '${it.toString().replace("'", "'\\''")}'\n" }

    /** Cuts the last [lengthSecs] from [bufferDir] into [output]. Returns false if nothing is buffered. */
    fun cut(ffmpeg: String, bufferDir: Path, lengthSecs: Int, output: Path): Boolean {
        val segments = select(list(bufferDir), lengthSecs)
        if (segments.isEmpty()) return false
        Files.createDirectories(output.parent)
        val list = bufferDir.resolve("clip-${System.nanoTime()}.txt")
        Files.writeString(list, listFile(segments))
        try {
            val p = ProcessBuilder(listOf(ffmpeg, "-hide_banner", "-loglevel", "error") + FfmpegCommands.concat(list, output))
                .redirectErrorStream(true).start()
            val log = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(60, TimeUnit.SECONDS)) { p.destroyForcibly(); throw FfmpegException("ffmpeg timed out while saving the clip") }
            if (p.exitValue() != 0) throw FfmpegException("ffmpeg failed: ${log.trim()}")
        } catch (e: IOException) {
            throw FfmpegException("Couldn't run ffmpeg ('$ffmpeg'). Set its path in PolyMedia > Capture > ffmpeg path.")
        } finally {
            Files.deleteIfExists(list)
        }
        return true
    }
}
