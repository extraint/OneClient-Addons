package org.polyfrost.polymedia.core

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.TimeUnit
import kotlin.test.*

class CoreTest {
    private val s = CaptureSettings()
    private fun has(a: List<String>, x: String, y: String) = a.windowed(2).any { it[0] == x && it[1] == y }

    @Test fun clipLengths() {
        assertEquals(listOf(30, 60, 600), ClipLength.entries.map { it.seconds })
    }

    @Test fun videoOnlyHasNoAudio() {
        val a = FfmpegCommands.record(s, AudioOptions(false, false), 1920, 1080, Path.of("o.mp4"), Os.LINUX)
        assertTrue("-an" in a)
        assertTrue(has(a, "-video_size", "1920x1080"))
        assertTrue(has(a, "-i", "pipe:0"))
    }

    @Test fun gameAndMicAreMixed() {
        val a = FfmpegCommands.record(s, AudioOptions(true, true), 1280, 720, Path.of("o.mp4"), Os.LINUX)
        assertTrue(a.any { "amix=inputs=2" in it })
        assertTrue(has(a, "-i", "@DEFAULT_MONITOR@") && has(a, "-i", "@DEFAULT_SOURCE@"))
    }

    @Test fun singleSourceMapsDirectly() {
        val a = FfmpegCommands.record(s, AudioOptions(false, true), 1280, 720, Path.of("o.mp4"), Os.LINUX)
        assertTrue(has(a, "-map", "1:a"))
        assertFalse(a.any { "DEFAULT_MONITOR" in it })
    }

    @Test fun macUsesDefaultMicButNeedsLoopbackForGameAudio() {
        val mic = FfmpegCommands.record(s, AudioOptions(false, true), 2, 2, Path.of("o.mp4"), Os.MAC)
        assertTrue(has(mic, "-i", ":default"))
        assertFailsWith<FfmpegException> { FfmpegCommands.record(s, AudioOptions(true, false), 2, 2, Path.of("o.mp4"), Os.MAC) }
        val game = FfmpegCommands.record(s.copy(gameAudioDevice = "1"), AudioOptions(true, false), 2, 2, Path.of("o.mp4"), Os.MAC)
        assertTrue(has(game, "-i", ":1"))
    }

    @Test fun macGameAudioIsDroppedWithWarningInsteadOfFailing() {
        val (a, w) = Platform.usableAudio(AudioOptions(true, true), s, Os.MAC)
        assertEquals(AudioOptions(false, true), a); assertNotNull(w)
        assertEquals(AudioOptions(true, true) to null, Platform.usableAudio(AudioOptions(true, true), s.copy(gameAudioDevice = "2"), Os.MAC))
        assertEquals(AudioOptions(true, false) to null, Platform.usableAudio(AudioOptions(true, false), s, Os.LINUX))
    }

    @Test fun ffmpegLocatorFindsHomebrewOnMac() {
        assertEquals("/opt/homebrew/bin/ffmpeg", Platform.resolveFfmpeg("ffmpeg", Os.MAC) { it == "/opt/homebrew/bin/ffmpeg" })
        assertEquals("/usr/local/bin/ffmpeg", Platform.resolveFfmpeg("", Os.MAC) { it == "/usr/local/bin/ffmpeg" })
        assertEquals("ffmpeg", Platform.resolveFfmpeg("ffmpeg", Os.MAC) { false })
        assertEquals("/custom/ffmpeg", Platform.resolveFfmpeg("/custom/ffmpeg", Os.MAC) { false })
        assertEquals("ffmpeg", Platform.resolveFfmpeg("ffmpeg", Os.LINUX) { true })
    }

    @Test fun windowsNeedsMicDevice() {
        val os = Os.WINDOWS
        assertFailsWith<FfmpegException> { FfmpegCommands.record(s, AudioOptions(false, true), 2, 2, Path.of("o.mp4"), os) }
        val a = FfmpegCommands.record(s.copy(micDevice = "Mic"), AudioOptions(false, true), 2, 2, Path.of("o.mp4"), Os.WINDOWS)
        assertTrue(has(a, "-i", "audio=Mic"))
    }

    @Test fun ringHoldsRequestedLength() {
        assertEquals(9, FfmpegCommands.ringSize(30))
        assertEquals(123, FfmpegCommands.ringSize(600))
        val a = FfmpegCommands.buffer(s, AudioOptions(false, false), 2, 2, Path.of("/b"), 60, Os.LINUX)
        assertTrue(has(a, "-segment_wrap", "15"))
    }

    @Test fun selectsNewestSegmentsInTimeOrder() {
        // Shuffled on purpose: ring wrap means names aren't chronological.
        val entries = (0 until 20).map { Path.of("s$it.ts") to FileTime.fromMillis(1000L + it) }.reversed().shuffled(java.util.Random(1))
        val sel = ClipRing.select(entries, 30).map { it.toString() }
        assertEquals((13..19).map { "s$it.ts" }, sel)
        assertEquals(3, ClipRing.select(entries.take(3), 600).size)
        assertTrue(ClipRing.select(emptyList(), 30).isEmpty())
    }

    @Test fun listFileEscapesQuotes() {
        assertEquals("file '/a/b'\\''c.ts'\n", ClipRing.listFile(listOf(Path.of("/a/b'c.ts"))))
    }

    @Test fun scalerResizes() {
        val src = ByteArray(2 * 2 * 4) { (it / 4).toByte() } // pixels 0,1,2,3
        val dst = ByteArray(4 * 4 * 4)
        FrameScaler.nearest(src, 2, 2, dst, 4, 4)
        assertEquals(0, dst[0].toInt()); assertEquals(1, dst[(0 * 4 + 3) * 4].toInt())
        assertEquals(2, dst[(3 * 4 + 0) * 4].toInt()); assertEquals(3, dst[(3 * 4 + 3) * 4].toInt())
    }

    @Test fun poolReusesBuffers() {
        val pool = FramePool()
        val f = pool.acquire(4, 4)
        val data = f.data
        f.retain(); f.release(); f.release()
        assertSame(data, pool.acquire(4, 4).data)
        assertNotSame(data, pool.acquire(8, 8).data)
    }

    private val hasFfmpeg = runCatching { ProcessBuilder("ffmpeg", "-version").start().waitFor(5, TimeUnit.SECONDS) }.getOrDefault(false)

    /** End to end against a real ffmpeg with synthetic frames: record, then ring-buffer + clip. */
    @Test fun endToEndWithRealFfmpeg() {
        assumeFfmpeg()
        val dir = Files.createTempDirectory("polymedia-test")
        val none = AudioOptions(false, false)
        val set = s.copy(fps = 10, bitrateKbps = 500)
        val w = 64; val h = 48
        val pool = FramePool()
        fun feed(offer: (Frame) -> Unit, seconds: Int) {
            repeat(seconds * 10) { i ->
                val f = pool.acquire(w, h)
                java.util.Arrays.fill(f.data, (i * 5).toByte())
                offer(f); f.release()
                Thread.sleep(100)
            }
        }

        val replay = ReplayRecorder { dir.resolve("rec") }
        val file = replay.start(set, none, w, h)
        feed(replay::offer, 3)
        assertEquals(file, replay.stop())
        assertTrue(Files.size(file) > 0, "recording is empty")

        val clip = ClipRecorder(dir.resolve("buf")) { dir.resolve("clips") }
        clip.start(ClipRecorder.Params(set, none, ClipLength.Last30Seconds), w, h)
        feed(clip::offer, 7)
        val out = clip.clip()
        assertNotNull(out)
        assertTrue(Files.size(out) > 0, "clip is empty")
        clip.stop()
        assertFalse(Files.exists(dir.resolve("buf")))
        dir.toFile().deleteRecursively()
    }

    private fun assumeFfmpeg() { org.junit.jupiter.api.Assumptions.assumeTrue(hasFfmpeg) }
}
