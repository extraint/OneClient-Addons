package org.polyfrost.polymedia.core

/** How far back PolyClip saves when the clip keybind is pressed. */
enum class ClipLength(val seconds: Int, val label: String) {
    Last30Seconds(30, "Last 30 seconds"),
    Last1Minute(60, "Last 1 minute"),
    Last10Minutes(600, "Last 10 minutes"),
}

/** Which audio to include. Both off = video only. */
data class AudioOptions(val game: Boolean, val mic: Boolean)

data class CaptureSettings(
    val ffmpegPath: String = "ffmpeg",
    val fps: Int = 30,
    val bitrateKbps: Int = 8000,
    /** Empty = platform default. */
    val gameAudioDevice: String = "",
    val micDevice: String = "",
)

enum class Os {
    LINUX, WINDOWS, MAC;

    companion object {
        fun current(): Os {
            val n = System.getProperty("os.name").lowercase()
            return when {
                "win" in n -> WINDOWS
                "mac" in n || "darwin" in n -> MAC
                else -> LINUX
            }
        }
    }
}

class FfmpegException(message: String) : RuntimeException(message)
