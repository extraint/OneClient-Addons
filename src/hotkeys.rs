//! Global keybinds (work while Minecraft has focus).
//!
//! On Windows and macOS the thread that creates [`Hotkeys`] must run an OS event loop (the
//! launcher's UI loop does). On Linux/X11 it works from a plain thread.

use std::str::FromStr;

use global_hotkey::hotkey::HotKey;
use global_hotkey::{GlobalHotKeyEvent, GlobalHotKeyManager, HotKeyState};

use crate::{Error, Result};

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum Action {
    ToggleReplay,
    Clip,
}

pub struct Hotkeys {
    _manager: GlobalHotKeyManager,
    replay_id: u32,
    clip_id: u32,
}

impl Hotkeys {
    /// Keybind strings look like `F9`, `ctrl+shift+KeyR`, `alt+F10`.
    pub fn register(replay: &str, clip: &str) -> Result<Self> {
        let parse = |s: &str| HotKey::from_str(s).map_err(|e| Error::Hotkey(format!("{s}: {e}")));
        let (r, c) = (parse(replay)?, parse(clip)?);
        if r.id() == c.id() {
            return Err(Error::Hotkey(
                "PolyReplay and PolyClip need different keybinds".into(),
            ));
        }
        let manager = GlobalHotKeyManager::new().map_err(|e| Error::Hotkey(e.to_string()))?;
        manager
            .register(r)
            .map_err(|e| Error::Hotkey(format!("{replay}: {e}")))?;
        manager
            .register(c)
            .map_err(|e| Error::Hotkey(format!("{clip}: {e}")))?;
        Ok(Self {
            _manager: manager,
            replay_id: r.id(),
            clip_id: c.id(),
        })
    }

    /// Blocks until the next keybind press.
    pub fn next(&self) -> Option<Action> {
        let rx = GlobalHotKeyEvent::receiver();
        loop {
            let ev = rx.recv().ok()?;
            if ev.state != HotKeyState::Pressed {
                continue;
            }
            if ev.id == self.replay_id {
                return Some(Action::ToggleReplay);
            }
            if ev.id == self.clip_id {
                return Some(Action::Clip);
            }
        }
    }
}
