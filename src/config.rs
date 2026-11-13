use std::path::{Path, PathBuf};

use serde::{Deserialize, Serialize};

use crate::Result;

/// Which audio to include. Both off = video only.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub struct AudioOptions {
    /// In-game (system output) audio.
    pub game: bool,
    /// Your microphone.
    pub mic: bool,
}

/// How far back PolyClip saves when the clip keybind is pressed.
#[derive(Clone, Copy, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
pub enum ClipLength {
    #[default]
    Last30Seconds,
    Last1Minute,
    Last10Minutes,
}

impl ClipLength {
    pub const ALL: [ClipLength; 3] = [Self::Last30Seconds, Self::Last1Minute, Self::Last10Minutes];

    pub fn seconds(self) -> u32 {
        match self {
            Self::Last30Seconds => 30,
            Self::Last1Minute => 60,
            Self::Last10Minutes => 600,
        }
    }

    pub fn label(self) -> &'static str {
        match self {
            Self::Last30Seconds => "30 seconds",
            Self::Last1Minute => "1 minute",
            Self::Last10Minutes => "10 minutes",
        }
    }
}

/// Capture settings shared by both addons.
#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct CaptureSettings {
    pub ffmpeg_path: String,
    pub fps: u32,
    pub video_bitrate_kbps: u32,
    /// Windows: window title to capture (e.g. "Minecraft"). Empty = whole desktop.
    pub window_title: String,
    /// Linux/X11 `DISPLAY` (default ":0.0"); macOS: avfoundation screen index (default "1").
    pub display: String,
    /// Override the game-audio (system loopback) device. Empty = platform default.
    pub game_audio_device: String,
    /// Override the microphone device. Empty = platform default.
    pub mic_device: String,
}

impl Default for CaptureSettings {
    fn default() -> Self {
        Self {
            ffmpeg_path: "ffmpeg".into(),
            fps: 60,
            video_bitrate_kbps: 8000,
            window_title: String::new(),
            display: String::new(),
            game_audio_device: String::new(),
            mic_device: String::new(),
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct PolyReplayConfig {
    pub keybind: String,
    pub audio: AudioOptions,
    pub output_dir: PathBuf,
}

impl Default for PolyReplayConfig {
    fn default() -> Self {
        Self {
            keybind: "F9".into(),
            audio: AudioOptions {
                game: true,
                mic: false,
            },
            output_dir: PathBuf::from("recordings"),
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct PolyClipConfig {
    pub keybind: String,
    pub length: ClipLength,
    pub audio: AudioOptions,
    pub output_dir: PathBuf,
    /// Where the rolling buffer lives. Use a fast disk; 10 min of footage is a few hundred MB.
    pub buffer_dir: PathBuf,
}

impl Default for PolyClipConfig {
    fn default() -> Self {
        Self {
            keybind: "F10".into(),
            length: ClipLength::default(),
            audio: AudioOptions {
                game: true,
                mic: false,
            },
            output_dir: PathBuf::from("clips"),
            buffer_dir: std::env::temp_dir().join("polyclip-buffer"),
        }
    }
}

#[derive(Clone, Debug, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(default)]
pub struct Config {
    pub capture: CaptureSettings,
    pub polyreplay: PolyReplayConfig,
    pub polyclip: PolyClipConfig,
}

impl Config {
    /// Loads the config, or returns defaults if the file does not exist.
    pub fn load(path: &Path) -> Result<Self> {
        match std::fs::read_to_string(path) {
            Ok(s) => Ok(serde_json::from_str(&s)?),
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Ok(Self::default()),
            Err(e) => Err(e.into()),
        }
    }

    pub fn save(&self, path: &Path) -> Result<()> {
        if let Some(parent) = path.parent() {
            std::fs::create_dir_all(parent)?;
        }
        std::fs::write(path, serde_json::to_string_pretty(self)?)?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn clip_lengths() {
        assert_eq!(ClipLength::Last30Seconds.seconds(), 30);
        assert_eq!(ClipLength::Last1Minute.seconds(), 60);
        assert_eq!(ClipLength::Last10Minutes.seconds(), 600);
    }

    #[test]
    fn partial_config_fills_defaults() {
        let c: Config = serde_json::from_str(r#"{"polyclip":{"length":"Last10Minutes"}}"#).unwrap();
        assert_eq!(c.polyclip.length, ClipLength::Last10Minutes);
        assert_eq!(c.polyclip.keybind, "F10");
        assert_eq!(c.polyreplay.keybind, "F9");
    }

    #[test]
    fn roundtrip_and_missing_file() {
        let dir = std::env::temp_dir().join(format!("oca-cfg-{}", std::process::id()));
        let p = dir.join("c.json");
        assert_eq!(Config::load(&p).unwrap(), Config::default());
        let mut c = Config::default();
        c.polyreplay.audio.mic = true;
        c.save(&p).unwrap();
        assert_eq!(Config::load(&p).unwrap(), c);
        let _ = std::fs::remove_dir_all(dir);
    }
}
