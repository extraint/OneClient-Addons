//! OneClient addons.
//!
//! * [`polyreplay::PolyReplay`] - press a keybind to start/stop recording your gameplay,
//!   optionally with game audio and/or your microphone.
//! * [`polyclip::PolyClip`] - runs a rolling buffer in the background; press a keybind to
//!   save the last 30 seconds, 1 minute or 10 minutes as a clip.
//!
//! Both drive the system `ffmpeg` binary. See [`ffmpeg`] for platform notes.

pub mod config;
pub mod ffmpeg;
pub mod polyclip;
pub mod polyreplay;
pub mod process;

#[cfg(feature = "hotkeys")]
pub mod hotkeys;

#[derive(Debug, thiserror::Error)]
pub enum Error {
    #[error("io error: {0}")]
    Io(#[from] std::io::Error),
    #[error("config error: {0}")]
    Config(#[from] serde_json::Error),
    #[error("ffmpeg failed: {0}")]
    Ffmpeg(String),
    #[error("already running")]
    AlreadyRunning,
    #[error("not running")]
    NotRunning,
    #[error("no buffered footage to clip yet")]
    NothingBuffered,
    #[error("hotkey error: {0}")]
    Hotkey(String),
}

pub type Result<T> = std::result::Result<T, Error>;
