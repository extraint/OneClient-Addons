package org.polyfrost.polymedia

import com.mojang.blaze3d.platform.NativeImage
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.client.Screenshot
import net.minecraft.network.chat.Component
import org.lwjgl.system.MemoryUtil
import org.polyfrost.polymedia.core.ClipRecorder
import org.polyfrost.polymedia.core.Frame
import org.polyfrost.polymedia.core.FramePool
import org.polyfrost.polymedia.core.Platform
import org.polyfrost.polymedia.core.ReplayRecorder
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Glue between the game and the recorders. [onFrameEnd] runs on the render thread after every frame;
 * everything slow (starting/stopping ffmpeg, cutting clips) happens on a worker thread.
 */
object PolyMedia {
    private val gameDir: Path get() = FabricLoader.getInstance().gameDir
    private val root: Path get() = gameDir.resolve("polymedia")

    private val pool = FramePool()
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "PolyMedia-control").apply { isDaemon = true } }

    val replay = ReplayRecorder { root.resolve("recordings") }
    // Per-process buffer dir so two game instances never share (or delete) each other's segments.
    private val bufferDir: Path =
        Path.of(System.getProperty("java.io.tmpdir"), "polymedia-buffer-${ProcessHandle.current().pid()}")
    val clip = ClipRecorder(bufferDir) { root.resolve("clips") }

    private val replayToggleRequested = AtomicBoolean()
    private val clipRequested = AtomicBoolean()
    private val replayBusy = AtomicBoolean()
    private val clipBusy = AtomicBoolean()
    private val readbackPending = AtomicBoolean()
    private var lastCaptureNanos = 0L
    @Volatile private var clipRetryAfterNanos = 0L

    init {
        Runtime.getRuntime().addShutdownHook(Thread({
            runCatching { replay.stop() }
            runCatching { clip.stop() }
        }, "PolyMedia-shutdown"))
    }

    fun requestReplayToggle() { replayToggleRequested.set(true) }
    fun requestClip() { clipRequested.set(true) }

    private fun say(msg: String) {
        val mc = Minecraft.getInstance()
        mc.execute { mc.player?.sendSystemMessage(Component.literal(msg)) }
    }

    /** Called on the render thread at the end of each frame. */
    @JvmStatic
    fun onFrameEnd() {
        val mc = Minecraft.getInstance()
        val cfg = PolyMediaConfig
        val wantClip = cfg.clipEnabled && mc.level != null
        val clipWanted = wantClip || clip.active
        val needs = replay.active || replayToggleRequested.get() || clipWanted || clipRequested.get()
        if (!needs || readbackPending.get()) return

        val interval = 1_000_000_000L / cfg.captureSettings().fps
        val now = System.nanoTime()
        if (now - lastCaptureNanos < interval && !replayToggleRequested.get()) return
        lastCaptureNanos = now

        val target = mainRenderTarget(mc)
        if (target.width <= 0 || target.height <= 0) return
        readbackPending.set(true)
        try {
            Screenshot.takeScreenshot(target, 1) { image -> onReadback(image, wantClip) }
        } catch (t: Throwable) {
            readbackPending.set(false)
            throw t
        }
    }

    private fun onReadback(image: NativeImage, wantClip: Boolean) {
        var frame: Frame? = null
        try {
            if (image.format() == NativeImage.Format.RGBA) {
                frame = pool.acquire(image.width, image.height)
                MemoryUtil.memByteBuffer(image.pointer, frame.data.size).get(frame.data, 0, frame.data.size)
            }
        } finally {
            image.close()
            readbackPending.set(false)
        }
        frame ?: return
        try {
            dispatch(frame, wantClip)
        } finally {
            frame.release()
        }
    }

    private fun dispatch(frame: Frame, wantClip: Boolean) {
        val cfg = PolyMediaConfig
        val w = frame.width
        val h = frame.height

        // PolyReplay: toggle on keybind.
        if (replayToggleRequested.compareAndSet(true, false) && replayBusy.compareAndSet(false, true)) {
            val starting = !replay.active
            val settings = cfg.captureSettings()
            val (audio, warn) = Platform.usableAudio(cfg.replayAudio(), settings)
            worker.execute {
                try {
                    if (starting) {
                        warn?.let { say("§e[PolyReplay] $it") }
                        val f = replay.start(settings, audio, w, h)
                        say("§a[PolyReplay] Recording → ${f.fileName}")
                    } else {
                        replay.stop()?.let { say("§a[PolyReplay] Saved ${it.fileName}") }
                    }
                } catch (e: Exception) {
                    say("§c[PolyReplay] ${e.message}")
                } finally {
                    replayBusy.set(false)
                }
            }
        }
        replay.offer(frame)

        // PolyClip: keep the buffer running while enabled and in a world; restart on settings change.
        val clipSettings = cfg.captureSettings()
        val (clipAudio, clipWarn) = Platform.usableAudio(cfg.clipAudio(), clipSettings)
        val desired = ClipRecorder.Params(clipSettings, clipAudio, cfg.clipLength)
        if (!clipBusy.get()) {
            val running = clip.params
            when {
                running != null && (!wantClip || running != desired || !clip.active) -> {
                    val died = wantClip && running == desired && !clip.active
                    val err = if (died) clip.lastError() else ""
                    if (clipBusy.compareAndSet(false, true)) worker.execute {
                        try {
                            clip.stop()
                            if (died) {
                                clipRetryAfterNanos = System.nanoTime() + 30_000_000_000L
                                say("§c[PolyClip] Buffer stopped: ${err.ifEmpty { "ffmpeg exited" }}")
                            }
                        } finally { clipBusy.set(false) }
                    }
                }
                running == null && wantClip && System.nanoTime() >= clipRetryAfterNanos -> {
                    if (clipBusy.compareAndSet(false, true)) worker.execute {
                        try {
                            clip.start(desired, w, h)
                            clipWarn?.let { say("§e[PolyClip] $it") }
                        } catch (e: Exception) {
                            clipRetryAfterNanos = System.nanoTime() + 30_000_000_000L
                            say("§c[PolyClip] ${e.message}")
                        } finally { clipBusy.set(false) }
                    }
                }
            }
        }
        clip.offer(frame)

        if (clipRequested.compareAndSet(true, false)) {
            if (!clip.active) {
                say("§e[PolyClip] Not buffering yet (join a world and make sure PolyClip is enabled).")
            } else worker.execute {
                try {
                    val out = clip.clip()
                    say(if (out != null) "§a[PolyClip] Saved ${out.fileName}" else "§e[PolyClip] Nothing buffered yet.")
                } catch (e: Exception) {
                    say("§c[PolyClip] ${e.message}")
                }
            }
        }
    }
}
