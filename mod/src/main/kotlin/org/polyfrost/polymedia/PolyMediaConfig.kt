package org.polyfrost.polymedia

import com.mojang.blaze3d.platform.InputConstants
import org.polyfrost.oneconfig.api.config.v1.Config
import org.polyfrost.oneconfig.api.config.v1.KtConfig
import org.polyfrost.oneconfig.api.ui.v1.keybind.KeybindHelper
import org.polyfrost.oneconfig.api.ui.v1.keybind.OneConfigKeybind
import org.polyfrost.polymedia.core.AudioOptions
import org.polyfrost.polymedia.core.CaptureSettings
import org.polyfrost.polymedia.core.ClipLength
import org.polyfrost.polymedia.core.Platform

/** Shown in OneConfig's menu (Right Shift). */
object PolyMediaConfig : KtConfig("polymedia.json", "PolyReplay & PolyClip", Config.Category.QOL) {
    private const val REPLAY = "PolyReplay"
    private const val CLIP = "PolyClip"
    private const val CAPTURE = "Capture"

    // ---- PolyReplay -------------------------------------------------------------------------
    val replayKeybind: OneConfigKeybind by keybind(
        name = "Start / stop recording",
        def = KeybindHelper.builder().key(InputConstants.KEY_F9)
            .name("PolyReplay: start / stop recording").category("PolyMedia")
            .action { pressed: Boolean -> if (pressed) PolyMedia.requestReplayToggle(); true }.register(),
        description = "Press once to start recording your gameplay, again to stop and save.",
        category = REPLAY,
    )
    var replayGameAudio: Boolean by switch(true, "Record game audio", "Include Minecraft / system output audio.", REPLAY)
    var replayMic: Boolean by switch(false, "Record microphone", "Include your microphone.", REPLAY)

    // ---- PolyClip ---------------------------------------------------------------------------
    var clipEnabled: Boolean by switch(
        true, "Enable PolyClip",
        "Keeps a rolling buffer while you are in a world so you can save moments after they happen.", CLIP,
    )
    val clipKeybind: OneConfigKeybind by keybind(
        name = "Clip that",
        def = KeybindHelper.builder().key(InputConstants.KEY_F10)
            .name("PolyClip: save a clip").category("PolyMedia")
            .action { pressed: Boolean -> if (pressed) PolyMedia.requestClip(); true }.register(),
        description = "Saves the last 30 seconds, 1 minute or 10 minutes (see below).",
        category = CLIP,
    )
    var clipLength: ClipLength by radiobutton(
        name = "Clip length",
        defaultOption = ClipLength.Last30Seconds,
        options = ClipLength.entries.toTypedArray(),
        description = "How far back a clip goes. Changing this restarts the buffer.",
        category = CLIP,
        stringTransformer = { it.label },
    )
    var clipGameAudio: Boolean by switch(true, "Record game audio", "Include Minecraft / system output audio.", CLIP)
    var clipMic: Boolean by switch(false, "Record microphone", "Include your microphone.", CLIP)

    // ---- Capture ----------------------------------------------------------------------------
    var ffmpegPath: String by text("ffmpeg", "ffmpeg path", "Path to the ffmpeg executable (must be installed).", CAPTURE)
    var fps: Float by slider(
        name = "Frames per second", def = 30f, category = CAPTURE, min = 15f, max = 60f, step = 5f,
        description = "Lower is lighter on your PC.",
    )
    var bitrateKbps: Float by slider(
        name = "Video bitrate (kbps)", def = 8000f, category = CAPTURE, min = 2000f, max = 30000f, step = 500f,
    )
    var gameAudioDevice: String by text(
        "", "Game audio device", "Leave empty for the default (Linux works out of the box; Windows/macOS need a loopback device).", CAPTURE,
    )
    var micDevice: String by text("", "Microphone device", "Leave empty for the default (required on Windows/macOS).", CAPTURE)

    fun captureSettings() = CaptureSettings(
        ffmpegPath = Platform.resolveFfmpeg(ffmpegPath),
        fps = fps.toInt().coerceIn(5, 120),
        bitrateKbps = bitrateKbps.toInt().coerceIn(500, 100_000),
        gameAudioDevice = gameAudioDevice.trim(),
        micDevice = micDevice.trim(),
    )

    fun replayAudio() = AudioOptions(replayGameAudio, replayMic)
    fun clipAudio() = AudioOptions(clipGameAudio, clipMic)
}
