use std::io::Write;
use std::process::{Child, Command, Stdio};
use std::time::{Duration, Instant};

use crate::{Error, Result};

/// A running `ffmpeg` child that is stopped gracefully (so files are finalized).
pub struct Ffmpeg {
    child: Child,
}

impl Ffmpeg {
    pub fn spawn(binary: &str, args: &[String]) -> Result<Self> {
        let mut child = Command::new(binary)
            .args(["-hide_banner", "-loglevel", "error"])
            .args(args)
            .stdin(Stdio::piped())
            .stdout(Stdio::null())
            .stderr(Stdio::piped())
            .spawn()
            .map_err(|e| Error::Ffmpeg(format!("could not start `{binary}`: {e}")))?;

        // Catch immediate failures (bad device, unsupported display, ...).
        std::thread::sleep(Duration::from_millis(400));
        if let Some(status) = child.try_wait()? {
            let mut msg = String::new();
            if let Some(mut e) = child.stderr.take() {
                use std::io::Read;
                let _ = e.read_to_string(&mut msg);
            }
            return Err(Error::Ffmpeg(format!(
                "exited immediately ({status}): {}",
                msg.trim()
            )));
        }
        // Drain stderr so ffmpeg never blocks on a full pipe.
        if let Some(mut e) = child.stderr.take() {
            std::thread::spawn(move || {
                let _ = std::io::copy(&mut e, &mut std::io::sink());
            });
        }
        Ok(Self { child })
    }

    pub fn is_running(&mut self) -> bool {
        matches!(self.child.try_wait(), Ok(None))
    }

    /// Sends `q`, waits up to 8s, then kills.
    pub fn stop(mut self) {
        if let Some(stdin) = self.child.stdin.as_mut() {
            let _ = stdin.write_all(b"q\n");
            let _ = stdin.flush();
        }
        drop(self.child.stdin.take());
        let deadline = Instant::now() + Duration::from_secs(8);
        while Instant::now() < deadline {
            if matches!(self.child.try_wait(), Ok(Some(_))) {
                return;
            }
            std::thread::sleep(Duration::from_millis(50));
        }
        let _ = self.child.kill();
        let _ = self.child.wait();
    }
}

impl Drop for Ffmpeg {
    fn drop(&mut self) {
        if matches!(self.child.try_wait(), Ok(None)) {
            let _ = self.child.kill();
            let _ = self.child.wait();
        }
    }
}

/// Run ffmpeg to completion.
pub fn run(binary: &str, args: &[String]) -> Result<()> {
    let out = Command::new(binary)
        .args(["-hide_banner", "-loglevel", "error"])
        .args(args)
        .stdin(Stdio::null())
        .output()
        .map_err(|e| Error::Ffmpeg(format!("could not start `{binary}`: {e}")))?;
    if out.status.success() {
        Ok(())
    } else {
        Err(Error::Ffmpeg(
            String::from_utf8_lossy(&out.stderr).trim().to_string(),
        ))
    }
}
