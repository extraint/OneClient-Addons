# OneClient-Addons

Addons for OneClient.

* **[`mod/`](mod/README.md)** - the in-game **PolyReplay** and **PolyClip** modules (Fabric,
  Minecraft 26.1.x / 26.3, configured from OneConfig's Right Shift menu). **Start here.**
* The Rust crate below is an earlier standalone version that records the whole desktop with
  ffmpeg instead of the game's framebuffer. It is kept as a library/CLI but is not what the
  in-game modules use.

## PolyReplay
Press a keybind (default **F9**) to start recording your gameplay, press it again to stop.
Choose whether to include **game audio**, your **microphone**, both, or neither
(`polyreplay.audio` in the config). Output is a fragmented MP4 in `recordings/`, so a crash
doesn't lose the file.

## PolyClip
Keeps a rolling buffer in the background. Press a keybind (default **F10**) to save the last
**30 seconds**, **1 minute** or **10 minutes** (`polyclip.length`: `Last30Seconds`,
`Last1Minute`, `Last10Minutes`) as an MP4 in `clips/`. Clips are joined without re-encoding,
so saving is near-instant. If less than the chosen length is buffered you get what exists.
Changing the length restarts the buffer (`PolyClip::set_length`), discarding buffered footage.

## Config (`oneclient-addons.json`)
Every field is optional.
```json
{
  "capture": { "fps": 60, "video_bitrate_kbps": 8000, "window_title": "", "game_audio_device": "", "mic_device": "" },
  "polyreplay": { "keybind": "F9", "audio": { "game": true, "mic": false } },
  "polyclip": { "keybind": "F10", "length": "Last1Minute", "audio": { "game": true, "mic": true } }
}
```
Keybinds accept forms like `F9`, `alt+F10`, `ctrl+shift+KeyR`.

## Running
Requires [`ffmpeg`](https://ffmpeg.org) on `PATH` (or set `capture.ffmpeg_path`).
```sh
cargo run --features hotkeys
cargo test
```
As a library, use `PolyReplay` / `PolyClip` directly and call `toggle()` / `clip()` from the
launcher's own keybind handling (the `hotkeys` feature is optional).

## Platform notes
| | Video | Game audio | Microphone |
|---|---|---|---|
| Linux (X11) | `x11grab` | default sink monitor (works out of the box) | default source |
| Windows | `gdigrab` (set `window_title` to capture only Minecraft) | needs a DirectShow loopback, default `virtual-audio-capturer` | set `mic_device` |
| macOS | `avfoundation` | needs a loopback device (e.g. BlackHole); set `game_audio_device` | set `mic_device` |

Wayland-only sessions aren't supported by `x11grab`. Software H.264 (`libx264`) is used, so
expect some CPU cost while recording.

## Status
Config, command-line building and clip selection are unit-tested, and the segment-ring and
concat commands were verified against real ffmpeg with a synthetic source. Live screen/audio
capture and the global hotkeys have **not** been tested on a real desktop yet, and nothing is
wired into the OneClient UI (OneClient currently has no addon API).
