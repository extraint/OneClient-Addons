# PolyMedia - PolyReplay & PolyClip (in-game mod)

Two modules in one Fabric mod for **Minecraft 26.1.x and 26.3**, configured from OneConfig's
menu (**Right Shift**):

* **PolyReplay** - press **F9** to start recording your gameplay, again to stop and save. Choose
  whether to record **game audio** and/or your **microphone**.
* **PolyClip** - keeps a rolling buffer while you're in a world. Press **F10** to save the last
  **30 seconds**, **1 minute** or **10 minutes** (your choice in the menu) as a clip.

Files go to `.minecraft/polymedia/recordings/` and `.minecraft/polymedia/clips/`. You get a chat
message when recording starts, stops, or a clip is saved (or if something is wrong).

Both keybinds are rebindable in OneConfig, and also appear in Minecraft's Controls screen.

## How it works
Video is read from the game's own main render target at the end of every frame
(`Screenshot.takeScreenshot`, which works on both the OpenGL and Vulkan backends), so only
Minecraft is recorded, HUD included, and no desktop capture is needed. Frames are handed to a
background thread that pipes them to `ffmpeg`; if ffmpeg falls behind, frames are dropped instead
of stalling the game. PolyClip writes a wrapping ring of 5-second segments on disk (a 10-minute
buffer never sits in RAM) and joins the newest ones without re-encoding when you clip.

## Requirements
* `ffmpeg` installed (or set its path in the menu).
* OneConfig, Fabric API and Fabric Language Kotlin.
* Audio: **Linux** works out of the box (PulseAudio/PipeWire). **Windows** needs a DirectShow
  loopback device for game audio (default `virtual-audio-capturer`) and your mic's device name.
  **macOS** needs a loopback device (e.g. BlackHole) and device indices. Set these under
  *Capture* in the menu.

## Building
Needs JDK 25.
```sh
cd mod
./gradlew build                  # Minecraft 26.1.2 (default)
./gradlew build -Pmc=26.3        # Minecraft 26.3
```
The jar is written to `mod/build/libs/`.

## Status
* Built and unit-tested on both versions. The encoder, ring buffer and clip cutting are
  tested end to end against a real ffmpeg with synthetic frames.
* **Not yet run inside a real Minecraft client.** The in-game parts (render hook, OneConfig page,
  keybinds, chat messages) compile against the real 26.1.2 and 26.3 jars but haven't been
  exercised live. If something misbehaves, the first suspects are the mixin hook
  (`MinecraftRenderMixin`) and the readback in `PolyMedia.kt`.
* Single-version quirks live in `src/mc-26.1` and `src/mc-26.3`.
