package org.polyfrost.polymedia.core

import java.nio.file.Path

/**
 * Builds `ffmpeg` command lines. Video arrives as raw RGBA frames on stdin (from the game's own
 * framebuffer, so only Minecraft is recorded); audio comes from system devices.
 *
 * Audio notes: Linux uses PulseAudio/PipeWire-pulse (game audio = default output monitor, works out
 * of the box). Windows needs a DirectShow loopback device for game audio (default
 * `virtual-audio-capturer`) and a named mic device. macOS uses the default mic; game audio needs a
 * loopback device (e.g. BlackHole) given as an avfoundation index. Override with [CaptureSettings.gameAudioDevice]/[CaptureSettings.micDevice].
 */
object FfmpegCommands {
    const val SEGMENT_SECS = 5

    /** Ring slots needed to hold [lengthSecs], plus slack so the oldest wanted slot isn't overwritten mid-clip. */
    fun ringSize(lengthSecs: Int): Int = (lengthSecs + SEGMENT_SECS - 1) / SEGMENT_SECS + 3

    private fun audioInput(device: String, default: String?, what: String, os: Os): List<String> {
        val dev = device.ifEmpty { default }
            ?: throw FfmpegException("No $what device configured; set it in the PolyMedia settings.")
        return when (os) {
            Os.WINDOWS -> listOf("-f", "dshow", "-i", "audio=$dev")
            Os.MAC -> listOf("-f", "avfoundation", "-i", ":$dev")
            Os.LINUX -> listOf("-f", "pulse", "-i", dev)
        }
    }

    private fun gameDefault(os: Os) = when (os) {
        Os.WINDOWS -> "virtual-audio-capturer"
        Os.MAC -> null
        Os.LINUX -> "@DEFAULT_MONITOR@"
    }

    private fun micDefault(os: Os) = when (os) {
        Os.LINUX -> "@DEFAULT_SOURCE@"
        Os.MAC -> "default" // avfoundation's default input
        Os.WINDOWS -> null
    }

    /** Input 0 is always the video pipe; audio inputs follow (game first, then mic). */
    private fun inputs(s: CaptureSettings, audio: AudioOptions, w: Int, h: Int, os: Os): Pair<List<String>, Int> {
        val args = mutableListOf(
            "-use_wallclock_as_timestamps", "1",
            "-f", "rawvideo", "-pixel_format", "rgba",
            "-video_size", "${w}x$h", "-framerate", s.fps.toString(), "-i", "pipe:0",
        )
        var n = 0
        if (audio.game) { args += audioInput(s.gameAudioDevice, gameDefault(os), "game audio", os); n++ }
        if (audio.mic) { args += audioInput(s.micDevice, micDefault(os), "microphone", os); n++ }
        return args to n
    }

    private fun encode(s: CaptureSettings, nAudio: Int): List<String> {
        // Even dimensions are required by yuv420p; fps filter makes the output constant-rate.
        val video = "[0:v]fps=${s.fps},scale=trunc(iw/2)*2:trunc(ih/2)*2,format=yuv420p[v]"
        val args = mutableListOf(
            "-c:v", "libx264", "-preset", "veryfast", "-b:v", "${s.bitrateKbps}k",
            // Keyframe at every segment boundary so ring segments start cleanly.
            "-force_key_frames", "expr:gte(t,n_forced*$SEGMENT_SECS)",
        )
        when (nAudio) {
            0 -> args += listOf("-filter_complex", video, "-map", "[v]", "-an")
            1 -> args += listOf("-filter_complex", video, "-map", "[v]", "-map", "1:a", "-c:a", "aac", "-b:a", "160k")
            else -> args += listOf(
                "-filter_complex", "$video;[1:a][2:a]amix=inputs=2:duration=longest[a]",
                "-map", "[v]", "-map", "[a]", "-c:a", "aac", "-b:a", "160k",
            )
        }
        return args
    }

    /** PolyReplay: record to a fragmented MP4 (survives a crash). */
    fun record(
        s: CaptureSettings, audio: AudioOptions, w: Int, h: Int, output: Path, os: Os = Os.current(),
    ): List<String> {
        val (inp, n) = inputs(s, audio, w, h, os)
        return listOf("-y") + inp + encode(s, n) +
            listOf("-movflags", "+frag_keyframe+empty_moov+default_base_moof", output.toString())
    }

    /** PolyClip: a wrapping ring of MPEG-TS segments in [dir]. */
    fun buffer(
        s: CaptureSettings, audio: AudioOptions, w: Int, h: Int, dir: Path, lengthSecs: Int, os: Os = Os.current(),
    ): List<String> {
        val (inp, n) = inputs(s, audio, w, h, os)
        return listOf("-y") + inp + encode(s, n) + listOf(
            "-f", "segment", "-segment_format", "mpegts",
            "-segment_time", SEGMENT_SECS.toString(),
            "-segment_wrap", ringSize(lengthSecs).toString(),
            "-reset_timestamps", "1",
            dir.resolve("seg%04d.ts").toString(),
        )
    }

    /** Join the segments listed in [listFile] into an MP4 without re-encoding. */
    fun concat(listFile: Path, output: Path): List<String> = listOf(
        "-y", "-f", "concat", "-safe", "0", "-i", listFile.toString(),
        "-c", "copy", "-bsf:a", "aac_adtstoasc", output.toString(),
    )
}
