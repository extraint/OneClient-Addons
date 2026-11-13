//! PolyClip: always-on rolling buffer; a keybind saves the last 30s / 1min / 10min.

use std::path::{Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

use crate::config::{CaptureSettings, ClipLength, PolyClipConfig};
use crate::ffmpeg::{self, SEGMENT_SECS};
use crate::process::{self, Ffmpeg};
use crate::{Error, Result};

/// Picks the segments to join: the newest `ceil(len/seg) + 1` (the extra one covers the segment
/// currently being written), returned oldest first.
pub fn select_segments(mut entries: Vec<(PathBuf, SystemTime)>, length_secs: u32) -> Vec<PathBuf> {
    entries.sort_by_key(|(_, t)| *t);
    let want = (length_secs.div_ceil(SEGMENT_SECS) + 1) as usize;
    let skip = entries.len().saturating_sub(want);
    entries.into_iter().skip(skip).map(|(p, _)| p).collect()
}

fn list_segments(dir: &Path) -> Result<Vec<(PathBuf, SystemTime)>> {
    let mut out = Vec::new();
    for e in std::fs::read_dir(dir)? {
        let e = e?;
        let p = e.path();
        if p.extension().is_some_and(|x| x == "ts") {
            let meta = e.metadata()?;
            // Skip empty files (ring slot opened but nothing written yet).
            if meta.len() > 0 {
                out.push((p, meta.modified()?));
            }
        }
    }
    Ok(out)
}

pub struct PolyClip {
    pub capture: CaptureSettings,
    pub config: PolyClipConfig,
    buffer: Option<Ffmpeg>,
}

impl PolyClip {
    pub fn new(capture: CaptureSettings, config: PolyClipConfig) -> Self {
        Self {
            capture,
            config,
            buffer: None,
        }
    }

    pub fn is_buffering(&mut self) -> bool {
        self.buffer.as_mut().is_some_and(|p| p.is_running())
    }

    /// Starts the background buffer. The ring is sized for the longest clip configured now,
    /// so call [`set_length`](Self::set_length) rather than editing `config.length` while running.
    pub fn start(&mut self) -> Result<()> {
        if self.is_buffering() {
            return Err(Error::AlreadyRunning);
        }
        // Drop stale segments from earlier sessions so they are never stitched in.
        let _ = std::fs::remove_dir_all(&self.config.buffer_dir);
        std::fs::create_dir_all(&self.config.buffer_dir)?;
        let args = ffmpeg::buffer_args(
            &self.capture,
            self.config.audio,
            &self.config.buffer_dir,
            self.config.length.seconds(),
        )?;
        self.buffer = Some(Ffmpeg::spawn(&self.capture.ffmpeg_path, &args)?);
        Ok(())
    }

    pub fn stop(&mut self) {
        if let Some(p) = self.buffer.take() {
            p.stop();
        }
        let _ = std::fs::remove_dir_all(&self.config.buffer_dir);
    }

    /// Changes how far back clips go, restarting the buffer if it is running (the buffered
    /// footage is discarded, since the ring size changes).
    pub fn set_length(&mut self, length: ClipLength) -> Result<()> {
        let was_running = self.is_buffering();
        if was_running {
            self.stop();
        }
        self.config.length = length;
        if was_running {
            self.start()?;
        }
        Ok(())
    }

    /// Saves the last `config.length` of footage (or whatever is buffered so far).
    pub fn clip(&mut self) -> Result<PathBuf> {
        if !self.is_buffering() {
            return Err(Error::NotRunning);
        }
        let segments = select_segments(
            list_segments(&self.config.buffer_dir)?,
            self.config.length.seconds(),
        );
        if segments.is_empty() {
            return Err(Error::NothingBuffered);
        }
        std::fs::create_dir_all(&self.config.output_dir)?;
        let secs = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .map_or(0, |d| d.as_secs());
        let out = self.config.output_dir.join(format!("clip-{secs}.mp4"));

        let list = self.config.buffer_dir.join(format!("clip-{secs}.txt"));
        let body: String = segments
            .iter()
            .map(|p| format!("file '{}'\n", p.to_string_lossy().replace('\'', "'\\''")))
            .collect();
        std::fs::write(&list, body)?;
        let res = process::run(&self.capture.ffmpeg_path, &ffmpeg::concat_args(&list, &out));
        let _ = std::fs::remove_file(&list);
        res?;
        Ok(out)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    fn entries(n: u64) -> Vec<(PathBuf, SystemTime)> {
        // Deliberately shuffled: ring wrap means file names are not chronological.
        let mut v: Vec<_> = (0..n)
            .map(|i| {
                (
                    PathBuf::from(format!("s{i}.ts")),
                    UNIX_EPOCH + Duration::from_secs(1000 + i),
                )
            })
            .collect();
        v.reverse();
        v.rotate_left(3);
        v
    }

    #[test]
    fn picks_newest_in_time_order() {
        let sel = select_segments(entries(20), 30); // 6 + 1 = 7 segments
        let names: Vec<_> = sel
            .iter()
            .map(|p| p.to_str().unwrap().to_string())
            .collect();
        assert_eq!(
            names,
            ["s13.ts", "s14.ts", "s15.ts", "s16.ts", "s17.ts", "s18.ts", "s19.ts"]
        );
    }

    #[test]
    fn short_buffer_returns_everything() {
        assert_eq!(select_segments(entries(3), 600).len(), 3);
        assert!(select_segments(vec![], 30).is_empty());
    }

    #[test]
    fn clip_without_buffer_errors() {
        let mut c = PolyClip::new(CaptureSettings::default(), PolyClipConfig::default());
        assert!(matches!(c.clip(), Err(Error::NotRunning)));
    }
}
