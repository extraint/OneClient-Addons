//! Standalone runner: `oneclient-addons [config.json]`
//!
//! Starts the PolyClip buffer, then F9 (default) toggles PolyReplay and F10 saves a clip.

use std::path::PathBuf;

use oneclient_addons::config::Config;
use oneclient_addons::hotkeys::{Action, Hotkeys};
use oneclient_addons::polyclip::PolyClip;
use oneclient_addons::polyreplay::{PolyReplay, Toggle};

fn main() {
    let path = std::env::args_os()
        .nth(1)
        .map(PathBuf::from)
        .unwrap_or_else(|| "oneclient-addons.json".into());
    let config = Config::load(&path).unwrap_or_else(|e| fail(e));
    if !path.exists() {
        let _ = config.save(&path);
        println!("Wrote default config to {}", path.display());
    }

    let keys = Hotkeys::register(&config.polyreplay.keybind, &config.polyclip.keybind)
        .unwrap_or_else(|e| fail(e));
    let mut replay = PolyReplay::new(config.capture.clone(), config.polyreplay.clone());
    let mut clip = PolyClip::new(config.capture.clone(), config.polyclip.clone());

    match clip.start() {
        Ok(()) => println!(
            "PolyClip buffering (last {}). {} to clip.",
            config.polyclip.length.label(),
            config.polyclip.keybind
        ),
        Err(e) => eprintln!("PolyClip disabled: {e}"),
    }
    println!(
        "PolyReplay: {} to start/stop recording. Ctrl+C to quit.",
        config.polyreplay.keybind
    );

    while let Some(action) = keys.next() {
        match action {
            Action::ToggleReplay => match replay.toggle() {
                Ok(Toggle::Started(p)) => println!("Recording -> {}", p.display()),
                Ok(Toggle::Stopped(p)) => println!("Saved {}", p.display()),
                Err(e) => eprintln!("PolyReplay: {e}"),
            },
            Action::Clip => match clip.clip() {
                Ok(p) => println!("Clip saved: {}", p.display()),
                Err(e) => eprintln!("PolyClip: {e}"),
            },
        }
    }
}

fn fail(e: impl std::fmt::Display) -> ! {
    eprintln!("error: {e}");
    std::process::exit(1)
}
