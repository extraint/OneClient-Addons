//! Builds `ffmpeg` command lines for each platform.
//!
//! Platform notes:
//! * **Linux** - X11 screen capture (`x11grab`) and PulseAudio/PipeWire-pulse. Game audio is the
//!   default sink's monitor. Wayland-only sessions are not supported by `x11grab`.
//! * **Windows** - `gdigrab` (optionally a single window by title). ffmpeg has no built-in
//!   loopback, so game audio needs a DirectShow loopback device such as
//!   `virtual-audio-capturer`; the microphone device name must be set in the config.
//! * **macOS** - `avfoundation`. Game audio needs a loopback device (e.g. BlackHole); set
//!   `game_audio_device` / `mic_device` to the avfoundation audio index.

use std::path::Path;

use crate::config::{AudioOptions, CaptureSettings};
use crate::{Error, Result};

/// Seconds per rolling-buffer segment.
pub const SEGMENT_SECS: u32 = 5;

fn s(v: impl Into<String>) -> String {
    v.into()
}

fn video_input(c: &CaptureSettings) -> Vec<String> {
    let fps = c.fps.to_string();
    if cfg!(target_os = "windows") {
        let target = if c.window_title.is_empty() {
            "desktop".to_string()
        } else {
            format!("title={}", c.window_title)
        };
        vec![
            s("-f"),
            s("gdigrab"),
            s("-framerate"),
            fps,
            s("-draw_mouse"),
            s("0"),
            s("-i"),
            target,
        ]
    } else if cfg!(target_os = "macos") {
        let screen = if c.display.is_empty() {
            "1"
        } else {
            &c.display
        };
        vec![
            s("-f"),
            s("avfoundation"),
            s("-framerate"),
            fps,
            s("-capture_cursor"),
            s("0"),
            s("-i"),
            format!("{screen}:none"),
        ]
    } else {
        let display = if !c.display.is_empty() {
            c.display.clone()
        } else {
            std::env::var("DISPLAY").unwrap_or_else(|_| ":0.0".into())
        };
        vec![
            s("-f"),
            s("x11grab"),
            s("-framerate"),
            fps,
            s("-i"),
            display,
        ]
    }
}

fn audio_input(device: &str, default: Option<&str>, what: &str) -> Result<Vec<String>> {
    let dev = if device.is_empty() {
        default
    } else {
        Some(device)
    };
    let Some(dev) = dev else {
        return Err(Error::Ffmpeg(format!(
            "no {what} device configured; set it in the capture settings"
        )));
    };
    Ok(if cfg!(target_os = "windows") {
        vec![s("-f"), s("dshow"), s("-i"), format!("audio={dev}")]
    } else if cfg!(target_os = "macos") {
        vec![s("-f"), s("avfoundation"), s("-i"), format!(":{dev}")]
    } else {
        vec![s("-f"), s("pulse"), s("-i"), s(dev)]
    })
}

fn default_game_device() -> Option<&'static str> {
    if cfg!(target_os = "windows") {
        Some("virtual-audio-capturer")
    } else if cfg!(target_os = "macos") {
        None
    } else {
        Some("@DEFAULT_MONITOR@")
    }
}

fn default_mic_device() -> Option<&'static str> {
    if cfg!(any(target_os = "windows", target_os = "macos")) {
        None
    } else {
        Some("@DEFAULT_SOURCE@")
    }
}

/// Inputs (video + selected audio) and the number of audio inputs added.
fn inputs(c: &CaptureSettings, audio: AudioOptions) -> Result<(Vec<String>, usize)> {
    let mut args = video_input(c);
    let mut n = 0;
    if audio.game {
        args.extend(audio_input(
            &c.game_audio_device,
            default_game_device(),
            "game audio",
        )?);
        n += 1;
    }
    if audio.mic {
        args.extend(audio_input(
            &c.mic_device,
            default_mic_device(),
            "microphone",
        )?);
        n += 1;
    }
    Ok((args, n))
}

fn encode(c: &CaptureSettings, n_audio: usize) -> Vec<String> {
    let mut a = vec![
        s("-c:v"),
        s("libx264"),
        s("-preset"),
        s("veryfast"),
        s("-pix_fmt"),
        s("yuv420p"),
        s("-b:v"),
        format!("{}k", c.video_bitrate_kbps),
        s("-force_key_frames"),
        format!("expr:gte(t,n_forced*{SEGMENT_SECS})"),
    ];
    match n_audio {
        0 => a.extend([s("-an")]),
        1 => a.extend([
            s("-map"),
            s("0:v"),
            s("-map"),
            s("1:a"),
            s("-c:a"),
            s("aac"),
            s("-b:a"),
            s("160k"),
        ]),
        _ => a.extend([
            s("-filter_complex"),
            s("[1:a][2:a]amix=inputs=2:duration=longest[a]"),
            s("-map"),
            s("0:v"),
            s("-map"),
            s("[a]"),
            s("-c:a"),
            s("aac"),
            s("-b:a"),
            s("160k"),
        ]),
    }
    a
}

/// PolyReplay: record straight to a fragmented MP4 (survives a crash).
pub fn record_args(c: &CaptureSettings, audio: AudioOptions, output: &Path) -> Result<Vec<String>> {
    let (mut args, n) = inputs(c, audio)?;
    args.insert(0, s("-y"));
    args.extend(encode(c, n));
    args.extend([
        s("-movflags"),
        s("+frag_keyframe+empty_moov+default_base_moof"),
    ]);
    args.push(output.to_string_lossy().into_owned());
    Ok(args)
}

/// Number of ring-buffer segments needed to hold `length_secs` (plus slack so the oldest
/// wanted segment is never overwritten while the clip is being cut).
pub fn ring_size(length_secs: u32) -> u32 {
    length_secs.div_ceil(SEGMENT_SECS) + 3
}

/// PolyClip: write a wrapping ring of MPEG-TS segments.
pub fn buffer_args(
    c: &CaptureSettings,
    audio: AudioOptions,
    dir: &Path,
    length_secs: u32,
) -> Result<Vec<String>> {
    let (mut args, n) = inputs(c, audio)?;
    args.insert(0, s("-y"));
    args.extend(encode(c, n));
    args.extend([
        s("-f"),
        s("segment"),
        s("-segment_format"),
        s("mpegts"),
        s("-segment_time"),
        SEGMENT_SECS.to_string(),
        s("-segment_wrap"),
        ring_size(length_secs).to_string(),
        s("-reset_timestamps"),
        s("1"),
    ]);
    args.push(dir.join("seg%04d.ts").to_string_lossy().into_owned());
    Ok(args)
}

/// Join segments listed in `list_file` into an MP4 without re-encoding.
pub fn concat_args(list_file: &Path, output: &Path) -> Vec<String> {
    vec![
        s("-y"),
        s("-f"),
        s("concat"),
        s("-safe"),
        s("0"),
        s("-i"),
        list_file.to_string_lossy().into_owned(),
        s("-c"),
        s("copy"),
        s("-bsf:a"),
        s("aac_adtstoasc"),
        output.to_string_lossy().into_owned(),
    ]
}

#[cfg(test)]
mod tests {
    use super::*;

    fn has(args: &[String], pair: [&str; 2]) -> bool {
        args.windows(2).any(|w| w[0] == pair[0] && w[1] == pair[1])
    }

    #[test]
    fn video_only_has_no_audio() {
        let a = record_args(
            &CaptureSettings::default(),
            AudioOptions::default(),
            Path::new("o.mp4"),
        )
        .unwrap();
        assert!(a.contains(&"-an".to_string()));
        assert!(!a.contains(&"-filter_complex".to_string()));
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn game_and_mic_are_mixed() {
        let audio = AudioOptions {
            game: true,
            mic: true,
        };
        let a = record_args(&CaptureSettings::default(), audio, Path::new("o.mp4")).unwrap();
        assert!(a.iter().any(|x| x.contains("amix=inputs=2")));
        assert!(has(&a, ["-i", "@DEFAULT_MONITOR@"]));
        assert!(has(&a, ["-i", "@DEFAULT_SOURCE@"]));
    }

    #[cfg(target_os = "linux")]
    #[test]
    fn single_source_maps_directly() {
        let a = record_args(
            &CaptureSettings::default(),
            AudioOptions {
                game: false,
                mic: true,
            },
            Path::new("o.mp4"),
        )
        .unwrap();
        assert!(has(&a, ["-map", "1:a"]));
        assert!(!a.iter().any(|x| x.contains("DEFAULT_MONITOR")));
    }

    #[cfg(any(target_os = "windows", target_os = "macos"))]
    #[test]
    fn mic_requires_device_name() {
        let r = record_args(
            &CaptureSettings::default(),
            AudioOptions {
                game: false,
                mic: true,
            },
            Path::new("o.mp4"),
        );
        assert!(r.is_err());
    }

    #[test]
    fn ring_covers_length() {
        assert_eq!(ring_size(30), 9);
        assert_eq!(ring_size(600), 123);
    }

    #[test]
    fn buffer_uses_wrapping_segments() {
        let a = buffer_args(
            &CaptureSettings::default(),
            AudioOptions::default(),
            Path::new("/b"),
            60,
        )
        .unwrap();
        assert!(has(&a, ["-segment_wrap", "15"]));
        assert!(a.last().unwrap().ends_with("seg%04d.ts"));
    }
}
