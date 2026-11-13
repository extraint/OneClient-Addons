//! PolyReplay: toggle-record your gameplay with a keybind.

use std::path::PathBuf;
use std::time::{SystemTime, UNIX_EPOCH};

use crate::config::{CaptureSettings, PolyReplayConfig};
use crate::ffmpeg;
use crate::process::Ffmpeg;
use crate::{Error, Result};

pub struct PolyReplay {
    pub capture: CaptureSettings,
    pub config: PolyReplayConfig,
    active: Option<(Ffmpeg, PathBuf)>,
}

impl PolyReplay {
    pub fn new(capture: CaptureSettings, config: PolyReplayConfig) -> Self {
        Self {
            capture,
            config,
            active: None,
        }
    }

    pub fn is_recording(&mut self) -> bool {
        match self.active.as_mut() {
            Some((p, _)) => p.is_running(),
            None => false,
        }
    }

    /// Starts recording and returns the file being written.
    pub fn start(&mut self) -> Result<PathBuf> {
        if self.is_recording() {
            return Err(Error::AlreadyRunning);
        }
        std::fs::create_dir_all(&self.config.output_dir)?;
        let secs = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_or(0, |d| d.as_secs());
        let path = self.config.output_dir.join(format!("replay-{secs}.mp4"));
        let args = ffmpeg::record_args(&self.capture, self.config.audio, &path)?;
        let proc = Ffmpeg::spawn(&self.capture.ffmpeg_path, &args)?;
        self.active = Some((proc, path.clone()));
        Ok(path)
    }

    /// Stops recording and returns the finished file.
    pub fn stop(&mut self) -> Result<PathBuf> {
        let (proc, path) = self.active.take().ok_or(Error::NotRunning)?;
        proc.stop();
        Ok(path)
    }

    /// Keybind handler: start if idle, stop if recording.
    pub fn toggle(&mut self) -> Result<Toggle> {
        if self.is_recording() {
            self.stop().map(Toggle::Stopped)
        } else {
            self.active = None;
            self.start().map(Toggle::Started)
        }
    }
}

#[derive(Debug, PartialEq, Eq)]
pub enum Toggle {
    Started(PathBuf),
    Stopped(PathBuf),
}
